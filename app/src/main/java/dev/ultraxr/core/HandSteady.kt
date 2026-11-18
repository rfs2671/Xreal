package dev.ultraxr.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Steadies where a tracked hand is, so the pointing ray an application draws from it can be aimed.
 *
 * Two things make a hand's ray hard to use, and neither is the wearer's fault:
 *
 *  - Jitter. A tracked hand held still is reported a few millimetres apart from frame to frame; a ray leaving it
 *    multiplies that by the distance to whatever it points at. A "one euro" filter (Casiez, Roussel and Vogel, 2012)
 *    smooths strongly while the hand is slow and hardly at all while it moves fast, so a still hand gives a still
 *    ray and a moving hand is not left behind.
 *  - The pinch moving the pointer. Bringing thumb and finger together moves the hand a little and often makes the
 *    tracker jump, so the ray leaves the thing aimed at just as the pinch lands. For a short time from the start of a
 *    pinch the hand is therefore reported where it was a moment before the pinch, unless it has clearly been
 *    carried away (a drag), and is then let go smoothly.
 *
 * It returns an offset to add to every joint of the hand, so the hand keeps its shape and only its place is steadied.
 * The constants are reasoned defaults, not yet measured on this hardware: the session record's hand numbers are what
 * they will be tuned on.
 */
class HandSteady(private val minCutoffHz: Double = 1.2, private val beta: Double = 6.0, private val derivCutoffHz: Double = 1.0) {
    private var lastNs = 0L
    private val x = DoubleArray(3); private val dx = DoubleArray(3)
    private var have = false
    // a short history of steadied positions, to find where the hand was just before a pinch
    private val histNs = LongArray(HIST); private val hist = Array(HIST) { DoubleArray(3) }; private var histAt = 0; private var histN = 0
    private var lockUntilNs = 0L; private var releaseFromNs = 0L
    private val lock = DoubleArray(3)

    fun reset() { have = false; histN = 0; lockUntilNs = 0L; releaseFromNs = 0L }

    /** [palm] is the tracked palm position (metres) at [tNs]. Writes the offset to add to the hand's joints into [out]. */
    fun offset(tNs: Long, palm: DoubleArray, pinchBegan: Boolean, pinching: Boolean, out: DoubleArray) {
        val dt = if (have) (tNs - lastNs) * 1e-9 else 0.0
        if (!have || dt <= 0.0 || dt > 0.5) {         // first result, or the hand was away: start from where it is
            for (i in 0..2) { x[i] = palm[i]; dx[i] = 0.0 }
            have = true; histN = 0; lockUntilNs = 0L; releaseFromNs = 0L
        } else {
            var speed = 0.0
            for (i in 0..2) {
                val raw = (palm[i] - x[i]) / dt
                dx[i] += alpha(derivCutoffHz, dt) * (raw - dx[i]); speed += dx[i] * dx[i]
            }
            val a = alpha(minCutoffHz + beta * sqrt(speed), dt)
            for (i in 0..2) x[i] += a * (palm[i] - x[i])
        }
        lastNs = tNs
        histNs[histAt] = tNs; for (i in 0..2) hist[histAt][i] = x[i]; histAt = (histAt + 1) % HIST; if (histN < HIST) histN++

        if (pinchBegan) {
            // where the steadied hand was LOOK_BACK ago: before the fingers started closing
            var best = -1; var bestErr = Long.MAX_VALUE
            for (k in 0 until histN) { val e = abs(tNs - LOOK_BACK_NS - histNs[k]); if (e < bestErr) { bestErr = e; best = k } }
            if (best >= 0) { for (i in 0..2) lock[i] = hist[best][i]; lockUntilNs = tNs + LOCK_NS; releaseFromNs = 0L }
        }
        var w = 0.0                                    // how much of the locked place is still used
        if (lockUntilNs != 0L) {
            val away = sqrt((x[0] - lock[0]) * (x[0] - lock[0]) + (x[1] - lock[1]) * (x[1] - lock[1]) + (x[2] - lock[2]) * (x[2] - lock[2]))
            if (releaseFromNs == 0L && (tNs >= lockUntilNs || away > DRAG_M || !pinching)) releaseFromNs = tNs
            w = if (releaseFromNs == 0L) 1.0 else 1.0 - ((tNs - releaseFromNs).toDouble() / RELEASE_NS).coerceIn(0.0, 1.0)
            if (w <= 0.0) { lockUntilNs = 0L; releaseFromNs = 0L }
        }
        for (i in 0..2) out[i] = (w * lock[i] + (1 - w) * x[i]) - palm[i]
    }

    private fun alpha(cutoffHz: Double, dt: Double): Double { val tau = 1.0 / (2 * PI * cutoffHz); return 1.0 / (1.0 + tau / dt) }

    companion object {
        private const val HIST = 16
        const val LOOK_BACK_NS = 120_000_000L
        const val LOCK_NS = 250_000_000L
        const val RELEASE_NS = 120_000_000L
        /** Carried this far from where the pinch began, the hand is dragging something: follow it. */
        const val DRAG_M = 0.03
    }
}

/**
 * Steadies which way a tracked hand is turned. Measured on the Pixel from a recording (tools/tracking/hand_steady.py):
 * a hand held still is reported turned 3 to 4.5 degrees about its average, which moves the far end of a two metre ray
 * by 10 to 15 cm, against under 1 cm from the wobble of the hand's place. So the turn is what makes a ray hard to aim.
 *
 * The same "one euro" filter as [HandSteady], on the palm's rotation: the cutoff rises with how fast the hand is
 * really turning, so a deliberate turn is followed. [correction] returns the rotation to apply to the whole hand about
 * its palm, so the hand keeps its shape.
 */
class HandTurnSteady(private val minCutoffHz: Double = 1.5, private val beta: Double = 1.0, private val derivCutoffHz: Double = 1.0) {
    private var lastNs = 0L
    private var q = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    private var speed = 0.0
    private var have = false

    fun reset() { have = false }

    /** [palmQ] is the tracked palm rotation (x, y, z, w) at [tNs]. Returns the rotation that turns the tracked hand into the steadied one. */
    fun correction(tNs: Long, palmQ: DoubleArray): DoubleArray {
        val raw = Q.normalize(palmQ)
        val dt = if (have) (tNs - lastNs) * 1e-9 else 0.0
        if (!have || dt <= 0.0 || dt > 0.5) { q = raw; speed = 0.0; have = true }
        else {
            val dot = abs(q[0] * raw[0] + q[1] * raw[1] + q[2] * raw[2] + q[3] * raw[3]).coerceAtMost(1.0)
            val rate = 2 * kotlin.math.acos(dot) / dt                    // radians a second between the steadied and the tracked rotation
            speed += alpha(derivCutoffHz, dt) * (rate - speed)
            q = Q.slerp(q, raw, alpha(minCutoffHz + beta * speed, dt))
        }
        lastNs = tNs
        return Q.normalize(Q.mul(q, Q.conj(raw)))
    }

    private fun alpha(cutoffHz: Double, dt: Double): Double { val tau = 1.0 / (2 * PI * cutoffHz); return 1.0 / (1.0 + tau / dt) }
}
