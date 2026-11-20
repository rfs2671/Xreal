#!/usr/bin/env python3
"""The rig and the poses of a replay as plain text for the on-phone map test (native/slam/test/rtab_replay.cpp).

  python slam_export.py BASALT_CALIB.json TAG-vio.csv OUT_DIR

BASALT_CALIB.json is the calibration the estimator was given (the app writes it to files/basalt_calib.json at every
start). Writes OUT_DIR/rig.txt (a line a camera: fx fy cx cy k1 k2 k3 k4, then the camera's place in the motion
sensor's frame as px py pz qx qy qz qw) and OUT_DIR/poses.txt (t_s px py pz qx qy qz qw trusted).
"""
import csv, json, os, sys

def main():
    calib, vio, out = sys.argv[1:4]
    d = json.load(open(calib))["value0"]; os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "rig.txt"), "w", newline="\n") as f:
        for cam, T in zip(d["intrinsics"], d["T_imu_cam"]):
            assert cam["camera_type"] == "kb4", cam["camera_type"]
            i = cam["intrinsics"]
            f.write(" ".join("%.12g" % x for x in [i["fx"], i["fy"], i["cx"], i["cy"], i["k1"], i["k2"], i["k3"], i["k4"]] + [T[k] for k in ("px", "py", "pz", "qx", "qy", "qz", "qw")]) + "\n")
    n = 0; ok = 0
    with open(os.path.join(out, "poses.txt"), "w", newline="\n") as f:
        for r in csv.DictReader(open(vio)):
            f.write(" ".join([r["t"]] + [r[k] for k in ("px", "py", "pz", "qx", "qy", "qz", "qw")] + [r["trusted"]]) + "\n"); n += 1; ok += int(r["trusted"]) > 0
    print("%d poses, %d trusted" % (n, ok))

if __name__ == "__main__": main()
