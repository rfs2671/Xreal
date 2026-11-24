import os, shutil
d = r"C:\uvio\hb\monado\src\xrt\tracking\hand\mercury"
def load(name, suffix):
    p = os.path.join(d, name); bk = p + suffix
    if not os.path.exists(bk): shutil.copy(p, bk)
    raw = open(bk, encoding='utf-8', newline='').read(); return p, ('\r\n' in raw), raw.replace('\r\n', '\n')
def save(p, crlf, s): open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n') if crlf else s)

p, crlf, s = load("hg_model.cpp", ".before_tiles3")
def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:90], s.count(a)); s = s.replace(a, b)
rep("""			if (ultra_stereo_seen(view, h, px, py)) {
				out[h].ultra_twice = true;
				out[h].ultra_stereo = true;
				out[h].ultra_tile = true;""", """			// A hand that was being tracked and was lost a moment ago, found again where it was or where it was going: the same hand. It
			// needs neither the other camera's tiles to have seen it nor a second look (a stand beside a laptop is not where a hand was).
			// ULTRA_HANDS_RECALL=1
			if (sched == 1 && counter - T.mem[h].lost_at <= 45 && ultra_recall_on()) {
				const UltraMemory &m = T.mem[h];
				const float age = (float)std::min(counter - m.lost_at, 8);
				const float ex = m.last.x + m.vel.x * age, ey = m.last.y + m.vel.y * age;
				if (hypotf(px - ex, py - ey) < 80.0f || hypotf(px - m.last.x, py - m.last.y) < 80.0f) {
					out[h].ultra_twice = true;
					out[h].ultra_stereo = true;
					out[h].ultra_tile = true;
					out[h].center_px.x = px;
					out[h].center_px.y = py;
					out[h].size_px = out[h].size_px * view->hgdist.fx / t.f;
					info->outputs[h] = out[h];
					best[h] = conf;
					continue;
				}
			}
			if (ultra_stereo_seen(view, h, px, py)) {
				out[h].ultra_twice = true;
				out[h].ultra_stereo = true;
				out[h].ultra_tile = true;""")
rep("""static bool
ultra_tile_covers(const UltraTile &t, float px, float py)""", """static bool
ultra_recall_on()
{
	static const bool on = []() {
		const char *e = std::getenv("ULTRA_HANDS_RECALL");
		return e != nullptr && e[0] != '\\0' && e[0] != '0';
	}();
	return on;
}

static bool
ultra_tile_covers(const UltraTile &t, float px, float py)""")
save(p, crlf, s)

p, crlf, s = load("hg_sync.cpp", ".before_tiles3")
rep("static bool ultra_begun_by_tile[2] = {false, false};\n", """static bool ultra_begun_by_tile[2] = {false, false};
// a hand that was tracked for a while and was lost less than 1.5 s ago, found again within reach of where it was, is the same hand (ULTRA_HANDS_RECALL=1):
// it need not be settled (five good fits) or warm up (four frames) again. Where it was last, in the camera's frame, and when.
static bool ultra_recalled[2] = {false, false};
static float ultra_last_wrist[2][3] = {};
static int64_t ultra_last_wrist_ns[2] = {-1000000000000LL, -1000000000000LL};
static int ultra_last_len[2] = {0, 0};
static bool
ultra_recall_enabled()
{
	static const bool on = []() {
		const char *e = std::getenv("ULTRA_HANDS_RECALL");
		return e != nullptr && e[0] != '\\0' && e[0] != '0';
	}();
	return on;
}
""")
rep("""		hgt->history_hands[hand_idx].push_back(asf);
		hgt->hand_tracked_for_num_frames[hand_idx]++;
""", """		hgt->history_hands[hand_idx].push_back(asf);
		hgt->hand_tracked_for_num_frames[hand_idx]++;
		if (ultra_recall_enabled()) {
			const xrt_vec3 &wp = put_in_set->values.hand_joint_set_default[XRT_HAND_JOINT_WRIST].relation.pose.position;
			const int64_t now_ns = (int64_t)hgt->current_frame_timestamp;
			if (hgt->hand_tracked_for_num_frames[hand_idx] == 1) {
				ultra_recalled[hand_idx] = false;
				const float age_s = (float)(now_ns - ultra_last_wrist_ns[hand_idx]) / 1e9f;
				if (age_s >= 0.0f && age_s <= 1.5f && ultra_last_len[hand_idx] >= 20) {
					const float dx = wp.x - ultra_last_wrist[hand_idx][0], dy = wp.y - ultra_last_wrist[hand_idx][1], dz = wp.z - ultra_last_wrist[hand_idx][2];
					const float reach = std::min(0.50f, 0.12f + 0.8f * age_s);
					if (sqrtf(dx * dx + dy * dy + dz * dz) < reach) {
						ultra_recalled[hand_idx] = true;
						ultra_settled[hand_idx] = true;
					}
				}
			}
			ultra_last_wrist[hand_idx][0] = wp.x;
			ultra_last_wrist[hand_idx][1] = wp.y;
			ultra_last_wrist[hand_idx][2] = wp.z;
			ultra_last_wrist_ns[hand_idx] = now_ns;
			ultra_last_len[hand_idx] = (int)hgt->hand_tracked_for_num_frames[hand_idx];
		}
""")
rep("""		if (hgt->hand_tracked_for_num_frames[hand_idx] < hgt->tuneable_values.num_frames_before_display ||""",
    """		if (hgt->hand_tracked_for_num_frames[hand_idx] < (ultra_recalled[hand_idx] ? std::min<uint64_t>(2, hgt->tuneable_values.num_frames_before_display) : hgt->tuneable_values.num_frames_before_display) ||""")
save(p, crlf, s)
print('ok')
