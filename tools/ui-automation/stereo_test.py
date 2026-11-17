#!/usr/bin/env python3
"""Checks the two-eye picture of the spatial desktop on an emulator.

  python stereo_test.py [-s SERIAL] [--out DIR]

The emulator has no glasses, so the desktop is started with its test switch that draws both eye
views on an attached 1920x1080 display. What is checked is the picture the phone would send:
each half holds one eye's whole view, squeezed to half width, and things 2 m away are offset
between the halves by the amount the eye distance calls for. Whether the glasses then show each
half to the right eye, and how it looks, cannot be checked here.
"""
import os, sys, time
import numpy as np, cv2
import uia

HERE = os.path.dirname(os.path.abspath(__file__))
results = []
def check(name, ok, detail=""):
    results.append((name, bool(ok), detail)); print(("PASS  " if ok else "FAIL  ") + name + (("  [" + detail + "]") if detail else ""), flush=True)

def grab(out, name):
    d = os.path.join(out, name); os.makedirs(d, exist_ok=True)
    for f in os.listdir(d): os.remove(os.path.join(d, f))
    uia.adb("emu", "screenrecord", "screenshot", "--display", "1", d.replace(os.sep, "/")); time.sleep(1.5)
    return cv2.imread(os.path.join(d, sorted(os.listdir(d))[-1]))

def shift_between(a, b, limit=80):
    """Horizontal shift (pixels, positive = a's content is further right) that best lines b up with a."""
    pa = a.astype(np.float32).mean(axis=0); pb = b.astype(np.float32).mean(axis=0)
    pa -= pa.mean(); pb -= pb.mean(); best = (-1e30, 0)
    for s in range(-limit, limit + 1):
        x = pa[max(0, s):len(pa) + min(0, s)]; y = pb[max(0, -s):len(pb) + min(0, -s)]
        c = float((x * y).sum() / (np.linalg.norm(x) * np.linalg.norm(y) + 1e-9))
        if c > best[0]: best = (c, s)
    return best[1], best[0]

def attach():
    """Attach the stand-in display and wait until the desktop has moved onto it."""
    uia.adb("emu", "multidisplay", "add", "1", "1920", "1080", "240", "0")
    for _ in range(20):
        time.sleep(1.5)
        if any("Showing on the glasses" in x for x in uia.texts()): break
    time.sleep(3)

def start(pkg, stereo):
    uia.sh("am force-stop " + pkg); time.sleep(1)
    uia.sh("am start -n %s/.ui.LauncherActivity --ez desktop true --ez stereo_preview %s" % (pkg, "true" if stereo else "false")); time.sleep(7)

