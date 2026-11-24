"""usage: new_tracks.py PAIRS BASE.csv VAR.csv CALIB OUT.png  -- intervals where VAR reports a hand and BASE does not (>= 5 results): crops at the middle, at 1/4 and 3/4"""
import sys, csv, json, numpy as np, cv2
pairs, bcsv, vcsv, calib, out = sys.argv[1:6]
W, H = 480, 640; REC = 8 + 2 * W * H
cal = json.load(open(calib))['value0']; k = cal['intrinsics'][0]['intrinsics']
K = np.array([[k['fx'], 0, k['cx']], [0, k['fy'], k['cy']], [0, 0, 1]]); D = np.array([k['k1'], k['k2'], k['k3'], k['k4']])
rb = [r for r in csv.reader(open(bcsv)) if r[0] != 't']; rv = [r for r in csv.reader(open(vcsv)) if r[0] != 't']; n = min(len(rb), len(rv))
mm = np.memmap(pairs, dtype=np.uint8, mode='r', offset=12)
def img(i): b = i * REC + 8; return np.array(mm[b:b + W * H]).reshape(H, W)
def project(x, y, z):
    d = np.array([[[x, -y, -z]]], np.float64); d /= np.linalg.norm(d)
    p, _ = cv2.fisheye.projectPoints(d, np.zeros(3), np.zeros(3), K, D); return p.reshape(2)
ev = []; tot = {}
for hand, bit, c in (("L", 1, 3), ("R", 2, 7)):
    start = None; frames = 0
    for i in range(n + 1):
        bad = i < n and (int(rv[i][1]) & bit) and not (int(rb[i][1]) & bit)
        frames += 1 if bad else 0
        if bad and start is None: start = i
        if not bad and start is not None:
            if i - start >= 5: ev.append((hand, c, start, i))
            start = None
    tot[hand] = frames
print('frames reported only by VAR:', tot, ' events(>=5):', [(h, round(float(rv[a][0]), 1), b - a) for h, c, a, b in ev])
tiles = []
for h, c, a, b in ev:
    for f in (0.25, 0.75):
        m = int(a + (b - a) * f); r = rv[m]
        if r[c] == '': continue
        x, y, z = float(r[c]), float(r[c + 1]), float(r[c + 2]); p = project(x, y, z)
        im = cv2.cvtColor(img(m), cv2.COLOR_GRAY2BGR); u, v = int(p[0]), int(p[1])
        x0 = int(np.clip(u - 90, 0, W - 180)); y0 = int(np.clip(v - 90, 0, H - 180))
        t = cv2.resize(im[y0:y0 + 180, x0:x0 + 180], (240, 240)); cv2.circle(t, (int((u - x0) * 240 / 180), int((v - y0) * 240 / 180)), 5, (0, 0, 255), 1)
        cv2.putText(t, "%s t=%.1f n=%d z=%.2f" % (h, float(r[0]), b - a, -z), (3, 12), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 255, 255), 1); tiles.append(t)
if tiles:
    cols = 6; rws = (len(tiles) + cols - 1) // cols; sheet = np.zeros((rws * 240, cols * 240, 3), np.uint8)
    for j, t in enumerate(tiles[:48]): sheet[(j // cols) * 240:(j // cols + 1) * 240, (j % cols) * 240:(j % cols + 1) * 240] = t
    cv2.imwrite(out, sheet)
