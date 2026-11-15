// Deterministic synthetic stereo + IMU simulation test for libultra_vio.
//
// Generator: a textured box room rendered through the calibration's camera
// models (KB4 here) for both cameras along an analytic 6DoF trajectory, plus a
// consistent 1 kHz IMU stream. The estimator is driven through the uvio_* C API
// only. basalt-headers/Eigen/Sophus are used here solely to *generate* data
// (camera projection, calibration JSON parsing) and to evaluate the result.
//
// Usage: uvio_sim_test <calib.json> <vio_config.json> [--out DIR]
//          [--duration S] [--scenarios api,nominal,reset_still,reset_moving,reset_gated,
//          gap_short,gap_long,gap_long_moving,negative_control,clock_restart,flood,
//          init_moving_sweep] [--reset-at S] [--opt key=value ...]
//          [--dump-frames]

#include <ultra_vio.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <sys/resource.h>
#include <unistd.h>

#include <Eigen/Dense>
#include <Eigen/Geometry>
#include <cereal/archives/json.hpp>
#include <sophus/se3.hpp>

#include <basalt/calibration/calibration.hpp>
#include <basalt/serialization/headers_serialization.h>

using Vec3 = Eigen::Vector3d;
using Mat3 = Eigen::Matrix3d;
using Clock = std::chrono::steady_clock;

static const double kG = 9.80665;
static const int64_t kT0ns = 100'000'000'000LL;  // arbitrary non-zero epoch of the common clock

// ----------------------------------------------------------------------------
// Ground-truth trajectory of the IMU/body frame in the world (z up).
// A 1.0 m x 0.5 m figure-eight with a vertical bob and yaw/pitch/roll
// oscillation, starting at rest and with one 1.5 s stop in the middle.
// ----------------------------------------------------------------------------
struct Trajectory {
  // Time warp tau(t): motion "phase". d(tau)/dt is 0 while at rest and 1 while
  // cruising, with C2 quintic ramps in between:
  //   0 .. 1 s      at rest (estimator start)
  //   1 .. 3 s      ramp up
  //   3 .. 19 s     moving
  //   19 .. 20 s    ramp down
  //   20 .. 21.5 s  at rest (dwell, used by the restart-at-rest scenarios)
  //   21.5 .. 22.5  ramp up, then moving until the end
  static double ramp_int(double u) {  // integral of smootherstep 6u^5-15u^4+10u^3 on [0,u]
    u = std::min(1.0, std::max(0.0, u));
    return u * u * u * u * u * u - 3.0 * u * u * u * u * u + 2.5 * u * u * u * u;
  }
  static constexpr double kDwellBegin = 20.0, kDwellEnd = 21.5;
  static double tau(double t) {
    double s = 0;
    s += 2.0 * ramp_int((t - 1.0) / 2.0);                 // ramp up 1..3
    if (t > 3.0) s += std::min(t, 19.0) - 3.0;            // cruise 3..19
    if (t > 19.0) s += std::min(t - 19.0, 1.0) - 1.0 * ramp_int((t - 19.0) / 1.0);  // ramp down 19..20
    s += 1.0 * ramp_int((t - kDwellEnd) / 1.0);           // ramp up 21.5..22.5
    if (t > kDwellEnd + 1.0) s += t - (kDwellEnd + 1.0);  // cruise
    return s;
  }
  static Vec3 pos(double t) {
    const double s = tau(t);
    return Vec3(0.5 * std::sin(2 * M_PI * s / 8.0), 0.25 * std::sin(4 * M_PI * s / 8.0),
                0.10 * std::sin(2 * M_PI * s / 5.0));
  }
  static Mat3 rot(double t) {
    const double s = tau(t);
    const double yaw = 0.5 * std::sin(2 * M_PI * s / 10.0);
    const double pitch = 0.15 * std::sin(2 * M_PI * s / 6.0);
    const double roll = 0.15 * std::sin(2 * M_PI * s / 7.0 + 1.0) - 0.15 * std::sin(1.0);
    return (Eigen::AngleAxisd(yaw, Vec3::UnitZ()) * Eigen::AngleAxisd(pitch, Vec3::UnitY()) *
            Eigen::AngleAxisd(roll, Vec3::UnitX()))
        .toRotationMatrix();
  }
  static Sophus::SE3d pose(double t) { return Sophus::SE3d(Eigen::Quaterniond(rot(t)).normalized(), pos(t)); }
  static Vec3 vel(double t) {
    const double h = 1e-3;
    return (pos(t + h) - pos(t - h)) / (2 * h);
  }
  static Vec3 acc(double t) {
    const double h = 1e-3;
    return (pos(t + h) - 2.0 * pos(t) + pos(t - h)) / (h * h);
  }
  static Vec3 omega_body(double t) {  // angular velocity expressed in the body frame
    const double h = 1e-3;
    const Mat3 dR = rot(t - h).transpose() * rot(t + h);
    return Sophus::SO3d(Eigen::Quaterniond(dR).normalized()).log() / (2 * h);
  }
};

// ----------------------------------------------------------------------------
// Scene: inside of a box with random high-contrast texture on all six faces.
// ----------------------------------------------------------------------------
struct Scene {
  Vec3 lo{-3.0, -2.5, -1.4}, hi{3.0, 2.5, 1.3};
  static constexpr double kTexel = 0.01;  // 1 cm
  struct Face {
    int w = 0, h = 0;
    std::vector<uint8_t> tex;
  };
  Face faces[6];  // index = 2*axis + (positive side)

