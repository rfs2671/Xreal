package dev.ultraxr

import dev.ultraxr.core.HandFreeScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-free scan's cue schedule and false-hand counting (24 November 2026). */
class HandFreeScanTest {
    @Test fun cuesComeEveryTwentySecondsAndRemindAboutTheHands() {
        val c = HandFreeScan.schedule()
        assertEquals(9, c.size)
        assertEquals(0.0, c[0].atS, 1e-9); assertEquals(20.0, c[1].atS, 1e-9); assertEquals(160.0, c.last().atS, 1e-9)
        assertTrue(c.all { it.atS < HandFreeScan.DURATION_S })
        assertEquals(listOf(0, 3, 6), c.indices.filter { c[it].text == HandFreeScan.REMINDER })
        assertTrue(c.any { it.text.contains("lap") } && c.any { it.text.contains("desk") } && c.any { it.text.contains("other side") })
        assertEquals(c.size, c.map { it.atS }.toSet().size)
    }
    @Test fun theCueShownIsTheLatestThatHasBegun() {
        val c = HandFreeScan.schedule()
        assertEquals(c[0], HandFreeScan.cueAt(c, 0.0)); assertEquals(c[0], HandFreeScan.cueAt(c, 19.9)); assertEquals(c[1], HandFreeScan.cueAt(c, 20.0))
        assertEquals(c.last(), HandFreeScan.cueAt(c, 500.0))
        assertEquals(null, HandFreeScan.cueAt(c, -1.0))
    }
    @Test fun aCleanScanHasNoFalseSeconds() {
        val f = HandFreeScan.FalseHands()
        var t = 0.0; while (t < 180.0) { f.sample(t, false, false); t += 0.1 }
        assertEquals(0, f.falseSeconds); assertEquals(0, f.handEvents); assertEquals(0.0, f.handSeconds, 1e-9)
    }
    @Test fun falseHandsAreCountedBySecondsEventsAndTime() {
        val f = HandFreeScan.FalseHands()
        var t = 0.0
        while (t < 60.0) {
            val left = t in 10.0..12.5     // 2.5 s across three whole seconds: 10, 11, 12
            val right = t in 40.0..40.3    // a flicker
            f.sample(t, left, right); t += 0.1
        }
        assertEquals(4, f.falseSeconds)
        assertEquals(2, f.handEvents)
        assertEquals(2.8, f.handSeconds, 0.25)
        assertTrue(f.leftSamples > 20 && f.rightSamples in 3..5)
        assertTrue(f.summary().startsWith("false-hand seconds: 4"))
    }
    @Test fun aGapInSamplesIsNotCountedAsHandTime() {
        val f = HandFreeScan.FalseHands()
        f.sample(0.0, true, false); f.sample(30.0, true, false)
        assertTrue(f.handSeconds <= 0.5)
        assertEquals(2, f.falseSeconds)
    }
}