def main():
    a = sys.argv[1:]; out = os.path.join(HERE, "out")
    if "-s" in a: uia.SERIAL = a[a.index("-s") + 1]
    if "--out" in a: out = a[a.index("--out") + 1]
    os.makedirs(out, exist_ok=True)
    pkg = "dev.ultraxr"
    if not uia.SERIAL.startswith("emulator"): print("this test needs an emulator"); return 2
    uia.adb("emu", "multidisplay", "del", "1"); time.sleep(2)
    uia.sh("pm clear " + pkg); time.sleep(1)
    for perm in ("CAMERA", "POST_NOTIFICATIONS"): uia.sh("pm grant %s android.permission.%s" % (pkg, perm))   # no dialogs over the desktop

    # one flat view first, for comparison
    start(pkg, False)
    attach()
    mono = grab(out, "stereo-mono")
    check("2D: one view fills the 1920x1080 picture", mono is not None and mono.shape[1] == 1920 and mono.shape[0] == 1080, str(None if mono is None else mono.shape))
    g = cv2.cvtColor(mono, cv2.COLOR_BGR2GRAY)
    mono_shift, _ = shift_between(g[:, :960], g[:, 960:])
    lit_cols = np.where(g.max(axis=0) > 60)[0]
    mono_centre = (lit_cols.min() + lit_cols.max()) / 2 if len(lit_cols) else -1
    check("2D: the home screen (clock and rail on black) is centred in the whole picture", abs(mono_centre - 960) < 40 and float((g > 60).mean()) < 0.2, "centre of what is lit: x=%.0f, %.1f%% lit" % (mono_centre, float((g > 60).mean()) * 100))
    cv2.imwrite(os.path.join(out, "stereo-mono.png"), mono)

    # both eyes
    uia.adb("emu", "multidisplay", "del", "1"); time.sleep(3)
    start(pkg, True)
    attach()
    st = grab(out, "stereo-pair")
    g = cv2.cvtColor(st, cv2.COLOR_BGR2GRAY); L = g[:, :960]; R = g[:, 960:]
    cv2.imwrite(os.path.join(out, "stereo-pair.png"), st)
    litL = float((L > 60).mean()); litR = float((R > 60).mean())
    check("3D: both halves of the picture are drawn", litL > 0.002 and litR > 0.002, "left %.2f%% lit, right %.2f%% lit" % (litL * 100, litR * 100))
    s, corr = shift_between(L, R)
    check("3D: the two halves show the same scene", corr > 0.9, "match %.3f" % corr)
    # the clock and rail are 2 m away; eyes 64 mm apart converge at 4 m: 0.064*(1/2-1/4)/tanH of half the eye's width
    expected = 0.064 * 0.25 / 0.3976 * 480
    check("3D: things 2 m away sit further right in the left eye's half, by the expected amount", abs(s - expected) <= 3, "measured %d px, expected %.1f px" % (s, expected))
    colsL = np.where(L.max(axis=0) > 60)[0]; colsR = np.where(R.max(axis=0) > 60)[0]
    cl = (colsL.min() + colsL.max()) / 2; cr = (colsR.min() + colsR.max()) / 2
    check("3D: each half holds a whole view, centred in its own half", abs(cl - 480) < 40 and abs(cr - 480) < 40, "left centre %.0f, right centre %.0f of 960" % (cl, cr))
    # squeezed to half width: the clock is half as wide in pixels as in the flat picture
    def clock_width(img):
        band = img[int(img.shape[0] * 0.25):int(img.shape[0] * 0.62)]; c = np.where(band.max(axis=0) > 120)[0]
        return (c.max() - c.min()) if len(c) else 0
    wm = clock_width(cv2.cvtColor(mono, cv2.COLOR_BGR2GRAY)); wl = clock_width(L)
    check("3D: each eye's view is squeezed to half width (the glasses stretch it back)", wm > 0 and abs(wl / wm - 0.5) < 0.06, "clock %d px wide in 2D, %d px in one half" % (wm, wl))

    # windows too, and the frame rate with two views
    uia.tap("Browser"); time.sleep(6)
    st2 = grab(out, "stereo-window"); cv2.imwrite(os.path.join(out, "stereo-window.png"), st2)
    g2 = cv2.cvtColor(st2, cv2.COLOR_BGR2GRAY)
    s2, corr2 = shift_between(g2[:, :960], g2[:, 960:])
    check("3D: an open window is drawn for both eyes with the same offset", corr2 > 0.85 and abs(s2 - expected) <= 4, "match %.3f, %d px" % (corr2, s2))
    uia.tap("Full"); time.sleep(2.5)
    st3 = grab(out, "stereo-full"); g3 = cv2.cvtColor(st3, cv2.COLOR_BGR2GRAY)
    s3, corr3 = shift_between(g3[:, :960], g3[:, 960:])
    check("3D: a fullscreen window keeps its depth", corr3 > 0.85 and abs(s3 - expected) <= 4, "match %.3f, %d px" % (corr3, s3))
    uia.tap("Full"); time.sleep(8)
    log = uia.sh("logcat -d -s UltraXR:* | grep 'desktop: ' | tail -3") if hasattr(uia, "sh") else ""
    fps = None
    for line in (log or "").splitlines():
        if " fps" in line:
            try: fps = float(line.split("desktop: ")[1].split(" fps")[0])
            except Exception: pass
    check("3D: still 55 frames a second or more with two views", fps is not None and fps >= 55, "last reported: %s" % fps)

    uia.adb("emu", "multidisplay", "del", "1"); time.sleep(2)
    bad = [r for r in results if not r[1]]
    print("\n%d checks, %d failed" % (len(results), len(bad)))
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
