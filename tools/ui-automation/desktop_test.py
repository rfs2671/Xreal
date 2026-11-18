#!/usr/bin/env python3
"""End-to-end test of the spatial desktop on an emulator (or a phone) over adb.

  python desktop_test.py [-s SERIAL] [--out DIR]

Starts a web server for the test pages in ./site (the emulator reaches it as 10.0.2.2), drives
the desktop's phone screen through adb, and checks the result through the state the screen
exposes for accessibility (window list and flags), page titles and screenshots of the preview.
No glasses are involved: this covers the interface, windows, web content, input and persistence
in the phone preview, not what is seen in the glasses.
"""
import http.server, os, socketserver, subprocess, sys, threading, time
import uia

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 8765
BASE = "http://10.0.2.2:%d" % PORT
results = []

def check(name, ok, detail=""):
    results.append((name, bool(ok), detail)); print(("PASS  " if ok else "FAIL  ") + name + ("  [" + detail + "]" if detail else ""), flush=True)

def serve():
    os.chdir(os.path.join(HERE, "site"))
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a): pass
    socketserver.TCPServer.allow_reuse_address = True
    srv = socketserver.TCPServer(("0.0.0.0", PORT), Quiet)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

def state():
    """Windows from the status chip's description: list of dicts."""
    for n in uia.nodes():
        d = n["desc"]
        if ";" in d and d.count(";") >= 10:
            out = []
            for part in d.split("|"):
                f = part.split(";")
                if len(f) < 11: continue
                out.append(dict(id=int(f[0]), title=f[1], follow=f[2] == "true", minimized=f[3] == "true", maximized=f[4] == "true", fullscreen=f[5] == "true",
                                focused=f[6] == "true", yaw=float(f[7]), pitch=float(f[8]), width=float(f[9]), distance=float(f[10])))
            return out
    return []

def wait(pred, timeout=25, step=0.8):
    end = time.time() + timeout; last = None
    while time.time() < end:
        last = state()
        try:
            if pred(last): return last
        except Exception: pass
        time.sleep(step)
    return last

def focused(s): return next((w for w in s if w["focused"]), None)
def title_is(prefix): return lambda s: focused(s) is not None and focused(s)["title"].startswith(prefix)

def go(text):
    n = uia.find("Address"); uia.sh("input tap %d %d" % (n["x"], n["y"])); time.sleep(0.5)
    uia.sh("input text '%s'" % text.replace(" ", "%s")); time.sleep(0.3)
    uia.tap("Go"); time.sleep(0.5)

def type_text(text):
    n = uia.find("Address"); uia.sh("input tap %d %d" % (n["x"], n["y"])); time.sleep(0.5)
    uia.sh("input text '%s'" % text); time.sleep(0.3)
    uia.tap("Type"); time.sleep(0.5)

def pad():
    n = uia.find("Touchpad"); return n["box"]

def preview_box():
    for n in uia.nodes():
        if n["text"].startswith("Preview (no glasses)"):
            x1, y1, _, _ = n["box"]; return (x1, y1)
    return None

