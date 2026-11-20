package dev.ultraxr.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import dev.ultraxr.desktop.FocusKey
import dev.ultraxr.desktop.HomeIcon
import dev.ultraxr.ui.Ui.add
import kotlin.math.abs
import kotlin.math.hypot

/*
 * The pieces the phone's screens are made of (docs/INTERFACE.md, "On the phone"). All of them are line on the dark
 * ground, and each shows the thumb on it in the one focus colour.
 */

/** A drawable that knows whether its view is under the thumb (or holds the keyboard's focus). */
abstract class LineDrawable(c: Context) : Drawable() {
    protected val density = c.resources.displayMetrics.density
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    protected var lit = false; private set
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed) || state.contains(android.R.attr.state_focused)
        if (now == lit) return false
        lit = now; invalidateSelf(); return true
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Four corner brackets, as round a window in the glasses: [rest] at rest. Under the thumb they turn to Lamp and, with
 * [closeOnPress], close into a whole frame, so a press is seen even at arm's length.
 */
class Brackets(c: Context, private val rest: Int, private val armDp: Float = 12f, private val restDp: Float = 1.5f, private val closeOnPress: Boolean = true) : LineDrawable(c) {
    override fun draw(canvas: Canvas) {
        val w = (if (lit) restDp + 1f else restDp) * density
        val b = bounds; val l = b.left + w; val t = b.top + w; val r = b.right - w; val bt = b.bottom - w
        paint.color = if (lit) Ui.LAMP else rest; paint.strokeWidth = w
        if (lit && closeOnPress) { canvas.drawRect(l, t, r, bt, paint); return }
        val a = minOf(armDp * density, (r - l) / 3f, (bt - t) / 3f)
        canvas.drawLine(l, t, l + a, t, paint); canvas.drawLine(l, t, l, t + a, paint)
        canvas.drawLine(r, t, r - a, t, paint); canvas.drawLine(r, t, r, t + a, paint)
        canvas.drawLine(l, bt, l + a, bt, paint); canvas.drawLine(l, bt, l, bt - a, paint)
        canvas.drawLine(r, bt, r - a, bt, paint); canvas.drawLine(r, bt, r, bt - a, paint)
    }
}

/** Under a word that can be pressed: a short Slate line, longer and in Lamp under the thumb. */
class WordLine(c: Context, private val belowCentreDp: Float = -1f) : LineDrawable(c) {
    override fun draw(canvas: Canvas) {
        val half = (if (lit) 16f else 9f) * density; val cx = bounds.exactCenterX()
        // at the foot of the view, or, for a word in the middle of a tall one, that far under its middle
        val y = if (belowCentreDp >= 0f) bounds.exactCenterY() + belowCentreDp * density else bounds.bottom - 5f * density
        paint.color = if (lit) Ui.LAMP else Ui.SLATE; paint.strokeWidth = (if (lit) 2.5f else 1.5f) * density
        canvas.drawLine(cx - half, y, cx + half, y, paint)
    }
}

/** Under a line to type on: a rule across, Lamp while it is being typed into. */
class FieldLine(c: Context) : LineDrawable(c) {
    override fun draw(canvas: Canvas) {
        val y = bounds.bottom - 2f * density
        paint.color = if (lit) Ui.LAMP else Ui.SLATE; paint.strokeWidth = (if (lit) 2f else 1f) * density
        canvas.drawLine(bounds.left.toFloat(), y, bounds.right.toFloat(), y, paint)
    }
}

/** A row of a list: a hairline under it, and a Lamp bar at its start while it is pressed. */
class RowGround(c: Context) : LineDrawable(c) {
    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = (Ui.SLATE and 0x00FFFFFF) or 0x66000000; paint.strokeWidth = 1f
        canvas.drawLine(b.left.toFloat(), b.bottom - 0.5f, b.right.toFloat(), b.bottom - 0.5f, paint)
        if (lit) {
            paint.color = Ui.LAMP; paint.strokeWidth = 3f * density
            canvas.drawLine(b.left + 2f * density, b.top + 10f * density, b.left + 2f * density, b.bottom - 10f * density, paint)
        }
    }
}

