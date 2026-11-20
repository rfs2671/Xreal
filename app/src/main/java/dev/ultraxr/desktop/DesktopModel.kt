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
    var distance: Double = DEFAULT_DISTANCE,
    /** Width of the content area in metres; height follows from [aspect]. */
    var width: Double = DEFAULT_WIDTH,
    var aspect: Double = 16.0 / 9.0,
    var follow: Boolean = false,
    var minimized: Boolean = false,
    var maximized: Boolean = false,
    /** A window kind decides defaults such as the user agent: "web", "youtube", "xbox"; "app" is another app, "settings" the settings drawn by the desktop itself. */
    val kind: String = "web",
) {
    val height: Double get() = width / aspect
    /** The name of the mark of the shelf this window was opened from: that mark shows that it has a window, and brings it back when it was put away. */
    var origin: String = ""
    /**
     * The place in the room this window is arranged around: where the wearer's head was when it was opened, brought
     * back or last moved. Its [yaw], [pitch] and [distance] are from there, so that it stays where it is in the room
     * when the wearer walks. Not saved: a new session has a new world.
     */
    var ax = 0.0; var ay = 0.0; var az = 0.0
    /**
     * The same place and the window's heading in the saved map of the room (x, y, z, yaw; core/MapAlign), when there
     * is one. Saved: a new session that finds itself in the map puts the window back where it stood.
     */
    var map: DoubleArray? = null

    /** Size and placement actually shown: a maximized window is large, centred and close. */
    fun shownWidth(): Double = if (maximized) MAXIMIZED_WIDTH else width
    fun shownHeight(): Double = shownWidth() / aspect
    fun shownDistance(): Double = if (maximized) MAX_DISTANCE else distance
    fun shownYaw(): Double = if (maximized) 0.0 else yaw
    fun shownPitch(): Double = if (maximized) 0.0 else pitch
    /** The strip the handle's words stand on, under the window: their x-height looks the same at any distance ([Sight.BODY]). */
    fun shownHandleHeight(): Double = Sight.metres(Sight.BODY, shownDistance()) * 2.6
    /** The handle is as wide as its four words need and no wider than the window, in the middle under it. */
    fun shownHandleWidth(): Double = minOf(shownWidth(), shownHandleHeight() * 11.0)
    /** The space between the page's lower edge and the handle: the lower brackets are drawn in it. */
    fun shownHandleGap(): Double = Sight.metres(Math.toRadians(0.5), shownDistance())
    /** From the window's middle down to the handle's lower edge. */
    fun shownBelow(): Double = shownHeight() / 2 + shownHandleGap() + shownHandleHeight()
    /** How far from a corner of the page, inwards and sideways, a pointer still has hold of that corner. A corner's bracket has arms of this length. */
    fun shownCornerReach(): Double = (shownWidth() * 0.06).coerceIn(0.04, 0.09)
    /** A game's page takes every key of a gamepad while it is in use (see [FocusModel]). */
    val isGame: Boolean get() = kind == "xbox"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("url", url).put("yaw", yaw).put("pitch", pitch)
        .put("distance", distance).put("width", width).put("aspect", aspect).put("follow", follow).put("minimized", minimized)
        .put("maximized", maximized).put("kind", kind).put("origin", origin)
        .also { o -> map?.let { m -> o.put("map", org.json.JSONArray().put(m[0]).put(m[1]).put(m[2]).put(m[3])) } }

    companion object {
        const val MIN_WIDTH = 0.4
        const val MAX_WIDTH = 3.2
        // The glasses show about 46 x 27 degrees. A new window is 24 degrees wide and 13.5 high: with its title above
        // and its handle below it then stands between the status line and the shelf, all of it in view at once
        // (see Shelf.clearPitch; ShelfTest checks it). A maximized one fills the view.
        const val DEFAULT_WIDTH = 0.80
        const val DEFAULT_DISTANCE = 2.0
        const val MAXIMIZED_WIDTH = 1.55
        const val MAX_DISTANCE = 2.2
        const val MIN_DISTANCE = 0.6
        const val FAR_DISTANCE = 6.0
        val MAX_PITCH = Math.toRadians(60.0)
        /** The shapes a window can be given: from two and a half times as high as wide to three times as wide as high. */
        const val MIN_ASPECT = 0.4
        const val MAX_ASPECT = 3.0

        /**
         * The picture a page is given for a window of this shape, (width, height) in pixels: always about as many
         * pixels as 1280 x 720, which is what a lying window gets (and 720 x 1280 an upright one), sides in eights.
         */
        fun pixels(aspect: Double): IntArray {
            val a = aspect.coerceIn(MIN_ASPECT, MAX_ASPECT); val area = 1280.0 * 720.0
            return intArrayOf((Math.round(sqrt(area * a) / 8.0) * 8).toInt(), (Math.round(sqrt(area / a) / 8.0) * 8).toInt())
        }

        fun fromJson(o: JSONObject): DesktopWindow = DesktopWindow(
            o.getInt("id"), o.optString("title", "Window"), o.optString("url", "about:blank"),
            o.optDouble("yaw", 0.0), o.optDouble("pitch", 0.0), o.optDouble("distance", DEFAULT_DISTANCE).coerceIn(MIN_DISTANCE, FAR_DISTANCE),
            o.optDouble("width", DEFAULT_WIDTH).coerceIn(MIN_WIDTH, MAX_WIDTH), o.optDouble("aspect", 16.0 / 9.0).coerceIn(MIN_ASPECT, MAX_ASPECT),
            o.optBoolean("follow", false), o.optBoolean("minimized", false), o.optBoolean("maximized", false), o.optString("kind", "web")).also { it.origin = o.optString("origin", "") }
            .also { w -> o.optJSONArray("map")?.let { a -> if (a.length() == 4) w.map = DoubleArray(4) { i -> a.optDouble(i, 0.0) } } }
    }
}

