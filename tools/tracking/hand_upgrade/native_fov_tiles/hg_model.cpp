// Copyright 2021-2022, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief Mercury ML models!
 * @author Moshi Turner <moshiturner@protonmail.com>
 * @ingroup drv_ht
 */

// Many C api things were stolen from here (MIT license):
// https://github.com/microsoft/onnxruntime-inference-examples/blob/main/c_cxx/fns_candy_style_transfer/fns_candy_style_transfer.c
#include "hg_sync.hpp"
#include "hg_image_math.inl"
#include "hg_numerics_checker.hpp"

#include <cstdlib>
#include <cstring>
#include <utility>
#include <vector>
#include <algorithm>

#include <filesystem>
#include <array>
#include <cstdio>
#include <cstdint>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>
#include <iterator>
#include "ultra_prof.hpp"
#include "ultra_phantom.hpp"

namespace xrt::tracking::hand::mercury {

using namespace xrt::auxiliary::onnx;

static cv::Matx23f
blackbar(const cv::Mat &in, enum t_camera_orientation rot, cv::Mat &out, xrt_size out_size)
{
	// Easy to think about, always right, but pretty slow:
	// Get a matrix from the original to the scaled down / blackbar'd image, then get one that goes back.
	// Then just warpAffine() it.
	// Easy in programmer time - never have to worry about off by one, special cases. We can come back and optimize
	// later.
	bool swapped_wh = false;
	float in_w, in_h;

	switch (rot) {
	case CAMERA_ORIENTATION_90:
	case CAMERA_ORIENTATION_270:
		// Swap width and height
		in_w = in.rows;
		in_h = in.cols;
		swapped_wh = true;
		break;
	default:
		in_w = in.cols;
		in_h = in.rows;
		break;
	}

	// Figure out from the rotation and frame sizes if the black bars need to be on top and bottom, or on left and
	// right?
	float scale_down_w = (float)out_size.w / in_w; // 128/1280 = 0.1
	float scale_down_h = (float)out_size.h / in_h; // 128/800 =  0.16

	float scale_down = fmin(scale_down_w, scale_down_h); // 0.1

	float width_inside, height_inside;

	if (swapped_wh) {
		width_inside = (float)in.rows * scale_down;
		height_inside = (float)in.cols * scale_down;
	} else {
		width_inside = (float)in.cols * scale_down;
		height_inside = (float)in.rows * scale_down;
	}

	float translate_x = (out_size.w - width_inside) / 2;  // should be 0 for 1280x800
	float translate_y = (out_size.h - height_inside) / 2; // should be (1280-800)/2 = 240

	cv::Matx23f go;
	cv::Point2f center(in.rows / 2, in.cols / 2);

	switch (rot) {
	case CAMERA_ORIENTATION_0:
		// clang-format off
			go(0,0) = scale_down;  go(0,1) = 0.0f;          go(0,2) = translate_x;
			go(1,0) = 0.0f;        go(1,1) = scale_down;    go(1,2) = translate_y;
		// clang-format on
		break;
	case CAMERA_ORIENTATION_90:
		// clang-format off
			go(0,0) = 0.0f;         go(0,1) = scale_down;   go(0,2) = translate_x;
			go(1,0) = -scale_down;  go(1,1) = 0.0f;         go(1,2) = translate_y+out_size.h-1;
		// clang-format on
		break;
	case CAMERA_ORIENTATION_180:
		// clang-format off
			go(0,0) = -scale_down;  go(0,1) = 0.0f;         go(0,2) = translate_x+out_size.w-1;
			go(1,0) = 0.0f;         go(1,1) = -scale_down;  go(1,2) = -translate_y+out_size.h-1;
		// clang-format on
		break;
	case CAMERA_ORIENTATION_270:
		// clang-format off
			go(0,0) = 0.0f;        go(0,1) = -scale_down;   go(0,2) = -translate_x+out_size.w-1;
			go(1,0) = scale_down;  go(1,1) = 0.0f;          go(1,2) = translate_y;
		// clang-format on
		break;
	}

	cv::warpAffine(in, out, go, cv::Size(out_size.w, out_size.h));

	// Return the inverse affine transform by passing
	// through a 3x3 rotation matrix
	cv::Mat e = cv::Mat::eye(3, 3, CV_32F);
	cv::Mat tmp = e(cv::Rect(0, 0, 3, 2));
	cv::Mat(go).copyTo(tmp);

	e = e.inv();
	cv::Matx23f ret = e(cv::Rect(0, 0, 3, 2));

	return ret;
}

static inline int
argmax(const float *data, int size)
{
	float max_value = data[0];
	int out_idx = 0;

	for (int i = 1; i < size; i++) {
		if (data[i] > max_value) {
			max_value = data[i];
			out_idx = i;
		}
	}
	return out_idx;
}

static bool
hand_depth_center_of_mass(struct HandTracking *hgt, float data[22], float *out_depth, float *out_confidence)
{
	float avg_location_px_coord = 0;
	float sum = 0;

	for (int i = 0; i < 22; i++) {
		data[i] = fmin(1.0, fmax(data[i], 0.0));
		sum += data[i];
		avg_location_px_coord += data[i] * (float)i;
	}

	if (sum < 1e-5) {
		HG_DEBUG(hgt, "All depth outputs were zero!");
		return false;
	}

	avg_location_px_coord /= sum;

	// std::cout << avg_location_px_coord << std::endl;

	// bounds check
	if (avg_location_px_coord < 0 || avg_location_px_coord > 21) {
		HG_DEBUG(hgt, "Very bad! avg_location_px_coord was %f", avg_location_px_coord);
		for (int i = 0; i < 22; i++) {
			HG_DEBUG(hgt, "%f", data[i]);
		}

		avg_location_px_coord = fmin(21.0, fmax(0.0, avg_location_px_coord));
		return false;
	}

	// nan check
	if (avg_location_px_coord != avg_location_px_coord) {
		HG_DEBUG(hgt, "Very bad! avg_location_px_coord was not a number: %f", avg_location_px_coord);
		for (int i = 0; i < 22; i++) {
			HG_DEBUG(hgt, "%f", data[i]);
		}
		*out_depth = 0;
		*out_confidence = 0;
		return false;
	}
	// printf("%f %d\n", avg_location_px_coord, (int)avg_location_px_coord);
	*out_confidence = data[(int)avg_location_px_coord];



	float depth_value = avg_location_px_coord + 0.5;
	depth_value /= 22;
	depth_value -= 0.5;
	depth_value *= 2 * 1.5;

	*out_depth = depth_value;
	return true;
}

static bool
refine_center_of_distribution(struct HandTracking *hgt, //
                              const float *data,        //
                              int coarse_x,             //
                              int coarse_y,             //
                              int w,                    //
                              int h,                    //
                              float *out_refined_x,     //
                              float *out_refined_y)
{
	// Be VERY suspicious of this function, it's probably not centering correctly.
	float sum_of_values = 0;
	float sum_of_values_times_locations_x = 0;
	float sum_of_values_times_locations_y = 0;

	int max_kern_width = 10;


	//! @todo this is not good and has at least one edge case, make it more readable and link to a jupyter notebook
	int kern_width_x = std::max(0, std::min(coarse_x, std::min(max_kern_width, abs(coarse_x - w) - 1)));
	int kern_width_y = std::max(0, std::min(coarse_y, std::min(max_kern_width, abs(coarse_y - h) - 1)));
	int min_x = coarse_x - kern_width_x;
	int max_x = coarse_x + kern_width_x;

	int min_y = coarse_y - kern_width_y;
	int max_y = coarse_y + kern_width_y;


	for (int y = min_y; y <= max_y; y++) {
		for (int x = min_x; x <= max_x; x++) {
			int acc = (y * w) + x;
			float val = data[acc];
			sum_of_values += val;
			sum_of_values_times_locations_y += val * ((float)y + 0.5);
			sum_of_values_times_locations_x += val * ((float)x + 0.5);
		}
	}

	if (sum_of_values == 0) {
		// Edge case, will fix soon
		*out_refined_x = coarse_x;
		*out_refined_y = coarse_y;
		HG_DEBUG(hgt, "Failed! %d %d %d %d %d", coarse_x, coarse_y, w, h, max_kern_width);
		return false;
	}
	*out_refined_x = sum_of_values_times_locations_x / sum_of_values;
	*out_refined_y = sum_of_values_times_locations_y / sum_of_values;
	return true;
}

static bool
normalizeGrayscaleImage(cv::Mat &data_in, cv::Mat &data_out)
{
	data_in.convertTo(data_out, CV_32FC1, 1 / 255.0);

	cv::Mat mean;
	cv::Mat stddev;
	cv::meanStdDev(data_out, mean, stddev);

	if (stddev.at<double>(0, 0) == 0) {
		U_LOG_W("Got image with zero standard deviation!");
		return false;
	}

	data_out *= 0.25 / stddev.at<double>(0, 0);

	// Calculate it again; mean has changed. Yes we don't need to but it's easy
	//! @todo optimize
	cv::meanStdDev(data_out, mean, stddev);
	data_out += (0.5 - mean.at<double>(0, 0));
	return true;
}

void
setup_ort_api(HandTracking *hgt, onnx_state *state, const std::filesystem::path &path)
{
	state->wrap = std::make_unique<OnnxWrapper>(hgt->log_level, path.string(), "monado_ht");
}

void
setup_model_image_input(HandTracking *hgt, onnx_state *state, const char *name, int64_t w, int64_t h)
{
	OnnxWrapper &wrap = *state->wrap;

	model_input_wrap inputimg = {};
	inputimg.name = name;
	inputimg.dimensions[0] = 1;
	inputimg.dimensions[1] = 1;
	inputimg.dimensions[2] = h;
	inputimg.dimensions[3] = w;
	inputimg.num_dimensions = 4;
	size_t data_size = w * h * sizeof(float);
	inputimg.data = (float *)malloc(data_size);

	ORT_SAFE(wrap, CreateTensorWithDataAsOrtValue(wrap.meminfo,                        //
	                                              inputimg.data,                       //
	                                              data_size,                           //
	                                              inputimg.dimensions,                 //
	                                              inputimg.num_dimensions,             //
	                                              ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, //
	                                              &inputimg.tensor));

	assert(inputimg.tensor);
	int is_tensor;
	ORT_SAFE(wrap, IsTensor(inputimg.tensor, &is_tensor));
	assert(is_tensor);

	state->wraps.push_back(inputimg);
}

void
init_hand_detection(HandTracking *hgt, onnx_state *wrap)
{
	std::filesystem::path path = hgt->models_folder;

	path /= "grayscale_detection_160x160.onnx";

	wrap->wraps.clear();

	setup_ort_api(hgt, wrap, path);

	setup_model_image_input(hgt, wrap, "inputImg", kDetectionInputSize, kDetectionInputSize);
}


static bool
xr_detector_env_flag_enabled(const char *name)
{
	const char *value = getenv(name);
	if (value == nullptr || value[0] == '\0') {
		return false;
	}
	return strcmp(value, "0") != 0 && strcmp(value, "false") != 0 && strcmp(value, "FALSE") != 0;
}

static float
xr_detector_env_float(const char *name, float fallback)
{
	const char *value = getenv(name);
	if (value == nullptr || value[0] == '\0') {
		return fallback;
	}
	char *end = nullptr;
	float parsed = strtof(value, &end);
	if (end == value) {
		return fallback;
	}
	return parsed;
}

struct xr_fusion_candidate
{
	hand_region_of_interest roi = {};
	float score = 0.0f;
	int source = 0; // 0=raw detector, 1=mirrored-detector/unmirrored-ROI
	int source_hand_idx = 0;
};

struct xr_fusion_pair_candidate
{
	hand_region_of_interest roi[2] = {};
	float score = 0.0f;
	int source = 0; // 0=raw detector, 1=mirrored-detector/unmirrored-ROI
	int source_hand_idx = 0;
	float avg_x = 0.0f;
};

struct xr_stereo_fusion_view_cache
{
	bool valid = false;
	ht_view *view = nullptr;
	hand_region_of_interest raw_outputs[2] = {};
	hand_region_of_interest mirrored_outputs[2] = {};
	std::vector<xr_fusion_candidate> candidates;
	hand_region_of_interest *outputs = nullptr;
};

struct xr_stereo_fusion_frame_cache
{
	HandTracking *hgt = nullptr;
	uint64_t timestamp_ns = 0;
	xr_stereo_fusion_view_cache views[2];
};

static float
xr_roi_center_distance_px(const hand_region_of_interest &a, const hand_region_of_interest &b)
{
	xrt_vec2 diff = a.center_px - b.center_px;
	return m_vec2_len(diff);
}

static float
xr_roi_iou_px(const hand_region_of_interest &a, const hand_region_of_interest &b)
{
	if (!a.found || !b.found || a.size_px <= 0.0f || b.size_px <= 0.0f) {
		return -1.0f;
	}
	float ax0 = a.center_px.x - a.size_px * 0.5f;
	float ax1 = a.center_px.x + a.size_px * 0.5f;
	float ay0 = a.center_px.y - a.size_px * 0.5f;
	float ay1 = a.center_px.y + a.size_px * 0.5f;
	float bx0 = b.center_px.x - b.size_px * 0.5f;
	float bx1 = b.center_px.x + b.size_px * 0.5f;
	float by0 = b.center_px.y - b.size_px * 0.5f;
	float by1 = b.center_px.y + b.size_px * 0.5f;

	float ix0 = fmaxf(ax0, bx0);
	float ix1 = fminf(ax1, bx1);
	float iy0 = fmaxf(ay0, by0);
	float iy1 = fminf(ay1, by1);
	float iw = fmaxf(0.0f, ix1 - ix0);
	float ih = fmaxf(0.0f, iy1 - iy0);
	float inter = iw * ih;
	float area_a = fmaxf(0.0f, ax1 - ax0) * fmaxf(0.0f, ay1 - ay0);
	float area_b = fmaxf(0.0f, bx1 - bx0) * fmaxf(0.0f, by1 - by0);
	float denom = area_a + area_b - inter;
	if (denom <= 1e-6f) {
		return -1.0f;
	}
	return inter / denom;
}

static float
xr_roi_dynamic_duplicate_threshold_px(const hand_region_of_interest &a,
                                                const hand_region_of_interest &b,
                                                float configured_min_px)
{
	// One hand seen through two detector conventions usually produces overlapping
	// ROIs. Keep the threshold tied to ROI size, with a configurable floor.
	float size_based = 0.55f * fmaxf(a.size_px, b.size_px);
	return fmaxf(configured_min_px, size_based);
}

static int
xr_detector_env_int(const char *name, int fallback)
{
	const char *value = getenv(name);
	if (value == nullptr || value[0] == '\0') {
		return fallback;
	}
	char *end = nullptr;
	long parsed = strtol(value, &end, 10);
	if (end == value) {
		return fallback;
	}
	return (int)parsed;
}

static const char *
xr_detector_source_name(int source)
{
	switch (source) {
	case 0: return "raw";
	case 1: return "mirror_unmirror";
	default: return "unknown";
	}
}

static const hand_region_of_interest &
xr_candidate_kind_roi(const xr_stereo_fusion_view_cache &view, int source, int hand_idx)
{
	return source == 0 ? view.raw_outputs[hand_idx] : view.mirrored_outputs[hand_idx];
}

static std::vector<xr_fusion_candidate>
xr_per_view_select_candidates(const std::vector<xr_fusion_candidate> &candidates, float min_dist_px)
{
	std::vector<xr_fusion_candidate> sorted = candidates;
	std::sort(sorted.begin(), sorted.end(), [](const xr_fusion_candidate &a, const xr_fusion_candidate &b) {
		return a.score > b.score;
	});

	std::vector<xr_fusion_candidate> selected;
	selected.reserve(2);
	for (const xr_fusion_candidate &candidate : sorted) {
		bool duplicate = false;
		for (const xr_fusion_candidate &existing : selected) {
			float dist_px = xr_roi_center_distance_px(candidate.roi, existing.roi);
			float threshold_px =
			    xr_roi_dynamic_duplicate_threshold_px(candidate.roi, existing.roi, min_dist_px);
			if (dist_px < threshold_px) {
				duplicate = true;
				break;
			}
		}
		if (duplicate) {
			continue;
		}
		selected.push_back(candidate);
		if (selected.size() >= 2) {
			break;
		}
	}

	std::sort(selected.begin(), selected.end(), [](const xr_fusion_candidate &a, const xr_fusion_candidate &b) {
		return a.roi.center_px.x < b.roi.center_px.x;
	});
	return selected;
}

static void
xr_clear_outputs(hand_region_of_interest outputs[2])
{
	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		outputs[hand_idx].found = false;
		outputs[hand_idx].hand_detection_confidence = false;
		outputs[hand_idx].provenance = ROIProvenance::HAND_DETECTION;
	}
}

