import sys, numpy as np
sys.path.insert(0,'/c/uvio/handup')
from predict_lab import load
from jitter_lab import windows
def rms_detr(tt,y):
    A=np.vstack([tt,np.ones_like(tt)]).T; co=np.linalg.lstsq(A,y,rcond=None)[0]; r=y-A@co; n=len(tt); return (r**2).sum()*n/(n-2)
tot={}
for f in sys.argv[1:]:
    for side in (0,1):
        t,P=load(f,side)
        if len(t)<300: continue
        for i,j in windows(t,P):
            tt=t[i:j]-t[i:j].mean(); n=j-i
            sp=np.linalg.norm(P[j-1].mean(0)-P[i].mean(0))/(t[j-1]-t[i])
            if sp>=0.03: continue
            def add(name,arr): # arr n x 3
                s=sum(rms_detr(tt,arr[:,c]) for c in range(3))/n
                tot.setdefault(name,[]).append(s)
            add('palm (world)',P[i:j,0]); add('index tip (world)',P[i:j,10]); add('index tip rel wrist',P[i:j,10]-P[i:j,1]); add('thumb tip rel wrist',P[i:j,5]-P[i:j,1])
            g=np.linalg.norm(P[i:j,5]-P[i:j,10],axis=1)
            tot.setdefault('tip gap',[]).append(rms_detr(tt,g)/n)
for k,v in tot.items(): print(f"{k:22s} jitter RMS {np.sqrt(np.mean(v))*1000:5.2f} mm over {len(v)} still windows")