/** Where a ray meets a window. [u], [v] run 0..1 across the content, left to right and top to bottom. */
class WindowHit(val window: DesktopWindow, val u: Double, val v: Double, val distance: Double,
    /** Below the page, where the handle's words are (only the window in focus has them); [handleWord] is the word, null between and beside them. */
    val onHandle: Boolean = false, val handleWord: HandleWord? = null,
    /** The corner of the page the ray has hold of, for sizing by dragging: 0 top left, 1 top right, 2 bottom left, 3 bottom right, -1 none. */
    val corner: Int = -1) {
    /** On the page itself, where a press is a touch. */
    val onPage: Boolean get() = !onHandle && u in 0.0..1.0
}

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
    /** The one focus (see Focus.kt). On a window in any form, that window is also [focusedId]. */
    var focus: Focus = Focus.Shelf(0); private set
    /** The shelf's mark the focus was last on: where it returns to. */
    var shelfIndex = 0; private set
    /** How the window being moved or sized was before, to put it back. */
    private class Before(val id: Int, val yaw: Double, val pitch: Double, val distance: Double, val width: Double, val aspect: Double, val maximized: Boolean)
    private var before: Before? = null
    var fullscreenId = -1
    val followAnchor = FollowAnchor()
    private var nextId = 1
    /** Bumped on every change, so observers (renderer, phone UI, persistence) know to refresh. */
    var revision = 0L; private set
    fun touch() { revision++ }

    val focused: DesktopWindow? get() = windows.firstOrNull { it.id == focusedId }
    val visible: List<DesktopWindow> get() = if (fullscreenId >= 0) windows.filter { it.id == fullscreenId } else windows.filter { !it.minimized }

    // ---- places in the room
    /** Where the wearer's head is in the world. New, moved and gathered windows are arranged around it. */
    var here = doubleArrayOf(0.0, 0.0, 0.0)
    /** The head's position is tracked: windows have their own places ([DesktopWindow.ax]). Without it they are all arranged around one point. */
    var positional = false
    /** The point window [w] is arranged around: its own, or the wearer's head for one that follows. [fallback] without position. */
    fun anchorFor(w: DesktopWindow, fallback: DoubleArray): DoubleArray = if (!positional) fallback else if (w.follow) here else doubleArrayOf(w.ax, w.ay, w.az)
    private fun placeHere(w: DesktopWindow) { w.ax = here[0]; w.ay = here[1]; w.az = here[2] }
    /** Every window is arranged around where the wearer is now: at the start, and when the view is recentred. */
    fun anchorAll() { for (w in windows) placeHere(w); touch() }
    /** The window stays where it is in the room and is from now on arranged around where the wearer is: it turns to face them. */
    fun reanchor(w: DesktopWindow) {
        if (!positional || w.follow || w.maximized) return
        val c = Geometry.centre(doubleArrayOf(w.ax, w.ay, w.az), w.yaw, w.pitch, w.distance)
        val d = doubleArrayOf(c[0] - here[0], c[1] - here[1], c[2] - here[2]); val n = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        if (n > 0.2) { w.yaw = Geometry.yawOf(d); w.pitch = Geometry.pitchOf(d).coerceIn(-DesktopWindow.MAX_PITCH, DesktopWindow.MAX_PITCH); w.distance = n.coerceIn(DesktopWindow.MIN_DISTANCE, DesktopWindow.FAR_DISTANCE) }
        placeHere(w)
    }
    /** Which way a window lies from where the wearer is now. */
    fun seenYaw(w: DesktopWindow): Double {
        if (!positional || w.follow) return worldYaw(w)
        val c = Geometry.centre(doubleArrayOf(w.ax, w.ay, w.az), worldYaw(w), w.shownPitch(), w.shownDistance())
        val dx = c[0] - here[0]; val dz = c[2] - here[2]
        return if (dx * dx + dz * dz < 0.01) worldYaw(w) else atan2(-dx, -dz)
    }

    fun add(title: String, url: String, kind: String = "web", headYaw: Double = 0.0): DesktopWindow? {
        if (windows.size >= MAX_WINDOWS) return null
        val w = DesktopWindow(nextId++, title, url, kind = kind); placeHere(w)
        // Straight ahead of where the wearer looks, unless a window stands there already: then the nearest place
        // beside it that is free, so that windows do not stack and a new one is never opened out of sight for nothing.
        val there = windows.filter { !it.minimized }.map { seenYaw(it) }
        val slot = SLOTS.firstOrNull { s -> there.none { Math.abs(Geometry.wrap(it - (headYaw + Math.toRadians(s)))) < Math.toRadians(SLOT_CLEAR) } } ?: SLOTS[there.size % SLOTS.size]
        w.yaw = Geometry.wrap(headYaw + Math.toRadians(slot)); w.pitch = 0.0
        windows += w; onWindow(w.id, Focus.Page(w.id)); touch()
        return w
    }

    // ---- the focus
    /** The window whose handle shows: the one in focus, unless it fills the view. */
    val handleId: Int get() = if (fullscreenId >= 0) -1 else focus.windowId ?: -1

    private fun setFocus(f: Focus) {
        if (f is Focus.Shelf) shelfIndex = f.index
        f.windowId?.let { focusedId = it }
        // a window that fills the view has no room for its handle or the shelf: going to either ends that
        if (fullscreenId >= 0 && (f is Focus.Shelf || f is Focus.Handle)) fullscreenId = -1
        focus = f
    }
    /** The focus goes to window [id] as [f], unless it is on that window already (then it keeps its place there). */
    private fun onWindow(id: Int, f: Focus) { focusedId = id; if (focus.windowId != id) { before = null; setFocus(f) } }
    /** The window in focus went away: the focus is on [focusedId] if that shows, else on the shelf. */
    private fun offWindow() { before = null; setFocus(if (visible.any { it.id == focusedId }) Focus.Window(focusedId) else Focus.Shelf(shelfIndex)) }

    fun scene(shelfCount: Int, headYaw: Double = 0.0) = FocusScene(shelfCount, visible.map { FocusWindow(it.id, seenYaw(it)) }, headYaw, shelfIndex)

    /**
     * A key of the d-pad. Moves the focus by the table, does what belongs to the windows themselves (moving, sizing,
     * pinning, closing) and returns the action, so that the caller can do the rest (open an app, send a key to a page,
     * let go of a closed window's page).
     */
    fun press(key: FocusKey, shelfCount: Int, headYaw: Double = 0.0): FocusAction {
        val step = FocusModel.press(focus, key, scene(shelfCount, headYaw))
        when (val a = step.action) {
            is FocusAction.StartMove -> begin(a.id)
            is FocusAction.StartSize -> begin(a.id)
            is FocusAction.MoveStep -> moveStep(a.id, a.key)
            is FocusAction.SizeStep -> sizeStep(a.id, a.key)
            is FocusAction.PutDown -> before = null
            is FocusAction.Cancel -> putBack()
            is FocusAction.TogglePin -> toggleFollow(a.id)
            is FocusAction.CloseWindow -> close(a.id)      // moves the focus itself: to the next window by direction, or the shelf
            else -> {}
        }
        if (step.action !is FocusAction.CloseWindow) setFocus(step.focus)
        touch()
        return step.action
    }

    /**
     * A pointer (a hand's ray, the touchpad's cursor, a look) came over [f]: the focus goes there. Not while a window
     * is being moved or sized, which ends only by putting it down or back. Returns whether the focus changed.
     */
    fun point(f: Focus): Boolean {
        if (focus is Focus.Moving || focus is Focus.Sizing || f == focus) return false
        val id = f.windowId
        if (id != null && visible.none { it.id == id }) return false
        if (f is Focus.Shelf && f.index < 0) return false
        setFocus(f); touch(); return true
    }

    /** A look rested on window [id]: the focus goes to that window, unless it is on it already. */
    fun look(id: Int): Boolean = if (focus.windowId == id) false else point(Focus.Window(id))

    /** What a pointer at ([u], [v]) of window [w] is over; v is above 1 under the page, where the handle is. */
    fun focusAt(w: DesktopWindow, u: Double, v: Double): Focus = when {
        v < 0.0 -> Focus.Window(w.id)
        v > 1.0 -> (if ((v - 1.0) * w.shownHeight() >= w.shownHandleGap()) HandleStrip.wordAt(handleU(w, (u - 0.5) * w.shownWidth())) else null)?.let { Focus.Handle(w.id, it) } ?: Focus.Window(w.id)
        else -> Focus.Page(w.id)
    }
    /** Where [x] metres to the right of the window's middle is across its handle, 0..1 (outside that: beside the words). */
    private fun handleU(w: DesktopWindow, x: Double) = x / w.shownHandleWidth() + 0.5
    fun focusAt(h: WindowHit): Focus = when {
        h.handleWord != null -> Focus.Handle(h.window.id, h.handleWord)
        h.onPage -> Focus.Page(h.window.id)
        else -> Focus.Window(h.window.id)
    }

    // ---- moving and sizing, with something to go back to
    private fun begin(id: Int) {
        val w = find(id) ?: return
        reanchor(w)      // it is moved round where the wearer stands now, and does not jump when taken up
        before = Before(id, w.yaw, w.pitch, w.distance, w.width, w.aspect, w.maximized)
        w.maximized = false      // a maximized window has its place and size given: it is let go of first
    }
    fun startMove(id: Int) { if (visible.none { it.id == id } || id == fullscreenId) return; begin(id); setFocus(Focus.Moving(id)); touch() }
    fun startSize(id: Int) { if (visible.none { it.id == id } || id == fullscreenId) return; begin(id); setFocus(Focus.Sizing(id)); touch() }
    /** Sizing by dragging a corner: the same going back, without the focus leaving where it is. */
    fun beginDrag(id: Int) { begin(id); touch() }
    fun endDrag() { before = null; touch() }
    /** The window that was being moved or sized is as it was before. */
    fun putBack() {
        val b = before ?: return; before = null
        val w = find(b.id) ?: return
        w.yaw = b.yaw; w.pitch = b.pitch; w.distance = b.distance; w.width = b.width; w.aspect = b.aspect; w.maximized = b.maximized; touch()
    }

    /** One press while moving: left and right round the wearer, up and down, nearer and further. */
    fun moveStep(id: Int, key: FocusKey) {
        when (key) {
            FocusKey.LEFT -> move(id, MOVE_STEP, 0.0); FocusKey.RIGHT -> move(id, -MOVE_STEP, 0.0)
            FocusKey.UP -> move(id, 0.0, MOVE_STEP); FocusKey.DOWN -> move(id, 0.0, -MOVE_STEP)
            FocusKey.NEARER -> push(id, 1 / PUSH_STEP); FocusKey.FURTHER -> push(id, PUSH_STEP)
            else -> {}
        }
    }

    /** One press while sizing: left narrower, right wider, up shorter, down taller, [SIZE_STEP] each. The other side keeps its length. */
    fun sizeStep(id: Int, key: FocusKey) {
        val w = find(id) ?: return; val h = w.height
        when (key) {
            // the width stops where the shape would become narrower or wider than a window may be
            FocusKey.LEFT -> sizeTo(id, maxOf(w.width / SIZE_STEP, h * DesktopWindow.MIN_ASPECT), h)
            FocusKey.RIGHT -> sizeTo(id, minOf(w.width * SIZE_STEP, h * DesktopWindow.MAX_ASPECT), h)
            FocusKey.UP -> sizeTo(id, w.width, h / SIZE_STEP)
            FocusKey.DOWN -> sizeTo(id, w.width, h * SIZE_STEP)
            else -> {}
        }
    }

    /** Free sizing: the window becomes [width] by [height] metres, as far as the limits allow. */
    fun sizeTo(id: Int, width: Double, height: Double) {
        val w = find(id) ?: return; if (w.maximized) return
        w.width = width.coerceIn(DesktopWindow.MIN_WIDTH, DesktopWindow.MAX_WIDTH)
        w.aspect = (w.width / height.coerceAtLeast(1e-6)).coerceIn(DesktopWindow.MIN_ASPECT, DesktopWindow.MAX_ASPECT); touch()
    }

    /** Closes a window. If it was the one in focus, the focus goes to the next window by direction, or to the shelf. */
    fun close(id: Int) {
        if (find(id) == null) return
        val next = FocusModel.neighbour(windows.filter { !it.minimized }.map { FocusWindow(it.id, worldYaw(it)) }, id, 1)
        windows.removeAll { it.id == id }
        if (before?.id == id) before = null
        if (fullscreenId == id) fullscreenId = -1
        if (focusedId == id) focusedId = next ?: -1
        if (focus.windowId == id) offWindow()
        touch()
    }
    /** The window is chosen to be used (its chip on the phone, opening it): its page is in focus. */
    fun focus(id: Int) { val w = windows.firstOrNull { it.id == id } ?: return; if (w.minimized) w.minimized = false; onWindow(id, Focus.Page(id)); touch() }
    fun minimize(id: Int) {
        val w = find(id) ?: return; w.minimized = true; w.maximized = false; if (fullscreenId == id) fullscreenId = -1
        if (before?.id == id) before = null
        if (focusedId == id) focusedId = windows.lastOrNull { !it.minimized }?.id ?: -1
        if (focus.windowId == id) offWindow()
        touch()
    }
    fun restore(id: Int) { val w = find(id) ?: return; w.minimized = false; onWindow(id, Focus.Page(id)); touch() }
    /** The shelf's focus is put on [i]: what the shelf shows has changed (the home panel for the dock, or back). */
    fun shelfAt(i: Int) { shelfIndex = maxOf(0, i); if (focus is Focus.Shelf) setFocus(Focus.Shelf(shelfIndex)); touch() }
    /**
     * A window that was asked for is brought to where the wearer looks, if it is not there already: to the nearest free
     * place in front (as a new window is placed), at the height [pitch]. Its size and distance are kept.
     */
    fun bringFront(id: Int, headYaw: Double, pitch: Double) {
        val w = find(id) ?: return
        if (w.maximized) return
        reanchor(w)
        if (Math.abs(Geometry.wrap(worldYaw(w) - headYaw)) < Math.toRadians(11.0) && Math.abs(w.pitch - pitch) < Math.toRadians(5.0) && w.distance <= DesktopWindow.DEFAULT_DISTANCE * 1.5) return
        if (positional && w.distance > DesktopWindow.DEFAULT_DISTANCE * 1.5) w.distance = DesktopWindow.DEFAULT_DISTANCE      // it was left behind in the room: it comes within reach
        val there = windows.filter { !it.minimized && it.id != id }.map { seenYaw(it) }
        val slot = SLOTS.firstOrNull { s -> there.none { Math.abs(Geometry.wrap(it - (headYaw + Math.toRadians(s)))) < Math.toRadians(SLOT_CLEAR) } } ?: 0.0
        val to = Geometry.wrap(headYaw + Math.toRadians(slot))
        w.yaw = if (w.follow) Geometry.wrap(to - followAnchor.yaw) else to; w.pitch = pitch; touch()
    }
    fun toggleMaximize(id: Int) { val w = find(id) ?: return; w.maximized = !w.maximized; w.minimized = false; onWindow(id, Focus.Page(id)); touch() }
    fun toggleFullscreen(id: Int) {
        if (find(id) == null) return; fullscreenId = if (fullscreenId == id) -1 else id; onWindow(id, Focus.Page(id))
        // filling the view, a window has no handle: a focus on it goes to the window
        if (fullscreenId == id && (focus is Focus.Handle || focus is Focus.Moving || focus is Focus.Sizing)) { before = null; setFocus(Focus.Window(id)) }
        touch()
    }

    /** Switches a window between following the head and staying in space without it jumping. */
    fun toggleFollow(id: Int) {
        val w = find(id) ?: return
        w.yaw = if (w.follow) Geometry.wrap(w.yaw + followAnchor.yaw) else Geometry.wrap(w.yaw - followAnchor.yaw)
        if (w.follow) placeHere(w)      // let go, it stays where it was seen
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
            for (w in vis) { w.yaw = Geometry.wrap(w.yaw - mean + headYaw); w.pitch = 0.0; placeHere(w); if (positional && !w.maximized) w.distance = w.distance.coerceAtMost(DesktopWindow.DEFAULT_DISTANCE * 1.5) }
        }
        followAnchor.yaw = headYaw; touch()
    }

    fun find(id: Int) = windows.firstOrNull { it.id == id }
    fun worldYaw(w: DesktopWindow) = if (w.follow) Geometry.wrap(w.shownYaw() + followAnchor.yaw) else w.shownYaw()

    /** The nearest visible window the ray hits (its page, a corner's bracket, or the handle under the one in focus), or null. */
    fun hit(origin: DoubleArray, dir: DoubleArray, anchor: DoubleArray): WindowHit? {
        var best: WindowHit? = null
        for (w in visible) {
            val yaw = worldYaw(w); val pitch = w.shownPitch()
            val c = Geometry.centre(anchorFor(w, anchor), yaw, pitch, w.shownDistance())
            val h = Geometry.planeHit(origin, dir, c, yaw, pitch) ?: continue
            val ww = w.shownWidth(); val wh = w.shownHeight(); val gap = w.shownHandleGap()
            val x = h[0]; val y = h[1]
            // only the window in focus has its handle under it; a corner can be taken hold of a little beside the page too
            val handle = if (w.id == handleId) gap + w.shownHandleHeight() else 0.0
            val reach = if (w.id == fullscreenId) 0.0 else w.shownCornerReach()
            if (abs(x) > ww / 2 + reach || y < -wh / 2 - handle || y > wh / 2) continue
            val onHandle = y < -wh / 2
            val byCorner = !onHandle && reach > 0 && abs(x) >= ww / 2 - reach && abs(y) >= wh / 2 - reach
            if (abs(x) > ww / 2 && !byCorner) continue
            val u = x / ww + 0.5; val v = 0.5 - y / wh
            val hitNow = WindowHit(w, u, v, h[2], onHandle,
                if (onHandle && y <= -wh / 2 - gap) HandleStrip.wordAt(handleU(w, x)) else null, if (byCorner) (if (y > 0) 0 else 2) + (if (x > 0) 1 else 0) else -1)
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
        fullscreenId = -1; before = null; shelfIndex = 0; focus = Focus.Shelf(0); touch()
    }

    companion object {
        const val MAX_WINDOWS = 6
        /** One press of the d-pad while moving turns the window this far round the wearer, or up or down. */
        val MOVE_STEP = Math.toRadians(4.0)
        /** One press of a shoulder key brings it this much nearer or further. */
        const val PUSH_STEP = 1.1
        /** One press while sizing: 4 %. */
        const val SIZE_STEP = 1.04
        /**
         * Yaw offsets in degrees for the first, second, ... open window. A new window is 24 degrees wide: 28 degrees
         * apart they stand side by side with a hand's breadth between them, and a glance to the side shows the next.
         */
        val SLOTS = doubleArrayOf(0.0, -28.0, 28.0, -56.0, 56.0, 84.0)
        /** A place is free when no window's middle is within this many degrees of it. */
        const val SLOT_CLEAR = 22.0
    }
}

