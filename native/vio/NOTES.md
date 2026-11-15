# ultra_vio — Basalt VIO for Android arm64 behind a small C API

Everything here was built and tested on 2026-11-15 on a 4-core Xeon (2.1 GHz) Linux x86_64 host.
**Nothing has been run on an Android device or on real XREAL data** (see "Not verified").

## Layout

| Path | What |
|---|---|
| `android/arm64-v8a/libultra_vio.so` | the library (stripped, 1,903,720 bytes, sha256 `a78a24b2…f459`) |
| `include/ultra_vio.h` | C API |
| `src/ultra_vio.cpp`, `src/ultra_vio.map` | wrapper, linker version script (exports `uvio_*` only) |
| `src/CMakeLists.txt` | one CMake project for Android and host; compiles only Basalt's VIO path |
| `src/patches/0001-…patch` | the only change to upstream Basalt |
| `src/versions.env`, `src/fetch_deps.sh` | pinned revisions, clean-clone fetch |
| `src/build_android.sh`, `src/build_host.sh` | reproduce both builds from clean clones (both were run end to end from an empty directory) |
| `test/uvio_sim_test.cpp`, `test/run_sim_test.sh` | generator + test |
| `test/output/uvio_sim_test.log` | captured output of the final run (`.raw.log` also has Basalt's own stdout chatter), `nominal_trajectory.csv` = estimate vs ground truth, `sample_frame_cam0_cam1.png` = one rendered stereo pair |
| `LICENSES/` | Basalt, basalt-headers, Eigen, Sophus, cereal (+ its bundled rapidjson/base64), fmt, oneTBB, magic_enum, nlohmann-json, XR Gate (InputFeeder code), OpenCV (dynamic on Android, static in the host test library) |

## Upstream revisions (all pinned in `src/versions.env`)

| Component | Revision | Notes |
|---|---|---|
| basalt | `0f3b2b52c807f70ff4e2973ce253c73329eea7bc` (github.com/VladyslavUsenko/basalt) | same commit XR Gate uses |
| basalt-headers | `aa441ba3e51050c47ba1902537792a2e4db7e43d` (gitlab.com/VladyslavUsenko/basalt-headers) | the ref in basalt's own `vcpkg/ports/basalt-headers` |
| Eigen | 5.0.1 `bc3b39870ecb690a623a3f49149a358b95c5781d` | versions below = basalt's vcpkg baseline `05442024c3fd` |
| Sophus | 1.24.6 `d0b7315a0d90fc6143defa54596a3a95d9fa10ec` | |
| cereal | 1.3.2 `ebef1e929807629befafbb2918ea1a08c7194554` | |
| fmt | 12.1.0 `407c905e45ad75fc29bf0f9bb7c5c2fd3475976f` | header-only mode |
| oneTBB | 2022.3.0 `f1862f38f83568d96e814e469ab61f88336cc595` | static |
| magic_enum | 0.9.7 `e046b69a3736d314fad813e159b1c192eaef92cd` | |
| nlohmann-json | 3.12.0 `55f93686c01528224f448c19128836e7df245f72` | only for basalt `time_utils.cpp` |
| OpenCV (host test only) | 4.12.0 `49486f61fb25722cbcf586b7f4320921d46fb38e` | minimal static core/imgproc/features2d/flann |

Android toolchain: NDK 27.2.12479018 (clang 18), `arm64-v8a`, `android-29`, `c++_shared`, Release `-O3 -DNDEBUG`, no `-march`.

## What is compiled, what was dropped

Compiled from Basalt: `linearization/*`, `optical_flow/optical_flow.cpp`, `vi_estimator/{ba_base,landmark_database,marg_helper,sc_ba_base,sqrt_ba_base,sqrt_keypoint_vio,sqrt_keypoint_vo,vio_estimator}.cpp`,
`utils/{keypoints,system_utils,time_utils,vio_config}.cpp`. Only the `float` instantiation is built (`BASALT_INSTANTIATIONS_FLOAT`; XR Gate runs `use_double=false`).

Not needed and not built: Pangolin, GUI apps, ROS/rosbag, calibration, mapper (`nfr_mapper`), dataset IO .cpp, apriltag, realsense, Boost, lz4, CLI11, gtest, **opengv**.

**OpenCV is still needed**: Basalt's optical flow detects corners with `cv::FAST` (`utils/keypoints.cpp`, plus `cv::Mat`). On Android the library links the app's existing
`libopencv_java4.so` (4.12.0 AAR) dynamically; no second OpenCV is built or bundled. Imported OpenCV symbols: `cv::FAST`, `cv::Mat::Mat(int,int,int)`, `cv::Mat::~Mat` only.

### Patch to Basalt (`src/patches/0001-basalt-no-opengv-and-config-from-string.patch`, 16 added lines)

1. `src/utils/keypoints.cpp`: opengv includes and `findInliersRansac()` wrapped in `#ifndef BASALT_NO_OPENGV`. That function is only used by the mapper; this removes the opengv dependency.
2. `VioConfig::load_from_string()` added next to `load(filename)` so the config can be parsed from memory (the cereal serializer for `VioConfig` is private to `vio_config.cpp`).

XR Gate's own patch (`basalt_vio_integration.patch`) only adds a CMake hook and removes realsense from vcpkg.json; it changes no estimator code, so it is not applied.

## Android library facts (`llvm-readelf -d`, final artifact)

```
NEEDED liblog.so, libdl.so, libopencv_java4.so, libm.so, libc++_shared.so, libc.so
SONAME libultra_vio.so
LOAD segments aligned 0x4000 (-Wl,-z,max-page-size=16384)
exported (llvm-nm -D --defined-only): uvio_create uvio_destroy uvio_get_state uvio_push_imu
                                      uvio_push_stereo uvio_reset uvio_set_option uvio_version   (nothing else)
```
Linked with `-fvisibility=hidden`, `-Wl,--exclude-libs,ALL`, a version script, `-Wl,--no-undefined` (so every import resolves against the API-29 stubs, `libc++_shared` and the real `libopencv_java4.so`), `--gc-sections`.
The NDK always adds `-g`; the unstripped .so is ~210 MB, the shipped one is `llvm-strip --strip-unneeded`.
Build time: ~3 min at `-j2`, no OOM on the 15 GB machine.

## API additions (documented in the header)

* `uvio_state` got four fields appended: `reset_count` (epoch counter; the world frame changes when it increments), `auto_resets`, `imu_in`, `imu_dropped`.
* `int uvio_set_option(uvio_t*, const char* key, double value)`: `max_frame_gap_s` (1.0), `max_imu_gap_s` (0.05), `imu_wait_s` (0.05), `max_pending` (2), `startup_imu_gate` (0), `gravity_magnitude` (9.80665), `num_threads` (0 = TBB default).
* `frames_dropped` counts every pushed pair that did not reach the estimator (backlog, bad size/timestamp, no IMU coverage, startup gate), so `states_out + frames_dropped == frames_in` once the queues are empty.
* `uvio_state.t_ns` is on the IMU clock: camera `t_ns` + `cam_time_offset_ns`.

## Input conventions the wrapper expects

These are exactly what XR Gate's capture service delivers to its Basalt backend with the `unified_480_ccw90_device_time` profile. I derived them from XR Gate's code and config, not from hardware.

**Images**
* 8-bit gray, row-major, **480 wide x 640 high** (the calibration's `resolution`); any other size is rejected and counted as dropped.
* `left` = calibration camera 0 = XR Gate `camera0` = the eye whose frame footer byte `0x3B` is 0; `right` = camera 1 = footer byte `0x3B` != 0.
* Orientation relative to the raw, de-scrambled 640x480 sensor image (XR Gate `capture_service` config `transform:`):
  * left: rotated **90° counter-clockwise** (`cv::ROTATE_90_COUNTERCLOCKWISE`): `out[r][c] = raw[c][639 - r]`
  * right: `ccw90` + `flip: xy`, which is **90° clockwise** (`cv::ROTATE_90_CLOCKWISE`): `out[r][c] = raw[479 - c][r]`
  * (`r` in 0..639, `c` in 0..479; `raw[row][col]`.) The raw image must first be un-shuffled as in XR Gate `xreal_camera_decoder.cpp` (128 chunks of 2400 bytes).
* Internally converted to 16 bit with scale 256 (XR Gate `--image-scale 256`). No exposure/vignette handling.
* One stereo pair = two eyes with the same device timestamp (XR Gate pairs on footer `t62` and requires |Δt| <= 1 ms).

**IMU**
* Frame = the calibration's IMU frame = the raw XREAL HID axes, identity mapping (XR Gate: "No imu.transform section"). From `T_imu_cam` in the calibration this frame is approximately **x = wearer's left, y = backward, z = up** (cameras look along -y; at rest and level the accelerometer reads about (0, 0, +9.81)).
* gyro in rad/s, accel in m/s² as specific force (reads +g when static). XR Gate decoding of the 24-bit raw values: gyro `raw / 2^23 * 2000 deg/s`, accel `raw / 2^23 * 16 g * 9.80665` (HID report bytes 18/21/24 and 33/36/39).
* No bias/scale correction is applied by the wrapper beyond what the calibration JSON contains (all zero in this profile).

**Timestamps**
* int64 nanoseconds, **one common clock for IMU and images**, strictly increasing per stream. XR Gate's `device_time` profile uses the glasses' own clock for both: IMU = HID report bytes 4..11, camera = frame footer bytes 62..69 (`t62`), both passed through the same affine device→host mapper. Do not mix host-receive time for one stream with device time for the other.
* Pass the **raw camera timestamp**; the wrapper adds the calibration's `cam_time_offset_ns` (−1,830,051 ns here; convention `t_imu = t_camera + offset`, applied once, as in XR Gate's synchronizer). The second XR Gate profile (`unified_480_ccw90`, host receive time) has −28.1 ms and different intrinsics: use the calibration that matches how you timestamp.
* Non-increasing timestamps are dropped. A backwards jump of more than 1 s on either stream is treated as a restarted device clock: input is flushed and the estimator restarts.

**Rates**
* Calibration/IMU noise model assumes 1000 Hz IMU (`imu_update_rate`); stereo at 30 pairs/s. Each frame needs an IMU sample newer than itself within `imu_wait_s` (50 ms wall time) of being picked up, and the IMU stream must have no gap above `max_imu_gap_s` (50 ms), otherwise the estimator is restarted (XR Gate behaviour).

**Output frame**: world z up (gravity = (0,0,−9.80665)), origin at the IMU position of the first frame of the epoch, yaw fixed by the initial pose. `p`, `q` are IMU-in-world (`T_w_i`), not camera or display pose.

## XR Gate config values that matter (`basalt_vio_config_640x480.json`)

* `optical_flow_type = frame_to_frame`, `optical_flow_pattern = 51`, `optical_flow_levels = 3`, `optical_flow_max_iterations = 5`, `optical_flow_detection_grid_size = 50` (one FAST corner per 50 px cell; the simulated run tracked 108–344 cam0 points), `optical_flow_epipolar_error = 0.005`, `optical_flow_max_recovered_dist2 = 0.09`.
* `optical_flow_skip_frames = 1` — must stay 1: the wrapper's frame accounting assumes one state per frame handed over.
* `vio_linearization_type = ABS_QR`, `vio_sqrt_marg = true`, `vio_max_states = 3`, `vio_max_kfs = 7`, `vio_max_iterations = 7`, `vio_use_lm = true`, `vio_obs_std_dev = 0.5`, `vio_obs_huber_thresh = 1.0`, `vio_min_triangulation_dist = 0.05`.
* `vio_init_pose_weight = 1e8`, `vio_init_ba_weight = 10`, `vio_init_bg_weight = 100` (initial prior; position and yaw only).
* `vio_enforce_realtime = false` (as XR Gate runs it): Basalt itself never drops frames, the wrapper does it in front of optical flow.
* From the calibration: `kb4` intrinsics, `T_imu_cam`, `cam_time_offset_ns`, IMU noise densities (`gyro_noise_std = 1e-3`, `accel_noise_std = 1e-2`, bias random walks `1e-4` / `1e-3`), `imu_update_rate = 1000`.
* `uvio_create` rejects unsupported `optical_flow_type` / `optical_flow_pattern` values, because Basalt's factory would `std::abort()` on them.

## How the wrapper follows XR Gate (and where it differs)

Same as `capture_basalt_backend.cpp` / `synchronizer.hpp` / `basalt_input_feeder.hpp`:
* `OpticalFlowFactory::getOpticalFlow(config, calib)` + `VioEstimatorFactory::getVioEstimator(config, calib, (0,0,-g), use_imu=true, use_double=false)`, state queue capacity 10000.
* `vio->initialize(0, 0)` only once the first complete input (frame + an IMU sample after it) exists; the first IMU batch is only that one sample.
* IMU window per frame: every new sample up to and including the first one strictly after the frame; IMU is pushed by a separate feeder thread (XR Gate's `InputFeeder`, copied), images by `try_push` loops.
* Restart (never an in-place repair) on: stereo stream stalled > 1 s, IMU not covering a frame, IMU discontinuity > 50 ms, VIO thread finished. Teardown order is XR Gate's (IMU EOS, null frame, join VIO, drain optical flow, drain queues).
* Optional startup IMU gate with XR Gate's thresholds (>= 10 samples, mean |gyro| <= 0.08 rad/s, std <= 0.04, | |accel| − g | <= 0.75, std <= 0.35, 30 consecutive frames); off by default as in XR Gate.

Differences:
* Stall detection uses the gap between consecutive frame timestamps instead of 1 s of wall time without a packet.
* A small relay thread sits between optical flow and `vio->vision_data_queue` to read the cam0 feature count (`num_features`).
* Frames are dropped in front of optical flow: at most `max_pending` (2) pairs wait, and a pair is only handed over when Basalt's queues are empty, so at most two frames are inside Basalt. XR Gate instead applies backpressure to its capture reader.
* A frame that cannot start a not-yet-running estimator (no IMU yet) is dropped quietly instead of triggering a restart loop.
* XR Gate's visual startup gate (brightness/corner checks with `goodFeaturesToTrack`) is not ported.
* States containing NaN/Inf are not published.

## Threading model

* Caller threads: `uvio_push_imu` and `uvio_push_stereo` may be called from two different threads; both only take a short mutex (the stereo push also converts 2 x 307 kB to 16 bit, ~0.2 ms). `uvio_get_state`, `uvio_set_option`, `uvio_reset` are safe from any thread. `uvio_reset` blocks until the old estimator is gone (ms; 5 s cap). `uvio_destroy` must not race with other calls on the same handle.
* Library threads per handle: 1 worker (owns the estimator), and per estimator epoch: Basalt optical-flow thread, Basalt VIO thread, IMU feeder, relay, state reader; plus the TBB worker pool (process-wide, default = number of cores; limit it with `uvio_set_option(h, "num_threads", n)`).
* No exception crosses the C boundary (all entry points and thread bodies catch). **Basalt's internal `BASALT_ASSERT`s call `std::abort()`**; they are left enabled (upstream behaviour). The wrapper guards the preconditions it controls (strictly increasing timestamps, non-null first IMU, config values), but an internal assertion would still kill the process.
* Logging: `__android_log_print` tag `ultra_vio` on Android, stderr on host. Basalt itself prints a few lines to stdout at each (re)start.

## Cost measured on the host (x86_64, 4 cores, not arm64)

Simulated 480x640 stereo @ 30 Hz + 1 kHz IMU, real-time feed, numbers from `test/output/uvio_sim_test.log` and two extra runs:

| TBB threads | per-frame latency (hand-over → state) mean / p95 / max | process CPU (includes the test's feeder/poller threads) |
|---|---|---|
| default (4) | 9.6 / 15.0 / 21.3 ms | 1.16 cores |
| 2 | 14.1 / 21.7 / 31.7 ms | 0.91 cores |
| 1 | 24.6 / 42.0 / 99.1 ms | 0.80 cores |

* Memory: +24…30 MB RSS for a running estimator (first instance in the process); flat over time (flood test: +0.0 MB over the last 70 %).
* No frame was dropped at 30 Hz in any real-time scenario on this host. With 1 thread the mean is already close to the 33 ms frame period, so a phone with slower cores may need to drop frames; this is not measured.

## Test (`test/uvio_sim_test.cpp`)

Generator (fixed seeds, analytic ground truth):
* Scene: inside of a 6 x 5 x 2.7 m box, every face covered with random gray rectangles (6–45 cm, 1 cm texels, blurred), rendered by ray casting through the calibration's KB4 models and `T_imu_cam` for both cameras, plus ±4 gray levels of pixel noise.
* Trajectory of the IMU frame, 31 s: 1 s at rest, 2 s ramp, then a 1.0 x 0.5 m figure-eight with 0.1 m vertical bob and yaw ±29° / pitch ±8.6° / roll ±8.6° oscillation (up to 0.56 m/s, 0.35 rad/s), with one full stop at 20.0–21.5 s. Path length 10.4 m.
* IMU 1000 Hz: gyro = body angular rate, accel = `R^T (a_world + (0,0,g))`, with per-sample noise σ = 0.005 rad/s and 0.05 m/s² and constant biases (0.002, −0.001, 0.0015) rad/s and (0.02, −0.015, 0.01) m/s².
* Camera timestamps are generated as `t_exposure − cam_time_offset_ns`, so the wrapper's offset handling is part of what is tested. Frames are delivered 12 ms after exposure from a camera thread, IMU from a separate 1 kHz thread.
* Evaluation: states polled through `uvio_get_state`, compared with ground truth at the state timestamp. ATE = position RMSE after rigid (rotation + translation, no scale) alignment; final drift = end-point error after aligning the first pose only; orientation error after the same rigid alignment; tilt error is alignment-free.

Thresholds (fixed before the first run, never loosened):
* Nominal: ATE RMSE < 0.05 m (0.5 % of the path), final drift < 1 % of path length, orientation RMSE < 2°. Typical published VIO accuracy is 0.5–1 % of path; the synthetic data is cleaner than real data, so passing these is a necessary sanity level, not a performance claim.
* Segments after a restart at rest use the same thresholds. `gap_short` (bridging 0.5 s without images) uses 0.10 m / 3°.
* Flood: `frames_dropped > 0`, frame accounting exact, RSS growth < 32 MB, pushes return (31 s of data in < 15.5 s).

Results of the final run (57 checks passed, 0 failed, exit code 0):

| Scenario | Result |
|---|---|
| nominal (31 s) | 930/930 states, 0 dropped. ATE RMSE **1.9 mm** (max 6.9 mm), final drift **4.0 mm = 0.04 %** of 10.36 m, orientation RMSE **0.12°** (max 0.75°), tilt 0.09°, velocity RMSE 4.3 mm/s. Gyro bias recovered to 1e-4 rad/s; accel bias (0.012, −0.015, 0.010) vs true (0.020, −0.015, 0.010) |
| (a) reset_still — `uvio_reset` at 20.6 s, at rest | new state after 1 frame; new epoch ATE RMSE 12.4 mm (max 67 mm), orientation RMSE 0.54° (max 3.9°) |
| (a) reset_moving — `uvio_reset` at 12 s, moving 0.41 m/s | API contract holds (invalid → valid in 1 frame, accounting exact) but **the new epoch diverges: ATE RMSE 1.67 m, orientation RMSE 21°**. Reported, not judged |
| (a) reset_gated — same reset with `startup_imu_gate=1` | restart held back until 20.47 s (at rest); new epoch ATE RMSE 9.8 mm, orientation 0.41°; 284 frames without pose in between |
| (b) gap_short — 0.5 s without images while moving | no restart; whole-sequence ATE RMSE 2.6 mm, orientation 0.17° |
| (b) gap_long — 2 s without images, resume at rest | exactly one automatic restart on the first image; new epoch ATE RMSE 8.7 mm, orientation 0.45° |
| (b) gap_long_moving — 2 s gap, resume while moving | one automatic restart; new epoch ATE RMSE 5.8 mm, orientation 0.57° (max 3.4°). Reported, not judged |
| (c) flood — 31 s pushed in 3.7 s | 402 of 930 dropped, 528 states, RSS growth 0.0 MB, ATE of what was processed 2.6 mm |
| clock_restart — timestamps jump back 5 s | one automatic restart, second epoch ATE RMSE 2.2 mm |
| negative_control — both images rotated 180° | ATE RMSE 57 m: the criteria do catch a wrong input convention |
| api | bad JSON / NULL / wrong image size / non-increasing IMU timestamps handled |

### Main finding: restarting while moving is unreliable

Upstream Basalt initialises with zero velocity and takes gravity from a single accelerometer sample, i.e. it assumes the device is at rest. `init_moving_sweep` starts a fresh estimator at nine moments and feeds 8 s each:

| start | speed | ATE RMSE | orientation RMSE |
|---|---|---|---|
| 20.5 s (at rest) | 0 | 0.010 m | 0.7° |
| 10 s | 0.56 m/s | 0.012 m | 0.3° |
| 16 s | 0.39 m/s | 0.007 m | 0.5° |
| 23 s | 0.32 m/s | 0.039 m | 2.6° |
| 4 s | 0.41 m/s | 0.029 m | 3.6° |
| 8 s | 0.39 m/s | 0.052 m | 4.7° |
| 6 s | 0.56 m/s | 0.104 m | 6.1° |
| 14 s | 0.56 m/s | 0.31 m | 48° |
| 12 s | 0.41 m/s | 2.1 m | 26° |

Only 4 of 9 stay within 0.10 m / 3°, two diverge and do not recover within 8 s (the 12 s case not within 19 s). This applies equally to `uvio_reset`, to the automatic restarts and to app start. XR Gate has the same behaviour and offers its startup gate for it.
Consequences for the app:
* Start tracking and call `uvio_reset` when the head is still, or enable `startup_imu_gate` (then there is no pose until the user holds still for 1 s).
* `valid = 1` only means "the estimator produced a finite state". There is no divergence detection: the diverged runs and the negative control kept reporting `valid = 1`. The app needs its own plausibility check (e.g. speed or position jumps) to decide when to reset.
* A real fix would be an in-motion initialiser (e.g. bootstrap with Basalt's stereo VO estimator and start VIO through `initialize(t, T_w_i, v, bg, ba)`). That is a different pipeline from XR Gate's and was not built.

## Not verified

* **The Android library has never been loaded or run.** No device, emulator or arm64 execution was available. Verified only: it compiles and links with `--no-undefined`, ELF/DT_NEEDED/exports/16 kB alignment as listed. Runtime behaviour on arm64 (NEON code paths, static oneTBB on Android, float-precision behaviour, performance, thermal) is untested. The host test exercises the same sources compiled with GCC 13 for x86_64.
* **No real XREAL data.** Image orientation, left/right assignment, IMU axes, units and the timestamp sources are taken from XR Gate's code/config, and the IMU axis description is inferred from the calibration. The synthetic test proves the wrapper is self-consistent with the calibration JSON, not that the calibration or these conventions match a given headset. The calibration is for one unit (serial ZBBM5DZFMP); another headset needs its own.
* Real-image effects absent from the simulation: motion blur, rolling shutter, exposure changes, low texture, dynamic objects, lens vignetting, IMU scale/misalignment, timestamp jitter, dropped USB packets.
* Phone performance and how many frames get dropped there; the TBB thread count to use on big.LITTLE.
* Sessions longer than 31 s, more than a handful of restarts per handle, several handles in one process.
* The "IMU does not cover the frame", "IMU gap > 50 ms" and "VIO thread stopped" restart paths are implemented but not exercised by the test; the image-gap, clock-restart and explicit reset paths are.
* The frame accounting assumes `optical_flow_skip_frames = 1`.
* Real-time scenarios depend on host load: on a busy machine frames get dropped and numbers move slightly. The final run had 0 drops.
