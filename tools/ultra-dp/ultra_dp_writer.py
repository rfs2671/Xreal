#!/usr/bin/env python3
"""Staged DP-bridge firmware writer for the XREAL Air 2 Ultra (Windows; hidapi).

STATUS: FULL STAGED BUILD. On real glasses this file can perform Stage 1, Stage 2 and restore,
each as its own command with its own typed confirmation, and only on the one pair of glasses
pinned below. The write code has been tested offline only (simulator); no write has been executed
on an Air 2 Ultra by its author. Every write is the owner's own decision at the keyboard.
Recovery after a failed or interrupted write is NOT guaranteed and has never been verified on an Ultra.

Stages (each is its own invocation; none starts the next):
  --stage0                         read-only: identify, read versions, verify image files
  --stage1 --stock F               write the OFFICIAL image onto the glasses. It has the same version
                                   (1140) the glasses report; whether it is byte-identical to what is
                                   installed CANNOT be verified, because the glasses offer no read-back
  --stage2 --stock F --patched F   write the corrected image; needs a Stage 1 receipt for the
                                   same glasses and its own approval
  --restore --stock F              write the OFFICIAL image back (rollback / recovery attempt). After an
                                   aborted write the tool itself must first watch the glasses disappear
                                   from USB and come back (it does not take your word for the replug)

What this tool can send: version reads (0x15, 0x16, 0x26) and DP update 110-113. It contains no
SOC/MCU/DSP update, boot-jump or mode message, and refuses to build one.

Honest limits: a successful Stage 1 shows that a complete write works on this unit. It does
NOT show that an interrupted write can be recovered; that has never been observed on an Ultra.
"""
from __future__ import annotations
import argparse, hashlib, json, struct, sys, time, zlib
from pathlib import Path

HARDWARE_WRITE_STAGES = frozenset({"stage1", "stage2", "restore"})
# Hardware writes are additionally pinned to the owner's unit, as read by the Windows diagnostic.
PINNED_GLASSES_ID = "G428X00969"
PINNED_SOC = "12.1.00.502_20250408"

VID, PID_APP, PID_BOOT, CONTROL_IF = 0x3318, 0x0426, 0x0425, 0
REPORT = 512
MAGIC, OFF_CRC, OFF_LEN, OFF_MSGID, OFF_PAYLOAD = 0xFD, 1, 5, 15, 22
R_GLASSES_ID, R_DP_VERSION, R_SOC_VERSION = 0x15, 0x16, 0x26
PREPARE, START, TRANSMIT, FINISH = 110, 111, 112, 113
READS = {R_GLASSES_ID, R_DP_VERSION, R_SOC_VERSION}
WRITES = {PREPARE, START, TRANSMIT, FINISH}
FORBIDDEN = set(range(56, 73)) | {82, 88} | set(range(4352, 4358))   # SOC/MCU/DSP update, boot jumps
HEADER_LEN, CHUNK = 64, 490              # XREAL: START = image[0:64]; TRANSMIT = 490-byte pieces
TIMEOUT = {PREPARE: 16.0, START: 16.0, TRANSMIT: 2.0, FINISH: 16.0}   # XREAL's own values
PROJECT, FWTYPE, NAME, SIZE = 0x1200, 2, "1140", 50632

STOCK_SHA = "E88B8E64957473281FE96DCF4B7C3390AB1DB6A0002321E7B87C5C8FF2974F95"
PATCHED_SHA = "644CD71F365D98F9D79B7E96A00C0A4B5BD3DE1E79BE2F8A9F62CD932739BDE9"
ALLOWED = {STOCK_SHA: "official Air 2 Ultra DP 1140", PATCHED_SHA: "Ultra DP 1140 + mkcalc + nohdcp"}
# SHA-256 of the full frame stream XREAL's own updater SDK emits for each image (offline simulation).
VENDOR_STREAM_SHA = {STOCK_SHA: "B8AAA9B96B47DDC04948E0B6982911351F44BBFACD4D7CD3C2C39F3371C0FDA9",
                     PATCHED_SHA: "EE868E736E41E9D9FF998595EA13F2640EB8F9DFE6F61DBFB0A53F12934B10C8"}
