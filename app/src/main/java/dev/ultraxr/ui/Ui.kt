package dev.ultraxr.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.ultraxr.R

/**
 * The app's visual language on the phone, built in code (docs/INTERFACE.md). It is the language of the glasses: line
 * and text, six colours, one family of type, and exactly one thing in the focus colour. The phone's screen is an
 * ordinary one, so the ground is a plain near-black; nothing is a filled panel, a card or a gradient wash.
 */
object Ui {
    // ---- the six colours of the design
    /** Text, resting lines. */
    const val CHALK = 0xFFF2F4F0.toInt()
    /** The one thing in focus: the one primary action of a screen, or the thing under the thumb. */
    const val LAMP = 0xFF9B8CFF.toInt()      // the accent: violet, as in the glasses (Glass.VIOLET); it was an amber
    /** Pinned to the room; things that are set and steady. */
    const val TIDE = 0xFF6FD6D0.toInt()
    /** Close, delete, something wrong. */
    const val EMBER = 0xFFFF6B5E.toInt()
    /** Labels of things not in focus, hints. */
    const val DUSK = 0xFF8A949E.toInt()
    /** Idle lines far from focus. */
    const val SLATE = 0xFF5C6670.toInt()
    /** The phone's ground. Not ink: it is what the six colours are drawn on (values/styles.xml has the same value). */
    const val GROUND = 0xFF07090D.toInt()

    // ---- the names the older screens (the check, the display test, the built-in game) still use, as the six colours
    const val BG = GROUND
    /** Once a faint glass fill: now no fill at all. [rounded] draws these two as a thin Slate outline. */
    const val SURFACE = 0x01000000
    const val SURFACE_HI = 0x02000000
    const val ACCENT = TIDE
    const val TEXT = CHALK
    const val MUTED = DUSK
    const val GOOD = TIDE
    /** The check's "works, but look at this": there is no seventh colour, and it must differ from good and from wrong. */
    const val WARN = LAMP
    const val BAD = EMBER

    fun dp(c: Context, v: Number): Int = (v.toFloat() * c.resources.displayMetrics.density + 0.5f).toInt()

    // ---- type: Atkinson Hyperlegible, bundled (res/font). Loaded once; the system's face if it cannot be read.
    private var regular: Typeface? = null
    private var strong: Typeface? = null
    fun face(c: Context, bold: Boolean = false): Typeface {
        if (regular == null) {
            regular = try { c.applicationContext.resources.getFont(R.font.atkinson_regular) } catch (_: Exception) { Typeface.SANS_SERIF }
            strong = try { c.applicationContext.resources.getFont(R.font.atkinson_bold) } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        }
        return (if (bold) strong else regular) ?: Typeface.SANS_SERIF
    }

    /** [rest] at rest, Lamp while pressed or holding the keyboard's focus: the thing being pressed is the thing that glows. */
    fun pressedColors(rest: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(LAMP, LAMP, rest))

    /** An outline for the older screens' panels. A real colour is still filled (their coloured notes); the glass is line only. */
    fun rounded(color: Int, radiusDp: Float, c: Context, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        val glass = color == SURFACE || color == SURFACE_HI
        setColor(if (glass) Color.TRANSPARENT else color); cornerRadius = minOf(radiusDp, 2f) * c.resources.displayMetrics.density
        if (stroke != 0) setStroke(dp(c, 1), stroke)
        else if (glass) setStroke(dp(c, 1), (SLATE and 0x00FFFFFF) or 0x99000000.toInt())
    }

    fun pressable(c: Context, content: android.graphics.drawable.Drawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf((LAMP and 0x00FFFFFF) or 0x33000000), content, null)

    fun text(c: Context, s: CharSequence, sizeSp: Float, color: Int = CHALK, bold: Boolean = false): TextView = TextView(c).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        typeface = face(c, bold)
        includeFontPadding = false; setLineSpacing(0f, 1.15f)
    }

    fun column(c: Context, padDp: Int = 0): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = dp(c, padDp); setPadding(p, p, p, p) }
    fun row(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun LinearLayout.add(v: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, topDp: Int = 0, startDp: Int = 0): View {
        addView(v, LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(context, topDp); marginStart = dp(context, startDp) })
        return v
    }

    /**
     * An action as the glasses draw a window: its words between four corner brackets, which close into a frame under
     * the thumb. [primary] is the one action of its screen and the only one in Lamp at rest.
     */
    fun button(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView = text(c, label, 16f, CHALK, bold = true).apply {
        setTextColor(pressedColors(if (primary) LAMP else CHALK))
        gravity = Gravity.CENTER
        background = Brackets(c, if (primary) LAMP else SLATE)
        minHeight = dp(c, 54); setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** The one large action of a screen ("Open in the glasses"). */
    fun action(c: Context, label: String, onClick: () -> Unit): TextView = button(c, label, primary = true, onClick = onClick).apply {
        textSize = 21f; minHeight = dp(c, 84); background = Brackets(c, LAMP, armDp = 22f, restDp = 2f)
    }

    /** A plain word that can be pressed ("back", "more", "skip for now"): a short line under it, Lamp under the thumb. At least 48 dp each way. */
    fun word(c: Context, label: String, onClick: () -> Unit): TextView = text(c, label, 16f, CHALK).apply {
        setTextColor(pressedColors(CHALK))
        gravity = Gravity.CENTER; background = WordLine(c)
        minHeight = dp(c, 48); minWidth = dp(c, 48); setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 12))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** The name of a group of rows: words in Dusk, nothing else. */
    fun label(c: Context, s: String): TextView = text(c, s, 14f, DUSK)

    /** A line of state in words. [setChip] gives it a coloured dot. (Once a rounded chip.) */
    fun chip(c: Context): TextView = text(c, "", 14f, DUSK)

    fun setChip(chip: TextView, label: String, color: Int) {
        val dot = android.text.SpannableString("●  $label")
        dot.setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, 0)
        chip.text = dot
    }

    fun spacer(c: Context, hDp: Int): View = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c, hDp)) }

    fun edgeToEdge(a: android.app.Activity) {
        a.window.statusBarColor = Color.TRANSPARENT; a.window.navigationBarColor = GROUND
        a.window.decorView.background = ColorDrawable(GROUND)      // a plain dark ground: the screen is not see-through
    }

    /**
     * Pads [v] by Android's bars (the clock above, the navigation keys or the gesture line below) plus the given
     * margins, so that nothing of a screen lies under them whichever way the phone's navigation is set.
     */
    fun fitBars(v: View, sideDp: Int, topDp: Int, bottomDp: Int) {
        val c = v.context
        v.setPadding(dp(c, sideDp), dp(c, topDp), dp(c, sideDp), dp(c, bottomDp))
        v.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            view.setPadding(dp(c, sideDp), top + dp(c, topDp), dp(c, sideDp), bottom + dp(c, bottomDp)); insets
        }
    }
}
