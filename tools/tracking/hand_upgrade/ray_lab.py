import sys, csv, math, numpy as np
sys.path.insert(0,'/c/uvio/handup')
from predict_lab import load
import pinch_lab as pl
def qrot(q,v):
    x,y,z,w=q; u=np.array([x,y,z]); return v+2*np.cross(u,np.cross(u,v)+w*v)
def load_head(f):
    rows=[r for r in csv.reader(open(f)) if len(r)>=8 and r[0]!='t']; a=np.array(rows,float); return a[:,0],a[:,1:4],a[:,4:8]
def head_at(ht,hp,hq,t):
    i=min(max(np.searchsorted(ht,t),1),len(ht)-1); a=(t-ht[i-1])/max(ht[i]-ht[i-1],1e-6); p=hp[i-1]*(1-a)+hp[i]*a; q=hq[i-1]*(1-a)+hq[i]*a; q=q/np.linalg.norm(q); return p,q
def raw_dir(hp,hq,origin,right):
    f=qrot(hq,np.array([0,0,-1.0])); fl=math.hypot(f[0],f[2])
    if fl<0.2: f=qrot(hq,np.array([0,1.0,0])); fl=math.hypot(f[0],f[2])
    if fl<1e-3: return None
    fx,fz=f[0]/fl,f[2]/fl; sd=0.17 if right else -0.17
    s=np.array([hp[0]-fz*sd-fx*0.06, hp[1]-0.22, hp[2]+fx*sd-fz*0.06]); d=origin-s; n=np.linalg.norm(d)
    return None if n<0.05 else d/n
class OldRay:
    def __init__(s): s.k=np.zeros(3); s.at=-9; s.pn=-9; s.was=False
    def aim(s,hp,hq,origin,right,pinching,t):
        d=raw_dir(hp,hq,origin,right)
        if d is None: return None
        if pinching and not s.was: s.pn=t
        s.was=pinching; fresh=t-s.at<0.5; hold=pinching and fresh and t-s.pn<0.3
        if not hold:
            if fresh:
                d=s.k*0.7+d*0.3; d/=np.linalg.norm(d)
            s.k=d
        s.at=t; return s.k.copy()
class NewRay:
    """One Euro on direction (time based), latch at closing with a dead band while pinching."""
    def __init__(s,mc=2.0,beta=3.0,lat=0.12,dz=0.025):
        s.mc,s.beta,s.lat,s.dz=mc,beta,lat,dz; s.f=None; s.t=None; s.w=0.0; s.hist=[]; s.was=False; s.a0=None; s.l0=None
    def a(s,dt,fc): return 1/(1+1/(2*math.pi*fc)/dt)
    def aim(s,hp,hq,origin,right,pinching,t):
        d=raw_dir(hp,hq,origin,right)
        if d is None: return None
        if s.f is None or t-s.t>0.5: s.f=d.copy(); s.w=0.0
        else:
            dt=max(t-s.t,1e-3); ang=math.acos(np.clip(np.dot(s.f,d),-1,1))/dt
            s.w=s.a(dt,1.0)*ang+(1-s.a(dt,1.0))*s.w; al=s.a(dt,s.mc+s.beta*s.w)
            f=s.f*(1-al)+d*al; s.f=f/np.linalg.norm(f)
        s.t=t; s.hist.append((t,s.f.copy())); s.hist=[h for h in s.hist if t-h[0]<0.6]
        if pinching and not s.was:
            old=[h for h in s.hist if h[0]<=t-s.lat]; s.a0=(old[-1][1] if old else s.hist[0][1]).copy(); s.l0=s.f.copy()
        s.was=pinching
        if pinching and s.a0 is not None:
            dl=s.f-s.l0; m=np.linalg.norm(dl); eff=0.0 if m<s.dz else (m-s.dz)
            v=s.a0+(dl/m*eff if m>1e-9 else 0); return v/np.linalg.norm(v)
        return s.f.copy()
def angle(a,b): return math.degrees(math.acos(np.clip(np.dot(a,b),-1,1)))
def evaluate(base,mk,label,hz=60):
    drifts=[];jit=[];lag=[]
    for jf in sys.argv[1:]:
        ht,hp,hq=load_head(jf.replace('-joints','-vio'))
        for side in (0,1):
            t,P=load(jf,side)
            if len(t)<300: continue
            gap=np.linalg.norm(P[:,5]-P[:,10],axis=1); fin=np.linalg.norm(P[:,7]-P[:,8],axis=1)+np.linalg.norm(P[:,8]-P[:,9],axis=1)+np.linalg.norm(P[:,9]-P[:,10],axis=1)
            m=pl.New(); st=[]
            for i in range(len(t)):
                if i>0 and t[i]-t[i-1]>0.25: m.lost()
                st.append(m.update(gap[i],fin[i],t[i]))
            ray=mk(); ts=np.arange(t[0],t[-1],1.0/hz); out=[];pinch=[]
            for q in ts:
                i=max(0,np.searchsorted(t,q,side='right')-1)
                if q-t[i]>0.4: out.append(None); pinch.append(False); continue
                p,qq=head_at(ht,hp,hq,q); org=(P[i,5]+P[i,10])/2
                out.append(ray.aim(p,qq,org,side==1,bool(st[i]),q)); pinch.append(bool(st[i]))
            # click drift: short pinches (<0.5 s): ray at release vs ray 120 ms before onset
            k=0
            while k<len(ts):
                if pinch[k] and (k==0 or not pinch[k-1]):
                    e=k
                    while e+1<len(ts) and pinch[e+1]: e+=1
                    dur=ts[e]-ts[k]
                    b=max(0,k-int(0.12*hz))
                    if 0.05<dur<0.5 and out[b] is not None and out[e] is not None: drifts.append(angle(out[b],out[e]))
                    k=e+1
                else: k+=1
            # stationary jitter: windows 0.6 s with palm speed < 3 cm/s, no pinch
            w=int(0.6*hz)
            for k in range(0,len(ts)-w,w):
                seg=out[k:k+w]
                if any(x is None for x in seg) or any(pinch[k:k+w]): continue
                i0=np.searchsorted(t,ts[k]); i1=np.searchsorted(t,ts[k+w])
                if i1-i0<5: continue
                if np.linalg.norm(P[i1-1,0]-P[i0,0])/(t[i1-1]-t[i0])>0.03: continue
                a=np.array(seg); a=a-a.mean(0); jit.append(math.degrees(math.sqrt((a**2).sum(1).mean())))
    d=np.array(drifts);j=np.array(jit)
    print(f"{label:30s} short-click drift: n={len(d)} median {np.median(d):.2f} deg P90 {np.percentile(d,90):.2f} | still-hand ray jitter RMS {math.sqrt((j**2).mean()):.2f} deg (n={len(j)})")
if __name__=="__main__":
    evaluate(None,OldRay,'old ArmRay (0.7/0.3, 0.3 s freeze)')
    for mc,beta,lat,dz in [(2.0,3.0,0.12,0.025),(1.0,3.0,0.12,0.025),(2.0,6.0,0.12,0.04),(2.0,3.0,0.0,0.0),(2.0,3.0,0.12,0.0),(1.0,6.0,0.15,0.04)]:
        evaluate(None,lambda mc=mc,beta=beta,lat=lat,dz=dz:NewRay(mc,beta,lat,dz),f"new mc{mc} b{beta} lat{lat} dz{dz}")
