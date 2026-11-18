package dev.ultraxr.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * One round app icon, drawn the same way in the glasses' home view and on the phone: the app's colour lit from the
 * upper left, a rim that is bright at the top, a white mark (a play triangle for a game, otherwise the name's first
 * letter), and a small tick for a game its owner has played on the glasses.
 */
object HomeIcon {
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun draw(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, color: Int, game: Boolean, letter: String, verified: Boolean, lit: Boolean) {
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
        val light = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.55f, minOf(1f, hsv[2] * 1.25f + 0.1f)))
        val deep = Color.HSVToColor(floatArrayOf(hsv[0], minOf(1f, hsv[1] * 1.05f), hsv[2] * 0.72f))
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.45f, r * 1.7f, intArrayOf(light, color, deep), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = r * (if (lit) 0.042f else 0.03f)
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, if (lit) 0xFFFFFFFF.toInt() else 0xB4FFFFFF.toInt(), 0x1EFFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r - p.strokeWidth / 2, p); p.shader = null; p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        if (game) {
            val t = r * 0.42f; val path = Path()
            path.moveTo(cx - t * 0.62f, cy - t); path.lineTo(cx + t * 0.98f, cy); path.lineTo(cx - t * 0.62f, cy + t); path.close()
            p.pathEffect = CornerPathEffect(r * 0.12f); c.drawPath(path, p); p.pathEffect = null
        } else {
            p.typeface = medium; p.textSize = r * 0.95f; p.textAlign = Paint.Align.CENTER
            c.drawText(letter, cx, cy + r * 0.33f, p)
        }
        if (verified) {
            val bx = cx + r * 0.68f; val by = cy - r * 0.68f; val br = r * 0.2f
            p.color = 0xFFFFFFFF.toInt(); c.drawCircle(bx, by, br, p)
            p.color = deep; p.style = Paint.Style.STROKE; p.strokeWidth = br * 0.27f; p.strokeCap = Paint.Cap.ROUND
            c.drawLine(bx - br * 0.41f, by + br * 0.06f, bx - br * 0.12f, by + br * 0.35f, p); c.drawLine(bx - br * 0.12f, by + br * 0.35f, bx + br * 0.47f, by - br * 0.35f, p)
            p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        }
    }

    /** The icon as a view, for the phone's screen. [dim] draws it at reduced strength (a game that does not run yet). */
    class IconView(c: Context, private val color: Int, private val game: Boolean, private val letter: String, private val verified: Boolean, private val dim: Boolean = false) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f - 2f
            if (dim) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 110)
            draw(canvas, paint, width / 2f, height / 2f, r, color, game, letter, verified, false)
            if (dim) canvas.restore()
        }
    }
}
