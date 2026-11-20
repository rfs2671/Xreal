package dev.ultraxr.desktop

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.abs

/**
 * The colours of the glass look (docs/INTERFACE.md, "The look: glass"). The glasses add light and cannot darken the
 * room, so a pane of "glass" is a faint, even veil of cool light with a brighter rim; what is chosen is violet.
 */
object Glass {
    const val PANE = 0x508C96C8          // a pane: the veil
    const val PANE_RIM = 0x55FFFFFF      // its rim
    const val CARD = 0x44AAB4E6          // a card on a pane
    const val CARD_RIM = 0x38FFFFFF
    const val TEXT = 0xFFF4F5FA.toInt()
    const val SOFT = 0xFFB9BED0.toInt()  // second lines
    const val FAINT = 0xFF8088A0.toInt()
    const val VIOLET = 0xFF9B8CFF.toInt()      // the accent: what is chosen, what is in focus
    const val VIOLET_DEEP = 0xFF6B57F5.toInt()
    const val CYAN = 0xFF4FE3FF.toInt()        // the accent's other end: violet runs into it
    const val GREEN = 0xFF4ADE80.toInt()       // running, connected, ready
    const val EMBER = 0xFFFF6B5E.toInt()
}

/** One thing on the home panel that the focus can be on: where it is (pixels of the panel's picture) and what a press does. */
class HomeTile(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float, val kind: Kind, val action: String,
    val label: String = "", val sub: String = "", val item: HomeItem? = null, val sign: String = "", val color: Int = Glass.VIOLET, val running: Int = 0, val chosen: Boolean = false) {
    enum class Kind { SIDE, HERO, ART, ROUND, ROOM, PLAIN }
    val cx: Float get() = x + w / 2; val cy: Float get() = y + h / 2
}

/** Everything the home panel shows. */
class HomeModel(
    val section: String, val web: List<HomeItem>, val games: List<HomeItem>, val phoneApps: List<HomeItem>, val appsPage: Int, val appsPages: Int,
    /** The windows: id, title, whether it is put away. */
    val windows: List<Triple<Int, String, Boolean>>,
    /** By a mark's name: 2 when it has an open window, 1 when its window is put away. */
    val running: Map<String, Int>,
    val last: HomeItem?, val room: List<DoubleArray>, val roomPlaced: Boolean,
    val glasses: Boolean, val tracking: String, val battery: Int, val time: String, val date: String,
    val icon: (HomeItem) -> Bitmap?,
) {
    /** What the picture depends on, as its name. */
    fun key() = "panel|$section|$appsPage|" + windows.joinToString(",") { "${it.first}:${it.second}:${it.third}" } + "|" + running.entries.joinToString(",") { it.key + it.value } +
        "|${last?.title}|${room.size}|$roomPlaced|$glasses|$tracking|$battery|$time|${phoneApps.size}|${games.size}"
}

/**
 * The home: what is in view when no window is open ("Aurora", chosen by the owner on 20 November 2026 from three
 * directions, docs/design/direction2.png). A wall of covers that curves round the wearer: the one in the middle is
 * large and near, its neighbours stand back and turn inwards. Above it a bar with the name, the sections and what is
 * connected; under it a row of actions. The covers are separate things in the room, each at its own depth
 * ([coverX], [coverTurn], [coverDepth]); the bar and the row are one flat picture ([draw]).
 *
 * The tiles ([layout]) are what the focus moves over ([neighbour]) and what a ray or a look finds ([hit]): first the
 * five sections, then the covers, then the row of actions. Their places are in the pixels of the flat picture, which
 * is as large as an eye's view.
 */
object Home {
    /** The flat picture: about as many pixels to a degree as the glasses have, and nearly all an eye sees. */
    const val W = 1760; const val H = 980
    const val DISTANCE = 2.0
    val WIDTH = 2 * DISTANCE * 0.385
    val HEIGHT = WIDTH * H / W
    const val PITCH = 0.0
    const val SIDE = 5      // the sections' tiles come first
    /** A page of the phone's apps. */
    const val APPS_A_PAGE = 19
    /** Something said to the wearer stands here on the home, between the covers and the row of actions. */
    val NOTICE_PITCH = Math.toRadians(-7.7)