static void
xr_apply_selected_to_outputs(const std::vector<xr_fusion_candidate> &selected, hand_region_of_interest outputs[2])
{
	xr_clear_outputs(outputs);
	for (size_t i = 0; i < selected.size() && i < 2; i++) {
		outputs[i] = selected[i].roi;
		outputs[i].xr_raw_cy = (float)selected[i].source;
		outputs[i].xr_raw_size = (float)selected[i].source_hand_idx;
	}
}

static void
xr_apply_pair_selection_to_outputs(const std::vector<xr_fusion_pair_candidate> &selected_pairs,
                                         xr_stereo_fusion_view_cache views[2],
                                         bool slot_swap)
{
	for (int view_idx = 0; view_idx < 2; view_idx++) {
		if (!views[view_idx].valid || views[view_idx].outputs == nullptr) {
			continue;
		}
		xr_clear_outputs(views[view_idx].outputs);
	}

	for (size_t slot = 0; slot < selected_pairs.size() && slot < 2; slot++) {
		size_t out_slot = slot_swap ? (1 - slot) : slot;
		for (int view_idx = 0; view_idx < 2; view_idx++) {
			if (!views[view_idx].valid || views[view_idx].outputs == nullptr) {
				continue;
			}
			views[view_idx].outputs[out_slot] = selected_pairs[slot].roi[view_idx];
			views[view_idx].outputs[out_slot].xr_raw_cy = (float)selected_pairs[slot].source;
			views[view_idx].outputs[out_slot].xr_raw_size = (float)selected_pairs[slot].source_hand_idx;
		}
	}
}

static void
xr_build_selected_debug_for_view(const std::vector<xr_fusion_pair_candidate> &selected_pairs,
                                    int view_idx,
                                    std::vector<xr_fusion_candidate> &out)
{
	out.clear();
	out.reserve(2);
	for (const xr_fusion_pair_candidate &pair : selected_pairs) {
		xr_fusion_candidate c = {};
		c.roi = pair.roi[view_idx];
		c.score = pair.score;
		c.source = pair.source;
		c.source_hand_idx = pair.source_hand_idx;
		out.push_back(c);
	}
}

static std::vector<xr_fusion_pair_candidate>
xr_select_stereo_pairs(const xr_stereo_fusion_view_cache views[2], float min_conf, float min_dist_px)
{
	std::vector<xr_fusion_pair_candidate> pairs;
	pairs.reserve(4);

	for (int source = 0; source < 2; source++) {
		for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
			const hand_region_of_interest &v0 = xr_candidate_kind_roi(views[0], source, hand_idx);
			const hand_region_of_interest &v1 = xr_candidate_kind_roi(views[1], source, hand_idx);
			if (!v0.found || !v1.found) {
				continue;
			}
			if (v0.xr_raw_hand_exists < min_conf || v1.xr_raw_hand_exists < min_conf) {
				continue;
			}

			xr_fusion_pair_candidate pair = {};
			pair.roi[0] = v0;
			pair.roi[1] = v1;
			pair.source = source;
			pair.source_hand_idx = hand_idx;
			pair.score = v0.xr_raw_hand_exists + v1.xr_raw_hand_exists;
			pair.avg_x = 0.5f * (v0.center_px.x + v1.center_px.x);
			pairs.push_back(pair);
		}
	}

	std::sort(pairs.begin(), pairs.end(), [](const xr_fusion_pair_candidate &a, const xr_fusion_pair_candidate &b) {
		return a.score > b.score;
	});

	std::vector<xr_fusion_pair_candidate> selected;
	selected.reserve(2);
	for (const xr_fusion_pair_candidate &pair : pairs) {
		bool duplicate = false;
		for (const xr_fusion_pair_candidate &existing : selected) {
			float d0 = xr_roi_center_distance_px(pair.roi[0], existing.roi[0]);
			float d1 = xr_roi_center_distance_px(pair.roi[1], existing.roi[1]);
			float avg = 0.5f * (d0 + d1);
			if (avg < min_dist_px) {
				duplicate = true;
				break;
			}
		}
		if (duplicate) {
			continue;
		}
		selected.push_back(pair);
		if (selected.size() >= 2) {
			break;
		}
	}

	std::sort(selected.begin(), selected.end(), [](const xr_fusion_pair_candidate &a, const xr_fusion_pair_candidate &b) {
		return a.avg_x < b.avg_x;
	});
	return selected;
}

