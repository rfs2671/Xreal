import os, shutil, re
d = r"C:\uvio\hb\monado\src\xrt\tracking\hand\mercury"
def load(name, suffix):
    p = os.path.join(d, name); bk = p + suffix
    if not os.path.exists(bk): shutil.copy(p, bk)
    raw = open(bk, encoding='utf-8', newline='').read(); return p, ('\r\n' in raw), raw.replace('\r\n', '\n')
def save(p, crlf, s): open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', '\r\n') if crlf else s)

# ---------------------------------------------------------------- hg_sync.hpp: a flag on a region
p, crlf, s = load("hg_sync.hpp", ".before_tiles2")
a = "	bool ultra_stereo = false;\n"
assert s.count(a) == 1
s = s.replace(a, a + "	// libultra_hands: it came through a tile (not the whole-picture detector).\n	bool ultra_tile = false;\n")
save(p, crlf, s)

# ---------------------------------------------------------------- hg_model.cpp
p, crlf, s = load("hg_model.cpp", ".before_tiles2")
def rep(a, b):
    global s
    assert s.count(a) == 1, (a[:90], s.count(a)); s = s.replace(a, b)

rep('#include "ultra_prof.hpp"\n', '#include "ultra_prof.hpp"\n#include "ultra_phantom.hpp"\n')

rep("""struct UltraTile
{
	float yaw_deg = 0, pitch_deg = 0;
	float R[9] = {};
	cv::Mat map_x, map_y;
};

struct UltraTiles
{
	bool ready = false;
	int cols = 0, rows = 0;
	float f = 1.0f;
	std::vector<UltraTile> tiles;
	std::vector<int> order;
	size_t next = 0;
};
""", """struct UltraTile
{
	float yaw_deg = 0, pitch_deg = 0;
	float fov_deg = kUltraTileFovDeg;
	float f = 1.0f;       // pixels of the tile to one unit of x / z
	float weight = 1.0f;  // how often it is looked through (a prior: where hands are)
	float R[9] = {};
	cv::Mat map_x, map_y;
	cv::Mat cover;        // one byte for each 8 x 8 pixels of the picture the tile's middle covers
	int last_visit = -1000;
};

// Where a hand that was being tracked in this view was, and where it was last seen: a hand that is lost is looked for there first.
struct UltraMemory
{
	bool was = false;
	int seen_at = -1000, lost_at = -1000;
	xrt_vec2 last = {0, 0}, vel = {0, 0};
	float size = 0;
};

struct UltraTiles
{
	bool ready = false;
	int cols = 0, rows = 0;
	float f = 1.0f;
	std::vector<UltraTile> tiles;
	std::vector<int> order;
	size_t next = 0;
	UltraMemory mem[2];
};
""")

