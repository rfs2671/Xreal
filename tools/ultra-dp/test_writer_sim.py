#!/usr/bin/env python3
"""Offline simulator tests for ultra_dp_writer.py. No USB, no hardware.

Needs the two image files (not stored in this repository):
  set ULTRA_STOCK=<official Ultra DP 1140>   set ULTRA_PATCHED=<output of build_ultra_dp.py>
Optional: ULTRA_VENDOR_FRAMES_STOCK / ULTRA_VENDOR_FRAMES_PATCHED = frame dumps produced by
tools/fw-analysis/vendor_sim.cjs (XREAL's own SDK), for a direct byte comparison.

The fake glasses below follow the behaviour DOCUMENTED FOR THE AIR FAMILY (CRC check at FINISH,
update state cleared only by a replug). Whether a real Ultra behaves this way is NOT known;
these tests verify the writer's behaviour, not the glasses'.
"""
import os, struct, sys, tempfile, unittest, zlib
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import ultra_dp_writer as W

STOCK = Path(os.environ.get("ULTRA_STOCK", "missing")); PATCHED = Path(os.environ.get("ULTRA_PATCHED", "missing"))
HAVE = STOCK.is_file() and PATCHED.is_file()


class FakeUltra:
    """In-memory glasses. `fault(step_index, msgid)` may return ('status', n) / 'drop' / 'usb' / 'badcrc'."""
    def __init__(self, installed: bytes, fault=None):
        self.installed = installed; self.fault = fault or (lambda i, m: None)
        self.glasses_id, self.soc, self.dp, self.present = "G428X00969", "12.1.00.502_20250408", "1140", True
        self.q, self.sent, self.buf, self.header, self.session_dirty, self.wstep = [], [], b"", b"", False, 0
        self.after_finish = None            # hook: called when a write commits
    def replug(self): self.buf = self.header = b""; self.session_dirty = False; self.wstep = 0; self.q = []
    def _reply(self, mid, status, pay=b"", bad=False):
        r = bytearray(W.REPORT); r[0] = W.MAGIC; n = 18 + len(pay)
        struct.pack_into("<H", r, W.OFF_LEN, n); struct.pack_into("<H", r, W.OFF_MSGID, mid); r[W.OFF_PAYLOAD] = status; r[23:23 + len(pay)] = pay
        struct.pack_into("<I", r, W.OFF_CRC, (zlib.crc32(bytes(r[W.OFF_LEN:W.OFF_LEN + n])) ^ (1 if bad else 0)) & 0xFFFFFFFF); self.q.append(bytes(r))
    def write(self, data):
        if not self.present: raise OSError("device not connected")
        assert len(data) == W.REPORT + 1 and data[0] == 0
        f = data[1:]; self.sent.append(f); p = W.parse(f); assert p is None or True
        mid = struct.unpack_from("<H", f, W.OFF_MSGID)[0]; n = struct.unpack_from("<H", f, W.OFF_LEN)[0]; pay = f[W.OFF_PAYLOAD:W.OFF_PAYLOAD + n - 17]
        if mid in W.READS:
            self._reply(mid, 0, {W.R_GLASSES_ID: self.glasses_id, W.R_DP_VERSION: self.dp, W.R_SOC_VERSION: self.soc}[mid].encode()); return len(data)
        i = self.wstep; self.wstep += 1; act = self.fault(i, mid)
        if act == "usb": self.present = False; raise OSError("device removed")
        if act == "drop": return len(data)
        if act == "badcrc": self._reply(mid, 0, bad=True); return len(data)
        if isinstance(act, tuple): self._reply(mid, act[1]); return len(data)
        if mid == W.PREPARE: self.session_dirty = self.session_dirty or bool(self.buf or self.header)   # offsets NOT reset
        elif mid == W.START: self.header += pay
        elif mid == W.TRANSMIT: self.buf += pay
        elif mid == W.FINISH:
            img = self.header + self.buf
            try: W.check_image(img, W.sha(img), "x"); good = not self.session_dirty
            except Exception: good = False
            if not good: self._reply(mid, 5); return len(data)
            self.installed = img; self.buf = self.header = b""; self.wstep = 0
            if self.after_finish: self.after_finish(self)
        self._reply(mid, 0); return len(data)
    def read(self, n, t): return list(self.q.pop(0)) if self.q else []
    def close(self): pass


