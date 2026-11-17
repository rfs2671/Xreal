#!/usr/bin/env python3
"""WebXR through a real browser, on an emulator, against Ultra XR's OpenXR runtime.

  python webxr_test.py [-s SERIAL] [--out DIR] [--browser PACKAGE/ACTIVITY] [--mode immersive-ar|immersive-vr]

Needs: an x86-64 emulator, Ultra XR's emulator build (gradlew assembleDebug -Pemu), and the Wolvic
browser's x86-64 build installed (com.igalia.wolvic). An extra 1920x1080 display stands in for
the glasses in half-width 3D, and the app's simulated wearer (a head that turns and sways, two
hands that pinch in turn) stands in for tracking.

The page site/webxr.html asks for an immersive session and reports what it is given; this script
serves it, collects the reports and checks them against what the simulated wearer fed.

This proves the chain browser -> WebXR -> OpenXR -> runtime -> display with made-up tracking.
It proves nothing about the Pixel, the glasses, or real tracking.
"""
import http.server, json, os, sys, threading, time
import numpy as np, cv2
import uia

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "dev.ultraxr"
results = []; reports = []
def check(name, ok, detail=""):
    results.append((name, bool(ok), detail)); print(("PASS  " if ok else "FAIL  ") + name + (("  [" + str(detail) + "]") if detail != "" else ""), flush=True)

def serve(port=8765):
    site = os.path.join(HERE, "site")
    class H(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **k): super().__init__(*a, directory=site, **k)
        def log_message(self, *a): pass
        def do_POST(self):
            n = int(self.headers.get("Content-Length", "0")); body = self.rfile.read(n)
            try: reports.append(json.loads(body))
            except Exception: pass
            # For watching by hand: WEBXR_REPORT=<file> keeps the newest report there.
            if os.environ.get("WEBXR_REPORT"):
                try: open(os.environ["WEBXR_REPORT"], "wb").write(body)
                except Exception: pass
            self.send_response(204); self.end_headers()
    s = http.server.ThreadingHTTPServer(("0.0.0.0", port), H)
    threading.Thread(target=s.serve_forever, daemon=True).start()

DEVICE_DIR = "/sdcard/Android/data/dev.ultraxr/files"
def last():
    """The newest report the page posted to the app's own test server on the device."""
    for _ in range(3):      # adb can be slow while a session starts; an old report must not stand in for a new one
        try:
            t = uia.adb("shell", "cat " + DEVICE_DIR + "/report.json", timeout=30)
            if t and t.strip().startswith("{"): r = json.loads(t); reports.append(r); return r
            if t is not None and "No such file" in t: return None
        except Exception: pass
        time.sleep(1)
    return None
