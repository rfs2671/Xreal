package dev.ultraxr

import dev.ultraxr.desktop.Dwell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Look-to-press. (The rail and the dock these tests were named for are gone: the shelf has its own, ShelfTest.) */
class RailTest {
    private val ms = 1_000_000L

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
}
