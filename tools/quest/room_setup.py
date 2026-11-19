#!/usr/bin/env python3
# Ultra XR: run the room set-up while somebody wears the glasses, and say what was kept.
# SPDX-License-Identifier: GPL-3.0-or-later
"""
  python tools/quest/room_setup.py SERIAL [--seconds 180] [--forget] [--out DIR]

Starts the test app (dev.ultraxr.questlab) in its room set-up mode. The wearer stands in the room and, for each
corner where a wall meets the floor, LOOKS at it (a white ring on the floor, with a post, is where the middle of
the view meets the floor) and pinches with either hand held up in view, or taps the phone's touchpad. Going
round the room once; looking at the first corner again (the green cross) and pinching closes the room. The phone buzzes once for
every corner taken and three times when the room is closed and kept.

The corners go to the Ultra XR app, which keeps them in files/room_plan.txt and from then on gives applications
these walls, whole, in place of the ones the cameras find; in a later session it places the plan again from the
walls and table tops the cameras see (core/RoomPlan).

--forget makes Ultra XR forget the plan it has (it must be running an XR session for that, so the set-up is started
and the plan is forgotten before anything is marked).
"""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import quest_diag as qd

PKG = "dev.ultraxr.questlab"

def main():
    a = sys.argv[1:]
    def opt(name, default):
        if name in a: i = a.index(name); v = a[i + 1]; del a[i:i + 2]; return v
        return default
    seconds = int(opt("--seconds", "180")); out = opt("--out", None); forget = "--forget" in a
    a = [x for x in a if not x.startswith("--")]
    if not a: print(__doc__); return 2
    serial = a[0]
    out = out or os.path.join("C:/uvio/quest_runs" if os.path.isdir("C:/uvio") else os.path.expanduser("~"), "room-setup-%s" % time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(out, exist_ok=True)
    if not qd.glasses_display(serial): print("the glasses are not attached"); return 1
    buzz = lambda n: [(qd.sh(serial, "cmd vibrator_manager synced -f oneshot 200"), time.sleep(0.35)) for _ in range(n)]
    qd.sh(serial, "am force-stop " + PKG); qd.adb(serial, "logcat", "-c")
    qd.sh(serial, "rm -f /sdcard/Android/data/%s/files/quest_api_report.txt" % PKG)
    qd.sh(serial, "am start --display 0 -n %s/.LabActivity --ei seconds 1 --ei room_seconds 6 --ei setup_seconds %d" % (PKG, seconds))
    print("started; the set-up begins in about 15 seconds (a buzz says so)")
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    log = subprocess.Popen(["adb", "-s", serial, "logcat", "-v", "raw", "-s", "QuestLab"], stdout=subprocess.PIPE, env=env)
    t0 = time.time(); shots = 0; kept = None
    for raw in log.stdout:
        l = raw.decode("utf-8", "replace").strip(); now = time.time() - t0
        if "CUE room set-up" in l:
            print("%5.0f s  set-up is on: look at a corner on the floor and pinch" % now); buzz(2)
            if forget: print("        forgetting the plan Ultra XR has: " + qd.sh(serial, "curl -s 'http://127.0.0.1:8765/room_plan?clear=1' 2>/dev/null || toybox wget -q -O - 'http://127.0.0.1:8765/room_plan?clear=1'").strip()[:120])
        elif "CUE corner" in l:
            print("%5.0f s  %s" % (now, l[4:])); buzz(1)
            p = os.path.join(out, "glasses-%d.png" % (shots + 1))
            if qd.capture(serial, p): shots += 1
        elif "CUE room closed" in l: print("%5.0f s  %s" % (now, l[4:]))
        elif "CUE room kept" in l or "CUE room NOT kept" in l:
            kept = "NOT" not in l; print("%5.0f s  %s" % (now, l[4:])); buzz(3 if kept else 5)
            time.sleep(2); qd.capture(serial, os.path.join(out, "glasses-closed.png"))
        elif " checks, " in l: print("%5.0f s  %s" % (now, l)); break
        if now > seconds + 120: print("gave up after %d s" % (seconds + 120)); break
    log.kill(); time.sleep(1.5)
    open(os.path.join(out, "report.txt"), "w", encoding="utf-8").write(qd.sh(serial, "cat /sdcard/Android/data/%s/files/quest_api_report.txt" % PKG))
    open(os.path.join(out, "logcat.txt"), "w", encoding="utf-8").write(qd.adb(serial, "logcat", "-d", "-v", "threadtime", timeout=120))
    plan = qd.sh(serial, "cat /sdcard/Android/data/dev.ultraxr/files/room_plan.txt 2>/dev/null")
    open(os.path.join(out, "room_plan.txt"), "w", encoding="utf-8").write(plan)
    print("the plan Ultra XR has now:" if plan.strip() else "Ultra XR has no plan"); print(plan.strip())
    print("files: " + out)
    return 0 if kept else 1

if __name__ == "__main__": sys.exit(main())
