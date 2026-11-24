"""usage: cand_sheet.py OUTPREFIX  -- candidate tracks (begun, never reached 4 frames settled) from gt_*.err traces: crops at the begin frame, sheets + cands.csv"""
import re, sys, struct, numpy as np, cv2, collections, csv
W, H = 480, 640; REC = 8 + 2 * W * H; out = sys.argv[1]
cands = []
for rec in ('full17', 'full20', 'full22'):
    mm = np.memmap('C:/uvio/hands_bench/%s.pairs' % rec, dtype=np.uint8, mode='r')
    n = (len(mm) - 12) // REC; ts = np.array([struct.unpack('<q', bytes(mm[12 + i * REC:12 + i * REC + 8]))[0] for i in range(n)]) / 1e9
    ev = []; drops = collections.defaultdict(dict)
    for l in open('gt_%s.err' % rec):
        m = re.match(r'GATE (\S+) (\d) det=(\d) tracked=(\d+) settled=(\d) both=(\d) roi0=(\d),(\S+),(\S+) roi1=(\d),(\S+),(\S+)', l)
        if m: ev.append((float(m[1]), int(m[2]), int(m[3]), int(m[4]), int(m[5]), int(m[7]), float(m[8]), float(m[9]), int(m[10]), float(m[11]), float(m[12])))
        m = re.match(r'DROP (\S+) (\d) (\S+)', l)
        if m: drops[int(m[2])][float(m[1])] = m[3]
    for h in (0, 1):
        e = [x for x in ev if x[1] == h]; i = 0
        while i < len(e):
            if e[i][2] == 1 and e[i][3] == 1:
                j = i + 1
                while j < len(e) and e[j][2] == 1 and e[j][3] == e[j - 1][3] + 1: j += 1
                opened = any(x[3] >= 4 and x[4] == 1 for x in e[i:j]); tend = e[j - 1][0]
                why = next((drops[h][k] for k in sorted(drops[h]) if 0 < k - tend < 0.12 and drops[h][k] != 'no_roi'), 'no_roi')
                f = e[i]; idx = int(np.argmin(np.abs(ts - f[0])))
                cands.append(dict(rec=rec, t=f[0], h=h, n=j - i, opened=opened, why=why, idx=idx, v0=f[5], x0=f[6], y0=f[7], v1=f[8], x1=f[9], y1=f[10], mm=mm))
                i = j
            else: i += 1
sel = [c for c in cands if not c['opened']]
tiles = []; rowsout = []
for k, c in enumerate(sel):
    view = 0 if c['v0'] else 1; x, y = (c['x0'], c['y0']) if view == 0 else (c['x1'], c['y1'])
    b = c['idx'] * REC + 12 + 8 + view * W * H; im = np.array(c['mm'][b:b + W * H]).reshape(H, W)
    x0 = int(np.clip(x - 60, 0, W - 120)); y0 = int(np.clip(y - 60, 0, H - 120))
    t = cv2.cvtColor(cv2.resize(im[y0:y0 + 120, x0:x0 + 120], (240, 240)), cv2.COLOR_GRAY2BGR)
    cv2.circle(t, (int((x - x0) * 2), int((y - y0) * 2)), 5, (0, 0, 255), 1)
    cv2.putText(t, "#%d %s %.1f %s%s n=%d %s" % (k, c['rec'][4:], c['t'], 'LR'[c['h']], 'lr'[view], c['n'], c['why'][:7]), (3, 12), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 255), 1)
    tiles.append(t); rowsout.append((k, c['rec'], round(c['t'], 3), c['h'], view, round(x), round(y), c['n'], c['why']))
csv.writer(open(out + '_cands.csv', 'w', newline='')).writerows(rowsout)
per = 24
for s in range(0, len(tiles), per):
    chunk = tiles[s:s + per]; cols = 6; rws = (len(chunk) + cols - 1) // cols; sheet = np.zeros((rws * 240, cols * 240, 3), np.uint8)
    for j, t in enumerate(chunk): sheet[(j // cols) * 240:(j // cols + 1) * 240, (j % cols) * 240:(j % cols + 1) * 240] = t
    cv2.imwrite('%s_%d.png' % (out, s // per), sheet)
print(len(sel), 'failed candidates;', len(cands) - len(sel), 'opened')
