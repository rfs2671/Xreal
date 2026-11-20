package dev.ultraxr

import dev.ultraxr.desktop.DesktopWindow
import dev.ultraxr.desktop.Geometry
import dev.ultraxr.desktop.Glide
import dev.ultraxr.desktop.Motion
import dev.ultraxr.desktop.Shelf
import dev.ultraxr.desktop.Sight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shelf: where its marks stand, that a ray at a mark finds that mark, that all of it is in view, and what moves for how long. */
class ShelfTest {
    private val origin = doubleArrayOf(0.0, 0.0, 0.0)
    private fun deg(r: Double) = Math.toDegrees(r)
    // half of what an eye sees, from the factory calibration: tan 0.3875 across, 0.2191 up and down
    private val halfAcross = Math.atan(0.3875); private val halfUp = Math.atan(0.2191)

    @Test fun theRowSlidesOnlyWhenTheFocusPassesItsEnd() {
        assertEquals("fewer marks than fit: nothing to slide", 0, Shelf.first(3, 0, 5))
        assertEquals(0, Shelf.first(6, 0, 12))
        assertEquals("past the last in view: one along", 1, Shelf.first(7, 0, 12))
        assertEquals("back inside the row: it stays", 1, Shelf.first(4, 1, 12))
        assertEquals("past the first in view: back", 0, Shelf.first(0, 1, 12))
        assertEquals("the last mark: the row ends with it", 5, Shelf.first(11, 0, 12))
        assertEquals("a row that became shorter", 0, Shelf.first(2, 9, 4))
        var first = 0
        for (focus in 0 until 12) { first = Shelf.first(focus, first, 12); assertTrue(focus in first until first + Shelf.IN_VIEW) }
        for (focus in 11 downTo 0) { first = Shelf.first(focus, first, 12); assertTrue(focus in first until first + Shelf.IN_VIEW) }
        assertEquals(7, Shelf.shown(12)); assertEquals(3, Shelf.shown(3)); assertEquals(0, Shelf.shown(0))
    }

