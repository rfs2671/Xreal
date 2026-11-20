#!/usr/bin/env python3
"""Mercury's own per-frame logs for one hand over some seconds, a line a frame: where does the hand go?

  python hand_trace.py DIR TAG HAND FROM_S TO_S [EVERY]      HAND: L or R; seconds of the recording

DIR holds TAG-trk.csv and TAG-det.csv (MERCURY_XR_TRACKING_DEBUG_CSV, MERCURY_XR_DETECTOR_DEBUG_CSV, written by
native/hands/test/ultra_hands_replay.c through the uh.sh runner; its times are the recording's plus one second).
A line: time; act = reported; prev = tracked in the frame before; views = cameras the fit used; err/limit = the
fit's reprojection error against its limit; rej = the rejection bits; then for each camera: the region of interest
(i = given to the keypoint model, provenance, centre, size), hand = the keypoint model's "is a hand" probability,
conf = mean keypoint confidence, out = joints predicted outside the picture, next = a region found for the next
frame; then the detector's row for that frame if it ran: found and confidence in each camera, the gate, the reason.
"""
import csv, os, sys
d, tag, hand, lo, hi = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4]), float(sys.argv[5]); every = int(sys.argv[6]) if len(sys.argv) > 6 else 1
h = 0 if hand == "L" else 1
det = {}
for r in csv.DictReader(open(os.path.join(d, tag + "-det.csv"))):
    if int(r["hand_idx"]) == h: det[r["timestamp_ns"]] = r
def f(v, n=2):
    try: x = float(v)
    except Exception: return "-"
    return "-" if x != x else ("%." + str(n) + "f") % x
k = 0
for r in csv.DictReader(open(os.path.join(d, tag + "-trk.csv"))):
    if int(r["hand_idx"]) != h: continue
    t = int(r["timestamp_ns"]) / 1e9 - 1.0
    if t < lo or t >= hi: continue
    k += 1
    if k % every: continue
    line = "%6.2f act%s prev%s views %s err %s/%s rej %s palm %s %s %s" % (t, r["final_active"], r["previous_detected"], f(r["observation_views"], 0), f(r["reprojection_error"], 3), f(r["reprojection_limit"], 3), r["rejection_mask"], f(r["palm_x"]), f(r["palm_y"]), f(r["palm_z"]))
    for v in ("v0", "v1"):
        line += " | %s roi%s%s p%s %s,%s s%s hand %s conf %s out %s next%s" % (v, r[v + "_roi_before_overlap_found"], r[v + "_roi_input_found"], r[v + "_roi_provenance"], f(r[v + "_roi_cx"], 0), f(r[v + "_roi_cy"], 0), f(r[v + "_roi_size"], 0),
                 f(r[v + "_is_hand_probability"]), f(r[v + "_confidence_xy_mean"]), r[v + "_predicted_outside_joints"], r[v + "_roi_next_found"])
    q = det.get(r["timestamp_ns"])
    if q: line += " || det v0 %s %s (%s,%s) v1 %s %s (%s,%s) gate%s try%s go%s why%s acc%s" % (q["view0_found"], f(q["view0_raw_conf"]), f(q["view0_center_x"], 0), f(q["view0_center_y"], 0), q["view1_found"], f(q["view1_raw_conf"]), f(q["view1_center_x"], 0), f(q["view1_center_y"], 0),
                 q["conf_gate_pass"], q["attempt_replace"], q["good_to_go"], q["reject_reason"], q["accepted"])
    print(line)
