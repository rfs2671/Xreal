package dev.ultraxr.desktop

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.text.TextPaint

/**
 * One row of the settings (docs/INTERFACE.md, "Settings"): a [name], its [value], and one [line] of plain words saying
 * what it does. [step] changes the value down (-1) or up (1): left and right. [select] is what Select does (switch an
 * on-off row over, do what an action row is for). [warn] is true while the line is a warning. [leave] is told when the
 * focus goes to another row.
 */
class SettingRow(val name: String, val value: () -> String, val line: () -> String, val step: ((Int) -> Unit)? = null, val select: (() -> Unit)? = null,
    val warn: () -> Boolean = { false }, val leave: (() -> Unit)? = null)

/**
 * The settings as a list with one row in focus: which rows are in view, what a key does, and what a pointer at a place
 * of the page is on. Free of Android, so that it is unit-tested; [SettingsPage] draws it by the same numbers.
 */
class SettingsList(val rows: List<SettingRow>) {
    var index = 0; private set
    /** The first row in view: the list slides when the focus passes its end, as the shelf does. */
    var first = 0; private set
    /** Bumped whenever what is drawn may have changed. */
    var revision = 0L; private set

    private fun go(i: Int): Boolean {
        val to = i.coerceIn(0, maxOf(0, rows.size - 1))
        if (to == index) return false
        rows.getOrNull(index)?.leave?.invoke()
        index = to
        first = when { index < first -> index; index > first + IN_VIEW - 1 -> index - IN_VIEW + 1; else -> first }.coerceIn(0, maxOf(0, rows.size - IN_VIEW))
        revision++; return true
    }

    /** A key while the page is in use: up and down go from row to row, left and right change the value, Select does what the row is for. Returns whether anything changed. */
    fun key(key: FocusKey): Boolean {
        val r = rows.getOrNull(index) ?: return false
        return when (key) {
            FocusKey.UP -> go(index - 1)
            FocusKey.DOWN -> go(index + 1)
            FocusKey.LEFT -> if (r.step != null) { r.step.invoke(-1); revision++; true } else false
            FocusKey.RIGHT -> if (r.step != null) { r.step.invoke(1); revision++; true } else false
            FocusKey.SELECT -> if (r.select != null) { r.select.invoke(); revision++; true } else false
            else -> false
        }
    }

    /** The row at [v] (0 at the top of the page, 1 at its foot), or null between the rows and the page's edges. */
    fun rowAt(v: Double): Int? {
        val k = Math.floor((v - TOP) / ROW).toInt()
        return if (v < TOP || k !in 0 until IN_VIEW || first + k >= rows.size) null else first + k
    }

    /** The middle of row [i], down the page; outside 0..1 when it is not in view. */
    fun rowCentre(i: Int): Double = TOP + (i - first + 0.5) * ROW

    /** A pointer came over ([u], [v]): the row there is the one in focus. */
    fun point(v: Double): Boolean = rowAt(v)?.let { go(it) } ?: false

    /**
     * A press at ([u], [v]): on the arrow before a value it goes down, on the value or the arrow after it up; anywhere
     * else on a row it is Select on that row.
     */
    fun tap(u: Double, v: Double): Boolean {
        val i = rowAt(v) ?: return false
        go(i); val r = rows[i]
        if (r.step != null && u >= STEP_FROM) { r.step.invoke(if (u < VALUE_MID) -1 else 1); revision++; return true }
        if (r.select != null) { r.select.invoke(); revision++; return true }
        return false
    }

    /** Something outside changed what a row says. */
    fun touch() { revision++ }

    companion object {
        /** Rows in view. Four, so that at a new window's size both lines of a row have the x-height body text must have (0.55 degrees at least). */
        const val IN_VIEW = 4
        const val TOP = 0.03
        const val ROW = (1.0 - 2 * TOP) / IN_VIEW
        /** A value stands in the right part of its row: from [STEP_FROM] across the page, its middle at [VALUE_MID], an arrow at [ARROW_DOWN] and one at [ARROW_UP]. */
        const val STEP_FROM = 0.60
        const val VALUE_MID = 0.795
        const val ARROW_DOWN = 0.64
        const val ARROW_UP = 0.95
    }
}

