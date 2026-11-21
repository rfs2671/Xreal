import csv, sys, numpy as np
def load(tag):
    r=np.array([[float(x) for x in row] for row in list(csv.reader(open(tag+'-render.csv')))[1:]])
    v=[x for x in csv.DictReader(open(tag+'-vio.csv'))]
    tv=np.array([float(x['t']) for x in v]); sp=np.linalg.norm(np.array([[float(x['vx']),float(x['vy']),float(x['vz'])] for x in v]),axis=1)
    return r,tv,sp
print('run       head     | place: rms of what is not smooth motion, mm per frame | 99th pct mm | turn: same, degrees | px at 2 m (place, 99th)')
for tag in sys.argv[1:]:
    r,tv,sp=load(tag); r=r[r[:,8]==1]; t=r[:,0]; p=r[:,1:4]; q=r[:,4:8]
    ok=(np.diff(t)[1:]<0.03)&(np.diff(t)[:-1]<0.03)
    dd=np.linalg.norm(p[2:]-2*p[1:-1]+p[:-2],axis=1)*1000
    x,y,z,w=q.T; f=np.stack([-(2*(x*z+w*y)),-(2*(y*z-w*x)),-(1-2*(x*x+y*y))],1)
    ang=np.degrees(np.arccos(np.clip((f[1:]*f[:-1]).sum(1),-1,1))); da=np.abs(np.diff(ang))
    s=np.interp(t[1:-1],tv,sp)
    for name,m in (('still',s<0.05),('slow',(s>=0.05)&(s<0.2)),('moving',s>=0.2)):
        m=m&ok
        if m.sum()<20: continue
        print(f'{tag:9s} {name:8s} | {np.sqrt((dd[m]**2).mean()):6.2f} | {np.percentile(dd[m],99):6.2f} | {np.sqrt((da[m]**2).mean()):.4f} | {42*np.degrees(np.percentile(dd[m],99)/2000):5.1f}   n={m.sum()}')
