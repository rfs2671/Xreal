// Copyright 2022, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Mercury hand tracking main file.
 * @author Jakob Bornecrantz <jakob@collabora.com>
 * @author Moshi Turner <moshiturner@protonmail.com>
 * @author Charlton Rodda <charlton.rodda@collabora.com>
 * @ingroup tracking
 */

#include "hg_sync.hpp"
#include "ultra_hands_config.h"
#include "hg_image_math.inl"
#include "tracking/t_hand_tracking.h"
#include "util/u_box_iou.hpp"
#include "util/u_hand_tracking.h"
#include "math/m_vec2.h"
#include "util/u_misc.h"
#include "xrt/xrt_defines.h"
#include "xrt/xrt_frame.h"
#include "xrt/xrt_tracking.h"


#include <numeric>

// libultra_hands: has a newly begun hand's fit settled yet (see where the fit is run)?
static bool ultra_settled[2] = {true, true};
// ULTRA_HANDS_JC_GATE=0.5: a hand is not shown to the application while the keypoint model's mean confidence over its 21 joints (the better camera's), smoothed over about
// five frames, is below this. The dark bag beside the laptop and the objects a fine-tuned detector begins as hands have joint confidences near 0.4; hands, near 0.6.
static float ultra_jc_ema[2] = {1.0f, 1.0f};
static bool ultra_jc_low[2] = {false, false};
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
static float
ultra_jc_threshold()
{
	static const float t = []() {
		const char *e = std::getenv("ULTRA_HANDS_JC_GATE");
		return e != nullptr ? (float)std::atof(e) : 0.0f;
	}();
	return t;
}
static float ultra_feat[2][4] = {};   // last fit of each hand: reprojection error, keypoint mean confidence in each view, hand size
static void
ultra_drop(const char *why, int hand, double t)
{
	static const bool on = std::getenv("ULTRA_HANDS_GATE_TRACE") != nullptr;
	if (on) {
		fprintf(stderr, "DROP %.3f %d %s\n", t, hand, why);
	}
}
static bool ultra_begun_by_stereo[2] = {false, false};
// a hand begun through a tile: where, in each view, and without the other camera's tiles having seen it
static bool ultra_begun_by_tile[2] = {false, false};
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
		return e != nullptr && e[0] == '1';
	}();
	return on;
}
static xrt_vec2 ultra_begin_px[2][2] = {};
static int ultra_settle_fits[2] = {0, 0}, ultra_settle_good[2] = {0, 0};

#include <cerrno>
#include <cstdint>
#include <cfloat>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <string>
#include <strings.h>
#include "ultra_prof.hpp"
#include "ultra_phantom.hpp"


namespace xrt::tracking::hand::mercury {

DEBUG_GET_ONCE_LOG_OPTION(mercury_log, "MERCURY_LOG", U_LOGGING_WARN)
DEBUG_GET_ONCE_BOOL_OPTION(mercury_optimize_hand_size, "MERCURY_optimize_hand_size", true)
DEBUG_GET_ONCE_FLOAT_OPTION(mercury_min_detection_confidence, "MERCURY_MIN_DETECTION_CONFIDENCE", 0.3)

static bool
xr_env_is_enabled(const char *name, bool default_value = false)
{
	const char *v = std::getenv(name);
	if (v == nullptr || v[0] == '\0') {
		return default_value;
	}
	return !(strcmp(v, "0") == 0 || strcasecmp(v, "false") == 0 || strcasecmp(v, "no") == 0 ||
	         strcasecmp(v, "off") == 0);
}

static const char *
xr_debug_dump_dir_env()
{
	const char *dir = std::getenv("MERCURY_XR_DEBUG_DUMP_DIR");
	if (dir == nullptr || dir[0] == '\0') {
		return nullptr;
	}
	return dir;
}

static bool
xr_debug_dump_enabled()
{
	return xr_debug_dump_dir_env() != nullptr;
}

static bool
xr_debug_dump_frame_allowed(struct HandTracking *hgt, bool left_active, bool right_active);

static std::string
xr_debug_safe_env_suffix(const char *name);

static bool
xr_write_ppm_rgb(const std::filesystem::path &path, const cv::Mat &bgr)
{
	if (bgr.empty() || bgr.type() != CV_8UC3) {
		return false;
	}

	std::ofstream out(path, std::ios::binary);
	if (!out) {
		return false;
	}

	out << "P6\n" << bgr.cols << " " << bgr.rows << "\n255\n";
	for (int y = 0; y < bgr.rows; y++) {
		const uint8_t *row = bgr.ptr<uint8_t>(y);
		for (int x = 0; x < bgr.cols; x++) {
			uint8_t rgb[3] = {row[(x * 3) + 2], row[(x * 3) + 1], row[(x * 3) + 0]};
			out.write(reinterpret_cast<const char *>(rgb), sizeof(rgb));
		}
	}
	return true;
}

static bool
xr_write_pgm_l8(const std::filesystem::path &path, const cv::Mat &gray)
{
	if (gray.empty() || gray.type() != CV_8UC1) {
		return false;
	}

	std::ofstream out(path, std::ios::binary);
	if (!out) {
		return false;
	}

	out << "P5\n" << gray.cols << " " << gray.rows << "\n255\n";
	for (int y = 0; y < gray.rows; y++) {
		out.write(reinterpret_cast<const char *>(gray.ptr<uint8_t>(y)), gray.cols);
	}
	return true;
}

static void
xr_debug_dump_frames(struct HandTracking *hgt, xrt_frame *ann_frame, xrt_frame *model_frame, bool left_active, bool right_active)
{
	const char *dir_c = xr_debug_dump_dir_env();
	if (dir_c == nullptr) {
		return;
	}

	if (!xr_debug_dump_frame_allowed(hgt, left_active, right_active)) {
		return;
	}

	const std::filesystem::path root(dir_c);
	const std::filesystem::path ann_dir = root / "ann";
	const std::filesystem::path model_dir = root / "model";
	std::error_code ec;
	std::filesystem::create_directories(ann_dir, ec);
	std::filesystem::create_directories(model_dir, ec);

	std::ostringstream name;
	name << "frame_" << std::setw(6) << std::setfill('0') << hgt->current_frame_timestamp << "_det"
	     << std::setw(6) << std::setfill('0') << hgt->detection_counter;

	std::string dump_suffix = xr_debug_safe_env_suffix("MERCURY_XR_CURRENT_DUMP_INDEX");
	if (!dump_suffix.empty()) {
		name << "_dump" << dump_suffix;
	}

	std::string label_suffix = xr_debug_safe_env_suffix("MERCURY_XR_CURRENT_LABEL");
	if (!label_suffix.empty()) {
		name << "_" << label_suffix;
	}

	name << "_L" << (left_active ? 1 : 0) << "_R" << (right_active ? 1 : 0);

	if (ann_frame != nullptr) {
		cv::Mat ann(cv::Size((int)ann_frame->width, (int)ann_frame->height), CV_8UC3, ann_frame->data,
		            ann_frame->stride);
		xr_write_ppm_rgb(ann_dir / (name.str() + "_ann.ppm"), ann);
	}

	if (model_frame != nullptr) {
		cv::Mat model(cv::Size((int)model_frame->width, (int)model_frame->height), CV_8UC1, model_frame->data,
		              model_frame->stride);
		xr_write_pgm_l8(model_dir / (name.str() + "_model.pgm"), model);
	}
}

static bool
xr_debug_draw_skeleton_enabled()
{
	return xr_env_is_enabled("MERCURY_XR_DEBUG_DRAW_SKELETON", true);
}

static bool
xr_debug_draw_joint_numbers_enabled()
{
	return xr_env_is_enabled("MERCURY_XR_DEBUG_DRAW_JOINT_NUMBERS", true);
}

static bool
xr_debug_draw_keypoints_enabled()
{
	return xr_env_is_enabled("MERCURY_XR_DEBUG_DRAW_KEYPOINTS", true);
}

static bool
xr_debug_draw_optimizer_enabled()
{
	return xr_env_is_enabled("MERCURY_XR_DEBUG_DRAW_OPTIMIZER", true);
}

static bool
xr_debug_draw_predictions_enabled()
{
	return xr_env_is_enabled("MERCURY_XR_DEBUG_DRAW_PREDICTIONS", false);
}

static bool
xr_debug_env_token_enabled(const char *name, const char *token)
{
	const char *v = std::getenv(name);
	if (v == nullptr || v[0] == '\0') {
		return false;
	}

	// Accept a small set of simple forms: "all", "both", exact token, or a comma-separated list.
	std::string value(v);
	std::string current;
	for (size_t i = 0; i <= value.size(); i++) {
		const char c = i < value.size() ? value[i] : ',';
		if (c == ',' || c == ';' || c == ' ' || c == '\t') {
			if (!current.empty()) {
				if (strcasecmp(current.c_str(), "all") == 0 || strcasecmp(current.c_str(), "both") == 0 ||
				    strcasecmp(current.c_str(), token) == 0) {
					return true;
				}
				current.clear();
			}
		} else {
			current.push_back(c);
		}
	}

	return false;
}

static bool
xr_debug_mirror_overlay_for_slot(int hand_idx)
{
	if (hand_idx == 0 && xr_env_is_enabled("MERCURY_XR_DEBUG_MIRROR_SLOT0", false)) {
		return true;
	}
	if (hand_idx == 1 && xr_env_is_enabled("MERCURY_XR_DEBUG_MIRROR_SLOT1", false)) {
		return true;
	}
	return false;
}

static bool
xr_debug_mirror_source_filter_is_set()
{
	const char *v = std::getenv("MERCURY_XR_DEBUG_MIRROR_SOURCE_TAG");
	return v != nullptr && v[0] != '\0';
}

static bool
xr_debug_mirror_overlay_for_source(const char *source_tag)
{
	// source_tag is currently "kp" for keypoint-model projection or "opt" for optimizer projection.
	// This is a diagnostic-only overlay transform; it does not change Mercury state/runtime output.
	if (!xr_debug_mirror_source_filter_is_set()) {
		return true;
	}
	return xr_debug_env_token_enabled("MERCURY_XR_DEBUG_MIRROR_SOURCE_TAG", source_tag);
}

static bool
xr_env_i64(const char *name, int64_t *out)
{
	const char *v = std::getenv(name);
	if (v == nullptr || v[0] == '\0') {
		return false;
	}
	char *end = nullptr;
	errno = 0;
	long long parsed = std::strtoll(v, &end, 10);
	if (errno != 0 || end == v || (end != nullptr && *end != '\0')) {
		return false;
	}
	*out = (int64_t)parsed;
	return true;
}

static bool
xr_i64_in_window(const char *center_name, const char *radius_name, int64_t value)
{
	int64_t center = 0;
	if (!xr_env_i64(center_name, &center)) {
		return true;
	}

	int64_t radius = 0;
	(void)xr_env_i64(radius_name, &radius);
	if (radius < 0) {
		radius = 0;
	}

	const int64_t diff = value >= center ? value - center : center - value;
	return diff <= radius;
}

static bool
xr_debug_dump_frame_allowed(struct HandTracking *hgt, bool left_active, bool right_active)
{
	if (xr_env_is_enabled("MERCURY_XR_DEBUG_DUMP_ACTIVE_ONLY", false) && !(left_active || right_active)) {
		return false;
	}

	if (!xr_i64_in_window("MERCURY_XR_DEBUG_DUMP_DET_CENTER", "MERCURY_XR_DEBUG_DUMP_DET_RADIUS",
	                         (int64_t)hgt->detection_counter)) {
		return false;
	}

	if (!xr_i64_in_window("MERCURY_XR_DEBUG_DUMP_TIMESTAMP_CENTER",
	                         "MERCURY_XR_DEBUG_DUMP_TIMESTAMP_RADIUS_NS",
	                         (int64_t)hgt->current_frame_timestamp)) {
		return false;
	}

	int64_t dump_index = 0;
	if (xr_env_i64("MERCURY_XR_DEBUG_DUMP_INDEX_CENTER", &dump_index)) {
		int64_t current_dump_index = 0;
		if (!xr_env_i64("MERCURY_XR_CURRENT_DUMP_INDEX", &current_dump_index)) {
			return false;
		}
		if (!xr_i64_in_window("MERCURY_XR_DEBUG_DUMP_INDEX_CENTER", "MERCURY_XR_DEBUG_DUMP_INDEX_RADIUS",
		                         current_dump_index)) {
			return false;
		}
	}

	return true;
}

static std::string
xr_debug_safe_env_suffix(const char *name)
{
	const char *v = std::getenv(name);
	if (v == nullptr || v[0] == '\0') {
		return "";
	}

	std::string out;
	for (const char *p = v; *p != '\0'; ++p) {
		const char c = *p;
		if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' ||
		    c == '-') {
			out.push_back(c);
		} else {
			out.push_back('_');
		}
	}
	return out;
}

