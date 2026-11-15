// ultra_vio: C API around upstream Basalt VIO.
//
// The estimator construction, queue wiring, timestamp handling, IMU windowing
// and teardown order follow XR Gate's backends/basalt_vio
// (capture_basalt_backend.cpp, basalt_input_feeder.hpp) and
// shared/include/capture_client/sync/synchronizer.hpp (MIT, see LICENSES/).
// Differences are listed in NOTES.md.

#include "ultra_vio.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include <tbb/concurrent_queue.h>
#include <tbb/global_control.h>

#include <cereal/archives/json.hpp>

#include <Eigen/Dense>
#include <Eigen/Geometry>

#include <basalt/calibration/calibration.hpp>
#include <basalt/imu/imu_types.h>
#include <basalt/io/dataset_io.h>
#include <basalt/optical_flow/optical_flow.h>
#include <basalt/serialization/headers_serialization.h>
#include <basalt/utils/vio_config.h>
#include <basalt/vi_estimator/vio_estimator.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#ifndef UVIO_BASALT_REV
#define UVIO_BASALT_REV "0f3b2b5"
#endif
#ifndef UVIO_BUILD_INFO
#define UVIO_BUILD_INFO ""
#endif

namespace {

using Clock = std::chrono::steady_clock;

enum LogLevel { kInfo, kWarn, kError };

void uvio_log(LogLevel lvl, const char* fmt, ...) __attribute__((format(printf, 2, 3)));
void uvio_log(LogLevel lvl, const char* fmt, ...) {
  char buf[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
#ifdef __ANDROID__
  const int prio = lvl == kInfo ? ANDROID_LOG_INFO : lvl == kWarn ? ANDROID_LOG_WARN : ANDROID_LOG_ERROR;
  __android_log_print(prio, "ultra_vio", "%s", buf);
#else
  fprintf(stderr, "[ultra_vio] %s%s\n", lvl == kInfo ? "" : lvl == kWarn ? "WARN: " : "ERROR: ", buf);
#endif
}

// ---------------------------------------------------------------------------
// XR Gate basalt_input_feeder.hpp (MIT), verbatim logic.
// Feed IMU independently of the image producer. A large IMU window must not
// block the producer before it can deliver the image the estimator awaits.
// Image queue backpressure bounds the number of outstanding batches.
template <typename Queue, typename Value>
class InputFeeder {
 public:
  InputFeeder(Queue& queue, std::function<bool()> consumer_finished)
      : queue_(queue), consumer_finished_(std::move(consumer_finished)), thread_([this] { run(); }) {}
  ~InputFeeder() {
    finish();
    join();
  }
  InputFeeder(const InputFeeder&) = delete;
  InputFeeder& operator=(const InputFeeder&) = delete;

  void submit(std::vector<Value> batch) {
    std::lock_guard<std::mutex> lock(mutex_);
    batches_.push_back(std::move(batch));
    ready_.notify_one();
  }
  void finish() {
    std::lock_guard<std::mutex> lock(mutex_);
    closing_ = true;
    ready_.notify_one();
  }
  void join() {
    if (thread_.joinable()) thread_.join();
  }

 private:
  bool send(const Value& value) {
    while (!queue_.try_push(value)) {
      if (consumer_finished_()) return false;
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return true;
  }
  void run() {
    for (;;) {
      std::vector<Value> batch;
      {
        std::unique_lock<std::mutex> lock(mutex_);
        ready_.wait(lock, [&] { return closing_ || !batches_.empty(); });
        if (batches_.empty()) break;
        batch = std::move(batches_.front());
        batches_.pop_front();
      }
      for (const auto& sample : batch)
        if (!send(sample)) return;
    }
    send(Value{});  // End of IMU input also unblocks an estimator waiting for IMU.
  }
  Queue& queue_;
  std::function<bool()> consumer_finished_;
  std::mutex mutex_;
  std::condition_variable ready_;
  std::deque<std::vector<Value>> batches_;
  bool closing_ = false;
  std::thread thread_;
};
// ---------------------------------------------------------------------------

struct ImuSample {
  int64_t t_ns;
  double gyro[3];
  double accel[3];
};

struct StereoFrame {
  int64_t t_ns = 0;  // already shifted by cam_time_offset_ns
  basalt::ManagedImage<uint16_t>::Ptr img[2];
};

// GRAY8 -> uint16 with scale 256, XR Gate's --image-scale default ("matches
// Basalt dataset image path for GRAY8 sources").
basalt::ManagedImage<uint16_t>::Ptr make_basalt_image(const uint8_t* src, int w, int h) {
  basalt::ManagedImage<uint16_t>::Ptr img(new basalt::ManagedImage<uint16_t>(w, h));
  for (int y = 0; y < h; ++y) {
    uint16_t* dst = img->RowPtr(y);
    const uint8_t* s = src + static_cast<size_t>(y) * w;
    for (int x = 0; x < w; ++x) dst[x] = static_cast<uint16_t>(s[x]) << 8;
  }
  return img;
}

struct InFlight {
  Clock::time_point fed;
  int num_features = -1;
};

// One estimator "epoch": optical flow + VIO + helper threads. Created and
// destroyed only by the worker thread.
struct Session {
  using StateQueue = tbb::concurrent_bounded_queue<basalt::PoseVelBiasState<double>::Ptr>;
  using FlowQueue = tbb::concurrent_bounded_queue<basalt::OpticalFlowResult::Ptr>;
  using ImuPtr = basalt::ImuData<double>::Ptr;

  basalt::OpticalFlowBase::Ptr opt_flow;
  basalt::VioEstimatorBase::Ptr vio;
  StateQueue out_state_queue;
  FlowQueue flow_out;  // optical flow -> relay -> vio->vision_data_queue
  std::unique_ptr<InputFeeder<decltype(basalt::VioEstimatorBase::imu_data_queue), ImuPtr>> imu_feeder;
  std::thread relay_thread;
  std::thread state_thread;
  std::atomic<bool> started{false};      // vio->initialize() has been called
  std::atomic<bool> tearing_down{false};

  std::mutex inflight_mtx;
  std::unordered_map<int64_t, InFlight> inflight;

  int64_t last_frame_t_ns = 0;  // last frame handed to optical flow
  int64_t last_imu_t_ns = 0;    // last IMU sample handed to the feeder
  bool have_imu = false;
  int gate_good = 0;            // consecutive "at rest" IMU windows (startup IMU gate)
  bool gate_passed = false;
  bool warned_no_imu = false;
};

// XR Gate startup IMU gate (compute_imu_health / imu_health_ok and its default
// thresholds): the IMU window of a frame "looks stationary".
bool imu_window_at_rest(const std::vector<ImuSample>& w, double gravity) {
  constexpr size_t kMinSamples = 10;
  constexpr double kMaxGyroNorm = 0.08, kMaxGyroStd = 0.04, kMaxAccelMagErr = 0.75, kMaxAccelStd = 0.35;
  if (w.size() < kMinSamples) return false;
  double gs = 0, gs2 = 0, as = 0, as2 = 0;
  for (const auto& s : w) {
    const double gn = std::sqrt(s.gyro[0] * s.gyro[0] + s.gyro[1] * s.gyro[1] + s.gyro[2] * s.gyro[2]);
    const double an = std::sqrt(s.accel[0] * s.accel[0] + s.accel[1] * s.accel[1] + s.accel[2] * s.accel[2]);
    gs += gn;
    gs2 += gn * gn;
    as += an;
    as2 += an * an;
  }
  const double n = double(w.size());
  const double gm = gs / n, am = as / n;
  const double gsd = std::sqrt(std::max(0.0, gs2 / n - gm * gm)), asd = std::sqrt(std::max(0.0, as2 / n - am * am));
  return gm <= kMaxGyroNorm && gsd <= kMaxGyroStd && std::abs(am - gravity) <= kMaxAccelMagErr && asd <= kMaxAccelStd;
}

}  // namespace

struct uvio {
  basalt::Calibration<double> calib;
  basalt::VioConfig config;
  int width = 0, height = 0;

  // options
  std::atomic<int64_t> max_frame_gap_ns{1'000'000'000};
  std::atomic<int64_t> max_imu_gap_ns{50'000'000};
  std::atomic<int64_t> imu_wait_us{50'000};
  std::atomic<int> max_pending{2};
  std::atomic<double> gravity_magnitude{9.80665};
  std::atomic<bool> startup_imu_gate{false};
  static constexpr int kGateGoodFrames = 30;  // XR Gate --startup-imu-good-frames
  std::mutex tbb_mtx;
  std::unique_ptr<tbb::global_control> tbb_control;

  // input side
  std::mutex in_mtx;
  std::condition_variable in_cv;
  std::deque<ImuSample> imu_buf;
  std::deque<StereoFrame> pending;
  int64_t last_imu_push_t = INT64_MIN;
  int64_t last_frame_push_t = INT64_MIN;
  bool imu_overflow = false;  // samples were discarded from imu_buf since the last feed
  // A timestamp that jumps back by more than this is a restarted device clock
  // (XR Gate: "clock moved backwards; restart capture and VIO"), not jitter.
  static constexpr int64_t kClockRestartNs = 1'000'000'000;
  void clock_restarted_locked(const char* who, int64_t t_ns);  // in_mtx held
  bool warned_size = false;
  static constexpr size_t kMaxImuBuf = 8192;

  // control
  std::atomic<bool> stop{false};
  std::atomic<uint64_t> reset_requested{0};
  std::mutex reset_mtx;
  std::condition_variable reset_cv;
  uint64_t reset_done = 0;

  // output side
  std::mutex st_mtx;
  uvio_state st{};
  std::atomic<int64_t> frames_in{0}, frames_dropped{0}, states_out{0};
  std::atomic<int64_t> reset_count{0}, auto_resets{0}, imu_in{0}, imu_dropped{0};

  std::unique_ptr<Session> session;  // worker thread only
  std::thread worker;

  // ---- worker-thread functions ----
  void start_session();
  void stop_session();
  void worker_loop();
  bool feed(StereoFrame&& frame);
};

void uvio::clock_restarted_locked(const char* who, int64_t t_ns) {
  uvio_log(kWarn, "%s timestamp jumped back to %lld: device clock restarted, flushing input and restarting estimator", who,
           (long long)t_ns);
  frames_dropped.fetch_add(static_cast<int64_t>(pending.size()));
  pending.clear();
  imu_buf.clear();
  imu_overflow = false;
  last_imu_push_t = INT64_MIN;
  last_frame_push_t = INT64_MIN;
  auto_resets.fetch_add(1);
  reset_requested.fetch_add(1);
}

void uvio::start_session() {
  auto s = std::make_unique<Session>();
  const double g = std::abs(gravity_magnitude.load());
  s->opt_flow = basalt::OpticalFlowFactory::getOpticalFlow(config, calib);
  if (!s->opt_flow) throw std::runtime_error("unsupported optical_flow_type in VIO config");
  // use_imu = true, use_double = false: XR Gate defaults.
  s->vio = basalt::VioEstimatorFactory::getVioEstimator(config, calib, Eigen::Vector3d(0.0, 0.0, -g), true, false);
  s->out_state_queue.set_capacity(10000);
  s->flow_out.set_capacity(10);
  s->opt_flow->output_queue = &s->flow_out;
  s->vio->out_state_queue = &s->out_state_queue;
  Session* sp = s.get();
  s->imu_feeder = std::make_unique<InputFeeder<decltype(sp->vio->imu_data_queue), Session::ImuPtr>>(
      sp->vio->imu_data_queue, [sp] { return sp->vio->finished.load(); });

  // Relay: XR Gate connects optical flow straight to vio->vision_data_queue.
  // The hop in between only records the cam0 feature count and makes sure the
  // optical-flow thread can never stay blocked on a stopped estimator.
  s->relay_thread = std::thread([sp] {
    for (;;) {
      basalt::OpticalFlowResult::Ptr res;
      sp->flow_out.pop(res);
      if (res) {
        std::lock_guard<std::mutex> lock(sp->inflight_mtx);
        auto it = sp->inflight.find(res->t_ns);
        if (it != sp->inflight.end() && !res->observations.empty())
          it->second.num_features = static_cast<int>(res->observations[0].size());
      }
      while (!sp->vio->vision_data_queue.try_push(res)) {
        if (sp->vio->finished.load() || (sp->tearing_down.load() && !sp->started.load())) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
      }
      if (!res) break;
    }
  });

  s->state_thread = std::thread([this, sp] {
    for (;;) {
      basalt::PoseVelBiasState<double>::Ptr data;
      sp->out_state_queue.pop(data);
      if (!data) break;
      if (sp->tearing_down.load()) continue;
      InFlight info;
      bool found = false;
      {
        std::lock_guard<std::mutex> lock(sp->inflight_mtx);
        auto it = sp->inflight.find(data->t_ns);
        if (it != sp->inflight.end()) {
          info = it->second;
          found = true;
          sp->inflight.erase(it);
        }
        // Entries of frames that never produced a state must not accumulate.
        if (sp->inflight.size() > 64) {
          for (auto i2 = sp->inflight.begin(); i2 != sp->inflight.end();)
            i2 = (i2->first < data->t_ns) ? sp->inflight.erase(i2) : std::next(i2);
        }
      }
      const Eigen::Vector3d p = data->T_w_i.translation();
      Eigen::Quaterniond q = data->T_w_i.unit_quaternion();
      q.normalize();
      const bool finite = p.allFinite() && q.coeffs().allFinite() && data->vel_w_i.allFinite();
      std::lock_guard<std::mutex> lock(st_mtx);
      if (!finite) {
        // Never hand NaNs to the app; the caller sees states_out stall.
        continue;
      }
      st.t_ns = data->t_ns;
      for (int i = 0; i < 3; ++i) {
        st.p[i] = p[i];
        st.v[i] = data->vel_w_i[i];
        st.bg[i] = data->bias_gyro[i];
        st.ba[i] = data->bias_accel[i];
      }
      st.q[0] = q.x();
      st.q[1] = q.y();
      st.q[2] = q.z();
      st.q[3] = q.w();
      st.valid = 1;
      st.num_features = found ? info.num_features : -1;
      if (found) st.last_process_ms = std::chrono::duration<double, std::milli>(Clock::now() - info.fed).count();
      states_out.fetch_add(1);
    }
  });

  session = std::move(s);
}

// Same order as the end of XR Gate's run loop.
void uvio::stop_session() {
  if (!session) return;
  Session& s = *session;
  s.tearing_down = true;
  // IMU EOS is independent of the image queue.
  s.imu_feeder->finish();
  while (!s.opt_flow->input_queue.try_push(nullptr)) {
    basalt::OpticalFlowInput::Ptr unused;
    s.opt_flow->input_queue.try_pop(unused);
  }
  s.imu_feeder->join();
  if (s.started.load()) s.vio->maybe_join();
  s.opt_flow.reset();  // joins the optical-flow thread; relay keeps draining its output
  if (s.relay_thread.joinable()) s.relay_thread.join();
  s.vio->drain_input_queues();
  s.out_state_queue.push(nullptr);
  if (s.state_thread.joinable()) s.state_thread.join();
  // Frames that were handed to Basalt but never reported a state.
  frames_dropped.fetch_add(static_cast<int64_t>(s.inflight.size()));
  s.imu_feeder.reset();
  s.vio.reset();
  session.reset();
  {
    // valid=0 and the epoch counter change together, so a state read through
    // uvio_get_state always carries the reset_count of the epoch it belongs to.
    std::lock_guard<std::mutex> lock(st_mtx);
    st.valid = 0;
    st.num_features = -1;
    reset_count.fetch_add(1);
  }
}

// Hands one frame plus its IMU window to the estimator. Returns false if the
// session must be restarted (reason already logged).
bool uvio::feed(StereoFrame&& frame) {
  Session& s = *session;

  if (s.started.load() && frame.t_ns - s.last_frame_t_ns > max_frame_gap_ns.load()) {
    uvio_log(kWarn, "stereo stream stalled for %.3f s; restarting estimator",
             double(frame.t_ns - s.last_frame_t_ns) * 1e-9);
    return false;
  }

  // XR Gate synchronizer: wait (bounded) until an IMU sample strictly newer
  // than the frame exists, then take every new sample up to and including the
  // first one after the frame ("Basalt needs the first sample strictly after
  // the frame to finish preintegration without waiting for the next image").
  std::vector<ImuSample> window;
  bool overflow = false;
  {
    std::unique_lock<std::mutex> lock(in_mtx);
    const auto covered = [&] {
      return (!imu_buf.empty() && imu_buf.back().t_ns > frame.t_ns) || (s.have_imu && s.last_imu_t_ns > frame.t_ns);
    };
    if (!in_cv.wait_for(lock, std::chrono::microseconds(imu_wait_us.load()),
                        [&] { return covered() || stop.load(); }) ||
        stop.load()) {
      if (stop.load()) return true;
      lock.unlock();
      if (!s.started.load()) {
        // Nothing to restart: the estimator simply cannot start on this frame
        // (e.g. the IMU stream is not up yet).
        frames_dropped.fetch_add(1);
        if (!s.warned_no_imu) uvio_log(kWarn, "no IMU sample newer than stereo frame t=%lld yet; waiting for IMU", (long long)frame.t_ns);
        s.warned_no_imu = true;
        return true;
      }
      uvio_log(kWarn, "IMU does not cover stereo frame t=%lld; restarting estimator", (long long)frame.t_ns);
      return false;
    }
    while (!imu_buf.empty()) {
      const ImuSample smp = imu_buf.front();
      imu_buf.pop_front();
      window.push_back(smp);
      if (smp.t_ns > frame.t_ns) break;
    }
    overflow = imu_overflow;
    imu_overflow = false;
  }

  if (s.started.load()) {
    bool discontinuity = overflow;
    int64_t prev = s.last_imu_t_ns;
    for (const auto& smp : window) {
      if (smp.t_ns - prev > max_imu_gap_ns.load()) discontinuity = true;
      prev = smp.t_ns;
    }
    if (discontinuity) {
      uvio_log(kWarn, "IMU timestamp discontinuity before frame t=%lld; restarting estimator", (long long)frame.t_ns);
      // Put the newest sample back so the fresh session can initialise from it.
      if (!window.empty() && window.back().t_ns > frame.t_ns) {
        std::lock_guard<std::mutex> lock(in_mtx);
        imu_buf.push_front(window.back());
      }
      return false;
    }
  }

  // "The initial filter only needs gravity from the first sample after its
  // first image." (XR Gate) Older samples are not sent to a fresh estimator.
  size_t first = 0;
  if (!s.started.load()) {
    if (window.empty() || window.back().t_ns <= frame.t_ns) {  // cannot initialise from this frame
      frames_dropped.fetch_add(1);
      return true;
    }
    // Optional XR Gate startup IMU gate: Basalt initialises with zero velocity
    // and takes gravity from a single accelerometer sample, i.e. it assumes
    // the device is at rest. Hold the (re)start back until it is.
    if (startup_imu_gate.load() && !s.gate_passed) {
      s.gate_good = imu_window_at_rest(window, std::abs(gravity_magnitude.load())) ? s.gate_good + 1 : 0;
      if (s.gate_good < kGateGoodFrames) {
        frames_dropped.fetch_add(1);
        return true;
      }
      s.gate_passed = true;
      uvio_log(kInfo, "startup IMU gate passed at frame t=%lld", (long long)frame.t_ns);
    }
    first = window.size() - 1;
  }
  std::vector<Session::ImuPtr> batch;
  batch.reserve(window.size() - first);
  for (size_t i = first; i < window.size(); ++i) {
    Session::ImuPtr imu(new basalt::ImuData<double>);
    imu->t_ns = window[i].t_ns;
    imu->gyro = Eigen::Vector3d(window[i].gyro[0], window[i].gyro[1], window[i].gyro[2]);
    imu->accel = Eigen::Vector3d(window[i].accel[0], window[i].accel[1], window[i].accel[2]);
    batch.push_back(std::move(imu));
  }
  if (!window.empty()) {
    s.last_imu_t_ns = window.back().t_ns;
    s.have_imu = true;
  }

  basalt::OpticalFlowInput::Ptr input(new basalt::OpticalFlowInput);
  input->t_ns = frame.t_ns;
  for (int i = 0; i < 2; ++i) {
    basalt::ImageData d;
    d.exposure = 0.0;
    d.img = std::move(frame.img[i]);
    input->img_data.emplace_back(std::move(d));
  }

  if (!s.started.load()) {
    // Initialize only once a complete input exists: sending nullptr as the
    // first IMU to an initialized VIO aborts upstream.
    s.vio->initialize(Eigen::Vector3d::Zero(), Eigen::Vector3d::Zero());
    s.started = true;
  }
  {
    std::lock_guard<std::mutex> lock(s.inflight_mtx);
    s.inflight[frame.t_ns] = InFlight{Clock::now(), -1};
  }
  s.imu_feeder->submit(std::move(batch));
  while (!s.opt_flow->input_queue.try_push(input)) {
    if (s.vio->finished.load()) {
      uvio_log(kError, "VIO stopped accepting images; restarting estimator");
      std::lock_guard<std::mutex> lock(s.inflight_mtx);
      s.inflight.erase(input->t_ns);
      return false;
    }
    if (stop.load()) return true;
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  s.last_frame_t_ns = frame.t_ns;
  return true;
}

void uvio::worker_loop() {
  uint64_t handled_reset = 0;
  while (!stop.load()) {
    try {
      if (!session) start_session();

      // explicit reset
      const uint64_t want = reset_requested.load();
      if (want != handled_reset) {
        stop_session();
        {
          std::lock_guard<std::mutex> lock(in_mtx);
          frames_dropped.fetch_add(static_cast<int64_t>(pending.size()));
          pending.clear();
        }
        handled_reset = want;
        {
          std::lock_guard<std::mutex> lock(reset_mtx);
          reset_done = want;
        }
        reset_cv.notify_all();
        uvio_log(kInfo, "estimator reset (#%lld)", (long long)reset_count.load());
        continue;
      }

      Session& s = *session;
      if (s.started.load() && s.vio->finished.load()) {
        uvio_log(kError, "VIO stopped while input is active; restarting estimator");
        auto_resets.fetch_add(1);
        stop_session();
        continue;
      }

      // Only hand over a new pair once the previous one has left the queues,
      // so at most two frames are inside Basalt (one in optical flow, one in
      // the optimiser) and the newest images are the ones that get processed.
      const bool pipeline_ready = s.opt_flow->input_queue.size() <= 0 && s.flow_out.size() <= 0 &&
                                  s.vio->vision_data_queue.size() <= 0;
      if (!pipeline_ready) {
        std::this_thread::sleep_for(std::chrono::microseconds(500));
        continue;
      }

      StereoFrame frame;
      {
        std::unique_lock<std::mutex> lock(in_mtx);
        in_cv.wait_for(lock, std::chrono::milliseconds(20), [&] {
          return stop.load() || !pending.empty() || reset_requested.load() != handled_reset;
        });
        if (stop.load() || pending.empty() || reset_requested.load() != handled_reset) continue;
        frame = std::move(pending.front());
        pending.pop_front();
      }

      StereoFrame retry;
      retry.t_ns = frame.t_ns;
      retry.img[0] = frame.img[0];
      retry.img[1] = frame.img[1];
      if (!feed(std::move(frame))) {
        auto_resets.fetch_add(1);
        stop_session();
        start_session();
        // The frame that revealed the problem is the first frame of the new epoch.
        if (!feed(std::move(retry))) {
          frames_dropped.fetch_add(1);
        }
      }
    } catch (const std::exception& e) {
      uvio_log(kError, "worker exception: %s", e.what());
      try {
        stop_session();
      } catch (...) {
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
    } catch (...) {
      uvio_log(kError, "worker: unknown exception");
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
  }
  try {
    stop_session();
  } catch (...) {
  }
  {
    std::lock_guard<std::mutex> lock(reset_mtx);
    reset_done = UINT64_MAX;
  }
  reset_cv.notify_all();
}

// ===========================================================================
// C API
// ===========================================================================

namespace {
void set_err(char* err, int err_len, const std::string& msg) {
  if (err && err_len > 0) {
    std::snprintf(err, static_cast<size_t>(err_len), "%s", msg.c_str());
  }
  uvio_log(kError, "%s", msg.c_str());
}
}  // namespace

extern "C" {

UVIO_API uvio_t* uvio_create(const char* calib_json, const char* config_json, char* err, int err_len) {
  if (err && err_len > 0) err[0] = 0;
  try {
    if (!calib_json || !config_json) {
      set_err(err, err_len, "calib_json/config_json is NULL");
      return nullptr;
    }
    auto h = std::make_unique<uvio>();
    try {
      std::istringstream is{std::string(calib_json)};
      cereal::JSONInputArchive ar(is);
      ar(h->calib);
    } catch (const std::exception& e) {
      set_err(err, err_len, std::string("failed to parse calibration JSON: ") + e.what());
      return nullptr;
    }
    try {
      h->config.load_from_string(config_json);
    } catch (const std::exception& e) {
      set_err(err, err_len, std::string("failed to parse VIO config JSON: ") + e.what());
      return nullptr;
    }
    const auto& c = h->calib;
    if (c.intrinsics.size() != 2 || c.T_i_c.size() != 2 || c.resolution.size() != 2) {
      set_err(err, err_len, "calibration must describe exactly 2 cameras (stereo)");
      return nullptr;
    }
    if (c.resolution[0] != c.resolution[1] || c.resolution[0][0] <= 0 || c.resolution[0][1] <= 0) {
      set_err(err, err_len, "calibration resolutions must be equal and positive");
      return nullptr;
    }
    h->width = c.resolution[0][0];
    h->height = c.resolution[0][1];
    // The Basalt factory calls std::abort() on unsupported values: check first.
    const auto& t = h->config.optical_flow_type;
    const int pat = h->config.optical_flow_pattern;
    if (t != "frame_to_frame" && t != "patch" && t != "multiscale_frame_to_frame") {
      set_err(err, err_len, "unsupported config.optical_flow_type: " + t);
      return nullptr;
    }
    if (pat != 24 && pat != 50 && pat != 51 && pat != 52) {
      set_err(err, err_len, "unsupported config.optical_flow_pattern: " + std::to_string(pat));
      return nullptr;
    }
    if (h->config.optical_flow_skip_frames < 1 || h->config.optical_flow_levels < 1 ||
        h->config.optical_flow_detection_grid_size < 8 || h->config.vio_max_states < 1 ||
        h->config.vio_max_kfs < 1) {
      set_err(err, err_len, "VIO config has out-of-range values");
      return nullptr;
    }
    h->st.num_features = -1;
    uvio_log(kInfo, "%s: %dx%d stereo, cam_time_offset_ns=%lld, imu_update_rate=%.1f", uvio_version(), h->width,
             h->height, (long long)c.cam_time_offset_ns, c.imu_update_rate);
    uvio* raw = h.get();
    h->worker = std::thread([raw] { raw->worker_loop(); });
    return h.release();
  } catch (const std::exception& e) {
    set_err(err, err_len, std::string("uvio_create: ") + e.what());
  } catch (...) {
    set_err(err, err_len, "uvio_create: unknown exception");
  }
  return nullptr;
}

UVIO_API void uvio_destroy(uvio_t* h) {
  if (!h) return;
  try {
    h->stop = true;
    {
      std::lock_guard<std::mutex> lock(h->in_mtx);
    }
    h->in_cv.notify_all();
    if (h->worker.joinable()) h->worker.join();
    delete h;
  } catch (...) {
  }
}

UVIO_API void uvio_push_imu(uvio_t* h, int64_t t_ns, const float gyro[3], const float accel[3]) {
  if (!h || !gyro || !accel) return;
  try {
    ImuSample s;
    s.t_ns = t_ns;
    for (int i = 0; i < 3; ++i) {
      s.gyro[i] = gyro[i];
      s.accel[i] = accel[i];
      if (!std::isfinite(gyro[i]) || !std::isfinite(accel[i])) {
        h->imu_dropped.fetch_add(1);
        return;
      }
    }
    {
      std::lock_guard<std::mutex> lock(h->in_mtx);
      if (t_ns <= h->last_imu_push_t) {
        if (h->last_imu_push_t - t_ns > uvio::kClockRestartNs) {
          h->clock_restarted_locked("IMU", t_ns);
        } else {
          h->imu_dropped.fetch_add(1);
          return;
        }
      }
      h->last_imu_push_t = t_ns;
      h->imu_buf.push_back(s);
      if (h->imu_buf.size() > uvio::kMaxImuBuf) {
        h->imu_buf.pop_front();
        h->imu_overflow = true;
        h->imu_dropped.fetch_add(1);
      }
    }
    h->imu_in.fetch_add(1);
    h->in_cv.notify_all();
  } catch (...) {
  }
}

UVIO_API void uvio_push_stereo(uvio_t* h, int64_t t_ns, const uint8_t* left, const uint8_t* right, int width,
                               int height) {
  if (!h || !left || !right) return;
  try {
    h->frames_in.fetch_add(1);
    if (width != h->width || height != h->height) {
      h->frames_dropped.fetch_add(1);
      bool warn = false;
      {
        std::lock_guard<std::mutex> lock(h->in_mtx);
        warn = !h->warned_size;
        h->warned_size = true;
      }
      if (warn)
        uvio_log(kError, "stereo pair is %dx%d but the calibration expects %dx%d; dropping", width, height, h->width,
                 h->height);
      return;
    }
    // Kalibr/Basalt convention: t_imu = t_camera + offset. Applied exactly once, here.
    const int64_t off = h->calib.cam_time_offset_ns;
    if ((off > 0 && t_ns > INT64_MAX - off) || (off < 0 && t_ns < INT64_MIN - off)) {
      h->frames_dropped.fetch_add(1);
      return;
    }
    StereoFrame f;
    f.t_ns = t_ns + off;
    f.img[0] = make_basalt_image(left, width, height);
    f.img[1] = make_basalt_image(right, width, height);
    {
      std::lock_guard<std::mutex> lock(h->in_mtx);
      if (f.t_ns <= h->last_frame_push_t) {  // duplicate / backwards timestamps would abort inside Basalt
        if (h->last_frame_push_t - f.t_ns > uvio::kClockRestartNs) {
          h->clock_restarted_locked("camera", f.t_ns);
        } else {
          h->frames_dropped.fetch_add(1);
          return;
        }
      }
      h->last_frame_push_t = f.t_ns;
      const size_t cap = static_cast<size_t>(std::clamp(h->max_pending.load(), 1, 8));
      while (h->pending.size() >= cap) {
        h->pending.pop_front();
        h->frames_dropped.fetch_add(1);
      }
      h->pending.push_back(std::move(f));
    }
    h->in_cv.notify_all();
  } catch (...) {
  }
}

UVIO_API int uvio_get_state(uvio_t* h, uvio_state* out) {
  if (!h || !out) return 0;
  try {
    {
      std::lock_guard<std::mutex> lock(h->st_mtx);
      *out = h->st;
      out->reset_count = h->reset_count.load();
    }
    out->frames_in = h->frames_in.load();
    out->frames_dropped = h->frames_dropped.load();
    out->states_out = h->states_out.load();
    out->auto_resets = h->auto_resets.load();
    out->imu_in = h->imu_in.load();
    out->imu_dropped = h->imu_dropped.load();
    return out->valid ? 1 : 0;
  } catch (...) {
    return 0;
  }
}

UVIO_API void uvio_reset(uvio_t* h) {
  if (!h) return;
  try {
    const uint64_t id = h->reset_requested.fetch_add(1) + 1;
    {
      std::lock_guard<std::mutex> lock(h->in_mtx);
    }
    h->in_cv.notify_all();
    std::unique_lock<std::mutex> lock(h->reset_mtx);
    h->reset_cv.wait_for(lock, std::chrono::seconds(5), [&] { return h->reset_done >= id; });
  } catch (...) {
  }
}

UVIO_API const char* uvio_version(void) {
  return "ultra_vio 1.0 (basalt " UVIO_BASALT_REV UVIO_BUILD_INFO ")";
}

UVIO_API int uvio_set_option(uvio_t* h, const char* key, double value) {
  if (!h || !key || !std::isfinite(value)) return 0;
  try {
    const std::string k(key);
    if (k == "max_frame_gap_s" && value > 0) {
      h->max_frame_gap_ns = static_cast<int64_t>(value * 1e9);
    } else if (k == "max_imu_gap_s" && value > 0) {
      h->max_imu_gap_ns = static_cast<int64_t>(value * 1e9);
    } else if (k == "imu_wait_s" && value >= 0) {
      h->imu_wait_us = static_cast<int64_t>(value * 1e6);
    } else if (k == "max_pending" && value >= 1 && value <= 8) {
      h->max_pending = static_cast<int>(value);
    } else if (k == "startup_imu_gate") {
      h->startup_imu_gate = value != 0;
    } else if (k == "gravity_magnitude" && value > 0) {
      h->gravity_magnitude = value;
    } else if (k == "num_threads" && value >= 0) {
      std::lock_guard<std::mutex> lock(h->tbb_mtx);
      h->tbb_control.reset();
      if (value >= 1)
        h->tbb_control = std::make_unique<tbb::global_control>(tbb::global_control::max_allowed_parallelism,
                                                               static_cast<size_t>(value));
    } else {
      return 0;
    }
    return 1;
  } catch (...) {
    return 0;
  }
}

}  // extern "C"
