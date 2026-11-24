"""Offline detector comparison on labelled seconds. usage: harness.py REC(full17|full20)  -- Mercury detector vs MediaPipe palm+landmark (whole picture and rectified tiles), both cameras."""
import sys, csv, json, struct, numpy as np, cv2, onnxruntime as ort, mediapipe as mp
from mediapipe.tasks import python as mpp
from mediapipe.tasks.python import vision
rec = sys.argv[1]; NORM = sys.argv[2] if len(sys.argv) > 2 else 'none'; lab_path = {'full17': 'C:/uvio/hands_bench/r17/labels.csv', 'full20': 'C:/uvio/hands_bench/r20/labels.csv', 'full22': 'C:/uvio/hands_bench/r22/labels.csv', 'full23': 'C:/uvio/hands_bench/r23/labels.csv'}[rec]
W, H = 480, 640; REC = 8 + 2 * W * H
CAL = 'C:/Users/asddd/Downloads/Xreal-claude-pixel-xreal-xr-gaming-cguv7y/_latest/app/build/intermediates/assets/debug/mergeDebugAssets/calibration/basalt_calib.json'
cal = json.load(open(CAL))['value0']
def cam(i):
    k = cal['intrinsics'][i]['intrinsics']; return np.array([[k['fx'], 0, k['cx']], [0, k['fy'], k['cy']], [0, 0, 1]]), np.array([k['k1'], k['k2'], k['k3'], k['k4']])
CAMS = [cam(0), cam(1)]
mm = np.memmap('C:/uvio/hands_bench/%s.pairs' % rec, dtype=np.uint8, mode='r'); n = (len(mm) - 12) // REC
ts = np.array([struct.unpack('<q', bytes(mm[12 + i * REC:12 + i * REC + 8]))[0] for i in range(n)]) / 1e9
def image(i, v): b = 12 + i * REC + 8 + v * W * H; return np.array(mm[b:b + W * H]).reshape(H, W)
lab = {int(r[0]): (r[1].strip(), r[2].strip()) for r in csv.reader(open(lab_path)) if r and r[0].strip().isdigit()}
# Mercury's detector
sess = ort.InferenceSession('C:/Users/asddd/Downloads/Xreal-claude-pixel-xreal-xr-gaming-cguv7y/_latest/app/src/main/assets/hand-models/grayscale_detection_160x160.onnx', providers=['CPUExecutionProvider'])
def mdet(img160):
    f = img160.astype(np.float32) / 255.0; sd = f.std() or 1.0; f *= 0.25 / sd; f += 0.5 - f.mean()
    o = sess.run(None, {'inputImg': f.reshape(1, 1, 160, 160)}); return [(float(o[0][0, h]), (float(o[1][0, h]) + 1) * 80, (float(o[2][0, h]) + 1) * 80) for h in (0, 1)]
def merc_whole(img):
    lb = cv2.warpAffine(img, np.array([[0.25, 0, 20], [0, 0.25, 0]], np.float32), (160, 160)); return [((cx - 20) / 0.25, cy / 0.25, s) for s, cx, cy in mdet(lb)]
def M_yp(yaw, pitch):
    a, b = np.radians(yaw), np.radians(pitch); ca, sa, cb, sb = np.cos(a), np.sin(a), np.cos(b), np.sin(b)
    return np.array([[ca, -sa * sb, sa * cb], [0, cb, sb], [-sa, -ca * sb, ca * cb]])
SYM9 = [(-24, 40, 70), (24, 40, 70), (-24, 58, 70), (24, 58, 70), (-24, -22, 70), (24, -22, 70), (-48, 40, 70), (48, 40, 70), (0, 22, 70)]
def tile_map(v, yaw, pitch, fov, size):
    K, D = CAMS[v]; f = (size / 2) / np.tan(np.radians(fov / 2)); gu, gv = np.meshgrid(np.arange(size) + 0.5, np.arange(size) + 0.5)
    r = np.stack([(gu.ravel() - size / 2) / f, (gv.ravel() - size / 2) / f, np.ones(size * size)], 1); r /= np.linalg.norm(r, axis=1, keepdims=True)
    d = r @ M_yp(yaw, pitch).T; good = d[:, 2] > 0.05; out = np.full((size * size, 2), -1.0)
    if good.any(): p, _ = cv2.fisheye.projectPoints(d[good].reshape(-1, 1, 3), np.zeros(3), np.zeros(3), K, D); out[good] = p.reshape(-1, 2)
    return out.astype(np.float32).reshape(size, size, 2), f
def tile_back(v, yaw, pitch, f, size, x, y):
    K, D = CAMS[v]; d = M_yp(yaw, pitch) @ np.array([(x - size / 2) / f, (y - size / 2) / f, 1.0]); d /= np.linalg.norm(d)
    p, _ = cv2.fisheye.projectPoints(d.reshape(1, 1, 3), np.zeros(3), np.zeros(3), K, D); return p.reshape(2)
