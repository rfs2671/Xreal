package dev.ultraxr.core

/**
 * Orientation of the Ultra's camera images for tracking. XR Gate's verified profile
 * ("unified_480_ccw90") feeds both eyes as upright 480x640 images:
 * left = rotate 90 counter-clockwise, right = rotate 90 counter-clockwise then flip both
 * axes (which is rotate 90 clockwise). The calibration files assume exactly this.
 */
object EyeImage {
    const val SRC_W = 640
    const val SRC_H = 480
    const val OUT_W = 480
    const val OUT_H = 640
    const val OUT_BYTES = OUT_W * OUT_H

    /** dst(r, c) = src(c, W-1-r) */
    fun rotateCcw90(src: ByteArray, dst: ByteArray) {
        var o = 0
        for (r in 0 until OUT_H) {
            val sx = SRC_W - 1 - r
            var si = sx
            for (c in 0 until OUT_W) { dst[o++] = src[si]; si += SRC_W }
        }
    }

    /** dst(r, c) = src(H-1-c, r) */
    fun rotateCw90(src: ByteArray, dst: ByteArray) {
        var o = 0
        for (r in 0 until OUT_H) {
            var si = (SRC_H - 1) * SRC_W + r
            for (c in 0 until OUT_W) { dst[o++] = src[si]; si -= SRC_W }
        }
    }

    fun transform(src: ByteArray, isRight: Boolean, dst: ByteArray) = if (isRight) rotateCw90(src, dst) else rotateCcw90(src, dst)
}

/**
 * Pairs alternating left/right frames by their shared device timestamp.
 * Port of XR Gate's XrealStereoTimestampPairer.
 */
class StereoPairer {
    enum class Result { DISCARD, PENDING, COMPLETE }
    private var pendingT = 0L
    private var publishedT = 0L
    private var pendingCounter = 0
    private var eyes = 0
    var dropped = 0L; private set

    fun push(t62Ns: Long, pairCounter: Int, isRight: Boolean): Result {
        if (t62Ns <= 0 || t62Ns <= publishedT) { dropped++; return Result.DISCARD }
        if (eyes != 0 && t62Ns < pendingT) { dropped++; return Result.DISCARD }
        if (eyes == 0 || t62Ns > pendingT) {
            if (eyes != 0) dropped++
            pendingT = t62Ns; pendingCounter = pairCounter; eyes = 0
        }
        val bit = if (isRight) 2 else 1
        if (pairCounter != pendingCounter || (eyes and bit) != 0) { dropped++; return Result.DISCARD }
        eyes = eyes or bit
        if (eyes != 3) return Result.PENDING
        publishedT = pendingT; eyes = 0
        return Result.COMPLETE
    }

    fun reset() { pendingT = 0; publishedT = 0; eyes = 0 }
}
