#!/usr/bin/env python3
"""Where are the floor, the tables and the walls? (from a recording and a replay of it, on this machine)

  python room_scan.py RECORDING.uxrec BASALT_CALIB.json REPLAY-vio.csv [--every S] [--from-to A,B] [--out DIR]

Every S seconds (0.3) one stereo pair is taken from the recording. Points are followed from the left picture into
the right one, kept only if the two sight lines meet, and placed in the room with the head position the replay
reported for that moment. The points of each tracking epoch (the estimator's world changes when it restarts) are
then searched for level surfaces (heights at which many points lie, spread over an area) and for walls.

This is the prototype of the app's room model (core/RoomModel.kt): the numbers it prints are what that code has to
reproduce on the phone. Needs numpy and opencv-python. The recording is private; only numbers leave this script
(and, with --out, a top-down drawing of the points).
"""
import csv, json, os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar
from stereo_check import quat_R, unproject_kb4, flow, skew, project_kb4

def triangulate(b0, b1, R01, t01):
    """Sight lines b0 (camera 0) and b1 (camera 1), camera 1's pose in camera 0. Returns points in camera 0 and the miss distance."""
    d1 = (R01 @ b1.T).T
    a = np.einsum("ni,ni->n", b0, d1)
    c0 = b0 @ t01; c1 = d1 @ t01
    den = 1 - a * a
    s = (c0 - a * c1) / np.maximum(den, 1e-9); u = (a * c0 - c1) / np.maximum(den, 1e-9)
    p0 = b0 * s[:, None]; p1 = t01 + d1 * u[:, None]
    return (p0 + p1) / 2, np.linalg.norm(p0 - p1, axis=1), s, u, den

def level_surfaces(pts, head_z, cell=0.03, grid=0.25):
    """Heights where many points lie, spread over an area: (height, points, area m2) from the lowest up."""
    z = pts[:, 2]
    lo = np.floor(z.min() / cell) * cell; n = int((z.max() - lo) / cell) + 2
    h = np.bincount(((z - lo) / cell).astype(int), minlength=n).astype(float)
    sm = np.convolve(h, [0.25, 0.5, 0.25], mode="same")
    out = []
    for i in range(1, n - 1):
        if sm[i] < 25 or sm[i] < sm[i - 1] or sm[i] <= sm[i + 1]: continue
        zc = lo + (i + 0.5) * cell
        sel = np.abs(z - zc) < 1.5 * cell
        zc = float(np.median(z[sel]))
        cells = set(map(tuple, np.floor(pts[sel][:, :2] / grid).astype(int)))
        # a level surface is wider than it is thick: its points cover floor area, a wall's or a shelf edge's do not
        out.append((zc, int(sel.sum()), len(cells) * grid * grid))
    return out

