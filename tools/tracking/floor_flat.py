#!/usr/bin/env python3
"""Is the cameras' depth right at every distance? Asked of the floor, which is flat.

  python floor_flat.py RECORDING.uxrec BASALT_CALIB.json REPLAY-vio.csv [--every S] [--turns D1,D2,...]

Points are followed from the left picture into the right one and placed in the room, as room_scan.py does, out
to 6 m. Those near the floor's level are sorted by how far from the cameras they were measured. If depth is right
the floor is at one height at every distance. If the angle between the two cameras is slightly off in the
calibration (the one error the sight lines' meeting cannot show), far points come out too near or too far and the
floor bends up or down with distance. The same is then done with the right camera turned by each of --turns
(degrees, about the axis square to the baseline and the view), to find the turn that makes the floor flat.

Only numbers leave this script. Needs numpy and opencv-python.
"""
import csv, json, os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar
from stereo_check import quat_R, unproject_kb4, flow, skew, project_kb4
from room_scan import triangulate

def rot(axis, deg):
    a = axis / np.linalg.norm(axis); t = np.radians(deg); K = skew(a)
    return np.eye(3) + np.sin(t) * K + (1 - np.cos(t)) * K @ K

def main():
    a = sys.argv[1:]; every = 0.3; turns = [0.0]
    if "--every" in a: i = a.index("--every"); every = float(a[i + 1]); del a[i:i + 2]
    if "--turns" in a: i = a.index("--turns"); turns = [float(v) for v in a[i + 1].split(",")]; del a[i:i + 2]
    span = (0.0, 1e9)
    if "--from-to" in a: i = a.index("--from-to"); span = tuple(float(v) for v in a[i + 1].split(",")); del a[i:i + 2]
    fixes = []      # --fix x,y,z (degrees, as in the app's stereo_rotation_fix.txt); may be given several times
    while "--fix" in a: i = a.index("--fix"); fixes.append([float(v) for v in a[i + 1].split(",")]); del a[i:i + 2]
    if len(a) != 3: print(__doc__); return 2
    c = json.load(open(a[1]))["value0"]
    k0 = c["intrinsics"][0]["intrinsics"]; k1 = c["intrinsics"][1]["intrinsics"]
    def pose(o): return quat_R([o["qx"], o["qy"], o["qz"], o["qw"]]), np.array([o["px"], o["py"], o["pz"]])
    Ri0, ti0 = pose(c["T_imu_cam"][0]); Ri1, ti1 = pose(c["T_imu_cam"][1])
    R01 = Ri0.T @ Ri1; t01 = Ri0.T @ (ti1 - ti0)
    R10 = R01.T; t10 = -R01.T @ t01
    E = skew(t10 / np.linalg.norm(t10)) @ R10
    centre = np.array([[240.0, 320.0]])
    guess = (project_kb4((R10 @ (unproject_kb4(centre, k0) * 2.0).T).T + t10, k1) - centre)[0]
    axis = np.cross(t01, [0.0, 0.0, 1.0])
    print("baseline %.1f mm along %s; the turn is about %s" % (np.linalg.norm(t01) * 1000, np.round(t01 / np.linalg.norm(t01), 2), np.round(axis / np.linalg.norm(axis), 2)))

    rows = [r for r in csv.DictReader(open(a[2]))]
    vt = np.array([float(r["t"]) for r in rows]); vp = np.array([[float(r[k]) for k in ("px", "py", "pz")] for r in rows])
    vq = np.array([[float(r[k]) for k in ("qx", "qy", "qz", "qw")] for r in rows]); vok = np.array([int(r["trusted"]) for r in rows])

    first = None; lefts = {}; rights = {}; last = -1e9; pairs = []
    for kind, t, data in ar.records(a[0]):
        if first is None: first = t
        if kind != 1: continue
        s = (t - first) / 1e9
        try: dev, counter, eye = ar.meta(data)
        except Exception: break       # a recording cut short ends in half a picture
        # the two pictures of one moment carry the same counter and arrive in either order
        mine, other = (lefts, rights) if eye == 0 else (rights, lefts)
        if counter not in other: mine[counter] = data; [mine.pop(k) for k in list(mine) if k < counter - 8]; continue
        od = other.pop(counter)
        if s - last < every or not (span[0] <= s < span[1]): continue
        last = s
        left = ar.decode(data if eye == 0 else od)[0]; right = ar.decode(od if eye == 0 else data)[0]
        i = int(np.searchsorted(vt, s + 0.07)); i = min(max(i, 0), len(vt) - 1)
        if abs(vt[i] - s - 0.07) > 0.15 or not vok[i]: continue
        pts = cv2.goodFeaturesToTrack(left, 300, 0.01, 8)
        if pts is None: continue
        pts = pts.reshape(-1, 2); p1, ok = flow(left, right, pts, guess, 4)
        b0 = unproject_kb4(pts[ok], k0); b1 = unproject_kb4(p1[ok], k1)
        epi = np.abs(np.einsum("ni,ij,nj->n", b1, E, b0))
        pairs.append((i, b0[epi < 0.01], b1[epi < 0.01]))
    print("stereo pairs %d, matches %d" % (len(pairs), sum(len(p[1]) for p in pairs)))
    head_z = float(np.median([vp[i][2] for i, _, _ in pairs]))

    edges = [1.5, 1.8, 2.1, 2.4, 2.7, 3.0, 3.5, 4.0, 5.0]
    cases = [("turn %+.2f deg" % d, rot(axis, d) @ R01) for d in turns]
    for f in fixes:      # the app turns the right camera: R_imu_cam1 becomes R_imu_cam1 * W^T
        n = np.linalg.norm(f); cases.append(("fix %s" % ",".join("%g" % v for v in f), Ri0.T @ (Ri1 @ rot(np.array(f), n).T) if n > 0 else R01))
    for name, R in cases:
        W = []
        for i, b0, b1 in pairs:
            pc, miss, s0, s1, den = triangulate(b0, b1, R, t01)
            good = (s0 > 0.3) & (s0 < 6.0) & (s1 > 0.3) & (den > 1e-5)
            Rwi = quat_R(vq[i]); pw = (Rwi @ (Ri0 @ pc[good].T + ti0[:, None])).T + vp[i]
            W.append(np.column_stack([pw[:, 2], s0[good]]))
        W = np.vstack(W)
        # the floor's level: the lowest well-filled height among the nearest floor points (a standing wearer's cameras
        # are 1.5 m from the floor at the least), then followed outwards bin by bin, so a bend is followed and not lost
        near = W[(W[:, 1] > 1.4) & (W[:, 1] < 2.1), 0]
        h, e = np.histogram(near, bins=np.arange(head_z - 2.3, head_z - 0.8, 0.03))
        ok = np.where(h >= max(20, 0.25 * h.max()))[0]
        if len(ok) == 0: print(name + ": no floor among the near points"); continue
        f0 = e[ok[0]] + 0.015
        f0 = float(np.median(near[np.abs(near - f0) < 0.06])); at = f0
        line = []; xs = []; ys = []
        for lo, hi in zip(edges[:-1], edges[1:]):
            z = W[(W[:, 1] >= lo) & (W[:, 1] < hi), 0]; z = z[np.abs(z - at) < 0.12]
            if len(z) < 25: line.append("  -- "); continue
            at = float(np.median(z)); line.append("%+4.0f" % ((at - f0) * 1000)); xs.append((lo + hi) / 2); ys.append(at - f0)
        slope = np.polyfit(xs, ys, 1)[0] * 1000 if len(xs) >= 3 else float("nan")
        print("%s: floor %.2f m under the head; mm by distance %s: %s   slope %+.0f mm per m" % (name, head_z - f0, " ".join("%.1f" % lo for lo in edges[:-1]), " ".join(line), slope))
    return 0

if __name__ == "__main__": sys.exit(main())
