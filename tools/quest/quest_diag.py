#!/usr/bin/env python3
# Ultra XR: run a converted Quest game (or any OpenXR app) on the phone and collect what happened.
# SPDX-License-Identifier: GPL-3.0-or-later
"""
  python tools/quest/quest_diag.py PACKAGE [--serial SERIAL] [--seconds 60] [--out DIR] [--no-start] [--capture N]
                                           [--extras "--ei draw_seconds 20"]

Starts the app, lets it run for the given time (or until its process dies), and writes into DIR
(default C:/uvio/quest_runs/<package>-<time>, outside the repository):

  report.txt        the summary: did it start, how far it got, which graphics API, how many frames, every
                    unsupported extension, function and feature it met, every failed OpenXR call, and the crash if
                    there was one (signal, where in OpenXR terms, the top of the stack)
  logcat.txt        everything the phone logged meanwhile
  xr.txt            only the OpenXR side: Ultra XR's layer (UltraXRQuest, UltraXRMeta), the dispatcher
                    (OpenXRDispatch), the loader and the runtime's client library
  crash.txt         Android's own crash dump for the process, when there was one
  layer_diag.txt    the layer's own file from the game's folder
  glasses-N.png     with --capture N: N pictures of what is on the glasses, spread over the run

While it runs the dispatcher's detailed logging is on (the debug property debug.sys.ovrport.verboselogging); it is
set back afterwards. The app is started the way Ultra XR's launcher starts it: its Quest VR activity (or OpenXR
immersive activity, or launcher activity) on the phone's own display.

Nothing in the game is changed and nothing it checks is answered for it: this only watches.
"""
import os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
XR_TAGS = ("UltraXRQuest", "UltraXRMeta", "OpenXRDispatch", "OpenXR-Loader", "OpenXR", "monado", "Monado", "QuestLab", "hello_xr")

def adb(serial, *args, timeout=60, binary=False):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    try: r = subprocess.run(cmd, capture_output=True, timeout=timeout, env=env)
    except subprocess.TimeoutExpired: return b"" if binary else ""
    return r.stdout if binary else (r.stdout + r.stderr).decode("utf-8", "replace")

def sh(serial, command, timeout=60): return adb(serial, "shell", command, timeout=timeout)

def glasses_display(serial):
    """The id screencap wants for the display that is not the phone's own, or None."""
    out = sh(serial, "dumpsys SurfaceFlinger --display-id")
    for m in re.finditer(r"Display (\d+) \(HWC display (\d+)\)", out):
        if m.group(2) != "0": return m.group(1)
    return None

def capture(serial, path):
    d = glasses_display(serial)
    if not d: return False
    png = adb(serial, "exec-out", "screencap", "-p", "-d", d, binary=True, timeout=30)
    if len(png) < 1000: return False
    open(path, "wb").write(png); return True

def find_activity(serial, pkg):
    for cat in ("com.oculus.intent.category.VR", "org.khronos.openxr.intent.category.IMMERSIVE_HMD", "android.intent.category.LAUNCHER", "android.intent.category.INFO"):
        out = sh(serial, "cmd package resolve-activity --brief -c %s %s" % (cat, pkg)).strip().splitlines()
        if out and "/" in out[-1] and "No activity" not in out[-1]: return out[-1].strip(), cat
    return None, None

