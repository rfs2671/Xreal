#!/usr/bin/env python3
"""XREAL Air 2 Ultra read-only diagnostic for Windows (also runs on Linux/macOS).

READ-ONLY BY CONSTRUCTION. It sends only version/status queries from a fixed allowlist and
refuses every other message id, including every firmware-update id (SOC 62-68, DP 110-113).
It cannot write firmware: there is no update code in this file.

  python ultra_diag.py                 enumerate, open the control interface, read versions
  python ultra_diag.py --list          enumerate only; open nothing, send nothing
  python ultra_diag.py --check-image F offline check of a DP firmware file (no USB at all)
  python ultra_diag.py --self-test     run against a built-in fake device (no hardware)

Needs: Python 3.10+ and, for hardware access, `pip install hidapi`.
"""
from __future__ import annotations
import argparse, hashlib, struct, sys, time, zlib
from pathlib import Path

VID = 0x3318
PID_APP, PID_BOOT = 0x0426, 0x0425
PID_TO_PROJECT = {0x0426: 0x1200, 0x0425: 0x1200}      # XREAL's own updater table
CONTROL_INTERFACE = 0                                  # hardware-reported for the Ultra
OFF_CRC, OFF_LEN, OFF_MSGID, OFF_PAYLOAD, MAGIC = 1, 5, 15, 22, 0xFD

# The ONLY messages this tool can ever send.
READ_ONLY = {
    0x03: "brightness", 0x07: "display mode", 0x15: "glasses id", 0x16: "DisplayPort bridge (DP) firmware",
    0x18: "DSP version", 0x21: "DSP app firmware", 0x26: "SOC / MCU app firmware",
}
UPDATE_IDS = set(range(62, 69)) | set(range(110, 114))

# Known official/derived DP images (SHA-256). The patched one is OFFLINE-built, never validated on hardware.
KNOWN_DP = {
    "E88B8E64957473281FE96DCF4B7C3390AB1DB6A0002321E7B87C5C8FF2974F95": "official Air 2 Ultra DP 1140 (stock)",
    "644CD71F365D98F9D79B7E96A00C0A4B5BD3DE1E79BE2F8A9F62CD932739BDE9": "OFFLINE-built Ultra DP 1140 + mkcalc + nohdcp (UNTESTED on hardware)",
    "350BACE369A83823D8EF867AE04AD07CF64D724C10EC7CEECFB83861AC9672F3": "official Air 2 / Air 2 Pro DP 1140 (NOT for the Ultra)",
}

def frame(msgid: int, size: int) -> bytes:
    if msgid in UPDATE_IDS:
        raise ValueError("firmware-update message ids are never sent by this tool")
    if msgid not in READ_ONLY:
        raise ValueError("message id 0x%02X is not on the read-only allowlist" % msgid)
    b = bytearray(size)
    b[0] = MAGIC
    struct.pack_into("<H", b, OFF_LEN, 17)
    struct.pack_into("<H", b, OFF_MSGID, msgid)
    struct.pack_into("<I", b, OFF_CRC, zlib.crc32(bytes(b[OFF_LEN:OFF_LEN + 17])) & 0xFFFFFFFF)
    return bytes(b)

def parse(b: bytes):
    if len(b) < 23 or b[0] != MAGIC:
        return None
    ln = struct.unpack_from("<H", b, OFF_LEN)[0]
    if ln < 17 or OFF_LEN + ln > len(b):
        return None
    crc_ok = zlib.crc32(bytes(b[OFF_LEN:OFF_LEN + ln])) & 0xFFFFFFFF == struct.unpack_from("<I", b, OFF_CRC)[0]
    return dict(msgid=struct.unpack_from("<H", b, OFF_MSGID)[0], status=b[OFF_PAYLOAD],
                payload=bytes(b[OFF_PAYLOAD + 1:OFF_LEN + ln]), crc_ok=crc_ok)

def text(p: bytes) -> str:
    return "".join(chr(c) for c in p if 0x20 <= c < 0x7F)

class Link:
    """Wraps an object with write(bytes)->int and read(n, timeout_ms)->list[int]."""
    def __init__(self, dev): self.dev = dev; self.size = None
    def query(self, msgid: int, size: int, wait: float = 2.0):
        data = frame(msgid, size)
        n = self.dev.write(bytes([0x00]) + data)           # report id 0 prefix
        if n is None or n <= 0:
            return "write-failed", None
        end = time.time() + wait
        while time.time() < end:
            r = self.dev.read(max(size, 64), 200)
            if not r: continue
            p = parse(bytes(r))
            if p and p["msgid"] == msgid:
                return "ok", p
        return "no-answer", None

