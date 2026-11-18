#!/usr/bin/env python3
"""Does the approximate camera model cost distance? (from one stereo pair and things of known size)

  python exact_model_check.py FACTORY_CONFIG.json BASALT_CALIB.json

The glasses' factory calibration describes each tracking camera with a "fisheye624" model (6 radial, 2 tangential,
4 thin-prism terms). The trackers are fed a simpler Kannala-Brandt ("kb4") model fitted to it, which is off by
0.7 pixel on average and 2.6 at worst. In stereo a pixel is distance: this script places the same hand-picked
picture points with both models and prints the sizes that come out, next to the real ones.

The points are the corners of the owner's phone (a Pixel 10 Pro XL, 162.8 x 76.6 mm) in the stereo pair 60 s into
the recording of 17 November; they were read off enlarged pictures by eye, good to about a pixel.
"""
import json, sys
import numpy as np
sys.path.insert(0, __import__("os").path.dirname(__file__))
from stereo_check import quat_R, unproject_kb4, project_kb4
from room_scan import triangulate

def project_624(v, c):
    x, y, z = v[:, 0], v[:, 1], v[:, 2]
    rr = np.hypot(x, y); th = np.arctan2(rr, z); t2 = th * th
    poly = np.ones_like(th); p = t2.copy()
    for i in range(6): poly += c["k"][i] * p; p = p * t2
    thd = th * poly
    ux = np.where(rr > 1e-12, x / np.maximum(rr, 1e-12), 0.0); uy = np.where(rr > 1e-12, y / np.maximum(rr, 1e-12), 0.0)
    xr = thd * ux; yr = thd * uy; r2 = xr * xr + yr * yr
    k = c["k"]
    dx = (2 * xr * xr + r2) * k[6] + 2 * xr * yr * k[7] + k[8] * r2 + k[9] * r2 * r2
    dy = (2 * yr * yr + r2) * k[7] + 2 * xr * yr * k[6] + k[10] * r2 + k[11] * r2 * r2
    return np.stack([c["fx"] * (xr + dx) + c["cx"], c["fy"] * (yr + dy) + c["cy"]], axis=1)

def unproject_624(p, c, start):
    """Pixels to unit sight lines by Newton's method on the exact model, from the kb4 answer."""
    out = []
    for pix, b in zip(p, start):
        a = np.array([b[0] / b[2], b[1] / b[2]])          # the sight line as (x/z, y/z)
        for _ in range(20):
            def f(a): return project_624(np.array([[a[0], a[1], 1.0]]), c)[0] - pix
            r = f(a); J = np.zeros((2, 2))
            for i in range(2):
                d = np.zeros(2); d[i] = 1e-6; J[:, i] = (f(a + d) - r) / 1e-6
            a = a - np.linalg.solve(J, r)
            if np.abs(r).max() < 1e-9: break
        v = np.array([a[0], a[1], 1.0]); out.append(v / np.linalg.norm(v))
    return np.array(out)

def cameras(factory):
    s = json.load(open(factory))["SLAM_camera"]; out = []
    for i in (1, 2):
        d = s["device_%d" % i]; out.append({"fx": d["fc"][0], "fy": d["fc"][1], "cx": d["cc"][0], "cy": d["cc"][1], "k": d["kc"]})
    return out

def rig(calib):
    c = json.load(open(calib))["value0"]
    def pose(o): return quat_R([o["qx"], o["qy"], o["qz"], o["qw"]]), np.array([o["px"], o["py"], o["pz"]])
    Ri0, ti0 = pose(c["T_imu_cam"][0]); Ri1, ti1 = pose(c["T_imu_cam"][1])
    return c["intrinsics"][0]["intrinsics"], c["intrinsics"][1]["intrinsics"], Ri0.T @ Ri1, Ri0.T @ (ti1 - ti0)

def main():
    if len(sys.argv) != 3: print(__doc__); return 2
    f0, f1 = cameras(sys.argv[1]); k0, k1, R01, t01 = rig(sys.argv[2])
    # how far apart the two models are across the picture
    g = np.array([[x, y] for x in range(20, 480, 46) for y in range(20, 640, 62)], float)
    for name, k, f in (("left", k0, f0), ("right", k1, f1)):
        d = project_kb4(unproject_624(g, f, unproject_kb4(g, k)), k) - g
        print("%s camera: the fitted model is off by %.2f px on average, %.2f at worst; across (x) by %+.2f px on average at the bottom third of the picture, %+.2f at the top third"
              % (name, np.hypot(d[:, 0], d[:, 1]).mean(), np.hypot(d[:, 0], d[:, 1]).max(), d[g[:, 1] > 426, 0].mean(), d[g[:, 1] < 213, 0].mean()))
    L = np.array([[291, 432.5], [337.5, 433], [290.5, 527], [337.5, 527]], float); R = np.array([[167, 426.5], [215.5, 427], [166.5, 526], [215.5, 526]], float)
    for name, b0, b1 in (("fitted model (kb4), as the app used", unproject_kb4(L, k0), unproject_kb4(R, k1)),
                         ("factory model (fisheye624)", unproject_624(L, f0, unproject_kb4(L, k0)), unproject_624(R, f1, unproject_kb4(R, k1)))):
        P, miss, s0, _, _ = triangulate(b0, b1, R01, t01)
        w = (np.linalg.norm(P[0] - P[1]) + np.linalg.norm(P[2] - P[3])) / 2; h = (np.linalg.norm(P[0] - P[2]) + np.linalg.norm(P[1] - P[3])) / 2
        print("%s: phone %.3f m away, %.1f x %.1f mm (real 162.8 x 76.6): %.3f and %.3f of real; sight lines miss by %.1f mm" % (name, s0.mean(), h * 1000, w * 1000, h / 0.1628, w / 0.0766, miss.mean() * 1000))
    return 0

if __name__ == "__main__": sys.exit(main())
