"""Reads st_TAG/*.csv (starts.sh): time from a cold start, with a hand in view, to the first report of a hand."""
import sys, os, csv, statistics as st
TWO = {17.0,17.5,18.0,18.5,19.0,19.5,20.0,20.5,21.0,21.5,22.0,22.5}
for tag in [a for a in sys.argv[1:] if a != "-v"]:
    d = "st_" + tag; first = {}; both = {}; share = []
    for f in sorted(os.listdir(d), key=lambda x: float(x[:-4])):
        s = float(f[:-4]); rows = [r for r in csv.reader(open(os.path.join(d, f))) if r and r[0].replace('.', '', 1).isdigit()]
        if not rows: continue
        t0 = float(rows[0][0])
        a = [float(r[0]) - t0 for r in rows if int(r[1])]; b = [float(r[0]) - t0 for r in rows if int(r[1]) == 3]
        first[s] = a[0] if a else None; share.append(len(a) / len(rows))
        if s in TWO: both[s] = b[0] if b else None
    got = [v for v in first.values() if v is not None]; n = len(first)
    line = "%-10s %d starts: a hand within 0.5 s in %d, 1 s in %d, 2 s in %d, never in 4 s: %d; median wait %.2f s (never counted as 4 s); results with a hand %.0f%%" % (
        tag, n, sum(v <= .5 for v in got), sum(v <= 1 for v in got), sum(v <= 2 for v in got), n - len(got), st.median([v if v is not None else 4.0 for v in first.values()]), 100 * st.mean(share))
    gb = [v for v in both.values() if v is not None]
    print(line + "; both hands (12 starts): within 2 s in %d, never %d" % (sum(v <= 2 for v in gb), len(both) - len(gb)))
    if "-v" in sys.argv: print("   ", " ".join("%g:%s" % (s, "-" if v is None else "%.1f" % v) for s, v in first.items()))
