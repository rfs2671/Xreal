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
 * Pairs left and right frames into stereo pairs. The two eyes of one exposure normally carry
 * the same pair counter and the same device timestamp, but neither is required to be exactly
 * equal: a pair is accepted when the counters match or the timestamps are within
 * [TOLERANCE_NS]. Every frame that does not end up in a pair is counted with the reason.
 * (XR Gate's pairer demands exact equality of both; that is kept as the common case.)
 */
class StereoPairer {
    enum class Result { DISCARD, PENDING, COMPLETE }
    private val pendT = LongArray(2)
    private val pendC = IntArray(2)
    private val have = BooleanArray(2)
    private var publishedT = 0L
    private val lastCounter = intArrayOf(-1, -1)

    /** Timestamp of the pair completed by the last COMPLETE result (the left eye's). */
    var pairTimeNs = 0L; private set
    @Volatile var complete = 0L; private set
    /** Frames older than or equal to an already published pair. */
    @Volatile var stale = 0L; private set
    /** Frames whose partner eye never arrived. */
    @Volatile var missingPartner = 0L; private set
    /** Pairs accepted on timestamp although the counters differed, and the reverse. */
    @Volatile var counterMismatchAccepted = 0L; private set
    @Volatile var timeMismatchAccepted = 0L; private set
    /** Frames missing from the stream, per eye, from gaps in the pair counter. */
    @Volatile var counterGapsLeft = 0L; private set
    @Volatile var counterGapsRight = 0L; private set
    @Volatile var maxPairDeltaNs = 0L; private set
    val dropped get() = stale + missingPartner

    fun push(t62Ns: Long, pairCounter: Int, isRight: Boolean): Result {
        val e = if (isRight) 1 else 0; val o = 1 - e
        val last = lastCounter[e]
        if (last >= 0) {
            val gap = (pairCounter - last) and 0xFFFF
            if (gap in 2..1000) { if (isRight) counterGapsRight += gap - 1 else counterGapsLeft += gap - 1 }
        }
        lastCounter[e] = pairCounter
        if (t62Ns <= 0 || (publishedT != 0L && t62Ns <= publishedT - TOLERANCE_NS)) { stale++; return Result.DISCARD }
        if (have[e]) missingPartner++ // the previous frame of this eye never got its partner
        pendT[e] = t62Ns; pendC[e] = pairCounter; have[e] = true
        if (!have[o]) return Result.PENDING
        val dt = Math.abs(pendT[o] - t62Ns)
        val sameCounter = pendC[o] == pairCounter
        val sameTime = dt <= TOLERANCE_NS
        // Counters alone are not trusted across a long gap (16-bit wrap or a restart).
        if ((sameCounter && dt <= MAX_COUNTER_PAIR_NS) || sameTime) {
            if (!sameCounter) counterMismatchAccepted++
            if (!sameTime) timeMismatchAccepted++
            if (dt > maxPairDeltaNs) maxPairDeltaNs = dt
            pairTimeNs = pendT[0]
            publishedT = maxOf(pendT[0], pendT[1])
            have[0] = false; have[1] = false
            complete++
            return Result.COMPLETE
        }
        // The other eye's pending frame belongs to a different exposure: keep whichever is newer.
        if (pendT[o] < t62Ns) { have[o] = false; missingPartner++; return Result.PENDING }
        have[e] = false; missingPartner++
        return Result.DISCARD
    }

    fun reset() { have[0] = false; have[1] = false; publishedT = 0; lastCounter[0] = -1; lastCounter[1] = -1 }

    companion object {
        /** Half of the shortest frame spacing seen from this camera is ~8 ms; 4 ms cannot join two exposures. */
        const val TOLERANCE_NS = 4_000_000L
        const val MAX_COUNTER_PAIR_NS = 25_000_000L
    }
}
