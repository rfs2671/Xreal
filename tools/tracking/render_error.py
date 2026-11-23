"""How far the head given to the renderer is from where the estimator later had it, and how much that shakes.

    python render_error.py DIR/TAG [DIR/TAG ...]

Reads TAG-render.csv (the head 60 times a second) and TAG-states.csv (every state the head tracker used, at its own
time, on the same clock) from a replay through the app (tools/pixel/lab.py). The states, smoothed over a third of a
second, stand for where the head was; a renderer's row is compared with them at its own time. "off" is the distance,
"shake" is how much that distance changes from one frame to the next, which is what a thing fixed in the room is
seen to tremble by. Pixels are for a thing 0.45 m and 2 m away on the Air 2 Ultra (1920 pixels over 46 degrees).
"""
import sys
import numpy as np
from scipy.signal import savgol_filter

PX = 1920 / 46.0

def rows(path, n):
    out = []
    for line in open(path).read().splitlines()[1:]:
        c = line.split(",")
        if len(c) >= n and c[n - 1] != "":
            out.append([float(x) for x in c[:n]])
    return np.array(out)

def main():
    print("run            head    frames |  off mm (rms, 95th) | shake mm a frame (rms, 95th) | shake in pixels at 0.45 m and at 2 m")
    for tag in sys.argv[1:]:
        r = rows(tag + "-render.csv", 9); s = rows(tag + "-states.csv", 4)
        r = r[r[:, 8] == 1]
        # stretches of states without a gap or a jump (a new world of the estimator's is another stretch)
        cut = np.where((np.diff(s[:, 0]) > 0.06) | (np.linalg.norm(np.diff(s[:, 1:4], axis=0), axis=1) > 0.05))[0]
        E = []; D = []; SP = []
        for part in np.split(np.arange(len(s)), cut + 1):
            if len(part) < 30: continue
            t = s[part, 0]; p = savgol_filter(s[part, 1:4], 11, 3, axis=0); v = savgol_filter(s[part, 1:4], 11, 3, deriv=1, delta=1 / 30.0, axis=0)
            m = (r[:, 0] > t[5]) & (r[:, 0] < t[-6]); rr = r[m]
            if len(rr) < 30: continue
            truth = np.stack([np.interp(rr[:, 0], t, p[:, i]) for i in range(3)], 1); speed = np.interp(rr[:, 0], t, np.linalg.norm(v, axis=1))
            e = rr[:, 1:4] - truth; ok = np.diff(rr[:, 0]) < 0.03
            E.append(np.linalg.norm(e, axis=1)[1:][ok]); D.append(np.linalg.norm(np.diff(e, axis=0), axis=1)[ok]); SP.append(speed[1:][ok])
        if not E: print(tag, "nothing to compare"); continue
        E = np.concatenate(E) * 1e3; D = np.concatenate(D) * 1e3; SP = np.concatenate(SP)
        for name, sel in (("still", SP < 0.05), ("slow", (SP >= 0.05) & (SP < 0.3)), ("walking", SP >= 0.3)):
            if sel.sum() < 30: continue
            rms = lambda x: float(np.sqrt((x[sel] ** 2).mean())); sh = rms(D)
            print("%-14s %-7s %6d | %5.1f %5.1f | %5.2f %5.2f | %4.1f %4.1f" % (tag.replace("\\", "/").split("/")[-1], name, sel.sum(), rms(E), np.percentile(E[sel], 95), sh, np.percentile(D[sel], 95),
                  np.degrees(sh / 450.0) * PX, np.degrees(sh / 2000.0) * PX))

if __name__ == "__main__":
    main()
