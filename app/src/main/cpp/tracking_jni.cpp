// JNI glue between dev.ultraxr.tracking.Native and the two tracking libraries:
// libultra_vio.so (Basalt) and libultra_hands.so (Monado's Mercury via XR Gate's C API).
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

#include "ultra_hands_ext.h"
#include "ultra_slam.h"
#include "ultra_vio.h"
#include "xr_mercury_runtime_c_api.h"

namespace {
std::mutex g_err_mutex;
std::string g_last_error;

void set_error(const std::string &e) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_last_error = e;
}

std::string to_string(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
}  // namespace

extern "C" {

// ---------------------------------------------------------------- 6DoF

JNIEXPORT jlong JNICALL Java_dev_ultraxr_tracking_Native_vioCreate(JNIEnv *env, jclass, jstring calib, jstring config) {
    const std::string c = to_string(env, calib), k = to_string(env, config);
    char err[512] = {0};
    uvio_t *v = uvio_create(c.c_str(), k.c_str(), err, sizeof(err));
    if (!v) set_error(err[0] ? err : "uvio_create failed");
    return reinterpret_cast<jlong>(v);
}

JNIEXPORT void JNICALL Java_dev_ultraxr_tracking_Native_vioDestroy(JNIEnv *, jclass, jlong h) {
    if (h) uvio_destroy(reinterpret_cast<uvio_t *>(h));
}

JNIEXPORT void JNICALL Java_dev_ultraxr_tracking_Native_vioPushImu(JNIEnv *, jclass, jlong h, jlong t, jfloat gx, jfloat gy, jfloat gz,
                                                                   jfloat ax, jfloat ay, jfloat az) {
    if (!h) return;
    const float g[3] = {gx, gy, gz}, a[3] = {ax, ay, az};
    uvio_push_imu(reinterpret_cast<uvio_t *>(h), t, g, a);
}

JNIEXPORT void JNICALL Java_dev_ultraxr_tracking_Native_vioPushStereo(JNIEnv *env, jclass, jlong h, jlong t, jbyteArray left,
                                                                      jbyteArray right, jint w, jint hgt) {
    if (!h || !left || !right) return;
    if (env->GetArrayLength(left) < w * hgt || env->GetArrayLength(right) < w * hgt) return;
    jbyte *l = env->GetByteArrayElements(left, nullptr);
    jbyte *r = env->GetByteArrayElements(right, nullptr);
    if (l && r) uvio_push_stereo(reinterpret_cast<uvio_t *>(h), t, reinterpret_cast<const uint8_t *>(l), reinterpret_cast<const uint8_t *>(r), w, hgt);
    if (l) env->ReleaseByteArrayElements(left, l, JNI_ABORT);
    if (r) env->ReleaseByteArrayElements(right, r, JNI_ABORT);
}

JNIEXPORT jlong JNICALL Java_dev_ultraxr_tracking_Native_vioGetState(JNIEnv *env, jclass, jlong h, jdoubleArray out) {
    if (!h || !out || env->GetArrayLength(out) < 22) return 0;
    uvio_state s;
    std::memset(&s, 0, sizeof(s));
    const int ok = uvio_get_state(reinterpret_cast<uvio_t *>(h), &s);
    const jdouble d[22] = {static_cast<double>(s.t_ns), s.p[0], s.p[1], s.p[2], s.q[0], s.q[1], s.q[2], s.q[3],
                           s.v[0], s.v[1], s.v[2], s.bg[0], s.bg[1], s.bg[2], static_cast<double>(s.num_features),
                           static_cast<double>(s.frames_in), static_cast<double>(s.frames_dropped), static_cast<double>(s.states_out),
                           s.last_process_ms, (ok && s.valid) ? 1.0 : 0.0, static_cast<double>(s.reset_count),
                           static_cast<double>(s.auto_resets)};
    env->SetDoubleArrayRegion(out, 0, 22, d);
    if (env->GetArrayLength(out) >= 24) {   // newer callers also get the stereo match and landmark counts
        const jdouble e[2] = {static_cast<double>(s.stereo_matches), static_cast<double>(s.landmarks)};
        env->SetDoubleArrayRegion(out, 22, 2, e);
    }
    return s.states_out;
}

JNIEXPORT jboolean JNICALL Java_dev_ultraxr_tracking_Native_vioSetOption(JNIEnv *env, jclass, jlong h, jstring key, jdouble value) {
    if (!h) return JNI_FALSE;
    const std::string k = to_string(env, key);
    return uvio_set_option(reinterpret_cast<uvio_t *>(h), k.c_str(), value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_ultraxr_tracking_Native_vioReset(JNIEnv *, jclass, jlong h) {
    if (h) uvio_reset(reinterpret_cast<uvio_t *>(h));
}

JNIEXPORT jstring JNICALL Java_dev_ultraxr_tracking_Native_vioVersion(JNIEnv *env, jclass) { return env->NewStringUTF(uvio_version()); }

// ---------------------------------------------------------------- hands

JNIEXPORT jlong JNICALL Java_dev_ultraxr_tracking_Native_handsCreate(JNIEnv *env, jclass, jstring calib, jstring models, jint threads) {
    const std::string c = to_string(env, calib), m = to_string(env, models);
    ultra_hands_set_threading(threads, 1, 0);
    xr_mercury_create_info info;
    std::memset(&info, 0, sizeof(info));
    info.abi_version = XR_MERCURY_RUNTIME_ABI_VERSION;
    info.size_bytes = sizeof(info);
    info.models_dir = m.c_str();
    info.calib_json = c.c_str();
    // XR Gate's defaults for this headset: no rotation, no swap, rectangular boundary.
    info.boundary0_center_x = info.boundary0_center_y = info.boundary1_center_x = info.boundary1_center_y = 0.5f;
    info.boundary0_radius = info.boundary1_radius = 0.55f;
    void *ctx = xr_mercury_runtime_create(&info);
    if (!ctx) {
        const char *e = ultra_hands_last_create_error();
        set_error(e && e[0] ? e : "xr_mercury_runtime_create failed");
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL Java_dev_ultraxr_tracking_Native_handsDestroy(JNIEnv *, jclass, jlong h) {
    if (h) xr_mercury_runtime_destroy(reinterpret_cast<void *>(h));
}

JNIEXPORT jint JNICALL Java_dev_ultraxr_tracking_Native_handsProcess(JNIEnv *env, jclass, jlong h, jlong t, jbyteArray left, jbyteArray right,
                                                                     jint w, jint hgt, jfloatArray out, jfloatArray out26) {
    static uint64_t sequence = 0;
    if (!h || !left || !right || !out) return -1;
    if (env->GetArrayLength(left) < w * hgt || env->GetArrayLength(right) < w * hgt) return -1;
    if (env->GetArrayLength(out) < static_cast<jsize>(2 * XR_MERCURY_HAND_JOINT_COUNT * 3)) return -1;
    jbyte *l = env->GetByteArrayElements(left, nullptr);
    jbyte *r = env->GetByteArrayElements(right, nullptr);
    xr_mercury_frame_f32 frame;
    std::memset(&frame, 0, sizeof(frame));
    int rc = -1;
    if (l && r) {
        rc = xr_mercury_runtime_process_gray8(reinterpret_cast<void *>(h), reinterpret_cast<const uint8_t *>(l), w, hgt, w,
                                              reinterpret_cast<const uint8_t *>(r), w, hgt, w, static_cast<uint64_t>(t), ++sequence, &frame);
    }
    if (l) env->ReleaseByteArrayElements(left, l, JNI_ABORT);
    if (r) env->ReleaseByteArrayElements(right, r, JNI_ABORT);
    if (rc != 0) {
        const char *e = xr_mercury_runtime_last_error(reinterpret_cast<void *>(h));
        set_error(e ? e : "hand tracking failed");
        return -1;
    }
    jfloat buf[2 * XR_MERCURY_HAND_JOINT_COUNT * 3];
    std::memset(buf, 0, sizeof(buf));
    int mask = 0;
    const xr_mercury_hand_side_f32 *sides[2] = {&frame.left, &frame.right};
    for (int s = 0; s < 2; s++) {
        const xr_mercury_hand_side_f32 &hs = *sides[s];
        if (hs.status == 0 || hs.joint_count < XR_MERCURY_HAND_JOINT_COUNT) continue;
        mask |= 1 << s;
        for (uint32_t j = 0; j < XR_MERCURY_HAND_JOINT_COUNT; j++) {
            jfloat *o = buf + (s * XR_MERCURY_HAND_JOINT_COUNT + j) * 3;
            o[0] = hs.joints[j].px; o[1] = hs.joints[j].py; o[2] = hs.joints[j].pz;
        }
    }
    env->SetFloatArrayRegion(out, 0, 2 * XR_MERCURY_HAND_JOINT_COUNT * 3, buf);
    // Full OpenXR joint set with orientations (px,py,pz,qx,qy,qz,qw per joint), same frame.
    if (out26 && env->GetArrayLength(out26) >= static_cast<jsize>(2 * ULTRA_HANDS_JOINT26_COUNT * 7)) {
        jfloat b26[2 * ULTRA_HANDS_JOINT26_COUNT * 7];
        std::memset(b26, 0, sizeof(b26));
        for (int s = 0; s < 2; s++) {
            if (!(mask & (1 << s))) continue;
            ultra_hands_joint_pose poses[ULTRA_HANDS_JOINT26_COUNT];
            if (ultra_hands_get_joints26(reinterpret_cast<void *>(h), s, poses) != 1) { mask &= ~(1 << s); continue; }
            for (uint32_t j = 0; j < ULTRA_HANDS_JOINT26_COUNT; j++) {
                jfloat *o = b26 + (s * ULTRA_HANDS_JOINT26_COUNT + j) * 7;
                o[0] = poses[j].px; o[1] = poses[j].py; o[2] = poses[j].pz;
                o[3] = poses[j].qx; o[4] = poses[j].qy; o[5] = poses[j].qz; o[6] = poses[j].qw;
            }
        }
        env->SetFloatArrayRegion(out26, 0, 2 * ULTRA_HANDS_JOINT26_COUNT * 7, b26);
    }
    return mask;
}

JNIEXPORT jstring JNICALL Java_dev_ultraxr_tracking_Native_handsVersion(JNIEnv *env, jclass) { return env->NewStringUTF(ultra_hands_version()); }

// ---------------------------------------------------------------- the saved map (RTAB-Map)

JNIEXPORT jlong JNICALL Java_dev_ultraxr_tracking_Native_slamCreate(JNIEnv *env, jclass, jstring rig, jstring db, jint mode, jstring params) {
    const std::string r = to_string(env, rig), d = to_string(env, db), p = to_string(env, params);
    uslam_t *h = uslam_create(r.c_str(), d.c_str(), mode, params ? p.c_str() : nullptr);
    if (!h) set_error(uslam_last_error());
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jboolean JNICALL Java_dev_ultraxr_tracking_Native_slamFeed(JNIEnv *env, jclass, jlong h, jlong t, jbyteArray left, jbyteArray right, jint w, jint hgt,
                                                                    jdoubleArray pose) {
    if (!h || !left || !right || !pose || env->GetArrayLength(pose) < 7) return JNI_FALSE;
    if (env->GetArrayLength(left) < w * hgt || env->GetArrayLength(right) < w * hgt) return JNI_FALSE;
    jdouble ps[7];
    env->GetDoubleArrayRegion(pose, 0, 7, ps);
    jbyte *l = env->GetByteArrayElements(left, nullptr);
    jbyte *r = env->GetByteArrayElements(right, nullptr);
    int ok = 0;
    if (l && r) ok = uslam_feed(reinterpret_cast<uslam_t *>(h), t, reinterpret_cast<const uint8_t *>(l), reinterpret_cast<const uint8_t *>(r), w, hgt, ps);
    if (l) env->ReleaseByteArrayElements(left, l, JNI_ABORT);
    if (r) env->ReleaseByteArrayElements(right, r, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_ultraxr_tracking_Native_slamNewEpoch(JNIEnv *, jclass, jlong h) {
    if (h) uslam_new_epoch(reinterpret_cast<uslam_t *>(h));
}

JNIEXPORT jlong JNICALL Java_dev_ultraxr_tracking_Native_slamGetState(JNIEnv *env, jclass, jlong h, jdoubleArray out) {
    if (!h || !out || env->GetArrayLength(out) < 18) return 0;
    uslam_state s;
    std::memset(&s, 0, sizeof(s));
    uslam_get_state(reinterpret_cast<uslam_t *>(h), &s);
    const jdouble d[18] = {static_cast<double>(s.results), static_cast<double>(s.found), static_cast<double>(s.dropped), static_cast<double>(s.last_t_ns),
                           static_cast<double>(s.found_t_ns), static_cast<double>(s.has_corr), s.corr[0], s.corr[1], s.corr[2], s.corr[3], s.corr[4], s.corr[5],
                           s.corr[6], static_cast<double>(s.places), static_cast<double>(s.last_place), static_cast<double>(s.inliers), s.last_ms,
                           static_cast<double>(s.epoch)};
    env->SetDoubleArrayRegion(out, 0, 18, d);
    return s.results;
}

JNIEXPORT void JNICALL Java_dev_ultraxr_tracking_Native_slamDestroy(JNIEnv *, jclass, jlong h, jboolean save) {
    if (h) uslam_destroy(reinterpret_cast<uslam_t *>(h), save ? 1 : 0);
}

JNIEXPORT jstring JNICALL Java_dev_ultraxr_tracking_Native_slamVersion(JNIEnv *env, jclass) { return env->NewStringUTF(uslam_version()); }

JNIEXPORT jstring JNICALL Java_dev_ultraxr_tracking_Native_lastError(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

}  // extern "C"
