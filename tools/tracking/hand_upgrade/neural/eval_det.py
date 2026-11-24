"""usage: eval_det.py RECORDING MODEL.pt[,MODEL2.pt...]  ('orig' = Mercury's own). Per labelled picture: is any hand reported (whole picture letterboxed, or any of 9 tiles), by threshold."""
import sys, csv, json, struct, numpy as np, cv2, torch
torch.set_num_threads(4)
rec = sys.argv[1]; models = sys.argv[2].split(',')
W, H = 480, 640; REC = 8 + 2 * W * H; FOV = 70.0; F = 80.0 / np.tan(np.radians(FOV / 2))
lab_path = {'full17': 'r17', 'full20': 'r20', 'full22': 'r22', 'full23': 'r23'}[rec]
CAL = 'C:/Users/asddd/Downloads/Xreal-claude-pixel-xreal-xr-gaming-cguv7y/_latest/app/build/intermediates/assets/debug/mergeDebugAssets/calibration/basalt_calib.json'
cal = json.load(open(CAL))['value0']
def cam(i): k = cal['intrinsics'][i]['intrinsics']; return np.array([[k['fx'], 0, k['cx']], [0, k['fy'], k['cy']], [0, 0, 1]]), np.array([k['k1'], k['k2'], k['k3'], k['k4']])
CAMS = [cam(0), cam(1)]
mm = np.memmap('C:/uvio/hands_bench/%s.pairs' % rec, dtype=np.uint8, mode='r'); n = (len(mm) - 12) // REC
ts = np.array([struct.unpack('<q', bytes(mm[12 + i * REC:12 + i * REC + 8]))[0] for i in range(n)]) / 1e9
def image(i, v): b = 12 + i * REC + 8 + v * W * H; return np.array(mm[b:b + W * H]).reshape(H, W)
lab = {int(r[0]): (r[1].strip(), r[2].strip()) for r in csv.reader(open('C:/uvio/hands_bench/%s/labels.csv' % lab_path)) if r and r[0].strip().isdigit()}
def M_yp(yaw, pitch):
    a, b = np.radians(yaw), np.radians(pitch); ca, sa, cb, sb = np.cos(a), np.sin(a), np.cos(b), np.sin(b)
    return np.array([[ca, -sa * sb, sa * cb], [0, cb, sb], [-sa, -ca * sb, ca * cb]])
gu, gv = np.meshgrid(np.arange(160) + 0.5, np.arange(160) + 0.5)
RAYS = np.stack([(gu.ravel() - 80) / F, (gv.ravel() - 80) / F, np.ones(160 * 160)], 1); RAYS /= np.linalg.norm(RAYS, axis=1, keepdims=True)
SYM9 = [(-24, 40), (24, 40), (-24, 58), (24, 58), (-24, -22), (24, -22), (-48, 40), (48, 40), (0, 22)]
MAPS = {}
for v in (0, 1):
    K, D = CAMS[v]
    for t in SYM9:
        d = RAYS @ M_yp(*t).T; good = d[:, 2] > 0.05; o = np.full((len(d), 2), -1.0)
        p, _ = cv2.fisheye.projectPoints(d[good].reshape(-1, 1, 3), np.zeros(3), np.zeros(3), K, D); o[good] = p.reshape(-1, 2); MAPS[(v, t)] = o.astype(np.float32).reshape(160, 160, 2)
def prep(im):
    f = im.astype(np.float32) / 255.0; sd = f.std() or 1.0; f *= 0.25 / sd; f += 0.5 - f.mean(); return f
LB = np.array([[0.25, 0, 20], [0, 0.25, 0]], np.float32)
def load(m):
    if m == 'orig': return torch.load('C:/uvio/mp/merc_det_torch.pt', weights_only=False).eval()
    return torch.load(m, weights_only=False).eval()
MODELS = {m: load(m) for m in models}
def run(model, ims):
    with torch.no_grad(): o = model(torch.from_numpy(np.array([prep(i) for i in ims])).unsqueeze(1)); return o[0].numpy().max(1)   # per image: best of the two slots
res = {m: [] for m in models}
for s in sorted(lab):
    for off in (0.25, 0.75):
        i = int(np.argmin(np.abs(ts - (ts[0] + s + off))))
        for v in (0, 1):
            l = lab[s][v]; exp = len(set(c.lower() for c in l)); plain = int(any(c.isupper() for c in l)); im = image(i, v)
            whole = cv2.warpAffine(im, LB, (160, 160)); tiles = [cv2.remap(im, MAPS[(v, t)][..., 0], MAPS[(v, t)][..., 1], cv2.INTER_LINEAR, borderValue=0) for t in SYM9]
            for m, mod in MODELS.items():
                sw = float(run(mod, [whole])[0]); st = float(run(mod, tiles).max()); res[m].append((exp, plain, sw, st))
print(rec, 'pictures', len(res[models[0]]))
for m in models:
    a = np.array(res[m]); none = a[:, 0] == 0; plain = a[:, 1] == 1; anyv = a[:, 0] > 0
    for thr in (0.5, 0.7, 0.9, 0.97):
        for nm, col in (('whole', 2), ('tiles9', 3)):
            hit = a[:, col] > thr
            print('%-12s %-6s thr %.2f  plainly-visible found %3d/%3d (%3.0f%%)  any visible %3.0f%%  hand-free pictures with a detection %3d/%3d (%3.0f%%)' % (m.split('/')[-1], nm, thr, hit[plain].sum(), plain.sum(), 100 * hit[plain].mean(), 100 * hit[anyv].mean(), hit[none].sum(), none.sum(), 100 * hit[none].mean()))