/** An app's mark as the glasses draw it (desktop/HomeIcon); under the thumb it is the one that glows. */
class MarkView(c: Context, private val color: Int, private val game: Boolean, private val letter: String, private val verified: Boolean = false, private val dim: Boolean = false) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }      // the halo's blur is not drawn by the hardware path
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f - 2f
        if (dim) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 110)
        HomeIcon.draw(canvas, paint, width / 2f, height / 2f, r * (if (isPressed) 1f else 0.9f), color, game, letter, verified, isPressed)
        if (dim) canvas.restore()
    }
}

/** How a step of the set-up stands: a Tide tick when done, a Lamp ring for the one being done, a Slate ring for one to come. */
class TickView(c: Context) : View(c) {
    companion object { const val TO_COME = 0; const val NOW = 1; const val DONE = 2 }
    var state = TO_COME; set(v) { if (field != v) { field = v; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        when (state) {
            DONE -> {
                paint.color = Ui.TIDE; paint.strokeWidth = s * 0.10f
                val p = Path(); p.moveTo(cx - s * 0.30f, cy + s * 0.02f); p.lineTo(cx - s * 0.08f, cy + s * 0.24f); p.lineTo(cx + s * 0.32f, cy - s * 0.24f)
                canvas.drawPath(p, paint)
            }
            NOW -> { paint.color = Ui.LAMP; paint.strokeWidth = s * 0.09f; canvas.drawCircle(cx, cy, s * 0.26f, paint) }
            else -> { paint.color = Ui.SLATE; paint.strokeWidth = s * 0.06f; canvas.drawCircle(cx, cy, s * 0.22f, paint) }
        }
    }
}

/** Less or more of a value: a minus or a plus drawn in line, 48 dp each way. Held down, it keeps going. */
class StepKey(c: Context, private val plus: Boolean, private val onStep: () -> Unit) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val again = object : Runnable { override fun run() { onStep(); postDelayed(this, 70) } }
    init {
        isClickable = true; isFocusable = true
        setOnClickListener { onStep() }      // a keyboard, or an accessibility service pressing it
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { isPressed = true; onStep(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); postDelayed(again, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isPressed = false; removeCallbacks(again) }
            }
            true
        }
    }
    override fun onDetachedFromWindow() { removeCallbacks(again); super.onDetachedFromWindow() }
    override fun onMeasure(w: Int, h: Int) { val s = Ui.dp(context, 48); setMeasuredDimension(s, s) }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density; val cx = width / 2f; val cy = height / 2f; val a = 8f * d
        val on = isPressed || isFocused
        paint.color = if (on) Ui.LAMP else Ui.CHALK; paint.strokeWidth = (if (on) 3f else 2f) * d
        canvas.drawLine(cx - a, cy, cx + a, cy, paint)
        if (plus) canvas.drawLine(cx, cy - a, cx, cy + a, paint)
    }
}

/**
 * One row of a list, the same on every screen: a name, its value in Chalk, and one line of plain words in Dusk saying
 * what it does (docs/INTERFACE.md, "Settings"). A row that can be pressed shows a Lamp bar under the thumb. Nothing
 * here has a fixed height, so the words wrap with Android's larger type instead of being cut.
 */
