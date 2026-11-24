"""usage: false_events.py PAIRS RUN.csv LABELS.csv CALIB.json OUT.png [recording-name]
Events where the tracker reports a hand at a second the labels say has none: one crop (left camera, around the reported hand) per event, with the event's length."""
import sys, csv, json, numpy as np, cv2
pairs, runcsv, labcsv, calib, out = sys.argv[1:6]
W, H = 480, 640
cal = json.load(open(calib))['value0']; k = cal['intrinsics'][0]['intrinsics']
K = np.array([[k['fx'], 0, k['cx']], [0, k['fy'], k['cy']], [0, 0, 1]]); D = np.array([k['k1'], k['k2'], k['k3'], k['k4']])
lab = {int(r[0]): (r[1].strip(), r[2].strip()) for r in csv.reader(open(labcsv)) if r and r[0].strip().isdigit()}
rows = [r for r in csv.reader(open(runcsv)) if r[0] != 't']
mm = np.memmap(pairs, dtype=np.uint8, mode='r', offset=12); REC = 8 + 2 * W * H
def img(i): b = i * REC + 8; return np.array(mm[b:b + W * H]).reshape(H, W)
def project(x, y, z):
    d = np.array([[[x, -y, -z]]], np.float64); d /= np.linalg.norm(d)
    p, _ = cv2.fisheye.projectPoints(d, np.zeros(3), np.zeros(3), K, D); return p.reshape(2)
ev = []
for hand, bit, cx in (("l", 1, 3), ("r", 2, 7)):
    start = None
    for i, r in enumerate(rows + [None]):
        bad = False
        if r is not None and (int(r[1]) & bit):
            s = int(float(r[0])); l = lab.get(s)
            bad = l is not None and hand not in l[0].lower() + l[1].lower()
        if bad and start is None: start = i
        if not bad and start is not None:
            if i - start >= 3: ev.append((hand, start, i))
            start = None
tiles = []
for hand, a, b in ev:
    m = (a + b) // 2; r = rows[m]; c = 3 if hand == "l" else 7
    x, y, z = float(r[c]), float(r[c + 1]), float(r[c + 2]); p = project(x, y, z)
    im = cv2.cvtColor(img(m), cv2.COLOR_GRAY2BGR)
    u, v = int(p[0]), int(p[1])
    x0 = int(np.clip(u - 90, 0, W - 180)); y0 = int(np.clip(v - 90, 0, H - 180))
    t = cv2.resize(im[y0:y0 + 180, x0:x0 + 180], (270, 270)); cv2.circle(t, (int((u - x0) * 1.5), int((v - y0) * 1.5)), 6, (0, 0, 255), 1)
    cv2.putText(t, "%s t=%.1f n=%d z=%.2f" % (hand, float(r[0]), b - a, -z), (4, 14), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1)
    tiles.append(t)
print(len(ev), "events", [(h, round(float(rows[a][0]), 1), b - a) for h, a, b in ev][:40])
if tiles:
    cols = 4; rws = (len(tiles) + cols - 1) // cols; sheet = np.zeros((rws * 270, cols * 270, 3), np.uint8)
    for j, t in enumerate(tiles): sheet[(j // cols) * 270:(j // cols + 1) * 270, (j % cols) * 270:(j % cols + 1) * 270] = t
    cv2.imwrite(out, sheet)