static void
xr_fusion_debug_write_roi_csv(FILE *f, const hand_region_of_interest &roi)
{
	fprintf(f, "%d,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g", roi.found ? 1 : 0, roi.xr_raw_hand_exists,
	        roi.xr_raw_cx, roi.xr_raw_cy, roi.xr_raw_size, roi.center_px.x, roi.center_px.y,
	        roi.size_px);
}

static void
xr_fusion_debug_write_selected_csv(FILE *f, const std::vector<xr_fusion_candidate> &selected, size_t idx)
{
	if (idx >= selected.size()) {
		fprintf(f, "0,none,-1,-1,0,0,0,0");
		return;
	}
	const xr_fusion_candidate &s = selected[idx];
	fprintf(f, "1,%s,%d,%zu,%.9g,%.9g,%.9g,%.9g", xr_detector_source_name(s.source),
	        s.source_hand_idx, idx, s.score, s.roi.center_px.x, s.roi.center_px.y, s.roi.size_px);
}

static void
xr_fusion_debug_write_row(HandTracking *hgt,
                             ht_view *view,
                             const hand_region_of_interest raw_outputs[2],
                             const hand_region_of_interest mirrored_outputs[2],
                             const std::vector<xr_fusion_candidate> &candidates,
                             const std::vector<xr_fusion_candidate> &selected,
                             const hand_region_of_interest final_outputs[2],
                             float min_conf,
                             float min_dist_px,
                             bool slot_swap)
{
	const char *path = getenv("MERCURY_XR_FUSION_DEBUG_CSV");
	if (path == nullptr || path[0] == '\0') {
		return;
	}

	static std::mutex mutex;
	static FILE *f = nullptr;
	static bool header_written = false;

	std::lock_guard<std::mutex> lock(mutex);

	if (f == nullptr) {
		f = fopen(path, "w");
		if (f == nullptr) {
			HG_WARN(hgt, "Failed to open MERCURY_XR_FUSION_DEBUG_CSV='%s'", path);
			return;
		}
	}

	if (!header_written) {
		fprintf(f,
		        "timestamp_ns,detection_counter,view_idx,min_conf,min_center_dist_px,slot_swap,"
		        "candidate_count,selected_count,"
		        "raw_left_found,raw_left_conf,raw_left_cx,raw_left_cy,raw_left_size_raw,raw_left_x,raw_left_y,raw_left_size_px,"
		        "raw_right_found,raw_right_conf,raw_right_cx,raw_right_cy,raw_right_size_raw,raw_right_x,raw_right_y,raw_right_size_px,"
		        "mir_left_found,mir_left_conf,mir_left_cx,mir_left_cy,mir_left_size_raw,mir_left_x,mir_left_y,mir_left_size_px,"
		        "mir_right_found,mir_right_conf,mir_right_cx,mir_right_cy,mir_right_size_raw,mir_right_x,mir_right_y,mir_right_size_px,"
		        "selected0_valid,selected0_source,selected0_source_hand_idx,selected0_assigned_slot,selected0_score,selected0_x,selected0_y,selected0_size_px,"
		        "selected1_valid,selected1_source,selected1_source_hand_idx,selected1_assigned_slot,selected1_score,selected1_x,selected1_y,selected1_size_px,"
		        "final0_found,final0_conf,final0_cx,final0_cy,final0_size_raw,final0_x,final0_y,final0_size_px,"
		        "final1_found,final1_conf,final1_cx,final1_cy,final1_size_raw,final1_x,final1_y,final1_size_px,"
		        "selected_center_dist_px,selected_iou\n");
		header_written = true;
	}

	float selected_dist = -1.0f;
	float selected_iou = -1.0f;
	if (selected.size() >= 2) {
		selected_dist = xr_roi_center_distance_px(selected[0].roi, selected[1].roi);
		selected_iou = xr_roi_iou_px(selected[0].roi, selected[1].roi);
	}

	fprintf(f, "%llu,%d,%d,%.9g,%.9g,%d,%zu,%zu,", (unsigned long long)hgt->current_frame_timestamp,
	        hgt->detection_counter, view->view, min_conf, min_dist_px, slot_swap ? 1 : 0, candidates.size(),
	        selected.size());

	xr_fusion_debug_write_roi_csv(f, raw_outputs[0]);
	fprintf(f, ",");
	xr_fusion_debug_write_roi_csv(f, raw_outputs[1]);
	fprintf(f, ",");
	xr_fusion_debug_write_roi_csv(f, mirrored_outputs[0]);
	fprintf(f, ",");
	xr_fusion_debug_write_roi_csv(f, mirrored_outputs[1]);
	fprintf(f, ",");
	xr_fusion_debug_write_selected_csv(f, selected, 0);
	fprintf(f, ",");
	xr_fusion_debug_write_selected_csv(f, selected, 1);
	fprintf(f, ",");
	xr_fusion_debug_write_roi_csv(f, final_outputs[0]);
	fprintf(f, ",");
	xr_fusion_debug_write_roi_csv(f, final_outputs[1]);
	fprintf(f, ",%.9g,%.9g\n", selected_dist, selected_iou);
	fflush(f);
}

static void
xr_put_rgb(std::vector<uint8_t> &rgb, int w, int h, int x, int y, uint8_t r, uint8_t g, uint8_t b)
{
	if (x < 0 || y < 0 || x >= w || y >= h) {
		return;
	}
	size_t idx = (size_t)(y * w + x) * 3;
	rgb[idx + 0] = r;
	rgb[idx + 1] = g;
	rgb[idx + 2] = b;
}

static void
xr_draw_rect_rgb(std::vector<uint8_t> &rgb,
                    int w,
                    int h,
                    const hand_region_of_interest &roi,
                    uint8_t r,
                    uint8_t g,
                    uint8_t b)
{
	if (!roi.found || roi.size_px <= 1.0f) {
		return;
	}
	int x0 = (int)lrintf(roi.center_px.x - roi.size_px * 0.5f);
	int x1 = (int)lrintf(roi.center_px.x + roi.size_px * 0.5f);
	int y0 = (int)lrintf(roi.center_px.y - roi.size_px * 0.5f);
	int y1 = (int)lrintf(roi.center_px.y + roi.size_px * 0.5f);

	for (int x = x0; x <= x1; x++) {
		xr_put_rgb(rgb, w, h, x, y0, r, g, b);
		xr_put_rgb(rgb, w, h, x, y1, r, g, b);
	}
	for (int y = y0; y <= y1; y++) {
		xr_put_rgb(rgb, w, h, x0, y, r, g, b);
		xr_put_rgb(rgb, w, h, x1, y, r, g, b);
	}

	int cx = (int)lrintf(roi.center_px.x);
	int cy = (int)lrintf(roi.center_px.y);
	for (int d = -4; d <= 4; d++) {
		xr_put_rgb(rgb, w, h, cx + d, cy, r, g, b);
		xr_put_rgb(rgb, w, h, cx, cy + d, r, g, b);
	}
}

static void
xr_fusion_debug_dump_overlay(HandTracking *hgt,
                                ht_view *view,
                                const hand_region_of_interest raw_outputs[2],
                                const hand_region_of_interest mirrored_outputs[2],
                                const hand_region_of_interest final_outputs[2])
{
	const char *dir = getenv("MERCURY_XR_FUSION_DUMP_OVERLAY_DIR");
	if (dir == nullptr || dir[0] == '\0') {
		return;
	}

	int every_n = xr_detector_env_int("MERCURY_XR_FUSION_DUMP_EVERY_N", 30);
	if (every_n <= 0) {
		every_n = 1;
	}
	if ((hgt->detection_counter % every_n) != 0) {
		return;
	}

	cv::Mat &gray = view->run_model_on_this;
	if (gray.empty() || gray.type() != CV_8UC1) {
		return;
	}

	const int w = gray.cols;
	const int h = gray.rows;
	std::vector<uint8_t> rgb((size_t)w * (size_t)h * 3);
	for (int y = 0; y < h; y++) {
		const uint8_t *row = gray.ptr<uint8_t>(y);
		for (int x = 0; x < w; x++) {
			uint8_t v = row[x];
			size_t idx = (size_t)(y * w + x) * 3;
			rgb[idx + 0] = v;
			rgb[idx + 1] = v;
			rgb[idx + 2] = v;
		}
	}

	// raw detector candidates: yellow/orange
	xr_draw_rect_rgb(rgb, w, h, raw_outputs[0], 255, 255, 0);
	xr_draw_rect_rgb(rgb, w, h, raw_outputs[1], 255, 165, 0);
	// mirrored detector candidates after unmirror: red/magenta
	xr_draw_rect_rgb(rgb, w, h, mirrored_outputs[0], 255, 0, 0);
	xr_draw_rect_rgb(rgb, w, h, mirrored_outputs[1], 255, 0, 255);
	// final selected slot outputs: green/cyan
	xr_draw_rect_rgb(rgb, w, h, final_outputs[0], 0, 255, 0);
	xr_draw_rect_rgb(rgb, w, h, final_outputs[1], 0, 255, 255);

	std::error_code ec;
	std::filesystem::create_directories(dir, ec);

	char path[2048];
	snprintf(path, sizeof(path), "%s/fusion_%012llu_det%06d_view%d.ppm", dir,
	         (unsigned long long)hgt->current_frame_timestamp, hgt->detection_counter, view->view);

	FILE *f = fopen(path, "wb");
	if (f == nullptr) {
		return;
	}
	fprintf(f, "P6\n%d %d\n255\n", w, h);
	fwrite(rgb.data(), 1, rgb.size(), f);
	fclose(f);
}