    @Test fun theMarksStandOnAnArcEightDegreesUnderTheLineOfSightAndClearOfEachOther() {
        for (n in 1..Shelf.IN_VIEW) {
            for (slot in 0 until n) {
                val c = Shelf.markCentre(origin, 0.0, slot.toDouble(), n)
                assertEquals("as far away as a new window", Shelf.DISTANCE, Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]), 1e-9)
                assertEquals(-8.0, deg(Geometry.pitchOf(c)), 1e-9)
            }
            assertEquals("the row is centred", 0.0, Shelf.yawOffset(0.0, n) + Shelf.yawOffset(n - 1.0, n), 1e-12)
            assertTrue("the first place is the leftmost", Shelf.yawOffset(0.0, n) >= Shelf.yawOffset(n - 1.0, n))
        }
        // the mark in focus is a quarter larger and still clear of the one beside it
        assertTrue(Shelf.SPACING > Shelf.MARK_ANGLE * Shelf.FOCUS_SCALE + Shelf.MARK_ANGLE + Math.toRadians(1.0))
    }

    @Test fun allOfTheShelfAndTheStatusLineIsInView() {
        // across: the last of seven marks, in focus, and its name at its widest
        val end = Math.abs(Shelf.yawOffset(0.0, Shelf.IN_VIEW))
        assertTrue(end + Shelf.MARK_ANGLE * Shelf.FOCUS_SCALE < halfAcross)
        assertTrue("the name of the last mark", end + Shelf.NAME_WIDTH / 2 < halfAcross)
        // down: the name's letters, tails and all (the x-height is in the middle of its strip)
        assertTrue(-(Shelf.NAME_PITCH - Sight.NAME) < halfUp)
        assertTrue("the name is under the enlarged mark", Shelf.NAME_PITCH + Sight.NAME < Shelf.PITCH - Shelf.MARK_ANGLE * Shelf.FOCUS_SCALE)
        // up: the status line's letters
        assertTrue(Shelf.STATUS_PITCH + Sight.BODY * 1.2 < halfUp)
        // text is at least as large as the design asks: 0.55 degrees of x-height, 0.7 for the name
        assertTrue(deg(Sight.BODY) >= 0.55 - 1e-9); assertTrue(deg(Sight.NAME) >= 0.7 - 1e-9)
    }

    @Test fun aRayAtAMarkFindsThatMarkAndBetweenMarksFindsNothing() {
        for (yaw in listOf(0.0, Math.toRadians(90.0), Math.toRadians(-140.0))) for (count in listOf(1, 4, 7, 12)) for (first in 0..maxOf(0, count - Shelf.IN_VIEW)) {
            val n = Shelf.shown(count)
            for (slot in 0 until n) {
                val c = Shelf.markCentre(origin, yaw, slot.toDouble(), n)
                assertEquals(first + slot, Shelf.hit(origin, c, origin, yaw, first, count))
                // anywhere on the mark as it is drawn, at rest and enlarged
                for (a in 0 until 8) {
                    val r = Shelf.MARK_RADIUS * Shelf.FOCUS_SCALE * 0.95; val up = Math.sin(a * Math.PI / 4) * r; val side = Math.cos(a * Math.PI / 4) * r
                    val at = Geometry.wrap(yaw + Shelf.yawOffset(slot.toDouble(), n))
                    val p = doubleArrayOf(c[0] + Math.cos(at) * side, c[1] + up, c[2] - Math.sin(at) * side)
                    assertEquals(first + slot, Shelf.hit(origin, p, origin, yaw, first, count))
                }
            }
            if (n >= 2) assertNull("half way between two marks", Shelf.hit(origin, Shelf.markCentre(origin, yaw, 0.5, n), origin, yaw, first, count))
            assertNull("level with the eyes is not the shelf", Shelf.hit(origin, Geometry.direction(yaw, 0.0), origin, yaw, first, count))
            assertNull("nor is beside the row", Shelf.hit(origin, Shelf.markCentre(origin, yaw, -1.0, n), origin, yaw, first, count))
            assertNull("nor behind the wearer", Shelf.hit(origin, Geometry.direction(yaw + Math.PI, Shelf.PITCH), origin, yaw, first, count))
        }
        assertNull("an empty shelf", Shelf.hit(origin, Geometry.direction(0.0, Shelf.PITCH), origin, 0.0, 0, 0))
    }

    @Test fun aNewWindowStandsBetweenTheStatusLineAndTheShelfWithAllOfItInView() {
        val w = DesktopWindow(1, "A", "u")
        val d = w.shownDistance(); val pitch = Shelf.clearPitch(w.shownBelow(), d)
        val handleBottom = pitch - Math.atan(w.shownBelow() / d)
        assertTrue("the handle ends above the resting marks", handleBottom > Shelf.PITCH + Shelf.MARK_ANGLE)
        assertTrue("and the page above the mark in focus", pitch - Math.atan(w.shownHeight() / 2 / d) > Shelf.PITCH + Shelf.MARK_ANGLE * Shelf.FOCUS_SCALE)
        // the title: above the top-left bracket, the tops of its letters about one x-height above the middle of its strip
        val x = Sight.metres(Sight.BODY, d)
        val titleTop = w.shownHeight() / 2 + Sight.metres(Sight.BRACKET_GAP, d) + Sight.metres(Sight.LINE_LIT, d) + (Sight.STRIP * Sight.TITLE_RISE + 1.0) * x
        val statusBottom = Shelf.STATUS_PITCH - Sight.BODY
        assertTrue("the title ends under the status line", pitch + Math.atan(titleTop / d) < statusBottom - Math.toRadians(0.2))
        assertTrue("the status line is in view", Shelf.STATUS_PITCH + Sight.BODY * 1.2 < halfUp)
        assertTrue("across, with its brackets", Math.atan((w.shownWidth() / 2 + Sight.metres(Sight.BRACKET_GAP + Sight.LINE_LIT * 4, d)) / d) < halfAcross)
        // the brackets' lower arms are drawn in the space between the page and the handle
        assertTrue(Sight.metres(Sight.BRACKET_GAP + Sight.LINE_LIT, d) < w.shownHandleGap())
        // the same at every distance the settings offer, with the size that goes with it
        for (far in listOf(1.0, 2.0, 3.0, 4.0)) {
            val v = DesktopWindow(2, "B", "u", distance = far, width = DesktopWindow.DEFAULT_WIDTH * far / DesktopWindow.DEFAULT_DISTANCE)
            assertEquals("as large to the eye", w.shownWidth() / d, v.shownWidth() / far, 1e-9)
            assertEquals(pitch, Shelf.clearPitch(v.shownBelow(), far), 1e-9)
        }
    }

    @Test fun motionTakesTheTimeTheDesignGivesItAndNoneWhenMotionIsReduced() {
        assertEquals(180_000_000L, Motion.OPEN_NS); assertEquals(140_000_000L, Motion.CLOSE_NS); assertEquals(90_000_000L, Motion.FOCUS_NS)
        assertEquals(0.0, Motion.progress(1000, 1000, Motion.OPEN_NS, false), 1e-12)
        assertEquals(0.5, Motion.progress(0, 90_000_000L, Motion.OPEN_NS, false), 1e-12)
        assertEquals(1.0, Motion.progress(0, 180_000_000L, Motion.OPEN_NS, false), 1e-12)
        assertEquals("it does not run on", 1.0, Motion.progress(0, 999_000_000L, Motion.OPEN_NS, false), 1e-12)
        assertEquals("reduce motion: at its end at once", 1.0, Motion.progress(0, 0, Motion.OPEN_NS, true), 1e-12)
        assertEquals(0.0, Motion.ease(0.0), 1e-12); assertEquals(1.0, Motion.ease(1.0), 1e-12)
        var last = 0.0
        for (i in 1..20) { val e = Motion.ease(i / 20.0); assertTrue("it never goes back", e > last); last = e }

        val g = Glide(0.0)
        assertEquals(0.0, g.toward(1.0, 0, Motion.FOCUS_NS, false), 1e-12)
        val half = g.toward(1.0, 45_000_000L, Motion.FOCUS_NS, false); assertTrue(half > 0.5 && half < 1.0)
        assertEquals("there in 90 ms", 1.0, g.toward(1.0, 90_000_000L, Motion.FOCUS_NS, false), 1e-12); assertTrue(g.resting)
        // sent back half way through, it turns from where it is
        g.toward(0.0, 100_000_000L, Motion.FOCUS_NS, false); val mid = g.toward(0.0, 130_000_000L, Motion.FOCUS_NS, false)
        assertTrue(mid > 0.0 && mid < 1.0)
        assertEquals("from where it was, it starts without a jump", mid, g.toward(1.0, 130_000_000L, Motion.FOCUS_NS, false), 1e-12)
        assertEquals(1.0, g.toward(1.0, 220_000_000L, Motion.FOCUS_NS, false), 1e-12)
        assertEquals("reduce motion: no glide", 0.0, g.toward(0.0, 220_000_001L, Motion.FOCUS_NS, true), 1e-12)
    }
}
