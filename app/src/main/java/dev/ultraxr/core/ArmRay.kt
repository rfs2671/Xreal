package dev.ultraxr.core

import kotlin.math.sqrt

/**
 * Where a hand points at distant things: along the arm, from the shoulder through the hand, as Quest's own hand ray.
 * The shoulder is taken from the head: 22 cm below the eyes, 17 cm to the hand's side, 6 cm back. Until 19 November
 * 2026 the ray ran from the wrist to the knuckles, which points wherever the hand is tilted; the wearer called that
 * "really bad" next to this one. Smoothed, and held still for the first 0.3 s of a pinch, since closing the fingers
 * moves the hand. One for each hand: it remembers the last direction.
 */
class ArmRay(private val right: Boolean) {
    private val k = DoubleArray(3); private var atNs = 0L; private var pinchNs = 0L; private var wasPressed = false

    /** The direction for a hand at [origin], the head being at [headP] turned by [headQ] (same world), or null when it cannot be told. */
    fun aim(headP: DoubleArray, headQ: DoubleArray, origin: DoubleArray, pinching: Boolean, nowNs: Long): DoubleArray? {
        var f = Q.rotate(headQ, doubleArrayOf(0.0, 0.0, -1.0)); var fl = sqrt(f[0] * f[0] + f[2] * f[2])
        if (fl < 0.2) { f = Q.rotate(headQ, doubleArrayOf(0.0, 1.0, 0.0)); fl = sqrt(f[0] * f[0] + f[2] * f[2]) }      // looking straight down or up: the top of the head says where forward is
        if (fl < 1e-3) return null
        val fx = f[0] / fl; val fz = f[2] / fl; val sd = if (right) 0.17 else -0.17
        val sx = headP[0] + -fz * sd - fx * 0.06; val sy = headP[1] - 0.22; val sz = headP[2] + fx * sd - fz * 0.06
        var dx = origin[0] - sx; var dy = origin[1] - sy; var dz = origin[2] - sz
        val dl = sqrt(dx * dx + dy * dy + dz * dz); if (dl < 0.05) return null
        dx /= dl; dy /= dl; dz /= dl
        if (pinching && !wasPressed) pinchNs = nowNs
        wasPressed = pinching
        val fresh = nowNs - atNs < 500_000_000L
        val hold = pinching && fresh && nowNs - pinchNs < 300_000_000L
        if (!hold) {
            if (fresh) { dx = k[0] * 0.7 + dx * 0.3; dy = k[1] * 0.7 + dy * 0.3; dz = k[2] * 0.7 + dz * 0.3; val n = sqrt(dx * dx + dy * dy + dz * dz); dx /= n; dy /= n; dz /= n }
            k[0] = dx; k[1] = dy; k[2] = dz
        }
        atNs = nowNs
        return doubleArrayOf(k[0], k[1], k[2])
    }
}
