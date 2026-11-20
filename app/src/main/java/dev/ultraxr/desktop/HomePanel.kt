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
import kotlin.math.tan

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
 * The home panel: what is in view when no window is open. One pane of glass in front of the wearer with the things
 * there are to open, drawn as one picture ([draw]) from a list of tiles ([layout]) that is also what the focus moves
 * over ([neighbour]) and what a ray or a look finds ([hit]). Plain numbers, so that the three cannot disagree.
 */
object Home {
    /** The picture: about as many pixels to a degree as the glasses have. */
    const val W = 1680; const val H = 760
    const val DISTANCE = 1.8
    /** 42 of the 46 degrees an eye sees, and a little under the line of sight's middle so that the head of the panel is not at the very top. */
    val WIDTH = 2 * DISTANCE * tan(Math.toRadians(21.0))
    val HEIGHT = WIDTH * H / W
    val PITCH = Math.toRadians(-0.6)
    const val SIDE = 5      // the sidebar's tiles come first, in every section

    private const val TOP = 108f; private const val BOTTOM = 738f
    /** The upper row of the home section is this high, and the lower row begins this far under the top. */
    private const val UPPER = 330f; private const val LOWER = 348f

    fun layout(m: HomeModel): List<HomeTile> {
        val t = ArrayList<HomeTile>()
        val side = listOf(Triple("home", "Home", "#home"), Triple("games", "Games", "#pad"), Triple("apps", "Apps", "#grid"), Triple("windows", "Windows", "#windows"), Triple("settings", "Settings", "#sliders"))
        for ((i, s) in side.withIndex()) t += HomeTile("side:" + s.first, 46f, TOP + 18f + i * 66f, 186f, 56f, 28f, HomeTile.Kind.SIDE, if (s.first == "settings") "settings" else "sec:" + s.first,
            s.second, sign = s.third, chosen = m.section == s.first, running = if (s.first == "windows" && m.windows.isNotEmpty()) 2 else 0)
        when (m.section) {
            "games" -> grid(t, m.games.map { art(it, m) })
            "apps" -> grid(t, m.phoneApps.map { art(it, m) } +
                (if (m.appsPage > 0) listOf(plain("apps:prev", "Earlier", "#back")) else emptyList()) + (if (m.appsPage < m.appsPages - 1) listOf(plain("apps:next", "More", "#more")) else emptyList()))
            "windows" -> grid(t, m.windows.map { w -> HomeTile("win:" + w.first, 0f, 0f, 0f, 0f, 22f, HomeTile.Kind.ART, "win:" + w.first, w.second, if (w.third) "put away" else "open", sign = HomeIcon.sign(w.second), running = if (w.third) 1 else 2) } +
                (if (m.windows.isNotEmpty()) listOf(plain("sys:closeall", "Close all", "#x")) else emptyList()))
            else -> {
                val hero = m.last ?: m.web.firstOrNull()
                if (hero != null) t += HomeTile("hero", 268f, TOP, 600f, UPPER, 30f, HomeTile.Kind.HERO, "item", hero.title, if (m.last != null) "Continue" else "Start here", hero, signOf(hero), hero.color, m.running[hero.title] ?: 0)
                // quick play: three games and the way to the rest
                val q = m.games.take(3)
                for ((i, g) in q.withIndex()) t += HomeTile("quick:" + g.title, 906f + (i % 2) * 150f, TOP + 62f + (i / 2) * 134f, 140f, 124f, 18f, HomeTile.Kind.ART, "item", g.title, "", g, signOf(g), g.color, m.running[g.title] ?: 0)
                if (m.games.isNotEmpty()) t += HomeTile("quick:more", 906f + 150f, TOP + 62f + 134f, 140f, 124f, 18f, HomeTile.Kind.PLAIN, "sec:games", "More", sign = "#grid")
                t += HomeTile("room", 1240f, TOP, 394f, UPPER, 30f, HomeTile.Kind.ROOM, "room", if (m.room.isEmpty()) "No room marked" else if (m.roomPlaced) "Room ready" else "Room marked", roomSize(m.room))
                // the library: the web apps, then the games
                val lib = (m.web + m.games).take(7)
                for ((i, a) in lib.withIndex()) t += HomeTile("lib:" + a.title, 286f + i * 132f, TOP + LOWER + 60f, 122f, 208f, 20f, HomeTile.Kind.ART, "item", a.title, "", a, signOf(a), a.color, m.running[a.title] ?: 0)
                val cuts = listOf(Triple("sys:recentre", "Recentre", "#target"), Triple("sys:gather", "Gather", "#gather"), Triple("sys:closeall", "Close all", "#x"))
                for ((i, c) in cuts.withIndex()) t += HomeTile(c.first, 1268f + i * 122f, TOP + LOWER + 92f, 96f, 96f, 48f, HomeTile.Kind.ROUND, c.first, c.second, sign = c.third)
            }
        }
        return t
    }

