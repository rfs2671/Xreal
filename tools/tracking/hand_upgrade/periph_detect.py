"""Does the whole-picture detector find a hand wherever it is in the picture? A real hand, seen well in the middle of the left camera's picture,
is moved by rotating the camera's view (exactly what the lens would show if the hand were in that direction and seen face on) to a grid of
places over the whole 480 x 640 picture, and the detector is run on each, as Mercury runs it (the picture letterboxed into 160 x 160, normalised
to mean 0.5 and spread 0.25), and also on a 70 degree pinhole tile centred on that place, as the tiles do.
usage: python periph_detect.py PAIRS RUN.csv CALIB.json OUT.csv [frames=40]"""
import sys, json, csv, numpy as np, cv2, onnxruntime as ort, os
pairs, runcsv, calib, out = sys.argv[1:5]; NFR = int(sys.argv[5]) if len(sys.argv) > 5 else 40
MODEL = r"C:\Users\asddd\Downloads\Xreal-claude-pixel-xreal-xr-gaming-cguv7y\_latest\app\src\main\assets\hand-models\grayscale_detection_160x160.onnx"
W, H = 480, 640
cal = json.load(open(calib))['value0']; k = cal['intrinsics'][0]['intrinsics']
K = np.array([[k['fx'], 0, k['cx']], [0, k['fy'], k['cy']], [0, 0, 1]], np.float64); D = np.array([k['k1'], k['k2'], k['k3'], k['k4']], np.float64)
so = ort.SessionOptions(); so.log_severity_level = 3; sess = ort.InferenceSession(MODEL, so, providers=["CPUExecutionProvider"])

def detect(img160):
    """img160: uint8 160x160. returns list of (score, cx_px, cy_px, size_px) for the two outputs, in the 160x160 picture."""
    f = img160.astype(np.float32) / 255.0; sd = f.std() or 1.0; f *= 0.25 / sd; f += 0.5 - f.mean()
    o = sess.run(None, {"inputImg": f.reshape(1, 1, 160, 160)})
    return [(float(o[0][0, h]), (float(o[1][0, h]) + 1) * 80, (float(o[2][0, h]) + 1) * 80, float(o[3][0, h]) * 160 * 2) for h in (0, 1)]

def letterbox(img):          # orientation 0: scale 0.25, 20 px bar left and right, as Mercury's blackbar
    M = np.array([[0.25, 0, 20], [0, 0.25, 0]], np.float32)
    return cv2.warpAffine(img, M, (160, 160))

def to_source(px, py):       # back from the 160 x 160 picture to the 480 x 640 one
    return (px - 20) / 0.25, py / 0.25

# the grid of rays of the output picture
us, vs = np.meshgrid(np.arange(W, dtype=np.float64), np.arange(H, dtype=np.float64))
pts = np.stack([us.ravel(), vs.ravel()], 1).reshape(-1, 1, 2)
norm = cv2.fisheye.undistortPoints(pts, K, D).reshape(-1, 2)
rays = np.concatenate([norm, np.ones((len(norm), 1))], 1); rays /= np.linalg.norm(rays, axis=1, keepdims=True)

def project(d):               # rays (n,3) -> pixels (n,2); rays behind the camera give nan
    good = d[:, 2] > 0.02
    out_ = np.full((len(d), 2), np.nan)
    if good.any():
        p, _ = cv2.fisheye.projectPoints(d[good].reshape(-1, 1, 3), np.zeros(3), np.zeros(3), K, D); out_[good] = p.reshape(-1, 2)
    return out_

def M_of(d):
    """the level frame of direction d (x right, y down, z forward): yaw about y then pitch about x, no roll about the line of sight"""
    az = np.arctan2(d[0], d[2]); el = np.arctan2(d[1], np.hypot(d[0], d[2]))
    Ry = np.array([[np.cos(az), 0, np.sin(az)], [0, 1, 0], [-np.sin(az), 0, np.cos(az)]])
    Rx = np.array([[1, 0, 0], [0, np.cos(-el), -np.sin(-el)], [0, np.sin(-el), np.cos(-el)]])
    return Ry @ Rx

def rot_between(a, b):
    return M_of(b) @ M_of(a).T          # keeps the hand upright in the world: it is only carried to the other direction

def rot_between_minimal(a, b):
    v = np.cross(a, b); c = a @ b; s = np.linalg.norm(v)
    if s < 1e-9: return np.eye(3)
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]]); return np.eye(3) + vx + vx @ vx * ((1 - c) / s**2)

def ray_of(px, py):
    n = cv2.fisheye.undistortPoints(np.array([[[px, py]]], np.float64), K, D).reshape(2); r = np.array([n[0], n[1], 1.0]); return r / np.linalg.norm(r)

# source frames: both hands tracked, palm near the middle of the left picture
rows = [r for r in csv.reader(open(runcsv)) if len(r) > 8 and r[0] != 't']
cands = []
for i, r in enumerate(rows):
    m = int(r[1])
    for hand, (cx_, cy_, cz_) in (("L", (3, 4, 5)), ("R", (7, 8, 9))):
        if r[cx_] == '': continue
        x, y, z = float(r[cx_]), float(r[cy_]), float(r[cz_])          # OpenXR-style camera frame: x right, y up, -z forward
        if not (0.18 < -z < 0.50): continue
        d = np.array([x, -y, -z]); d /= np.linalg.norm(d); p = project(d.reshape(1, 3))[0]
        if np.isnan(p).any() or not (60 < p[0] < 420 and 120 < p[1] < 560): continue
        cands.append((i, hand, p, -z, float(np.hypot(p[0] - k['cx'], p[1] - k['cy']))))
