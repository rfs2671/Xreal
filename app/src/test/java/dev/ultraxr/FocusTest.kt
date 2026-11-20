package dev.ultraxr

import dev.ultraxr.desktop.BackHold
import dev.ultraxr.desktop.Desktop
import dev.ultraxr.desktop.DesktopWindow
import dev.ultraxr.desktop.Focus
import dev.ultraxr.desktop.FocusAction
import dev.ultraxr.desktop.FocusKey
import dev.ultraxr.desktop.FocusKeys
import dev.ultraxr.desktop.FocusModel
import dev.ultraxr.desktop.FocusScene
import dev.ultraxr.desktop.FocusWindow
import dev.ultraxr.desktop.Geometry
import dev.ultraxr.desktop.HandleStrip
import dev.ultraxr.desktop.HandleWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one focus: every row of the table in docs/INTERFACE.md ("Focus and the d-pad"), and what the handle's words do. */
class FocusTest {
    private val origin = doubleArrayOf(0.0, 0.0, 0.0)
    private fun deg(d: Double) = Math.toRadians(d)

    /** Three windows: 1 to the left (yaw +40), 2 straight ahead, 3 to the right (yaw -40). Five marks on the shelf, last left at the third. */
    private val scene = FocusScene(5, listOf(FocusWindow(2, 0.0), FocusWindow(3, deg(-40.0)), FocusWindow(1, deg(40.0))), headYaw = 0.0, shelfIndex = 2)
    private val empty = FocusScene(5, emptyList())
    private fun press(f: Focus, k: FocusKey, s: FocusScene = scene) = FocusModel.press(f, k, s)
    private fun assertStays(f: Focus, k: FocusKey, s: FocusScene = scene) { val r = press(f, k, s); assertEquals(f, r.focus); assertEquals(FocusAction.None, r.action) }

    // ---- the table, row by row
    @Test fun shelfRow() {
        assertEquals(Focus.Shelf(1), press(Focus.Shelf(2), FocusKey.LEFT).focus)
        assertEquals(Focus.Shelf(3), press(Focus.Shelf(2), FocusKey.RIGHT).focus)
        assertEquals("the row ends at its first mark", Focus.Shelf(0), press(Focus.Shelf(0), FocusKey.LEFT).focus)
        assertEquals("and at its last", Focus.Shelf(4), press(Focus.Shelf(4), FocusKey.RIGHT).focus)
        assertEquals("up: to the nearest window", Focus.Window(2), press(Focus.Shelf(2), FocusKey.UP).focus)
        assertStays(Focus.Shelf(2), FocusKey.UP, empty)
        assertStays(Focus.Shelf(2), FocusKey.DOWN)
        val open = press(Focus.Shelf(2), FocusKey.SELECT)
        assertEquals(FocusAction.OpenApp(2), open.action); assertEquals(Focus.Shelf(2), open.focus)
        assertStays(Focus.Shelf(2), FocusKey.BACK)
        assertStays(Focus.Shelf(2), FocusKey.NEARER); assertStays(Focus.Shelf(2), FocusKey.FURTHER)
        assertEquals("an empty shelf opens nothing", FocusAction.None, press(Focus.Shelf(0), FocusKey.SELECT, FocusScene(0, emptyList())).action)
    }

    @Test fun windowRow() {
        assertEquals("left: the window to the left", Focus.Window(1), press(Focus.Window(2), FocusKey.LEFT).focus)
        assertEquals("right: the window to the right", Focus.Window(3), press(Focus.Window(2), FocusKey.RIGHT).focus)
        assertStays(Focus.Window(2), FocusKey.UP)
        assertEquals("down: to its handle", Focus.Handle(2, HandleWord.MOVE), press(Focus.Window(2), FocusKey.DOWN).focus)
        val use = press(Focus.Window(2), FocusKey.SELECT)
        assertEquals("select: the page is in use", Focus.Page(2), use.focus); assertEquals(FocusAction.None, use.action)
        assertEquals("back: to the shelf, where it was left", Focus.Shelf(2), press(Focus.Window(2), FocusKey.BACK).focus)
        assertStays(Focus.Window(2), FocusKey.NEARER)
        assertStays(Focus.Window(7), FocusKey.LEFT, FocusScene(5, listOf(FocusWindow(7, 0.0))))
    }