class ListRow(c: Context) : LinearLayout(c) {
    val name: TextView = Ui.text(c, "", 17f, Ui.CHALK, bold = true)
    val value: TextView = Ui.text(c, "", 16f, Ui.CHALK).apply { gravity = Gravity.END }
    val line: TextView = Ui.text(c, "", 14f, Ui.DUSK).apply { visibility = GONE }
    private val top = Ui.row(c)
    private val words = Ui.column(c)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = RowGround(c)
        minimumHeight = Ui.dp(c, 60); setPadding(Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 14))
        top.add(name, 0, weight = 1f)
        top.add(value, ViewGroup.LayoutParams.WRAP_CONTENT, startDp = 12)
        words.add(top); words.add(line, topDp = 5)
        add(words, 0, weight = 1f)
    }

    fun set(n: CharSequence, v: CharSequence = value.text, l: CharSequence = line.text): ListRow {
        name.text = n; value.text = v; line.text = l; line.visibility = if (l.isEmpty()) GONE else VISIBLE
        return this
    }

    /** The value of something that is on or off: "on" in Tide (set and steady), "off" in Dusk. */
    fun onOff(on: Boolean): ListRow { value.text = if (on) "on" else "off"; value.setTextColor(if (on) Ui.TIDE else Ui.DUSK); return this }

    fun onTap(run: () -> Unit): ListRow {
        isClickable = true; isFocusable = true
        name.setTextColor(Ui.pressedColors(name.currentTextColor))
        setOnClickListener { run() }
        return this
    }

    /** Something in front of the words: an app's mark or its icon. */
    fun lead(v: View, sizeDp: Int): ListRow {
        addView(v, 0, LayoutParams(Ui.dp(context, sizeDp), Ui.dp(context, sizeDp)).apply { marginEnd = Ui.dp(context, 14) })
        return this
    }

    /**
     * A value changed in steps: less on its left, more on its right, the value between them. [onChange] gets every
     * new value. The whole row is not pressed: only the two keys are.
     */
    fun stepper(min: Int, max: Int, initial: Int, label: (Int) -> String, by: Int = 1, onChange: (Int) -> Unit): ListRow {
        var v = initial.coerceIn(min, max)
        value.text = label(v); value.gravity = Gravity.CENTER; value.minWidth = Ui.dp(context, 64)
        fun step(d: Int) { val n = (v + d).coerceIn(min, max); if (n != v) { v = n; value.text = label(v); onChange(v) } }
        val less = StepKey(context, false) { step(-by) }; val more = StepKey(context, true) { step(by) }
        top.removeView(value)
        top.addView(less); top.addView(value, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)); top.addView(more)
        less.contentDescription = "Less"; more.contentDescription = "More"
        return this
    }
}

/**
 * The phone's d-pad: four directions and Select in its middle, one view, drawn in line (docs/INTERFACE.md, "On the
 * phone"). It is made to be used without looking: the whole square answers, the middle two fifths are Select and
 * everything round them is the direction the thumb is in, and each press ticks under the thumb. A direction held
 * down repeats, as a held key of a gamepad does, so a window being moved keeps going.
 */
