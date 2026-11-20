#!/usr/bin/env python3
"""Where does the hand detector find hands? Mercury's own detector as it is used now (each camera's whole fisheye
picture squeezed to 160x160) against the same detector run on fisheye-corrected tiles, on the same recorded frames.

  python hand_bench.py cache RECORDING.uxrec DIR [EVERY]     every EVERY-th stereo pair into DIR/pairs.npz (default 3)
  python hand_bench.py sheets DIR                            contact sheets DIR/sheet_NN.png, one pair a second, for labelling by eye
  python hand_bench.py run DIR [--labels labels.csv]         both detectors over DIR/pairs.npz; numbers, and DIR/detections.csv

Only the detection stage is measured here: whether a hand is found in a camera's picture at all. Mercury's keypoint
and 3D fitting stages are not run. Pictures and results stay in DIR, which must be outside the repository.

labels.csv (made by looking at the sheets): "second,left_cam,right_cam" where each cam is a string of the hands that are
plainly in that camera's picture: "", "L", "R" or "LR" (the wearer's left and right hand). A small letter ("l", "r") is a
hand that cannot be judged (at the very edge, half hidden, holding something): it is counted neither way.
"""
import csv, os, sys, time
import numpy as np, cv2

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "hand-models", "grayscale_detection_160x160.onnx")
CALIB = r"C:\uvio\rec\basalt_calib.json"
N = 160
MIN_CONF = 0.3      # Mercury's MERCURY_MIN_DETECTION_CONFIDENCE

_sess = None
def session():
    global _sess
    if _sess is None:
        import onnxruntime as ort
        o = ort.SessionOptions(); o.intra_op_num_threads = 2
        _sess = ort.InferenceSession(MODEL, o, providers=["CPUExecutionProvider"])
    return _sess

def normalise(img160):
    """Mercury's normalizeGrayscaleImage: standard deviation 0.25, mean 0.5."""
    f = img160.astype(np.float32) / 255.0; sd = float(f.std())
    if sd == 0: return None
    f = f * (0.25 / sd); return f + (0.5 - float(f.mean()))

def detect(img):
    """Mercury's detector on one picture of any size, letterboxed to 160x160 as Mercury does.
    Returns [(hand 0 left / 1 right, confidence, x, y, size)] in the picture's own pixels."""
    h, w = img.shape; k = min(N / w, N / h); iw, ih = w * k, h * k; tx, ty = (N - iw) / 2, (N - ih) / 2
    m = np.float32([[k, 0, tx], [0, k, ty]])
    small = cv2.warpAffine(img, m, (N, N), flags=cv2.INTER_AREA if k < 1 else cv2.INTER_LINEAR)
    f = normalise(small)
    if f is None: return []
    exists, cx, cy, size = session().run(["hand_exists", "cx", "cy", "size"], {"inputImg": f[None, None]})
    out = []
    for i in range(2):
        if exists[0, i] > MIN_CONF:
            px = (cx[0, i] + 1) / 2 * N; py = (cy[0, i] + 1) / 2 * N
            out.append((i, float(exists[0, i]), (px - tx) / k, (py - ty) / k, float(size[0, i]) * N * 2.0 / k))
    return out

# ---- the cameras: Kannala-Brandt (kb4), the upright 480x640 pictures; x right, y down, z forward
def cameras():
    import json
    c = json.load(open(CALIB))["value0"]["intrinsics"]
    return [c[0]["intrinsics"], c[1]["intrinsics"]]

def project(cam, d):
    """Rays d (..., 3) into pixels (..., 2) of a kb4 camera; NaN behind the camera."""
    x, y, z = d[..., 0], d[..., 1], d[..., 2]; rho = np.sqrt(x * x + y * y); th = np.arctan2(rho, z)
    t2 = th * th; r = th * (1 + cam["k1"] * t2 + cam["k2"] * t2 ** 2 + cam["k3"] * t2 ** 3 + cam["k4"] * t2 ** 4)
    s = np.where(rho > 1e-9, r / np.maximum(rho, 1e-9), 1.0)
    return np.stack([cam["fx"] * s * x + cam["cx"], cam["fy"] * s * y + cam["cy"]], -1)

