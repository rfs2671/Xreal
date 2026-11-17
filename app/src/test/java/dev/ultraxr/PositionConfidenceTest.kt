package dev.ultraxr

import dev.ultraxr.core.PositionConfidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionConfidenceTest {
    private val frame = 33_333_333L

    /** Feeds (landmarks, stereo matches) samples, each held for [seconds], and returns the final verdict. */
    private fun run(c: PositionConfidence, samples: List<Pair<Int, Int>>, seconds: Double = 5.0, start: Long = 1_000_000_000L): Boolean {
        var t = start; var last = false
        for ((lm, st) in samples) { val n = (seconds * 30).toInt(); repeat(n) { last = c.update(t, lm, st); t += frame } }
        return last
    }

    @Test fun theRunawayRunsOfNov16AreNeverTrusted() {
        // "flow:" lines logged while the still glasses' position ran away (cam1 matches, landmarks)
        val c = PositionConfidence()
        assertFalse(run(c, listOf(0 to 0, 0 to 0, 4 to 0, 0 to 1, 0 to 0, 0 to 1, 0 to 0)))
        // the earlier baseline: plenty of landmarks triangulated from the drift itself, but no stereo match
        val d = PositionConfidence()
        assertFalse(run(d, listOf(61 to 0, 45 to 0, 49 to 0, 50 to 0, 66 to 0, 75 to 0)))
    }

    @Test fun aRunWithStereoMatchesIsTrustedAfterASecond() {
        val c = PositionConfidence()
        assertFalse("not on the first frame", c.update(0, 48, 18))
        assertTrue(run(c, listOf(48 to 18, 32 to 21, 64 to 9)))
    }

    @Test fun trustIsLostWhenStereoMatchesDisappearAndRegainedWhenTheyReturn() {
        val c = PositionConfidence()
        assertTrue(run(c, listOf(40 to 6), 3.0))
        assertTrue("one odd frame does not flip it", c.update(200_000_000_000L, 40, 0))
        assertFalse(run(c, listOf(40 to 0), 2.0, 200_000_000_000L))
        assertTrue(run(c, listOf(40 to 5), 2.0, 300_000_000_000L))
    }

    @Test fun borderlineCountsKeepWhateverTheStateWas() {
        // 5..7 landmarks or exactly one stereo match is neither good nor bad
        val c = PositionConfidence()
        assertFalse(run(c, listOf(6 to 1)))
        assertTrue(run(c, listOf(20 to 4), 2.0, 400_000_000_000L))
        assertTrue(run(c, listOf(6 to 1), 5.0, 500_000_000_000L))
    }

    @Test fun aLibraryThatReportsNoCountsIsNotGated() {
        assertTrue(PositionConfidence().update(0, -1, -1))
    }

    @Test fun resetDropsTrust() {
        val c = PositionConfidence(); run(c, listOf(30 to 5)); c.reset()
        assertFalse(c.update(900_000_000_000L, 30, 5))
    }
}