    @Test fun handleRow() {
        assertEquals(Focus.Handle(2, HandleWord.SIZE), press(Focus.Handle(2, HandleWord.MOVE), FocusKey.RIGHT).focus)
        assertEquals(Focus.Handle(2, HandleWord.PIN), press(Focus.Handle(2, HandleWord.SIZE), FocusKey.RIGHT).focus)
        assertEquals(Focus.Handle(2, HandleWord.CLOSE), press(Focus.Handle(2, HandleWord.PIN), FocusKey.RIGHT).focus)
        assertEquals("close is the last word", Focus.Handle(2, HandleWord.CLOSE), press(Focus.Handle(2, HandleWord.CLOSE), FocusKey.RIGHT).focus)
        assertEquals(Focus.Handle(2, HandleWord.PIN), press(Focus.Handle(2, HandleWord.CLOSE), FocusKey.LEFT).focus)
        assertEquals("move is the first", Focus.Handle(2, HandleWord.MOVE), press(Focus.Handle(2, HandleWord.MOVE), FocusKey.LEFT).focus)
        assertEquals("up: to the window", Focus.Window(2), press(Focus.Handle(2, HandleWord.PIN), FocusKey.UP).focus)
        assertEquals("down: to the shelf", Focus.Shelf(2), press(Focus.Handle(2, HandleWord.PIN), FocusKey.DOWN).focus)
        assertEquals("back: to the window", Focus.Window(2), press(Focus.Handle(2, HandleWord.PIN), FocusKey.BACK).focus)
        // select: do it
        var r = press(Focus.Handle(2, HandleWord.MOVE), FocusKey.SELECT)
        assertEquals(Focus.Moving(2), r.focus); assertEquals(FocusAction.StartMove(2), r.action)
        r = press(Focus.Handle(2, HandleWord.SIZE), FocusKey.SELECT)
        assertEquals(Focus.Sizing(2), r.focus); assertEquals(FocusAction.StartSize(2), r.action)
        r = press(Focus.Handle(2, HandleWord.PIN), FocusKey.SELECT)
        assertEquals(Focus.Handle(2, HandleWord.PIN), r.focus); assertEquals(FocusAction.TogglePin(2), r.action)
        r = press(Focus.Handle(2, HandleWord.CLOSE), FocusKey.SELECT)
        assertEquals(FocusAction.CloseWindow(2), r.action); assertEquals("the next window by direction", Focus.Window(3), r.focus)
        r = press(Focus.Handle(7, HandleWord.CLOSE), FocusKey.SELECT, FocusScene(5, listOf(FocusWindow(7, 0.0)), shelfIndex = 4))
        assertEquals(FocusAction.CloseWindow(7), r.action); assertEquals("or the shelf if there is none", Focus.Shelf(4), r.focus)
        assertEquals(listOf("move", "size", "pin", "close"), HandleWord.values().map { it.label })
    }

    @Test fun pageRow() {
        for (k in listOf(FocusKey.LEFT, FocusKey.RIGHT, FocusKey.UP, FocusKey.DOWN, FocusKey.SELECT)) {
            val r = press(Focus.Page(2), k)
            assertEquals("the page keeps the focus", Focus.Page(2), r.focus); assertEquals(FocusAction.PageKey(2, k), r.action)
        }
        assertEquals("the page gets real arrow keys", FocusKeys.KEY_DPAD_LEFT, FocusKeys.pageKey(FocusKey.LEFT))
        assertEquals("and Enter for Select", FocusKeys.KEY_ENTER, FocusKeys.pageKey(FocusKey.SELECT))
        assertNull(FocusKeys.pageKey(FocusKey.BACK))
        val back = press(Focus.Page(2), FocusKey.BACK)
        assertEquals("back: leave the page, focus the window", Focus.Window(2), back.focus); assertEquals(FocusAction.None, back.action)
        assertStays(Focus.Page(2), FocusKey.NEARER); assertStays(Focus.Page(2), FocusKey.FURTHER)
    }

    @Test fun movingRow() {
        for (k in listOf(FocusKey.LEFT, FocusKey.RIGHT, FocusKey.UP, FocusKey.DOWN, FocusKey.NEARER, FocusKey.FURTHER)) {
            val r = press(Focus.Moving(2), k)
            assertEquals(Focus.Moving(2), r.focus); assertEquals(FocusAction.MoveStep(2, k), r.action)
        }
        var r = press(Focus.Moving(2), FocusKey.SELECT)
        assertEquals("select puts it down", FocusAction.PutDown(2), r.action); assertEquals(Focus.Handle(2, HandleWord.MOVE), r.focus)
        r = press(Focus.Moving(2), FocusKey.BACK)
        assertEquals("back puts it back as it was", FocusAction.Cancel(2), r.action); assertEquals(Focus.Handle(2, HandleWord.MOVE), r.focus)
    }

