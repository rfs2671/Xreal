package dev.ultraxr.desktop

import dev.ultraxr.core.Hand
import dev.ultraxr.core.HandProbe
import dev.ultraxr.core.HandReader
import dev.ultraxr.core.PinchState
import kotlin.math.sqrt

/** The hand that points at the desktop, for one frame: where its ray starts and goes, whether it pinches, and its two fingertips. All in the app world. */
class HandAim(val right: Boolean, val origin: DoubleArray, val dir: DoubleArray, val pinching: Boolean, val thumb: DoubleArray, val index: DoubleArray, val gapM: Double)

/**
 * A tracked hand as a pointer for the desktop. The hand is read by [HandReader], the same code the test scenes use
 * (the ray along the arm from the middle of the fingertips, the measured pinch rule), so that the desktop and the
 * ball follow a hand alike; this adds only the choice of the one hand that points.
 *
 * One hand points at a time: the one that is pinching, otherwise the one that pointed last, otherwise the right.
 * A hand the tracker loses is kept where it was for [HOLD_NS], pinch included (the fingers hide each other exactly
 * when they touch, and a hand moving out of the cameras' view is lost for a moment): as long as the ball's hold.
 */
class HandPointer(pressM: Float = PinchState.PRESS_M, releaseM: Float = PinchState.RELEASE_M) {
    private val reader = HandReader(pressM, releaseM)
    private val last = arrayOfNulls<Hand>(2); private val lastProbe = arrayOfNulls<HandProbe>(2); private val seenNs = LongArray(2)
    private var active = -1
    /** How many results and how many pinches there have been, for the log. */
    val results: Long get() = reader.results
    val pinches: Long get() = reader.pinches

    fun update(nowNs: Long, hands: List<Hand>, headP: DoubleArray, headQ: DoubleArray): HandAim? {
        for (p in reader.read(hands, nowNs, headP, headQ)) {
            val side = if (p.isRight) 1 else 0
            lastProbe[side] = p; last[side] = hands.first { it.isRight == p.isRight }; seenNs[side] = nowNs
        }
        for (side in 0..1) if (last[side] != null && nowNs - seenNs[side] > HOLD_NS) { last[side] = null; lastProbe[side] = null }
        val have = (0..1).filter { last[it] != null }
        if (have.isEmpty()) { active = -1; return null }
        val side = if (active in have && lastProbe[active]!!.pinching) active else have.firstOrNull { lastProbe[it]!!.pinching } ?: if (active in have) active else have.last()
        active = side
        val h = last[side]!!; val p = lastProbe[side]!!
        val d = p.rayDir ?: run {
            val wrist = h.joint(Hand.WRIST); val mid = h.joint(Hand.MIDDLE_MCP)
            val n = dist(mid, wrist).coerceAtLeast(1e-6); DoubleArray(3) { (mid[it] - wrist[it]) / n }
        }
        val thumb = h.joint(Hand.THUMB_TIP); val index = h.joint(Hand.INDEX_TIP)
        return HandAim(side == 1, p.rayOrigin, d, p.pinching, thumb, index, dist(thumb, index))
    }

    fun reset() { for (s in 0..1) { last[s] = null; lastProbe[s] = null }; active = -1 }

    companion object {
        const val HOLD_NS = 400_000_000L
        fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    }
}
