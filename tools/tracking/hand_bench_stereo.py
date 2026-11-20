#!/usr/bin/env python3
"""A test for what the tiled detector finds: is it a thing within arm's reach? The two cameras' finds of a frame are
paired, the two rays to them are met, and a pair is kept when the rays do meet and meet between 12 and 85 cm from
the cameras. A thing across the room (a lamp stand taken for fingers, the wearer in a mirror) fails it.

  python hand_bench_stereo.py DIR          reads DIR/detections.csv and DIR/labels.csv (hand_bench.py)
"""
import csv, json, os, sys
import numpy as np

CALIB = r"C:\uvio\rec\basalt_calib.json"
NEAR, FAR, MISS = 0.12, 0.85, 0.035     # metres: nearest and furthest a hand can be, and how far apart the two rays may pass

c = json.load(open(CALIB))["value0"]
cams = [x["intrinsics"] for x in c["intrinsics"]]

def quat(q):
    x, y, z, w = q["qx"], q["qy"], q["qz"], q["qw"]
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)], [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)], [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
R = [quat(t) for t in c["T_imu_cam"]]; P = [np.array([t["px"], t["py"], t["pz"]]) for t in c["T_imu_cam"]]

def ray(e, u, v):
    """The ray of pixel (u, v) of camera e, in the motion sensor's frame (kb4 unprojected by Newton's method)."""
    k = cams[e]; mx = (u - k["cx"]) / k["fx"]; my = (v - k["cy"]) / k["fy"]; r = np.hypot(mx, my); th = r
    for _ in range(8):
        t2 = th * th; f = th * (1 + k["k1"] * t2 + k["k2"] * t2 ** 2 + k["k3"] * t2 ** 3 + k["k4"] * t2 ** 4) - r
        df = 1 + 3 * k["k1"] * t2 + 5 * k["k2"] * t2 ** 2 + 7 * k["k3"] * t2 ** 3 + 9 * k["k4"] * t2 ** 4; th -= f / df
    d = np.array([np.sin(th) * mx / r, np.sin(th) * my / r, np.cos(th)]) if r > 1e-9 else np.array([0.0, 0.0, 1.0])
    return R[e] @ d

def meet(a, b):
    """Where the left camera's ray to a and the right camera's ray to b pass closest: (distance from the cameras, how far apart they pass)."""
    d0, d1 = ray(0, a["x"], a["y"]), ray(1, b["x"], b["y"]); w = P[0] - P[1]
    aa, bb, cc = d0 @ d0, d0 @ d1, d1 @ d1; dd, ee = d0 @ w, d1 @ w; den = aa * cc - bb * bb
    if abs(den) < 1e-9: return 9.0, 9.0
    s = (bb * ee - cc * dd) / den; t = (aa * ee - bb * dd) / den
    if s <= 0 or t <= 0: return 9.0, 9.0
    p0, p1 = P[0] + s * d0, P[1] + t * d1; mid = (p0 + p1) / 2
    return float(np.linalg.norm(mid - (P[0] + P[1]) / 2)), float(np.linalg.norm(p0 - p1))

def parse(s):
    out = []
    for part in s.split(";"):
        if not part: continue
        f = part.split(":"); out.append(dict(hand=f[0], conf=float(f[1]), x=float(f[2]), y=float(f[3])))
    return out

def main():
    d = sys.argv[1]; lab = {}
    for r in csv.reader(open(os.path.join(d, "labels.csv"))):
        if r and r[0].strip().isdigit(): lab[int(r[0])] = (r[1].strip(), r[2].strip())
    rows = list(csv.DictReader(open(os.path.join(d, "detections.csv"))))
    for name, col in (("whole picture", "full_at"), ("tiled", "tiled_at")):
        have = got = 0; clear = false = 0; dist = []
        per = {}
        for r in rows:
            s = int(float(r["t"]))
            if s not in lab: continue
            L, Rr = parse(r["l_" + col]), parse(r["r_" + col])
            kept = set()
            for a in L:
                for b in Rr:
                    if a["hand"] != b["hand"]: continue
                    far, miss = meet(a, b)
                    if NEAR <= far <= FAR and miss <= MISS: kept.add(a["hand"]); dist.append(far)
            for hand in "LR":
                both_plain = hand in lab[s][0] and hand in lab[s][1]
                neither = hand not in lab[s][0] and hand not in lab[s][1] and hand.lower() not in lab[s][0] and hand.lower() not in lab[s][1]
                if both_plain: have += 1; got += hand in kept
                if neither: clear += 1; false += hand in kept
            per.setdefault(s, []).append("".join(sorted(kept)))
        print("%s, pairs that pass the arm's-reach test: a hand plainly in both pictures kept %d of %d times (%.0f%%); a hand kept that is in neither picture %d of %d times (%.1f%%); kept hands were %.0f to %.0f cm away (median %.0f)" % (
            name, got, have, 100.0 * got / max(1, have), false, clear, 100.0 * false / max(1, clear), 100 * min(dist) if dist else 0, 100 * max(dist) if dist else 0, 100 * float(np.median(dist)) if dist else 0))
        if name == "tiled":
            print("second by second, the share (of 9) of pairs with a hand kept:  " + "  ".join("%d:%s%s" % (s, "L%d" % (sum(1 for k in v if "L" in k) * 9 // len(v)), "R%d" % (sum(1 for k in v if "R" in k) * 9 // len(v))) for s, v in sorted(per.items())))

if __name__ == "__main__": main()