    // ---- the wall of covers. A cover's slot is how many places it is from the middle (fractions while the wall turns).
    const val COVER_W = 300f; const val COVER_H = 420f; const val COVER_Y = 462f
    /** The cover in the middle is this much larger. */
    const val COVER_FOCUS = 1.27f
    private const val STEP1 = 380.0; private const val STEP = 336.0
    /** With this many covers or more the wall goes round: after the last comes the first, and the middle one always has neighbours on both sides. */
    const val ROUND_FROM = 5
    /** How many places cover [i] is from the place [pos] of a wall of [n] covers. */
    fun slot(i: Int, pos: Double, n: Int): Double {
        var d = i - pos
        if (n >= ROUND_FROM) { d %= n; if (d > n / 2.0) d -= n; if (d < -n / 2.0) d += n }
        return d
    }
    /** Covers in view on each side of the middle one. */
    const val EACH_SIDE = 2
    /** The picture a cover is drawn in. */
    const val COVER_PX_W = 380; const val COVER_PX_H = 532

    /** Where a cover's middle is across the picture, pixels. */
    fun coverX(slot: Double): Double { val a = abs(slot); return W / 2.0 + Math.signum(slot) * (minOf(a, 1.0) * STEP1 + maxOf(a - 1.0, 0.0) * STEP) }
    fun coverScale(slot: Double): Double = 1.0 + (COVER_FOCUS - 1.0) * maxOf(0.0, 1.0 - abs(slot))
    /** How far a cover is turned inwards, radians: the wall is tighter than a circle round the wearer, so that it is seen to curve. */
    fun coverTurn(slot: Double): Double { val a = minOf(abs(slot), 2.6); return -Math.signum(slot) * (minOf(a, 1.0) * 0.50 + maxOf(a - 1.0, 0.0) * 0.10) }
    /** How much nearer than the flat picture a cover stands, metres (negative: further). */
    fun coverDepth(slot: Double): Double { val a = abs(slot); return 0.14 * maxOf(0.0, 1.0 - a) - 0.22 * maxOf(0.0, a - 1.0) }
    fun coverAlpha(slot: Double): Double { val a = abs(slot); return if (a <= 1) 1.0 - 0.05 * a else if (a <= 2) 0.95 - 0.27 * (a - 1) else maxOf(0.0, 0.68 * (1 - (a - 2) / 0.6)) }

    private fun isCover(t: HomeTile) = t.kind == HomeTile.Kind.ART || t.kind == HomeTile.Kind.PLAIN || t.kind == HomeTile.Kind.HERO
    /** How many covers the section has (in view or not). They follow the five sections in [layout]'s list. */
    fun coverCount(tiles: List<HomeTile>): Int = tiles.count { isCover(it) }

    /** The tiles of the section shown, with the cover numbered [centre] in the middle of the wall. A cover out of view has no size. */
    fun layout(m: HomeModel, centre: Int = 0): List<HomeTile> {
        val t = ArrayList<HomeTile>()
        val tabs = listOf(Triple("home", "Home", 118f), Triple("games", "Games", 132f), Triple("apps", "Apps", 112f), Triple("windows", "Windows", 164f), Triple("settings", "Settings", 152f))
        var x = 548f
        for (s in tabs) {
            t += HomeTile("side:" + s.first, x, 40f, s.third, 56f, 28f, HomeTile.Kind.SIDE, if (s.first == "settings") "settings" else "sec:" + s.first, s.second,
                chosen = m.section == s.first, running = if (s.first == "windows" && m.windows.isNotEmpty()) 2 else 0)
            x += s.third + 8f
        }
        val covers: List<HomeTile> = when (m.section) {
            "games" -> m.games.map { art(it, m) }
            "apps" -> (if (m.appsPage > 0) listOf(plain("apps:prev", "Earlier", "#back")) else emptyList()) + m.phoneApps.map { art(it, m) } +
                (if (m.appsPage < m.appsPages - 1) listOf(plain("apps:next", "More", "#more")) else emptyList())
            "windows" -> m.windows.map { w -> HomeTile("win:" + w.first, 0f, 0f, 0f, 0f, 30f, HomeTile.Kind.ART, "win:" + w.first, w.second, if (w.third) "Put away" else "Open", sign = HomeIcon.sign(w.second), color = 0xFF5B8DEF.toInt(), running = if (w.third) 1 else 2) }
            else -> (m.web + m.games).map { art(it, m) }
        }
        val mid = centre.coerceIn(0, maxOf(0, covers.size - 1))
        for ((i, c) in covers.withIndex()) {
            val slot = slot(i, mid.toDouble(), covers.size)
            if (abs(slot) > EACH_SIDE) { t += at(c, -9000f, COVER_Y, 0f, 0f); continue }
            val k = coverScale(slot).toFloat(); val w = COVER_W * k; val h = COVER_H * k
            t += at(c, coverX(slot).toFloat() - w / 2, COVER_Y - h / 2, w, h)
        }
        t += HomeTile("room", 60f, 858f, 340f, 64f, 32f, HomeTile.Kind.ROOM, "room", if (m.room.isEmpty()) "No room marked" else if (m.roomPlaced) "Room ready" else "Room marked", roomSize(m.room))
        val cuts = listOf(Triple("sys:recentre", "Recentre", "#target"), Triple("sys:gather", "Gather", "#gather"), Triple("sys:closeall", "Close all", "#x"))
        for ((i, c) in cuts.withIndex()) t += HomeTile(c.first, W / 2f - 36f + (i - 1) * 124f, 850f, 72f, 72f, 36f, HomeTile.Kind.ROUND, c.first, c.second, sign = c.third)
        return t
    }

