"""Fine-tune Mercury's hand detector (160x160 grayscale, outputs hand_exists, cx, cy, size for left/right) for peripheral hands.
Teacher = the same detector on a tile centred on the hand (works there). Student input = the same picture re-rendered with the hand rotated to a random place
in the camera's field and cut as a 70 degree pinhole tile at a random orientation, so that the hand appears anywhere in or at the edge of a tile.
Negatives = tiles of hand-free pictures. Train on 17 and 20 November; validate on 22 and 23 November (see eval_det.py).
usage: train_det.py STEPS OUT.pt"""
import sys, csv, json, struct, random, numpy as np, cv2, torch, onnxruntime as ort
torch.set_num_threads(6); random.seed(1); np.random.seed(1); torch.manual_seed(1)
steps = int(sys.argv[1]); outpt = sys.argv[2]
W, H = 480, 640; REC = 8 + 2 * W * H; FOV = 70.0; F = 80.0 / np.tan(np.radians(FOV / 2))
CAL = 'C:/Users/asddd/Downloads/Xreal-claude-pixel-xreal-xr-gaming-cguv7y/_latest/app/build/intermediates/assets/debug/mergeDebugAssets/calibration/basalt_calib.json'
k = json.load(open(CAL))['value0']['intrinsics'][0]['intrinsics']
K = np.array([[k['fx'], 0, k['cx']], [0, k['fy'], k['cy']], [0, 0, 1]]); D = np.array([k['k1'], k['k2'], k['k3'], k['k4']])
ONNX = 'C:/Users/asddd/Downloads/Xreal-claude-pixel-xreal-xr-gaming-cguv7y/_latest/app/src/main/assets/hand-models/grayscale_detection_160x160.onnx'
sess = ort.InferenceSession(ONNX, providers=['CPUExecutionProvider'])
gu, gv = np.meshgrid(np.arange(160) + 0.5, np.arange(160) + 0.5)
RAYS = np.stack([(gu.ravel() - 80) / F, (gv.ravel() - 80) / F, np.ones(160 * 160)], 1); RAYS /= np.linalg.norm(RAYS, axis=1, keepdims=True)
def M_of(d):
    az = np.arctan2(d[0], d[2]); el = np.arctan2(d[1], np.hypot(d[0], d[2]))
    Ry = np.array([[np.cos(az), 0, np.sin(az)], [0, 1, 0], [-np.sin(az), 0, np.cos(az)]]); Rx = np.array([[1, 0, 0], [0, np.cos(-el), -np.sin(-el)], [0, np.sin(-el), np.cos(-el)]]); return Ry @ Rx
def M_yp(yaw, pitch):
    a, b = np.radians(yaw), np.radians(pitch); ca, sa, cb, sb = np.cos(a), np.sin(a), np.cos(b), np.sin(b)
    return np.array([[ca, -sa * sb, sa * cb], [0, cb, sb], [-sa, -ca * sb, ca * cb]])
def project(d):
    good = d[:, 2] > 0.05; o = np.full((len(d), 2), -1.0)
    if good.any(): p, _ = cv2.fisheye.projectPoints(d[good].reshape(-1, 1, 3), np.zeros(3), np.zeros(3), K, D); o[good] = p.reshape(-1, 2)
    return o
def ray_of(px, py):
    n = cv2.fisheye.undistortPoints(np.array([[[px, py]]], np.float64), K, D).reshape(2); r = np.array([n[0], n[1], 1.0]); return r / np.linalg.norm(r)
def render(img, R_T, Q):
    sp = project((RAYS @ R_T.T) @ Q.T).astype(np.float32).reshape(160, 160, 2)
    return cv2.remap(img, sp[..., 0], sp[..., 1], cv2.INTER_LINEAR, borderValue=0)
def teacher(t160):
    f = t160.astype(np.float32) / 255.0; sd = f.std() or 1.0; f *= 0.25 / sd; f += 0.5 - f.mean()
    return sess.run(None, {'inputImg': f.reshape(1, 1, 160, 160)})
