package dev.ultraxr.desktop

import kotlin.math.abs

/**
 * One focus (docs/INTERFACE.md, "Focus and the d-pad"). Exactly one thing is in focus at any moment, and a press acts
 * on it however it was reached: a d-pad, a hand's ray, the phone's touchpad or a look. Nothing here knows Android, so
 * every row of the design's table is unit-tested (FocusTest).
 */
enum class FocusKey { LEFT, RIGHT, UP, DOWN, SELECT, BACK, NEARER, FURTHER }

/** The words of a window's handle, in the order they stand. [label] is what the wearer reads. */
enum class HandleWord(val label: String) { MOVE("move"), SIZE("size"), PIN("pin"), CLOSE("close") }

/** Where the focus is. */
sealed class Focus {
    /** A mark of the shelf ([Shelf]). */
    data class Shelf(val index: Int) : Focus()
    data class Window(val id: Int) : Focus()
    data class Handle(val id: Int, val word: HandleWord) : Focus()
    /** The page is in use: keys go to it. */
    data class Page(val id: Int) : Focus()
    data class Moving(val id: Int) : Focus()
    data class Sizing(val id: Int) : Focus()

    /** The window this focus belongs to, or null on the shelf. */
    val windowId: Int? get() = when (this) { is Shelf -> null; is Window -> id; is Handle -> id; is Page -> id; is Moving -> id; is Sizing -> id }

    /** In plain words, for the log: "shelf 2", "handle 3 move". */
    fun describe(): String = when (this) {
        is Shelf -> "shelf $index"; is Window -> "window $id"; is Handle -> "handle $id ${word.label}"; is Page -> "page $id"; is Moving -> "moving $id"; is Sizing -> "sizing $id"
    }
}

/** What a press asks for besides moving the focus. */
sealed class FocusAction {
    object None : FocusAction()
    data class OpenApp(val index: Int) : FocusAction()
    data class CloseWindow(val id: Int) : FocusAction()
    data class StartMove(val id: Int) : FocusAction()
    data class StartSize(val id: Int) : FocusAction()
    data class TogglePin(val id: Int) : FocusAction()
    /** The key goes to the page itself: an arrow key, or Enter for [FocusKey.SELECT]. */
    data class PageKey(val id: Int, val key: FocusKey) : FocusAction()
    /** One step of a window being moved: round the wearer, up or down, nearer or further. */
    data class MoveStep(val id: Int, val key: FocusKey) : FocusAction()
    /** One step of a window being sized: narrower, wider, shorter, taller. */
    data class SizeStep(val id: Int, val key: FocusKey) : FocusAction()
    /** The window stays as it now is. */
    data class PutDown(val id: Int) : FocusAction()
    /** The window goes back to how it was before it was moved or sized. */
    data class Cancel(val id: Int) : FocusAction()
}

/** A window as the focus sees it: which one, and its direction round the wearer (yaw, positive to the left, radians). */
class FocusWindow(val id: Int, val yaw: Double)

/** What the focus needs to know of the desktop: how many marks the shelf has, the windows that show, where the wearer faces, and the mark the shelf was last left at. */
class FocusScene(val shelfCount: Int, val windows: List<FocusWindow>, val headYaw: Double = 0.0, val shelfIndex: Int = 0)

class FocusStep(val focus: Focus, val action: FocusAction)

object FocusModel {
    /** The windows from left to right round the wearer. Yaw grows to the left, so the largest comes first. */
    fun ordered(windows: List<FocusWindow>): List<FocusWindow> = windows.sortedWith(compareByDescending<FocusWindow> { Geometry.wrap(it.yaw) }.thenBy { it.id })

    /**
     * The window after [id] to the right ([dir] 1) or to the left ([dir] -1). The windows stand in a circle, so after
     * the last comes the first. Null when there is no other.
     */
    fun neighbour(windows: List<FocusWindow>, id: Int, dir: Int): Int? {
        val o = ordered(windows); val i = o.indexOfFirst { it.id == id }
        if (i < 0 || o.size < 2) return null
        return o[((i + dir) % o.size + o.size) % o.size].id
    }

