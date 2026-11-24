"""Which layout of detection tiles finds a hand wherever it is in the left camera's picture? A bank of tiles (yaw, pitch, field of view) is run on real hands
carried over the picture (see periph_detect.py); for each place, which tiles find the hand. Then fixed layouts are compared by the share of places (weighted towards chest, belly and lap) that at least one of their tiles covers.
usage: python tile_layout_eval.py PAIRS RUN.csv CALIB.json OUT.npz [frames=10]"""
import sys, json, csv, numpy as np, cv2, onnxruntime as ort, itertools
pairs, runcsv, calib, out = sys.argv[1:5]; NFR = int(sys.argv[5]) if len(sys.argv) > 5 else 10
MODEL = r"C:\Users\asddd\Downloads\Xreal-claude-pixel-xreal-xr-gaming-cguv7y\_latest\app\src\main\assets\hand-models\grayscale_detection_160x160.onnx"
W, H = 480, 640
cal = json.load(open(calib))['value0']; k = cal['intrinsics'][0]['intrinsics']
K = np.array([[k['fx'], 0, k['cx']], [0, k['fy'], k['cy']], [0, 0, 1]], np.float64); D = np.array([k['k1'], k['k2'], k['k3'], k['k4']], np.float64)
so = ort.SessionOptions(); so.log_severity_level = 3; sess = ort.InferenceSession(MODEL, so, providers=["CPUExecutionProvider"])
def detect(img160):
    f = img160.astype(np.float32) / 255.0; sd = f.std() or 1.0; f *= 0.25 / sd; f += 0.5 - f.mean()
    o = sess.run(None, {"inputImg": f.reshape(1, 1, 160, 160)})
    return [(float(o[0][0, h]), (float(o[1][0, h]) + 1) * 80, (float(o[2][0, h]) + 1) * 80) for h in (0, 1)]
def letterbox(img): return cv2.warpAffine(img, np.array([[0.25, 0, 20], [0, 0.25, 0]], np.float32), (160, 160))
def project(d):
    good = d[:, 2] > 0.02; o = np.full((len(d), 2), np.nan)
    if good.any(): p, _ = cv2.fisheye.projectPoints(d[good].reshape(-1, 1, 3), np.zeros(3), np.zeros(3), K, D); o[good] = p.reshape(-1, 2)
    return o
def ray_of(px, py):
    n = cv2.fisheye.undistortPoints(np.array([[[px, py]]], np.float64), K, D).reshape(2); r = np.array([n[0], n[1], 1.0]); return r / np.linalg.norm(r)
def M_of(d):
    az = np.arctan2(d[0], d[2]); el = np.arctan2(d[1], np.hypot(d[0], d[2]))
    Ry = np.array([[np.cos(az), 0, np.sin(az)], [0, 1, 0], [-np.sin(az), 0, np.cos(az)]]); Rx = np.array([[1, 0, 0], [0, np.cos(-el), -np.sin(-el)], [0, np.sin(-el), np.cos(-el)]]); return Ry @ Rx
def M_yp(yaw, pitch):       # Mercury's tile: R = Ry(yaw) Rx(pitch)^T
    a, b = np.radians(yaw), np.radians(pitch); ca, sa, cb, sb = np.cos(a), np.sin(a), np.cos(b), np.sin(b)
    return np.array([[ca, -sa * sb, sa * cb], [0, cb, sb], [-sa, -ca * sb, ca * cb]])
us, vs = np.meshgrid(np.arange(W, dtype=np.float64), np.arange(H, dtype=np.float64))
rays = cv2.fisheye.undistortPoints(np.stack([us.ravel(), vs.ravel()], 1).reshape(-1, 1, 2), K, D).reshape(-1, 2); rays = np.concatenate([rays, np.ones((len(rays), 1))], 1); rays /= np.linalg.norm(rays, axis=1, keepdims=True)
gu, gv = np.meshgrid(np.arange(160, dtype=np.float64) + 0.5, np.arange(160, dtype=np.float64) + 0.5)
def tile_rays(fov):
    f = 80.0 / np.tan(np.radians(fov / 2)); r = np.stack([(gu.ravel() - 80) / f, (gv.ravel() - 80) / f, np.ones(160 * 160)], 1); return r / np.linalg.norm(r, axis=1, keepdims=True), f
BANK = [(yaw, pitch, fov) for fov in (50, 70) for yaw in (-72, -48, -24, 0, 24, 48, 72) for pitch in (-22, 0, 22, 40, 58, 75)]
TR = {fov: tile_rays(fov) for fov in (50, 70)}
rows = [r for r in csv.reader(open(runcsv)) if len(r) > 8 and r[0] != 't']
mm = np.memmap(pairs, dtype=np.uint8, mode='r', offset=12); REC = 8 + 2 * W * H
def left_image(i): base = i * REC + 8; return np.array(mm[base:base + W * H]).reshape(H, W)
cands = []
for i, r in enumerate(rows):
    for hand, (cx_, cy_, cz_) in (("L", (3, 4, 5)), ("R", (7, 8, 9))):
        if r[cx_] == '': continue
        x, y, z = float(r[cx_]), float(r[cy_]), float(r[cz_])
        if not (0.18 < -z < 0.50): continue
        d = np.array([x, -y, -z]); d /= np.linalg.norm(d); p = project(d.reshape(1, 3))[0]
        if np.isnan(p).any() or not (60 < p[0] < 420 and 120 < p[1] < 560): continue
        cands.append((i, hand, p, -z, float(np.hypot(p[0] - k['cx'], p[1] - k['cy']))))