    private fun signOf(i: HomeItem) = HomeIcon.sign(i.title, if (i.kind == HomeItem.Kind.WEB) i.webKind else "")
    private fun art(i: HomeItem, m: HomeModel) = HomeTile("item:" + i.title, 0f, 0f, 0f, 0f, 22f, HomeTile.Kind.ART, "item", i.title, "", i, signOf(i), i.color, m.running[i.title] ?: 0)
    private fun plain(action: String, label: String, sign: String) = HomeTile(action, 0f, 0f, 0f, 0f, 22f, HomeTile.Kind.PLAIN, action, label, sign = sign)

    /** A section that is one grid: seven to a row, three rows. */
    private fun grid(t: MutableList<HomeTile>, cells: List<HomeTile>) {
        for ((i, c) in cells.take(GRID).withIndex())
            t += HomeTile(c.id, 290f + (i % 7) * 192f, TOP + 78f + (i / 7) * 186f, 180f, 174f, 22f, c.kind, c.action, c.label, c.sub, c.item, c.sign, c.color, c.running, c.chosen)
    }
    const val GRID = 21
    /** A page of the phone's apps leaves room for "Earlier" and "More". */
    const val APPS_A_PAGE = 19

    private fun roomSize(c: List<DoubleArray>): String {
        if (c.size < 3) return "Mark it from the phone"
        val w = c.maxOf { it[0] } - c.minOf { it[0] }; val d = c.maxOf { it[1] } - c.minOf { it[1] }
        return String.format(java.util.Locale.US, "%.1f × %.1f m", maxOf(w, d), minOf(w, d))
    }

    /** The tile the focus begins on in a section. */
    fun first(tiles: List<HomeTile>): Int = tiles.indexOfFirst { it.id == "hero" }.let { if (it >= 0) it else if (tiles.size > SIDE) SIDE else 0 }

    /**
     * The tile a direction leads to from tile [from], or [from] itself when there is none that way: the nearest of
     * those that lie that way, where being off to the side counts more than being far.
     */
    fun neighbour(tiles: List<HomeTile>, from: Int, key: FocusKey): Int {
        val a = tiles.getOrNull(from) ?: return if (tiles.isEmpty()) 0 else first(tiles)
        var best = from; var bestScore = Float.MAX_VALUE
        for ((i, b) in tiles.withIndex()) {
            if (i == from) continue
            if (from < SIDE && i >= SIDE && (key == FocusKey.UP || key == FocusKey.DOWN)) continue      // the sidebar is walked up and down within itself
            val dx = b.cx - a.cx; val dy = b.cy - a.cy
            val along: Float; val across: Float; val overlap: Boolean
            when (key) {
                FocusKey.LEFT -> { along = a.x - (b.x + b.w); across = abs(dy); overlap = b.y < a.y + a.h && a.y < b.y + b.h; if (dx >= 0) continue }
                FocusKey.RIGHT -> { along = b.x - (a.x + a.w); across = abs(dy); overlap = b.y < a.y + a.h && a.y < b.y + b.h; if (dx <= 0) continue }
                FocusKey.UP -> { along = a.y - (b.y + b.h); across = abs(dx); overlap = b.x < a.x + a.w && a.x < b.x + b.w; if (dy >= 0) continue }
                FocusKey.DOWN -> { along = b.y - (a.y + a.h); across = abs(dx); overlap = b.x < a.x + a.w && a.x < b.x + b.w; if (dy <= 0) continue }
                else -> return from
            }
            if (along < -8f) continue      // it is beside, not that way
            val score = maxOf(along, 0f) + (if (overlap) across * 0.3f else across * 2.2f + 300f)
            if (score < bestScore) { bestScore = score; best = i }
        }
        // from the things of a section, left leads to the sidebar's tile of that section: the way back is the way in
        if (key == FocusKey.LEFT && from >= SIDE && best < SIDE) tiles.indexOfFirst { it.kind == HomeTile.Kind.SIDE && it.chosen }.let { if (it >= 0) return it }
        return best
    }

    /** The tile at a place of the picture, or null. */
    fun at(tiles: List<HomeTile>, x: Double, y: Double): Int? = tiles.indexOfFirst { x >= it.x - 4 && x <= it.x + it.w + 4 && y >= it.y - 4 && y <= it.y + it.h + 4 }.takeIf { it >= 0 }

    /** The tile a ray is on, or null: the panel is a plane [DISTANCE] metres in front of the heading [yaw]. */
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

