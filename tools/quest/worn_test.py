#!/usr/bin/env python3
# Ultra XR: drive one run of the Quest API test while somebody wears the glasses, cue them, and keep what it showed.
# SPDX-License-Identifier: GPL-3.0-or-later
"""
  python tools/quest/worn_test.py SERIAL [--room 15] [--hand 10] [--draw 40] [--vulkan] [--out DIR]

Starts the test app (dev.ultraxr.questlab) and follows its log. The phone buzzes as a cue for the wearer:

  one buzz     hold up the LEFT hand and pinch a few times
  two buzzes   now the RIGHT hand
  three        the room view is on: look at the walls, the floor, a table; hold a hand up

While the room view is drawn (or, with --vulkan, the eye tints) pictures of what is on the glasses are taken.
Everything goes into DIR (default C:/uvio/quest_runs/worn-<time>): the test's report, the layer's log, the
pictures, and the left eye's picture as the test itself drew it.
"""
import os, subprocess, sys, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import quest_diag as qd

PKG = "dev.ultraxr.questlab"

def main():
    a = sys.argv[1:]
    def opt(name, default):
        if name in a: i = a.index(name); v = a[i + 1]; del a[i:i + 2]; return v
        return default
    room = int(opt("--room", "15")); hand = int(opt("--hand", "10")); draw = int(opt("--draw", "40")); out = opt("--out", None)
    vulkan = "--vulkan" in a; a = [x for x in a if not x.startswith("--")]
    if not a: print(__doc__); return 2
    serial = a[0]
    out = out or os.path.join("C:/uvio/quest_runs" if os.path.isdir("C:/uvio") else os.path.expanduser("~"), "worn-%s%s" % (time.strftime("%Y%m%d-%H%M%S"), "-vulkan" if vulkan else ""))
    os.makedirs(out, exist_ok=True)
    if not qd.glasses_display(serial): print("the glasses are not attached"); return 1
    buzz = lambda n: [(qd.sh(serial, "cmd vibrator_manager synced -f oneshot 250"), time.sleep(0.45)) for _ in range(n)]
    qd.sh(serial, "am force-stop " + PKG); qd.adb(serial, "logcat", "-c")
    report = "quest_api_report_vulkan.txt" if vulkan else "quest_api_report.txt"
    qd.sh(serial, "rm -f /sdcard/Android/data/%s/files/%s /sdcard/Android/data/%s/files/room_view_left.ppm" % (PKG, report, PKG))
    # the hand phase reads 6 frames a 'second' asked for: at the glasses' 60 pictures a second, ten of those are one real second
    extras = "--ei room_seconds %d --ei seconds %d --ei draw_seconds %d%s" % (room, hand * 10, draw, " --ez vulkan true" if vulkan else "")
    qd.sh(serial, "am start --display 0 -n %s/.LabActivity %s" % (PKG, extras))
    print("started; look around the room for the first %d seconds" % (room + 3))
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    log = subprocess.Popen(["adb", "-s", serial, "logcat", "-v", "raw", "-s", "QuestLab"], stdout=subprocess.PIPE, env=env)
    done = threading.Event(); shots = []
    def pictures(n, over):
        for i in range(n):
            time.sleep(over / (n + 1));
            if done.is_set(): return
            p = os.path.join(out, "glasses-%d.png" % (len(shots) + 1))
            if qd.capture(serial, p): shots.append(p); print("  picture of the glasses saved: %s" % os.path.basename(p))
    if vulkan: threading.Thread(target=pictures, args=(3, room + 2 * hand + 6), daemon=True).start()
    t0 = time.time(); limit = room + 2 * hand + draw + 60
    for raw in log.stdout:
        l = raw.decode("utf-8", "replace").strip()
        if "CUE reading the LEFT" in l: print("%5.0f s  CUE: left hand" % (time.time() - t0)); buzz(1)
        elif "CUE reading the RIGHT" in l: print("%5.0f s  CUE: right hand" % (time.time() - t0)); buzz(2)
        elif "CUE the room view" in l: print("%5.0f s  CUE: room view" % (time.time() - t0)); buzz(3); threading.Thread(target=pictures, args=(5, draw), daemon=True).start()
        elif " checks, " in l: print("%5.0f s  %s" % (time.time() - t0, l)); break
        if time.time() - t0 > limit: print("gave up after %d s" % limit); break
    done.set(); log.kill(); time.sleep(1.5)
    text = qd.sh(serial, "cat /sdcard/Android/data/%s/files/%s" % (PKG, report)); open(os.path.join(out, report), "w", encoding="utf-8").write(text)
    open(os.path.join(out, "layer_diag.txt"), "w", encoding="utf-8").write(qd.sh(serial, "cat /sdcard/Android/data/%s/files/uxr_quest_diag.txt" % PKG))
    open(os.path.join(out, "logcat.txt"), "w", encoding="utf-8").write(qd.adb(serial, "logcat", "-d", "-v", "threadtime", timeout=120))
    if not vulkan:
        qd.adb(serial, "pull", "/sdcard/Android/data/%s/files/room_view_left.ppm" % PKG, os.path.join(out, "room_view_left.ppm"))
    for l in text.splitlines():
        if not l.startswith("PASS  offered"): print(l)
    print("files: " + out)
    return 0

if __name__ == "__main__": sys.exit(main())
