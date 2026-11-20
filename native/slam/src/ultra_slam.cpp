// A saved map of the room for Ultra XR: RTAB-Map behind a small C API (see ultra_slam.h).
// SPDX-License-Identifier: GPL-3.0-or-later
#include "ultra_slam.h"

#include <rtabmap/core/Memory.h>
#include <rtabmap/core/Parameters.h>
#include <rtabmap/core/Rtabmap.h>
#include <rtabmap/core/SensorData.h>
#include <rtabmap/core/Statistics.h>
#include <rtabmap/core/StereoCameraModel.h>
#include <rtabmap/core/Transform.h>
#include <rtabmap/utilite/ULogger.h>

#include <opencv2/calib3d.hpp>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include <android/log.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ultra_slam", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ultra_slam", __VA_ARGS__)

using namespace rtabmap;
typedef cv::Matx44d M4;

namespace {
thread_local std::string g_error;

M4 pose7(const double *v) {
	double qx = v[3], qy = v[4], qz = v[5], qw = v[6];
	const double n = std::sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
	qx /= n, qy /= n, qz /= n, qw /= n;
	return M4(1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw), v[0],
	          2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw), v[1],
	          2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy), v[2],
	          0, 0, 0, 1);
}
M4 inv(const M4 &m) {
	M4 r = M4::eye();
	for (int i = 0; i < 3; i++)
		for (int j = 0; j < 3; j++) r(i, j) = m(j, i);
	for (int i = 0; i < 3; i++) r(i, 3) = -(r(i, 0) * m(0, 3) + r(i, 1) * m(1, 3) + r(i, 2) * m(2, 3));
	return r;
}
Transform tf(const M4 &m) {
	return Transform((float)m(0, 0), (float)m(0, 1), (float)m(0, 2), (float)m(0, 3), (float)m(1, 0), (float)m(1, 1), (float)m(1, 2),
	                 (float)m(1, 3), (float)m(2, 0), (float)m(2, 1), (float)m(2, 2), (float)m(2, 3));
}
double now_ms() {
	return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
} // namespace

struct uslam {
	int mode = USLAM_MAP;
	std::string db, saved; // saved: the map file a copy of which (db) is being added to
	ParametersMap params;
	int W = 0, H = 0;
	std::string cores;
	int cv_threads = -1;
	double c[2][15];
	cv::Mat mapx[2], mapy[2];
	std::unique_ptr<StereoCameraModel> model;
	std::unique_ptr<Rtabmap> rt;
	int root = 0; // the map's first place
	// USLAM_EXTEND: is the map being added to just now, or only read?
	// add_always: every place of every session is added (measured, docs/SPATIAL.md section 8). Adding only where the
	// map has no places ("Ultra/AddAlways=0") is built and not yet measured on the phone, so it is not the default.
	bool adding = false, add_always = true, dangling = false, have_hit_at = false;
	int misses = 0, old_hits = 0, old_max = 0, spells = 0;
	double hit_at[3] = {0, 0, 0};
	double add_after_m = 0.4;
	int add_after_misses = 4, read_after_hits = 3;

	void set_adding(bool on) {
		ParametersMap p;
		p[Parameters::kMemIncrementalMemory()] = on ? "true" : "false";
		p[Parameters::kRGBDLinearUpdate()] = on ? params[Parameters::kRGBDLinearUpdate()] : "0";
		p[Parameters::kRGBDAngularUpdate()] = on ? params[Parameters::kRGBDAngularUpdate()] : "0";
		rt->parseParameters(p);
		adding = on;
		misses = old_hits = 0;
		if (on) {
			spells++;
			old_max = 0;
			if (rt->getMemory()) {
				const std::set<int> ids = rt->getMemory()->getAllSignatureIds();
				if (!ids.empty()) old_max = *ids.rbegin();
			}
		}
		LOGI("%s (%d places)", on ? "nothing here is in the map: adding places" : "back among places the map has: reading only", rt->getWMSize() + rt->getSTMSize());
	}