    @Test fun sizingRow() {
        for (k in listOf(FocusKey.LEFT, FocusKey.RIGHT, FocusKey.UP, FocusKey.DOWN)) {
            val r = press(Focus.Sizing(2), k)
            assertEquals(Focus.Sizing(2), r.focus); assertEquals(FocusAction.SizeStep(2, k), r.action)
        }
        assertStays(Focus.Sizing(2), FocusKey.NEARER)
        var r = press(Focus.Sizing(2), FocusKey.SELECT)
        assertEquals(FocusAction.PutDown(2), r.action); assertEquals(Focus.Handle(2, HandleWord.SIZE), r.focus)
        r = press(Focus.Sizing(2), FocusKey.BACK)
        assertEquals(FocusAction.Cancel(2), r.action); assertEquals(Focus.Handle(2, HandleWord.SIZE), r.focus)
    }

    @Test fun aFocusOnAWindowThatIsGoneActsFromTheShelf() {
        assertEquals(Focus.Shelf(3), press(Focus.Page(99), FocusKey.RIGHT).focus)
        assertEquals(FocusAction.OpenApp(2), press(Focus.Handle(99, HandleWord.CLOSE), FocusKey.SELECT).action)
    }

    // ---- previous and next window by direction
    @Test fun windowsAreOrderedByDirectionRoundTheWearer() {
        val ws = listOf(FocusWindow(1, deg(10.0)), FocusWindow(2, deg(-80.0)), FocusWindow(3, deg(170.0)), FocusWindow(4, deg(-170.0)), FocusWindow(5, deg(80.0)))
        assertEquals("from the left round to the right", listOf(3, 5, 1, 2, 4), FocusModel.ordered(ws).map { it.id })
        assertEquals(2, FocusModel.neighbour(ws, 1, 1)); assertEquals(5, FocusModel.neighbour(ws, 1, -1))
        // behind the wearer the circle closes: right of the last is the first
        assertEquals(3, FocusModel.neighbour(ws, 4, 1)); assertEquals(4, FocusModel.neighbour(ws, 3, -1))
        assertNull("no other window", FocusModel.neighbour(listOf(FocusWindow(1, 0.0)), 1, 1))
        assertNull("not among them", FocusModel.neighbour(ws, 9, 1))
        // the same direction: by the order they were opened in
        assertEquals(listOf(7, 8), FocusModel.ordered(listOf(FocusWindow(8, 0.5), FocusWindow(7, 0.5))).map { it.id })
        // a direction given more than once round means the same place
        assertEquals(listOf(2, 1), FocusModel.ordered(listOf(FocusWindow(1, deg(350.0)), FocusWindow(2, deg(20.0)))).map { it.id })
    }

    @Test fun theNearestWindowIsTheOneClosestToWhereTheWearerFaces() {
        val ws = listOf(FocusWindow(1, deg(40.0)), FocusWindow(2, 0.0), FocusWindow(3, deg(-40.0)), FocusWindow(4, deg(175.0)))
        assertEquals(2, FocusModel.nearest(ws, deg(5.0))); assertEquals(3, FocusModel.nearest(ws, deg(-30.0)))
        assertEquals("across the seam behind", 4, FocusModel.nearest(ws, deg(-170.0)))
        assertNull(FocusModel.nearest(emptyList(), 0.0))
    }

    @Test fun theDesktopOrdersItsWindowsByWhereTheyAreInTheRoom() {
        val d = Desktop(); val a = d.add("A", "u")!!; val b = d.add("B", "u")!!; val c = d.add("C", "u")!!
        a.yaw = 0.0; b.yaw = deg(-40.0); c.yaw = deg(40.0)
        // a window that follows the head is placed against the follow heading: turned 100 degrees left, b is now the leftmost
        d.toggleFollow(b.id); d.followAnchor.yaw = deg(100.0)
        assertEquals(deg(60.0), d.worldYaw(b), 1e-9)
        assertEquals(listOf(b.id, c.id, a.id), FocusModel.ordered(d.scene(5).windows).map { it.id })
        d.point(Focus.Window(c.id))
        d.press(FocusKey.LEFT, 5); assertEquals(Focus.Window(b.id), d.focus); assertEquals(b.id, d.focusedId)
        d.press(FocusKey.LEFT, 5); assertEquals("round the circle", Focus.Window(a.id), d.focus)
        d.minimize(c.id)
        d.press(FocusKey.LEFT, 5); assertEquals("a window put away is passed over", Focus.Window(b.id), d.focus)
    }

