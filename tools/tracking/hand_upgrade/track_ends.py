"""Where do tracked hands start and stop, in the cameras' pictures? A track that stops with the hand still well inside both pictures was lost by the software;
one that stops at a picture's edge was lost by the optics. usage: python track_ends.py RUN.csv [RUN2.csv ...]   (harness csv: t,mask,ms,l_x,l_y,l_z,l_gap,r_x,r_y,r_z,r_gap; palm in the left camera's frame)"""
import sys, csv, json, numpy as np
sys.path.insert(0, r"C:\uvio\handup")
import coverage_geometry as cg
W, H = cg.W, cg.H
cam0, cam1 = cg.cams
def px_both(Xc_openxr):
    """a palm position in the left camera's frame (x right, y up, -z forward) -> pixels in the left and the right camera"""
    x, y, z = Xc_openxr
    # left camera frame (x right, y down, z forward) -> head frame -> right camera
    Xl = np.array([x, -y, -z]); Xh = cam0['R'] @ Xl + cam0['p']
    ul, vl, _, zl = cg.project(cam0, Xh); ur, vr, _, zr = cg.project(cam1, Xh)
    return (ul, vl, zl), (ur, vr, zr)
def margin(u, v): return min(u, W - 1 - u, v, H - 1 - v)
ends = []; starts = []
for f in sys.argv[1:]:
    rows = [r for r in csv.reader(open(f)) if len(r) > 8 and r[0] != 't']
    for hand, ix in (("L", (3, 4, 5)), ("R", (7, 8, 9))):
        present = [r[ix[0]] != '' for r in rows]
        pos = [tuple(float(r[i]) for i in ix) if p else None for r, p in zip(rows, present)]
        i = 0; n = len(rows)
        while i < n:
            if present[i]:
                j = i
                while j + 1 < n and (present[j + 1] or (j + 1 < n and any(present[j + 1:j + 8]))): j += 1       # gaps under 8 pairs do not end a track
                if j - i >= 15:            # a track of half a second
                    last = next(pos[k] for k in range(j, i - 1, -1) if pos[k]); first = next(pos[k] for k in range(i, j + 1) if pos[k])
                    if j < n - 10: ends.append((f, hand, j, last))
                    if i > 10: starts.append((f, hand, i, first))
                i = j + 1
            else: i += 1
def report(name, items):
    print(f"\n{name}: {len(items)} tracks")
    cls = {"inside both pictures (>60 px from every edge)": 0, "inside both, near an edge (20-60 px)": 0, "at an edge or out of one picture (<20 px)": 0, "behind/other": 0}
    out = []
    for (f, hand, idx, pos) in items:
        (ul, vl, zl), (ur, vr, zr) = px_both(pos)
        if zl <= 0 or zr <= 0: cls["behind/other"] += 1; continue
        m = min(margin(ul, vl), margin(ur, vr))
        key = "inside both pictures (>60 px from every edge)" if m > 60 else ("inside both, near an edge (20-60 px)" if m >= 20 else "at an edge or out of one picture (<20 px)")
        cls[key] += 1; out.append((hand, ul, vl, ur, vr, m, -pos[2]))
    for k, v in cls.items(): print(f"  {k}: {v}")
    if out:
        a = np.array([(o[1], o[2], o[5], o[6]) for o in out]); print("  left-camera pixel of the palm: u %d..%d (median %d), v %d..%d (median %d); distance %.0f-%.0f cm" % (a[:, 0].min(), a[:, 0].max(), np.median(a[:, 0]), a[:, 1].min(), a[:, 1].max(), np.median(a[:, 1]), 100 * a[:, 3].min(), 100 * a[:, 3].max()))
        low = sum(1 for o in out if o[2] > 450); print("  of them with the palm in the lowest fifth of the left picture (v > 450): %d" % low)
report("where tracks END (the hand is then lost for 10 pairs or more)", ends)
report("where tracks BEGIN (after 10 pairs or more without the hand)", starts)
