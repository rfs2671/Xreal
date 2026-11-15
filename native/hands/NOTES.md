# libultra_hands: Monado "Mercury" hand tracking for Android arm64

Mercury (Monado's optical hand tracker) + XR Gate's patches and C API, built as
one shared library for the XREAL Air 2 Ultra app. Everything below was read
from the code/configs named; items I could not check are listed in section 10.

## 1. What is in this directory

| Path | Content |
| --- | --- |
| `android/arm64-v8a/libultra_hands.so` | 486,288 bytes, stripped, sha256 `a2934d1bc69c1dbe2a14b2210e54ce2d90c1a57a3f7ad7cb73dbfaa601e1c8e1` |
| `include/xr_mercury_runtime_c_api.h` | XR Gate's C API, unmodified (ABI version 1) |
| `include/ultra_hands_ext.h` | my small additions: thread knobs, create error text, 26-joint accessor, version |
| `models/*.onnx`, `SHA256SUMS.txt`, `SOURCE_INFO.txt` | the two real Mercury models (7,299,956 and 3,966,382 bytes) |
| `src/` | `CMakeLists.txt`, `ultra_hands.map`, `ext/`, `config/`, `patches/0001-ultra-hands.patch`, `prepare_sources.sh`, `build_android.sh`, `build_host.sh` |
| `test/` | `ultra_hands_smoke.c` (C API only), `make_hand_stereo.py`, captured `smoke_output*.txt` |
| `LICENSES/` | license texts + `README.md` table of what is compiled in |

Pinned inputs: Monado `6c9804934324ac5a3e68d64938579dbbeb4d75b3`; XR Gate
`56010adf410fd66f47ea9534e88052304ff0b011` (overlay + 3 patches, applied
exactly as `install_mercury.sh` does); Eigen 3.4.0
`3147391d946bb4b6c68edd901f2add6ac1f31f8c` (headers); models repo
`37a8a81bc8f433ac6cbdf2471909d2bac74beca1`.

Rebuild: `src/build_android.sh` and `src/build_host.sh` (both default to
`WORK=/home/user/build/hands`, `-j2`, write into this directory). Both were run
from clean clones (`WORK=/home/user/build/hands/repro`) and produced the
delivered files.

## 2. The Android library

Built with NDK 27.2.12479018, `arm64-v8a`, `android-29`, `c++_shared`,
Release `-O3`, C++20/C11, `-fvisibility=hidden`, and linked with
`-Wl,--exclude-libs,ALL -Wl,-Bsymbolic -Wl,-z,max-page-size=16384
-Wl,--no-undefined -Wl,--gc-sections` plus a version script (`ultra_hands.map`)
that makes everything except the C API local.

`llvm-readelf -d`:

```
NEEDED libopencv_java4.so
NEEDED libonnxruntime.so
NEEDED liblog.so
NEEDED libm.so
NEEDED libc++_shared.so
NEEDED libdl.so
NEEDED libc.so
SONAME libultra_hands.so
FLAGS  SYMBOLIC BIND_NOW
```

All three LOAD segments have alignment 0x4000 (16 KB). Exported dynamic
symbols are exactly these nine; no Monado/cJSON symbol is exported, and the
only imports are libc/libm/liblog/libdl, libc++ (`std::`), OpenCV (`cv::`,
27 symbols) and `OrtGetApiBase`:

```
xr_mercury_runtime_create   xr_mercury_runtime_destroy
xr_mercury_runtime_process_gray8   xr_mercury_runtime_last_error
ultra_hands_set_threading   ultra_hands_get_threading
ultra_hands_last_create_error   ultra_hands_get_joints26   ultra_hands_version
```

I did not use Monado's own CMake. `src/CMakeLists.txt` lists the 29 sources
needed: Mercury (`hg_sync`, `hg_model`, `hg_image_distorter`, `kine_lm/*`,
XR Gate's `xr_mercury_runtime_c_api.cpp`), `aux/onnx/onnx_wrapper.cpp`,
`aux/tracking/t_data_utils.c`, six `aux/math` files, `aux/os/os_time.cpp`,
eleven `aux/util` files, cJSON, and `ext/ultra_hands_ext.cpp`. No compositor,
drivers, IPC or OpenXR state tracker. The generated `xrt_config_*.h` headers
are replaced by five tiny hand-written ones in `src/config/xrt/`.

### Changes on top of XR Gate (`src/patches/0001-ultra-hands.patch`)

1. Thread counts configurable (section 5).
2. `xr_mercury_runtime_create()` failure reason kept
   (`ultra_hands_last_create_error`).
3. Full 26-joint output (`ultra_hands_get_joints26`).
4. **Crash fix:** at this Monado commit a failed create (for example a missing
   `.onnx` file) segfaults in `HandTracking::~HandTracking()` because `pool`,
   `group`, `kinematic_hands[]` are uninitialised and `release_onnx_state()`
   dereferences a null session. Reproduced on host, fixed (members initialised,
   null check). A bad models path now returns NULL.
5. **Leak fix:** upstream never drops its own reference to the worker thread
   pool, so every destroy leaked the pool and its threads. Fixed; verified on
   host that the process returns to 1 thread after each of 12 create/destroy
   cycles (5 threads while a context exists).
6. On Android, Monado reads its `DEBUG_GET_ONCE_*` options only from system
   properties `debug.xrt.<NAME>`, which an app cannot set. Added a `getenv()`
   fallback so `setenv()` before create works (section 8).

## 3. C API

All structs in `xr_mercury_runtime_c_api.h` are `#pragma pack(push, 1)`.
Sizes (checked by the smoke test on x86_64; arm64 has the same 8-byte
pointers): `xr_mercury_create_info` 68, `xr_mercury_joint_f32` 44,
`xr_mercury_hand_side_f32` 1072, `xr_mercury_frame_f32` 2200 bytes.

```c
void *xr_mercury_runtime_create(const struct xr_mercury_create_info *info);
void  xr_mercury_runtime_destroy(void *ctx);              /* NULL is fine */
int   xr_mercury_runtime_process_gray8(void *ctx,
          const uint8_t *cam0, uint32_t w0, uint32_t h0, uint32_t stride0,
          const uint8_t *cam1, uint32_t w1, uint32_t h1, uint32_t stride1,
          uint64_t source_timestamp_ns, uint64_t sequence,
          struct xr_mercury_frame_f32 *out_frame);
const char *xr_mercury_runtime_last_error(void *ctx);
```

`xr_mercury_create_info`:

| Field | Meaning | XR Gate value for this headset |
| --- | --- | --- |
| `abi_version` | must be `XR_MERCURY_RUNTIME_ABI_VERSION` (1) | 1 |
| `size_bytes` | must be `sizeof(struct xr_mercury_create_info)` (68) | 68 |
| `models_dir` | directory containing the two `.onnx` files | |
| `calib_json` | path of the calibration JSON file (section 7) | |
| `flags` | unused | 0 |
| `orientation0`, `orientation1` | 0/90/180/270: how each camera image is rotated relative to upright. Only used to rotate the image fed to the detector | 0, 0 |
| `swap_cameras` | non-zero: `cam1` is Mercury's left view | 0 |
| `boundary_circle` + `boundaryN_center_x/y`, `boundaryN_radius` | optional circular valid-image area (normalised; radius relative to image width) | 0 (off); CLI defaults 0.5, 0.5, 0.55 |

`create` returns NULL on failure. Reasons: bad ABI/size, empty paths,
calibration file missing/unparsable/missing fields, bad orientation, or Mercury
init failure (model missing or rejected by ONNX Runtime; the ORT detail is
logged, the returned text is `t_hand_tracking_sync_mercury_create failed`).
Text: `ultra_hands_last_create_error()`.

`process_gray8` return codes: `0` ok, `-1` `ctx` or `out_frame` NULL, `-2`
exception (NULL image, zero size, `stride < width`; text from
`xr_mercury_runtime_last_error(ctx)`). The call is synchronous: detection,
keypoints and the optimiser all finish before it returns. Images are copied, so
the buffers can be reused after the call. Both images must have the same size;
Mercury only `assert()`s this and asserts are compiled out in Release, so the
app must guarantee it. One context must be used from one thread at a time.

Output `xr_mercury_frame_f32`:

| Field | Value |
| --- | --- |
| `version` | 2 |
| `size_bytes` | 2200 |
| `sequence`, `source_timestamp_ns` | echoed from the call |
| `timestamp_ns` | Mercury's frame timestamp = `source_timestamp_ns` (XR Gate's loader then overwrites it with "now") |
| `reset_counter` | 0 (XR Gate's backend fills it) |
| `tracking_status` | 0 no hands, 1 tracking |
| `flags` | bit0 left valid, bit1 right valid, bit2 joints valid |
| `confidence` | 1.0 if any hand, else 0.0 (not a real confidence) |
| `hand_count` | 0..2 |
| `left`, `right` | `xr_mercury_hand_side_f32` |

`xr_mercury_hand_side_f32`: `handedness` 1 = left, 2 = right; `status` 0/1;
`flags` = 1 (pose valid) | 8 (joints valid) when tracked; `confidence` 1.0 when
tracked; `palm_*` = Mercury PALM joint; `wrist_*` = WRIST joint;
`controller_*` = copy of the palm pose; `joint_count` 21; `joints[21]`.
**Not filled by this library (always 0):** `vx..wz`, `pinch_strength`,
`grab_strength`, `pinch_active`, `grab_active`, `reserved0`, joint `radius_m`.
XR Gate computes velocities and gestures later, outside Mercury
(`mercury_runtime_loader.hpp`, runtime adapter). Joint `confidence` is 1.0 or
0.0, joint `flags` are Monado relation flags (51 = orientation+position valid
and tracked). When a hand is not tracked all its poses are identity/zero.

Quaternion field order in these structs is `qw, qx, qy, qz`.

Extension (`ultra_hands_ext.h`):

```c
void ultra_hands_set_threading(int worker_threads, int ort_intra_op_threads, int ort_allow_spinning);
void ultra_hands_get_threading(int *worker_threads, int *ort_intra_op_threads, int *ort_allow_spinning);
const char *ultra_hands_last_create_error(void);   /* thread-local text */
int  ultra_hands_get_joints26(void *ctx, int hand /*0 left, 1 right*/,
                              struct ultra_hands_joint_pose out26[26]);  /* 1 active, 0 inactive, -1 bad args */
const char *ultra_hands_version(void);
```

`ultra_hands_joint_pose` is `{float px,py,pz; float qx,qy,qz,qw; uint32_t flags;}`
(note `x,y,z,w` order here, natural alignment, 32 bytes).

## 4. Joints

`joints[i]` (21 per hand):

| i | Joint | XrHandJointEXT index |
| --- | --- | --- |
| 0 | wrist | 1 |
| 1..4 | thumb metacarpal, proximal, distal, tip | 2..5 |
| 5..8 | index proximal, intermediate, distal, tip | 7..10 |
| 9..12 | middle proximal, intermediate, distal, tip | 12..15 |
| 13..16 | ring proximal, intermediate, distal, tip | 17..20 |
| 17..20 | little proximal, intermediate, distal, tip | 22..25 |

Missing from the 21-joint array compared with `XR_EXT_hand_tracking`'s 26:
PALM (0), which is available as `palm_*`, and the four finger metacarpals
INDEX/MIDDLE/RING/LITTLE_METACARPAL (6, 11, 16, 21), which XR Gate drops.
Mercury does compute them: `ultra_hands_get_joints26()` returns all 26 in
exact `XrHandJointEXT` order (Monado's `xrt_hand_joint` enum is identical),
for the frame last processed on that context. Tip joints reuse the distal
joint's orientation. Joint radii are not produced (0).

## 5. Coordinate frame, units, timestamps

- **Origin and axes:** the frame of Mercury's left view, which is `cam0` when
  `swap_cameras = 0`. For this headset `cam0` is the user's **left** camera
  (in the calibration, cam1 sits 0.136 m along +x of cam0). Axes follow the
  OpenXR/OpenGL camera convention relative to the *upright image fed in*:
  **+X right, +Y up, -Z forward** (Mercury unprojects with
  `t_camera_models_unproject_and_flip`, i.e. OpenCV camera coordinates with Y
  and Z negated). Right-handed. Positions are **metres**. Orientations are unit
  quaternions in the same frame.
- Checked empirically on host: a hand pasted at image centre with the disparity
  of a point 0.20 m away came out at palm = (+0.072, -0.070, -0.216) m:
  negative Z, +X by about half the 0.136 m baseline (the hand was centred
  between the cameras), and negative Y for a hand drawn below the principal
  point. At 0.163 m simulated it gave Z = -0.160.
- This is a camera frame, not a head/display frame. The two cameras are toed
  about 10 degrees relative to each other (from the calibration), so cam0's -Z
  is not exactly the head's forward axis. To get head/IMU coordinates use
  `T_imu_cam[0]` from the calibration (camera in OpenCV convention to IMU), after
  converting the output back to OpenCV convention (negate y and z). XR Gate's
  own runtime adapter instead applies a hand-tuned HMD-relative offset
  (`xr_21_joint_hand_viewer_verified.json`: `offset_m.x = -0.07`,
  `rotate_with_hmd_orientation`).
- **Timestamps:** `source_timestamp_ns` is copied to both frames and becomes
  Mercury's frame time. Any monotonic nanosecond clock works; Mercury only uses
  differences (ROI prediction between frames, optional frame-rate-based
  smoothing). They must be **strictly increasing**: the pose-prediction step
  divides by the previous frame interval. XR Gate passes one timestamp for the
  stereo pair (steady-clock receive time, or the device footer time in the
  `device_time` profile) and only pairs frames within
  `--max-stereo-delta-ms`. `sequence` is only echoed.

## 6. Image input for this headset

Confirmed from `capture_service_cpp` (`xreal_camera_source.cpp`,
`image_transform.cpp`) and
`devices/xreal_ultra/configs/capture_service/xreal_air2ultra_linux_unified_480_shm.yaml`:

- Each raw eye image is 640x480 gray8. The eye is identified by header byte
  `0x3B` of the USB frame (0 = left eye, non-zero = right eye).
- `camera0` = **left eye rotated 90 degrees counter-clockwise** to 480x640.
- `camera1` = **right eye rotated 90 degrees clockwise** to 480x640. The config
  says `rotate: ccw90, flip: xy`; `transform_image()` collapses that to a
  single `cv::ROTATE_90_CLOCKWISE`.
- Both go to Mercury as `cam0`/`cam1`, 480 wide, 640 high, stride 480, with
  `orientation0 = orientation1 = 0`, `swap_cameras = 0`, no boundary circle
  (the backend's defaults; `start_hand_tracking.sh` passes none of those flags).

Pixel mapping (`r`,`c` = output row/column, input indexed `[row][col]`,
input 480 rows x 640 columns):

```
cam0 (ccw90): out[r][c] = in_left [c][639 - r]      r in 0..639, c in 0..479
cam1 (cw90):  out[r][c] = in_right[479 - c][r]
```

Do the rotation in the app (OpenCV `cv::rotate` or a direct loop) and pass
upright images with orientation 0; that is the only path XR Gate exercises.
Passing the raw 640x480 frames with `orientation = 90/270` is not equivalent:
Mercury's orientation setting only rotates the detector input, while the
keypoint stage and the calibration assume the image as given.

The `metadata.rig.convention` line in the YAML ("camera0=cw90, camera1=cw90")
is informational and contradicts the `transform:` block; the code uses the
`transform:` block.

## 7. Calibration JSON

Parsed by `load_stereo_calibration_json()` in `xr_mercury_runtime_c_api.cpp`.
It is a Basalt-style file; only three members of `value0` are read:

```json
{ "value0": {
    "T_imu_cam":  [ {"px":..,"py":..,"pz":..,"qx":..,"qy":..,"qz":..,"qw":..}, {...} ],
    "intrinsics": [ {"camera_type":"kb4","intrinsics":{"fx":..,"fy":..,"cx":..,"cy":..,"k1":..,"k2":..,"k3":..,"k4":..}}, {...} ],
    "resolution": [ [480, 640], [480, 640] ]
} }
```

- Index 0 = `cam0`, index 1 = `cam1`. All listed fields are required numbers.
- Intrinsics are always treated as Kannala-Brandt 4 fisheye (`camera_type` is
  not read), in pixels of the image actually passed in (480x640 upright).
- `T_imu_cam[i]` is the pose of camera i in the IMU frame (metres, quaternion).
  Only the relative pose cam0 to cam1 is used; the IMU frame itself is not.
- `resolution` is `[width, height]`. If frames of another size are passed,
  Mercury rescales the intrinsics (aspect ratio must match).
- Everything else in the file (`cam_time_offset_ns`, vignette, noise) is ignored.

The two files shipped by XR Gate are both for **480x640** images despite one
being named `mercury_calib_640x480.json`:

| File | fx/fy/cx/cy cam0 | baseline | used by |
| --- | --- | --- | --- |
| `unified_480_ccw90/mercury_calib_unified_480_ccw90.json` | 238.88 / 238.44 / 239.66 / 321.16 | 0.1364 m | profile `xreal_air2ultra_unified_480.env`, `start_hand_tracking.sh` default |
| `unified_480_ccw90_device_time/mercury_calib_640x480.json` | 231.21 / 230.13 / 241.16 / 323.14 | 0.1366 m | `xreal_ultra_new_tracking.env` (device-timestamp profile) |

Both belong to one physical unit, serial `ZBBM5DZFMP`. They are a reasonable
starting point for another Air 2 Ultra, not a calibration of it.

## 8. Models, runtime file access, settings

- `models_dir` is a plain directory path (max 1023 chars). Mercury opens
  exactly `<models_dir>/grayscale_detection_160x160.onnx` (twice, one session
  per view) and `<models_dir>/grayscale_keypoint_jan18.onnx` (four sessions:
  2 views x 2 hands) through ONNX Runtime's file loader. So the app must copy
  both from APK assets to real files. The calibration JSON must also be a real
  file. Nothing else is read at runtime. Files are only *written* when one of
  the debug variables below is set.
- Create took 76 to 126 ms on the host.

XR Gate's defaults for this headset (`start_hand_tracking.sh`, profile env,
backend CLI defaults):

| Setting | Value |
| --- | --- |
| calibration | `mercury_calib_unified_480_ccw90.json` |
| orientation0 / orientation1 | 0 / 0 |
| swap_cameras | off |
| boundary circle | off |
| `MERCURY_MIN_DETECTION_CONFIDENCE` | 0.3 (script default; also Mercury's built-in default) |
| detector mirror / slot swap / fusion (`MERCURY_XR_DETECTOR_*`) | off ("plain Mercury path on corrected camera input") |
| `MERCURY_XR_QUALITY_FILTER` | **0** (script exports 0) |
| ONNX Runtime | 1.18.1 on Linux x64 (this build uses 1.22.0) |

Options (set with `setenv()` before `create`; with patch item 6 this also works
on Android, otherwise `adb shell setprop debug.xrt.<NAME>` for the first three):

| Variable | Default | Effect |
| --- | --- | --- |
| `MERCURY_LOG` | warn | Mercury log level (trace/debug/info/warn/error); logcat on Android |
| `MERCURY_MIN_DETECTION_CONFIDENCE` | 0.3 | detector acceptance threshold |
| `MERCURY_optimize_hand_size` | true | estimate hand size during the first ~100 tracked frames |
| `MERCURY_XR_QUALITY_FILTER` | off | see below |
| `MERCURY_XR_QUALITY_MIN_MEAN_XY` / `_WEAK_MEAN_XY` / `_MAX_WEAK_REPROJECTION` | 0.25 / 0.35 / 0.5 | filter thresholds |
| `MERCURY_XR_TRACKING_DEBUG_CSV`, `MERCURY_XR_DETECTOR_DEBUG_CSV`, `MERCURY_XR_FUSION_*`, `MERCURY_XR_DEBUG_DUMP_*` | unset | debug file output; leave unset in the app |
| `ULTRA_HANDS_WORKER_THREADS`, `ULTRA_HANDS_ORT_INTRA_OP_THREADS`, `ULTRA_HANDS_ORT_ALLOW_SPINNING` | 4 / 1 / 0 | same as `ultra_hands_set_threading()` |

### Quality filter

`xr_mercury_quality_filter.hpp`, per hand and per frame:

1. Before the optimiser: reject the hand if, in any camera that sees it, the
   mean XY keypoint confidence over the 21 keypoints is not finite or below
   `min_mean_xy` (0.25), or if no camera is active.
2. After the optimiser: reject if the reprojection error is not finite, or if
   any active camera's mean confidence is below `weak_mean_xy` (0.35) **and**
   the reprojection error exceeds `max_weak_reprojection_error` (0.5).

A rejected hand becomes inactive for that frame and its tracking history is
cleared, so it has to be re-detected and pass the 10-frame warm-up again. No
pose is substituted.

XR Gate's `patches/monado/README.md` says the filter is "enabled by default"
when the variable is unset. **The code at the pinned commit does the opposite:**
`Config::enabled` defaults to `false` and is only set true by
`MERCURY_XR_QUALITY_FILTER=1|true|yes`, and `start_hand_tracking.sh` exports
`0`. So XR Gate currently runs with the filter off and this library does too
unless you set the variable to `1`.

### Behaviour worth knowing

- A hand is reported only after it has been tracked for 10 consecutive frames
  (`num_frames_before_display = 10`; the code comment says 4). In the smoke
  test the first reported hand was the 10th frame of a static image, i.e.
  about a third of a second at 30 pairs/s after it becomes visible or after
  any rejection.
- The detector runs on every frame while fewer than two hands are tracked,
  alternating between the two views (both views in parallel during hand-size
  refinement).
- Mercury logs a warning per frame for a completely uniform image ("zero
  standard deviation"). Harmless, but it reaches logcat.

## 9. Threading

What the code does (unchanged defaults):

- Six ONNX Runtime sessions, each with its own `OrtEnv`, graph optimisation
  `ORT_ENABLE_ALL`, CPU execution provider, **intra-op threads = 1**. With one
  thread ORT creates no thread pool of its own.
- Mercury's own pool: `u_worker_thread_pool_create(3, 4)`, i.e. 4 pool threads
  of which 3 run concurrently plus the calling thread, which helps while it
  waits. Per frame it runs up to 2 detector inferences and up to 4 keypoint
  inferences (2 hands x 2 views) as parallel tasks, then the Levenberg-Marquardt
  fit per hand on the calling thread.

The XR Gate API has no thread knob, so I added
`ultra_hands_set_threading(worker_threads, ort_intra_op_threads, allow_spinning)`
(call before create; process-global). I also set inter-op threads to 1 and, by
default, disable ORT idle spinning (only relevant if intra-op > 1).

Suggestion for the phone, not measured there: keep the defaults (4 workers,
intra-op 1). That gives at most 4 busy threads for a few milliseconds per
frame and matches the maximum parallelism of the workload. If the tracker
competes with SLAM or rendering for the big cores, try `worker_threads = 2`
or 3. Raising intra-op above 1 multiplies threads by six sessions; on the
host it did not give a consistent gain. Call `process_gray8` from one dedicated
thread, never from the camera callback or render thread, and drop frames
rather than queue them if it falls behind.

## 10. Verified and not verified

Verified:

- Android arm64 library builds from clean clones with the given NDK, OpenCV
  4.12.0 AAR and ONNX Runtime 1.22.0 AAR; links with `--no-undefined`;
  dependencies, exports, 16 KB alignment as listed in section 2.
- Models are the real upstream files: sha256 equals the LFS pointer oids of the
  freedesktop.org repository, and ONNX Runtime 1.22.0 loads and runs them.
- Same sources built for Linux x86_64 (GCC 13, OpenCV 4.6.0 from Ubuntu,
  ONNX Runtime 1.22.0 from the PyPI wheel) and exercised through the C API only
  (`test/smoke_output.txt`, exit code 0, 0 failed checks):
  - create with XR Gate's calibration and the real models; bad models dir and
    bad calibration path return NULL with a message;
  - 120 frames each of black, grey, white, per-pixel noise and 16x16 block
    noise, in three thread configurations: no crash, no errors, **0 frames
    reporting a hand**;
  - padded stride, NULL image (returns -2), 5 create/destroy cycles;
  - synthetic stereo pairs made from one photographed hand
    (`test/make_hand_stereo.py`): left hand and mirrored (right) hand at a
    simulated 0.20 m and left hand at 0.163 m were detected, classified with the
    correct handedness and tracked in 111 of 120 frames, with depth matching
    the simulated distance; 21-joint and 26-joint outputs agree.
- Also run with `MERCURY_XR_QUALITY_FILTER=1` and with the `device_time`
  calibration (`test/smoke_output_quality_filter.txt`,
  `test/smoke_output_device_time_calib.txt`): pass, same detections.

Host timing (4-core Xeon 2.1 GHz shared with other compile jobs, so noisy):

| Case | median ms/frame | p95 |
| --- | --- | --- |
| no hand (blank/noise/undetected image), default threads | 1.9 to 5.3 | up to 16 |
| one hand tracked, 4 workers / intra-op 1 | 13.9 to 17.1 | 31 to 35 |
| one hand tracked, 2 workers / intra-op 1 | 11.3 to 19.3 | 20 to 40 |
| one hand tracked, 4 workers / intra-op 2 | 10.7 to 12.0 | 15 to 18 |

An earlier run on the same machine (before a rebuild that did not touch
host code) gave 10.5 to 12.6 ms median with one hand at default threads, so
the spread between rows is mostly load from the other jobs. Two hands were not timed.

Not verified:

- **Nothing was run on Android.** The `.so` was never loaded on a device or
  emulator: no check of load order with the other Monado `.so`, of ONNX
  Runtime's Android build with these models, or of logcat output.
- **Performance on the phone** (per-frame time, whether 30 pairs/s is met,
  thermal behaviour, thread-count choice).
- **Accuracy on this headset.** No real XREAL frames were available. The
  synthetic test is a flat studio photo pasted into both views; it ignores
  fisheye distortion. The same photo at realistic sizes for 0.30 m and 0.40 m
  was **not** detected (detector confidence below 0.02), which says the pasted
  photo is far from the training data, not how the tracker behaves on real
  infrared frames; real-world range is unknown from this work.
- That the app's raw eye frames have the same orientation and left/right
  assignment as XR Gate's decoder output (section 6 describes XR Gate's side
  only), and that XR Gate's calibration fits a different physical unit.
- Two hands at once, hand-over-hand, long runs, memory growth over hours.
- The NNAPI/XNNPACK execution providers (the code only uses the CPU provider).

Known leftovers in XR Gate's wrapper that I did not change: if
`process_gray8` throws after creating the first frame (bad second image), the
first frame object is leaked; and `xr_mercury_runtime_last_error()` is never
cleared after a later success.

## 11. Minimal use

```c
#include "xr_mercury_runtime_c_api.h"
#include "ultra_hands_ext.h"

ultra_hands_set_threading(4, 1, 0);                  /* optional, before create */
struct xr_mercury_create_info ci = {0};
ci.abi_version = XR_MERCURY_RUNTIME_ABI_VERSION;
ci.size_bytes  = sizeof(ci);
ci.models_dir  = "/data/user/0/<pkg>/files/mercury"; /* holds the two .onnx */
ci.calib_json  = "/data/user/0/<pkg>/files/mercury_calib.json";
void *ht = xr_mercury_runtime_create(&ci);           /* orientation 0/0, no swap */
if (!ht) log(ultra_hands_last_create_error());

/* per stereo pair, 480x640 upright (section 6), one thread: */
struct xr_mercury_frame_f32 f;
if (xr_mercury_runtime_process_gray8(ht, cam0, 480, 640, 480, cam1, 480, 640, 480,
                                     t_ns, seq, &f) == 0 && f.left.status == 1) {
    struct ultra_hands_joint_pose j[ULTRA_HANDS_JOINT26_COUNT];
    ultra_hands_get_joints26(ht, 0, j);              /* XrHandJointEXT order, cam0 frame */
}
xr_mercury_runtime_destroy(ht);
```

Load order in the app: `libc++_shared.so`, `libopencv_java4.so`,
`libonnxruntime.so`, then `libultra_hands.so` (or let the linker resolve
`DT_NEEDED` from the APK's lib directory).
