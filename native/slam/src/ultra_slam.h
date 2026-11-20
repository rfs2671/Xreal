/* A saved map of the room for Ultra XR: RTAB-Map behind a small C API.
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The position estimator (native/vio, Basalt) stays what moves the head: it has no memory. This library is given a
 * stereo pair now and then, with the estimator's pose for it, on a thread of its own. Making a map, it keeps places
 * (corners with descriptors and their 3D positions from the two cameras) in a database file. Finding, it opens that
 * file and, when a pair is recognised, says where the estimator's world lies in the saved map's world. The caller
 * turns that into a correction of its own world; nothing here touches the head pose.
 */
#ifndef ULTRA_SLAM_H
#define ULTRA_SLAM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(__GNUC__)
#define USLAM_API __attribute__((visibility("default")))
#else
#define USLAM_API
#endif

typedef struct uslam uslam_t;

/* USLAM_EXTEND: the map at db is opened and added to. What the estimator's world is in the map's is said only once a
 * place of the map as it was has been seen again and the new places hang together with it; the file is written at
 * the end only if that happened (new places that were never tied to the old ones would be a second map with a frame
 * of its own in the same file). With no file there it is USLAM_MAP. */
enum { USLAM_MAP = 0, USLAM_FIND = 1, USLAM_EXTEND = 2 };

typedef struct {
	int64_t results;    /* pairs worked through since create or the last new epoch */
	int64_t found;      /* of those, recognised in the map (a loop closure or a localisation) */
	int64_t dropped;    /* pairs handed in while the worker was busy */
	int64_t last_t_ns;  /* time of the last pair worked through */
	int64_t found_t_ns; /* time of the last pair recognised */
	int has_corr;       /* corr is set: the newest place hangs together with the map's first place (its frame is the map's) */
	int linked;         /* EXTEND: the session was tied to the old map at some time */
	int added;          /* EXTEND: places were added in this session (it is written at the end if all of them are tied) */
	double corr[7];     /* the estimator's world in the map's world: p xyz, q xyzw */
	int places;         /* places in the map */
	int last_place;     /* the place the last pair was matched to, 0 for none */
	int inliers;        /* matched points that agreed on the last accepted match */
	double last_ms;     /* time the last pair took */
	int mode;
	int epoch;          /* counts uslam_new_epoch calls taken up by the worker */
} uslam_state;

/* rig: two lines, a camera each: "fx fy cx cy k1 k2 k3 k4 px py pz qx qy qz qw" (Basalt's kb4 model; the camera's
 * place in the motion sensor's frame). db: the map file; USLAM_MAP starts it afresh, USLAM_FIND opens it and leaves it
 * as it is. params: RTAB-Map parameters as "Key=value" lines replacing this library's defaults, or NULL.
 * Returns NULL on failure; uslam_last_error() says why. */
USLAM_API uslam_t *uslam_create(const char *rig, const char *db, int mode, const char *params);

/* One pair (w x h bytes each, as the cameras give them, upright) and the motion sensor's pose in the estimator's world
 * at that moment (p xyz, q xyzw; the world's z is up). Copies and returns at once: 1 taken, 0 the worker is still busy
 * with the pair before (this one is dropped). */
USLAM_API int uslam_feed(uslam_t *h, int64_t t_ns, const uint8_t *left, const uint8_t *right, int w, int hgt, const double pose[7]);

/* The estimator began a new world (it was restarted): what was found so far says nothing about it. */
USLAM_API void uslam_new_epoch(uslam_t *h);

USLAM_API void uslam_get_state(uslam_t *h, uslam_state *out);

/* Blocks until the worker has nothing left to do (tests). */
USLAM_API void uslam_wait_idle(uslam_t *h);

/* Stops the worker. USLAM_MAP with save != 0 writes the map file; USLAM_FIND never changes it. */
USLAM_API void uslam_destroy(uslam_t *h, int save);

USLAM_API const char *uslam_version(void);
USLAM_API const char *uslam_last_error(void);

#ifdef __cplusplus
}
#endif
#endif
