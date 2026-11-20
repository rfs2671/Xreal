#!/usr/bin/env python3
"""When would the position estimator's start gate let it start, on a recording's motion data alone?

  python vio_gate.py RECORDING.uxrec [...]

The estimator (native/vio/src/ultra_vio.cpp) is started only after 30 camera frames running in which the motion
sensor looks at rest (XR Gate's limits: mean turn rate 0.08 rad/s, its spread 0.04, acceleration within 0.75 m/s2 of
gravity, its spread 0.35). The same wait follows every restart. This prints the share of frames that count as at
rest and which limit fails, when a start at 0 s would pass, and how long a restart at each whole second would wait.
"""
import sys, os
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar
for rec in sys.argv[1:]:
    t = []; g = []; a = []; first = None
    for kind, host, data in ar.records_imu_only(rec):
        if first is None: first = host
        t.append((host - first) / 1e9); g.append([ar.s24(data, o) for o in (18, 21, 24)]); a.append([ar.s24(data, o) for o in (33, 36, 39)])
    t = np.array(t); g = np.linalg.norm(np.array(g, float) * (2000.0 / (1 << 23)) * np.pi / 180, axis=1); a = np.linalg.norm(np.array(a, float) * (16.0 / (1 << 23)) * 9.80665, axis=1)
    n = int(t[-1] / (1 / 30.0)); ok = np.zeros(n, bool); idx = np.searchsorted(t, np.arange(n + 1) / 30.0)
    why = np.zeros(4)
    for i in range(n):
        gg = g[idx[i]:idx[i + 1]]; aa = a[idx[i]:idx[i + 1]]
        if len(gg) < 10: continue
        c = [gg.mean() <= 0.08, gg.std() <= 0.04, abs(aa.mean() - 9.81) <= 0.75, aa.std() <= 0.35]
        ok[i] = all(c); why += [not x for x in c]
    run = 0; passes = []; i = 0
    while i < n:
        run = run + 1 if ok[i] else 0
        if run == 30: passes.append(i / 30.0)
        i += 1
    # how long from any moment until the gate would pass (a restart at that moment)
    wait = []; nxt = None
    runs_end = []; run = 0
    for i in range(n):
        run = run + 1 if ok[i] else 0
        if run >= 30: runs_end.append(i)
    runs_end = np.array(runs_end)
    for i in range(0, n, 30):
        # a restart at frame i: first j >= i+29 with 30 still frames all after i
        run = 0; j = i
        while j < n:
            run = run + 1 if ok[j] else 0
            if run == 30: break
            j += 1
        wait.append((j - i) / 30.0 if j < n else float("inf"))
    wait = np.array(wait); fin = wait[np.isfinite(wait)]
    print(os.path.basename(rec), "%.0f s, %d motion reports (%.0f a second)" % (t[-1], len(t), len(t) / t[-1]))
    print("   frames that count as still: %.0f%%; failing on turn rate %.0f%%, turn-rate spread %.0f%%, accel size %.0f%%, accel spread %.0f%%" % ((100 * ok.mean(),) + tuple(100 * why / n)))
    print("   a start at 0 s would pass the gate at %s s" % (("%.1f" % passes[0]) if passes else "never"))
    print("   a restart at a random moment waits: median %.1f s, 90th %.1f s, worst %.1f s; never passes for %d of %d moments" % (np.median(fin) if len(fin) else -1, np.percentile(fin, 90) if len(fin) else -1, fin.max() if len(fin) else -1, int((~np.isfinite(wait)).sum()), len(wait)))
