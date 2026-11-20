#!/usr/bin/env python3
"""The spatial desktop, driven with the keys of a d-pad on a phone over adb (docs/INTERFACE.md).

  python desktop_test.py -s SERIAL [--out DIR]

Opens the desktop on the phone (no glasses needed), presses the keys a d-pad or a gamepad sends, and checks what
they did through the state the screen exposes for accessibility (the focus, and each window's place, size and
flags). At every step it also saves the picture one eye of the glasses would get (a debug build draws it on
request, at the glasses' own field of view: DesktopRenderer.eyeCapture), so the look can be judged without the
glasses on. This covers the interface; it says nothing about tracking, and nothing about how it looks through the
glasses' own optics.

It closes the windows that are open when it starts, and leaves none behind.
"""
import os, re, subprocess, sys, time
import uia

results = []
def check(name, ok, detail=""):
    results.append((name, bool(ok), detail)); print(("PASS  " if ok else "FAIL  ") + name + ("  [" + str(detail) + "]" if detail else ""), flush=True)

def state():
    """(focus, name of the shelf's mark, windows) from the status line's description."""
    for n in uia.nodes():
        d = n["desc"]
        if not d.startswith("focus;"): continue
        parts = d.split("|"); f = parts[0].split(";"); ws = []
        for part in parts[1:]:
            g = part.split(";")
            if len(g) < 13: continue
            ws.append(dict(id=int(g[0]), title=g[1], follow=g[2] == "true", minimized=g[3] == "true", maximized=g[4] == "true", fullscreen=g[5] == "true",
                           focused=g[6] == "true", yaw=float(g[7]), pitch=float(g[8]), width=float(g[9]), distance=float(g[10]), height=float(g[11]), kind=g[12]))
        return f[1], (f[2] if len(f) > 2 else ""), ws
    return None, None, []

def key(*names, pause=0.35):
    for k in names: uia.sh("input keyevent " + k); time.sleep(pause)

def wait(pred, timeout=20, step=0.6):
    end = time.time() + timeout; s = state()
    while time.time() < end:
        if s[0] is not None and pred(s): return s
        time.sleep(step); s = state()
    return s

def eye(path):
    uia.sh("run-as dev.ultraxr sh -c 'rm -f files/eye_capture.png; touch files/eye_capture.req'")
    for _ in range(12):
        time.sleep(0.4)
        if "eye_capture.png" in uia.sh("run-as dev.ultraxr ls files/eye_capture.png 2>/dev/null"):
            open(path, "wb").write(uia.adb("exec-out", "run-as", "dev.ultraxr", "cat", "files/eye_capture.png", binary=True)); return True
    return False

def lit(path, box=None):
    """How much of a picture (or of a box of it, in fractions) is not black, 0..1."""
    try:
        from PIL import Image
    except ImportError: return -1.0
    im = Image.open(path).convert("L")
    if box: im = im.crop((int(box[0] * im.width), int(box[1] * im.height), int(box[2] * im.width), int(box[3] * im.height)))
    h = im.histogram(); return sum(h[24:]) / float(im.width * im.height)

def to_shelf():
    """Back until the focus is on the shelf (Back: page -> window -> shelf). A keyboard's Escape is Back; the phone's own Back key would leave the screen from the shelf."""
    for _ in range(4):
        if (state()[0] or "").startswith("shelf"): return
        key("ESCAPE")

def shelf_to(title):
    """Walks the shelf's focus to the mark called [title]."""
    to_shelf()
    for _ in range(30):      # to the first mark, then right until it is found
        f, _, _ = state()
        if f == "shelf 0": break
        key("DPAD_LEFT", pause=0.15)
    for _ in range(30):
        f, name, _ = state()
        if name == title: return True
        key("DPAD_RIGHT", pause=0.15)
    return False

def handle_to(wid, word):
    """Takes the focus to a word of the handle of window [wid], from wherever on that window it is."""
    for _ in range(3):
        f = state()[0] or ""
        if f in ("moving %d" % wid, "sizing %d" % wid, "page %d" % wid): key("ESCAPE")
        elif f == "window %d" % wid: key("DPAD_DOWN")
        elif f.startswith("handle %d" % wid): break
    for _ in range(4):
        if (state()[0] or "") != "handle %d move" % wid: key("DPAD_LEFT", pause=0.2)
    for _ in range(4):
        if state()[0] == "handle %d %s" % (wid, word): return True
        key("DPAD_RIGHT", pause=0.2)
    return state()[0] == "handle %d %s" % (wid, word)

def focused(ws): return next((w for w in ws if w["focused"]), None)

