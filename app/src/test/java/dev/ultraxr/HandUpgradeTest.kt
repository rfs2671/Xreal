package dev.ultraxr

import dev.ultraxr.core.ArmRay
import dev.ultraxr.core.HandPredictor
import dev.ultraxr.core.PinchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.acos
import kotlin.math.sqrt

/** 24 November 2026: the hand upgrade: prediction, the pinch that begins on the way in, the ray that a click does not move. */
class HandUpgradeTest {
    private val ms = 1_000_000L

    // ---- prediction
    private fun pose(x: Double, joints: Int = 5): FloatArray { val a = FloatArray(joints * 3); for (j in 0 until joints) { a[j * 3] = x.toFloat(); a[j * 3 + 1] = 0.1f * j } ; return a }

    @Test fun aHandMovingAtAWalkingPaceIsCarriedForwardByItsSpeedAndAStillOneIsLeftAlone() {
        val p = HandPredictor(3, 5); val d = DoubleArray(3)
        for (i in 0 until 6) p.add(1_000 * ms + i * 38 * ms, pose(0.5 * i * 0.038))      // 0.5 m/s along x
        assertTrue(p.shift(0.05, d)); assertEquals(0.5 * 0.05, d[0], 0.002); assertEquals(0.0, d[1], 1e-6)
        assertTrue("capped at 80 ms", p.shift(0.5, d)); assertEquals(0.5 * 0.08, d[0], 0.002)
        val s = HandPredictor(3, 5); val r = Random(3)
        for (i in 0 until 12) s.add(1_000 * ms + i * 38 * ms, pose(0.2 + 0.003 * r.nextGaussian()))      // still, 3 mm of jitter
        assertFalse("a still hand is not moved", s.shift(0.05, d)); assertEquals(0.0, d[0], 0.0)
    }

    @Test fun aGapInTheResultsStartsThePredictionOverAndNeverMovesAnUnknownHand() {
        val p = HandPredictor(3, 5); val d = DoubleArray(3)
        for (i in 0 until 6) p.add(1_000 * ms + i * 38 * ms, pose(0.5 * i * 0.038))
        p.add(1_400 * ms, pose(1.0))
        assertFalse("after a gap there is no speed to go by", p.shift(0.05, d))
        assertFalse(HandPredictor(3, 5).shift(0.05, d))
    }

    // ---- pinch
    private val finger = 0.071f
    private fun closing(s: PinchState, gaps: List<Float>, startNs: Long = 1_000 * ms, ageNs: Long = 40 * ms): Long {
        var t = startNs; for (g in gaps) { s.update(g, finger, t, ageNs); t += 38 * ms }; return t
    }

    @Test fun aPinchBeginsOnTheWayInBeforeTheFingertipsReachThePressDistance() {
        val early = PinchState(); val late = PinchState(anticipate = false)
        // 80, 60, 38, 30 mm: a closing of 0.6 finger lengths in two results
        val gaps = listOf(0.085f, 0.080f, 0.060f, 0.038f, 0.030f, 0.020f)
        var tEarly = -1; var tLate = -1; var t = 1_000 * ms
        for ((i, g) in gaps.withIndex()) {
            if (early.update(g, finger, t, 40 * ms) && tEarly < 0) tEarly = i
            if (late.update(g, finger, t, 40 * ms) && tLate < 0) tLate = i
            t += 38 * ms
        }
        assertTrue("both see it", tEarly >= 0 && tLate >= 0); assertTrue("the early one is earlier ($tEarly against $tLate)", tEarly < tLate)
    }

    @Test fun aStillHandWithTrackingJitterDoesNotPinch() {
        val s = PinchState(); val r = Random(11); var t = 1_000 * ms; var began = 0
        for (i in 0 until 600) { if (s.update((0.075f + 0.007f * r.nextGaussian().toFloat()), finger, t, 40 * ms)) began++; t += 38 * ms }
        assertEquals("an open hand with 7 mm of jitter in its gap never pinches", 0, began)
    }