    private fun signOf(i: HomeItem) = HomeIcon.sign(i.title, if (i.kind == HomeItem.Kind.WEB) i.webKind else "")
    private fun art(i: HomeItem, m: HomeModel) = HomeTile("item:" + i.title, 0f, 0f, 0f, 0f, 30f, HomeTile.Kind.ART, "item", i.title,
        when (i.kind) { HomeItem.Kind.GAME -> "Game"; HomeItem.Kind.APP -> "On this phone"; else -> when (i.webKind) { "youtube" -> "Video"; "xbox" -> "Cloud gaming"; else -> "Web" } },
        i, signOf(i), i.color, m.running[i.title] ?: 0, chosen = m.last?.title == i.title)
    private fun plain(action: String, label: String, sign: String) = HomeTile(action, 0f, 0f, 0f, 0f, 30f, HomeTile.Kind.PLAIN, action, label, sign = sign)
    private fun at(c: HomeTile, x: Float, y: Float, w: Float, h: Float) = HomeTile(c.id, x, y, w, h, c.radius, c.kind, c.action, c.label, c.sub, c.item, c.sign, c.color, c.running, c.chosen)

    private fun roomSize(c: List<DoubleArray>): String {
        if (c.size < 3) return "Mark it from the phone"
        val w = c.maxOf { it[0] } - c.minOf { it[0] }; val d = c.maxOf { it[1] } - c.minOf { it[1] }
        return String.format(java.util.Locale.US, "%.1f × %.1f m", maxOf(w, d), minOf(w, d))
    }

    /** The tile the focus begins on: the cover in the middle, or the section's own tile when it has no covers. */
    fun first(tiles: List<HomeTile>, centre: Int = 0): Int {
        val n = coverCount(tiles)
        return if (n > 0 && tiles.size > SIDE) SIDE + centre.coerceIn(0, n - 1) else tiles.indexOfFirst { it.kind == HomeTile.Kind.SIDE && it.chosen }.coerceAtLeast(0)
    }

    /**
     * The tile a direction leads to from tile [from], or [from] itself when there is none that way. Three rows: the
     * sections, the covers, the actions. Left and right walk along a row; up and down go between them, to the section
     * shown, to the cover in the middle ([centre]) and to the first action.
     */
    fun neighbour(tiles: List<HomeTile>, from: Int, key: FocusKey, centre: Int = 0): Int {
        if (tiles.isEmpty()) return 0
        if (from !in tiles.indices) return first(tiles, centre)
        val n = coverCount(tiles); val foot = SIDE + n
        val chosen = tiles.indexOfFirst { it.kind == HomeTile.Kind.SIDE && it.chosen }.coerceAtLeast(0)
        val middle = if (n > 0) SIDE + centre.coerceIn(0, n - 1) else -1
        return when {
            from < SIDE -> when (key) {
                FocusKey.LEFT -> maxOf(0, from - 1); FocusKey.RIGHT -> minOf(SIDE - 1, from + 1)
                FocusKey.DOWN -> if (middle >= 0) middle else minOf(tiles.size - 1, foot + 1)
                else -> from
            }
            from < foot -> when (key) {
                FocusKey.LEFT -> if (n >= ROUND_FROM) SIDE + (from - SIDE - 1 + n) % n else maxOf(SIDE, from - 1)
                FocusKey.RIGHT -> if (n >= ROUND_FROM) SIDE + (from - SIDE + 1) % n else minOf(foot - 1, from + 1)
                FocusKey.UP -> chosen; FocusKey.DOWN -> minOf(tiles.size - 1, foot + 1)
                else -> from
            }
            else -> when (key) {
                FocusKey.LEFT -> maxOf(foot, from - 1); FocusKey.RIGHT -> minOf(tiles.size - 1, from + 1)
                FocusKey.UP -> if (middle >= 0) middle else chosen
                else -> from
            }
        }
    }

