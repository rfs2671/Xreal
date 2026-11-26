import re, csv, sys, struct, numpy as np, glob, os
W,H=480,640; REC=8+2*W*H
LABD={'full17':'r17','full20':'r20','full22':'r22','full23':'r23'}
def ts0(rec):
    mm=np.memmap('C:/uvio/hands_bench/%s.pairs'%rec,dtype=np.uint8,mode='r'); return struct.unpack('<q',bytes(mm[12:20]))[0]/1e9
real=[];ph=[]
for f in sorted(glob.glob('jc_*.err')):
    m=re.match(r'jc_(\w+?)_(full\d+)\.err',os.path.basename(f)); cfg,rec=m[1],m[2]
    t0=ts0(rec); labs={int(r[0]):(r[1].strip(),r[2].strip()) for r in csv.reader(open('C:/uvio/hands_bench/%s/labels.csv'%LABD[rec])) if r and r[0].strip().isdigit()}
    for l in open(f):
        m=re.match(r'GATE (\S+) (\d) det=(\d) tracked=(\d+) settled=(\d) both=(\d) roi0=(\d),(\S+),(\S+) roi1=(\d),(\S+),(\S+) err=(\S+) mxy=\S+ size=\S+ ish=(\S+),(\S+) jc=(\S+),(\S+),(\S+),(\S+)',l)
        if not m or m[3]!='1' or int(m[4])<4 or m[5]!='1': continue
        s=int(float(m[1])-t0); h=int(m[2]); letter='lr'[h]
        if s not in labs: continue
        lab=labs[s]; txt=(lab[0]+lab[1])
        found=[(float(m[16]),float(m[17])) if int(m[7]) else None, (float(m[18]),float(m[19])) if int(m[10]) else None]
        found=[x for x in found if x]
        if not found: continue
        jm=max(x[0] for x in found); jn=max(x[1] for x in found); jmin_any=min(x[1] for x in found)
        rec_=(cfg,rec,h,jm,jn,jmin_any,float(m[13]))
        if letter.upper() in txt: real.append(rec_)
        elif letter not in txt.lower(): ph.append(rec_)
def st(name,a):
    a=np.array([x[3:] for x in a]); 
    if not len(a): print(name,'none'); return
    print('%-28s n=%5d  jc mean med %.3f p10 %.3f p90 %.3f | jc min(best view) med %.3f p10 %.3f | worst-view min med %.3f | err med %.3f'%(name,len(a),np.median(a[:,0]),np.percentile(a[:,0],10),np.percentile(a[:,0],90),np.median(a[:,1]),np.percentile(a[:,1],10),np.median(a[:,2]),np.median(a[:,3])))
st('real (plain label)',real); st('phantom (label: none)',ph)
for cfg in sorted(set(x[0] for x in real+ph)):
    st('  real, '+cfg,[x for x in real if x[0]==cfg]); st('  phantom, '+cfg,[x for x in ph if x[0]==cfg])
from sklearn.metrics import roc_auc_score
y=np.array([1]*len(real)+[0]*len(ph)); 
for k,name in ((3,'jc mean'),(4,'jc min (best view)'),(5,'jc min (worst view)')):
    x=np.array([r[k] for r in real+ph]); print('AUC real vs phantom with',name,'%.3f'%roc_auc_score(y,x))