static void
xr_run_hand_detection_pass(hand_detection_run_info *info, bool mirror_horizontal, hand_region_of_interest outputs[2])
{
	ht_view *view = info->view;
	HandTracking *hgt = view->hgt;
	onnx_state *state = &view->detection;
	OnnxWrapper &wrap = *state->wrap;
	ultra_prof::Scope _prof_pass(ultra_prof::DET_PASS);
	const uint64_t _prof_t0 = ultra_prof::now();

	cv::Mat &orig_data = view->run_model_on_this;

	// XR diagnostic/compatibility mode:
	// Mirror only the detector input, then unmirror the detector ROI back into
	// the original raw image coordinate system. Keypoint estimation and the 3D
	// optimizer will still use the unmodified raw frames and raw calibration.
	// This is intentionally different from flipping the whole input pipeline.
	cv::Mat detector_input;
	if (mirror_horizontal) {
		cv::flip(orig_data, detector_input, 1);
	} else {
		detector_input = orig_data;
	}

	cv::Mat binned_uint8;

	xrt_size desired_bin_size;
	desired_bin_size.h = kDetectionInputSize;
	desired_bin_size.w = kDetectionInputSize;

	cv::Matx23f go_back = blackbar(detector_input, view->camera_info.camera_orientation, binned_uint8, desired_bin_size);

	cv::Mat binned_float_wrapper_mat(cv::Size(kDetectionInputSize, kDetectionInputSize),
	                                 CV_32FC1,             //
	                                 state->wraps[0].data, //
	                                 kDetectionInputSize * sizeof(float));

	normalizeGrayscaleImage(binned_uint8, binned_float_wrapper_mat);

	const OrtValue *inputs[] = {state->wraps[0].tensor};
	const char *input_names[] = {state->wraps[0].name};

	OrtValue *output_tensors[] = {nullptr, nullptr, nullptr, nullptr};
	const char *output_names[] = {"hand_exists", "cx", "cy", "size"};

	{
		XRT_TRACE_IDENT(model);
		static_assert(ARRAY_SIZE(input_names) == ARRAY_SIZE(inputs));
		static_assert(ARRAY_SIZE(output_names) == ARRAY_SIZE(output_tensors));
		ultra_prof::dump("det", state->wraps[0].data, kDetectionInputSize * kDetectionInputSize);
		const uint64_t _prof_t1 = ultra_prof::now();
		if (ultra_prof::state().on) ultra_prof::add(ultra_prof::DET_PRE, _prof_t1 - _prof_t0);
		ORT_SAFE(wrap, Run(wrap.session, nullptr, input_names, inputs, ARRAY_SIZE(input_names), output_names,
		                   ARRAY_SIZE(output_names), output_tensors));
		if (ultra_prof::state().on) ultra_prof::add(ultra_prof::DET_INFER, ultra_prof::now() - _prof_t1);
	}

	float *hand_exists = nullptr;
	float *cx = nullptr;
	float *cy = nullptr;
	float *sizee = nullptr;

	ORT_SAFE(wrap, GetTensorMutableData(output_tensors[0], (void **)&hand_exists));
	ORT_SAFE(wrap, GetTensorMutableData(output_tensors[1], (void **)&cx));
	ORT_SAFE(wrap, GetTensorMutableData(output_tensors[2], (void **)&cy));
	ORT_SAFE(wrap, GetTensorMutableData(output_tensors[3], (void **)&sizee));



	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		hand_region_of_interest &output = outputs[hand_idx];

		// Preserve raw detector outputs for offline diagnostics. These are the
		// values before min_detection_confidence/found and later ROI gates.
		output.xr_raw_hand_exists = hand_exists[hand_idx];
		output.xr_raw_cx = cx[hand_idx];
		output.xr_raw_cy = cy[hand_idx];
		output.xr_raw_size = sizee[hand_idx];

		output.found = hand_exists[hand_idx] > hgt->tuneable_values.min_detection_confidence.val;

		if (output.found) {
			output.hand_detection_confidence = hand_exists[hand_idx];

			xrt_vec2 _pt = {};
			_pt.x = math_map_ranges(cx[hand_idx], -1, 1, 0, kDetectionInputSize);
			_pt.y = math_map_ranges(cy[hand_idx], -1, 1, 0, kDetectionInputSize);

			float size = sizee[hand_idx];



			constexpr float fac = 2.0f;
			size *= kDetectionInputSize * fac;
			size *= m_vec2_len({go_back(0, 0), go_back(0, 1)});


			cv::Mat &debug_frame = view->debug_out_to_this;

			_pt = transformVecBy2x3(_pt, go_back);
			if (mirror_horizontal) {
				_pt.x = (float)(orig_data.cols - 1) - _pt.x;
			}

			output.center_px = _pt;
			output.size_px = size;

			if (hgt->debug_scribble) {
				handSquare(debug_frame, output.center_px, output.size_px, PINK);
			}
		}

		if (hgt->debug_scribble) {
			// note: this will multiply the model outputs by 255, don't do anything with them after this.
			int top_of_rect_y = kVisSpacerSize; // 8 + 128 + 8 + 128 + 8;
			int left_of_rect_x = kVisSpacerSize + ((kKeypointInputSize + kVisSpacerSize) * 4);
			int start_y = top_of_rect_y + ((kDetectionInputSize + kVisSpacerSize) * view->view);
			cv::Rect p = cv::Rect(left_of_rect_x, start_y, kDetectionInputSize, kDetectionInputSize);

			binned_uint8.copyTo(hgt->visualizers.mat(p));
		}
	}

	for (size_t i = 0; i < ARRAY_SIZE(output_tensors); i++) {
		wrap.api->ReleaseValue(output_tensors[i]);
	}
}

// ---- libultra_hands: the detector also looks through fisheye-corrected tiles.
//
// The detector is given the whole fisheye picture squeezed to 160x160. A hand low in the picture or at its side is
// small, stretched and cut off there, and is not found (measured on the wearer's recordings: tools/tracking/
// hand_bench.py). So, for a hand that is not being tracked and that the whole picture did not show, a few ordinary
// (pinhole) views of 70 degrees are cut out of the fisheye picture and the same detector is run on each: three ahead
// and up, three ahead and down, three at chest height, the low and the side ones oftener. What a tile finds is put
// back into the fisheye picture's pixels and goes on as any detection does: the keypoint model and the fit decide
// whether it is a hand.
//   ULTRA_HANDS_TILES            tiles looked through each time the detector runs on a view (default 1, 0 = off).
//                                Three found hands sooner in a test that took every frame however long it took, and
//                                cost so much in the app that frames were dropped and fewer hands were tracked.
//   ULTRA_HANDS_TILES_MIN_CONF   the least confidence a tile's detection needs (default 0.5)
namespace {

constexpr int kUltraTile = 160;
constexpr float kUltraTileFovDeg = 70.0f;

struct UltraTile
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

UltraTiles ultra_tiles[2];

int
ultra_tiles_per_call()
{
	int n = -1; // read each time: see ultra_verify
	if (n < 0) {
		const char *e = std::getenv("ULTRA_HANDS_TILES");
		n = (e != nullptr && e[0] != '\0') ? std::atoi(e) : 1;
		n = std::max(0, std::min(12, n));
	}
	return n;
}

float
ultra_tiles_min_conf()
{
	float c = -1.0f;
	if (c < 0.0f) {
		const char *e = std::getenv("ULTRA_HANDS_TILES_MIN_CONF");
		c = (e != nullptr && e[0] != '\0') ? (float)std::atof(e) : 0.5f;
		c = std::max(0.0f, std::min(1.0f, c));
	}
	return c;
}

struct UltraSpec
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

static int
ultra_recall_mode()
{
	static const int mode = []() {
		const char *e = std::getenv("ULTRA_HANDS_RECALL");
		return (e != nullptr && e[0] != 0) ? (e[0] - '0') : 0;
	}();
	return mode;
}
// ULTRA_HANDS_TRUST_TILES=1: a tile's find (confidence >= 0.5) begins a candidate without the other camera's word or a second look.
// The keypoint network and the settled-fit gate still decide whether it is ever shown.
static bool
ultra_trust_tiles()
{
	static const bool on = std::getenv("ULTRA_HANDS_TRUST_TILES") != nullptr;
	return on;
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

} // namespace

static void xr_run_hand_detection_pass(hand_detection_run_info *info, bool mirror_horizontal, hand_region_of_interest outputs[2]);

// A second look at what a tile found. The detector takes dark things of the right shape for hands through a tile
// (the stands beside the wearer's laptop, at 0.92), and the keypoint model then agrees with it; a higher confidence
// does not tell them from hands. Looking again does: four views aimed straight at the find (70 and 95 degrees wide,
// and 70 turned 25 degrees either way), of which three have to say "hand" over 0.5. Measured through the whole
// tracker on three of the wearer's recordings (docs/HAND_TRACKING.md): with three of four the stands start no hand
// and low hands over the desk are found; with two of four the stands start one again (a hand reported where there
// is none in 35 % of pairs); with all four the low hands are not found. A find that fails is left alone for half
// a second, so that a thing that keeps being found does not cost four runs of the detector every frame.
//   ULTRA_HANDS_VERIFY=0         no second looks
//   ULTRA_HANDS_VERIFY_MIN=0.5   what a second look has to say at least
//   ULTRA_HANDS_VERIFY_NEED=3    how many of the four have to say it
static float
ultra_second_look(hand_detection_run_info *info, const cv::Mat &whole, float px, float py, float fov_deg, float roll_deg, int hand)
{
	ht_view *view = info->view;
	float dx = 0, dy = 0, dz = 1;
	if (!t_camera_models_unproject(&view->hgdist, px, py, &dx, &dy, &dz)) {
		return 0.0f;
	}
	const float a = atan2f(dx, dz), b = atan2f(dy, sqrtf(dx * dx + dz * dz));
	const float ca = cosf(a), sa = sinf(a), cb = cosf(b), sb = sinf(b);
	const float R[9] = {ca, -sa * sb, sa * cb, 0.0f, cb, sb, -sa, -ca * sb, ca * cb};
	const float f = (float)kUltraTile / 2.0f / tanf(fov_deg * (float)M_PI / 360.0f);
	const float cr = cosf(roll_deg * (float)M_PI / 180.0f), sr = sinf(roll_deg * (float)M_PI / 180.0f);
	// Worked out at every fourth pixel and filled in between: the mapping is smooth, and this is a sixteenth of the
	// work (it was most of what a second look cost).
	constexpr int kStep = 4, kGrid = kUltraTile / kStep + 1;
	cv::Mat grid_x(kGrid, kGrid, CV_32FC1), grid_y(kGrid, kGrid, CV_32FC1);
	for (int v = 0; v < kGrid; v++) {
		for (int u = 0; u < kGrid; u++) {
			const float x0 = ((float)(u * kStep) - kUltraTile / 2.0f) / f, y0 = ((float)(v * kStep) - kUltraTile / 2.0f) / f;
			const float x = cr * x0 - sr * y0, y = sr * x0 + cr * y0;
			float qx = -1.0f, qy = -1.0f;
			t_camera_models_project(&view->hgdist, R[0] * x + R[1] * y + R[2], R[3] * x + R[4] * y + R[5],
			                        R[6] * x + R[7] * y + R[8], &qx, &qy);
			if (!std::isfinite(qx) || !std::isfinite(qy)) {
				qx = -1.0f;
				qy = -1.0f;
			}
			grid_x.at<float>(v, u) = qx;
			grid_y.at<float>(v, u) = qy;
		}
	}
	cv::Mat map_x, map_y;
	cv::resize(grid_x, map_x, cv::Size(kUltraTile + kStep, kUltraTile + kStep), 0, 0, cv::INTER_LINEAR);
	cv::resize(grid_y, map_y, cv::Size(kUltraTile + kStep, kUltraTile + kStep), 0, 0, cv::INTER_LINEAR);
	cv::Mat tile;
	cv::remap(whole, tile, map_x(cv::Rect(kStep / 2, kStep / 2, kUltraTile, kUltraTile)), map_y(cv::Rect(kStep / 2, kStep / 2, kUltraTile, kUltraTile)),
	          cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0));
	hand_region_of_interest out[2] = {};
	view->run_model_on_this = tile;
	try {
		xr_run_hand_detection_pass(info, false, out);
	} catch (...) {
		view->run_model_on_this = whole;
		throw;
	}
	view->run_model_on_this = whole;
	return out[hand].xr_raw_hand_exists;
}

