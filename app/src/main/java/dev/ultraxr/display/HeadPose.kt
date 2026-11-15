package dev.ultraxr.display

/**
 * Gyro-only orientation, integrated from the glasses' IMU. This exists so the test
 * scene visibly reacts to head motion; it is NOT a tracking implementation (it
 * drifts, and the sensor-to-display axis mapping is unverified).
 */
object HeadPose {
    private val q = floatArrayOf(1f, 0f, 0f, 0f) // w, x, y, z
    private var lastTs = 0L

    @Synchronized fun reset() { q[0] = 1f; q[1] = 0f; q[2] = 0f; q[3] = 0f; lastTs = 0 }

    @Synchronized fun integrate(gyro: FloatArray, timestampNs: Long) {
        val dt = if (lastTs == 0L) 0f else (timestampNs - lastTs) * 1e-9f
        lastTs = timestampNs
        if (dt <= 0f || dt > 0.1f) return
        // Axis guess from Pocket XR's Air mapping (sensor x left, y back, z up -> head -x, z, y).
        val wx = -gyro[0]; val wy = gyro[2]; val wz = gyro[1]
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        val h = 0.5f * dt
        q[0] = w + h * (-x * wx - y * wy - z * wz)
        q[1] = x + h * (w * wx + y * wz - z * wy)
        q[2] = y + h * (w * wy - x * wz + z * wx)
        q[3] = z + h * (w * wz + x * wy - y * wx)
        val n = Math.sqrt((q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).toDouble()).toFloat()
        if (n > 0f) for (i in 0..3) q[i] /= n
    }

    /** Writes the inverse rotation (world -> head) as a column-major 4x4 matrix. */
    @Synchronized fun viewRotation(m: FloatArray) {
        val w = q[0]; val x = -q[1]; val y = -q[2]; val z = -q[3]
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y + w * z); m[2] = 2 * (x * z - w * y); m[3] = 0f
        m[4] = 2 * (x * y - w * z); m[5] = 1 - 2 * (x * x + z * z); m[6] = 2 * (y * z + w * x); m[7] = 0f
        m[8] = 2 * (x * z + w * y); m[9] = 2 * (y * z - w * x); m[10] = 1 - 2 * (x * x + y * y); m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
    }
}