def run_queries(link: Link, sizes=(512, 64, 1024)):
    lines, results = [], {}
    chosen = None
    for sz in sizes:                                         # first size the OS accepts AND the device answers
        try:
            st, p = link.query(0x16, sz)
        except OSError as e:
            lines.append("  report size %4d: write refused (%s)" % (sz, e)); continue
        lines.append("  report size %4d: %s" % (sz, st))
        if st == "ok": chosen = sz; results[0x16] = p; break
    if chosen is None:
        return lines, results, None
    for mid, name in READ_ONLY.items():
        if mid in results: continue
        try: st, p = link.query(mid, chosen)
        except OSError as e: lines.append("  0x%02X %-34s write refused (%s)" % (mid, name, e)); continue
        if st == "ok": results[mid] = p
        else: lines.append("  0x%02X %-34s %s" % (mid, name, st))
    return lines, results, chosen

def container(d: bytes):
    h = dict(crc=struct.unpack_from("<I", d, 0)[0], length=struct.unpack_from("<I", d, 4)[0],
             project=struct.unpack_from("<I", d, 8)[0], fwtype=struct.unpack_from("<I", d, 12)[0],
             name=d[16:36].split(b"\0")[0].decode("ascii", "replace"))
    def c32(x):
        c = 0
        for b in x:
            c ^= b << 24
            for _ in range(8): c = ((c << 1) ^ 0xF4ACFB13) & 0xFFFFFFFF if c & 0x80000000 else (c << 1) & 0xFFFFFFFF
        return c
    def c8(x):
        c = 0
        for b in x:
            c ^= b
            for _ in range(8): c = ((c << 1) ^ 0x31) & 0xFF if c & 0x80 else (c << 1) & 0xFF
        return c
    pay = d[0x40:]
    h["crc_ok"] = h["length"] + 8 == len(d) and c32(d[8:8 + h["length"]]) == h["crc"]
    h["bank0_ok"] = c8(pay + b"\xFF" * (0xFFFF - len(pay)) + bytes([d[0x38]])) == 0 if len(pay) <= 0xFFFF else False
    return h

def check_image(path: Path) -> int:
    d = path.read_bytes(); sha = hashlib.sha256(d).hexdigest().upper(); h = container(d)
    print("file      :", path, len(d), "bytes")
    print("sha256    :", sha)
    print("known as  :", KNOWN_DP.get(sha, "UNKNOWN image: do not use"))
    print("header    : project 0x%04X, type %d, name %r" % (h["project"], h["fwtype"], h["name"]))
    print("container CRC-32 ok:", h["crc_ok"], "   bank-0 tag ok:", h["bank0_ok"])
    ok = sha in KNOWN_DP and h["project"] == 0x1200 and h["fwtype"] == 2 and h["crc_ok"] and h["bank0_ok"] and "NOT for the Ultra" not in KNOWN_DP[sha]
    print("VERDICT   :", "structurally valid Ultra DP image (container checks only; NOT a safety clearance)" if ok else "REJECTED for the Ultra")
    return 0 if ok else 1

def enumerate_devices(hid):
    devs = [d for d in hid.enumerate(VID, 0)]
    out = ["XREAL (VID 0x%04X) HID interfaces found: %d" % (VID, len(devs))]
    for d in sorted(devs, key=lambda x: (x["product_id"], x.get("interface_number", -1))):
        out.append("  PID 0x%04X  interface %s  usage 0x%04X/0x%04X  %r" % (
            d["product_id"], d.get("interface_number"), d.get("usage_page", 0), d.get("usage", 0), d.get("product_string")))
    return devs, out

