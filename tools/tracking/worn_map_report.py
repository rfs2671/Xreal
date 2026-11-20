#!/usr/bin/env python3
"""After a worn session: what did position tracking and the saved map of the room do? From the app's own log.

  python worn_map_report.py SERIAL [OUT_DIR]      pulls files/log.txt (and log.1.txt) from the phone and reads them
  python worn_map_report.py LOG.txt [LOG.txt ...] reads logs already pulled

Nothing is measured here that the app did not write down itself; it is a reading aid for the worn test of the first
milestone (learn the room, place three windows, walk, close the app, open it again). It prints, in order of time:

  - every start of the map (made afresh, found in, added to) and when the head was first found in it
  - the ten-second status lines' last word: places, pictures looked at and recognised, time a picture takes, the
    estimator's time for a frame, how far the map was last seen from where it is held, small steps and corrections
    made at once
  - every large correction (the world glided back onto the map), with its size: these a wearer can see
  - windows and room surfaces put back from the saved room; anchors are in the game's own uxr_quest_diag.txt (ANCHORS)
  - every loss of position ("rotation only") with how long it lasted, and every restart of the estimator with its reason
"""
import os, re, subprocess, sys

STAMP = re.compile(r"^((?:\d{4}-)?(?:\d\d-\d\d )?\d\d:\d\d:\d\d(?:\.\d+)?)\s+(.*)$")

def seconds(stamp):
    h, m, s = stamp.split(" ")[-1].split(":"); return int(h) * 3600 + int(m) * 60 + float(s)

def main():
    a = sys.argv[1:]
    if not a: print(__doc__); return 2
    logs = []
    if os.path.isfile(a[0]): logs = a
    else:
        out = a[1] if len(a) > 1 else "."; os.makedirs(out, exist_ok=True)
        env = dict(os.environ, MSYS_NO_PATHCONV="1")
        for name in ("log.1.txt", "log.txt"):
            dst = os.path.join(out, name)
            r = subprocess.run(["adb", "-s", a[0], "pull", "/sdcard/Android/data/dev.ultraxr/files/" + name, dst], capture_output=True, env=env)
            if r.returncode == 0: logs.append(dst)
        if not logs: print("no log could be pulled from " + a[0]); return 1
    lines = []
    for p in logs: lines += open(p, encoding="utf-8", errors="replace").read().splitlines()
    lost_at = None; losses = []; restarts = []; status = None; shown = 0
    for l in lines:
        m = STAMP.match(l); stamp, text = (m.group(1), m.group(2)) if m else ("", l)
        if "room map:" in text:
            if re.search(r"room map: (making|finding|growing), ", text): status = (stamp, text); continue
            print(stamp, text); shown += 1
        elif "room memory:" in text or "put back where they stood" in text or "map test:" in text: print(stamp, text); shown += 1
        elif "6DoF position not trusted" in text: lost_at = stamp
        elif "6DoF position trusted" in text and lost_at:
            try: losses.append((lost_at, seconds(stamp) - seconds(lost_at)))
            except Exception: losses.append((lost_at, float("nan")))
            lost_at = None
        elif "restart" in text and ("6DoF" in text or "estimator" in text): restarts.append((stamp, text))
    if status: print("last status:", status[0], status[1])
    if not shown and not status: print("the log has no word of the room map: was the room scan on, and is this build new enough?")
    print("position lost %d times%s" % (len(losses), (": " + ", ".join("%s for %.1f s" % (s, d) for s, d in losses[:20])) if losses else ""))
    if lost_at: print("  and still lost at the end, since " + lost_at)
    print("estimator restarts: %d" % len(restarts))
    for s, t in restarts[:20]: print("  ", s, t[:160])
    return 0

if __name__ == "__main__": sys.exit(main())