RECEIPT_MAX_AGE_S = 24 * 3600
STATE_DIR = Path.home() / ".ultra-dp-writer"


class Refused(Exception):
    """A precondition failed. Nothing was written."""

class WriteAborted(Exception):
    """A write was started and did not complete. The bridge image state is UNKNOWN."""


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest().upper()

# ---------------------------------------------------------------- framing (XREAL cmdBuild / parseRsp)
def frame(msgid: int, payload: bytes = b"") -> bytes:
    if msgid in FORBIDDEN or msgid not in (READS | WRITES):
        raise ValueError("message id %d is not permitted in this tool" % msgid)
    if msgid in READS and payload:
        raise ValueError("read queries carry no payload")
    if len(payload) > REPORT - OFF_PAYLOAD:
        raise ValueError("payload too large")
    b = bytearray(REPORT)
    b[0] = MAGIC
    struct.pack_into("<H", b, OFF_MSGID, msgid)
    b[OFF_PAYLOAD:OFF_PAYLOAD + len(payload)] = payload
    n = 17 + len(payload)
    struct.pack_into("<H", b, OFF_LEN, n)
    struct.pack_into("<I", b, OFF_CRC, zlib.crc32(bytes(b[OFF_LEN:OFF_LEN + n])) & 0xFFFFFFFF)
    return bytes(b)

def parse(b: bytes):
    if len(b) < 23 or b[0] != MAGIC:
        return None
    n = struct.unpack_from("<H", b, OFF_LEN)[0]
    if n < 18 or OFF_LEN + n > len(b):
        return None
    ok = zlib.crc32(bytes(b[OFF_LEN:OFF_LEN + n])) & 0xFFFFFFFF == struct.unpack_from("<I", b, OFF_CRC)[0]
    return dict(msgid=struct.unpack_from("<H", b, OFF_MSGID)[0], status=b[OFF_PAYLOAD],
                payload=bytes(b[OFF_PAYLOAD + 1:OFF_LEN + n]), crc_ok=ok)

def plan(img: bytes):
    """The exact message sequence XREAL's upgradeInDp sends."""
    steps = [("PREPARE", PREPARE, b""), ("START", START, img[:HEADER_LEN])]
    for a in range(HEADER_LEN, len(img), CHUNK):
        steps.append(("TRANSMIT", TRANSMIT, img[a:a + CHUNK]))
    steps.append(("FINISH", FINISH, b""))
    return steps

def frame_stream(img: bytes) -> bytes:
    return b"".join(frame(m, p) for _n, m, p in plan(img))

# ---------------------------------------------------------------- image checks
def _crc32c(x: bytes) -> int:
    c = 0
    for v in x:
        c ^= v << 24
        for _ in range(8):
            c = ((c << 1) ^ 0xF4ACFB13) & 0xFFFFFFFF if c & 0x80000000 else (c << 1) & 0xFFFFFFFF
    return c

def _crc8(x: bytes) -> int:
    c = 0
    for v in x:
        c ^= v
        for _ in range(8):
            c = ((c << 1) ^ 0x31) & 0xFF if c & 0x80 else (c << 1) & 0xFF
    return c

def check_image(img: bytes, want_sha: str, what: str) -> None:
    """Every check must pass or Refused is raised. Hash allowlist first."""
    if sha(img) != want_sha or want_sha not in ALLOWED:
        raise Refused("%s: SHA-256 %s is not the expected %s" % (what, sha(img), want_sha))
    if len(img) != SIZE:
        raise Refused("%s: wrong size" % what)
    crc, length, project, fwtype = struct.unpack_from("<IIII", img, 0)
    name = img[16:36].split(b"\0")[0].decode("ascii", "replace")
    if (project, fwtype, name) != (PROJECT, FWTYPE, NAME):
        raise Refused("%s: header is project 0x%04X type %d name %r, not Ultra DP" % (what, project, fwtype, name))
    if length + 8 != len(img) or _crc32c(img[8:8 + length]) != crc:
        raise Refused("%s: container CRC-32 mismatch" % what)
    pay = img[0x40:]
    if _crc8(pay + b"\xFF" * (0xFFFF - len(pay)) + bytes([img[0x38]])) != 0:
        raise Refused("%s: bank-0 tag mismatch" % what)
    if sha(frame_stream(img)) != VENDOR_STREAM_SHA[want_sha]:
        raise Refused("%s: frame stream differs from XREAL's reference stream" % what)

