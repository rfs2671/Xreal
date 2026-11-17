package dev.ultraxr

import dev.ultraxr.core.PointerInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The touchpad pointer's rules: when it takes over the right hand, what counts as a press, where it can go. */
class PointerInputTest {
    private val s = 1_000_000_000L
    @Before fun fresh() = PointerInput.reset()

    @Test fun itIsNotInChargeUntilTouchedAndLetsGoAfterAWhile() {
        assertFalse(PointerInput.active(5 * s))
        PointerInput.move(5 * s, 0.1, 0.0)
        assertTrue(PointerInput.active(5 * s + 1)); assertTrue(PointerInput.active(5 * s + PointerInput.ACTIVE_NS - 1))
        assertFalse("tracked hands get the right hand back", PointerInput.active(5 * s + PointerInput.ACTIVE_NS + 1))
    }

    @Test fun aTapIsAShortPress() {
        PointerInput.tap(10 * s)
        assertTrue(PointerInput.pressed(10 * s + 1)); assertTrue(PointerInput.pressed(10 * s + PointerInput.TAP_NS - 1))
        assertFalse(PointerInput.pressed(10 * s + PointerInput.TAP_NS + 1))
        assertEquals(1, PointerInput.clicks)
    }

    @Test fun aQuickKeyPressLastsLongEnoughToBeSeen() {
        PointerInput.hold(10 * s, 1, true); PointerInput.hold(10 * s + 2_000_000, 1, false)   // down and up 2 ms apart
        assertTrue(PointerInput.pressed(10 * s + 30_000_000))
        assertFalse(PointerInput.pressed(10 * s + PointerInput.TAP_NS + 1))
    }

    @Test fun holdingKeepsItPressedUntilEverySourceLetsGo() {
        PointerInput.hold(10 * s, 0, true); PointerInput.hold(11 * s, 1, true)
        assertTrue(PointerInput.pressed(20 * s))
        PointerInput.hold(21 * s, 0, false); assertTrue("the key is still down", PointerInput.pressed(22 * s))
        PointerInput.hold(23 * s, 1, false); assertFalse(PointerInput.pressed(24 * s))
        assertEquals("one press, not two", 1, PointerInput.clicks)
    }

    @Test fun thePointerStaysWithinReachAndCentres() {
        PointerInput.move(s, 5.0, 5.0)
        assertArrayEquals(doubleArrayOf(PointerInput.MAX_X, PointerInput.MAX_Y, 0.0), PointerInput.offset(), 1e-9)
        PointerInput.move(s, -50.0, -50.0)
        assertArrayEquals(doubleArrayOf(-PointerInput.MAX_X, PointerInput.MIN_Y, 0.0), PointerInput.offset(), 1e-9)
        PointerInput.hold(s, 0, true); PointerInput.center(2 * s)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), PointerInput.offset(), 1e-9)
        assertFalse(PointerInput.pressed(3 * s))
    }
}