static bool
ultra_verify(hand_detection_run_info *info, const cv::Mat &whole, float px, float py, int hand)
{
	const bool on = []() {
		const char *e = std::getenv("ULTRA_HANDS_VERIFY");
		return e == nullptr || e[0] != '0';
	}();
	const float least = []() {
		const char *e = std::getenv("ULTRA_HANDS_VERIFY_MIN");
		return (e != nullptr && e[0] != '\0') ? (float)std::atof(e) : 0.5f;
	}();
	if (!on) {
		return true;
	}
	const float looks[4][2] = {{70.0f, 0.0f}, {95.0f, 0.0f}, {70.0f, 25.0f}, {70.0f, -25.0f}};
	const bool say = std::getenv("ULTRA_HANDS_DEBUG") != nullptr;
	// How many of the four have to agree (ULTRA_HANDS_VERIFY_NEED).
	const int need = []() {
		const char *e = std::getenv("ULTRA_HANDS_VERIFY_NEED");
		int n = (e != nullptr && e[0] != 0) ? std::atoi(e) : 3;
		return std::max(1, std::min(4, n));
	}();
	float got[4] = {-1, -1, -1, -1};
	int agree = 0;
	for (int i = 0; i < 4; i++) {
		got[i] = ultra_second_look(info, whole, px, py, looks[i][0], looks[i][1], hand);
		if (got[i] >= least) {
			agree++;
		}
		// no need to look on once it is decided
		if (!say && (agree >= need || agree + (3 - i) < need)) {
			break;
		}
	}
	bool ok = agree >= need;
	if (say) {
		fprintf(stderr, "ultra verify: t %.3f view %d hand %d at %.0f,%.0f looks %.2f %.2f %.2f %.2f -> %d\n", (double)info->view->hgt->current_frame_timestamp / 1e9 - 1.0, info->view->view, hand, px, py, got[0], got[1], got[2], got[3], ok ? 1 : 0);
	}
	return ok;
}

// Where a find last failed its second looks, for each view and hand, and until which run of the detector it is left alone.
static struct
{
	float x = 0, y = 0;
	int until = 0;
} ultra_failed[2][2];

// Where a find last passed its second looks, for each view and hand, and at which run of the detector.
static struct
{
	float x = 0, y = 0;
	int at = -1000;
} ultra_passed[2][2];

// What the tiles of each view last found for each hand, before any second look: where, and at which run of the detector.
static struct UltraSeen
{
	float x = 0, y = 0;
	int at = -1000;
} ultra_seen[2][2];
static std::mutex ultra_seen_mutex;

// A tile's find that the other camera's tiles bear out: within a second the other camera found the same hand, and the
// two sight lines meet within 5 cm of each other between 12 and 85 cm from the cameras, where a hand of the wearer's
// is and a thing across the room is not. Cold starts on the wearer's recording of 22 November 2026 (48 moments with a
// hand plainly in both pictures, four seconds each): the whole-picture detector saw nothing of a low hand in any
// frame, the tiles found it nine times in four seconds, first in one camera and then in the other, and all nine
// failed the three-of-four second looks; no hand began in 23 of the 48. Such a find needs no second looks and may
// begin the hand from its one view.
//   ULTRA_HANDS_STEREO_SEEN=0   off
static bool
ultra_stereo_seen(ht_view *view, int hand, float px, float py)
{
	static const bool on = []() {
		const char *e = std::getenv("ULTRA_HANDS_STEREO_SEEN");
		return e == nullptr || e[0] != '0';
	}();
	HandTracking *hgt = view->hgt;
	const int v = view->view & 1;
	UltraSeen other;
	{
		std::lock_guard<std::mutex> lock(ultra_seen_mutex);
		other = ultra_seen[!v][hand];
		ultra_seen[v][hand].x = px;
		ultra_seen[v][hand].y = py;
		ultra_seen[v][hand].at = hgt->detection_counter;
	}
	if (!on) {
		return false;
	}
	const int age = hgt->detection_counter - other.at;
	if (age < 0 || age > 30) {
		return false;
	}
	ht_view *vo = &hgt->views[!v];
	Eigen::Vector3f da = Eigen::Vector3f::Zero(), db = Eigen::Vector3f::Zero();
	if (!t_camera_models_unproject_and_flip(&view->hgdist, px, py, &da.x(), &da.y(), &da.z()) ||
	    !t_camera_models_unproject_and_flip(&vo->hgdist, other.x, other.y, &db.x(), &db.y(), &db.z())) {
		return false;
	}
	da.normalize();
	db.normalize();
	// Both sight lines in the left camera's frame (a point of the left camera's is q p + t in the right one's).
	const Eigen::Quaternionf q = map_quat(hgt->left_in_right.orientation);
	const Eigen::Vector3f t = map_vec3(hgt->left_in_right.position);
	Eigen::Vector3f o1 = Eigen::Vector3f::Zero(), d1 = da, o2 = q.conjugate() * (-t), d2 = q.conjugate() * db;
	if (v == 1) {
		o1 = q.conjugate() * (-t);
		d1 = q.conjugate() * da;
		o2 = Eigen::Vector3f::Zero();
		d2 = db;
	}
	const Eigen::Vector3f w = o1 - o2;
	const float b = d1.dot(d2), d = d1.dot(w), e = d2.dot(w);
	const float den = 1.0f - b * b;
	if (den < 1e-5f) {
		return false;
	}
	const float s1 = (b * e - d) / den, s2 = (e - b * d) / den;
	if (s1 <= 0.0f || s2 <= 0.0f) {
		return false;
	}
	const float miss = ((o1 + s1 * d1) - (o2 + s2 * d2)).norm();
	bool ok = s1 >= 0.12f && s1 <= 0.85f && s2 >= 0.12f && s2 <= 0.85f && miss <= 0.05f;
	// Not the other hand over again: on the recording of 17 November the left hand, held up and tracked, was found
	// by the tiles as a right hand too, and reported as both for 13 s. A find within 12 cm of any joint of the other
	// hand, where that one is tracked, is that hand.
	if (ok && hgt->last_frame_hand_detected[!hand]) {
		const Eigen::Vector3f at = ((o1 + s1 * d1) + (o2 + s2 * d2)) * 0.5f;
		float nearest = 1e9f;
		for (int i = 0; i < 21; i++) {
			const Eigen::Vector3f k = hgt->pose_predicted_keypoints[!hand].col(i);
			if (k.allFinite()) {
				nearest = std::min(nearest, (k - at).norm());
			}
		}
		if (std::getenv("ULTRA_HANDS_DEBUG") != nullptr) {
			const Eigen::Vector3f k0 = hgt->pose_predicted_keypoints[!hand].col(0);
			fprintf(stderr, "ultra stereo: the find is at %.2f %.2f %.2f, the other hand's wrist at %.2f %.2f %.2f, its nearest joint %.2f m off\n", at.x(), at.y(), at.z(), k0.x(), k0.y(), k0.z(), nearest);
		}
		if (nearest < 0.12f) {
			ok = false;
		}
	}
	if (std::getenv("ULTRA_HANDS_DEBUG") != nullptr) {
		fprintf(stderr, "ultra stereo: t %.3f view %d hand %d at %.0f,%.0f and the other view's %.0f,%.0f of %d runs ago: %.2f m and %.2f m off, within %.3f m -> %d\n",
		        (double)hgt->current_frame_timestamp / 1e9 - 1.0, v, hand, px, py, other.x, other.y, age, s1, s2, miss, ok ? 1 : 0);
	}
	return ok;
}

