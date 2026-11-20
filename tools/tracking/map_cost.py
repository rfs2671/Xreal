#!/usr/bin/env python3
"""What does the saved map cost the estimator and the hands? From replays of one recording under several settings.

  python map_cost.py DIR TAG [TAG ...]

For each run (tools/pixel/lab.py): the estimator's states a second and its time for a frame (median, 90th
percentile), the share of the recording with a trusted position, the hand tracker's time for a result, and how many
map readings there were with their time. Put runs without the map between the others: the phone warms up over a
batch, and a run is only to be compared with its neighbours.
"""
import csv, os, re, sys
import numpy as np

def main():
    d, tags = sys.argv[1], sys.argv[2:]
    print("%-11s %8s %10s %8s %9s %10s %9s %8s" % ("run", "states/s", "est. ms", "90th", "trusted", "hands ms", "readings", "map ms"))
    for tag in tags:
        v = [r for r in csv.DictReader(open(os.path.join(d, tag + "-vio.csv"))) if r.get("trusted") not in (None, "")]
        if not v: print(tag, "no states"); continue
        t = np.array([float(r["t"]) for r in v]); ms = np.array([float(r["process_ms"]) for r in v]); ok = np.array([int(r["trusted"]) for r in v])
        h = [float(r["process_ms"]) for r in csv.DictReader(open(os.path.join(d, tag + "-hands.csv"))) if r.get("process_ms") not in (None, "")]
        m = [float(x) for x in re.findall(r"map-in-app .* ms (\d+)", open(os.path.join(d, tag + "-notes.txt"), encoding="utf-8", errors="replace").read())]
        print("%-11s %8.1f %10.1f %8.1f %8.0f%% %10.1f %9d %8s" % (tag, len(t) / (t[-1] - t[0]), np.median(ms), np.percentile(ms, 90), 100 * ok.mean(), np.median(h) if h else float("nan"), len(m), ("%.0f" % np.median(m)) if m else "-"))

if __name__ == "__main__": main()
