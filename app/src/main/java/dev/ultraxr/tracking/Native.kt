package dev.ultraxr.tracking

/** JNI bindings (cpp/tracking_jni.cpp) to the Basalt and Mercury libraries. */
object Native {
    @Volatile var loaded = false; private set
    @Volatile var loadError = ""; private set

    init {
        try { System.loadLibrary("ultraxr"); loaded = true }
        catch (e: Throwable) { loadError = e.toString() }
    }

    // --- 6DoF (Basalt)
    /** Returns a handle, or 0 with the reason available from [lastError]. */
    @JvmStatic external fun vioCreate(calibJson: String, configJson: String): Long
    @JvmStatic external fun vioDestroy(handle: Long)
    @JvmStatic external fun vioPushImu(handle: Long, tNs: Long, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float)
    @JvmStatic external fun vioPushStereo(handle: Long, tNs: Long, left: ByteArray, right: ByteArray, width: Int, height: Int)
    /**
     * Fills [out] (22 doubles): t_ns, p xyz, q xyzw, v xyz, bg xyz, features, frames_in,
     * frames_dropped, states_out, last_process_ms, valid, reset_count, auto_resets.
     * Returns states_out (a sequence number).
     */
    @JvmStatic external fun vioGetState(handle: Long, out: DoubleArray): Long
    @JvmStatic external fun vioReset(handle: Long)
    /** Tuning knob of the estimator wrapper (see ultra_vio.h). Returns false for an unknown key. */
    @JvmStatic external fun vioSetOption(handle: Long, key: String, value: Double): Boolean
    @JvmStatic external fun vioVersion(): String

    // --- hands (Mercury)
    /** [calibPath] is the path of a calibration JSON file; [modelDir] holds the two .onnx models. */
    @JvmStatic external fun handsCreate(calibPath: String, modelDir: String, threads: Int): Long
    @JvmStatic external fun handsDestroy(handle: Long)
    /**
     * Runs hand tracking on one stereo pair (blocking). Fills [out] with 2 x 21 x 3 floats
     * (left hand then right hand, metres, in the left camera frame) and returns a bit mask:
     * bit 0 = left hand valid, bit 1 = right hand valid; negative on error. [out26] receives
     * 2 x 26 x 7 floats: the OpenXR joint set as px,py,pz,qx,qy,qz,qw in the same frame.
     */
    @JvmStatic external fun handsProcess(handle: Long, tNs: Long, left: ByteArray, right: ByteArray, width: Int, height: Int, out: FloatArray, out26: FloatArray): Int
    @JvmStatic external fun handsVersion(): String

    // --- the saved map of the room (RTAB-Map, native/slam)
    /** [rig]: two lines "fx fy cx cy k1 k2 k3 k4 px py pz qx qy qz qw". [mode] 0 makes a map at [db] afresh, 1 finds the head in the one there. 0 on failure. */
    @JvmStatic external fun slamCreate(rig: String, db: String, mode: Int, params: String?): Long
    /** One pair with the estimator's pose for it (p xyz, q xyzw, its own world). False: the worker was busy, the pair is dropped. */
    @JvmStatic external fun slamFeed(handle: Long, tNs: Long, left: ByteArray, right: ByteArray, width: Int, height: Int, pose: DoubleArray): Boolean
    @JvmStatic external fun slamNewEpoch(handle: Long)
    /**
     * Fills [out] (18 doubles): results, found, dropped, last_t_ns, found_t_ns, has_corr, the estimator's world in the
     * map's as p xyz q xyzw, places, last_place, inliers, last_ms, epoch. Returns results.
     */
    @JvmStatic external fun slamGetState(handle: Long, out: DoubleArray): Long
    /** Blocks for a second or two when a map is written. */
    @JvmStatic external fun slamDestroy(handle: Long, save: Boolean)
    @JvmStatic external fun slamVersion(): String

    @JvmStatic external fun lastError(): String
}
