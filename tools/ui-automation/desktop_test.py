#!/usr/bin/env python3
"""The spatial desktop, driven with the keys of a d-pad on a phone over adb (docs/INTERFACE.md).

  python desktop_test.py -s SERIAL [--out DIR]

Opens the desktop on the phone (no glasses needed), presses the keys a d-pad or a gamepad sends, and checks what
they did through the state the screen exposes for accessibility (the focus, whether the home panel or the dock is
shown, and each window's place, size and flags). At every step it also saves the picture one eye of the glasses
would get (a debug build draws it on request, at the glasses' own field of view: DesktopRenderer.eyeCapture), so
the look can be judged without the glasses on. This covers the interface; it says nothing about tracking, and
nothing about how it looks through the glasses' own optics.

It closes the windows that are open when it starts, and leaves none behind.
"""
import os, re, subprocess, sys, time
import uia

results = []
def check(name, ok, detail=""):
    results.append((name, bool(ok), detail)); print(("PASS  " if ok else "FAIL  ") + name + ("  [" + str(detail) + "]" if detail else ""), flush=True)

def state():
    """(focus, name of the tile or mark in focus, windows, "panel:SECTION" or "dock") from the status line's description."""
    for n in uia.nodes():
        d = n["desc"]
        if not d.startswith("focus;"): continue
        parts = d.split("|"); f = parts[0].split(";"); ws = []
        for part in parts[1:]:
            g = part.split(";")
            if len(g) < 13: continue
            ws.append(dict(id=int(g[0]), title=g[1], follow=g[2] == "true", minimized=g[3] == "true", maximized=g[4] == "true", fullscreen=g[5] == "true",
                           focused=g[6] == "true", yaw=float(g[7]), pitch=float(g[8]), width=float(g[9]), distance=float(g[10]), height=float(g[11]), kind=g[12]))
        return f[1], (f[2] if len(f) > 2 else ""), ws, (f[3] if len(f) > 3 else "")
    return None, None, [], ""

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

def visible(ws): return [w for w in ws if not w["minimized"]]
def focused(ws): return next((w for w in ws if w["focused"]), None)

def to_shelf():
    """Back until the focus is on the panel or the dock (Back: page -> window -> shelf). A keyboard's Escape is Back; the phone's own Back key would leave the screen from there."""
    for _ in range(4):
        if (state()[0] or "").startswith("shelf"): return
        key("ESCAPE")

def dock_to(title):
    """Walks the dock's focus to the mark called [title] (the dock is shown while a window is open)."""
    to_shelf()
    for _ in range(30):
        if state()[0] == "shelf 0": break
        key("DPAD_LEFT", pause=0.15)
    for _ in range(30):
        if state()[1] == title: return True
        key("DPAD_RIGHT", pause=0.15)
    return False

def side_to(name):
    """On the home panel: the focus to the sidebar's entry [name] (home, games, apps, windows, settings)."""
    to_shelf()
    for _ in range(3):      # the sections are the top row: up from the covers, and from the actions by way of the covers
        if (state()[1] or "").startswith("side:"): break
        key("DPAD_UP", pause=0.2)
    key("DPAD_LEFT", "DPAD_LEFT", "DPAD_LEFT", "DPAD_LEFT", pause=0.15)
    for _ in range(5):
        if state()[1] == "side:" + name: return True
        key("DPAD_RIGHT", pause=0.2)
    return state()[1] == "side:" + name

def row_to(name, way="DPAD_RIGHT"):
    """Along a row of the panel until the tile called [name] is in focus."""
    for _ in range(24):
        if state()[1] == name: return True
        before = state()[0]; key(way, pause=0.2)
        if state()[0] == before: break
    return state()[1] == name

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