    @Test fun anEarlyPinchThatContactDoesNotConfirmIsTakenBack() {
        val s = PinchState(); var t = 1_000 * ms
        for (g in listOf(0.085f, 0.080f, 0.062f, 0.040f)) { s.update(g, finger, t, 40 * ms); t += 38 * ms }
        assertTrue("began early", s.pinching)
        for (g in listOf(0.060f, 0.075f, 0.080f, 0.082f)) { s.update(g, finger, t, 40 * ms); t += 38 * ms }
        assertFalse("the fingers went apart again: not a pinch", s.pinching)
    }

    @Test fun noClickBeginsOnStaleTrackingButOneUnderWayCanEnd() {
        val stale = PinchState(); closing(stale, listOf(0.085f, 0.080f, 0.060f, 0.040f, 0.025f, 0.020f), ageNs = 400 * ms)
        assertFalse("results 0.4 s old start nothing, however closed", stale.pinching)
        val s = PinchState(); var t = closing(s, listOf(0.085f, 0.080f, 0.060f, 0.040f, 0.025f), ageNs = 40 * ms); assertTrue(s.pinching)
        s.update(0.080f, finger, t, 400 * ms); t += 38 * ms; s.update(0.085f, finger, t, 400 * ms)
        assertFalse("and a pinch can end on an old one", s.pinching)
    }

    // ---- ray
    private fun angle(a: DoubleArray, b: DoubleArray) = Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))
    private val q = doubleArrayOf(0.0, 0.0, 0.0, 1.0); private val head = doubleArrayOf(0.0, 0.0, 0.0)

    @Test fun aClickDoesNotMoveTheRayButADragFollowsTheHand() {
        val r = ArmRay(true); var t = 5_000 * ms
        fun at(x: Double, pinching: Boolean): DoubleArray { t += 16 * ms; return r.aim(head, q, doubleArrayOf(x, -0.1, -0.45), pinching, t)!! }
        var base = at(0.12, false); repeat(30) { base = at(0.12, false) }
        // the fingers close and the hand drifts 1.2 cm sideways with it: under the dead band, the ray stays
        repeat(6) { at(0.12 + 0.002 * it, false) }
        var p = at(0.124, true); repeat(8) { p = at(0.132, true) }
        assertTrue("a click moved the ray ${angle(base, p)} degrees", angle(base, p) < 0.6)
        // then the hand sweeps 12 cm: the ray follows, the dead band only taken off
        repeat(12) { p = at(0.132 + 0.01 * (it + 1), true) }
        assertTrue("a drag moves the ray (${angle(base, p)} degrees)", angle(base, p) > 5.0)
    }

    @Test fun aStillHandsRayDoesNotJitterAndASweepIsNotLeftBehind() {
        val r = ArmRay(false); val rnd = Random(2); var t = 1_000 * ms
        var last: DoubleArray? = null; val devs = ArrayList<Double>()
        for (i in 0 until 200) { t += 16 * ms; val d = r.aim(head, q, doubleArrayOf(-0.1 + 0.003 * rnd.nextGaussian(), -0.1 + 0.003 * rnd.nextGaussian(), -0.45), false, t)!!; if (i > 40) { last?.let { devs += angle(it, d) }; last = d } }
        assertTrue("a still hand's ray moves under a third of a degree a frame: " + devs.average(), devs.average() < 0.33)
        // a sweep: 0.5 m/s sideways; at the end the ray is within 3 degrees of where the hand points
        var x = -0.1; var out = DoubleArray(3)
        for (i in 0 until 60) { t += 16 * ms; x -= 0.5 * 0.016; out = r.aim(head, q, doubleArrayOf(x, -0.1, -0.45), false, t)!! }
        val live = DoubleArray(3).also { val d = doubleArrayOf(x - (-0.17), -0.1 + 0.22, -0.45 - 0.06); val n = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); for (c in 0..2) it[c] = d[c] / n }
        assertTrue("a sweep is followed (behind by ${angle(out, live)} degrees)", angle(out, live) < 3.0)
    }
}