	// Called after every pair of a map that is added to. Places are added only where the map has none: while pairs
	// are recognised nothing is added; after some that are not, and some way from where one last was, the map is
	// added to; and once pairs are again recognised as places that were there before this spell, it is read again.
	// So walking a room the map already has adds nothing to it, however often.
	void adding_step(int hit, const double *ps, bool tied) {
		if (mode != USLAM_EXTEND || add_always) return;
		if (!adding) {
			if (hit > 0) {
				misses = 0, have_hit_at = true;
				hit_at[0] = ps[0], hit_at[1] = ps[1], hit_at[2] = ps[2];
			} else if (have_hit_at) {
				const double d = std::sqrt((ps[0] - hit_at[0]) * (ps[0] - hit_at[0]) + (ps[1] - hit_at[1]) * (ps[1] - hit_at[1]) + (ps[2] - hit_at[2]) * (ps[2] - hit_at[2]));
				if (++misses >= add_after_misses && d > add_after_m) set_adding(true);
			}
		} else {
			old_hits = hit > 0 && hit <= old_max ? old_hits + 1 : 0;
			if (old_hits >= read_after_hits) {
				if (!tied) dangling = true; // should not be: recognised as old places and still not linked to them
				hit_at[0] = ps[0], hit_at[1] = ps[1], hit_at[2] = ps[2];
				set_adding(false);
			}
		}
	}

	std::mutex mtx;
	std::condition_variable cv, idle;
	std::thread worker;
	bool stop = false, busy = false, pending = false, epoch_due = false, last_tied = false;
	std::vector<uint8_t> left, right;
	int64_t t_ns = 0;
	double pose[7];
	uslam_state st{};

