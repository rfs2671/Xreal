package dev.ultraxr.desktop

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The spatial desktop's state and geometry, free of Android classes so it can be unit-tested.
 *
 * World frame (the app's): y up, -z forward, metres. A window is a flat rectangle whose centre
 * sits [distance] metres from its anchor in the direction ([yaw], [pitch]) and which faces the
 * anchor. yaw is positive to the left, pitch positive upwards, both in radians.
 *  - Lock in space: the direction is absolute in the world.
 *  - Follow head: the direction is relative to a heading that trails the head (see [FollowAnchor]).
 */
class DesktopWindow(
    val id: Int,
    var title: String,
    var url: String,
    var yaw: Double = 0.0,
    var pitch: Double = 0.0,
    var distance: Double = 1.8,
    /** Width of the content area in metres; height follows from [aspect]. */
    var width: Double = 1.4,
    var aspect: Double = 16.0 / 9.0,
    var follow: Boolean = false,
    var minimized: Boolean = false,
    var maximized: Boolean = false,
    /** A window kind decides defaults such as the user agent: "web", "youtube", "xbox". */
    val kind: String = "web",
) {
    val height: Double get() = width / aspect
    val barHeight: Double get() = (width * BAR_FRACTION).coerceIn(0.05, 0.11)

    /** Size and placement actually shown: a maximized window is large, centred and close. */
    fun shownWidth(): Double = if (maximized) MAX_WIDTH else width
    fun shownHeight(): Double = shownWidth() / aspect
    fun shownDistance(): Double = if (maximized) MAX_DISTANCE else distance
    fun shownYaw(): Double = if (maximized) 0.0 else yaw
    fun shownPitch(): Double = if (maximized) 0.0 else pitch
    fun shownBarHeight(): Double = (shownWidth() * BAR_FRACTION).coerceIn(0.05, 0.11)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("url", url).put("yaw", yaw).put("pitch", pitch)
        .put("distance", distance).put("width", width).put("aspect", aspect).put("follow", follow).put("minimized", minimized)
        .put("maximized", maximized).put("kind", kind)

    companion object {
        const val BAR_FRACTION = 0.05
        const val MIN_WIDTH = 0.4
        const val MAX_WIDTH = 3.2
        const val MAX_DISTANCE = 2.2
        const val MIN_DISTANCE = 0.6
        const val FAR_DISTANCE = 6.0
        val MAX_PITCH = Math.toRadians(60.0)

        fun fromJson(o: JSONObject): DesktopWindow = DesktopWindow(
            o.getInt("id"), o.optString("title", "Window"), o.optString("url", "about:blank"),
            o.optDouble("yaw", 0.0), o.optDouble("pitch", 0.0), o.optDouble("distance", 1.8).coerceIn(MIN_DISTANCE, FAR_DISTANCE),
            o.optDouble("width", 1.4).coerceIn(MIN_WIDTH, MAX_WIDTH), o.optDouble("aspect", 16.0 / 9.0).coerceIn(0.4, 3.0),
            o.optBoolean("follow", false), o.optBoolean("minimized", false), o.optBoolean("maximized", false), o.optString("kind", "web"))
    }
}

/** Where a ray meets a window. [u], [v] run 0..1 across the content, left to right and top to bottom. */
class WindowHit(val window: DesktopWindow, val u: Double, val v: Double, val onBar: Boolean, val barButton: BarButton?, val distance: Double)

enum class BarButton { FOLLOW, MINIMIZE, MAXIMIZE, CLOSE }

/**
 * The heading that "Follow head" windows are placed against. It does not copy the head: it stays
 * put while the head turns inside a comfort zone, and catches up smoothly once the head leaves it,
 * so a followed window can still be looked at and across without sliding away.
 */
class FollowAnchor(var yaw: Double = 0.0) {
    private var catchingUp = false

    fun update(headYaw: Double, dt: Double) {
        val diff = Geometry.wrap(headYaw - yaw)
        if (abs(diff) > COMFORT) catchingUp = true
        if (catchingUp) {
            val step = diff * (1 - Math.exp(-dt * RATE))
            yaw = Geometry.wrap(yaw + step)
            if (abs(Geometry.wrap(headYaw - yaw)) < SETTLE) catchingUp = false
        }
    }

    companion object {
        val COMFORT = Math.toRadians(32.0)
        val SETTLE = Math.toRadians(3.0)
        const val RATE = 4.0
    }
}

object Geometry {
    fun wrap(a: Double): Double { var x = a; while (x > PI) x -= 2 * PI; while (x < -PI) x += 2 * PI; return x }

    /** Unit direction for a yaw (positive = left) and pitch (positive = up). */
    fun direction(yaw: Double, pitch: Double) = doubleArrayOf(-sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))

    fun yawOf(d: DoubleArray) = atan2(-d[0], -d[2])
    fun pitchOf(d: DoubleArray): Double { val n = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); return if (n < 1e-9) 0.0 else asin((d[1] / n).coerceIn(-1.0, 1.0)) }

    /** Centre of a window in the world: [anchor] position, absolute [yaw] and [pitch]. */
    fun centre(anchor: DoubleArray, yaw: Double, pitch: Double, distance: Double): DoubleArray {
        val d = direction(yaw, pitch)
        return doubleArrayOf(anchor[0] + d[0] * distance, anchor[1] + d[1] * distance, anchor[2] + d[2] * distance)
    }

    /**
     * Column-major 4x4 model matrix for a unit quad (x, y in -0.5..0.5, facing +z) placed as a
     * window: scaled to [w] x [h], pitched, yawed and moved to [c]. The quad's +z then points back
     * at the anchor.
     */
    fun modelMatrix(c: DoubleArray, yaw: Double, pitch: Double, w: Double, h: Double, out: FloatArray) {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        // R = Ry(yaw) * Rx(pitch); columns are the window's right, up and normal in the world
        val rx = doubleArrayOf(cy, 0.0, -sy)
        val ux = doubleArrayOf(sy * sp, cp, cy * sp)
        val nx = doubleArrayOf(sy * cp, -sp, cy * cp)
        out[0] = (rx[0] * w).toFloat(); out[1] = (rx[1] * w).toFloat(); out[2] = (rx[2] * w).toFloat(); out[3] = 0f
        out[4] = (ux[0] * h).toFloat(); out[5] = (ux[1] * h).toFloat(); out[6] = (ux[2] * h).toFloat(); out[7] = 0f
        out[8] = nx[0].toFloat(); out[9] = nx[1].toFloat(); out[10] = nx[2].toFloat(); out[11] = 0f
        out[12] = c[0].toFloat(); out[13] = c[1].toFloat(); out[14] = c[2].toFloat(); out[15] = 1f
    }

    /**
     * Intersects the ray [o] + t [d] with a window plane. Returns (x, y, t): x to the window's
     * right and y up from its centre, in metres, or null if the ray is parallel or points away.
     */
    fun planeHit(o: DoubleArray, d: DoubleArray, c: DoubleArray, yaw: Double, pitch: Double): DoubleArray? {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val right = doubleArrayOf(cy, 0.0, -sy); val up = doubleArrayOf(sy * sp, cp, cy * sp); val n = doubleArrayOf(sy * cp, -sp, cy * cp)
        val denom = d[0] * n[0] + d[1] * n[1] + d[2] * n[2]
        if (denom > -1e-6) return null                       // parallel, or looking at the back
        val rel = doubleArrayOf(c[0] - o[0], c[1] - o[1], c[2] - o[2])
        val t = (rel[0] * n[0] + rel[1] * n[1] + rel[2] * n[2]) / denom
        if (t <= 0) return null
        val p = doubleArrayOf(o[0] + d[0] * t - c[0], o[1] + d[1] * t - c[1], o[2] + d[2] * t - c[2])
        return doubleArrayOf(p[0] * right[0] + p[1] * right[1] + p[2] * right[2], p[0] * up[0] + p[1] * up[1] + p[2] * up[2], t)
    }
}