# ---- the new setup
i0 = s.index("void\nultra_tiles_setup(UltraTiles &T, const t_camera_model_params &dist, int cols, int rows)\n{")
i1 = s.index("	T.ready = true;\n}\n", i0) + len("	T.ready = true;\n}\n")
new_setup = r'''struct UltraSpec
{
	float yaw, pitch, fov, weight;
};

// ULTRA_HANDS_TILE_LIST="yaw,pitch,fov,weight;..." sets the tiles (degrees right of, and below, the axis; the field of view; how often it is looked
// through). Unset, the nine of 20 November: 70 degrees, yaw -48 0 48, pitch -22 22 58, the low and the side ones twice as often.
static std::vector<UltraSpec>
ultra_tile_specs(bool *custom)
{
	std::vector<UltraSpec> v;
	const char *e = std::getenv("ULTRA_HANDS_TILE_LIST");
	*custom = false;
	if (e != nullptr && e[0] != '\0') {
		std::string str(e);
		size_t pos = 0;
		while (pos < str.size()) {
			size_t end = str.find(';', pos);
			if (end == std::string::npos) {
				end = str.size();
			}
			float y = 0, pi = 0, fov = kUltraTileFovDeg, w = 1;
			if (sscanf(str.substr(pos, end - pos).c_str(), "%f,%f,%f,%f", &y, &pi, &fov, &w) >= 2) {
				v.push_back({y, pi, fov, w});
			}
			pos = end + 1;
		}
		if (!v.empty()) {
			*custom = true;
			return v;
		}
	}
	const float yaws[3] = {-48.0f, 0.0f, 48.0f};
	const float pitches[3] = {-22.0f, 22.0f, 58.0f};
	for (float pitch : pitches) {
		for (float yaw : yaws) {
			float w = 1.0f;
			if ((pitch == 58.0f && yaw == 0.0f) || (pitch == 22.0f && yaw != 0.0f)) {
				w = 2.0f;
			}
			v.push_back({yaw, pitch, kUltraTileFovDeg, w});
		}
	}
	return v;
}

void
ultra_tiles_setup(UltraTiles &T, const t_camera_model_params &dist, int cols, int rows)
{
	T.tiles.clear();
	T.order.clear();
	T.next = 0;
	T.cols = cols;
	T.rows = rows;
	T.f = (float)kUltraTile / 2.0f / tanf(kUltraTileFovDeg * (float)M_PI / 360.0f);
	bool custom = false;
	const std::vector<UltraSpec> specs = ultra_tile_specs(&custom);
	for (const UltraSpec &sp : specs) {
		UltraTile t;
		t.yaw_deg = sp.yaw;
		t.pitch_deg = sp.pitch;
		t.fov_deg = sp.fov;
		t.weight = sp.weight;
		t.f = (float)kUltraTile / 2.0f / tanf(sp.fov * (float)M_PI / 360.0f);
		const float a = sp.yaw * (float)M_PI / 180.0f, b = sp.pitch * (float)M_PI / 180.0f;
		const float ca = cosf(a), sa = sinf(a), cb = cosf(b), sb = sinf(b);
		// R = Ry(yaw) * Rx(pitch)^T: the tile's own axes in the camera's frame (x right, y down, z forward).
		const float R[9] = {ca, -sa * sb, sa * cb, 0.0f, cb, sb, -sa, -ca * sb, ca * cb};
		std::copy(R, R + 9, t.R);
		t.map_x = cv::Mat(kUltraTile, kUltraTile, CV_32FC1);
		t.map_y = cv::Mat(kUltraTile, kUltraTile, CV_32FC1);
		t.cover = cv::Mat::zeros((rows + 7) / 8, (cols + 7) / 8, CV_8UC1);
		int inside = 0;
		for (int v = 0; v < kUltraTile; v++) {
			for (int u = 0; u < kUltraTile; u++) {
				const float x = ((float)u + 0.5f - kUltraTile / 2.0f) / t.f;
				const float y = ((float)v + 0.5f - kUltraTile / 2.0f) / t.f;
				const float dx = R[0] * x + R[1] * y + R[2];
				const float dy = R[3] * x + R[4] * y + R[5];
				const float dz = R[6] * x + R[7] * y + R[8];
				float px = -1.0f, py = -1.0f;
				t_camera_models_project(&dist, dx, dy, dz, &px, &py);
				if (!std::isfinite(px) || !std::isfinite(py)) {
					px = -1.0f;
					py = -1.0f;
				}
				t.map_x.at<float>(v, u) = px;
				t.map_y.at<float>(v, u) = py;
				const bool in_pic = px >= 0.0f && px < (float)cols && py >= 0.0f && py < (float)rows;
				if (in_pic) {
					inside++;
					// the middle three fifths of the tile: where a hand is well inside it
					if (u >= kUltraTile / 5 && u < kUltraTile * 4 / 5 && v >= kUltraTile / 5 && v < kUltraTile * 4 / 5) {
						t.cover.at<uint8_t>((int)py / 8, (int)px / 8) = 1;
					}
				}
			}
		}
		// A tile that is mostly outside the picture is not worth a run of the detector.
		if ((float)inside / (float)(kUltraTile * kUltraTile) > 0.55f) {
			T.tiles.push_back(std::move(t));
		}
	}
	if (!custom) {
		// The order they are looked through in: the low and the side ones twice in a round, the ones ahead and up once.
		const float round[12][2] = {{0, 58}, {-48, 22}, {48, 22}, {0, 22}, {-48, 58}, {48, 58},
		                            {0, -22}, {0, 58}, {-48, 22}, {48, 22}, {-48, -22}, {48, -22}};
		for (const auto &want : round) {
			for (size_t i = 0; i < T.tiles.size(); i++) {
				if (T.tiles[i].yaw_deg == want[0] && T.tiles[i].pitch_deg == want[1]) {
					T.order.push_back((int)i);
				}
			}
		}
	} else {
		float maxw = 1.0f;
		for (const auto &t : T.tiles) {
			maxw = std::max(maxw, t.weight);
		}
		for (int r = 0; r < (int)ceilf(maxw); r++) {
			for (size_t i = 0; i < T.tiles.size(); i++) {
				if ((float)r < T.tiles[i].weight) {
					T.order.push_back((int)i);
				}
			}
		}
	}
	T.ready = true;
}

// ULTRA_HANDS_TILE_SCHED=1: which tiles to look through, each time, by what is known about the hands.
//  - a tile that has not been looked through for long, by its weight (so everything is looked at in the end, the low and the side ones oftener);
//  - first, the tile over where a hand that has just been lost was last seen, taking its drift into account, for 45 runs of the detector, and one more tile a time then;
//  - less, the tile over a hand that is being tracked (there is nothing new there).
// 0 (the default): in turn, as before.
static int
ultra_sched_mode()
{
	const char *e = std::getenv("ULTRA_HANDS_TILE_SCHED");
	return (e != nullptr && e[0] != '\0') ? std::atoi(e) : 0;
}

static bool
ultra_tile_covers(const UltraTile &t, float px, float py)
{
	const int cx = (int)(px / 8.0f), cy = (int)(py / 8.0f);
	if (cx < 0 || cy < 0 || cy >= t.cover.rows || cx >= t.cover.cols) {
		return false;
	}
	return t.cover.at<uint8_t>(cy, cx) != 0;
}
'''
s = s[:i0] + new_setup + s[i1:]

