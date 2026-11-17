package dev.ultraxr

import dev.ultraxr.desktop.Dwell
import dev.ultraxr.desktop.Geometry
import dev.ultraxr.desktop.Rail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RailTest {
    private val ms = 1_000_000L
    private val origin = doubleArrayOf(0.0, 0.0, 0.0)

    @Test fun restingTheGazePressesOnceAfterTheHold() {
        val d = Dwell()
        assertNull(d.update(0, "a"))
        assertNull(d.update(200 * ms, "a")); assertEquals("still arming", 0.0, d.progress, 1e-9)
        assertNull(d.update(700 * ms, "a")); assertEquals(0.5, d.progress, 1e-9)
        assertEquals("a", d.update(1150 * ms, "a"))
        assertNull("it does not repeat while the gaze stays", d.update(3000 * ms, "a"))
        assertNull(d.update(3100 * ms, null))
        assertNull(d.update(3200 * ms, "a"))
        assertEquals("after looking away it can be pressed again", "a", d.update(4400 * ms, "a"))
    }

    @Test fun sweepingAcrossTargetsPressesNothing() {
        val d = Dwell(); var t = 0L; var fired = 0
        repeat(40) { i -> if (d.update(t, if (i % 2 == 0) "a" else "b") != null) fired++; t += 200 * ms }
        assertEquals(0, fired)
    }

    @Test fun movingToAnotherTargetRestartsTheHold() {
        val d = Dwell()
        d.update(0, "a"); d.update(1000 * ms, "a")
        assertNull(d.update(1100 * ms, "b"))
        assertNull(d.update(2000 * ms, "b"))
        assertEquals("b", d.update(2300 * ms, "b"))
    }

    @Test fun dockTilesAreFoundByLookingDownAtThem() {
        val n = 4
        fun look(tile: Int): Int? {
            val x = ((tile + 0.5) / n - 0.5) * Rail.DOCK_WIDTH
            // a point on the dock plane, x to the right of its centre
            val c = Geometry.centre(origin, 0.0, Rail.DOCK_PITCH, Rail.DOCK_DISTANCE)
            return Rail.dockTile(origin, doubleArrayOf(c[0] + x, c[1], c[2]), origin, 0.0, n)
        }
        for (i in 0 until n) assertEquals(i, look(i))
        assertNull("looking straight ahead is not on the dock", Rail.dockTile(origin, Geometry.direction(0.0, 0.0), origin, 0.0, n))
        assertNull("nor is looking at the rail itself", Rail.dockTile(origin, Geometry.direction(0.0, Rail.PITCH), origin, 0.0, n))
        assertNull(Rail.dockTile(origin, Geometry.direction(Math.toRadians(40.0), Rail.DOCK_PITCH), origin, 0.0, n))
        assertEquals("the dock follows its heading", 1, Rail.dockTile(origin, run {
            val yaw = Math.toRadians(90.0); val c = Geometry.centre(origin, yaw, Rail.DOCK_PITCH, Rail.DOCK_DISTANCE)
            val x = ((1 + 0.5) / n - 0.5) * Rail.DOCK_WIDTH
            doubleArrayOf(c[0] + Math.cos(yaw) * x, c[1], c[2] - Math.sin(yaw) * x)
        }, origin, Math.toRadians(90.0), n))
    }

    @Test fun theRailAndDockSitBelowADefaultWindowAndInsideAHeadTilt() {
        // a default window's lower edge, in degrees below eye level
        val w = dev.ultraxr.desktop.DesktopWindow(1, "A", "u")
        val bottom = Math.toDegrees(Math.atan((w.height / 2) / w.distance))
        assertTrue("the rail is below the window", -Math.toDegrees(Rail.PITCH) > bottom)
        // looking at the dock keeps it within the vertical field of view (about +-12.4 degrees)
        val halfDock = Math.toDegrees(Math.atan((Rail.DOCK_HEIGHT / 2) / Rail.DOCK_DISTANCE))
        assertTrue(halfDock < 12.4 * 0.5)
        // and its width within the central two thirds of the horizontal view (+-21 degrees)
        assertTrue(Math.toDegrees(Math.atan((Rail.DOCK_WIDTH / 2) / Rail.DOCK_DISTANCE)) < 21.0 * 0.75)
        assertNull(Rail.tileAt(1.0, 4)); assertNull(Rail.tileAt(-0.01, 4)); assertEquals(3, Rail.tileAt(0.99, 4))
    }
}