def session(dev, answers, tmp, presence="replug"):
    """presence: 'replug' = the user really unplugs and replugs when asked; 'stay' = never unplugs;
    'gone' = unplugs and never returns; 'blip' = unplugged for under 5 seconds."""
    log = []; it = iter(answers); st = {"n": 0}
    def opener():
        if not dev.present: raise OSError("not connected")
        return dev
    def pres():
        st["n"] += 1; n = st["n"]
        if presence == "stay": return 4
        if presence == "gone": return 0
        if presence == "blip": return 0 if n % 6 else 4
        if n <= 3: return 4                               # still plugged for a moment
        if n <= 20: return 0                              # unplugged
        if n == 21: dev.replug(); dev.present = True      # power-cycled: update state cleared
        return 4
    s = W.Session(opener, Path(tmp), log=log.append, ask=lambda prompt: next(it, ""), sleep=lambda x: None, hardware=False, presence=pres)
    return s, log

def write_frames(dev): return [f for f in dev.sent if struct.unpack_from("<H", f, W.OFF_MSGID)[0] in W.WRITES]


class Framing(unittest.TestCase):
    def test_forbidden_and_unknown_ids_refused(self):
        for mid in sorted(W.FORBIDDEN) + [0x08, 0x04, 0x01, 61, 60, 0x6C04]:
            with self.assertRaises(ValueError): W.frame(mid)
        self.assertFalse(W.FORBIDDEN & (W.READS | W.WRITES))
    def test_source_has_no_soc_update_ids(self):
        src = (Path(__file__).parent / "ultra_dp_writer.py").read_text()
        self.assertEqual(src.count("dev.write("), 1)                      # single hardware write call site
        self.assertEqual(W.HARDWARE_WRITE_STAGES, frozenset({"stage1"}))             # Stage 1-only build