# ---- the pass: memory, choice, phantom, per-tile f
rep("""	UltraTiles &T = ultra_tiles[view->view & 1];
	if (!T.ready || T.cols != whole.cols || T.rows != whole.rows) {
		ultra_tiles_setup(T, view->hgdist, whole.cols, whole.rows);
	}
	if (T.order.empty()) {
		return;
	}
	const float min_conf = ultra_tiles_min_conf();
	float best[2] = {0.0f, 0.0f};
	for (int k = 0; k < per_call; k++) {
		// Both views look through the same tiles in a frame (the detector's counter is the frame's), so that a hand
		// both cameras see is found in both at once: a detection has to be, to begin a hand with two views.
		UltraTile &t = T.tiles[T.order[((size_t)hgt->detection_counter * (size_t)per_call + (size_t)k) % T.order.size()]];
""", """	UltraTiles &T = ultra_tiles[view->view & 1];
	if (!T.ready || T.cols != whole.cols || T.rows != whole.rows) {
		ultra_tiles_setup(T, view->hgdist, whole.cols, whole.rows);
	}
	if (T.order.empty()) {
		return;
	}
	const int sched = ultra_sched_mode();
	const int counter = hgt->detection_counter;
	int per_eff = per_call;
	bool used[64] = {};
	if (sched == 1) {
		// what is known of the hands: where a tracked one is, and where one that was has just been lost
		for (int h = 0; h < 2; h++) {
			UltraMemory &m = T.mem[h];
			const hand_region_of_interest &roi = view->regions_of_interest_this_frame[h];
			if (!need[h]) {
				if (roi.found) {
					if (m.was && counter - m.seen_at <= 3 && counter > m.seen_at) {
						const float dt = (float)(counter - m.seen_at);
						m.vel.x = 0.5f * m.vel.x + 0.5f * (roi.center_px.x - m.last.x) / dt;
						m.vel.y = 0.5f * m.vel.y + 0.5f * (roi.center_px.y - m.last.y) / dt;
					} else {
						m.vel = {0, 0};
					}
					m.last = roi.center_px;
					m.size = roi.size_px;
					m.seen_at = counter;
					m.was = true;
				}
			} else if (m.was && counter - m.seen_at <= 4) {
				m.was = false;
				m.lost_at = counter;
			}
		}
		for (int h = 0; h < 2; h++) {
			if (need[h] && counter - T.mem[h].lost_at <= 45) {
				per_eff = per_call + 1;
			}
		}
		per_eff = std::min(per_eff, 4);
	}
	const float min_conf = ultra_tiles_min_conf();
	float best[2] = {0.0f, 0.0f};
	for (int k = 0; k < per_eff; k++) {
		// Both views look through the same tiles in a frame (the detector's counter is the frame's), so that a hand
		// both cameras see is found in both at once: a detection has to be, to begin a hand with two views.
		size_t pick = T.order[((size_t)hgt->detection_counter * (size_t)per_call + (size_t)k) % T.order.size()];
		if (sched == 1) {
			float best_score = -1.0f;
			for (size_t i = 0; i < T.tiles.size() && i < 64; i++) {
				if (used[i]) {
					continue;
				}
				UltraTile &c = T.tiles[i];
				float score = c.weight * (1.0f + (float)std::min(40, counter - c.last_visit));
				for (int h = 0; h < 2; h++) {
					const UltraMemory &m = T.mem[h];
					if (need[h] && counter - m.lost_at <= 45) {
						const float age = (float)std::min(counter - m.lost_at, 8);
						if (ultra_tile_covers(c, m.last.x + m.vel.x * age, m.last.y + m.vel.y * age) || ultra_tile_covers(c, m.last.x, m.last.y)) {
							score += 1000.0f;
						}
					} else if (!need[h] && m.was && ultra_tile_covers(c, m.last.x, m.last.y)) {
						score *= 0.3f;
					}
				}
				if (score > best_score) {
					best_score = score;
					pick = i;
				}
			}
			used[pick] = true;
			T.tiles[pick].last_visit = counter;
		}
		UltraTile &t = T.tiles[pick];
""")
# centre mapping and size with the tile's own f
rep("""			const float x = (out[h].center_px.x - kUltraTile / 2.0f) / T.f;
			const float y = (out[h].center_px.y - kUltraTile / 2.0f) / T.f;
			const float dx = t.R[0] * x""", """			const float x = (out[h].center_px.x - kUltraTile / 2.0f) / t.f;
			const float y = (out[h].center_px.y - kUltraTile / 2.0f) / t.f;
			const float dx = t.R[0] * x""")
