"""Builds crops (64x64, around the candidate's ROI in the view that found it) for every frame of every candidate track in fb_*.err.
label: 1 = shown track not suspect, 0 = failed candidate (never shown) or listed phantom window. Saves verif.npz"""
import re, struct, numpy as np, cv2
W,H=480,640; REC=8+2*W*H
SUSPECT={'full22':[(1,45.0,54.5),(0,87.8,90.5),(1,97.0,97.6),(0,1.4,2.8),(1,27.4,28.0)]}
X=[];Y=[];META=[]
for rec,fn in (('full17','fb_17.err'),('full20','fb_20.err'),('full22','fb_22.err')):
    mm=np.memmap('C:/uvio/hands_bench/%s.pairs'%rec,dtype=np.uint8,mode='r'); n=(len(mm)-12)//REC
    ts=np.array([struct.unpack('<q',bytes(mm[12+i*REC:12+i*REC+8]))[0] for i in range(n)])/1e9
    ev=[]
    for l in open(fn):
        m=re.match(r'GATE (\S+) (\d) det=(\d) tracked=(\d+) settled=(\d) both=(\d) roi0=(\d),(\S+),(\S+) roi1=(\d),(\S+),(\S+)',l)
        if m: ev.append((float(m[1]),int(m[2]),int(m[3]),int(m[4]),int(m[5]),int(m[7]),float(m[8]),float(m[9]),int(m[10]),float(m[11]),float(m[12])))
    for h in (0,1):
        e=[x for x in ev if x[1]==h]; i=0
        while i<len(e):
            if e[i][2]==1 and e[i][3]==1:
                j=i+1
                while j<len(e) and e[j][2]==1 and e[j][3]==e[j-1][3]+1: j+=1
                seg=e[i:j]; opened=any(x[3]>=4 and x[4]==1 for x in seg)
                for x in seg:
                    sus=any(hh==h and a<=x[0]<=b for hh,a,b in SUSPECT.get(rec,[]))
                    lab=1 if (opened and not sus) else 0
                    if opened and sus: lab=0
                    view=0 if x[5] else (1 if x[8] else None)
                    if view is None: continue
                    cx,cy=(x[6],x[7]) if view==0 else (x[9],x[10])
                    k=int(np.argmin(np.abs(ts-x[0]))); b=k*REC+12+8+view*W*H; im=np.array(mm[b:b+W*H]).reshape(H,W)
                    x0=int(np.clip(cx-48,0,W-96)); y0=int(np.clip(cy-48,0,H-96))
                    X.append(cv2.resize(im[y0:y0+96,x0:x0+96],(64,64))); Y.append(lab); META.append((rec,h,x[0],x[3],view))
                i=j
            else: i+=1
X=np.array(X);Y=np.array(Y)
np.savez('verif.npz',X=X,Y=Y,rec=np.array([m[0] for m in META]),h=np.array([m[1] for m in META]),t=np.array([m[2] for m in META]),n=np.array([m[3] for m in META]))
for r in ('full17','full20','full22'):
    s=np.array([m[0]==r for m in META]); print(r,'pos',int(((Y==1)&s).sum()),'neg',int(((Y==0)&s).sum()))