    // ---- keys
    @Test fun androidsKeysBecomeTheKeysOfTheFocus() {
        assertEquals(FocusKey.UP, FocusKeys.of(19)); assertEquals(FocusKey.DOWN, FocusKeys.of(20)); assertEquals(FocusKey.LEFT, FocusKeys.of(21)); assertEquals(FocusKey.RIGHT, FocusKeys.of(22))
        for (code in listOf(23, 66, 160, 96)) assertEquals("d-pad centre, Enter, A", FocusKey.SELECT, FocusKeys.of(code))
        for (code in listOf(4, 111, 97)) assertEquals("Back, Escape, B", FocusKey.BACK, FocusKeys.of(code))
        assertEquals(FocusKey.NEARER, FocusKeys.of(102)); assertEquals(FocusKey.FURTHER, FocusKeys.of(103))
        for (code in listOf(24, 25, 29, 99, 100, 82)) assertNull("volume keys, letters, X and Y, Menu are not the focus's", FocusKeys.of(code))
    }

    @Test fun backHeldForASecondLeavesAGameOnce() {
        val ms = 1_000_000L; val h = BackHold()
        assertFalse(h.due(0))
        h.down(100 * ms); assertFalse(h.due(900 * ms))
        h.down(500 * ms)      // the key repeating while held does not start the second again
        assertTrue(h.due(1100 * ms)); assertFalse("once", h.due(1200 * ms))
        h.up(); assertFalse(h.due(5000 * ms))
        h.down(6000 * ms); h.up(); assertFalse("a short press leaves nothing", h.due(8000 * ms))
    }

    // ---- the handle's words on a real desktop
    private fun desktopWithThree(): Triple<Desktop, DesktopWindow, List<DesktopWindow>> {
        val d = Desktop(); val a = d.add("A", "u")!!; val b = d.add("B", "u")!!; val c = d.add("C", "u")!!
        a.yaw = 0.0; b.yaw = deg(-40.0); c.yaw = deg(40.0)      // c, a, b from left to right
        d.point(Focus.Window(a.id))
        return Triple(d, a, listOf(a, b, c))
    }
    private fun Desktop.toHandle(word: HandleWord) { press(FocusKey.DOWN, 5); repeat(word.ordinal) { press(FocusKey.RIGHT, 5) } }

    @Test fun openingAWindowPutsItsPageInUseAndBackWalksOut() {
        val d = Desktop()
        assertEquals("with nothing open the focus is on the shelf", Focus.Shelf(0), d.focus)
        d.press(FocusKey.RIGHT, 5); d.press(FocusKey.RIGHT, 5)
        assertEquals(FocusAction.OpenApp(2), d.press(FocusKey.SELECT, 5))
        val w = d.add("A", "u")!!
        assertEquals(Focus.Page(w.id), d.focus)
        d.press(FocusKey.BACK, 5); assertEquals(Focus.Window(w.id), d.focus)
        d.press(FocusKey.BACK, 5); assertEquals("the shelf, at the mark it was left on", Focus.Shelf(2), d.focus)
        assertEquals(w.id, d.focusedId)
        d.press(FocusKey.UP, 5); assertEquals(Focus.Window(w.id), d.focus)
    }

    @Test fun movingStepsRoundUpAndNearerAndSelectPutsItDown() {
        val (d, a, _) = desktopWithThree()
        d.toHandle(HandleWord.MOVE); assertEquals(FocusAction.StartMove(a.id), d.press(FocusKey.SELECT, 5))
        assertEquals(Focus.Moving(a.id), d.focus)
        d.press(FocusKey.LEFT, 5); assertEquals("left is round to the wearer's left", Desktop.MOVE_STEP, a.yaw, 1e-9)
        d.press(FocusKey.RIGHT, 5); d.press(FocusKey.RIGHT, 5); assertEquals(-Desktop.MOVE_STEP, a.yaw, 1e-9)
        d.press(FocusKey.UP, 5); assertEquals(Desktop.MOVE_STEP, a.pitch, 1e-9)
        d.press(FocusKey.DOWN, 5); d.press(FocusKey.DOWN, 5); assertEquals(-Desktop.MOVE_STEP, a.pitch, 1e-9)
        d.press(FocusKey.NEARER, 5); assertEquals(DesktopWindow.DEFAULT_DISTANCE / Desktop.PUSH_STEP, a.distance, 1e-9)
        d.press(FocusKey.FURTHER, 5); d.press(FocusKey.FURTHER, 5); assertEquals(DesktopWindow.DEFAULT_DISTANCE * Desktop.PUSH_STEP, a.distance, 1e-9)
        repeat(60) { d.press(FocusKey.NEARER, 5) }; assertEquals(DesktopWindow.MIN_DISTANCE, a.distance, 1e-9)
        repeat(60) { d.press(FocusKey.FURTHER, 5) }; assertEquals(DesktopWindow.FAR_DISTANCE, a.distance, 1e-9)
        assertEquals(FocusAction.PutDown(a.id), d.press(FocusKey.SELECT, 5))
        assertEquals(Focus.Handle(a.id, HandleWord.MOVE), d.focus)
        assertEquals("it stays where it was put", -Desktop.MOVE_STEP, a.yaw, 1e-9); assertEquals(DesktopWindow.FAR_DISTANCE, a.distance, 1e-9)
        d.putBack(); assertEquals("and there is nothing left to put back", -Desktop.MOVE_STEP, a.yaw, 1e-9)
    }

