#!/usr/bin/env python3
"""After the cameras were covered in a replay: how soon was position back, and was it the right position?

  python recovery_report.py DIR REFERENCE_TAG RUN_TAG [RUN_TAG ...]

DIR holds the app's replay files (tools/pixel/lab.py). REFERENCE_TAG is a run of the same recording in which nothing
went wrong; each RUN_TAG had the cameras covered for a stretch (vio_options.txt: test_blind=FROM,TO). TAG-app.csv has
the head in the app's world and whether the estimator's position was in use, for every state.

The same recording puts the head in the same physical place at the same second. So where the two runs' worlds agree
before the covering, the distance between the head's place in the run and in the reference afterwards is how far the
run's world is out. Printed for each run:

  - how long position went on being used after the pictures stopped (what a wearer would have been shown of a blind
    estimator), and the furthest the head was shown from where it really was in that time
  - how long after the pictures came back position was in use again
  - how far out the world was at that moment, the worst it was at any time afterwards, when it was within 10 cm for
    good, and what it was from then to the end
  - what the app said it did (restarts, where it joined the world on, corrections against the map)
"""
import csv, os, re, sys
import numpy as np

def head(d, tag):
    return np.array([(float(r["t"]), float(r["hx"]), float(r["hy"]), float(r["hz"]), int(r["used"])) for r in csv.DictReader(open(os.path.join(d, tag + "-app.csv"))) if r.get("used") not in (None, "")])

def main():
    d, ref, tags = sys.argv[1], sys.argv[2], sys.argv[3:]
    a = head(d, ref)
    for tag in tags:
        b = head(d, tag); notes = open(os.path.join(d, tag + "-notes.txt"), encoding="utf-8", errors="replace").read().splitlines()
        blind = None
        for l in open(os.path.join(d, tag + "-vio.csv")): pass
        v = [r for r in csv.DictReader(open(os.path.join(d, tag + "-vio.csv"))) if r.get("trusted") not in (None, "")]
        tv = np.array([float(r["t"]) for r in v]); feat = np.array([int(r["features"]) for r in v])
        dark = np.where(feat < 25)[0]
        if not len(dark): print("%s: the cameras were never covered" % tag); continue
        t0 = tv[dark[0]]; t1 = tv[dark[-1]] + 1.0 / 30
        i = np.clip(np.searchsorted(a[:, 0], b[:, 0]), 1, len(a) - 1); i = np.where(np.abs(a[i - 1, 0] - b[:, 0]) < np.abs(a[i, 0] - b[:, 0]), i - 1, i)
        ok = (np.abs(a[i, 0] - b[:, 0]) < 0.012) & (a[i, 4] > 0)
        t = b[ok, 0]; used = b[ok, 4] > 0; raw = b[ok, 1:4] - a[i[ok], 1:4]
        # the two runs' worlds differ by a little even before anything went wrong: that is taken out
        before = (t > t0 - 10) & (t < t0 - 0.2) & used
        off0 = np.median(raw[before], axis=0) if before.any() else np.zeros(3)
        off = np.linalg.norm(raw - off0, axis=1)
        shown_blind = (t >= t0) & (t < t1) & used
        back = np.where((t >= t1) & used)[0]
        print("%s: covered from %.1f to %.1f s" % (tag, t0, t1))
        print("    position went on being used for %.2f s of that; the head was shown up to %.0f cm from where it was" % (shown_blind.sum() / 30.0, 100 * (off[shown_blind].max() if shown_blind.any() else 0)))
        if not len(back): print("    position was never in use again"); continue
        tb = t[back[0]]; after = (t >= tb) & used
        home = None
        for k in np.where(after & (off < 0.10))[0]:
            w = (t >= t[k]) & (t < t[k] + 3.0) & used
            if w.any() and off[w].max() < 0.10: home = t[k]; break
        rest = off[(t > (home if home else tb) + 0.5) & used]
        print("    in use again %.1f s after the pictures came back, %.0f cm out; worst afterwards %.0f cm; %s" % (tb - t1, 100 * off[back[0]], 100 * off[after].max(),
              ("within 10 cm for good %.1f s after the pictures came back" % (home - t1)) if home else "never within 10 cm for three seconds again"))
        if len(rest): print("    from then on: median %.1f cm, 90th %.1f cm, worst %.1f cm" % (100 * np.median(rest), 100 * np.percentile(rest, 90), 100 * rest.max()))
        for l in notes:
            if re.search(r"restart:|6DoF: position|room map: the (world|head is found again)|is put back|where the map has it again", l) and float(l.split()[0]) >= t0 - 0.5: print("      " + l[:170])

if __name__ == "__main__": main()
