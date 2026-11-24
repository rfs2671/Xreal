package dev.ultraxr

import dev.ultraxr.core.Hand
import dev.ultraxr.core.HandEdge
import dev.ultraxr.core.HandReader
import dev.ultraxr.desktop.HandPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One reading of a hand for the test scenes and the desktop (24 November 2026: "exact same hand tracking all around"). */
class HandReaderTest {
    private val zero = doubleArrayOf(0.0, 0.0, 0.0); private val q = doubleArrayOf(0.0, 0.0, 0.0, 1.0)

    private fun hand(tNs: Long, gap: Double, right: Boolean = true, x: Double = 0.15): Hand {
        val j = FloatArray(Hand.JOINTS * 3)
        fun put(i: Int, px: Double, py: Double, pz: Double) { j[i * 3] = px.toFloat(); j[i * 3 + 1] = py.toFloat(); j[i * 3 + 2] = pz.toFloat() }
        for (i in 0 until Hand.JOINTS) put(i, x, -0.15, -0.40)
        put(Hand.WRIST, x, -0.20, -0.36); put(Hand.INDEX_MCP, x - 0.02, -0.14, -0.40); put(Hand.LITTLE_MCP, x + 0.04, -0.15, -0.40); put(Hand.MIDDLE_MCP, x, -0.13, -0.40)
        put(5, x - 0.02, -0.14, -0.40); put(6, x - 0.02, -0.11, -0.41); put(7, x - 0.02, -0.09, -0.42); put(Hand.INDEX_TIP, x - 0.02, -0.07, -0.43)
        put(Hand.THUMB_TIP, x - 0.02 - gap, -0.07, -0.43)
        return Hand(right, j, tNs)
    }

    @Test fun theRayAndThePinchPointAreTheMiddleOfTheTwoFingertips() {
        val r = HandReader(); val t = 1_000_000_000L
        val p = r.read(listOf(hand(t, 0.06)), t, zero, q).single()
        assertEquals(0.15 - 0.02 - 0.03, p.pinchPoint[0], 1e-6); assertEquals(-0.07, p.pinchPoint[1], 1e-6)
        assertTrue(p.rayOrigin.contentEquals(p.pinchPoint)); assertNotNull(p.rayDir)
        assertNull("no ray without a head pose", r.read(listOf(hand(t, 0.06)), t, zero, q, rayWanted = false).single().rayDir)
    }

    @Test fun aResultIsUsedForFourTenthsOfASecondAndThePinchHistoryIsKeptWhileTheHandIsOutOfSight() {
        val r = HandReader(); var t = 1_000_000_000L
        for (i in 0..4) { r.read(listOf(hand(t, 0.09)), t, zero, q); t += 40_000_000L }
        assertTrue(r.read(listOf(hand(t, 0.09)), t + 350_000_000L, zero, q).isNotEmpty())
        assertTrue("older than 0.4 s is no hand", r.read(listOf(hand(t, 0.09)), t + 450_000_000L, zero, q).isEmpty())
        // out of sight for a long time, then the fingers are seen closed: a pinch begins on that first result
        t += 5_000_000_000L
        assertTrue(r.read(listOf(hand(t, 0.012)), t, zero, q).single().pinching); assertEquals(1L, r.pinches)
    }

    @Test fun theDesktopPointerRaysFromTheFingertipsLikeTheBall() {
        val t = 1_000_000_000L
        val aim = HandPointer().update(t, listOf(hand(t, 0.06)), zero, q)!!
        val probe = HandReader().read(listOf(hand(t, 0.06)), t, zero, q).single()
        assertTrue(aim.origin.contentEquals(probe.rayOrigin)); assertTrue(aim.dir.contentEquals(probe.rayDir!!))
    }

    @Test fun aHandOutsideThePictureIsMarkedAtItsEdgeAndOneInsideIsNot() {
        val th = 0.3976; val tv = 0.2238
        assertNull("straight ahead", HandEdge.mark(doubleArrayOf(0.0, 0.0, -0.5), th, tv))
        val right = HandEdge.mark(doubleArrayOf(0.5, 0.0, -0.5), th, tv)!!       // 45 degrees to the right: outside 21.7
        assertEquals(th * 0.86, right[0], 1e-9); assertEquals(0.0, right[1], 1e-9); assertEquals(-1.0, right[2], 1e-9)
        val low = HandEdge.mark(doubleArrayOf(0.0, -0.4, -0.4), th, tv)!!
        assertEquals(-tv * 0.80, low[1], 1e-9)
        val behind = HandEdge.mark(doubleArrayOf(-0.2, 0.0, 0.3), th, tv)!!        // behind the head: on the side it is on
        assertEquals(-th * 0.86, behind[0], 1e-9)
        assertFalse(HandEdge.mark(doubleArrayOf(0.1, 0.05, -0.5), th, tv) != null)
    }
}
