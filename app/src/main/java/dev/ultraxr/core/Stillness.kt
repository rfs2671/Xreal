package dev.ultraxr.core

import kotlin.math.sqrt

/**
 * Says when the glasses are lying perfectly still (put down on a table), from the motion sensor alone.
 *
 * Why: with the glasses put down the cameras often see something useless (a blank table top a few centimetres
 * away, reflections, someone walking past), and the position estimator then walks off: 8 to 26 cm in two minutes
 * with the shipped settings in the one recording there is, and much more with looser ones. But a sensor that
 * reports no motion at all is not on a head, and its position is not changing, so the position can simply be held.
 *
 * The numbers are measured (recording of 17 November 2026, half-second windows): lying still, the gyroscope's
 * scatter was 0.10 to 0.11 deg/s and the accelerometer's 0.03 to 0.065 m/s2; on the owner's head the gyroscope's
 * was never under 1.26 deg/s and the accelerometer's never under 0.097. The limits sit between, so this does not
 * fire while the glasses are worn, however still the wearer sits.
 *
 * Still after two quiet half-second windows in a row; not still again at once on any sample that departs from the
 * window's mean by more than [BREAK_RAD_S].
 */
class StillnessDetector(private val windowNs: Long = 500_000_000L) {
    @Volatile var still = false; private set
    private var startNs = 0L
    private var n = 0
    private val sum = DoubleArray(6); private val sumSq = DoubleArray(6)
    private val mean = DoubleArray(3)
    private var haveMean = false
    private var quietWindows = 0

    /** One motion sample: time in nanoseconds, gyroscope in rad/s, accelerometer in m/s2. */
    @Synchronized fun onImu(tNs: Long, gyro: FloatArray, accel: FloatArray) {
        if (startNs == 0L || tNs < startNs) { startNs = tNs; clear() }
        if (haveMean && still) {
            val dx = gyro[0] - mean[0]; val dy = gyro[1] - mean[1]; val dz = gyro[2] - mean[2]
            if (dx * dx + dy * dy + dz * dz > BREAK_RAD_S * BREAK_RAD_S) { still = false; quietWindows = 0 }
        }
        for (i in 0..2) { val g = gyro[i].toDouble(); val a = accel[i].toDouble(); sum[i] += g; sumSq[i] += g * g; sum[3 + i] += a; sumSq[3 + i] += a * a }
        n++
        if (tNs - startNs < windowNs) return
        if (n >= MIN_SAMPLES) {
            var gv = 0.0; var av = 0.0
            for (i in 0..2) {
                val m = sum[i] / n; gv += (sumSq[i] / n - m * m).coerceAtLeast(0.0); mean[i] = m
                val ma = sum[3 + i] / n; av += (sumSq[3 + i] / n - ma * ma).coerceAtLeast(0.0)
            }
            haveMean = true
            val quiet = sqrt(gv) < GYRO_STD_RAD_S && sqrt(av) < ACCEL_STD_M_S2
            quietWindows = if (quiet) quietWindows + 1 else 0
            still = quietWindows >= 2
        } else { quietWindows = 0; still = false }      // too few samples to tell (a gap in the data)
        startNs = tNs; clear()
    }

    @Synchronized fun reset() { still = false; quietWindows = 0; haveMean = false; startNs = 0L; clear() }
    private fun clear() { n = 0; for (i in 0..5) { sum[i] = 0.0; sumSq[i] = 0.0 } }

    companion object {
        /** 0.35 deg/s: three times the sensor's own scatter lying still, a quarter of the least seen on a head. */
        const val GYRO_STD_RAD_S = 0.35 * Math.PI / 180
        const val ACCEL_STD_M_S2 = 0.08
        /** 2 deg/s away from the window's mean ends stillness at once. */
        const val BREAK_RAD_S = 2.0 * Math.PI / 180
        const val MIN_SAMPLES = 100
    }
}
