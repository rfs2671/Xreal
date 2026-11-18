package dev.ultraxr.core

import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A Kannala-Brandt fisheye camera (Basalt's "kb4"): pixels to sight lines and back. */
class Kb4(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val k1: Double, val k2: Double, val k3: Double, val k4: Double) {
    /** A point in the camera's frame (x right, y down, z forward) to a pixel. False when it is behind or beside the camera. */
    fun project(x: Double, y: Double, z: Double, out: DoubleArray): Boolean {
        val r = sqrt(x * x + y * y); val th = atan2(r, z); val t2 = th * th
        val d = th * (1 + t2 * (k1 + t2 * (k2 + t2 * (k3 + t2 * k4))))
        val s = if (r > 1e-9) d / r else 1.0
        out[0] = fx * x * s + cx; out[1] = fy * y * s + cy
        return th < 1.45
    }

    /** A pixel to its unit sight line. */
    fun unproject(u: Double, v: Double, out: DoubleArray) {
        val mx = (u - cx) / fx; val my = (v - cy) / fy
        val r = sqrt(mx * mx + my * my); var th = r
        repeat(8) {
            val t2 = th * th
            val f = th * (1 + t2 * (k1 + t2 * (k2 + t2 * (k3 + t2 * k4)))) - r
            val d = 1 + t2 * (3 * k1 + t2 * (5 * k2 + t2 * (7 * k3 + t2 * 9 * k4)))
            th -= f / d
        }
        val s = if (r > 1e-9) sin(th) / r else 1.0
        out[0] = mx * s; out[1] = my * s; out[2] = cos(th)
    }
}

/** The two tracking cameras as the position estimator's calibration describes them. */
class StereoRig(val k0: Kb4, val k1: Kb4, val cam0InImu: Pose, val cam1InImu: Pose) {
    /** Camera 0's frame seen from camera 1: a point p in camera 0 is [cam0InCam1].apply(p) in camera 1. */
    val cam0InCam1: Pose = cam1InImu.inverse() * cam0InImu

    companion object {
        /** From the Basalt calibration JSON (value0.intrinsics, value0.T_imu_cam). */
        fun fromBasalt(json: String): StereoRig {
            val v = JSONObject(json).getJSONObject("value0")
            fun cam(i: Int): Kb4 {
                val k = v.getJSONArray("intrinsics").getJSONObject(i).getJSONObject("intrinsics")
                return Kb4(k.getDouble("fx"), k.getDouble("fy"), k.getDouble("cx"), k.getDouble("cy"), k.getDouble("k1"), k.getDouble("k2"), k.getDouble("k3"), k.getDouble("k4"))
            }
            fun pose(i: Int): Pose {
                val o = v.getJSONArray("T_imu_cam").getJSONObject(i)
                return Pose(doubleArrayOf(o.getDouble("px"), o.getDouble("py"), o.getDouble("pz")), Q.normalize(doubleArrayOf(o.getDouble("qx"), o.getDouble("qy"), o.getDouble("qz"), o.getDouble("qw"))))
            }
            return StereoRig(cam(0), cam(1), pose(0), pose(1))
        }
    }
}

/**
 * Where things in the room are, from one pair of camera pictures: corners are picked in the left picture, and each
 * is looked for in the right picture along the line the calibration says it must lie on, one candidate distance
 * after another (from 0.3 m to 10 m, in steps of about one pixel). The distance whose patch of the right picture
 * looks most like the left one is the answer, if it is a clear winner.
 *
 * Pure arithmetic on the two grey pictures (480 x 640, upright, as the trackers get them); no native code.
 */
class StereoPoints(private val rig: StereoRig, private val w: Int = 480, private val h: Int = 640) {
    /** Lowest likeness (zero-mean normalised correlation, -1 to 1) for a match to count. */
    var minScore = 0.80
    /** The best distance must beat every clearly different distance by this much. */
    var minLead = 0.06

    private val steps = 110
    private val nearInv = 1.0 / 0.3; private val farInv = 1.0 / 10.0
    private val half = 5                    // 11 x 11 patch
    private val cell = 20                   // one corner at most per 20 x 20 pixels
    private val margin = 14
    private val ixx = IntArray(w * h); private val iyy = IntArray(w * h); private val ixy = IntArray(w * h)
    private val sxx = LongArray((w + 1) * (h + 1)); private val syy = LongArray((w + 1) * (h + 1)); private val sxy = LongArray((w + 1) * (h + 1))
    private val score = FloatArray(steps)
    private val ray = DoubleArray(3); private val uv = DoubleArray(2)
    private val patch = IntArray((2 * half + 1) * (2 * half + 1))
    private val pu = IntArray(steps); private val pv = IntArray(steps)
    private val fu = DoubleArray(steps); private val fv = DoubleArray(steps)
    /** How many corners the last call looked for, for the record. */
    var lastCorners = 0; private set

    private fun integral(src: IntArray, dst: LongArray) {
        val W = w + 1
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) { row += src[y * w + x]; dst[(y + 1) * W + x + 1] = dst[y * W + x + 1] + row }
        }
    }
    private fun box(s: LongArray, x: Int, y: Int, r: Int): Double {
        val W = w + 1; val x0 = x - r; val y0 = y - r; val x1 = x + r + 1; val y1 = y + r + 1
        return (s[y1 * W + x1] - s[y0 * W + x1] - s[y1 * W + x0] + s[y0 * W + x0]).toDouble()
    }

    /** Likeness of the left patch (already in [patch], with its mean and spread) to the right picture's patch at (u, v). */
    private fun zncc(right: ByteArray, u: Int, v: Int, mean: Double, dev: Double): Float {
        if (u < half || v < half || u >= w - half || v >= h - half) return -2f
        var s = 0L; var ss = 0L; var sp = 0L; var k = 0
        for (dy in -half..half) {
            var o = (v + dy) * w + u - half
            for (dx in -half..half) { val b = right[o].toInt() and 0xFF; s += b; ss += b * b; sp += b.toLong() * patch[k]; o++; k++ }
        }
        val n = patch.size.toDouble()
        val m = s / n; val d2 = ss / n - m * m
        if (d2 < 4.0) return -2f                // a flat patch looks like nothing
        return ((sp / n - m * mean) / (sqrt(d2) * dev)).toFloat()
    }

    /** The same, with the right picture read between its pixels at (u, v): for the last, fine comparison. */
    private fun znccFine(right: ByteArray, u: Double, v: Double, mean: Double, dev: Double): Float {
        val iu = Math.floor(u).toInt(); val iv = Math.floor(v).toInt()
        if (iu < half || iv < half || iu >= w - half - 1 || iv >= h - half - 1) return -2f
        val fu = u - iu; val fv = v - iv
        val w00 = (1 - fu) * (1 - fv); val w10 = fu * (1 - fv); val w01 = (1 - fu) * fv; val w11 = fu * fv
        var s = 0.0; var ss = 0.0; var sp = 0.0; var k = 0
        for (dy in -half..half) {
            var o = (iv + dy) * w + iu - half
            for (dx in -half..half) {
                val b = w00 * (right[o].toInt() and 0xFF) + w10 * (right[o + 1].toInt() and 0xFF) + w01 * (right[o + w].toInt() and 0xFF) + w11 * (right[o + w + 1].toInt() and 0xFF)
                s += b; ss += b * b; sp += b * patch[k]; o++; k++
            }
        }
        val n = patch.size.toDouble()
        val m = s / n; val d2 = ss / n - m * m
        if (d2 < 4.0) return -2f
        return ((sp / n - m * mean) / (sqrt(d2) * dev)).toFloat()
    }

    /**
     * Fills [out] with up to [max] points, three floats each, in the left camera's frame (x right, y down, z forward,
     * metres), and returns how many. [quality], if given, gets each point's likeness score.
     */
    fun find(left: ByteArray, right: ByteArray, out: FloatArray, max: Int = out.size / 3, quality: FloatArray? = null): Int {
        // Corners: where the picture changes in both directions (smaller eigenvalue of the gradient's spread).
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (left[i + 1].toInt() and 0xFF) - (left[i - 1].toInt() and 0xFF); val gy = (left[i + w].toInt() and 0xFF) - (left[i - w].toInt() and 0xFF)
            ixx[i] = gx * gx; iyy[i] = gy * gy; ixy[i] = gx * gy
        }
        integral(ixx, sxx); integral(iyy, syy); integral(ixy, sxy)
        val cols = w / cell; val rows = h / cell
        val bestScore = DoubleArray(cols * rows); val bestAt = IntArray(cols * rows) { -1 }
        var top = 0.0
        for (y in margin until h - margin step 2) for (x in margin until w - margin step 2) {
            val a = box(sxx, x, y, 3); val c = box(syy, x, y, 3); val b = box(sxy, x, y, 3)
            val e = (a + c) / 2 - sqrt((a - c) * (a - c) / 4 + b * b)
            val ci = (y / cell) * cols + x / cell
            if (ci < bestScore.size && e > bestScore[ci]) { bestScore[ci] = e; bestAt[ci] = y * w + x }
            if (e > top) top = e
        }
        // 49 pixels with a gradient of 6 grey levels each way: below that it is sensor noise, not a corner.
        val floor = maxOf(top * 0.01, 49.0 * 36.0)
        val order = bestScore.indices.filter { bestAt[it] >= 0 && bestScore[it] >= floor }.sortedByDescending { bestScore[it] }
        lastCorners = order.size
        val c01 = rig.cam0InCam1; val p0 = DoubleArray(3)
        val stepInv = (nearInv - farInv) / (steps - 1)
        var n = 0
        for (ci in order) {
            if (n >= max) break
            val at = bestAt[ci]; val u0 = at % w; val v0 = at / w
            var s = 0L; var ss = 0L; var k = 0
            for (dy in -half..half) for (dx in -half..half) { val b = left[(v0 + dy) * w + u0 + dx].toInt() and 0xFF; patch[k++] = b; s += b; ss += b * b }
            val mean = s / patch.size.toDouble(); val d2 = ss / patch.size.toDouble() - mean * mean
            if (d2 < 4.0) continue
            val dev = sqrt(d2)
            rig.k0.unproject(u0.toDouble(), v0.toDouble(), ray)
            var best = -1; var bestS = -2f
            for (i in 0 until steps) {
                val dist = 1.0 / (nearInv - i * stepInv)
                p0[0] = ray[0] * dist; p0[1] = ray[1] * dist; p0[2] = ray[2] * dist
                val p1 = c01.apply(p0)
                if (!rig.k1.project(p1[0], p1[1], p1[2], uv)) { score[i] = -2f; continue }
                val u = Math.round(uv[0]).toInt(); val v = Math.round(uv[1]).toInt()
                pu[i] = u; pv[i] = v; fu[i] = uv[0]; fv[i] = uv[1]
                score[i] = zncc(right, u, v, mean, dev)
                if (score[i] > bestS) { bestS = score[i]; best = i }
            }
            if (best <= 0 || best >= steps - 1 || bestS < 0.5f) continue
            // Coarse search done (whole pixels). Now the candidates around the best are compared exactly where the
            // calibration puts them, between pixels, and one row up and down: the calibration is good to about a pixel.
            var row = 0.0; var rowS = -2f
            for (dv in doubleArrayOf(-1.0, -0.5, 0.0, 0.5, 1.0)) { val z = znccFine(right, fu[best], fv[best] + dv, mean, dev); if (z > rowS) { rowS = z; row = dv } }
            val lo = maxOf(1, best - 2); val hi = minOf(steps - 2, best + 2)
            for (i in lo - 1..hi + 1) score[i] = znccFine(right, fu[i], fv[i] + row, mean, dev)
            for (i in lo..hi) if (score[i] > score[best]) best = i
            if (best <= 0 || best >= steps - 1) continue
            bestS = score[best]
            if (bestS < minScore) continue
            var rival = -2f
            for (i in 0 until steps) if ((i < best - 3 || i > best + 3) && score[i] > rival) rival = score[i]
            if (rival > bestS - minLead) continue
            // Between steps: the top of the parabola through the best score and its two neighbours.
            val a = score[best - 1].toDouble(); val b = bestS.toDouble(); val c = score[best + 1].toDouble()
            val den = a - 2 * b + c
            val shift = if (a > -1.5 && c > -1.5 && den < -1e-6) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
            val dist = 1.0 / (nearInv - (best + shift) * stepInv)
            out[n * 3] = (ray[0] * dist).toFloat(); out[n * 3 + 1] = (ray[1] * dist).toFloat(); out[n * 3 + 2] = (ray[2] * dist).toFloat()
            quality?.set(n, bestS)
            n++
        }
        return n
    }
}