def report(hid) -> int:
    devs, lines = enumerate_devices(hid)
    print("\n".join(lines)); lines.append("")
    if not devs:
        print("\nNo XREAL device. Use a direct USB-C (data + DP) connection."); return 2
    boots = [d for d in devs if d["product_id"] == PID_BOOT]
    apps = [d for d in devs if d["product_id"] == PID_APP and d.get("interface_number") == CONTROL_INTERFACE]
    if boots and not apps:
        print("\nThe glasses are in BOOTLOADER mode (PID 0x0425). Nothing was sent. Unplug and replug."); return 3
    if not apps:
        print("\nPID 0x0426 interface 0 not found. Nothing was sent (not an Air 2 Ultra, or wrong port)."); return 3
    if len(apps) != 1:
        print("\nMore than one Ultra control interface; connect exactly one pair. Nothing was sent."); return 3
    dev = hid.device(); dev.open_path(apps[0]["path"])
    try:
        out, res, size = run_queries(Link(dev))
    finally:
        dev.close()
    print("\nPID 0x0426 interface 0 opened (read-only queries only).")
    print("\n".join(out))
    if size is None:
        print("\nNo valid answer at any report size. Nothing was written."); return 4
    print("\nAnswered at report size %d bytes (XREAL's Ultra updater uses 512)." % size)
    for mid, p in res.items():
        print("  0x%02X %-34s status=%d crc=%s  %r  hex=%s" % (mid, READ_ONLY[mid], p["status"], "ok" if p["crc_ok"] else "BAD", text(p["payload"]), p["payload"][:24].hex()))
    dp = text(res.get(0x16, {"payload": b""})["payload"])
    print("\nSUMMARY")
    print("  control PID 0x0426 / interface 0 : confirmed")
    print("  project code                     : 0x1200 INFERRED from the PID (no read message known)")
    print("  DP bridge firmware               : %r  (does NOT distinguish patched from stock)" % dp)
    print("  SOC firmware                     : %r" % text(res.get(0x26, {"payload": b""})["payload"]))
    print("  all responses CRC-valid          : %s" % all(p["crc_ok"] for p in res.values()))
    print("  frames sent                      : read-only queries only; nothing written")
    return 0

class FakeDev:
    """Stand-in HID device for --self-test: answers allowlisted queries, rejects wrong report sizes like Windows."""
    def __init__(self, good=512): self.good, self.q, self.sent = good, [], []
    def write(self, buf):
        if len(buf) - 1 != self.good: raise OSError("wrong report length")
        self.sent.append(buf); f = buf[1:]; mid = struct.unpack_from("<H", f, OFF_MSGID)[0]
        pay = {0x16: b"1140", 0x26: b"12.1.00.502_20250408"}.get(mid, b"")
        r = bytearray(self.good); r[0] = MAGIC; ln = 18 + len(pay)
        struct.pack_into("<H", r, OFF_LEN, ln); struct.pack_into("<H", r, OFF_MSGID, mid); r[OFF_PAYLOAD] = 0; r[23:23 + len(pay)] = pay
        struct.pack_into("<I", r, OFF_CRC, zlib.crc32(bytes(r[OFF_LEN:OFF_LEN + ln])) & 0xFFFFFFFF); self.q.append(bytes(r)); return len(buf)
    def read(self, n, t): return list(self.q.pop(0)) if self.q else []

def self_test() -> int:
    for bad in sorted(UPDATE_IDS) + [0x08, 0x04, 0x01, 0x6C04]:
        try: frame(bad, 512); print("FAIL: allowed", bad); return 1
        except ValueError: pass
    for good in READ_ONLY: assert len(frame(good, 512)) == 512 and parse(frame(good, 512)) is not None
    dev = FakeDev(512); lines, res, size = run_queries(Link(dev))
    assert size == 512 and text(res[0x16]["payload"]) == "1140" and all(p["crc_ok"] for p in res.values())
    assert all(struct.unpack_from("<H", s[1:], OFF_MSGID)[0] in READ_ONLY for s in dev.sent)
    assert FakeDev(64) and run_queries(Link(FakeDev(64)))[2] == 64
    src = Path(__file__).read_text()
    assert src.count(".write(") == 2, "unexpected extra write call sites"      # Link.query + FakeDev only
    print("self-test OK: update ids refused, only allowlisted frames sent, 512- and 64-byte devices handled")
    return 0

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--list", action="store_true"); ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-image", type=Path); ap.add_argument("--save", type=Path, help="also write the output to this file")
    a = ap.parse_args()
    if a.self_test: sys.exit(self_test())
    if a.check_image: sys.exit(check_image(a.check_image))
    try: import hid
    except ImportError: sys.exit("Install the HID library first:  pip install hidapi")
    if a.list:
        print("\n".join(enumerate_devices(hid)[1])); return
    rc = report(hid); sys.exit(rc)

if __name__ == "__main__":
    main()