cands.sort(key=lambda c: c[4]); sel = []
for c in cands:
    if len(sel) >= NFR: break
    if not all(abs(c[0] - s[0]) >= 12 for s in sel):
        continue
    ok = any(sc > 0.7 and np.hypot((cx_ - 20) / 0.25 - c[2][0], cy_ / 0.25 - c[2][1]) < 60 for sc, cx_, cy_ in detect(letterbox(left_image(c[0]))))
    if ok: sel.append(c)
print("source hands:", len(sel), flush=True)
targets = [(u, v) for v in (60, 150, 240, 330, 420, 490, 550, 600) for u in (40, 130, 230, 330, 430)]
hit = np.zeros((len(sel), len(targets), len(BANK)), bool); whole = np.zeros((len(sel), len(targets)), bool)
for fi, (i, hand, p, zdist, _) in enumerate(sel):
    img = left_image(i); dh = ray_of(*p); Mh = M_of(dh)
    for ti, (tu, tv) in enumerate(targets):
        dt = ray_of(tu, tv); Rm = M_of(dt) @ Mh.T
        src = project(rays @ Rm); syn = cv2.remap(img, src[:, 0].reshape(H, W).astype(np.float32), src[:, 1].reshape(H, W).astype(np.float32), cv2.INTER_LINEAR, borderValue=0)
        for sc, cx_, cy_ in detect(letterbox(syn)):
            if sc > 0.3 and np.hypot((cx_ - 20) / 0.25 - tu, cy_ / 0.25 - tv) < 45: whole[fi, ti] = True
        for bi, (yaw, pitch, fov) in enumerate(BANK):
            Rt = M_yp(yaw, pitch); rr, f = TR[fov]
            sp = project(rr @ Rt.T)
            if np.isnan(sp).mean() > 0.45: continue
            tile = cv2.remap(syn, sp[:, 0].reshape(160, 160).astype(np.float32), sp[:, 1].reshape(160, 160).astype(np.float32), cv2.INTER_LINEAR, borderValue=0)
            # where the target would be in this tile
            tp = project((rays[(int(tv) * W + int(tu))] @ Rt).reshape(1, 3))   # not used for the test below; the test is the detector's own centre mapped back
            for sc, cx_, cy_ in detect(tile):
                if sc < 0.5: continue
                x = (cx_ - 80) / f; y = (cy_ - 80) / f; dd = Rt @ np.array([x, y, 1.0]); dd /= np.linalg.norm(dd); q = project(dd.reshape(1, 3))[0]
                if not np.isnan(q).any() and np.hypot(q[0] - tu, q[1] - tv) < 45: hit[fi, ti, bi] = True
    print("hand", fi, "done", flush=True)
np.savez(out, hit=hit, whole=whole, targets=np.array(targets), bank=np.array(BANK))
cov = hit.mean(0)                  # targets x bank
w = np.array([1.0 + 1.0 * (v >= 330) + 1.0 * (v >= 490) for (u, v) in targets]); w /= w.sum()      # lower places count more
def score(idx): return float((w * hit[:, :, idx].any(2).mean(0)).sum()) if len(idx) else 0.0
cur = [BANK.index(t) for t in [(yaw, pitch, 70) for pitch in (-22, 22, 58) for yaw in (-48, 0, 48)]]
print("\nthe whole-picture detector alone: weighted coverage %.2f" % float((w * whole.mean(0)).sum()))
print("current 9 tiles (70 degrees, yaw -48 0 48, pitch -22 22 58): weighted coverage %.2f; any-tile coverage per place (u across, v down):" % score(cur))
def show(idx):
    a = hit[:, :, idx].any(2).mean(0); us_ = sorted(set(t[0] for t in targets)); vs_ = sorted(set(t[1] for t in targets))
    print("        " + "".join("%6d" % u for u in us_))
    for v in vs_: print("  v=%4d " % v + "".join("%6.0f" % (100 * a[targets.index((u, v))]) for u in us_))
show(cur)
# greedy sets
for K_ in (4, 6, 9, 12):
    chosen = []
    for _ in range(K_):
        best = max((b for b in range(len(BANK)) if b not in chosen), key=lambda b: score(chosen + [b])); chosen.append(best)
    print("\ngreedy %d tiles: weighted coverage %.2f: %s" % (K_, score(chosen), [BANK[b] for b in chosen]));
    if K_ in (6, 9): show(chosen)
