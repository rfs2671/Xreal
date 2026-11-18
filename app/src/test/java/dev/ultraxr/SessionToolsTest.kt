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

    @Test fun aPinchCanBeJudgedInFingerLengths() {
        // A hand tracked 30 % too large: its fingertips never come under 18 mm, but they do come under 0.28 finger lengths.
        val p = PinchState()
        assertFalse(p.update(0.060f, 0.100f))
        assertTrue("24 mm on a 100 mm finger is together", p.update(0.024f, 0.100f))
        assertFalse(p.update(0.040f, 0.100f)); assertTrue("40 mm is not yet apart on that hand", p.pinching)
        assertFalse(p.update(0.055f, 0.100f)); assertFalse(p.pinching)
        // without a finger length the millimetre rule is all there is
        assertFalse(PinchState().update(0.024f))
    }

    @Test fun oneStrayResultNeitherStartsNorEndsAPinch() {
        val p = PinchState(frames = 2)
        assertFalse(p.update(0.010f)); assertFalse("one result is not enough", p.pinching)
        assertFalse(p.update(0.060f)); assertFalse(p.update(0.010f)); assertFalse(p.pinching)
        assertTrue("two in a row", p.update(0.010f)); assertTrue(p.pinching)
        assertFalse(p.update(0.060f)); assertTrue("one open result does not end it", p.pinching)
        assertFalse(p.update(0.010f)); assertFalse(p.update(0.060f)); assertTrue(p.pinching)
        assertFalse(p.update(0.060f)); assertFalse(p.pinching)
    }

    @Test fun aPinchSurvivesLosingTheHandBrieflyButNotForLong() {
        val ms = 1_000_000L
        val p = PinchState(); assertTrue(p.update(0.010f))
        assertTrue(p.lost(1000 * ms)); assertTrue(p.lost(1100 * ms)); assertTrue("fingers hid each other for 0.2 s", p.pinching)
        assertFalse(p.update(0.012f)); assertTrue("the same pinch goes on", p.pinching)
        assertTrue(p.lost(2000 * ms)); assertFalse("gone for 0.3 s", p.lost(2300 * ms)); assertFalse(p.pinching)
        // a hand that was not pinching is simply lost
        val q = PinchState(); assertFalse(q.lost(5000 * ms)); assertFalse(q.pinching)
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
