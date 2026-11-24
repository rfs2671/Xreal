import csv, sys, numpy as np, glob, os
def pct(a,p): return float(np.percentile(a,p)) if len(a) else float('nan')
def load_hands(f):
    r=list(csv.reader(open(f))); h=r[0]; rows=np.array([[float(x) if x!='' else np.nan for x in row] for row in r[1:]]); return h,rows
for f in sys.argv[1:]:
    h,a=load_hands(f); t=a[:,0]; mask=a[:,1].astype(int); ms=a[:,2]
    hv=mask>0
    dur=t[-1]-t[0]
    print(f"== {os.path.basename(f)}: {len(a)} results in {dur:.0f}s = {len(a)/dur:.1f}/s; with a hand {hv.sum()} ({100*hv.mean():.0f}%)")
    for name,sel in (("hand",hv),("no hand",~hv)):
        x=ms[sel]
        if len(x): print(f"   process ms {name}: median {pct(x,50):.1f}  P95 {pct(x,95):.1f}  P99 {pct(x,99):.1f}  max {x.max():.1f}")
    dt=np.diff(t)*1000
    print(f"   gap between results ms: median {pct(dt,50):.0f}  P95 {pct(dt,95):.0f}  P99 {pct(dt,99):.0f}  (>100 ms: {(dt>100).sum()})")
    # hand dropouts: runs of consecutive no-hand results inside hand-present stretches
    runs=[];n=0
    for v in hv:
        if not v: n+=1
        else:
            if 0<n<=15: runs.append(n)
            n=0
    print(f"   short dropouts (1-15 results) between hand results: {len(runs)}, median length {np.median(runs) if runs else 0}")
