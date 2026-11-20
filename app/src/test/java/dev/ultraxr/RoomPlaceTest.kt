package dev.ultraxr

import dev.ultraxr.core.Hand
import dev.ultraxr.desktop.Desktop
import dev.ultraxr.desktop.Geometry
import dev.ultraxr.desktop.HandPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Windows that keep their place in the room while the wearer moves, and the hand as a pointer. */
class RoomPlaceTest {
    private val zero = doubleArrayOf(0.0, 0.0, 0.0)
    private fun centre(d: Desktop, id: Int): DoubleArray { val w = d.find(id)!!; return Geometry.centre(d.anchorFor(w, zero), d.worldYaw(w), w.shownPitch(), w.shownDistance()) }

    @Test fun aWindowStaysWhereItIsWhenTheWearerWalks() {
        val d = Desktop(); d.positional = true; d.here = doubleArrayOf(0.0, 1.6, 0.0)
        val w = d.add("A", "u", headYaw = 0.0)!!
        val was = centre(d, w.id)
        assertEquals(-2.0, was[2], 1e-9); assertEquals(1.6, was[1], 1e-9)
        d.here = doubleArrayOf(1.0, 1.6, -0.5)      // the wearer walks: the window does not
        assertArrayEquals(was, centre(d, w.id))
        // a ray from the new place at the window's middle hits its middle
        val dir = doubleArrayOf(was[0] - 1.0, 0.0, was[2] + 0.5); val n = Math.hypot(dir[0], dir[2]); dir[0] /= n; dir[2] /= n
        val hit = d.hit(d.here, dir, zero)
        assertNotNull(hit); assertEquals(0.5, hit!!.u, 0.02); assertEquals(0.5, hit.v, 0.02)
        // and from where the wearer now stands it lies to the left
        assertTrue(d.seenYaw(w) > Math.toRadians(20.0))
    }

    @Test fun takenUpFromAnotherPlaceItDoesNotJumpAndANewOneOpensInFrontOfTheWearer() {
        val d = Desktop(); d.positional = true; d.here = doubleArrayOf(0.0, 1.6, 0.0)
        val w = d.add("A", "u", headYaw = 0.0)!!
        val was = centre(d, w.id)
        d.here = doubleArrayOf(1.2, 1.6, 0.3)
        d.startMove(w.id)
        assertArrayEquals(was, centre(d, w.id))      // taking it up moves nothing
        assertEquals(1.2, w.ax, 1e-9)                // but it is now carried round where the wearer stands
        val b = d.add("B", "u", headYaw = Math.toRadians(90.0))!!
        val cb = centre(d, b.id)
        assertEquals(1.2 - 2.0, cb[0], 1e-6); assertEquals(0.3, cb[2], 1e-6)      // two metres to the wearer's left, from where they are
    }

    @Test fun withoutPositionEveryWindowIsRoundTheOnePoint() {
        val d = Desktop(); d.here = doubleArrayOf(3.0, 0.0, 3.0)
        val w = d.add("A", "u")!!
        assertFalse(d.positional)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, -2.0), centre(d, w.id))
    }

    private fun assertArrayEquals(a: DoubleArray, b: DoubleArray) { for (i in 0..2) assertEquals(a[i], b[i], 1e-6) }

    /** A hand 40 cm in front of the head and a little to the right, its fingertips [gap] metres apart. */
    private fun hand(tNs: Long, gap: Double, right: Boolean = true, x: Double = 0.15): Hand {
        val j = FloatArray(Hand.JOINTS * 3)
        fun put(i: Int, px: Double, py: Double, pz: Double) { j[i * 3] = px.toFloat(); j[i * 3 + 1] = py.toFloat(); j[i * 3 + 2] = pz.toFloat() }
        for (i in 0 until Hand.JOINTS) put(i, x, -0.15, -0.40)
        put(Hand.WRIST, x, -0.20, -0.36); put(Hand.INDEX_MCP, x - 0.02, -0.14, -0.40); put(Hand.LITTLE_MCP, x + 0.04, -0.15, -0.40); put(Hand.MIDDLE_MCP, x, -0.13, -0.40)
        put(5, x - 0.02, -0.14, -0.40); put(6, x - 0.02, -0.11, -0.41); put(7, x - 0.02, -0.09, -0.42); put(Hand.INDEX_TIP, x - 0.02, -0.07, -0.43)
        put(Hand.THUMB_TIP, x - 0.02 - gap, -0.07, -0.43)
        return Hand(right, j, tNs)
    }

    @Test fun theHandPointsAlongTheArmAndPinchesByTheMeasuredRule() {
        val p = HandPointer(); val q = doubleArrayOf(0.0, 0.0, 0.0, 1.0); var t = 1_000_000_000L
        assertNull(p.update(t, emptyList(), zero, q))
        var aim = p.update(t, listOf(hand(t, 0.09)), zero, q)!!
        assertTrue(aim.right); assertFalse(aim.pinching)
        // from the right shoulder (17 cm right, 22 cm down, 6 cm back) through the palm: forwards, and upwards
        assertTrue(aim.dir[2] < -0.8); assertTrue(aim.dir[1] > 0.0); assertTrue(Math.abs(aim.dir[0]) < 0.2)
        for (i in 1..4) { t += 40_000_000L; aim = p.update(t, listOf(hand(t, 0.09)), zero, q)!! }
        t += 40_000_000L; aim = p.update(t, listOf(hand(t, 0.015)), zero, q)!!
        assertTrue("fingertips 15 mm apart are a pinch", aim.pinching); assertEquals(1L, p.pinches)
        // the tracker loses the hand as the fingers touch: the pinch is kept for a quarter of a second, not longer
        t += 100_000_000L; assertTrue(p.update(t, emptyList(), zero, q)!!.pinching)
        t += 400_000_000L; assertNull(p.update(t, emptyList(), zero, q))
        // an old result is no hand
        assertNull(p.update(t, listOf(hand(t - 500_000_000L, 0.09)), zero, q))
        // with both hands in view the one that pinches points
        t += 40_000_000L; p.update(t, listOf(hand(t, 0.09, right = false, x = -0.15), hand(t, 0.09)), zero, q)
        t += 40_000_000L; aim = p.update(t, listOf(hand(t, 0.012, right = false, x = -0.15), hand(t, 0.09)), zero, q)!!
        assertFalse(aim.right); assertTrue(aim.pinching)
    }
}