/** The set of windows, focus, and every operation the interface offers. */
class Desktop {
    val windows = ArrayList<DesktopWindow>()
    var focusedId = -1
    var fullscreenId = -1
    val followAnchor = FollowAnchor()
    private var nextId = 1
    /** Bumped on every change, so observers (renderer, phone UI, persistence) know to refresh. */
    var revision = 0L; private set
    fun touch() { revision++ }

    val focused: DesktopWindow? get() = windows.firstOrNull { it.id == focusedId }
    val visible: List<DesktopWindow> get() = if (fullscreenId >= 0) windows.filter { it.id == fullscreenId } else windows.filter { !it.minimized }

    fun add(title: String, url: String, kind: String = "web", headYaw: Double = 0.0): DesktopWindow? {
        if (windows.size >= MAX_WINDOWS) return null
        val w = DesktopWindow(nextId++, title, url, kind = kind)
        // Open in front of the user, then fan further windows out to the sides so they do not stack.
        val slot = SLOTS[windows.count { !it.minimized } % SLOTS.size]
        w.yaw = Geometry.wrap(headYaw + Math.toRadians(slot)); w.pitch = 0.0
        windows += w; focusedId = w.id; touch()
        return w
    }

    fun close(id: Int) { windows.removeAll { it.id == id }; if (focusedId == id) focusedId = windows.lastOrNull { !it.minimized }?.id ?: -1; if (fullscreenId == id) fullscreenId = -1; touch() }
    fun focus(id: Int) { val w = windows.firstOrNull { it.id == id } ?: return; if (w.minimized) w.minimized = false; if (focusedId != id) { focusedId = id; touch() } else touch() }
    fun minimize(id: Int) { val w = find(id) ?: return; w.minimized = true; w.maximized = false; if (fullscreenId == id) fullscreenId = -1; if (focusedId == id) focusedId = windows.lastOrNull { !it.minimized }?.id ?: -1; touch() }
    fun restore(id: Int) { val w = find(id) ?: return; w.minimized = false; focusedId = id; touch() }
    fun toggleMaximize(id: Int) { val w = find(id) ?: return; w.maximized = !w.maximized; w.minimized = false; focusedId = id; touch() }
    fun toggleFullscreen(id: Int) { if (find(id) == null) return; fullscreenId = if (fullscreenId == id) -1 else id; focusedId = id; touch() }