cands.sort(key=lambda c: c[4])          # the ones nearest the middle of the picture first: the lens samples them best
mm = np.memmap(pairs, dtype=np.uint8, mode='r', offset=12)
REC = 8 + 2 * W * H
def left_image(i):
    base = i * REC + 8; return np.array(mm[base:base + W * H]).reshape(H, W)
sel = []; tried = 0
for c in cands:
    if len(sel) >= NFR: break
    if not all(abs(c[0] - s[0]) >= 12 for s in sel): continue
    tried += 1
    # only a hand that the whole-picture detector finds where it really is can say how well it finds it elsewhere
    ok = any(sc > 0.7 and np.hypot(to_source(cx_, cy_)[0] - c[2][0], to_source(cx_, cy_)[1] - c[2][1]) < 60 for sc, cx_, cy_, sz in detect(letterbox(left_image(c[0]))))
    if ok: sel.append(c[:4])
print("source frames:", len(sel), "of", tried, "tried (the detector finds the hand where it is); their pixel places:", [(int(c[2][0]), int(c[2][1])) for c in sel][:8], flush=True)

targets = [(u, v) for v in range(40, 620, 60) for u in range(30, 460, 50)]
res = []
for (i, hand, p, zdist) in sel:
    img = left_image(i); dh = ray_of(*p)
    # sanity: does the detector find the hand where it really is?
    s0 = detect(letterbox(img));
    for (tu, tv) in targets:
        dt = ray_of(tu, tv); Rm = rot_between(dh, dt)          # takes the hand's ray to the target's
        src = project(rays @ Rm)                                 # output ray d' came from source ray Rm^T d'... (rays @ Rm = (Rm^T rays^T)^T)
        mapx = src[:, 0].reshape(H, W).astype(np.float32); mapy = src[:, 1].reshape(H, W).astype(np.float32)
        syn = cv2.remap(img, mapx, mapy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
        # whole picture
        dets = detect(letterbox(syn)); ok_whole = 0; best = 0.0
        for sc, cx_, cy_, sz in dets:
            ux, uy = to_source(cx_, cy_)
            if np.hypot(ux - tu, uy - tv) < 45: best = max(best, sc)
        # tile: a 70 degree pinhole view centred on the target direction, 160 x 160
        f_t = 80.0 / np.tan(np.radians(35.0)); Kt = np.array([[f_t, 0, 80], [0, f_t, 80], [0, 0, 1]])
        az = np.arctan2(dt[0], dt[2]); el = np.arcsin(dt[1])
        Rt = M_of(dt)
        gu, gv = np.meshgrid(np.arange(160, dtype=np.float64), np.arange(160, dtype=np.float64))
        rr = np.stack([(gu.ravel() - 80) / f_t, (gv.ravel() - 80) / f_t, np.ones(160 * 160)], 1); rr /= np.linalg.norm(rr, axis=1, keepdims=True)
        sp = project(rr @ Rt.T)          # tile ray in the picture's frame
        # the source image is the *unrotated* one: rotate the tile ray back to where the hand really is
        sp2 = project((rr @ Rt.T) @ Rm)
        # as the real tiles are cut: out of the picture as the lens gives it at that place (the synthetic picture), so the lens' own sampling there is in it
        tile = cv2.remap(syn, sp[:, 0].reshape(160, 160).astype(np.float32), sp[:, 1].reshape(160, 160).astype(np.float32), cv2.INTER_LINEAR, borderValue=0)
        tbest = 0.0
        for sc, cx_, cy_, sz in detect(tile):
            if np.hypot(cx_ - 80, cy_ - 80) < 28: tbest = max(tbest, sc)
        # hand size in the picture at the target (pixels across 12 cm at this distance)
        a = ray_of(tu - 1, tv); b = ray_of(tu + 1, tv); ang = np.arccos(np.clip(a @ b, -1, 1)) / 2.0           # radians per pixel
        size_px = 0.12 / zdist / ang if ang > 0 else 0
        res.append((i, hand, tu, tv, best, tbest, size_px, float(np.degrees(np.arccos(np.clip(dt[2], -1, 1))))))
    print("frame", i, hand, "done; detector at the real place:", [round(d[0], 2) for d in s0], flush=True)
with open(out, 'w', newline='') as f:
    w = csv.writer(f); w.writerow(["frame", "hand", "u", "v", "whole_score", "tile_score", "hand_px", "off_axis_deg"]); w.writerows(res)
a = np.array([(r[2], r[3], r[4] > 0.3, r[5] > 0.5, r[6], r[7]) for r in res], float)
print("\nrows = v of the hand centre in the 480x640 picture, columns = u; each cell: whole-picture detection %% / tile detection %%  (n frames per cell = %d)" % len(sel))
us_ = sorted(set(int(t[0]) for t in targets)); vs_ = sorted(set(int(t[1]) for t in targets))
print("      " + "".join("%10d" % u for u in us_))
for v in vs_:
    line = "%5d " % v
    for u in us_:
        m = (a[:, 0] == u) & (a[:, 1] == v); line += "%5.0f/%-4.0f" % (100 * a[m, 2].mean(), 100 * a[m, 3].mean())
    print(line)