static void
ultra_tiles_pass(hand_detection_run_info *info)
{
	const int per_call = ultra_tiles_per_call();
	if (per_call <= 0) {
		return;
	}
	ht_view *view = info->view;
	HandTracking *hgt = view->hgt;
	// Only for a hand that is not being tracked and that the whole picture did not show.
	bool need[2];
	for (int h = 0; h < 2; h++) {
		need[h] = !info->outputs[h].found && !hgt->last_frame_hand_detected[h];
	}
	if (!need[0] && !need[1]) {
		return;
	}
	cv::Mat whole = view->run_model_on_this; // a header on the same picture
	if (whole.empty() || whole.type() != CV_8UC1) {
		return;
	}
	UltraTiles &T = ultra_tiles[view->view & 1];
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
		cv::Mat tile;
		cv::remap(whole, tile, t.map_x, t.map_y, cv::INTER_LINEAR, cv::BORDER_CONSTANT, cv::Scalar(0));
		hand_region_of_interest out[2] = {};
		for (int h = 0; h < 2; h++) {
			out[h].found = false;
			out[h].hand_detection_confidence = 0;
			out[h].provenance = ROIProvenance::HAND_DETECTION;
		}
		view->run_model_on_this = tile;
		try {
			xr_run_hand_detection_pass(info, false, out);
		} catch (...) {
			view->run_model_on_this = whole;
			throw;
		}
		view->run_model_on_this = whole;
		for (int h = 0; h < 2; h++) {
			if (!need[h] || !out[h].found) {
				continue;
			}
			const float conf = out[h].xr_raw_hand_exists;
			if (conf < min_conf || conf <= best[h]) {
				continue;
			}
			// Its place in the tile, as a place of the fisheye picture.
			const float x = (out[h].center_px.x - kUltraTile / 2.0f) / t.f;
			const float y = (out[h].center_px.y - kUltraTile / 2.0f) / t.f;
			const float dx = t.R[0] * x + t.R[1] * y + t.R[2];
			const float dy = t.R[3] * x + t.R[4] * y + t.R[5];
			const float dz = t.R[6] * x + t.R[7] * y + t.R[8];
			float px = -1.0f, py = -1.0f;
			if (!t_camera_models_project(&view->hgdist, dx, dy, dz, &px, &py)) {
				continue;
			}
			if (!(px >= 0.0f && px < (float)whole.cols && py >= 0.0f && py < (float)whole.rows)) {
				continue;
			}
			// The other hand, if it is being tracked, is found through the tiles too, and often as this one.
			// The gate would turn that away as an overlap, after four second looks had been spent on it in
			// every frame (it was most of what a frame cost while one hand was tracked). It is left out here.
			{
				const hand_region_of_interest &other = view->regions_of_interest_this_frame[!h];
				if (hgt->last_frame_hand_detected[!h] && other.found && other.size_px > 0.0f &&
				    fabsf(px - other.center_px.x) < other.size_px * 0.75f && fabsf(py - other.center_px.y) < other.size_px * 0.75f) {
					continue;
				}
			}
			// A place where a hand that a tile began was dropped within a few frames a moment ago: not believed again at once.
			if (sched == 1 && ultra_phantom::near(view->view, px, py, counter, 55.0f)) {
				continue;
			}
			// A hand that was being tracked and was lost a moment ago, found again where it was or where it was going: the same hand. It
			// needs neither the other camera's tiles to have seen it nor a second look (a stand beside a laptop is not where a hand was).
			// ULTRA_HANDS_RECALL=1
			if (sched == 1 && ((ultra_recall_mode() == 1 && counter - T.mem[h].lost_at <= 45) || ultra_trust_tiles())) {
				const UltraMemory &m = T.mem[h];
				const float age = (float)std::min(counter - m.lost_at, 8);
				const float ex = m.last.x + m.vel.x * age, ey = m.last.y + m.vel.y * age;
				if (ultra_trust_tiles() || hypotf(px - ex, py - ey) < 80.0f || hypotf(px - m.last.x, py - m.last.y) < 80.0f) {
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
				out[h].ultra_tile = true;
				out[h].center_px.x = px;
				out[h].center_px.y = py;
				out[h].size_px = out[h].size_px * view->hgdist.fx / t.f;
				info->outputs[h] = out[h];
				best[h] = conf;
				continue;
			}
			auto &failed = ultra_failed[view->view & 1][h];
			if (failed.until - hgt->detection_counter > 15) {
				failed.until = 0;
			}
			if (hgt->detection_counter < failed.until && fabsf(px - failed.x) < 45.0f && fabsf(py - failed.y) < 45.0f) {
				continue;
			}
			if (!ultra_verify(info, whole, px, py, h)) {
				failed.x = px;
				failed.y = py;
				failed.until = hgt->detection_counter + 15;
				continue;
			}
			// Passed twice in the same place within thirty runs of the detector (a second): in the wearer's recordings that
			// happens for hands (20 times in the eight seconds of low hands) and never where there is none
			// (the stands beside the laptop: 42 finds, none passed once). Such a find may begin a hand from
			// this one camera (hg_sync.cpp, the detection gate).
			auto &passed = ultra_passed[view->view & 1][h];
			if (passed.at > hgt->detection_counter) {
				passed.at = -1000;
			}
			out[h].ultra_twice = hgt->detection_counter - passed.at <= 30 && hgt->detection_counter != passed.at &&
			                     fabsf(px - passed.x) < 50.0f && fabsf(py - passed.y) < 50.0f;
			passed.x = px;
			passed.y = py;
			passed.at = hgt->detection_counter;
			out[h].ultra_tile = true;
			out[h].center_px.x = px;
			out[h].center_px.y = py;
			// As many pixels to an angle as the fisheye picture has there (it is near enough equidistant).
			out[h].size_px = out[h].size_px * view->hgdist.fx / t.f;
			info->outputs[h] = out[h];
			best[h] = conf;
		}
	}
}

static void
run_hand_detection_unsafe(hand_detection_run_info *info)
{
	const bool xr_detector_fusion = xr_detector_env_flag_enabled("MERCURY_XR_DETECTOR_FUSION");
	const bool xr_detector_swap_slots = xr_detector_env_flag_enabled("MERCURY_XR_DETECTOR_SWAP_SLOTS");

	if (!xr_detector_fusion) {
		const bool mirror_horizontal =
		    xr_detector_env_flag_enabled("MERCURY_XR_DETECTOR_MIRROR_HORIZONTAL");
		xr_run_hand_detection_pass(info, mirror_horizontal, info->outputs);
		{
			// libultra_hands: ULTRA_HANDS_TILES_WHEN_ONE=0 leaves the tiles and second looks out of the frames in which a hand is
			// being tracked already: they are for finding a hand low in the picture, and cost about 5 ms a frame
			static const bool tiles_when_one = []() {
				const char *e = std::getenv("ULTRA_HANDS_TILES_WHEN_ONE");
				return e == nullptr || e[0] != '0';
			}();
			HandTracking *hg = info->view->hgt;
			const bool one = hg->last_frame_hand_detected[0] || hg->last_frame_hand_detected[1];
			if (tiles_when_one || !one) {
				ultra_prof::Scope _prof_tiles(ultra_prof::DET_TILES);
				ultra_tiles_pass(info);
			}
		}
		if (xr_detector_swap_slots) {
			std::swap(info->outputs[0], info->outputs[1]);
		}
		return;
	}

	ht_view *view = info->view;
	HandTracking *hgt = view->hgt;

	hand_region_of_interest raw_outputs[2] = {};
	hand_region_of_interest mirrored_outputs[2] = {};
	xr_run_hand_detection_pass(info, false, raw_outputs);
	xr_run_hand_detection_pass(info, true, mirrored_outputs);

	std::vector<xr_fusion_candidate> candidates;
	candidates.reserve(4);

	const float min_conf = xr_detector_env_float(
	    "MERCURY_XR_FUSION_MIN_CONF", hgt->tuneable_values.min_detection_confidence.val);
	const float min_dist_px = xr_detector_env_float("MERCURY_XR_FUSION_MIN_CENTER_DIST_PX", 70.0f);

	auto add_candidate = [&](const hand_region_of_interest &roi, int source, int hand_idx) {
		float score = roi.xr_raw_hand_exists;
		if (score < min_conf || !roi.found) {
			return;
		}
		xr_fusion_candidate c = {};
		c.roi = roi;
		c.score = score;
		c.source = source;
		c.source_hand_idx = hand_idx;
		candidates.push_back(c);
	};

	for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
		add_candidate(raw_outputs[hand_idx], 0, hand_idx);
		add_candidate(mirrored_outputs[hand_idx], 1, hand_idx);
	}

	const bool xr_detector_fusion_stereo_pairs =
	    xr_detector_env_flag_enabled("MERCURY_XR_DETECTOR_FUSION_STEREO_PAIRS");

	if (xr_detector_fusion_stereo_pairs) {
		// Fusion v1 selected candidates independently per view. That can assign
		// slot0=view0/raw_right but slot0=view1/mir_left, which is invalid for a
		// stereo optimizer. Fusion v2 keeps source-kind pairs together across both
		// views: raw_right is selected as raw_right(view0)+raw_right(view1), etc.
		static std::mutex stereo_mutex;
		static xr_stereo_fusion_frame_cache stereo_cache;

		std::lock_guard<std::mutex> lock(stereo_mutex);
		uint64_t ts = (uint64_t)hgt->current_frame_timestamp;
		if (stereo_cache.hgt != hgt || stereo_cache.timestamp_ns != ts) {
			stereo_cache = {};
			stereo_cache.hgt = hgt;
			stereo_cache.timestamp_ns = ts;
		}

		int view_idx = view->view;
		if (view_idx < 0 || view_idx > 1) {
			view_idx = 0;
		}

		xr_stereo_fusion_view_cache &entry = stereo_cache.views[view_idx];
		entry.valid = true;
		entry.view = view;
		entry.outputs = info->outputs;
		std::copy(std::begin(raw_outputs), std::end(raw_outputs), std::begin(entry.raw_outputs));
		std::copy(std::begin(mirrored_outputs), std::end(mirrored_outputs), std::begin(entry.mirrored_outputs));
		entry.candidates = candidates;

		// Fallback for the first view that returns before the other view has populated
		// the frame cache. The second view overwrites both info structs before
		// u_worker_group_wait_all() returns to hg_sync.cpp.
		std::vector<xr_fusion_candidate> fallback = xr_per_view_select_candidates(candidates, min_dist_px);
		xr_apply_selected_to_outputs(fallback, info->outputs);
		if (xr_detector_swap_slots) {
			std::swap(info->outputs[0], info->outputs[1]);
		}

		if (stereo_cache.views[0].valid && stereo_cache.views[1].valid) {
			std::vector<xr_fusion_pair_candidate> selected_pairs =
			    xr_select_stereo_pairs(stereo_cache.views, min_conf, min_dist_px);
			xr_apply_pair_selection_to_outputs(selected_pairs, stereo_cache.views, xr_detector_swap_slots);

			for (int vi = 0; vi < 2; vi++) {
				std::vector<xr_fusion_candidate> selected_debug;
				xr_build_selected_debug_for_view(selected_pairs, vi, selected_debug);
				xr_fusion_debug_write_row(hgt, stereo_cache.views[vi].view, stereo_cache.views[vi].raw_outputs,
				                            stereo_cache.views[vi].mirrored_outputs, stereo_cache.views[vi].candidates,
				                            selected_debug, stereo_cache.views[vi].outputs, min_conf, min_dist_px,
				                            xr_detector_swap_slots);
				xr_fusion_debug_dump_overlay(hgt, stereo_cache.views[vi].view, stereo_cache.views[vi].raw_outputs,
				                                stereo_cache.views[vi].mirrored_outputs, stereo_cache.views[vi].outputs);
			}
		}

		if (hgt->debug_scribble) {
			for (int hand_idx = 0; hand_idx < 2; hand_idx++) {
				if (info->outputs[hand_idx].found) {
					handSquare(view->debug_out_to_this, info->outputs[hand_idx].center_px,
					           info->outputs[hand_idx].size_px, colors[hand_idx]);
				}
			}
		}
		return;
	}

	std::vector<xr_fusion_candidate> selected = xr_per_view_select_candidates(candidates, min_dist_px);
	xr_apply_selected_to_outputs(selected, info->outputs);

	if (xr_detector_swap_slots) {
		std::swap(info->outputs[0], info->outputs[1]);
	}

	xr_fusion_debug_write_row(hgt, view, raw_outputs, mirrored_outputs, candidates, selected, info->outputs, min_conf,
	                            min_dist_px, xr_detector_swap_slots);
	xr_fusion_debug_dump_overlay(hgt, view, raw_outputs, mirrored_outputs, info->outputs);

	if (hgt->debug_scribble) {
		for (size_t i = 0; i < selected.size() && i < 2; i++) {
			handSquare(view->debug_out_to_this, info->outputs[i].center_px, info->outputs[i].size_px, colors[i]);
		}
	}
}

void
run_hand_detection(void *ptr)
{
	XRT_TRACE_MARKER();

	hand_detection_run_info *info = (hand_detection_run_info *)ptr;
	try {
		run_hand_detection_unsafe(info);
	} catch (const std::exception &e) {
		HG_ERROR(info->view->hgt, "Exception while running hand detection: %s", e.what());

		// Report nothing found if hand detection hits an exception
		for (auto &output : info->outputs) {
			output.found = false;
		}
	}
}

