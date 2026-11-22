#!/usr/bin/env python3
"""What the cameras saw before position was lost (the sets the app writes to files/loss-pictures on the phone).

  python loss_pictures.py pull OUT_DIR [SERIAL]        copy the sets from the phone (they show the room: keep OUT_DIR out of the repository)
  python loss_pictures.py SET.uxloss [OUT_DIR]         one set: numbers for every pair, and contact sheets in OUT_DIR

A set is ten pairs a second for the four seconds up to 0.8 s after a loss was told (core/LossPictures), with the
estimator's states of the last twenty seconds beside it (SET.csv). For each pair this prints the pictures' brightness
and spread, how many corners the left picture has, how many of the corners of the pair before are found again in it
(pyramid flow there and back: a stand-in for the estimator's own following, which works the same way but on every
picture, not every third), and how many are found in the right picture. With the states' columns beside: what the
estimator itself counted at that moment.

Needs numpy and opencv-python.
"""
import csv, os, struct, subprocess, sys
import numpy as np, cv2

W, H = 480, 640; SIZE = W * H
FILES = "/sdcard/Android/data/dev.ultraxr/files/loss-pictures"

def read(path):
    with open(path, "rb") as f:
        if f.readline() != b"UXLOSS1\n": raise SystemExit("not a set of loss pictures")
        note = f.readline().decode("utf-8", "replace").strip(); pairs = []
        while True:
            h = f.read(8)
            if len(h) < 8: break
            l = f.read(SIZE); r = f.read(SIZE)
            if len(r) < SIZE: break
            pairs.append((struct.unpack(">q", h)[0], np.frombuffer(l, np.uint8).reshape(H, W), np.frombuffer(r, np.uint8).reshape(H, W)))
    return note, pairs

LK = dict(winSize=(21, 21), maxLevel=3, criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 20, 0.03))
def found_again(a, b, pts):
    if pts is None or len(pts) == 0: return 0
    p1, st, _ = cv2.calcOpticalFlowPyrLK(a, b, pts, None, **LK)
    p0, st2, _ = cv2.calcOpticalFlowPyrLK(b, a, p1, None, **LK)
    return int(((st.ravel() == 1) & (st2.ravel() == 1) & (np.linalg.norm((p0 - pts).reshape(-1, 2), axis=1) < 0.7)).sum())

def one(path, out):
    note, pairs = read(path)
    print("%s: %d pairs. %s" % (os.path.basename(path), len(pairs), note))
    states = []
    side = path[:-len(".uxloss")] + ".csv"
    if os.path.isfile(side):
        rows = [l for l in open(side) if not l.startswith("#")]
        states = [{k: float(v) for k, v in r.items()} for r in csv.DictReader(rows)]
    def state_at(t):
        if not states: return None
        s = min(states, key=lambda r: abs(r["t_ns"] - t))
        return s if abs(s["t_ns"] - t) < 60e6 else None
    t_end = pairs[-1][0]; prev = None; sheet = []
    for t, l, r in pairs:
        pts = cv2.goodFeaturesToTrack(l, 150, 0.01, 12)
        n = 0 if pts is None else len(pts)
        back = found_again(prev, l, cv2.goodFeaturesToTrack(prev, 150, 0.01, 12)) if prev is not None else -1
        # into the right picture: started where a point two metres away would be (the cameras are 10 degrees apart)
        both = -1
        if pts is not None:
            best = 0
            for dx in (0, 30, 60, 90):
                for sign in (1, -1):
                    init = pts + np.float32([sign * dx, 0])
                    p1, st, _ = cv2.calcOpticalFlowPyrLK(l, r, pts, init.copy(), flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **LK)
                    p0, st2, _ = cv2.calcOpticalFlowPyrLK(r, l, p1, pts.copy(), flags=cv2.OPTFLOW_USE_INITIAL_FLOW, **LK)
                    best = max(best, int(((st.ravel() == 1) & (st2.ravel() == 1) & (np.linalg.norm((p0 - pts).reshape(-1, 2), axis=1) < 0.7)).sum()))
            both = best
        s = state_at(t)
        est = "" if s is None else "  | estimator: %3d corners, %3d landmarks, %3d by both, %4.1f ms, %.2f m/s, turning %.0f deg/s" % (
            s["features"], s.get("landmarks", -1), s.get("stereo", -1), s["process_ms"], (s["vx"] ** 2 + s["vy"] ** 2 + s["vz"] ** 2) ** .5, np.degrees(s["gyro_norm"]) if s["gyro_norm"] == s["gyro_norm"] else -1)
        print("%6.2f s  left: brightness %5.1f spread %5.1f, %3d corners, %3d of the last pair's found again, %3d found in the right (brightness %5.1f)%s" % (
            (t - t_end) / 1e9, l.mean(), l.std(), n, back, both, r.mean(), est))
        prev = l
        if out:
            tile = np.hstack([cv2.resize(l, (240, 320)), cv2.resize(r, (240, 320))]); tile = cv2.cvtColor(tile, cv2.COLOR_GRAY2BGR)
            cv2.putText(tile, "%.2f s  again %d" % ((t - t_end) / 1e9, back), (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1)
            sheet.append(tile)
    if out and sheet:
        os.makedirs(out, exist_ok=True); base = os.path.basename(path)[:-len(".uxloss")]
        for i in range(0, len(sheet), 20):
            part = sheet[i:i + 20]
            while len(part) % 4: part.append(np.zeros_like(part[0]))
            cv2.imwrite(os.path.join(out, "%s-%d.jpg" % (base, i // 20)), np.vstack([np.hstack(part[j:j + 4]) for j in range(0, len(part), 4)]), [cv2.IMWRITE_JPEG_QUALITY, 82])
        print("contact sheets (left and right side by side) in " + out)

def pull(out, serial):
    os.makedirs(out, exist_ok=True)
    adb = ["adb"] + (["-s", serial] if serial else [])
    names = subprocess.run(adb + ["shell", "ls " + FILES], capture_output=True, text=True).stdout.split()
    for n in names:
        if not os.path.isfile(os.path.join(out, n)): subprocess.run(adb + ["pull", FILES + "/" + n, os.path.join(out, n)], capture_output=True)
    print("%d files in %s" % (len(names), out))

def main():
    a = sys.argv[1:]
    if not a: print(__doc__); return 2
    if a[0] == "pull": pull(a[1], a[2] if len(a) > 2 else None); return 0
    one(a[0], a[1] if len(a) > 1 else None); return 0

if __name__ == "__main__":
    sys.exit(main())
