#!/usr/bin/env python3
"""From the launcher straight into a game: one tap on a game card, no hands, no touchpad. (emulator)

  python enter_test.py [--apk APP_EMU.apk] [--out DIR]

With "Enter games automatically" switched on, Ultra XR opens the page in the browser and then presses the page's
own start button itself. The test taps the XR dragging card in the launcher the way a person would and then only
watches: the page must end up in an immersive session (its button then reads STOP XR), and the stand-in for the
glasses must show the game's shapes.

Emulator, simulated head. It says nothing about the phone's page load time or where the browser puts its window
there; the app allows for both by trying three places, three times.
"""
import os, sys, time
import uia, cdp
import webxr_pages as wp
import webxr_interact as wi

results = []
def check(name, ok, detail=""):
    results.append(bool(ok)); print(("PASS  " if ok else "FAIL  ") + name + (("  [" + str(detail) + "]") if detail != "" else ""), flush=True)

def main():
    a = sys.argv[1:]; out = os.path.join(wp.HERE, "out-enter"); apk = None
    if "--out" in a: i = a.index("--out"); out = a[i + 1]; del a[i:i + 2]
    if "--apk" in a: i = a.index("--apk"); apk = a[i + 1]; del a[i:i + 2]
    os.makedirs(out, exist_ok=True)
    key = "threejs.org/examples/webxr_xr_dragging.html"
    wp.restart_emulator()
    if apk:
        print(uia.adb("install", "-r", apk, timeout=400).strip().splitlines()[-1]); uia.sh("sync"); time.sleep(40)
    wp.setup()
    for k, v in (("sim_motion", "0"), ("sim_auto", "0"), ("sim_look", "0,0"), ("sim_aim", "0,0"), ("sim_pinch", "0"), ("sim_hand_l", "0,0,0"), ("sim_hand_r", "0,0,0"), ("ptr_hold", "0")): wi.prop(k, v)
    uia.sh("am start -n %s/.ui.LauncherActivity --ez auto_enter true" % wp.PKG); time.sleep(4)
    tapped = False
    for _ in range(6):
        if uia.tap("Play XR dragging (three.js)"): tapped = True; break
        uia.sh("input swipe 540 1700 540 900 300"); time.sleep(1)
    check("the XR dragging card is in the launcher and was tapped", tapped)
    state = None; text = None
    for _ in range(30):
        time.sleep(4)
        try:
            text = cdp.evaluate(key, "(() => { const b = document.querySelector('#XRButton, #VRButton, #ARButton'); return b ? b.textContent : null; })()")
        except BaseException: text = None
        if text and "STOP" in text.upper() or text and "EXIT" in text.upper(): state = text; break
    check("the page is in an immersive session without anyone pointing at its button", state is not None, state or ("button reads: %r" % text))
    pressed = uia.sh("logcat -d | grep -c \"pressed the game's start button\"").strip()
    print("start button presses made by the app: " + pressed)
    time.sleep(3)
    img = wi.shot(out, "entered.png")
    lit = float((img.max(axis=2) > 40).mean()) if img is not None else 0.0
    check("the glasses' picture shows the game", lit > 0.03, "%.1f%% of the picture is lit" % (lit * 100))
    print("\n%d checks, %d failed" % (len(results), results.count(False)))
    return 0 if all(results) else 1

if __name__ == "__main__":
    sys.exit(main())
