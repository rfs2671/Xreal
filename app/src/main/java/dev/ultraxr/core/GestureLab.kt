// Ultra XR: a scripted, labelled hand session and its scoring. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The hand lab: a fixed script of cues ("pinch with your right hand now", "hold your hand still", "wave without
 * pinching") played to the wearer, and the scoring of what the hand pipeline made of them. Asked for on 24 November
 * 2026: a benchmark of hundreds of labelled gestures instead of three matched pinches.
 *
 * What a cue labels: the wearer is told when, and acts within a second or so; so a pinch counts as meant for a cue
 * when it begins in the cue's window, and a pinch that begins where no cue asked for one is a false activation (in a
 * rest, in a "hold still" or a "wave", or by the wrong hand). The reaction time (cue to onset) is the wearer's and the
 * system's together; it is not the system's latency. What is the system's alone is the age of the tracking result a
 * pinch began on, which is recorded with each onset ([Ev.ageMs]).
 */
class GestureLab(val coverage: Boolean = false) {
    /** A place to hold a hand, for the coverage lab: [x], [y], [z] cm right of, above and in front of the head for the right hand (the left hand's is mirrored), [both] for two hands at once. */
    class Zone(val name: String, val x: Double, val y: Double, val z: Double, val both: Boolean = false)
    enum class Kind { PINCH, DOUBLE, HOLD, STILL, WAVE, BOTH, TURN, TARGET, COVER }

    /** One cue: [side] 0 left, 1 right, 2 both; [atS] seconds from the start; the pinch is expected in [atS + 0.10, atS + [windowS]]. */
    class Cue(val id: Int, val section: String, val kind: Kind, val side: Int, val atS: Double, val windowS: Double, val text: String) {
        /** Pinches expected from the hand (each hand, for [Kind.BOTH]). */
        val expected: Int get() = when (kind) { Kind.PINCH, Kind.HOLD, Kind.BOTH, Kind.TURN, Kind.TARGET -> 1; Kind.DOUBLE -> 2; else -> 0 }
        /** A hold is to be let go at [atS] + [HOLD_RELEASE_S]. */
        val releaseS: Double get() = atS + HOLD_RELEASE_S
    }

    /** [errDeg]: for a target cue, how far the ray was from the target at this moment, degrees; -1 when not asked. */
    class Ev(val tS: Double, val side: Int, val onset: Boolean, val ageMs: Double, val errDeg: Double = -1.0)

    /** Where the target of a [Kind.TARGET] cue stands: degrees right of and above the heading the lab began with. */
    fun targetOf(c: Cue): DoubleArray { val k = c.id % TARGETS.size; return doubleArrayOf(TARGETS[k][0], TARGETS[k][1]) }

    val cues: List<Cue>
    val durationS: Double
    private val events = ArrayList<Ev>()

    init {
        val l = ArrayList<Cue>(); var t = 6.0
        fun add(section: String, kind: Kind, side: Int, gap: Double, window: Double, text: String) { l += Cue(l.size, section, kind, side, t, window, text); t += gap }
        if (coverage) {
            // every place the cameras might see a hand, held there for 4 seconds, each hand in turn, then both: whether it is in the display or not
            for (z in ZONES) { if (z.both) add("coverage", Kind.COVER, 2, 6.5, 5.0, z.name) else for (side in 0..1) add("coverage", Kind.COVER, side, 6.5, 5.0, (if (side == 0) "LEFT hand: " else "RIGHT hand: ") + z.name) }
            cues = l; durationS = t + 3.0
        } else {
        for (i in 0 until 40) add("single pinches", Kind.PINCH, i % 2, 2.5, 1.6, if (i % 2 == 0) "Pinch: LEFT hand" else "Pinch: RIGHT hand")
        t += 3.0
        for (i in 0 until 14) add("quick double pinches", Kind.DOUBLE, i % 2, 3.2, 2.0, if (i % 2 == 0) "Pinch twice, quickly: LEFT" else "Pinch twice, quickly: RIGHT")
        t += 3.0
        for (i in 0 until 12) add("holds", Kind.HOLD, i % 2, 4.2, 1.6, if (i % 2 == 0) "Pinch and HOLD: LEFT (let go at the tone)" else "Pinch and HOLD: RIGHT (let go at the tone)")
        t += 3.0
        for (i in 0 until 4) add("hold still", Kind.STILL, 2, 15.0, 15.0, "Hold a hand up in front of you, open and STILL. Do not pinch.")
        t += 3.0
        for (i in 0 until 4) add("wave", Kind.WAVE, 2, 11.0, 10.0, "Wave and sweep your hands about quickly. Do not pinch.")
        t += 3.0
        for (i in 0 until 16) add("two hands", Kind.BOTH, 2, 3.2, 1.8, "Pinch with BOTH hands together")
        t += 3.0
        for (i in 0 until 36) add("targets", Kind.TARGET, 2, 3.4, 2.4, "Point at the ring and pinch")
        t += 3.0
        for (i in 0 until 16) add("head turning", Kind.TURN, i % 2, 3.6, 2.2, if (i % 2 == 0) "Turn your head slowly, and pinch LEFT" else "Turn your head slowly, and pinch RIGHT")
        cues = l; durationS = t + 4.0
        }
    }
    fun zoneOf(c: Cue): Zone? = if (c.kind == Kind.COVER) ZONES.firstOrNull { c.text.endsWith(it.name) } else null