    /** Switches a window between following the head and staying in space without it jumping. */
    fun toggleFollow(id: Int) {
        val w = find(id) ?: return
        w.yaw = if (w.follow) Geometry.wrap(w.yaw + followAnchor.yaw) else Geometry.wrap(w.yaw - followAnchor.yaw)
        w.follow = !w.follow; touch()
    }

    fun move(id: Int, dYaw: Double, dPitch: Double) { val w = find(id) ?: return; if (w.maximized) return; w.yaw = Geometry.wrap(w.yaw + dYaw); w.pitch = (w.pitch + dPitch).coerceIn(-DesktopWindow.MAX_PITCH, DesktopWindow.MAX_PITCH); touch() }
    fun resize(id: Int, factor: Double) { val w = find(id) ?: return; if (w.maximized) return; w.width = (w.width * factor).coerceIn(DesktopWindow.MIN_WIDTH, DesktopWindow.MAX_WIDTH); touch() }
    fun push(id: Int, factor: Double) { val w = find(id) ?: return; if (w.maximized) return; w.distance = (w.distance * factor).coerceIn(DesktopWindow.MIN_DISTANCE, DesktopWindow.FAR_DISTANCE); touch() }

    /** Points a window at an absolute world direction (used while it is dragged by a ray). */
    fun aim(id: Int, worldYaw: Double, pitch: Double) {
        val w = find(id) ?: return; if (w.maximized) return
        w.yaw = if (w.follow) Geometry.wrap(worldYaw - followAnchor.yaw) else Geometry.wrap(worldYaw)
        w.pitch = pitch.coerceIn(-DesktopWindow.MAX_PITCH, DesktopWindow.MAX_PITCH); touch()
    }

