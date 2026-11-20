package dev.ultraxr

import dev.ultraxr.desktop.FocusKey
import dev.ultraxr.desktop.SettingRow
import dev.ultraxr.desktop.SettingsList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings in the glasses as a list: up and down go from row to row, left and right change a value, and a press finds the row it is on. */
class SettingsListTest {
    private var number = 5; private var on = false; private var done = 0; private var left = 0
    private fun list() = SettingsList(listOf(
        SettingRow("Number", { "$number" }, { "A number." }, step = { d -> number += d }),
        SettingRow("Switch", { if (on) "on" else "off" }, { "A switch." }, step = { d -> on = d > 0 }, select = { on = !on }),
        SettingRow("Action", { "" }, { "Does a thing." }, select = { done++ }, leave = { left++ }),
        SettingRow("Words", { "" }, { "Says a thing." }),
        SettingRow("Fifth", { "" }, { "" }), SettingRow("Sixth", { "" }, { "" }), SettingRow("Seventh", { "" }, { "" })))

    @Test fun upAndDownGoFromRowToRowAndTheListSlidesWhenTheFocusPassesItsEnd() {
        val l = list()
        assertEquals(0, l.index); assertEquals(0, l.first)
        assertFalse("the first row is the top", l.key(FocusKey.UP)); assertEquals(0, l.index)
        repeat(3) { assertTrue(l.key(FocusKey.DOWN)) }
        assertEquals(3, l.index); assertEquals("four rows are in view", 0, l.first)
        l.key(FocusKey.DOWN); assertEquals(4, l.index); assertEquals(1, l.first)
        repeat(5) { l.key(FocusKey.DOWN) }
        assertEquals("the last row is the foot", 6, l.index); assertEquals(3, l.first)
        l.key(FocusKey.UP); l.key(FocusKey.UP); assertEquals(4, l.index); assertEquals("back inside: it stays", 3, l.first)
        repeat(2) { l.key(FocusKey.UP) }; assertEquals(2, l.index); assertEquals(2, l.first)
        assertEquals("the row an action was on is told when the focus leaves it", 1, left)
    }

    @Test fun leftAndRightChangeAValueAndSelectDoesWhatTheRowIsFor() {
        val l = list(); val before = l.revision
        assertTrue(l.key(FocusKey.RIGHT)); assertEquals(6, number); l.key(FocusKey.LEFT); l.key(FocusKey.LEFT); assertEquals(4, number)
        assertTrue("what is drawn is told to change", l.revision > before)
        assertFalse("a number has nothing to select", l.key(FocusKey.SELECT))
        l.key(FocusKey.DOWN)
        l.key(FocusKey.RIGHT); assertTrue(on); l.key(FocusKey.RIGHT); assertTrue("right is on, however often", on); l.key(FocusKey.LEFT); assertFalse(on)
        l.key(FocusKey.SELECT); assertTrue("Select switches over", on)
        l.key(FocusKey.DOWN)
        assertFalse("an action has no value to change", l.key(FocusKey.LEFT)); assertTrue(l.key(FocusKey.SELECT)); assertEquals(1, done)
        l.key(FocusKey.DOWN)
        assertFalse(l.key(FocusKey.SELECT)); assertFalse(l.key(FocusKey.RIGHT)); assertFalse("Back is not the list's", l.key(FocusKey.BACK))
    }

    @Test fun aPressFindsTheRowItIsOnAndTheArrowsOfItsValue() {
        val l = list()
        for (k in 0 until SettingsList.IN_VIEW) { assertEquals(k, l.rowAt(l.rowCentre(k))); assertEquals(k, l.rowAt(SettingsList.TOP + k * SettingsList.ROW + 0.001)) }
        assertNull("above the first row", l.rowAt(SettingsList.TOP / 2)); assertNull("under the last", l.rowAt(1.0 - SettingsList.TOP / 2)); assertNull(l.rowAt(-0.2)); assertNull(l.rowAt(1.2))
        // the arrow before the value takes it down, the value and the arrow after it up; the name selects
        assertTrue(l.tap(SettingsList.ARROW_DOWN, l.rowCentre(0))); assertEquals(4, number)
        assertTrue(l.tap(SettingsList.ARROW_UP, l.rowCentre(0))); assertTrue(l.tap(SettingsList.VALUE_MID + 0.01, l.rowCentre(0))); assertEquals(6, number)
        assertFalse("a press on a number's name changes nothing", l.tap(0.2, l.rowCentre(0))); assertEquals(6, number)
        assertTrue(l.tap(0.2, l.rowCentre(1))); assertTrue("a press on a switch's name switches it over", on); assertEquals("and the focus is on that row", 1, l.index)
        assertTrue(l.tap(SettingsList.ARROW_DOWN, l.rowCentre(1))); assertFalse(on)
        assertTrue(l.tap(0.9, l.rowCentre(2))); assertEquals("an action is done wherever on its row the press is", 1, done)
        assertFalse(l.tap(0.5, l.rowCentre(3))); assertFalse("between the rows and the edge", l.tap(0.5, 0.001))
        // a pointer over a row brings the focus to it; rows that have slid out of view are not there to be pressed
        assertTrue(l.point(l.rowCentre(0))); assertEquals(0, l.index); assertFalse(l.point(l.rowCentre(0)))
        repeat(6) { l.key(FocusKey.DOWN) }
        assertEquals(3, l.first); assertEquals("the first row in view is now the fourth", 3, l.rowAt(SettingsList.TOP + 0.001))
        assertTrue(l.rowCentre(0) < 0.0)
        // the arrows are inside the part of the row that changes the value, either side of its middle
        assertTrue(SettingsList.ARROW_DOWN > SettingsList.STEP_FROM && SettingsList.ARROW_DOWN < SettingsList.VALUE_MID && SettingsList.ARROW_UP > SettingsList.VALUE_MID && SettingsList.ARROW_UP < 1.0)
    }
}