void
init_keypoint_estimation(HandTracking *hgt, onnx_state *state)
{

	std::filesystem::path path = hgt->models_folder;

	path /= "grayscale_keypoint_jan18.onnx";

	state->wraps.clear();

	state->wrap = std::make_unique<OnnxWrapper>(hgt->log_level, path.string(), "monado_ht");

	OnnxWrapper &wrap = *state->wrap;

	// size_t input_size = wrap->input_shape[0] * wrap->input_shape[1] * wrap->input_shape[2] *
	// wrap->input_shape[3];

	// wrap->data = (float *)malloc(input_size * sizeof(float));
	{
		model_input_wrap inputimg = {};
		inputimg.name = "inputImg";
		inputimg.dimensions[0] = 1;
		inputimg.dimensions[1] = 1;
		inputimg.dimensions[2] = 128;
		inputimg.dimensions[3] = 128;
		inputimg.num_dimensions = 4;

		inputimg.data = (float *)malloc(128 * 128 * sizeof(float)); // SORRY IM BUSY



		ORT_SAFE(wrap, CreateTensorWithDataAsOrtValue(wrap.meminfo,                        //
		                                              inputimg.data,                       //
		                                              128 * 128 * sizeof(float),           //
		                                              inputimg.dimensions,                 //
		                                              inputimg.num_dimensions,             //
		                                              ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, //
		                                              &inputimg.tensor));

		assert(inputimg.tensor);
		int is_tensor;
		ORT_SAFE(wrap, IsTensor(inputimg.tensor, &is_tensor));
		assert(is_tensor);

		state->wraps.push_back(inputimg);
	}

	{
		model_input_wrap inputimg = {};
		inputimg.name = "lastKeypoints";
		inputimg.dimensions[0] = 1;
		inputimg.dimensions[1] = 42;
		inputimg.num_dimensions = 2;

		inputimg.data = (float *)malloc(1 * 42 * sizeof(float)); // SORRY IM BUSY



		ORT_SAFE(wrap, CreateTensorWithDataAsOrtValue(wrap.meminfo,                        //
		                                              inputimg.data,                       //
		                                              1 * 42 * sizeof(float),              //
		                                              inputimg.dimensions,                 //
		                                              inputimg.num_dimensions,             //
		                                              ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, //
		                                              &inputimg.tensor));

		assert(inputimg.tensor);
		int is_tensor;
		ORT_SAFE(wrap, IsTensor(inputimg.tensor, &is_tensor));
		assert(is_tensor);
		state->wraps.push_back(inputimg);
	}

	{
		model_input_wrap inputimg = {};
		inputimg.name = "useLastKeypoints";
		inputimg.dimensions[0] = 1;
		inputimg.num_dimensions = 1;

		inputimg.data = (float *)malloc(1 * sizeof(float)); // SORRY IM BUSY



		ORT_SAFE(wrap, CreateTensorWithDataAsOrtValue(wrap.meminfo,                        //
		                                              inputimg.data,                       //
		                                              1 * sizeof(float),                   //
		                                              inputimg.dimensions,                 //
		                                              inputimg.num_dimensions,             //
		                                              ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, //
		                                              &inputimg.tensor));

		assert(inputimg.tensor);
		int is_tensor;
		ORT_SAFE(wrap, IsTensor(inputimg.tensor, &is_tensor));
		assert(is_tensor);
		state->wraps.push_back(inputimg);
	}
}

enum xrt_hand_joint joints_ml_to_xr[21]{
    XRT_HAND_JOINT_WRIST,               //
    XRT_HAND_JOINT_THUMB_METACARPAL,    //
    XRT_HAND_JOINT_THUMB_PROXIMAL,      //
    XRT_HAND_JOINT_THUMB_DISTAL,        //
    XRT_HAND_JOINT_THUMB_TIP,           //
                                        //
    XRT_HAND_JOINT_INDEX_PROXIMAL,      //
    XRT_HAND_JOINT_INDEX_INTERMEDIATE,  //
    XRT_HAND_JOINT_INDEX_DISTAL,        //
    XRT_HAND_JOINT_INDEX_TIP,           //
                                        //
    XRT_HAND_JOINT_MIDDLE_PROXIMAL,     //
    XRT_HAND_JOINT_MIDDLE_INTERMEDIATE, //
    XRT_HAND_JOINT_MIDDLE_DISTAL,       //
    XRT_HAND_JOINT_MIDDLE_TIP,          //
                                        //
    XRT_HAND_JOINT_RING_PROXIMAL,       //
    XRT_HAND_JOINT_RING_INTERMEDIATE,   //
    XRT_HAND_JOINT_RING_DISTAL,         //
    XRT_HAND_JOINT_RING_TIP,            //
                                        //
    XRT_HAND_JOINT_LITTLE_PROXIMAL,     //
    XRT_HAND_JOINT_LITTLE_INTERMEDIATE, //
    XRT_HAND_JOINT_LITTLE_DISTAL,       //
    XRT_HAND_JOINT_LITTLE_TIP,          //
};

void
make_keypoint_heatmap_output(int camera_idx, int hand_idx, int grid_pt_x, int grid_pt_y, float *plane, cv::Mat &out)
{
	int root_x = 8 + ((1 + 2 * hand_idx) * (128 + 8));
	int root_y = 8 + ((2 * camera_idx) * (128 + 8));

	int org_x = (root_x) + (grid_pt_x * 25);
	int org_y = (root_y) + (grid_pt_y * 25);
	cv::Rect p = cv::Rect(org_x, org_y, 22, 22);


	cv::Mat start(cv::Size(22, 22), CV_32FC1, plane, 22 * sizeof(float));
	start *= 255.0;

	start.copyTo(out(p));
}

void
make_keypoint_depth_heatmap_output(int camera_idx, //
                                   int hand_idx,   //
                                   int grid_pt_x,  //
                                   int grid_pt_y,
                                   float *plane, //
                                   cv::Mat &out)
{
	int root_x = 8 + ((1 + 2 * hand_idx) * (128 + 8));
	int root_y = 8 + ((1 + 2 * camera_idx) * (128 + 8));

	int org_x = (root_x) + (grid_pt_x * 25);
	int org_y = (root_y) + (grid_pt_y * 25);


	cv::Rect p = cv::Rect(org_x, org_y, 22, 22);


	cv::Mat start(cv::Size(22, 22), CV_32FC1);
	float *ptr = (float *)start.data; // Cope cope cope cope cope


	for (int i = 0; i < 22; i++) {
		for (int j = 0; j < 22; j++) {
			ptr[(i * 22) + j] = plane[i];
		}
	}

	start *= 255.0;

	start.copyTo(out(p));
}

void
set_predicted_zero(float *data)
{
	for (int i = 0; i < 42; i++) {
		data[i] = 0.0f;
	}
}

