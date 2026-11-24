import sys, numpy as np, math
sys.path.insert(0,'/c/uvio/handup')
from predict_lab import load
class Old:   # faithful port of core/SessionTools.kt PinchState (frames=1)
    PRESS=0.033; REL=0.046; W=60; LOOK=12; STAY=3; STEADY=0.012; RATIO=0.62; DROP=0.030; MAX=0.070; BACK=0.020
    def __init__(s): s.p=False; s.recent=[0.0]*60; s.seen=0; s.smallest=0; s.byd=False
    def update(s,gap,finger=0.0,t=None):
        scaled=finger>0.03; near=gap<s.PRESS or (scaled and gap<0.46*finger); far=gap>s.REL and ((not scaled) or gap>0.65*finger)
        s.recent[s.seen%60]=gap; s.seen+=1
        open_=0.0;before=0.0;lo=gap;hi=gap;n=min(s.seen,60)
        for i in range(n):
            g=s.recent[(s.seen-1-i)%60]
            if g>open_: open_=g
            if i<3:
                lo=min(lo,g); hi=max(hi,g)
            elif i<15 and g>before: before=g
        closing= n>3 and hi<0.62*open_ and hi<0.070 and hi-lo<=0.012 and before-gap>=0.030
        was=s.p; together=near or closing
        if not was: apart=False
        else:
            if near: s.byd=True
            if gap<s.smallest: s.smallest=gap
            apart= far if s.byd else gap> s.smallest+max(0.020,0.3*(open_-s.smallest))
        wants=(not apart) if was else together
        if wants==was: return s.p
        s.p=wants
        if s.p: s.smallest=gap; s.byd=near
        return s.p
    def lost(s): s.p=False; s.seen=0
def series(f,side):
    t,P=load(f,side)
    gap=np.linalg.norm(P[:,5]-P[:,10],axis=1)
    fin=np.linalg.norm(P[:,7]-P[:,8],axis=1)+np.linalg.norm(P[:,8]-P[:,9],axis=1)+np.linalg.norm(P[:,9]-P[:,10],axis=1)
    return t,gap,fin
def run(machine,t,gap,fin):
    st=[];m=machine()
    for i in range(len(t)):
        if i>0 and t[i]-t[i-1]>0.25: m.lost()
        st.append(m.update(gap[i],fin[i],t[i]))
    return np.array(st)
def events(t,st):
    on=[];off=[];prev=False
    for i,v in enumerate(st):
        if v and not prev: on.append(t[i])
        if (not v) and prev: off.append(t[i])
        prev=v
    return on,off
def certain(t,gap):   # intervals where raw gap < 22 mm for >=3 consecutive samples
    out=[];i=0
    while i<len(t):
        if gap[i]<0.022:
            j=i
            while j+1<len(t) and gap[j+1]<0.022 and t[j+1]-t[j]<0.1: j+=1
            if j-i>=2: out.append((i,j))
            i=j+1
        else: i+=1
    return out
def report(name,machine,files):
    tot=dict(cert=0,hit=0,on=0,flick=0,unsup=0,lat=[],rel=[],hold_open=0,opensamples=0,openfalse=0)
    for f in files:
        for side in (0,1):
            t,gap,fin=series(f,side)
            if len(t)<300: continue
            st=run(machine,t,gap,fin); on,off=events(t,st)
            cs=certain(t,gap)
            tot['cert']+=len(cs)
            for i,j in cs:
                hit=[o for o in on if t[i]-0.15<=o<=t[j]+0.05]
                if hit:
                    tot['hit']+=1
                    # latency: from the time gap fell under 40 mm on the way down
                    k=i
                    while k>0 and gap[k-1]<0.04+0.0 and t[k]-t[k-1]<0.1: k-=1
                    k0=max(0,i-6)
                    cross=t[i]
                    for q in range(i,max(0,i-8),-1):
                        if gap[q]>=0.040: cross=t[q]; break
                    tot['lat'].append((hit[0]-cross)*1000)
            tot['on']+=len(on)
            for o in on:
                k=np.searchsorted(t,o); 
                dur=[x for x in off if x>o]; d=(dur[0]-o) if dur else 9
                if d<0.12: tot['flick']+=1
                near=np.abs(t-o)<0.3
                if gap[near].min()>0.035: tot['unsup']+=1
            # open-hand false: samples with gap>0.06 flagged pinching
            op=gap>0.060; tot['opensamples']+=op.sum(); tot['openfalse']+=(st&op).sum()
    L=np.array(tot['lat']) if tot['lat'] else np.array([np.nan])
    print(f"{name:14s} certain pinches {tot['cert']:4d} caught {tot['hit']:4d} ({100*tot['hit']/max(1,tot['cert']):.0f}%) | onsets {tot['on']:4d} flicker<120ms {tot['flick']:3d} unsupported {tot['unsup']:3d} | open-hand samples pinching {tot['openfalse']}/{tot['opensamples']} | onset vs 40mm-crossing: median {np.nanmedian(L):.0f} ms P90 {np.nanpercentile(L,90):.0f}")
if __name__=="__main__":
    report('old PinchState',Old,sys.argv[1:])

class New(Old):
    """Old rule plus: anticipation from closing velocity, fast release on quick opening, stale and plausibility guards."""
    LEAD=0.045; DROP2=0.20; NMAX=0.75; PRESS_N=0.46; OPEN_V=2.0
    def __init__(s): super().__init__(); s.hist=[]; s.early=False; s.early_t=0; s.fn=None
    def update(s,gap,finger=0.0,t=None,age=0.0):
        scale=finger if finger>0.03 else 0.071
        n=gap/scale; s.hist.append((t,n)); s.hist=s.hist[-4:]
        was=s.p
        plaus=0.045<scale<0.11 and gap<0.20
        r=Old.update(s,gap,finger,t)
        if r and not was and not plaus: s.p=False; r=False
        if not r and not was and plaus and age<0.25 and len(s.hist)>=3:
            (t2,n2),(t1,n1),(t0,n0)=s.hist[-3],s.hist[-2],s.hist[-1]
            v=(n0-n2)/(t0-t2) if t0>t2 else 0
            if n0<s.NMAX and n2-n0>=s.DROP2 and n2>n1>n0 and n0+v*s.LEAD<s.PRESS_N:
                s.p=True; s.smallest=gap; s.byd=False; s.early=True; s.early_t=t; s.seen=max(s.seen,1)
        if s.p and s.early and t-s.early_t>0.12:
            s.early=False
            if n>0.60 and not (gap<s.PRESS): s.p=False
        if s.p and was and len(s.hist)>=2:
            (t1,n1),(t0,n0)=s.hist[-2],s.hist[-1]; v=(n0-n1)/(t0-t1) if t0>t1 else 0
            if v>s.OPEN_V and n0>s.PRESS_N+0.10: s.p=False
        return s.p
if __name__=="__main__":
    report('new machine',New,sys.argv[1:])

class Rel(New):
    """New + fast release with a lockout against chatter."""
    LOCK=0.15; OPEN_V=2.0; ON=0.56
    def __init__(s): super().__init__(); s.lock=-1
    def update(s,gap,finger=0.0,t=None,age=0.0):
        r=New.update(s,gap,finger,t,age)
        if t<s.lock and gap>=0.025: s.p=False; r=False
        return s.p
