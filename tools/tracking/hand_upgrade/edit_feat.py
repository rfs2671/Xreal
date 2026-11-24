import os
d = r"C:\uvio\hb\monado\src\xrt\tracking\hand\mercury"
p = os.path.join(d, "hg_sync.cpp")
raw = open(p, encoding='utf-8', newline='').read(); crlf = '\r\n' in raw; s = raw.replace('\r\n', '\n')
BS = chr(92)
def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:80], s.count(a)); s = s.replace(a, b)
rep("static bool ultra_settled[2] = {true, true};\n", "static bool ultra_settled[2] = {true, true};\nstatic float ultra_feat[2][4] = {};   // last fit of each hand: reprojection error, keypoint mean confidence in each view, hand size\n")
# after the xr_quality reject_fit line, before it: record features right after the settle block start
rep("\t\tif (xr_mercury_quality::reject_fit(hgt->xr_quality_filter, quality_sample, reprojection_error)) {",
    "\t\tultra_feat[hand_idx][0] = reprojection_error;\n\t\tultra_feat[hand_idx][1] = quality_sample.mean_xy[0];\n\t\tultra_feat[hand_idx][2] = quality_sample.mean_xy[1];\n\t\tultra_feat[hand_idx][3] = out_hand_size;\n\t\tif (xr_mercury_quality::reject_fit(hgt->xr_quality_filter, quality_sample, reprojection_error)) {")
rep("roi1=%d,%.0f,%.0f" + BS + "n\"", "roi1=%d,%.0f,%.0f err=%.3f mxy=%.3f,%.3f size=%.3f" + BS + "n\"")
rep("(double)hgt->views[1].regions_of_interest_this_frame[hand_idx].center_px.y);",
    "(double)hgt->views[1].regions_of_interest_this_frame[hand_idx].center_px.y,\n\t\t\t\t        (double)ultra_feat[hand_idx][0], (double)ultra_feat[hand_idx][1], (double)ultra_feat[hand_idx][2], (double)ultra_feat[hand_idx][3]);")
open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n') if crlf else s)
print('ok')
