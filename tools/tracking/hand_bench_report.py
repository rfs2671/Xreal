#!/usr/bin/env python3
"""Reads DIR/detections.csv (hand_bench.py run) and DIR/labels.csv: where the tiled detector's false finds come from,
what a higher confidence does, and how each second of the recording went.

  python hand_bench_report.py DIR
"""
import csv, os, sys
from collections import defaultdict

d = sys.argv[1]
lab = {}
for r in csv.reader(open(os.path.join(d, "labels.csv"))):
    if r and r[0].strip().isdigit(): lab[int(r[0])] = (r[1].strip(), r[2].strip())
rows = list(csv.DictReader(open(os.path.join(d, "detections.csv"))))

def parse(s):
    out = []
    for part in s.split(";"):
        if not part: continue
        f = part.split(":"); out.append(dict(hand=f[0], conf=float(f[1]), x=float(f[2]), y=float(f[3]), tile=f[4] if len(f) > 4 else ""))
    return out

def score(col, min_conf, any_side=False):
    """present and found, present, absent and found, absent; with [any_side] a hand counts as found whichever side the detector calls it."""
    a = [0, 0, 0, 0]
    for r in rows:
        s = int(float(r["t"]))
        if s not in lab: continue
        for e, cam in enumerate("lr"):
            det = [x for x in parse(r[cam + "_" + col]) if x["conf"] >= min_conf]; label = lab[s][e]
            if any_side:
                if label.lower() != label and label.upper() != label: continue
                if any(c.islower() for c in label): continue
                present = len(label) > 0; got = len(det) > 0
                if present: a[1] += 1; a[0] += got
                else: a[3] += 1; a[2] += got
            else:
                for hand in "LR":
                    if hand.lower() in label: continue
                    present = hand in label; got = any(x["hand"] == hand for x in det)
                    if present: a[1] += 1; a[0] += got
                    else: a[3] += 1; a[2] += got
    return a

print("confidence  | whole picture: found / false          | tiled: found / false")
for c in (0.3, 0.5, 0.7, 0.8, 0.9, 0.95):
    w = score("full_at", c); t = score("tiled_at", c)
    print("  over %.2f | %3d of %d (%2.0f%%) / %3d of %d (%4.1f%%)   | %3d of %d (%2.0f%%) / %3d of %d (%4.1f%%)" % (c, w[0], w[1], 100.0 * w[0] / max(1, w[1]), w[2], w[3], 100.0 * w[2] / max(1, w[3]), t[0], t[1], 100.0 * t[0] / max(1, t[1]), t[2], t[3], 100.0 * t[2] / max(1, t[3])))
print("whichever side the detector calls the hand (a camera's picture has a hand or has none; seconds that cannot be judged left out):")
for c in (0.3, 0.7, 0.9):
    w = score("full_at", c, True); t = score("tiled_at", c, True)
    print("  over %.2f | whole %3d of %d (%2.0f%%) / false %d of %d | tiled %3d of %d (%2.0f%%) / false %d of %d" % (c, w[0], w[1], 100.0 * w[0] / max(1, w[1]), w[2], w[3], t[0], t[1], 100.0 * t[0] / max(1, t[1]), t[2], t[3]))

# the false finds of the tiled detector: which tile, how sure, where in the picture
by_tile = defaultdict(list)
for r in rows:
    s = int(float(r["t"]))
    if s not in lab: continue
    for e, cam in enumerate("lr"):
        for x in parse(r[cam + "_tiled_at"]):
            if x["hand"] not in lab[s][e] and x["hand"].lower() not in lab[s][e]: by_tile[x["tile"]].append((x["conf"], x["x"], x["y"], s, cam, x["hand"], lab[s][e]))
print("false finds of the tiled detector by tile (yaw/pitch): count, median confidence, and a few (second, camera, called, label, x, y)")
for tile, v in sorted(by_tile.items(), key=lambda kv: -len(kv[1])):
    confs = sorted(c[0] for c in v)
    print("  %-8s %3d  conf %.2f   %s" % (tile, len(v), confs[len(confs) // 2], "  ".join("%ds %s %s[%s] %.0f,%.0f" % (c[3], c[4], c[5], c[6], c[1], c[2]) for c in v[:: max(1, len(v) // 4)][:4])))

print("second by second (label left cam | right cam; then the share of that second's pairs in which each detector found L and R in each camera):")
by_s = defaultdict(list)
for r in rows: by_s[int(float(r["t"]))].append(r)
for s in sorted(by_s):
    if s not in lab: continue
    def share(col, hand): return sum(1 for r in by_s[s] if hand in r[col]) * 9 // max(1, len(by_s[s]))
    print("  %2d s  %-3s|%-3s  whole L%d%d R%d%d   tiled L%d%d R%d%d" % (s, lab[s][0], lab[s][1], share("l_full", "L"), share("r_full", "L"), share("l_full", "R"), share("r_full", "R"),
          share("l_tiled", "L"), share("r_tiled", "L"), share("l_tiled", "R"), share("r_tiled", "R")))
