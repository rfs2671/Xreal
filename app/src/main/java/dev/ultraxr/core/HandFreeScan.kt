// Ultra XR: the hand-free scan, a recording with no hands in view. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

/**
 * The hand-free scan (asked for on 24 November 2026): the wearer walks the rooms for [DURATION_S] seconds with both
 * hands out of sight (pockets, behind the back, the phone held away) and looks at the places where a hand is
 * mistaken: the lap, the desk, the keyboard, the phone, bags and bottles. Every hand the tracker reports in the
 * recording is then a false hand, so the recording is a set of hard negatives that needs no labelling.
 *
 * This is the pure part: the schedule of cues, and the counting of the false-hand seconds.
 */
object HandFreeScan {
    const val DURATION_S = 180.0
    const val CUE_EVERY_S = 20.0
    /** The time given to put the hands away before the recording begins. */
    const val LEAD_IN_S = 12.0
    /** The hand log is written this often, seconds. */
    const val SAMPLE_S = 0.1

    class Cue(val atS: Double, val text: String)

    const val REMINDER = "Keep both hands out of sight"
    private val LOOKS = listOf(
        "Look down at your lap", "Look at the desk", "Look at the keyboard", "Turn to the other side of the room",
        "Look down at your phone", "Look at a bag or a bottle", "Look at your feet and the floor", "Walk to the next room and look around",
        "Look at a screen or a monitor", "Look up at the ceiling, then down at the table")

    /** One cue every [every] seconds from 0, every third of them the reminder to keep the hands away, the rest places to look. */
    fun schedule(durationS: Double = DURATION_S, every: Double = CUE_EVERY_S): List<Cue> {
        val l = ArrayList<Cue>(); var look = 0; var i = 0
        var t = 0.0
        while (t < durationS - 1e-9) {
            l += Cue(t, if (i % 3 == 0) REMINDER else LOOKS[look++ % LOOKS.size])
            i++; t += every
        }
        return l
    }

    /** The cue to show at [tS]: the latest that has begun. */
    fun cueAt(cues: List<Cue>, tS: Double): Cue? = cues.lastOrNull { it.atS <= tS }

    /**
     * Counts the time a hand was reported. [sample] is called with the time, and whether the left and the right hand
     * were tracked then; samples should come about every [SAMPLE_S].
     */
    class FalseHands {
        private var lastT = Double.NaN
        private val seconds = HashSet<Int>()
        private var any = 0.0
        private var events = 0; private var was = false
        var samples = 0; private set
        var leftSamples = 0; private set
        var rightSamples = 0; private set

        fun sample(tS: Double, left: Boolean, right: Boolean) {
            samples++
            if (left) leftSamples++
            if (right) rightSamples++
            val a = left || right
            if (a) { seconds += Math.floor(tS).toInt(); if (!lastT.isNaN()) any += (tS - lastT).coerceIn(0.0, 0.5); if (!was) events++ }
            was = a; lastT = tS
        }
        /** Whole seconds of the recording in which a hand was reported at least once: the figure shown at the end. */
        val falseSeconds: Int get() = seconds.size
        /** The time a hand was reported, seconds. */
        val handSeconds: Double get() = any
        /** How many times a hand began to be reported. */
        val handEvents: Int get() = events
        fun summary(): String = "false-hand seconds: $falseSeconds (a hand reported " + events + " times, " + Fmt.f(any, 1) + " s in all)"
    }
}