	void rectifier(int w, int h) {
		W = w, H = h;
		M4 T_i_c[2];
		for (int i = 0; i < 2; i++) T_i_c[i] = pose7(&c[i][8]);
		const M4 T01 = inv(T_i_c[0]) * T_i_c[1];
		// The rectified pair, in the left camera's frame: x along the line between the cameras, z the mean of the two
		// cameras' forward directions made square to it (the cameras are toed out by some ten degrees).
		cv::Vec3d x(T01(0, 3), T01(1, 3), T01(2, 3));
		const double baseline = cv::norm(x);
		x /= baseline;
		cv::Vec3d z(0.5 * T01(0, 2), 0.5 * T01(1, 2), 0.5 + 0.5 * T01(2, 2));
		z -= x * z.dot(x);
		z /= cv::norm(z);
		const cv::Vec3d y = z.cross(x);
		const cv::Matx33d R0(x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]);
		cv::Matx33d R01;
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++) R01(i, j) = T01(i, j);
		const cv::Matx33d R1 = R0 * R01;
		const double f = 0.5 * (c[0][0] + c[1][0]), cx = w / 2.0, cy = h / 2.0;
		const cv::Matx33d Kr(f, 0, cx, 0, f, cy, 0, 0, 1);
		for (int i = 0; i < 2; i++) {
			const cv::Matx33d K(c[i][0], 0, c[i][2], 0, c[i][1], c[i][3], 0, 0, 1);
			const cv::Vec4d D(c[i][4], c[i][5], c[i][6], c[i][7]);
			cv::fisheye::initUndistortRectifyMap(K, D, i == 0 ? R0 : R1, Kr, cv::Size(w, h), CV_16SC2, mapx[i], mapy[i]);
		}
		M4 T_c0_rect = M4::eye();
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++) T_c0_rect(i, j) = R0(j, i);
		// The motion sensor's frame is the body RTAB-Map is given poses of; the rectified camera sits in it here.
		model.reset(new StereoCameraModel("stereo", f, f, cx, cy, baseline, tf(T_i_c[0] * T_c0_rect), cv::Size(w, h)));
		LOGI("rig: baseline %.1f mm, rectified %dx%d at f %.0f", baseline * 1000, w, h, f);
	}

	void open() {
		ParametersMap p = params;
		// A map that is added to is read at first, like one that is only read: places are added only where the map
		// has none (see adding_step). "Ultra/AddAlways=1" is the way of before: every place of every session is added.
		adding = mode == USLAM_MAP || (mode == USLAM_EXTEND && add_always);
		misses = old_hits = 0, have_hit_at = false, dangling = false;
		p[Parameters::kMemIncrementalMemory()] = adding ? "true" : "false";
		if (mode != USLAM_MAP) p[Parameters::kMemInitWMWithAllNodes()] = "true";
		if (!adding) {
			p[Parameters::kRGBDLinearUpdate()] = "0";
			p[Parameters::kRGBDAngularUpdate()] = "0";
		}
		rt.reset(new Rtabmap());
		const double t0 = now_ms();
		rt->init(p, db);
		const int places = rt->getWMSize() + rt->getSTMSize();
		// The map's frame is that of its first place. A later piece (another session, or what follows a restart of the
		// estimator) has a frame of its own until it is tied to that one.
		root = 0;
		if (rt->getMemory()) {
			const std::set<int> ids = rt->getMemory()->getAllSignatureIds();
			if (!ids.empty()) root = *ids.begin();
		}
		LOGI("%s %s: opened in %.0f ms, %d places, first place %d", mode == USLAM_MAP ? "map" : mode == USLAM_FIND ? "find" : "extend", db.c_str(), now_ms() - t0, places, root);
		std::lock_guard<std::mutex> lock(mtx);
		st.places = places;
	}

	void run() {
		// The map can wait; the head, the hands and the picture cannot. Measured in the app at the default priority
		// the estimator's time for a frame went from 35 to 52 ms while a map was made or read (docs/SPATIAL.md).
		setpriority(PRIO_PROCESS, (id_t)gettid(), 15);
		// "Ultra/Cores=little": the worker keeps to the four small cores (0 to 3 on the phones this runs on), leaving
		// the large ones to the estimator and the hands. "Ultra/CvThreads=n": OpenCV's own worker threads, for the
		// whole process (it is one library for everybody in it).
		if (cores == "little") {
			cpu_set_t set;
			CPU_ZERO(&set);
			for (int i = 0; i < 4; i++) CPU_SET(i, &set);
			if (sched_setaffinity(gettid(), sizeof set, &set) != 0) LOGW("the worker could not be kept to the small cores");
		}
		if (cv_threads >= 0) cv::setNumThreads(cv_threads);
		std::vector<uint8_t> l, r;
		for (;;) {
			int64_t t;
			double ps[7];
			bool epoch;
			{
				std::unique_lock<std::mutex> lock(mtx);
				busy = false;
				idle.notify_all();
				cv.wait(lock, [&] { return stop || pending || epoch_due; });
				if (stop) return;
				busy = true;
				epoch = epoch_due;
				epoch_due = false;
				if (epoch) {
					const int e = st.epoch + 1, places = st.places;
					const int64_t dropped = st.dropped;
					const int linked = st.linked, added = st.added;
					st = uslam_state{};
					st.epoch = e, st.mode = mode, st.places = places, st.dropped = dropped, st.linked = linked, st.added = added;
				}
				if (!pending) {
					if (!epoch) continue;
				} else {
					l.swap(left), r.swap(right);
					t = t_ns;
					std::memcpy(ps, pose, sizeof ps);
					pending = false;
				}
			}
			try {
				// A new world of the estimator's: what a finder holds of the old one (its odometry cache, its last
				// localisation) must go. Opening the file again is the plain way, and takes a tenth of a second.
				if (epoch && mode == USLAM_FIND) {
					rt->close(false);
					open();
				}
				// Making a map: what follows is a piece of its own until a place of the earlier piece is seen again.
				if (epoch && mode == USLAM_MAP) rt->triggerNewMap();
				if (epoch && mode == USLAM_EXTEND) {
					// what was being added hangs in the air unless it was tied to the map; where the head is now is
					// not known: read until it is
					if (adding && !add_always) {
						if (!last_tied) dangling = true;
						set_adding(false);
					} else if (adding) rt->triggerNewMap();
					have_hit_at = false;
				}
				if (l.empty()) continue;
				if (!model) rectifier(W, H);
				cv::Mat lr, rr;
				cv::remap(cv::Mat(H, W, CV_8UC1, l.data()), lr, mapx[0], mapy[0], cv::INTER_LINEAR);
				cv::remap(cv::Mat(H, W, CV_8UC1, r.data()), rr, mapx[1], mapy[1], cv::INTER_LINEAR);
				SensorData data(lr, rr, *model, 0, t * 1e-9);
				static const cv::Mat cov = cv::Mat::eye(6, 6, CV_64FC1) * 0.0001;
				const double t0 = now_ms();
				rt->process(data, tf(pose7(ps)), cov);
				const double took = now_ms() - t0;
				const Statistics &s = rt->getStatistics();
				const int loop = rt->getLoopClosureId(), prox = s.proximityDetectionId();
				const int hit = loop > 0 ? loop : prox > 0 ? prox : 0;
				const Transform corr = rt->getMapCorrection();
				if (root == 0) root = rt->getLastLocationId();
				// Does the place this pair belongs to hang together with the map's first place? By the graph's links, not
				// by the poses RTAB-Map holds: those of a map read from the file are all there whether or not anything
				// newer is linked to them.
				bool tied = false;
				{
					const int from = adding ? rt->getLastLocationId() : hit;
					if (root != 0 && from == root) tied = true;
					else if (root != 0 && from > 0 && rt->getMemory()) tied = rt->getMemory()->getNeighborsId(from, 0).count(root) > 0;
				}
				int inl = 0;
				const auto it = s.data().find(Statistics::kLoopVisual_inliers());
				if (it != s.data().end()) inl = (int)it->second;
				std::lock_guard<std::mutex> lock(mtx);
				if (epoch_due) { // the estimator began a new world while this pair was worked on: it says nothing of it
					l.clear();
					continue;
				}
				st.results++;
				st.last_t_ns = t;
				st.last_ms = took;
				st.last_place = hit;
				st.places = rt->getWMSize() + rt->getSTMSize();
				if (hit) {
					st.found++;
					st.found_t_ns = t;
					st.inliers = inl;
				}
				st.has_corr = 0;
				last_tied = tied;
				if (mode == USLAM_EXTEND && tied) st.linked = 1;
				if (mode == USLAM_EXTEND && adding) st.added = 1;
				if (!corr.isNull() && (!adding ? (tied && st.found > 0) : (tied || (mode == USLAM_MAP && st.epoch == 0)))) {
					const Eigen::Quaternionf q = corr.getQuaternionf();
					st.corr[0] = corr.x(), st.corr[1] = corr.y(), st.corr[2] = corr.z();
					st.corr[3] = q.x(), st.corr[4] = q.y(), st.corr[5] = q.z(), st.corr[6] = q.w();
					st.has_corr = 1;
				}
				adding_step(hit, ps, tied);
			} catch (const std::exception &e) {
				LOGW("a pair failed: %s", e.what());
			}
			l.clear();
		}
	}
};