    /** The window whose direction is closest to where the wearer faces. */
    fun nearest(windows: List<FocusWindow>, headYaw: Double): Int? = windows.minWithOrNull(compareBy<FocusWindow> { abs(Geometry.wrap(it.yaw - headYaw)) }.thenBy { it.id })?.id

    /** Where the focus goes when window [id] is closed: the next window by direction, or the shelf if there is none. */
    fun afterClose(scene: FocusScene, id: Int): Focus = neighbour(scene.windows, id, 1)?.let { Focus.Window(it) } ?: shelf(scene, scene.shelfIndex)

    private fun shelf(scene: FocusScene, index: Int): Focus = Focus.Shelf(index.coerceIn(0, maxOf(0, scene.shelfCount - 1)))

    /** The table. [focus] is where the focus is, [key] what was pressed; the answer is where it goes and what to do. */
    fun press(focus: Focus, key: FocusKey, scene: FocusScene): FocusStep {
        fun stay(a: FocusAction = FocusAction.None) = FocusStep(focus, a)
        fun go(f: Focus, a: FocusAction = FocusAction.None) = FocusStep(f, a)
        // a focus left on a window that is no longer there is on the shelf
        val wid = focus.windowId
        if (wid != null && scene.windows.none { it.id == wid }) return press(shelf(scene, scene.shelfIndex), key, scene)
        return when (focus) {
            is Focus.Shelf -> {
                val i = focus.index.coerceIn(0, maxOf(0, scene.shelfCount - 1))
                when (key) {
                    FocusKey.LEFT -> go(shelf(scene, i - 1))
                    FocusKey.RIGHT -> go(shelf(scene, i + 1))
                    FocusKey.UP -> nearest(scene.windows, scene.headYaw)?.let { go(Focus.Window(it)) } ?: go(Focus.Shelf(i))
                    FocusKey.SELECT -> if (scene.shelfCount > 0) go(Focus.Shelf(i), FocusAction.OpenApp(i)) else go(Focus.Shelf(i))
                    else -> go(Focus.Shelf(i))
                }
            }
            is Focus.Window -> when (key) {
                FocusKey.LEFT -> neighbour(scene.windows, focus.id, -1)?.let { go(Focus.Window(it)) } ?: stay()
                FocusKey.RIGHT -> neighbour(scene.windows, focus.id, 1)?.let { go(Focus.Window(it)) } ?: stay()
                FocusKey.DOWN -> go(Focus.Handle(focus.id, HandleWord.MOVE))
                FocusKey.SELECT -> go(Focus.Page(focus.id))
                FocusKey.BACK -> go(shelf(scene, scene.shelfIndex))
                else -> stay()
            }
            is Focus.Handle -> {
                val words = HandleWord.values(); val i = focus.word.ordinal
                when (key) {
                    FocusKey.LEFT -> go(Focus.Handle(focus.id, words[maxOf(0, i - 1)]))
                    FocusKey.RIGHT -> go(Focus.Handle(focus.id, words[minOf(words.size - 1, i + 1)]))
                    FocusKey.UP, FocusKey.BACK -> go(Focus.Window(focus.id))
                    FocusKey.DOWN -> go(shelf(scene, scene.shelfIndex))
                    FocusKey.SELECT -> when (focus.word) {
                        HandleWord.MOVE -> go(Focus.Moving(focus.id), FocusAction.StartMove(focus.id))
                        HandleWord.SIZE -> go(Focus.Sizing(focus.id), FocusAction.StartSize(focus.id))
                        HandleWord.PIN -> stay(FocusAction.TogglePin(focus.id))
                        HandleWord.CLOSE -> go(afterClose(scene, focus.id), FocusAction.CloseWindow(focus.id))
                    }
                    else -> stay()
                }
            }
            is Focus.Page -> when (key) {
                FocusKey.LEFT, FocusKey.RIGHT, FocusKey.UP, FocusKey.DOWN, FocusKey.SELECT -> stay(FocusAction.PageKey(focus.id, key))
                FocusKey.BACK -> go(Focus.Window(focus.id))
                else -> stay()
            }
            // put down or put back, the focus returns to the word that started it, so the next thing is one press away
            is Focus.Moving -> when (key) {
                FocusKey.SELECT -> go(Focus.Handle(focus.id, HandleWord.MOVE), FocusAction.PutDown(focus.id))
                FocusKey.BACK -> go(Focus.Handle(focus.id, HandleWord.MOVE), FocusAction.Cancel(focus.id))
                else -> stay(FocusAction.MoveStep(focus.id, key))
            }
            is Focus.Sizing -> when (key) {
                FocusKey.SELECT -> go(Focus.Handle(focus.id, HandleWord.SIZE), FocusAction.PutDown(focus.id))
                FocusKey.BACK -> go(Focus.Handle(focus.id, HandleWord.SIZE), FocusAction.Cancel(focus.id))
                FocusKey.LEFT, FocusKey.RIGHT, FocusKey.UP, FocusKey.DOWN -> stay(FocusAction.SizeStep(focus.id, key))
                else -> stay()
            }
        }
    }
}

