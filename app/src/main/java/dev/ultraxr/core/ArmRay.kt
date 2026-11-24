package dev.ultraxr.core

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Where a hand points at distant things: along the arm, from the shoulder through the hand, as Quest's own hand ray.
 * The shoulder is taken from the head: 22 cm below the eyes, 17 cm to the hand's side, 6 cm back. Until 19 November
 * 2026 the ray ran from the wrist to the knuckles, which points wherever the hand is tilted; the wearer called that
 * "really bad" next to this one. One for each hand: it remembers the last direction.
 *
 * Until 24 November 2026 it was smoothed by taking three tenths of each call (so by a different time at 25 calls a
 * second from a game's feed than at 60 from a drawn frame) and held still for the first 0.3 s of a pinch, whatever the
 * hand did. Now:
 *
 *  - **Smoothing by time, by how fast the ray turns** (a One Euro filter on the direction): still, it is
 *    steadied ([MIN_HZ]); turning, the steadying falls away in proportion ([BETA]), so a sweep is not slowed.
 *  - **A pinch does not move the ray.** Closing the fingers moves the hand, and the ray, a little. When a pinch
 *    begins the ray goes back to where it was [LATCH_S] earlier, before the fingers began to close, and from there
 *    follows the hand's turning only past a dead band of [DEAD_RAD] radians (about 2.3 degrees): a click stays where
 *    it was aimed, a drag begins as soon as the hand has really moved.
 *
 * Measured on the owner's recordings of 23 November 2026 (27 short clicks, `C:\uvio\handup\ray_lab.py`, the ray from
 * the middle of the fingertips, replayed at 60 calls a second): between the ray 0.12 s before a click began and the
 * ray as it was let go, a median of 1.72 degrees and a 90th percentile of 9.9 with the old rule, 0.91 and 7.3 with this.
 * The ray of a still hand: 0.59 degrees RMS before, 0.62 now. (Which way the wearer meant to point was not recorded;
 * this measures how much a click moves the ray, not where it should be.)
 */