extern "C" {

USLAM_API const char *uslam_version(void) { return "ultra_slam 1.0 (rtabmap " RTABMAP_VERSION ")"; }
USLAM_API const char *uslam_last_error(void) { return g_error.c_str(); }

USLAM_API uslam_t *uslam_create(const char *rig, const char *db, int mode, const char *params) {
	try {
		if (!rig || !db) throw std::runtime_error("no rig or no map file");
		std::unique_ptr<uslam> h(new uslam());
		h->mode = mode == USLAM_FIND ? USLAM_FIND : mode == USLAM_EXTEND ? USLAM_EXTEND : USLAM_MAP;
		if (h->mode == USLAM_EXTEND) {
			// RTAB-Map writes places to its file as it goes, and closing "without saving" does not take them out
			// again (measured: the file of a session that never saw the map had changed). So a map is added to in a
			// copy, and the copy takes the saved map's place only when the session ends tied to it.
			std::ifstream in(db, std::ios::binary);
			if (!in) h->mode = USLAM_MAP;
			else {
				h->saved = db;
				h->db = std::string(db) + ".work.db"; // RTAB-Map takes no file that does not end in .db
				std::ofstream out(h->db, std::ios::binary | std::ios::trunc);
				out << in.rdbuf();
				if (!out) throw std::runtime_error("the saved map could not be copied to work on");
			}
		}
		if (h->saved.empty()) h->db = db;
		h->st.mode = h->mode;
		std::istringstream in(rig);
		for (int i = 0; i < 2; i++)
			for (int j = 0; j < 15; j++)
				if (!(in >> h->c[i][j])) throw std::runtime_error("the rig needs two lines of 15 numbers");
		if (h->mode == USLAM_FIND) {
			FILE *f = fopen(db, "rb");
			if (!f) throw std::runtime_error(std::string("no map at ") + db);
			fclose(f);
		} else if (h->mode == USLAM_MAP) {
			remove(db);
		}
		ParametersMap &p = h->params;
		// What these pictures need (480x640, dark, noisy; measured on the phone, docs/SPATIAL.md): more corners than the
		// defaults and fewer agreeing points asked of a match.
		p[Parameters::kRGBDEnabled()] = "true";
		p[Parameters::kKpDetectorStrategy()] = "8"; // GFTT corners, ORB descriptors
		p[Parameters::kVisFeatureType()] = "8";
		p[Parameters::kKpMaxFeatures()] = "800";
		p[Parameters::kVisMaxFeatures()] = "1200";
		p[Parameters::kGFTTMinDistance()] = "4";
		p[Parameters::kGFTTQualityLevel()] = "0.0005";
		p[Parameters::kKpMaxDepth()] = "6";
		p[Parameters::kVisMaxDepth()] = "6";
		p[Parameters::kVisEstimationType()] = "1"; // PnP
		p[Parameters::kVisMinInliers()] = "12";
		p[Parameters::kOptimizerStrategy()] = "1"; // g2o
		p[Parameters::kg2oSolver()] = "3";         // Eigen: the only direct solver in this build
		p[Parameters::kRegForce3DoF()] = "false";
		p[Parameters::kMemBinDataKept()] = "false"; // no pictures in the file: corners and descriptors only
		p[Parameters::kRGBDLinearUpdate()] = "0.1";
		p[Parameters::kRGBDAngularUpdate()] = "0.1";
		if (params) {
			std::istringstream ps(params);
			std::string line;
			while (std::getline(ps, line)) {
				const size_t eq = line.find('=');
				if (eq == std::string::npos || eq == 0) continue;
				const std::string k = line.substr(0, eq), v = line.substr(eq + 1);
				if (k == "Ultra/Cores") h->cores = v;
				else if (k == "Ultra/AddAlways") h->add_always = atoi(v.c_str()) != 0;
				else if (k == "Ultra/AddAfterM") h->add_after_m = atof(v.c_str());
				else if (k == "Ultra/CvThreads") h->cv_threads = atoi(v.c_str());
				else p[k] = v;
			}
		}
		ULogger::setType(ULogger::kTypeNoLog);
		h->open();
		h->worker = std::thread([p = h.get()] { p->run(); });
		return h.release();
	} catch (const std::exception &e) {
		g_error = e.what();
		return nullptr;
	}
}

USLAM_API int uslam_feed(uslam_t *h, int64_t t_ns, const uint8_t *left, const uint8_t *right, int w, int hgt, const double pose[7]) {
	if (!h || !left || !right || w <= 0 || hgt <= 0) return 0;
	std::lock_guard<std::mutex> lock(h->mtx);
	if (h->busy || h->pending || (h->W && (h->W != w || h->H != hgt))) {
		h->st.dropped++;
		return 0;
	}
	h->W = w, h->H = hgt;
	h->left.assign(left, left + (size_t)w * hgt);
	h->right.assign(right, right + (size_t)w * hgt);
	h->t_ns = t_ns;
	std::memcpy(h->pose, pose, sizeof h->pose);
	h->pending = true;
	h->cv.notify_all();
	return 1;
}

USLAM_API void uslam_new_epoch(uslam_t *h) {
	if (!h) return;
	std::lock_guard<std::mutex> lock(h->mtx);
	h->epoch_due = true;
	h->pending = false; // a pair waiting belongs to the old world
	h->cv.notify_all();
}

USLAM_API void uslam_get_state(uslam_t *h, uslam_state *out) {
	if (!h || !out) return;
	std::lock_guard<std::mutex> lock(h->mtx);
	*out = h->st;
}

USLAM_API void uslam_wait_idle(uslam_t *h) {
	if (!h) return;
	std::unique_lock<std::mutex> lock(h->mtx);
	h->idle.wait(lock, [&] { return !h->busy && !h->pending && !h->epoch_due; });
}

USLAM_API void uslam_destroy(uslam_t *h, int save) {
	if (!h) return;
	{
		std::lock_guard<std::mutex> lock(h->mtx);
		h->stop = true;
		h->cv.notify_all();
	}
	if (h->worker.joinable()) h->worker.join();
	try {
		const double t0 = now_ms();
		// a map that was added to is written if anything was added, all of it tied to the map; what was being added
		// last must be tied too
		if (h->mode == USLAM_EXTEND && h->adding && !h->last_tied) h->dangling = true;
		const bool keep = save && (h->mode == USLAM_MAP || (h->mode == USLAM_EXTEND && h->st.linked && h->st.added && !h->dangling));
		if (h->rt) h->rt->close(keep);
		if (h->mode == USLAM_MAP && !save) remove(h->db.c_str());
		if (!h->saved.empty()) {
			if (keep) {
				if (rename(h->db.c_str(), h->saved.c_str()) != 0) LOGW("the map that was added to could not take the saved one's place");
			} else remove(h->db.c_str());
		}
		LOGI("closed in %.0f ms%s", now_ms() - t0, keep ? ", map written" : h->mode == USLAM_EXTEND && save ? (!h->st.added ? ", nothing written: nothing was added" : ", nothing written: new places were not tied to the map") : "");
	} catch (const std::exception &e) {
		LOGW("close failed: %s", e.what());
	}
	delete h;
}
}
