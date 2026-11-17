/*
 * ultra_vio - small C API around the Basalt visual-inertial odometry estimator
 * (upstream https://github.com/VladyslavUsenko/basalt @ 0f3b2b52), wired the
 * same way XR Gate's capture_basalt_backend wires it for the XREAL Air 2 Ultra.
 *
 * See NOTES.md for the exact input conventions.
 */
#ifndef ULTRA_VIO_H_
#define ULTRA_VIO_H_

#include <stdint.h>

#if defined(_WIN32)
#define UVIO_API
#else
#define UVIO_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct uvio uvio_t;

/* calib_json / config_json: full text of a Basalt calibration and VIO config
 * JSON. Returns NULL and fills err on failure. */
UVIO_API uvio_t* uvio_create(const char* calib_json, const char* config_json,
                             char* err, int err_len);
UVIO_API void uvio_destroy(uvio_t*);

/* IMU sample in the calibration's IMU frame. t_ns must be on the same clock as
 * images. gyro in rad/s, accel (specific force, reads +g when at rest) in
 * m/s^2. Samples whose t_ns is not strictly increasing are dropped; a jump
 * back by more than 1 s (on either stream) is treated as a restarted device
 * clock: queued input is flushed and the estimator restarts. */
UVIO_API void uvio_push_imu(uvio_t*, int64_t t_ns, const float gyro_rad_s[3],
                            const float accel_m_s2[3]);

/* One synchronized stereo pair, 8-bit gray, row-major, already in the
 * orientation the calibration expects (width/height must equal the
 * calibration's "resolution" entry, 480x640 for the XR Gate profile).
 * left = calibration camera 0, right = calibration camera 1.
 * t_ns is the camera timestamp; the calibration's cam_time_offset_ns is added
 * internally (t_imu = t_camera + offset), do NOT pre-apply it.
 * Copies the data. Never blocks for long: if the estimator is behind, the
 * oldest queued frame is dropped and counted in frames_dropped. */
UVIO_API void uvio_push_stereo(uvio_t*, int64_t t_ns, const uint8_t* left,
                               const uint8_t* right, int width, int height);

typedef struct {
  int64_t t_ns;        /* timestamp of the state (IMU clock = camera t_ns +
                          cam_time_offset_ns) */
  double p[3];         /* position of IMU/body in world, metres */
  double q[4];         /* orientation body->world, x y z w */
  double v[3];         /* velocity in world */
  double bg[3], ba[3]; /* gyro / accel bias */
  int32_t valid;       /* 0 until the estimator has produced a state */
  int32_t num_features; /* tracked points in the last frame (cam0), -1 if
                           unknown */
  int64_t frames_in, frames_dropped, states_out; /* frames_dropped counts
                          every pushed pair that did not reach the estimator:
                          backlog drops, bad size/timestamp, not covered by
                          IMU, startup gate. states_out + frames_dropped ==
                          frames_in once the queues are empty. */
  double last_process_ms; /* wall time the estimator took for the last frame
                             (hand-over to optical flow -> state out) */
  /* ---- additions (appended, see NOTES.md) ---- */
  int64_t reset_count;  /* number of estimator restarts so far (uvio_reset +
                           automatic ones). The world frame changes whenever
                           this increments. */
  int64_t auto_resets;  /* subset of reset_count triggered internally (image
                           gap, IMU discontinuity, estimator stopped) */
  int64_t imu_in;       /* IMU samples accepted */
  int64_t imu_dropped;  /* IMU samples rejected (non-increasing timestamp or
                           buffer overflow) */
  int32_t stereo_matches; /* points of the last frame also matched in cam1, -1 if unknown */
  int32_t landmarks;      /* 3D landmarks the estimator held at that frame, -1 if unknown */
} uvio_state;

/* Latest state (non-blocking). Returns 1 if a state is available. The counter
 * fields are always filled, even when 0 is returned. */
UVIO_API int uvio_get_state(uvio_t*, uvio_state* out);

/* Tear down and restart the estimator in place (used for tracking recovery).
 * Safe to call from any thread. Returns once the old estimator is gone (or
 * after 5 s at most); the new one initialises on the next stereo pair that is
 * covered by IMU data. Position restarts at the origin, yaw at 0. */
UVIO_API void uvio_reset(uvio_t*);

UVIO_API const char* uvio_version(void); /* e.g. "ultra_vio 1.0 (basalt 0f3b2b5 ...)" */

/* ---- additions ---- */

/* Tuning knobs. Returns 1 if the key is known and the value accepted, else 0.
 * Keys (defaults are XR Gate's values):
 *   "max_frame_gap_s"   1.0   gap between consecutive stereo timestamps above
 *                             which the estimator is restarted (XR Gate:
 *                             "capture stereo stream stalled")
 *   "max_imu_gap_s"     0.05  IMU timestamp gap that counts as a discontinuity
 *                             and restarts the estimator
 *   "imu_wait_s"        0.05  wall time to wait for an IMU sample newer than a
 *                             frame before giving up on that frame
 *   "max_pending"       2     stereo pairs queued in front of the estimator
 *                             before the oldest is dropped (1..8)
 *   "startup_imu_gate"  0     1 = hold every (re)start of the estimator back
 *                             until the IMU has looked stationary for 30
 *                             consecutive frames (XR Gate's optional startup
 *                             IMU gate and thresholds). Frames consumed while
 *                             waiting are counted in frames_dropped.
 *   "gravity_magnitude" 9.80665  m/s^2; applied at the next (re)start
 *   "num_threads"       0     TBB max parallelism, 0 = TBB default (process
 *                             wide setting)
 */
UVIO_API int uvio_set_option(uvio_t*, const char* key, double value);

#ifdef __cplusplus
}
#endif

#endif /* ULTRA_VIO_H_ */