/**
 * Android's key codes as the focus takes them, from the phone's own controls or a Bluetooth gamepad or keyboard.
 * The numbers are those of android.view.KeyEvent, written out so that this file stays free of Android.
 */
object FocusKeys {
    const val KEY_BACK = 4; const val KEY_DPAD_UP = 19; const val KEY_DPAD_DOWN = 20; const val KEY_DPAD_LEFT = 21; const val KEY_DPAD_RIGHT = 22; const val KEY_DPAD_CENTER = 23
    const val KEY_ENTER = 66; const val KEY_BUTTON_A = 96; const val KEY_BUTTON_B = 97; const val KEY_BUTTON_L1 = 102; const val KEY_BUTTON_R1 = 103; const val KEY_ESCAPE = 111; const val KEY_NUMPAD_ENTER = 160

    fun of(keyCode: Int): FocusKey? = when (keyCode) {
        KEY_DPAD_LEFT -> FocusKey.LEFT; KEY_DPAD_RIGHT -> FocusKey.RIGHT; KEY_DPAD_UP -> FocusKey.UP; KEY_DPAD_DOWN -> FocusKey.DOWN
        KEY_DPAD_CENTER, KEY_ENTER, KEY_NUMPAD_ENTER, KEY_BUTTON_A -> FocusKey.SELECT
        KEY_BACK, KEY_ESCAPE, KEY_BUTTON_B -> FocusKey.BACK
        KEY_BUTTON_L1 -> FocusKey.NEARER; KEY_BUTTON_R1 -> FocusKey.FURTHER
        else -> null
    }

    /** The key a page is sent for a direction or Select. */
    fun pageKey(key: FocusKey): Int? = when (key) {
        FocusKey.LEFT -> KEY_DPAD_LEFT; FocusKey.RIGHT -> KEY_DPAD_RIGHT; FocusKey.UP -> KEY_DPAD_UP; FocusKey.DOWN -> KEY_DPAD_DOWN; FocusKey.SELECT -> KEY_ENTER
        else -> null
    }
}

/**
 * Back held down. While a game's page is in use every key goes to the game, Back too; held for [holdNs] it leaves the
 * page instead. [due] answers true once, when the hold is complete.
 */
class BackHold(private val holdNs: Long = 1_000_000_000L) {
    private var downNs = 0L; private var fired = false
    fun down(nowNs: Long) { if (downNs == 0L) { downNs = nowNs; fired = false } }
    fun up() { downNs = 0L; fired = false }
    fun due(nowNs: Long): Boolean { if (downNs == 0L || fired || nowNs - downNs < holdNs) return false; fired = true; return true }
}

/**
 * The handle under the focused window: four words side by side, a little below the page, in the middle under it. How
 * wide and high the strip is and how far below the page is the window's to say ([DesktopWindow.shownHandleWidth],
 * [DesktopWindow.shownHandleHeight], [DesktopWindow.shownHandleGap]); each word has a quarter of the strip.
 */
object HandleStrip {
    /** The word at [u] (0..1 across the handle), or null beside it. */
    fun wordAt(u: Double): HandleWord? { val n = HandleWord.values().size; return if (u < 0.0 || u >= 1.0) null else HandleWord.values()[(u * n).toInt().coerceIn(0, n - 1)] }

    /** The middle of a word, 0..1 across the handle. */
    fun centre(word: HandleWord): Double = (word.ordinal + 0.5) / HandleWord.values().size
}
