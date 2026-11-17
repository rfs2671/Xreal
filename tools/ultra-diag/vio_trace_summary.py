#!/usr/bin/env python3
"""Summarise 6DoF trace files written by the app (VioTrace): python vio_trace_summary.py DIR"""
import glob, math, os, sys

def euler(q):
    x, y, z, w = q
    return [math.degrees(a) for a in (math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y)),
                                      math.asin(max(-1.0, min(1.0, 2 * (w * y - z * x)))),
                                      math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z)))]

for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.csv"))):
    lines = open(f).read().splitlines()
    print(os.path.basename(f), lines[0])
    rows = [list(map(float, r.split(","))) for r in lines[2:]]
    t0 = rows[0][0]
    print("   t_s      px      py      pz |     vx     vy     vz |  roll  pitch    yaw | feat  ms   |a|")
    for i in list(range(0, len(rows), 60)) + [len(rows) - 1]:
        r = rows[i]; e = euler(r[4:8])
        print("%6.1f %7.2f %7.2f %7.2f | %6.2f %6.2f %6.2f | %6.1f %6.1f %6.1f | %4d %3.0f %.3f"
              % ((r[0] - t0) * 1e-9, r[1], r[2], r[3], r[8], r[9], r[10], e[0], e[1], e[2], r[14], r[15], r[16]))
    a = rows[max(0, len(rows) - 300)]; b = rows[-1]; dt = (b[0] - a[0]) * 1e-9
    if dt > 0:
        print("   mean dv/dt over the last %.1f s: %.3f %.3f %.3f m/s^2; frame interval mean %.1f ms"
              % (dt, (b[8] - a[8]) / dt, (b[9] - a[9]) / dt, (b[10] - a[10]) / dt, 1e-6 * (rows[-1][0] - rows[0][0]) / (len(rows) - 1)))
