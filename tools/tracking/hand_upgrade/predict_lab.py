import csv, sys, numpy as np, math
J=26; TIP=[5,10,0,15,20,25]  # thumb tip, index tip, palm, middle tip, ring tip, little tip
def load(f, side):
    t=[];P=[]
    for r in csv.reader(open(f)):
        if len(r)<10 or r[0]=='t': continue
        if int(float(r[1]))!=side: continue
        v=np.array(r[2:2+J*7],float).reshape(J,7); t.append(float(r[0])); P.append(v[:,:3])
    return np.array(t),np.array(P)
def interp(t,P,tq):
    i=np.searchsorted(t,tq); 
    if i<=0 or i>=len(t): return None
    a=(tq-t[i-1])/(t[i]-t[i-1]); return P[i-1]*(1-a)+P[i]*a
class OneEuro:
    def __init__(s,mc,beta,dc=1.0): s.mc,s.beta,s.dc=mc,beta,dc; s.x=None
    def alpha(s,dt,fc): tau=1/(2*math.pi*fc); return 1/(1+tau/dt)
    def step(s,t,x):
        if s.x is None: s.x=x.copy(); s.dx=np.zeros_like(x); s.t=t; return s.x,s.dx
        dt=max(t-s.t,1e-3); s.t=t
        d=(x-s.x)/dt; ad=s.alpha(dt,s.dc); s.dx=ad*d+(1-ad)*s.dx
        sp=np.linalg.norm(s.dx.reshape(-1,3),axis=1).mean()
        fc=s.mc+s.beta*sp; a=s.alpha(dt,fc); s.x=a*x+(1-a)*s.x; return s.x,s.dx
def run(files, hs=(0.04,0.08,0.12)):
    res={}
    for f in files:
        for side in (0,1):
            t,P=load(f,side)
            if len(t)<300: continue
            # segments with no gap >80 ms
            for h in hs:
                errs={'hold':[],'cv2':[],'cv_oe':[],'cv_oe_cap':[],'oe_hold':[]}
                speeds=[]
                oe=OneEuro(1.5,25.0)  # params to explore
                F=[];D=[]
                for i in range(len(t)):
                    if i>0 and t[i]-t[i-1]>0.08: oe.x=None
                    x,dx=oe.step(t[i],P[i].reshape(-1)); F.append(x.reshape(J,3).copy()); D.append(dx.reshape(J,3).copy())
                for i in range(2,len(t)):
                    if t[i]-t[i-2]>0.16: continue
                    tq=t[i]+h; tgt=interp(t,P,tq)
                    if tgt is None: continue
                    j=np.searchsorted(t,tq)
                    if t[j]-t[i]>h+0.1: continue
                    sp=np.linalg.norm((P[i]-P[i-1])[0]/(t[i]-t[i-1]))
                    d=lambda a: np.linalg.norm((a-tgt)[TIP],axis=1).mean()*1000
                    cv=P[i]+(P[i]-P[i-2])/(t[i]-t[i-2])*h
                    errs['hold'].append(d(P[i])); errs['cv2'].append(d(cv))
                    errs['oe_hold'].append(d(F[i]))
                    errs['cv_oe'].append(d(F[i]+D[i]*h))
                    errs['cv_oe_cap'].append(d(F[i]+D[i]*min(h,0.06)))
                    speeds.append(sp)
                sp=np.array(speeds); fast=sp>0.25; slow=sp<0.06
                for k,v in errs.items():
                    v=np.array(v); 
                    res.setdefault((h,k),[]).append((v.mean(), v[fast].mean() if fast.any() else np.nan, v[slow].mean() if slow.any() else np.nan, len(v), fast.sum(), slow.sum()))
    for (h,k),l in sorted(res.items()):
        a=np.array(l); w=a[:,3]
        print(f"h={h*1000:.0f}ms {k:10s} all {np.average(a[:,0],weights=w):6.1f}mm  fast(>0.25m/s) {np.nanmean(a[:,1]):6.1f}  slow(<0.06) {np.nanmean(a[:,2]):6.1f}  n={int(w.sum())}")
if __name__=="__main__": run(sys.argv[1:])
