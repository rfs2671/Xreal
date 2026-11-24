"""Can optical flow carry a lost hand's patch through the gap to where the next track begins?
usage: flow_bridge.py REC TRACE.err  -- gaps (< 3 s) between shown tracks of the same hand: KLT of the patch around the last ROI from the last tracked frame to the next track's first frame;
distance (pixels, same view) of the flow's endpoint, of 'stay where it was', and of constant-velocity, to the next track's first ROI."""
import re, struct, sys, numpy as np, cv2
W,H=480,640; REC=8+2*W*H
rec,fn=sys.argv[1:3]
mm=np.memmap('C:/uvio/hands_bench/%s.pairs'%rec,dtype=np.uint8,mode='r'); n=(len(mm)-12)//REC
ts=np.array([struct.unpack('<q',bytes(mm[12+i*REC:12+i*REC+8]))[0] for i in range(n)])/1e9
def img(i,v): b=i*REC+12+8+v*W*H; return np.array(mm[b:b+W*H]).reshape(H,W)
ev=[]
for l in open(fn):
    m=re.match(r'GATE (\S+) (\d) det=(\d) tracked=(\d+) settled=(\d) both=(\d) roi0=(\d),(\S+),(\S+) roi1=(\d),(\S+),(\S+)',l)
    if m: ev.append((float(m[1]),int(m[2]),int(m[3]),int(m[4]),int(m[5]),(int(m[7]),float(m[8]),float(m[9])),(int(m[10]),float(m[11]),float(m[12]))))
res=[]
for h in (0,1):
    e=[x for x in ev if x[1]==h]; segs=[]; i=0
    while i<len(e):
        if e[i][2]==1 and e[i][3]==1:
            j=i+1
            while j<len(e) and e[j][2]==1 and e[j][3]==e[j-1][3]+1: j+=1
            if any(x[3]>=4 and x[4]==1 for x in e[i:j]): segs.append(e[i:j])
            i=j
        else: i+=1
    for a,b in zip(segs,segs[1:]):
        ta=a[-1][0]; tb=b[0][0]
        if not (0.05<tb-ta<3.0): continue
        for v in (0,1):
            ra=a[-1][5+v]; rb=b[0][5+v]
            if ra[0] and rb[0]:
                p0=np.array([[ra[1],ra[2]]],np.float32); target=np.array([rb[1],rb[2]])
                # velocity from the last 5 tracked frames
                pts=[x[5+v] for x in a[-6:] if x[5+v][0]]; vel=np.zeros(2)
                if len(pts)>=3: vel=(np.array(pts[-1][1:])-np.array(pts[0][1:]))/(len(pts)-1)
                ia=int(np.argmin(np.abs(ts-ta))); ib=int(np.argmin(np.abs(ts-tb)))
                prev=img(ia,v); x0=int(np.clip(ra[1]-32,0,W-64)); y0=int(np.clip(ra[2]-32,0,H-64))
                mask=np.zeros_like(prev); mask[y0:y0+64,x0:x0+64]=255
                f0=cv2.goodFeaturesToTrack(prev,25,0.01,5,mask=mask)
                if f0 is None or len(f0)<3: res.append((rec,h,v,tb-ta,None,np.linalg.norm(target-p0[0]),np.linalg.norm(target-(p0[0]+vel*(ib-ia)))));continue
                pts_=f0.copy(); alive=np.ones(len(pts_),bool); cur=prev
                for k in range(ia+1,ib+1):
                    nxt=img(k,v); p1,st,_=cv2.calcOpticalFlowPyrLK(cur,nxt,pts_,None,winSize=(21,21),maxLevel=3)
                    alive&=st.ravel()==1; pts_=p1; cur=nxt
                if alive.sum()>=2:
                    d=np.median((pts_[alive]-f0[alive]).reshape(-1,2),axis=0); end=p0[0]+d
                    res.append((rec,h,v,tb-ta,np.linalg.norm(target-end),np.linalg.norm(target-p0[0]),np.linalg.norm(target-(p0[0]+vel*(ib-ia)))))
                else: res.append((rec,h,v,tb-ta,None,np.linalg.norm(target-p0[0]),np.linalg.norm(target-(p0[0]+vel*(ib-ia)))))
print(rec,'gaps with a usable ROI in both ends:',len(res))
for r in res: print('  hand %d view %d gap %.2fs | flow %s px | stay %.0f px | const-vel %.0f px'%(r[1],r[2],r[3],'lost' if r[4] is None else '%.0f'%r[4],r[5],r[6]))
fl=[r[4] for r in res if r[4] is not None]; 
if res: print('  flow within 40 px: %d/%d (survived %d); stay within 40: %d; const-vel within 40: %d'%(sum(1 for x in fl if x<40),len(res),len(fl),sum(1 for r in res if r[5]<40),sum(1 for r in res if r[6]<40)))
