package dev.ultraxr.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import dev.ultraxr.R

/**
 * The one family everything in the glasses is set in: Atkinson Hyperlegible, regular and bold, bundled in the app
 * (docs/INTERFACE.md, "Type"). Loaded once; until then, and if it cannot be loaded, the system's sans serif stands in.
 */
object Type {
    @Volatile var regular: Typeface = Typeface.SANS_SERIF; private set
    @Volatile var bold: Typeface = Typeface.DEFAULT_BOLD; private set
    @Volatile private var loaded = false
    private val xRatio = HashMap<Typeface, Float>()

    fun load(c: Context) {
        if (loaded) return
        try { regular = c.resources.getFont(R.font.atkinson_regular); bold = c.resources.getFont(R.font.atkinson_bold) }
        catch (_: Throwable) {
            // the copies under assets/fonts are the same files
            try { regular = Typeface.createFromAsset(c.assets, "fonts/AtkinsonHyperlegible-Regular.ttf"); bold = Typeface.createFromAsset(c.assets, "fonts/AtkinsonHyperlegible-Bold.ttf") } catch (_: Throwable) {}
        }
        loaded = true
    }

    /** The text size at which a lower-case x of [face] is [xHeightPx] pixels high: sizes are given as x-heights, because that is what is read. */
    fun sizeFor(face: Typeface, xHeightPx: Float): Float {
        val ratio = synchronized(xRatio) { xRatio.getOrPut(face) { val p = Paint(); p.typeface = face; p.textSize = 200f; val r = Rect(); p.getTextBounds("x", 0, 1, r); (r.height() / 200f).takeIf { it > 0.2f } ?: 0.5f } }
        return xHeightPx / ratio
    }

    /**
     * A piece of text as a picture: light on nothing, on a strip [Sight.STRIP] x-heights high with the x-height in its
     * middle. Drawn [Sight.STRIP] x-heights high in the world, its letters have the x-height asked for. A text wider than
     * [maxWidthPx] is shortened with an ellipsis. [before] may draw a sign in front of the text, in a square of the
     * strip's height, and is given the canvas, the paint and the square's middle.
     */
    fun label(text: String, xHeightPx: Float, color: Int, face: Typeface, maxWidthPx: Float = Float.MAX_VALUE, before: ((Canvas, Paint, Float, Float) -> Unit)? = null): Bitmap {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG); p.typeface = face; p.textSize = sizeFor(face, xHeightPx); p.color = color
        val h = Math.round(xHeightPx * Sight.STRIP.toFloat()); val pad = xHeightPx * 0.5f; val lead = if (before != null) xHeightPx * 2.2f else 0f
        val shown = if (maxWidthPx < Float.MAX_VALUE) TextUtils.ellipsize(text, p, (maxWidthPx - 2 * pad - lead).coerceAtLeast(xHeightPx * 4), TextUtils.TruncateAt.END).toString() else text
        val w = Math.ceil((p.measureText(shown) + 2 * pad + lead).toDouble()).toInt().coerceAtLeast(8)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        before?.invoke(c, p, pad + lead * 0.4f, h / 2f)
        p.style = Paint.Style.FILL; p.color = color
        c.drawText(shown, pad + lead, h / 2f + xHeightPx / 2f, p)
        return b
    }
}
