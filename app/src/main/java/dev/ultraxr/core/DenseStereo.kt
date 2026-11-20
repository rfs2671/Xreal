package dev.ultraxr.core

import kotlin.math.sqrt

/**
 * How far away everything in view is, from one pair of the tracking cameras' pictures: a distance for (nearly) every
 * pixel, not only for corners as [StereoPoints] gives.
 *
 * The two fisheye pictures (480 x 640, upright) are first redrawn as two ordinary pictures of 240 x 320 that look
 * the same way with their rows lined up ("rectified"), so that a thing in row y of the left picture is in row y of
 * the right one, further left by a number of pixels (the disparity) that says how near it is:
 * distance = focal length * baseline / disparity. The disparity is found by semi-global matching (Hirschmueller
 * 2005): each pixel is described by which of its 24 neighbours are darker than it (a census, which does not mind the
 * two cameras exposing differently), every disparity from 0 to 63 is scored by how many of those answers differ, and
 * the scores are smoothed along four directions so that surfaces come out as surfaces. A pixel keeps its answer only
 * if the right picture, asked the same question, agrees, and if no other answer was nearly as good.
 *
 * Pure arithmetic on bytes; no native code. About a fifth of a second on one core of the phone, so it is run a few
 * times a second at most, off the tracking thread.
 */
class DenseStereo(rig: StereoRig, srcW: Int = 480, srcH: Int = 640) {
    val w = W; val h = H
    /** Rectified focal length in pixels, and the distance between the cameras in metres. */
    val focal = FOCAL
    val baseline: Double
    /** The rectified picture's axes in camera 0's frame (columns): a point (x, y, z) of the rectified view is rect * (x, y, z) in camera 0. */
    private val rect = DoubleArray(9)
    private val mapL = IntArray(FW * FH); private val mapR = IntArray(FW * FH)       // source pixel index, -1 outside; fractions beside it
    private val fracL = ShortArray(FW * FH); private val fracR = ShortArray(FW * FH)
    private val fullL = ByteArray(FW * FH); private val fullR = ByteArray(FW * FH); private val fullOkL = BooleanArray(FW * FH); private val fullOkR = BooleanArray(FW * FH)
    private val left = ByteArray(W * H); private val right = ByteArray(W * H)
    private val validL = BooleanArray(W * H); private val validR = BooleanArray(W * H)
    private val censusL = IntArray(W * H); private val censusR = IntArray(W * H)
    private val cost = ByteArray(W * H * D)
    private val sum = ShortArray(W * H * D)
    private val prev = IntArray(D + 2); private val cur = IntArray(D + 2)
    private val colPrev = IntArray(W * (D + 2))
    private val dispR = FloatArray(W * H)
    /** Disparity in pixels for each rectified pixel of the left picture; 0 where nothing can be said. */
    val disparity = FloatArray(W * H)
    private val srcW = srcW; private val srcH = srcH

