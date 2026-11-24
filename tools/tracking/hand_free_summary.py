#!/usr/bin/env python3
"""Summarise a hand-free scan's hands file (handfree-<time>-hands.csv: time_s,left_tracked,right_tracked).

Every hand the tracker reports during a scan with no hands in view is a false hand. Prints how many false-hand events
there were (a run of consecutive samples with a hand), their durations and the longest, per hand and for either.
Optionally (--cues handfree-<time>-cues.csv) names the cue each event began in.

  python hand_free_summary.py handfree-20261124-120000-hands.csv [--cues handfree-20261124-120000-cues.csv]
"""
import argparse, csv, sys


def load(path):
    rows = []
    with open(path, newline='') as f:
        for r in csv.DictReader(f):
            rows.append((float(r['time_s']), int(r['left_tracked']), int(r['right_tracked'])))
    return rows


def events(rows, pick, gap=0.5):
    """Runs where pick(row) is true; samples more than `gap` s apart end a run. Returns [(start, end)], end = last true sample."""
    out = []; start = last = None
    for r in rows:
        t = r[0]
        if pick(r):
            if start is not None and t - last > gap:
                out.append((start, last)); start = None
            if start is None:
                start = t
            last = t
        elif start is not None:
            out.append((start, last)); start = None
    if start is not None:
        out.append((start, last))
    return out


def load_cues(path):
    with open(path, newline='') as f:
        return [(float(r['time_s']), r['cue']) for r in csv.DictReader(f)]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('hands'); ap.add_argument('--cues')
    a = ap.parse_args(argv)
    rows = load(a.hands)
    if not rows:
        print('no samples'); return 0
    dur = rows[-1][0] - rows[0][0]
    cues = load_cues(a.cues) if a.cues else []
    print('%s: %d samples over %.1f s' % (a.hands, len(rows), dur))
    for name, pick in (('left', lambda r: r[1]), ('right', lambda r: r[2]), ('either', lambda r: r[1] or r[2])):
        ev = events(rows, pick)
        d = [e - s + 0.1 for s, e in ev]       # a one-sample event counts as one sample's time
        if not ev:
            print('  %-6s 0 false-hand events' % name); continue
        secs = len({int(t) for r in rows if pick(r) for t in [r[0]]})
        print('  %-6s %d events, %.1f s in all, mean %.2f s, max %.2f s, %d whole seconds touched'
              % (name, len(ev), sum(d), sum(d) / len(d), max(d), secs))
        if name == 'either':
            for (s, e), dd in zip(ev, d):
                cue = ''
                if cues:
                    c = [x for x in cues if x[0] <= s]
                    cue = '  during "%s"' % c[-1][1] if c else ''
                print('    t=%6.1f s  %.2f s%s' % (s, dd, cue))
    return 0


if __name__ == '__main__':
    sys.exit(main())
