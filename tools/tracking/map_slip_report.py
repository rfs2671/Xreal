#!/usr/bin/env python3
"""Is the world put back onto the saved map without a jump? From replays in which it was pushed aside on purpose.

  python map_slip_report.py DIR REFERENCE_TAG SLIP_TAG [SLIP_TAG ...]

DIR holds the app's replay files (tools/pixel/lab.py). The reference run found the head in the saved map and was left
alone ("m_find"); in each slip run ("s_8", "s_20", ...) the app's world was pushed aside at one moment
(vio_options.txt: map_test_slip) without the aligner being told, as after a wrong join. TAG-app.csv has the head in
the app's world for every state of the estimator.

The same recording puts the head in the same physical place at the same second, so the distance between the head's
place in the slip run and in the reference run is how far the slip run's world is off. This prints that distance
before the push (what two runs differ by anyway), just after it, how long it takes to come back under 3 cm, what is
left afterwards, and the largest change of it from one state to the next while it comes back: the jump a wearer
could see. A change between two states is also made of the two runs' own noise, so the same figure is given for the
seconds before the push.
"""
import csv, os, re, sys
import numpy as np

def head(d, tag):
    rows = [(float(r["t"]), float(r["hx"]), float(r["hy"]), float(r["hz"])) for r in csv.DictReader(open(os.path.join(d, tag + "-app.csv"))) if r.get("used") not in (None, "") and int(r["used"]) > 0]
    return np.array(rows)

def main():
    d, ref, tags = sys.argv[1], sys.argv[2], sys.argv[3:]
    a = head(d, ref)
    for tag in tags:
        b = head(d, tag); notes = open(os.path.join(d, tag + "-notes.txt"), encoding="utf-8", errors="replace").read().splitlines()
        push = [l for l in notes if "map test: the world is pushed" in l]; back = [l for l in notes if "is put back" in l]
        if not push: print("%s: the world was never pushed (the head was not found in the map before the moment set?)" % tag); continue
        t0 = float(push[0].split()[0])
        i = np.searchsorted(a[:, 0], b[:, 0]); i = np.clip(i, 1, len(a) - 1); i = np.where(np.abs(a[i - 1, 0] - b[:, 0]) < np.abs(a[i, 0] - b[:, 0]), i - 1, i)
        ok = np.abs(a[i, 0] - b[:, 0]) < 0.012
        t = b[ok, 0]; off = np.linalg.norm(b[ok, 1:] - a[i[ok], 1:], axis=1)
        before = off[(t > t0 - 15) & (t < t0)]; after = (t >= t0)
        change = np.abs(np.diff(off)); tc = t[1:]; gap = np.diff(t) < 0.05
        just = off[(t >= t0) & (t < t0 + 0.5)]
        under = np.where(after & (off < 0.03))[0]
        # back for good: the first moment after which it stays under 3 cm for two seconds
        home = None
        for k in under:
            w = (t >= t[k]) & (t < t[k] + 2.0)
            if off[w].max() < 0.03: home = t[k]; break
        coming = (tc > t0 + 0.2) & (tc < (home if home else t[-1])) & gap
        calm = (tc > t0 - 15) & (tc < t0) & gap
        rest = off[t > (home if home else t0) + 1.0]
        print("%s: %s" % (tag, push[0].split(" ", 1)[1]))
        print("    off against the reference run: median %.1f cm before the push; %.1f cm just after it; %s" % (100 * np.median(before) if len(before) else -1, 100 * (just.max() if len(just) else -1),
              ("under 3 cm for good %.1f s later" % (home - t0)) if home else "never under 3 cm for two seconds again"))
        if len(rest): print("    afterwards: median %.1f cm, 90th %.1f cm, worst %.1f cm" % (100 * np.median(rest), 100 * np.percentile(rest, 90), 100 * rest.max()))
        if coming.any(): print("    largest change from one state to the next while it comes back: %.1f mm (99th percentile %.1f mm; %d states); in the 15 s before the push: %.1f mm (99th %.1f mm)" % (
            1000 * change[coming].max(), 1000 * np.percentile(change[coming], 99), int(coming.sum()), 1000 * (change[calm].max() if calm.any() else -1), 1000 * (np.percentile(change[calm], 99) if calm.any() else -1)))
        # the app's own word: how far it last saw the map from where it holds it, at every reading
        own = [(float(m.group(1)), float(m.group(2))) for m in (re.match(r"^([\d.]+) \w+ map-in-app .* off ([\d.]+) m", l) for l in notes) if m]
        seen = [x for x in own if x[0] > t0]; first = next((x for x in seen if x[1] > 0.03), None)
        if first:
            done = next((x for x in seen if x[0] > first[0] and x[1] < 0.02), None)
            print("    by the app's own readings: first seen %.1f cm off %.1f s after the push; %s" % (100 * first[1], first[0] - t0, ("within 2 cm %.1f s after the push" % (done[0] - t0)) if done else "never within 2 cm again"))
        print("    the app: %d times put back at once%s" % (len(back), ("; " + back[0].split(" ", 1)[1]) if back else ""))

if __name__ == "__main__": main()
