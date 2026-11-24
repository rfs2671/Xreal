// Ultra XR: where a hand will be when its picture is shown. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

/**
 * Carries a tracked hand forward to the time it is drawn: the hand's own motion, the same for every joint, over the
 * time between its picture and the picture the wearer sees (the tracker takes 20 to 90 ms, then the application and
 * the display).
 *
 * Measured on the owner's worn recordings of 23 November 2026 (three recordings, 9,000 samples; the error of the
 * position held at the last result against the next measured position of thumb tip, index tip, palm and three more
 * tips; `C:\uvio\handup\predict2.py`):
 *
 *   horizon        hold, all / fast      this, all / fast      (fast: over 0.25 m/s)
 *   40 ms          7.6 / 14.9 mm          6.9 / 11.9 mm
 *   80 ms         12.5 / 25.8 mm         11.3 / 20.7 mm
 *   120 ms        17.0 / 35.5 mm         15.2 / 27.9 mm      (the carry is capped at 80 ms)
 *
 * and the same, 4.1 / 6.3 / 8.4 mm, for a hand that is nearly still, which it leaves alone.
 *
 * Why this form and not a filter on each joint: the fingers' own motion is noise at these speeds (a still hand's
 * index tip is 11 mm RMS from steady against the palm's 3 mm), so velocity taken from single joints makes it worse; the
 * mean of all joints' velocity over the last four results is the hand's motion, and it is applied only above 5 cm/s,
 * in full at 25 cm/s. A gap of results of 80 ms or more starts it again. It never invents a hand: it only moves
 * one that was measured, by no more than [CAP_S] times its speed.
 */
class HandPredictor(private val stride: Int, private val joints: Int) {
    private val ts = DoubleArray(K); private val xs = Array(K) { DoubleArray(joints * 3) }
    private var n = 0; private var head = 0
    private val v = DoubleArray(3)
    /** The speed of the hand's motion at the last result, metres a second. */
    var speed = 0.0; private set

    /** A result: [pose] holds [joints] joints of [stride] floats each, position first. */
    fun add(tNs: Long, pose: FloatArray) {
        val t = tNs * 1e-9
        if (n > 0 && t - ts[(head + K - 1) % K] > MAX_GAP_S) n = 0
        if (n > 0 && t <= ts[(head + K - 1) % K]) return
        val slot = head; head = (head + 1) % K; if (n < K) n++
        ts[slot] = t
        for (j in 0 until joints) { val o = j * stride; val q = j * 3; xs[slot][q] = pose[o].toDouble(); xs[slot][q + 1] = pose[o + 1].toDouble(); xs[slot][q + 2] = pose[o + 2].toDouble() }
        estimate()
    }

    private fun estimate() {
        v[0] = 0.0; v[1] = 0.0; v[2] = 0.0; speed = 0.0
        if (n < K) return
        var tm = 0.0; for (i in 0 until K) tm += ts[(head + i) % K]; tm /= K
        var den = 0.0; for (i in 0 until K) { val d = ts[(head + i) % K] - tm; den += d * d }
        if (den < 1e-9) return
        // least-squares slope of the mean of the joints (the mean of the joints' slopes is the slope of their mean)
        for (c in 0..2) {
            val m = DoubleArray(K)
            for (i in 0 until K) { val xi = xs[(head + i) % K]; var s = 0.0; for (j in 0 until joints) s += xi[j * 3 + c]; m[i] = s / joints }
            val mean = m.average()
            var num = 0.0
            for (i in 0 until K) num += (ts[(head + i) % K] - tm) * (m[i] - mean)
            v[c] = num / den
        }
        speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    }

    /** The shift, metres, of the whole hand for [horizonS] seconds after the last result, into [out]; false (and zero) when the hand is not moving enough or is not known. */
    fun shift(horizonS: Double, out: DoubleArray): Boolean {
        out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
        if (n < K || horizonS <= 0.0) return false
        val u = ((speed - LO) / (HI - LO)).coerceIn(0.0, 1.0); val g = u * u * (3 - 2 * u)
        if (g <= 0.0) return false
        val h = minOf(horizonS, CAP_S) * g
        out[0] = v[0] * h; out[1] = v[1] * h; out[2] = v[2] * h
        return true
    }

    fun reset() { n = 0; head = 0; speed = 0.0 }

    companion object {
        const val K = 4
        const val MAX_GAP_S = 0.08
        const val CAP_S = 0.08
        const val LO = 0.05; const val HI = 0.25
        /** Adds [d] to the position of each of [count] joints of [stride] floats in [joints]. */
        fun apply(joints: FloatArray, stride: Int, count: Int, d: DoubleArray) {
            for (j in 0 until count) { val o = j * stride; joints[o] += d[0].toFloat(); joints[o + 1] += d[1].toFloat(); joints[o + 2] += d[2].toFloat() }
        }
    }
}
