package dev.ultraxr

import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import dev.ultraxr.core.RoomPlan
import dev.ultraxr.core.RoomRecall.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** The room its owner marked ([RoomPlan]) and finding it again in the world of another session, on made-up rooms. */
class RoomPlanTest {
    private val box = RoomPlan(listOf(doubleArrayOf(-2.0, -1.5), doubleArrayOf(2.0, -1.5), doubleArrayOf(2.0, 1.5), doubleArrayOf(-2.0, 1.5)))
    private val boxWithTable = RoomPlan(box.corners, listOf(doubleArrayOf(1.2, -0.9, 0.74)))
    // an L: no place in it looks like another
    private val ell = RoomPlan(listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(5.0, 0.0), doubleArrayOf(5.0, 2.0), doubleArrayOf(2.5, 2.0), doubleArrayOf(2.5, 4.0), doubleArrayOf(0.0, 4.0)))

    /** What the cameras find of the walls [which] of the plan in a world where it lies at (yaw, tx, tz): parts of them, a little off. */
    private fun found(plan: RoomPlan, which: List<Int>, yaw: Double, tx: Double, tz: Double, r: Random, noise: Double = 0.03): List<Line> {
        val f = RoomPlan.Fit(yaw, tx, tz, 0, 0)
        return which.map { val l = f.line(plan.walls[it]); val a = 0.1 + 0.2 * r.nextDouble(); val b = 0.7 + 0.25 * r.nextDouble()
            Line(l.x0 + (l.x1 - l.x0) * a + r.nextGaussian() * noise, l.z0 + (l.z1 - l.z0) * a + r.nextGaussian() * noise, l.x0 + (l.x1 - l.x0) * b + r.nextGaussian() * noise, l.z0 + (l.z1 - l.z0) * b + r.nextGaussian() * noise) }
    }
    private fun close(f: RoomPlan.Fit, yaw: Double, tx: Double, tz: Double) = abs(RoomPlan.wrap(f.yaw - yaw)) < 0.05 && abs(f.tx - tx) < 0.15 && abs(f.tz - tz) < 0.15

    @Test fun anEllIsFoundAgainFromThreeOfItsWallsWhereverItLies() {
        val r = Random(1); var ok = 0; var wrong = 0
        repeat(300) {
            val yaw = r.nextDouble() * 6.28 - 3.14; val tx = r.nextDouble() * 6 - 3; val tz = r.nextDouble() * 6 - 3
            val k = r.nextInt(6); val f = ell.fit(found(ell, listOf(k, (k + 1) % 6, (k + 2) % 6), yaw, tx, tz, r))
            if (f != null) { if (close(f, yaw, tx, tz)) ok++ else wrong++ }
        }
        println("RoomPlan ell: right $ok of 300, wrong $wrong")
        assertTrue("right $ok", ok >= 170); assertTrue("wrong $wrong", wrong <= 3)
    }

    @Test fun aPlainRectangleCannotSayWhichEndIsWhich() {
        val r = Random(2)
        assertNull(box.fit(found(box, listOf(0, 1), 0.7, 1.0, -0.5, r)))
    }

    @Test fun aTableTopSettlesIt() {
        val r = Random(3); val yaw = 0.7; val tx = 1.0; val tz = -0.5
        val t = RoomPlan.Fit(yaw, tx, tz, 0, 0).apply(1.2, -0.9)
        val f = boxWithTable.fit(found(box, listOf(0, 1), yaw, tx, tz, r), listOf(doubleArrayOf(t[0] + 0.1, t[1] - 0.1, 0.70)))
        assertNotNull(f); assertTrue(close(f!!, yaw, tx, tz)); assertEquals(1, f.tables)
    }

    @Test fun afterALossTheHeadingIsKnownAndTheRectangleIsEnough() {
        val r = Random(4); val before = RoomPlan.Fit(0.7, 1.0, -0.5, 4, 0)
        // the world slipped 0.8 m and 0.3 m while position was away; heading did not
        val f = box.fit(found(box, listOf(1, 2), 0.72, 1.8, -0.2, r), near = before, maxShift = 2.0)
        assertNotNull(f); assertTrue(close(f!!, 0.72, 1.8, -0.2))
        // and the move that puts the world back takes what is found now to where it was
        val back = before.worldMoveFrom(f)
        val wNow = f.apply(2.0, 1.5); val wThen = before.apply(2.0, 1.5)
        val moved = back.apply(doubleArrayOf(wNow[0], 0.0, wNow[1]))
        assertEquals(wThen[0], moved[0], 1e-6); assertEquals(wThen[1], moved[2], 1e-6)
    }

    @Test fun aSlipLongerThanCouldHaveBeenWalkedIsNotBelieved() {
        val r = Random(5); val before = RoomPlan.Fit(0.0, 0.0, 0.0, 4, 0)
        assertNull(ell.fit(found(ell, listOf(0, 1, 2), 0.0, 3.0, 0.0, r), near = before, maxShift = 1.0))
    }

    @Test fun anotherRoomIsNotThisOne() {
        val other = listOf(Line(0.0, 0.0, 3.0, 0.0), Line(3.0, 0.0, 4.2, 2.5), Line(4.2, 2.5, 1.0, 4.0))
        assertNull(ell.fit(other))
    }

    @Test fun aRecentreCarriesTheFitAlong() {
        val f = RoomPlan.Fit(0.4, 1.0, 2.0, 3, 0)
        val moved = Pose(doubleArrayOf(0.5, 0.0, -0.3), Q.axisAngle(0.0, 1.0, 0.0, 0.9))
        val g = f.moved(moved)
        val a = f.apply(2.0, -1.0); val b = moved.apply(doubleArrayOf(a[0], 0.0, a[1])); val c = g.apply(2.0, -1.0)
        assertEquals(b[0], c[0], 1e-9); assertEquals(b[2], c[1], 1e-9)
    }

    @Test fun thePlanIsKeptAsTextAndReadBack() {
        val p = RoomPlan.parse(boxWithTable.toText())
        assertNotNull(p); assertEquals(4, p!!.corners.size); assertEquals(1, p.tables.size); assertEquals(4, p.walls.size)
        assertNull(RoomPlan.parse("something else")); assertNull(RoomPlan.parse("ultraxr room plan 1\ncorner 0 0\ncorner 1 0\n"))
    }
}
