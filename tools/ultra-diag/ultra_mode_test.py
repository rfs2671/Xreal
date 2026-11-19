#!/usr/bin/env python3
"""Do the glasses hold half-width 3D when a PC is the picture source? (Windows laptop, glasses worn)

  python ultra_mode_test.py            asks, runs the test (about 40 s), puts the glasses back in 2D
  python ultra_mode_test.py --full     the same with full-width 3D (mode 3, 3840x1080), which the Pixel cannot send
  python ultra_mode_test.py --restore  only puts the glasses back in 2D
  (--yes skips the two questions; what Windows sends each display is logged throughout)

From the Pixel the glasses accept half-width 3D (display mode 8), show nothing, and go back to 2D by themselves
about two seconds after the picture starts. This is the same request from a PC, which decides where the fault is:

  the glasses stay in mode 8 and show a picture   the glasses can do it; what the Pixel sends is what they reject
  the glasses go back to mode 1 here too          this firmware does not keep mode 8, whoever sends the picture

It sends two kinds of message and no others: the read-only queries of ultra_diag.py, and "set display mode"
(0x08) with mode 1 (2D) or 8 (half-width 3D), the same command the glasses' own button and XREAL's software send.
It cannot write firmware: every firmware-update message id is refused, as in ultra_diag.py. The mode is not
stored: unplugging the glasses also returns them to 2D.

While it runs, keep the glasses on and say what you see: a picture (the Windows desktop, squeezed sideways in
each eye, is what half-width 3D looks like without 3D content), or black.
"""
from __future__ import annotations
import struct, sys, time, zlib
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import ultra_diag as ud

SET_MODE = 0x08; MODES = {1: "2D 1920x1080@60", 3: "full-width 3D 3840x1080@60", 8: "half-width 3D 1920x1080@60"}

def windows_displays():
    """What Windows is sending each active display right now, e.g. '1920x1200@60, 3840x1080@60' (empty off Windows)."""
    try:
        import ctypes
        from ctypes import wintypes
        class DD(ctypes.Structure): _fields_ = [("cb", wintypes.DWORD), ("DeviceName", wintypes.WCHAR * 32), ("DeviceString", wintypes.WCHAR * 128), ("StateFlags", wintypes.DWORD), ("DeviceID", wintypes.WCHAR * 128), ("DeviceKey", wintypes.WCHAR * 128)]
        class DM(ctypes.Structure): _fields_ = [("dmDeviceName", wintypes.WCHAR * 32), ("a", wintypes.WORD * 4), ("dmFields", wintypes.DWORD), ("b", ctypes.c_byte * 16), ("c", wintypes.WORD * 5 ), ("dmFormName", wintypes.WCHAR * 32), ("dmLogPixels", wintypes.WORD), ("dmBitsPerPel", wintypes.DWORD), ("dmPelsWidth", wintypes.DWORD), ("dmPelsHeight", wintypes.DWORD), ("dmDisplayFlags", wintypes.DWORD), ("dmDisplayFrequency", wintypes.DWORD), ("pad", ctypes.c_byte * 64)]
        out = []; i = 0; u = ctypes.windll.user32
        while True:
            d = DD(); d.cb = ctypes.sizeof(DD)
            if not u.EnumDisplayDevicesW(None, i, ctypes.byref(d), 0): break
            i += 1
            if not d.StateFlags & 1: continue
            m = DM(); m.a[2] = ctypes.sizeof(DM)
            if u.EnumDisplaySettingsW(d.DeviceName, -1, ctypes.byref(m)): out.append("%dx%d@%d" % (m.dmPelsWidth, m.dmPelsHeight, m.dmDisplayFrequency))
        return ", ".join(out)
    except Exception: return ""

def frame_set_mode(mode: int, size: int) -> bytes:
    if mode not in MODES: raise ValueError("only modes 1, 3 and 8 are ever sent by this tool")
    b = bytearray(size); b[0] = ud.MAGIC
    struct.pack_into("<H", b, ud.OFF_LEN, 18); struct.pack_into("<I", b, 7, 0x1337)
    struct.pack_into("<H", b, ud.OFF_MSGID, SET_MODE); b[ud.OFF_PAYLOAD] = mode
    struct.pack_into("<I", b, ud.OFF_CRC, zlib.crc32(bytes(b[ud.OFF_LEN:ud.OFF_LEN + 18])) & 0xFFFFFFFF)
    return bytes(b)

