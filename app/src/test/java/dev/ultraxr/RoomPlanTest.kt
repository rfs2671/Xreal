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
    private fun close(f: RoomPlan.Fit, yaw: Double, tx: Double, tz: Double) = abs(RoomPlan.wrap(f.yaw - yaw)) < 0.06 && abs(f.tx - tx) < 0.2 && abs(f.tz - tz) < 0.2

    @Test fun anEllIsFoundAgainFromThreeOfItsWallsWhereverItLies() {
        val r = Random(1); var ok = 0; var wrong = 0
        repeat(300) {
            val yaw = r.nextDouble() * 6.28 - 3.14; val tx = r.nextDouble() * 6 - 3; val tz = r.nextDouble() * 6 - 3
            val k = r.nextInt(6); val f = ell.fit(found(ell, listOf(k, (k + 1) % 6, (k + 2) % 6), yaw, tx, tz, r))
            if (f != null) { if (close(f, yaw, tx, tz)) ok++ else { wrong++; println("  wrong: yaw off " + RoomPlan.wrap(f.yaw - yaw) + " shift off " + (f.tx - tx) + " " + (f.tz - tz) + " walls " + k) } }
        }
        println("RoomPlan ell: right $ok of 300, wrong $wrong")
        assertTrue("right $ok", ok >= 170); assertTrue("wrong $wrong", wrong <= 3)
    }

    /** The room the owner marked on 19 November 2026: 26 corners, walls from 0.4 to 2.8 m. */
    private val owners = RoomPlan(listOf(-1.26 to -1.02, -1.406 to -0.644, -0.157 to -0.113, 1.279 to 0.354, 1.692 to 0.466, 1.455 to 1.146, 1.069 to 2.488, 0.684 to 2.601,
        0.382 to 3.627, 0.192 to 4.007, 0.747 to 4.143, -0.211 to 6.207, -0.831 to 5.681, -3.502 to 4.995, -3.458 to 3.519, -2.838 to 2.994, -2.038 to 2.87, -1.251 to 3.225,
        -0.866 to 2.428, -0.701 to 1.79, -1.252 to 1.246, -3.225 to 1.596, -2.941 to 0.551, -2.454 to -0.325, -2.79 to -0.613, -2.367 to -1.342).map { doubleArrayOf(it.first, it.second) })

    @Test fun theOwnersRoomIsFoundFromLongStraightRunsThatPassItsNooks() {
        // What the wall finder gives is a straight run of 1.2 m or more: here, a line from one corner to a later one
        // wherever the corners between stay within 12 cm of it (a wall with a step or a skirting in it).
        val r = Random(11); val c = owners.corners; val runs = ArrayList<Line>()
        for (i in c.indices) for (span in 1..4) {
            val a = c[i]; val b = c[(i + span) % c.size]; val l = Line(a[0], a[1], b[0], b[1]); if (l.length < 1.2) continue
            var ok = true; for (k in 1 until span) { val m = c[(i + k) % c.size]; if (abs(-(m[0] - a[0]) * l.uz + (m[1] - a[1]) * l.ux) > 0.12) ok = false }
            if (ok) runs.add(l)
        }
        assertTrue("runs " + runs.size, runs.size >= 8)
        var right = 0; var wrong = 0; var none = 0; val rightBy = IntArray(5); val triedBy = IntArray(5)
        repeat(200) {
            val yaw = r.nextDouble() * 6.28 - 3.14; val tx = r.nextDouble() * 6 - 3; val tz = r.nextDouble() * 6 - 3; val f = RoomPlan.Fit(yaw, tx, tz, 0, 0)
            val pick = runs.shuffled(r).take(2 + r.nextInt(3))
            val seen = pick.map { val l = f.line(it); val a = 0.05 + 0.2 * r.nextDouble(); val b = 0.75 + 0.2 * r.nextDouble()
                Line(l.x0 + (l.x1 - l.x0) * a + r.nextGaussian() * 0.04, l.z0 + (l.z1 - l.z0) * a + r.nextGaussian() * 0.04, l.x0 + (l.x1 - l.x0) * b + r.nextGaussian() * 0.04, l.z0 + (l.z1 - l.z0) * b + r.nextGaussian() * 0.04) }
            val g = owners.fit(seen); triedBy[pick.size]++
            if (g == null) none++ else if (abs(RoomPlan.wrap(g.yaw - yaw)) < 0.07 && abs(g.tx - tx) < 0.25 && abs(g.tz - tz) < 0.25) { right++; rightBy[pick.size]++ } else wrong++
        }
        println("RoomPlan owner's room: right $right of 200, wrong $wrong, not said $none; with 2 runs " + rightBy[2] + " of " + triedBy[2] + ", 3 runs " + rightBy[3] + " of " + triedBy[3] + ", 4 runs " + rightBy[4] + " of " + triedBy[4])
        // Two straight runs seldom say where one is in a room with many walls running the same two ways; what matters
        // is that nothing is placed wrongly, and that more runs settle it more often.
        assertTrue("wrong $wrong", wrong <= 2); assertTrue("right $right", right >= 30); assertTrue(rightBy[4] * triedBy[2] >= rightBy[2] * triedBy[4])
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