static bool
xr_debug_draw_joint_valid(const cv::Mat &mat, const xrt_vec2 &pt)
{
	return std::isfinite(pt.x) && std::isfinite(pt.y) && pt.x >= 0.0f && pt.y >= 0.0f &&
	       pt.x < (float)mat.cols && pt.y < (float)mat.rows;
}

static cv::Scalar
xr_debug_hand_color(int hand_idx)
{
	// OpenCV uses BGR. Slot 0 is cyan-ish, slot 1 is blue-ish to match the existing ROI colors.
	return hand_idx == 0 ? cv::Scalar(255, 220, 0) : cv::Scalar(255, 70, 70);
}

static cv::Scalar
xr_debug_text_color()
{
	return cv::Scalar(255, 255, 255);
}

static void
xr_debug_draw_line(cv::Mat &mat, const xrt_vec2 &a, const xrt_vec2 &b, cv::Scalar color, int thickness)
{
	if (!xr_debug_draw_joint_valid(mat, a) || !xr_debug_draw_joint_valid(mat, b)) {
		return;
	}

	cv::line(mat, cv::Point((int)std::lround(a.x), (int)std::lround(a.y)),
	         cv::Point((int)std::lround(b.x), (int)std::lround(b.y)), color, thickness, cv::LINE_AA);
}

static void
xr_debug_draw_text(cv::Mat &mat, const xrt_vec2 &pt, const std::string &text, cv::Scalar color, double scale)
{
	if (!xr_debug_draw_joint_valid(mat, pt)) {
		return;
	}

	cv::Point org((int)std::lround(pt.x) + 4, (int)std::lround(pt.y) - 4);
	cv::putText(mat, text, org + cv::Point(1, 1), cv::FONT_HERSHEY_SIMPLEX, scale, cv::Scalar(0, 0, 0), 2,
	            cv::LINE_AA);
	cv::putText(mat, text, org, cv::FONT_HERSHEY_SIMPLEX, scale, color, 1, cv::LINE_AA);
}

static bool
xr_debug_joint_valid_no_bounds(const xrt_vec2 &pt)
{
	return std::isfinite(pt.x) && std::isfinite(pt.y);
}

static bool
xr_debug_mirror_anchor(const xrt_vec2 keypoints[21], const bool invalid[21], xrt_vec2 *out_anchor)
{
	const char *anchor_env = std::getenv("MERCURY_XR_DEBUG_MIRROR_ANCHOR");
	const char *anchor = (anchor_env == nullptr || anchor_env[0] == '\0') ? "palm" : anchor_env;

	if (strcasecmp(anchor, "wrist") == 0) {
		if (!invalid[0] && xr_debug_joint_valid_no_bounds(keypoints[0])) {
			*out_anchor = keypoints[0];
			return true;
		}
		return false;
	}

	if (strcasecmp(anchor, "bbox") == 0) {
		bool any = false;
		xrt_vec2 mn = {FLT_MAX, FLT_MAX};
		xrt_vec2 mx = {-FLT_MAX, -FLT_MAX};
		for (int i = 0; i < 21; i++) {
			if (invalid[i] || !xr_debug_joint_valid_no_bounds(keypoints[i])) {
				continue;
			}
			any = true;
			mn.x = fminf(mn.x, keypoints[i].x);
			mn.y = fminf(mn.y, keypoints[i].y);
			mx.x = fmaxf(mx.x, keypoints[i].x);
			mx.y = fmaxf(mx.y, keypoints[i].y);
		}
		if (!any) {
			return false;
		}
		*out_anchor = m_vec2_mul_scalar(mn + mx, 0.5f);
		return true;
	}

	// Default: approximate palm center using wrist and MCP joints. This is usually more stable than bbox during
	// curled/fist poses and more centered than the wrist alone.
	static constexpr int palm_joints[] = {0, 5, 9, 13, 17};
	xrt_vec2 sum = {0.0f, 0.0f};
	int count = 0;
	for (const int idx : palm_joints) {
		if (invalid[idx] || !xr_debug_joint_valid_no_bounds(keypoints[idx])) {
			continue;
		}
		sum = sum + keypoints[idx];
		count++;
	}
	if (count == 0) {
		return false;
	}
	*out_anchor = m_vec2_mul_scalar(sum, 1.0f / (float)count);
	return true;
}

static void
xr_debug_mirror_joints_for_overlay(const xrt_vec2 input[21], const bool invalid[21], xrt_vec2 output[21])
{
	for (int i = 0; i < 21; i++) {
		output[i] = input[i];
	}

	xrt_vec2 anchor = {};
	if (!xr_debug_mirror_anchor(input, invalid, &anchor)) {
		return;
	}

	const char *axis_env = std::getenv("MERCURY_XR_DEBUG_MIRROR_AXIS");
	const char *axis = (axis_env == nullptr || axis_env[0] == '\0') ? "x" : axis_env;
	const bool mirror_x = strcasecmp(axis, "x") == 0 || strcasecmp(axis, "xy") == 0 || strcasecmp(axis, "both") == 0;
	const bool mirror_y = strcasecmp(axis, "y") == 0 || strcasecmp(axis, "xy") == 0 || strcasecmp(axis, "both") == 0;

	for (int i = 0; i < 21; i++) {
		if (invalid[i] || !xr_debug_joint_valid_no_bounds(input[i])) {
			continue;
		}
		if (mirror_x) {
			output[i].x = 2.0f * anchor.x - input[i].x;
		}
		if (mirror_y) {
			output[i].y = 2.0f * anchor.y - input[i].y;
		}
	}
}

static void
xr_debug_draw_joint_overlay(cv::Mat &mat,
                              const xrt_vec2 keypoints[21],
                              const bool invalid[21],
                              int hand_idx,
                              const char *source_tag)
{
	const cv::Scalar hand_color = xr_debug_hand_color(hand_idx);
	const cv::Scalar text_color = xr_debug_text_color();

	xrt_vec2 display_keypoints[21] = {};
	for (int i = 0; i < 21; i++) {
		display_keypoints[i] = keypoints[i];
	}

	const bool mirror_overlay = xr_debug_mirror_overlay_for_slot(hand_idx) &&
	                            xr_debug_mirror_overlay_for_source(source_tag);
	if (mirror_overlay) {
		xr_debug_mirror_joints_for_overlay(keypoints, invalid, display_keypoints);
	}

	if (xr_debug_draw_skeleton_enabled()) {
		static constexpr int bones[][2] = {
		    // Thumb
		    {0, 1}, {1, 2}, {2, 3}, {3, 4},
		    // Index
		    {0, 5}, {5, 6}, {6, 7}, {7, 8},
		    // Middle
		    {0, 9}, {9, 10}, {10, 11}, {11, 12},
		    // Ring
		    {0, 13}, {13, 14}, {14, 15}, {15, 16},
		    // Little
		    {0, 17}, {17, 18}, {18, 19}, {19, 20},
		    // Palm cross-links. These are useful for visually checking finger order/handedness.
		    {5, 9}, {9, 13}, {13, 17},
		};

		for (const auto &bone : bones) {
			const int a = bone[0];
			const int b = bone[1];
			if (invalid[a] || invalid[b]) {
				continue;
			}
			xr_debug_draw_line(mat, display_keypoints[a], display_keypoints[b], hand_color, 1);
		}
	}

	if (xr_debug_draw_joint_numbers_enabled()) {
		for (int i = 0; i < 21; i++) {
			if (invalid[i]) {
				continue;
			}
			xr_debug_draw_text(mat, display_keypoints[i], std::to_string(i), text_color, 0.35);
		}
	}

	// Label the slot near wrist joint 0. This helps when checking whether slot0/slot1 or L/R got swapped.
	if (!invalid[0]) {
		std::ostringstream label;
		label << source_tag << " slot" << hand_idx;
		if (mirror_overlay) {
			label << " MIR";
		}
		xr_debug_draw_text(mat, display_keypoints[0], label.str(), hand_color, 0.45);
	}
}

// Flags to tell state tracker that these are indeed valid joints
static const enum xrt_space_relation_flags valid_flags_ht = (enum xrt_space_relation_flags)(
    XRT_SPACE_RELATION_ORIENTATION_VALID_BIT | XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT |
    XRT_SPACE_RELATION_POSITION_VALID_BIT | XRT_SPACE_RELATION_POSITION_TRACKED_BIT);

/*!
 * Setup helper functions.
 */

static bool
getCalibration(struct HandTracking *hgt, t_stereo_camera_calibration &calibration)
{
	xrt_vec3 trans = {
	    (float)calibration.camera_translation[0],
	    (float)calibration.camera_translation[1],
	    (float)calibration.camera_translation[2],
	};

	if (hgt->log_level <= U_LOGGING_DEBUG) {
		HG_DEBUG(hgt, "Dumping full camera calibration!");
		t_stereo_camera_calibration_dump(&calibration);
	}

	hgt->baseline = m_vec3_len(trans);
	HG_DEBUG(hgt, "I think the baseline is %f meters!", hgt->baseline);

	{
		// Officially, I have no idea if this is row-major or col-major. Empirically it seems to work, and that
		// is all I will say.

		// It might be that the below is *transposing* the matrix, I never remember if OpenCV's R is "left in
		// right" or "right in left"
		//!@todo
		xrt_matrix_3x3 s;

		s.v[0] = (float)calibration.camera_rotation[0][0];
		s.v[1] = (float)calibration.camera_rotation[1][0];
		s.v[2] = (float)calibration.camera_rotation[2][0];

		s.v[3] = (float)calibration.camera_rotation[0][1];
		s.v[4] = (float)calibration.camera_rotation[1][1];
		s.v[5] = (float)calibration.camera_rotation[2][1];

		s.v[6] = (float)calibration.camera_rotation[0][2];
		s.v[7] = (float)calibration.camera_rotation[1][2];
		s.v[8] = (float)calibration.camera_rotation[2][2];

		xrt_pose left_in_right;
		left_in_right.position = trans;

		math_quat_from_matrix_3x3(&s, &left_in_right.orientation);

		//! @todo what are these magic values?
		//! they're probably turning the OpenCV formalism into OpenXR, but especially what gives with negating
		//! orientation.x?
		left_in_right.orientation.x = -left_in_right.orientation.x;
		left_in_right.position.y = -left_in_right.position.y;
		left_in_right.position.z = -left_in_right.position.z;

		hgt->left_in_right = left_in_right;

		HG_DEBUG(hgt, "left_in_right pose: %f %f %f   %f %f %f %f",                            //
		         left_in_right.position.x, left_in_right.position.y, left_in_right.position.z, //
		         left_in_right.orientation.x, left_in_right.orientation.y, left_in_right.orientation.z,
		         left_in_right.orientation.w);
	}

	for (int view_idx = 0; view_idx < 2; view_idx++) {
		ht_view &view = hgt->views[view_idx];
		t_camera_model_params_from_t_camera_calibration(&calibration.view[view_idx], &view.hgdist_orig);

		view.hgdist = view.hgdist_orig;
	}

	//!@todo Really? We can totally support cameras with varying resolutions.
	// For a later MR.
	hgt->calibration_one_view_size_px.w = calibration.view[0].image_size_pixels.w;
	hgt->calibration_one_view_size_px.h = calibration.view[0].image_size_pixels.h;

	hgt->last_frame_one_view_size_px = hgt->calibration_one_view_size_px;
	hgt->multiply_px_coord_for_undistort = 1.0f;

	hgt->hand_pose_camera_offset = XRT_POSE_IDENTITY;
	return true;
}

static inline bool
check_outside_view(struct HandTracking *hgt, struct t_camera_extra_info_one_view boundary, xrt_vec2 &keypoint)
{
	// Regular case - the keypoint is literally outside the image
	if (keypoint.y > hgt->last_frame_one_view_size_px.h || //
	    keypoint.y < 0 ||                                  //
	    keypoint.x > hgt->last_frame_one_view_size_px.w || //
	    keypoint.x < 0) {
		return true;
	}

	switch (boundary.boundary_type) {
	// No boundary, and we passed the previous check. Not outside the view.
	case HT_IMAGE_BOUNDARY_NONE: return false; break;
	case HT_IMAGE_BOUNDARY_CIRCLE: {
		//!@todo Optimize:  Most of this can be calculated once at startup
		xrt_vec2 center_px = {
		    boundary.boundary.circle.normalized_center.x * (float)hgt->last_frame_one_view_size_px.w, //
		    boundary.boundary.circle.normalized_center.y * (float)hgt->last_frame_one_view_size_px.h};
		float radius_px =
		    boundary.boundary.circle.normalized_radius * (float)hgt->last_frame_one_view_size_px.w;

		xrt_vec2 keypoint_xrt = {float(keypoint.x), float(keypoint.y)};

		xrt_vec2 diff = center_px - keypoint_xrt;
		if (m_vec2_len(diff) > radius_px) {
			return true;
		}
	} break;
	}

	return false;
}

