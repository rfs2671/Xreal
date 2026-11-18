#!/usr/bin/env python3
"""The phone as a touchpad, without any hand tracking: does it select and grab in a WebXR page? (emulator)

  python pointer_test.py [--apk APP_EMU.apk] [--out DIR]

Ultra XR's touchpad (xr/PointerService, an accessibility service) is switched on, a third-party page (three.js
"XR dragging") is opened in Wolvic, and from then on the test only touches the phone's screen and presses its
volume keys, the way a person holding the phone would:

  1. the pointer is dragged over the page's own START XR button and tapped: the page must start a session
     (found by sweeping the pointer over the lower part of the browser window, since the test does not know
     where the page drew its button);
  2. taps must arrive in the page as WebXR select events;
  3. "press and keep pressed" while the finger drags must pick an object up and leave it somewhere else.

Not covered: the volume keys themselves. Android keeps injected key presses away from accessibility services, so
only a person pressing the real button can show that part; the test sets the held state through a test property.

The simulated wearer's own hands are told not to pinch, so every select comes from the touchpad. This shows the
input path works; it is an emulator with a simulated head, and says nothing about how it feels in the glasses.
"""
import json, os, subprocess, sys, threading, time
import numpy as np, cv2
import uia, cdp
import webxr_pages as wp
import webxr_interact as wi

SERVICE = "dev.ultraxr/dev.ultraxr.xr.PointerService"
results = []
def check(name, ok, detail=""):
    results.append(bool(ok)); print(("PASS  " if ok else "FAIL  ") + name + (("  [" + str(detail) + "]") if detail != "" else ""), flush=True)

def size():
    """The phone screen as it is turned now (the browser holds it in landscape), from a screenshot."""
    png = uia.adb("exec-out", "screencap", "-p", binary=True)
    im = cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_COLOR)
    return im.shape[1], im.shape[0]

TURN = [None]
def turn():
    """Which way the phone's screen is turned (0 upright, 1 top at the phone's left, 3 the other way)."""
    if TURN[0] is None:
        out = uia.sh("dumpsys window displays")
        import re
        # the phone's own screen is display 0; other displays (the stand-in for the glasses) are listed too
        m = re.search(r"mDisplayId=0\b.*?mRotation=ROTATION_(\d+)", out, re.S); v = int(m.group(1)) if m else 0
        TURN[0] = {0: 0, 90: 1, 180: 2, 270: 3}.get(v, 0)
    return TURN[0]

def drag(dx_m, dy_m, w, h, ms=400):
    """Carry the pointer by metres (right, up) as someone holding the phone upright would: the touchpad maps the
    screen's short side to 0.7 m and reads upright whichever way the browser has turned the screen."""
    k = min(w, h) / 0.7; right = dx_m * k; up = dy_m * k
    t = turn()
    px, py = {0: (right, -up), 1: (-up, -right), 3: (up, right), 2: (-right, up)}[t]
    px = int(px); py = int(py)
    x0 = w // 2 - px // 2; y0 = h // 2 - py // 2
    uia.sh("input swipe %d %d %d %d %d" % (x0, y0, x0 + px, y0 + py, ms)); time.sleep(0.5)