def open_glasses(hid):
    devs, _ = ud.enumerate_devices(hid)
    apps = [d for d in devs if d["product_id"] == ud.PID_APP and d.get("interface_number") == ud.CONTROL_INTERFACE]
    if len(apps) != 1: return None
    dev = hid.device(); dev.open_path(apps[0]["path"]); return dev

def get_mode(link, size):
    try: st, p = link.query(0x07, size, wait=0.6)
    except OSError: return None
    return p["payload"][0] if st == "ok" and p["payload"] else None

def set_mode(dev, link, size, mode):
    dev.write(bytes([0x00]) + frame_set_mode(mode, size))
    end = time.time() + 1.5
    while time.time() < end:
        r = dev.read(max(size, 64), 200)
        p = ud.parse(bytes(r)) if r else None
        if p and p["msgid"] == SET_MODE: return p["status"]
    return None

def main() -> int:
    try: import hid
    except ImportError: print("Needs hidapi:  pip install hidapi"); return 2
    restore = "--restore" in sys.argv
    want = 3 if "--full" in sys.argv else 8; yes = "--yes" in sys.argv; name = MODES[want].split(" 3D")[0] + " 3D"
    dev = open_glasses(hid)
    if dev is None: print("The glasses' control interface was not found (plug them into this laptop, alone). Nothing was sent."); return 3
    log = []
    def say(s): print(s, flush=True); log.append(s)
    try:
        link = ud.Link(dev); size = None
        for sz in (64, 512, 1024):
            try:
                st, _ = link.query(0x07, sz, wait=1.0)
            except OSError: continue
            if st == "ok": size = sz; break
        if size is None: say("The glasses did not answer a mode query. Nothing else was sent."); return 4
        say("glasses answer at report size %d; display mode now %s" % (size, get_mode(link, size)))
        if restore:
            say("set mode 1 (2D): status %s; mode now %s" % (set_mode(dev, link, size, 1), get_mode(link, size))); return 0
        print(__doc__.split("While it runs")[1].strip() if "While it runs" in __doc__ else "")
        if not yes and input("Put the glasses on. Type YES to ask them for " + name + " for 30 seconds: ").strip() != "YES": say("Not confirmed. Nothing was changed."); return 0
        say("Windows is sending: " + windows_displays())
        t0 = time.time(); st = set_mode(dev, link, size, want)
        say("%5.2f s  asked for mode %d (%s): status %s" % (time.time() - t0, want, name, st))
        seen = []; shown = ""
        while time.time() - t0 < 30:
            w = windows_displays()
            if w != shown: shown = w; say("%5.2f s  Windows is sending: %s" % (time.time() - t0, w))
            try: m = get_mode(link, size)
            except Exception as e: m = None
            if m is None:
                # the display link restarting can take the control interface away for a moment
                try: dev.close()
                except Exception: pass
                time.sleep(0.4); dev = open_glasses(hid)
                if dev is None: say("%5.2f s  control interface away" % (time.time() - t0)); time.sleep(0.6); dev = open_glasses(hid)
                if dev is None: continue
                link = ud.Link(dev); continue
            if not seen or seen[-1][1] != m: say("%5.2f s  the glasses report mode %d (%s)" % (time.time() - t0, m, MODES.get(m, "another mode")))
            seen.append((time.time() - t0, m)); time.sleep(0.25)
        held = [t for t, m in seen if m == want]; back = [t for t, m in seen if m == 1 and held and t > held[0]]
        if held and not back: say("RESULT: the glasses stayed in " + name + " for the whole %d s." % int(held[-1] - held[0]))
        elif held: say("RESULT: the glasses were in " + name + " from %.1f s and back in 2D at %.1f s, without being asked." % (held[0], back[0]))
        else: say("RESULT: the glasses never reported " + name + ".")
        what = "asked in chat" if yes else input("What did you see in the glasses during those 30 seconds (picture / black / flicker)? ").strip()
        say("owner saw: " + what)
        if dev is not None: say("set mode 1 (2D) again: status %s; mode now %s" % (set_mode(dev, link, size, 1), get_mode(link, size)))
    finally:
        try:
            if dev is not None: dev.close()
        except Exception: pass
        try: Path("ultra_mode_test_result.txt").write_text("\n".join(log) + "\n", encoding="utf-8"); print("\nSaved to ultra_mode_test_result.txt")
        except Exception: pass
    return 0

if __name__ == "__main__": sys.exit(main())
