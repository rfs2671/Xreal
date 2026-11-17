#!/usr/bin/env python3
"""What a tracking recording (.uxrec, made by the app's recorder) contains, measured on this machine.

  python analyse_recording.py RECORDING.uxrec [--out DIR] [--pairs N]

Needs numpy and opencv-python. Reads the raw USB data the glasses sent and reports, without running the trackers:

  timing    motion (IMU) sample rate, the longest gaps, how late reports reached the phone; camera frame rate
            per eye, frames lost (gaps in the cameras' own pair counter), exposure brightness over time
  stereo    for N stereo pairs spread over the recording: how many image features the left camera has, and how
            many of them can be matched in the right camera at all (descriptor match checked against the epipolar
            geometry). That is the ceiling for the estimator's "seen by both cameras" count, which decides whether
            position has a real scale.
  motion    how much the wearer moved: rotation rate and the share of the time the glasses were still

The recording itself is private (it shows the room) and is never copied anywhere by this script; only numbers
and, with --out, a few small pictures are written, to a folder outside the repository.
"""
import json, os, struct, sys
import numpy as np, cv2

CHUNK_MAP = [119,54,21,0,108,22,51,63,93,99,67,7,32,112,52,43,14,35,75,116,64,71,44,89,18,88,26,61,70,56,90,79,87,120,81,101,121,17,72,31,53,124,127,113,111,36,48,19,37,83,126,74,109,5,84,41,76,30,110,29,12,115,28,102,105,62,103,20,3,68,49,77,117,125,106,60,69,98,9,16,78,47,40,2,118,34,13,50,46,80,85,66,42,123,122,96,11,25,97,39,6,86,1,8,82,92,59,104,24,15,73,65,38,58,10,23,33,55,57,107,100,94,27,95,45,91,4,114]
REVERSE = [0] * 128
for i, v in enumerate(CHUNK_MAP): REVERSE[v] = i
IMAGE = 640 * 480

def decode(frame):
    """One eye's picture as the trackers get it: unshuffled, then turned upright (left CCW, right CW)."""
    a = np.frombuffer(frame, np.uint8)[:IMAGE].reshape(128, 2400)
    off = REVERSE[int(np.argmin(a[:, :128].astype(np.int32).sum(axis=1)))]
    img = a[[CHUNK_MAP[(off + i) % 128] for i in range(128)]].reshape(480, 640)
    right = frame[IMAGE + 59] == 1
    return (np.rot90(img, -1) if right else np.rot90(img, 1)).copy(), right

def meta(frame):
    f = IMAGE
    return struct.unpack_from("<q", frame, f + 62)[0], struct.unpack_from("<H", frame, f + 18)[0], frame[f + 59]

def s24(b, o):
    v = b[o] | (b[o + 1] << 8) | (b[o + 2] << 16)
    return v - (1 << 24) if v & 0x800000 else v

def records(path):
    with open(path, "rb") as f:
        if f.readline() != b"UXREC1\n": raise SystemExit("not a recording")
        while True:
            h = f.read(13)
            if len(h) < 13: return
            kind, t, n = struct.unpack(">BqI", h)
            yield kind, t, f.read(n)

def records_imu_only(path):
    """Like records(), but camera frames are skipped over instead of read (they are most of the file)."""
    with open(path, "rb") as f:
        if f.readline() != b"UXREC1\n": raise SystemExit("not a recording")
        while True:
            h = f.read(13)
            if len(h) < 13: return
            kind, t, n = struct.unpack(">BqI", h)
            if kind == 2:
                data = f.read(n)
                if len(data) >= 42 and data[0] == 1 and data[1] == 2: yield kind, t, data
            else: f.seek(n, 1)

def pct(v, p): return float(np.percentile(v, p)) if len(v) else float("nan")

