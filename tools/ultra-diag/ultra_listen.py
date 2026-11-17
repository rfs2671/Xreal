#!/usr/bin/env python3
"""Listen to the XREAL Air 2 Ultra controller on a working host (Windows; hidapi).

Purpose: record what the glasses' own controller says while video starts on a host where the
picture works (firmware text log 0x6C09, panel power 0x6C18, display toggled 0x6C04), so it
can be compared with what the same glasses say on the Pixel.

  python ultra_listen.py --wait --seconds 40      wait for the glasses to be plugged in, then listen
  python ultra_listen.py --seconds 20 --rewrite-mode
        also read the display mode (0x07) and write the SAME value back (0x08) once, which makes
        the glasses restart their video link. Nothing else is ever written.

Cannot send a firmware-update, boot or SOC message: the only ids it can build are the read-only
queries below and 0x08 with the mode value it has just read.
"""
from __future__ import annotations
import argparse, struct, sys, time, zlib

VID, PID_APP, CONTROL_IF, REPORT, MAGIC = 0x3318, 0x0426, 0, 512, 0xFD
READS = {0x03: "brightness", 0x07: "display mode", 0x15: "glasses id", 0x16: "DP firmware", 0x26: "SOC firmware"}
SET_MODE = 0x08
NAMES = {0x6C04: "display toggled", 0x6C05: "button", 0x6C09: "firmware text log", 0x6C18: "panel power",
         0x6C02: "heartbeat start", 0x6C12: "heartbeat end", 0x1A: "heartbeat"}
MODES = {1: "2D 60", 3: "SBS 60", 4: "SBS 72", 5: "2D 72", 8: "half-SBS 60", 9: "SBS 90", 10: "2D 90", 11: "2D 120"}


def frame(msgid: int, payload: bytes = b"") -> bytes:
    if not (msgid in READS and not payload) and not (msgid == SET_MODE and len(payload) == 1 and payload[0] in MODES):
        raise ValueError("message 0x%02X is not permitted in this tool" % msgid)
    b = bytearray(REPORT); b[0] = MAGIC
    n = 17 + len(payload)
    struct.pack_into("<H", b, 5, n); struct.pack_into("<I", b, 7, 0x1337); struct.pack_into("<H", b, 15, msgid)
    b[22:22 + len(payload)] = payload
    struct.pack_into("<I", b, 1, zlib.crc32(bytes(b[5:5 + n])) & 0xFFFFFFFF)
    return bytes(b)


def parse(b: bytes):
    if len(b) < 23 or b[0] != MAGIC: return None
    n = struct.unpack_from("<H", b, 5)[0]
    if n < 17 or 5 + n > len(b): return None
    return struct.unpack_from("<H", b, 15)[0], bytes(b[22:5 + n])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--wait", action="store_true"); ap.add_argument("--seconds", type=float, default=30)
    ap.add_argument("--rewrite-mode", action="store_true"); ap.add_argument("--rewrite-at", type=float, default=6.0)
    a = ap.parse_args()
    import hid
    t_wait = time.monotonic()
    while True:
        ctl = [d for d in hid.enumerate(VID, PID_APP) if d.get("interface_number") == CONTROL_IF]
        if ctl or not a.wait: break
        if time.monotonic() - t_wait > 300: print("no glasses within 5 minutes"); return 2
        time.sleep(0.05)
    if len(ctl) != 1: print("expected one Ultra control interface, found %d" % len(ctl)); return 2
    dev = hid.device()
    for _ in range(40):                               # Windows may still be finishing enumeration
        try: dev.open_path(ctl[0]["path"]); break
        except OSError: time.sleep(0.05)
    else: print("could not open the control interface"); return 2
    t0 = time.monotonic()
    def stamp(): return "t+%6.2fs" % (time.monotonic() - t0)
    print("%s opened control interface %.2f s after it appeared" % (stamp(), t0 - t_wait if a.wait else 0.0), flush=True)
    def send(mid, payload=b""):
        dev.write(bytes([0]) + frame(mid, payload)); print("%s > sent 0x%02X %s" % (stamp(), mid, payload.hex()), flush=True)
    pending = [0x07, 0x03, 0x16]; mode = None; rewritten = False; counts = {}
    while time.monotonic() - t0 < a.seconds:
        if pending: send(pending.pop(0))
        if a.rewrite_mode and not rewritten and mode is not None and time.monotonic() - t0 >= a.rewrite_at:
            rewritten = True; send(SET_MODE, bytes([mode]))
        r = dev.read(REPORT, 200)
        if not r: continue
        p = parse(bytes(r))
        if p is None: print("%s < unparsed %s" % (stamp(), bytes(r[:24]).hex()), flush=True); continue
        mid, pay = p
        if mid == 0x07 and len(pay) >= 2 and pay[0] == 0 and pay[1] in MODES: mode = pay[1]
        if mid in (0x1A, 0x6C02, 0x6C12):             # heartbeats: count, do not print each
            counts[mid] = counts.get(mid, 0) + 1; continue
        text = "".join(chr(c) for c in pay if 0x20 <= c < 0x7F)
        print("%s < 0x%04X %-18s %s  %r" % (stamp(), mid, NAMES.get(mid, READS.get(mid, "")), pay[:40].hex(), text), flush=True)
    for mid, n in counts.items(): print("heartbeat 0x%04X: %d" % (mid, n))
    if rewritten: send(0x07); time.sleep(0.3); r = dev.read(REPORT, 500); print("final mode answer:", parse(bytes(r)) if r else None)
    dev.close(); return 0


if __name__ == "__main__":
    sys.exit(main())
