#!/usr/bin/env python3
"""Does the calibration agree with what the two cameras actually see? (from a recording, on this machine)

  python stereo_check.py RECORDING.uxrec BASALT_CALIB.json [--pairs N] [--out DIR]

The position estimator (Basalt) follows a point from the left camera into the right one and keeps it only if the
two sight lines meet: the "epipolar error" must be under optical_flow_epipolar_error (0.005 in our configuration).
Points that pass give position its real scale. On the Pixel the log shows 10 to 25 of about 150 points passing.

This script finds left-to-right matches from the pictures alone (optical flow with a forward-backward check and a
geometry test that does not use the calibration), then asks the calibration about each of them:

  * the epipolar error of every real match, against the estimator's limit: how many real matches the limit throws away
  * how the search from the left picture into the right does, for different starting guesses and pyramid depths,
    which is what the estimator's stereo_guess_depth and optical_flow_levels settings control
  * the best single correction to the right camera's rotation, to tell a small mounting error from noise

Needs numpy and opencv-python. Writes nothing but numbers (and stereo_check.json with --out).
"""
import json, os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar

def quat_R(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])

def unproject_kb4(p, k):
    """Pixels (N,2) to unit sight lines (N,3) for a Kannala-Brandt camera."""
    mx = (p[:, 0] - k["cx"]) / k["fx"]; my = (p[:, 1] - k["cy"]) / k["fy"]
    r = np.sqrt(mx * mx + my * my); th = r.copy()
    for _ in range(8):
        t2 = th * th
        f = th * (1 + t2 * (k["k1"] + t2 * (k["k2"] + t2 * (k["k3"] + t2 * k["k4"])))) - r
        d = 1 + t2 * (3 * k["k1"] + t2 * (5 * k["k2"] + t2 * (7 * k["k3"] + t2 * 9 * k["k4"])))
        th = th - f / d
    s = np.where(r > 1e-9, np.sin(th) / np.maximum(r, 1e-9), 1.0)
    return np.stack([mx * s, my * s, np.cos(th)], axis=1)

def project_kb4(v, k):
    x, y, z = v[:, 0], v[:, 1], v[:, 2]
    r = np.sqrt(x * x + y * y); th = np.arctan2(r, z); t2 = th * th
    d = th * (1 + t2 * (k["k1"] + t2 * (k["k2"] + t2 * (k["k3"] + t2 * k["k4"]))))
    s = np.where(r > 1e-9, d / np.maximum(r, 1e-9), 1.0)
    return np.stack([k["fx"] * x * s + k["cx"], k["fy"] * y * s + k["cy"]], axis=1)

def skew(t): return np.array([[0, -t[2], t[1]], [t[2], 0, -t[0]], [-t[1], t[0], 0]])

def epipolar_error(b0, b1, R10, t10):
    """Basalt's measure: |b1 . E . b0| for unit sight lines, E from the pose of camera 0 in camera 1."""
    E = skew(t10 / np.linalg.norm(t10)) @ R10
    return np.abs(np.einsum("ni,ij,nj->n", b1, E, b0))

def flow(left, right, pts, guess, levels):
    """Follow points from left to right starting at pts + guess; keep those that come back to where they started."""
    p0 = pts.reshape(-1, 1, 2).astype(np.float32); init = (p0 + np.float32(guess)).copy()
    lk = dict(winSize=(21, 21), maxLevel=levels, criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01))
    p1, st, _ = cv2.calcOpticalFlowPyrLK(left, right, p0, init, flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **lk)
    back, st2, _ = cv2.calcOpticalFlowPyrLK(right, left, p1, p0.copy(), flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **lk)
    ok = (st.ravel() == 1) & (st2.ravel() == 1) & (np.linalg.norm((back - p0).reshape(-1, 2), axis=1) < 0.7)
    inside = (p1[:, 0, 0] > 4) & (p1[:, 0, 0] < right.shape[1] - 4) & (p1[:, 0, 1] > 4) & (p1[:, 0, 1] < right.shape[0] - 4)
    return p1.reshape(-1, 2), ok & inside