    fun draw(m: HomeModel, tiles: List<HomeTile>): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // ---- the head of the panel: the name, what is connected, the time
        p.shader = LinearGradient(40f, 26f, 92f, 78f, Glass.VIOLET, 0xFF5B8DEF.toInt(), Shader.TileMode.CLAMP); p.style = Paint.Style.STROKE; p.strokeWidth = 9f; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(46f, 30f, 90f, 74f), 40f, 280f, false, p); p.shader = null; p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        text(c, "Ultra", 106f, 62f, 40f, Glass.TEXT, true); val uw = TextPaint().apply { typeface = Type.bold; textSize = 40f }.measureText("Ultra ")
        text(c, "XR", 106f + uw, 62f, 40f, Glass.VIOLET, true)
        text(c, "Mixed reality home", 108f, 86f, 17f, Glass.SOFT)
        pane(c, p, 560f, 22f, 640f, 62f, 31f)
        fun status(x: Float, signText: String, a: String, bText: String, ok: Boolean?) {
            sign(c, p, x + 20f, 53f, 13f, signText, Glass.TEXT); text(c, a, x + 48f, 48f, 17f, Glass.TEXT)
            if (ok != null) { p.color = if (ok) Glass.GREEN else Glass.EMBER; c.drawCircle(x + 53f, 64f, 4f, p) }
            text(c, bText, x + (if (ok != null) 64f else 48f), 70f, 15f, Glass.SOFT)
        }
        status(582f, "#glasses", "XREAL Air 2 Ultra", if (m.glasses) "Connected" else "Not connected", m.glasses)
        status(800f, "#phone", "Phone", if (m.battery in 0..100) "${m.battery} %" else "", null)
        status(952f, "#target", "Tracking", m.tracking.replaceFirstChar { it.uppercase() }, m.tracking == "room found")
        p.color = 0x30FFFFFF; c.drawRect(786f, 36f, 787f, 70f, p); c.drawRect(938f, 36f, 939f, 70f, p)
        text(c, m.time, 1634f, 56f, 30f, Glass.TEXT, true, align = Paint.Align.RIGHT); text(c, m.date, 1634f, 80f, 16f, Glass.SOFT, align = Paint.Align.RIGHT)

        // ---- the panes
        pane(c, p, 30f, TOP, 218f, BOTTOM - TOP, 30f)
        text(c, "Select opens", 62f, BOTTOM - 46f, 15f, Glass.FAINT); text(c, "Back returns", 62f, BOTTOM - 24f, 15f, Glass.FAINT)
        if (m.section == "home") {
            pane(c, p, 888f, TOP, 334f, UPPER, 30f); text(c, "Quick play", 910f, TOP + 40f, 23f, Glass.TEXT, true)
            pane(c, p, 268f, TOP + LOWER, 954f, BOTTOM - TOP - LOWER, 30f); text(c, "My library", 290f, TOP + LOWER + 38f, 23f, Glass.TEXT, true)
            pane(c, p, 1240f, TOP + LOWER, 394f, BOTTOM - TOP - LOWER, 30f); text(c, "Shortcuts", 1264f, TOP + LOWER + 38f, 23f, Glass.TEXT, true)
            if (m.games.isEmpty()) text(c, "No games yet", 910f, TOP + 110f, 17f, Glass.SOFT)
        } else {
            pane(c, p, 268f, TOP, 1366f, BOTTOM - TOP, 30f)
            val head = when (m.section) { "games" -> "Games"; "apps" -> "Apps on this phone" + (if (m.appsPages > 1) "  ·  ${m.appsPage + 1} of ${m.appsPages}" else ""); else -> "Windows" }
            text(c, head, 292f, TOP + 48f, 28f, Glass.TEXT, true)
            if (tiles.size <= SIDE) text(c, when (m.section) { "windows" -> "No window is open. Open something from Home."; "apps" -> "The helper that shows phone apps in windows is not running."; else -> "Nothing here yet." }, 292f, TOP + 110f, 19f, Glass.SOFT)
        }

        // ---- the tiles
        for (t in tiles) when (t.kind) {
            HomeTile.Kind.SIDE -> {
                if (t.chosen) { p.shader = LinearGradient(t.x, 0f, t.x + t.w, 0f, Glass.VIOLET, Glass.VIOLET_DEEP, Shader.TileMode.CLAMP); c.drawRoundRect(t.x, t.y, t.x + t.w, t.y + t.h, t.radius, t.radius, p); p.shader = null }
                sign(c, p, t.x + 34f, t.cy, 12f, t.sign, if (t.chosen) Color.WHITE else Glass.TEXT)
                text(c, t.label, t.x + 64f, t.cy + 8f, 22f, if (t.chosen) Color.WHITE else Glass.TEXT, t.chosen)
                if (t.running > 0 && !t.chosen) dot(c, p, t.x + t.w - 18f, t.cy, 2)
            }
            HomeTile.Kind.HERO -> {
                artwork(c, p, t.x, t.y, t.w, t.h, t.radius, t.color, t.item?.let(m.icon), t.sign, true, t.item?.kind == HomeItem.Kind.GAME, abs(t.label.hashCode()))
                text(c, t.sub, t.x + 36f, t.y + 88f, 44f, Color.WHITE, true); text(c, t.label, t.x + 38f, t.y + 130f, 26f, 0xFFE4E6F2.toInt(), max = t.w * 0.5f)
                val by = t.y + t.h - 96f
                rr(c, p, t.x + 36f, by, 176f, 56f, 28f, 0x66FFFFFF, 0x70FFFFFF)
                val tri = Path(); tri.moveTo(t.x + 70f, by + 17f); tri.lineTo(t.x + 88f, by + 28f); tri.lineTo(t.x + 70f, by + 39f); tri.close(); p.color = Color.WHITE; c.drawPath(tri, p)
                text(c, if (t.sub == "Continue") "Resume" else "Open", t.x + 104f, by + 36f, 22f, Color.WHITE, true)
                dot(c, p, t.x + t.w - 26f, t.y + 26f, t.running)
            }
            HomeTile.Kind.ART -> {
                artwork(c, p, t.x, t.y, t.w, t.h, t.radius, t.color, t.item?.let(m.icon), t.sign, false, t.item?.kind == HomeItem.Kind.GAME, abs(t.label.hashCode()))
                val small = t.w < 150f
                if (t.sub.isNotEmpty()) { text(c, t.label, t.x + 12f, t.y + t.h - 32f, 17f, Color.WHITE, true, t.w - 34f); text(c, t.sub, t.x + 12f, t.y + t.h - 13f, 14f, 0xFFD0D4E4.toInt(), max = t.w - 34f) }
                else {
                    // a name of two words stands on two lines when it is too long for one
                    val tp = TextPaint().apply { typeface = Type.bold; textSize = if (small) 16.5f else 18f }; val room = t.w - 32f
                    val cut = t.label.indexOf(' ')
                    if (tp.measureText(t.label) > room && cut > 0) { text(c, t.label.substring(0, cut), t.x + 12f, t.y + t.h - 33f, tp.textSize, Color.WHITE, true, room); text(c, t.label.substring(cut + 1), t.x + 12f, t.y + t.h - 13f, tp.textSize, Color.WHITE, true, room) }
                    else text(c, t.label, t.x + 12f, t.y + t.h - 14f, tp.textSize, Color.WHITE, true, room)
                }
                dot(c, p, t.x + t.w - 16f, t.y + 16f, t.running)
            }
            HomeTile.Kind.PLAIN -> {
                rr(c, p, t.x, t.y, t.w, t.h, t.radius, Glass.CARD, Glass.CARD_RIM)
                sign(c, p, t.cx, t.cy - 14f, 14f, t.sign, Glass.TEXT); text(c, t.label, t.cx, t.y + t.h - 20f, 17f, Glass.TEXT, align = Paint.Align.CENTER)
            }
            HomeTile.Kind.ROUND -> {
                rr(c, p, t.x + 12f, t.y, 72f, 72f, 36f, Glass.CARD, Glass.CARD_RIM)
                sign(c, p, t.cx, t.y + 36f, 14f, t.sign, Glass.TEXT); text(c, t.label, t.cx, t.y + 96f, 16f, Glass.TEXT, align = Paint.Align.CENTER)
            }
            HomeTile.Kind.ROOM -> {
                pane(c, p, t.x, t.y, t.w, t.h, t.radius); text(c, "Room & MR", t.x + 24f, t.y + 40f, 23f, Glass.TEXT, true)
                val ix = t.x + 24f; val iy = t.y + 58f; val iw = t.w - 48f; val ih = t.h - 146f
                p.shader = LinearGradient(ix, iy, ix + iw, iy + ih, 0x664A3FA8, 0x66203060, Shader.TileMode.CLAMP); c.drawRoundRect(ix, iy, ix + iw, iy + ih, 18f, 18f, p); p.shader = null
                rr(c, p, ix, iy, iw, ih, 18f, 0, Glass.CARD_RIM)
                plan(c, p, m.room, ix + 22f, iy + 16f, iw - 44f, ih - 32f)
                if (m.roomPlaced) { p.color = Glass.VIOLET; c.drawCircle(ix + iw - 22f, iy + 22f, 13f, p); p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f; p.strokeCap = Paint.Cap.ROUND; p.color = Color.WHITE
                    c.drawLine(ix + iw - 28f, iy + 22f, ix + iw - 23.5f, iy + 26.5f, p); c.drawLine(ix + iw - 23.5f, iy + 26.5f, ix + iw - 16f, iy + 17.5f, p); p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT }
                text(c, t.label, t.x + 24f, t.y + t.h - 48f, 23f, Glass.TEXT, true); text(c, t.sub, t.x + 24f, t.y + t.h - 22f, 17f, Glass.SOFT)
            }
        }
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