static void
back_project(struct HandTracking *hgt,        //
             Eigen::Array<float, 3, 21> &pts, //
             int hand_idx,                    //
             bool also_debug_output,          //
             int num_outside[2])
{

	for (int view_idx = 0; view_idx < 2; view_idx++) {
		cv::Mat debug = hgt->views[view_idx].debug_out_to_this;
		xrt_pose move_amount = {};

		if (view_idx == 0) {
			// left camera.
			move_amount = XRT_POSE_IDENTITY;
		} else {
			move_amount = hgt->left_in_right;
		}

		Eigen::Vector3f p = map_vec3(move_amount.position);
		Eigen::Quaternionf q = map_quat(move_amount.orientation);

		Eigen::Array<float, 3, 21> pts_relative_to_camera = {};

		bool invalid[21] = {};

		for (int i = 0; i < 21; i++) {
			pts_relative_to_camera.col(i) = (q * pts.col(i)) + p;

			if (pts_relative_to_camera.col(i).z() > 0) {
				invalid[i] = true;
			}
		}

		xrt_vec2 keypoints_global[21];

		for (int i = 0; i < 21; i++) {
			invalid[i] =
			    invalid[i] || !t_camera_models_flip_and_project(&hgt->views[view_idx].hgdist,      //
			                                                    pts_relative_to_camera.col(i).x(), //
			                                                    pts_relative_to_camera.col(i).y(), //
			                                                    pts_relative_to_camera.col(i).z(), //
			                                                    &keypoints_global[i].x,            //
			                                                    &keypoints_global[i].y             //
			                  );
		}

		for (int i = 0; i < 21; i++) {
			invalid[i] = invalid[i] ||
			             check_outside_view(hgt, hgt->views[view_idx].camera_info, keypoints_global[i]);
		}

		if (num_outside != NULL) {
			num_outside[view_idx] = 0;
			for (int i = 0; i < 21; i++) {
				if (invalid[i]) {
					num_outside[view_idx]++;
				}
			}

			xrt_vec2 min = keypoints_global[0];
			xrt_vec2 max = keypoints_global[0];

			for (int i = 0; i < 21; i++) {
				xrt_vec2 &pt = keypoints_global[i];
				min.x = fmin(pt.x, min.x);
				min.y = fmin(pt.y, min.y);

				max.x = fmax(pt.x, max.x);
				max.y = fmax(pt.y, max.y);
			}
			xrt_vec2 center = m_vec2_mul_scalar(min + max, 0.5);

			float r = fmax(center.x - min.x, center.y - min.y);

			float size = r * 2;


			hgt->views[view_idx].regions_of_interest_this_frame[hand_idx].center_px = center;
			hgt->views[view_idx].regions_of_interest_this_frame[hand_idx].size_px = size;
			if (also_debug_output) {
				handSquare(debug, center, size, GREEN);
			}
		}



		if (also_debug_output) {
			for (int i = 0; i < 21; i++) {
				xrt_vec2 loc;
				loc.x = keypoints_global[i].x;
				loc.y = keypoints_global[i].y;
				handDot(debug, loc, 2, invalid[i] ? 0.0 : (float)(i) / 26.0, 1, 2);
			}

			if (xr_debug_draw_optimizer_enabled()) {
				xr_debug_draw_joint_overlay(debug, keypoints_global, invalid, hand_idx, "opt");
			}
		}
	} // for view_idx
}

static void
back_project_keypoint_output(struct HandTracking *hgt, //
                             int hand_idx,             //
                             int view_idx)
{

	cv::Mat debug = hgt->views[view_idx].debug_out_to_this;
	one_frame_one_view &view = hgt->keypoint_outputs[hand_idx].views[view_idx];

	xrt_vec2 keypoints_global[21] = {};
	bool invalid[21] = {};

	for (int i = 0; i < 21; i++) {

		//!@todo We're trivially rewriting the stereographic projection for like the 2nd or 3rd time here. We
		//! should add an Eigen template for this instead.

		xrt_vec2 dir_sg =
		    m_vec2_mul_scalar(view.keypoints_in_scaled_stereographic[i].pos_2d, view.stereographic_radius);

		float denom = (1 + dir_sg.x * dir_sg.x + dir_sg.y * dir_sg.y);
		xrt_vec3 dir = {};
		dir.x = 2 * dir_sg.x / denom;
		dir.y = 2 * dir_sg.y / denom;
		dir.z = (-1 + (dir_sg.x * dir_sg.x) + (dir_sg.y * dir_sg.y)) / denom;

		math_quat_rotate_vec3(&view.look_dir, &dir, &dir);

		xrt_vec2 loc = {};
		bool projected = t_camera_models_flip_and_project(&hgt->views[view_idx].hgdist, //
		                                                 dir.x,                        //
		                                                 dir.y,                        //
		                                                 dir.z,                        //
		                                                 &loc.x,                       //
		                                                 &loc.y                        //
		);

		keypoints_global[i] = loc;
		invalid[i] = !projected || !xr_debug_draw_joint_valid(debug, loc);

		handDot(debug, loc, 2, (float)(i) / 26.0, 1, 2);
	}

	if (xr_debug_draw_keypoints_enabled()) {
		xr_debug_draw_joint_overlay(debug, keypoints_global, invalid, hand_idx, "kp");
	}
}

static bool
handle_changed_image_size(HandTracking *hgt, xrt_size &new_one_view_size)
{
	int gcd_calib = std::gcd(hgt->calibration_one_view_size_px.h, hgt->calibration_one_view_size_px.w);
	int gcd_new = std::gcd(new_one_view_size.h, new_one_view_size.w);

	int lcm_h_calib = hgt->calibration_one_view_size_px.h / gcd_calib;
	int lcm_w_calib = hgt->calibration_one_view_size_px.w / gcd_calib;

	int lcm_h_new = new_one_view_size.h / gcd_new;
	int lcm_w_new = new_one_view_size.w / gcd_new;

	bool good = (lcm_h_calib == lcm_h_new) && (lcm_w_calib == lcm_w_new);

	if (!good) {
		HG_WARN(hgt, "Can't process this frame, wrong aspect ratio. What we wanted: %dx%d, what we got: %dx%d",
		        lcm_h_calib, lcm_w_calib, lcm_h_new, lcm_w_new);
		return false;
	}

	//!@todo optimize: can't we just scale camera matrix/etc correctly?
	hgt->multiply_px_coord_for_undistort = (float)hgt->calibration_one_view_size_px.h / (float)new_one_view_size.h;
	hgt->last_frame_one_view_size_px = new_one_view_size;

	for (int view_idx = 0; view_idx < 2; view_idx++) {
		hgt->views[view_idx].hgdist.fx =
		    hgt->views[view_idx].hgdist_orig.fx / hgt->multiply_px_coord_for_undistort;
		hgt->views[view_idx].hgdist.fy =
		    hgt->views[view_idx].hgdist_orig.fy / hgt->multiply_px_coord_for_undistort;

		hgt->views[view_idx].hgdist.cx =
		    hgt->views[view_idx].hgdist_orig.cx / hgt->multiply_px_coord_for_undistort;
		hgt->views[view_idx].hgdist.cy =
		    hgt->views[view_idx].hgdist_orig.cy / hgt->multiply_px_coord_for_undistort;
	}
	return true;
}

float
hand_confidence_value(float reprojection_error, one_frame_input &input)
{
	float out_confidence = 0.0f;
	for (int view_idx = 0; view_idx < 2; view_idx++) {
		for (int i = 0; i < 21; i++) {
			// whatever
			out_confidence += input.views[view_idx].keypoints_in_scaled_stereographic[i].confidence_xy;
		}
	}
	out_confidence /= 42.0f; // number of hand joints
	float reproj_err_mul = 1.0f / ((reprojection_error * 10) + 1.0f);
	return out_confidence * reproj_err_mul;
}


xrt_vec3
correct_direction(xrt_vec2 in)
{
	xrt_vec3 out = {in.x, -in.y, -1};
	return m_vec3_normalize(out);
}

void
check_new_user_event(struct HandTracking *hgt)
{
	if (hgt->tuneable_values.new_user_event) {
		hgt->tuneable_values.new_user_event = false;
		hgt->hand_seen_before[0] = false;
		hgt->hand_seen_before[1] = false;
		hgt->refinement.hand_size_refinement_schedule_x = 0;
		hgt->refinement.optimizing = true;
		hgt->target_hand_size = STANDARD_HAND_SIZE;
	}
}



static float
hand_bounding_boxes_iou(const hand_region_of_interest &one, const hand_region_of_interest &two)
{
	if (!one.found || !two.found) {
		return -1;
	}
	box_iou::Box this_box(one.center_px, one.size_px);
	box_iou::Box other_box(two.center_px, two.size_px);

	return boxIOU(this_box, other_box);
}

static bool
xr_env_flag_enabled(const char *name)
{
	const char *value = getenv(name);
	if (value == nullptr || value[0] == '\0') {
		return false;
	}
	return strcmp(value, "0") != 0 && strcmp(value, "false") != 0 && strcmp(value, "FALSE") != 0;
}

static void
xr_detector_debug_write_header_if_needed(HandTracking *hgt)
{
	if (!hgt->xr_detector_debug_enabled) {
		return;
	}

	if (hgt->xr_detector_debug_file == nullptr) {
		hgt->xr_detector_debug_file = fopen(hgt->xr_detector_debug_csv, "w");
		if (hgt->xr_detector_debug_file == nullptr) {
			HG_WARN(hgt, "Failed to open MERCURY_XR_DETECTOR_DEBUG_CSV='%s'", hgt->xr_detector_debug_csv);
			hgt->xr_detector_debug_enabled = false;
			return;
		}
	}

	if (hgt->xr_detector_debug_header_written) {
		return;
	}

	fprintf(hgt->xr_detector_debug_file,
	        "timestamp_ns,detection_counter,num_views,active_camera,no_hands_detected_last_frame,"
	        "last_left_detected,last_right_detected,hand_idx,"
	        "view0_found,view0_conf_bool,view0_raw_conf,view0_raw_cx,view0_raw_cy,view0_raw_size,"
	        "view0_center_x,view0_center_y,view0_size_px,"
	        "view1_found,view1_conf_bool,view1_raw_conf,view1_raw_cx,view1_raw_cy,view1_raw_size,"
	        "view1_center_x,view1_center_y,view1_size_px,"
	        "confidence_sum_bool,raw_conf_avg,conf_gate_pass,attempt_replace,good_to_go,"
	        "reject_reason,reject_view,reject_iou,accepted,this_frame_detected,roi0_found_after,roi1_found_after\n");
	fflush(hgt->xr_detector_debug_file);
	hgt->xr_detector_debug_header_written = true;
}

static void
xr_detector_debug_write_row(HandTracking *hgt,
                               const hand_detection_run_info infos[2],
                               int hand_idx,
                               int num_views,
                               size_t active_camera,
                               bool no_hands_detected_last_frame,
                               float confidence_sum,
                               float raw_conf_avg,
                               bool conf_gate_pass,
                               bool attempt_replace,
                               bool good_to_go,
                               int reject_reason,
                               int reject_view,
                               float reject_iou,
                               bool accepted)
{
	if (!hgt->xr_detector_debug_enabled) {
		return;
	}

	xr_detector_debug_write_header_if_needed(hgt);
	if (hgt->xr_detector_debug_file == nullptr) {
		return;
	}

	const hand_region_of_interest &v0 = infos[0].outputs[hand_idx];
	const hand_region_of_interest &v1 = infos[1].outputs[hand_idx];

	fprintf(hgt->xr_detector_debug_file,
	        "%llu,%d,%d,%zu,%d,%d,%d,%d,"
	        "%d,%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,"
	        "%d,%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,"
	        "%.9g,%.9g,%d,%d,%d,%d,%d,%.9g,%d,%d,%d,%d\n",
	        (unsigned long long)hgt->current_frame_timestamp, hgt->detection_counter, num_views, active_camera,
	        no_hands_detected_last_frame ? 1 : 0, hgt->last_frame_hand_detected[0] ? 1 : 0,
	        hgt->last_frame_hand_detected[1] ? 1 : 0, hand_idx,
	        v0.found ? 1 : 0, v0.hand_detection_confidence ? 1 : 0, v0.xr_raw_hand_exists, v0.xr_raw_cx,
	        v0.xr_raw_cy, v0.xr_raw_size, v0.center_px.x, v0.center_px.y, v0.size_px,
	        v1.found ? 1 : 0, v1.hand_detection_confidence ? 1 : 0, v1.xr_raw_hand_exists, v1.xr_raw_cx,
	        v1.xr_raw_cy, v1.xr_raw_size, v1.center_px.x, v1.center_px.y, v1.size_px,
	        confidence_sum, raw_conf_avg, conf_gate_pass ? 1 : 0, attempt_replace ? 1 : 0,
	        good_to_go ? 1 : 0, reject_reason, reject_view, reject_iou, accepted ? 1 : 0,
	        hgt->this_frame_hand_detected[hand_idx] ? 1 : 0,
	        hgt->views[0].regions_of_interest_this_frame[hand_idx].found ? 1 : 0,
	        hgt->views[1].regions_of_interest_this_frame[hand_idx].found ? 1 : 0);
}

