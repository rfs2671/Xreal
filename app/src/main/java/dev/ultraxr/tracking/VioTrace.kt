package dev.ultraxr.tracking

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Diagnostic record of the 6DoF estimator. Keeps the last [KEEP] states with the IMU readings
 * that arrived between them; [dump] writes them to a CSV when the estimator is reset, so the
 * seconds before a reset can be inspected. Also logs one summary line every [SUMMARY_NS].
 * Changes nothing about tracking.
 */
class VioTrace(private val log: (String) -> Unit) {
    // t_ns, p xyz, q xyzw, v xyz, bg xyz, features, process_ms, mean |accel|, mean |gyro|, imu samples
    private val ring = ArrayDeque<DoubleArray>()
    private var accelSum = 0.0; private var gyroSum = 0.0; private var imuN = 0
    private var epochStartNs = 0L; private var epochStartP: DoubleArray? = null
    private var lastSummaryNs = 0L; private var maxSpeed = 0.0; private var pathM = 0.0; private var lastP: DoubleArray? = null
    private var dumps = 0

    /** IMU thread. */
    fun onImu(gyro: FloatArray, accel: FloatArray) {
        val a = sqrt((accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]).toDouble())
        val g = sqrt((gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2]).toDouble())
        synchronized(this) { accelSum += a; gyroSum += g; imuN++ }
    }

    /** Estimator poll thread, once per fresh state; [out] is the array from Native.vioGetState. */
    fun onState(out: DoubleArray) {
        val row = DoubleArray(18)
        System.arraycopy(out, 0, row, 0, 14)              // t, p, q, v, bg
        row[14] = out[14]; row[15] = out[18]
        synchronized(this) {
            row[16] = if (imuN > 0) accelSum / imuN else Double.NaN; row[17] = if (imuN > 0) gyroSum / imuN else Double.NaN
            accelSum = 0.0; gyroSum = 0.0; imuN = 0
            ring.addLast(row); while (ring.size > KEEP) ring.removeFirst()
        }
        val t = out[0].toLong(); val p = doubleArrayOf(out[1], out[2], out[3])
        val speed = sqrt(out[8] * out[8] + out[9] * out[9] + out[10] * out[10])
        if (epochStartP == null) { epochStartP = p; epochStartNs = t; lastSummaryNs = t; maxSpeed = 0.0; pathM = 0.0 }
        lastP?.let { pathM += dist(it, p) }; lastP = p
        if (speed > maxSpeed) maxSpeed = speed
        if (t - lastSummaryNs >= SUMMARY_NS) {
            lastSummaryNs = t
            log(String.format(Locale.US, "6DoF status: %.0f s since start, pos %.3f %.3f %.3f, %.3f m from start, path %.2f m, speed now %.2f max %.2f m/s, %d features, gyro bias %.4f %.4f %.4f, |accel| %.3f |gyro| %.3f",
                (t - epochStartNs) * 1e-9, p[0], p[1], p[2], dist(epochStartP!!, p), pathM, speed, maxSpeed, out[14].toInt(), out[11], out[12], out[13], row[16], row[17]))
        }
    }

    /** Writes the kept states to a file and starts a new epoch. Returns a short description, or null. */
    fun dump(context: Context?, reason: String): String? {
        val rows: List<DoubleArray>
        val seconds: Double; val moved: Double
        synchronized(this) {
            rows = ring.toList(); ring.clear()
            seconds = if (epochStartP != null && rows.isNotEmpty()) (rows.last()[0] - epochStartNs) * 1e-9 else 0.0
            moved = if (epochStartP != null && rows.isNotEmpty()) dist(epochStartP!!, doubleArrayOf(rows.last()[1], rows.last()[2], rows.last()[3])) else 0.0
            epochStartP = null; lastP = null
        }
        if (rows.isEmpty() || context == null) return null
        return try {
            val dir = File(context.getExternalFilesDir(null), "vio-traces").apply { mkdirs() }
            if (++dumps > MAX_FILES) return String.format(Locale.US, "epoch lasted %.0f s (trace limit reached, not written)", seconds)
            val f = File(dir, "trace-" + System.currentTimeMillis() + ".csv")
            f.bufferedWriter().use { w ->
                w.write(String.format(Locale.US, "# reason: %s; epoch lasted %.1f s; %.3f m from its start\n", reason, seconds, moved))
                w.write("t_ns,px,py,pz,qx,qy,qz,qw,vx,vy,vz,bgx,bgy,bgz,features,process_ms,accel_norm,gyro_norm\n")
                for (r in rows) { w.write(r[0].toLong().toString()); for (i in 1 until r.size) { w.write(","); w.write(String.format(Locale.US, "%.6f", r[i])) }; w.write("\n") }
            }
            String.format(Locale.US, "epoch lasted %.0f s, ended %.2f m from its start, %d states saved to %s", seconds, moved, rows.size, f.name)
        } catch (e: Exception) { "trace not written: $e" }
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    companion object {
        const val KEEP = 600                  // 20 s at 30 states a second
        const val SUMMARY_NS = 5_000_000_000L
        const val MAX_FILES = 200
    }
}
