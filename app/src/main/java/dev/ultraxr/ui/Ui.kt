package dev.ultraxr.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** The app's visual language, built in code: dark surfaces, one accent, large touch targets. */
object Ui {
    const val BG = 0xFF07090D.toInt()
    const val SURFACE = 0xFF121720.toInt()
    const val SURFACE_HI = 0xFF1A2130.toInt()
    const val ACCENT = 0xFF4FD8FF.toInt()
    const val TEXT = 0xFFF2F6FA.toInt()
    const val MUTED = 0xFF8D99AB.toInt()
    const val GOOD = 0xFF4BE39A.toInt()
    const val WARN = 0xFFFFB84D.toInt()
    const val BAD = 0xFFFF6B6B.toInt()

    fun dp(c: Context, v: Number): Int = (v.toFloat() * c.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Float, c: Context, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * c.resources.displayMetrics.density
        if (stroke != 0) setStroke(dp(c, 1), stroke)
    }

    fun gradient(from: Int, to: Int, radiusDp: Float, c: Context): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply { cornerRadius = radiusDp * c.resources.displayMetrics.density }

    fun pressable(c: Context, content: android.graphics.drawable.Drawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

    fun text(c: Context, s: CharSequence, sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView = TextView(c).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    fun column(c: Context, padDp: Int = 0): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; val p = dp(c, padDp); setPadding(p, p, p, p) }
    fun row(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun LinearLayout.add(v: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, topDp: Int = 0, startDp: Int = 0): View {
        addView(v, LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(context, topDp); marginStart = dp(context, startDp) })
        return v
    }

    /** Primary or secondary button. */
    fun button(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView = text(c, label, 16f, if (primary) 0xFF041018.toInt() else TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        val bg = if (primary) gradient(0xFF7BE6FF.toInt(), 0xFF3FB6FF.toInt(), 18f, c) else rounded(SURFACE_HI, 18f, c)
        background = pressable(c, bg)
        minHeight = dp(c, 54); setPadding(dp(c, 20), 0, dp(c, 20), 0)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Small rounded status chip with a coloured dot. */
    fun chip(c: Context): TextView = text(c, "", 13f, TEXT, bold = true).apply {
        background = rounded(SURFACE_HI, 20f, c); setPadding(dp(c, 12), dp(c, 7), dp(c, 14), dp(c, 7))
    }

    fun setChip(chip: TextView, label: String, color: Int) {
        val dot = android.text.SpannableString("●  $label")
        dot.setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, 0)
        chip.text = dot
    }

    fun spacer(c: Context, hDp: Int): View = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c, hDp)) }

    fun edgeToEdge(a: android.app.Activity) {
        a.window.statusBarColor = Color.TRANSPARENT; a.window.navigationBarColor = BG
        a.window.decorView.setBackgroundColor(BG)
    }
}
