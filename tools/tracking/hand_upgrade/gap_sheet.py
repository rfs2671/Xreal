import re, struct, numpy as np, cv2, sys
W,H=480,640; REC=8+2*W*H
rec=sys.argv[1]; mm=np.memmap('C:/uvio/hands_bench/%s.pairs'%rec,dtype=np.uint8,mode='r'); n=(len(mm)-12)//REC
ts=np.array([struct.unpack('<q',bytes(mm[12+i*REC:12+i*REC+8]))[0] for i in range(n)])/1e9
ev=[]
for l in open('gt_%s.err'%rec):
    m=re.match(r'GATE (\S+) (\d) det=(\d) tracked=(\d+) settled=(\d) both=(\d)',l)
    if m: ev.append((float(m[1]),int(m[2]),int(m[3]),int(m[4]),int(m[5])))
# opened track intervals per hand
iv={0:[],1:[]}
for h in (0,1):
    e=[x for x in ev if x[1]==h]; i=0
    while i<len(e):
        if e[i][2]==1 and e[i][3]==1:
            j=i+1
            while j<len(e) and e[j][2]==1 and e[j][3]==e[j-1][3]+1: j+=1
            if any(x[3]>=4 and x[4]==1 for x in e[i:j]):
                t_open=next(x[0] for x in e[i:j] if x[3]>=4 and x[4]==1); iv[h].append((e[i][0],t_open,e[j-1][0]))
            i=j
        else: i+=1
gaps=[]
for h in (0,1):
    for a,b in zip(iv[h],iv[h][1:]):
        if b[0]-a[2]<3.0: gaps.append((h,a[2],b[0],b[1]))
print(len(gaps),'gaps',[(h,round(s,1),round(e-s,2),round(o-s,2)) for h,s,e,o in gaps])
rows=[]
for h,s,e,o in gaps[:int(sys.argv[2])]:
    times=np.arange(s-0.1,o+0.35,0.2)[:8]; tiles=[]
    for t in times:
        i=int(np.argmin(np.abs(ts-t))); b=i*REC+12+8; im=np.array(mm[b:b+W*H]).reshape(H,W)
        tl=cv2.cvtColor(cv2.resize(im,(150,200)),cv2.COLOR_GRAY2BGR)
        col=(0,255,0) if t>=o else ((0,200,255) if t>=e else (0,0,255))
        cv2.putText(tl,'%s %+.1f'%('LR'[h],t-s),(3,12),cv2.FONT_HERSHEY_SIMPLEX,0.4,col,1); tiles.append(tl)
    while len(tiles)<8: tiles.append(np.zeros((200,150,3),np.uint8))
    rows.append(np.hstack(tiles))
cv2.imwrite(sys.argv[3],np.vstack(rows))
