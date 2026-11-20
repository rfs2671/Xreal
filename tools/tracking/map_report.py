#!/usr/bin/env python3
"""Would a thing placed in one session stand in the same physical place in the next? From two replays of one recording.

  python map_report.py DIR MAKING_TAG FINDING_TAG [FINDING_TAG ...]

DIR holds the app's replay files (tools/pixel/lab.py): the run that made and saved the map of the room ("m_new") and
runs, each after a restart of the app, that looked for the head in it ("m_find"). TAG-notes.txt has the app's word on
where the map lies in its world at each reading ("map-in-app x y z yaw ..."), TAG-app.csv the head in the app's world.

The same recording replayed twice puts the head in the same physical place at the same second. So: take the head's
place at second t of the making run as a thing put down there, turn it into map coordinates as the app would to save
it, bring it back in the finding run through where that run says the map lies, and compare with where the head is at
second t of the finding run. The distance is the error a saved anchor would show there, without any other reference.
It counts everything: the map's own error, the finding, and how much the estimator differs between two runs.
"""
import csv, math, os, re, sys
import numpy as np

LINE = re.compile(r"^([\d.]+) (found|making) map-in-app (-?[\d.]+) (-?[\d.]+) (-?[\d.]+) yaw (-?[\d.]+) off ([\d.]+) m ([\d.]+) deg place (\d+) inliers (\d+) ms (\d+)")

def read(d, tag):
    maps = []; said = []
    for l in open(os.path.join(d, tag + "-notes.txt"), encoding="utf-8", errors="replace"):
        m = LINE.match(l.strip())
        if m: maps.append([float(m.group(1)), float(m.group(3)), float(m.group(4)), float(m.group(5)), math.radians(float(m.group(6))), float(m.group(11))])
        elif "room map" in l: said.append(l.strip())
    head = [(float(r["t"]), float(r["hx"]), float(r["hy"]), float(r["hz"]), int(r["used"])) for r in csv.DictReader(open(os.path.join(d, tag + "-app.csv"))) if r.get("used") not in (None, "")]      # a run cut off ends in half a line
    return np.array(maps), np.array(head), said

def to_app(m, p):      # the map's pose in the app's world (x, y, z, yaw about +y) applied to a point
    c, s = math.cos(m[4]), math.sin(m[4])
    return np.array([c * p[0] + s * p[2] + m[1], p[1] + m[2], -s * p[0] + c * p[2] + m[3]])
def to_map(m, p):
    c, s = math.cos(m[4]), math.sin(m[4]); q = np.array(p) - m[1:4]
    return np.array([c * q[0] - s * q[2], q[1], s * q[0] + c * q[2]])
def at(rows, t):
    i = int(np.searchsorted(rows[:, 0], t)); i = min(max(i, 0), len(rows) - 1)
    if i > 0 and abs(rows[i - 1, 0] - t) < abs(rows[i, 0] - t): i -= 1
    return rows[i]

def main():
    d, making, finding = sys.argv[1], sys.argv[2], sys.argv[3:]
    m1, h1, said1 = read(d, making)
    print("%s: %d readings while making; %s" % (making, len(m1), "; ".join(s.split(" ", 1)[1] for s in said1[-2:])))
    if not len(m1): print("the making run has no map readings"); return 1
    for tag in finding:
        m2, h2, said2 = read(d, tag)
        for s in said2: print("   " + s)
        if not len(m2): print("%s: the head was never found in the saved map" % tag); continue
        yaw = np.degrees(m2[:, 4]); mid = np.median(m2[:, 1:4], axis=0); off = np.linalg.norm(m2[:, 1:4] - mid, axis=1)
        print("%s: first found at %.1f s; %d readings used; a reading takes median %.0f ms; where the map is held moved by at most %.1f cm and %.2f degrees over the run" % (
            tag, m2[0, 0], len(m2), np.median(m2[:, 5]), 100 * off.max(), yaw.max() - yaw.min()))
        errs = []; ts = []
        for t in np.arange(math.ceil(m2[0, 0]) + 1.0, min(h1[-1, 0], h2[-1, 0]), 1.0):
            a = at(h1, t); b = at(h2, t)
            if abs(a[0] - t) > 0.05 or abs(b[0] - t) > 0.05 or a[4] < 1 or b[4] < 1: continue
            ma = at(m1, t); mb = m2[max(0, int(np.searchsorted(m2[:, 0], t, side="right")) - 1)]
            errs.append(float(np.linalg.norm(to_app(mb, to_map(ma, a[1:4])) - b[1:4]))); ts.append(t)
        if errs:
            e = np.array(errs)
            print("    a thing put down at the head's place in the first session, brought back in this one, over %d seconds of the recording: median %.1f cm off, 90th %.1f cm, worst %.1f cm (at %.0f s); within 10 cm for %.0f%% of them" % (
                len(e), 100 * np.median(e), 100 * np.percentile(e, 90), 100 * e.max(), ts[int(e.argmax())], 100 * (e < 0.10).mean()))

if __name__ == "__main__": sys.exit(main() or 0)
