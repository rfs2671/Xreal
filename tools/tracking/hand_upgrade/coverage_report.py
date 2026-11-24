"""The coverage lab's session, place by place: how much of the time each hand was tracked while it was held there, how soon, how far the tracked hand was from where it was asked to be,
and whether the cameras could see it there at all. usage: python coverage_report.py lab-<stamp>   (the folder of the files, without the suffixes)"""
import sys, csv, numpy as np
sys.path.insert(0, r"C:\uvio\handup")
import coverage_geometry as cg
ZONES = {"straight ahead, arm's length": (0, 0, 50), "raised, in front of your face": (10, 5, 40), "chest height, in front of you": (0, -35, 35), "chest height, off to the side": (30, -35, 35),
         "belly height, in front of you": (0, -55, 30), "in your lap, a little to the side": (20, -60, 20), "resting by your hip": (30, -60, 0), "arm out to the side, forward a little": (50, -15, 50),
         "arm straight out to the side": (60, -15, 20), "high, above the display": (0, 40, 40), "close to your face": (0, 0, 20), "beside your head": (25, 0, 10),
         "BOTH hands wide apart at chest height": (45, -35, 35), "BOTH hands together at your belly": (8, -55, 30), "BOTH hands wide apart at shoulder height": (60, -15, 30)}
stem = sys.argv[1]
cues = [r for r in csv.DictReader(open(stem + "-cues.csv"))]
cover = np.array([[float(x) if x != '' else np.nan for x in r] for r in list(csv.reader(open(stem + "-cover.csv")))[1:]])
t, cue, seen = cover[:, 0], cover[:, 1].astype(int), cover[:, 2:4]; pos = [cover[:, 4:7], cover[:, 7:10]]
print("%-46s %-5s %8s %9s %12s  %-30s %s" % ("place", "hand", "tracked", "first (s)", "off (cm)", "cameras: left / right / stereo", "verdict"))
for c in cues:
    if c['kind'] != 'COVER': continue
    cid = int(c['id']); at = float(c['at_s']); win = float(c['window_s']); text = c['text']
    for name, (x, y, z) in ZONES.items():
        if text.endswith(name): zone = name; nominal = np.array([x, y, z], float); break
    else: continue
    sides = [0, 1] if c['side'] == '2' else [int(c['side'])]
    for side in sides:
        nom = nominal.copy()
        if side == 0 or (c['side'] == '2' and side == 0): nom[0] = -nom[0]                        # the left hand's place is the mirror of the right's
        m = (t >= at + 1.0) & (t <= at + win - 0.3)
        frac = np.nanmean(seen[m, side]) if m.any() else float('nan')
        first = next((tt - at for tt, s in zip(t[(t >= at) & (t <= at + win)], seen[(t >= at) & (t <= at + win), side]) if s == 1), None)
        p = pos[side][m & (seen[:, side] == 1)]
        off = np.linalg.norm(np.nanmedian(p, axis=0) - nom) if len(p) else float('nan')
        X = np.array([nom[0], nom[1], -nom[2]]) / 100.0; cl = cg.classify(X)
        cams = " / ".join(a[0] for a in cl); both = all(a[0] == 'full' for a in cl); anyv = any(a[0] in ('full', 'part') for a in cl)
        if frac >= 0.8: v = "tracked"
        elif both: v = "SOFTWARE: in both pictures, not tracked (%.0f %%)" % (100 * frac)
        elif anyv: v = "partly out of a picture; tracked %.0f %%" % (100 * frac)
        else: v = "OPTICAL: out of both pictures" + ("" if frac < 0.2 else " (yet tracked %.0f %%: it was not where asked)" % (100 * frac))
        print("%-46s %-5s %7.0f%% %9s %12s  %-30s %s" % (zone, "L" if side == 0 else "R", 100 * frac, "-" if first is None else "%.1f" % first, "-" if np.isnan(off) else "%.0f" % off, cams, v))