def main():
    a = sys.argv[1:]; npairs = 60; out = None
    if "--pairs" in a: i = a.index("--pairs"); npairs = int(a[i + 1]); del a[i:i + 2]
    if "--out" in a: i = a.index("--out"); out = a[i + 1]; del a[i:i + 2]
    if len(a) != 2: print(__doc__); return 2
    c = json.load(open(a[1]))["value0"]
    k0 = c["intrinsics"][0]["intrinsics"]; k1 = c["intrinsics"][1]["intrinsics"]
    def pose(o): return quat_R([o["qx"], o["qy"], o["qz"], o["qw"]]), np.array([o["px"], o["py"], o["pz"]])
    R0, t0 = pose(c["T_imu_cam"][0]); R1, t1 = pose(c["T_imu_cam"][1])
    R10 = R1.T @ R0; t10 = R1.T @ (t0 - t1)          # camera 0 in camera 1
    base = float(np.linalg.norm(t10)); angle = float(np.degrees(np.arccos(np.clip((np.trace(R10) - 1) / 2, -1, 1))))

    total = sum(1 for kind, _, _ in ar.records(a[0]) if kind == 1); step = max(1, (total // 2) // npairs)
    pend = {}; pairs = []; times = []; n = 0
    for kind, t, data in ar.records(a[0]):
        if kind != 1: continue
        dev, counter, eye = ar.meta(data)
        if eye == 0:
            n += 1
            if n % step == 0: pend[counter] = ar.decode(data)[0]
        elif eye == 1 and counter in pend: pairs.append((pend.pop(counter), ar.decode(data)[0])); times.append(t)

    # Where a point at a given depth lands in the right picture, by the calibration: the guess the search should start from.
    centre = np.array([[240.0, 320.0]])
    def predicted_shift(depth): return (project_kb4((R10 @ (unproject_kb4(centre, k0) * depth).T).T + t10, k1) - centre)[0]
    guesses = {"none (0 px)": np.zeros(2)}
    for depth in (0.5, 1.0, 1.7, 3.0, 8.0): guesses["calibration at %.1f m" % depth] = predicted_shift(depth)

    real = []; table = {(g, lv): [] for g in guesses for lv in (3, 4, 5)}
    for (left, right), when in zip(pairs, times):
        pts = cv2.goodFeaturesToTrack(left, 150, 0.01, 12)
        if pts is None or len(pts) < 20: continue
        pts = pts.reshape(-1, 2)
        best = None
        for g, gv in guesses.items():
            for lv in (3, 4, 5):
                p1, ok = flow(left, right, pts, gv, lv)
                table[(g, lv)].append(float(ok.mean()))
                if lv == 5 and (best is None or ok.sum() > best[1].sum()): best = (p1, ok)
        p1, ok = best
        if ok.sum() >= 12:
            F, mask = cv2.findFundamentalMat(pts[ok], p1[ok], cv2.FM_RANSAC, 1.5, 0.99)
            if mask is not None:
                keep = mask.ravel() == 1
                real.append((pts[ok][keep], p1[ok][keep], when))
    pl = np.vstack([r[0] for r in real]); pr = np.vstack([r[1] for r in real])
    b0 = unproject_kb4(pl, k0); b1 = unproject_kb4(pr, k1)
    err = epipolar_error(b0, b1, R10, t10)
    R = {"pairs": len(pairs), "baseline_m": base, "angle_between_cameras_deg": angle, "real_matches": int(len(err)),
         "epipolar_error_median": float(np.median(err)), "epipolar_error_p90": float(np.percentile(err, 90)),
         "share_of_real_matches_within_0.005": float((err < 0.005).mean()), "share_within_0.01": float((err < 0.01).mean()), "share_within_0.02": float((err < 0.02).mean()),
         "search_success_share_by_start_and_pyramid_levels": {"%s, %d levels" % k: float(np.mean(v)) for k, v in table.items() if v},
         "predicted_shift_px": {g: [float(v[0]), float(v[1])] for g, v in guesses.items()}}

    # A small rotation of the right camera that would make the real matches agree best (Gauss-Newton on the same error).
    def fit(b0, b1):
        w = np.zeros(3)
        def errs(w):
            Rw, _ = cv2.Rodrigues(w.reshape(3, 1)); E = skew(t10 / np.linalg.norm(t10)) @ (Rw @ R10)
            return np.einsum("ni,ij,nj->n", b1, E, b0)
        for _ in range(12):
            e = errs(w); J = np.zeros((len(e), 3))
            for i in range(3):
                d = np.zeros(3); d[i] = 1e-5; J[:, i] = (errs(w + d) - e) / 1e-5
            keep = np.abs(e) < max(0.02, 3 * np.median(np.abs(e)))
            w = w - np.linalg.lstsq(J[keep], e[keep], rcond=None)[0]
        return w, np.abs(errs(w))
    w, e2 = fit(b0, b1)
    # The same fit on each quarter of the recording by itself: a mounting error is the same all along, noise is not.
    t_first = times[0]; span = max(1, times[-1] - t_first); R["correction_by_quarter_deg"] = []
    for q in range(4):
        sel = [r for r in real if q * span / 4 <= r[2] - t_first < (q + 1) * span / 4 + (1 if q == 3 else 0)]
        if sum(len(r[0]) for r in sel) < 60: R["correction_by_quarter_deg"].append(None); continue
        wq, _ = fit(unproject_kb4(np.vstack([r[0] for r in sel]), k0), unproject_kb4(np.vstack([r[1] for r in sel]), k1))
        R["correction_by_quarter_deg"].append([round(float(v), 3) for v in np.degrees(wq)])
    R["best_rotation_correction_deg"] = [float(v) for v in np.degrees(w)]
    R["best_rotation_correction_total_deg"] = float(np.degrees(np.linalg.norm(w)))
    R["after_correction_epipolar_error_median"] = float(np.median(e2)); R["after_correction_share_within_0.005"] = float((e2 < 0.005).mean())
    print(json.dumps(R, indent=1))
    if out: os.makedirs(out, exist_ok=True); json.dump(R, open(os.path.join(out, "stereo_check.json"), "w"), indent=1)
    return 0

if __name__ == "__main__":
    sys.exit(main())