def rot(yaw, pitch):
    """A view turned yaw to the right and pitch downwards (degrees) from the camera's own axis."""
    a, b = np.radians(yaw), np.radians(pitch)
    ry = np.array([[np.cos(a), 0, np.sin(a)], [0, 1, 0], [-np.sin(a), 0, np.cos(a)]]); rx = np.array([[1, 0, 0], [0, np.cos(b), -np.sin(b)], [0, np.sin(b), np.cos(b)]])
    return ry @ rx.T

class Tile:
    """An ordinary (pinhole) view cut out of a fisheye picture: [fov] degrees across, [size] pixels, aimed at ([yaw], [pitch])."""
    def __init__(self, cam, yaw, pitch, fov=70.0, size=N):
        self.cam, self.yaw, self.pitch, self.size = cam, yaw, pitch, size
        self.f = size / 2 / np.tan(np.radians(fov) / 2); self.R = rot(yaw, pitch)
        u, v = np.meshgrid(np.arange(size) + 0.5, np.arange(size) + 0.5)
        d = np.stack([(u - size / 2) / self.f, (v - size / 2) / self.f, np.ones_like(u)], -1) @ self.R.T
        p = project(cam, d).astype(np.float32); self.mx, self.my = p[..., 0], p[..., 1]
        inside = (self.mx >= 0) & (self.mx < 480) & (self.my >= 0) & (self.my < 640); self.cover = float(inside.mean())
    def cut(self, img): return cv2.remap(img, self.mx, self.my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    def back(self, x, y):
        """A place of the tile as a place of the fisheye picture."""
        d = self.R @ np.array([(x - self.size / 2) / self.f, (y - self.size / 2) / self.f, 1.0]); p = project(self.cam, d); return float(p[0]), float(p[1])

def tiles_for(cam):
    """Overlapping views over what the camera sees: a row ahead, a row below (chest), and the low corners."""
    out = []
    for pitch in (-22, 22, 58):
        for yaw in (-48, 0, 48):
            t = Tile(cam, yaw, pitch)
            if t.cover > 0.55: out.append(t)
    return out

def detect_tiled(img, tiles):
    """Every tile's detections, as places of the fisheye picture; of detections of one hand close together, the surest."""
    found = []
    for t in tiles:
        for (hand, conf, x, y, size) in detect(t.cut(img)):
            if not (0 <= x < t.size and 0 <= y < t.size): continue
            fx, fy = t.back(x, y)
            if 0 <= fx < 480 and 0 <= fy < 640: found.append((hand, conf, fx, fy, size * 240.0 / t.f, t.yaw, t.pitch))
    best = {}
    for d in found:
        if d[0] not in best or d[1] > best[d[0]][1]: best[d[0]] = d
    return list(best.values()), found

def load(d):
    z = np.load(os.path.join(d, "pairs.npz")); return z["t"], z["left"], z["right"]

def main():
    cmd = sys.argv[1]
    if cmd == "cache":
        import hand_frames
        rec, d = sys.argv[2], sys.argv[3]; every = int(sys.argv[4]) if len(sys.argv) > 4 else 3; os.makedirs(d, exist_ok=True)
        ts, ls, rs = [], [], []
        for s, l, r in hand_frames.pairs(rec, every): ts.append(s); ls.append(l); rs.append(r)
        np.savez_compressed(os.path.join(d, "pairs.npz"), t=np.array(ts), left=np.array(ls), right=np.array(rs)); print("%d pairs over %.1f s" % (len(ts), ts[-1]))
    elif cmd == "sheets":
        d = sys.argv[2]; t, L, R = load(d); sec = 0; cells = []
        for i in range(len(t)):
            if t[i] >= sec:
                both = cv2.resize(np.hstack([L[i], R[i]]), (480, 320)); cv2.putText(both, "%d s" % sec, (6, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.7, 255, 2); cells.append(both); sec += 1
        for k in range(0, len(cells), 6):
            row = cells[k:k + 6]; row += [np.zeros_like(cells[0])] * (6 - len(row))
            cv2.imwrite(os.path.join(d, "sheet_%02d.png" % (k // 6)), np.vstack([np.hstack(row[:3]), np.hstack(row[3:])]))
        print("%d seconds on %d sheets" % (len(cells), (len(cells) + 5) // 6))
    elif cmd == "run":
        d = sys.argv[2]; t, L, R = load(d); cams = cameras(); tiles = [tiles_for(cams[0]), tiles_for(cams[1])]
        print("tiles a camera: %d (yaw, pitch, share of the tile that is picture): %s" % (len(tiles[0]), ", ".join("%d/%d %.0f%%" % (x.yaw, x.pitch, 100 * x.cover) for x in tiles[0])))
        rows = []; t_full = t_tile = 0.0
        for i in range(len(t)):
            row = [float(t[i])]
            for e, img in ((0, L[i]), (1, R[i])):
                t0 = time.time(); full = detect(img); t1 = time.time(); best, _ = detect_tiled(img, tiles[e]); t2 = time.time(); t_full += t1 - t0; t_tile += t2 - t1
                fm = "".join("LR"[h[0]] for h in sorted(full)); tm = "".join("LR"[h[0]] for h in sorted(best))
                row += [fm, tm, ";".join("%s:%.2f:%.0f:%.0f" % ("LR"[h[0]], h[1], h[2], h[3]) for h in full), ";".join("%s:%.2f:%.0f:%.0f:%d/%d" % ("LR"[h[0]], h[1], h[2], h[3], h[5], h[6]) for h in best)]
            rows.append(row)
        with open(os.path.join(d, "detections.csv"), "w", newline="") as f:
            w = csv.writer(f); w.writerow(["t", "l_full", "l_tiled", "l_full_at", "l_tiled_at", "r_full", "r_tiled", "r_full_at", "r_tiled_at"]); w.writerows(rows)
        n = len(rows)
        print("%d pairs. On this laptop: whole picture %.1f ms a camera, %d tiles %.1f ms a camera" % (n, 1000 * t_full / n / 2, len(tiles[0]), 1000 * t_tile / n / 2))
        def share(col): return 100.0 * sum(1 for r in rows if r[col]) / n
        print("a hand found in the left camera's picture: whole %.0f%%, tiled %.0f%%; right camera: whole %.0f%%, tiled %.0f%%" % (share(1), share(2), share(5), share(6)))
        print("in both cameras at once (what the 3D fit needs): whole %.0f%%, tiled %.0f%%" % (100.0 * sum(1 for r in rows if r[1] and r[5]) / n, 100.0 * sum(1 for r in rows if r[2] and r[6]) / n))
        if "--labels" in sys.argv:
            lab = {}
            for r in csv.reader(open(sys.argv[sys.argv.index("--labels") + 1])):
                if r and r[0].strip().isdigit(): lab[int(r[0])] = (r[1].strip(), r[2].strip())
            # each labelled second is compared with the detections of the pairs in that second, hand by hand and camera by camera
            score = {"whole": [0, 0, 0, 0], "tiled": [0, 0, 0, 0]}      # present and found, present, absent and found, absent
            low = {"whole": [0, 0], "tiled": [0, 0]}
            for r in rows:
                s = int(r[0])
                if s not in lab: continue
                for e, (fc, tc) in enumerate(((1, 2), (5, 6))):
                    for hand in "LR":
                        if hand.lower() in lab[s][e]: continue      # cannot be judged
                        present = hand in lab[s][e]
                        for name, col in (("whole", fc), ("tiled", tc)):
                            got = hand in r[col]; sc = score[name]
                            if present: sc[1] += 1; sc[0] += got
                            else: sc[3] += 1; sc[2] += got
            both = {"whole": [0, 0], "tiled": [0, 0]}      # a hand plainly in both pictures: found in both, times
            for r in rows:
                s = int(r[0])
                if s not in lab: continue
                for hand in "LR":
                    if hand in lab[s][0] and hand in lab[s][1]:
                        for name, (a, b) in (("whole", (1, 5)), ("tiled", (2, 6))): both[name][1] += 1; both[name][0] += (hand in r[a] and hand in r[b])
            for name in ("whole", "tiled"): print("%-5s picture: a hand plainly in both cameras was found in both %d of %d times (%.0f%%)" % (name, both[name][0], both[name][1], 100.0 * both[name][0] / max(1, both[name][1])))
            for name in ("whole", "tiled"):
                sc = score[name]
                print("%-5s picture: of %d times a hand was in a camera's picture it was found %d (%.0f%%); found where there was none %d of %d (%.1f%%)" % (name, sc[1], sc[0], 100.0 * sc[0] / max(1, sc[1]), sc[2], sc[3], 100.0 * sc[2] / max(1, sc[3])))

if __name__ == "__main__": main()