void
dispatch_and_process_hand_detections(struct HandTracking *hgt)
{
	if (hgt->tuneable_values.always_run_detection_model) {
		// Pretend like nothing was detected last frame.
		for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
			hgt->this_frame_hand_detected[hand_idx] = false;

			hgt->history_hands[hand_idx].clear();
		}
	}

	hand_detection_run_info infos[2] = {};

	// Mega paranoia, should get optimized out.
	for (int view_idx = 0; view_idx < 2; view_idx++) {
		for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
			infos[view_idx].outputs[hand_idx].found = false;
			infos[view_idx].outputs[hand_idx].hand_detection_confidence = 0;
			infos[view_idx].outputs[hand_idx].provenance = ROIProvenance::HAND_DETECTION;
		}
	}

	infos[0].view = &hgt->views[0];
	infos[1].view = &hgt->views[1];

	bool no_hands_detected_last_frame = !(hgt->this_frame_hand_detected[0] || hgt->this_frame_hand_detected[1]);

	size_t active_camera = hgt->detection_counter++ % 2;

	int num_views = 0;

	if (hgt->tuneable_values.always_run_detection_model || hgt->refinement.optimizing ||
	    hgt->tuneable_values.detection_model_in_both_views) {
		u_worker_group_push(hgt->group, run_hand_detection, &infos[0]);
		u_worker_group_push(hgt->group, run_hand_detection, &infos[1]);
		num_views = 2;
		u_worker_group_wait_all(hgt->group);
	} else {
		run_hand_detection(&infos[active_camera]);
		num_views = 1;
	}

	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		float confidence_sum = (infos[0].outputs[hand_idx].hand_detection_confidence +
		                        infos[1].outputs[hand_idx].hand_detection_confidence) /
		                       float(num_views);
		float raw_conf_avg = (infos[0].outputs[hand_idx].xr_raw_hand_exists +
		                      infos[1].outputs[hand_idx].xr_raw_hand_exists) /
		                     float(num_views);

		bool conf_gate_pass = confidence_sum >= 0.92f;
		// libultra_hands: while the detector runs in both views, this gate lets a hand begin only when both views
		// found it in the same frame. A hand low over a desk is found through the tiles in one view at a time
		// (traced on the wearer's recording of 20 November: the right hand passed its second looks eleven times
		// in one view between 25.9 and 27.6 s and never in both at once). A find that passed its second looks
		// twice may begin the hand from its one view; the other view gets a region from the fit.
		// ULTRA_HANDS_ONE_VIEW=0 is the gate as it was.
		{
			const bool one_view_on = []() {
				const char *e = std::getenv("ULTRA_HANDS_ONE_VIEW");
				return e == nullptr || e[0] != '0';
			}();
			const auto &o0 = infos[0].outputs[hand_idx];
			const auto &o1 = infos[1].outputs[hand_idx];
			if (one_view_on && !conf_gate_pass && num_views == 2 && (o0.found != o1.found) &&
			    ((o0.found && o0.ultra_twice) || (o1.found && o1.ultra_twice))) {
				conf_gate_pass = true;
			}
		}
		bool attempt_replace = false;
		bool good_to_go = false;
		bool accepted = false;
		int reject_reason = 0; // 0=none, 1=confidence_gate, 2=double_iou, 3=single_iou, 4=already_tracked_no_replace
		int reject_view = -1;
		float reject_iou = -1.0f;

		if (!conf_gate_pass) {
			reject_reason = 1;
			xr_detector_debug_write_row(hgt, infos, hand_idx, num_views, active_camera,
			                               no_hands_detected_last_frame, confidence_sum, raw_conf_avg,
			                               conf_gate_pass, attempt_replace, good_to_go, reject_reason,
			                               reject_view, reject_iou, accepted);
			continue;
		}

		if (hgt->tuneable_values.always_run_detection_model || !hgt->last_frame_hand_detected[hand_idx]) {
			attempt_replace = true;
			good_to_go = true;

			if (no_hands_detected_last_frame) {
				// Stop overlapping _double_ hand detections - detecting both hands in the same place.
				// This happens a lot if you put your hands together (we can't track intertwining hands
				// yet)
				for (int view_idx = 0; view_idx < 2; view_idx++) {
					float iou = hand_bounding_boxes_iou(infos[view_idx].outputs[hand_idx],
					                                    infos[view_idx].outputs[!hand_idx]);
					if (iou > hgt->tuneable_values.mpiou_double_detection.val) {
						HG_DEBUG(hgt,
						         "Rejected double detection because the iou for hand idx %d, "
						         "view idx "
						         "%d was %f",
						         hand_idx, view_idx, iou);
						good_to_go = false;
						reject_reason = 2;
						reject_view = view_idx;
						reject_iou = iou;
						break;
					}
				}
			} else {
				// Stop overlapping _single_ hand detections - detecting one hand where another hand is
				// already tracked. This happens a lot if you trick the hand detector into thinking your
				// left hand is a right hand.
				for (int view_idx = 0; view_idx < 2; view_idx++) {
					hand_region_of_interest &this_state = infos[view_idx].outputs[hand_idx];

					// Note that this is not just the other state
					hand_region_of_interest &other_state =
					    hgt->views[view_idx].regions_of_interest_this_frame[!hand_idx];
					float iou = hand_bounding_boxes_iou(this_state, other_state);
					if (iou > hgt->tuneable_values.mpiou_single_detection.val) {
						HG_DEBUG(hgt,
						         "Rejected single detection because the iou for hand idx %d, "
						         "view idx "
						         "%d was %f",
						         hand_idx, view_idx, iou);
						good_to_go = false;
						reject_reason = 3;
						reject_view = view_idx;
						reject_iou = iou;
						break;
					}
				}
			}

			if (good_to_go) {
				// Note we already initialized the previous-keypoints-input to nonexistent above this.
				hgt->views[0].regions_of_interest_this_frame[hand_idx] = infos[0].outputs[hand_idx];
				hgt->views[1].regions_of_interest_this_frame[hand_idx] = infos[1].outputs[hand_idx];
				accepted = true;
				ultra_begun_by_stereo[hand_idx] = infos[0].outputs[hand_idx].ultra_stereo || infos[1].outputs[hand_idx].ultra_stereo;
				ultra_begun_by_tile[hand_idx] = infos[0].outputs[hand_idx].ultra_tile || infos[1].outputs[hand_idx].ultra_tile;
				for (int v = 0; v < 2; v++) {
					ultra_begin_px[hand_idx][v] = infos[v].outputs[hand_idx].center_px;
				}
			}
		} else {
			reject_reason = 4;
		}

		hgt->this_frame_hand_detected[hand_idx] = true;
		xr_detector_debug_write_row(hgt, infos, hand_idx, num_views, active_camera,
		                               no_hands_detected_last_frame, confidence_sum, raw_conf_avg,
		                               conf_gate_pass, attempt_replace, good_to_go, reject_reason,
		                               reject_view, reject_iou, accepted);
	}
}

void
hand_joint_set_to_eigen_21(const xrt_hand_joint_set &set, Eigen::Array<float, 3, 21> &out)
{
	int acc_idx = 0;

	out.col(acc_idx++) = map_vec3(set.values.hand_joint_set_default[XRT_HAND_JOINT_WRIST].relation.pose.position);
	for (int finger = 0; finger < 5; finger++) {
		for (int joint = 1; joint < 5; joint++) {
			xrt_hand_joint j = joints_5x5_to_26[finger][joint];
			const xrt_vec3 &jp = set.values.hand_joint_set_default[j].relation.pose.position;
			out.col(acc_idx++) = map_vec3(jp);
		}
	}
}

// Most of the time, this codepath runs - we predict where the hand should be based on the last
// two frames.
void
predict_new_regions_of_interest(struct HandTracking *hgt)
{

	xrt_hand_masks_sample masks{}; // Zero initialization

	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		// If we don't have the past two frames, this code doesn't do what we want.
		// If we only have *one* frame, we just reuse the same bounding box and hope the hand
		// hasn't moved too much. @todo

		auto &hh = hgt->history_hands[hand_idx];


		if (hh.size() < 2) {
			HG_TRACE(hgt, "continuing, size is %zu", hgt->history_hands[hand_idx].size());
			continue;
		}

		// We can only do this *after* we know we're predicting - this would otherwise overwrite the detection
		// model.
		hgt->this_frame_hand_detected[hand_idx] = hgt->last_frame_hand_detected[hand_idx];

		uint64_t time_two_frames_ago = *hgt->history_timestamps.get_at_age(1);
		uint64_t time_one_frame_ago = *hgt->history_timestamps.get_at_age(0);
		uint64_t time_now = hgt->current_frame_timestamp;



		// double dt_past = (double)() / (double)U_TIME_1S_IN_NS;
		double dt_past = time_ns_to_s(time_one_frame_ago - time_two_frames_ago);

		double dt_now = time_ns_to_s(time_now - time_one_frame_ago);


		Eigen::Array<float, 3, 21> &n_minus_two = *hh.get_at_age(1);
		Eigen::Array<float, 3, 21> &n_minus_one = *hh.get_at_age(0);


		Eigen::Array<float, 3, 21> add;

		add = n_minus_one - n_minus_two;

		add *= (dt_now * hgt->tuneable_values.amount_to_lerp_prediction.val) / dt_past;

		hgt->pose_predicted_keypoints[hand_idx] = n_minus_one + add;
		if (hgt->xr_tracking_diag.enabled()) {
			auto &diag = hgt->xr_tracking_diag.frame.hands[hand_idx];
			diag.roi_dt_past_ms = dt_past * 1000;
			diag.roi_dt_now_ms = dt_now * 1000;
			diag.roi_prediction_multiplier = (dt_now * hgt->tuneable_values.amount_to_lerp_prediction.val) / dt_past;
			diag.predicted_wrist_x = hgt->pose_predicted_keypoints[hand_idx](0, 0);
			diag.predicted_wrist_y = hgt->pose_predicted_keypoints[hand_idx](1, 0);
			diag.predicted_wrist_z = hgt->pose_predicted_keypoints[hand_idx](2, 0);
		}


		int num_outside[2];
		back_project(hgt, hgt->pose_predicted_keypoints[hand_idx], hand_idx,
		             hgt->tuneable_values.scribble_predictions_into_next_frame && hgt->debug_scribble,
		             num_outside);

		for (int view_idx = 0; view_idx < 2; view_idx++) {
			hand_region_of_interest &hroi = hgt->views[view_idx].regions_of_interest_this_frame[hand_idx];
			auto &masks_view = masks.views[view_idx];
			auto &masks_hand = masks_view.hands[hand_idx];
			if (hgt->xr_tracking_diag.enabled()) {
				hgt->xr_tracking_diag.frame.hands[hand_idx].views[view_idx].predicted_outside_joints = num_outside[view_idx];
			}

			masks_view.enabled = true;

			if (num_outside[view_idx] < hgt->tuneable_values.max_num_outside_view) {
				hroi.provenance = ROIProvenance::POSE_PREDICTION;
				hroi.found = true;

				const float SCALER = 1.25f;
				float s = hroi.size_px * SCALER;
				xrt_vec2 &c = hroi.center_px;
				masks_hand.rect = xrt_rect_f32{c.x - s / 2, c.y - s / 2, s, s};
				masks_hand.enabled = true;
			} else {
				hroi.found = false;
				masks_hand.enabled = false;
			}
		}
	}

	if (hgt->hand_masks_sink != NULL) {
		xrt_sink_push_hand_masks(hgt->hand_masks_sink, &masks);
	}
}

//!@todo This looks like it sucks, but it doesn't given the current architecture.
// There are two distinct failure modes here:
// * One hand goes over the other hand, and we wish to discard the hand that is being obscured.
// * One hand "ate" the other hand: easiest way to see this is by putting your hands close together and shaking them
// around.
//
// If we were only concerned about the first one, we'd do some simple depth testing to figure out which one is
// closer to the hand and only discard the further-away hand. But the second one is such a common (and bad)
// failure mode that we really just need to stop tracking all hands if they start overlapping.

