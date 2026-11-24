"""What the two tracking cameras can physically see, from the calibration alone, independent of the display.
usage: python coverage_geometry.py [out.csv]
Head frame: x right, y up, z back (forward is -z), origin at the motion sensor. A point is "seen" by a camera when it projects inside its
480 x 640 picture; "partly" when only the hand's own radius reaches into it; "stereo" when seen by both (a hand has to be in both for Mercury to start)."""
import json, sys, numpy as np
CAL = r"C:\Users\asddd\Downloads\Xreal-claude-pixel-xreal-xr-gaming-cguv7y\_latest\app\src\main\assets\calibration\basalt_calib.json"
d = json.load(open(CAL))['value0']
def R(q):
    x, y, z, w = q; return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])
IMU2HEAD = np.array([[-1, 0, 0], [0, 0, 1], [0, 1, 0]], float)      # head x right = -imu x; head y up = imu z; head z back = imu y
cams = []
for i in (0, 1):
    t = d['T_imu_cam'][i]; r = R([t['qx'], t['qy'], t['qz'], t['qw']]); p = np.array([t['px'], t['py'], t['pz']])
    k = d['intrinsics'][i]['intrinsics']; cams.append(dict(R=IMU2HEAD @ r, p=IMU2HEAD @ p, k=k))
W, H = 480, 640

def project(c, X):
    """pixel of head-frame point X in camera c (kb4), and the angle from the optical axis."""
    Xc = c['R'].T @ (X - c['p']); x, y, z = Xc
    r = np.hypot(x, y); th = np.arctan2(r, z); k = c['k']
    rr = th + k['k1'] * th**3 + k['k2'] * th**5 + k['k3'] * th**7 + k['k4'] * th**9
    s = (k['fx'] * rr / r) if r > 1e-9 else k['fx']
    return k['fx'] * rr * x / r + k['cx'] if r > 1e-9 else k['cx'], (k['fy'] * rr * y / r + k['cy']) if r > 1e-9 else k['cy'], th, z

def px_per_metre(c, X):
    """how many pixels a metre across the line of sight spans at X, along the picture's two axes (local scale of the lens)."""
    out = []
    for e in (np.array([1., 0, 0]), np.array([0, 1., 0])):
        a = project(c, X + 1e-3 * e); b = project(c, X - 1e-3 * e)
        out.append(np.hypot(a[0] - b[0], a[1] - b[1]) / 2e-3)
    return out

def classify(X, hand_r=0.06):
    res = []
    for c in cams:
        u, v, th, z = project(c, X)
        if z <= 0.01: res.append(('behind', u, v, th, None)); continue
        # the hand's radius as pixels here
        s = np.mean(px_per_metre(c, X)); rad = hand_r * s
        inside = 0 <= u < W and 0 <= v < H
        full = rad <= u < W - rad and rad <= v < H - rad
        res.append(('full' if full else ('part' if (-rad <= u < W + rad and -rad <= v < H + rad) else 'out'), u, v, th, 2 * rad))
    return res

