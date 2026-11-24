// Ultra XR: things to pinch, drag and press, for seeing whether hands and the world hold. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

import kotlin.math.sqrt

/** One hand as these things need it: where its pinch is, whether it pinches, and where it points ([rayDir] null: not known). */
class HandProbe(val isRight: Boolean, val pinchPoint: DoubleArray, val pinching: Boolean, val rayOrigin: DoubleArray, val rayDir: DoubleArray?, val tNs: Long = 0L)

private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

/** How far along the ray its nearest point to [p] is (metres, may be negative), and how far that point is from [p]. */
internal fun rayMiss(origin: DoubleArray, dir: DoubleArray, p: DoubleArray): DoubleArray {
    val t = (p[0] - origin[0]) * dir[0] + (p[1] - origin[1]) * dir[1] + (p[2] - origin[2]) * dir[2]
    val q = doubleArrayOf(origin[0] + dir[0] * t, origin[1] + dir[1] * t, origin[2] + dir[2] * t)
    return doubleArrayOf(t, dist(q, p))
}

/**
 * A ball that stays where it is in the room until a hand takes it.
 *
 * Asked for on 23 November 2026: "a ball to pinch and drag around", to see with one's own eyes that the world holds
 * still (the ball left alone must not move), that a hand is followed, and that pinching and dragging work, also
 * with the hand outside what the display shows.
 *
 * It is taken when a pinch begins with the fingers within [NEAR_M] of its surface, or with the hand's ray passing
 * within [FAR_M] of it. Taken by the fingers it keeps its place relative to them; taken by the ray it keeps its
 * distance along the ray. It is let go when the pinch ends, or [LOST_NS] after the hand was last seen, and stays
 * where it was let go.
 */
class GrabBall(start: DoubleArray, val radius: Double = 0.06) {
    val p = start.copyOf()
    /** Which hand holds it: 0 the left, 1 the right, -1 none. */
    var heldBy = -1; private set
    /** Which hand could take it now by pinching, -1 none. */
    var hover = -1; private set
    /** Held by the ray, not by the fingers. */
    var far = false; private set
    var grabs = 0; private set
    var draggedM = 0.0; private set
    private val off = DoubleArray(3); private var along = 0.0
    private val was = BooleanArray(2); private var seenNs = 0L

    fun place(at: DoubleArray) { for (i in 0..2) p[i] = at[i]; heldBy = -1; hover = -1 }

    fun update(hands: List<HandProbe>, nowNs: Long) {
        hover = -1
        var holder: HandProbe? = null
        for (h in hands) {
            val side = if (h.isRight) 1 else 0
            val near = dist(h.pinchPoint, p) < radius + NEAR_M
            val m = h.rayDir?.let { rayMiss(h.rayOrigin, it, p) }
            val aimed = m != null && m[0] > 0.15 && m[1] < radius + FAR_M
            if (heldBy == side) holder = h
            else if (heldBy < 0) {
                if ((near || aimed) && (hover < 0 || near)) hover = side
                if (h.pinching && !was[side] && (near || aimed)) {
                    heldBy = side; far = !near; grabs++; holder = h; hover = -1
                    val grip = if (near) h.pinchPoint else doubleArrayOf(h.rayOrigin[0] + h.rayDir!![0] * m!![0], h.rayOrigin[1] + h.rayDir[1] * m[0], h.rayOrigin[2] + h.rayDir[2] * m[0])
                    if (!near) along = m!![0]
                    for (i in 0..2) off[i] = p[i] - grip[i]
                }
            }
            was[side] = h.pinching
        }
        if (heldBy < 0) return
        val h = holder
        if (h == null) { if (nowNs - seenNs > LOST_NS) heldBy = -1; return }
        seenNs = nowNs
        if (!h.pinching) { heldBy = -1; return }
        val d = h.rayDir
        val grip = if (!far) h.pinchPoint else if (d != null) doubleArrayOf(h.rayOrigin[0] + d[0] * along, h.rayOrigin[1] + d[1] * along, h.rayOrigin[2] + d[2] * along) else return
        var moved = 0.0
        for (i in 0..2) { val t = grip[i] + off[i]; moved += (t - p[i]) * (t - p[i]); p[i] = t }
        draggedM += sqrt(moved)
    }

    companion object {
        const val NEAR_M = 0.05
        const val FAR_M = 0.04
        const val LOST_NS = 400_000_000L
    }
}

/** A thing that is pressed by beginning a pinch while a hand's ray is on it, or with the fingers at it. It counts. */
class PressTarget(val p: DoubleArray, val radius: Double = 0.07) {
    var presses = 0; private set
    var hover = -1; private set
    /** When it was last pressed (the caller's clock), for a flash. */
    var pressedNs = 0L; private set
    private val was = BooleanArray(2)

    fun update(hands: List<HandProbe>, nowNs: Long) {
        hover = -1
        for (h in hands) {
            val side = if (h.isRight) 1 else 0
            val m = h.rayDir?.let { rayMiss(h.rayOrigin, it, p) }
            val on = dist(h.pinchPoint, p) < radius + 0.04 || (m != null && m[0] > 0.15 && m[1] < radius + 0.03)
            if (on && hover < 0) hover = side
            if (on && h.pinching && !was[side]) { presses++; pressedNs = nowNs }
            was[side] = h.pinching
        }
    }
}
