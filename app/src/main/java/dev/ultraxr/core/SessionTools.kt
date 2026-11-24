package dev.ultraxr.core

import org.json.JSONObject

/**
 * Recentre by gesture: pinch with both hands at once and hold. In a game the phone shows the game's
 * own screen, so there is no button to press; this works from inside any game.
 *
 * Fires once when both hands have pinched without a break for [holdNs], and not again until at
 * least one hand has let go.
 */
class RecenterGesture(private val holdNs: Long = 1_500_000_000L) {
    private var sinceNs = 0L
    private var holding = false
    private var fired = false
    /** 0..1 while the hold is building, for feedback. */
    var progress = 0.0; private set

    fun update(nowNs: Long, leftPinching: Boolean, rightPinching: Boolean): Boolean {
        if (!(leftPinching && rightPinching)) { holding = false; fired = false; progress = 0.0; return false }
        if (!holding) { holding = true; sinceNs = nowNs }
        progress = ((nowNs - sinceNs).toDouble() / holdNs).coerceIn(0.0, 1.0)
        if (!fired && nowNs - sinceNs >= holdNs) { fired = true; return true }
        return false
    }
}

/**
 * A pinch as on/off from the distance between thumb tip and index tip.
 *
 *  - Hysteresis: it starts under one distance and ends only above a larger one, so it does not chatter.
 *  - Relative to the hand: when the length of the index finger is given, the fingertips also count as together
 *    under [pressRatio] of that length. A tracker that gets a hand's distance from the cameras slightly wrong gets
 *    its size wrong by the same factor, so a distance in finger lengths survives that where one in millimetres does not.
 *  - Two results in a row: with [frames] = 2 a single stray result neither starts nor ends a pinch.
 *  - Brief loss: the tracker often loses a hand for a frame or two exactly when the fingers touch, because they hide
 *    each other. [lost] with a time keeps the pinch for [holdNs]; only a longer loss ends it.
 *  - The closing movement ([stroke]): seen from behind, the fingertips of a pinching hand hide each other and the
 *    tracker leaves them apart. In the owner's recording of 19 November 2026 his right hand's pinches measured 30 to
 *    65 mm at their closest (the left hand's 15 to 37 mm), from 80 to 125 mm open: a distance rule caught 1 of 11.
 *    What every one of them has is the movement: the gap falls by 30 mm or more within twelve results, to under 0.62
 *    of the widest it was in the last sixty, and then stays there (three results within 12 mm of each other; a
 *    single stray low result is not a pinch). That starts a pinch too, and such a pinch ends when the gap has come
 *    back up from its smallest by 20 mm or by three tenths of the way to the open width, whichever is more.
 *    Replayed on the S21 Ultra (25 results a second) the same recording then gives 15 right-hand pinches two
 *    seconds apart, as they were made (6 by distance alone), and 9 of the left hand's (7).
 */
