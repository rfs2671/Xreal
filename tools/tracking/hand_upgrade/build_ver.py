"""Candidate mining for the verifier (stage 2). Stage 1 = a high-recall detector (a fine-tuned Mercury detector, whole picture + nine tiles), run on the left camera of every labelled second.
Every detection is a candidate. Label: negative = the picture has no hand of the wearer (labels), positive = the detection lies within 45 px of a tracked hand (the tracker's own position, projected)
in a picture where a hand is plainly visible. Anything else is left out (unclear). Saves 96x96 crops, labels, scores, recording, second.
usage: build_ver.py DETECTOR.pt OUT.npz"""
import sys, csv, json, struct, numpy as np, cv2, torch
torch.set_num_threads(4)
det_path, out = sys.argv[1:3]
W, H = 480, 640; REC = 8 + 2 * W * H; F = 80.0 / np.tan(np.radians(35))
CAL = 'C:/Users/asddd/Downloads/Xreal-claude-pixel-xreal-xr-gaming-cguv7y/_latest/app/build/intermediates/assets/debug/mergeDebugAssets/calibration/basalt_calib.json'
k = json.load(open(CAL))['value0']['intrinsics'][0]['intrinsics']
K = np.array([[k['fx'], 0, k['cx']], [0, k['fy'], k['cy']], [0, 0, 1]]); D = np.array([k['k1'], k['k2'], k['k3'], k['k4']])
model = torch.load(det_path, weights_only=False).eval()
def M_yp(yaw, pitch):
    a, b = np.radians(yaw), np.radians(pitch); ca, sa, cb, sb = np.cos(a), np.sin(a), np.cos(b), np.sin(b)
    return np.array([[ca, -sa * sb, sa * cb], [0, cb, sb], [-sa, -ca * sb, ca * cb]])
gu, gv = np.meshgrid(np.arange(160) + 0.5, np.arange(160) + 0.5)
RAYS = np.stack([(gu.ravel() - 80) / F, (gv.ravel() - 80) / F, np.ones(160 * 160)], 1); RAYS /= np.linalg.norm(RAYS, axis=1, keepdims=True)
SYM9 = [(-24, 40), (24, 40), (-24, 58), (24, 58), (-24, -22), (24, -22), (-48, 40), (48, 40), (0, 22)]
MAPS = {}
for t in SYM9:
    d = RAYS @ M_yp(*t).T; good = d[:, 2] > 0.05; o = np.full((len(d), 2), -1.0)
    p, _ = cv2.fisheye.projectPoints(d[good].reshape(-1, 1, 3), np.zeros(3), np.zeros(3), K, D); o[good] = p.reshape(-1, 2); MAPS[t] = o.astype(np.float32).reshape(160, 160, 2)
def back(t, x, y):
    d = M_yp(*t) @ np.array([(x - 80) / F, (y - 80) / F, 1.0]); d /= np.linalg.norm(d)
    p, _ = cv2.fisheye.projectPoints(d.reshape(1, 1, 3), np.zeros(3), np.zeros(3), K, D); return p.reshape(2)
def prep(im):
    f = im.astype(np.float32) / 255.0; sd = f.std() or 1.0; f *= 0.25 / sd; f += 0.5 - f.mean(); return f
LB = np.array([[0.25, 0, 20], [0, 0.25, 0]], np.float32)
def detect(im):
    ins = [cv2.warpAffine(im, LB, (160, 160))] + [cv2.remap(im, MAPS[t][..., 0], MAPS[t][..., 1], cv2.INTER_LINEAR, borderValue=0) for t in SYM9]
    with torch.no_grad(): o = model(torch.from_numpy(np.array([prep(i) for i in ins])).unsqueeze(1))
    ex, cx, cy = o[0].numpy(), (o[1].numpy() + 1) * 80, (o[2].numpy() + 1) * 80; res = []
    for i in range(len(ins)):
        for s in (0, 1):
            if ex[i, s] < 0.5: continue
            if i == 0: res.append(((cx[i, s] - 20) / 0.25, cy[i, s] / 0.25, float(ex[i, s]), 'w'))
            else:
                q = back(SYM9[i - 1], cx[i, s], cy[i, s]); res.append((float(q[0]), float(q[1]), float(ex[i, s]), 't%d' % (i - 1)))
    return res
SETS = {'full17': 'r17', 'full20': 'r20', 'full22': 'r22', 'full23': 'r23'}
CR = []; LAB = []; SC = []; REC_ = []; SEC = []; SRC = []
for rec, rd in SETS.items():
    mm = np.memmap('C:/uvio/hands_bench/%s.pairs' % rec, dtype=np.uint8, mode='r'); n = (len(mm) - 12) // REC
    ts = np.array([struct.unpack('<q', bytes(mm[12 + i * REC:12 + i * REC + 8]))[0] for i in range(n)]) / 1e9
    labs = {int(r[0]): (r[1].strip(), r[2].strip()) for r in csv.reader(open('C:/uvio/hands_bench/%s/labels.csv' % rd)) if r and r[0].strip().isdigit()}
    rows = [r for r in csv.reader(open('C:/uvio/handup/mod/m_models_%s.csv' % rec)) if r[0] != 't']; rt = np.array([float(r[0]) for r in rows])
    for s in sorted(labs):
        lab = labs[s][0]
        for off in (0.2, 0.5, 0.8):
            i = int(np.argmin(np.abs(ts - (ts[0] + s + off)))); b = 12 + i * REC + 8; im = np.array(mm[b:b + W * H]).reshape(H, W)
            tracked = []
            j = int(np.argmin(np.abs(rt - (ts[i] - ts[0])))) if len(rt) else 0
            for hand, c in (('L', 3), ('R', 7)):
                if rows[j][c] != '' and hand in lab:
                    x, y, z = float(rows[j][c]), float(rows[j][c + 1]), float(rows[j][c + 2])
                    if z < 0:
                        d = np.array([[[x, -y, -z]]]); d /= np.linalg.norm(d); p = cv2.fisheye.projectPoints(d, np.zeros(3), np.zeros(3), K, D)[0].reshape(2); tracked.append(p)
            for (cx, cy, sc, src) in detect(im):
                if lab == '': l = 0
                elif any(np.hypot(cx - p[0], cy - p[1]) < 45 for p in tracked): l = 1
                else: continue
                x0 = int(np.clip(cx - 48, 0, W - 96)); y0 = int(np.clip(cy - 48, 0, H - 96))
                CR.append(im[y0:y0 + 96, x0:x0 + 96]); LAB.append(l); SC.append(sc); REC_.append(rec); SEC.append(s); SRC.append(src)
    print(rec, 'done; so far', len(LAB), 'pos', int(sum(LAB)), flush=True)
np.savez(out, crops=np.array(CR), lab=np.array(LAB), score=np.array(SC), rec=np.array(REC_), sec=np.array(SEC), src=np.array(SRC))
