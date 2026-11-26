import os
d = r"C:\uvio\hb\monado\src\xrt\tracking\hand\mercury"
p = os.path.join(d, "hg_sync.cpp")
raw = open(p, encoding='utf-8', newline='').read(); crlf = '\r\n' in raw; s = raw.replace('\r\n', '\n')
if not os.path.exists(p + ".before_jclatch"): open(p + ".before_jclatch", 'w', encoding='utf-8', newline='').write(raw)
def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:80], s.count(a)); s = s.replace(a, b)
rep("static bool ultra_jc_low[2] = {false, false};\n", """static bool ultra_jc_low[2] = {false, false};
// ULTRA_HANDS_JC_LATCH=N (with ULTRA_HANDS_JC_GATE): the gate is asked of a new track only: it is shown once N frames in a row have a joint confidence over the threshold, and then stays shown
// for as long as the track lasts, whatever the confidence does (a real hand dips for a frame when fingers hide each other; a thing that is no hand rarely holds it for N frames).
static int ultra_jc_run[2] = {0, 0};
static bool ultra_jc_latched[2] = {false, false};
static int
ultra_jc_latch_frames()
{
	static const int n = []() {
		const char *e = std::getenv("ULTRA_HANDS_JC_LATCH");
		return e != nullptr ? std::atoi(e) : 0;
	}();
	return n;
}
""")
rep("""				ultra_jc_ema[hand_idx] = hgt->hand_tracked_for_num_frames[hand_idx] <= 1 ? best : 0.8f * ultra_jc_ema[hand_idx] + 0.2f * best;
				ultra_jc_low[hand_idx] = ultra_jc_ema[hand_idx] < ultra_jc_threshold();
			} else {
				ultra_jc_low[hand_idx] = false;
			}""", """				if (hgt->hand_tracked_for_num_frames[hand_idx] <= 1) {
					ultra_jc_run[hand_idx] = 0;
					ultra_jc_latched[hand_idx] = false;
				}
				ultra_jc_ema[hand_idx] = hgt->hand_tracked_for_num_frames[hand_idx] <= 1 ? best : 0.8f * ultra_jc_ema[hand_idx] + 0.2f * best;
				if (ultra_jc_latch_frames() > 0) {
					if (!ultra_jc_latched[hand_idx]) {
						ultra_jc_run[hand_idx] = best >= ultra_jc_threshold() ? ultra_jc_run[hand_idx] + 1 : 0;
						ultra_jc_latched[hand_idx] = ultra_jc_run[hand_idx] >= ultra_jc_latch_frames();
					}
					ultra_jc_low[hand_idx] = !ultra_jc_latched[hand_idx];
				} else {
					ultra_jc_low[hand_idx] = ultra_jc_ema[hand_idx] < ultra_jc_threshold();
				}
			} else {
				ultra_jc_low[hand_idx] = false;
				ultra_jc_run[hand_idx] = 0;
				ultra_jc_latched[hand_idx] = false;
			}""")
open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n') if crlf else s)
print('ok')