    init {
        // camera 1 seen from camera 0
        val c1in0 = rig.cam0InImu.inverse() * rig.cam1InImu
        val t = c1in0.p; val bl = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]); baseline = bl
        // x along the baseline; z half way between the two cameras' forward directions, squared up to x; y = z cross x
        val x = doubleArrayOf(t[0] / bl, t[1] / bl, t[2] / bl)
        val f1 = Q.rotate(c1in0.q, doubleArrayOf(0.0, 0.0, 1.0))
        var z = doubleArrayOf(f1[0], f1[1], 1.0 + f1[2])
        val dz = z[0] * x[0] + z[1] * x[1] + z[2] * x[2]; z = doubleArrayOf(z[0] - dz * x[0], z[1] - dz * x[1], z[2] - dz * x[2])
        val zl = sqrt(z[0] * z[0] + z[1] * z[1] + z[2] * z[2]); z = doubleArrayOf(z[0] / zl, z[1] / zl, z[2] / zl)
        val y = doubleArrayOf(z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0])
        for (i in 0..2) { rect[i * 3] = x[i]; rect[i * 3 + 1] = y[i]; rect[i * 3 + 2] = z[i] }
        val toCam1 = Q.conj(c1in0.q); val px = DoubleArray(2)
        // the rectified pictures at full size (twice the matching size each way): the matching runs on their halves,
        // and each answer is then sharpened on the full pictures
        for (v in 0 until FH) for (u in 0 until FW) {
            val rx = (u - (FW - 1) / 2.0) / (2 * FOCAL); val ry = (v - (FH - 1) / 2.0) / (2 * FOCAL)
            val d0 = doubleArrayOf(rect[0] * rx + rect[1] * ry + rect[2], rect[3] * rx + rect[4] * ry + rect[5], rect[6] * rx + rect[7] * ry + rect[8])
            val i = v * FW + u
            mapL[i] = if (rig.k0.project(d0[0], d0[1], d0[2], px)) pack(px, fracL, i) else -1
            val d1 = Q.rotate(toCam1, d0)
            mapR[i] = if (rig.k1.project(d1[0], d1[1], d1[2], px)) pack(px, fracR, i) else -1
        }
    }
    private fun pack(px: DoubleArray, frac: ShortArray, i: Int): Int {
        val fx = px[0]; val fy = px[1]
        if (fx < 1 || fy < 1 || fx >= srcW - 2 || fy >= srcH - 2) return -1
        val x0 = fx.toInt(); val y0 = fy.toInt()
        frac[i] = ((((fx - x0) * 15.99).toInt() shl 4) or ((fy - y0) * 15.99).toInt()).toShort()
        return y0 * srcW + x0
    }
    private fun halve(full: ByteArray, ok: BooleanArray, dst: ByteArray, valid: BooleanArray) {
        for (v in 0 until H) for (u in 0 until W) {
            val o = (2 * v) * FW + 2 * u
            dst[v * W + u] = (((full[o].toInt() and 0xff) + (full[o + 1].toInt() and 0xff) + (full[o + FW].toInt() and 0xff) + (full[o + FW + 1].toInt() and 0xff) + 2) shr 2).toByte()
            valid[v * W + u] = ok[o] && ok[o + 1] && ok[o + FW] && ok[o + FW + 1]
        }
    }
    private fun remap(src: ByteArray, map: IntArray, frac: ShortArray, dst: ByteArray, valid: BooleanArray) {
        for (i in 0 until FW * FH) {
            val m = map[i]
            if (m < 0) { dst[i] = 0; valid[i] = false; continue }
            val f = frac[i].toInt(); val ax = (f shr 4) and 15; val ay = f and 15
            val a = src[m].toInt() and 0xff; val b = src[m + 1].toInt() and 0xff; val c = src[m + srcW].toInt() and 0xff; val d = src[m + srcW + 1].toInt() and 0xff
            dst[i] = (((a * (16 - ax) + b * ax) * (16 - ay) + (c * (16 - ax) + d * ax) * ay) shr 8).toByte(); valid[i] = true
        }
    }
    private fun census(img: ByteArray, valid: BooleanArray, out: IntArray) {
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            if (y < 2 || x < 2 || y >= H - 2 || x >= W - 2 || !valid[i]) { out[i] = 0; continue }
            val c = img[i].toInt() and 0xff; var bits = 0
            for (dy in -2..2) for (dx in -2..2) { if (dx == 0 && dy == 0) continue; bits = (bits shl 1) or (if ((img[i + dy * W + dx].toInt() and 0xff) < c) 1 else 0) }
            out[i] = bits
        }
    }

    /** The rectified left picture of the last [compute], for looking at. */
    fun rectifiedLeft(): ByteArray = left

    /** Fills [disparity] from one pair of pictures (each srcW x srcH bytes). Returns how many pixels got an answer. */
    fun compute(srcLeft: ByteArray, srcRight: ByteArray): Int {
        remap(srcLeft, mapL, fracL, fullL, fullOkL); remap(srcRight, mapR, fracR, fullR, fullOkR)
        halve(fullL, fullOkL, left, validL); halve(fullR, fullOkR, right, validR)
        census(left, validL, censusL); census(right, validR, censusR)
        // scores: how many census answers differ, for each pixel and disparity
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x; val o = i * D; val cl = censusL[i]; val ok = validL[i]
            for (d in 0 until D) {
                val xr = x - d
                cost[o + d] = if (!ok || xr < 2 || !validR[i - d]) BAD else Integer.bitCount(cl xor censusR[i - d]).toByte()
            }
        }
        java.util.Arrays.fill(sum, 0)
        // smoothing along rows, both ways
        for (y in 0 until H) { path(y * W, 1, W); path(y * W + W - 1, -1, W) }
        // and along columns, both ways (a whole row of running values at a time)
        columns(true); columns(false)
        // the right picture's answers, from the same scores
        for (y in 0 until H) for (xr in 0 until W) {
            var best = Int.MAX_VALUE; var bd = 0
            for (d in 0 until D) { val x = xr + d; if (x >= W) break; val s = sum[(y * W + x) * D + d].toInt(); if (s < best) { best = s; bd = d } }
            dispR[y * W + xr] = bd.toFloat()
        }
        var n = 0
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x; val o = i * D; disparity[i] = 0f
            if (!validL[i] || y < 3 || x < 3 || y >= H - 3 || x >= W - 3) continue
            var best = Int.MAX_VALUE; var bd = -1; var second = Int.MAX_VALUE
            for (d in 0 until D) { val s = sum[o + d].toInt(); if (s < best) { best = s; bd = d } }
            for (d in 0 until D) { if (d >= bd - 1 && d <= bd + 1) continue; val s = sum[o + d].toInt(); if (s < second) second = s }
            if (bd < MIN_DISP || bd >= D - 1) continue
            if (best * 100 > second * UNIQUE) continue                                   // another answer was nearly as good
            if (cost[o + bd] > MAX_OWN_COST) continue                                    // the pixel itself does not look like its match
            if (x - bd < 0 || kotlin.math.abs(dispR[i - bd] - bd) > 1f) continue         // the right picture disagrees
            val a = sum[o + bd - 1].toInt(); val c = sum[o + bd + 1].toInt(); val den = a - 2 * best + c
            val coarse = bd + (if (den > 0) (a - c) / (2f * den) else 0f)
            disparity[i] = sharpen(x, y, bd, coarse); n++
        }
        speckles()
        return n
    }

    private val sad = IntArray(7)
    /**
     * The answer for matching pixel ([x], [y]) looked at again on the full-size pictures, where a pixel is half as
     * wide: the 9 x 9 patch around it is compared (brightness differences apart) with the right picture at the seven
     * places around twice the coarse disparity, and the best place is settled between pixels by a parabola. Returns
     * the disparity in matching pixels; the coarse one when the full pictures do not agree with it.
     */
    private fun sharpen(x: Int, y: Int, bd: Int, coarse: Float): Float {
        val fx = 2 * x; val fy = 2 * y; val c0 = 2 * bd
        if (fx < 5 + c0 + 3 || fx >= FW - 5 || fy < 5 || fy >= FH - 5) return coarse
        var ml = 0
        for (dy in -4..4) { val o = (fy + dy) * FW + fx; for (dx in -4..4) ml += fullL[o + dx].toInt() and 0xff }
        ml /= 81
        var best = Int.MAX_VALUE; var bk = -1
        for (k in 0 until 7) {
            val d = c0 - 3 + k; var mr = 0
            for (dy in -4..4) { val o = (fy + dy) * FW + fx - d; for (dx in -4..4) mr += fullR[o + dx].toInt() and 0xff }
            mr /= 81
            var sum = 0
            for (dy in -4..4) { val o = (fy + dy) * FW + fx; for (dx in -4..4) sum += kotlin.math.abs((fullL[o + dx].toInt() and 0xff) - ml - (fullR[o + dx - d].toInt() and 0xff) + mr) }
            sad[k] = sum; if (sum < best) { best = sum; bk = k }
        }
        if (bk <= 0 || bk >= 6) return coarse
        val a = sad[bk - 1]; val c = sad[bk + 1]; val den = a - 2 * best + c
        val fine = (c0 - 3 + bk) + (if (den > 0) (a - c) / (2f * den) else 0f)
        return fine / 2f
    }

    // One pass along a row: start index, step (+1 or -1), length.
    private fun path(start: Int, step: Int, len: Int) {
        var minPrev = 0
        for (k in 0 until len) {
            val o = (start + k * step) * D
            var minCur = Int.MAX_VALUE
            if (k == 0) { for (d in 0 until D) { val c = cost[o + d].toInt(); cur[d + 1] = c; if (c < minCur) minCur = c } }
            else for (d in 0 until D) {
                var m = prev[d + 1]
                val a = prev[d] + P1; if (a < m) m = a
                val b = prev[d + 2] + P1; if (b < m) m = b
                val j = minPrev + P2; if (j < m) m = j
                val v = cost[o + d] + m - minPrev; cur[d + 1] = v; if (v < minCur) minCur = v
            }
            cur[0] = BIG; cur[D + 1] = BIG
            for (d in 0 until D) { sum[o + d] = (sum[o + d] + cur[d + 1]).toShort(); prev[d + 1] = cur[d + 1] }
            prev[0] = BIG; prev[D + 1] = BIG; minPrev = minCur
        }
    }
    private val colMin = IntArray(W)
    private fun columns(down: Boolean) {
        for (k in 0 until H) {
            val y = if (down) k else H - 1 - k
            for (x in 0 until W) {
                val o = (y * W + x) * D; val p = x * (D + 2); var minCur = Int.MAX_VALUE
                if (k == 0) { for (d in 0 until D) { val c = cost[o + d].toInt(); cur[d + 1] = c; if (c < minCur) minCur = c } }
                else { val mp = colMin[x]; for (d in 0 until D) {
                    var m = colPrev[p + d + 1]
                    val a = colPrev[p + d] + P1; if (a < m) m = a
                    val b = colPrev[p + d + 2] + P1; if (b < m) m = b
                    val j = mp + P2; if (j < m) m = j
                    val v = cost[o + d] + m - mp; cur[d + 1] = v; if (v < minCur) minCur = v
                } }
                colPrev[p] = BIG; colPrev[p + D + 1] = BIG
                for (d in 0 until D) { sum[o + d] = (sum[o + d] + cur[d + 1]).toShort(); colPrev[p + d + 1] = cur[d + 1] }
                colMin[x] = minCur
            }
        }
    }

    private val label = IntArray(W * H); private val stack = IntArray(W * H)
    /** Small islands of answers that differ from all around them are noise: removed. */
    private fun speckles() {
        java.util.Arrays.fill(label, 0); var next = 0
        for (s in 0 until W * H) {
            if (disparity[s] == 0f || label[s] != 0) continue
            next++; var top = 0; stack[top++] = s; label[s] = next; var size = 0; val begin = top - 1
            var head = begin
            while (head < top) {
                val i = stack[head++]; size++; val d = disparity[i]; val x = i % W
                if (x > 0) grow(i - 1, d, next).let { if (it) stack[top++] = i - 1 }
                if (x < W - 1) grow(i + 1, d, next).let { if (it) stack[top++] = i + 1 }
                if (i >= W) grow(i - W, d, next).let { if (it) stack[top++] = i - W }
                if (i < W * H - W) grow(i + W, d, next).let { if (it) stack[top++] = i + W }
            }
            if (size < SPECKLE) for (k in begin until top) disparity[stack[k]] = 0f
        }
    }
    private fun grow(j: Int, d: Float, l: Int): Boolean {
        if (label[j] != 0 || disparity[j] == 0f || kotlin.math.abs(disparity[j] - d) > 1.5f) return false
        label[j] = l; return true
    }

    /**
     * How far the newest depth picture saw along the line from camera 0 to the point [p] (camera 0's frame): metres
     * to what it saw there, NaN where it has no answer for that pixel, and -1 when the point is outside the picture
     * or behind the camera. For asking whether something that should stand at [p] was seen, or seen through.
     */
    fun rangeToward(p: DoubleArray): Double {
        // into the rectified camera: the transpose of the turn point() makes
        val x = rect[0] * p[0] + rect[3] * p[1] + rect[6] * p[2]; val y = rect[1] * p[0] + rect[4] * p[1] + rect[7] * p[2]; val z = rect[2] * p[0] + rect[5] * p[1] + rect[8] * p[2]
        if (z < 0.2) return -1.0
        val u = Math.round(FOCAL * x / z + CX).toInt(); val v = Math.round(FOCAL * y / z + CY).toInt()
        if (u < 3 || v < 3 || u >= W - 3 || v >= H - 3) return -1.0
        val d = disparity[v * W + u]; if (d <= 0f) return Double.NaN
        val zz = FOCAL * baseline / d
        return zz * Math.sqrt(x * x + y * y + z * z) / z
    }

    /** The place in camera 0's frame (x right, y down, z forward) of rectified pixel ([u], [v]) with [disp]; false when there is no answer. */
    fun point(u: Int, v: Int, disp: Float, out: DoubleArray): Boolean {
        if (disp <= 0f) return false
        val z = FOCAL * baseline / disp; val x = (u - CX) / FOCAL * z; val y = (v - CY) / FOCAL * z
        out[0] = rect[0] * x + rect[1] * y + rect[2] * z; out[1] = rect[3] * x + rect[4] * y + rect[5] * z; out[2] = rect[6] * x + rect[7] * y + rect[8] * z
        return true
    }

    companion object {
        const val W = 240; const val H = 320
        /** The full-size rectified pictures. */
        const val FW = 480; const val FH = 640
        const val D = 64
        const val FOCAL = 130.0; const val CX = 119.5; const val CY = 159.5
        /** Nearer than disparity 63 (about 0.28 m) cannot be told; further than disparity 3.5 (about 5 m) is too coarse to use. */
        const val MIN_DISP = 4
        private const val P1 = 3; private const val P2 = 22
        private const val BAD: Byte = 24
        private const val BIG = 1 shl 20
        private const val UNIQUE = 92
        private const val MAX_OWN_COST = 9
        private const val SPECKLE = 60
    }
}