    /** Brings every window back in front of the user at the heading [headYaw], keeping its arrangement. */
    fun gather(headYaw: Double) {
        val vis = windows.filter { !it.minimized && !it.follow }
        if (vis.isNotEmpty()) {
            val mean = atan2(vis.sumOf { sin(it.yaw) }, vis.sumOf { cos(it.yaw) })
            for (w in vis) w.yaw = Geometry.wrap(w.yaw - mean + headYaw)
        }
        followAnchor.yaw = headYaw; touch()
    }

    fun find(id: Int) = windows.firstOrNull { it.id == id }
    fun worldYaw(w: DesktopWindow) = if (w.follow) Geometry.wrap(w.shownYaw() + followAnchor.yaw) else w.shownYaw()

    /** The nearest visible window the ray hits (content or title bar), or null. */
    fun hit(origin: DoubleArray, dir: DoubleArray, anchor: DoubleArray): WindowHit? {
        var best: WindowHit? = null
        for (w in visible) {
            val yaw = worldYaw(w); val pitch = w.shownPitch()
            val c = Geometry.centre(anchor, yaw, pitch, w.shownDistance())
            val h = Geometry.planeHit(origin, dir, c, yaw, pitch) ?: continue
            val ww = w.shownWidth(); val wh = w.shownHeight(); val bar = w.shownBarHeight()
            val x = h[0]; val y = h[1]
            if (abs(x) > ww / 2 || y < -wh / 2 || y > wh / 2 + bar) continue
            val onBar = y > wh / 2
            val u = x / ww + 0.5; val v = 0.5 - y / wh
            val hitNow = WindowHit(w, u, v, onBar, if (onBar) barButtonAt(u) else null, h[2])
            if (best == null || hitNow.distance < best.distance) best = hitNow
        }
        return best
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("focused", focusedId).put("nextId", nextId)
        .put("windows", JSONArray().also { a -> windows.forEach { a.put(it.toJson()) } })

    fun load(o: JSONObject) {
        windows.clear()
        val a = o.optJSONArray("windows") ?: JSONArray()
        for (i in 0 until minOf(a.length(), MAX_WINDOWS)) windows += DesktopWindow.fromJson(a.getJSONObject(i))
        nextId = maxOf(o.optInt("nextId", 1), (windows.maxOfOrNull { it.id } ?: 0) + 1)
        focusedId = o.optInt("focused", -1).let { f -> if (windows.any { it.id == f && !it.minimized }) f else windows.lastOrNull { !it.minimized }?.id ?: -1 }
        fullscreenId = -1; touch()
    }

    companion object {
        const val MAX_WINDOWS = 6
        /** Yaw offsets in degrees for the first, second, ... open window. */
        val SLOTS = doubleArrayOf(0.0, -48.0, 48.0, -96.0, 96.0, 0.0)
        /** Title-bar buttons occupy the right end of the bar; fractions of the bar's width. */
        const val BUTTON_FRACTION = 0.07
        fun barButtonAt(u: Double): BarButton? {
            val fromRight = ((1.0 - u) / BUTTON_FRACTION).toInt()
            return when {
                u < 0 || u > 1 -> null
                fromRight == 0 -> BarButton.CLOSE
                fromRight == 1 -> BarButton.MAXIMIZE
                fromRight == 2 -> BarButton.MINIMIZE
                fromRight == 3 -> BarButton.FOLLOW
                else -> null
            }
        }
    }
}

/** Sites offered on the home screen. */
class HomeApp(val title: String, val url: String, val kind: String, val color: Int)

object HomeApps {
    val all = listOf(
        HomeApp("YouTube", "https://m.youtube.com/", "youtube", 0xFFE53935.toInt()),
        HomeApp("Xbox Cloud", "https://www.xbox.com/play", "xbox", 0xFF2E7D32.toInt()),
        HomeApp("Browser", "https://www.google.com/", "web", 0xFF1E88E5.toInt()),
        HomeApp("3D web", "https://threejs.org/examples/#webgl_animation_keyframes", "web", 0xFF8E24AA.toInt()),
    )

    /** Turns what the user typed into a URL: a full URL, a bare host, or a web search. */
    fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return "about:blank"
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("about:")) return s
        return if (!s.contains(' ') && s.contains('.')) "https://$s" else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, "UTF-8")
    }
}
