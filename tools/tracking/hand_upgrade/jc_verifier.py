import re, csv, struct, numpy as np, glob, os, warnings; warnings.filterwarnings('ignore')
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
LABD={'full17':'r17','full20':'r20','full22':'r22','full23':'r23'}
def ts0(rec):
    mm=np.memmap('C:/uvio/hands_bench/%s.pairs'%rec,dtype=np.uint8,mode='r'); return struct.unpack('<q',bytes(mm[12:20]))[0]/1e9
rows=[]   # cfg, rec, hand, t, label, feats..., trackid
for f in sorted(glob.glob('jc_*.err')):
    m=re.match(r'jc_(\w+?)_(full\d+)\.err',os.path.basename(f)); cfg,rec=m[1],m[2]; t0=ts0(rec)
    labs={int(r[0]):(r[1].strip(),r[2].strip()) for r in csv.reader(open('C:/uvio/hands_bench/%s/labels.csv'%LABD[rec])) if r and r[0].strip().isdigit()}
    prev={0:-1,1:-1}; tid={0:0,1:0}
    for l in open(f):
        m=re.match(r'GATE (\S+) (\d) det=(\d) tracked=(\d+) settled=(\d) both=(\d) roi0=(\d),(\S+),(\S+) roi1=(\d),(\S+),(\S+) err=(\S+) mxy=\S+ size=\S+ ish=(\S+),(\S+) jc=(\S+),(\S+),(\S+),(\S+)',l)
        if not m or m[3]!='1': continue
        h=int(m[2]); tr=int(m[4])
        if tr==1: tid[h]+=1
        if tr<4 or m[5]!='1': continue
        s=int(float(m[1])-t0); 
        if s not in labs: continue
        txt=labs[s][0]+labs[s][1]; letter='lr'[h]
        lab=1 if letter.upper() in txt else (0 if letter not in txt.lower() else None)
        if lab is None: continue
        v=[(float(m[16]),float(m[17])) if int(m[7]) else None,(float(m[18]),float(m[19])) if int(m[10]) else None]; v=[x for x in v if x]
        if not v: continue
        rows.append((cfg,rec,h,float(m[1]),lab,max(x[0] for x in v),min(x[0] for x in v),max(x[1] for x in v),min(x[1] for x in v),float(m[13]),len(v),'%s-%d-%d'%(rec,h,tid[h])))
R=np.array([r[1] for r in rows]); Y=np.array([r[4] for r in rows]); X=np.array([r[5:11] for r in rows],float); T=np.array([r[3] for r in rows]); TID=np.array([r[11] for r in rows])
names=['jc mean best','jc mean worst','jc min best','jc min worst','fit error','views']
print('frames',len(Y),'real',int(Y.sum()),'phantom',int((1-Y).sum()))
def smooth(x,tid,k=5):
    o=x.copy()
    for t in set(tid):
        idx=np.where(tid==t)[0]
        for a,i in enumerate(idx): o[i]=x[idx[max(0,a-k+1):a+1]].mean(0)
    return o
Xs=smooth(X,TID,5)
for label,XX in (('single frame',X),('mean of last 5 frames (167 ms)',Xs)):
    print('---',label)
    for hold in ('full17','full20','full22','full23'):
        te=R==hold; tr=~te
        if (Y[te]==0).sum()<20 or (Y[te]==1).sum()<20: print(hold,'too few of one class',int((Y[te]==0).sum()),int((Y[te]==1).sum())); continue
        mu=XX[tr].mean(0); sd=XX[tr].std(0)+1e-6
        clf=LogisticRegression(C=1.0,class_weight='balanced',max_iter=2000).fit((XX[tr]-mu)/sd,Y[tr]); p=clf.predict_proba((XX[te]-mu)/sd)[:,1]
        pos=Y[te]==1; neg=~pos; thr=np.percentile(p[pos],5); thr2=np.percentile(p[pos],2)
        print('held out %s: %5d real %5d phantom frames | AUC %.3f | jc-mean-only AUC %.3f | keep 95%% of real: reject %.0f%% of phantom frames; keep 98%%: reject %.0f%%'%(hold,pos.sum(),neg.sum(),roc_auc_score(Y[te],p),roc_auc_score(Y[te],XX[te][:,0]),100*(p[neg]<thr).mean(),100*(p[neg]<thr2).mean()))
print('--- gate on the 5-frame mean joint confidence (best view): per configuration')
cfgs=np.array([r[0] for r in rows])
for cfg,rec in (('base','full17'),('base','full20'),('base','full22'),('base','full23'),('B','full22'),('ft2','full22'),('ft2','full23'),('ft3','full23')):
    sel=(cfgs==cfg)&(R==rec)
    if sel.sum()==0: continue
    pos=sel&(Y==1); neg=sel&(Y==0); out=[]
    for thr in (0.40,0.45,0.50,0.55):
        out.append('thr %.2f: real kept %3.0f%%, phantom frames rejected %3.0f%%'%(thr,100*(Xs[pos][:,0]>=thr).mean(),100*(Xs[neg][:,0]<thr).mean() if neg.sum() else float('nan')))
    print('%-5s %s (%d real, %d phantom) | '%(cfg,rec,pos.sum(),neg.sum())+' | '.join(out))