def wait(pred, timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        r = last()
        if r is not None and pred(r): return True
        time.sleep(1)
    return False

def grab(out, name):
    d = os.path.join(out, name); os.makedirs(d, exist_ok=True)
    for f in os.listdir(d): os.remove(os.path.join(d, f))
    uia.adb("emu", "screenrecord", "screenshot", "--display", "1", d.replace(os.sep, "/")); time.sleep(2)
    fs = sorted(os.listdir(d))
    return cv2.imread(os.path.join(d, fs[-1])) if fs else None

def main():
    a = sys.argv[1:]; out = os.path.join(HERE, "out")
    browser = "com.igalia.wolvic/com.igalia.wolvic.VRBrowserActivity"; mode = None
    if "-s" in a: uia.SERIAL = a[a.index("-s") + 1]
    if "--out" in a: out = a[a.index("--out") + 1]
    if "--browser" in a: browser = a[a.index("--browser") + 1]
    if "--mode" in a: mode = a[a.index("--mode") + 1]
    os.makedirs(out, exist_ok=True)
    bpkg = browser.split("/")[0]
    # Stop only the browser, and give the runtime time to shut its compositor down. Force-stopping Ultra XR
    # while that is happening hangs the emulator (its GPU pipe never returns).
    uia.sh("am force-stop " + bpkg); time.sleep(8)
    for perm in ("CAMERA", "POST_NOTIFICATIONS"): uia.sh("pm grant %s android.permission.%s" % (PKG, perm))
    uia.adb("emu", "multidisplay", "del", "1"); time.sleep(2)
    uia.adb("emu", "multidisplay", "add", "1", "1920", "1080", "240", "0"); time.sleep(4)
    # The pages are served by the app itself on the device's localhost: WebXR needs a secure context, which
    # localhost is. (Forwarding a port from this machine with "adb reverse" froze the emulator's adb.)
    uia.sh("am start -n %s/.ui.LauncherActivity --ez force_half_stereo true --ez sim_wearer true --ez test_site true" % PKG); time.sleep(6)
    uia.sh("rm -f " + DEVICE_DIR + "/report.json")
    uia.adb("push", os.path.join(HERE, "site"), DEVICE_DIR + "/", timeout=120)
    uia.sh("setprop debug.ultraxr.sim_motion 0")      # hold still while clicking into the session
    for k, v in (("sim_auto", "0"), ("sim_look", "0,0"), ("sim_aim", "0,0")): uia.sh("setprop debug.ultraxr.%s %s" % (k, v))
    uia.sh("setprop debug.xrt.XRT_COMPOSITOR_LOG error")   # the emulator misses frames; do not flood the log with it
    uia.sh("logcat -c")
    url = "http://localhost:8765/webxr.html" + ("?mode=" + mode if mode else "")
    uia.sh("am start -n %s -a android.intent.action.VIEW -d '%s'" % (browser, url))

    check("the page loads in the browser and WebXR is present", wait(lambda r: r.get("hasXR") is True, 120), (last() or {}).get("ua", "no report")[:80])
    wait(lambda r: r.get("vr") is not None and r.get("ar") is not None, 20)
    r = last() or {}
    check("immersive-vr is offered", r.get("vr") is True, r.get("vr"))
    check("immersive-ar is offered (additive see-through)", r.get("ar") is True, r.get("ar"))
    # The page's whole surface is one button; the simulated hands point at it and pinch.
    entered = False
    for n in range(1, 30):      # one brief pinch of the right hand at a time, until the page has been clicked
        uia.sh("setprop debug.ultraxr.sim_tap %d" % n)
        if wait(lambda r: r.get("mode") is not None or r.get("error"), 4): entered = True; break
    uia.sh("setprop debug.ultraxr.sim_auto 1")        # in the session both hands pinch in turn
    wait(lambda r: r.get("mode") is not None or r.get("error"), 40)
    r = last() or {}
    check("an immersive session starts from a pinch on the page", entered and r.get("mode") is not None, r.get("error") or r.get("mode") or "no click arrived (clicks=%s, page events %s)" % (r.get("clicks"), r.get("events")))
    if not r.get("mode"):
        open(os.path.join(out, "webxr-report.json"), "w").write(json.dumps(r, indent=1))
        l = uia.sh("logcat -d", timeout=120) or ""
        open(os.path.join(out, "webxr-logcat.txt"), "w", encoding="utf-8", errors="replace").write(l)
        print("\n%d checks, %d failed" % (len(results), len([x for x in results if not x[1]]))); return 1
    uia.sh("setprop debug.ultraxr.sim_motion 1")      # now the simulated head turns and sways
    wait(lambda r: r.get("frames", 0) > 900, 90)
    time.sleep(12)
    r = last() or {}
    open(os.path.join(out, "webxr-report.json"), "w").write(json.dumps(r, indent=1))
    check("frames are delivered", r.get("frames", 0) > 300, "%s frames, %.1f ms apart on average" % (r.get("frames"), r.get("frameMsAvg") or 0))
    check("two views, one per eye, side by side in one framebuffer", r.get("views") == 2 and len(r.get("viewports", [])) == 2 and r["viewports"][0][4] == "left" and r["viewports"][1][4] == "right" and r["viewports"][0][0] < r["viewports"][1][0], r.get("viewports"))
    check("the eyes are 64 mm apart, as configured", r.get("eyeSeparation") is not None and abs(r["eyeSeparation"] - 0.064) < 0.003, r.get("eyeSeparation"))
    check("the projections are off-axis in opposite directions (converged optics)", len(r.get("projections", [])) == 2 and r["projections"][0][2] * r["projections"][1][2] < 0, r.get("projections"))
    check("blend mode reported to the page", r.get("blend") in ("additive", "opaque", "alpha-blend"), "%s in %s" % (r.get("blend"), r.get("mode")))
    if r.get("mode") == "immersive-ar": check("an AR session is additive (optical see-through)", r.get("blend") == "additive", r.get("blend"))
    rs = r.get("refSpaces", {})
    check("reference spaces: viewer, local and local-floor", rs.get("viewer") and rs.get("local") and rs.get("local-floor"), rs)
    yaw = (r.get("headYawMax") or 0) - (r.get("headYawMin") or 0)
    check("the head pose turns as the simulated head does (about 0.5 rad peak to peak)", 0.35 < yaw < 0.65, "%.3f rad" % yaw)
    sway = (r.get("headXMax") or 0) - (r.get("headXMin") or 0)
    check("the head position moves (6DoF, not emulated)", sway > 0.03 and r.get("emulatedPosition") is False, "%.3f m side to side, emulatedPosition=%s" % (sway, r.get("emulatedPosition")))
    # Input inside the session. With Wolvic 1.4 (Chromium backend) these four fail: it hands pages no input
    # sources at all in an immersive session on this runtime, although the same hands click its own windows.
    check("two tracked hands with all 25 joints each", r.get("handsSeen") == 2 and r.get("jointsValid") == 25, "hands %s, joints %s" % (r.get("handsSeen"), r.get("jointsValid")))
    check("finger positions are real: the thumb and index tips close and open", r.get("pinchGapMin") is not None and r["pinchGapMin"] < 0.012 and r["pinchGapMax"] > 0.03, "%s to %s m" % (r.get("pinchGapMin"), r.get("pinchGapMax")))
    check("pinches arrive as select events", r.get("selectEvents", 0) >= 2, r.get("selectEvents"))
    check("input sources have a pointing ray", len(r.get("inputs", [])) >= 2 and all(i.get("aim") for i in r["inputs"]), [(i.get("hand"), i.get("ray"), i.get("profiles")) for i in r.get("inputs", [])])
    print("note: hit-test granted=%s, anchors granted=%s, features=%s" % (r.get("hitTest"), r.get("anchors"), r.get("features")))

    img = grab(out, "webxr")
    ok = img is not None and img.shape[1] == 1920 and img.shape[0] == 1080
    check("the glasses' display shows a 1920x1080 picture", ok, None if img is None else img.shape)
    if ok:
        cv2.imwrite(os.path.join(out, "webxr-eyes.png"), img)
        b, g, rr = img[:, :, 0].astype(int), img[:, :, 1].astype(int), img[:, :, 2].astype(int)
        blue = (b > 180) & (rr < 80); red = (rr > 180) & (b < 80)
        # the page draws its room-fixed square blue in the left eye and red in the right
        check("the left half holds the left eye's view and the right half the right eye's", blue[:, :960].sum() > 500 and red[:, 960:].sum() > 500 and blue[:, 960:].sum() < 50 and red[:, :960].sum() < 50,
              "blue left/right %d/%d, red left/right %d/%d" % (blue[:, :960].sum(), blue[:, 960:].sum(), red[:, :960].sum(), red[:, 960:].sum()))
        if blue[:, :960].sum() > 0 and red[:, 960:].sum() > 0:
            xl = float(np.where(blue[:, :960])[1].mean()); xr = float(np.where(red[:, 960:])[1].mean())
            # 1.5 m away, eyes 64 mm apart converged at 4 m: 0.064*(1/1.5-1/4)/tanH of half a 960-pixel view
            expected = 0.064 * (1 / 1.5 - 1 / 4.0) / 0.3976 * 480
            check("a point 1.5 m away is offset between the eyes by the predicted amount", abs((xl - xr) - expected) < 8, "%.0f px, predicted %.0f" % (xl - xr, expected))
    l = uia.sh("logcat -d", timeout=120) or ""
    open(os.path.join(out, "webxr-logcat.txt"), "w", encoding="utf-8", errors="replace").write(l)
    uia.sh("am force-stop " + bpkg); time.sleep(8)
    uia.adb("emu", "multidisplay", "del", "1")
    bad = [x for x in results if not x[1]]
    print("\n%d checks, %d failed" % (len(results), len(bad)))
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