@unittest.skipUnless(HAVE, "image files not supplied")
class WithImages(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.stock, cls.patched = STOCK.read_bytes(), PATCHED.read_bytes()
    def setUp(self): self.tmp = tempfile.mkdtemp()

    # ---- requirement 1: byte-for-byte with XREAL's updater
    def test_streams_match_vendor_reference(self):
        for img, key, env in ((self.stock, W.STOCK_SHA, "ULTRA_VENDOR_FRAMES_STOCK"), (self.patched, W.PATCHED_SHA, "ULTRA_VENDOR_FRAMES_PATCHED")):
            st = W.frame_stream(img)
            self.assertEqual(len(st), 107 * 512); self.assertEqual(W.sha(st), W.VENDOR_STREAM_SHA[key])
            if os.environ.get(env): self.assertEqual(st, Path(os.environ[env]).read_bytes())
    def test_plan_shape(self):
        names = [n for n, _m, _p in W.plan(self.stock)]
        self.assertEqual((names[0], names[1], names.count("TRANSMIT"), names[-1]), ("PREPARE", "START", 104, "FINISH"))
        self.assertEqual(b"".join(p for _n, _m, p in W.plan(self.stock)), self.stock)

    # ---- requirement 2/3: image checks
    def test_wrong_firmware_refused(self):
        bad = bytearray(self.stock); bad[0x5000] ^= 1
        air2 = bytearray(self.stock); struct.pack_into("<I", air2, 8, 0x0900)
        for img, want in ((bytes(bad), W.STOCK_SHA), (bytes(air2), W.STOCK_SHA), (self.stock[:-1], W.STOCK_SHA),
                          (self.patched, W.STOCK_SHA), (self.stock, W.PATCHED_SHA), (b"", W.STOCK_SHA), (self.stock, "0" * 64)):
            with self.assertRaises(W.Refused): W.check_image(img, want, "t")
        W.check_image(self.stock, W.STOCK_SHA, "t"); W.check_image(self.patched, W.PATCHED_SHA, "t")
    def test_stage_with_wrong_files_sends_nothing(self):
        dev = FakeUltra(self.stock); s, _ = session(dev, ["STAGE1 G428X00969"], self.tmp)
        with self.assertRaises(W.Refused): s.stage1(PATCHED)
        with self.assertRaises(W.Refused): s.stage2(PATCHED, STOCK)
        with self.assertRaises(W.Refused): s.restore(PATCHED)
        self.assertEqual(dev.sent, [])

    # ---- happy paths and stage separation
    def test_stage0_sends_only_reads(self):
        dev = FakeUltra(self.stock); s, _ = session(dev, [], self.tmp); s.stage0(STOCK, PATCHED)
        self.assertEqual(write_frames(dev), [])
    def test_stage1_success_writes_stock_and_never_starts_stage2(self):
        dev = FakeUltra(self.stock); s, log = session(dev, ["STAGE1 G428X00969"], self.tmp); s.stage1(STOCK)
        self.assertEqual(b"".join(write_frames(dev)), W.frame_stream(self.stock)); self.assertEqual(dev.installed, self.stock)
        self.assertTrue(any("Stage 2 is NOT started" in l for l in log)); self.assertTrue(s.receipt.exists()); self.assertFalse(s.lock.exists())
    def test_stage2_needs_receipt_and_both_confirmations(self):
        dev = FakeUltra(self.stock); s, _ = session(dev, ["YES", "STAGE2 G428X00969"], self.tmp)
        with self.assertRaises(W.Refused): s.stage2(STOCK, PATCHED)                      # no Stage 1
        s, _ = session(dev, ["STAGE1 G428X00969"], self.tmp); s.stage1(STOCK); n = len(write_frames(dev))
        for answers in (["no"], ["YES", "STAGE1 G428X00969"], ["YES", ""]):               # wrong answers
            s, _ = session(dev, answers, self.tmp)
            with self.assertRaises(W.Refused): s.stage2(STOCK, PATCHED)
        self.assertEqual(len(write_frames(dev)), n)
        s, _ = session(dev, ["YES", "STAGE2 G428X00969"], self.tmp); s.stage2(STOCK, PATCHED)
        self.assertEqual(dev.installed, self.patched); self.assertEqual(b"".join(write_frames(dev)[n:]), W.frame_stream(self.patched))
        s, _ = session(dev, ["YES", "STAGE2 G428X00969"], self.tmp)
        with self.assertRaises(W.Refused): s.stage2(STOCK, PATCHED)                      # receipt is single-use
    def test_stage2_refuses_receipt_for_other_glasses_or_soc_or_old(self):
        import json, time
        for change in ("id", "soc", "old"):
            tmp = tempfile.mkdtemp(); dev = FakeUltra(self.stock); s, _ = session(dev, ["STAGE1 G428X00969"], tmp); s.stage1(STOCK); n = len(dev.sent)
            if change == "id": dev.glasses_id = "OTHER00001"
            if change == "soc": dev.soc = "12.1.00.498_20241115"
            if change == "old": rc = json.loads(s.receipt.read_text()); rc["time"] -= 25 * 3600; s.receipt.write_text(json.dumps(rc))
            s, _ = session(dev, ["YES", "STAGE2 " + dev.glasses_id], tmp)
            with self.assertRaises(W.Refused): s.stage2(STOCK, PATCHED)
            self.assertEqual(write_frames(dev)[107:], [])
    def test_rollback_restores_official_image(self):
        dev = FakeUltra(self.patched); s, _ = session(dev, ["RESTORE G428X00969"], self.tmp); s.restore(STOCK)
        self.assertEqual(dev.installed, self.stock)
    def test_unconfirmed_or_wrong_state_sends_no_write(self):
        dev = FakeUltra(self.stock); s, _ = session(dev, ["stage1 g428x00969"], self.tmp)
        with self.assertRaises(W.Refused): s.stage1(STOCK)
        dev.dp = "1109"; s, _ = session(dev, ["STAGE1 G428X00969"], self.tmp)
        with self.assertRaises(W.Refused): s.stage1(STOCK)                               # fallback image: only restore allowed
        dev.dp = "1140"; dev.soc = "09.1.00.180_20240507"; s, _ = session(dev, ["STAGE1 G428X00969"], self.tmp)
        with self.assertRaises(W.Refused): s.stage1(STOCK)                               # not an Ultra SOC
        self.assertEqual(write_frames(dev), [])

    # ---- requirement 4: failures
    def _abort(self, fault, expect_frames):
        dev = FakeUltra(self.stock, fault); s, log = session(dev, ["STAGE1 G428X00969"], self.tmp)
        with self.assertRaises(W.WriteAborted): s.stage1(STOCK)
        self.assertEqual(len(write_frames(dev)), expect_frames)                          # nothing sent after the failure
        self.assertTrue(s.lock.exists()); self.assertFalse(s.receipt.exists())
        self.assertTrue(any("state is UNKNOWN" in l for l in log))
        return dev, s
    def test_invalid_ack_at_each_phase(self):
        for step in (0, 1, 2, 50, 105, 106):
            self.setUp(); self._abort(lambda i, m, step=step: ("status", 5) if i == step else None, step + 1)
        self.setUp(); self._abort(lambda i, m: ("status", 250) if i == 10 else None, 11)   # 250 is NOT accepted on the Ultra
    def test_timeout_mid_transfer(self):
        W.TIMEOUT.update({k: 0.05 for k in W.TIMEOUT})
        try: self._abort(lambda i, m: "drop" if i == 40 else None, 41)
        finally: W.TIMEOUT.update({W.PREPARE: 16.0, W.START: 16.0, W.TRANSMIT: 2.0, W.FINISH: 16.0})
    def test_usb_disconnect_mid_transfer(self):
        self._abort(lambda i, m: "usb" if i == 30 else None, 31)
    def test_corrupt_response(self):
        self._abort(lambda i, m: "badcrc" if i == 7 else None, 8)
    def test_device_gone_or_changed_after_finish(self):
        for hook in (lambda d: setattr(d, "present", False), lambda d: setattr(d, "soc", "12.1.00.498_20241115"),
                     lambda d: setattr(d, "dp", "1109"), lambda d: setattr(d, "glasses_id", "OTHER00001")):
            self.setUp(); dev = FakeUltra(self.stock); dev.after_finish = hook; s, _ = session(dev, ["STAGE1 G428X00969"], self.tmp)
            with self.assertRaises(W.WriteAborted): s.stage1(STOCK)
            self.assertTrue(s.lock.exists()); self.assertFalse(s.receipt.exists())
    def test_after_abort_only_restore_is_allowed_and_no_auto_retry(self):
        dev, s = self._abort(lambda i, m: ("status", 5) if i == 50 else None, 51); n = len(dev.sent)
        dev.fault = lambda i, m: None
        for call, answers in ((lambda s: s.stage1(STOCK), ["STAGE1 G428X00969"]), (lambda s: s.stage2(STOCK, PATCHED), ["YES", "STAGE2 G428X00969"])):
            s2, _ = session(dev, answers, self.tmp)
            with self.assertRaises(W.Refused): call(s2)
        self.assertEqual(len(write_frames(dev)), 51)
        # restore WITHOUT an observed replug is refused before any packet, whatever the user types
        for mode in ("stay", "blip", "gone"):
            s3, _ = session(dev, ["RESTORE G428X00969"], self.tmp, presence=mode)
            with self.assertRaises(W.Refused): s3.restore(STOCK)
            self.assertTrue(s3.lock.exists()); self.assertEqual(len(write_frames(dev)), 51)
        s3 = W.Session(lambda: dev, Path(self.tmp), log=lambda x: None, ask=lambda p: "RESTORE G428X00969", sleep=lambda x: None, hardware=False, presence=None)
        with self.assertRaises(W.Refused): s3.restore(STOCK)
        self.assertEqual(len(write_frames(dev)), 51)
        # restore AFTER the tool watched a real unplug/replug, on a device model whose control channel survived
        s4, log = session(dev, ["RESTORE G428X00969"], self.tmp); s4.restore(STOCK)
        self.assertTrue(any("seen unplugged" in l for l in log) and any("seen reconnected" in l for l in log))
        self.assertEqual(dev.installed, self.stock); self.assertFalse(s4.lock.exists()); self.assertFalse(s4.receipt.exists())
    def test_failed_restore_stays_locked(self):
        dev, s = self._abort(lambda i, m: ("status", 5) if i == 50 else None, 51)
        dev.fault = lambda i, m: ("status", 5) if i == 3 else None
        s2, _ = session(dev, ["RESTORE G428X00969"], self.tmp)
        with self.assertRaises(W.WriteAborted): s2.restore(STOCK)
        self.assertTrue(s2.lock.exists())
        s3, _ = session(dev, ["STAGE1 G428X00969"], self.tmp)
        with self.assertRaises(W.Refused): s3.stage1(STOCK)
    def test_ctrl_c_or_unexpected_error_mid_transfer_leaves_lock(self):
        for exc in (KeyboardInterrupt, RuntimeError, MemoryError):
            self.setUp()
            def fault(i, m, exc=exc):
                if i == 20: raise exc("boom")
            dev = FakeUltra(self.stock, fault); s, log = session(dev, ["STAGE1 G428X00969"], self.tmp)
            with self.assertRaises(exc): s.stage1(STOCK)
            self.assertTrue(s.lock.exists()); self.assertFalse(s.receipt.exists()); self.assertEqual(len(write_frames(dev)), 21)
            s2, _ = session(dev, ["STAGE1 G428X00969"], self.tmp)
            with self.assertRaises(W.Refused): s2.stage1(STOCK)
            self.assertEqual(len(write_frames(dev)), 21)
    def test_lock_is_on_disk_before_first_update_packet_and_blocks_if_unwritable(self):
        seen = []
        def fault(i, m):
            if i == 0: seen.append((Path(self.tmp) / "ABORTED_WRITE.lock").exists())
        dev = FakeUltra(self.stock, fault); s, _ = session(dev, ["STAGE1 G428X00969"], self.tmp); s.stage1(STOCK)
        self.assertEqual(seen, [True])
        blocker = Path(tempfile.mkdtemp()) / "file"; blocker.write_text("x")             # state dir cannot be created
        dev = FakeUltra(self.stock); s, _ = session(dev, ["STAGE1 G428X00969"], blocker / "sub")
        with self.assertRaises((OSError, W.Refused)): s.stage1(STOCK)
        self.assertEqual(write_frames(dev), [])
    def test_plain_rollback_without_lock_needs_no_replug(self):
        dev = FakeUltra(self.patched); s, log = session(dev, ["RESTORE G428X00969"], self.tmp, presence="stay"); s.restore(STOCK)
        self.assertEqual(dev.installed, self.stock)
    def test_cli_has_no_state_dir_option(self):
        with self.assertRaises(SystemExit): W.main(["--stage0", "--state-dir", "x"])
    def test_recovery_impossible_when_device_never_returns(self):
        dev, s = self._abort(lambda i, m: "usb" if i == 30 else None, 31)
        s2, _ = session(dev, ["RESTORE G428X00969"], self.tmp, presence="gone")
        with self.assertRaises(W.Refused): s2.restore(STOCK)                             # tool cannot fix a device that is gone
        self.assertEqual(len(write_frames(dev)), 31); self.assertTrue(s2.lock.exists())
    def _hw(self, dev, answers):
        it = iter(answers); log = []
        return W.Session(lambda: dev, Path(self.tmp), log=log.append, ask=lambda p: next(it, ""), sleep=lambda x: None, hardware=True, presence=lambda: 4), log
    def test_hardware_path_stage2_and_restore_disabled_in_this_build(self):
        dev = FakeUltra(self.stock)
        s, _ = self._hw(dev, ["STAGE1 G428X00969"]); s.stage1(STOCK); n = len(write_frames(dev))      # receipt now exists
        self.assertEqual(n, 107)
        s, _ = self._hw(dev, ["YES", "STAGE2 G428X00969"])
        with self.assertRaises(W.Refused): s.stage2(STOCK, PATCHED)
        s, _ = self._hw(dev, ["RESTORE G428X00969"])
        with self.assertRaises(W.Refused): s.restore(STOCK)
        self.assertEqual(len(write_frames(dev)), n); self.assertEqual(dev.installed, self.stock)
    def test_hardware_path_stage1_only_for_pinned_unit(self):
        for attr, val in (("glasses_id", "G428X00970"), ("soc", "12.1.00.498_20241115")):
            dev = FakeUltra(self.stock); setattr(dev, attr, val); s, _ = self._hw(dev, ["STAGE1 " + dev.glasses_id])
            with self.assertRaises(W.Refused): s.stage1(STOCK)
            self.assertEqual(write_frames(dev), [])
    def test_hardware_path_stage1_abort_locks_and_restore_stays_disabled(self):
        dev = FakeUltra(self.stock, lambda i, m: ("status", 5) if i == 60 else None); s, _ = self._hw(dev, ["STAGE1 G428X00969"])
        with self.assertRaises(W.WriteAborted): s.stage1(STOCK)
        self.assertTrue(s.lock.exists()); dev.fault = lambda i, m: None
        for call, ans in ((lambda s: s.stage1(STOCK), ["STAGE1 G428X00969"]), (lambda s: s.restore(STOCK), ["RESTORE G428X00969"])):
            s2, _ = self._hw(dev, ans)
            with self.assertRaises(W.Refused): call(s2)
        self.assertEqual(len(write_frames(dev)), 61)
    def test_stage1_never_sends_the_corrected_image(self):
        dev = FakeUltra(self.stock); s, _ = self._hw(dev, ["STAGE1 G428X00969"])
        with self.assertRaises(W.Refused): s.stage1(PATCHED)
        self.assertEqual(dev.sent, [])
        s, _ = self._hw(dev, ["STAGE1 G428X00969"]); s.stage1(STOCK)
        self.assertEqual(W.sha(b"".join(write_frames(dev))), W.VENDOR_STREAM_SHA[W.STOCK_SHA])

if __name__ == "__main__":
    unittest.main(verbosity=2)
