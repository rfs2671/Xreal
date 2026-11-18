#!/usr/bin/env python3
"""Compares replays of one recording through the trackers with different settings.

  python replay_report.py RECORDING.uxrec DIR TAG [TAG ...] [--revisits REVISITS.json] [--out FILE.json]

DIR holds TAG-vio.csv, TAG-hands.csv and TAG-notes.txt as written by the app's replay (tracking/ReplayLog). The
recording itself is read for one thing only: when the glasses were still (the motion sensor says under 3 degrees
a second for a whole second). While they are still the true position does not change, so whatever the estimator
reports then is error, and that needs no ground truth:

  creep_cm_s      median speed the estimator reports while the glasses are still
  wander_cm       how far the reported position strays within one still stretch (median and worst over stretches)

Also per run: how much of the time a position was reported and trusted, points seen by both cameras, landmarks,
resets, jumps (more than 5 cm between two states 33 ms apart), processing time; and for the hands: frames in
which each hand was found, the fingertip distance distribution, processing time.

The same real motion goes through every run, so differences between rows are differences between settings.
"""
import csv, json, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar

def still_seconds(recording, limit=3.0):
    """Whole seconds (from the start of the recording) in which rotation stayed under [limit] deg/s."""
    t = []; g = []; first = None
    for kind, host, data in ar.records_imu_only(recording):
        if first is None: first = host
        t.append((host - first) / 1e9); g.append([ar.s24(data, o) for o in (18, 21, 24)])
    t = np.array(t); g = np.array(g, dtype=np.float64) * (2000.0 / (1 << 23)); g -= np.median(g, axis=0)
    rate = np.linalg.norm(g, axis=1); sec = t.astype(int); still = set()
    for s in range(int(t[-1]) + 1):
        m = sec == s
        if m.sum() > 500 and rate[m].max() < limit: still.add(s)
    return still, first

def stretches(still):
    out = []; run = []
    for s in sorted(still):
        if run and s != run[-1] + 1:
            if len(run) >= 3: out.append((run[0], run[-1] + 1))
            run = []
        run.append(s)
    if len(run) >= 3: out.append((run[0], run[-1] + 1))
    return out

def read(path):
    with open(path, newline="") as f: return list(csv.DictReader(f))

