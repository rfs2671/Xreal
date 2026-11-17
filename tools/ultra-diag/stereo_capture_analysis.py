#!/usr/bin/env python3
"""Offline check of the Air 2 Ultra stereo pair against the calibration the estimator uses.

  python stereo_capture_analysis.py camera_raw_frames.bin basalt_calib.json [out_prefix]

camera_raw_frames.bin: raw frames saved by the app's hardware check; basalt_calib.json: the
calibration the app hands to the estimator (written to its external files directory).
Needs numpy and opencv-python. Reads files only.

It answers, with independent feature matches (SIFT + RANSAC) as the reference:
  * do true matches satisfy the calibration's epipolar constraint (Basalt rejects > 0.005)?
  * how far is the true right-image position from the positions the stereo search starts at?
  * for corners detected the way the estimator does (one FAST corner per 50 px cell), how many
    are found by a Lucas-Kanade search from those starts, and where are the rest lost?
"""
import json, sys
import numpy as np, cv2

MAP = [119, 54, 21, 0, 108, 22, 51, 63, 93, 99, 67, 7, 32, 112, 52, 43, 14, 35, 75, 116, 64, 71, 44, 89, 18, 88, 26, 61, 70, 56,
       90, 79, 87, 120, 81, 101, 121, 17, 72, 31, 53, 124, 127, 113, 111, 36, 48, 19, 37, 83, 126, 74, 109, 5, 84, 41, 76, 30, 110, 29,
       12, 115, 28, 102, 105, 62, 103, 20, 3, 68, 49, 77, 117, 125, 106, 60, 69, 98, 9, 16, 78, 47, 40, 2, 118, 34, 13, 50, 46, 80,
       85, 66, 42, 123, 122, 96, 11, 25, 97, 39, 6, 86, 1, 8, 82, 92, 59, 104, 24, 15, 73, 65, 38, 58, 10, 23, 33, 55, 57, 107,
       100, 94, 27, 95, 45, 91, 4, 114]
REV = [0] * 128
for i, v in enumerate(MAP): REV[v] = i
FB, IB = 640 * 482, 640 * 480

def decode(fr):
    off = REV[int(np.argmin([int(fr[2400 * i:2400 * i + 128].sum()) for i in range(128)]))]
    return np.concatenate([fr[2400 * MAP[(off + i) % 128]:2400 * MAP[(off + i) % 128] + 2400] for i in range(128)]).reshape(480, 640)

def load_pairs(path):
    raw = np.fromfile(path, dtype=np.uint8); eyes = {}
    for k in range(len(raw) // FB):
        fr = raw[k * FB:(k + 1) * FB]; eye = int(fr[IB + 59]); t = int.from_bytes(bytes(fr[IB + 62:IB + 70]), "little")
        img = decode(fr)
        # the app's orientation: left = rotate 90 counter-clockwise, right = rotate 90 clockwise
        eyes.setdefault(t, {})[eye] = np.ascontiguousarray(img[:, ::-1].T) if eye == 0 else np.ascontiguousarray(img[::-1, :].T)
    return [(v[0], v[1]) for v in eyes.values() if 0 in v and 1 in v]

class Kb4:
    def __init__(self, d): self.fx, self.fy, self.cx, self.cy = d["fx"], d["fy"], d["cx"], d["cy"]; self.k = [d["k1"], d["k2"], d["k3"], d["k4"]]
    def project(self, P):
        P = np.asarray(P, float); x, y, z = P[:, 0], P[:, 1], P[:, 2]
        r = np.hypot(x, y); th = np.arctan2(r, z); t2 = th * th
        d = th * (1 + self.k[0] * t2 + self.k[1] * t2 ** 2 + self.k[2] * t2 ** 3 + self.k[3] * t2 ** 4)
        s = np.where(r > 1e-9, d / np.maximum(r, 1e-9), 1.0)
        return np.stack([self.fx * s * x + self.cx, self.fy * s * y + self.cy], 1)
    def unproject(self, uv):
        uv = np.asarray(uv, float); mx = (uv[:, 0] - self.cx) / self.fx; my = (uv[:, 1] - self.cy) / self.fy
        rd = np.hypot(mx, my); th = rd.copy()
        for _ in range(12):
            t2 = th * th
            f = th * (1 + self.k[0] * t2 + self.k[1] * t2 ** 2 + self.k[2] * t2 ** 3 + self.k[3] * t2 ** 4) - rd
            df = 1 + 3 * self.k[0] * t2 + 5 * self.k[1] * t2 ** 2 + 7 * self.k[2] * t2 ** 3 + 9 * self.k[3] * t2 ** 4
            th = th - f / df
        s = np.where(rd > 1e-9, np.sin(th) / np.maximum(rd, 1e-9), 1.0)
        return np.stack([s * mx, s * my, np.cos(th)], 1)

def quat_R(q):
    x, y, z, w = q["qx"], q["qy"], q["qz"], q["qw"]
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])

