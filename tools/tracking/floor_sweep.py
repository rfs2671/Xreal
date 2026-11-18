#!/usr/bin/env python3
"""How far below the cameras is the floor? Every height is tried against both pictures. (from a recording)

  python floor_sweep.py RECORDING.uxrec BASALT_CALIB.json REPLAY-vio.csv [--every S] [--from-to A,B] [--fix x,y,z]

Corners are rare on a plain floor, so placing corners (room_scan.py) finds the clutter standing on a floor more
readily than the floor. This asks the other way round: if the floor were H metres under the left camera, level
(the motion sensor says which way is down), then every pixel of the left picture that looks down at it would be
found at a known place in the right picture. For each H from 0.5 to 2.3 m the left picture's lower part is
compared, block by block, with where the right picture would show it; the blocks that agree are counted. A level
surface under the cameras shows as a height at which many blocks agree. The floor is the deepest such height.

Prototype of core/FloorSweep.kt. Needs numpy and opencv-python.
"""
import csv, json, os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar
from stereo_check import quat_R, unproject_kb4, project_kb4

HEIGHTS = np.arange(0.5, 2.31, 0.02)
BLOCK = 16

def sweep(left, right, k0, k1, R10, t10, down, rays, uv):
    """Agreeing blocks for each height. down: unit vector toward the floor in the left camera's frame."""
    # fine detail only (grout lines, speckle): broad shading agrees at any height and says nothing
    def detail(im):
        f = im.astype(np.float32); return cv2.GaussianBlur(f, (0, 0), 1.0) - cv2.GaussianBlur(f, (0, 0), 3.0)
    lf = detail(left); rf = detail(right)
    cosd = rays @ down                                   # how steeply each pixel's sight line goes down
    use = cosd > 0.42                                    # at least 25 degrees below level
    out = np.zeros(len(HEIGHTS), int); best = np.full(len(uv), -1.0); besth = np.zeros(len(uv))
    lv = lf[uv[:, 1], uv[:, 0]]
    nb = (left.shape[0] // BLOCK) * (left.shape[1] // BLOCK)
    bid = (uv[:, 1] // BLOCK) * (left.shape[1] // BLOCK) + uv[:, 0] // BLOCK
    scores = np.zeros((len(HEIGHTS), nb), np.float32)
    for hi, H in enumerate(HEIGHTS):
        dist = H / np.maximum(cosd, 1e-6)
        ok = use & (dist < 4.0)
        P = rays * dist[:, None]
        q = project_kb4((R10 @ P.T).T + t10, k1)
        # the right picture read between its pixels at q (bilinear), -1 outside it
        x = q[:, 0]; y = q[:, 1]; inside = (x >= 0) & (x < rf.shape[1] - 1) & (y >= 0) & (y < rf.shape[0] - 1) & np.isfinite(x) & np.isfinite(y)
        xi = np.where(inside, x, 0).astype(int); yi = np.where(inside, y, 0).astype(int); fx = np.where(inside, x - xi, 0); fy = np.where(inside, y - yi, 0)
        rv = rf[yi, xi] * (1 - fx) * (1 - fy) + rf[yi, xi + 1] * fx * (1 - fy) + rf[yi + 1, xi] * (1 - fx) * fy + rf[yi + 1, xi + 1] * fx * fy
        rv = np.where(inside, rv, -1e9)
        ok &= rv > -1e8
        # zero-mean correlation per block
        n = np.bincount(bid[ok], minlength=nb).astype(np.float32)
        sl = np.bincount(bid[ok], lv[ok], nb); sr = np.bincount(bid[ok], rv[ok], nb)
        sll = np.bincount(bid[ok], lv[ok] ** 2, nb); srr = np.bincount(bid[ok], rv[ok] ** 2, nb); slr = np.bincount(bid[ok], lv[ok] * rv[ok], nb)
        with np.errstate(divide="ignore", invalid="ignore"):
            vl = sll / n - (sl / n) ** 2; vr = srr / n - (sr / n) ** 2
            z = (slr / n - sl * sr / n ** 2) / np.sqrt(vl * vr)
        good = (n >= 40) & (vl > 2.0) & (vr > 2.0)      # enough pixels, and detail above the sensor's noise
        z[~good] = np.nan
        scores[hi] = z
    return scores

def main():
    a = sys.argv[1:]; every = 1.0; span = (0.0, 1e9); fix = None
    if "--every" in a: i = a.index("--every"); every = float(a[i + 1]); del a[i:i + 2]
    if "--from-to" in a: i = a.index("--from-to"); span = tuple(float(v) for v in a[i + 1].split(",")); del a[i:i + 2]
    if "--fix" in a: i = a.index("--fix"); fix = [float(v) for v in a[i + 1].split(",")]; del a[i:i + 2]
    if len(a) != 3: print(__doc__); return 2
    c = json.load(open(a[1]))["value0"]
    k0 = c["intrinsics"][0]["intrinsics"]; k1 = c["intrinsics"][1]["intrinsics"]
    def pose(o): return quat_R([o["qx"], o["qy"], o["qz"], o["qw"]]), np.array([o["px"], o["py"], o["pz"]])
    Ri0, ti0 = pose(c["T_imu_cam"][0]); Ri1, ti1 = pose(c["T_imu_cam"][1])
    if fix:
        w = np.radians(fix); Rw, _ = cv2.Rodrigues(w.reshape(3, 1)); Ri1 = Ri1 @ Rw.T
    R10 = Ri1.T @ Ri0; t10 = Ri1.T @ (ti0 - ti1)
    # every second pixel of the left picture
    xs, ys = np.meshgrid(np.arange(8, 472, 2), np.arange(8, 632, 2)); uv = np.stack([xs.ravel(), ys.ravel()], 1)
    rays = unproject_kb4(uv.astype(float), k0)
    rows = [r for r in csv.DictReader(open(a[2]))]
    vt = np.array([float(r["t"]) for r in rows]); vq = np.array([[float(r[k]) for k in ("qx", "qy", "qz", "qw")] for r in rows]); vok = np.array([int(r["trusted"]) for r in rows])
    first = None; pend = {}; last = -1e9; total = None; frames = 0; per = []
    for kind, t, data in ar.records(a[0]):
        if first is None: first = t
        if kind != 1: continue
        s = (t - first) / 1e9
        if not (span[0] <= s < span[1]): continue
        dev, counter, eye = ar.meta(data)
        if eye == 0:
            if s - last >= every: pend = {counter: ar.decode(data)[0]}; last = s
            continue
        if counter not in pend: continue
        left = pend.pop(counter); right = ar.decode(data)[0]
        i = min(max(int(np.searchsorted(vt, s + 0.07)), 0), len(vt) - 1)
        if abs(vt[i] - s - 0.07) > 0.15 or not vok[i]: continue
        down = (quat_R(vq[i]) @ Ri0).T @ np.array([0.0, 0.0, -1.0])
        sc = sweep(left, right, k0, k1, R10, t10, down, rays, uv)
        agree = np.nansum(sc > 0.5, axis=1); compared = np.sum(~np.isnan(sc), axis=1)
        frames += 1
        total = agree if total is None else total + agree
        # this picture's own answer: heights where at least 12 blocks agree and no neighbour has more
        peaks = [(HEIGHTS[j], agree[j]) for j in range(2, len(HEIGHTS) - 2) if agree[j] >= 12 and agree[j] == agree[j - 2:j + 3].max()]
        per.append((s, peaks, np.degrees(np.arccos(np.clip(down[2], -1, 1)))))
        if frames % 10 == 1: print("%5.1f s  looking %2.0f deg below level  level surfaces at: %s" % (s, 90 - per[-1][2], ", ".join("%.2f m (%d blocks)" % p for p in peaks) or "none"))
    print("\n%d pictures. Blocks agreeing, summed over all pictures, by height below the left camera:" % frames)
    for j in range(0, len(HEIGHTS), 1):
        if j % 2 == 0: print("  %.2f m  %6d  %s" % (HEIGHTS[j], total[j], "#" * int(60 * total[j] / total.max())))
    deep = [p[1][-1][0] for p in per if p[1]]
    if deep: print("deepest level surface of each picture: median %.2f m, middle half %.2f to %.2f m (%d pictures)" % (np.median(deep), *np.percentile(deep, [25, 75]), len(deep)))
    return 0

if __name__ == "__main__": sys.exit(main())