    @Test fun backWhileMovingPutsTheWindowBackWhereItWas() {
        val (d, a, _) = desktopWithThree()
        a.yaw = 0.3; a.pitch = -0.1; a.distance = 1.5
        d.toHandle(HandleWord.MOVE); d.press(FocusKey.SELECT, 5)
        repeat(5) { d.press(FocusKey.LEFT, 5) }; repeat(3) { d.press(FocusKey.UP, 5) }; d.press(FocusKey.FURTHER, 5)
        d.move(a.id, 0.2, 0.1)      // and by the pointer
        assertTrue(a.yaw > 0.5)
        assertEquals(FocusAction.Cancel(a.id), d.press(FocusKey.BACK, 5))
        assertEquals(0.3, a.yaw, 1e-9); assertEquals(-0.1, a.pitch, 1e-9); assertEquals(1.5, a.distance, 1e-9)
        assertEquals(Focus.Handle(a.id, HandleWord.MOVE), d.focus)
    }

    @Test fun sizingIsFreeFourPerCentAPress() {
        val (d, a, _) = desktopWithThree()
        val w0 = a.width; val h0 = a.height
        d.toHandle(HandleWord.SIZE); assertEquals(FocusAction.StartSize(a.id), d.press(FocusKey.SELECT, 5))
        assertEquals(Focus.Sizing(a.id), d.focus)
        d.press(FocusKey.RIGHT, 5)
        assertEquals("right: wider by 4 %", w0 * 1.04, a.width, 1e-9); assertEquals("and no taller", h0, a.height, 1e-9)
        d.press(FocusKey.LEFT, 5); d.press(FocusKey.LEFT, 5)
        assertEquals("left: narrower", w0 / 1.04, a.width, 1e-9); assertEquals(h0, a.height, 1e-9)
        d.press(FocusKey.RIGHT, 5)
        d.press(FocusKey.DOWN, 5)
        assertEquals("down: taller by 4 %", h0 * 1.04, a.height, 1e-9); assertEquals("and no wider", w0, a.width, 1e-9)
        d.press(FocusKey.UP, 5); d.press(FocusKey.UP, 5)
        assertEquals("up: shorter", h0 / 1.04, a.height, 1e-9); assertEquals(w0, a.width, 1e-9)
        assertEquals(FocusAction.PutDown(a.id), d.press(FocusKey.SELECT, 5))
        assertEquals("select keeps it", h0 / 1.04, a.height, 1e-9); assertEquals(Focus.Handle(a.id, HandleWord.SIZE), d.focus)
    }

    @Test fun theWidthStaysBetweenItsLimits() {
        val (d, a, _) = desktopWithThree()
        d.toHandle(HandleWord.SIZE); d.press(FocusKey.SELECT, 5)
        repeat(200) { d.press(FocusKey.DOWN, 5); d.press(FocusKey.RIGHT, 5) }
        assertEquals("3.2 m at most", DesktopWindow.MAX_WIDTH, a.width, 1e-9)
        repeat(300) { d.press(FocusKey.UP, 5); d.press(FocusKey.LEFT, 5) }
        assertEquals("0.4 m at least", DesktopWindow.MIN_WIDTH, a.width, 1e-9)
        // and the shape between its limits, so a window never becomes a line
        repeat(300) { d.press(FocusKey.UP, 5) }; assertEquals(DesktopWindow.MAX_ASPECT, a.aspect, 1e-9); assertEquals(DesktopWindow.MIN_WIDTH, a.width, 1e-9)
        repeat(300) { d.press(FocusKey.DOWN, 5) }; assertEquals(DesktopWindow.MIN_ASPECT, a.aspect, 1e-9); assertEquals(DesktopWindow.MIN_WIDTH, a.width, 1e-9)
        val h = a.height
        repeat(300) { d.press(FocusKey.RIGHT, 5) }
        assertEquals("widening stops at the widest shape, without the height changing", DesktopWindow.MAX_ASPECT, a.aspect, 1e-9); assertEquals(h, a.height, 1e-9)
        // by a dragged corner the same limits hold
        d.sizeTo(a.id, 99.0, 0.001); assertEquals(DesktopWindow.MAX_WIDTH, a.width, 1e-9); assertEquals(DesktopWindow.MAX_ASPECT, a.aspect, 1e-9)
        d.sizeTo(a.id, 0.0, 99.0); assertEquals(DesktopWindow.MIN_WIDTH, a.width, 1e-9); assertEquals(DesktopWindow.MIN_ASPECT, a.aspect, 1e-9)
        d.sizeTo(a.id, 1.0, 0.5); assertEquals(1.0, a.width, 1e-9); assertEquals(0.5, a.height, 1e-9)
    }

