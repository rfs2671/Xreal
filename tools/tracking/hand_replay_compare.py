#!/usr/bin/env python3
"""Replays of one recording through the phone's hand tracker under different settings, side by side.

  python hand_replay_compare.py DIR TAG [TAG ...] [--labels labels.csv]

DIR holds TAG-hands.csv from tools/pixel/lab.py replay (t, mask: bit 0 the left hand, bit 1 the right; process_ms;
then for each hand the fingertip gap, the distance, x and y). With labels (hand_bench.py: for each second, which hand
is plainly in the left and in the right camera's picture; small letters cannot be judged) each tracker result is put
in one of three classes for each hand, and the share of results in which the tracker reports that hand is given:
  both     the hand is plainly in both cameras' pictures: it should be tracked
  none     the hand is in neither picture: a hand reported here is a false one
  unsure   everything else (in one picture only, or not to be judged): given for information
"""
import csv, os, sys
import numpy as np

a = sys.argv[1:]; labels = None
if "--labels" in a: i = a.index("--labels"); labels = a[i + 1]; del a[i:i + 2]
d, tags = a[0], a[1:]
lab = {}
if labels:
    for r in csv.reader(open(labels)):
        if r and r[0].strip().isdigit(): lab[int(r[0])] = (r[1].strip(), r[2].strip())

def cls(s, hand):
    if s not in lab: return None
    l, r = lab[s]
    if hand in l and hand in r: return "both"
    if hand not in l and hand not in r and hand.lower() not in l and hand.lower() not in r: return "none"
    return "unsure"

print("%-12s %7s %6s %6s %8s %8s | %-22s | %-22s | %-22s" % ("run", "results", "a sec", "ms", "left", "right", "in both pictures", "in neither (false)", "unsure"))
per_second = {}
for tag in tags:
    rows = list(csv.DictReader(open(os.path.join(d, tag + "-hands.csv"))))
    t = np.array([float(r["t"]) for r in rows]); m = np.array([int(r["mask"]) for r in rows]); ms = np.array([float(r["process_ms"]) for r in rows])
    n = len(rows); dur = t[-1] - t[0] if n > 1 else 1
    c = {"both": [0, 0], "none": [0, 0], "unsure": [0, 0]}
    sec = {}
    for i in range(n):
        s = int(t[i])
        for bit, hand in ((1, "L"), (2, "R")):
            k = cls(s, hand)
            if k: c[k][1] += 1; c[k][0] += (m[i] & bit) > 0
        e = sec.setdefault(s, [0, 0, 0]); e[0] += 1; e[1] += (m[i] & 1) > 0; e[2] += (m[i] & 2) > 0
    per_second[tag] = sec
    def f(k): return "%4d of %4d (%3.0f%%)" % (c[k][0], c[k][1], 100.0 * c[k][0] / max(1, c[k][1])) if lab else ""
    # distances of the tracked hands, to see that they are within reach
    dist = [float(r[k]) for r in rows for k in ("l_dist", "r_dist") if r.get(k)]
    print("%-12s %7d %6.1f %6.1f %7.0f%% %7.0f%% | %-22s | %-22s | %-22s  hands %.0f to %.0f cm away" % (tag, n, n / dur, float(np.mean(ms)), 100.0 * ((m & 1) > 0).mean(), 100.0 * ((m & 2) > 0).mean(), f("both"), f("none"), f("unsure"),
          100 * (np.percentile(dist, 5) if dist else 0), 100 * (np.percentile(dist, 95) if dist else 0)))
if lab:
    print("second by second: label (left camera|right camera), then for each run the share (0 to 9) of results with the left and with the right hand")
    secs = sorted(set().union(*[set(v) for v in per_second.values()]))
    for s in secs:
        if s not in lab: continue
        print("  %2d s %-3s|%-3s  " % (s, lab[s][0], lab[s][1]) + "   ".join("%s L%d R%d" % (tag, per_second[tag].get(s, [1, 0, 0])[1] * 9 // max(1, per_second[tag].get(s, [1, 0, 0])[0]), per_second[tag].get(s, [1, 0, 0])[2] * 9 // max(1, per_second[tag].get(s, [1, 0, 0])[0])) for tag in tags))
