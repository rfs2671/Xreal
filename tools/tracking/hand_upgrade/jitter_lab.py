import sys, numpy as np, math
sys.path.insert(0,'/c/uvio/handup')
from predict_lab import load, interp, TIP, J
class OE:
    def __init__(s,mc,beta,dc=1.0): s.mc,s.beta,s.dc=mc,beta,dc; s.x=None
    def a(s,dt,fc): return 1/(1+1/(2*math.pi*fc)/dt)
    def step(s,t,x,speed=None):
        if s.x is None: s.x=x.copy(); s.dx=np.zeros_like(x); s.t=t; return s.x.copy()
        dt=max(t-s.t,1e-3); s.t=t
        d=(x-s.x)/dt; ad=s.a(dt,s.dc); s.dx=ad*d+(1-ad)*s.dx
        sp=np.linalg.norm(s.dx.reshape(-1,3),axis=1).mean() if speed is None else speed
        s.x=s.a(dt,s.mc+s.beta*sp)*x+(1-s.a(dt,s.mc+s.beta*sp))*s.x; return s.x.copy()
def filt(t,P,mc,beta):
    oe=OE(mc,beta); out=np.zeros_like(P)
    for i in range(len(t)):
        if i>0 and t[i]-t[i-1]>0.08: oe.x=None
        out[i]=oe.step(t[i],P[i].reshape(-1)).reshape(J,3)
    return out
def windows(t,P,w=0.6):
    idx=[];i=0
    while i<len(t):
        j=np.searchsorted(t,t[i]+w)
        if j>=len(t): break
        if j-i>=8 and np.diff(t[i:j+1]).max()<0.08: idx.append((i,j))
        i=j
    return idx
def jitter_and_speed(t,P,F,tipset=(5,10)):
    js=[];sp=[]
    for i,j in windows(t,P):
        seg=F[i:j][:,list(tipset)]; tt=t[i:j]-t[i:j].mean()
        speed=np.linalg.norm(P[j-1].mean(0)-P[i].mean(0))/(t[j-1]-t[i])
        # detrend linear
        res=[]
        for k in range(len(tipset)):
            for c in range(3):
                y=seg[:,k,c]; A=np.vstack([tt,np.ones_like(tt)]).T; co=np.linalg.lstsq(A,y,rcond=None)[0]; res.append(y-A@co)
        r=np.array(res); n=j-i
        js.append(np.sqrt((r**2).sum(0).mean()*n/(n-2)/len(tipset)/1)) ; sp.append(speed)
    return np.array(js)*1000,np.array(sp)
def lag(t,P,F):
    # best shift tau (ms) so that F(t) ~ P(t - tau) over fast windows
    best=[]
    for i,j in windows(t,P,0.8):
        sp=np.linalg.norm(P[j-1].mean(0)-P[i].mean(0))/(t[j-1]-t[i])
        path=np.linalg.norm(np.diff(P[i:j].mean(1),axis=0),axis=1).sum()/(t[j-1]-t[i])
        if path<0.25: continue
        errs=[]
        for tau in range(0,121,5):
            e=[]
            for k in range(i+1,j):
                q=interp(t,P,t[k]-tau/1000.0)
                if q is None: continue
                e.append(np.linalg.norm((F[k]-q)[TIP],axis=1).mean())
            errs.append(np.mean(e) if e else 9)
        best.append(5*int(np.argmin(errs)))
    return np.median(best) if best else float('nan'), len(best)
if __name__=="__main__":
    files=sys.argv[1:]
    cfgs=[('raw',None)]+[(f"mc{mc}_b{b}",(mc,b)) for mc in (0.5,1.0,2.0) for b in (5,20,60)]
    for name,cf in cfgs:
        Js=[];Ss=[];L=[]
        for f in files:
            for side in (0,1):
                t,P=load(f,side)
                if len(t)<300: continue
                F=P if cf is None else filt(t,P,*cf)
                j,s=jitter_and_speed(t,P,F); Js+=list(j); Ss+=list(s)
                if cf is not None or name=='raw': L.append(lag(t,P,F))
        Js=np.array(Js);Ss=np.array(Ss); st=Ss<0.03
        print(f"{name:12s} stationary jitter RMS {np.sqrt((Js[st]**2).mean()):4.2f} mm (n={st.sum()})  | median lag in motion {np.nanmedian([a for a,b in L]):5.1f} ms")
