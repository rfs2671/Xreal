package dev.ultraxr

import dev.ultraxr.core.PositionConfidence
import dev.ultraxr.core.TrackingHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Whether the estimator's position is fit to be shown ([TrackingHealth]): made-up states, and real ones when given. */
class TrackingHealthTest {
    private val frame = 33_333_333L
    /** A head walking steadily at [speed] along x (where it is depends on the state's number alone, so calls join up), [n] states from [from]; returns the last fit. */
    private fun walk(h: TrackingHealth, from: Int, n: Int, speed: Double = 0.8, features: Int = 150, x0: Double = 0.0): Boolean {
        var fit = false
        for (i in from until from + n) { val t = i * frame; fit = h.update(t, doubleArrayOf(x0 + speed * i * frame * 1e-9, 1.5, 0.0), doubleArrayOf(speed, 0.0, 0.0), features) }
        return fit
    }

    @Test fun aSteadyWalkIsFitAfterItHasSettledAndStaysSo() {
        val h = TrackingHealth()
        assertFalse(walk(h, 0, 10)); assertTrue(walk(h, 10, 30)); assertTrue(walk(h, 40, 600))
        assertEquals(0, h.losses); assertEquals(TrackingHealth.Why.FINE, h.why)
    }

    /** Worn, 21 November 2026: a hand over the cameras leaves 108 corners and a believable velocity; only the landmarks go. */
    @Test fun aHandOverTheCamerasIsToldFromTheLandmarksAndAsksForARestartWhenTheySeeAgain() {
        val h = TrackingHealth(); val v = doubleArrayOf(0.7, 0.0, 0.0)
        fun st(i: Int, lm: Int, s: Int) = h.update(i * frame, doubleArrayOf(0.7 * i * frame * 1e-9, 1.5, 0.0), v, 108, lm, s)
        for (i in 0 until 60) st(i, 60, 25); assertTrue(h.fit)
        // a short dip, as at second 53 of the walk of 18 November: nothing happens
        for (i in 60 until 78) assertTrue(st(i, 1, 0)); for (i in 78 until 90) assertTrue(st(i, 40, 20)); assertEquals(0, h.losses)
        // the hand: unfit 0.7 s on, and it stays so whatever the corners say
        for (i in 90 until 112) assertTrue(st(i, 0, 0)); for (i in 112 until 240) assertFalse(st(i, 0, 0))
        assertEquals(TrackingHealth.Why.BLIND, h.why); assertEquals(1, h.losses); assertFalse(h.takeRestart())
        // a stray landmark is not sight
        assertFalse(st(240, 3, 1)); assertFalse(h.takeRestart())
        // the hand is gone: a fresh start is asked for, once
        assertFalse(st(241, 30, 12)); assertTrue(h.takeRestart()); assertFalse(h.takeRestart())
        h.reset()
        // the fresh estimator has no landmarks at first: that is not a loss
        for (i in 242 until 262) st(i, 0, 0); assertFalse(h.takeRestart()); assertEquals(TrackingHealth.Why.SETTLING, h.why)
        for (i in 262 until 300) st(i, 40, 20); assertTrue(h.fit)
    }

    @Test fun coveredCamerasAreUnfitAtTheFirstStateAndAskForARestartWhenTheySeeAgain() {
        val h = TrackingHealth(); assertTrue(walk(h, 0, 60))
        // the very first state without corners
        assertFalse(h.update(60 * frame, doubleArrayOf(1.6, 1.5, 0.0), doubleArrayOf(0.8, 0.0, 0.0), 0)); assertEquals(TrackingHealth.Why.BLIND, h.why); assertEquals(1, h.losses)
        for (i in 61 until 240) assertFalse(h.update(i * frame, doubleArrayOf(1.6 + (i - 60) * 0.05, 1.5, 0.0), doubleArrayOf(1.5, 0.0, 0.0), 0))
        assertFalse(h.takeRestart())
        // pictures again: not believed, and a fresh start is asked for, once
        assertFalse(h.update(240 * frame, doubleArrayOf(10.56, 1.5, 0.0), doubleArrayOf(0.4, 0.0, 0.0), 130))
        assertTrue(h.takeRestart()); assertFalse(h.takeRestart())
        h.reset()
        // the fresh estimator must behave for 0.7 s before it is believed
        assertFalse(walk(h, 241, 15)); assertTrue(walk(h, 256, 10))
    }

    @Test fun aHeadSaidToFlyIsUnfitAtOnce() {
        val h = TrackingHealth(); assertTrue(walk(h, 0, 60))
        assertFalse(h.update(60 * frame, doubleArrayOf(1.62, 1.5, 0.0), doubleArrayOf(3.4, 0.0, 0.0), 150)); assertEquals(TrackingHealth.Why.FLYING, h.why)
    }