def close_everything():
    """Every window closed, open or put away: "Close all" on the dock's System page or in the panel's Windows section."""
    for _ in range(3):
        f, name, ws, mode = state()
        if not ws: return True
        if mode == "dock":
            dock_to("System"); key("DPAD_CENTER"); time.sleep(0.6); dock_to("Close all"); key("DPAD_CENTER"); time.sleep(1.0)
        else:
            side_to("windows"); key("DPAD_CENTER"); time.sleep(0.8); key("DPAD_DOWN", "DPAD_DOWN"); row_to("sys:closeall"); key("DPAD_CENTER"); time.sleep(1.0)
    return not state()[2]

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
    f, name, ws, mode = wait(lambda s: True)
    check("the desktop opens and says where the focus is", f is not None, "%s, %s" % (f, mode))
    if f is None: return finish()

    # ---- start from nothing
    check("every window of earlier days can be closed with 'Close all'", close_everything(), [w["id"] for w in state()[2]])
    side_to("home"); key("DPAD_CENTER", "DPAD_DOWN"); time.sleep(0.5)

    # ---- the home panel
    f, name, ws, mode = state()
    check("with no window open the home panel is shown", mode == "panel:home" and f.startswith("shelf"), mode)
    check("under the sections is the wall of covers, the focus on the one in the middle", (name or "").startswith("item:"), name)
    p = shot("01-home")
    check("an eye's picture of the home can be taken, and the wall fills its middle", p is not None and lit(p, (0.1, 0.25, 0.9, 0.7)) > 0.5, "%.0f%% lit" % (100 * lit(p, (0.1, 0.25, 0.9, 0.7))) if p else "no picture")
    was = name; key("DPAD_RIGHT"); check("Right turns the wall to the next cover", (state()[1] or "").startswith("item:") and state()[1] != was, state()[1])
    key("DPAD_LEFT"); check("Left comes back", state()[1] == was, state()[1])
    key("DPAD_DOWN"); check("Down goes to the actions under the wall", state()[1] == "sys:recentre", state()[1])
    key("DPAD_LEFT"); check("with the room to their left", state()[1] == "room", state()[1])
    key("DPAD_UP"); check("Up comes back to the cover in the middle", state()[1] == was, state()[1])
    key("DPAD_UP"); check("Up again goes to the sections, on the one shown", state()[1] == "side:home", state()[1])
    key("DPAD_RIGHT", "DPAD_CENTER"); time.sleep(0.8); check("Right and Select show the games", state()[3] == "panel:games" and state()[1] == "side:games", state()[1:4:2]); shot("02-games")
    key("DPAD_RIGHT", "DPAD_CENTER"); time.sleep(1.5); check("the phone's apps are a section too", state()[3] == "panel:apps", state()[3]); shot("03-apps")
    key("ESCAPE"); check("Back does nothing on the home, and the screen is not left", state()[3] == "panel:apps" and uia.find("Open in the glasses") is None, state()[3])
    check("Home is the first of the sections", side_to("home")); key("DPAD_CENTER"); time.sleep(0.6)

    # ---- a window
    key("DPAD_DOWN"); check("the wall has the Browser, and it goes round", row_to("item:Browser"), state()[1])
    key("DPAD_CENTER")
    f, _, ws, mode = wait(lambda s: s[0].startswith("page") and len(visible(s[2])) == 1)
    w = focused(ws)
    check("Select opens it in a window, the keys go to its page, and the panel gives way to the dock", w is not None and f == "page %d" % w["id"] and mode == "dock", "%s, %s" % (f, mode))
    if w is None: return finish()
    wid = w["id"]; time.sleep(5)
    p = shot("04-page-in-use")
    check("the page draws in the window", p is not None and lit(p, (0.3, 0.2, 0.7, 0.6)) > 0.3, "%.0f%% of the middle lit" % (100 * lit(p, (0.3, 0.2, 0.7, 0.6))) if p else "")
    check("a new window is pinned to the room, straight ahead, 24 degrees wide", not w["follow"] and abs(w["yaw"]) < 1 and 20 < 2 * 57.3 * (w["width"] / 2 / w["distance"]) < 28 and w["pitch"] > 0, "%.2f m at %.1f m, yaw %.1f, pitch %.1f" % (w["width"], w["distance"], w["yaw"], w["pitch"]))
    key("ESCAPE"); check("Back leaves the page: the window itself is in focus", state()[0] == "window %d" % wid, state()[0]); shot("05-window-in-focus")
    key("DPAD_DOWN"); check("Down goes to its handle, on 'move'", state()[0] == "handle %d move" % wid, state()[0]); shot("06-handle")

    # ---- size
    key("DPAD_RIGHT"); check("Right goes to 'size'", state()[0] == "handle %d size" % wid, state()[0])
    key("DPAD_CENTER"); check("Select begins sizing", state()[0] == "sizing %d" % wid, state()[0])
    w0 = focused(state()[2])
    key("DPAD_RIGHT", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_DOWN", "DPAD_DOWN", pause=0.2); w1 = focused(state()[2]); shot("07-sizing")
    check("Right makes it wider and Down taller (the lower right corner follows the keys), freely", w1["width"] > w0["width"] * 1.1 and w1["height"] > w0["height"] * 1.06, "%.2f x %.2f -> %.2f x %.2f" % (w0["width"], w0["height"], w1["width"], w1["height"]))
    key("ESCAPE"); w2 = focused(state()[2])
    check("Back puts it back as it was", abs(w2["width"] - w0["width"]) < 0.01 and abs(w2["height"] - w0["height"]) < 0.01 and not state()[0].startswith("sizing"), state()[0])
    handle_to(wid, "size")
    key("DPAD_CENTER", "DPAD_LEFT", "DPAD_LEFT", "DPAD_CENTER", pause=0.25); w3 = focused(state()[2])
    check("sized narrower and put down with Select, it stays", w3["width"] < w0["width"] * 0.95 and not state()[0].startswith("sizing"), "%.2f -> %.2f, %s" % (w0["width"], w3["width"], state()[0]))

    # ---- move
    handle_to(wid, "move")
    key("DPAD_CENTER"); check("Select on 'move' begins moving", state()[0] == "moving %d" % wid, state()[0])
    m0 = focused(state()[2]); key("DPAD_LEFT", "DPAD_LEFT", "DPAD_LEFT", "DPAD_UP", pause=0.2); key("BUTTON_R1", "BUTTON_R1", pause=0.2); m1 = focused(state()[2]); shot("08-moving")
    check("the directions carry it round the wearer and up, the shoulder keys further away", abs(m1["yaw"] - m0["yaw"]) > 3 and m1["pitch"] > m0["pitch"] and m1["distance"] > m0["distance"],
          "yaw %.1f -> %.1f, pitch %.1f -> %.1f, %.2f -> %.2f m" % (m0["yaw"], m1["yaw"], m0["pitch"], m1["pitch"], m0["distance"], m1["distance"]))
    key("DPAD_CENTER"); m2 = focused(state()[2])
    check("Select puts it down there", abs(m2["yaw"] - m1["yaw"]) < 0.2 and not state()[0].startswith("moving"), state()[0])

    # ---- pin
    handle_to(wid, "pin")
    key("DPAD_CENTER"); check("'pin' lets a window follow the head", focused(state()[2])["follow"], "")
    key("DPAD_CENTER"); check("and pins it to the room again", not focused(state()[2])["follow"], "")

    # ---- the dock, a second window, and from one to the other
    to_shelf(); f, name, _, mode = state()
    check("with a window open the dock is shown, and Back from the window is on its first mark, Home", mode == "dock" and f == "shelf 0" and name == "Home", "%s %s %s" % (f, name, mode)); shot("09-dock")
    check("YouTube is on the dock", dock_to("YouTube"))
    key("DPAD_CENTER")
    f, _, ws, _ = wait(lambda s: len(visible(s[2])) == 2 and s[0].startswith("page"))
    vis = visible(ws)
    check("a second window opens beside the first", len(vis) == 2 and abs(vis[0]["yaw"] - vis[1]["yaw"]) > 10, [(w["id"], w["yaw"]) for w in vis])
    time.sleep(4); key("ESCAPE"); shot("10-two-windows")
    key("DPAD_LEFT"); a1 = state()[0]; key("DPAD_RIGHT"); a2 = state()[0]
    check("Left and Right go from window to window", a1 != a2 and a1.startswith("window") and a2.startswith("window"), "%s, %s" % (a1, a2))

    # ---- settings, as a window
    check("the settings' mark is on the dock", dock_to("Settings"))
    key("DPAD_CENTER"); f, _, ws, _ = wait(lambda s: any(w["kind"] == "settings" and not w["minimized"] for w in s[2]))
    st = next((w for w in ws if w["kind"] == "settings"), None)
    check("Select opens the settings as a window, straight ahead and nearer than the others", st is not None and f == "page %d" % st["id"] and abs(st["yaw"]) < 1 and st["distance"] < 1.9, "%s, %s" % (f, (st or {}).get("distance")))
    time.sleep(1.0); p = shot("11-settings")
    check("the settings draw their rows", p is not None and lit(p) > 0.01, "%.1f%% lit" % (100 * lit(p)) if p else "")
    key("ESCAPE", "DPAD_DOWN", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_RIGHT"); check("its handle has 'close'", state()[0].endswith("close"), state()[0])
    key("DPAD_CENTER"); time.sleep(0.6)
    check("Select on 'close' closes it and the focus goes to a window that is left", not any(w["kind"] == "settings" for w in state()[2]) and state()[0].startswith(("window", "page")), state()[0])

    # ---- home puts the windows away; what was put away comes back in front
    check("Home is the dock's first mark", dock_to("Home")); key("DPAD_CENTER"); time.sleep(1.0)
    f, name, ws, mode = state()
    check("Select on it puts the windows away and shows the home panel", mode == "panel:home" and len(ws) == 2 and not visible(ws), "%s, %d windows, %d in view" % (mode, len(ws), len(visible(ws)))); shot("12-home-with-windows-away")
    side_to("windows"); key("DPAD_CENTER"); time.sleep(0.8); key("DPAD_DOWN")
    check("the Windows section lists them", (state()[1] or "").startswith("win:"), state()[1]); shot("13-windows")
    key("DPAD_CENTER"); f, _, ws, mode = wait(lambda s: len(visible(s[2])) == 1)
    back = focused(ws)
    check("Select on one brings it back, in front of the wearer", back is not None and abs(back["yaw"]) < 12 and mode == "dock", "yaw %.1f, %s" % (back["yaw"] if back else 99, mode))

    # ---- close what is open from its handle; the rest with Close all
    if back is not None and handle_to(back["id"], "close"): key("DPAD_CENTER"); time.sleep(0.8)
    f, name, ws, mode = state()
    check("closing the last window in view from its handle shows the home panel again", mode.startswith("panel") and f.startswith("shelf") and not visible(ws), "%s %s" % (f, mode))
    check("and 'Close all' leaves nothing behind", close_everything(), [w["id"] for w in state()[2]])
    side_to("home"); key("DPAD_CENTER")
    log = uia.sh("logcat -d -t 4000 | grep -E 'FATAL EXCEPTION|AndroidRuntime: Process: dev.ultraxr' | grep -v adbd")
    check("no crash was logged", not log.strip(), log.strip()[-160:])
    perf = [l for l in uia.sh("logcat -d -t 4000 | grep 'desktop: .* fps'").splitlines() if l.strip()]
    found = [m for m in (re.search(r"desktop: (\d+) fps", line) for line in perf) if m]; fps = float(found[-1].group(1)) if found else 0
    check("the desktop draws at the screen's rate", fps >= 55, perf[-1].split("UltraXR :")[-1].strip() if perf else "no line")
    finish()

def finish():
    bad = [r for r in results if not r[1]]
    print("\n%d checks, %d failed" % (len(results), len(bad)))
    sys.exit(1 if bad else 0)

if __name__ == "__main__": main()