# ---- sources
def load(rec, csvp, lab):
    mm = np.memmap('C:/uvio/hands_bench/%s.pairs' % rec, dtype=np.uint8, mode='r'); n = (len(mm) - 12) // REC
    rows = [r for r in csv.reader(open(csvp)) if r[0] != 't']; labs = {int(r[0]): (r[1].strip(), r[2].strip()) for r in csv.reader(open(lab)) if r and r[0].strip().isdigit()}
    return mm, n, rows, labs
def img_of(mm, i): b = 12 + i * REC + 8; return np.array(mm[b:b + W * H]).reshape(H, W)
POS = []; NEG = []
for rec, csvp, lab in (('full17', 'C:/uvio/handup/cont/base_full17.csv', 'C:/uvio/hands_bench/r17/labels.csv'), ('full20', 'C:/uvio/handup/cont/base_full20.csv', 'C:/uvio/hands_bench/r20/labels.csv'), ('full22', 'C:/uvio/handup/cont/base_full22.csv', 'C:/uvio/hands_bench/r22/labels.csv')):
    mm, n, rows, labs = load(rec, csvp, lab)
    for i, r in enumerate(rows[:n]):
        mask = int(r[1]); s = int(float(r[0]))
        if mask in (1, 2) and i % 1 == 0:
            c = 3 if mask == 1 else 7
            if r[c] == '': continue
            x, y, z = float(r[c]), float(r[c + 1]), float(r[c + 2])
            if not (0.18 < -z < 0.6): continue
            POS.append((rec, i, 0 if mask == 1 else 1, np.array([x, -y, -z]) / np.linalg.norm([x, y, z])))
        if s in labs and labs[s] == ('', '') and i % 4 == 0: NEG.append((rec, i))
SRC = {}
def src(rec, i):
    key = (rec, i)
    if key not in SRC:
        mm = MM[rec]; SRC[key] = img_of(mm, i)
    return SRC[key]
MM = {r: np.memmap('C:/uvio/hands_bench/%s.pairs' % r, dtype=np.uint8, mode='r') for r in ('full17', 'full20', 'full22')}
# teacher labels on hand-centred tiles
random.shuffle(POS); GOOD = []
for rec, i, slot, dh in POS:
    if len(GOOD) >= 1400: break
    im = src(rec, i); Rc = M_of(dh); t = render(im, Rc, np.eye(3)); o = teacher(t)
    ex, cx, cy, sz = float(o[0][0, slot]), (float(o[1][0, slot]) + 1) * 80, (float(o[2][0, slot]) + 1) * 80, float(o[3][0, slot])
    if ex > 0.9 and abs(cx - 80) < 25 and abs(cy - 80) < 25:
        d2 = Rc @ (np.array([(cx - 80) / F, (cy - 80) / F, 1.0]) / np.linalg.norm([(cx - 80) / F, (cy - 80) / F, 1.0]))
        GOOD.append((rec, i, slot, d2, sz))
print('positive sources', len(GOOD), 'of', len(POS), '| negative pictures', len(NEG), flush=True)
# ---- model
t = torch.load('C:/uvio/mp/merc_det_torch.pt', weights_only=False); t.train()
opt = torch.optim.AdamW(t.parameters(), lr=3e-5, weight_decay=1e-4)
def prep(im):
    f = im.astype(np.float32) / 255.0; sd = f.std() or 1.0; f *= 0.25 / sd; f += 0.5 - f.mean(); return f