TM = {(v, t): tile_map(v, *t, 256) for v in (0, 1) for t in SYM9}
def merc_tiles(v, img):
    out = []
    for t in SYM9:
        m, f = TM[(v, t)]; tile = cv2.remap(img, m[..., 0], m[..., 1], cv2.INTER_LINEAR, borderValue=0); t160 = cv2.resize(tile, (160, 160), interpolation=cv2.INTER_AREA)
        for s, cx, cy in mdet(t160):
            if s > 0.3: q = tile_back(v, *t[:2], f, 160 * 256 / 160, cx * 256 / 160, cy * 256 / 160); out.append((q[0], q[1], s))
    return out
def mk(conf):
    o = vision.HandLandmarkerOptions(base_options=mpp.BaseOptions(model_asset_path='C:/uvio/mp/hand_landmarker.task'), num_hands=2, min_hand_detection_confidence=conf, min_hand_presence_confidence=conf, min_tracking_confidence=conf, running_mode=vision.RunningMode.IMAGE)
    return vision.HandLandmarker.create_from_options(o)
MP = {c: mk(c) for c in (0.1, 0.2, 0.3)}
def mp_img(det, img):
    rgb = np.ascontiguousarray(np.stack([img] * 3, -1)); r = det.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)); return r.hand_landmarks
def mp_whole(det, img):
    out = []
    for lm in mp_img(det, img): out.append((np.mean([p.x for p in lm]) * W, np.mean([p.y for p in lm]) * H, 1.0))
    return out
def mp_tiles(det, v, img):
    out = []
    for t in SYM9:
        m, f = TM[(v, t)]; tile = cv2.remap(img, m[..., 0], m[..., 1], cv2.INTER_LINEAR, borderValue=0)
        for lm in mp_img(det, tile):
            x = np.mean([p.x for p in lm]) * 256; y = np.mean([p.y for p in lm]) * 256; q = tile_back(v, *t[:2], f, 256, x, y); out.append((q[0], q[1], 1.0))
    return out
def cluster(dets, rad=70):
    cs = []
    for x, y, s in dets:
        for c in cs:
            if np.hypot(c[0] - x, c[1] - y) < rad: break
        else: cs.append((x, y, s))
    return cs
methods = {'merc_whole': lambda v, im: merc_whole(im), 'merc_tiles9': lambda v, im: cluster([d for d in merc_whole(im)] + merc_tiles(v, im))}
for c in MP:
    methods['mp_whole_%.1f' % c] = (lambda c: lambda v, im: mp_whole(MP[c], im))(c)
    methods['mp_tiles9_%.1f' % c] = (lambda c: lambda v, im: cluster(mp_whole(MP[c], im) + mp_tiles(MP[c], v, im)))(c)
thr = {'merc_whole': 0.3, 'merc_tiles9': 0.5}
stat = {m: {'plain_n': 0, 'plain_found': 0, 'any_n': 0, 'any_found': 0, 'none_n': 0, 'none_fp': 0, 'ndet_none': 0} for m in methods}
secs = sorted(lab)
for s in secs:
    for off in (0.25, 0.75):
        i = int(np.argmin(np.abs(ts - (ts[0] + s + off))))
        for v in (0, 1):
            l = lab[s][v]; exp = len(set(c.lower() for c in l)); plain = len(set(c for c in l if c.isupper()))
            im = image(i, v)
            if NORM == 'clahe': im = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8)).apply(im)
            elif NORM == 'stretch': im = cv2.normalize(im, None, 0, 255, cv2.NORM_MINMAX)
            for m, fn in methods.items():
                d = fn(v, im); d = [x for x in d if x[2] >= thr.get(m, 0)]; k = len(cluster(d)) if m.startswith('merc_whole') else len(d)
                st = stat[m]
                if exp == 0: st['none_n'] += 1; st['none_fp'] += 1 if k > 0 else 0; st['ndet_none'] += k
                else:
                    st['any_n'] += 1; st['any_found'] += 1 if k >= 1 else 0
                    if plain: st['plain_n'] += 1; st['plain_found'] += 1 if k >= 1 else 0
print(rec, NORM, 'labelled seconds', len(secs))
for m, st in stat.items():
    print('%-16s plainly-visible hand found %3d/%3d (%3.0f%%) | any visible %3d/%3d (%3.0f%%) | hand-free pictures with a detection %3d/%3d (%3.0f%%)' % (m, st['plain_found'], st['plain_n'], 100 * st['plain_found'] / max(1, st['plain_n']), st['any_found'], st['any_n'], 100 * st['any_found'] / max(1, st['any_n']), st['none_fp'], st['none_n'], 100 * st['none_fp'] / max(1, st['none_n'])))