if __name__ == "__main__":
    print("cameras (head frame), position and optical axis, degrees right / up of straight ahead:")
    for i, c in enumerate(cams):
        ax = c['R'] @ np.array([0, 0, 1.]); print("  cam %d at (%.1f, %.1f, %.1f) cm: axis %.1f deg %s, %.1f deg %s" % (i, *(c['p'] * 100), abs(np.degrees(np.arctan2(ax[0], -ax[2]))), 'right' if ax[0] > 0 else 'left', abs(np.degrees(np.arcsin(ax[1]))), 'up' if ax[1] > 0 else 'down'))
    # the picture's own limits as view directions (the edge of the 480 x 640 rectangle)
    def edge_angles(c):
        k = c['k']; out = {}
        for name, (u, v) in dict(left=(0, k['cy']), right=(W - 1, k['cy']), top=(k['cx'], 0), bottom=(k['cx'], H - 1), corner=(0, H - 1)).items():
            x = (u - k['cx']) / k['fx']; y = (v - k['cy']) / k['fy']; rr = np.hypot(x, y); th = rr
            for _ in range(30): th = th - (th + k['k1'] * th**3 + k['k2'] * th**5 + k['k3'] * th**7 + k['k4'] * th**9 - rr) / (1 + 3 * k['k1'] * th**2 + 5 * k['k2'] * th**4 + 7 * k['k3'] * th**6 + 9 * k['k4'] * th**8)
            out[name] = np.degrees(th)
        return out
    for i, c in enumerate(cams): print("  cam %d: the picture's edge is %s degrees from its axis" % (i, {k: round(v, 1) for k, v in edge_angles(c).items()}))
    print("\nplaces a hand rests or goes (hand centre in the head frame, cm: right, up, forward) and what the cameras make of it (hand radius 6 cm):")
    places = [("straight ahead, arm's length", (0, 0, 50)), ("raised, in the display", (10, 5, 40)), ("chest height, in front", (0, -35, 35)), ("chest height, 30 cm to the right", (30, -35, 35)), ("belly / lap, in front", (0, -55, 30)),
              ("lap, 20 cm to the right", (20, -60, 20)), ("resting, hand by the hip", (30, -60, 0)), ("arm out to the right, shoulder height", (60, -15, 20)), ("arm out to the right, forward", (50, -15, 50)),
              ("arm out to the left, shoulder height", (-60, -15, 20)), ("high, above the head's front", (0, 40, 40)), ("far to the right, display's edge", (40, 0, 45)), ("close to the face", (0, 0, 20)), ("beside the head, right", (25, 0, 10))]
    print("%-40s %-22s %-22s %s" % ("place", "left camera (u,v, hand px)", "right camera", "stereo (both, whole hand)"))
    for name, (x, y, z) in places:
        X = np.array([x, y, -z]) / 100.0; r = classify(X)
        def f(t): return "%-4s %4.0f,%4.0f %3.0f px" % (t[0], t[1], t[2], t[4] or 0) if t[0] != 'behind' else 'behind'
        both = 'both whole' if all(t[0] == 'full' for t in r) else ('both, one partly' if all(t[0] in ('full', 'part') for t in r) else ('ONE camera only' if any(t[0] in ('full', 'part') for t in r) else 'NEITHER'))
        print("%-40s %-22s %-22s %s" % (name, f(r[0]), f(r[1]), both))
    # a grid of view directions, at 40 cm: which fraction of each ring is seen by both whole, by one, by none
    print("\ngrid of directions at 40 cm (degrees right, degrees up; hand centre), S = whole hand in both pictures, p = partly in both, L or R = one picture only, . = neither:")
    els = list(range(60, -91, -10)); azs = list(range(-110, 111, 10))
    print("      " + " ".join("%4d" % a for a in azs))
    rows = []
    for el in els:
        line = []
        for az in azs:
            a, e = np.radians(az), np.radians(el); X = 0.4 * np.array([np.sin(a) * np.cos(e), np.sin(e), -np.cos(a) * np.cos(e)]); r = classify(X)
            vis = [t[0] in ('full', 'part') for t in r]; fullv = [t[0] == 'full' for t in r]
            line.append('S' if all(fullv) else ('p' if all(vis) else ('L' if vis[0] else ('R' if vis[1] else '.'))))
        print("%5d " % el + " ".join("%4s" % ch for ch in line)); rows.append(line)
    # the display: 46 x 27 degrees; the share of the picture-wide field it covers
    tot = sum(len(r) for r in rows); S = sum(r.count('S') for r in rows); P = sum(r.count('p') for r in rows)
    print("\nof %d directions in the grid (220 deg wide, 150 deg tall) at 40 cm: %d seen whole by both cameras, %d partly by both" % (tot, S, P))
    # hand size in pixels, and the detector's view of it (the picture is shown to the detector as 160 x 160)
    print("\nhand 12 cm across, in pixels of the 480 x 640 picture, and in the detector's 160 x 160 (a quarter of that vertically, a third horizontally):")
    for name, (x, y, z) in [("straight ahead, 50 cm", (0, 0, 50)), ("chest, 35 cm", (0, -35, 35)), ("lap, 30 cm", (0, -55, 30)), ("arm out right, 50 cm", (50, -15, 50)), ("raised in display, 40 cm", (10, 5, 40))]:
        X = np.array([x, y, -z]) / 100.0; c = cams[0]; sc = px_per_metre(c, X); print("  %-26s %3.0f x %3.0f px in the picture, %3.0f x %3.0f px for the detector" % (name, 0.12 * sc[0], 0.12 * sc[1], 0.12 * sc[0] / 3.0, 0.12 * sc[1] / 4.0))
    # depth resolution of stereo at the point, per pixel of disparity
    print("\nstereo: 1 pixel of disparity is this much depth at distance z (baseline %.1f cm, f %.0f px at the axis):" % (abs(cams[0]['p'][0] - cams[1]['p'][0]) * 100, cams[0]['k']['fx']))
    for z in (0.2, 0.3, 0.4, 0.5, 0.7): print("  %2.0f cm: %.1f mm" % (z * 100, z * z / (cams[0]['k']['fx'] * abs(cams[0]['p'][0] - cams[1]['p'][0])) * 1000))
