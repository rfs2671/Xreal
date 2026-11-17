package dev.ultraxr.glasses

/**
 * Decides when to ask the glasses for half-width 3D and when to stop trying.
 *
 * In half-width 3D the glasses still take a 1920x1080 picture, but show its left half to the left
 * eye and its right half to the right eye. On the Pixel 10 the link to the glasses does not always
 * come up at full width in that mode (measured: two of four attempts fell back to 640x480), so the
 * switch is tried a bounded number of times, always starting from a 2D picture that is known to be
 * showing, and after the last failure the glasses stay in 2D until the user asks again.
 *
 * Pure logic: [step] is called once a second with what the glasses and Android report, and returns
 * the mode command to send, if any. Every command restarts the DisplayPort link, so the total is
 * bounded: at most [maxAttempts] switches to 3D and as many back to 2D.
 */
class StereoSwitch(private val maxAttempts: Int = 3) {
    enum class State { OFF, STARTING, RESTING, ACTIVE, GAVE_UP }
    enum class Action { SET_3D, SET_2D }

    @get:Synchronized var state = State.OFF; private set
    @get:Synchronized var attempts = 0; private set
    /** Why the last attempt failed, in words for the status line. Empty when none has. */
    @get:Synchronized var lastReason = ""; private set
    /** Mode commands sent since the last [reset]: each one restarts the DisplayPort link. */
    @get:Synchronized var commands = 0; private set

    private var wanted = false
    private var seconds = 0
    private var good = 0
    private var bad = 0
    private var sawDrop = false

    /** The desktop wants 3D ([on]) or plain 2D. Takes effect at the next [step]. */
    @Synchronized fun want(on: Boolean) { wanted = on }

    /** The user asked again, or the glasses were plugged in again: the attempts start over. */
    @Synchronized fun reset() { attempts = 0; commands = 0; lastReason = ""; if (state == State.GAVE_UP) state = State.OFF; good = 0; bad = 0 }

    /** The glasses were unplugged: nothing is showing and nothing is being attempted. */
    @Synchronized fun unplugged() { state = State.OFF; attempts = 0; commands = 0; good = 0; bad = 0 }

    /**
     * @param halfSbs whether the glasses report their half-width 3D mode; null if they did not answer
     * @param width, height the external display's current size, 0 when there is none
     */
    @Synchronized fun step(halfSbs: Boolean?, width: Int, height: Int): Action? {
        val full = width >= FULL_WIDTH && height >= FULL_HEIGHT
        val small = width > 0 && !full
        seconds++
        when (state) {
            State.OFF -> {
                good = if (full && halfSbs == false) good + 1 else 0
                if (wanted && good >= STABLE_SECONDS) return begin()
            }
            State.STARTING -> {
                if (!wanted) { state = State.OFF; good = 0; return send(Action.SET_2D) }
                if (width == 0) sawDrop = true
                good = if (full && halfSbs == true) good + 1 else 0
                bad = if (small) bad + 1 else 0
                // The old 2D picture can still be reported for a moment after the command: wait for
                // the link to drop first (or long enough that it would have).
                if (good >= STABLE_SECONDS && (sawDrop || seconds >= LINGER_SECONDS)) { state = State.ACTIVE; bad = 0; lastReason = ""; return null }
                if (bad >= STABLE_SECONDS) return fail("the link came up too narrow for a full picture")
                if (seconds >= START_TIMEOUT_SECONDS) return fail(if (halfSbs == true) "no picture in 3D" else "the glasses did not switch to 3D")
            }
            State.RESTING -> {
                if (!wanted) { state = State.OFF; good = 0; return null }
                good = if (full && halfSbs == false) good + 1 else 0
                if (good >= STABLE_SECONDS && seconds >= REST_SECONDS) return begin()
                if (seconds >= REST_TIMEOUT_SECONDS) { state = State.GAVE_UP; lastReason = "the 2D picture did not come back between attempts"; return null }
            }
            State.ACTIVE -> {
                if (!wanted) { state = State.OFF; good = 0; return send(Action.SET_2D) }
                if (halfSbs == false) {   // the glasses left 3D by themselves (their button, or a reset)
                    state = State.GAVE_UP; lastReason = "the glasses left 3D"; return null
                }
                bad = if (full) 0 else bad + 1
                if (bad >= LOST_SECONDS) return fail("the picture was lost in 3D")
            }
            State.GAVE_UP -> if (!wanted) { state = State.OFF; good = 0 }
        }
        return null
    }

    private fun begin(): Action? {
        // Already in 2D here, so giving up needs no command.
        if (attempts >= maxAttempts) { state = State.GAVE_UP; if (lastReason.isEmpty()) lastReason = "3D was already tried $maxAttempts times"; return null }
        attempts++; state = State.STARTING; seconds = 0; good = 0; bad = 0; sawDrop = false
        return send(Action.SET_3D)
    }

    private fun fail(reason: String): Action {
        lastReason = reason; seconds = 0; good = 0; bad = 0
        state = if (attempts >= maxAttempts) State.GAVE_UP else State.RESTING
        return send(Action.SET_2D)
    }

    private fun send(a: Action): Action { commands++; return a }

    companion object {
        const val FULL_WIDTH = 1920
        const val FULL_HEIGHT = 1080
        const val STABLE_SECONDS = 2
        const val LINGER_SECONDS = 5
        const val START_TIMEOUT_SECONDS = 12
        const val REST_SECONDS = 4
        const val REST_TIMEOUT_SECONDS = 20
        const val LOST_SECONDS = 4
    }
}