def main():
    a = sys.argv[1:]; out = os.path.join(HERE, "out")
    if "-s" in a: uia.SERIAL = a[a.index("-s") + 1]
    if "--out" in a: out = a[a.index("--out") + 1]
    os.makedirs(out, exist_ok=True); out = os.path.abspath(out)
    serve()
    pkg = "dev.ultraxr"
    uia.sh("am force-stop %s; run-as %s rm -f shared_prefs/desktop.xml; logcat -c" % (pkg, pkg))
    uia.sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
    uia.sh("am start -n %s/.ui.LauncherActivity --ez desktop true" % pkg); time.sleep(6)

    # 1. home
    t = uia.texts()
    check("desktop opens with the home screen and no windows", "Spatial desktop" in t and any("No windows open" in x for x in t), "")
    uia.shot(os.path.join(out, "01-home.png"))

    # 2. open a page from the address box, click in it through the touchpad
    go(BASE + "/click.html")
    s = wait(title_is("click-test"))
    check("a typed address opens a window and the page loads", focused(s) is not None and focused(s)["title"] == "click-test", str(focused(s)))
    x1, y1, x2, y2 = pad(); cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    uia.sh("input tap %d %d" % (cx, cy)); s = wait(title_is("clicked-1"), 10)
    check("a tap on the touchpad clicks in the page", focused(s)["title"] == "clicked-1", focused(s)["title"])
    uia.sh("input swipe %d %d %d %d 300" % (cx, cy, cx + 150, cy + 100)); time.sleep(0.5)     # move the pointer, then click again
    uia.sh("input tap %d %d" % (cx, cy)); s = wait(title_is("clicked-2"), 10)
    check("the pointer moves and a second tap clicks again", focused(s)["title"] == "clicked-2", focused(s)["title"])
    uia.shot(os.path.join(out, "02-click.png"))

    # 3. scrolling (volume keys scroll the focused window)
    go(BASE + "/scroll.html"); wait(title_is("scroll-0"))
    for _ in range(3): uia.sh("input keyevent KEYCODE_VOLUME_DOWN"); time.sleep(0.4)
    s = wait(lambda s: focused(s)["title"].startswith("scroll-") and int(focused(s)["title"][7:]) > 0, 10)
    down = int(focused(s)["title"][7:]) if focused(s)["title"][7:].isdigit() else 0
    check("volume down scrolls the page down", down > 0, focused(s)["title"])
    for _ in range(6): uia.sh("input keyevent KEYCODE_VOLUME_UP"); time.sleep(0.3)
    s = wait(title_is("scroll-0"), 10)
    check("volume up scrolls it back to the top", focused(s)["title"] == "scroll-0", focused(s)["title"])

    # 4. typing into the page
    go(BASE + "/type.html"); wait(title_is("type-empty")); time.sleep(1)
    type_text("hello")
    s = wait(title_is("submitted-hello"), 10)
    check("text from the phone is typed into the page and Enter submits it", focused(s)["title"] == "submitted-hello", focused(s)["title"])

    # 5. second and third windows, focus by chip
    uia.tap("Browser"); s = wait(lambda s: len(s) == 2, 20)
    check("a second window opens beside the first", len(s) == 2 and abs(s[0]["yaw"] - s[1]["yaw"]) > 20, str([(w["id"], w["yaw"]) for w in s]))
    uia.tap("3D web"); s = wait(lambda s: len(s) == 3, 20)
    check("a third window opens", len(s) == 3, "")
    first = s[0]["id"]
    uia.tap("Window %d" % first); s = wait(lambda s: focused(s)["id"] == first, 8)
    check("tapping a window's chip focuses it", focused(s)["id"] == first, "")
    uia.shot(os.path.join(out, "03-three-windows.png"))

    # 6. window controls on the focused window
    uia.tap("Min"); s = wait(lambda s: next(w for w in s if w["id"] == first)["minimized"], 8)
    check("Min hides the window", next(w for w in s if w["id"] == first)["minimized"] and focused(s)["id"] != first, "")
    uia.tap("Window %d" % first); s = wait(lambda s: not next(w for w in s if w["id"] == first)["minimized"], 8)
    check("tapping its chip restores it", not next(w for w in s if w["id"] == first)["minimized"] and focused(s)["id"] == first, "")
    w0 = focused(s)["width"]
    uia.tap("Max"); s = wait(lambda s: focused(s)["maximized"], 8)
    check("Max enlarges the window", focused(s)["maximized"] and focused(s)["width"] > w0, "%.2f -> %.2f" % (w0, focused(s)["width"]))
    uia.shot(os.path.join(out, "04-maximized.png"))
    uia.tap("Max"); s = wait(lambda s: not focused(s)["maximized"], 8)
    check("Max again restores its size", abs(focused(s)["width"] - w0) < 0.01, "")
    uia.tap("Full"); s = wait(lambda s: focused(s)["fullscreen"], 8)
    check("Full shows only this window", focused(s)["fullscreen"], ""); uia.shot(os.path.join(out, "05-fullscreen.png"))
    uia.tap("Full"); wait(lambda s: not focused(s)["fullscreen"], 8)
    y0 = focused(state())["yaw"]
    uia.tap("Locked"); s = wait(lambda s: focused(s)["follow"], 8)
    check("Lock/Follow switches to Follow head without the window jumping", focused(s)["follow"] and abs(focused(s)["yaw"] - y0) < 0.5, "%.1f -> %.1f" % (y0, focused(s)["yaw"]))
    uia.tap("Following"); s = wait(lambda s: not focused(s)["follow"], 8)
    check("and back to Lock in space", not focused(s)["follow"], "")

    # 7. move mode: dragging the pad turns the window; pinch is covered by unit tests only
    uia.tap("Move"); time.sleep(0.6)
    y0 = focused(state())["yaw"]; p0 = focused(state())["pitch"]
    uia.sh("input swipe %d %d %d %d 400" % (cx - 150, cy, cx + 150, cy - 120)); time.sleep(0.8)
    s = state()
    check("in Move mode a drag on the touchpad moves the window", abs(focused(s)["yaw"] - y0) > 3 and abs(focused(s)["pitch"] - p0) > 2, "yaw %.1f -> %.1f, pitch %.1f -> %.1f" % (y0, focused(s)["yaw"], p0, focused(s)["pitch"]))
    uia.tap("Moving"); time.sleep(0.5)
    uia.tap("Gather"); time.sleep(0.8)
    s = state(); mean = sum(w["yaw"] for w in s if not w["follow"]) / max(1, len([w for w in s if not w["follow"]]))
    check("Gather brings the windows back in front", abs(mean) < 25, "mean yaw %.1f" % mean)

    # 8. the preview can be dragged to look around (changes what is drawn)
    pv = preview_box()
    a1 = os.path.join(out, "06-preview-a.png"); a2 = os.path.join(out, "07-preview-b.png")
    uia.shot(a1)
    if pv: uia.sh("input swipe %d %d %d %d 300" % (pv[0] + 500, pv[1] + 250, pv[0] + 150, pv[1] + 250)); time.sleep(0.8)
    uia.shot(a2)
    check("dragging the preview looks around", pv is not None and open(a1, "rb").read() != open(a2, "rb").read(), "")

    # 9. video plays inside a window
    uia.tap("Window %d" % first); time.sleep(0.5)
    go(BASE + "/video.html")
    s = wait(title_is("video-playing-3"), 40)
    f = focused(s) if s else None
    check("a video plays in a window (it reaches 3 s of playback)", f is not None and f["title"] == "video-playing-3", f["title"] if f else "no state")
    uia.shot(os.path.join(out, "08-video.png"))

    # 10. persistence across a restart of the app
    before = sorted((w["id"], round(w["yaw"]), w["minimized"], w["follow"], round(w["width"], 1)) for w in state())
    uia.sh("input keyevent KEYCODE_HOME"); time.sleep(1.5); uia.sh("am force-stop %s" % pkg); time.sleep(1)
    uia.sh("am start -n %s/.ui.LauncherActivity --ez desktop true" % pkg); time.sleep(7)
    s = wait(lambda s: len(s) == len(before), 20)
    check("it opens on the main menu, with the windows of last time put away", bool(s) and all(w["minimized"] for w in s), str([w["minimized"] for w in s or []]))
    # bring them back from their chips on the phone, as the owner would
    for _ in range(len(before)):
        chip = next((n for n in uia.nodes() if n["text"].startswith("▁")), None)
        if chip is None: break
        x1, y1, x2, y2 = chip["box"]; uia.sh("input tap %d %d" % ((x1 + x2) // 2, (y1 + y2) // 2)); time.sleep(0.8)
    s = wait(lambda s: len(s) == len(before) and not any(w["minimized"] for w in s), 10)
    after = sorted((w["id"], round(w["yaw"]), w["minimized"], w["follow"], round(w["width"], 1)) for w in (s or []))
    check("the layout is restored after the app is closed and reopened", before == after, "%s vs %s" % (before, after))
    s = wait(lambda s: all(w["title"] not in ("", "Window") for w in s), 30)
    check("the restored windows reload their pages", all(w["title"] for w in s), str([w["title"] for w in s]))
    uia.shot(os.path.join(out, "09-restored.png"))

    # 10b. glasses attach and detach (an emulator's extra display stands in for the glasses)
    if uia.SERIAL.startswith("emulator"):
        uia.tap("Gather"); time.sleep(0.5)
        uia.adb("emu", "multidisplay", "add", "1", "1920", "1080", "240", "0"); time.sleep(5)
        t = uia.texts()
        check("when a display is attached the desktop moves onto it", any("Showing on the glasses" in x for x in t), "")
        uia.tap("Gather"); time.sleep(1.0)      # centre the windows for the new view
        # a white page is darkened (black is see-through on the glasses)
        go(BASE + "/light.html"); wait(title_is("light-page"), 15); time.sleep(1.5)
        dshots = os.path.join(out, "glasses-dark"); os.makedirs(dshots, exist_ok=True)
        for f in os.listdir(dshots): os.remove(os.path.join(dshots, f))
        uia.adb("emu", "screenrecord", "screenshot", "--display", "1", dshots.replace(os.sep, "/")); time.sleep(1.5)
        bright = 1.0
        try:
            import numpy as np, cv2
            img = cv2.imread(os.path.join(dshots, sorted(os.listdir(dshots))[-1])); bright = float((img.min(axis=2) > 200).mean())
        except Exception: pass
        check("a white page is shown dark on the glasses display", bright < 0.15, "%.0f%% of the picture is near-white" % (bright * 100))
        titles = sorted(w["title"] for w in state())
        shots = os.path.join(out, "glasses"); os.makedirs(shots, exist_ok=True)
        for f in os.listdir(shots): os.remove(os.path.join(shots, f))
        uia.adb("emu", "screenrecord", "screenshot", "--display", "1", shots.replace(os.sep, "/")); time.sleep(1.5)
        lit = 0.0; size = None
        try:
            import numpy as np, cv2
            img = cv2.imread(os.path.join(shots, sorted(os.listdir(shots))[-1])); size = img.shape[1], img.shape[0]
            lit = float((img.max(axis=2) > 40).mean())
        except Exception as e: size = str(e)
        check("the picture on the attached display is 1920x1080 and shows the windows", size == (1920, 1080) and lit > 0.02, "%s, %.0f%% lit" % (size, lit * 100))
        check("windows keep their pages across the switch", sorted(w["title"] for w in state()) == titles, "")
        uia.adb("emu", "multidisplay", "del", "1"); time.sleep(4)
        t = uia.texts()
        check("when it is detached the desktop returns to the phone preview", any("Preview (no glasses)" in x for x in t), "")
        check("and the windows are still there", sorted(w["title"] for w in state()) == titles, "")
        uia.adb("emu", "multidisplay", "add", "1", "1920", "1080", "240", "0"); time.sleep(5)
        check("re-attaching shows it on the display again", any("Showing on the glasses" in x for x in uia.texts()), "")
        uia.adb("emu", "multidisplay", "del", "1"); time.sleep(3)

    # 10c. pocket mode: the whole screen is a touchpad; Back twice leaves it
    go(BASE + "/click.html"); wait(title_is("click-test"), 15)
    uia.tap("Pocket mode"); time.sleep(1.5)
    if uia.find("Got it"): uia.tap("Got it"); time.sleep(0.8)     # Android's one-time note about full screen
    check("Pocket mode covers the screen with a black touchpad", uia.find("Pocket touchpad") is not None, "")
    pk = os.path.join(out, "12-pocket.png"); uia.shot(pk)
    dark = 0.0
    try:
        import numpy as np, cv2
        dark = float((cv2.imread(pk).max(axis=2) < 16).mean())
    except Exception: pass
    check("the phone screen is black in pocket mode", dark > 0.97, "%.1f%% black" % (dark * 100))
    uia.sh("input tap 540 1200"); s = wait(title_is("clicked-1"), 10)
    check("a tap anywhere in pocket mode clicks in the focused window", focused(s) is not None and focused(s)["title"] == "clicked-1", focused(s)["title"] if focused(s) else "")
    uia.sh("input keyevent KEYCODE_BACK"); time.sleep(2.5)
    check("one Back press does not leave pocket mode", uia.find("Pocket touchpad") is not None, "")
    uia.sh("input keyevent KEYCODE_BACK; input keyevent KEYCODE_BACK"); time.sleep(1.2)
    check("Back twice leaves pocket mode", uia.find("Pocket touchpad") is None and uia.find("Spatial desktop") is not None, "")

    # 10d. a hardware keyboard types into the focused window
    go(BASE + "/type.html"); wait(title_is("type-empty"), 15); time.sleep(1.0)
    uia.sh("input keyevent KEYCODE_H KEYCODE_I"); s = wait(title_is("typed-hi"), 10)
    check("keys from a keyboard are typed into the focused window", focused(s) is not None and focused(s)["title"] == "typed-hi", focused(s)["title"] if focused(s) else "")

    # 11. closing
    n0 = len(state()); uia.tap("Close"); s = wait(lambda s: len(s) == n0 - 1, 8)
    check("Close removes the focused window", len(s) == n0 - 1, "")

    # 12. frame rate reported by the renderer
    time.sleep(6)
    log = uia.adb("logcat", "-d", "-s", "UltraXR:I")
    perf = [l for l in log.splitlines() if "desktop:" in l and "fps" in l]
    fps = float(perf[-1].split("desktop:")[1].split("fps")[0]) if perf else 0
    check("the renderer reports its frame rate", fps > 0, perf[-1].split("UltraXR :")[-1].strip() if perf else "no line")
    crashes = [l for l in uia.adb("logcat", "-d").splitlines() if "FATAL EXCEPTION" in l or ("AndroidRuntime" in l and "dev.ultraxr" in l)]
    check("no crash was logged", not crashes, crashes[0][-120:] if crashes else "")

    failed = [r for r in results if not r[1]]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    return 1 if failed else 0

if __name__ == "__main__":
    sys.exit(main())