def run_report(d, tag, still, spans, calm_spans=(), revisits=()):
    r = {"tag": tag}
    vio = read(os.path.join(d, tag + "-vio.csv")); hands = read(os.path.join(d, tag + "-hands.csv"))
    notes = open(os.path.join(d, tag + "-notes.txt")).read().strip().splitlines() if os.path.exists(os.path.join(d, tag + "-notes.txt")) else []
    r["notes"] = notes[-2:]; r["resets_noted"] = sum(1 for n in notes if "reset:" in n)
    if vio:
        t = np.array([float(v["t"]) for v in vio]); p = np.array([[float(v["px"]), float(v["py"]), float(v["pz"])] for v in vio])
        vel = np.array([[float(v["vx"]), float(v["vy"]), float(v["vz"])] for v in vio]); trusted = np.array([v["trusted"] == "1" for v in vio])
        stereo = np.array([int(v["stereo"]) for v in vio]); lm = np.array([int(v["landmarks"]) for v in vio]); ms = np.array([float(v["process_ms"]) for v in vio])
        epoch = np.array([int(v["resets"]) for v in vio])
        r.update({"states": len(vio), "seconds_with_states": float(len(set(t.astype(int)))), "trusted_share": float(trusted.mean()),
                  "stereo_points_median": float(np.median(stereo)), "stereo_points_p10": float(np.percentile(stereo, 10)),
                  "landmarks_median": float(np.median(lm)), "process_ms_median": float(np.median(ms)), "process_ms_p95": float(np.percentile(ms, 95)),
                  "estimator_restarts": int(epoch.max() - epoch.min())})
        same = (np.diff(epoch) == 0) & (np.diff(t) < 0.1)
        step = np.linalg.norm(np.diff(p, axis=0), axis=1)
        r["jumps_over_5cm"] = int((step[same] > 0.05).sum()); r["path_m"] = float(step[same & (step < 0.05)].sum())
        m = np.isin(t.astype(int), list(still)) & trusted
        r["creep_cm_s"] = float(np.median(np.linalg.norm(vel[m], axis=1)) * 100) if m.sum() > 30 else None
        w = []
        for a, b in spans:
            k = (t >= a) & (t < b) & trusted
            if k.sum() > 20 and epoch[k].max() == epoch[k].min(): w.append(float(np.linalg.norm(p[k] - p[k][0], axis=1).max() * 100))
        # The estimator's own output over each still stretch, whether or not the app trusted it at the time: how far
        # it strays, and how far apart its first and last second are. This is the estimator's drift with no gating.
        raw = []; ends = []
        for a, b in spans:
            k = (t >= a) & (t < b)
            if k.sum() > 60 and epoch[k].max() == epoch[k].min():
                q = p[k]; raw.append(float(np.linalg.norm(q - q[:30].mean(axis=0), axis=1).max() * 100))
                ends.append(float(np.linalg.norm(q[-30:].mean(axis=0) - q[:30].mean(axis=0)) * 100))
        r["still_raw_stray_cm_worst"] = max(raw) if raw else None; r["still_raw_end_minus_start_cm"] = max(ends) if ends else None
        r["still_trusted_share"] = float(np.mean([trusted[(t >= a) & (t < b)].mean() for a, b in spans])) if spans else None
        k = t < 97
        r["worn_stereo_points_median"] = float(np.median(stereo[k])) if k.sum() else None; r["worn_trusted_share"] = float(trusted[k].mean()) if k.sum() else None
        # Revisits (tools/tracking/revisit.py): the head was in the same place at t1 and t2, so the distance reported
        # between them is drift. Only pairs where position was trusted at both ends and the estimator did not restart.
        e = []
        for pr in revisits:
            i = int(np.argmin(np.abs(t - pr["t1"]))); j = int(np.argmin(np.abs(t - pr["t2"])))
            if abs(t[i] - pr["t1"]) < 0.1 and abs(t[j] - pr["t2"]) < 0.1 and epoch[i] == epoch[j] and trusted[i] and trusted[j]:
                e.append(float(np.linalg.norm(p[j] - p[i]) * 100))
        r["revisits_scored"] = len(e)
        r["revisit_error_cm_median"] = float(np.median(e)) if e else None; r["revisit_error_cm_p90"] = float(np.percentile(e, 90)) if e else None; r["revisit_error_cm_worst"] = max(e) if e else None
        r["wander_cm_median"] = float(np.median(w)) if w else None; r["wander_cm_worst"] = float(max(w)) if w else None; r["still_stretches_used"] = len(w)
        # On the head and calm (under 12 deg/s): the head does move a little here, so this is an upper bound on error,
        # per second of calm, not error itself. Useful only to compare runs on the same motion.
        c = []
        for a, b in calm_spans:
            k = (t >= a) & (t < b) & trusted
            if k.sum() > 20 and epoch[k].max() == epoch[k].min(): c.append(float(np.linalg.norm(p[k] - p[k][0], axis=1).max() * 100 / (b - a)))
        r["calm_spread_cm_per_s"] = float(np.median(c)) if c else None; r["calm_stretches_used"] = len(c)
        # What the application was given (TAG-app.csv, builds from 17 Nov 2026 evening on): the same two questions asked
        # of the head position handed to games, which includes "not trusted" and "lying still" holds.
        ap = os.path.join(d, tag + "-app.csv")
        if os.path.exists(ap):
            rows = read(ap)
            if rows:
                ta = np.array([float(v["t"]) for v in rows]); h = np.array([[float(v["hx"]), float(v["hy"]), float(v["hz"])] for v in rows]); ly = np.array([v["lying"] == "1" for v in rows])
                stray = [float(np.linalg.norm(h[(ta >= a) & (ta < b)] - h[(ta >= a) & (ta < b)][:30].mean(axis=0), axis=1).max() * 100) for a, b in spans if ((ta >= a) & (ta < b)).sum() > 60]
                # from two seconds into each still stretch: the hold needs a second to notice
                late = [float(np.linalg.norm(h[(ta >= a + 2) & (ta < b)] - h[(ta >= a + 2) & (ta < b)][:30].mean(axis=0), axis=1).max() * 100) for a, b in spans if ((ta >= a + 2) & (ta < b)).sum() > 60]
                r["app_still_stray_cm"] = max(stray) if stray else None; r["app_still_stray_after_2s_cm"] = max(late) if late else None
                r["lying_share_while_still"] = float(np.mean([ly[(ta >= a) & (ta < b)].mean() for a, b in spans])) if spans else None
                r["lying_share_while_worn"] = float(ly[ta < 97].mean()) if (ta < 97).sum() else None
                e = []
                for pr in revisits:
                    i = int(np.argmin(np.abs(ta - pr["t1"]))); j = int(np.argmin(np.abs(ta - pr["t2"])))
                    if abs(ta[i] - pr["t1"]) < 0.1 and abs(ta[j] - pr["t2"]) < 0.1: e.append(float(np.linalg.norm(h[j] - h[i]) * 100))
                r["app_revisit_error_cm_median"] = float(np.median(e)) if e else None; r["app_revisit_error_cm_worst"] = max(e) if e else None
    else: r["states"] = 0
    if hands:
        mask = np.array([int(h["mask"]) for h in hands]); ms = np.array([float(h["process_ms"]) for h in hands])
        r["hand_results"] = len(hands); r["hand_process_ms_median"] = float(np.median(ms)); r["hand_process_ms_p95"] = float(np.percentile(ms, 95))
        for side, name, col in ((0, "left", "l"), (1, "right", "r")):
            found = (mask >= 0) & ((mask & (1 << side)) != 0)
            r[name + "_found_frames"] = int(found.sum())
            gaps = np.array([float(h[col + "_gap"]) for h, f in zip(hands, found) if f and h[col + "_gap"]]) * 1000
            if len(gaps): r[name + "_gap_mm_min_p5_median"] = [float(gaps.min()), float(np.percentile(gaps, 5)), float(np.median(gaps))]
            # stretches of at least half a second in which the hand was held
            runs = []; n = 0
            for f in found:
                if f: n += 1
                else:
                    if n: runs.append(n)
                    n = 0
            if n: runs.append(n)
            r[name + "_longest_run_frames"] = int(max(runs)) if runs else 0; r[name + "_runs"] = len(runs)
    return r

