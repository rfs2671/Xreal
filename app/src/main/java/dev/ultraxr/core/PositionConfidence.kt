package dev.ultraxr.core

/**
 * Decides whether the estimator's position may be shown, from the evidence it has for depth.
 *
 * Measured on the Air 2 Ultra (2026-11-16): with no stereo matches and few landmarks the
 * position ran away within seconds although orientation stayed right; with a handful of stereo
 * matches it held. So position is trusted only while the estimator holds enough 3D landmarks
 * and at least a couple of them are confirmed by both cameras. Otherwise the head stays
 * rotation-only (its last position is held), which is always usable.
 *
 * Both directions need the condition to persist, so one odd frame does not flip the mode.
 * Counts of -1 mean the native library does not report them: nothing is gated then.
 */
class PositionConfidence {
    var trusted = false; private set
    private var goodSinceNs = 0L
    private var badSinceNs = 0L
    var lastLandmarks = -1; private set
    var lastStereo = -1; private set

    /** Returns true if the state at [nowNs] may be used for position. */
    fun update(nowNs: Long, landmarks: Int, stereoMatches: Int): Boolean {
        lastLandmarks = landmarks; lastStereo = stereoMatches
        if (landmarks < 0 || stereoMatches < 0) { trusted = true; return true }
        val good = landmarks >= GOOD_LANDMARKS && stereoMatches >= GOOD_STEREO
        val bad = landmarks < BAD_LANDMARKS || stereoMatches < BAD_STEREO
        if (good) { if (goodSinceNs == 0L) goodSinceNs = nowNs } else goodSinceNs = 0L
        if (bad) { if (badSinceNs == 0L) badSinceNs = nowNs } else badSinceNs = 0L
        if (!trusted && good && nowNs - goodSinceNs >= GAIN_NS) trusted = true
        else if (trusted && bad && nowNs - badSinceNs >= LOSE_NS) trusted = false
        return trusted
    }

    fun reset() { trusted = false; goodSinceNs = 0L; badSinceNs = 0L }

    companion object {
        const val GOOD_LANDMARKS = 8
        const val GOOD_STEREO = 2
        const val BAD_LANDMARKS = 5
        const val BAD_STEREO = 1
        const val GAIN_NS = 1_000_000_000L
        const val LOSE_NS = 700_000_000L
    }
}