def load_image(path: Path, want_sha: str, what: str) -> bytes:
    if path is None or not Path(path).is_file():
        raise Refused("%s file is required and was not found" % what)
    img = Path(path).read_bytes()
    check_image(img, want_sha, what)
    return img

# ---------------------------------------------------------------- device access
class Link:
    """dev: write(bytes)->int, read(n, timeout_ms)->list[int], close()."""
    def __init__(self, dev, log):
        self.dev, self.log = dev, log

    def exchange(self, msgid: int, payload: bytes, timeout: float):
        data = frame(msgid, payload)
        n = self.dev.write(bytes([0]) + data)          # raises OSError on USB failure
        if n is None or n <= 0:
            raise OSError("HID write returned %r" % n)
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            r = self.dev.read(REPORT, 100)
            if not r:
                continue
            p = parse(bytes(r))
            if p and p["msgid"] == msgid:
                return p
        return None

    def read_text(self, msgid: int) -> str:
        p = self.exchange(msgid, b"", 3.0)
        if p is None or p["status"] != 0 or not p["crc_ok"]:
            raise Refused("read 0x%02X failed (%s)" % (msgid, "no answer" if p is None else "status %d crc %s" % (p["status"], p["crc_ok"])))
        return "".join(chr(c) for c in p["payload"] if 0x20 <= c < 0x7F)

    def close(self):
        try: self.dev.close()
        except Exception: pass


DP_UNREADABLE = "(unreadable)"

def identify(link: Link, dp_optional: bool = False) -> dict:
    """Read-only. Each value is read twice and must agree. With dp_optional (restore only) a bridge
    that does not report a version is tolerated, because that is exactly when a restore is needed."""
    out = {}
    for key, mid in (("glasses_id", R_GLASSES_ID), ("dp", R_DP_VERSION), ("soc", R_SOC_VERSION)):
        try:
            a, b = link.read_text(mid), link.read_text(mid)
            if a != b or not a:
                raise Refused("unstable or empty answer for %s (%r / %r)" % (key, a, b))
        except Refused:
            if key == "dp" and dp_optional:
                out[key] = DP_UNREADABLE; continue
            raise
        out[key] = a
    if not out["soc"].startswith("12."):
        raise Refused("SOC version %r is not an Air 2 Ultra (project 12) version" % out["soc"])
    return out


def count_xreal_hardware() -> int:
    """Number of XREAL HID interfaces currently on USB (any model, any mode). Sends nothing."""
    import hid
    return len(hid.enumerate(VID, 0))


def open_hardware():
    """Open exactly one Ultra control interface, or raise Refused. Sends nothing."""
    try:
        import hid
    except ImportError:
        raise Refused("install the HID library first: pip install hidapi")
    devs = hid.enumerate(VID, 0)
    if any(d["product_id"] == PID_BOOT for d in devs):
        raise Refused("glasses are in bootloader mode (PID 0x0425); unplug and replug")
    if any(d["product_id"] not in (PID_APP,) for d in devs):
        raise Refused("another XREAL model is connected; connect only the Air 2 Ultra")
    ctl = [d for d in devs if d["product_id"] == PID_APP and d.get("interface_number") == CONTROL_IF]
    if len(ctl) != 1:
        raise Refused("expected exactly one PID 0x0426 interface 0, found %d" % len(ctl))
    dev = hid.device(); dev.open_path(ctl[0]["path"])
    return dev

