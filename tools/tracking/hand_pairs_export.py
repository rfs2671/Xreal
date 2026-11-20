#!/usr/bin/env python3
"""Stereo pairs of a recording as one plain file for the on-phone test harness (native/hands/test/ultra_hands_replay.c).

  python hand_pairs_export.py RECORDING.uxrec OUT.pairs [FROM_S TO_S]

Every pair of the recording (the two eyes' frames whose device times are within 2 ms of each other), upright 480x640
as the hand tracker gets them. The file: "UXPAIRS1", int32 count, then for each pair int64 nanoseconds since the
recording began, the left picture (307200 bytes), the right picture. Private: keep it outside the repository.
"""
import os, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyse_recording as ar

def pairs(path):
    first = None; wait = [None, None]
    for kind, t, data in ar.records(path):
        if first is None: first = t
        if kind != 1: continue
        dev, counter, eye = ar.meta(data)
        if eye > 1: continue
        other = wait[1 - eye]
        if other is not None and abs(other[0] - dev) < 2_000_000:
            a = ar.decode(data)[0]; b = ar.decode(other[2])[0]; wait = [None, None]
            yield t - first, (a if eye == 0 else b), (b if eye == 0 else a)
        else: wait[eye] = (dev, t, data)

if __name__ == "__main__":
    rec, out = sys.argv[1], sys.argv[2]; lo = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0; hi = float(sys.argv[4]) if len(sys.argv) > 4 else 1e9
    n = 0
    with open(out, "wb") as f:
        f.write(b"UXPAIRS1" + struct.pack("<i", 0))
        for t, l, r in pairs(rec):
            s = t / 1e9
            if s < lo: continue
            if s >= hi: break
            f.write(struct.pack("<q", t)); f.write(l.tobytes()); f.write(r.tobytes()); n += 1
        f.seek(8); f.write(struct.pack("<i", n))
    print("%d pairs from %.1f to %.1f s, %.0f MB" % (n, lo, min(hi, s), os.path.getsize(out) / 1e6))
