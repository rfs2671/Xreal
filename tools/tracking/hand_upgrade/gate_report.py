import sys, re, numpy as np
rows=[]
for f in sys.argv[1:]:
    tr={0:[],1:[]}
    for l in open(f):
        m=re.match(r'GATE (\S+) (\d) det=(\d) tracked=(\d+) settled=(\d) both=(\d)',l)
        if m: tr[int(m[2])].append((float(m[1]),int(m[3]),int(m[4]),int(m[5]),int(m[6])))
    for h in (0,1):
        ev=tr[h]; i=0
        while i<len(ev):
            if ev[i][1]==1 and ev[i][2]==1:
                j=i+1
                while j<len(ev) and ev[j][1]==1 and ev[j][2]==ev[j-1][2]+1: j+=1
                seg=ev[i:j]; t0=seg[0][0]
                def first(pred):
                    for e in seg:
                        if pred(e): return e[0]-t0
                    return None
                ts=first(lambda e:e[3]==1); tb=first(lambda e:e[4]==1); tw=first(lambda e:e[2]>=4)
                disp=None
                if None not in (ts,tb,tw): disp=max(ts,tb,tw)
                rows.append((f.split('_')[-1][:6],h,t0,len(seg),tw,ts,tb,disp))
                i=j
            else: i+=1
print('recording hand t0 frames | warmup(4 frames) settle(5 good fits) both-views | open(max) | gate last')
for r in rows:
    f,h,t0,n,tw,ts,tb,d=r
    last=None
    if d is not None: last=['warmup','settle','both'][int(np.argmax([tw,ts,tb]))]
    fm=lambda x:'  -  ' if x is None else '%.2f'%x
    print(f,h,'%.1f'%t0,n,'|',fm(tw),fm(ts),fm(tb),'|',fm(d),'|',last or 'never opened (track ended first)')
ds=[r[7] for r in rows if r[7] is not None]
print('tracks %d, opened %d, median open delay %.2f s, p25 %.2f p75 %.2f'%(len(rows),len(ds),np.median(ds),np.percentile(ds,25),np.percentile(ds,75)))
for k,name in ((4,'warmup'),(5,'settle'),(6,'both')): 
    v=[r[k] for r in rows if r[k] is not None]; print(name,'median %.2f s  max %.2f'%(np.median(v),max(v)))
