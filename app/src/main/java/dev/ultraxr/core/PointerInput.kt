package dev.ultraxr.core

/**
 * A pointer that does not depend on hand tracking: the phone's touchscreen, its volume keys or a mouse move a
 * virtual pointing hand, and a tap is its pinch. The running application sees it as the wearer's right hand, so
 * everything that works with a tracked hand (pointing, selecting, grabbing and carrying) works with it.
 *
 * Plain state with no Android in it, so the rules can be tested. Times are System.nanoTime() nanoseconds.
 */
object PointerInput {
    /** How long after the last touch or key the pointer stays in charge of the right hand. */
    const val ACTIVE_NS = 20_000_000_000L
    const val TAP_NS = 200_000_000L
    /** Where the virtual hand can be carried, in metres from its resting place (right, up). */
    const val MAX_X = 0.55; const val MIN_Y = -0.30; const val MAX_Y = 0.55

    private var lastNs = 0L
    private var movedNs = 0L
    private var x = 0.0; private var y = 0.0
    private var holds = 0            // bit 0: fingers, bit 1: key, bit 2: mouse button
    private var tapUntilNs = 0L
    @Volatile var clicks = 0; private set

    @Synchronized fun active(nowNs: Long): Boolean = lastNs != 0L && nowNs - lastNs < ACTIVE_NS
    @Synchronized fun pressed(nowNs: Long): Boolean = holds != 0 || nowNs < tapUntilNs
    @Synchronized fun offset(): DoubleArray = doubleArrayOf(x, y, 0.0)

    /** The pointer is carried by ([dxM], [dyM]) metres: right and up are positive. */
    @Synchronized fun move(nowNs: Long, dxM: Double, dyM: Double) {
        x = (x + dxM).coerceIn(-MAX_X, MAX_X); y = (y + dyM).coerceIn(MIN_Y, MAX_Y); lastNs = nowNs; movedNs = nowNs
    }
    /** One short press: a click. */
    @Synchronized fun tap(nowNs: Long) { tapUntilNs = nowNs + TAP_NS; lastNs = nowNs; clicks++ }
    /** Press and keep pressed (to grab and carry), from one of the sources ([source] 0 fingers, 1 key, 2 mouse). */
    @Synchronized fun hold(nowNs: Long, source: Int, down: Boolean) {
        val bit = 1 shl source; val was = holds
        holds = if (down) holds or bit else holds and bit.inv()
        // A press lasts at least as long as a tap, so a quick key press is not missed between two samples.
        if (was == 0 && holds != 0) { clicks++; tapUntilNs = maxOf(tapUntilNs, nowNs + TAP_NS) }
        lastNs = nowNs
    }
    /** Back to the middle, released. */
    @Synchronized fun center(nowNs: Long) { x = 0.0; y = 0.0; holds = 0; tapUntilNs = 0; lastNs = nowNs; movedNs = nowNs }
    /**
     * Whether the finger moved the pointer within the last [ns]. The pointing hand is carried along with the head
     * only while it is being moved: left alone, or during a tap or a press, it stays where it is in the room, so
     * the ordinary small movements of a head do not turn a tap into a drag or pull the pointer off its target.
     */
    @Synchronized fun movedWithin(nowNs: Long, ns: Long): Boolean = movedNs != 0L && nowNs - movedNs < ns
    @Synchronized fun reset() { x = 0.0; y = 0.0; holds = 0; tapUntilNs = 0; lastNs = 0; movedNs = 0; clicks = 0 }
}