    fun clear() = events.clear()
    fun onset(tS: Double, side: Int, ageMs: Double, errDeg: Double = -1.0) { events += Ev(tS, side, true, ageMs, errDeg) }
    fun release(tS: Double, side: Int, errDeg: Double = -1.0) { events += Ev(tS, side, false, 0.0, errDeg) }
    fun allEvents(): List<Ev> = events

    /** The cue being asked for at [tS], or null in a rest. */
    fun cueAt(tS: Double): Cue? = cues.lastOrNull { tS >= it.atS - 1.0 && tS <= it.atS + it.windowS }
    /** The cue that comes next after [tS]. */
    fun nextCue(tS: Double): Cue? = cues.firstOrNull { it.atS > tS }

    class Score(val json: JSONObject, val text: String)

    fun score(): Score {
        var expected = 0; var hit = 0; var falseN = 0; var extra = 0; var dropped = 0; var holds = 0; var late = 0
        val react = ArrayList<Double>(); val ages = ArrayList<Double>(); val releaseLag = ArrayList<Double>(); val errOn = ArrayList<Double>(); val errOff = ArrayList<Double>()
        val bySection = LinkedHashMap<String, IntArray>()       // expected, hit, false
        val used = HashSet<Ev>()
        val perCue = JSONArray()
        for (c in cues) {
            val sec = bySection.getOrPut(c.section) { IntArray(3) }
            val sides = if (c.side == 2 && c.kind != Kind.TARGET) listOf(0, 1) else if (c.kind == Kind.TARGET) listOf(0, 1) else listOf(c.side)
            var cueHit = 0; var cueFalse = 0
            val lo = c.atS + 0.10; val hi = c.atS + c.windowS
            for (s in sides) {
                val on = events.filter { it.onset && it.side == s && it.tS in lo..hi }
                if (c.expected == 0) { cueFalse += on.size; used += on; continue }
                if (c.kind == Kind.TARGET) {      // either hand, one pinch
                    if (s == 0) { expected += 1; sec[0] += 1; val any = events.filter { it.onset && it.tS in lo..hi }.sortedBy { it.tS }; used += any
                        if (any.isNotEmpty()) { cueHit++; hit++; sec[1]++; react += any[0].tS - c.atS; ages += any[0].ageMs; if (any[0].errDeg >= 0) errOn += any[0].errDeg
                            events.firstOrNull { !it.onset && it.side == any[0].side && it.tS > any[0].tS }?.let { if (it.errDeg >= 0) errOff += it.errDeg } }
                        extra += maxOf(0, any.size - 1) }
                    continue
                }
                expected += c.expected; sec[0] += c.expected
                val take = on.take(c.expected); used += on
                cueHit += take.size; hit += take.size; sec[1] += take.size
                extra += on.size - take.size
                for (e in take) { react += e.tS - c.atS; ages += e.ageMs }
                if (c.kind == Kind.HOLD && take.isNotEmpty()) {
                    holds++
                    val rel = events.firstOrNull { !it.onset && it.side == s && it.tS > take[0].tS }
                    if (rel == null || rel.tS < c.releaseS - 0.15) dropped++ else { releaseLag += rel.tS - c.releaseS; if (rel.tS > c.releaseS + 1.5) late++ }
                }
            }
            if (c.kind != Kind.STILL && c.kind != Kind.WAVE && c.kind != Kind.TARGET && c.kind != Kind.COVER) {
                // the other hand pinching for a one-handed cue
                if (c.side != 2) { val o = events.filter { it.onset && it.side != c.side && it.tS in lo..hi }; cueFalse += o.size; used += o }
            }
            falseN += cueFalse; sec[2] += cueFalse
            perCue.put(JSONObject().put("id", c.id).put("section", c.section).put("kind", c.kind.name).put("side", c.side).put("at", c.atS).put("expected", c.expected * sides.size).put("hit", cueHit).put("false", cueFalse))
        }
        // onsets that no cue's window explains: in a rest
        val rest = events.filter { it.onset && it !in used && cues.none { c -> it.tS in (c.atS - 0.2)..(c.atS + c.windowS + 0.5) } }
        falseN += rest.size
        val minutes = durationS / 60.0
        fun pct(l: List<Double>, p: Double) = if (l.isEmpty()) -1.0 else l.sorted()[((l.size - 1) * p).toInt()]
        val j = JSONObject().put("expected_pinches", expected).put("detected", hit).put("recall_pct", if (expected > 0) 100.0 * hit / expected else 0.0)
            .put("false_activations", falseN).put("false_per_minute", falseN / minutes).put("extra_onsets_in_windows", extra)
            .put("holds", holds).put("holds_dropped_early", dropped).put("holds_released_late", late)
            .put("reaction_s_median", pct(react, 0.5)).put("reaction_s_p95", pct(react, 0.95))
            .put("onset_result_age_ms_median", pct(ages, 0.5)).put("onset_result_age_ms_p95", pct(ages, 0.95)).put("onset_result_age_ms_p99", pct(ages, 0.99))
            .put("release_lag_s_median", pct(releaseLag, 0.5)).put("target_pinches", errOn.size)
            .put("target_err_deg_median", pct(errOn, 0.5)).put("target_err_deg_p90", pct(errOn, 0.9)).put("target_err_deg_p99", pct(errOn, 0.99)).put("target_hit_within_3deg_pct", if (errOn.isEmpty()) -1.0 else 100.0 * errOn.count { it <= 3.0 } / errOn.size)
            .put("target_release_err_deg_median", pct(errOff, 0.5)).put("target_release_err_deg_p90", pct(errOff, 0.9)).put("rest_onsets", rest.size).put("cues", perCue)
        val sj = JSONObject(); for ((k, v) in bySection) sj.put(k, JSONObject().put("expected", v[0]).put("hit", v[1]).put("false", v[2])); j.put("by_section", sj)
        val sb = StringBuilder()
        sb.append("hand lab: ").append(hit).append(" of ").append(expected).append(" pinches found (").append(Fmt.f(if (expected > 0) 100.0 * hit / expected else 0.0, 1)).append(" %), ")
            .append(falseN).append(" false (").append(Fmt.f(falseN / minutes, 1)).append(" a minute), ").append(dropped).append(" of ").append(holds).append(" holds dropped early; result age at onset median ")
            .append(Fmt.f(pct(ages, 0.5), 0)).append(" ms, P95 ").append(Fmt.f(pct(ages, 0.95), 0)).append(" ms\n")
        if (errOn.isNotEmpty()) sb.append("  ray at the pinch, on ").append(errOn.size).append(" targets: median ").append(Fmt.f(pct(errOn, 0.5), 1)).append(" deg, P90 ").append(Fmt.f(pct(errOn, 0.9), 1)).append(", P99 ").append(Fmt.f(pct(errOn, 0.99), 1)).append("; at release median ").append(Fmt.f(pct(errOff, 0.5), 1)).append(" deg\n")
        for ((k, v) in bySection) sb.append("  ").append(k).append(": ").append(v[1]).append("/").append(v[0]).append(" found, ").append(v[2]).append(" false\n")
        return Score(j, sb.toString())
    }

