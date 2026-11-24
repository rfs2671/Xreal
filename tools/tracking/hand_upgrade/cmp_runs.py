"""usage: cmp_runs.py A.csv B.csv  -- how two runs of the tracker over the same pairs differ (A is the reference)."""
import csv, sys, numpy as np
def load(f):
    rows = [r for r in csv.reader(open(f)) if len(r) >= 11 and r[0] != 't']
    t = np.array([float(r[0]) for r in rows]); ms = np.array([float(r[2]) if r[2] else np.nan for r in rows])
    def col(i): return np.array([float(r[i]) if r[i] not in ('', 'nan') else np.nan for r in rows])
    return t, ms, {"L": np.stack([col(3), col(4), col(5)], 1), "R": np.stack([col(7), col(8), col(9)], 1)}, {"L": col(6), "R": col(10)}
ta, ma, pa, ga = load(sys.argv[1]); tb, mb, pb, gb = load(sys.argv[2])
n = min(len(ta), len(tb))
for s in "LR":
    A = ~np.isnan(pa[s][:n, 0]); B = ~np.isnan(pb[s][:n, 0]); both = A & B
    d = np.linalg.norm(pa[s][:n][both] - pb[s][:n][both], axis=1) * 1000
    dg = np.abs(ga[s][:n][both] - gb[s][:n][both]) * 1000
    print(f"{s} hand: reference tracks {A.sum()} pairs, other {B.sum()}, both {both.sum()}, only reference {(A & ~B).sum()}, only other {(B & ~A).sum()}; palm differs {d.mean():.2f} mm mean, P95 {np.percentile(d,95):.2f}; tip gap differs {dg.mean():.2f} mm mean, P95 {np.percentile(dg,95):.2f}" if both.sum() else f"{s}: nothing in common")
anyA = ~(np.isnan(pa["L"][:n, 0]) & np.isnan(pa["R"][:n, 0])); hand = anyA
for name, m in (("reference", ma), ("other", mb)):
    x = m[:n][hand]; print(f"  {name}: pairs with a hand (by the reference): median {np.nanmedian(x):.1f} ms P95 {np.nanpercentile(x,95):.1f} P99 {np.nanpercentile(x,99):.1f}")
