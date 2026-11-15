// libultra_hands: small additions on top of XR Gate's xr_mercury_runtime C API.
// SPDX-License-Identifier: BSL-1.0
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ULTRA_HANDS_EXPORT __attribute__((visibility("default")))

/*
 * Threading knobs. Process-global; read when xr_mercury_runtime_create() runs,
 * so call this BEFORE create. A value <= 0 keeps the current/default value.
 *
 *  worker_threads        Size of Mercury's own task pool (upstream default 4:
 *                        3 pool threads + the calling thread helps while it
 *                        waits). Used for the 2 detector runs and the up to 4
 *                        keypoint-model runs (2 hands x 2 views) of one frame.
 *                        Clamped to [2, 16].
 *  ort_intra_op_threads  Intra-op threads of EACH of the 6 ONNX Runtime
 *                        sessions (upstream default 1 = no ORT thread pools).
 *                        Clamped to [1, 8].
 *  ort_allow_spinning    0 = ORT pool threads do not busy-spin while idle
 *                        (default 0 here; only matters if intra-op > 1).
 *
 * If never called, the environment variables ULTRA_HANDS_WORKER_THREADS,
 * ULTRA_HANDS_ORT_INTRA_OP_THREADS and ULTRA_HANDS_ORT_ALLOW_SPINNING are
 * consulted (setenv() before create), else the defaults 4 / 1 / 0 apply.
 */
ULTRA_HANDS_EXPORT void
ultra_hands_set_threading(int worker_threads, int ort_intra_op_threads, int ort_allow_spinning);

/* Effective values the next create will use. Any pointer may be NULL. */
ULTRA_HANDS_EXPORT void
ultra_hands_get_threading(int *worker_threads, int *ort_intra_op_threads, int *ort_allow_spinning);

/*
 * xr_mercury_runtime_create() returns NULL on failure and XR Gate's API has no
 * way to read the reason (the context is already gone). This returns the
 * message of the most recent failed create on the calling thread ("" if none).
 * The pointer stays valid until the next failed create on the same thread.
 */
ULTRA_HANDS_EXPORT const char *
ultra_hands_last_create_error(void);

#define ULTRA_HANDS_JOINT26_COUNT 26u

/* One joint of the full 26-joint set. Same frame/units as xr_mercury_joint_f32. */
struct ultra_hands_joint_pose
{
	float px, py, pz;     /* metres */
	float qx, qy, qz, qw; /* unit quaternion (note: x,y,z,w order here) */
	uint32_t flags;       /* xrt_space_relation_flags: 1 ori valid, 2 pos valid,
	                         16 ori tracked, 32 pos tracked (= 51 when tracked) */
};

/*
 * XR Gate's frame struct carries 21 joints (no PALM, no finger metacarpals).
 * Mercury computes all 26 XR_EXT_hand_tracking joints; this returns them for
 * the frame most recently passed to xr_mercury_runtime_process_gray8() on
 * this context. out26[i] is indexed exactly like XrHandJointEXT (0 = PALM,
 * 1 = WRIST, 2..5 thumb, 6..10 index, 11..15 middle, 16..20 ring, 21..25
 * little). hand: 0 = left, 1 = right.
 * Returns 1 if that hand is active (out26 filled), 0 if not active (out26 set
 * to identity poses with flags 0), -1 on bad arguments. Call from the same
 * thread as process (no internal locking).
 */
ULTRA_HANDS_EXPORT int
ultra_hands_get_joints26(void *ctx, int hand, struct ultra_hands_joint_pose *out26);

/* Build identification string, e.g. "libultra_hands monado=6c98049... ". */
ULTRA_HANDS_EXPORT const char *
ultra_hands_version(void);

#ifdef __cplusplus
}
#endif
