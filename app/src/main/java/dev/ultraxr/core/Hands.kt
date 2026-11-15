package dev.ultraxr.core

import kotlin.math.sqrt

/**
 * One tracked hand: 21 keypoints in the app world (metres), in the usual order
 * wrist, thumb (cmc, mcp, ip, tip), then index / middle / ring / little (mcp, pip, dip, tip).
 */
class Hand(val isRight: Boolean, val joints: FloatArray, val timestampNs: Long) {
    init { require(joints.size == JOINTS * 3) }
    fun joint(i: Int) = doubleArrayOf(joints[i * 3].toDouble(), joints[i * 3 + 1].toDouble(), joints[i * 3 + 2].toDouble())

    companion object {
        const val JOINTS = 21
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_MCP = 5
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9
        const val MIDDLE_TIP = 12
        const val RING_MCP = 13
        const val RING_TIP = 16
        const val LITTLE_MCP = 17
        const val LITTLE_TIP = 20
        /** Bones as joint index pairs, for drawing. */
        val BONES = intArrayOf(
            0, 1, 1, 2, 2, 3, 3, 4,
            0, 5, 5, 6, 6, 7, 7, 8,
            0, 9, 9, 10, 10, 11, 11, 12,
            0, 13, 13, 14, 14, 15, 15, 16,
            0, 17, 17, 18, 18, 19, 19, 20,
            5, 9, 9, 13, 13, 17,
        )
    }
}

/** What a game or the runtime needs from a hand, derived from its keypoints. */
class HandState(
    val isRight: Boolean,
    val pinching: Boolean,
    /** 0 = fingers apart, 1 = touching. */
    val pinchStrength: Double,
    val fist: Boolean,
    val pointing: Boolean,
    /** Midpoint of thumb tip and index tip. */
    val pinchPoint: DoubleArray,
    val indexTip: DoubleArray,
    val palm: DoubleArray,
    /** Velocity of the pinch point, m/s. */
    val velocity: DoubleArray,
    /** Ray for pointing at distant things: from the palm along the wrist->knuckles direction. */
    val aimOrigin: DoubleArray,
    val aimDir: DoubleArray,
    val timestampNs: Long,
)

/** Per-hand gesture recogniser with hysteresis so states do not flicker at the threshold. */
class GestureTracker(private val isRight: Boolean) {
    private var pinching = false
    private var fist = false
    private var lastPoint: DoubleArray? = null
    private var lastNs = 0L
    private var vel = doubleArrayOf(0.0, 0.0, 0.0)

    fun update(hand: Hand): HandState {
        val wrist = hand.joint(Hand.WRIST)
        val thumb = hand.joint(Hand.THUMB_TIP)
        val index = hand.joint(Hand.INDEX_TIP)
        val midMcp = hand.joint(Hand.MIDDLE_MCP)
        val handSize = dist(wrist, midMcp).coerceAtLeast(0.04) // ~9 cm on an adult hand

        // Thresholds scale with the measured hand so they hold for small and large hands.
        val d = dist(thumb, index) / handSize
        pinching = if (pinching) d < PINCH_OFF else d < PINCH_ON
        val strength = ((PINCH_OFF - d) / (PINCH_OFF - 0.1)).coerceIn(0.0, 1.0)

        // Curl of a finger: how close its tip is to the wrist compared with a straight finger.
        fun curled(tip: Int, mcp: Int): Boolean = dist(hand.joint(tip), wrist) < dist(hand.joint(mcp), wrist) * 1.25
        val idxCurled = curled(Hand.INDEX_TIP, Hand.INDEX_MCP)
        val others = curled(Hand.MIDDLE_TIP, Hand.MIDDLE_MCP) && curled(Hand.RING_TIP, Hand.RING_MCP) && curled(Hand.LITTLE_TIP, Hand.LITTLE_MCP)
        val nowFist = idxCurled && others
        fist = if (fist) (others && (idxCurled || pinching)) else nowFist
        val pointing = !idxCurled && others && !pinching

        val palm = DoubleArray(3) { (wrist[it] + hand.joint(Hand.INDEX_MCP)[it] + hand.joint(Hand.LITTLE_MCP)[it]) / 3 }
        val pp = DoubleArray(3) { (thumb[it] + index[it]) / 2 }
        val prev = lastPoint
        if (prev != null && hand.timestampNs > lastNs) {
            val dt = (hand.timestampNs - lastNs) * 1e-9
            if (dt < 0.25) for (i in 0..2) vel[i] = 0.5 * vel[i] + 0.5 * (pp[i] - prev[i]) / dt
            else vel = doubleArrayOf(0.0, 0.0, 0.0)
        }
        lastPoint = pp; lastNs = hand.timestampNs

        var ax = midMcp[0] - wrist[0]; var ay = midMcp[1] - wrist[1]; var az = midMcp[2] - wrist[2]
        val n = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6)
        ax /= n; ay /= n; az /= n
        return HandState(isRight, pinching, strength, fist, pointing, pp, index, palm, vel.copyOf(), palm, doubleArrayOf(ax, ay, az), hand.timestampNs)
    }

    fun reset() { pinching = false; fist = false; lastPoint = null; vel = doubleArrayOf(0.0, 0.0, 0.0) }

    companion object {
        const val PINCH_ON = 0.28   // of hand size (~2.5 cm)
        const val PINCH_OFF = 0.45  // (~4 cm)
        fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    }
}
