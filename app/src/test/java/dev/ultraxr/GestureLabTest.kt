package dev.ultraxr

import dev.ultraxr.core.GestureLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The hand lab's script and its scoring (24 November 2026). */
class GestureLabTest {
    /** A wearer who does what is asked [react] seconds after the cue, with every hand; [slipEvery]: every n-th pinch is missed. */
    private fun play(lab: GestureLab, react: Double = 0.45, slipEvery: Int = 0, strayAt: List<Double> = emptyList(), age: Double = 40.0) {
        var n = 0
        for (c in lab.cues) {
            val sides = if (c.kind == GestureLab.Kind.TARGET) listOf(1) else if (c.side == 2) listOf(0, 1) else listOf(c.side)
            if (c.expected == 0) continue
            for (s in sides) for (k in 0 until c.expected) {
                n++
                if (slipEvery > 0 && n % slipEvery == 0) continue
                val t = c.atS + react + k * 0.35
                lab.onset(t, s, age)
                if (c.kind == GestureLab.Kind.HOLD) lab.release(c.releaseS + 0.3, s) else lab.release(t + 0.2, s)
            }
        }
        for (t in strayAt) lab.onset(t, 1, age)
    }

    @Test fun theScriptHasHundredsOfLabelledPinchesAndStretchesWhereNoneIsAsked() {
        val lab = GestureLab()
        val pinches = lab.cues.sumOf { c -> c.expected * (if (c.side == 2 && c.expected > 0 && c.kind != GestureLab.Kind.TARGET) 2 else 1) }
        assertTrue("pinches asked for: $pinches", pinches >= 150)
        assertTrue("seconds of hold-still and waving: " + lab.cues.filter { it.expected == 0 }.sumOf { it.windowS }, lab.cues.filter { it.expected == 0 }.sumOf { it.windowS } >= 90.0)
        for (i in 1 until lab.cues.size) assertTrue("cues do not overlap: ${lab.cues[i - 1].id}", lab.cues[i].atS >= lab.cues[i - 1].atS + 2.0)
        assertTrue("under ten minutes", lab.durationS < 600.0)
    }

    @Test fun aPerfectWearerScoresAllAndNoFalseActivation() {
        val lab = GestureLab(); play(lab)
        val s = lab.score().json
        assertEquals(s.getInt("expected_pinches"), s.getInt("detected")); assertEquals(0, s.getInt("false_activations")); assertEquals(0, s.getInt("holds_dropped_early"))
        assertEquals(40.0, s.getDouble("onset_result_age_ms_median"), 1e-9)
    }

    @Test fun missedPinchesAndStrayOnesAreCounted() {
        val lab = GestureLab(); val still = lab.cues.first { it.kind == GestureLab.Kind.STILL }
        play(lab, slipEvery = 10, strayAt = listOf(still.atS + 4.0, still.atS + 9.0, 2.0))
        val s = lab.score().json
        val e = s.getInt("expected_pinches")
        assertTrue("about a tenth missed: " + s.getInt("detected") + " of $e", s.getInt("detected") in (e * 85 / 100)..(e * 95 / 100))
        assertEquals("two in the still cue, one in the first rest", 3, s.getInt("false_activations"))
    }

    @Test fun targetPinchesGiveTheRaysErrorAtThePinchAndAtTheRelease() {
        val lab = GestureLab(); val ts = lab.cues.filter { it.kind == GestureLab.Kind.TARGET }
        assertTrue("many targets: " + ts.size, ts.size >= 30)
        for ((i, c) in ts.withIndex()) { val e = if (i % 10 == 0) 6.0 else 1.5; lab.onset(c.atS + 0.9, i % 2, 45.0, e); lab.release(c.atS + 1.2, i % 2, e + 0.5) }
        val s = lab.score().json
        assertEquals(ts.size, s.getInt("target_pinches")); assertEquals(1.5, s.getDouble("target_err_deg_median"), 1e-9); assertEquals(6.0, s.getDouble("target_err_deg_p99"), 1e-9)
        assertTrue(s.getDouble("target_hit_within_3deg_pct") in 80.0..95.0); assertEquals(2.0, s.getDouble("target_release_err_deg_median"), 1e-9)
        val targets = ts.map { lab.targetOf(it) }; assertTrue("targets stay in the picture", targets.all { Math.abs(it[0]) <= 15.0 && Math.abs(it[1]) <= 9.0 })
    }

    @Test fun theCoverageLabAsksForEveryPlaceAndNeverForAPinch() {
        val lab = GestureLab(true)
        assertTrue("every zone, each hand, then both: " + lab.cues.size, lab.cues.size == GestureLab.ZONES.count { !it.both } * 2 + GestureLab.ZONES.count { it.both })
        assertTrue("under four minutes: " + lab.durationS, lab.durationS < 240.0)
        assertTrue(lab.cues.all { it.kind == GestureLab.Kind.COVER && it.expected == 0 })
        assertTrue("each cue names its zone", lab.cues.all { lab.zoneOf(it) != null })
        // a pinch in it is a false activation, as in a hold-still cue
        lab.onset(lab.cues[3].atS + 1.0, 1, 40.0); assertEquals(1, lab.score().json.getInt("false_activations"))
        // places beyond the display are among them: some zone is more than 25 degrees to the side and some more than 30 degrees below straight ahead
        assertTrue(GestureLab.ZONES.any { Math.toDegrees(Math.atan2(Math.abs(it.x), it.z)) > 25.0 } && GestureLab.ZONES.any { Math.toDegrees(Math.atan2(-it.y, it.z)) > 30.0 })
    }

    @Test fun aHoldLetGoTooSoonIsDropped() {
        val lab = GestureLab(); val h = lab.cues.first { it.kind == GestureLab.Kind.HOLD }
        lab.onset(h.atS + 0.5, h.side, 40.0); lab.release(h.atS + 0.9, h.side)
        assertEquals(1, lab.score().json.getInt("holds_dropped_early"))
    }

    @Test fun theOtherHandPinchingForAOneHandedCueIsFalse() {
        val lab = GestureLab(); val c = lab.cues.first { it.kind == GestureLab.Kind.PINCH && it.side == 1 }
        lab.onset(c.atS + 0.5, 1, 40.0); lab.onset(c.atS + 0.6, 0, 40.0)
        val s = lab.score().json; assertEquals(1, s.getInt("false_activations"))
    }
}