    @Test fun backWhileSizingRestoresTheOldSize() {
        val (d, a, _) = desktopWithThree()
        a.width = 1.4; a.aspect = 1.25
        d.toHandle(HandleWord.SIZE); d.press(FocusKey.SELECT, 5)
        repeat(7) { d.press(FocusKey.RIGHT, 5) }; repeat(4) { d.press(FocusKey.UP, 5) }
        assertTrue(a.width > 1.4 && a.aspect > 1.25)
        assertEquals(FocusAction.Cancel(a.id), d.press(FocusKey.BACK, 5))
        assertEquals(1.4, a.width, 1e-9); assertEquals(1.25, a.aspect, 1e-9)
        assertEquals(Focus.Handle(a.id, HandleWord.SIZE), d.focus)
    }

    @Test fun aMaximizedWindowIsLetGoToBeMovedOrSizedAndBackRestoresThatToo() {
        val (d, a, _) = desktopWithThree()
        d.toggleMaximize(a.id); d.point(Focus.Window(a.id))
        d.toHandle(HandleWord.SIZE); d.press(FocusKey.SELECT, 5)
        assertFalse(a.maximized)
        d.press(FocusKey.RIGHT, 5); assertEquals(DesktopWindow.DEFAULT_WIDTH * 1.04, a.width, 1e-9)
        d.press(FocusKey.BACK, 5)
        assertTrue(a.maximized); assertEquals(DesktopWindow.DEFAULT_WIDTH, a.width, 1e-9)
    }

    @Test fun aCornerDraggedByThePointerCanBePutBackToo() {
        val (d, a, _) = desktopWithThree()
        val w0 = a.width; val asp = a.aspect
        d.point(Focus.Page(a.id))
        d.beginDrag(a.id); d.sizeTo(a.id, 2.0, 1.9)
        assertEquals("the focus stays where it was", Focus.Page(a.id), d.focus)
        d.putBack(); assertEquals(w0, a.width, 1e-9); assertEquals(asp, a.aspect, 1e-9)
        d.beginDrag(a.id); d.sizeTo(a.id, 2.0, 1.0); d.endDrag(); d.putBack()
        assertEquals("let go, it keeps its size", 2.0, a.width, 1e-9); assertEquals(2.0, a.aspect, 1e-9)
    }

    @Test fun pinIsTheOldLockedAndFollowingUnderItsNewName() {
        val (d, a, _) = desktopWithThree()
        assertFalse("a window is pinned to the room by default", a.follow)
        a.yaw = 0.3; d.followAnchor.yaw = 1.0; val before = d.worldYaw(a)
        d.toHandle(HandleWord.PIN)
        assertEquals(FocusAction.TogglePin(a.id), d.press(FocusKey.SELECT, 5))
        assertTrue("unpinned, it follows the head", a.follow); assertEquals("without jumping", before, d.worldYaw(a), 1e-9)
        assertEquals("the focus stays on the word", Focus.Handle(a.id, HandleWord.PIN), d.focus)
        d.press(FocusKey.SELECT, 5); assertFalse(a.follow); assertEquals(before, d.worldYaw(a), 1e-9)
    }