static void
run_keypoint_estimation_unsafe(keypoint_estimation_run_info &info)
{
	ultra_prof::Scope _prof_kp(ultra_prof::KP_RUN);
	const uint64_t _prof_k0 = ultra_prof::now();
	onnx_state *state = &info.view->keypoint[info.hand_idx];
	OnnxWrapper &wrap = *state->wrap;

	struct HandTracking *hgt = info.view->hgt;

	int view_idx = info.view->view;
	int hand_idx = info.hand_idx;
	one_frame_one_view &this_output = hgt->keypoint_outputs[hand_idx].views[view_idx];
	if (hgt->xr_tracking_diag.enabled()) {
		hgt->xr_tracking_diag.frame.hands[hand_idx].views[view_idx].model_ran = 1;
	}
	MLOutput2D &px_coord = this_output.keypoints_in_scaled_stereographic;
	// Factor out starting here

	hand_region_of_interest &output = info.view->regions_of_interest_this_frame[hand_idx];

	cv::Mat data_128x128_uint8;

	projection_instructions instr(info.view->hgdist);
	instr.rot_quat = Eigen::Quaternionf::Identity();
	instr.stereographic_radius = 0.4;



	t_camera_model_params dist = info.view->hgdist;


	float twist = 0;

	if (output.provenance == ROIProvenance::HAND_DETECTION) {

		xrt_vec3 center;

		xrt_vec3 edges[4];


		t_camera_models_unproject_and_flip(&hgt->views[view_idx].hgdist, output.center_px.x, output.center_px.y,
		                                   &center.x, &center.y, &center.z);

		xrt_vec2 r = {output.size_px / 2, 0};
		xrt_vec2 d = {0, output.size_px / 2};
		xrt_vec2 v;

		// note! we do not need to rotate this! it's *already* in camera space.
		int acc_idx = 0;
		v = output.center_px + r + d;
		t_camera_models_unproject_and_flip(&hgt->views[view_idx].hgdist, v.x, v.y, &edges[acc_idx].x,
		                                   &edges[acc_idx].y, &edges[acc_idx].z);
		acc_idx++;

		v = output.center_px - r + d;
		t_camera_models_unproject_and_flip(&hgt->views[view_idx].hgdist, v.x, v.y, &edges[acc_idx].x,
		                                   &edges[acc_idx].y, &edges[acc_idx].z);
		acc_idx++;

		v = output.center_px - r - d;
		t_camera_models_unproject_and_flip(&hgt->views[view_idx].hgdist, v.x, v.y, &edges[acc_idx].x,
		                                   &edges[acc_idx].y, &edges[acc_idx].z);
		acc_idx++;

		v = output.center_px + r - d;
		t_camera_models_unproject_and_flip(&hgt->views[view_idx].hgdist, v.x, v.y, &edges[acc_idx].x,
		                                   &edges[acc_idx].y, &edges[acc_idx].z);


		float angle = 0;

		for (int i = 0; i < 4; i++) {
			angle = fmaxf(angle, m_vec3_angle(center, edges[i]));
		}


		make_projection_instructions_angular(center, hand_idx, angle,
		                                     hgt->tuneable_values.after_detection_fac.val, twist, instr);

		state->wraps[2].data[0] = 0.0f;
		set_predicted_zero(state->wraps[1].data);
	} else {
		Eigen::Array<float, 3, 21> keypoints_in_camera;


		if (view_idx == 0) {
			keypoints_in_camera = hgt->pose_predicted_keypoints[hand_idx];
		} else {
			for (int i = 0; i < 21; i++) {

				Eigen::Quaternionf ori = map_quat(hgt->left_in_right.orientation);
				Eigen::Vector3f tmp = hgt->pose_predicted_keypoints[hand_idx].col(i);

				tmp = ori * tmp;
				tmp += map_vec3(hgt->left_in_right.position);
				keypoints_in_camera.col(i) = tmp;
			}
		}

		hand21_2d bleh;

		make_projection_instructions(dist, hand_idx, hgt->tuneable_values.dyn_radii_fac.val, twist,
		                             keypoints_in_camera, instr, bleh);

		if (hgt->tuneable_values.enable_pose_predicted_input) {
			for (int ml_joint_idx = 0; ml_joint_idx < 21; ml_joint_idx++) {
				float *data = state->wraps[1].data;
				data[(ml_joint_idx * 2) + 0] = bleh[ml_joint_idx].pos_2d.x;
				data[(ml_joint_idx * 2) + 1] = bleh[ml_joint_idx].pos_2d.y;
				// data[(ml_joint_idx * 2) + 2] = bleh[ml_joint_idx].depth_relative_to_midpxm;
			}


			state->wraps[2].data[0] = 1.0f;
		} else {
			state->wraps[2].data[0] = 0.0f;
			set_predicted_zero(state->wraps[1].data);
		}
	}

	stereographic_project_image(dist, instr, hgt->views[view_idx].run_model_on_this,
	                            &hgt->views[view_idx].debug_out_to_this, info.hand_idx ? RED : YELLOW,
	                            data_128x128_uint8);


	xrt::auxiliary::math::map_quat(this_output.look_dir) = instr.rot_quat;
	this_output.stereographic_radius = instr.stereographic_radius;

	bool is_hand = true;

	{
		XRT_TRACE_IDENT(convert_format);

		// here!
		cv::Mat data_128x128_float(cv::Size(128, 128), CV_32FC1, state->wraps[0].data, 128 * sizeof(float));

		is_hand = is_hand && normalizeGrayscaleImage(data_128x128_uint8, data_128x128_float);
	}


	// Ending here


	const OrtValue *inputs[] = {state->wraps[0].tensor, state->wraps[1].tensor, state->wraps[2].tensor};
	const char *input_names[] = {state->wraps[0].name, state->wraps[1].name, state->wraps[2].name};

	OrtValue *output_tensors[] = {nullptr, nullptr, nullptr, nullptr};
	const char *output_names[] = {"heatmap_xy", "heatmap_depth", "scalar_extras", "curls"};

	{
		XRT_TRACE_IDENT(model);
		assert(ARRAY_SIZE(input_names) == ARRAY_SIZE(inputs));
		assert(ARRAY_SIZE(output_names) == ARRAY_SIZE(output_tensors));
		ultra_prof::dump("kpimg", state->wraps[0].data, 128 * 128); ultra_prof::dump("kplast", state->wraps[1].data, 42); ultra_prof::dump("kpuse", state->wraps[2].data, 1);
		const uint64_t _prof_k1 = ultra_prof::now();
		if (ultra_prof::state().on) ultra_prof::add(ultra_prof::KP_PRE, _prof_k1 - _prof_k0);
		ORT_SAFE(wrap, Run(wrap.session, nullptr, input_names, inputs, ARRAY_SIZE(input_names), output_names,
		                   ARRAY_SIZE(output_names), output_tensors));
		if (ultra_prof::state().on) ultra_prof::add(ultra_prof::KP_INFER, ultra_prof::now() - _prof_k1);
	}

	// To here

	// Interpret model outputs!


	float *out_data = nullptr;


	ORT_SAFE(wrap, GetTensorMutableData(output_tensors[0], (void **)&out_data));

	// I don't know why this was added
	// float *confidences = info.view->keypoint_outputs.views[hand_idx].confidences;

	// This was added for debug scribbling, and should be added back at some point.
	// xrt_vec2 *keypoints_global = px_coord.kps;

	size_t plane_size = 22 * 22;

	for (int i = 0; i < 21; i++) {
		float *data = &out_data[i * plane_size];


		// This will be optimized out if nan checking is disabled in hg_numerics_checker
		for (size_t x = 0; x < plane_size; x++) {
			CHECK_NOT_NAN(data[i]);
		}


		int out_idx = argmax(data, 22 * 22);
		int row = out_idx / 22;
		int col = out_idx % 22;

		xrt_vec2 loc = {};

		// This is a good start but rethink it. Maybe fail if less than 18/21 joints failed?
		// is_hand = is_hand &&
		refine_center_of_distribution(hgt, data, col, row, 22, 22, &loc.x, &loc.y);

		if (hand_idx == 0) {
			px_coord[i].pos_2d.x = math_map_ranges(loc.x, 0, 22, -1, 1);
		} else {
			px_coord[i].pos_2d.x = math_map_ranges(loc.x, 0, 22, 1, -1);
		}

		//!@todo when you change this to have +Z-forward
		// note note note the flip!!!
		px_coord[i].pos_2d.y = math_map_ranges(loc.y, 0, 22, 1, -1);

		px_coord[i].confidence_xy = data[out_idx];
	}


	float *out_data_depth = nullptr;
	ORT_SAFE(wrap, GetTensorMutableData(output_tensors[1], (void **)&out_data_depth));

	for (int joint_idx = 0; joint_idx < 21; joint_idx++) {
		float *p_ptr = &out_data_depth[(joint_idx * 22)];


		float depth = 0;
		float confidence = 0;

		// This function can fail
		if (hand_depth_center_of_mass(hgt, p_ptr, &depth, &confidence)) {

			px_coord[joint_idx].depth_relative_to_midpxm = depth;
			px_coord[joint_idx].confidence_depth = confidence;
		} else {
			px_coord[joint_idx].depth_relative_to_midpxm = 0;
			px_coord[joint_idx].confidence_depth = 0;
		}
	}

	float *out_data_extras = nullptr;
	ORT_SAFE(wrap, GetTensorMutableData(output_tensors[2], (void **)&out_data_extras));

	float is_hand_explicit = out_data_extras[0];

	is_hand_explicit = (1.0) / (1.0 + powf(2.71828182845904523536, -is_hand_explicit));

	// When the model is sure, it's _really_ sure.
	// Index was fine with 0.99.
	// North Star seemed to need 0.97.
	if (is_hand_explicit < 0.97) {
		U_LOG_D("Not hand! %f", is_hand_explicit);
		is_hand = false;
	}

	this_output.active = is_hand;
	hgt->views[view_idx].ultra_is_hand[hand_idx] = is_hand_explicit;
	if (hgt->xr_tracking_diag.enabled()) {
		auto &diag = hgt->xr_tracking_diag.frame.hands[hand_idx].views[view_idx];
		diag.is_hand_probability = is_hand_explicit;
		diag.keypoint_active = is_hand;
	}


	float *out_data_curls = nullptr;
	ORT_SAFE(wrap, GetTensorMutableData(output_tensors[3], (void **)&out_data_curls));

	for (int i = 0; i < 5; i++) {
		float curl = out_data_curls[i];
		float variance = out_data_curls[5 + i];

		// Next two lines directly correspond to py_training/settings.py
		// We don't want it to be negative
		variance = fabsf(variance);
		// We don't want it to be possible to be zero
		variance += 0.01;

		this_output.curls[i].value = curl;
		this_output.curls[i].variance = curl;
	}



	if (hgt->debug_scribble) {
		int data_acc_idx = 0;

		int root_x = 8 + ((2 * hand_idx) * (128 + 8));
		int root_y = 8 + (2 * info.view->view * (128 + 8));

		cv::Rect p = cv::Rect(root_x, root_y, 128, 128);

		data_128x128_uint8.copyTo(hgt->visualizers.mat(p));

		make_keypoint_heatmap_output(info.view->view, hand_idx, 0, 0, out_data + (data_acc_idx * plane_size),
		                             hgt->visualizers.mat);
		make_keypoint_depth_heatmap_output(info.view->view, hand_idx, 0, 0,
		                                   out_data_depth + (data_acc_idx * 22), hgt->visualizers.mat);

		data_acc_idx++;

		for (int finger = 0; finger < 5; finger++) {
			for (int joint = 0; joint < 4; joint++) {

				make_keypoint_heatmap_output(info.view->view, hand_idx, 1 + joint, finger,
				                             out_data + (data_acc_idx * plane_size),
				                             hgt->visualizers.mat);
				make_keypoint_depth_heatmap_output(info.view->view, hand_idx, 1 + joint, finger,
				                                   out_data_depth + (data_acc_idx * 22),
				                                   hgt->visualizers.mat);
				data_acc_idx++;
			}
		}

		// Hand existence
		char amt[5];

		snprintf(amt, ARRAY_SIZE(amt), "%.2f", is_hand_explicit);

		cv::Point2i text_origin;

		text_origin.x = root_x + 128 + 2;
		text_origin.y = root_y + 60;

		// Clear out what was there before
		cv::rectangle(hgt->visualizers.mat, cv::Rect(text_origin - cv::Point2i{0, 25}, cv::Size{30, 30}), {255},
		              cv::FILLED);


		cv::putText(hgt->visualizers.mat, amt, text_origin, cv::FONT_HERSHEY_SIMPLEX, 0.3, {0, 0, 0});

		// Curls
		cv::rectangle(hgt->visualizers.mat, cv::Rect(cv::Point2i{root_x, root_y + 128 + 22}, cv::Size{128, 60}),
		              {255}, cv::FILLED);
		for (int i = 0; i < 5; i++) {
			int r = 15;

			cv::Point2i center = {root_x + r + (20 * i), root_y + 128 + 60};

			cv::circle(hgt->visualizers.mat, center, 1, {0}, 1);

			float c = this_output.curls[i].value * 0.3;
			int x = cos(c) * r;
			// Remember, OpenCV has (0,0) at top left
			int y = -sin(c) * r;

			cv::Point2i pt2 = {x, y};
			pt2 += center;
			cv::circle(hgt->visualizers.mat, pt2, 1, {0}, 1);
			cv::line(hgt->visualizers.mat, center, pt2, {0}, 1);
		}
	}

	for (size_t i = 0; i < ARRAY_SIZE(output_tensors); i++) {
		wrap.api->ReleaseValue(output_tensors[i]);
	}
}

void
run_keypoint_estimation(void *ptr)
{
	XRT_TRACE_MARKER();
	keypoint_estimation_run_info info = *(keypoint_estimation_run_info *)ptr;

	try {
		run_keypoint_estimation_unsafe(info);
	} catch (const std::exception &e) {
		HG_ERROR(info.view->hgt, "Exception while running keypoint estimation: %s", e.what());

		// Report not active if keypoint estimation hits an exception
		info.view->hgt->keypoint_outputs[info.hand_idx].views[info.view->view].active = false;
	}
}

void
release_onnx_state(onnx_state *state)
{
	// libultra_hands: nothing to release if session creation failed.
	if (!state->wrap) {
		return;
	}
	OnnxWrapper &wrap = *state->wrap;

	for (model_input_wrap &a : state->wraps) {
		wrap.api->ReleaseValue(a.tensor);
		free(a.data);
	}
}

} // namespace xrt::tracking::hand::mercury
