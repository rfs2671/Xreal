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

/** A pinch as on/off from the distance between thumb tip and index tip, with hysteresis so it does not chatter. */
class PinchState(private val pressM: Float = 0.018f, private val releaseM: Float = 0.032f) {
    var pinching = false; private set
    /** Returns true on the moment the pinch starts. */
    fun update(gapM: Float): Boolean {
        val was = pinching
        pinching = if (was) gapM < releaseM else gapM < pressM
        return pinching && !was
    }
    fun lost() { pinching = false }
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
    fun gap(side: Int, m: Float) {
        if (m < minGapM[side]) minGapM[side] = m
        if (m < 0.02f) gapUnder[side][0]++; if (m < 0.03f) gapUnder[side][1]++; if (m < 0.05f) gapUnder[side][2]++
    }
    var stereoMatchesSum = 0L; var landmarksSum = 0L; var vioSamples = 0L
    var maxHeadTravelM = 0.0
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
        put("left_hand_frames", handFrames[0]); put("right_hand_frames", handFrames[1])
        put("left_pinches", pinches[0]); put("right_pinches", pinches[1])
        put("recenters", recenters)
        put("touchpad_selections", pointerSelections)
        for ((side, name) in listOf(0 to "left", 1 to "right")) if (handFrames[side] > 0) {
            put(name + "_min_gap_mm", Math.round(minGapM[side] * 1000.0))
            put(name + "_frames_gap_under_20_30_50_mm", "" + gapUnder[side][0] + "/" + gapUnder[side][1] + "/" + gapUnder[side][2])
        }
        put("mean_stereo_matches", if (vioSamples > 0) stereoMatchesSum.toDouble() / vioSamples else 0.0)
        put("mean_landmarks", if (vioSamples > 0) landmarksSum.toDouble() / vioSamples else 0.0)
    }
}
