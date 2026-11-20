package dev.ultraxr

import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.MapAlign
import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's world tied to the saved map ([MapAlign]), on made-up poses. */
class MapAlignTest {
    private val zy = HeadTracker.Z_TO_Y
    private fun zUp(x: Double, y: Double, z: Double, yawDeg: Double) = Pose(doubleArrayOf(x, y, z), Q.axisAngle(0.0, 0.0, 1.0, Math.toRadians(yawDeg)))
    private fun yUp(x: Double, y: Double, z: Double, yawDeg: Double) = Pose(doubleArrayOf(x, y, z), Q.axisAngle(0.0, 1.0, 0.0, Math.toRadians(yawDeg)))
    private fun dist(a: DoubleArray, b: DoubleArray) = Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    /** A point given in an estimator world (z up), in the app's world. */
    private fun app(fix: Pose, e: DoubleArray) = fix.apply(Q.rotate(zy, e))

    /** The same reading three times: enough agreement for it to be used. */
    private fun MapAlign.thrice(fix: Pose, corr: Pose): Pose? { onReading(fix, corr); onReading(fix, corr); return onReading(fix, corr) }

    @Test fun oneReadingIsNotBelievedThreeThatAgreeAre() {
        val a = MapAlign(zy); val fix = yUp(0.3, 0.0, -0.2, 20.0); val corr = zUp(1.0, 2.0, 0.1, 35.0)
        assertNull(a.onReading(fix, corr)); assertNull(a.appFromMap)
        assertNull(a.onReading(fix, zUp(1.03, 2.0, 0.1, 35.5))); assertNull(a.appFromMap)
        assertNull(a.onReading(fix, zUp(1.0, 2.02, 0.1, 34.6)))
        assertNotNull(a.appFromMap); assertEquals(1, a.version); assertEquals(2, a.unsure)
    }

    @Test fun aReadingFarFromTheOthersIsNotUsed() {
        val a = MapAlign(zy); val fix = yUp(0.0, 0.0, 0.0, 0.0)
        a.thrice(fix, Pose())
        val held = a.appFromMap!!
        assertNull(a.onReading(fix, zUp(0.33, 0.0, 0.0, 4.0)))        // one in fifty on the phone was like this
        assertEquals(0.0, dist(held.p, a.appFromMap!!.p), 1e-12); assertEquals(1, a.version)
    }

    @Test fun aPlaceSavedInOneSessionComesBackInTheNext() {
        // the map is the first session's estimator world; a thing stands at e (in that world)
        val e = doubleArrayOf(1.2, -0.4, 0.3)
        val fix1 = yUp(0.5, 0.1, 0.2, -15.0); val one = MapAlign(zy); one.thrice(fix1, Pose())
        val saved = MapAlign.toMap(one.appFromMap!!, app(fix1, e), Math.toRadians(70.0))
        // the next session's estimator world lies somewhere else in the map, and the app's world somewhere else again
        val corr2 = zUp(-2.0, 1.5, 0.4, 130.0); val fix2 = yUp(-1.0, 0.0, 3.0, 48.0)
        val two = MapAlign(zy); two.thrice(fix2, corr2)
        val back = MapAlign.fromMap(two.appFromMap!!, saved)
        val there = app(fix2, corr2.inverse().apply(e))       // where the thing really is in the second session's app world
        assertEquals(0.0, dist(there, back), 1e-9)
        // and its heading turned by as much as the worlds are turned against each other: the same wall is faced
        val turned = Q.yawY(two.appFromMap!!.q) - Q.yawY(one.appFromMap!!.q)
        assertEquals(0.0, Math.IEEEremainder(back[3] - Math.toRadians(70.0) - turned, 2 * Math.PI), 1e-9)
    }

    @Test fun aSmallDriftIsTakenOutInSmallSteps() {
        val a = MapAlign(zy); var fix = yUp(0.0, 0.0, 0.0, 0.0)
        a.thrice(fix, Pose())
        val drifted = zUp(0.06, -0.03, 0.0, 1.0)        // the estimator has wandered 7 cm and a degree
        var n = 0; var quiet = 0
        while (n < 100) {
            val m = a.onReading(fix, drifted)
            if (m == null) { if (++quiet > 6) break else continue }      // the first readings of the drift are outvoted by the old ones
            quiet = 0
            val near = fix.p; val to = m.apply(near)
            assertTrue("a step of " + dist(near, to), dist(near, to) <= MapAlign.STEP_M + 1e-9)
            assertTrue(Math.abs(Q.yawY(m.q)) <= MapAlign.STEP_RAD + 1e-9)
            fix = m * fix; a.onWorldMoved(m, own = true); n++
        }
        assertTrue("steps: $n", n in 4..20)
        val left = a.appFromMap!! * a.seen(fix, drifted).inverse()
        assertTrue(dist(left.p, doubleArrayOf(0.0, 0.0, 0.0)) < 0.03); assertTrue(Math.abs(Q.yawY(left.q)) < Math.toRadians(0.5))
        assertEquals(1, a.version)
    }

    @Test fun aLargeErrorIsPutRightAtOnce() {
        val a = MapAlign(zy); var fix = yUp(0.2, 0.0, 0.1, 10.0)
        a.thrice(fix, zUp(0.5, 0.5, 0.0, 20.0))
        val after = zUp(1.6, 0.2, 0.0, 55.0)             // position was lost and joined on a metre off
        val m = a.thrice(fix, after)!!
        fix = m * fix; a.onWorldMoved(m, own = true)
        val left = a.appFromMap!! * a.seen(fix, after).inverse()
        assertEquals(0.0, dist(left.p, doubleArrayOf(0.0, 0.0, 0.0)), 1e-9); assertEquals(0.0, Q.yawY(left.q), 1e-9)
        assertEquals(2, a.version)
        assertNull(a.onReading(fix, after))
    }

    @Test fun aRecentreCarriesTheMapAlong() {
        val a = MapAlign(zy); var fix = yUp(0.2, 0.0, 0.1, 10.0); val corr = zUp(0.5, 0.5, 0.0, 20.0)
        a.thrice(fix, corr)
        val recentre = yUp(-1.0, 0.0, 2.0, 90.0)
        fix = recentre * fix; a.onWorldMoved(recentre)
        assertNull(a.onReading(fix, corr)); assertEquals(0.0, a.lastOffM, 1e-9)
    }

    @Test fun noiseIsLeftAlone() {
        val a = MapAlign(zy); val fix = yUp(0.0, 0.0, 0.0, 0.0)
        a.thrice(fix, Pose())
        assertNull(a.thrice(fix, zUp(0.01, 0.005, 0.0, 0.1)))
    }
}