def main():
    a = sys.argv[1:]; every = 0.3; out = None; span = (0.0, 1e9)
    if "--every" in a: i = a.index("--every"); every = float(a[i + 1]); del a[i:i + 2]
    if "--out" in a: i = a.index("--out"); out = a[i + 1]; del a[i:i + 2]
    if "--from-to" in a: i = a.index("--from-to"); span = tuple(float(v) for v in a[i + 1].split(",")); del a[i:i + 2]
    if len(a) != 3: print(__doc__); return 2
    c = json.load(open(a[1]))["value0"]
    k0 = c["intrinsics"][0]["intrinsics"]; k1 = c["intrinsics"][1]["intrinsics"]
    def pose(o): return quat_R([o["qx"], o["qy"], o["qz"], o["qw"]]), np.array([o["px"], o["py"], o["pz"]])
    Ri0, ti0 = pose(c["T_imu_cam"][0]); Ri1, ti1 = pose(c["T_imu_cam"][1])
    R01 = Ri0.T @ Ri1; t01 = Ri0.T @ (ti1 - ti0)       # camera 1 in camera 0
    R10 = R01.T; t10 = -R01.T @ t01
    E = skew(t10 / np.linalg.norm(t10)) @ R10
    centre = np.array([[240.0, 320.0]])
    guess = (project_kb4((R10 @ (unproject_kb4(centre, k0) * 2.0).T).T + t10, k1) - centre)[0]

    rows = [r for r in csv.DictReader(open(a[2]))]
    vt = np.array([float(r["t"]) for r in rows]); vp = np.array([[float(r[k]) for k in ("px", "py", "pz")] for r in rows])
    vq = np.array([[float(r[k]) for k in ("qx", "qy", "qz", "qw")] for r in rows]); ve = np.array([int(r["resets"]) for r in rows]); vok = np.array([int(r["trusted"]) for r in rows])

    first = None; pend = {}; last = -1e9; world = []; heads = []; kept = 0; tried = 0
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
        # the replay writes a state when it comes out of the estimator, about 0.07 s after its picture
        i = int(np.searchsorted(vt, s + 0.07)); i = min(max(i, 0), len(vt) - 1)
        if abs(vt[i] - s - 0.07) > 0.15 or not vok[i]: continue
        pts = cv2.goodFeaturesToTrack(left, 300, 0.01, 8)
        if pts is None: continue
        pts = pts.reshape(-1, 2); p1, ok = flow(left, right, pts, guess, 4)
        b0 = unproject_kb4(pts[ok], k0); b1 = unproject_kb4(p1[ok], k1)
        epi = np.abs(np.einsum("ni,ij,nj->n", b1, E, b0))
        pc, miss, s0, s1, den = triangulate(b0, b1, R01, t01)
        # in front of both cameras, sight lines that meet, between 0.3 and 4 m, and not near-parallel lines
        good = (epi < 0.01) & (s0 > 0.3) & (s0 < 4.0) & (s1 > 0.3) & (den > 1e-4)
        tried += len(pts); kept += int(good.sum())
        Rwi = quat_R(vq[i]); pw = (Rwi @ (Ri0 @ pc[good].T + ti0[:, None])).T + vp[i]
        world.append(np.column_stack([pw, np.full(len(pw), ve[i]), np.full(len(pw), s), s0[good]])); heads.append((s, ve[i], *vp[i]))
    W = np.vstack(world); H = np.array(heads)
    print("pairs used %d, points placed %d of %d tried (%.0f a pair)" % (len(heads), kept, tried, kept / max(1, len(heads))))
    report = {"epochs": []}
    for e in sorted(set(W[:, 3].astype(int))):
        P = W[W[:, 3] == e]; Hd = H[H[:, 1] == e]
        if len(P) < 400: continue
        hz = float(np.median(Hd[:, 4])); travel = float(np.ptp(Hd[:, 2:4], axis=0).max())
        print("epoch %d: %.0f to %.0f s, %d points, head height median %.2f (range %.2f to %.2f), walked over %.1f m" % (e, Hd[0, 0], Hd[-1, 0], len(P), hz, Hd[:, 4].min(), Hd[:, 4].max(), travel))
        surf = level_surfaces(P[:, :3], hz)
        big = [s for s in surf if s[2] >= 0.5]
        for zc, n, area in surf:
            if area >= 0.3: print("   level surface %.2f m below the head's median height: %d points over %.1f m2" % (hz - zc, n, area))
        below = [s for s in big if hz - s[0] > 0.6]
        floor = min(below, key=lambda s: s[0]) if below else None
        if floor: print("   FLOOR: %.2f m below the head's median height (%d points, %.1f m2)" % (hz - floor[0], floor[1], floor[2]))
        # how soon would the floor have been known? the same search on the points of the first N seconds
        if floor:
            for secs in (3, 5, 10, 20, 40):
                Q = P[P[:, 4] < Hd[0, 0] + secs]
                if len(Q) < 200: continue
                s2 = [s for s in level_surfaces(Q[:, :3], hz) if s[2] >= 0.5 and hz - s[0] > 0.6]
                f2 = min(s2, key=lambda s: s[0]) if s2 else None
                print("      after %2d s: %s" % (secs, "floor %.2f m below (%.1f m2), %+.0f mm from the final answer" % (hz - f2[0], f2[2], (f2[0] - floor[0]) * 1000) if f2 else "no floor yet (%d points)" % len(Q)))
        report["epochs"].append({"epoch": int(e), "points": int(len(P)), "head_height": hz, "floor_below_head": (hz - floor[0]) if floor else None, "surfaces": [[hz - s[0], s[1], s[2]] for s in surf]})
        if out:
            os.makedirs(out, exist_ok=True)
            img = np.zeros((600, 600, 3), np.uint8); sc = 60.0; cx = np.median(P[:, 0]); cy = np.median(P[:, 1])
            for x, y, z in P[:, :3]:
                u = int(300 + (x - cx) * sc); v = int(300 - (y - cy) * sc)
                if 0 <= u < 600 and 0 <= v < 600:
                    col = (60, 200, 60) if floor and abs(z - floor[0]) < 0.05 else (200, 200, 200) if z > hz - 0.3 else (200, 120, 60)
                    img[v, u] = col
            for s_, e_, x, y, z in Hd:
                u = int(300 + (x - cx) * sc); v = int(300 - (y - cy) * sc)
                if 0 <= u < 600 and 0 <= v < 600: img[v, u] = (0, 0, 255)
            cv2.imwrite(os.path.join(out, "room-epoch%d.png" % e), img)
            np.save(os.path.join(out, "points-epoch%d.npy" % e), P)
    if out: json.dump(report, open(os.path.join(out, "room_scan.json"), "w"), indent=1)
    return 0

if __name__ == "__main__": sys.exit(main())
