package dev.ultraxr.desktop

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * One app's mark, drawn the same way on the glasses' shelf and on the phone.
 *
 * The glasses add light to the room and cannot darken it: a filled disc of colour is a bright blot that hides what is
 * behind it and glares in a dim room. So the mark is drawn in line, as everything else in the glasses is (see Ink in
 * DesktopRenderer): a ring in the app's own colour, thinned towards white so that it reads at low brightness, and
 * inside it a sign in the interface's white: a small drawing for the things the desktop knows (video, a game pad, the
 * web, a cube, settings, all apps), or else the initials of the name, so that no two marks of a row look alike. A game
 * has a small play mark at the foot of its ring. The one under the pointer is the only thing that glows: its ring
 * thickens, turns to the focus amber and gets a soft halo, so that there is never a doubt which one a press will open.
 * A game its owner has played has a small tick on the ring.
 */
object HomeIcon {
    private const val CHALK = 0xFFF2F4F0.toInt()      // Ink.WHITE
    private const val LAMP = 0xFFFFB454.toInt()       // Ink.AMBER: the one thing in focus

    /** The app's colour as a line colour: its hue kept, washed half way to white and brought up to full brightness. */
    private fun line(color: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
        return Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.5f, 1f))
    }

    /**
     * What stands inside the ring of the mark for [title]: "#video", "#pad", "#globe", "#cube", "#sliders", "#grid",
     * "#home", "#more" for a drawing, or the initials of the name (two letters when it has two words).
     */
    fun sign(title: String, what: String = ""): String = when {
        what == "youtube" -> "#video"; what == "xbox" -> "#pad"
        what == "nav:settings" || title == "Settings" -> "#sliders"; what == "nav:apps" -> "#grid"; what == "nav:home" -> "#home"; what == "nav:next" -> "#more"
        title == "Browser" -> "#globe"; title == "3D web" -> "#cube"
        else -> { val words = title.split(' ', '-', '_').filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
            if (words.size >= 2) (words[0].take(1) + words[1].take(1)).uppercase() else title.trim().take(1).uppercase() }
    }

    /** One of the drawings, in line, in a square of side 2 [t] round ([cx], [cy]). */
    private fun glyph(c: Canvas, p: Paint, cx: Float, cy: Float, t: Float, sign: String) {
        val path = Path()
        when (sign) {
            "#video" -> {      // a screen with a play mark
                c.drawRoundRect(cx - t, cy - t * 0.68f, cx + t, cy + t * 0.68f, t * 0.28f, t * 0.28f, p)
                path.moveTo(cx - t * 0.24f, cy - t * 0.32f); path.lineTo(cx + t * 0.36f, cy); path.lineTo(cx - t * 0.24f, cy + t * 0.32f); path.close(); c.drawPath(path, p)
            }
            "#pad" -> {      // a game pad: its body, a cross on the left, two keys on the right
                c.drawRoundRect(cx - t * 1.05f, cy - t * 0.6f, cx + t * 1.05f, cy + t * 0.6f, t * 0.55f, t * 0.55f, p)
                c.drawLine(cx - t * 0.72f, cy, cx - t * 0.28f, cy, p); c.drawLine(cx - t * 0.5f, cy - t * 0.22f, cx - t * 0.5f, cy + t * 0.22f, p)
                p.style = Paint.Style.FILL; c.drawCircle(cx + t * 0.38f, cy + t * 0.14f, t * 0.09f, p); c.drawCircle(cx + t * 0.68f, cy - t * 0.14f, t * 0.09f, p); p.style = Paint.Style.STROKE
            }
            "#globe" -> {
                c.drawCircle(cx, cy, t * 0.92f, p); c.drawLine(cx - t * 0.92f, cy, cx + t * 0.92f, cy, p)
                c.drawOval(cx - t * 0.4f, cy - t * 0.92f, cx + t * 0.4f, cy + t * 0.92f, p)
            }
            "#cube" -> {
                val a = t * 0.9f; val h = a * 0.5f      // seen from a corner: a hexagon and the three edges that meet in its middle
                path.moveTo(cx, cy - a); path.lineTo(cx + a * 0.87f, cy - h); path.lineTo(cx + a * 0.87f, cy + h); path.lineTo(cx, cy + a); path.lineTo(cx - a * 0.87f, cy + h); path.lineTo(cx - a * 0.87f, cy - h); path.close()
                c.drawPath(path, p); c.drawLine(cx, cy, cx, cy + a, p); c.drawLine(cx, cy, cx - a * 0.87f, cy - h, p); c.drawLine(cx, cy, cx + a * 0.87f, cy - h, p)
            }
            "#sliders" -> for (i in -1..1) {      // three lines, each with its knob somewhere else
                val y = cy + i * t * 0.6f; val k = cx + floatArrayOf(0.38f, -0.42f, 0.1f)[i + 1] * t
                c.drawLine(cx - t * 0.9f, y, cx + t * 0.9f, y, p)
                p.style = Paint.Style.FILL; val was = p.color; p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR); c.drawCircle(k, y, t * 0.26f, p); p.xfermode = null
                p.style = Paint.Style.STROKE; p.color = was; c.drawCircle(k, y, t * 0.2f, p)
            }
            "#grid" -> { p.style = Paint.Style.FILL; for (i in -1..1) for (j in -1..1) c.drawCircle(cx + i * t * 0.62f, cy + j * t * 0.62f, t * 0.13f, p); p.style = Paint.Style.STROKE }
            "#home" -> {
                path.moveTo(cx - t * 0.95f, cy - t * 0.05f); path.lineTo(cx, cy - t * 0.9f); path.lineTo(cx + t * 0.95f, cy - t * 0.05f); c.drawPath(path, p)
                path.reset(); path.moveTo(cx - t * 0.62f, cy - t * 0.3f); path.lineTo(cx - t * 0.62f, cy + t * 0.8f); path.lineTo(cx + t * 0.62f, cy + t * 0.8f); path.lineTo(cx + t * 0.62f, cy - t * 0.3f); c.drawPath(path, p)
            }
            else -> { path.moveTo(cx - t * 0.35f, cy - t * 0.7f); path.lineTo(cx + t * 0.4f, cy); path.lineTo(cx - t * 0.35f, cy + t * 0.7f); c.drawPath(path, p) }      // "#more": onwards
        }
    }

    fun draw(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, color: Int, game: Boolean, letter: String, verified: Boolean, lit: Boolean) {
        val ring = if (lit) LAMP else line(color)
        p.shader = null; p.pathEffect = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        if (lit) {      // the halo: light spilling off the ring, as a lit thing does on an additive display
            p.color = (LAMP and 0x00FFFFFF) or 0x66000000; p.strokeWidth = r * 0.16f
            p.maskFilter = BlurMaskFilter(r * 0.22f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(cx, cy, r * 0.90f, p); p.maskFilter = null
        }
        p.color = ring; p.strokeWidth = r * (if (lit) 0.085f else 0.05f)
        c.drawCircle(cx, cy, r * 0.90f, p)
        // a second, fainter ring inside: depth without a fill
        p.color = (ring and 0x00FFFFFF) or 0x40000000; p.strokeWidth = r * 0.02f
        c.drawCircle(cx, cy, r * 0.74f, p)
        val mark = if (lit) LAMP else CHALK
        if (letter.startsWith("#")) { p.color = mark; p.strokeWidth = r * 0.065f; glyph(c, p, cx, cy, r * 0.4f, letter) }
        else {
            val size = r * (if (letter.length > 1) 0.62f else 0.82f)
            p.style = Paint.Style.FILL; p.color = mark; p.typeface = Type.bold; p.textSize = size; p.textAlign = Paint.Align.CENTER
            c.drawText(letter, cx, cy + size * 0.355f - (if (game) r * 0.06f else 0f), p); p.style = Paint.Style.STROKE
        }
        if (game) {      // a game: a small play mark at the foot of the ring, in a gap of it
            val by = cy + r * 0.90f; val t = r * 0.13f; val path = Path()
            p.style = Paint.Style.FILL; p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR); c.drawCircle(cx, by, t * 2.1f, p); p.xfermode = null
            path.moveTo(cx - t * 0.75f, by - t); path.lineTo(cx + t * 1.05f, by); path.lineTo(cx - t * 0.75f, by + t); path.close()
            p.color = mark; c.drawPath(path, p); p.style = Paint.Style.STROKE
        }
        if (verified) {      // played on the glasses before: a tick where the ring would be at two o'clock
            val bx = cx + r * 0.64f; val by = cy - r * 0.64f; val br = r * 0.17f
            p.style = Paint.Style.FILL; p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR); c.drawCircle(bx, by, br * 1.35f, p); p.xfermode = null      // a gap in the ring: nothing, not black
            p.style = Paint.Style.STROKE; p.color = CHALK; p.strokeWidth = br * 0.36f
            c.drawLine(bx - br * 0.55f, by + br * 0.05f, bx - br * 0.12f, by + br * 0.48f, p); c.drawLine(bx - br * 0.12f, by + br * 0.48f, bx + br * 0.62f, by - br * 0.46f, p)
        }
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT; p.strokeJoin = Paint.Join.MITER
    }

    /** The icon as a view, for the phone's screen. [dim] draws it at reduced strength (a game that does not run yet). */
    class IconView(c: Context, private val color: Int, private val game: Boolean, private val letter: String, private val verified: Boolean, private val dim: Boolean = false) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { Type.load(c); setLayerType(LAYER_TYPE_SOFTWARE, null) }      // the halo's blur is not drawn by the hardware path
        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f - 2f
            if (dim) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 110)
            draw(canvas, paint, width / 2f, height / 2f, r, color, game, letter, verified, false)
            if (dim) canvas.restore()
        }
    }
}