def main():
    a = sys.argv[1:]; out = None; npairs = 60
    if "--out" in a: i = a.index("--out"); out = a[i + 1]; del a[i:i + 2]
    if "--pairs" in a: i = a.index("--pairs"); npairs = int(a[i + 1]); del a[i:i + 2]
    if len(a) != 1: print(__doc__); return 2
    if out: os.makedirs(out, exist_ok=True)
    imu_dev = []; imu_host = []; gyro = []; accel = []
    cam = {0: [], 1: []}     # per eye: (host ns, device ns, pair counter, mean brightness)
    total = sum(1 for k, _, _ in records(a[0]) if k == 1)
    step = max(1, (total // 2) // npairs)
    pending = {}; pairs = []; seen_left = 0; first = None
    for kind, t, data in records(a[0]):
        if first is None: first = t
        if kind == 2:
            if len(data) < 42 or data[0] != 1 or data[1] != 2: continue
            imu_host.append(t); imu_dev.append(struct.unpack_from("<q", data, 4)[0])
            gyro.append([s24(data, o) for o in (18, 21, 24)]); accel.append([s24(data, o) for o in (33, 36, 39)])
        else:
            dev, counter, eye = meta(data)
            if eye > 1: continue
            b = int(np.frombuffer(data, np.uint8)[:IMAGE:97].mean())
            cam[eye].append((t, dev, counter, b))
            if eye == 0:
                seen_left += 1
                if seen_left % step == 0: pending[counter] = decode(data)[0]
            elif counter in pending:
                pairs.append(((t - first) / 1e9, pending.pop(counter), decode(data)[0]))
    R = {"seconds": (max(imu_host[-1], cam[0][-1][0]) - first) / 1e9}

    # ---- timing
    d = np.diff(np.array(imu_dev)) / 1e6; hd = np.diff(np.array(imu_host)) / 1e6
    R["imu"] = {"samples": len(imu_dev), "rate_hz": 1000.0 / float(np.median(d)), "gap_ms_p99": pct(d, 99), "gap_ms_max": float(d.max()),
                "gaps_over_10ms": int((d > 10).sum()), "arrival_gap_ms_p99": pct(hd, 99), "arrival_gap_ms_max": float(hd.max()), "arrival_gaps_over_20ms": int((hd > 20).sum())}
    for eye, name in ((0, "left"), (1, "right")):
        c = np.array(cam[eye], dtype=np.int64); dd = np.diff(c[:, 1]) / 1e6; cn = np.diff(c[:, 2]) % 65536
        R["camera_" + name] = {"frames": len(c), "rate_hz": 1000.0 / float(np.median(dd)), "interval_ms_p99": pct(dd, 99), "interval_ms_max": float(dd.max()),
                               "frames_lost_by_counter": int((cn - 1)[cn > 1].sum()), "brightness_median": float(np.median(c[:, 3])), "brightness_p5": pct(c[:, 3], 5)}

    # ---- motion: 2000 deg/s and 16 g full scale over 24 bits
    g = np.array(gyro, dtype=np.float64) * (2000.0 / (1 << 23)); acc = np.array(accel, dtype=np.float64) * (16.0 / (1 << 23))
    rate = np.linalg.norm(g - np.median(g, axis=0), axis=1)
    R["motion"] = {"rotation_deg_s_median": float(np.median(rate)), "rotation_deg_s_p95": pct(rate, 95), "rotation_deg_s_max": float(rate.max()),
                   "share_of_time_under_3_deg_s": float((rate < 3).mean()), "accel_g_median": float(np.median(np.linalg.norm(acc, axis=1)))}

    # ---- stereo: what the two cameras can share
    orb = cv2.ORB_create(nfeatures=600, fastThreshold=10); bf = cv2.BFMatcher(cv2.NORM_HAMMING)
    rows = []
    for t, left, right in pairs:
        kl, dl = orb.detectAndCompute(left, None); kr, dr = orb.detectAndCompute(right, None)
        inl = 0; shift = []
        if dl is not None and dr is not None and len(kl) >= 8 and len(kr) >= 8:
            good = [m for m, n in (p for p in bf.knnMatch(dl, dr, k=2) if len(p) == 2) if m.distance < 0.75 * n.distance]
            if len(good) >= 8:
                pl = np.float32([kl[m.queryIdx].pt for m in good]); pr = np.float32([kr[m.trainIdx].pt for m in good])
                F, mask = cv2.findFundamentalMat(pl, pr, cv2.FM_RANSAC, 2.0, 0.99)
                if mask is not None:
                    keep = mask.ravel() == 1; inl = int(keep.sum()); shift = (pr[keep] - pl[keep]).tolist()
        rows.append((t, len(kl), len(kr), inl, shift, left, right))
    feat = np.array([r[1] for r in rows]); both = np.array([r[3] for r in rows])
    sh = np.array([s for r in rows for s in r[4]]) if any(r[4] for r in rows) else np.zeros((0, 2))
    R["stereo"] = {"pairs_examined": len(rows), "left_features_median": float(np.median(feat)) if len(rows) else 0,
                   "matched_in_right_median": float(np.median(both)) if len(rows) else 0, "matched_in_right_p10": pct(both, 10), "matched_in_right_p90": pct(both, 90),
                   "matched_share_median": float(np.median(both / np.maximum(feat, 1))) if len(rows) else 0,
                   "pairs_with_fewer_than_10_matches": int((both < 10).sum()),
                   "shift_px_x_median": float(np.median(sh[:, 0])) if len(sh) else float("nan"), "shift_px_x_p5_p95": [pct(sh[:, 0], 5), pct(sh[:, 0], 95)] if len(sh) else [],
                   "shift_px_y_median": float(np.median(sh[:, 1])) if len(sh) else float("nan")}
    print(json.dumps(R, indent=1))
    if out:
        json.dump(R, open(os.path.join(out, "recording.json"), "w"), indent=1)
        with open(os.path.join(out, "stereo.csv"), "w") as f:
            f.write("t,left_features,right_features,matched\n")
            for r in rows: f.write("%.1f,%d,%d,%d\n" % r[:4])
        for i in (len(rows) // 4, len(rows) // 2):      # two example pairs, small, for a look at exposure and overlap
            if i < len(rows): cv2.imwrite(os.path.join(out, "pair-%ds.jpg" % rows[i][0]), np.hstack([rows[i][5], rows[i][6]]), [cv2.IMWRITE_JPEG_QUALITY, 60])
    return 0

if __name__ == "__main__":
    sys.exit(main())