    companion object {
        const val HOLD_RELEASE_S = 1.8
        /** Where hands rest and go, from the cameras' coverage (docs/HAND_TRACKING.md, 24 November 2026): in the display, at chest and belly height, in the lap, out to the side, high, close, beside the head, and two hands wide apart. */
        val ZONES = listOf(
            Zone("straight ahead, arm's length", 0.0, 0.0, 50.0), Zone("raised, in front of your face", 10.0, 5.0, 40.0), Zone("chest height, in front of you", 0.0, -35.0, 35.0),
            Zone("chest height, off to the side", 30.0, -35.0, 35.0), Zone("belly height, in front of you", 0.0, -55.0, 30.0), Zone("in your lap, a little to the side", 20.0, -60.0, 20.0),
            Zone("resting by your hip", 30.0, -60.0, 0.0), Zone("arm out to the side, forward a little", 50.0, -15.0, 50.0), Zone("arm straight out to the side", 60.0, -15.0, 20.0),
            Zone("high, above the display", 0.0, 40.0, 40.0), Zone("close to your face", 0.0, 0.0, 20.0), Zone("beside your head", 25.0, 0.0, 10.0),
            Zone("BOTH hands wide apart at chest height", 45.0, -35.0, 35.0, true), Zone("BOTH hands together at your belly", 8.0, -55.0, 30.0, true), Zone("BOTH hands wide apart at shoulder height", 60.0, -15.0, 30.0, true))
        /** Target places (degrees right, up) within the picture's 46 by 27: a grid, in an order that is not a sweep. */
        val TARGETS = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(-14.0, 6.0), doubleArrayOf(12.0, -6.0), doubleArrayOf(14.0, 7.0), doubleArrayOf(-12.0, -6.0), doubleArrayOf(0.0, 8.0), doubleArrayOf(-6.0, -3.0), doubleArrayOf(7.0, 3.0), doubleArrayOf(0.0, -7.0))
    }
}
