#!/usr/bin/env python3
"""Finds moments in a recording where the head came back to (almost) exactly where it had been: a drift benchmark
that needs no ground truth.

  python revisit.py RECORDING.uxrec OUT.json [--calib BASALT_CALIB.json] [--from-to A,B] [--min-gap SECONDS]

If the left camera's picture at time t2 lines up with its picture at t1 to within a few pixels everywhere, the head
was in the same place and facing the same way at both times (a few pixels at this lens is about a degree, or two
to three centimetres for things a metre away). Whatever distance the position estimator reports between t1 and t2
is then its drift over that interval. tools/tracking/replay_report.py reads OUT.json and reports that distance for
each replay.

How pairs are found: one left frame every half second is shrunk and compared with every other by normalised
correlation; the best candidates more than --min-gap apart are then checked at full size by following corner
points from one picture to the other (forward and back). A pair is kept when at least 40 points agree and their
median shift is under 4 pixels. Pairs are thinned so one revisit does not count many times.

With --calib the head may also have turned between the two moments: the points' sight lines are fitted with a pure
rotation, and the pair is kept when what is left over after that rotation is under 1.5 pixels (median). Anything a
rotation cannot explain is parallax, that is, the head having moved; 1.5 pixels is about 3 mm of movement for
things half a metre away and 15 mm for things two metres away. This finds many more pairs than exact revisits.
"""
import json, os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar

def fit_rotation(b0, b1):
    """Rotation R with b1 ~ R b0 (unit sight lines), trimmed twice. Returns R and the leftover angle per point (radians)."""
    keep = np.ones(len(b0), bool); R = np.eye(3)
    for _ in range(3):
        H = b0[keep].T @ b1[keep]; U, _, Vt = np.linalg.svd(H); d = np.sign(np.linalg.det(Vt.T @ U.T))
        R = Vt.T @ np.diag([1, 1, d]) @ U.T
        res = np.arccos(np.clip(np.einsum("ni,ni->n", b1, b0 @ R.T), -1, 1))
        keep = res < max(2.5 * np.median(res), 0.002)
    return R, res

def main():
    a = sys.argv[1:]; span = (0.0, 1e9); min_gap = 15.0; k0 = None
    if "--calib" in a:
        i = a.index("--calib"); k0 = json.load(open(a[i + 1]))["value0"]["intrinsics"][0]["intrinsics"]; del a[i:i + 2]
        import stereo_check as sc
    if "--from-to" in a: i = a.index("--from-to"); span = tuple(float(v) for v in a[i + 1].split(",")); del a[i:i + 2]
    if "--min-gap" in a: i = a.index("--min-gap"); min_gap = float(a[i + 1]); del a[i:i + 2]
    if len(a) != 2: print(__doc__); return 2
    first = None; times = []; small = []; full = []; nxt = span[0]
    for kind, t, data in ar.records(a[0]):
        if first is None: first = t
        if kind != 1: continue
        s = (t - first) / 1e9
        if s < nxt or s >= span[1] or data[ar.IMAGE + 59] != 0: continue
        nxt = s + 0.5
        img, _ = ar.decode(data)
        times.append(s); full.append(img)
        g = cv2.resize(img, (48, 64), interpolation=cv2.INTER_AREA).astype(np.float32).ravel()
        g -= g.mean(); n = np.linalg.norm(g); small.append(g / n if n > 0 else g)
    S = np.array(small); C = S @ S.T; T = np.array(times)
    cand = [(float(C[i, j]), i, j) for i in range(len(T)) for j in range(i + 1, len(T)) if T[j] - T[i] >= min_gap and C[i, j] > (0.80 if k0 is None else 0.55)]
    cand.sort(reverse=True)
    lk = dict(winSize=(21, 21), maxLevel=4, criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01))
    pairs = []; used = []
    for c, i, j in cand[:(1500 if k0 is None else 6000)]:
        if any(abs(T[i] - u[0]) < 3 and abs(T[j] - u[1]) < 3 for u in used): continue
        pts = cv2.goodFeaturesToTrack(full[i], 200, 0.01, 10)
        if pts is None or len(pts) < 60: continue
        p1, st, _ = cv2.calcOpticalFlowPyrLK(full[i], full[j], pts, None, **lk)
        back, st2, _ = cv2.calcOpticalFlowPyrLK(full[j], full[i], p1, None, **lk)
        ok = (st.ravel() == 1) & (st2.ravel() == 1) & (np.linalg.norm((back - pts).reshape(-1, 2), axis=1) < 1.0)
        if ok.sum() < 40: continue
        shift = np.linalg.norm((p1 - pts).reshape(-1, 2)[ok], axis=1)
        med = float(np.median(shift)); p90 = float(np.percentile(shift, 90))
        if k0 is not None:
            if ok.sum() < 60: continue
            R, res = fit_rotation(sc.unproject_kb4(pts.reshape(-1, 2)[ok], k0), sc.unproject_kb4(p1.reshape(-1, 2)[ok], k0))
            left = float(np.median(res) * k0["fx"]); left90 = float(np.percentile(res, 90) * k0["fx"])
            turned = float(np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1))))
            if left < 1.5 and left90 < 5.0 and turned < 25:
                pairs.append({"t1": float(T[i]), "t2": float(T[j]), "points": int(ok.sum()), "turned_deg": turned, "leftover_px": left, "leftover_p90_px": left90, "median_shift_px": med, "similarity": c}); used.append((T[i], T[j]))
        elif med < 4.0 and p90 < 12.0:
            pairs.append({"t1": float(T[i]), "t2": float(T[j]), "points": int(ok.sum()), "median_shift_px": med, "p90_shift_px": p90, "similarity": c}); used.append((T[i], T[j]))
    pairs.sort(key=lambda p: (p["t1"], p["t2"]))
    json.dump({"recording": os.path.basename(a[0]), "frames_compared": len(T), "pairs": pairs}, open(a[1], "w"), indent=1)
    gaps = [p["t2"] - p["t1"] for p in pairs]
    print("%d frames compared, %d revisits kept; time between visits %s s" % (len(T), len(pairs), ("%.0f to %.0f, median %.0f" % (min(gaps), max(gaps), float(np.median(gaps)))) if gaps else "-"))
    for p in pairs[:12]: print("  %.1f s and %.1f s: %d points, median shift %.1f px" % (p["t1"], p["t2"], p["points"], p["median_shift_px"]))
    return 0

if __name__ == "__main__":
    sys.exit(main())