/** A site offered on the shelf, and the installed app ([pkg]) that is shown instead when it can be. */
class HomeApp(val title: String, val url: String, val kind: String, val color: Int, val pkg: String = "")

object HomeApps {
    val all = listOf(
        HomeApp("YouTube", "https://m.youtube.com/", "youtube", 0xFFE53935.toInt(), "com.google.android.youtube"),
        HomeApp("Xbox Cloud", "https://www.xbox.com/play", "xbox", 0xFF2E7D32.toInt(), "com.gamepass"),
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

/**
 * Look-to-press. Resting the gaze on a target for [holdNs] presses it once; the gaze must leave
 * before the same target can be pressed again, so it cannot repeat while you keep looking.
 * A short [armNs] passes before progress starts, so sweeping across a target does nothing.
 */
class Dwell(private val holdNs: Long = 900_000_000L, private val armNs: Long = 250_000_000L) {
    var target: String? = null; private set
    /** 0 while arming, rising to 1 when the press happens. */
    var progress = 0.0; private set
    private var sinceNs = 0L
    private var fired = false

    /** [lookedAt] is what the gaze is on now (null for nothing). Returns the target to press, once. */
    fun update(nowNs: Long, lookedAt: String?): String? {
        if (lookedAt != target) { target = lookedAt; sinceNs = nowNs; fired = false; progress = 0.0; return null }
        if (lookedAt == null || fired) { progress = 0.0; return null }
        val t = nowNs - sinceNs - armNs
        progress = (t.toDouble() / holdNs).coerceIn(0.0, 1.0)
        if (t >= holdNs) { fired = true; progress = 0.0; return lookedAt }
        return null
    }

    fun cancel() { target = null; progress = 0.0; fired = false }
}

/**
 * Two-eye geometry for the glasses' 3D mode. Each eye sits half the eye distance to the side of the
 * head's centre. The glasses' optics show both panels converged at [CONVERGENCE_M] (where a flat 2D
 * picture appears), so each eye's view is turned towards the nose by [shift]: something at that
 * distance lands on the same pixel in both eyes, nearer things are seen crossed, farther uncrossed.
 */
object Stereo {
    /** Distance at which the glasses show a flat picture (the value Pocket XR's driver uses for XREAL glasses). */
    const val CONVERGENCE_M = 4.0

    /** Sideways position of the eye drawn into the left ([half] 0) or right ([half] 1) half of the picture, metres. */
    fun eyeOffset(half: Int, ipdM: Double, swap: Boolean): Double = if ((half == 0) != swap) -ipdM / 2 else ipdM / 2

    /** How far that eye's view centre is turned, as a tangent (positive = to the wearer's right). */
    fun shift(eyeOffset: Double): Double = -eyeOffset / CONVERGENCE_M

    /** Where a point [x] to the right and [depth] ahead of the head's centre lands in that eye's picture, -1..1. */
    fun pictureX(x: Double, depth: Double, eyeOffset: Double, tanH: Double): Double = ((x - eyeOffset) / depth - shift(eyeOffset)) / tanH
}