class PinchState(private val pressM: Float = PRESS_M, private val releaseM: Float = RELEASE_M,
                 private val pressRatio: Float = 0.46f, private val releaseRatio: Float = 0.65f,
                 private val frames: Int = 1, private val holdNs: Long = 250_000_000L, private val stroke: Boolean = true, private val anticipate: Boolean = true) {
    var pinching = false; private set
    private var run = 0
    private var lostSinceNs = 0L
    private val recent = FloatArray(STROKE_WINDOW); private var seen = 0
    private var smallest = 0f; private var byDistance = false

    /** One tracking result. [fingerM] is the index finger's length, 0 when not known. Returns true on the moment the pinch starts. */
    fun update(gapM: Float, fingerM: Float = 0f): Boolean = step(gapM, fingerM, false)

    private val nT = LongArray(3); private val nV = FloatArray(3); private var nSeen = 0; private var earlyNs = 0L

    /**
     * One tracking result with its time ([tNs], the picture's) and how old it is as it is used ([ageNs]).
     *
     *  - No pinch begins on a result older than [STALE_NS]: a click is never started from stale tracking. One that is
     *    under way goes on and can end.
     *  - A pinch can begin on the way in, before the fingertips reach the press distance, when the gap, in finger
     *    lengths, has fallen steadily over two results by 0.30 or more, is under 0.60 and would reach 0.40 within
     *    [LEAD_S]. Measured on the owner's recordings of 23 November 2026 (18 pinches whose gap fell under 22 mm,
     *    `C:\uvio\handup\pinch_var.py`): the pinch began a median 36 ms after the gap crossed 40 mm on its way down,
     *    against 56 ms; onsets 119 against 118, brief ones (under 120 ms) 1 against 3, onsets with no gap under 35 mm
     *    near them 67 against 66. One that contact does not confirm within [CONFIRM_NS] (the gap still over 0.60 of a
     *    finger and over the press distance) is taken back. A faster release on quick opening was tried the same way
     *    and not taken: it made 6 more onsets and 5 more brief ones.
     */
    fun update(gapM: Float, fingerM: Float, tNs: Long, ageNs: Long): Boolean {
        val stale = ageNs > STALE_NS
        val scale = if (fingerM > 0.03f) fingerM else 0.071f; val nrm = gapM / scale
        nT[nSeen % 3] = tNs; nV[nSeen % 3] = nrm; nSeen++
        val was = pinching
        var began = step(gapM, fingerM, stale)
        if (!began && !pinching && anticipate && !stale && nSeen >= 3) {
            val i0 = (nSeen - 1) % 3; val i1 = (nSeen - 2) % 3; val i2 = (nSeen - 3) % 3
            val n0 = nV[i0]; val n1 = nV[i1]; val n2 = nV[i2]; val dt = (nT[i0] - nT[i2]) * 1e-9
            val plausible = scale in 0.045f..0.11f && gapM < 0.20f
            if (plausible && dt > 0.0 && dt < 0.2 && n0 < 0.60f && n2 - n0 >= 0.30f && n2 > n1 && n1 > n0 && n0 + (n0 - n2) / dt.toFloat() * LEAD_S < 0.40f) {
                pinching = true; smallest = gapM; byDistance = false; earlyNs = tNs; began = true; run = 0
            }
        }
        if (pinching && earlyNs != 0L && tNs - earlyNs > CONFIRM_NS) { earlyNs = 0L; if (nrm > 0.60f && gapM >= pressM) pinching = false }
        if (!pinching) earlyNs = 0L
        return began && !was
    }

    private fun step(gapM: Float, fingerM: Float, blockStart: Boolean): Boolean {
        lostSinceNs = 0L
        val scaled = fingerM > 0.03f
        val near = gapM < pressM || (scaled && gapM < pressRatio * fingerM)
        val far = gapM > releaseM && (!scaled || gapM > releaseRatio * fingerM)
        // the widest of the last sixty results; the last three (this one included), which must sit together; and the
        // widest of the twelve before those, which they must have come down from
        recent[seen % STROKE_WINDOW] = gapM; seen++
        var open = 0f; var before = 0f; var lo = gapM; var hi = gapM; val n = minOf(seen, STROKE_WINDOW)
        for (i in 0 until n) {
            val g = recent[(seen - 1 - i) % STROKE_WINDOW]; if (g > open) open = g
            if (i < STROKE_STAY) { if (g < lo) lo = g; if (g > hi) hi = g } else if (i < STROKE_STAY + STROKE_LOOK && g > before) before = g
        }
        val closing = stroke && n > STROKE_STAY && hi < STROKE_RATIO * open && hi < STROKE_MAX_M && hi - lo <= STROKE_STEADY_M && before - gapM >= STROKE_DROP_M
        val was = pinching
        val together = near || closing
        val apart = if (!was) false else {
            if (near) byDistance = true
            if (gapM < smallest) smallest = gapM
            if (byDistance) far else gapM > smallest + maxOf(STROKE_BACK_M, 0.3f * (open - smallest))
        }
        val wants = if (was) !apart else together
        if (wants == was || (!was && blockStart)) { run = 0; return false }
        if (++run < frames) return false
        run = 0; pinching = wants
        if (pinching) { smallest = gapM; byDistance = near }
        return pinching && !was
    }

    companion object {
        /**
         * Measured, not chosen: in the owner's recording of 17 November 2026 (five deliberate pinches of the right hand,
         * tracked at 15 results a second) the fingertips came to 23, 17, 24, 31 and 23 mm apart as the tracker
         * measures them, and an open hand sat at 50 mm and more. The first rule (18 mm, two results in a row) caught
         * none of the five. Under 33 mm to start and over 46 mm to end, on single results, catches four or five of
         * the five and both of the left hand's two. In finger lengths (his index finger: 71 mm) that is 0.46 and 0.65.
         */
        const val STALE_NS = 250_000_000L; const val CONFIRM_NS = 120_000_000L; const val LEAD_S = 0.045f
        const val PRESS_M = 0.033f
        const val RELEASE_M = 0.046f
        const val STROKE_WINDOW = 60; const val STROKE_LOOK = 12; const val STROKE_STAY = 3; const val STROKE_STEADY_M = 0.012f
        const val STROKE_RATIO = 0.62f; const val STROKE_DROP_M = 0.030f; const val STROKE_MAX_M = 0.070f; const val STROKE_BACK_M = 0.020f
    }

    /** The hand was not found. Without a time, or after [holdNs] without the hand, the pinch ends. Returns true while it is kept. */
    fun lost(nowNs: Long = 0L): Boolean {
        if (nowNs == 0L || !pinching) { pinching = false; run = 0; lostSinceNs = 0L; seen = 0; nSeen = 0; earlyNs = 0L; return false }
        if (lostSinceNs == 0L) lostSinceNs = nowNs
        if (nowNs - lostSinceNs > holdNs) { pinching = false; run = 0; lostSinceNs = 0L; seen = 0; nSeen = 0; earlyNs = 0L; return false }
        return true
    }
}