# ---------------------------------------------------------------- the write itself
def write_image(link: Link, img: bytes, log) -> None:
    """Stop-and-wait, exactly XREAL's sequence. No retries. Any deviation raises WriteAborted."""
    steps = plan(img)
    sent = 0
    for i, (name, msgid, payload) in enumerate(steps):
        try:
            p = link.exchange(msgid, payload, TIMEOUT[msgid])
        except OSError as e:
            raise WriteAborted("USB error at %s (step %d of %d, %d image bytes sent): %s" % (name, i + 1, len(steps), sent, e))
        if p is None:
            raise WriteAborted("no answer to %s (step %d of %d, %d image bytes sent)" % (name, i + 1, len(steps), sent))
        if not p["crc_ok"]:
            raise WriteAborted("corrupt answer to %s (step %d of %d)" % (name, i + 1, len(steps)))
        if p["status"] != 0:
            raise WriteAborted("%s refused with status %d (step %d of %d, %d image bytes sent)" % (name, p["status"], i + 1, len(steps), sent))
        sent += len(payload)
        if name == "TRANSMIT" and (i % 20 == 0):
            log("  sent %d / %d bytes" % (sent, len(img)))
    log("  FINISH acknowledged (status 0)")

# ---------------------------------------------------------------- stages
ABORT_NOTICE = """
*** THE WRITE DID NOT COMPLETE. The bridge firmware state is UNKNOWN. ***
  1. Do NOT run anything else now. This tool sent nothing after the failure and will not retry.
  2. Unplug the glasses, wait 10 seconds, plug them back in (this resets the update state).
  3. Run:  --stage0   and keep its output.
  4. If Stage 0 shows the glasses (id and SOC) still answer, the recovery ATTEMPT is:
         --restore --stock <official file>
     Restore makes you unplug and replug again while it watches, then writes the official image.
     Recovery is NOT guaranteed: it has never been verified on an Air 2 Ultra.
     If restore itself aborts, stop completely. Do not try a third time.
  The tool is now LOCKED: Stage 1 and Stage 2 are refused until a restore completes.
  5. If Stage 0 cannot talk to the glasses, stop and contact XREAL support.
"""

