import os
d = r"C:\uvio\hb\monado\src\xrt\tracking\hand\mercury"
p = os.path.join(d, "hg_sync.cpp")
raw = open(p, encoding='utf-8', newline='').read(); crlf = '\r\n' in raw; s = raw.replace('\r\n', '\n')
if not os.path.exists(p + ".before_jcgate"): open(p + ".before_jcgate", 'w', encoding='utf-8', newline='').write(raw)
def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:80], s.count(a)); s = s.replace(a, b)
rep("static bool ultra_settled[2] = {true, true};\n", """static bool ultra_settled[2] = {true, true};
// ULTRA_HANDS_JC_GATE=0.5: a hand is not shown to the application while the keypoint model's mean confidence over its 21 joints (the better camera's), smoothed over about
// five frames, is below this. The dark bag beside the laptop and the objects a fine-tuned detector begins as hands have joint confidences near 0.4; hands, near 0.6.
static float ultra_jc_ema[2] = {1.0f, 1.0f};
static bool ultra_jc_low[2] = {false, false};
static float
ultra_jc_threshold()
{
	static const float t = []() {
		const char *e = std::getenv("ULTRA_HANDS_JC_GATE");
		return e != nullptr ? (float)std::atof(e) : 0.0f;
	}();
	return t;
}
""")
rep("		// Don't send the hand to OpenXR until it's been tracked for 4 frames\n", """		// Don't send the hand to OpenXR until it's been tracked for 4 frames
		if (ultra_jc_threshold() > 0.0f) {
			if (hgt->this_frame_hand_detected[hand_idx]) {
				float best = 0.0f;
				for (int v = 0; v < 2; v++) {
					if (!hgt->views[v].regions_of_interest_this_frame[hand_idx].found) {
						continue;
					}
					float sum = 0.0f;
					for (int j = 0; j < 21; j++) {
						sum += hgt->keypoint_outputs[hand_idx].views[v].keypoints_in_scaled_stereographic[j].confidence_xy;
					}
					best = std::max(best, sum / 21.0f);
				}
				ultra_jc_ema[hand_idx] = hgt->hand_tracked_for_num_frames[hand_idx] <= 1 ? best : 0.8f * ultra_jc_ema[hand_idx] + 0.2f * best;
				ultra_jc_low[hand_idx] = ultra_jc_ema[hand_idx] < ultra_jc_threshold();
			} else {
				ultra_jc_low[hand_idx] = false;
			}
		}
""")
rep("!hgt->ultra_both_views_seen[hand_idx] || !ultra_settled[hand_idx]) {", "!hgt->ultra_both_views_seen[hand_idx] || !ultra_settled[hand_idx] || ultra_jc_low[hand_idx]) {")
open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n') if crlf else s)
print('ok')