    @Test fun closeMovesTheFocusToTheNextWindowByDirectionThenToTheShelf() {
        val (d, a, ws) = desktopWithThree(); val b = ws[1]; val c = ws[2]
        d.toHandle(HandleWord.CLOSE)
        assertEquals(FocusAction.CloseWindow(a.id), d.press(FocusKey.SELECT, 5))
        assertNull(d.find(a.id))
        assertEquals("the next one to the right", Focus.Window(b.id), d.focus); assertEquals(b.id, d.focusedId)
        d.toHandle(HandleWord.CLOSE); d.press(FocusKey.SELECT, 5)
        assertEquals("right of the rightmost is the leftmost", Focus.Window(c.id), d.focus)
        d.toHandle(HandleWord.CLOSE); d.press(FocusKey.SELECT, 5)
        assertEquals("none left: the shelf", Focus.Shelf(0), d.focus); assertEquals(-1, d.focusedId); assertTrue(d.windows.isEmpty())
    }

    @Test fun closingFromThePhoneFollowsTheSameRuleAndLeavesAFocusElsewhereAlone() {
        val (d, a, ws) = desktopWithThree(); val b = ws[1]; val c = ws[2]
        d.point(Focus.Page(c.id))
        d.close(a.id); assertEquals("another window closed: the focus stays", Focus.Page(c.id), d.focus)
        d.close(c.id); assertEquals(Focus.Window(b.id), d.focus)
        d.startMove(b.id); d.move(b.id, 0.5, 0.0); d.close(b.id)
        assertEquals(Focus.Shelf(0), d.focus)
        val e = d.add("E", "u")!!; val y = e.yaw
        d.putBack(); assertEquals("nothing of the closed window is put back on another", y, e.yaw, 1e-9)
    }

    @Test fun aWindowPutAwayOrFillingTheViewDoesNotKeepAFocusItCannotShow() {
        val (d, a, ws) = desktopWithThree(); val b = ws[1]
        d.toHandle(HandleWord.SIZE)
        d.minimize(a.id); assertEquals("the focus goes to the window that takes over", Focus.Window(d.focusedId), d.focus); assertTrue(d.focusedId != a.id)
        d.focus(b.id); assertEquals("chosen on the phone, a window's page is in use", Focus.Page(b.id), d.focus)
        d.toggleFullscreen(b.id); assertEquals(Focus.Page(b.id), d.focus); assertEquals(-1, d.handleId)
        d.press(FocusKey.BACK, 5); assertEquals(Focus.Window(b.id), d.focus); assertEquals(b.id, d.fullscreenId)
        d.press(FocusKey.DOWN, 5)
        assertEquals("going to the handle ends filling the view, so the handle can be seen", -1, d.fullscreenId); assertEquals(b.id, d.handleId)
        for (w in d.windows.toList()) d.minimize(w.id)
        assertEquals("everything put away: the shelf", Focus.Shelf(0), d.focus)
    }

    // ---- pointers: the same focus however it is reached
    @Test fun aPointerMovesTheFocusToWhatItIsOver() {
        val (d, a, ws) = desktopWithThree(); val b = ws[1]
        assertTrue(d.point(Focus.Shelf(3))); assertEquals(Focus.Shelf(3), d.focus); assertEquals(3, d.shelfIndex)
        assertFalse("already there", d.point(Focus.Shelf(3)))
        assertTrue(d.point(Focus.Handle(b.id, HandleWord.PIN))); assertEquals(b.id, d.focusedId)
        assertFalse("not onto a window that is not there", d.point(Focus.Page(99)))
        // a look that rests on a window brings the focus to the window, and leaves a focus already on it alone
        assertFalse(d.look(b.id)); assertEquals(Focus.Handle(b.id, HandleWord.PIN), d.focus)
        assertTrue(d.look(a.id)); assertEquals(Focus.Window(a.id), d.focus)
        // while a window is carried the focus is not taken from it
        d.startMove(a.id)
        assertFalse(d.point(Focus.Shelf(0))); assertFalse(d.look(b.id)); assertEquals(Focus.Moving(a.id), d.focus)
    }

