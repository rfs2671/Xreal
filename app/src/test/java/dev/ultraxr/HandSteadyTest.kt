package dev.ultraxr

import dev.ultraxr.core.HandSteady
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/** The hand steadier: a still hand gives a still pointer, a moving hand is followed, a pinch does not move the pointer. */
class HandSteadyTest {
    private val frame = 33_333_333L        // hands are tracked 30 times a second
    private fun steadied(s: HandSteady, t: Long, p: DoubleArray, began: Boolean = false, pinching: Boolean = false): DoubleArray {
        val o = DoubleArray(3); s.offset(t, p, began, pinching, o); return doubleArrayOf(p[0] + o[0], p[1] + o[1], p[2] + o[2])
    }
    private fun rms(v: List<Double>): Double { val m = v.average(); return sqrt(v.map { (it - m) * (it - m) }.average()) }

    @Test fun aStillHandWithTrackingJitterGivesAMuchStillerPointer() {
        val s = HandSteady(); val r = Random(5); val raw = ArrayList<Double>(); val out = ArrayList<Double>()
        for (i in 0 until 150) {
            val p = doubleArrayOf(0.1 + 0.003 * r.nextGaussian(), -0.2 + 0.003 * r.nextGaussian(), -0.4 + 0.003 * r.nextGaussian())
            val q = steadied(s, i * frame, p)
            if (i > 30) { raw += p[0]; out += q[0] }
        }
        assertTrue("jitter of 3 mm is cut to under a third: " + rms(raw) + " -> " + rms(out), rms(out) < rms(raw) / 3)
    }

    @Test fun aMovingHandIsNotLeftBehind() {
        // 0.5 m/s sideways: an ordinary reach. The steadied hand may trail, but by less than 3 cm.
        val s = HandSteady(); var worst = 0.0
        for (i in 0 until 60) {
            val x = if (i < 15) 0.0 else 0.5 * (i - 15) * frame * 1e-9
            val q = steadied(s, i * frame, doubleArrayOf(x, -0.2, -0.4))
            if (i > 25) worst = maxOf(worst, abs(x - q[0]))
        }
        assertTrue("trails by $worst m", worst < 0.03)
    }

    @Test fun thePinchDoesNotMoveThePointer() {
        // The hand is still; as the fingers close the tracked hand dips 1.5 cm for a few frames.
        val s = HandSteady(); var worstDuring = 0.0
        for (i in 0 until 60) {
            val dip = if (i in 30..35) -0.015 else 0.0
            val q = steadied(s, i * frame, doubleArrayOf(0.1, -0.2 + dip, -0.4), began = i == 31, pinching = i in 31..45)
            if (i in 31..36) worstDuring = maxOf(worstDuring, abs(q[1] - (-0.2)))
        }
        assertTrue("the pointer moved $worstDuring m during the pinch", worstDuring < 0.004)
    }

    @Test fun aDragIsFollowed() {
        // Pinch, then carry the hand 20 cm: after the short hold the pointer goes with it.
        val s = HandSteady(); var last = doubleArrayOf(0.0, 0.0, 0.0)
        for (i in 0 until 90) {
            val x = if (i < 31) 0.0 else minOf(0.2, 0.4 * (i - 31) * frame * 1e-9)
            last = steadied(s, i * frame, doubleArrayOf(x, -0.2, -0.4), began = i == 31, pinching = i >= 31)
        }
        assertEquals(0.2, last[0], 0.01)
    }

    @Test fun aHandThatComesBackAfterBeingLostStartsWhereItIs() {
        val s = HandSteady()
        for (i in 0 until 30) steadied(s, i * frame, doubleArrayOf(0.0, -0.2, -0.4))
        val q = steadied(s, 30 * frame + 2_000_000_000L, doubleArrayOf(0.3, 0.1, -0.5))
        assertEquals(0.3, q[0], 1e-9); assertEquals(0.1, q[1], 1e-9)
    }

    // ---- which way the hand is turned
    private fun aboutY(deg: Double): DoubleArray { val h = Math.toRadians(deg) / 2; return doubleArrayOf(0.0, Math.sin(h), 0.0, Math.cos(h)) }
    /** The angle, in degrees, of the hand's forward axis from straight ahead after [fix] is applied to a hand turned [q]. */
    private fun heading(fix: DoubleArray, q: DoubleArray): Double {
        val f = dev.ultraxr.core.Q.rotate(dev.ultraxr.core.Q.mul(fix, q), doubleArrayOf(0.0, 0.0, -1.0))
        return Math.toDegrees(Math.atan2(-f[0], -f[2]))
    }

    @Test fun aStillHandWhoseTurnJittersPointsMuchMoreSteadily() {
        // 3 degrees of wobble is what the Pixel's recording showed for a hand held still
        val s = dev.ultraxr.core.HandTurnSteady(); val r = Random(7); val raw = ArrayList<Double>(); val out = ArrayList<Double>()
        for (i in 0 until 150) {
            val a = 20 + 3 * r.nextGaussian(); val q = aboutY(a)
            val h = heading(s.correction(i * frame, q), q)
            if (i > 30) { raw += a; out += h }
        }
        assertTrue("wobble " + rms(raw) + " -> " + rms(out) + " degrees", rms(out) < rms(raw) / 2)
        assertEquals("and it still points where the hand does on average", 20.0, out.average(), 1.0)
    }

    @Test fun aDeliberateTurnIsFollowed() {
        // 120 degrees a second: a quick sweep across the room. The steadied hand may trail, but by under 8 degrees,
        // and it arrives within 2 degrees a third of a second after the hand stops.
        val s = dev.ultraxr.core.HandTurnSteady(); var worst = 0.0; var last = 0.0
        for (i in 0 until 75) {
            val a = when { i < 15 -> 0.0; i < 45 -> 120.0 * (i - 15) * frame * 1e-9; else -> 120.0 * 30 * frame * 1e-9 }
            val q = aboutY(a); val h = heading(s.correction(i * frame, q), q)
            if (i in 20..45) worst = maxOf(worst, abs(a - h))
            if (i == 55) last = abs(a - h)
        }
        assertTrue("trails by $worst degrees", worst < 8.0)
        assertTrue("settles to $last degrees", last < 2.0)
    }

    @Test fun aHandThatWasAwayStartsFromWhereItIsNow() {
        val s = dev.ultraxr.core.HandTurnSteady()
        for (i in 0 until 30) s.correction(i * frame, aboutY(0.0))
        val q = aboutY(90.0); val h = heading(s.correction(30 * frame + 2_000_000_000L, q), q)
        assertEquals(90.0, h, 0.01)
    }
}
