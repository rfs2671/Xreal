// Ultra XR: the one place where tracked hands become pinches and rays, for the test scenes and the desktop alike. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

import kotlin.math.sqrt

/**
 * The tracker's hands as things to point and pinch with: where the fingers pinch, whether they do, and where the arm
 * points. One of these is used by the test scenes (the ball) and by the desktop, so that a hand is read the same way
 * everywhere. Until 24 November 2026 the desktop had its own copy with a shorter freshness (0.3 s), a ray from the palm
 * and a pinch history wiped a quarter of a second after the tracker lost the hand; worn, the ball took the pinch and
 * the desktop "didn't persist outside camera, pinch is really bad".
 *
 * A result is used while it is less than [FRESH_NS] old. The pinch rule ([PinchState]) runs on each new result of the
 * tracker, not on each drawn frame, and keeps its history while a hand is out of sight. The ray runs from the shoulder
 * through the middle of the two fingertips ([ArmRay]).
 */
class HandReader(pressM: Float = PinchState.PRESS_M, releaseM: Float = PinchState.RELEASE_M, anticipate: Boolean = true, private val predict: Boolean = true,
                 /** Where the ray starts, for measuring: 0 the middle of the fingertips (shipped), 1 the index tip, 2 the knuckles (index and middle), 3 half way between 0 and 2. */
                 private val rayFrom: Int = 0, eye: Double = 0.0) {
    private val pinch = arrayOf(PinchState(pressM, releaseM, anticipate = anticipate), PinchState(pressM, releaseM, anticipate = anticipate))
    private val predictor = arrayOf(HandPredictor(3, Hand.JOINTS), HandPredictor(3, Hand.JOINTS)); private val shift = DoubleArray(3)
    private val arm = arrayOf(ArmRay(false, eye = eye), ArmRay(true, eye = eye))
    private val seenNs = LongArray(2)
    /** New tracker results read and pinches begun, for the log. */
    var results = 0L; private set
    var pinches = 0L; private set

    /** The hands fresh at [nowNs], with the head at [headP] turned by [headQ]; no ray when [rayWanted] is false (no head pose). */
    fun read(hands: List<Hand>, nowNs: Long, headP: DoubleArray, headQ: DoubleArray, rayWanted: Boolean = true): List<HandProbe> {
        val out = ArrayList<HandProbe>(2)
        for (h in hands) {
            if (nowNs - h.timestampNs > FRESH_NS) continue
            val side = if (h.isRight) 1 else 0
            val thumb = h.joint(Hand.THUMB_TIP); val index = h.joint(Hand.INDEX_TIP)
            if (h.timestampNs != seenNs[side]) {
                seenNs[side] = h.timestampNs; results++
                var finger = 0.0; for (j in Hand.INDEX_MCP until Hand.INDEX_TIP) finger += dist(h.joint(j), h.joint(j + 1))
                if (pinch[side].update(dist(thumb, index).toFloat(), finger.toFloat(), h.timestampNs, nowNs - h.timestampNs)) pinches++
                if (predict) predictor[side].add(h.timestampNs, h.joints)
            }
            // the hand where it will be when this frame is shown: its own motion over the time since its picture and a frame
            val ahead = if (predict) predictor[side].shift((nowNs - h.timestampNs) * 1e-9 + DISPLAY_LEAD_S, shift) else false
            val pp = DoubleArray(3) { (thumb[it] + index[it]) / 2 + (if (ahead) shift[it] else 0.0) }
            val from = when (rayFrom) {
                1 -> DoubleArray(3) { index[it] + (if (ahead) shift[it] else 0.0) }
                2 -> { val a = h.joint(Hand.INDEX_MCP); val b = h.joint(Hand.MIDDLE_MCP); DoubleArray(3) { (a[it] + b[it]) / 2 + (if (ahead) shift[it] else 0.0) } }
                3 -> { val a = h.joint(Hand.INDEX_MCP); val b = h.joint(Hand.MIDDLE_MCP); DoubleArray(3) { ((a[it] + b[it]) / 2 + (if (ahead) shift[it] else 0.0) + pp[it]) / 2 } }
                else -> pp
            }
            val dir = if (!rayWanted) null else arm[side].aim(headP, headQ, from, pinch[side].pinching, nowNs)
            out += HandProbe(h.isRight, pp, pinch[side].pinching, from, dir, h.timestampNs)
        }
        return out
    }

    companion object {
        const val FRESH_NS = 400_000_000L
        /** From the application's frame to the light on the glasses, about. */
        const val DISPLAY_LEAD_S = 0.016
        fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    }
}

/**
 * A hand tracked outside what the display shows is marked at the picture's edge on its side. Measured on the worn
 * recording of 23 November 2026: three quarters of the places at which a hand was tracked were outside the display's
 * picture, and nothing told the wearer so; on the desktop, which showed only the fingertips, the hand seemed to
 * vanish there ("doesn't persist outside camera").
 */
object HandEdge {
    /**
     * For [v], a point in the head's frame (x right, y up, forward is -z): null while it is well inside the picture
     * ([tanH], [tanV] the tangents of its half angles), otherwise the place for its mark, 1 m ahead at the edge.
     */
    fun mark(v: DoubleArray, tanH: Double, tanV: Double): DoubleArray? {
        val f = -v[2]
        var x = if (f > 0.05) v[0] / f else Math.signum(v[0]) * 9.0
        var y = if (f > 0.05) v[1] / f else Math.signum(v[1]) * 9.0
        if (f > 0.05 && Math.abs(x) < tanH * 0.92 && Math.abs(y) < tanV * 0.92) return null
        x = x.coerceIn(-tanH * 0.86, tanH * 0.86); y = y.coerceIn(-tanV * 0.80, tanV * 0.80)
        return doubleArrayOf(x, y, -1.0)
    }
}
