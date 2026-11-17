#!/usr/bin/env python3
"""Independent validation of the stereo epipolar threshold on recorded pairs.

  python stereo_threshold_validation.py basalt_calib.json capture1.bin [capture2.bin ...]

For every stereo pair it produces candidate matches the way the estimator does (one FAST corner
per 50 px cell in the left image, Lucas-Kanade search from calibrated depth hypotheses, forward-
backward check), then judges each candidate against a reference that does not use the
calibration's extrinsics: the fundamental matrix fitted to SIFT matches of that same pair.
A candidate more than 2.5 px (Sampson) from that geometry is counted as a false match.

Reported per threshold: accepted candidates, false matches among them, and the triangulated
depth and reprojection error of the accepted ones using the calibration. It also fits a small
rotation correction to the calibration over all pairs to show how much of the epipolar error is
a constant offset.
"""
import json, sys
import numpy as np, cv2
sys.path.insert(0, __import__("os").path.dirname(__file__))
from stereo_capture_analysis import Kb4, load_pairs, quat_R

def skew(t): return np.array([[0, -t[2], t[1]], [t[2], 0, -t[0]], [-t[1], t[0], 0]])
def rodrigues(w):
    th = np.linalg.norm(w)
    if th < 1e-12: return np.eye(3)
    k = skew(w / th); return np.eye(3) + np.sin(th) * k + (1 - np.cos(th)) * k @ k

def sampson(F, a, b):
    A = np.hstack([a, np.ones((len(a), 1))]); B = np.hstack([b, np.ones((len(b), 1))])
    Fa = A @ F.T; Fb = B @ F
    num = np.einsum("ij,ij->i", B, Fa) ** 2
    return np.sqrt(num / (Fa[:, 0] ** 2 + Fa[:, 1] ** 2 + Fb[:, 0] ** 2 + Fb[:, 1] ** 2 + 1e-12))

def triangulate(ra, rb, R01, t01):
    """Midpoint triangulation of unit rays ra (cam0) and rb (cam1); returns points in cam0 and the ray gap."""
    d1 = ra; d2 = (R01 @ rb.T).T
    a = np.einsum("ij,ij->i", d1, d1); b = np.einsum("ij,ij->i", d1, d2); c = np.einsum("ij,ij->i", d2, d2)
    d = d1 @ t01; e = d2 @ t01
    den = a * c - b * b + 1e-12
    s = (d * c - b * e) / den; t = (b * d - a * e) / den
    p1 = d1 * s[:, None]; p2 = t01 + d2 * t[:, None]
    return (p1 + p2) / 2, np.linalg.norm(p1 - p2, axis=1), s, t

