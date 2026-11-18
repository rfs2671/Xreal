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
        assertTrue(p.update(0.025f))
        assertFalse("still the same pinch", p.update(0.040f)); assertTrue(p.pinching)
        assertFalse(p.update(0.05f)); assertFalse(p.pinching)
        assertTrue(p.update(0.02f))
        p.lost(); assertFalse(p.pinching)
    }

    @Test fun theOwnersRecordedPinchesAreCaught() {
        // The right hand's fingertip distances from the recording of 17 November 2026, every third result, in mm.
        val mm = intArrayOf(73, 44, 40, 52, 106, 111, 94, 111, 87, 54, 85, 101, 97, 35, 28, 39, 65, 76, 94, 94, 84, 69, 79, 49, 64, 49, 74, 79, 57, 71, 59,
            53, 59, 53, 82, 100, 92, 57, 53, 62, 40, 38, 41, 37, 26, 20, 45, 27, 25, 65, 40, 38, 54, 31, 49, 40, 25, 23)
        val now = PinchState(); val first = PinchState(0.018f, 0.032f, 0.28f, 0.48f, frames = 2)
        var caught = 0; var before = 0
        for (v in mm) { if (now.update(v / 1000f, 0.071f)) caught++; if (first.update(v / 1000f, 0.071f)) before++ }
        assertEquals("the first rule caught none of them", 0, before)
        assertTrue("five deliberate pinches, at least four caught: $caught", caught in 4..6)
    }

    @Test fun aPinchCanBeJudgedInFingerLengths() {
        // A hand tracked 30 % too large: its fingertips never come under 33 mm, but they do come under 0.46 finger lengths.
        val p = PinchState()
        assertFalse(p.update(0.080f, 0.100f))
        assertTrue("40 mm on a 100 mm finger is together", p.update(0.040f, 0.100f))
        assertFalse(p.update(0.055f, 0.100f)); assertTrue("55 mm is not yet apart on that hand", p.pinching)
        assertFalse(p.update(0.070f, 0.100f)); assertFalse(p.pinching)
        // without a finger length the millimetre rule is all there is
        assertFalse(PinchState().update(0.040f))
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