  void build(uint32_t seed) {
    std::mt19937 rng(seed);
    for (int f = 0; f < 6; ++f) {
      const int a = f / 2, b = (a + 1) % 3, c = (a + 2) % 3;
      Face& F = faces[f];
      F.w = int(std::ceil((hi[b] - lo[b]) / kTexel)) + 2;
      F.h = int(std::ceil((hi[c] - lo[c]) / kTexel)) + 2;
      std::vector<uint8_t> t(size_t(F.w) * F.h, 110);
      const int nrect = int(double(F.w) * F.h / 550.0);
      for (int i = 0; i < nrect; ++i) {
        const int rw = 6 + int(rng() % 40), rh = 6 + int(rng() % 40);
        const int x0 = int(rng() % uint32_t(F.w)), y0 = int(rng() % uint32_t(F.h));
        const uint8_t val = uint8_t(20 + rng() % 216);
        for (int y = y0; y < std::min(F.h, y0 + rh); ++y)
          for (int x = x0; x < std::min(F.w, x0 + rw); ++x) t[size_t(y) * F.w + x] = val;
      }
      // 3x3 box blur: soft edges so that sub-pixel optical flow has gradients.
      F.tex.resize(t.size());
      for (int y = 0; y < F.h; ++y)
        for (int x = 0; x < F.w; ++x) {
          int sum = 0, n = 0;
          for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
              const int xx = x + dx, yy = y + dy;
              if (xx < 0 || yy < 0 || xx >= F.w || yy >= F.h) continue;
              sum += t[size_t(yy) * F.w + xx];
              ++n;
            }
          F.tex[size_t(y) * F.w + x] = uint8_t(sum / n);
        }
    }
  }

  inline float sample(const Eigen::Vector3f& o, const Eigen::Vector3f& d) const {
    float tbest = 1e30f;
    int face = 0;
    for (int a = 0; a < 3; ++a) {
      if (d[a] > 1e-9f) {
        const float t = (float(hi[a]) - o[a]) / d[a];
        if (t < tbest) tbest = t, face = 2 * a + 1;
      } else if (d[a] < -1e-9f) {
        const float t = (float(lo[a]) - o[a]) / d[a];
        if (t < tbest) tbest = t, face = 2 * a;
      }
    }
    const Eigen::Vector3f p = o + tbest * d;
    const int a = face / 2, b = (a + 1) % 3, c = (a + 2) % 3;
    const Face& F = faces[face];
    float u = (p[b] - float(lo[b])) / float(kTexel), v = (p[c] - float(lo[c])) / float(kTexel);
    u = std::min(std::max(u, 0.0f), float(F.w - 2));
    v = std::min(std::max(v, 0.0f), float(F.h - 2));
    const int iu = int(u), iv = int(v);
    const float fu = u - iu, fv = v - iv;
    const uint8_t* r0 = &F.tex[size_t(iv) * F.w + iu];
    const uint8_t* r1 = r0 + F.w;
    return (1 - fv) * ((1 - fu) * r0[0] + fu * r0[1]) + fv * ((1 - fu) * r1[0] + fu * r1[1]);
  }
};

// ----------------------------------------------------------------------------
struct Sequence {
  basalt::Calibration<double> calib;
  int w = 0, h = 0;
  double duration = 0;
  // IMU (1 kHz), on the common clock
  std::vector<int64_t> imu_t;
  std::vector<std::array<float, 3>> gyro, accel;
  // Stereo (30 Hz): frame_t_imu = exposure time on the IMU clock;
  // frame_t_cam = timestamp handed to uvio_push_stereo (= t_imu - cam_time_offset_ns)
  std::vector<int64_t> frame_t_imu, frame_t_cam;
  std::vector<uint8_t> pixels;  // n_frames * 2 * w * h
  const uint8_t* left(size_t i) const { return &pixels[(2 * i) * size_t(w) * h]; }
  const uint8_t* right(size_t i) const { return &pixels[(2 * i + 1) * size_t(w) * h]; }
};

static double ns_to_s(int64_t t_ns) { return double(t_ns - kT0ns) * 1e-9; }

static void generate(Sequence& seq, double duration, uint32_t seed) {
  seq.duration = duration;
  seq.w = seq.calib.resolution[0][0];
  seq.h = seq.calib.resolution[0][1];
  const size_t npix = size_t(seq.w) * seq.h;

  // --- IMU ---
  std::mt19937 rng(seed);
  std::normal_distribution<double> nd(0.0, 1.0);
  const double gyro_sigma = 0.005, accel_sigma = 0.05;  // per-sample noise at 1 kHz
  const Vec3 bg(0.002, -0.001, 0.0015), ba(0.02, -0.015, 0.01);
  const int n_imu = int(duration * 1000.0) + 200;  // a little IMU after the last frame
  for (int k = 0; k < n_imu; ++k) {
    const double t = k * 1e-3;
    const Mat3 R = Trajectory::rot(t);
    const Vec3 w = Trajectory::omega_body(t) + bg + gyro_sigma * Vec3(nd(rng), nd(rng), nd(rng));
    // specific force: a_body = R^T (a_world - g_world), g_world = (0,0,-g)
    const Vec3 a = R.transpose() * (Trajectory::acc(t) + Vec3(0, 0, kG)) + ba +
                   accel_sigma * Vec3(nd(rng), nd(rng), nd(rng));
    seq.imu_t.push_back(kT0ns + int64_t(k) * 1'000'000);
    seq.gyro.push_back({float(w.x()), float(w.y()), float(w.z())});
    seq.accel.push_back({float(a.x()), float(a.y()), float(a.z())});
  }

  // --- stereo ---
  Scene scene;
  scene.build(seed * 7 + 1);
  std::vector<Eigen::Vector3f> rays[2];
  for (int c = 0; c < 2; ++c) {
    rays[c].resize(npix);
    for (int y = 0; y < seq.h; ++y)
      for (int x = 0; x < seq.w; ++x) {
        Eigen::Vector4d p3;
        const bool ok = seq.calib.intrinsics[c].unproject(Eigen::Vector2d(x, y), p3);
        rays[c][size_t(y) * seq.w + x] = ok ? Eigen::Vector3f(p3.head<3>().normalized().cast<float>())
                                           : Eigen::Vector3f(0, 0, 0);
      }
  }
  const int n_frames = int(duration * 30.0);
  const int64_t off = seq.calib.cam_time_offset_ns;
  for (int j = 0; j < n_frames; ++j) {
    const int64_t t_imu = kT0ns + int64_t(std::llround((j / 30.0 + 0.0004) * 1e9));
    seq.frame_t_imu.push_back(t_imu);
    seq.frame_t_cam.push_back(t_imu - off);  // wrapper adds `off` back
  }
  seq.pixels.resize(size_t(n_frames) * 2 * npix);
  auto render_range = [&](int begin, int step) {
    for (int j = begin; j < n_frames; j += step) {
      const double t = ns_to_s(seq.frame_t_imu[j]);
      const Sophus::SE3d T_w_i = Trajectory::pose(t);
      for (int c = 0; c < 2; ++c) {
        const Sophus::SE3d T_w_c = T_w_i * seq.calib.T_i_c[c];
        const Eigen::Matrix3f R = T_w_c.so3().matrix().cast<float>();
        const Eigen::Vector3f o = T_w_c.translation().cast<float>();
        uint8_t* out = &seq.pixels[(2 * size_t(j) + c) * npix];
        // per-image deterministic noise stream
        uint32_t s = uint32_t(seed) * 2654435761u + uint32_t(j) * 40503u + uint32_t(c) * 9781u + 12345u;
        for (size_t i = 0; i < npix; ++i) {
          const Eigen::Vector3f& dc = rays[c][i];
          float val = 0;
          if (dc.squaredNorm() > 0.5f) val = scene.sample(o, R * dc);
          // cheap triangular noise (sum of two uniforms), sigma ~= 1.6 gray levels
          s = s * 1664525u + 1013904223u;
          const float n1 = float((s >> 16) & 0xff) / 255.0f;
          s = s * 1664525u + 1013904223u;
          const float n2 = float((s >> 16) & 0xff) / 255.0f;
          val += (n1 + n2 - 1.0f) * 4.0f;
          out[i] = uint8_t(std::min(255.0f, std::max(0.0f, val + 0.5f)));
        }
      }
    }
  };
  std::thread t1(render_range, 0, 2), t2(render_range, 1, 2);
  t1.join();
  t2.join();
}

