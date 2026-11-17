#!/usr/bin/env python3
"""Does a hand actually move an object in an existing WebXR page? (emulator)

  python webxr_interact.py [-s SERIAL] [--out DIR] [URL]

Default page: three.js "XR dragging", where pointing at a shape and holding a pinch picks it up.
The page is not ours and is not changed. The simulated right hand is pointed in one direction
after another; in each it pinches and holds, swings to the side, lets go, and returns. With the
head still, the picture before and after is identical unless a shape was carried somewhere else.

A pass means: the pointing ray derived from the hand's joints hit an object in the page's world,
the held pinch grabbed it, the hand's motion carried it, and releasing left it in a new place.
That is the interaction itself, not just input events. It is still a simulated hand on an
emulator: it says nothing about how well real hand tracking aims.
"""
import json, os, sys, time
import numpy as np, cv2
import uia, cdp
import webxr_pages as wp

def prop(k, v): uia.sh("setprop debug.ultraxr.%s %s" % (k, v))

def shot(out, name):
    d = os.path.join(out, "shots"); os.makedirs(d, exist_ok=True)
    for f in os.listdir(d): os.remove(os.path.join(d, f))
    uia.adb("emu", "screenrecord", "screenshot", "--display", "1", d.replace(os.sep, "/")); time.sleep(1.5)
    fs = sorted(os.listdir(d))
    img = cv2.imread(os.path.join(d, fs[-1])) if fs else None
    if img is not None: cv2.imwrite(os.path.join(out, name), img)
    return img

def changed(a, b):
    """Fraction of the left eye's picture that differs clearly between two captures."""
    d = np.abs(a[:, :960].astype(int) - b[:, :960].astype(int)).max(axis=2)
    return float((d > 50).mean())

def main():
    a = sys.argv[1:]; out = os.path.join(wp.HERE, "out")
    if "-s" in a: i = a.index("-s"); uia.SERIAL = a[i + 1]; del a[i:i + 2]
    if "--out" in a: i = a.index("--out"); out = a[i + 1]; del a[i:i + 2]
    url = a[0] if a else "https://threejs.org/examples/webxr_xr_dragging.html"
    os.makedirs(out, exist_ok=True)
    wp.restart_emulator(); wp.setup()
    for k, v in (("sim_motion", "0"), ("sim_auto", "0"), ("sim_look", "0,0"), ("sim_aim", "0,0"), ("sim_pinch", "0"), ("sim_hand_l", "0,0,0"), ("sim_hand_r", "0,0,0")): prop(k, v)
    uia.sh("am start -n %s -a android.intent.action.VIEW -d '%s'" % (wp.BROWSER, url))
    key = url.split("//", 1)[-1].split("?")[0]
    for _ in range(40):
        time.sleep(3)
        try:
            st = cdp.evaluate(key, "document.readyState")
            if st == "complete": break
        except BaseException: pass
    time.sleep(4)
    print("hook:", cdp.evaluate(key, wp.HOOK)); print("button:", cdp.evaluate(key, wp.BUTTON, gesture=True, await_promise=False))
    time.sleep(10)
    s = cdp.evaluate(key, wp.READ) or {}
    print("session:", s.get("mode"), s.get("blend"), "inputs", s.get("inputs"))
    if not s.get("mode"): print("RESULT: no session"); return 1
    # Wolvic first shows its own "press any button to continue" card over the session; one pinch dismisses it.
    # (An earlier version of this test took that card's disappearance for a moved object.)
    for n in (1, 2):
        prop("sim_tap", "dismiss%d" % n); time.sleep(3)
    s = cdp.evaluate(key, wp.READ) or {}
    base = shot(out, "interact-before.png"); time.sleep(1)
    again = shot(out, "interact-before2.png")
    noise = changed(base, again)
    print("two captures with nothing happening differ by %.3f%%" % (noise * 100))
    result = None
    # A browser points each hand's ray from the shoulder through the hand, so the hand is steered by where it is.
    # Either hand, at a grid of places in front of the wearer: pinch and hold, carry the hand 20 cm, let go, return.
    places = [(x, y) for y in (0.0, 0.12, -0.1, 0.24) for x in (0.0, -0.1, 0.1, -0.2, 0.2, -0.3, 0.3)]
    for hand, bit in (("l", 2), ("r", 1)):
        for x, y in places:
            prop("sim_hand_" + hand, "%.2f,%.2f,0" % (x, y)); time.sleep(1.2)
            prop("sim_pinch", str(bit)); time.sleep(1.2)                 # pinch and hold: grab whatever the ray is on
            prop("sim_hand_" + hand, "%.2f,%.2f,-0.05" % (x + (0.2 if x <= 0 else -0.2), y + 0.1)); time.sleep(1.5)   # carry it
            prop("sim_pinch", "0"); time.sleep(0.8)                      # let go
            prop("sim_hand_" + hand, "0,0,0"); time.sleep(1.5)
            after = shot(out, "interact-after.png")
            diff = changed(base, after)
            got = ((cdp.evaluate(key, wp.READ) or {}).get("select") or 0) - (s.get("select") or 0)
            print("%s hand at x %+.2f y %+.2f: picture changed by %.3f%%, selects so far %d" % ("left" if hand == "l" else "right", x, y, diff * 100, got), flush=True)
            if diff > max(0.004, noise * 4) and got > 0:      # the picture changed AND the page saw the pinch
                result = (hand, x, y, diff); break
        if result: break
    s2 = cdp.evaluate(key, wp.READ) or {}
    print("select events during the test:", (s2.get("select") or 0) - (s.get("select") or 0))
    if result:
        # the change must persist: the object stays where it was put
        time.sleep(2); later = shot(out, "interact-later.png")
        stays = changed(base, later)
        print("RESULT: the %s hand picked an object up (hand at x %+.2f y %+.2f) and moved it: %.2f%% of the picture changed, %.2f%% still changed two seconds after release" % ("left" if result[0] == "l" else "right", result[1], result[2], result[3] * 100, stays * 100))
        json.dump({"url": url, "hand": result[0], "hand_at": result[1:3], "changed": result[3], "still_changed": stays}, open(os.path.join(out, "interact.json"), "w"))
        return 0 if stays > 0.002 else 1
    print("RESULT: no direction grabbed anything")
    return 1

if __name__ == "__main__":
    sys.exit(main())