/**
 * Draws the settings as the page of their window: one column of rows, light on nothing. A row is its name, its value
 * in Chalk, and under them one line in Dusk. While the page is in use the row in focus is the one thing that glows:
 * its name is Lamp, with a short Lamp line before it, and a value that left and right change has an arrow either side.
 */
object SettingsPage {
    fun draw(c: Canvas, list: SettingsList, inUse: Boolean) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        c.drawColor(Color.BLACK, PorterDuff.Mode.SRC)      // black is see-through on the glasses: there is no panel behind the rows
        val rh = h * SettingsList.ROW.toFloat(); val xh = rh * 0.19f; val left = w * 0.04f
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        fun fit(text: String, max: Float) { val m = p.measureText(text); if (m > max) p.textSize *= max / m }
        for (k in 0 until SettingsList.IN_VIEW) {
            val i = list.first + k; val row = list.rows.getOrNull(i) ?: break
            val top = h * SettingsList.TOP.toFloat() + k * rh; val on = inUse && i == list.index
            val nameY = top + rh * 0.42f; val lineY = top + rh * 0.82f
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.LEFT
            p.typeface = if (on) Type.bold else Type.regular; p.textSize = Type.sizeFor(p.typeface, xh); p.color = if (on) Ink.LAMP_INT else Ink.CHALK_INT
            fit(row.name, w * SettingsList.STEP_FROM.toFloat() - left - xh); c.drawText(row.name, left, nameY, p)
            val value = row.value()
            if (value.isNotEmpty()) {
                p.typeface = Type.bold; p.textSize = Type.sizeFor(Type.bold, xh); p.color = Ink.CHALK_INT; p.textAlign = Paint.Align.CENTER
                fit(value, w * (SettingsList.ARROW_UP - SettingsList.ARROW_DOWN).toFloat() - xh * 2.4f); c.drawText(value, w * SettingsList.VALUE_MID.toFloat(), nameY, p)
            }
            p.typeface = Type.regular; p.textSize = Type.sizeFor(Type.regular, xh); p.color = if (row.warn()) Ink.EMBER_INT else Ink.DUSK_INT; p.textAlign = Paint.Align.LEFT
            val line = row.line(); fit(line, w - 2 * left); c.drawText(line, left, lineY, p)
            if (on) {
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                p.color = Ink.LAMP_INT; p.strokeWidth = xh * 0.16f
                c.drawLine(w * 0.018f, nameY - xh * 1.3f, w * 0.018f, lineY + xh * 0.3f, p)
                if (row.step != null) {      // an arrow either side of the value: left and right change it, and a press on one does the same
                    p.color = Ink.DUSK_INT; p.strokeWidth = xh * 0.14f
                    val y = nameY - xh / 2; val a = xh * 0.55f
                    val down = w * SettingsList.ARROW_DOWN.toFloat(); val up = w * SettingsList.ARROW_UP.toFloat()
                    c.drawLine(down + a * 0.5f, y - a, down - a * 0.5f, y, p); c.drawLine(down - a * 0.5f, y, down + a * 0.5f, y + a, p)
                    c.drawLine(up - a * 0.5f, y - a, up + a * 0.5f, y, p); c.drawLine(up + a * 0.5f, y, up - a * 0.5f, y + a, p)
                }
                p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
            }
        }
        if (list.rows.size > SettingsList.IN_VIEW) {      // a hairline at the edge says where in the list the rows in view are
            val x = w * 0.985f; val y0 = h * SettingsList.TOP.toFloat() + rh * 0.1f; val y1 = h * (1 - SettingsList.TOP.toFloat()) - rh * 0.1f; val n = list.rows.size.toFloat()
            p.style = Paint.Style.STROKE; p.strokeWidth = xh * 0.08f; p.color = Ink.SLATE_INT; c.drawLine(x, y0, x, y1, p)
            p.strokeWidth = xh * 0.14f; p.color = Ink.DUSK_INT; c.drawLine(x, y0 + (y1 - y0) * list.first / n, x, y0 + (y1 - y0) * (list.first + SettingsList.IN_VIEW) / n, p)
            p.style = Paint.Style.FILL
        }
    }
}