/**
 * What happened in one OpenXR or WebXR session, counted as it runs and written out when it ends, so
 * that a short test on the glasses leaves a record to read afterwards instead of something to
 * describe from memory. Counts only what the app itself measured.
 */
class SessionJournal(val client: String, val display: String, val handsAs: String, private val startMs: Long) {
    var headSamples = 0L; var positionTracked = 0L
    val handFrames = longArrayOf(0, 0); val pinches = intArrayOf(0, 0)
    var recenters = 0
    /** Selections made with the phone's touchpad (taps and presses). */
    var pointerSelections = 0
    /** Thumb-tip to index-tip distance while each hand was tracked: the smallest seen, and how often it was under 2, 3 and 5 cm. */
    val minGapM = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
    val gapUnder = arrayOf(longArrayOf(0, 0, 0), longArrayOf(0, 0, 0))
    /** Frames in which a hand that had just been lost was kept where it was. */
    val heldFrames = longArrayOf(0, 0)
    private val fingerSum = doubleArrayOf(0.0, 0.0); private val fingerN = longArrayOf(0, 0)
    private val minRatio = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
    private val lastGap = floatArrayOf(0f, 0f)
    /** The index finger's measured length for this frame (call after [gap]). */
    fun finger(side: Int, m: Float) {
        if (m <= 0.03f) return
        fingerSum[side] += m; fingerN[side]++
        val r = lastGap[side] / m; if (r < minRatio[side]) minRatio[side] = r
    }
    fun gap(side: Int, m: Float) {
        if (m < minGapM[side]) minGapM[side] = m
        lastGap[side] = m
        if (m < 0.02f) gapUnder[side][0]++; if (m < 0.03f) gapUnder[side][1]++; if (m < 0.05f) gapUnder[side][2]++
    }
    var stereoMatchesSum = 0L; var landmarksSum = 0L; var vioSamples = 0L
    var maxHeadTravelM = 0.0
    /** The floor given to the application: whether the cameras had found it by the end, how far below the world's origin, and how often it was only the height setting. */
    var floorMeasured = false; private set
    var floorBelowOriginM = Double.NaN; private set
    private var floorSamples = 0L; private var floorMeasuredSamples = 0L
    fun floor(measured: Boolean, belowOriginM: Double) { floorMeasured = measured; floorBelowOriginM = belowOriginM; floorSamples++; if (measured) floorMeasuredSamples++ }
    private var origin: DoubleArray? = null

    fun head(positionIsTracked: Boolean, p: DoubleArray) {
        headSamples++
        if (positionIsTracked) {
            positionTracked++
            val o = origin ?: p.copyOf().also { origin = it }
            val d = Math.sqrt((p[0] - o[0]) * (p[0] - o[0]) + (p[1] - o[1]) * (p[1] - o[1]) + (p[2] - o[2]) * (p[2] - o[2]))
            if (d > maxHeadTravelM) maxHeadTravelM = d
        }
    }
    fun recentred() { recenters++; origin = null }

    fun toJson(endMs: Long): JSONObject = JSONObject().apply {
        put("client", client); put("display", display); put("hands_as", handsAs)
        put("started_ms", startMs); put("seconds", (endMs - startMs) / 1000.0)
        put("head_samples", headSamples)
        put("position_tracked_fraction", if (headSamples > 0) positionTracked.toDouble() / headSamples else 0.0)
        put("max_head_travel_m", maxHeadTravelM)
        if (floorSamples > 0) { put("floor_measured", floorMeasured); put("floor_below_origin_m", floorBelowOriginM); put("floor_measured_fraction", floorMeasuredSamples.toDouble() / floorSamples) }
        put("left_hand_frames", handFrames[0]); put("right_hand_frames", handFrames[1])
        put("left_pinches", pinches[0]); put("right_pinches", pinches[1])
        put("recenters", recenters)
        put("touchpad_selections", pointerSelections)
        for ((side, name) in listOf(0 to "left", 1 to "right")) if (handFrames[side] > 0) {
            put(name + "_min_gap_mm", Math.round(minGapM[side] * 1000.0))
            put(name + "_frames_gap_under_20_30_50_mm", "" + gapUnder[side][0] + "/" + gapUnder[side][1] + "/" + gapUnder[side][2])
            if (fingerN[side] > 0) { put(name + "_index_finger_mm", Math.round(fingerSum[side] / fingerN[side] * 1000)); put(name + "_min_gap_in_finger_lengths", Math.round(minRatio[side] * 100) / 100.0) }
            put(name + "_frames_held_through_loss", heldFrames[side])
        }
        put("mean_stereo_matches", if (vioSamples > 0) stereoMatchesSum.toDouble() / vioSamples else 0.0)
        put("mean_landmarks", if (vioSamples > 0) landmarksSum.toDouble() / vioSamples else 0.0)
    }
}