def main():
    a = sys.argv[1:]
    def opt(name, default=None):
        if name in a: i = a.index(name); v = a[i + 1]; del a[i:i + 2]; return v
        return default
    serial = opt("--serial"); seconds = int(opt("--seconds", "60")); out = opt("--out"); shots = int(opt("--capture", "0")); extras = opt("--extras", "")
    no_start = "--no-start" in a; a = [x for x in a if not x.startswith("--")]
    if not a: print(__doc__); return 2
    pkg = a[0]
    if not out:
        base = "C:/uvio/quest_runs" if os.path.isdir("C:/uvio") else os.path.join(os.path.expanduser("~"), "ultraxr_quest_runs")
        out = os.path.join(base, "%s-%s" % (pkg, time.strftime("%Y%m%d-%H%M%S")))
    os.makedirs(out, exist_ok=True)
    R = []
    def say(s=""): R.append(s); print(s)

    if "package:" + pkg not in sh(serial, "pm list packages " + pkg).split(): say("NOT INSTALLED  %s is not on the phone" % pkg); return 1
    info = sh(serial, "dumpsys package " + pkg)
    version = re.search(r"versionName=(\S+)", info); say("Run of %s %s, %s" % (pkg, version.group(1) if version else "", time.strftime("%Y-%m-%d %H:%M:%S")))
    runtime = sh(serial, "pidof dev.ultraxr").strip(); glasses = glasses_display(serial)
    say("  Ultra XR running: %s; glasses attached: %s" % ("yes" if runtime else "NO (open Ultra XR first: the game will find no runtime)", "yes" if glasses else "no (the game gets no cameras: no walls, no hands; the phone's screen must stay on)"))
    if not glasses: sh(serial, "input keyevent KEYCODE_WAKEUP")
    was_verbose = sh(serial, "getprop debug.sys.ovrport.verboselogging").strip()
    sh(serial, "setprop debug.sys.ovrport.verboselogging 1")
    activity, cat = find_activity(serial, pkg)
    try:
        if not no_start:
            if not activity: say("NO ACTIVITY  nothing in %s can be started" % pkg); return 1
            sh(serial, "am force-stop " + pkg); adb(serial, "logcat", "-c"); adb(serial, "logcat", "-b", "crash", "-c")
            started = sh(serial, "am start -W --display 0 -n %s %s" % (activity, extras))
            say("  started %s (found by %s)" % (activity, cat.split(".")[-1]))
            if "Error" in started: say("  am start said: " + started.strip().splitlines()[-1])
        t0 = time.time(); died_at = None; pid = None; shot = 0
        while time.time() - t0 < seconds:
            time.sleep(1.0); now = sh(serial, "pidof " + pkg).strip()
            if now and not pid: pid = now.split()[0]
            if pid and not now: died_at = time.time() - t0; break
            if shots and time.time() - t0 >= (shot + 1) * seconds / (shots + 1) and shot < shots:
                shot += 1; ok = capture(serial, os.path.join(out, "glasses-%d.png" % shot)); print("  picture %d of the glasses: %s" % (shot, "saved" if ok else "no glasses display to take it from"))
    finally:
        sh(serial, "setprop debug.sys.ovrport.verboselogging %s" % (was_verbose or "0"))
    log = adb(serial, "logcat", "-d", "-v", "threadtime", timeout=120); open(os.path.join(out, "logcat.txt"), "w", encoding="utf-8").write(log)
    crash = adb(serial, "logcat", "-b", "crash", "-d", timeout=60)
    lines = log.splitlines()
    def of_tag(l, tags): m = re.match(r"\S+ \S+\s+(\d+)\s+\d+ \w (\S+?)\s*: ", l); return bool(m) and m.group(2) in tags
    mine = [l for l in lines if (pid and re.match(r"\S+ \S+\s+%s\s" % pid, l))]
    xr = [l for l in lines if of_tag(l, XR_TAGS) or (pid and re.match(r"\S+ \S+\s+%s\s" % pid, l) and re.search(r"XR_ERROR|xr[A-Z]\w+\(|openxr", l))]
    open(os.path.join(out, "xr.txt"), "w", encoding="utf-8").write("\n".join(xr) + "\n")
    diag = sh(serial, "cat /sdcard/Android/data/%s/files/uxr_quest_diag.txt 2>/dev/null || run-as %s cat uxr_quest_diag.txt 2>/dev/null" % (pkg, pkg))
    open(os.path.join(out, "layer_diag.txt"), "w", encoding="utf-8").write(diag)

    # the runtime's extension list, as the dispatcher printed it: kept for quest_inspect.py
    ext = []
    for l in lines:
        m = re.search(r"OpenXRDispatch: runtime extensions: (.*)", l)
        if m: ext += m.group(1).split()
    if len(ext) > 30: open(os.path.join(HERE, "runtime_extensions.txt"), "w").write("\n".join(sorted(set(ext))) + "\n")

    text = "\n".join(mine) + "\n" + diag
    def grab(pattern, source=None, limit=40):
        seen = [];
        for l in (source if source is not None else lines):
            m = re.search(pattern, l)
            if m and m.group(0) not in seen: seen.append(m.group(0))
        return seen[:limit]
    say(""); say("How far it got")
    say("  process: %s" % ("never appeared" if not pid else "died %.0f s after the start" % died_at if died_at is not None else "still running after %d s" % seconds))
    say("  loader chain: %s" % ("dispatcher in front" if grab(r"open dispatcher .*", mine) else "no dispatcher seen (an ordinary OpenXR app, or the game is not converted)"))
    for label, pat in (("layer", r"GAME  .*"), ("instance", r"xrCreateInstance\(.*"), ("instance failed", r"FAILED  xrCreateInstance.*"), ("session", r"(?:ok|FAILED)  xrCreateSession with .*"), ("first frame", r"FRAMES  .*"), ("last summary", r"SUMMARY .*")):
        g = grab(pat, mine + diag.splitlines());
        if g: say("  %s: %s" % (label, g[-1][:300]))
    if not grab(r"GAME  ", mine + diag.splitlines()): say("  Ultra XR's layer never saw xrCreateInstance: the game did not reach OpenXR (it stopped earlier, or it is not packed: quest_pack.py)")
    states = grab(r"SESSION  state \w+", diag.splitlines());
    if states: say("  session states: %s" % " > ".join(s.split()[-1] for s in states))
    inputs = grab(r"INPUT  .*", diag.splitlines()); [say("  " + i) for i in inputs[-2:]]

    say(""); say("Unsupported things the game met")
    uns = grab(r"UNSUPPORTED (?:EXTENSION|CALL|FEATURE)  .*", diag.splitlines() + mine) + grab(r"OpenXRDispatch: unsupported: .*", mine)
    for u in uns: say("  " + u[:400])
    if not uns: say("  none logged")
    say(""); say("OpenXR calls that failed")
    fails = grab(r"FAILED  .*", diag.splitlines()) + grab(r"XR_ERROR_\w+[^\n]{0,200}", [l for l in mine if "UltraXRQuest" not in l and "bytedance" not in l and "unsupported:" not in l and "QuestLab" not in l])
    # the dispatcher offers every game's Touch bindings under Pico's names too; a runtime that is not Pico's refuses them
    pico = [f for f in fails if "bytedance" in f]; fails = [f for f in fails if "bytedance" not in f]
    for f in fails[:40]: say("  " + f[:400])
    if pico: say("  (not counted: %d refusals of Pico controller profiles the dispatcher tried as aliases; expected on a runtime that is not Pico's)" % len(pico))
    if not fails: say("  none logged")

    say(""); say("Other things that stop games")
    # the game's own lines, without ours and without what Samsung's system libraries say in every process
    own = [l for l in mine if not any(t in l for t in ("UltraXRQuest", "UltraXRMeta", "QuestLab", "OpenXRDispatch", "libpenguin"))]
    for label, pat in (("a library was not found", r"(?:dlopen failed|UnsatisfiedLinkError|library \"[^\"]+\" not found)[^\n]{0,200}"), ("Java crash", r"FATAL EXCEPTION[^\n]*"), ("Meta platform / entitlement", r"(?i)[^\n]{0,80}(?:entitle|ovr_Platform|ovrplatform|OVRPlatform|not entitled)[^\n]{0,160}"),
                       ("OBB or data missing", r"(?i)[^\n]{0,60}(?:\.obb|data\.unity3d|Unable to (?:load|find) )[^\n]{0,160}"), ("Vulkan", r"(?i)[^\n]{0,60}(?:VK_ERROR_\w+|vulkan[^\n]{0,40}(?:fail|error|not supported))[^\n]{0,120}"), ("out of memory", r"[^\n]{0,60}(?:lowmemorykiller|[Oo]ut of memory|\bOOM\b)[^\n]{0,120}")):
        g = grab(pat, own if label != "out of memory" else [l for l in lines if (pkg in l or (pid and pid in l)) and "QuestLab" not in l and "UltraXR" not in l], limit=6)
        for x in g: say("  %s: %s" % (label, x.strip()[:300]))
    am = grab(r"(?:Process %s[^\n]*has died[^\n]*|ANR in %s[^\n]*|Force stopping %s[^\n]*|Killing \d+:%s[^\n]*)" % ((re.escape(pkg),) * 4)); [say("  system: " + x[:300]) for x in am[:6]]

    say(""); say("Crash")
    cl = [l for l in crash.splitlines() if not pid or re.search(r"\s%s\s" % pid, l) or "pid: %s" % pid in l]
    native = [l for l in lines if re.search(r" F DEBUG\s*:| F libc\s*:", l)]
    layer_crash = grab(r"CRASH  .*", diag.splitlines() + lines)
    if layer_crash or native or (died_at is not None and cl):
        open(os.path.join(out, "crash.txt"), "w", encoding="utf-8").write("\n".join(layer_crash + native + cl) + "\n")
        for c in layer_crash: say("  " + c[:500])
        for l in native:
            m = re.search(r"DEBUG\s*: (.*)", l) or re.search(r"libc\s*: (.*)", l); s = m.group(1) if m else l
            if re.search(r"signal \d+|Abort message|Cause:|^\s*#0\d pc|^\s*#1[0-5] pc|Fatal signal", s): say("  " + s.strip()[:300])
        if not native and cl: [say("  " + l[:300]) for l in cl[:25]]
    else: say("  none")
    say(""); say("Files: %s" % out)
    open(os.path.join(out, "report.txt"), "w", encoding="utf-8").write("\n".join(R) + "\n")
    return 0

if __name__ == "__main__": sys.exit(main())