class DpadView(c: Context) : View(c) {
    /** A key went down (true) or came up (false). A held direction sends down again and again, then one up. */
    var onKey: ((FocusKey, Boolean) -> Unit)? = null
    private var held: FocusKey? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val arc = RectF(); private val chevron = Path()
    private val again = object : Runnable { override fun run() { held?.let { onKey?.invoke(it, true); postDelayed(this, 110) } } }
    private val keys = listOf(FocusKey.UP to "Up", FocusKey.DOWN to "Down", FocusKey.LEFT to "Left", FocusKey.RIGHT to "Right", FocusKey.SELECT to "Select")

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)      // the halo's blur is not drawn by the hardware path
        contentDescription = "Direction pad: up, down, left and right move the focus, the middle selects"
        isClickable = true; isHapticFeedbackEnabled = true
    }

    override fun onMeasure(w: Int, h: Int) {
        // a square as large as the room it is given, never under 144 dp (48 dp for each of three keys across)
        val min = Ui.dp(context, 144)
        val ws = MeasureSpec.getSize(w); val hs = MeasureSpec.getSize(h)
        val wide = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else ws
        val high = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else hs
        var s = minOf(wide, high); if (s == Int.MAX_VALUE) s = Ui.dp(context, 220)
        s = maxOf(s, min)
        setMeasuredDimension(if (MeasureSpec.getMode(w) == MeasureSpec.EXACTLY) ws else s, if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) hs else s)
    }

    private fun radius() = minOf(width, height) / 2f - 4f * resources.displayMetrics.density

    private fun keyAt(x: Float, y: Float): FocusKey {
        val dx = x - width / 2f; val dy = y - height / 2f
        if (hypot(dx, dy) <= radius() * 0.40f) return FocusKey.SELECT
        return if (abs(dx) > abs(dy)) (if (dx < 0) FocusKey.LEFT else FocusKey.RIGHT) else (if (dy < 0) FocusKey.UP else FocusKey.DOWN)
    }

    private fun release() { val k = held ?: return; removeCallbacks(again); held = null; onKey?.invoke(k, false); invalidate() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                release()
                val k = keyAt(e.x, e.y); held = k
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKey?.invoke(k, true)
                if (k != FocusKey.SELECT) postDelayed(again, 420)      // Select acts once, as on a gamepad
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }

    // ---- for someone who cannot see the pad at all: each key is an action of this one view
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        keys.forEachIndexed { i, k -> info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_BASE + i, k.second)) }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val k = keys.getOrNull(action - ACTION_BASE) ?: return super.performAccessibilityAction(action, arguments)
        onKey?.invoke(k.first, true); onKey?.invoke(k.first, false); return true
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density; val cx = width / 2f; val cy = height / 2f; val r = radius()
        val on = held
        // the rim, and four short strokes where one direction ends and the next begins
        paint.maskFilter = null; paint.color = Ui.SLATE; paint.strokeWidth = 1.5f * d
        canvas.drawCircle(cx, cy, r, paint)
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val a = 0.7071f * r
            canvas.drawLine(cx + sx * a * 0.62f, cy + sy * a * 0.62f, cx + sx * a * 0.94f, cy + sy * a * 0.94f, paint)
        }
        // the four directions
        for (k in arrayOf(FocusKey.UP, FocusKey.RIGHT, FocusKey.DOWN, FocusKey.LEFT)) {
            val lit = on == k
            val ux = when (k) { FocusKey.LEFT -> -1f; FocusKey.RIGHT -> 1f; else -> 0f }; val uy = when (k) { FocusKey.UP -> -1f; FocusKey.DOWN -> 1f; else -> 0f }
            if (lit) {      // its quarter of the rim lights, with light spilling off it
                arc.set(cx - r, cy - r, cx + r, cy + r)
                val start = when (k) { FocusKey.RIGHT -> -45f; FocusKey.DOWN -> 45f; FocusKey.LEFT -> 135f; else -> 225f }
                paint.color = (Ui.LAMP and 0x00FFFFFF) or 0x66000000; paint.strokeWidth = 7f * d; paint.maskFilter = BlurMaskFilter(6f * d, BlurMaskFilter.Blur.NORMAL)
                canvas.drawArc(arc, start, 90f, false, paint); paint.maskFilter = null
                paint.color = Ui.LAMP; paint.strokeWidth = 3f * d
                canvas.drawArc(arc, start, 90f, false, paint)
            }
            val px = cx + ux * r * 0.70f; val py = cy + uy * r * 0.70f; val s = r * 0.13f
            chevron.rewind()
            // an arrow head pointing along (ux, uy): its two arms run back and to either side
            chevron.moveTo(px - ux * s - uy * s, py - uy * s - ux * s); chevron.lineTo(px, py); chevron.lineTo(px - ux * s + uy * s, py - uy * s + ux * s)
            paint.color = if (lit) Ui.LAMP else Ui.CHALK; paint.strokeWidth = (if (lit) 4f else 2.5f) * d
            canvas.drawPath(chevron, paint)
        }
        // Select, in the middle
        val lit = on == FocusKey.SELECT
        if (lit) {
            paint.color = (Ui.LAMP and 0x00FFFFFF) or 0x66000000; paint.strokeWidth = 7f * d; paint.maskFilter = BlurMaskFilter(6f * d, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(cx, cy, r * 0.36f, paint); paint.maskFilter = null
        }
        paint.color = if (lit) Ui.LAMP else Ui.CHALK; paint.strokeWidth = (if (lit) 4f else 2f) * d
        canvas.drawCircle(cx, cy, r * 0.36f, paint)
        paint.color = ((if (lit) Ui.LAMP else Ui.CHALK) and 0x00FFFFFF) or 0x40000000; paint.strokeWidth = 1f * d
        canvas.drawCircle(cx, cy, r * 0.27f, paint)
    }

    private companion object { const val ACTION_BASE = 0x7F0D0001 }
}