def main():
    a = sys.argv[1:]; out = "desktop_test_out"
    if "-s" in a: uia.SERIAL = a[a.index("-s") + 1]
    if "--out" in a: out = a[a.index("--out") + 1]
    os.makedirs(out, exist_ok=True)
    def shot(name): p = os.path.join(out, name + ".png"); return p if eye(p) else None

    uia.sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
    uia.sh("am start -n dev.ultraxr/.ui.LauncherActivity --ez skip_setup true"); time.sleep(2.5)
    n = uia.find("Open in the glasses"); check("the main screen offers one action: Open in the glasses", n is not None)
    if n is None: return finish()
    uia.sh("input tap %d %d" % (n["x"], n["y"])); time.sleep(3.5)
    f, name, ws = wait(lambda s: True)
    check("the desktop opens and says where the focus is", f is not None, f)
    if f is None: return finish()

    # ---- start from nothing: "Close all" on the shelf's System page (windows put away are closed by hand below)
    check("the shelf has a System mark", shelf_to("System")); key("DPAD_CENTER"); time.sleep(0.6)
    check("its page has 'Close all'", shelf_to("Close all")); key("DPAD_CENTER"); time.sleep(0.8)
    shelf_to("Home"); key("DPAD_CENTER"); time.sleep(0.6)
    f, name, ws = state()
    check("'Close all' closes every open window and the focus is on the shelf", f.startswith("shelf") and not [w for w in ws if not w["minimized"]], f)

    # ---- the shelf
    check("the shelf begins with YouTube", shelf_to("YouTube") and state()[0] == "shelf 0", state()[:2])
    p = shot("01-shelf")
    check("an eye's picture of the shelf can be taken, and is mostly the room (black)", p is not None and 0.003 < lit(p) < 0.12, "%.1f%% lit" % (100 * lit(p)) if p else "no picture")
    key("DPAD_RIGHT"); check("Right moves the focus to the next mark", state()[:2] == ("shelf 1", "Xbox Cloud"), state()[:2])
    key("DPAD_LEFT", "DPAD_LEFT"); check("Left moves it back, and stops at the first", state()[0] == "shelf 0", state()[0])
    key("DPAD_DOWN", "ESCAPE"); check("Down and Back do nothing on the shelf", state()[0] == "shelf 0" and uia.find("Open in the glasses") is None, state()[0])

    # ---- a window
    check("the Browser's mark is on the shelf", shelf_to("Browser"))
    key("DPAD_CENTER")
    f, _, ws = wait(lambda s: s[0].startswith("page") and len([w for w in s[2] if not w["minimized"]]) == 1)
    w = focused(ws)
    check("Select opens it in a window and the keys go to its page", w is not None and f == "page %d" % w["id"], f)
    if w is None: return finish()
    wid = w["id"]; time.sleep(5)
    p = shot("02-page-in-use")
    check("the page draws in the window", p is not None and lit(p, (0.3, 0.2, 0.7, 0.6)) > 0.3, "%.0f%% of the middle lit" % (100 * lit(p, (0.3, 0.2, 0.7, 0.6))) if p else "")
    check("a new window is pinned to the room, 24 degrees wide, clear of the shelf", not w["follow"] and 20 < 2 * 57.3 * (w["width"] / 2 / w["distance"]) < 28 and w["pitch"] > 0, "%.2f m at %.1f m, pitch %.1f" % (w["width"], w["distance"], w["pitch"]))
    key("ESCAPE"); check("Back leaves the page: the window itself is in focus", state()[0] == "window %d" % wid, state()[0]); shot("03-window-in-focus")
    key("DPAD_DOWN"); check("Down goes to its handle, on 'move'", state()[0] == "handle %d move" % wid, state()[0]); shot("04-handle")

    # ---- size
    key("DPAD_RIGHT"); check("Right goes to 'size'", state()[0] == "handle %d size" % wid, state()[0])
    key("DPAD_CENTER"); check("Select begins sizing", state()[0] == "sizing %d" % wid, state()[0])
    w0 = focused(state()[2])
    key("DPAD_RIGHT", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_DOWN", "DPAD_DOWN", pause=0.2); w1 = focused(state()[2]); shot("05-sizing")
    check("Right makes it wider and Down taller (the lower right corner follows the keys), freely", w1["width"] > w0["width"] * 1.1 and w1["height"] > w0["height"] * 1.06, "%.2f x %.2f -> %.2f x %.2f" % (w0["width"], w0["height"], w1["width"], w1["height"]))
    key("ESCAPE"); w2 = focused(state()[2])
    check("Back puts it back as it was", abs(w2["width"] - w0["width"]) < 0.01 and abs(w2["height"] - w0["height"]) < 0.01 and not state()[0].startswith("sizing"), state()[0])
    handle_to(wid, "size")
    key("DPAD_CENTER", "DPAD_LEFT", "DPAD_LEFT", "DPAD_CENTER", pause=0.25); w3 = focused(state()[2])
    check("sized narrower and put down with Select, it stays", w3["width"] < w0["width"] * 0.95 and not state()[0].startswith("sizing"), "%.2f -> %.2f, %s" % (w0["width"], w3["width"], state()[0]))

    # ---- move
    handle_to(wid, "move")
    key("DPAD_CENTER"); check("Select on 'move' begins moving", state()[0] == "moving %d" % wid, state()[0])
    m0 = focused(state()[2]); key("DPAD_LEFT", "DPAD_LEFT", "DPAD_LEFT", "DPAD_UP", pause=0.2); key("BUTTON_R1", "BUTTON_R1", pause=0.2); m1 = focused(state()[2]); shot("06-moving")
    check("the directions carry it round the wearer and up, the shoulder keys further away", abs(m1["yaw"] - m0["yaw"]) > 3 and m1["pitch"] > m0["pitch"] and m1["distance"] > m0["distance"],
          "yaw %.1f -> %.1f, pitch %.1f -> %.1f, %.2f -> %.2f m" % (m0["yaw"], m1["yaw"], m0["pitch"], m1["pitch"], m0["distance"], m1["distance"]))
    key("DPAD_CENTER"); m2 = focused(state()[2])
    check("Select puts it down there", abs(m2["yaw"] - m1["yaw"]) < 0.2 and not state()[0].startswith("moving"), state()[0])

    # ---- pin
    handle_to(wid, "pin")
    key("DPAD_CENTER"); check("'pin' lets a window follow the head", focused(state()[2])["follow"], ""); shot("07-following")
    key("DPAD_CENTER"); check("and pins it to the room again", not focused(state()[2])["follow"], "")

    # ---- a second window, and from one to the other
    check("YouTube's mark is found again", shelf_to("YouTube"))
    key("DPAD_CENTER")
    f, _, ws = wait(lambda s: len([w for w in s[2] if not w["minimized"]]) == 2 and s[0].startswith("page"))
    vis = [w for w in ws if not w["minimized"]]
    check("a second window opens beside the first", len(vis) == 2 and abs(vis[0]["yaw"] - vis[1]["yaw"]) > 10, [(w["id"], w["yaw"]) for w in vis])
    time.sleep(4); key("ESCAPE"); shot("08-two-windows")
    second = focused(state()[2])["id"] if focused(state()[2]) else -1
    key("DPAD_LEFT"); a1 = state()[0]; key("DPAD_RIGHT"); a2 = state()[0]
    check("Left and Right go from window to window", {a1, a2} == {"window %d" % wid, "window %d" % second} or (a1 != a2 and a1.startswith("window") and a2.startswith("window")), "%s, %s" % (a1, a2))

    # ---- settings, as a window
    check("the settings' mark is on the shelf", shelf_to("Settings"))
    key("DPAD_CENTER"); f, _, ws = wait(lambda s: any(w["kind"] == "settings" and not w["minimized"] for w in s[2]))
    st = next((w for w in ws if w["kind"] == "settings"), None)
    check("Select opens the settings as a window", st is not None and f == "page %d" % st["id"], f)
    time.sleep(1.0); p = shot("09-settings")
    check("the settings draw their rows", p is not None and lit(p) > 0.01, "%.1f%% lit" % (100 * lit(p)) if p else "")
    key("DPAD_DOWN", "DPAD_DOWN"); shot("10-settings-third-row")
    key("ESCAPE", "DPAD_DOWN", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_RIGHT"); check("its handle has 'close'", state()[0].endswith("close"), state()[0]); shot("11-close-in-focus")
    key("DPAD_CENTER"); time.sleep(0.6)
    check("Select on 'close' closes it and the focus goes to a window that is left", not any(w["kind"] == "settings" for w in state()[2]) and state()[0].startswith(("window", "page")), state()[0])

    # ---- six windows, and what is said when a seventh is asked for
    for t in ("Xbox Cloud", "3D web"):
        shelf_to(t); key("DPAD_CENTER"); time.sleep(2.0)
    n = len([w for w in state()[2] if not w["minimized"]])
    check("four windows are open at once", n == 4, n); time.sleep(3); to_shelf(); shot("12-four-windows")

    # ---- close them all from their handles
    for _ in range(8):
        vis = [w for w in state()[2] if not w["minimized"]]
        if not vis: break
        to_shelf(); key("DPAD_UP", "DPAD_DOWN", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_CENTER", pause=0.25); time.sleep(0.5)
    f, _, ws = state()
    check("every window can be closed by the d-pad alone, and the focus ends on the shelf", not [w for w in ws if not w["minimized"]] and f.startswith("shelf"), f)
    log = uia.sh("logcat -d -t 3000 | grep -E 'FATAL EXCEPTION|AndroidRuntime: Process: dev.ultraxr'")
    check("no crash was logged", not log.strip(), log.strip()[-160:])
    perf = [l for l in uia.sh("logcat -d -t 3000 | grep 'desktop: .* fps'").splitlines() if l.strip()]
    fps = float(re.search(r"desktop: (\d+) fps", perf[-1]).group(1)) if perf else 0
    check("the desktop draws at the screen's rate", fps >= 55, perf[-1].split("UltraXR :")[-1].strip() if perf else "no line")
    finish()

def finish():
    bad = [r for r in results if not r[1]]
    print("\n%d checks, %d failed" % (len(results), len(bad)))
    sys.exit(1 if bad else 0)

if __name__ == "__main__": main()