    @Test fun aPositionThatLeapsIsUnfitWhateverItsVelocitySays() {
        val h = TrackingHealth(); assertTrue(walk(h, 0, 60))
        // 20 cm in one frame (6 m/s) with a velocity of 0.3, as after the covered-camera replays
        assertFalse(h.update(60 * frame, doubleArrayOf(1.8, 1.5, 0.0), doubleArrayOf(0.3, 0.0, 0.0), 150)); assertEquals(TrackingHealth.Why.FLYING, h.why)
        // and 6 cm in a frame (a fast step, 1.8 m/s) is a head
        val g = TrackingHealth(); assertTrue(walk(g, 0, 60, speed = 1.8)); assertEquals(0, g.losses)
    }

    @Test fun aShortBlinkDoesNotAskForARestart() {
        val h = TrackingHealth(); assertTrue(walk(h, 0, 60))
        for (i in 60 until 66) assertFalse(h.update(i * frame, doubleArrayOf(1.6, 1.5, 0.0), doubleArrayOf(0.0, 0.0, 0.0), 3))      // 0.2 s
        assertFalse(h.update(66 * frame, doubleArrayOf(1.6, 1.5, 0.0), doubleArrayOf(0.0, 0.0, 0.0), 140)); assertFalse(h.takeRestart())
    }

    /**
     * Real states, if given: UXR_HEALTH_DIR holds TAG-vio.csv files of replays (tools/pixel/lab.py). For each it prints
     * how long the old rule (PositionConfidence alone) and the new one (with TrackingHealth) would have shown the
     * position, and what was shown: the fastest "head" and the largest step between two shown states. It cannot show
     * what a restart does: the recorded estimator was not restarted.
     */
    @Test fun recordedStatesIfGiven() {
        val dir = System.getenv("UXR_HEALTH_DIR"); assumeTrue(dir != null)
        for (f in File(dir!!).listFiles { x -> x.name.endsWith("-vio.csv") }!!.sortedBy { it.name }) {
            val rows = f.readLines().drop(1).map { it.split(",") }.filter { it.size >= 18 }; if (rows.size < 100) continue
            val pc = PositionConfidence(); val h = TrackingHealth()
            var oldShown = 0; var newShown = 0; var oldFast = 0.0; var newFast = 0.0; var oldStep = 0.0; var newStep = 0.0; var asked = 0
            var lastOld: DoubleArray? = null; var lastNew: DoubleArray? = null; var lastT = 0.0; var resets = rows[0][15]
            var firstNew = Double.NaN
            for (r in rows) {
                val t = r[0].toDouble(); val p = doubleArrayOf(r[1].toDouble(), r[2].toDouble(), r[3].toDouble()); val v = doubleArrayOf(r[8].toDouble(), r[9].toDouble(), r[10].toDouble())
                if (r[15] != resets) { resets = r[15]; pc.reset(); h.reset(); lastOld = null; lastNew = null }
                val tNs = (t * 1e9).toLong(); val speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                val old = pc.update(tNs, r[12].toInt(), r[13].toInt())
                val fit = h.update(tNs, p, v, r[11].toInt()); if (h.takeRestart()) asked++
                val new = old && fit
                fun step(a: DoubleArray?) = if (a == null || t - lastT > 0.1) 0.0 else Math.sqrt((p[0] - a[0]) * (p[0] - a[0]) + (p[1] - a[1]) * (p[1] - a[1]) + (p[2] - a[2]) * (p[2] - a[2]))
                if (old) { oldShown++; oldFast = maxOf(oldFast, speed); oldStep = maxOf(oldStep, step(lastOld)); lastOld = p } else lastOld = null
                if (new) { newShown++; newFast = maxOf(newFast, speed); newStep = maxOf(newStep, step(lastNew)); lastNew = p; if (firstNew.isNaN()) firstNew = t } else lastNew = null
                lastT = t
            }
            println("%-22s shown %5.1f%% -> %5.1f%% of %d states (first at %.1f s); fastest shown %.1f -> %.1f m/s; largest step between shown states %.0f -> %.0f cm; blind %d, flying %d, losses %d, restarts asked %d".format(
                f.name.removeSuffix("-vio.csv"), 100.0 * oldShown / rows.size, 100.0 * newShown / rows.size, rows.size, firstNew, oldFast, newFast, oldStep * 100, newStep * 100, h.blindStates, h.flyingStates, h.losses, asked))
        }
    }
}
