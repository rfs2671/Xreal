package dev.ultraxr

import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import dev.ultraxr.core.RoomRecall
import dev.ultraxr.core.RoomRecall.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Finding the room again after position was lost ([RoomRecall]), on made-up floor plans. */
class RoomRecallTest {
    // a room 4 m by 3 m
    private val room = listOf(Line(-2.0, -1.5, 2.0, -1.5), Line(2.0, -1.5, 2.0, 1.5), Line(2.0, 1.5, -2.0, 1.5), Line(-2.0, 1.5, -2.0, -1.5))
    private fun seen(walls: List<Line>, dx: Double, dz: Double, r: Random, noise: Double = 0.03) =
        walls.map { Line(it.x0 - dx + r.nextGaussian() * noise, it.z0 - dz + r.nextGaussian() * noise, it.x1 - dx + r.nextGaussian() * noise, it.z1 - dz + r.nextGaussian() * noise) }
    private val s = 1_000_000_000L

    @Test fun twoWallsOfACornerGiveTheStepsBack() {
        val r = Random(1)
        // the wearer walked 0.9 m and 0.6 m unseen: everything is found that much short
        val d = RoomRecall.solve(room, seen(room.subList(0, 2), 0.9, -0.6, r), 3.0)
        assertNotNull(d); assertEquals(0.9, d!![0], 0.08); assertEquals(-0.6, d[1], 0.08)
    }

    @Test fun partsOfWallsAreEnough() {
        val r = Random(2)
        val parts = listOf(Line(-0.5, -1.5, 1.6, -1.5), Line(2.0, -1.0, 2.0, 0.9))
        val d = RoomRecall.solve(room, seen(parts, -1.2, 0.4, r), 3.0)
        assertNotNull(d); assertEquals(-1.2, d!![0], 0.08); assertEquals(0.4, d[1], 0.08)
    }

    @Test fun oneWallOrTwoParallelOnesSayNothing() {
        val r = Random(3)
        assertNull(RoomRecall.solve(room, seen(room.subList(0, 1), 0.5, 0.5, r), 3.0))
        assertNull(RoomRecall.solve(room, seen(listOf(room[0], room[2]), 0.5, 0.5, r), 3.0))
    }

    @Test fun aShiftLongerThanTheWearerCouldHaveWalkedIsNotBelieved() {
        val r = Random(4)
        assertNull(RoomRecall.solve(room, seen(room.subList(0, 2), 2.5, 0.0, r), 1.0))
    }

    @Test fun aRoomThatLooksTheSameFromTwoPlacesIsLeftAlone() {
        // a long wall with two like cross walls 0.8 m apart: one cross wall seen could be either
        val old = listOf(Line(-4.0, 0.0, 4.0, 0.0), Line(-0.4, 0.0, -0.4, 2.0), Line(0.4, 0.0, 0.4, 2.0))
        val now = listOf(Line(-1.0, 0.0, 1.0, 0.0), Line(0.0, 0.0, 0.0, 2.0))
        assertNull(RoomRecall.solve(old, now, 3.0))
    }

    @Test fun anotherRoomIsNotMistakenForThisOne() {
        val other = listOf(Line(0.0, 0.0, 3.0, 2.2), Line(3.0, 2.2, 1.0, 5.0))       // walls at other angles
        assertNull(RoomRecall.solve(room, other, 6.0))
    }

    @Test fun manyLossesAtRandomAreUndoneToAHandWidth() {
        val r = Random(5); var worst = 0.0; var found = 0; var wrong = 0
        repeat(500) {
            val dx = r.nextDouble() * 2 - 1.0; val dz = r.nextDouble() * 1.4 - 0.7
            val k = r.nextInt(4); val two = listOf(room[k], room[(k + 1) % 4])
            val d = RoomRecall.solve(room, seen(two, dx, dz, r, 0.04), 2.0) ?: return@repeat
            found++; val e = Math.hypot(d[0] - dx, d[1] - dz); if (e > 0.20) wrong++; worst = maxOf(worst, e)
        }
        println("RoomRecall: found $found of 500, wrong $wrong, worst $worst m")
        assertTrue("found $found of 500", found >= 400)
        assertEquals("worst $worst m", 0, wrong)
    }

    @Test fun rememberedOnlyWhileSeenAndLookedForAfterALoss() {
        val r = Random(6); val m = RoomRecall()
        m.see(room.subList(0, 1)); assertFalse("one wall cannot place a room", m.lost(0, 3.0))
        m.see(room); assertTrue(m.lost(10 * s, 3.0)); assertTrue(m.waiting)
        m.see(seen(room, 1.0, 0.5, r))                                   // what is seen while waiting must not replace the memory
        assertNull(m.find(11 * s, emptyList()))
        val d = m.find(16 * s, seen(room.subList(1, 3), 1.0, 0.5, r))
        assertNotNull(d); assertEquals(1.0, d!![0], 0.08); assertEquals(0.5, d[1], 0.08); assertFalse(m.waiting)
    }

    @Test fun aSecondLossAddsToHowFarTheWearerMayHaveGone() {
        val r = Random(7); val m = RoomRecall(); m.see(room)
        m.lost(0, 0.5)                                                   // 0.5 s: 1.05 m at most
        assertNull(m.find(1 * s, seen(room.subList(0, 2), 1.6, 0.0, r)))
        m.lost(2 * s, 0.5)                                               // and 0.5 s more: 2.1 m
        assertNotNull(m.find(3 * s, seen(room.subList(0, 2), 1.6, 0.0, r)))
    }

    @Test fun givenUpAfterAMinuteAndTheNewWallsTaken() {
        val m = RoomRecall(); m.see(room); m.lost(0, 3.0)
        val other = listOf(Line(10.0, 10.0, 13.0, 12.2), Line(13.0, 12.2, 11.0, 15.0))
        assertNull(m.find(61 * s, other)); assertFalse(m.waiting); assertEquals(2, m.walls)
    }

    @Test fun aRecentreMovesTheMemoryWithTheWorld() {
        val r = Random(8); val m = RoomRecall(); m.see(room)
        val turn = Pose(doubleArrayOf(0.5, 0.0, -0.2), Q.axisAngle(0.0, 1.0, 0.0, 0.6))
        m.moved(turn); m.lost(0, 2.0)
        val moved = room.map { val a = turn.apply(doubleArrayOf(it.x0, 0.0, it.z0)); val b = turn.apply(doubleArrayOf(it.x1, 0.0, it.z1)); Line(a[0], a[2], b[0], b[2]) }
        val d = m.find(1 * s, seen(moved.subList(0, 2), 0.7, 0.3, r))
        assertNotNull(d); assertEquals(0.7, d!![0], 0.08); assertEquals(0.3, d[1], 0.08)
    }
}