class ArmRay(private val right: Boolean, private val latchS: Double = LATCH_S, private val adaptive: Boolean = true,
             /** 0: the ray starts at the estimated shoulder; 1: at the eye (along the line the wearer sees the fingers on); between: in between. For measuring against the hand lab's targets. */
             private val eye: Double = 0.0) {
    private val f = DoubleArray(3); private var haveF = false; private var lastNs = 0L; private var turnRate = 0.0
    private val histNs = LongArray(HIST); private val hist = Array(HIST) { DoubleArray(3) }; private var histN = 0; private var histHead = 0
    private var wasPressed = false
    private val anchor = DoubleArray(3); private val live0 = DoubleArray(3); private var latched = false
    private val out = DoubleArray(3)

    /** The direction for a hand at [origin], the head being at [headP] turned by [headQ] (same world), or null when it cannot be told. */
    fun aim(headP: DoubleArray, headQ: DoubleArray, origin: DoubleArray, pinching: Boolean, nowNs: Long): DoubleArray? {
        var fw = Q.rotate(headQ, doubleArrayOf(0.0, 0.0, -1.0)); var fl = sqrt(fw[0] * fw[0] + fw[2] * fw[2])
        if (fl < 0.2) { fw = Q.rotate(headQ, doubleArrayOf(0.0, 1.0, 0.0)); fl = sqrt(fw[0] * fw[0] + fw[2] * fw[2]) }      // looking straight down or up: the top of the head says where forward is
        if (fl < 1e-3) return null
        val fx = fw[0] / fl; val fz = fw[2] / fl; val sd = if (right) 0.17 else -0.17
        val sx = headP[0] + (-fz * sd - fx * 0.06) * (1 - eye); val sy = headP[1] - 0.22 * (1 - eye); val sz = headP[2] + (fx * sd - fz * 0.06) * (1 - eye)
        var dx = origin[0] - sx; var dy = origin[1] - sy; var dz = origin[2] - sz
        val dl = sqrt(dx * dx + dy * dy + dz * dz); if (dl < 0.05) return null
        dx /= dl; dy /= dl; dz /= dl

        val dtS = (nowNs - lastNs) * 1e-9
        if (!haveF || dtS > 0.5 || dtS < 0.0) { f[0] = dx; f[1] = dy; f[2] = dz; haveF = true; turnRate = 0.0; histN = 0; latched = false }
        else {
            val dt = maxOf(dtS, 1e-3)
            val ang = acos((f[0] * dx + f[1] * dy + f[2] * dz).coerceIn(-1.0, 1.0)) / dt
            val ar = alpha(dt, 1.0); turnRate = ar * ang + (1 - ar) * turnRate
            val a = alpha(dt, MIN_HZ + BETA * turnRate)
            var nx = f[0] * (1 - a) + dx * a; var ny = f[1] * (1 - a) + dy * a; var nz = f[2] * (1 - a) + dz * a
            val n = sqrt(nx * nx + ny * ny + nz * nz); f[0] = nx / n; f[1] = ny / n; f[2] = nz / n
        }
        lastNs = nowNs
        // the last 0.6 s of the steadied ray, for the latch
        histNs[histHead] = nowNs; hist[histHead][0] = f[0]; hist[histHead][1] = f[1]; hist[histHead][2] = f[2]; histHead = (histHead + 1) % HIST; if (histN < HIST) histN++
        if (pinching && !wasPressed) {
            var pick = -1
            // how fast the ray is turning just now: over the last 60 ms or so
            var speed = 0.0
            if (adaptive) for (i in 0 until histN) { val k = ((histHead - 1 - i) % HIST + HIST) % HIST; if (nowNs - histNs[k] >= 60_000_000L) { val dtp = (nowNs - histNs[k]) * 1e-9; speed = acos((f[0] * hist[k][0] + f[1] * hist[k][1] + f[2] * hist[k][2]).coerceIn(-1.0, 1.0)) / dtp; break } }
            val back = if (adaptive) latchS / (1.0 + (speed / FAST_RAD_S) * (speed / FAST_RAD_S)) else latchS
            val backNs = (back * 1e9).toLong()
            for (i in 0 until histN) { val k = ((histHead - 1 - i) % HIST + HIST) % HIST; if (nowNs - histNs[k] >= backNs) { pick = k; break } }
            if (pick < 0) pick = ((histHead - histN) % HIST + HIST) % HIST
            for (c in 0..2) { anchor[c] = hist[pick][c]; live0[c] = f[c] }
            latched = true
        }
        wasPressed = pinching
        if (!pinching) latched = false
        if (latched) {
            val ex = f[0] - live0[0]; val ey = f[1] - live0[1]; val ez = f[2] - live0[2]
            val m = sqrt(ex * ex + ey * ey + ez * ez)
            val eff = if (m < DEAD_RAD) 0.0 else (m - DEAD_RAD) / m
            var ox = anchor[0] + ex * eff; var oy = anchor[1] + ey * eff; var oz = anchor[2] + ez * eff
            val n = sqrt(ox * ox + oy * oy + oz * oz); out[0] = ox / n; out[1] = oy / n; out[2] = oz / n
        } else { out[0] = f[0]; out[1] = f[1]; out[2] = f[2] }
        return doubleArrayOf(out[0], out[1], out[2])
    }

    private fun alpha(dt: Double, fc: Double): Double { val tau = 1.0 / (2 * Math.PI * fc); return 1.0 / (1.0 + tau / dt) }

    companion object {
        const val MIN_HZ = 2.0; const val BETA = 6.0
        const val LATCH_S = 0.12; const val LATCH_NS = 120_000_000L; const val DEAD_RAD = 0.04
        /** Turning faster than this (about 46 degrees a second) shortens the look-back: the hand is going somewhere, not just closing. */
        const val FAST_RAD_S = 0.8
        private const val HIST = 64
    }
}