//!@todo I really want to try making a discrete optimizer that looks at recent info and decides whether to drop tracking
//! for a hand, switch its handedness or switch to some forthcoming overlapping-hands model. This would likely work by
//! pruning impossible combinations, calculating a loss for each remaining option and picking the least bad one.
void
stop_everything_if_hands_are_overlapping(struct HandTracking *hgt)
{
	bool ok = true;
	for (int view_idx = 0; view_idx < 2; view_idx++) {
		hand_region_of_interest left_box = hgt->views[view_idx].regions_of_interest_this_frame[0];
		hand_region_of_interest right_box = hgt->views[view_idx].regions_of_interest_this_frame[1];
		if (!left_box.found || !right_box.found) {
			continue;
		}
		box_iou::Box this_nbox(left_box.center_px, right_box.size_px);
		box_iou::Box other_nbox(right_box.center_px, right_box.size_px);
		float iou = box_iou::boxIOU(this_nbox, other_nbox);
		if (hgt->xr_tracking_diag.enabled()) {
			// Compare with the correct left-box size without changing the existing decision.
			box_iou::Box correct_left_box(left_box.center_px, left_box.size_px);
			const float correct_iou = box_iou::boxIOU(correct_left_box, other_nbox);
			for (auto &diag : hgt->xr_tracking_diag.frame.hands) {
				diag.views[view_idx].overlap_iou_used = iou;
				diag.views[view_idx].overlap_iou_correct_size = correct_iou;
				if (iou > hgt->tuneable_values.mpiou_any.val) diag.reject(xr_mercury_diag::overlap);
			}
		}
		if (iou > hgt->tuneable_values.mpiou_any.val) {
			HG_DEBUG(hgt, "Stopped tracking because iou was %f in view %d", iou, view_idx);
			ok = false;
			break;
		}
	}
	if (!ok) {
		for (int view_idx = 0; view_idx < 2; view_idx++) {
			for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
				hgt->views[view_idx].regions_of_interest_this_frame[hand_idx].found = false;
			}
		}
	}
}

bool
hand_too_far(struct HandTracking *hgt, xrt_hand_joint_set &set)
{
	xrt_vec3 dp = set.values.hand_joint_set_default[XRT_HAND_JOINT_PALM].relation.pose.position;
	return (m_vec3_len(dp) > hgt->tuneable_values.max_hand_dist.val);
}

void
scribble_image_boundary(struct HandTracking *hgt)
{
	for (int view_idx = 0; view_idx < 2; view_idx++) {
		struct ht_view *view = &hgt->views[view_idx];

		cv::Mat &debug_frame = view->debug_out_to_this;
		t_camera_extra_info_one_view &info = hgt->views[view_idx].camera_info;

		if (info.boundary_type == HT_IMAGE_BOUNDARY_CIRCLE) {
			int center_x = hgt->last_frame_one_view_size_px.w * info.boundary.circle.normalized_center.x;
			int center_y = hgt->last_frame_one_view_size_px.h * info.boundary.circle.normalized_center.y;
			cv::circle(debug_frame, {center_x, center_y},
			           info.boundary.circle.normalized_radius * hgt->last_frame_one_view_size_px.w,
			           hsv2rgb(270.0, 0.5, 0.5), 2);
		}
	}
}

/*
 *
 * Member functions.
 *
 */

HandTracking::HandTracking()
{
	this->base.process = &HandTracking::cCallbackProcess;
	this->base.destroy = &HandTracking::cCallbackDestroy;
	u_sink_debug_init(&this->debug_sink_ann);
	u_sink_debug_init(&this->debug_sink_model);
}

HandTracking::~HandTracking()
{
	u_sink_debug_destroy(&this->debug_sink_ann);
	u_sink_debug_destroy(&this->debug_sink_model);

	xrt_frame_reference(&this->visualizers.old_frame, NULL);

	// Stop all the workers before we release the ONNX state
	u_worker_group_reference(&this->group, NULL);
	// libultra_hands: upstream never drops its own pool reference, leaking the
	// pool and its threads on every destroy.
	u_worker_thread_pool_reference(&this->pool, NULL);

	release_onnx_state(&this->views[0].keypoint[0]);
	release_onnx_state(&this->views[0].keypoint[1]);
	release_onnx_state(&this->views[0].detection);

	release_onnx_state(&this->views[1].keypoint[0]);
	release_onnx_state(&this->views[1].keypoint[1]);
	release_onnx_state(&this->views[1].detection);

	t_stereo_camera_calibration_reference(&this->calib, NULL);

	lm::optimizer_destroy(&this->kinematic_hands[0]);
	lm::optimizer_destroy(&this->kinematic_hands[1]);

	u_var_remove_root((void *)&this->base);
	u_frame_times_widget_teardown(&this->ft_widget);

	if (this->xr_detector_debug_file != nullptr) {
		fclose(this->xr_detector_debug_file);
		this->xr_detector_debug_file = nullptr;
	}
}