// ----------------------------------------------------------------------------
static size_t rss_bytes() {
  std::ifstream fs("/proc/self/statm");
  size_t a = 0, r = 0;
  fs >> a >> r;
  return r * size_t(sysconf(_SC_PAGESIZE));
}
static double cpu_seconds() {
  struct rusage u;
  getrusage(RUSAGE_SELF, &u);
  return u.ru_utime.tv_sec + u.ru_utime.tv_usec * 1e-6 + u.ru_stime.tv_sec + u.ru_stime.tv_usec * 1e-6;
}

struct RunOpts {
  std::string name;
  bool realtime = true;                              // pace by wall clock, 2 producer threads
  double reset_at = -1;                              // call uvio_reset at this stream time
  std::vector<std::pair<double, double>> image_gaps; // [begin,end) stream times without images
  double camera_latency = 0.012;                     // frames are delivered this long after exposure
  double t_begin = 0, t_end = 1e9;                   // part of the sequence that is fed
  std::vector<std::pair<std::string, double>> options;  // uvio_set_option() calls after create
  int pace_us = 3333;                                // !realtime: sleep per frame (3333 us = ~10x real time)
  int passes = 1;                                    // !realtime: feed [t_begin,t_end] this many times (clock restart)
  bool rotate180 = false;                            // negative control: push both images upside down
};

struct RunResult {
  std::vector<uvio_state> states;  // every distinct state seen by the poller
  uvio_state final_state{};
  int64_t frames_pushed = 0;
  double wall_s = 0, cpu_s = 0;
  size_t rss_before = 0, rss_peak = 0, rss_at_30pct = 0, rss_end = 0;
  int64_t reset_count_before_reset = -1;
  double first_valid_after_reset_s = -1;  // stream time of first state after reset
  bool invalid_seen_after_reset = false;
};

static bool in_gap(const RunOpts& o, double t) {
  for (auto& g : o.image_gaps)
    if (t >= g.first && t < g.second) return true;
  return false;
}

static std::vector<std::pair<std::string, double>> g_global_options;  // --opt key=value