rep("""			if (ultra_stereo_seen(view, h, px, py)) {
				out[h].ultra_twice = true;
				out[h].ultra_stereo = true;
				out[h].center_px.x = px;
				out[h].center_px.y = py;
				out[h].size_px = out[h].size_px * view->hgdist.fx / T.f;""", """			// A place where a hand that a tile began was dropped within a few frames a moment ago: not believed again at once.
			if (sched == 1 && ultra_phantom::near(view->view, px, py, counter, 55.0f)) {
				continue;
			}
			if (ultra_stereo_seen(view, h, px, py)) {
				out[h].ultra_twice = true;
				out[h].ultra_stereo = true;
				out[h].ultra_tile = true;
				out[h].center_px.x = px;
				out[h].center_px.y = py;
				out[h].size_px = out[h].size_px * view->hgdist.fx / t.f;""")
rep("""			out[h].center_px.x = px;
			out[h].center_px.y = py;
			// As many pixels to an angle as the fisheye picture has there (it is near enough equidistant).
			out[h].size_px = out[h].size_px * view->hgdist.fx / T.f;""", """			out[h].ultra_tile = true;
			out[h].center_px.x = px;
			out[h].center_px.y = py;
			// As many pixels to an angle as the fisheye picture has there (it is near enough equidistant).
			out[h].size_px = out[h].size_px * view->hgdist.fx / t.f;""")
save(p, crlf, s)

# ---------------------------------------------------------------- hg_sync.cpp
p, crlf, s = load("hg_sync.cpp", ".before_tiles2")
rep("#include \"ultra_prof.hpp\"\n", "#include \"ultra_prof.hpp\"\n#include \"ultra_phantom.hpp\"\n")
rep("static bool ultra_begun_by_stereo[2] = {false, false};\n", "static bool ultra_begun_by_stereo[2] = {false, false};\n// a hand begun through a tile: where, in each view, and without the other camera's tiles having seen it\nstatic bool ultra_begun_by_tile[2] = {false, false};\nstatic xrt_vec2 ultra_begin_px[2][2] = {};\n")
rep("				ultra_begun_by_stereo[hand_idx] = infos[0].outputs[hand_idx].ultra_stereo || infos[1].outputs[hand_idx].ultra_stereo;\n",
    "				ultra_begun_by_stereo[hand_idx] = infos[0].outputs[hand_idx].ultra_stereo || infos[1].outputs[hand_idx].ultra_stereo;\n				ultra_begun_by_tile[hand_idx] = infos[0].outputs[hand_idx].ultra_tile || infos[1].outputs[hand_idx].ultra_tile;\n				for (int v = 0; v < 2; v++) {\n					ultra_begin_px[hand_idx][v] = infos[v].outputs[hand_idx].center_px;\n				}\n")
rep("""		if (!hgt->last_frame_hand_detected[hand_idx]) {
			hgt->views[0].regions_of_interest_this_frame[hand_idx].found = false;""", """		if (!hgt->last_frame_hand_detected[hand_idx]) {
			// ULTRA_HANDS_TILE_SCHED=1: a hand that a tile began, with only its own camera's word for it, and that is gone within 8 frames, was
			// most likely not one: no tile's find is believed within 55 pixels of where it began for 45 runs of the detector.
			if (ultra_begun_by_tile[hand_idx] && !ultra_begun_by_stereo[hand_idx] && hgt->hand_tracked_for_num_frames[hand_idx] <= 8) {
				for (int v = 0; v < 2; v++) {
					ultra_phantom::add(v, ultra_begin_px[hand_idx][v].x, ultra_begin_px[hand_idx][v].y, (int)hgt->detection_counter + 45);
				}
			}
			ultra_begun_by_tile[hand_idx] = false;
			hgt->views[0].regions_of_interest_this_frame[hand_idx].found = false;""")
save(p, crlf, s)
print('ok')
