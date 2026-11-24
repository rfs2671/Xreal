import sys, csv, numpy as np
sys.path.insert(0,'/c/uvio/handup')
from predict_lab import load
from jitter_lab import windows
from ray_lab import load_head, head_at
def det(tt,y):
    A=np.vstack([tt,np.ones_like(tt)]).T; co=np.linalg.lstsq(A,y,rcond=None)[0]; r=y-A@co; n=len(tt); return (r**2).sum()*n/(n-2)
acc={}
for f in sys.argv[1:]:
    ht,hp,hq=load_head(f.replace('-joints','-vio'))
    for side in (0,1):
        t,P=load(f,side)
        if len(t)<300: continue
        for i,j in windows(t,P):
            sp=np.linalg.norm(P[j-1].mean(0)-P[i].mean(0))/(t[j-1]-t[i])
            if sp>=0.03: continue
            tt=t[i:j]-t[i:j].mean(); n=j-i
            p,q=head_at(ht,hp,hq,t[(i+j)//2]); w=P[i:j,1].mean(0); ax=w-p; dist=np.linalg.norm(ax)
            if not (0.25<dist<0.75): continue
            ax/=dist
            e1=np.cross(ax,[0,1,0]); e1/=np.linalg.norm(e1); e2=np.cross(ax,e1)
            for name,jn in (("index tip",10),("thumb tip",5),("palm",0),("wrist",1)):
                v=P[i:j,jn]-(P[i:j,1] if jn!=1 else 0*P[i:j,1])
                if jn==1: v=P[i:j,1]
                comp=[det(tt,v@ax),det(tt,v@e1),det(tt,v@e2)]
                acc.setdefault(name,[]).append(comp+[dist])
for k,v in acc.items():
    a=np.array(v); print(f"{k:10s} still windows {len(a)} (hand {a[:,3].mean()*100:.0f} cm from the head): jitter RMS along the line of sight {np.sqrt(a[:,0].mean())*1000:5.2f} mm, across {np.sqrt(a[:,1].mean())*1000:5.2f} and {np.sqrt(a[:,2].mean())*1000:5.2f} mm")
