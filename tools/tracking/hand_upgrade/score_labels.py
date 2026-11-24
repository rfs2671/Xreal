"""usage: score_labels.py labels.csv run.csv [run2.csv ...]  -- tracked share where a hand is plainly in both pictures, and reported where there is none"""
import csv, sys, numpy as np
lab = {}
for r in csv.reader(open(sys.argv[1])):
    if r and r[0].strip().isdigit(): lab[int(r[0])] = (r[1].strip(), r[2].strip())
def cls(s, h):
    if s not in lab: return None
    l, r = lab[s]
    if h in l and h in r: return "both"
    if h not in l and h not in r and h.upper() not in l and h.upper() not in r: return "none"
    return "unsure"
for f in sys.argv[2:]:
    rows = [r for r in csv.reader(open(f)) if len(r) > 3 and r[0] != 't']
    out = []
    for hand, bit in (("L", 1), ("R", 2)):
        cnt = {"both": [0, 0], "none": [0, 0]}
        for r in rows:
            c = cls(int(float(r[0])), hand)
            if c in cnt: cnt[c][1] += 1; cnt[c][0] += 1 if (int(r[1]) & bit) else 0
        out.append(f"{hand}: plainly there {cnt['both'][0]}/{cnt['both'][1]} ({100*cnt['both'][0]/max(1,cnt['both'][1]):.0f}%), reported where none {cnt['none'][0]}/{cnt['none'][1]} ({100*cnt['none'][0]/max(1,cnt['none'][1]):.0f}%)")
    print(f.replace(chr(92),'/').split('/')[-1], '|', ' | '.join(out))
