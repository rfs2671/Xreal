#!/usr/bin/env python3
"""Can a second look tell a hand from what the tiled detector takes for one? For every find of the tiled detector
(DIR/detections.csv, hand_bench.py) the detector is run again on views aimed straight at the find: three widths, and
turned a little either way. Finds are sorted by the labels into true (that hand is plainly in that camera's picture)
and false (it is not there, and not unsure), and what the second looks say of each kind is printed.

  python hand_bench_verify.py DIR
"""
import csv, os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hand_bench as hb

d = sys.argv[1]; t, L, R = hb.load(d); cams = hb.cameras()
lab = {}
for r in csv.reader(open(os.path.join(d, "labels.csv"))):
    if r and r[0].strip().isdigit(): lab[int(r[0])] = (r[1].strip(), r[2].strip())
rows = list(csv.DictReader(open(os.path.join(d, "detections.csv"))))

def ray(cam, u, v):
    mx = (u - cam["cx"]) / cam["fx"]; my = (v - cam["cy"]) / cam["fy"]; r = np.hypot(mx, my); th = r
    for _ in range(8):
        t2 = th * th; f = th * (1 + cam["k1"] * t2 + cam["k2"] * t2 ** 2 + cam["k3"] * t2 ** 3 + cam["k4"] * t2 ** 4) - r
        th -= f / (1 + 3 * cam["k1"] * t2 + 5 * cam["k2"] * t2 ** 2 + 7 * cam["k3"] * t2 ** 3 + 9 * cam["k4"] * t2 ** 4)
    return np.array([np.sin(th) * mx / r, np.sin(th) * my / r, np.cos(th)]) if r > 1e-9 else np.array([0.0, 0.0, 1.0])

def second_looks(cam, img, u, v, hand):
    """The detector's confidence for [hand] (0 left, 1 right) in views aimed at (u, v): widths 50, 70, 95 degrees, and 70 turned 25 degrees either way."""
    dd = ray(cam, u, v); yaw = np.degrees(np.arctan2(dd[0], dd[2])); pitch = np.degrees(np.arctan2(dd[1], np.hypot(dd[0], dd[2])))
    out = []
    for fov, roll in ((50, 0), (70, 0), (95, 0), (70, 25), (70, -25)):
        tile = hb.Tile(cam, yaw, pitch, fov); pic = tile.cut(img)
        if roll: pic = cv2.warpAffine(pic, cv2.getRotationMatrix2D((80, 80), roll, 1.0), (160, 160))
        f = hb.normalise(pic)
        if f is None: out.append(0.0); continue
        e = hb.session().run(["hand_exists"], {"inputImg": f[None, None]})[0][0]
        out.append(float(e[hand]));
    return out

true, false = [], []
for i, r in enumerate(rows):
    s = int(float(r["t"]))
    if s not in lab: continue
    for e, cam in enumerate("lr"):
        for part in r[cam + "_tiled_at"].split(";"):
            if not part: continue
            f = part.split(":"); hand = f[0]; u, v = float(f[2]), float(f[3]); label = lab[s][e]
            if hand.lower() in label: continue
            looks = second_looks(cams[e], (L if e == 0 else R)[i], u, v, 0 if hand == "L" else 1)
            (true if hand in label else false).append([float(f[1])] + looks)
T = np.array(true); F = np.array(false)
print("%d true finds, %d false finds. Columns: first find, then second looks at 50, 70, 95 degrees, 70 turned +25, 70 turned -25" % (len(T), len(F)))
for name, A in (("true", T), ("false", F)):
    print("  %-5s median %s   10th percentile %s" % (name, np.round(np.median(A, 0), 2), np.round(np.percentile(A, 10, 0), 2)))
np.savez(os.path.join(d, "verify.npz"), T=T, F=F)
for rule, fn in (("all five second looks over 0.5", lambda A: (A[:, 1:] > 0.5).all(1)), ("70, 95 and both turned, all over 0.5", lambda A: (A[:, 2:] > 0.5).all(1)), ("70, 95 and both turned, all over 0.35", lambda A: (A[:, 2:] > 0.35).all(1)), ("70 and both turned, all over 0.5", lambda A: (A[:, [2, 4, 5]] > 0.5).all(1)), ("mean of 70, 95 and both turned over 0.7", lambda A: A[:, 2:].mean(1) > 0.7), ("mean of 70, 95, turned over 0.6 and least over 0.25", lambda A: (A[:, 2:].mean(1) > 0.6) & (A[:, 2:].min(1) > 0.25)), ("at least four of five over 0.5", lambda A: (A[:, 1:] > 0.5).sum(1) >= 4), ("at least three of five over 0.5", lambda A: (A[:, 1:] > 0.5).sum(1) >= 3),
                 ("mean of the five over 0.6", lambda A: A[:, 1:].mean(1) > 0.6), ("least of 50/70/95 over 0.3", lambda A: A[:, 1:4].min(1) > 0.3), ("mean of the five over 0.8", lambda A: A[:, 1:].mean(1) > 0.8)):
    print("  rule: %-34s keeps %3d of %3d true (%.0f%%), %3d of %3d false (%.0f%%)" % (rule, fn(T).sum(), len(T), 100.0 * fn(T).mean(), fn(F).sum(), len(F), 100.0 * fn(F).mean()))
