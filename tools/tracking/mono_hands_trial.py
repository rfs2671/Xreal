# Offline trial: does a single-camera hand tracker (MediaPipe Hand Landmarker) see hands in the glasses' pictures
# where the two-camera tracker (Mercury) does not? Private recordings in, numbers out.
#   python mono_hands_trial.py RECORDING.uxrec MERCURY-hands.csv [--every 3] [--save DIR]
import sys, os, csv, time
sys.path.insert(0, r"C:\Users\asddd\Downloads\Xreal-claude-pixel-xreal-xr-gaming-cguv7y\_latest\tools\tracking")
import numpy as np, cv2
import analyse_recording as ar
import mediapipe as mp
from mediapipe.tasks.python import vision, BaseOptions

a = sys.argv[1:]; every = 3; save = None
if "--every" in a: i = a.index("--every"); every = int(a[i + 1]); del a[i:i + 2]
if "--save" in a: i = a.index("--save"); save = a[i + 1]; del a[i:i + 2]; os.makedirs(save, exist_ok=True)
rec, mercury = a[0], a[1]

opts = vision.HandLandmarkerOptions(base_options=BaseOptions(model_asset_path=r"C:\uvio\models\hand_landmarker\hand_landmarker.task"),
                                    num_hands=2, min_hand_detection_confidence=0.3, min_hand_presence_confidence=0.3, min_tracking_confidence=0.3)
det = [vision.HandLandmarker.create_from_options(opts), vision.HandLandmarker.create_from_options(opts)]

clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
def look(eye, img):
    if os.environ.get('CLAHE'): img = clahe.apply(img)
    rgb = cv2.cvtColor(img, cv2.COLOR_GRAY2RGB)
    r = det[eye].detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb)))
    out = []
    for lm in r.hand_landmarks:
        xs = [p.x for p in lm]; ys = [p.y for p in lm]
        out.append((float(np.mean(xs)), float(np.mean(ys)), max(xs) - min(xs), max(ys) - min(ys)))
    return out

rows = []; first = None; pend = {}; k = 0; ms = []; saved = 0
for kind, t, data in ar.records(rec):
    if first is None: first = t
    if kind != 1: continue
    dev, counter, eye = ar.meta(data)
    if eye > 1: continue
    if eye == 0:
        k += 1
        if k % every == 0: pend[counter] = ar.decode(data)[0]
    elif counter in pend:
        left = pend.pop(counter); right = ar.decode(data)[0]; s = (t - first) / 1e9
        t0 = time.time(); hl = look(0, left); hr = look(1, right); ms.append((time.time() - t0) * 500)
        rows.append((s, len(hl), len(hr), hl, hr))
        if save and saved < 40 and (len(hl) + len(hr)) > 0 and len(rows) % 7 == 0:
            both = cv2.cvtColor(np.hstack([left, right]), cv2.COLOR_GRAY2BGR)
            for e, hs in ((0, hl), (1, hr)):
                for (cx, cy, w, h) in hs: cv2.rectangle(both, (int((cx - w / 2) * 480) + e * 480, int((cy - h / 2) * 640)), (int((cx + w / 2) * 480) + e * 480, int((cy + h / 2) * 640)), (0, 255, 0), 2)
            cv2.imwrite(os.path.join(save, "t%05.1f.png" % s), both); saved += 1

# Mercury's own results on the same recording (replayed on the phone): time, mask (bit 0 left hand, bit 1 right hand)
mt = []; mm = []
for r in csv.DictReader(open(mercury)):
    mt.append(float(r["t"])); mm.append(int(r["mask"]))
mt = np.array(mt); mm = np.array(mm)
end = rows[-1][0]
print("recording %.0f s, %d stereo pairs looked at (every %d), %.0f ms a picture on this laptop" % (end, len(rows), every, float(np.mean(ms))))
print("%-12s %8s | %-28s | %-22s" % ("seconds", "pairs", "single camera finds a hand in", "Mercury (both cameras)"))
step = 5.0
for w0 in np.arange(0, end, step):
    seg = [r for r in rows if w0 <= r[0] < w0 + step]
    if not seg: continue
    n = len(seg); l = sum(1 for r in seg if r[1] > 0); rr = sum(1 for r in seg if r[2] > 0); either = sum(1 for r in seg if r[1] + r[2] > 0); both = sum(1 for r in seg if r[1] > 0 and r[2] > 0)
    sel = (mt >= w0) & (mt < w0 + step); mn = int(sel.sum()); mf = int((mm[sel] > 0).sum()) if mn else 0
    print("%4.0f to %-4.0f %8d | left %3d%% right %3d%% either %3d%% both %3d%% | %3d%% of %d results" % (w0, w0 + step, n, 100 * l / n, 100 * rr / n, 100 * either / n, 100 * both / n, (100 * mf / mn) if mn else 0, mn))
n = len(rows); either = sum(1 for r in rows if r[1] + r[2] > 0); both = sum(1 for r in rows if r[1] > 0 and r[2] > 0)
print("whole recording: a hand in either camera %d%% of pairs, in both %d%%; Mercury any hand %d%% of its results" % (100 * either / n, 100 * both / n, 100 * (mm > 0).sum() / len(mm)))