def tap(w, h): uia.sh("input tap %d %d" % (w // 2, h // 2)); time.sleep(0.6)

def hold(down):
    """Press and keep pressed. On the phone this is the volume-down key (or a second finger); Android does not pass
    injected key presses to an accessibility service, so the test sets the same state through a test property."""
    wi.prop("ptr_hold", "1" if down else "0"); time.sleep(0.5)

def main():
    a = sys.argv[1:]; out = os.path.join(wp.HERE, "out-pointer"); apk = None
    if "--out" in a: i = a.index("--out"); out = a[i + 1]; del a[i:i + 2]
    if "--apk" in a: i = a.index("--apk"); apk = a[i + 1]; del a[i:i + 2]
    os.makedirs(out, exist_ok=True)
    url = "https://threejs.org/examples/webxr_xr_dragging.html"; key = url.split("//", 1)[-1]
    wp.restart_emulator()
    if apk:
        print(uia.adb("install", "-r", apk, timeout=400).strip().splitlines()[-1]); uia.sh("sync"); time.sleep(40)
    uia.sh("settings put secure enabled_accessibility_services " + SERVICE); uia.sh("settings put secure accessibility_enabled 1"); time.sleep(3)
    wp.setup()
    for k, v in (("sim_motion", "0"), ("sim_auto", "0"), ("sim_look", "0,0"), ("sim_aim", "0,0"), ("sim_pinch", "0"), ("sim_hand_l", "0,0,0"), ("sim_hand_r", "0,0,0"), ("ptr_hold", "0")): wi.prop(k, v)
    uia.sh("am start -n %s -a android.intent.action.VIEW -d '%s'" % (wp.BROWSER, url))
    for _ in range(40):
        time.sleep(3)
        try:
            if cdp.evaluate(key, "document.readyState") == "complete": break
        except BaseException: pass
    time.sleep(5)
    w, h = size()
    check("the touchpad window is on the phone while the browser runs", "pointer: touchpad shown" in uia.sh("logcat -d | grep 'UltraXR *: pointer' | tail -3"))
    print("hook:", cdp.evaluate(key, wp.HOOK))

    # 1. Enter the game with the pointer alone: sweep the lower middle of the window, tapping, until a session starts.
    entered = None
    spots = [(x, y) for y in (-0.05, -0.10, -0.15, 0.0, -0.20, 0.05, -0.25) for x in (0.0, 0.05, -0.05, 0.10, -0.10, 0.15, -0.15)]
    px, py = 0.0, 0.0
    for x, y in spots:
        drag(x - px, y - py, w, h, 250); px, py = x, y
        tap(w, h); time.sleep(1.5)
        s = cdp.evaluate(key, wp.READ) or {}
        if s.get("mode"): entered = (x, y); break
    wi.shot(out, "pointer-entered.png")
    check("a tap on the page's own START XR button, made with the touchpad, starts the session", entered is not None, entered if entered else "no session after %d spots" % len(spots))
    if entered is None:
        print("button: pressed through developer tools instead, to test the rest:", cdp.evaluate(key, wp.BUTTON, gesture=True, await_promise=False)); time.sleep(8)
    time.sleep(6)
    s0 = cdp.evaluate(key, wp.READ) or {}
    print("session:", s0.get("mode"), s0.get("blend"), "inputs", s0.get("inputs"))

    # 2. Taps are selects. (The first one or two dismiss the browser's "press any button" card.)
    for _ in range(4): tap(w, h); time.sleep(0.8)
    s1 = cdp.evaluate(key, wp.READ) or {}
    n = (s1.get("select") or 0) - (s0.get("select") or 0)
    check("touchpad taps arrive in the page as select events", n >= 2, "%d selects from 4 taps" % n)

    # 3. Grab and carry: volume-down held while the finger drags.
    drag(-px, -py, w, h, 300); px, py = 0.0, 0.0; time.sleep(1.5)
    base = wi.shot(out, "pointer-before.png"); time.sleep(1); noise = wi.changed(base, wi.shot(out, "pointer-before2.png"))
    moved = None
    for x, y in [(x, y) for y in (0.0, 0.12, -0.1, 0.24) for x in (0.0, -0.1, 0.1, -0.2, 0.2, -0.3, 0.3)]:
        drag(x - px, y - py, w, h, 250); px, py = x, y
        hold(True); time.sleep(0.6)                        # press and keep pressed: grab what the pointer is on
        carry = 0.2 if x <= 0 else -0.2
        drag(carry, 0.1, w, h, 900); px += carry; py += 0.1
        hold(False); time.sleep(0.5)                       # let go
        drag(-px, -py, w, h, 300); px, py = 0.0, 0.0; time.sleep(1.2)   # pointer back where it was for the first picture
        after = wi.shot(out, "pointer-after.png"); diff = wi.changed(base, after)
        print("pointer at x %+.2f y %+.2f: picture changed by %.3f%%" % (x, y, diff * 100), flush=True)
        if diff > max(0.004, noise * 4): moved = (x, y, diff); break
    stays = 0.0
    if moved:
        time.sleep(2)                                # the object must stay where it was put
        stays = wi.changed(base, wi.shot(out, "pointer-later.png"))
    check("a press held while dragging picks an object up and leaves it somewhere else", moved is not None and stays > 0.002,
          ("grabbed at x %+.2f y %+.2f, %.2f%% of the picture changed, %.2f%% still changed after release" % (moved[0], moved[1], moved[2] * 100, stays * 100)) if moved else "nothing moved")
    json.dump({"entered_at": entered, "selects_from_taps": n, "moved": moved, "still_changed": stays}, open(os.path.join(out, "pointer.json"), "w"))
    print("\n%d checks, %d failed" % (len(results), results.count(False)))
    return 0 if all(results) else 1

if __name__ == "__main__":
    sys.exit(main())
