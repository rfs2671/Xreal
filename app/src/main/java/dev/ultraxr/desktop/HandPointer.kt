package dev.ultraxr.desktop

import dev.ultraxr.core.ArmRay
import dev.ultraxr.core.Hand
import dev.ultraxr.core.PinchState
import kotlin.math.sqrt

/** The hand that points at the desktop, for one frame: where its ray starts and goes, whether it pinches, and its two fingertips. All in the app world. */
class HandAim(val right: Boolean, val origin: DoubleArray, val dir: DoubleArray, val pinching: Boolean, val thumb: DoubleArray, val index: DoubleArray, val gapM: Double)

/**
 * A tracked hand as a pointer for the desktop. It uses what the games use, because those are the ones the wearer
 * found to work: the ray along the arm ([ArmRay]) and the measured pinch rule ([PinchState], which also knows a pinch
 * by its closing movement). Until 20 November 2026 the desktop pointed from the wrist to the knuckles and took a
 * pinch by distance alone, a rule that caught 1 of 11 of the wearer's pinches in his own recording.
 *
 * One hand points at a time: the one that is pinching, otherwise the one that pointed last, otherwise the right.
 * A hand the tracker loses is kept where it was for a quarter of a second, pinch included (the fingers hide each
 * other exactly when they touch).
 */
class HandPointer(pressM: Float = PinchState.PRESS_M, releaseM: Float = PinchState.RELEASE_M) {
    private val pinch = arrayOf(PinchState(pressM, releaseM), PinchState(pressM, releaseM))
    private val arm = arrayOf(ArmRay(false), ArmRay(true))
    private val last = arrayOfNulls<Hand>(2); private val seenNs = LongArray(2); private val resultNs = LongArray(2)
    private var active = -1
    /** How many results and how many pinches there have been, for the log. */
    var results = 0L; private set
    var pinches = 0L; private set

    fun update(nowNs: Long, hands: List<Hand>, headP: DoubleArray, headQ: DoubleArray): HandAim? {
        for (side in 0..1) {
            val h = hands.firstOrNull { it.isRight == (side == 1) && nowNs - it.timestampNs < FRESH_NS }
            if (h == null) {
                if (last[side] != null && nowNs - seenNs[side] > HOLD_NS) { last[side] = null; pinch[side].lost() } else if (last[side] != null) pinch[side].lost(nowNs)
                continue
            }
            if (h.timestampNs != resultNs[side]) {
                resultNs[side] = h.timestampNs; results++
                var finger = 0f
                for (b in 5..7) finger += dist(h.joint(b), h.joint(b + 1)).toFloat()
                if (pinch[side].update(dist(h.joint(Hand.THUMB_TIP), h.joint(Hand.INDEX_TIP)).toFloat(), finger)) pinches++
            }
            last[side] = h; seenNs[side] = nowNs
        }
        val have = (0..1).filter { last[it] != null }
        if (have.isEmpty()) { active = -1; return null }
        val side = if (active in have && pinch[active].pinching) active else have.firstOrNull { pinch[it].pinching } ?: if (active in have) active else have.last()
        active = side
        val h = last[side]!!
        val wrist = h.joint(Hand.WRIST); val a = h.joint(Hand.INDEX_MCP); val b = h.joint(Hand.LITTLE_MCP); val mid = h.joint(Hand.MIDDLE_MCP)
        val palm = DoubleArray(3) { (wrist[it] + a[it] + b[it]) / 3 }
        val d = arm[side].aim(headP, headQ, palm, pinch[side].pinching, nowNs) ?: run {
            val n = dist(mid, wrist).coerceAtLeast(1e-6); DoubleArray(3) { (mid[it] - wrist[it]) / n }
        }
        val thumb = h.joint(Hand.THUMB_TIP); val index = h.joint(Hand.INDEX_TIP)
        return HandAim(side == 1, palm, d, pinch[side].pinching, thumb, index, dist(thumb, index))
    }

    fun reset() { for (s in 0..1) { last[s] = null; pinch[s].lost() }; active = -1 }

    companion object {
        const val FRESH_NS = 300_000_000L
        const val HOLD_NS = 250_000_000L
        fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    }
}
