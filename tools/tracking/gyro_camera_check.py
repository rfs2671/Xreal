#!/usr/bin/env python3
"""Is each camera turned against the motion sensor the way the calibration says? (from a recording, on this machine)

  python gyro_camera_check.py RECORDING.uxrec BASALT_CALIB.json [--from-to A,B] [--step N]

When the head turns, the gyroscope measures the turn in the motion sensor's frame, and each camera sees the same turn
in its own frame (from how the picture moves between two frames, with the calibration's lens model). The two are the
same turn seen from two frames, so over many turns about different axes they give the rotation between the frames:
camera to motion sensor. That is compared with the rotation the calibration holds (T_imu_cam), which the position
estimator and everything built on it trusts. The delay between camera and motion sensor clocks is found the same
way (the delay at which the two agree best).

Uses nothing but the recording and the calibration's lens models. Needs numpy and opencv-python.
"""
import json, os, struct, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar
from stereo_check import quat_R, unproject_kb4

def rotvec(R):
    v, _ = cv2.Rodrigues(R); return v.ravel()

def integrate(ts, w, t0, t1):
    """The turn between device times t0 and t1 (ns) from gyro samples (rad/s), as a rotation matrix (frame at t1 in frame at t0)."""
    i0 = np.searchsorted(ts, t0); i1 = np.searchsorted(ts, t1)
    R = np.eye(3); t = t0
    for i in range(max(i0 - 1, 0), min(i1 + 1, len(ts) - 1)):
        a = max(ts[i], t0); b = min(ts[i + 1], t1)
        if b <= a: continue
        dR, _ = cv2.Rodrigues((w[i] * (b - a) * 1e-9).reshape(3, 1)); R = R @ dR
    return R

def kabsch(a, b, wts=None):
    """Rotation R with b ~ R a."""
    if wts is None: wts = np.ones(len(a))
    H = (a * wts[:, None]).T @ b
    U, S, Vt = np.linalg.svd(H); d = np.sign(np.linalg.det(Vt.T @ U.T))
    return Vt.T @ np.diag([1, 1, d]) @ U.T

