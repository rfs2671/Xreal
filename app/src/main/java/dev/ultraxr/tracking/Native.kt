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
    @JvmStatic external fun vioVersion(): String

    // --- hands (Mercury)
    @JvmStatic external fun handsCreate(calibJson: String, modelDir: String, threads: Int): Long
    @JvmStatic external fun handsDestroy(handle: Long)
    /**
     * Runs hand tracking on one stereo pair (blocking). Fills [out] with 2 x 21 x 3 floats
     * (left hand then right hand, metres, in the left camera frame) and returns a bit mask:
     * bit 0 = left hand valid, bit 1 = right hand valid; negative on error.
     */
    @JvmStatic external fun handsProcess(handle: Long, tNs: Long, left: ByteArray, right: ByteArray, width: Int, height: Int, out: FloatArray): Int
    @JvmStatic external fun handsVersion(): String

    @JvmStatic external fun lastError(): String
}
