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
        val now = PinchState(); val first = PinchState(0.018f, 0.032f, 0.28f, 0.48f, frames = 2, stroke = false)
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

    // Thumb-to-index gaps (mm) from the owner's recording of 19 November 2026, replayed through the hand tracker:
    // the right hand raised and pinching about every two seconds (seen from behind), the left hand doing the same,
    // and the right hand resting low holding the phone (no pinch meant).
    private val rightPinching = intArrayOf(
        72, 70, 70, 70, 71, 71, 91, 110, 119, 121, 115, 105, 102, 86, 74, 69, 66, 63, 62, 65, 94, 107, 107, 103, 97, 92, 92, 91, 86, 81,
        72, 62, 56, 52, 50, 49, 46, 51, 85, 103, 110, 113, 111, 110, 110, 106, 97, 82, 67, 55, 49, 51, 51, 56, 58, 62, 70, 74, 74, 77,
        78, 79, 79, 76, 76, 77, 79, 80, 71, 57, 50, 50, 47, 47, 51, 54, 57, 56, 57, 60, 64, 68, 73, 77, 78, 79, 82, 85, 88, 86,
        86, 85, 80, 79, 62, 54, 48, 45, 40, 47, 60, 64, 74, 79, 82, 88, 88, 90, 90, 90, 91, 89, 92, 91, 91, 89, 83, 82, 56, 45,
        39, 35, 33, 32, 30, 30, 31, 39, 61, 69, 71, 73, 74, 73, 74, 74, 73, 73, 70, 71)
    private val leftPinching = intArrayOf(
        49, 48, 46, 45, 43, 43, 40, 38, 37, 36, 34, 32, 79, 111, 121, 122, 121, 114, 101, 93, 86, 89, 87, 86, 86, 85, 84, 84, 84, 83,
        77, 64, 52, 49, 43, 40, 34, 33, 31, 29, 28, 27, 25, 31, 75, 94, 106, 105, 103, 102, 96, 96, 97, 95, 93, 93, 90, 58, 41, 27,
        22, 20, 19, 19, 18, 16, 15, 58, 110, 122, 126, 126, 122, 123, 119, 117, 115, 117, 119, 117, 114, 110, 102, 99, 91, 72, 59, 55, 50, 42,
        45, 42, 40, 37, 64, 103, 114, 117, 122, 120, 119, 117, 106, 98, 90, 82, 76, 80, 80, 71, 69, 68, 68, 69, 69, 65, 70, 68, 67, 71,
        91, 96, 94, 97, 101, 105, 111, 106, 105, 95, 90, 55, 42, 35, 35, 34, 32, 32, 31, 32, 30, 45, 82, 104, 111, 113, 111, 107, 104, 106,
        103, 93, 91, 91, 72, 67, 64, 63, 61, 58, 58, 63, 84, 88, 87, 87, 96, 115, 115, 118, 109, 98, 89, 74, 50, 40, 33, 31, 31, 31,
        31, 30, 30, 53, 74, 91, 98, 105, 104, 98, 95, 87, 81, 77, 75, 74, 73, 66, 62, 66, 64, 63, 58, 54, 55, 50, 52, 47)
    private val rightResting = intArrayOf(
        65, 64, 64, 64, 63, 63, 63, 63, 62, 62, 63, 63, 62, 62, 61, 62, 62, 62, 61, 61, 61, 61, 62, 61, 62, 62, 63, 62, 63, 63,
        63, 62, 62, 62, 62, 62, 62, 63, 62, 61, 61, 60)

    private fun pinches(mm: IntArray, stroke: Boolean): Int { val p = PinchState(stroke = stroke); var n = 0; for (g in mm) if (p.update(g / 1000f, 0.075f)) n++; return n }

    @Test fun `a pinch seen from behind is caught by its closing movement`() {
        assertTrue("by distance alone: " + pinches(rightPinching, false), pinches(rightPinching, false) <= 1)
        assertEquals(6, pinches(rightPinching, true))
        assertEquals(5, pinches(leftPinching, false)); assertEquals(9, pinches(leftPinching, true))
        assertEquals(0, pinches(rightResting, true))
    }

    @Test fun `a slow drift of the fingers is not a pinch`() {
        val p = PinchState(); var n = 0
        for (i in 0..60) if (p.update((120 - i) / 1000f, 0.075f)) n++      // 120 mm down to 60 mm, a millimetre a result
        assertEquals(0, n)
    }
}