def main():
    pairs = load_pairs(sys.argv[1]); cal = json.load(open(sys.argv[2])); cal = cal.get("value0", cal)
    out = sys.argv[3] if len(sys.argv) > 3 else None
    cams = [Kb4(c["intrinsics"]) for c in cal["intrinsics"]]
    R = [quat_R(t) for t in cal["T_imu_cam"]]; p = [np.array([t["px"], t["py"], t["pz"]]) for t in cal["T_imu_cam"]]
    R01 = R[0].T @ R[1]; t01 = R[0].T @ (p[1] - p[0])          # T_0_1: point in cam1 -> cam0
    R10 = R01.T; t10 = -R01.T @ t01
    tn = t01 / np.linalg.norm(t01)
    E = np.array([[0, -tn[2], tn[1]], [tn[2], 0, -tn[0]], [-tn[1], tn[0], 0]]) @ R01     # as Basalt's computeEssential
    print("pairs in capture: %d; baseline %.1f mm; angle between cameras %.1f deg" % (len(pairs), 1000 * np.linalg.norm(t01), np.degrees(np.arccos((np.trace(R01) - 1) / 2))))
    depths = [1.7, 0.85, 3.4, 0.476, 100.0]
    for n, (L, Rr) in enumerate(pairs):
        print("\n=== pair %d: mean brightness left %.0f right %.0f ===" % (n, L.mean(), Rr.mean()))
        sift = cv2.SIFT_create(2500, contrastThreshold=0.02)
        k1, d1 = sift.detectAndCompute(L, None); k2, d2 = sift.detectAndCompute(Rr, None)
        if d1 is None or d2 is None or len(k1) < 8 or len(k2) < 8: print("too few features"); continue
        good = [a for a, b in cv2.BFMatcher().knnMatch(d1, d2, k=2) if a.distance < 0.75 * b.distance]
        if len(good) < 12: print("only %d independent matches" % len(good)); continue
        a = np.float32([k1[g.queryIdx].pt for g in good]); b = np.float32([k2[g.trainIdx].pt for g in good])
        _F, mask = cv2.findFundamentalMat(a, b, cv2.FM_RANSAC, 2.0, 0.99); inl = mask.ravel() == 1; a, b = a[inl], b[inl]
        ra, rb = cams[0].unproject(a), cams[1].unproject(b)
        ep = np.abs(np.einsum("ij,jk,ik->i", ra, E, rb))
        print("reference matches (SIFT + RANSAC): %d of %d / %d keypoints" % (len(a), len(k1), len(k2)))
        print("  epipolar error with the calibration: median %.4f, p90 %.4f; within Basalt's 0.005: %d%%, within 0.02: %d%%"
              % (np.median(ep), np.percentile(ep, 90), 100 * np.mean(ep < 0.005), 100 * np.mean(ep < 0.02)))
        print("  right minus left (px): dx median %.1f (p10 %.1f, p90 %.1f), dy median %.1f (p10 %.1f, p90 %.1f)"
              % tuple(np.percentile((b - a)[:, i], q) for i in (0, 1) for q in (50, 10, 90)))
        starts = np.stack([cams[1].project((R10 @ (ra * d).T).T + t10) for d in depths], 1)      # [match, depth, 2]
        err = np.linalg.norm(starts - b[:, None, :], axis=2)
        best = err.min(1)
        print("  distance from the nearest search start to the true position: median %.1f px, p90 %.1f px; within 8 px %d%%, 16 px %d%%, 30 px %d%%"
              % (np.median(best), np.percentile(best, 90), 100 * np.mean(best < 8), 100 * np.mean(best < 16), 100 * np.mean(best < 30)))
        print("  same-pixel start (upstream) to the true position: median %.1f px" % np.median(np.linalg.norm(b - a, axis=1)))
        # corners the way the estimator picks them: strongest FAST corner per 50 px cell of the left image
        fast = cv2.FastFeatureDetector_create(threshold=40); corners = []
        for y0 in range(0, 640, 50):
            for x0 in range(0, 480, 50):
                kp = fast.detect(L[y0:y0 + 50, x0:x0 + 50])
                if kp:
                    m = max(kp, key=lambda q: q.response); corners.append((x0 + m.pt[0], y0 + m.pt[1]))
        c = np.float32(corners)
        if len(c) == 0: print("no FAST corners"); continue
        rc = cams[0].unproject(c)
        st = np.stack([cams[1].project((R10 @ (rc * d).T).T + t10) for d in depths], 1)
        inside = ((st[:, 0, 0] > 8) & (st[:, 0, 0] < 472) & (st[:, 0, 1] > 8) & (st[:, 0, 1] < 632))
        found = np.zeros(len(c), bool); found_ep = np.zeros(len(c), bool); pos = np.zeros((len(c), 2), np.float32)
        lk = dict(winSize=(11, 11), maxLevel=3, criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 20, 0.03))
        per_depth = []
        for j, d in enumerate(depths):
            todo = np.where(~found)[0]
            if len(todo) == 0: per_depth.append(0); continue
            p0 = c[todo].reshape(-1, 1, 2); g = st[todo, j].astype(np.float32).reshape(-1, 1, 2)
            p1, s1, _ = cv2.calcOpticalFlowPyrLK(L, Rr, p0, g.copy(), flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **lk)
            pb, s2, _ = cv2.calcOpticalFlowPyrLK(Rr, L, p1, p0.copy(), flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **lk)
            ok = (s1.ravel() == 1) & (s2.ravel() == 1) & (np.linalg.norm((pb - p0).reshape(-1, 2), axis=1) < 0.5)
            e2 = np.abs(np.einsum("ij,jk,ik->i", cams[0].unproject(c[todo]), E, cams[1].unproject(p1.reshape(-1, 2))))
            okk = ok & (e2 < 0.005)
            found[todo[okk]] = True; pos[todo[okk]] = p1.reshape(-1, 2)[okk]; found_ep[todo[ok]] = True
            per_depth.append(int(okk.sum()))
        print("  grid FAST corners in the left image: %d; search start inside the right image: %d" % (len(c), inside.sum()))
        print("  found by Lucas-Kanade from the starts, forward-backward < 0.5 px: %d; of those within epipolar 0.005: %d (per depth %s)"
              % (found_ep.sum(), found.sum(), per_depth))
        if out:
            vis = cv2.cvtColor(np.hstack([L, Rr]), cv2.COLOR_GRAY2BGR)
            for i in range(len(c)):
                col = (0, 255, 0) if found[i] else ((0, 165, 255) if inside[i] else (0, 0, 255))
                cv2.circle(vis, (int(c[i, 0]), int(c[i, 1])), 4, col, 1)
                if found[i]: cv2.circle(vis, (int(pos[i, 0]) + 480, int(pos[i, 1])), 4, col, 1)
            cv2.imwrite("%s_pair%d.png" % (out, n), vis)

if __name__ == "__main__":
    main()
