#!/usr/bin/env python3
"""Stereo pairs out of a recording, as the hand tracker gets them (upright 480x640, left | right), at given seconds.

  python hand_frames.py RECORDING.uxrec OUT_DIR 8 20 24 28        pictures at those seconds
  python hand_frames.py RECORDING.uxrec OUT_DIR --npz EVERY       every EVERY-th pair into OUT_DIR/pairs.npz (t, left, right)

The pictures show the wearer's room and hands: OUT_DIR must be outside the repository.
"""
import os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar

def pairs(path, every=1):
    """(seconds, left, right) for every [every]-th stereo pair of the recording."""
    first = None; pend = {}; k = 0
    for kind, t, data in ar.records(path):
        if first is None: first = t
        if kind != 1: continue
        dev, counter, eye = ar.meta(data)
        if eye > 1: continue
        if eye == 0:
            k += 1
            if k % every == 0: pend[counter] = ar.decode(data)[0]
            if len(pend) > 8: pend.pop(next(iter(pend)))
        elif counter in pend:
            yield (t - first) / 1e9, pend.pop(counter), ar.decode(data)[0]

if __name__ == "__main__":
    rec, out = sys.argv[1], sys.argv[2]; os.makedirs(out, exist_ok=True)
    if sys.argv[3] == "--npz":
        every = int(sys.argv[4]); ts = []; ls = []; rs = []
        for s, l, r in pairs(rec, every): ts.append(s); ls.append(l); rs.append(r)
        np.savez_compressed(os.path.join(out, "pairs.npz"), t=np.array(ts), left=np.array(ls), right=np.array(rs))
        print("%d pairs, %.1f s" % (len(ts), ts[-1] if ts else 0))
    else:
        want = sorted(float(x) for x in sys.argv[3:]); i = 0
        for s, l, r in pairs(rec):
            if i >= len(want): break
            if s >= want[i]:
                both = np.hstack([l, r]); cv2.imwrite(os.path.join(out, "t%05.1f.png" % s), both); print("t %.1f s, mean brightness %.0f" % (s, both.mean())); i += 1
