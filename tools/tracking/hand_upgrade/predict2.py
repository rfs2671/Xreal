import sys, numpy as np, math
sys.path.insert(0,'/c/uvio/handup')
from predict_lab import load, interp, TIP, J
def smooth(x,lo,hi): u=np.clip((x-lo)/(hi-lo),0,1); return u*u*(3-2*u)
def evaluate(files,hs,cfgs):
    out={}
    for f in files:
        for side in (0,1):
            t,P=load(f,side)
            if len(t)<300: continue
            for h in hs:
                for name,cfg in cfgs.items():
                    e=[];sp=[]
                    for i in range(3,len(t)):
                        if t[i]-t[i-3]>0.24: continue
                        tq=t[i]+h; tgt=interp(t,P,tq)
                        if tgt is None: continue
                        j=np.searchsorted(t,tq)
                        if t[j]-t[i]>h+0.1: continue
                        # rigid velocity: mean over joints of 3-sample slope (least squares over last k samples)
                        k=cfg.get('k',3); tt=t[i-k+1:i+1]-t[i]; X=P[i-k+1:i+1]            # k,J,3
                        tm=tt.mean(); den=((tt-tm)**2).sum()
                        slope=((tt-tm)[:,None,None]*(X-X.mean(0))).sum(0)/den                  # J,3
                        vr=slope.mean(0)                                                    # rigid
                        spd=np.linalg.norm(vr)
                        g=smooth(spd,cfg['lo'],cfg['hi'])
                        hh=min(h,cfg['cap'])
                        pred=P[i]+(vr[None,:]*g*hh) + (cfg.get('art',0.0)*(slope-vr[None,:])*hh if cfg.get('art',0.0) else 0)
                        d=np.linalg.norm((pred-tgt)[TIP],axis=1).mean()*1000
                        e.append(d); sp.append(np.linalg.norm((P[i]-P[i-1]).mean(0)/(t[i]-t[i-1])))
                    sp=np.array(sp);e=np.array(e)
                    out.setdefault((h,name),[]).append((e.mean(),e[sp>0.25].mean(),e[sp<0.06].mean(),len(e)))
    for (h,n),l in sorted(out.items()):
        a=np.array(l);w=a[:,3]
        print(f"h={h*1000:.0f} {n:28s} all {np.average(a[:,0],weights=w):5.1f} fast {np.nanmean(a[:,1]):5.1f} slow {np.nanmean(a[:,2]):5.1f}")
cfgs={'hold':dict(lo=9,hi=10,cap=0.0),
 'rigid_k3_gate.1-.4_cap80':dict(k=3,lo=0.1,hi=0.4,cap=0.08),
 'rigid_k3_gate.05-.25_cap80':dict(k=3,lo=0.05,hi=0.25,cap=0.08),
 'rigid_k3_gate.05-.25_cap120':dict(k=3,lo=0.05,hi=0.25,cap=0.12),
 'rigid_k4_gate.05-.25_cap80':dict(k=4,lo=0.05,hi=0.25,cap=0.08),
 'rigid_k2_gate.05-.25_cap80':dict(k=2,lo=0.05,hi=0.25,cap=0.08),
 'rigid_k3_nogate_cap80':dict(k=3,lo=-1,hi=0,cap=0.08)}
if __name__=="__main__":
    evaluate(sys.argv[1:],(0.04,0.08,0.12),cfgs)
