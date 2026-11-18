"""How steady is a tracked hand's pointing ray, and what would steadying its orientation buy?

  python hand_steady.py DIR TAG [TAG ...]

DIR holds TAG-joints.csv as written by the app's replay (tracking/ReplayLog): every found hand's 26 joint poses in
the tracker's camera frame. Nothing here needs the phone.

A browser draws a hand's ray from the joint poses it is given, so two things move the ray's far end: the hand's
place wobbling (millimetres, one to one) and its orientation wobbling (a degree is 3.5 cm at two metres). For
each hand this prints, over the stretches where the hand is held nearly still:

  place    RMS of the palm's position about its own half-second average, in millimetres
  turn     RMS angle of the palm's forward axis about its half-second average, in degrees
  bones    the same for the wrist-to-index-knuckle direction, which uses positions only
  at 2 m   what each does to the far end of a two metre ray, in centimetres

and then the same after the filter the app would apply (a "one euro" filter on the palm's rotation, the settings
given by --cutoff and --beta), with what that filter costs while the hand is turning quickly: how far behind the
true direction it is, in degrees (median and 95th percentile).
"""
import math, sys
import numpy as np

PALM, WRIST, INDEX_PROXIMAL = 0, 1, 7

def read(path):
    hands = {0: [], 1: []}
    with open(path) as f:
        next(f)
        for line in f:
            v = line.rstrip().split(",")
            if len(v) < 2 + 182: continue
            hands[int(v[1])].append([float(v[0])] + [float(x) for x in v[2:2 + 182]])
    return {s: np.array(r) for s, r in hands.items() if len(r) > 30}

def rotate(q, v):
    """Rotate vector v by quaternions q (n x 4, x y z w)."""
    u = q[:, :3]; w = q[:, 3:4]
    return v + 2 * np.cross(u, np.cross(u, v) + w * v)

def unit(a): return a / np.maximum(np.linalg.norm(a, axis=1, keepdims=True), 1e-9)

def smooth(t, x, window):
    """Centred average over [window] seconds, by time (the tracker's rate is not constant)."""
    out = np.empty_like(x); j0 = 0; j1 = 0; n = len(t)
    for i in range(n):
        while t[i] - t[j0] > window / 2: j0 += 1
        while j1 < n and t[j1] - t[i] <= window / 2: j1 += 1
        out[i] = x[j0:j1].mean(axis=0)
    return out

def angle(a, b): return np.degrees(np.arccos(np.clip((a * b).sum(axis=1), -1, 1)))

def qmul(a, b):
    ax, ay, az, aw = a; bx, by, bz, bw = b
    return np.array([aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx, aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz])

def slerp(a, b, k):
    d = float(np.dot(a, b))
    if d < 0: b = -b; d = -d
    if d > 0.9995: q = a + k * (b - a); return q / np.linalg.norm(q)
    th = math.acos(d); return (math.sin((1 - k) * th) * a + math.sin(k * th) * b) / math.sin(th)

def one_euro_rotation(t, q, cutoff, beta, dcut=1.0):
    """The filter of core/HandSteady, for rotations: the cutoff rises with the (smoothed) turning speed in rad/s."""
    out = np.empty_like(q); out[0] = q[0]; speed = 0.0
    for i in range(1, len(t)):
        dt = t[i] - t[i - 1]
        if dt <= 0 or dt > 0.5: out[i] = q[i]; speed = 0.0; continue
        d = abs(float(np.dot(out[i - 1], q[i]))); raw = 2 * math.acos(min(1.0, d)) / dt
        a = 1 / (1 + (1 / (2 * math.pi * dcut)) / dt); speed += a * (raw - speed)
        k = 1 / (1 + (1 / (2 * math.pi * (cutoff + beta * speed))) / dt)
        out[i] = slerp(out[i - 1], q[i], k)
    return out

def report(name, t, rows, cutoff, beta):
    j = rows[:, 1:].reshape(len(rows), 26, 7)
    palm = j[:, PALM, :3]; q = j[:, PALM, 3:]; q = q / np.linalg.norm(q, axis=1, keepdims=True)
    fwd = rotate(q, np.array([0.0, 0.0, -1.0])); bones = unit(j[:, INDEX_PROXIMAL, :3] - j[:, WRIST, :3])
    dt = np.diff(t); ok = np.concatenate([[False], (dt > 0) & (dt < 0.2)])
    avg = smooth(t, palm, 0.3); speed = np.zeros(len(t)); speed[1:] = np.linalg.norm(np.diff(avg, axis=0), axis=1) / np.maximum(dt, 1e-3)
    favg = unit(smooth(t, fwd, 0.3)); turn = np.zeros(len(t)); turn[1:] = angle(favg[1:], favg[:-1]) / np.maximum(dt, 1e-3)
    still = ok & (speed < 0.05) & (turn < 15); moving = ok & (turn > 60)
    def rms(a): return float(np.sqrt((a ** 2).mean())) if len(a) else float("nan")
    place = rms(np.linalg.norm(palm - smooth(t, palm, 0.5), axis=1)[still]) * 1000
    def wobble(d): return rms(angle(d, unit(smooth(t, d, 0.5)))[still])
    w_turn = wobble(fwd); w_bones = wobble(bones)
    filt = one_euro_rotation(t, q, cutoff, beta); ffwd = rotate(filt, np.array([0.0, 0.0, -1.0]))
    w_filt = wobble(ffwd); lag = angle(ffwd, fwd)[moving]
    cm = lambda deg: 200 * math.tan(math.radians(deg))
    print("%s: %d results over %.0f s at %.0f Hz; held still in %d, turning fast in %d" % (name, len(t), t[-1] - t[0], len(t) / max(t[-1] - t[0], 1e-3), still.sum(), moving.sum()))
    print("  place %.1f mm   turn %.2f deg (%.1f cm at 2 m)   bones %.2f deg (%.1f cm at 2 m)" % (place, w_turn, cm(w_turn), w_bones, cm(w_bones)))
    print("  with the rotation filter (cutoff %.1f Hz, beta %.1f): turn %.2f deg (%.1f cm at 2 m); behind a fast turn by %s deg median, %s deg at the 95th percentile"
          % (cutoff, beta, w_filt, cm(w_filt), ("%.1f" % np.median(lag)) if len(lag) else "-", ("%.1f" % np.percentile(lag, 95)) if len(lag) else "-"))
    return dict(place_mm=place, turn_deg=w_turn, bones_deg=w_bones, filtered_deg=w_filt, lag_deg=float(np.median(lag)) if len(lag) else None)

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opt = dict(a[2:].split("=") for a in sys.argv[1:] if a.startswith("--") and "=" in a)
    if len(args) < 2: print(__doc__); return 1
    cutoff = float(opt.get("cutoff", 1.5)); beta = float(opt.get("beta", 1.0))
    for tag in args[1:]:
        hands = read("%s/%s-joints.csv" % (args[0], tag))
        if not hands: print("%s: no hands in the log" % tag); continue
        for side, rows in hands.items(): report("%s %s hand" % (tag, "right" if side else "left"), rows[:, 0], rows, cutoff, beta)
    return 0

if __name__ == "__main__": sys.exit(main())