    @Test fun theHandleIsUnderTheWindowInFocusAndARayFindsItsWords() {
        val d = Desktop(); val w = d.add("A", "u")!!
        // the handle is in the middle under the page, as wide as its words need: u runs across the handle
        val gap = w.shownHandleGap(); val wide = w.shownHandleWidth()
        assertTrue("no wider than the window", wide <= w.width); assertTrue("each word has room", wide / 4 > 2.5 * w.shownHandleHeight())
        fun ray(u: Double, below: Double) = doubleArrayOf((u - 0.5) * wide, -w.height / 2 - below, -w.distance)
        val mid = gap + w.shownHandleHeight() / 2
        assertEquals(w.id, d.handleId)
        for (word in HandleWord.values()) {
            val h = d.hit(origin, ray(HandleStrip.centre(word), mid), origin)!!
            assertTrue(h.onHandle); assertFalse(h.onPage); assertEquals(word, h.handleWord); assertEquals(Focus.Handle(w.id, word), d.focusAt(h))
        }
        assertNull("below the words there is nothing", d.hit(origin, ray(0.5, gap + w.shownHandleHeight() + 0.02), origin))
        val beside = d.hit(origin, doubleArrayOf(w.width / 2 - 0.01, -w.height / 2 - mid, -w.distance), origin)!!
        assertTrue("under the page but beside the words: the handle, and no word", beside.onHandle); assertNull(beside.handleWord); assertEquals(Focus.Window(w.id), d.focusAt(beside))
        val between = d.hit(origin, ray(0.5, gap / 2), origin)!!
        assertTrue("between the page and the words: no word", between.onHandle); assertNull(between.handleWord)
        assertEquals("on the page", Focus.Page(w.id), d.focusAt(d.hit(origin, Geometry.direction(0.0, 0.0), origin)!!))
        assertNull("there is no title bar: above the page is not the window", d.hit(origin, doubleArrayOf(0.0, w.height / 2 + 0.03, -w.distance), origin))
        // the touchpad's cursor says the same: v above 1 is the handle. Its u runs across the window, the words stand across the handle.
        fun across(word: HandleWord) = 0.5 + (HandleStrip.centre(word) - 0.5) * wide / w.width
        assertEquals(Focus.Page(w.id), d.focusAt(w, 0.3, 0.7))
        for (word in HandleWord.values()) assertEquals(Focus.Handle(w.id, word), d.focusAt(w, across(word), 1.0 + mid / w.height))
        assertEquals("beside the words", Focus.Window(w.id), d.focusAt(w, 0.01, 1.0 + mid / w.height))
        assertEquals("between the page and the words", Focus.Window(w.id), d.focusAt(w, 0.5, 1.0 + gap / 2 / w.height))
        // only the window in focus has a handle
        val other = d.add("B", "u")!!
        assertEquals(other.id, d.handleId)
        assertNull(d.hit(origin, ray(0.5, mid), origin))
        d.press(FocusKey.BACK, 5); d.press(FocusKey.BACK, 5)
        assertEquals("with the focus on the shelf no window has one", -1, d.handleId)
        assertNull(HandleStrip.wordAt(-0.1)); assertNull(HandleStrip.wordAt(1.0)); assertEquals(HandleWord.SIZE, HandleStrip.wordAt(0.3)); assertEquals(HandleWord.PIN, HandleStrip.wordAt(0.6))
    }

    @Test fun aRayFindsTheCornersOfAPageAndNothingElseBesideIt() {
        val d = Desktop(); val w = d.add("A", "u")!!; val r = w.shownCornerReach()
        fun ray(x: Double, y: Double) = doubleArrayOf(x, y, -w.distance)
        val right = w.width / 2; val top = w.height / 2
        assertEquals(0, d.hit(origin, ray(-right + r / 2, top - r / 2), origin)!!.corner)
        assertEquals(1, d.hit(origin, ray(right - r / 2, top - r / 2), origin)!!.corner)
        assertEquals(2, d.hit(origin, ray(-right + r / 2, -top + r / 2), origin)!!.corner)
        val outside = d.hit(origin, ray(right + r / 2, -top + r / 2), origin)!!
        assertEquals("a little beside the page too", 3, outside.corner); assertFalse(outside.onPage); assertEquals(Focus.Window(w.id), d.focusAt(outside))
        assertEquals("the middle of the page is no corner", -1, d.hit(origin, ray(0.0, 0.0), origin)!!.corner)
        assertEquals("nor the middle of an edge", -1, d.hit(origin, ray(right - r / 2, 0.0), origin)!!.corner)
        assertNull("beside the page, away from the corners, is not the window", d.hit(origin, ray(right + r / 2, 0.0), origin))
    }

    @Test fun thePagesPictureFollowsTheWindowsShape() {
        assertEquals(listOf(1280, 720), DesktopWindow.pixels(16.0 / 9.0).toList())
        assertEquals(listOf(720, 1280), DesktopWindow.pixels(9.0 / 16.0).toList())
        for (a in listOf(0.4, 0.75, 1.0, 1.5, 2.2, 3.0)) {
            val p = DesktopWindow.pixels(a)
            assertEquals("the picture has the window's shape", a, p[0].toDouble() / p[1], 0.03)
            assertEquals("and about as many pixels whatever the shape", 1280.0 * 720.0, (p[0] * p[1]).toDouble(), 1280.0 * 720.0 * 0.03)
            assertEquals(0, p[0] % 8); assertEquals(0, p[1] % 8)
        }
    }
}