    /** The tile at a place of the picture, or null. */
    fun at(tiles: List<HomeTile>, x: Double, y: Double): Int? = tiles.indexOfFirst { it.w > 0f && x >= it.x - 4 && x <= it.x + it.w + 4 && y >= it.y - 4 && y <= it.y + it.h + 4 }.takeIf { it >= 0 }

    /** The tile a ray is on, or null: the home is taken as a plane [DISTANCE] metres in front of the heading [yaw]. */
    fun hit(tiles: List<HomeTile>, origin: DoubleArray, dir: DoubleArray, anchor: DoubleArray, yaw: Double): Int? {
        val h = Geometry.planeHit(origin, dir, Geometry.centre(anchor, yaw, PITCH, DISTANCE), yaw, PITCH) ?: return null
        return at(tiles, (h[0] / WIDTH + 0.5) * W, (0.5 - h[1] / HEIGHT) * H)
    }

    // ---- the picture
    private fun rr(c: Canvas, p: Paint, x: Float, y: Float, w: Float, h: Float, r: Float, fill: Int, rim: Int = 0) {
        p.shader = null; p.maskFilter = null
        if (fill != 0) { p.style = Paint.Style.FILL; p.color = fill; c.drawRoundRect(x, y, x + w, y + h, r, r, p) }
        if (rim != 0) { p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = rim; c.drawRoundRect(x + 0.75f, y + 0.75f, x + w - 0.75f, y + h - 0.75f, r, r, p) }
        p.style = Paint.Style.FILL
    }

