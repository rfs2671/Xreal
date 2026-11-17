package dev.ultraxr

import dev.ultraxr.core.PinchState
import dev.ultraxr.core.RecenterGesture
import dev.ultraxr.core.SessionJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionToolsTest {
    private val ms = 1_000_000L

    @Test fun bothHandsHeldRecentresOnce() {
        val g = RecenterGesture()
        assertFalse(g.update(0, true, true))
        assertFalse(g.update(1000 * ms, true, true)); assertEquals(1000.0 / 1500, g.progress, 1e-9)
        assertTrue(g.update(1500 * ms, true, true))
        assertFalse("not again while still held", g.update(5000 * ms, true, true))
        assertFalse(g.update(5100 * ms, true, false))
        assertFalse(g.update(5200 * ms, true, true))
        assertTrue("after letting go it can be done again", g.update(6700 * ms, true, true))
    }

    @Test fun oneHandOrABriefPinchDoesNotRecentre() {
        val g = RecenterGesture(); var fired = false
        for (t in 0..4000 step 100) fired = fired or g.update(t * ms, true, false)
        for (t in 5000..6400 step 100) fired = fired or g.update(t * ms, true, true)       // 1.4 s: not enough
        fired = fired or g.update(6500 * ms, false, true)
        assertFalse(fired)
    }

    @Test fun aPinchStartsOnceAndNeedsTheFingersApartToEnd() {
        val p = PinchState()
        assertFalse(p.update(0.06f)); assertFalse(p.pinching)
        assertTrue(p.update(0.015f))
        assertFalse("still the same pinch", p.update(0.025f)); assertTrue(p.pinching)
        assertFalse(p.update(0.04f)); assertFalse(p.pinching)
        assertTrue(p.update(0.01f))
        p.lost(); assertFalse(p.pinching)
    }

    @Test fun theJournalCountsWhatHappened() {
        val j = SessionJournal("com.example", "glasses, one view per eye", "joints", 1000)
        j.head(false, doubleArrayOf(0.0, 0.0, 0.0))
        j.head(true, doubleArrayOf(0.0, 0.0, 0.0)); j.head(true, doubleArrayOf(0.3, 0.0, 0.4)); j.head(true, doubleArrayOf(0.1, 0.0, 0.0))
        j.handFrames[1] = 30; j.pinches[1] = 2; j.recentred()
        val o = j.toJson(11000)
        assertEquals(10.0, o.getDouble("seconds"), 1e-9)
        assertEquals(0.75, o.getDouble("position_tracked_fraction"), 1e-9)
        assertEquals(0.5, o.getDouble("max_head_travel_m"), 1e-9)
        assertEquals(2, o.getInt("right_pinches")); assertEquals(1, o.getInt("recenters"))
    }
}
