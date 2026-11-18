#!/usr/bin/env python3
"""The room reaches a web page: floor, table top and wall, ray casts, and the standard hit-test calls. (emulator)

  python room_test.py [-s SERIAL] [--out DIR] [--browser PACKAGE/ACTIVITY]

There are no cameras on an emulator, so the room is made up (xr/SimWearer, "sim_room"): the floor 1.30 m below
where the head starts, a table top 0.75 m above it one metre ahead, and a wall 2.5 m ahead. What is tested is
everything after the cameras: the floor given to the runtime, the room served on localhost (xr/LocalSite), and
the page-side script (assets/site/ultraxr-room.js) lining it up with the page's own space and answering ray casts,
both through its own calls and through the standard WebXR hit test, plane detection and anchors.

Whether the cameras find the real floor is measured elsewhere: on recordings (app/src/test RoomTest,
tools/pixel/lab.py replay) and by looking through the glasses.
"""
import json, os, sys, time
import uia
import webxr_test as wt
from webxr_test import check, last, wait, PKG, DEVICE_DIR

def main():
    a = sys.argv[1:]; out = os.path.join(wt.HERE, "out-room")
    browser = "com.igalia.wolvic/com.igalia.wolvic.VRBrowserActivity"
    if "-s" in a: uia.SERIAL = a[a.index("-s") + 1]
    if "--out" in a: out = a[a.index("--out") + 1]
    if "--browser" in a: browser = a[a.index("--browser") + 1]
    os.makedirs(out, exist_ok=True)
    uia.sh("am force-stop " + browser.split("/")[0]); time.sleep(8)
    for perm in ("CAMERA", "POST_NOTIFICATIONS"): uia.sh("pm grant %s android.permission.%s" % (PKG, perm))
    uia.adb("emu", "multidisplay", "del", "1"); time.sleep(2)
    uia.adb("emu", "multidisplay", "add", "1", "1920", "1080", "240", "0"); time.sleep(4)
    uia.sh("am start -n %s/.ui.LauncherActivity --ez force_half_stereo true --ez sim_wearer true --ez test_site true" % PKG); time.sleep(6)
    uia.sh("rm -f " + DEVICE_DIR + "/report.json")
    for k, v in (("sim_motion", "0"), ("sim_auto", "0"), ("sim_look", "0,0"), ("sim_aim", "0,0"), ("sim_head_y", "0"), ("sim_floor", ""), ("sim_room", "")): uia.sh("setprop debug.ultraxr.%s '%s'" % (k, v))
    uia.sh("setprop debug.xrt.XRT_COMPOSITOR_LOG error"); uia.sh("logcat -c")
    uia.sh("am start -n %s -a android.intent.action.VIEW -d 'http://localhost:8765/room.html'" % browser)

    check("the room page loads from the app itself", wait(lambda r: r.get("page") == "room" and r.get("hasXR") is True, 120))
    entered = False
    for n in range(1, 30):
        uia.sh("setprop debug.ultraxr.sim_tap %d" % n)
        if wait(lambda r: r.get("mode") is not None or r.get("error"), 4): entered = True; break
    r = last() or {}
    check("its session starts, having asked for hit-test, plane-detection and anchors", entered and r.get("mode") is not None, r.get("error") or r.get("mode"))
    if not r.get("mode"):
        print("\n%d checks, %d failed" % (len(wt.results), len([x for x in wt.results if not x[1]]))); return 1
    wait(lambda r: r.get("frames", 0) > 60 and r.get("ready"), 60)
    r = last() or {}
    check("the page lines the room up with its own space (from the head pose)", r.get("ready") is True and r.get("packets", 0) > 3, "ready=%s, %s readings of the room, error=%s" % (r.get("ready"), r.get("packets"), r.get("roomError")))
    check("before the cameras have found a floor it is the height setting, and the page is told so", r.get("measured") is False and abs((r.get("eyeHeight") or 0) - 1.6) < 0.04, "measured=%s, eyes %s m above the floor" % (r.get("measured"), r.get("eyeHeight")))

    uia.sh("setprop debug.ultraxr.sim_room 1.30")
    wait(lambda r: r.get("measured") is True and abs((r.get("eyeHeight") or 0) - 1.30) < 0.04, 30)
    r = last() or {}
    check("a floor found by the cameras becomes the page's floor", r.get("measured") is True and abs((r.get("eyeHeight") or 0) - 1.30) < 0.04, "measured=%s, eyes %s m above the floor" % (r.get("measured"), r.get("eyeHeight")))
    check("a ring is drawn on each tracked fingertip (the display alignment test)", r.get("fingerMarks", 0) >= 2, "%s marks" % r.get("fingerMarks"))
    check("the page gets the floor, the table top and the wall", sorted((r.get("kinds") or "").split(",")) == ["floor", "table", "wall"], r.get("kinds"))
    log = uia.sh("logcat -d | grep -c 'floor 1.30 m below the world.s origin, measured'").strip()
    check("the runtime was told the measured floor", log not in ("", "0"), log + " log lines")

    # Looking straight ahead: the wall 2.5 m away. Looking 30 degrees down: the table top. Looking 70 degrees down: the floor.
    def look(pitch, kind, y, d):
        uia.sh("setprop debug.ultraxr.sim_look 0,%d" % pitch)
        ok = wait(lambda r: (r.get("lookHit") or {}).get("kind") == kind and abs((r.get("lookHit") or {}).get("y", 9) - y) < 0.05, 20)
        r = last() or {}; h = r.get("lookHit") or {}
        check("looking %d degrees down meets the %s, %.2f m above the floor" % (-pitch, kind, y), ok and abs(h.get("d", 0) - d) < 0.12, "ray cast: %s; standard hit test: %s" % (h, r.get("apiHit")))
        api = r.get("apiHit") if isinstance(r.get("apiHit"), dict) else {}
        check("  and the standard WebXR hit test says the same", abs(api.get("y", 9) - y) < 0.05, r.get("apiHit"))
        return r
    look(0, "wall", 1.30, 2.5)
    r = look(-30, "table", 0.75, 1.10)
    look(-70, "floor", 0.0, 1.38)
    planes = r.get("apiPlanes") or []
    check("plane detection lists the three surfaces with outlines", len(planes) == 3 and all(p.get("points") == 5 for p in planes) and sorted(p.get("orientation") for p in planes) == ["horizontal", "horizontal", "vertical"], planes)
    r = last() or {}
    an = r.get("apiAnchor")
    check("an anchor made from a hit stays where it was made", isinstance(an, dict) and an.get("tracked") is True, an)
    # The wearer's head moves; the room must stay where it is for the page.
    uia.sh("setprop debug.ultraxr.sim_look 0,-30"); uia.sh("setprop debug.ultraxr.sim_motion 1"); time.sleep(8)
    r = last() or {}
    check("with the head swaying the floor stays put for the page", r.get("measured") is True and abs((r.get("eyeHeight") or 0) - 1.30) < 0.06, "eyes %s m above the floor" % r.get("eyeHeight"))
    uia.sh("setprop debug.ultraxr.sim_motion 0"); uia.sh("setprop debug.ultraxr.sim_look 0,-30"); uia.sh("setprop debug.ultraxr.sim_aim 0,-30")
    # A ball thrown at the table (a pinch) bounces.
    b0 = r.get("bounces", 0)
    for n in range(40, 46): uia.sh("setprop debug.ultraxr.sim_tap %d" % n); time.sleep(1.5)
    time.sleep(4); r = last() or {}
    check("balls thrown with a pinch bounce off what was found", r.get("balls", 0) >= 1 and r.get("bounces", 0) > b0, "%s balls, %s bounces" % (r.get("balls"), r.get("bounces")))
    img = wt.grab(out, "room")
    if img is not None:
        import cv2; cv2.imwrite(os.path.join(out, "room.png"), img)
        g = (img[:, :, 1] > 150) & (img[:, :, 2] < 120); cyan = (img[:, :, 0] > 180) & (img[:, :, 1] > 150) & (img[:, :, 2] < 120)
        check("the glasses' picture shows the green floor grid and the table's outline", g.sum() > 1500 and cyan.sum() > 200, "%d green, %d cyan pixels" % (g.sum(), cyan.sum()))
    open(os.path.join(out, "room-report.json"), "w").write(json.dumps(r, indent=1))
    for k in ("sim_room", "sim_look", "sim_aim"): uia.sh("setprop debug.ultraxr.%s ''" % k)
    bad = [x for x in wt.results if not x[1]]
    print("\n%d checks, %d failed" % (len(wt.results), len(bad)))
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