def augment(im):
    im = im.astype(np.float32)
    g = np.exp(np.random.uniform(-0.5, 0.5)); im = 255 * (im / 255.0) ** g
    im = im * np.random.uniform(0.5, 1.5) + np.random.uniform(-15, 15)
    if random.random() < 0.4: kk = random.choice([3, 5, 7]); ker = np.zeros((kk, kk), np.float32); ker[kk // 2, :] = 1 / kk; M = cv2.getRotationMatrix2D((kk / 2 - .5, kk / 2 - .5), random.uniform(0, 180), 1); im = cv2.filter2D(im, -1, cv2.warpAffine(ker, M, (kk, kk)) / max(1e-6, cv2.warpAffine(ker, M, (kk, kk)).sum()))
    im = im + np.random.normal(0, random.uniform(0, 6), im.shape)
    return np.clip(im, 0, 255).astype(np.uint8)
def sample_pos():
    rec, i, slot, dh, sz = random.choice(GOOD); im = src(rec, i)
    for _ in range(20):
        yaw = random.uniform(-55, 55); pitch = random.uniform(-30, 70)
        if random.random() < 0.5: pitch = random.uniform(15, 70)
        RT = M_yp(yaw, pitch)
        px = random.uniform(-10, 170) if random.random() < 0.35 else random.uniform(25, 135); py = random.uniform(-10, 170) if random.random() < 0.35 else random.uniform(25, 135)
        r = np.array([(px - 80) / F, (py - 80) / F, 1.0]); r /= np.linalg.norm(r); dt = RT @ r
        if dt[2] < 0.35: continue
        Q = M_of(dh) @ M_of(dt).T; tile = render(im, RT, Q)
        return augment(tile), slot, px, py, sz
    return None
def sample_far():
    rec, i, slot, dh, sz = random.choice(GOOD); im = src(rec, i)
    for _ in range(30):
        yaw = random.uniform(-60, 60); pitch = random.uniform(-30, 75); RT = M_yp(yaw, pitch); ax = RT @ np.array([0, 0, 1.0])
        if np.degrees(np.arccos(np.clip(ax @ dh, -1, 1))) > 62: return augment(render(im, RT, np.eye(3)))
    return None
def sample_neg():
    if random.random() < 0.5:
        s = sample_far()
        if s is not None: return s
    rec, i = random.choice(NEG); im = src(rec, i)
    yaw = random.uniform(-60, 60); pitch = random.uniform(-30, 75); tile = render(im, M_yp(yaw, pitch), np.eye(3)); return augment(tile)
for step in range(steps):
    X = []; EX = []; CX = []; CY = []; SZ = []; MASK = []
    for b in range(32):
        if random.random() < 0.5:
            s = sample_pos()
            if s is None: s = (sample_neg(), None, 0, 0, 0)
        else: s = (sample_neg(), None, 0, 0, 0)
        tile, slot, px, py, sz = s; X.append(prep(tile)); e = [0.0, 0.0]; m = [0.0, 0.0]; cx = [0.0, 0.0]; cy = [0.0, 0.0]; z = [0.0, 0.0]
        if slot is not None: e[slot] = 1.0; m[slot] = 1.0; cx[slot] = px / 80 - 1; cy[slot] = py / 80 - 1; z[slot] = sz
        EX.append(e); MASK.append(m); CX.append(cx); CY.append(cy); SZ.append(z)
    x = torch.from_numpy(np.array(X)).unsqueeze(1); ex = torch.tensor(EX); mk = torch.tensor(MASK); cx = torch.tensor(CX); cy = torch.tensor(CY); sz = torch.tensor(SZ)
    o = t(x); pe = o[0].clamp(1e-4, 1 - 1e-4)
    # hand_exists is a probability in the original; use BCE with positives weighted
    loss_e = -(ex * torch.log(pe) * 1.0 + (1 - ex) * torch.log(1 - pe)).mean()
    loss_r = ((torch.abs(o[1] - cx) + torch.abs(o[2] - cy) + torch.abs(o[3] - sz)) * mk).sum() / mk.sum().clamp(min=1)
    loss = loss_e + 2.0 * loss_r; opt.zero_grad(); loss.backward(); opt.step()
    if step % 50 == 0: print(step, 'loss %.4f exists %.4f reg %.4f' % (loss.item(), loss_e.item(), loss_r.item()), flush=True)
    if step % 500 == 499: torch.save(t, outpt)
torch.save(t, outpt); print('saved', outpt)
