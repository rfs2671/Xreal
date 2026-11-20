#!/usr/bin/env python3
"""When does the position estimator lose its way in a replay, and what was the head doing?

  python vio_loss.py RECORDING.uxrec DIR TAG [TAG ...]

DIR holds TAG-vio.csv from the app's replay (tools/pixel/lab.py): a line a state with the features tracked, the
landmarks held, how many are seen by both cameras, resets, and whether the position was trusted. The recording is
read for the motion sensor only: how fast the head was turning, second by second.

For each run: the share of the recording with a trusted position, how long it took to get one at the start, every
stretch without one (when, how long, how fast the head turned in the second before and during, features and
landmarks just before), restarts of the estimator, and jumps of more than 5 cm between two states.
"""
import csv, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar

def turning(recording):
    """(seconds since the recording began, degrees a second) for every motion report."""
    t = []; g = []; first = None
    for kind, host, data in ar.records(recording) if False else ar.records_imu_only(recording):
        if first is None: first = host
        t.append((host - first) / 1e9); g.append([ar.s24(data, o) for o in (18, 21, 24)])
    g = np.array(g, dtype=np.float64) * (2000.0 / (1 << 23))
    return np.array(t), np.linalg.norm(g, axis=1)

def main():
    rec, d, tags = sys.argv[1], sys.argv[2], sys.argv[3:]
    tt, rate = turning(rec); end = tt[-1]
    print("recording %.0f s; the head turns faster than 60 deg/s for %.1f s of it, faster than 120 for %.1f s; fastest %.0f deg/s" % (
        end, (rate > 60).mean() * end, (rate > 120).mean() * end, rate.max()))
    def turn(a, b):
        sel = (tt >= a) & (tt < b); return float(rate[sel].max()) if sel.any() else 0.0
    ref = None
    for tag in tags:
        rows = list(csv.DictReader(open(os.path.join(d, tag + "-vio.csv"))))
        if not rows: print(tag, "no states"); continue
        t = np.array([float(r["t"]) for r in rows]); ok = np.array([int(r["trusted"]) for r in rows]) > 0
        feat = np.array([int(r["features"]) for r in rows]); land = np.array([int(r["landmarks"]) for r in rows]); st = np.array([int(r["stereo"]) for r in rows])
        p = np.array([[float(r["px"]), float(r["py"]), float(r["pz"])] for r in rows]); ms = np.array([float(r["process_ms"]) for r in rows])
        resets = int(rows[-1]["resets"]) - int(rows[0]["resets"]); auto = int(rows[-1]["auto_resets"]) - int(rows[0]["auto_resets"])
        first_ok = float(t[ok][0]) if ok.any() else float("nan")
        # stretches without a trusted position (gaps between states count: no state is no position)
        losses = []; lost_from = None; last_t = 0.0
        for i in range(len(t)):
            if t[i] - last_t > 0.5 and lost_from is None and i > 0: lost_from = last_t
            if not ok[i] and lost_from is None: lost_from = t[i]
            if ok[i] and lost_from is not None:
                losses.append((lost_from, t[i])); lost_from = None
            last_t = t[i]
        if lost_from is not None: losses.append((lost_from, end))
        after = [l for l in losses if l[0] > first_ok + 0.1] if ok.any() else []
        trusted_s = float(np.sum(np.diff(t)[ok[:-1] & (np.diff(t) < 0.5)])) if len(t) > 1 else 0.0
        step = np.linalg.norm(np.diff(p, axis=0), axis=1); jumps = int(((step > 0.05) & (np.diff(t) < 0.05) & ok[1:] & ok[:-1]).sum())
        print("%-10s states %d (%.1f a second), %.1f ms; trusted %.0f%% of the recording (%.1f s), first at %.1f s; %d restarts (%d by itself); %d jumps over 5 cm; features median %d, landmarks %d, seen by both %d" % (
            tag, len(t), len(t) / max(1e-9, t[-1] - t[0]), float(np.median(ms)), 100 * trusted_s / end, trusted_s, first_ok, resets, auto, jumps, int(np.median(feat)), int(np.median(land)), int(np.median(st))))
        # how far the head went, and whether the run agrees with the first one named: the distance moved over every
        # two seconds does not depend on where a run's world began, so it can be compared between runs that started
        # at different moments
        good = ok[1:] & ok[:-1] & (np.diff(t) < 0.5) & (np.diff(t) > 0.02)      # two states a few ms apart say nothing of speed
        speed = step[good] / np.maximum(1e-6, np.diff(t)[good])
        def moved(span=2.0):
            out = {}
            for k in range(0, int(end / span)):
                i0 = int(np.searchsorted(t, k * span)); i1 = int(np.searchsorted(t, (k + 1) * span))
                if i0 >= len(t) or i1 >= len(t) or not ok[i0:i1 + 1].all() or abs(t[i0] - k * span) > 0.1 or abs(t[i1] - (k + 1) * span) > 0.1: continue
                out[k] = float(np.linalg.norm(p[i1] - p[i0]))
            return out
        mv = moved()
        if ref is None: ref = mv; agree = ""
        else:
            both = [k for k in mv if k in ref]; dd = np.array([abs(mv[k] - ref[k]) for k in both])
            agree = "; against %s over %d two-second spans: median difference %.1f cm, 90th %.1f cm, worst %.1f cm" % (tags[0], len(both), 100 * np.median(dd), 100 * np.percentile(dd, 90), 100 * dd.max()) if len(both) else "; no span in common with " + tags[0]
        print("    path %.1f m while trusted, fastest %.1f m/s (99th percentile %.2f)%s" % (float(step[good].sum()), float(speed.max()) if len(speed) else 0, float(np.percentile(speed, 99)) if len(speed) else 0, agree))
        for a, b in after:
            i = int(np.searchsorted(t, a)) - 1; i = max(0, i)
            j = max(0, i - 15)
            print("    lost %6.1f to %6.1f s (%4.1f s): turning up to %3.0f deg/s in the second before, %3.0f during; just before: features %d, landmarks %d, seen by both %d" % (
                a, b, b - a, turn(a - 1.0, a), turn(a, b), int(np.median(feat[j:i + 1])), int(np.median(land[j:i + 1])), int(np.median(st[j:i + 1]))))

if __name__ == "__main__": main()