static RunResult run(const Sequence& seq, const std::string& calib_json, const std::string& config_json,
                     const RunOpts& opt_in) {
  RunOpts opt = opt_in;
  opt.options.insert(opt.options.end(), g_global_options.begin(), g_global_options.end());
  RunResult res;
  const size_t npix = size_t(seq.w) * seq.h;
  auto push_pair = [&](uvio_t* hh, size_t j) {
    if (!opt.rotate180) {
      uvio_push_stereo(hh, seq.frame_t_cam[j], seq.left(j), seq.right(j), seq.w, seq.h);
      return;
    }
    std::vector<uint8_t> l(seq.left(j), seq.left(j) + npix), r(seq.right(j), seq.right(j) + npix);
    std::reverse(l.begin(), l.end());  // reversing a row-major buffer = 180 deg rotation
    std::reverse(r.begin(), r.end());
    uvio_push_stereo(hh, seq.frame_t_cam[j], l.data(), r.data(), seq.w, seq.h);
  };
  res.rss_before = rss_bytes();
  char err[256];
  uvio_t* h = uvio_create(calib_json.c_str(), config_json.c_str(), err, sizeof(err));
  if (!h) {
    std::printf("uvio_create failed: %s\n", err);
    std::exit(2);
  }
  for (auto& kv : opt.options) {
    if (!uvio_set_option(h, kv.first.c_str(), kv.second)) {
      std::printf("uvio_set_option(%s) failed\n", kv.first.c_str());
      std::exit(2);
    }
  }
  std::atomic<bool> done{false};
  std::atomic<double> stream_now{0.0};
  std::atomic<double> reset_time{-1.0};
  std::mutex res_mtx;

  std::thread poller([&] {
    int64_t last_t = -1;
    int64_t last_resets = 0;
    uint64_t iter = 0;
    while (!done.load()) {
      uvio_state st;
      const int ok = uvio_get_state(h, &st);
      {
        std::lock_guard<std::mutex> lock(res_mtx);
        if (st.reset_count != last_resets) {
          last_resets = st.reset_count;
          last_t = -1;
        }
        if (reset_time.load() >= 0 && !ok) res.invalid_seen_after_reset = true;
        if (ok && st.t_ns != last_t) {
          last_t = st.t_ns;
          res.states.push_back(st);
          if (reset_time.load() >= 0 && res.first_valid_after_reset_s < 0 && st.reset_count > res.reset_count_before_reset)
            res.first_valid_after_reset_s = ns_to_s(st.t_ns);
        }
        if ((++iter % 50) == 0) res.rss_peak = std::max(res.rss_peak, rss_bytes());  // every ~50 ms
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
  });

  const double cpu0 = cpu_seconds();
  const auto wall0 = Clock::now();
  const size_t n_frames = seq.frame_t_imu.size();

  if (opt.realtime) {
    auto at = [&](double stream_s) {
      return wall0 + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(stream_s - opt.t_begin));
    };
    // IMU thread: 1 kHz, delivered in ~1 ms batches like a HID reader would.
    std::thread imu_thread([&] {
      size_t k = 0;
      while (k < seq.imu_t.size() && ns_to_s(seq.imu_t[k]) < opt.t_begin - 0.05) ++k;
      while (k < seq.imu_t.size() && ns_to_s(seq.imu_t[k]) <= opt.t_end + 0.1) {
        const double now = opt.t_begin + std::chrono::duration<double>(Clock::now() - wall0).count();
        while (k < seq.imu_t.size() && ns_to_s(seq.imu_t[k]) <= now) {
          uvio_push_imu(h, seq.imu_t[k], seq.gyro[k].data(), seq.accel[k].data());
          ++k;
        }
        stream_now = now;
        std::this_thread::sleep_for(std::chrono::microseconds(500));
      }
    });
    // Camera thread: each pair arrives camera_latency after its exposure.
    std::thread cam_thread([&] {
      for (size_t j = 0; j < n_frames; ++j) {
        const double t = ns_to_s(seq.frame_t_imu[j]);
        if (t < opt.t_begin || t > opt.t_end) continue;
        std::this_thread::sleep_until(at(t + opt.camera_latency));
        if (in_gap(opt, t)) continue;
        push_pair(h, j);
        ++res.frames_pushed;
      }
    });
    if (opt.reset_at >= 0) {
      std::this_thread::sleep_until(at(opt.reset_at));
      uvio_state st;
      uvio_get_state(h, &st);
      res.reset_count_before_reset = st.reset_count;
      uvio_reset(h);
      reset_time = opt.reset_at;
    }
    imu_thread.join();
    cam_thread.join();
  } else {
    // Single producer thread, strict timestamp order, fixed sleep per frame.
    for (int pass = 0; pass < opt.passes; ++pass) {
      size_t k = 0;
      while (k < seq.imu_t.size() && ns_to_s(seq.imu_t[k]) < opt.t_begin) ++k;
      for (size_t j = 0; j < n_frames; ++j) {
        const double t = ns_to_s(seq.frame_t_imu[j]);
        if (t < opt.t_begin || t > opt.t_end) continue;
        while (k < seq.imu_t.size() && seq.imu_t[k] <= seq.frame_t_imu[j]) {
          uvio_push_imu(h, seq.imu_t[k], seq.gyro[k].data(), seq.accel[k].data());
          ++k;
        }
        if (k < seq.imu_t.size()) {  // first sample strictly after the frame
          uvio_push_imu(h, seq.imu_t[k], seq.gyro[k].data(), seq.accel[k].data());
          ++k;
        }
        push_pair(h, j);
        ++res.frames_pushed;
        if (j == (n_frames * 3) / 10) res.rss_at_30pct = rss_bytes();
        // Pure CPU-speed flooding would finish in a few ms; the default 3333 us
        // holds ~10x real time so the estimator runs during the whole flood.
        std::this_thread::sleep_for(std::chrono::microseconds(opt.pace_us));
      }
    }
  }
  // let the estimator finish what is queued
  int64_t last_out = -1;
  for (int i = 0; i < 40; ++i) {
    uvio_state st;
    uvio_get_state(h, &st);
    if (st.states_out == last_out && i > 4) break;
    last_out = st.states_out;
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
  }
  res.wall_s = std::chrono::duration<double>(Clock::now() - wall0).count();
  res.cpu_s = cpu_seconds() - cpu0;
  res.rss_end = rss_bytes();
  uvio_get_state(h, &res.final_state);
  done = true;
  poller.join();
  uvio_destroy(h);
  return res;
}

// ----------------------------------------------------------------------------
struct Metrics {
  size_t n = 0;
  double ate_rmse = NAN, ate_max = NAN;      // after rigid (SE3) alignment over the segment
  double final_drift = NAN;                  // end-point error after aligning the first pose only
  double rot_rmse_deg = NAN, rot_max_deg = NAN;   // after the same rigid alignment
  double tilt_rmse_deg = NAN;                // gravity direction error, alignment free
  double vel_rmse = NAN;                     // world velocity after alignment
  double path_len = NAN;
};

static Metrics evaluate(const std::vector<uvio_state>& states, double t_from, double t_to, int64_t epoch = -1) {
  Metrics m;
  std::vector<Vec3> pe, pg, ve;
  std::vector<Mat3> Re, Rg;
  std::vector<double> ts;
  for (const auto& s : states) {
    const double t = ns_to_s(s.t_ns);
    if (t < t_from || t > t_to) continue;
    if (epoch >= 0 && s.reset_count != epoch) continue;
    ts.push_back(t);
    pe.emplace_back(s.p[0], s.p[1], s.p[2]);
    ve.emplace_back(s.v[0], s.v[1], s.v[2]);
    Re.push_back(Eigen::Quaterniond(s.q[3], s.q[0], s.q[1], s.q[2]).normalized().toRotationMatrix());
    pg.push_back(Trajectory::pos(t));
    Rg.push_back(Trajectory::rot(t));
  }
  m.n = ts.size();
  if (m.n < 10) return m;
  // Horn / Umeyama without scale: gt ~= R * est + t
  Vec3 ce = Vec3::Zero(), cg = Vec3::Zero();
  for (size_t i = 0; i < m.n; ++i) ce += pe[i], cg += pg[i];
  ce /= double(m.n);
  cg /= double(m.n);
  Mat3 H = Mat3::Zero();
  for (size_t i = 0; i < m.n; ++i) H += (pg[i] - cg) * (pe[i] - ce).transpose();
  Eigen::JacobiSVD<Mat3> svd(H, Eigen::ComputeFullU | Eigen::ComputeFullV);
  Mat3 D = Mat3::Identity();
  if ((svd.matrixU() * svd.matrixV().transpose()).determinant() < 0) D(2, 2) = -1;
  const Mat3 R = svd.matrixU() * D * svd.matrixV().transpose();
  const Vec3 t = cg - R * ce;
  double se = 0, sr = 0, st = 0, sv = 0;
  m.ate_max = 0;
  m.rot_max_deg = 0;
  m.path_len = 0;
  for (size_t i = 0; i < m.n; ++i) {
    const double e = (R * pe[i] + t - pg[i]).norm();
    se += e * e;
    m.ate_max = std::max(m.ate_max, e);
    const double ang = Eigen::AngleAxisd(Rg[i].transpose() * R * Re[i]).angle() * 180.0 / M_PI;
    sr += ang * ang;
    m.rot_max_deg = std::max(m.rot_max_deg, ang);
    const Vec3 ge = Re[i].transpose() * Vec3::UnitZ(), gg = Rg[i].transpose() * Vec3::UnitZ();
    const double tilt = std::acos(std::min(1.0, std::max(-1.0, ge.dot(gg)))) * 180.0 / M_PI;
    st += tilt * tilt;
    sv += (R * ve[i] - Trajectory::vel(ts[i])).squaredNorm();
    if (i) m.path_len += (pg[i] - pg[i - 1]).norm();
  }
  m.ate_rmse = std::sqrt(se / double(m.n));
  m.rot_rmse_deg = std::sqrt(sr / double(m.n));
  m.tilt_rmse_deg = std::sqrt(st / double(m.n));
  m.vel_rmse = std::sqrt(sv / double(m.n));
  // final drift: align with the first pose of the segment only
  const Sophus::SE3d Te0(Eigen::Quaterniond(Re[0]).normalized(), pe[0]);
  const Sophus::SE3d Tg0(Eigen::Quaterniond(Rg[0]).normalized(), pg[0]);
  const Sophus::SE3d A = Tg0 * Te0.inverse();
  m.final_drift = (A * pe.back() - pg.back()).norm();
  return m;
}

static void print_metrics(const char* label, const Metrics& m) {
  std::printf("  %-28s n=%zu path=%.2f m  ATE_rmse=%.4f m  ATE_max=%.4f m  final_drift=%.4f m (%.2f%% of path)\n", label,
              m.n, m.path_len, m.ate_rmse, m.ate_max, m.final_drift,
              m.path_len > 0 ? 100.0 * m.final_drift / m.path_len : NAN);
  std::printf("  %-28s rot_rmse=%.3f deg  rot_max=%.3f deg  tilt_rmse=%.3f deg  vel_rmse=%.4f m/s\n", "", m.rot_rmse_deg,
              m.rot_max_deg, m.tilt_rmse_deg, m.vel_rmse);
}

static void print_run(const RunResult& r, double stream_s) {
  const auto& f = r.final_state;
  std::vector<double> ms;
  std::vector<int> nf;
  for (auto& s : r.states) {
    ms.push_back(s.last_process_ms);
    if (s.num_features >= 0) nf.push_back(s.num_features);
  }
  std::sort(ms.begin(), ms.end());
  std::sort(nf.begin(), nf.end());
  auto pct = [&](double p) { return ms.empty() ? NAN : ms[std::min(ms.size() - 1, size_t(p * ms.size()))]; };
  double mean = 0;
  for (double v : ms) mean += v;
  if (!ms.empty()) mean /= ms.size();
  std::printf("  frames_pushed=%lld frames_in=%lld frames_dropped=%lld states_out=%lld (polled %zu) imu_in=%lld imu_dropped=%lld\n",
              (long long)r.frames_pushed, (long long)f.frames_in, (long long)f.frames_dropped, (long long)f.states_out,
              r.states.size(), (long long)f.imu_in, (long long)f.imu_dropped);
  std::printf("  reset_count=%lld auto_resets=%lld valid=%d\n", (long long)f.reset_count, (long long)f.auto_resets, f.valid);
  std::printf("  process_ms: mean=%.2f p50=%.2f p95=%.2f max=%.2f | features(cam0): min=%d median=%d max=%d\n", mean,
              pct(0.5), pct(0.95), ms.empty() ? NAN : ms.back(), nf.empty() ? -1 : nf.front(),
              nf.empty() ? -1 : nf[nf.size() / 2], nf.empty() ? -1 : nf.back());
  std::printf("  wall=%.2f s (stream %.2f s)  process CPU=%.2f s (%.2f cores avg)  RSS: before=%.1f MB peak=%.1f MB (+%.1f MB)\n",
              r.wall_s, stream_s, r.cpu_s, r.cpu_s / r.wall_s, r.rss_before / 1048576.0, r.rss_peak / 1048576.0,
              (double(r.rss_peak) - double(r.rss_before)) / 1048576.0);
}

static std::string slurp(const std::string& path) {
  std::ifstream is(path, std::ios::binary);
  if (!is) {
    std::printf("cannot open %s\n", path.c_str());
    std::exit(2);
  }
  std::ostringstream ss;
  ss << is.rdbuf();
  return ss.str();
}

static int g_fail = 0;
static void check(bool ok, const char* fmt, ...) __attribute__((format(printf, 2, 3)));
static void check(bool ok, const char* fmt, ...) {
  char buf[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  std::printf("  [%s] %s\n", ok ? "PASS" : "FAIL", buf);
  if (!ok) ++g_fail;
}

int main(int argc, char** argv) {
  if (argc < 3) {
    std::printf("usage: %s <calib.json> <vio_config.json> [--out DIR] [--duration S] [--scenarios a,b,..] [--dump-frames]\n",
                argv[0]);
    return 2;
  }
  std::setvbuf(stdout, nullptr, _IOLBF, 0);
  const std::string calib_json = slurp(argv[1]), config_json = slurp(argv[2]);
  std::string out_dir = ".", scen = "api,nominal,reset_still,reset_moving,reset_gated,gap_short,gap_long,gap_long_moving,negative_control,clock_restart,flood,init_moving_sweep";
  double duration = 31.0;
  bool dump = false;
  double reset_at = 12.0;
  for (int i = 3; i < argc; ++i) {
    const std::string a = argv[i];
    if (a == "--out" && i + 1 < argc) out_dir = argv[++i];
    else if (a == "--duration" && i + 1 < argc) duration = std::atof(argv[++i]);
    else if (a == "--scenarios" && i + 1 < argc) scen = argv[++i];
    else if (a == "--reset-at" && i + 1 < argc) reset_at = std::atof(argv[++i]);
    else if (a == "--opt" && i + 1 < argc) {
      const std::string kv = argv[++i];
      const size_t eq = kv.find('=');
      if (eq == std::string::npos) return 2;
      g_global_options.emplace_back(kv.substr(0, eq), std::atof(kv.c_str() + eq + 1));
    } else if (a == "--dump-frames") dump = true;
  }
  auto want = [&](const char* s) { return ("," + scen + ",").find(std::string(",") + s + ",") != std::string::npos; };

  // ---- thresholds (fixed before the first run; justification in NOTES.md) ----
  const double kAteNominal = 0.05;      // m, ~0.5 % of the ~10 m path
  const double kDriftPctNominal = 1.0;  // % of path length
  const double kRotNominal = 2.0;       // deg RMSE
  const double kAteRecovery = 0.10;     // m, segments that (re)initialise while moving / bridge a gap
  const double kRotRecovery = 3.0;      // deg RMSE

  std::printf("== uvio_sim_test ==\nlibrary: %s\ncalib: %s\nconfig: %s\n", uvio_version(), argv[1], argv[2]);

  Sequence seq;
  {
    std::istringstream is(calib_json);
    cereal::JSONInputArchive ar(is);
    ar(seq.calib);
  }
  const auto tg0 = Clock::now();
  generate(seq, duration, 20261115u);
  std::printf("generated %zu stereo pairs %dx%d @30 Hz, %zu IMU samples @1000 Hz, %.1f s, cam_time_offset_ns=%lld (%.1f s to render)\n",
              seq.frame_t_imu.size(), seq.w, seq.h, seq.imu_t.size(), duration, (long long)seq.calib.cam_time_offset_ns,
              std::chrono::duration<double>(Clock::now() - tg0).count());
  {
    // image statistics of a mid-sequence frame, as a sanity check of the renderer
    const size_t j = seq.frame_t_imu.size() / 2, npix = size_t(seq.w) * seq.h;
    double s = 0, s2 = 0;
    for (size_t i = 0; i < npix; ++i) s += seq.left(j)[i], s2 += double(seq.left(j)[i]) * seq.left(j)[i];
    std::printf("frame %zu left: mean=%.1f std=%.1f\n", j, s / npix, std::sqrt(s2 / npix - (s / npix) * (s / npix)));
  }
  if (dump) {
    for (size_t j : {size_t(0), seq.frame_t_imu.size() / 2}) {
      for (int c = 0; c < 2; ++c) {
        const std::string p = out_dir + "/frame" + std::to_string(j) + "_cam" + std::to_string(c) + ".pgm";
        std::ofstream os(p, std::ios::binary);
        os << "P5\n" << seq.w << " " << seq.h << "\n255\n";
        os.write(reinterpret_cast<const char*>(c ? seq.right(j) : seq.left(j)), std::streamsize(size_t(seq.w) * seq.h));
      }
    }
  }

  // -------------------------------------------------------------------------
  if (want("api")) {
    std::printf("\n-- scenario api: argument / error handling --\n");
    char err[128] = {0};
    uvio_t* bad = uvio_create("{not json", config_json.c_str(), err, sizeof(err));
    check(bad == nullptr && err[0] != 0, "garbage calibration JSON is rejected with a message (\"%s\")", err);
    err[0] = 0;
    bad = uvio_create(calib_json.c_str(), "{\"value0\":", err, sizeof(err));
    check(bad == nullptr && err[0] != 0, "garbage config JSON is rejected with a message (\"%s\")", err);
    bad = uvio_create(nullptr, nullptr, nullptr, 0);
    check(bad == nullptr, "NULL arguments are rejected");
    uvio_t* h = uvio_create(calib_json.c_str(), config_json.c_str(), err, sizeof(err));
    check(h != nullptr, "valid JSON creates a handle");
    if (h) {
      uvio_state st;
      check(uvio_get_state(h, &st) == 0 && st.valid == 0, "no state before any input");
      std::vector<uint8_t> img(size_t(seq.w) * seq.h, 128);
      uvio_push_stereo(h, kT0ns, img.data(), img.data(), seq.h, seq.w);  // swapped dimensions
      uvio_get_state(h, &st);
      check(st.frames_in == 1 && st.frames_dropped == 1, "wrong image size is counted and dropped");
      float z[3] = {0, 0, 0}, g[3] = {0, 0, float(kG)};
      uvio_push_imu(h, kT0ns + 1000, z, g);
      uvio_push_imu(h, kT0ns + 1000, z, g);  // duplicate timestamp
      uvio_push_imu(h, kT0ns + 500, z, g);   // backwards
      uvio_get_state(h, &st);
      check(st.imu_in == 1 && st.imu_dropped == 2, "non-increasing IMU timestamps are dropped");
      check(uvio_set_option(h, "max_pending", 3) == 1 && uvio_set_option(h, "nope", 1) == 0, "uvio_set_option");
      uvio_reset(h);  // reset with nothing running
      uvio_get_state(h, &st);
      check(st.reset_count == 1, "uvio_reset on an idle estimator returns");
      uvio_destroy(h);
      uvio_destroy(nullptr);
    }
  }

  // -------------------------------------------------------------------------
  if (want("nominal")) {
    std::printf("\n-- scenario nominal: full sequence, real-time pacing, IMU and camera on separate threads --\n");
    RunOpts o;
    o.name = "nominal";
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, duration);
    const Metrics m = evaluate(r.states, 0, 1e9);
    print_metrics("whole sequence", m);
    {
      std::ofstream os(out_dir + "/nominal_trajectory.csv");
      os << "t_s,est_px,est_py,est_pz,est_qx,est_qy,est_qz,est_qw,gt_px,gt_py,gt_pz,gt_qx,gt_qy,gt_qz,gt_qw,num_features,process_ms\n";
      for (auto& s : r.states) {
        const double t = ns_to_s(s.t_ns);
        const Vec3 p = Trajectory::pos(t);
        const Eigen::Quaterniond q(Trajectory::rot(t));
        os << t << "," << s.p[0] << "," << s.p[1] << "," << s.p[2] << "," << s.q[0] << "," << s.q[1] << "," << s.q[2] << ","
           << s.q[3] << "," << p.x() << "," << p.y() << "," << p.z() << "," << q.x() << "," << q.y() << "," << q.z() << ","
           << q.w() << "," << s.num_features << "," << s.last_process_ms << "\n";
      }
    }
    const auto& f = r.final_state;
    check(f.valid == 1 && f.reset_count == 0 && f.auto_resets == 0, "estimator valid, no resets");
    check(f.frames_in == r.frames_pushed && f.states_out + f.frames_dropped == f.frames_in,
          "every pushed frame is accounted for (states_out + frames_dropped == frames_in)");
    check(f.states_out >= int64_t(0.9 * double(r.frames_pushed)), "at least 90%% of frames produced a state (%lld/%lld)",
          (long long)f.states_out, (long long)r.frames_pushed);
    check(m.ate_rmse < kAteNominal, "ATE RMSE %.4f m < %.2f m", m.ate_rmse, kAteNominal);
    check(100.0 * m.final_drift / m.path_len < kDriftPctNominal, "final drift %.4f m = %.2f%% of %.2f m path < %.1f%%",
          m.final_drift, 100.0 * m.final_drift / m.path_len, m.path_len, kDriftPctNominal);
    check(m.rot_rmse_deg < kRotNominal, "orientation RMSE %.3f deg < %.1f deg", m.rot_rmse_deg, kRotNominal);
    const Vec3 bg(f.bg[0], f.bg[1], f.bg[2]), ba(f.ba[0], f.ba[1], f.ba[2]);
    std::printf("  final bias estimate: bg=(%.4f %.4f %.4f) [true 0.0020 -0.0010 0.0015]  ba=(%.3f %.3f %.3f) [true 0.020 -0.015 0.010]\n",
                bg.x(), bg.y(), bg.z(), ba.x(), ba.y(), ba.z());
  }

  // -------------------------------------------------------------------------
  auto motion_str = [](double t) {
    char b[128];
    std::snprintf(b, sizeof(b), "|v|=%.2f m/s, |a|=%.2f m/s^2, |w|=%.2f rad/s", Trajectory::vel(t).norm(),
                  Trajectory::acc(t).norm(), Trajectory::omega_body(t).norm());
    return std::string(b);
  };

  // Shared checks for a run with exactly one restart. Epoch 0 = before, epoch 1 = after.
  auto report_restart = [&](const RunResult& r, double t_event, double ate_thr, double rot_thr, bool accuracy_is_criterion) {
    const Metrics pre = evaluate(r.states, 0, 1e9, 0);
    const Metrics post = evaluate(r.states, 0, 1e9, 1);
    print_metrics("epoch 0 (before restart)", pre);
    print_metrics("epoch 1 (after restart)", post);
    std::printf("  first state of the new epoch at stream t=%.3f s (%.3f s after the event)\n", r.first_valid_after_reset_s,
                r.first_valid_after_reset_s - t_event);
    check(r.final_state.valid == 1, "estimator valid at the end");
    check(pre.ate_rmse < kAteNominal, "pre-restart ATE RMSE %.4f m < %.2f m", pre.ate_rmse, kAteNominal);
    if (accuracy_is_criterion) {
      check(post.ate_rmse < ate_thr, "post-restart ATE RMSE %.4f m < %.2f m", post.ate_rmse, ate_thr);
      check(post.rot_rmse_deg < rot_thr, "post-restart orientation RMSE %.3f deg < %.1f deg", post.rot_rmse_deg, rot_thr);
    } else {
      std::printf("  [INFO] post-restart accuracy is reported, not judged (see init_moving_sweep): ATE RMSE %.4f m, rot RMSE %.3f deg\n",
                  post.ate_rmse, post.rot_rmse_deg);
    }
    return post;
  };

  if (want("reset_still")) {
    const double t_reset = 20.6;
    std::printf("\n-- scenario reset_still: uvio_reset() at t=%.2f s during the 20.0-21.5 s stop (%s) --\n", t_reset,
                motion_str(t_reset).c_str());
    RunOpts o;
    o.name = "reset_still";
    o.reset_at = t_reset;
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, duration);
    const auto& f = r.final_state;
    check(r.reset_count_before_reset == 0 && f.reset_count == 1 && f.auto_resets == 0, "exactly one reset, none automatic");
    check(r.invalid_seen_after_reset, "state reported invalid right after uvio_reset");
    check(r.first_valid_after_reset_s > t_reset && r.first_valid_after_reset_s - t_reset < 0.5,
          "new estimator produces a state within 0.5 s (%.3f s)", r.first_valid_after_reset_s - t_reset);
    report_restart(r, t_reset, kAteNominal, kRotNominal, true);
  }

  if (want("reset_moving")) {
    std::printf("\n-- scenario reset_moving: uvio_reset() at t=%.2f s while moving (%s) --\n", reset_at, motion_str(reset_at).c_str());
    RunOpts o;
    o.name = "reset_moving";
    o.reset_at = reset_at;
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, duration);
    const auto& f = r.final_state;
    check(r.reset_count_before_reset == 0 && f.reset_count == 1 && f.auto_resets == 0, "exactly one reset, none automatic");
    check(r.invalid_seen_after_reset, "state reported invalid right after uvio_reset");
    check(r.first_valid_after_reset_s > reset_at && r.first_valid_after_reset_s - reset_at < 0.5,
          "new estimator produces a state within 0.5 s (%.3f s)", r.first_valid_after_reset_s - reset_at);
    check(f.states_out + f.frames_dropped == f.frames_in, "frame accounting holds across the reset");
    report_restart(r, reset_at, 0, 0, false);
  }

  if (want("reset_gated")) {
    std::printf("\n-- scenario reset_gated: startup_imu_gate=1, uvio_reset() at t=%.2f s while moving; the estimator may only\n"
                "   restart once the IMU has looked stationary for 30 frames (stop is at 20.0-21.5 s) --\n", reset_at);
    RunOpts o;
    o.name = "reset_gated";
    o.reset_at = reset_at;
    o.options = {{"startup_imu_gate", 1.0}};
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, duration);
    const auto& f = r.final_state;
    check(f.reset_count == 1 && f.auto_resets == 0, "exactly one reset, none automatic");
    const double tf = r.first_valid_after_reset_s;
    // The gate uses XR Gate's thresholds (mean |gyro| <= 0.08 rad/s, ...) over 30 consecutive frames, so it may
    // already open at the slow end of the 19-20 s ramp-down. What matters: no restart while really moving.
    check(tf > reset_at + 1.0 && tf > 19.0 && tf < Trajectory::kDwellEnd + 0.2 && Trajectory::vel(tf).norm() < 0.05 &&
              Trajectory::omega_body(tf).norm() < 0.08,
          "restart was held back until the device was at rest (first new state at %.3f s: |v|=%.3f m/s, |w|=%.3f rad/s)", tf,
          Trajectory::vel(tf).norm(), Trajectory::omega_body(tf).norm());
    check(f.states_out + f.frames_dropped == f.frames_in, "frame accounting holds (gated frames are counted as dropped)");
    report_restart(r, reset_at, kAteNominal, kRotNominal, true);
  }

  // -------------------------------------------------------------------------
  if (want("gap_short")) {
    std::printf("\n-- scenario gap_short: no images for 0.5 s (t=15.0..15.5 s, moving: %s), IMU keeps running --\n",
                motion_str(15.25).c_str());
    RunOpts o;
    o.name = "gap_short";
    o.image_gaps = {{15.0, 15.5}};
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, duration);
    const Metrics m = evaluate(r.states, 0, 1e9);
    const Metrics after = evaluate(r.states, 15.5, 1e9);
    print_metrics("whole sequence", m);
    print_metrics("after the gap", after);
    const auto& f = r.final_state;
    check(f.reset_count == 0 && f.valid == 1, "no restart for a gap below max_frame_gap_s, estimator valid");
    check(m.ate_rmse < kAteRecovery, "whole-sequence ATE RMSE %.4f m < %.2f m (single alignment across the gap)", m.ate_rmse,
          kAteRecovery);
    check(m.rot_rmse_deg < kRotRecovery, "orientation RMSE %.3f deg < %.1f deg", m.rot_rmse_deg, kRotRecovery);
  }

  if (want("gap_long")) {
    std::printf("\n-- scenario gap_long: no images for 2.0 s (t=18.7..20.7 s), IMU keeps running; images resume during the stop --\n");
    RunOpts o;
    o.name = "gap_long";
    o.image_gaps = {{18.7, 20.7}};
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, duration);
    const auto& f = r.final_state;
    // the poller has no explicit reset time here: take the first state of epoch 1
    double t_first = -1;
    for (auto& s : r.states)
      if (s.reset_count == 1) {
        t_first = ns_to_s(s.t_ns);
        break;
      }
    r.first_valid_after_reset_s = t_first;
    check(f.reset_count == 1 && f.auto_resets == 1, "gap above max_frame_gap_s triggers exactly one automatic restart");
    check(t_first >= 20.7 && t_first < 20.8, "first image after the gap starts the new epoch (%.3f s)", t_first);
    report_restart(r, 20.7, kAteNominal, kRotNominal, true);
  }

  if (want("gap_long_moving")) {
    std::printf("\n-- scenario gap_long_moving: no images for 2.0 s (t=13.0..15.0 s), images resume while moving (%s) --\n",
                motion_str(15.0).c_str());
    RunOpts o;
    o.name = "gap_long_moving";
    o.image_gaps = {{13.0, 15.0}};
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, duration);
    const auto& f = r.final_state;
    double t_first = -1;
    for (auto& s : r.states)
      if (s.reset_count == 1) {
        t_first = ns_to_s(s.t_ns);
        break;
      }
    r.first_valid_after_reset_s = t_first;
    check(f.reset_count == 1 && f.auto_resets == 1, "gap above max_frame_gap_s triggers exactly one automatic restart");
    check(t_first >= 15.0 && t_first < 15.1, "first image after the gap starts the new epoch (%.3f s)", t_first);
    report_restart(r, 15.0, 0, 0, false);
  }

  if (want("negative_control")) {
    std::printf("\n-- scenario negative_control: first 10 s with both images rotated by 180 deg (a wrong image orientation).\n"
                "   The check passes when the accuracy criteria of the nominal scenario are NOT met, i.e. it shows that the\n"
                "   test notices a broken input convention. --\n");
    RunOpts o;
    o.name = "negative_control";
    o.t_end = 10.0;
    o.rotate180 = true;
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, 10.0);
    const Metrics m = evaluate(r.states, 0, 1e9);
    print_metrics("first 10 s, wrong orientation", m);
    const bool nominal_ok = m.n >= 270 && m.ate_rmse < kAteNominal && m.rot_rmse_deg < kRotNominal;
    check(!nominal_ok, "wrong image orientation is detected by the accuracy criteria (ATE RMSE %.4f m, rot RMSE %.3f deg, %zu states)",
          m.ate_rmse, m.rot_rmse_deg, m.n);
  }

  if (want("clock_restart")) {
    std::printf("\n-- scenario clock_restart: the first 5 s are fed twice, i.e. all timestamps jump back by 5 s (device replug) --\n");
    RunOpts o;
    o.name = "clock_restart";
    o.realtime = false;
    o.pace_us = 33333;
    o.passes = 2;
    o.t_end = 5.0;
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, 10.0);
    const Metrics e0 = evaluate(r.states, 0, 1e9, 0), e1 = evaluate(r.states, 0, 1e9, 1);
    print_metrics("epoch 0 (first pass)", e0);
    print_metrics("epoch 1 (second pass)", e1);
    const auto& f = r.final_state;
    check(f.reset_count == 1 && f.auto_resets == 1, "backwards clock jump triggers exactly one automatic restart");
    check(f.valid == 1 && e1.n > 100, "estimator tracks again on the new clock (%zu states)", e1.n);
    check(e0.ate_rmse < kAteNominal && e1.ate_rmse < kAteNominal, "ATE RMSE %.4f m / %.4f m < %.2f m", e0.ate_rmse, e1.ate_rmse,
          kAteNominal);
  }

  // -------------------------------------------------------------------------
  if (want("init_moving_sweep")) {
    std::printf("\n-- scenario init_moving_sweep (informational, no pass/fail): a fresh estimator is started at different\n"
                "   moments of the motion and fed 8 s of data. Basalt initialises with v=0 and gravity from one accelerometer\n"
                "   sample, so this measures how well it copes with a (re)start that is NOT at rest. --\n");
    std::printf("   %8s %10s %10s %10s | %10s %10s %10s %10s %8s\n", "t_start", "|v| m/s", "|a| m/s2", "|w| rad/s", "ATE_rmse m",
                "ATE_max m", "rot_rmse", "rot_max", "states");
    int good = 0, total = 0;
    for (double t0 : {4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 20.5, 23.0}) {
      if (t0 + 8.0 > duration) continue;
      RunOpts o;
      o.name = "sweep";
      o.t_begin = t0;
      o.t_end = t0 + 8.0;
      RunResult r = run(seq, calib_json, config_json, o);
      const Metrics m = evaluate(r.states, 0, 1e9);
      const bool ok = m.n > 200 && m.ate_rmse < kAteRecovery && m.rot_rmse_deg < kRotRecovery;
      std::printf("   %8.1f %10.2f %10.2f %10.2f | %10.4f %10.4f %10.3f %10.3f %8zu  %s\n", t0, Trajectory::vel(t0).norm(),
                  Trajectory::acc(t0).norm(), Trajectory::omega_body(t0).norm(), m.ate_rmse, m.ate_max, m.rot_rmse_deg,
                  m.rot_max_deg, m.n, ok ? "ok" : "DEGRADED");
      ++total;
      good += ok;
    }
    std::printf("   %d of %d starts stayed within the recovery thresholds (ATE RMSE < %.2f m, rot RMSE < %.1f deg)\n", good, total,
                kAteRecovery, kRotRecovery);
  }

  // -------------------------------------------------------------------------
  if (want("flood")) {
    std::printf("\n-- scenario flood: whole sequence pushed at ~10x real time from one thread --\n");
    RunOpts o;
    o.name = "flood";
    o.realtime = false;
    RunResult r = run(seq, calib_json, config_json, o);
    print_run(r, duration);
    const Metrics m = evaluate(r.states, 0, 1e9);
    print_metrics("whole sequence (informational)", m);
    const auto& f = r.final_state;
    const double growth_mb = (double(r.rss_end) - double(r.rss_at_30pct)) / 1048576.0;
    std::printf("  RSS at 30%% of the flood: %.1f MB, at the end: %.1f MB (growth %.1f MB)\n", r.rss_at_30pct / 1048576.0,
                r.rss_end / 1048576.0, growth_mb);
    check(f.frames_in == r.frames_pushed, "all pushes returned and were counted (%lld)", (long long)f.frames_in);
    check(f.frames_dropped > 0, "frames_dropped increased (%lld)", (long long)f.frames_dropped);
    check(f.states_out > 0 && f.states_out + f.frames_dropped == f.frames_in,
          "states_out (%lld) + frames_dropped (%lld) == frames_in (%lld)", (long long)f.states_out,
          (long long)f.frames_dropped, (long long)f.frames_in);
    check(growth_mb < 32.0, "memory does not grow with the backlog (RSS growth %.1f MB < 32 MB over the last 70%% of the flood)",
          growth_mb);
    check(r.wall_s < duration * 0.5, "pushes never blocked for long (flood took %.2f s for %.1f s of data)", r.wall_s, duration);
  }

  std::printf("\n== RESULT: %s (%d failed checks) ==\n", g_fail ? "FAIL" : "PASS", g_fail);
  return g_fail ? 1 : 0;
}