static void
callback_process_unsafe(HandTracking *hgt,
                        struct xrt_frame *left_frame,
                        struct xrt_frame *right_frame,
                        struct xrt_hand_joint_set *out_left_hand,
                        struct xrt_hand_joint_set *out_right_hand,
                        int64_t *out_timestamp_ns)
{

	ultra_prof::Scope _prof_frame(ultra_prof::FRAME);
	struct xrt_hand_joint_set *out_xrt_hands[2] = {out_left_hand, out_right_hand};


	/*
	 * Setup views.
	 */

	assert(left_frame->width == right_frame->width);
	assert(left_frame->height == right_frame->height);

	const int full_height = left_frame->height;
	const int full_width = left_frame->width * 2;

	if ((left_frame->width != (uint32_t)hgt->last_frame_one_view_size_px.w) ||
	    (left_frame->height != (uint32_t)hgt->last_frame_one_view_size_px.h)) {
		xrt_size new_one_view_size;
		new_one_view_size.h = left_frame->height;
		new_one_view_size.w = left_frame->width;
		// Could be an assert, should never happen after first frame.
		if (!handle_changed_image_size(hgt, new_one_view_size)) {
			return;
		}
	}

	const int view_width = hgt->last_frame_one_view_size_px.w;
	const int view_height = hgt->last_frame_one_view_size_px.h;

	const cv::Size full_size = cv::Size(full_width, full_height);
	const cv::Size view_size = cv::Size(view_width, view_height);
	const cv::Point view_offsets[2] = {cv::Point(0, 0), cv::Point(view_width, 0)};

	hgt->views[0].run_model_on_this = cv::Mat(view_size, CV_8UC1, left_frame->data, left_frame->stride);
	hgt->views[1].run_model_on_this = cv::Mat(view_size, CV_8UC1, right_frame->data, right_frame->stride);

	const bool xr_force_debug_dump = xr_debug_dump_enabled();
	hgt->debug_scribble = xr_force_debug_dump ||
	                      (u_sink_debug_is_active(&hgt->debug_sink_ann) &&
	                       u_sink_debug_is_active(&hgt->debug_sink_model));

	if (xr_force_debug_dump) {
		// Dataset/realtime debug dump: force the useful Mercury scribbles even when the Monado UI sinks are not open.
		// The XR env toggles below allow keypoint-only, optimizer-only, or combined overlays.
		hgt->tuneable_values.scribble_keypoint_model_outputs = xr_debug_draw_keypoints_enabled();
		hgt->tuneable_values.scribble_optimizer_outputs = xr_debug_draw_optimizer_enabled();
		hgt->tuneable_values.scribble_predictions_into_next_frame = xr_debug_draw_predictions_enabled();
	}

	cv::Mat debug_output = {};
	xrt_frame *debug_frame = nullptr;

	// If we're outputting to a debug image, setup the image.
	if (hgt->debug_scribble) {
		u_frame_create_one_off(XRT_FORMAT_R8G8B8, full_width, full_height, &debug_frame);
		debug_frame->timestamp = hgt->current_frame_timestamp;

		debug_output = cv::Mat(full_size, CV_8UC3, debug_frame->data, debug_frame->stride);

		cv::cvtColor(hgt->views[0].run_model_on_this, debug_output(cv::Rect(view_offsets[0], view_size)),
		             cv::COLOR_GRAY2BGR);
		cv::cvtColor(hgt->views[1].run_model_on_this, debug_output(cv::Rect(view_offsets[1], view_size)),
		             cv::COLOR_GRAY2BGR);

		hgt->views[0].debug_out_to_this = debug_output(cv::Rect(view_offsets[0], view_size));
		hgt->views[1].debug_out_to_this = debug_output(cv::Rect(view_offsets[1], view_size));
		scribble_image_boundary(hgt);

		// Let's check that the collage size is actually as big as we think it is
		static_assert(1064 == (8 + ((128 + 8) * 4) + ((320 + 8)) + ((80 + 8) * 2) + 8));
		static_assert(504 == (240 + 240 + 8 + 8 + 8));
		static_assert(552 == (8 + (128 + 8) * 4));

		const int w = 1064;
		const int h = 552;

		u_frame_create_one_off(XRT_FORMAT_L8, w, h, &hgt->visualizers.xrtframe);
		hgt->visualizers.xrtframe->timestamp = hgt->current_frame_timestamp;

		cv::Size size = cv::Size(w, h);

		hgt->visualizers.mat =
		    cv::Mat(size, CV_8U, hgt->visualizers.xrtframe->data, hgt->visualizers.xrtframe->stride);

		if (hgt->visualizers.old_frame == NULL) {
			// There wasn't a previous frame so let's setup the background
			hgt->visualizers.mat = 255;
		} else {
			// They had better be the same size.
			memcpy(hgt->visualizers.xrtframe->data, hgt->visualizers.old_frame->data,
			       hgt->visualizers.old_frame->size);
			xrt_frame_reference(&hgt->visualizers.old_frame, NULL);
		}
	}

	check_new_user_event(hgt);
	if (hgt->xr_tracking_diag.enabled()) {
		auto &frame = hgt->xr_tracking_diag.frame;
		frame = {};
		frame.timestamp_ns = hgt->current_frame_timestamp;
		frame.camera0_ns = left_frame->timestamp;
		frame.camera1_ns = right_frame->timestamp;
		frame.width = view_width;
		frame.height = view_height;
		for (int hand_idx = 0; hand_idx < 2; ++hand_idx) {
			auto &diag = frame.hands[hand_idx];
			diag.previous_detected = hgt->last_frame_hand_detected[hand_idx];
			diag.history_frames = hgt->history_hands[hand_idx].size();
			diag.tracked_frames_before = hgt->hand_tracked_for_num_frames[hand_idx];
			diag.target_hand_size = hgt->target_hand_size;
			diag.outside_limit = hgt->tuneable_values.max_num_outside_view;
			diag.overlap_limit = hgt->tuneable_values.mpiou_any.val;
		}
	}



	// Every now and then if we're not already tracking both hands, try to detect new hands.
	bool saw_both_hands_last_frame = hgt->last_frame_hand_detected[0] && hgt->last_frame_hand_detected[1];
	// libultra_hands: upstream looks for a new hand in every frame in which it does not have both. With one hand
	// tracked, that is the detector run in every frame (and the tiles with it) for the sake of a second hand.
	// ULTRA_HANDS_DETECT_EVERY=N looks for the second hand in every N-th frame while one is tracked (1: as upstream);
	// with no hand tracked it still looks in every frame, so that a first hand is found as soon as it was.
	static int ultra_since_detect = 1000;
	const int ultra_every = []() {
		const char *e = std::getenv("ULTRA_HANDS_DETECT_EVERY");
		const int v = (e != nullptr && e[0] != '\0') ? std::atoi(e) : 1;
		return v < 1 ? 1 : v;
	}();
	if (!saw_both_hands_last_frame && (hgt->last_frame_hand_detected[0] || hgt->last_frame_hand_detected[1]) &&
	    ultra_every > 1 && ultra_since_detect + 1 < ultra_every) {
		ultra_since_detect++;
		saw_both_hands_last_frame = true;     // not this frame
	} else if (!saw_both_hands_last_frame) {
		ultra_since_detect = 0;
	}
	if (!saw_both_hands_last_frame) {
		if (hgt->xr_tracking_diag.enabled()) {
			for (auto &diag : hgt->xr_tracking_diag.frame.hands) diag.detector_ran = 1;
		}
		{
			ultra_prof::Scope _prof_det(ultra_prof::DETECT);
			dispatch_and_process_hand_detections(hgt);
		}
	}

	if (hgt->xr_tracking_diag.enabled()) {
		for (int hand_idx = 0; hand_idx < 2; ++hand_idx) {
			for (int view_idx = 0; view_idx < 2; ++view_idx) {
				const auto &roi = hgt->views[view_idx].regions_of_interest_this_frame[hand_idx];
				auto &diag = hgt->xr_tracking_diag.frame.hands[hand_idx].views[view_idx];
				diag.roi_before_overlap_found = roi.found;
				diag.roi_provenance = roi.provenance;
				diag.roi_cx = roi.center_px.x;
				diag.roi_cy = roi.center_px.y;
				diag.roi_size = roi.size_px;
			}
		}
	}
	stop_everything_if_hands_are_overlapping(hgt);

	//!@todo does this go here?
	// If no hand regions of interest were found anywhere, there's no hand - register that in the state
	// tracker
	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		if (hgt->xr_tracking_diag.enabled()) {
			for (int view_idx = 0; view_idx < 2; ++view_idx) {
				hgt->xr_tracking_diag.frame.hands[hand_idx].views[view_idx].roi_input_found =
				    hgt->views[view_idx].regions_of_interest_this_frame[hand_idx].found;
			}
		}
		if (!(hgt->views[0].regions_of_interest_this_frame[hand_idx].found ||
		      hgt->views[1].regions_of_interest_this_frame[hand_idx].found)) {
			hgt->this_frame_hand_detected[hand_idx] = false;
			ultra_drop("no_roi", hand_idx, (double)hgt->current_frame_timestamp / 1e9);
			if (hgt->xr_tracking_diag.enabled()) {
				hgt->xr_tracking_diag.frame.hands[hand_idx].reject(xr_mercury_diag::no_roi);
			}
		}
	}


	// Dispatch keypoint estimator neural nets
	const uint64_t _prof_kw = ultra_prof::now();
	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		for (int view_idx = 0; view_idx < 2; view_idx++) {
			if (!hgt->views[view_idx].regions_of_interest_this_frame[hand_idx].found) {
				continue;
			}

			struct keypoint_estimation_run_info &inf = hgt->views[view_idx].run_info[hand_idx];
			inf.view = &hgt->views[view_idx];
			inf.hand_idx = hand_idx;
			u_worker_group_push(hgt->group, hgt->keypoint_estimation_run_func,
			                    &hgt->views[view_idx].run_info[hand_idx]);
		}
	}
	u_worker_group_wait_all(hgt->group);
	if (ultra_prof::state().on) ultra_prof::add(ultra_prof::KP_WALL, ultra_prof::now() - _prof_kw);

	// Spaghetti logic for optimizing hand size
	if (hgt->xr_tracking_diag.enabled()) {
		for (int hand_idx = 0; hand_idx < 2; ++hand_idx) {
			for (int view_idx = 0; view_idx < 2; ++view_idx) {
				auto &diag = hgt->xr_tracking_diag.frame.hands[hand_idx].views[view_idx];
				const auto &kp = hgt->keypoint_outputs[hand_idx].views[view_idx];
				diag.keypoint_active = diag.roi_input_found == 1 && kp.active;
				// A failed model run must not report stale confidence from the preceding frame.
				if (diag.model_ran != 1 || !std::isfinite(diag.is_hand_probability)) continue;
				double xy_sum = 0, depth_sum = 0;
				double xy_min = std::numeric_limits<double>::infinity();
				double depth_min = std::numeric_limits<double>::infinity();
				for (int joint = 0; joint < 21; ++joint) {
					const auto &point = kp.keypoints_in_scaled_stereographic[joint];
					xy_sum += point.confidence_xy;
					depth_sum += point.confidence_depth;
					xy_min = std::min(xy_min, double(point.confidence_xy));
					depth_min = std::min(depth_min, double(point.confidence_depth));
				}
				diag.confidence_xy_mean = xy_sum / 21;
				diag.confidence_xy_min = xy_min;
				diag.confidence_depth_mean = depth_sum / 21;
				diag.confidence_depth_min = depth_min;
			}
		}
	}
	bool any_hands_are_only_visible_in_one_view = false;

	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		any_hands_are_only_visible_in_one_view =                             //
		    any_hands_are_only_visible_in_one_view ||                        //
		    (hgt->views[0].regions_of_interest_this_frame[hand_idx].found != //
		     hgt->views[1].regions_of_interest_this_frame[hand_idx].found);
	}

	constexpr float mul_max = 1.0;
	constexpr float frame_max = 100;
	bool optimize_hand_size;

	if ((hgt->refinement.hand_size_refinement_schedule_x > frame_max)) {
		hgt->refinement.hand_size_refinement_schedule_y = mul_max;
		optimize_hand_size = false;
		hgt->refinement.optimizing = false;
	} else {
		hgt->refinement.hand_size_refinement_schedule_y =
		    powf((hgt->refinement.hand_size_refinement_schedule_x / frame_max), 2) * mul_max;
		optimize_hand_size = true;
		hgt->refinement.optimizing = true;
	}

	if (any_hands_are_only_visible_in_one_view) {
		optimize_hand_size = false;
	}


	// if either hand was not visible before the last new-user event but is visible now, reset the schedule
	// a bit.
	if ((hgt->this_frame_hand_detected[0] && !hgt->hand_seen_before[0]) ||
	    (hgt->this_frame_hand_detected[1] && !hgt->hand_seen_before[1])) {
		hgt->refinement.hand_size_refinement_schedule_x =
		    std::min(hgt->refinement.hand_size_refinement_schedule_x, frame_max / 2);
	}

	optimize_hand_size = optimize_hand_size && hgt->tuneable_values.optimize_hand_size;

	int num_hands = 0;
	float avg_hand_size = 0;

	// Dispatch the optimizers!
	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {


		// libultra_hands: upstream drops the whole hand as soon as the keypoint model is less than 0.97 sure of
		// it in any one view. Low in the fisheye picture one camera's crop dips under that for a frame while
		// the other camera and the fit are fine (the wearer's recording, 18.2 s: 0.87 in one view, 1.00 in
		// the other, the hand gone for good). A hand that has been tracked for a while keeps going on the
		// view that is sure; the unsure view sits this frame out and is given a new region from the fit.
		// ULTRA_HANDS_KEEP=0 is upstream's rule.
		{
			const bool keep_on = []() {
				const char *e = std::getenv("ULTRA_HANDS_KEEP");
				return e == nullptr || e[0] != '0';
			}();
			const float keep_floor = []() {
				const char *e = std::getenv("ULTRA_HANDS_KEEP_FLOOR");
				return (e != nullptr && e[0] != '\0') ? (float)std::atof(e) : 0.30f;
			}();
			int n_roi = 0, n_sure = 0;
			float least = 1.0f;
			for (int view_idx = 0; view_idx < 2; view_idx++) {
				if (!hgt->views[view_idx].regions_of_interest_this_frame[hand_idx].found) {
					continue;
				}
				n_roi++;
				if (hgt->keypoint_outputs[hand_idx].views[view_idx].active) {
					n_sure++;
				} else {
					least = fminf(least, hgt->views[view_idx].ultra_is_hand[hand_idx]);
				}
			}
			const bool tracked_a_while = hgt->last_frame_hand_detected[hand_idx] &&
			                             hgt->hand_tracked_for_num_frames[hand_idx] >= 8;
			if (n_sure < n_roi) {
				if (keep_on && n_sure >= 1 && tracked_a_while && least >= keep_floor) {
					for (int view_idx = 0; view_idx < 2; view_idx++) {
						if (hgt->views[view_idx].regions_of_interest_this_frame[hand_idx].found &&
						    !hgt->keypoint_outputs[hand_idx].views[view_idx].active) {
							hgt->views[view_idx].regions_of_interest_this_frame[hand_idx].found = false;
						}
					}
				} else {
					HG_DEBUG(hgt, "Removing hand %d because keypoint estimator said to!", hand_idx);
					ultra_drop("keypoint_removed", hand_idx, (double)hgt->current_frame_timestamp / 1e9);
					hgt->this_frame_hand_detected[hand_idx] = false;
					if (hgt->xr_tracking_diag.enabled()) {
						hgt->xr_tracking_diag.frame.hands[hand_idx].reject(
						    xr_mercury_diag::keypoint_rejected);
					}
				}
			}
		}

		if (!hgt->this_frame_hand_detected[hand_idx]) {
			continue;
		}


		for (int view = 0; view < 2; view++) {
			hand_region_of_interest &from_model = hgt->views[view].regions_of_interest_this_frame[hand_idx];
			if (!from_model.found) {
				hgt->keypoint_outputs[hand_idx].views[view].active = false;
			}
		}

		xr_mercury_quality::Sample quality_sample;
		if (hgt->xr_quality_filter.enabled) {
			for (int view = 0; view < 2; ++view) {
				const auto &observation = hgt->keypoint_outputs[hand_idx].views[view];
				quality_sample.active[view] = observation.active;
				if (!observation.active) continue;
				double sum = 0;
				for (int joint = 0; joint < 21; ++joint) {
					sum += observation.keypoints_in_scaled_stereographic[joint].confidence_xy;
				}
				quality_sample.mean_xy[view] = sum / 21;
			}
			if (xr_mercury_quality::reject_keypoints(hgt->xr_quality_filter, quality_sample)) {
				hgt->this_frame_hand_detected[hand_idx] = false;
				ultra_drop("kp_quality", hand_idx, (double)hgt->current_frame_timestamp / 1e9);
				if (hgt->xr_tracking_diag.enabled()) {
					hgt->xr_tracking_diag.frame.hands[hand_idx].reject(xr_mercury_diag::quality_rejected);
				}
				continue;
			}
		}

		if (hgt->tuneable_values.scribble_keypoint_model_outputs && hgt->debug_scribble) {
			for (int view_idx = 0; view_idx < 2; view_idx++) {

				if (!hgt->keypoint_outputs[hand_idx].views[view_idx].active) {
					continue;
				}

				back_project_keypoint_output(hgt, hand_idx, view_idx);
			}
		}

		struct xrt_hand_joint_set *put_in_set = out_xrt_hands[hand_idx];

		lm::KinematicHandLM *hand = hgt->kinematic_hands[hand_idx];

		float reprojection_error_threshold = hgt->tuneable_values.max_reprojection_error.val;
		float smoothing_factor = hgt->tuneable_values.opt_smooth_factor.val;

		if (hgt->last_frame_hand_detected[hand_idx]) {
			if (hgt->tuneable_values.enable_framerate_based_smoothing) {
				int64_t one_before = *hgt->history_timestamps.get_at_age(0);
				int64_t now = hgt->current_frame_timestamp;

				uint64_t diff = now - one_before;
				double diff_d = time_ns_to_s(diff);
				smoothing_factor = hgt->tuneable_values.opt_smooth_factor.val * (1 / 60.0f) / diff_d;
			}
		} else {
			reprojection_error_threshold = hgt->tuneable_values.max_reprojection_error.val;
		}



		float out_hand_size;

		//!@todo optimize: We can have one of these on each thread
		float reprojection_error;
		const uint64_t _prof_o = ultra_prof::now();
		lm::optimizer_run(hand,                                     //
		                  hgt->keypoint_outputs[hand_idx],          //
		                  !hgt->last_frame_hand_detected[hand_idx], //
		                  smoothing_factor,
		                  optimize_hand_size,                              //
		                  hgt->target_hand_size,                           //
		                  hgt->refinement.hand_size_refinement_schedule_y, //
		                  hgt->tuneable_values.amt_use_depth.val,
		                  *put_in_set,   //
		                  out_hand_size, //
		                  reprojection_error);
		if (ultra_prof::state().on) ultra_prof::add(ultra_prof::OPT, ultra_prof::now() - _prof_o);
		if (hgt->xr_tracking_diag.enabled()) {
			auto &diag = hgt->xr_tracking_diag.frame.hands[hand_idx];
			diag.optimizer_ran = 1;
			diag.observation_views = int(hgt->keypoint_outputs[hand_idx].views[0].active) +
			                         int(hgt->keypoint_outputs[hand_idx].views[1].active);
			diag.reprojection_error = reprojection_error;
			diag.reprojection_limit = reprojection_error_threshold;
			diag.smoothing_factor = smoothing_factor;
			diag.depth_weight = hgt->tuneable_values.amt_use_depth.val;
			diag.optimize_hand_size = optimize_hand_size;
			diag.result_hand_size = out_hand_size;
			const auto &palm = put_in_set->values.hand_joint_set_default[XRT_HAND_JOINT_PALM].relation.pose;
			const auto &wrist = put_in_set->values.hand_joint_set_default[XRT_HAND_JOINT_WRIST].relation.pose;
			diag.palm_x = palm.position.x; diag.palm_y = palm.position.y; diag.palm_z = palm.position.z;
			diag.palm_qx = palm.orientation.x; diag.palm_qy = palm.orientation.y;
			diag.palm_qz = palm.orientation.z; diag.palm_qw = palm.orientation.w;
			diag.wrist_x = wrist.position.x; diag.wrist_y = wrist.position.y; diag.wrist_z = wrist.position.z;
		}



		// libultra_hands: a hand that has just begun is shown only once its fit has settled, and let go if it does not.
		// The dark stand beside the wearer's laptop, 40 cm off, is found by the tiles of both cameras as a right hand
		// (17 November's recording, from second 29.6) and the keypoint model agrees; its fit's error stayed over 0.3
		// for 60 fits and more. The wearer's hands had five fits in a row under 0.3 within 5 to 15 fits of beginning
		// in 18 of 19 tracks (17 and 22 November). So: five fits in a row under 0.3 before a new hand is reported,
		// and a hand that has not got there in 30 fits is dropped. It is asked of every new hand: asked only of
		// hands begun on the other camera's word, the stand was back in 16 % of the frames with no hand (2 % with
		// this, 2 % before any of it), begun some other way. ULTRA_HANDS_SETTLE=0: not asked.
		{
			static const bool ultra_settle_on = []() {
				const char *e = std::getenv("ULTRA_HANDS_SETTLE");
				return e == nullptr || e[0] != '0';
			}();
			if (!hgt->last_frame_hand_detected[hand_idx]) {
				ultra_settled[hand_idx] = !ultra_settle_on;
				ultra_settle_fits[hand_idx] = 0;
				ultra_settle_good[hand_idx] = 0;
			}
			if (!ultra_settled[hand_idx]) {
				ultra_settle_fits[hand_idx]++;
				ultra_settle_good[hand_idx] = reprojection_error < 0.3f ? ultra_settle_good[hand_idx] + 1 : 0;
				if (ultra_settle_good[hand_idx] >= 5) {
					ultra_settled[hand_idx] = true;
				} else if (ultra_settle_fits[hand_idx] >= 30) {
					hgt->this_frame_hand_detected[hand_idx] = false;
					ultra_drop("settle_timeout", hand_idx, (double)hgt->current_frame_timestamp / 1e9);
					if (hgt->xr_tracking_diag.enabled()) {
						hgt->xr_tracking_diag.frame.hands[hand_idx].reject(xr_mercury_diag::reprojection_rejected);
					}
					continue;
				}
			}
		}

		ultra_feat[hand_idx][0] = reprojection_error;
		ultra_feat[hand_idx][1] = quality_sample.mean_xy[0];
		ultra_feat[hand_idx][2] = quality_sample.mean_xy[1];
		ultra_feat[hand_idx][3] = out_hand_size;
		if (xr_mercury_quality::reject_fit(hgt->xr_quality_filter, quality_sample, reprojection_error)) {
			hgt->this_frame_hand_detected[hand_idx] = false;
			ultra_drop("fit_quality", hand_idx, (double)hgt->current_frame_timestamp / 1e9);
			if (hgt->xr_tracking_diag.enabled()) {
				hgt->xr_tracking_diag.frame.hands[hand_idx].reject(xr_mercury_diag::quality_rejected);
			}
			continue;
		}

		if (reprojection_error > reprojection_error_threshold) {
			HG_DEBUG(hgt, "Reprojection error above threshold!");
			hgt->this_frame_hand_detected[hand_idx] = false;
			ultra_drop("reproj_threshold", hand_idx, (double)hgt->current_frame_timestamp / 1e9);
			if (hgt->xr_tracking_diag.enabled()) {
				hgt->xr_tracking_diag.frame.hands[hand_idx].reject(xr_mercury_diag::reprojection_rejected);
			}
			continue;
		}

		if (hand_too_far(hgt, *put_in_set)) {
			HG_DEBUG(hgt, "Hand too far away");
			hgt->this_frame_hand_detected[hand_idx] = false;
			ultra_drop("too_far", hand_idx, (double)hgt->current_frame_timestamp / 1e9);
			if (hgt->xr_tracking_diag.enabled()) {
				hgt->xr_tracking_diag.frame.hands[hand_idx].reject(xr_mercury_diag::distance_rejected);
			}
			continue;
		}


		avg_hand_size += out_hand_size;
		num_hands++;

		if (!any_hands_are_only_visible_in_one_view) {
			hgt->refinement.hand_size_refinement_schedule_x +=
			    hand_confidence_value(reprojection_error, hgt->keypoint_outputs[hand_idx]);
		}

		u_hand_joints_apply_joint_width(put_in_set);



		put_in_set->hand_pose.pose = hgt->hand_pose_camera_offset;
		put_in_set->hand_pose.relation_flags = valid_flags_ht;

		Eigen::Array<float, 3, 21> asf = {};



		hand_joint_set_to_eigen_21(*put_in_set, asf);

		back_project(hgt,                                                                    //
		             asf,                                                                    //
		             hand_idx,                                                               //
		             hgt->tuneable_values.scribble_optimizer_outputs && hgt->debug_scribble, //
		             NULL                                                                    //
		);

		hgt->history_hands[hand_idx].push_back(asf);
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
		if (hgt->keypoint_outputs[hand_idx].views[0].active && hgt->keypoint_outputs[hand_idx].views[1].active) {
			hgt->ultra_both_views_seen[hand_idx] = true;
		}
	}

	// Push our timestamp back as well
	hgt->history_timestamps.push_back(hgt->current_frame_timestamp);

	// More hand-size-optimization spaghetti
	if (num_hands > 0) {
		hgt->target_hand_size = (float)avg_hand_size / (float)num_hands;
	}

	// State tracker tweaks
	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		out_xrt_hands[hand_idx]->is_active = hgt->this_frame_hand_detected[hand_idx];
		if (hgt->xr_tracking_diag.enabled()) {
			hgt->xr_tracking_diag.frame.hands[hand_idx].tracked_before_roi_prediction =
			    hgt->this_frame_hand_detected[hand_idx];
		}
		hgt->last_frame_hand_detected[hand_idx] = hgt->this_frame_hand_detected[hand_idx];

		hgt->hand_seen_before[hand_idx] =
		    hgt->hand_seen_before[hand_idx] || hgt->this_frame_hand_detected[hand_idx];

		if (!hgt->last_frame_hand_detected[hand_idx]) {
			// ULTRA_HANDS_TILE_SCHED=1: a hand that a tile began, with only its own camera's word for it, and that is gone within 8 frames, was
			// most likely not one: no tile's find is believed within 55 pixels of where it began for 45 runs of the detector.
			if (ultra_begun_by_tile[hand_idx] && !ultra_begun_by_stereo[hand_idx] && hgt->hand_tracked_for_num_frames[hand_idx] <= 8) {
				for (int v = 0; v < 2; v++) {
					ultra_phantom::add(v, ultra_begin_px[hand_idx][v].x, ultra_begin_px[hand_idx][v].y, (int)hgt->detection_counter + 45);
				}
			}
			ultra_begun_by_tile[hand_idx] = false;
			hgt->views[0].regions_of_interest_this_frame[hand_idx].found = false;
			hgt->views[1].regions_of_interest_this_frame[hand_idx].found = false;
			hgt->history_hands[hand_idx].clear();
			hgt->hand_tracked_for_num_frames[hand_idx] = 0;
			hgt->ultra_both_views_seen[hand_idx] = false;
		}
	}

	// estimators next frame. Also, if next frame's hand will be outside of the camera's field of view, mark it as
	// inactive this frame. This stops issues where our hand detector detects hands that are slightly too close to
	// the edge, causing flickery hands.
	if (!hgt->tuneable_values.always_run_detection_model) {
		predict_new_regions_of_interest(hgt);
		bool still_found[2] = {hgt->last_frame_hand_detected[0], hgt->last_frame_hand_detected[1]};
		still_found[0] = hgt->views[0].regions_of_interest_this_frame[0].found ||
		                 hgt->views[1].regions_of_interest_this_frame[0].found;
		still_found[1] = hgt->views[0].regions_of_interest_this_frame[1].found ||
		                 hgt->views[1].regions_of_interest_this_frame[1].found;

		for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
			out_xrt_hands[hand_idx]->is_active = still_found[hand_idx];
		}
	}

	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		// Don't send the hand to OpenXR until it's been tracked for 4 frames
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
				if (hgt->hand_tracked_for_num_frames[hand_idx] <= 1) {
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
			}
		}
		{
			static const bool gate_trace = std::getenv("ULTRA_HANDS_GATE_TRACE") != nullptr;
			double jc_mean[2] = {0, 0}, jc_min[2] = {1, 1};
			if (gate_trace) {
				for (int v = 0; v < 2; v++) {
					double sum = 0;
					for (int j = 0; j < 21; j++) {
						const double cf = hgt->keypoint_outputs[hand_idx].views[v].keypoints_in_scaled_stereographic[j].confidence_xy;
						sum += cf;
						if (cf < jc_min[v]) jc_min[v] = cf;
					}
					jc_mean[v] = sum / 21.0;
				}
			}
			if (gate_trace && (hgt->this_frame_hand_detected[hand_idx] || hgt->last_frame_hand_detected[hand_idx])) {
				fprintf(stderr, "GATE %.3f %d det=%d tracked=%d settled=%d both=%d roi0=%d,%.0f,%.0f roi1=%d,%.0f,%.0f err=%.3f mxy=%.3f,%.3f size=%.3f ish=%.3f,%.3f jc=%.3f,%.3f,%.3f,%.3f\n", (double)hgt->current_frame_timestamp / 1e9, hand_idx,
				        (int)hgt->this_frame_hand_detected[hand_idx], (int)hgt->hand_tracked_for_num_frames[hand_idx],
				        (int)ultra_settled[hand_idx], (int)hgt->ultra_both_views_seen[hand_idx],
				        (int)hgt->views[0].regions_of_interest_this_frame[hand_idx].found, (double)hgt->views[0].regions_of_interest_this_frame[hand_idx].center_px.x, (double)hgt->views[0].regions_of_interest_this_frame[hand_idx].center_px.y,
				        (int)hgt->views[1].regions_of_interest_this_frame[hand_idx].found, (double)hgt->views[1].regions_of_interest_this_frame[hand_idx].center_px.x, (double)hgt->views[1].regions_of_interest_this_frame[hand_idx].center_px.y,
				        (double)ultra_feat[hand_idx][0], (double)ultra_feat[hand_idx][1], (double)ultra_feat[hand_idx][2], (double)ultra_feat[hand_idx][3], (double)hgt->views[0].ultra_is_hand[hand_idx], (double)hgt->views[1].ultra_is_hand[hand_idx],
				        jc_mean[0], jc_min[0], jc_mean[1], jc_min[1]);
			}
		}
		if (hgt->hand_tracked_for_num_frames[hand_idx] < (ultra_recalled[hand_idx] ? std::min<uint64_t>(2, hgt->tuneable_values.num_frames_before_display) : hgt->tuneable_values.num_frames_before_display) ||
		    !hgt->ultra_both_views_seen[hand_idx] || !ultra_settled[hand_idx] || ultra_jc_low[hand_idx]) {
			out_xrt_hands[hand_idx]->is_active = false;
			if (hgt->xr_tracking_diag.enabled() && hgt->this_frame_hand_detected[hand_idx]) {
				hgt->xr_tracking_diag.frame.hands[hand_idx].reject(xr_mercury_diag::display_warmup);
			}
		}
	}

	if (hgt->xr_tracking_diag.enabled()) {
		for (int hand_idx = 0; hand_idx < 2; ++hand_idx) {
			auto &diag = hgt->xr_tracking_diag.frame.hands[hand_idx];
			diag.final_active = out_xrt_hands[hand_idx]->is_active;
			if (diag.tracked_before_roi_prediction == 1 &&
			    !(hgt->views[0].regions_of_interest_this_frame[hand_idx].found ||
			      hgt->views[1].regions_of_interest_this_frame[hand_idx].found)) {
				diag.reject(xr_mercury_diag::predicted_outside);
			}
			for (int view_idx = 0; view_idx < 2; ++view_idx) {
				const auto &roi = hgt->views[view_idx].regions_of_interest_this_frame[hand_idx];
				auto &view = diag.views[view_idx];
				view.roi_next_found = roi.found;
				view.roi_next_cx = roi.center_px.x;
				view.roi_next_cy = roi.center_px.y;
				view.roi_next_size = roi.size_px;
			}
		}
		hgt->xr_tracking_diag.write_frame();
	}
	// If the debug UI is active, push to the frame-timing widget
	u_frame_times_widget_push_sample(&hgt->ft_widget, hgt->current_frame_timestamp);

	// If requested, dump the Mercury annotated/model debug frames to disk.
	if (hgt->debug_scribble && xr_force_debug_dump) {
		xr_debug_dump_frames(hgt, debug_frame, hgt->visualizers.xrtframe, out_left_hand->is_active,
		                        out_right_hand->is_active);
	}

	// If the debug UI is active, push our debug frame.
	const bool xr_ui_debug_active =
	    u_sink_debug_is_active(&hgt->debug_sink_ann) && u_sink_debug_is_active(&hgt->debug_sink_model);
	if (hgt->debug_scribble && xr_ui_debug_active) {
		u_sink_debug_push_frame(&hgt->debug_sink_ann, debug_frame);
		xrt_frame_reference(&debug_frame, NULL);

		// We don't dereference the model inputs/outputs frame here; we make a copy of it next frame and
		// dereference it then.
		u_sink_debug_push_frame(&hgt->debug_sink_model, hgt->visualizers.xrtframe);
		xrt_frame_reference(&hgt->visualizers.old_frame, hgt->visualizers.xrtframe);
		xrt_frame_reference(&hgt->visualizers.xrtframe, NULL);
	} else if (hgt->debug_scribble) {
		xrt_frame_reference(&debug_frame, NULL);
		xrt_frame_reference(&hgt->visualizers.xrtframe, NULL);
	}

	// done!
}

