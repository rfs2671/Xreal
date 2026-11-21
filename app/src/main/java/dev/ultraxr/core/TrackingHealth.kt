// Ultra XR: is the estimator's position fit to be shown? SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

/**
 * Judges every state of the position estimator before anything is done with its position.
 *
 * Measured by replay with the cameras covered (20 November 2026, docs/SPATIAL.md section 10): with no picture the
 * estimator runs on the motion sensor alone and is metres out within seconds; its position went on being shown for
 * 0.7 s of that; and when the pictures came back it was believed again after a second although for eight more seconds
 * its position moved by metres its own velocity knew nothing of. So:
 *
 *   blind      it tracks fewer than [MIN_FEATURES] corners: nothing it says about position is used, from that state on
 *   flying     it says the head moves faster than [MAX_SPEED], or its position moved between two states as if it
 *              did: nobody walks a room at that speed
 *   starved    (blind, told another way) it has seen, and now for [STARVED_NS] holds no landmark seen by both cameras
 *              or under [STARVED_LANDMARKS] landmarks at all (in noise a stray one is matched now and then). Worn, 21 November 2026: a hand over the cameras left the
 *              count of corners at 108 (the detector finds its corners in the noise of a dark picture) and the
 *              velocity under 1.2 m/s, so neither rule above fired; the world came back 3.4 and 3.9 m out. The
 *              landmarks were gone at once. In 1.9 hours of replays of ordinary walking this state never lasted
 *              longer than 0.63 s (one moment of one walk). Once starved it stays blind until it sees again: [SEE_LANDMARKS] landmarks,
 *              [SEE_STEREO] of them by both cameras.
 *
 * (A third rule, that the position must move as the velocity says, was tried on the recorded states of 40 replays and
 * thrown out: it called 8 to 14 losses in every 83 seconds of ordinary walking.)
 *
 * Any of these makes the position unfit at once. It is fit again only after [STABLE_NS] without any of them and with
 * enough landmarks seen by both cameras (the caller's [PositionConfidence] counts those). After being unfit for longer
 * than [RESTART_AFTER_NS] the estimator is not carried on with: [wantsRestart] is true once when it first sees again,
 * and the caller starts it afresh, so that what comes after a loss begins from rest and not from where it flew to.
 */
class TrackingHealth {
    enum class Why { FINE, BLIND, FLYING, SETTLING }
    var fit = false; private set
    var why = Why.SETTLING; private set
    /** True once when the estimator sees again after a long loss: start it afresh. Cleared by reading [takeRestart]. */
    private var restartDue = false
    var blindStates = 0; private set
    var flyingStates = 0; private set
    var losses = 0; private set

    private var lastT = 0L; private var lastP: DoubleArray? = null
    private var cleanSinceNs = 0L; private var unfitSinceNs = 0L
    private var sawSight = false; private var starvedSinceNs = 0L; private var lostSight = false
    var starvedStates = 0; private set

    /** A new session of the estimator (it was started afresh): nothing of the old one counts. */
    fun reset() { fit = false; why = Why.SETTLING; lastT = 0L; lastP = null; cleanSinceNs = 0L; restartDue = false; sawSight = false; starvedSinceNs = 0L; lostSight = false }

    fun takeRestart(): Boolean { val r = restartDue; restartDue = false; return r }

    /**
     * One state: its time, position and velocity in the estimator's own world, and how many corners it tracks
     * (negative: the library does not say; then blindness cannot be told and is not judged), and how many landmarks
     * it holds and how many of them both cameras see (negative: not said, not judged). Returns whether its position
     * is fit to be used.
     */
    fun update(tNs: Long, p: DoubleArray, v: DoubleArray, features: Int, landmarks: Int = -1, stereo: Int = -1): Boolean {
        var bad = Why.FINE
        if (features in 0 until MIN_FEATURES) { bad = Why.BLIND; blindStates++ }
        val counts = landmarks >= 0 && stereo >= 0
        val sees = !counts || (landmarks >= SEE_LANDMARKS && stereo >= SEE_STEREO)
        if (counts) {
            if (sees) { sawSight = true; lostSight = false }
            if (sawSight && (stereo < 1 || landmarks < STARVED_LANDMARKS)) { if (starvedSinceNs == 0L) starvedSinceNs = tNs } else starvedSinceNs = 0L
            if (starvedSinceNs != 0L && tNs - starvedSinceNs >= STARVED_NS) lostSight = true
            if (bad == Why.FINE && lostSight) { bad = Why.BLIND; starvedStates++ }
        }
        val speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (bad == Why.FINE && (speed > MAX_SPEED || !speed.isFinite())) { bad = Why.FLYING; flyingStates++ }
        // and by where it says the head is: a step between two states that no head makes
        val lp = lastP
        if (bad == Why.FINE && lp != null && tNs > lastT && tNs - lastT < 250_000_000L) {
            val dt = maxOf((tNs - lastT) * 1e-9, 0.030)
            val d = Math.sqrt((p[0] - lp[0]) * (p[0] - lp[0]) + (p[1] - lp[1]) * (p[1] - lp[1]) + (p[2] - lp[2]) * (p[2] - lp[2]))
            if (d / dt > MAX_SPEED) { bad = Why.FLYING; flyingStates++ }
        }
        lastT = tNs; lastP = p.copyOf()

        if (bad != Why.FINE) {
            if (fit) losses++
            if (unfitSinceNs == 0L) unfitSinceNs = tNs
            fit = false; why = bad; cleanSinceNs = 0L
            return false
        }
        // it sees and behaves. After a long loss: not carried on with, started afresh (once)
        if (!fit && unfitSinceNs != 0L && tNs - unfitSinceNs > RESTART_AFTER_NS) { restartDue = true; unfitSinceNs = 0L; cleanSinceNs = 0L; why = Why.SETTLING; return false }
        if (cleanSinceNs == 0L) cleanSinceNs = tNs
        if (!fit && tNs - cleanSinceNs >= STABLE_NS) { fit = true; unfitSinceNs = 0L }
        why = if (fit) Why.FINE else Why.SETTLING
        return fit
    }

    companion object {
        /** Walking and turning it tracks 100 to 190 corners (five recordings); with the cameras covered, none. */
        const val MIN_FEATURES = 25
        /** Metres a second. The fastest believable head in the recordings: 1.6 m/s (99th percentile 1.2). */
        const val MAX_SPEED = 3.0
        const val STABLE_NS = 700_000_000L
        const val RESTART_AFTER_NS = 500_000_000L
        const val STARVED_NS = 700_000_000L
        const val STARVED_LANDMARKS = 5
        const val SEE_LANDMARKS = 8
        const val SEE_STEREO = 2
    }
}
