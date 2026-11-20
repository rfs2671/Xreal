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
 * inside it a play mark for a game or the first letter of the name, in the interface's white. The one under the
 * pointer is the only thing that glows: its ring thickens, turns to the focus amber and gets a soft halo, so that
 * there is never a doubt which one a press will open. A game its owner has played has a small tick on the ring.
 */
object HomeIcon {
    private const val CHALK = 0xFFF2F4F0.toInt()      // Ink.WHITE
    private const val LAMP = 0xFFFFB454.toInt()       // Ink.AMBER: the one thing in focus

    /** The app's colour as a line colour: its hue kept, washed half way to white and brought up to full brightness. */
    private fun line(color: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
        return Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.5f, 1f))
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
        if (game) {
            val t = r * 0.34f; val path = Path()
            path.moveTo(cx - t * 0.62f, cy - t); path.lineTo(cx + t * 0.98f, cy); path.lineTo(cx - t * 0.62f, cy + t); path.close()
            p.color = mark; p.strokeWidth = r * 0.07f; c.drawPath(path, p)
        } else {
            p.style = Paint.Style.FILL; p.color = mark; p.typeface = Type.bold; p.textSize = r * 0.82f; p.textAlign = Paint.Align.CENTER
            c.drawText(letter, cx, cy + r * 0.29f, p); p.style = Paint.Style.STROKE
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