class Session:
    def __init__(self, opener, state_dir: Path, log=print, ask=input, sleep=time.sleep, hardware=True, presence=None):
        self.opener, self.dir, self.log, self.ask, self.sleep, self.hardware = opener, Path(state_dir), log, ask, sleep, hardware
        self.presence = presence
        self.lock = self.dir / "ABORTED_WRITE.lock"
        self.receipt = self.dir / "stage1_receipt.json"

    # -- helpers
    def _open(self) -> Link:
        return Link(self.opener(), self.log)

    def _confirm(self, phrase: str, text: str) -> None:
        self.log(text)
        if self.ask('Type exactly  %s  to continue, anything else cancels: ' % phrase).strip() != phrase:
            raise Refused("not confirmed; nothing was written")

    def _set_lock(self, text: str) -> None:
        """Written and flushed to disk BEFORE the first update packet. If this fails, nothing is sent."""
        self.dir.mkdir(parents=True, exist_ok=True)
        with open(self.lock, "w") as f:
            f.write(text); f.flush()
            try:
                import os; os.fsync(f.fileno())
            except OSError:
                pass
        if not self.lock.exists():
            raise Refused("could not create the safety lock file; nothing was written")

    def _witness_replug(self) -> None:
        """After an aborted write: the tool itself must see the glasses leave USB and return."""
        if self.presence is None:
            raise Refused("cannot observe USB presence; restore after an aborted write is not possible")
        POLL, GONE_POLLS, LIMIT = 0.5, 10, 240           # 5 s continuously absent; 2 minutes per phase
        self.log("\nAn earlier write was aborted. UNPLUG the glasses from the laptop now (waiting up to 2 minutes)...")
        gone = 0
        for _ in range(LIMIT):
            gone = gone + 1 if self.presence() == 0 else 0
            if gone >= GONE_POLLS: break
            self.sleep(POLL)
        else:
            raise Refused("the glasses were not seen unplugged for 5 seconds; nothing was written")
        self.log("  seen unplugged for 5 seconds. PLUG the glasses back in now (waiting up to 2 minutes)...")
        for _ in range(LIMIT):
            if self.presence() > 0: break
            self.sleep(POLL)
        else:
            raise Refused("the glasses did not come back; nothing was written")
        self.sleep(4.0)                                  # let Windows finish enumerating
        self.log("  seen reconnected.")

    def _reopen_and_verify(self, before: dict) -> dict:
        last = None
        for _ in range(20):
            self.sleep(2.0)
            try:
                link = self._open()
            except (Refused, OSError) as e:
                last = e; continue
            try:
                return identify(link)
            except (Refused, OSError) as e:
                last = e
            finally:
                link.close()
        raise WriteAborted("the glasses did not answer after the write (%s)" % last)

    def stage0(self, stock: Path | None = None, patched: Path | None = None) -> dict:
        self.log("STAGE 0 (read-only)")
        if stock: load_image(stock, STOCK_SHA, "official image"); self.log("  official image file: verified " + STOCK_SHA)
        if patched: load_image(patched, PATCHED_SHA, "corrected image"); self.log("  corrected image file: verified " + PATCHED_SHA)
        link = self._open()
        try:
            info = identify(link)
        finally:
            link.close()
        self.log("  glasses %(glasses_id)s  DP %(dp)r  SOC %(soc)r" % info)
        self.log("  safety lock: " + ("SET - an earlier write was aborted (%s)" % self.lock.read_text().strip() if self.lock.exists() else "not set"))
        self.log("  hardware writes in this build: " + (", ".join(sorted(HARDWARE_WRITE_STAGES)) + " only" if HARDWARE_WRITE_STAGES else "DISABLED"))
        self.log("  pinned unit: %s / SOC %s -> %s" % (PINNED_GLASSES_ID, PINNED_SOC, "MATCH" if (info["glasses_id"], info["soc"]) == (PINNED_GLASSES_ID, PINNED_SOC) else "NO MATCH (writes will be refused)"))
        self.log("  nothing was written")
        return info

    def _write_stage(self, stage: str, label: str, img: bytes, dp_allowed: set, phrase_prefix: str, why: str, clears_lock: bool) -> dict:
        if self.hardware and stage not in HARDWARE_WRITE_STAGES:
            raise Refused("%s is disabled on real hardware in this build; nothing was sent" % stage)
        if self.lock.exists() and not clears_lock:
            raise Refused("an earlier write was aborted (%s). Only --restore is allowed." % self.lock.read_text().strip())
        if self.lock.exists():
            self._witness_replug()                       # mandatory, observed by the tool
        link = self._open()
        try:
            before = identify(link, dp_optional=(stage == "restore"))
            if self.hardware and (before["glasses_id"], before["soc"]) != (PINNED_GLASSES_ID, PINNED_SOC):
                raise Refused("this build writes only to glasses %s with SOC %s; found %s / %s" % (PINNED_GLASSES_ID, PINNED_SOC, before["glasses_id"], before["soc"]))
            if before["dp"] not in dp_allowed:
                raise Refused("DP version %r is not one of %s for this stage" % (before["dp"], sorted(dp_allowed)))
            self._confirm("%s %s" % (phrase_prefix, before["glasses_id"]),
                          "\n%s\n  glasses %s, DP %r, SOC %r\n%s\n  Laptop on mains power, sleep off, do not touch the cable." % (label, before["glasses_id"], before["dp"], before["soc"], why))
            self._set_lock("%s started %s for %s\n" % (label, time.strftime("%Y-%m-%d %H:%M:%S"), before["glasses_id"]))
            try:
                write_image(link, img, self.log)
            except BaseException:                        # includes Ctrl+C and unexpected errors: stay locked
                self.log(ABORT_NOTICE); raise
        finally:
            link.close()
        try:
            after = self._reopen_and_verify(before)
        except WriteAborted:
            self.log(ABORT_NOTICE); raise
        problems = []
        if after["glasses_id"] != before["glasses_id"]: problems.append("a different pair of glasses answered")
        if after["soc"] != before["soc"]: problems.append("SOC version changed %r -> %r" % (before["soc"], after["soc"]))
        if after["dp"] != NAME: problems.append("DP version reads %r, not %r" % (after["dp"], NAME))
        if problems:
            self.log(ABORT_NOTICE)
            raise WriteAborted("post-write check failed: " + "; ".join(problems))
        self.lock.unlink()
        self.log("  after write: DP %(dp)r, SOC %(soc)r (unchanged)" % after)
        self.log("  The version string cannot tell which image is installed; check the picture on the laptop now.")
        return dict(before=before, after=after)

    def stage1(self, stock: Path) -> dict:
        img = load_image(stock, STOCK_SHA, "official image")
        r = self._write_stage("stage1", "STAGE 1: write the OFFICIAL image (same version 1140 as reported)", img, {NAME}, "STAGE1",
                              "  The glasses offer no read-back, so it CANNOT be verified that this file is byte-identical to what is\n"
                              "  installed now. A success shows a complete write works on this unit. It does NOT show that an\n"
                              "  interrupted write is recoverable.", False)
        self.receipt.write_text(json.dumps(dict(glasses_id=r["after"]["glasses_id"], soc=r["after"]["soc"], image=STOCK_SHA, time=time.time())))
        self.log("STAGE 1 complete. Stage 2 is NOT started. Confirm the laptop picture still works before deciding on Stage 2.")
        return r

    def stage2(self, stock: Path, patched: Path) -> dict:
        load_image(stock, STOCK_SHA, "official image (rollback)")          # rollback file must be present and valid
        img = load_image(patched, PATCHED_SHA, "corrected image")
        if self.lock.exists():
            raise Refused("an earlier write was aborted (%s). Only --restore is allowed." % self.lock.read_text().strip())
        if not self.receipt.exists():
            raise Refused("no Stage 1 receipt; Stage 1 must succeed on these glasses first")
        rc = json.loads(self.receipt.read_text())
        if time.time() - rc["time"] > RECEIPT_MAX_AGE_S or rc.get("image") != STOCK_SHA:
            raise Refused("Stage 1 receipt is too old or invalid; run Stage 1 again")
        link = self._open()
        try:
            now = identify(link)
        finally:
            link.close()
        if now["glasses_id"] != rc["glasses_id"] or now["soc"] != rc["soc"]:
            raise Refused("Stage 1 receipt is for other glasses or another SOC version")
        if self.ask("Did the laptop show a normal picture in the glasses AFTER Stage 1? Type YES: ").strip() != "YES":
            raise Refused("Stage 2 needs a working laptop picture after Stage 1; nothing was written")
        r = self._write_stage("stage2", "STAGE 2: write the CORRECTED image (HDCP will be off while it is installed)", img, {NAME}, "STAGE2",
                              "  Rollback is --restore with the official image.", False)
        self.receipt.unlink()
        self.log("STAGE 2 complete. Test on the laptop first, then on the Pixel.")
        return r

    def restore(self, stock: Path) -> dict:
        img = load_image(stock, STOCK_SHA, "official image")
        if self.receipt.exists(): self.receipt.unlink()                    # a restore always invalidates Stage 1
        return self._write_stage("restore", "RESTORE: write the OFFICIAL image", img, {NAME, "1109", DP_UNREADABLE}, "RESTORE",
                                 "  After an aborted write, this runs only once the tool has watched the glasses unplug and return.", True)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    g = ap.add_mutually_exclusive_group(required=True)
    for s in ("stage0", "stage1", "stage2", "restore"):
        g.add_argument("--" + s, action="store_true")
    ap.add_argument("--stock", type=Path); ap.add_argument("--patched", type=Path)
    a = ap.parse_args(argv)
    s = Session(open_hardware, STATE_DIR, presence=count_xreal_hardware)   # fixed location: the lock cannot be side-stepped by an option
    try:
        if a.stage0: s.stage0(a.stock, a.patched)
        elif a.stage1: s.stage1(a.stock)
        elif a.stage2: s.stage2(a.stock, a.patched)
        else: s.restore(a.stock)
    except Refused as e:
        print("REFUSED (nothing written):", e); return 2
    except WriteAborted as e:
        print("WRITE ABORTED:", e); return 3
    except OSError as e:
        print("CANNOT REACH THE GLASSES:", e)
        if s.lock.exists(): print("The tool is LOCKED after an incomplete write. See the notice above.")
        return 4
    except KeyboardInterrupt:
        print("INTERRUPTED.", "The tool is LOCKED after an incomplete write; see the notice above." if s.lock.exists() else "Nothing was written.")
        return 5
    return 0


if __name__ == "__main__":
    sys.exit(main())