void
HandTracking::cCallbackProcess(struct t_hand_tracking_sync *ht_sync,
                               struct xrt_frame *left_frame,
                               struct xrt_frame *right_frame,
                               struct xrt_hand_joint_set *out_left_hand,
                               struct xrt_hand_joint_set *out_right_hand,
                               int64_t *out_timestamp_ns)
{
	XRT_TRACE_MARKER();

	HandTracking *hgt = &HandTracking::fromC(ht_sync);

	hgt->current_frame_timestamp = left_frame->timestamp;

	// No filtering, fine to do this now. Also just a reminder that this took you 2 HOURS TO DEBUG THAT ONE TIME.
	*out_timestamp_ns = hgt->current_frame_timestamp;

	try {
		callback_process_unsafe(hgt, left_frame, right_frame, out_left_hand, out_right_hand, out_timestamp_ns);
	} catch (const std::exception &e) {
		HG_ERROR(hgt, "Exception in hand tracking process callback: %s", e.what());

		// Mark the hands as not active since an exception occurred.
		out_left_hand->is_active = false;
		out_right_hand->is_active = false;
	}
}

void
HandTracking::cCallbackDestroy(t_hand_tracking_sync *ht_sync)
{
	HandTracking *ht_ptr = &HandTracking::fromC(ht_sync);

	try {
		delete ht_ptr;
	} catch (const std::exception &e) {
		HG_ERROR(ht_ptr, "Exception in hand tracking destroy callback: %s", e.what());
	}
}

} // namespace xrt::tracking::hand::mercury