def main():
    a = sys.argv[1:]; out = None; revisits = []
    if "--revisits" in a: i = a.index("--revisits"); revisits = json.load(open(a[i + 1]))["pairs"]; del a[i:i + 2]
    if "--out" in a: i = a.index("--out"); out = a[i + 1]; del a[i:i + 2]
    if len(a) < 3: print(__doc__); return 2
    still, _ = still_seconds(a[0]); spans = stretches(still)
    calm, _ = still_seconds(a[0], 12.0); calm_spans = [sp for sp in stretches(calm - still)]
    print("the glasses were still for %d of the recording's seconds, in %d stretches of 3 s or more; calm but not still (on the head) in %d stretches" % (len(still), len(spans), len(calm_spans)))
    rows = [run_report(a[1], tag, still, spans, calm_spans, revisits) for tag in a[2:]]
    keys = ["app_revisit_error_cm_median", "app_revisit_error_cm_worst", "app_still_stray_cm", "app_still_stray_after_2s_cm", "lying_share_while_still", "lying_share_while_worn", "revisits_scored", "revisit_error_cm_median", "revisit_error_cm_p90", "revisit_error_cm_worst", "worn_trusted_share", "worn_stereo_points_median", "calm_spread_cm_per_s", "still_trusted_share", "still_raw_stray_cm_worst", "still_raw_end_minus_start_cm", "creep_cm_s", "wander_cm_worst", "landmarks_median", "jumps_over_5cm", "estimator_restarts", "resets_noted",
            "process_ms_median", "left_found_frames", "right_found_frames", "left_longest_run_frames", "right_longest_run_frames", "hand_process_ms_median"]
    print("%-28s" % "" + "".join("%16s" % r["tag"][:15] for r in rows))
    for k in keys:
        print("%-28s" % k + "".join("%16s" % (("%.3f" % r[k]) if isinstance(r.get(k), float) else str(r.get(k, "-"))) for r in rows))
    if out: json.dump(rows, open(out, "w"), indent=1)
    return 0

if __name__ == "__main__":
    sys.exit(main())