    /** A pane of glass: the veil, light caught along its upper edge, and the rim. */
    private fun pane(c: Canvas, p: Paint, x: Float, y: Float, w: Float, h: Float, r: Float) {
        rr(c, p, x, y, w, h, r, Glass.PANE)
        p.shader = LinearGradient(0f, y, 0f, y + h * 0.5f, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawRoundRect(x, y, x + w, y + h, r, r, p); p.shader = null
        rr(c, p, x, y, w, h, r, 0, Glass.PANE_RIM)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, max: Float = Float.MAX_VALUE, align: Paint.Align = Paint.Align.LEFT) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG); p.typeface = if (bold) Type.bold else Type.regular; p.textSize = size; p.color = color; p.textAlign = align
        c.drawText(if (max < Float.MAX_VALUE) TextUtils.ellipsize(s, p, max, TextUtils.TruncateAt.END).toString() else s, x, y, p)
    }

    private fun sign(c: Canvas, p: Paint, cx: Float, cy: Float, t: Float, sign: String, color: Int) {
        p.shader = null; p.maskFilter = null
        if (sign.startsWith("#")) { p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.strokeWidth = t * 0.17f; p.color = color; HomeIcon.glyph(c, p, cx, cy, t, sign); p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT }
        else text(c, sign, cx, cy + t * 0.62f, t * 1.75f, color, true, align = Paint.Align.CENTER)
    }

    /** The picture a thing without one of its own gets: its colour as a soft light from one corner, and its sign. */
    private fun artwork(c: Canvas, p: Paint, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, icon: Bitmap?, signText: String, big: Boolean, scene: Boolean = false, seed: Int = 0) {
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
        val deep = Color.HSVToColor(0xE6, floatArrayOf(hsv[0], minOf(1f, hsv[1] * 0.9f + 0.1f), 0.30f)); val lit = Color.HSVToColor(0xF0, floatArrayOf(hsv[0], hsv[1] * 0.75f, 0.85f))
        val save = c.save(); val clip = Path(); clip.addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW); c.clipPath(clip)
        p.style = Paint.Style.FILL; p.shader = LinearGradient(x, y, x + w, y + h, lit, deep, Shader.TileMode.CLAMP); c.drawRect(x, y, x + w, y + h, p)
        p.shader = RadialGradient(x + w * 0.78f, y + h * 0.18f, maxOf(w, h) * 0.75f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawRect(x, y, x + w, y + h, p)
        p.shader = LinearGradient(0f, y + h * 0.45f, 0f, y + h, 0x00000000, 0xB0000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(x, y, x + w, y + h, p); p.shader = null
        val t = (if (big) minOf(w, h) * 0.26f else minOf(w, h) * 0.2f)
        val cx = if (big) x + w * 0.72f else x + w / 2; val cy = if (big) y + h * 0.5f else y + h * 0.36f
        if (scene) {      // a game: a low sun over a floor of lines running to the horizon, so that its card is a place and not a label
            val hy = y + h * (if (big) 0.62f else 0.56f); val sx = if (big) x + w * 0.72f else x + w * (0.34f + (seed % 4) * 0.1f); val sr = minOf(w, h) * (if (big) 0.3f else 0.27f)
            val hue2 = (hsv[0] + 35f) % 360f
            p.shader = LinearGradient(0f, y, 0f, hy, Color.HSVToColor(0xF0, floatArrayOf(hsv[0], 0.85f, 0.22f)), Color.HSVToColor(0xF0, floatArrayOf(hue2, 0.7f, 0.8f)), Shader.TileMode.CLAMP); c.drawRect(x, y, x + w, hy, p)
            p.shader = LinearGradient(0f, hy, 0f, y + h, Color.HSVToColor(0xF0, floatArrayOf(hsv[0], 0.8f, 0.34f)), Color.HSVToColor(0xF0, floatArrayOf(hsv[0], 0.9f, 0.08f)), Shader.TileMode.CLAMP); c.drawRect(x, hy, x + w, y + h, p)
            p.shader = RadialGradient(sx, hy, sr * 2.4f, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP); val sky = c.save(); c.clipRect(x, y, x + w, hy); c.drawRect(x, y, x + w, hy, p)
            p.shader = LinearGradient(0f, hy - sr, 0f, hy, 0xFFFFFFFF.toInt(), Color.HSVToColor(0xFF, floatArrayOf(hue2, 0.45f, 1f)), Shader.TileMode.CLAMP); c.drawCircle(sx, hy, sr, p); c.restoreToCount(sky); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = if (big) 1.6f else 1.2f; p.color = Color.HSVToColor(0xB0, floatArrayOf(hue2, 0.35f, 1f))
            for (i in 0..5) { val gy = hy + (y + h - hy) * (i * i / 25f); c.drawLine(x, gy, x + w, gy, p) }
            for (i in -8..8) c.drawLine(sx + i * w * 0.045f, hy, sx + i * w * 0.4f, y + h, p)
            p.style = Paint.Style.FILL
            if (!big) { p.shader = LinearGradient(0f, y + h * 0.6f, 0f, y + h, 0x00000000, 0xA0000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(x, y, x + w, y + h, p); p.shader = null }
        }
        p.alpha = 255
        if (icon != null) { val s = t * 2.6f; p.isFilterBitmap = true; c.drawBitmap(icon, null, RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), p) }
        else if (scene && !big) { /* the scene is the picture; the name says which */ }
        else if (scene && big) { /* the scene fills the card */ }
        else {
            if (big) { p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x50FFFFFF; c.drawCircle(cx, cy, t * 2.1f, p); p.color = 0x28FFFFFF; c.drawCircle(cx, cy, t * 2.9f, p); p.style = Paint.Style.FILL }
            sign(c, p, cx, cy, t, signText, 0xF2FFFFFF.toInt())
        }
        c.restoreToCount(save)
        rr(c, p, x, y, w, h, r, 0, 0x40FFFFFF)
    }

    private fun dot(c: Canvas, p: Paint, x: Float, y: Float, running: Int) {
        if (running == 0) return
        p.shader = null; p.color = Glass.GREEN
        if (running == 2) { p.style = Paint.Style.FILL; c.drawCircle(x, y, 5f, p) } else { p.style = Paint.Style.STROKE; p.strokeWidth = 2f; c.drawCircle(x, y, 4.5f, p); p.style = Paint.Style.FILL }
    }

    private fun gradientText(c: Canvas, str: String, x: Float, y: Float, size: Float, from: Int, to: Int): Float {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG); p.typeface = Type.bold; p.textSize = size; val w = p.measureText(str)
        p.shader = LinearGradient(x, 0f, x + w, 0f, from, to, Shader.TileMode.CLAMP); c.drawText(str, x, y, p); return w
    }

    /** A line of light that fades at both ends: violet into cyan, with its glow. */
    private fun lightLine(c: Canvas, p: Paint, x0: Float, x1: Float, y: Float, thick: Float, glow: Float) {
        val colors = intArrayOf(0x008F7BFF, Glass.VIOLET, Glass.CYAN, 0x004FE3FF); val stops = floatArrayOf(0f, 0.3f, 0.7f, 1f)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.shader = LinearGradient(x0, 0f, x1, 0f, colors, stops, Shader.TileMode.CLAMP)
        if (glow > 0f) { p.strokeWidth = thick * 3f; p.alpha = 150; p.maskFilter = BlurMaskFilter(glow, BlurMaskFilter.Blur.NORMAL); c.drawLine(x0, y, x1, y, p); p.maskFilter = null; p.alpha = 255 }
        p.strokeWidth = thick; c.drawLine(x0, y, x1, y, p)
        p.shader = null; p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
    }

    /** A pill of tinted glass: a veil of violet light, brighter along its upper edge, with a rim. */
    private fun glassPill(c: Canvas, p: Paint, x: Float, y: Float, w: Float, h: Float) {
        val r = h / 2
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, y, 0f, y + h, 0x5A968CFF, 0x30465AAA, Shader.TileMode.CLAMP); c.drawRoundRect(x, y, x + w, y + h, r, r, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.shader = LinearGradient(0f, y, 0f, y + h, 0xAAFFFFFF.toInt(), 0x50A0AAFF, Shader.TileMode.CLAMP); c.drawRoundRect(x + 0.8f, y + 0.8f, x + w - 0.8f, y + h - 0.8f, r, r, p)
        p.shader = null; p.style = Paint.Style.FILL
    }

    /** The flat parts of the home: the bar above the covers and the row under them. The covers are not in this picture ([cover]). */
    fun draw(m: HomeModel, tiles: List<HomeTile>): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // ---- the bar: the name, the sections, what is connected and the time
        text(c, "Ultra", 60f, 80f, 38f, Glass.TEXT, true); val uw = TextPaint().apply { typeface = Type.bold; textSize = 38f }.measureText("Ultra ")
        gradientText(c, "XR", 60f + uw, 80f, 38f, Glass.VIOLET, Glass.CYAN)
        for (t in tiles) if (t.kind == HomeTile.Kind.SIDE) {
            text(c, t.label, t.cx, t.cy + 9f, 25f, if (t.chosen) Color.WHITE else Glass.SOFT, t.chosen, align = Paint.Align.CENTER)
            if (t.chosen) lightLine(c, p, t.x + 22f, t.x + t.w - 22f, t.y + t.h + 4f, 4f, 7f)
            if (t.running > 0 && !t.chosen) dot(c, p, t.x + t.w - 10f, t.y + 18f, 2)
        }
        text(c, m.time, W - 60f, 78f, 30f, Glass.TEXT, true, align = Paint.Align.RIGHT)
        val tw = TextPaint().apply { typeface = Type.bold; textSize = 30f }.measureText(m.time)
        val word = m.tracking.replaceFirstChar { it.uppercase() }; val ok = m.tracking == "room found" || m.tracking == "position tracked" || m.tracking == "room recognised" || m.tracking == "learning the room"
        var sx = W - 60f - tw - 26f
        if (m.battery in 0..100) { val bt = "${m.battery} %"; text(c, bt, sx, 76f, 19f, Glass.SOFT, align = Paint.Align.RIGHT); sx -= TextPaint().apply { typeface = Type.regular; textSize = 19f }.measureText(bt) + 24f }
        text(c, if (m.glasses) word else "Glasses not connected", sx, 76f, 19f, Glass.SOFT, align = Paint.Align.RIGHT)
        sx -= TextPaint().apply { typeface = Type.regular; textSize = 19f }.measureText(if (m.glasses) word else "Glasses not connected") + 14f
        p.color = if (m.glasses && ok) Glass.GREEN else Glass.EMBER; p.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.SOLID); c.drawCircle(sx, 70f, 5f, p); p.maskFilter = null

        // ---- the floor the covers stand on: a line of light
        lightLine(c, p, 330f, W - 330f, 760f, 3f, 16f)
        if (coverCount(tiles) == 0) text(c, when (m.section) { "windows" -> "No window is open. Open something from Home."; "apps" -> "The helper that shows phone apps in windows is not running."; else -> "Nothing here yet." }, W / 2f, COVER_Y, 24f, Glass.SOFT, align = Paint.Align.CENTER)
        if (m.section == "apps" && m.appsPages > 1) text(c, "Page ${m.appsPage + 1} of ${m.appsPages}", W / 2f, 800f, 17f, Glass.FAINT, align = Paint.Align.CENTER)

        // ---- the row of actions
        val rounds = tiles.filter { it.kind == HomeTile.Kind.ROUND }
        if (rounds.isNotEmpty()) glassPill(c, p, rounds.first().x - 30f, 838f, rounds.last().x + 72f + 30f - (rounds.first().x - 30f), 96f)
        for (t in tiles) when (t.kind) {
            HomeTile.Kind.ROUND -> { sign(c, p, t.cx, t.cy, 15f, t.sign, Glass.TEXT); text(c, t.label, t.cx, 958f, 15f, Glass.SOFT, align = Paint.Align.CENTER) }
            HomeTile.Kind.ROOM -> {
                glassPill(c, p, t.x, t.y, t.w, t.h)
                p.color = if (m.roomPlaced) Glass.GREEN else if (m.room.isEmpty()) Glass.EMBER else Glass.VIOLET; p.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.SOLID); c.drawCircle(t.x + 30f, t.cy, 5.5f, p); p.maskFilter = null
                text(c, t.label, t.x + 50f, t.cy + 7f, 20f, Glass.TEXT, true)
                text(c, t.sub, t.x + t.w - 24f, t.cy + 7f, 18f, Glass.SOFT, align = Paint.Align.RIGHT)
            }
            else -> {}
        }
        text(c, "Pinch or Select to open", W - 60f, 898f, 18f, Glass.FAINT, align = Paint.Align.RIGHT)
        return b
    }

    /**
     * One cover of the wall, as large as it is shown in the middle. In focus it has its rim of light, what a press
     * does as a button, and whether it has a window.
     */
    fun cover(t: HomeTile, m: HomeModel, focused: Boolean): Bitmap {
        val w = COVER_PX_W.toFloat(); val h = COVER_PX_H.toFloat(); val r = 38f
        val b = Bitmap.createBitmap(COVER_PX_W, COVER_PX_H, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (t.kind == HomeTile.Kind.PLAIN) {
            p.shader = LinearGradient(0f, 0f, w, h, 0x70968CFF, 0x30465AAA, Shader.TileMode.CLAMP); c.drawRoundRect(0f, 0f, w, h, r, r, p); p.shader = null
            sign(c, p, w / 2, h * 0.42f, 34f, t.sign, Glass.TEXT); text(c, t.label, w / 2, h - 60f, 32f, Glass.TEXT, true, align = Paint.Align.CENTER)
        } else {
            artwork(c, p, 0f, 0f, w, h, r, t.color, t.item?.let(m.icon), t.sign, false, t.item?.kind == HomeItem.Kind.GAME, abs(t.label.hashCode()))
            val base = if (focused) h - 132f else h - 62f
            text(c, t.label, 28f, base, if (focused) 38f else 32f, Color.WHITE, true, w - 56f)
            val second = if (focused && t.chosen) "Continue where you stopped" else t.sub
            if (second.isNotEmpty()) text(c, second, 28f, base + 30f, 20f, 0xFFE0E4F2.toInt(), max = w - 56f)
            if (focused) {
                p.color = Color.WHITE; c.drawRoundRect(28f, h - 86f, 28f + 176f, h - 28f, 29f, 29f, p)
                val tri = Path(); tri.moveTo(60f, h - 69f); tri.lineTo(78f, h - 57f); tri.lineTo(60f, h - 45f); tri.close(); p.color = 0xFF151528.toInt(); c.drawPath(tri, p)
                text(c, if (t.running == 2 || t.action.startsWith("win:")) "Show" else if (t.running == 1 || t.chosen) "Resume" else "Open", 92f, h - 49f, 23f, 0xFF151528.toInt(), true)
            }
            if (t.running > 0) {
                val word = if (t.running == 2) "Window open" else "Put away"; val tp = TextPaint().apply { typeface = Type.regular; textSize = 17f }; val bw = tp.measureText(word) + 46f
                p.color = 0x70000000; c.drawRoundRect(w - bw - 20f, 20f, w - 20f, 54f, 17f, 17f, p); dot(c, p, w - bw - 2f, 37f, t.running); text(c, word, w - bw + 10f, 43f, 17f, Color.WHITE)
            }
        }
        // the rim: a thin edge of light at rest; in focus white, with violet round it
        p.style = Paint.Style.STROKE
        if (focused) { p.color = Glass.VIOLET; p.strokeWidth = 9f; c.drawRoundRect(4.5f, 4.5f, w - 4.5f, h - 4.5f, r - 3f, r - 3f, p); p.color = Color.WHITE; p.strokeWidth = 3f; c.drawRoundRect(9f, 9f, w - 9f, h - 9f, r - 8f, r - 8f, p) }
        else { p.color = 0x66FFFFFF; p.strokeWidth = 1.8f; c.drawRoundRect(1f, 1f, w - 1f, h - 1f, r, r, p) }
        p.style = Paint.Style.FILL
        return b
    }

    /** The light behind the cover in focus: violet in its middle, cyan towards its edge, gone at the edge. */
    fun glow(): Bitmap {
        val n = 256; val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(0x967A6CFF.toInt(), 0x5A6E78FF, 0x2A5A96FF, 0x103CD2FF, 0x003CD2FF), floatArrayOf(0f, 0.3f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
        return b
    }

    /** The marked room seen from above, in line with a glow; or, with none marked, a dashed square where it would be. */
    private fun plan(c: Canvas, p: Paint, room: List<DoubleArray>, x: Float, y: Float, w: Float, h: Float) {
        val path = Path()
        if (room.size >= 3) {
            val x0 = room.minOf { it[0] }; val x1 = room.maxOf { it[0] }; val z0 = room.minOf { it[1] }; val z1 = room.maxOf { it[1] }
            val k = minOf(w / (x1 - x0).coerceAtLeast(0.1).toFloat(), h / (z1 - z0).coerceAtLeast(0.1).toFloat())
            val ox = x + (w - k * (x1 - x0).toFloat()) / 2; val oy = y + (h - k * (z1 - z0).toFloat()) / 2
            for ((i, q) in room.withIndex()) { val px = ox + k * (q[0] - x0).toFloat(); val py = oy + k * (q[1] - z0).toFloat(); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            path.close()
        } else path.addRoundRect(RectF(x + w * 0.2f, y + h * 0.1f, x + w * 0.8f, y + h * 0.9f), 6f, 6f, Path.Direction.CW)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND
        if (room.size >= 3) { p.color = 0xAA9B8CFF.toInt(); p.strokeWidth = 7f; p.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL); c.drawPath(path, p); p.maskFilter = null; p.color = 0xFFD9D3FF.toInt(); p.strokeWidth = 2.2f; c.drawPath(path, p) }
        else { p.color = 0x90FFFFFF.toInt(); p.strokeWidth = 2f; p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 9f), 0f); c.drawPath(path, p); p.pathEffect = null }
        p.style = Paint.Style.FILL; p.strokeJoin = Paint.Join.MITER
    }

    /** The light round the tile in focus: a bright rim and its glow, in a picture a little larger than the tile ([RING_PAD] pixels each way). */
    fun ring(w: Float, h: Float, r: Float): Bitmap {
        val pad = RING_PAD; val b = Bitmap.createBitmap(Math.ceil((w + 2 * pad).toDouble()).toInt(), Math.ceil((h + 2 * pad).toDouble()).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.STROKE
        p.color = 0xCC9B8CFF.toInt(); p.strokeWidth = 10f; p.maskFilter = BlurMaskFilter(11f, BlurMaskFilter.Blur.NORMAL); c.drawRoundRect(pad, pad, pad + w, pad + h, r, r, p); p.maskFilter = null
        p.color = 0xFFEDE9FF.toInt(); p.strokeWidth = 3f; c.drawRoundRect(pad - 2f, pad - 2f, pad + w + 2f, pad + h + 2f, r + 2f, r + 2f, p)
        return b
    }
    const val RING_PAD = 26f
}