def main():
    a = sys.argv[1:]; span = (0.0, 1e9); step = 3
    if "--from-to" in a: i = a.index("--from-to"); span = tuple(float(v) for v in a[i + 1].split(",")); del a[i:i + 2]
    if "--step" in a: i = a.index("--step"); step = int(a[i + 1]); del a[i:i + 2]
    if len(a) != 2: print(__doc__); return 2
    c = json.load(open(a[1]))["value0"]
    ks = [c["intrinsics"][i]["intrinsics"] for i in (0, 1)]
    Ric = [quat_R([c["T_imu_cam"][i][k] for k in ("qx", "qy", "qz", "qw")]) for i in (0, 1)]
    off = c.get("cam_time_offset_ns", 0)
    its = []; gy = []; frames = {0: [], 1: []}; first = None
    for kind, t, data in ar.records(a[0]):
        if first is None: first = t
        s = (t - first) / 1e9
        if kind == 2:
            if len(data) < 42 or data[0] != 1 or data[1] != 2: continue
            its.append(struct.unpack_from("<q", data, 4)[0]); gy.append([ar.s24(data, o) for o in (18, 21, 24)])
        elif span[0] <= s < span[1]:
            dev, counter, eye = ar.meta(data)
            if eye <= 1: frames[eye].append((dev, ar.decode(data)[0]))
    its = np.array(its, np.int64); w = np.radians(np.array(gy, float) * (2000.0 / (1 << 23)))
    order = np.argsort(its); its = its[order]; w = w[order]
    # the gyro's own zero: its reading over the calmest tenth of the recording
    mag = np.linalg.norm(w - np.median(w, axis=0), axis=1); bias = w[mag < np.percentile(mag, 10)].mean(axis=0)
    w = w - bias
    print("gyro: %d samples, zero offset %s rad/s; camera frames: %d left, %d right" % (len(its), np.round(bias, 4), len(frames[0]), len(frames[1])))
    lk = dict(winSize=(21, 21), maxLevel=3, criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01))
    for cam in (0, 1):
        fr = frames[cam]; pairs = []
        for i in range(0, len(fr) - step, step):
            t0, im0 = fr[i]; t1, im1 = fr[i + step]
            if not (0 < t1 - t0 < 0.3e9): continue
            p0 = cv2.goodFeaturesToTrack(im0, 250, 0.01, 10)
            if p0 is None or len(p0) < 60: continue
            p1, st, _ = cv2.calcOpticalFlowPyrLK(im0, im1, p0, None, **lk)
            back, st2, _ = cv2.calcOpticalFlowPyrLK(im1, im0, p1, None, **lk)
            ok = (st.ravel() == 1) & (st2.ravel() == 1) & (np.linalg.norm((back - p0).reshape(-1, 2), axis=1) < 0.5)
            if ok.sum() < 50: continue
            b0 = unproject_kb4(p0.reshape(-1, 2)[ok].astype(float), ks[cam]); b1 = unproject_kb4(p1.reshape(-1, 2)[ok].astype(float), ks[cam])
            front = (b0[:, 2] > 0.35) & (b1[:, 2] > 0.35)
            if front.sum() < 40: continue
            n0 = (b0[front, :2] / b0[front, 2:3]).astype(np.float64); n1 = (b1[front, :2] / b1[front, 2:3]).astype(np.float64)
            E, mask = cv2.findEssentialMat(n0, n1, np.eye(3), cv2.RANSAC, 0.999, 0.004)
            if E is None or E.shape != (3, 3) or mask.sum() < 30: continue
            _, R, tvec, m2 = cv2.recoverPose(E, n0, n1, np.eye(3), mask=mask.copy())
            pairs.append((t0, t1, -rotvec(R), int(mask.sum())))          # the camera's own turn between the frames, in its frame
        if len(pairs) < 50: print("camera %d: too few usable frame pairs (%d)" % (cam, len(pairs))); continue
        vc = np.array([p[2] for p in pairs])
        def imu_turns(delay_ns): return np.array([rotvec(integrate(its, w, p[0] + off + delay_ns, p[1] + off + delay_ns)) for p in pairs])
        best = None
        for delay_ms in range(-40, 41, 4):
            vi = imu_turns(int(delay_ms * 1e6))
            big = (np.linalg.norm(vi, axis=1) > np.radians(0.6)) & (np.linalg.norm(vc, axis=1) > np.radians(0.6))
            R = kabsch(vc[big], vi[big]); res = np.linalg.norm(vi[big] - vc[big] @ R.T, axis=1)
            keep = res < 3 * np.median(res); R = kabsch(vc[big][keep], vi[big][keep]); res = np.linalg.norm(vi[big][keep] - vc[big][keep] @ R.T, axis=1)
            score = float(np.median(res) / np.median(np.linalg.norm(vi[big][keep], axis=1)))
            if best is None or score < best[0]: best = (score, delay_ms, R, int(keep.sum()), vi[big][keep], vc[big][keep])
        score, delay_ms, R, n, vi, vc2 = best
        d = Ric[cam].T @ R; ang = np.degrees(np.linalg.norm(rotvec(d))); axis = rotvec(d) / max(np.linalg.norm(rotvec(d)), 1e-12)
        # how well each axis of turning is covered (a turn about one axis only cannot fix the rotation)
        cov = np.linalg.svd(vi, compute_uv=False); cov = cov / cov[0]
        # which calibration fits the turns better, in degrees of leftover per turn
        left_cal = np.degrees(np.median(np.linalg.norm(vi - vc2 @ Ric[cam].T, axis=1))); left_fit = np.degrees(np.median(np.linalg.norm(vi - vc2 @ R.T, axis=1)))
        print("camera %d: %d turns of over 0.6 degrees (spread over the three axes %.2f / %.2f / %.2f)" % (cam, n, cov[0], cov[1], cov[2]))
        print("  measured camera-to-sensor rotation differs from the calibration's by %.2f degrees, about the camera's axis (%.2f, %.2f, %.2f)  [x right, y down, z forward]" % (ang, *(axis)))
        print("  leftover per turn: %.3f deg with the calibration's rotation, %.3f deg with the measured one (a turn is %.2f deg in the middle)" % (left_cal, left_fit, np.degrees(np.median(np.linalg.norm(vi, axis=1)))))
        print("  the camera's clock agrees best with the sensor's at a further delay of %+d ms (calibration: %.2f ms)" % (delay_ms, off / 1e6))
        # stability: the same from each third of the pairs
        parts = []
        for k in range(3):
            sl = slice(k * len(vi) // 3, (k + 1) * len(vi) // 3)
            Rk = kabsch(vc2[sl], vi[sl]); dk = Ric[cam].T @ Rk; parts.append(np.degrees(rotvec(dk)))
        print("  by thirds of the recording, as turn about x / y / z in degrees: " + "; ".join("%+.2f %+.2f %+.2f" % tuple(p) for p in parts))
    return 0

if __name__ == "__main__": sys.exit(main())