def main():
    cal = json.load(open(sys.argv[1])); cal = cal.get("value0", cal)
    cams = [Kb4(c["intrinsics"]) for c in cal["intrinsics"]]
    R = [quat_R(t) for t in cal["T_imu_cam"]]; p = [np.array([t["px"], t["py"], t["pz"]]) for t in cal["T_imu_cam"]]
    R01 = R[0].T @ R[1]; t01 = R[0].T @ (p[1] - p[0]); R10 = R01.T; t10 = -R01.T @ t01
    def essential(Rm): return skew(t01 / np.linalg.norm(t01)) @ Rm
    E = essential(R01)
    depths = [1.7, 0.85, 3.4, 0.476, 100.0]
    lk = dict(winSize=(11, 11), maxLevel=3, criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 20, 0.03))
    all_ra, all_rb = [], []
    cand = []      # per candidate: calibration epipolar error, Sampson distance to the pair's own geometry, rays
    scenes = []
    for path in sys.argv[2:]:
        for L, Rr in load_pairs(path):
            sift = cv2.SIFT_create(2500, contrastThreshold=0.02)
            k1, d1 = sift.detectAndCompute(L, None); k2, d2 = sift.detectAndCompute(Rr, None)
            if d1 is None or d2 is None or len(k1) < 20 or len(k2) < 20: continue
            good = [m for m, n in cv2.BFMatcher().knnMatch(d1, d2, k=2) if m.distance < 0.75 * n.distance]
            if len(good) < 25: continue
            a = np.float32([k1[g.queryIdx].pt for g in good]); b = np.float32([k2[g.trainIdx].pt for g in good])
            F, mask = cv2.findFundamentalMat(a, b, cv2.FM_RANSAC, 1.5, 0.995)
            if F is None: continue
            inl = mask.ravel() == 1; a, b = a[inl], b[inl]
            all_ra.append(cams[0].unproject(a)); all_rb.append(cams[1].unproject(b))
            fast = cv2.FastFeatureDetector_create(threshold=12); corners = []
            for y0 in range(0, 640, 50):
                for x0 in range(0, 480, 50):
                    kp = fast.detect(L[y0:y0 + 50, x0:x0 + 50])
                    if kp: m = max(kp, key=lambda q: q.response); corners.append((x0 + m.pt[0], y0 + m.pt[1]))
            c = np.float32(corners)
            if len(c) == 0: continue
            rc = cams[0].unproject(c); found = np.zeros(len(c), bool); pos = np.zeros((len(c), 2), np.float32)
            for d in depths:
                todo = np.where(~found)[0]
                if len(todo) == 0: break
                p0 = c[todo].reshape(-1, 1, 2); g = cams[1].project((R10 @ (rc[todo] * d).T).T + t10).astype(np.float32).reshape(-1, 1, 2)
                inside = (g[:, 0, 0] > 8) & (g[:, 0, 0] < 472) & (g[:, 0, 1] > 8) & (g[:, 0, 1] < 632)
                p1, s1, _ = cv2.calcOpticalFlowPyrLK(L, Rr, p0, g.copy(), flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **lk)
                pb, s2, _ = cv2.calcOpticalFlowPyrLK(Rr, L, p1, p0.copy(), flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **lk)
                ok = inside & (s1.ravel() == 1) & (s2.ravel() == 1) & (np.linalg.norm((pb - p0).reshape(-1, 2), axis=1) < 0.3)
                found[todo[ok]] = True; pos[todo[ok]] = p1.reshape(-1, 2)[ok]
            ca, cb = c[found], pos[found]
            if len(ca) == 0: scenes.append((path, len(c), 0)); continue
            ra, rb = cams[0].unproject(ca), cams[1].unproject(cb)
            ep = np.abs(np.einsum("ij,jk,ik->i", ra, E, rb)); sd = sampson(F, ca, cb)
            for i in range(len(ca)): cand.append((ep[i], sd[i], ra[i], rb[i], L.mean()))
            scenes.append((path, len(c), int(found.sum())))
    if not cand: print("no candidates"); return
    ep = np.array([x[0] for x in cand]); sd = np.array([x[1] for x in cand]); ra = np.array([x[2] for x in cand]); rb = np.array([x[3] for x in cand])
    truth_bad = sd > 2.5
    print("pairs used: %d; grid corners: %d; candidates after the forward-backward check: %d" % (len(scenes), sum(s[1] for s in scenes), len(cand)))
    print("candidates the pair's own geometry calls false (Sampson > 2.5 px): %d (%.0f%%)" % (truth_bad.sum(), 100 * truth_bad.mean()))
    print("\nthreshold  accepted  false among accepted  true rejected  median depth  depth<0.3m or behind  ray gap p90")
    for thr in (0.005, 0.01, 0.02, 0.04, 1e9):
        acc = ep < thr
        P, gap, s, t = triangulate(ra[acc], rb[acc], R01, t01)
        bad_depth = (s <= 0.3) | (t <= 0) if acc.any() else np.array([])
        print("%-9s  %5d     %4d (%4.1f%%)          %4d           %5.2f m        %4d                %.1f mm" % (
            "none" if thr > 1 else thr, acc.sum(), (acc & truth_bad).sum(), 100 * (acc & truth_bad).sum() / max(1, acc.sum()),
            (~acc & ~truth_bad).sum(), np.median(s) if acc.any() else float("nan"), int(bad_depth.sum()) if acc.any() else 0,
            1000 * np.percentile(gap, 90) if acc.any() else float("nan")))
    # how much of the epipolar error is a constant rotation offset in the calibration?
    A = np.vstack(all_ra); B = np.vstack(all_rb)
    def cost(w): return np.abs(np.einsum("ij,jk,ik->i", A, essential(R01 @ rodrigues(w)), B))
    w = np.zeros(3); step = 2e-3
    for _ in range(60):
        base = np.median(cost(w)); improved = False
        for i in range(3):
            for sgn in (1, -1):
                w2 = w.copy(); w2[i] += sgn * step
                if np.median(cost(w2)) < base - 1e-7: w = w2; base = np.median(cost(w2)); improved = True
        if not improved: step /= 2
        if step < 2e-5: break
    e0 = cost(np.zeros(3)); e1 = cost(w)
    print("\nreference matches (SIFT inliers) over all pairs: %d" % len(A))
    print("epipolar error with the calibration as is: median %.4f, within 0.005: %.0f%%" % (np.median(e0), 100 * np.mean(e0 < 0.005)))
    print("after a constant rotation correction of %.2f deg (x %.2f, y %.2f, z %.2f deg): median %.4f, within 0.005: %.0f%%"
          % (np.degrees(np.linalg.norm(w)), *np.degrees(w), np.median(e1), 100 * np.mean(e1 < 0.005)))

if __name__ == "__main__":
    main()
