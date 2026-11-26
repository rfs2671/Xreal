import sys, numpy as np, torch, warnings; warnings.filterwarnings('ignore')
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
torch.set_num_threads(4)
d=np.load(sys.argv[1]); X=d['crops']; Y=d['lab']; R=d['rec']; SC=d['score']
m=torch.load('mnv3s.pt',weights_only=False).eval()
mean=torch.tensor([0.485,0.456,0.406]).view(1,3,1,1); std=torch.tensor([0.229,0.224,0.225]).view(1,3,1,1)
def feats(X):
    out=[]
    with torch.no_grad():
        for i in range(0,len(X),64):
            b=torch.from_numpy(X[i:i+64]).float().div(255).unsqueeze(1).repeat(1,3,1,1); b=torch.nn.functional.interpolate(b,size=(112,112),mode='bilinear',align_corners=False)
            b=(b-mean)/std; f=m.avgpool(m.features(b)).flatten(1); out.append(f.numpy())
    return np.concatenate(out)
F=feats(X); np.save('ver_feats.npy',F)
print('features',F.shape)
for hold in ('full17','full20','full22','full23'):
    te=R==hold; tr=~te
    clf=LogisticRegression(C=0.3,max_iter=3000,class_weight='balanced').fit(F[tr],Y[tr]); p=clf.predict_proba(F[te])[:,1]
    pos=Y[te]==1; neg=~pos
    if neg.sum()==0 or pos.sum()==0: continue
    auc=roc_auc_score(Y[te],p); auc_det=roc_auc_score(Y[te],SC[te])
    # operating point: keep 95 % of positives
    thr=np.percentile(p[pos],5); rej=(p[neg]<thr).mean()
    thr2=np.percentile(p[pos],1); rej2=(p[neg]<thr2).mean()
    print('held out %s: %d pos %d neg | verifier AUC %.3f (detector score alone %.3f) | at 95%% positives kept: %.0f%% of negatives rejected; at 99%% kept: %.0f%%'%(hold,pos.sum(),neg.sum(),auc,auc_det,100*rej,100*rej2))
