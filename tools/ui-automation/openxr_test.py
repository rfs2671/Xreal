#!/usr/bin/env python3
"""Runs the Khronos hello_xr sample against Ultra XR's OpenXR runtime on an emulator.

  python openxr_test.py [-s SERIAL] [--out DIR]

Needs an x86-64 emulator image that translates arm64 code (the runtime is arm64 only), with
Ultra XR and extras/hello_xr-vulkan.apk installed. An extra 1920x1080 display stands in for the
glasses, and a test switch makes the app treat it as glasses in half-width 3D.

What this proves: a third-party OpenXR application finds the runtime through the standard broker
query, loads it, gets a session to FOCUSED, and its two eye views reach the glasses' display side
by side, one per half. What it cannot prove: anything about the real glasses, tracking or input.
(The OpenGL ES build of hello_xr crashes inside the emulator's arm translator before it draws, so
only the Vulkan build is used here.)
"""
import os, sys, time
import numpy as np, cv2
import uia

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "dev.ultraxr"; HELLO = "org.khronos.openxr.hello_xr.vulkan"
results = []
def check(name, ok, detail=""):
    results.append((name, bool(ok), detail)); print(("PASS  " if ok else "FAIL  ") + name + (("  [" + detail + "]") if detail else ""), flush=True)

def log(): return uia.sh("logcat -d", timeout=120) or ""

def main():
    a = sys.argv[1:]; out = os.path.join(HERE, "out")
    if "-s" in a: uia.SERIAL = a[a.index("-s") + 1]
    if "--out" in a: out = a[a.index("--out") + 1]
    os.makedirs(out, exist_ok=True)
    if not uia.SERIAL.startswith("emulator"): print("this test needs an emulator"); return 2
    for p in (HELLO, PKG): uia.sh("am force-stop " + p)
    for perm in ("CAMERA", "POST_NOTIFICATIONS"): uia.sh("pm grant %s android.permission.%s" % (PKG, perm))
    uia.adb("emu", "multidisplay", "del", "1"); time.sleep(2)
    uia.adb("emu", "multidisplay", "add", "1", "1920", "1080", "240", "0"); time.sleep(4)
    uia.sh("am start -n %s/.ui.LauncherActivity --ez force_half_stereo true" % PKG); time.sleep(6)
    uia.sh("logcat -c")
    uia.sh("am start -a android.intent.action.MAIN -c org.khronos.openxr.intent.category.IMMERSIVE_HMD -n %s/android.app.NativeActivity" % HELLO)
    focused = False
    for _ in range(30):
        time.sleep(2); l = log()
        if "XR_SESSION_STATE_FOCUSED" in l or "Fatal signal" in l: focused = "XR_SESSION_STATE_FOCUSED" in l; break
    l = log()
    check("the sample finds Ultra XR through the standard runtime broker", "Got runtime: package: dev.ultraxr" in l)
    check("the runtime starts with a head, two controllers and hand tracking", "Ultra XR hands" in l and "Pocket XR left controller" in l and "head: Pocket XR glasses" in l)
    check("it is configured for half-width 3D on the 1920x1080 display", "runtime configured for 1920x1080@60, half-width 3D" in l)
    check("the application's picture goes to the glasses' display", "an OpenXR application connected and owns the glasses" in l and "the application's surface is 1920x1080" in l)
    check("each eye is offered a 960x1080 view (rendered at 140%)", "view 0 with dimensions Width=1344 Height=1512" in l and "view 1 with dimensions Width=1344 Height=1512" in l)
    check("the session reaches FOCUSED", focused)
    time.sleep(4)
    d = os.path.join(out, "openxr"); os.makedirs(d, exist_ok=True)
    for f in os.listdir(d): os.remove(os.path.join(d, f))
    uia.adb("emu", "screenrecord", "screenshot", "--display", "1", d.replace(os.sep, "/")); time.sleep(2)
    files = sorted(os.listdir(d)); img = cv2.imread(os.path.join(d, files[-1])) if files else None
    ok = img is not None and img.shape[1] == 1920 and img.shape[0] == 1080
    check("the glasses' display shows a 1920x1080 picture", ok, str(None if img is None else img.shape))
    if ok:
        cv2.imwrite(os.path.join(out, "openxr-hello_xr.png"), img)
        # hello_xr's cubes are pure blue on a grey-blue clear colour
        blue = ((img[:, :, 0] > 200) & (img[:, :, 1] < 60) & (img[:, :, 2] < 60)).astype(np.float32)
        L = blue[:, :960]; R = blue[:, 960:]
        check("a cube is drawn in each eye's half", L.sum() > 2000 and R.sum() > 2000, "%d and %d blue pixels" % (L.sum(), R.sum()))
        if L.sum() > 0 and R.sum() > 0:
            cl = float((L.sum(axis=0) * np.arange(960)).sum() / L.sum()); cr = float((R.sum(axis=0) * np.arange(960)).sum() / R.sum())
            check("the two eyes see it from different places (the left eye further right, as for anything near)", 3 < cl - cr < 120, "left half x=%.0f, right half x=%.0f, %.0f px apart" % (cl, cr, cl - cr))
    alive = (uia.sh("pidof " + HELLO) or "").strip()
    check("the sample is still running", alive != "", alive)
    late = [x for x in log().splitlines() if "Compositor probably missed frame" in x]
    print("note: the compositor reported %d missed frames (expected under arm translation; not a pass/fail item)" % len(late))
    uia.sh("am force-stop " + HELLO); time.sleep(3)
    check("when the application leaves, the glasses return to the app", "the glasses are back with the app" in log())
    uia.adb("emu", "multidisplay", "del", "1"); time.sleep(2)
    bad = [r for r in results if not r[1]]
    print("\n%d checks, %d failed" % (len(results), len(bad)))
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