using namespace xrt::tracking::hand::mercury;

/*
 *
 * 'Exported' functions.
 *
 */

extern "C" t_hand_tracking_sync *
t_hand_tracking_sync_mercury_create(struct t_stereo_camera_calibration *calib,
                                    struct t_hand_tracking_create_info create_info,
                                    const char *models_folder)
{
	XRT_TRACE_MARKER();

	xrt::tracking::hand::mercury::HandTracking *hgt = new xrt::tracking::hand::mercury::HandTracking();

	// Setup logging first. We like logging.
	hgt->log_level = xrt::tracking::hand::mercury::debug_get_log_option_mercury_log();

	/*
	 * Get configuration
	 */

	assert(calib != NULL);
	hgt->calib = NULL;
	// We have to reference it, getCalibration points at it.
	t_stereo_camera_calibration_reference(&hgt->calib, calib);
	getCalibration(hgt, *calib);
	strncpy(hgt->models_folder, models_folder, ARRAY_SIZE(hgt->models_folder) - 1);


	hgt->views[0].hgt = hgt;
	hgt->views[1].hgt = hgt; // :)

	hgt->hand_masks_sink = create_info.masks_sink;

	struct t_camera_extra_info &extra_camera_info = create_info.cams_info;
	hgt->views[0].camera_info = extra_camera_info.views[0];
	hgt->views[1].camera_info = extra_camera_info.views[1];

	try {
		init_hand_detection(hgt, &hgt->views[0].detection);
		init_hand_detection(hgt, &hgt->views[1].detection);

		init_keypoint_estimation(hgt, &hgt->views[0].keypoint[0]);
		init_keypoint_estimation(hgt, &hgt->views[0].keypoint[1]);

		init_keypoint_estimation(hgt, &hgt->views[1].keypoint[0]);
		init_keypoint_estimation(hgt, &hgt->views[1].keypoint[1]);
	} catch (const std::exception &e) {
		HG_ERROR(hgt, "Failed to initialize ONNX runtime: %s", e.what());
		delete hgt;
		return NULL;
	}
	hgt->keypoint_estimation_run_func = xrt::tracking::hand::mercury::run_keypoint_estimation;

	hgt->views[0].view = 0;
	hgt->views[1].view = 1;

	// libultra_hands: worker pool size is configurable (default 4, as upstream).
	int num_threads = ultra_hands_cfg_worker_threads();
	hgt->pool = u_worker_thread_pool_create(num_threads - 1, num_threads, "Hand Tracking");
	hgt->group = u_worker_group_create(hgt->pool);

	lm::optimizer_create(hgt->left_in_right, false, hgt->log_level, &hgt->kinematic_hands[0]);
	lm::optimizer_create(hgt->left_in_right, true, hgt->log_level, &hgt->kinematic_hands[1]);

	u_frame_times_widget_init(&hgt->ft_widget, 10.0f, 10.0f);

	u_var_add_root(hgt, "Camera-based Hand Tracker", true);


	u_var_add_ro_f32(hgt, &hgt->ft_widget.fps, "FPS!");
	u_var_add_f32_timing(hgt, hgt->ft_widget.debug_var, "Frame timing!");

	u_var_add_f32(hgt, &hgt->target_hand_size, "Hand size (Meters between wrist and middle-proximal joint)");
	u_var_add_ro_f32(hgt, &hgt->refinement.hand_size_refinement_schedule_x, "Schedule (X value)");
	u_var_add_ro_f32(hgt, &hgt->refinement.hand_size_refinement_schedule_y, "Schedule (Y value)");


	u_var_add_bool(hgt, &hgt->tuneable_values.new_user_event, "Estimate hand sizes");

	hgt->tuneable_values.optimize_hand_size = debug_get_bool_option_mercury_optimize_hand_size();

	hgt->tuneable_values.dyn_radii_fac.max = 4.0f;
	hgt->tuneable_values.dyn_radii_fac.min = 0.3f;
	hgt->tuneable_values.dyn_radii_fac.step = 0.02f;
	hgt->tuneable_values.dyn_radii_fac.val = 1.7f;


	hgt->tuneable_values.after_detection_fac.max = 1.0f;
	hgt->tuneable_values.after_detection_fac.min = 0.1f;
	hgt->tuneable_values.after_detection_fac.step = 0.01f;
	// note that sqrt2/2 is what should make sense, but I tuned it down to this. Detection model needs work.
	hgt->tuneable_values.after_detection_fac.val = 0.65f; // but 0.5 is closer to what we actually want

	hgt->tuneable_values.dyn_joint_y_angle_error.max = 40.0f;
	hgt->tuneable_values.dyn_joint_y_angle_error.min = 0.0f;
	hgt->tuneable_values.dyn_joint_y_angle_error.step = 0.1f;
	hgt->tuneable_values.dyn_joint_y_angle_error.val = 10.0f;

	// Number of times this has been changed without rigorously testing: 1
	hgt->tuneable_values.amount_to_lerp_prediction.max = 1.5f;
	hgt->tuneable_values.amount_to_lerp_prediction.min = -1.5f;
	hgt->tuneable_values.amount_to_lerp_prediction.step = 0.01f;
	hgt->tuneable_values.amount_to_lerp_prediction.val = 0.4f;

	hgt->tuneable_values.amt_use_depth.max = 1.0f;
	hgt->tuneable_values.amt_use_depth.min = 0.0f;
	hgt->tuneable_values.amt_use_depth.step = 0.01f;
	hgt->tuneable_values.amt_use_depth.val = 0.01f;

	hgt->tuneable_values.mpiou_any.max = 1.0f;
	hgt->tuneable_values.mpiou_any.min = 0.0f;
	hgt->tuneable_values.mpiou_any.step = 0.01f;
	hgt->tuneable_values.mpiou_any.val = 0.7f;

	hgt->tuneable_values.mpiou_double_detection.max = 1.0f;
	hgt->tuneable_values.mpiou_double_detection.min = 0.0f;
	hgt->tuneable_values.mpiou_double_detection.step = 0.01f;
	hgt->tuneable_values.mpiou_double_detection.val = 0.4f;

	hgt->tuneable_values.mpiou_single_detection.max = 1.0f;
	hgt->tuneable_values.mpiou_single_detection.min = 0.0f;
	hgt->tuneable_values.mpiou_single_detection.step = 0.01f;
	hgt->tuneable_values.mpiou_single_detection.val = 0.2f;

	hgt->tuneable_values.max_reprojection_error.max = 600.0f;
	hgt->tuneable_values.max_reprojection_error.min = 0.0f;
	hgt->tuneable_values.max_reprojection_error.step = 0.001f;
	hgt->tuneable_values.max_reprojection_error.val = 15.0f;

	hgt->tuneable_values.opt_smooth_factor.max = 30.0f;
	hgt->tuneable_values.opt_smooth_factor.min = 0.0f;
	hgt->tuneable_values.opt_smooth_factor.step = 0.01f;
	hgt->tuneable_values.opt_smooth_factor.val = 2.0f;

	hgt->tuneable_values.max_hand_dist.max = 1000000.0f;
	hgt->tuneable_values.max_hand_dist.min = 0.0f;
	hgt->tuneable_values.max_hand_dist.step = 0.05f;
	hgt->tuneable_values.max_hand_dist.val = 1.7f;

	hgt->tuneable_values.min_detection_confidence.max = 1.0f;
	hgt->tuneable_values.min_detection_confidence.min = 0.0f;
	hgt->tuneable_values.min_detection_confidence.step = 0.01f;
	hgt->tuneable_values.min_detection_confidence.val = debug_get_float_option_mercury_min_detection_confidence();
	hgt->xr_tracking_diag.configure_from_environment();
	hgt->xr_quality_filter = xr_mercury_quality::Config::from_environment();
	U_LOG_I("[mercury_quality_filter] enabled=%d min_mean_xy=%.3f weak_mean_xy=%.3f max_weak_reprojection=%.3f",
	        int(hgt->xr_quality_filter.enabled), hgt->xr_quality_filter.min_mean_xy,
	        hgt->xr_quality_filter.weak_mean_xy, hgt->xr_quality_filter.max_weak_reprojection_error);

	const char *xr_detector_debug_csv = getenv("MERCURY_XR_DETECTOR_DEBUG_CSV");
	if (xr_detector_debug_csv != nullptr && xr_detector_debug_csv[0] != '\0') {
		hgt->xr_detector_debug_enabled = true;
		strncpy(hgt->xr_detector_debug_csv, xr_detector_debug_csv,
		        ARRAY_SIZE(hgt->xr_detector_debug_csv) - 1);
	}

	// Useful for offline dataset diagnostics: get detector output for both stereo
	// views on every detection pass without opening the Mercury UI. This does not
	// reset tracking history, unlike always_run_detection_model.
	if (xr_env_flag_enabled("MERCURY_XR_DETECT_BOTH_VIEWS") ||
	    xr_env_flag_enabled("MERCURY_XR_DETECTOR_FUSION")) {
		hgt->tuneable_values.detection_model_in_both_views = true;
	}

	// Stronger diagnostic mode: rerun detector and clear tracking history every
	// frame. This intentionally changes runtime behavior and should be used only
	// when inspecting raw detector slots.
	if (xr_env_flag_enabled("MERCURY_XR_FORCE_ALWAYS_DETECT")) {
		hgt->tuneable_values.always_run_detection_model = true;
		hgt->tuneable_values.detection_model_in_both_views = true;
	}

	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.amt_use_depth, "Amount to use depth prediction");


	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.dyn_radii_fac, "radius factor (predict)");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.after_detection_fac, "radius factor (after hand detection)");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.dyn_joint_y_angle_error, "max error hand joint");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.amount_to_lerp_prediction, "Amount to lerp pose-prediction");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.mpiou_any, "Max permissible IOU (Any)");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.mpiou_double_detection,
	                        "Max permissible IOU (For suppressing double detections)");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.mpiou_single_detection,
	                        "Max permissible IOU (For suppressing single detections)");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.max_reprojection_error, "Max reprojection error");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.opt_smooth_factor, "Optimizer smoothing factor");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.max_hand_dist, "Max hand distance");
	u_var_add_draggable_f32(hgt, &hgt->tuneable_values.min_detection_confidence, "Min detection confidence");

	u_var_add_i32(hgt, &hgt->tuneable_values.max_num_outside_view,
	              "max allowed number of hand joints outside view");
	u_var_add_u64(hgt, &hgt->tuneable_values.num_frames_before_display,
	              "Number of frames before we show hands to OpenXR");


	u_var_add_bool(hgt, &hgt->tuneable_values.scribble_predictions_into_next_frame,
	               "Scribble pose-predictions into next frame");
	u_var_add_bool(hgt, &hgt->tuneable_values.scribble_keypoint_model_outputs, "Scribble keypoint model output");
	u_var_add_bool(hgt, &hgt->tuneable_values.scribble_optimizer_outputs, "Scribble kinematic optimizer output");
	u_var_add_bool(hgt, &hgt->tuneable_values.always_run_detection_model,
	               "Use detection model instead of pose-predicting into next frame");
	u_var_add_bool(hgt, &hgt->tuneable_values.optimize_hand_size, "Optimize hand size");
	u_var_add_bool(hgt, &hgt->tuneable_values.enable_pose_predicted_input,
	               "Enable pose-predicted input to keypoint model");
	u_var_add_bool(hgt, &hgt->tuneable_values.enable_framerate_based_smoothing,
	               "Enable framerate-based smoothing (Don't use; surprisingly seems to make things worse)");
	u_var_add_bool(hgt, &hgt->tuneable_values.detection_model_in_both_views, "Run detection model in both views ");



	u_var_add_sink_debug(hgt, &hgt->debug_sink_ann, "Annotated camera feeds");
	u_var_add_sink_debug(hgt, &hgt->debug_sink_model, "Model inputs and outputs");

	HG_DEBUG(hgt, "Hand Tracker initialized!");

	return &hgt->base;
}
