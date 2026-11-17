package dev.ultraxr.desktop

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.Surface
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Q
import dev.ultraxr.tracking.Tracking
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the spatial desktop: owns the window model and each window's web content, turns touchpad,
 * gaze and hand input into actions, builds what the renderer draws, and saves the layout.
 * Main thread only, except [onSurface], which the renderer calls from the GL thread.
 */
class DesktopSession(private val activity: Activity) {
    val desktop = Desktop()
    private val web = HashMap<Int, WebWindow>()
    private val bars = HashMap<Int, Pair<Bitmap, String>>()
    private val barVersions = HashMap<Int, Long>()
    private val cursors = HashMap<Int, DoubleArray>()
    private val prefs = activity.getSharedPreferences("desktop", Context.MODE_PRIVATE)
    private var homeBitmap: Bitmap? = null
    private var homeVersion = 0L; private var homeMinute = ""
    private var savedRevision = -1L
    private var lastTickNs = 0L
    private var pressed = false
    private var gazeCandidate = -1; private var gazeSinceNs = 0L
    private var padTouchNs = 0L
    private var handDown = false; private var handDragWindow = -1; private var handDownHit: WindowHit? = null; private var handMoved = false
    private var lastHandRay: Pair<DoubleArray, DoubleArray>? = null
    @Volatile private var surfaces = HashMap<Int, Surface>()
    var onChanged: (() -> Unit)? = null
    /** Set by the stage: where frames go. */
    var renderers: List<DesktopRenderer> = emptyList()
    var statusLine = ""; private set

    // ---- settings (persisted)
    var usePosition: Boolean get() = prefs.getBoolean("usePosition", false); set(v) { prefs.edit().putBoolean("usePosition", v).apply() }
    var gazeFocus: Boolean get() = prefs.getBoolean("gazeFocus", true); set(v) { prefs.edit().putBoolean("gazeFocus", v).apply() }
    var handGestures: Boolean get() = prefs.getBoolean("handGestures", true); set(v) { prefs.edit().putBoolean("handGestures", v).apply() }
    var dimUnfocused: Boolean get() = prefs.getBoolean("dim", true); set(v) { prefs.edit().putBoolean("dim", v).apply() }

    fun start() {
        try { prefs.getString("layout", null)?.let { desktop.load(JSONObject(it)) } } catch (e: Exception) { Tracking.log("desktop: saved layout ignored: $e") }
        for (w in desktop.windows) ensureWeb(w)
        savedRevision = desktop.revision
    }

    fun stop() {
        save()
        for (w in web.values) w.destroy()
        web.clear(); surfaces = HashMap()
    }

    fun pause() { save(); web.values.forEach { it.pause() } }
    fun resume() { web.values.forEach { it.resume() } }

    private fun save() { prefs.edit().putString("layout", desktop.toJson().toString()).apply(); savedRevision = desktop.revision }

    private fun ensureWeb(w: DesktopWindow): WebWindow = web.getOrPut(w.id) {
        WebWindow(activity, w.id, w.kind, w.url) { ww ->
            desktop.find(ww.id)?.let { m ->
                val t = ww.title.ifBlank { m.title }
                if (m.title != t || m.url != ww.url) { m.title = t; m.url = ww.url; desktop.touch() }
            }
            onChanged?.invoke()
        }.also { ww -> surfaces[w.id]?.let { ww.attach(it) } }
    }

    /** GL thread: the renderer made (or dropped) the texture a window renders into. */
    fun onSurface(id: Int, surface: Surface?) {
        activity.runOnUiThread {
            val next = HashMap(surfaces)
            if (surface == null) { next.remove(id); web[id]?.detachSurface() } else { next[id] = surface; web[id]?.attach(surface) }
            surfaces = next
        }
    }

    // ---- actions from the phone UI
    fun open(app: HomeApp) = open(app.title, app.url, app.kind)
    fun open(title: String, url: String, kind: String = "web"): Boolean {
        val w = desktop.add(title, url, kind, headYaw()) ?: return false
        if (w.follow) w.yaw = 0.0
        ensureWeb(w); cursors[w.id] = doubleArrayOf(0.5, 0.5); changed(); return true
    }
    fun close(id: Int) { web.remove(id)?.destroy(); bars.remove(id); cursors.remove(id); desktop.close(id); changed() }
    fun focus(id: Int) { desktop.focus(id); changed() }
    fun minimize(id: Int) { desktop.minimize(id); changed() }
    fun toggleMaximize(id: Int) { desktop.toggleMaximize(id); changed() }
    fun toggleFullscreen(id: Int) { desktop.toggleFullscreen(id); changed() }
    fun toggleFollow(id: Int) { desktop.toggleFollow(id); changed() }
    fun recenter() { Tracking.recenter(); desktop.gather(0.0); changed() }
    fun gather() { desktop.gather(headYaw()); changed() }
    fun navigate(text: String) { val w = desktop.focused ?: return; val u = HomeApps.toUrl(text); w.url = u; web[w.id]?.load(u); changed() }
    fun back() { desktop.focused?.let { web[it.id]?.back() } }
    fun reload() { desktop.focused?.let { web[it.id]?.reload() } }
    fun type(text: String, enter: Boolean) { desktop.focused?.let { web[it.id]?.typeText(text, enter) } }
    fun focusedWeb(): WebWindow? = desktop.focused?.let { web[it.id] }
    fun resetLayout() { for (id in desktop.windows.map { it.id }) close(id); changed() }

    private fun changed() { onChanged?.invoke() }

    /** One line per window: id;title;follow;minimized;maximized;fullscreen;focused;yaw deg;pitch deg;width;distance. */
    fun debugState(): String = desktop.windows.joinToString("|") { w ->
        String.format(java.util.Locale.US, "%d;%s;%b;%b;%b;%b;%b;%.1f;%.1f;%.2f;%.2f", w.id, w.title.replace(';', ' ').replace('|', ' '), w.follow, w.minimized, w.maximized,
            desktop.fullscreenId == w.id, desktop.focusedId == w.id, Math.toDegrees(desktop.worldYaw(w)), Math.toDegrees(w.shownPitch()), w.shownWidth(), w.shownDistance())
    }

    // ---- touchpad (phone) input. Distances are fractions of the pad's width.
    fun padMove(dx: Double, dy: Double, moveMode: Boolean) {
        padTouchNs = System.nanoTime()
        val w = desktop.focused ?: return
        if (moveMode) { desktop.move(w.id, -dx * 1.2, -dy * 1.2); return }
        val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        c[0] = (c[0] + dx * 0.9).coerceIn(0.0, 1.0); c[1] = (c[1] + dy * 0.9 * w.aspect).coerceIn(0.0, 1.0)
        if (pressed) web[w.id]?.touch(MotionEvent.ACTION_MOVE, c[0], c[1])
    }
    fun padTap() { padTouchNs = System.nanoTime(); val w = desktop.focused ?: return; val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }; web[w.id]?.tap(c[0], c[1]) }
    fun padPress(down: Boolean) {
        val w = desktop.focused ?: return; val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        if (down == pressed) return
        pressed = down; web[w.id]?.touch(if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, c[0], c[1])
    }
    fun padScroll(dx: Double, dy: Double, moveMode: Boolean) {
        padTouchNs = System.nanoTime()
        val w = desktop.focused ?: return
        if (moveMode) { desktop.push(w.id, Math.exp(dy * 1.5)); return }
        val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        web[w.id]?.scroll(c[0], c[1], (-dx * 1400).toFloat(), (-dy * 1400).toFloat())
    }
    fun padPinch(factor: Double) { padTouchNs = System.nanoTime(); desktop.focused?.let { desktop.resize(it.id, factor) } }

    private fun headForward(head: HeadTracker.State) = Q.rotate(head.pose.q, doubleArrayOf(0.0, 0.0, -1.0))
    private fun headYaw(): Double { val h = Tracking.head.predict(System.nanoTime()); return if (h.mode == HeadTracker.Mode.NONE) renderers.firstOrNull()?.previewYaw ?: 0.0 else Geometry.yawOf(headForward(h)) }

    /** Once per display frame: follow anchor, gaze focus, hand input, and the frame for the renderer. */
    fun tick(nowNs: Long) {
        val dt = if (lastTickNs == 0L) 0.016 else ((nowNs - lastTickNs) * 1e-9).coerceIn(0.0, 0.1)
        lastTickNs = nowNs
        val head = Tracking.head.predict(nowNs)
        val tracked = head.mode != HeadTracker.Mode.NONE
        val forward = if (tracked) headForward(head) else Geometry.direction(renderers.firstOrNull()?.previewYaw ?: 0.0, renderers.firstOrNull()?.previewPitch ?: 0.0)
        val positionOk = usePosition && head.mode == HeadTracker.Mode.SIX_DOF
        // Without trusted position the windows are placed around the viewer; with it, around the world origin.
        val origin = if (positionOk) head.pose.p else ZERO
        val anchor = ZERO
        desktop.followAnchor.update(Geometry.yawOf(forward), dt)

        val full = desktop.fullscreenId >= 0
        // Gaze focus: the window looked at for a moment becomes the focused one, unless the pad is in use.
        if (gazeFocus && !full && nowNs - padTouchNs > 700_000_000L && !handDown) {
            val g = desktop.hit(origin, forward, anchor)
            val id = g?.window?.id ?: -1
            if (id != gazeCandidate) { gazeCandidate = id; gazeSinceNs = nowNs }
            else if (id >= 0 && id != desktop.focusedId && nowNs - gazeSinceNs > 450_000_000L) { desktop.focus(id); changed() }
        }
        if (handGestures && !full) hands(nowNs, head, origin, anchor, tracked) else if (handDown) releaseHand()

        if (desktop.revision != savedRevision && !handDown && !pressed) save()
        if (nowNs - lastPerfNs > 5_000_000_000L) {
            lastPerfNs = nowNs
            renderers.firstOrNull()?.let { r -> if (r.frames > 0) Tracking.log(String.format(java.util.Locale.US, "desktop: %.0f fps, %.1f ms per frame on the CPU, %d late of %d frames, %d windows, %dx%d", r.fps, r.avgCpuMs, r.lateFrames, r.frames, desktop.visible.size, r.width, r.height)) }
        }
        if (desktop.revision != lastUiRevision) { lastUiRevision = desktop.revision; changed() }
        publish(nowNs, anchor, positionOk, head)
    }
    private var lastUiRevision = -1L
    private var lastPerfNs = 0L

    private fun hands(nowNs: Long, head: HeadTracker.State, origin: DoubleArray, anchor: DoubleArray, tracked: Boolean) {
        val hand = if (tracked) Tracking.hands.filter { nowNs - it.timestampNs < 300_000_000L }.maxByOrNull { if (it.isRight) 1 else 0 } else null
        if (hand == null) { lastHandRay = null; if (handDown) releaseHand(); return }
        // Hand positions are in the app world. Without trusted position the view sits at the origin,
        // so the hand ray is taken relative to the head instead.
        val o = if (origin === ZERO) doubleArrayOf(hand.aimOrigin[0] - head.pose.p[0], hand.aimOrigin[1] - head.pose.p[1], hand.aimOrigin[2] - head.pose.p[2]) else hand.aimOrigin
        lastHandRay = o to hand.aimDir
        val hit = desktop.hit(o, hand.aimDir, anchor)
        if (handDragWindow >= 0) {   // a window is being carried by its title bar
            if (hand.pinching) { desktop.aim(handDragWindow, Geometry.yawOf(hand.aimDir), Geometry.pitchOf(hand.aimDir) - 0.0); handMoved = true } else releaseHand()
            return
        }
        if (hit != null && !hit.onBar) cursors[hit.window.id] = doubleArrayOf(hit.u.coerceIn(0.0, 1.0), hit.v.coerceIn(0.0, 1.0))
        if (hand.pinching && !handDown) {
            handDown = true; handDownHit = hit; handMoved = false
            if (hit != null) {
                desktop.focus(hit.window.id)
                if (hit.onBar && hit.barButton == null) handDragWindow = hit.window.id
                else if (!hit.onBar) web[hit.window.id]?.touch(MotionEvent.ACTION_DOWN, hit.u, hit.v)
            }
        } else if (hand.pinching && handDown) {
            val d = handDownHit
            if (d != null && !d.onBar && hit != null && hit.window.id == d.window.id) web[d.window.id]?.touch(MotionEvent.ACTION_MOVE, hit.u, hit.v)
        } else if (!hand.pinching && handDown) {
            val d = handDownHit
            if (d != null && d.onBar && d.barButton != null && hit != null && hit.window.id == d.window.id && hit.barButton == d.barButton) barAction(d.window.id, d.barButton)
            else if (d != null && !d.onBar) { val c = cursors[d.window.id] ?: doubleArrayOf(d.u, d.v); web[d.window.id]?.touch(MotionEvent.ACTION_UP, c[0], c[1]) }
            releaseHand()
        }
    }

    private fun releaseHand() {
        val d = handDownHit
        if (handDown && d != null && !d.onBar && handDragWindow < 0) web[d.window.id]?.touch(MotionEvent.ACTION_CANCEL, d.u, d.v)
        handDown = false; handDragWindow = -1; handDownHit = null
    }

    fun barAction(id: Int, b: BarButton) {
        when (b) { BarButton.FOLLOW -> toggleFollow(id); BarButton.MINIMIZE -> minimize(id); BarButton.MAXIMIZE -> toggleMaximize(id); BarButton.CLOSE -> close(id) }
    }

    private fun publish(nowNs: Long, anchor: DoubleArray, positionOk: Boolean, head: HeadTracker.State) {
        val full = desktop.fullscreenId
        val list = ArrayList<RenderWindow>()
        for (w in desktop.visible) {
            val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
            val focused = w.id == desktop.focusedId
            if (w.id == full) {
                val tanH = Tracking.displayTanH; val tanV = Tracking.displayTanV
                val d = 2.0; val width = minOf(2 * d * tanH, 2 * d * tanV * w.aspect) * 0.98
                list += RenderWindow(w.id, 0.0, 0.0, d, width, width / w.aspect, 0.0, true, c[0], c[1], true, pressed, null, 0, true)
            } else {
                val key = "${w.title}|${w.follow}|${w.maximized}|$focused"
                val bar = bars[w.id]
                if (bar == null || bar.second != key) { bars[w.id] = drawBar(w, focused) to key; barVersions[w.id] = (barVersions[w.id] ?: 0) + 1 }
                list += RenderWindow(w.id, desktop.worldYaw(w), w.shownPitch(), w.shownDistance(), w.shownWidth(), w.shownHeight(), w.shownBarHeight(),
                    focused, c[0], c[1], focused, pressed || handDown, bars[w.id]!!.first, barVersions[w.id]!!, false)
            }
        }
        val showHome = desktop.visible.isEmpty()
        if (showHome) {
            val minute = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            if (homeBitmap == null || minute != homeMinute) { homeBitmap = drawHome(minute); homeMinute = minute; homeVersion++ }
        }
        val frame = RenderFrame(list, anchor, if (showHome) homeBitmap else null, homeVersion, desktop.followAnchor.yaw, positionOk, if (dimUnfocused) 0.62f else 1f)
        for (r in renderers) r.frame = frame
        statusLine = when (head.mode) {
            HeadTracker.Mode.NONE -> "No glasses: preview"
            HeadTracker.Mode.ORIENTATION_ONLY -> "Rotation tracking"
            HeadTracker.Mode.SIX_DOF -> if (positionOk) "Position tracking" else "Rotation tracking"
        }
    }

    // ---- textures drawn with the 2D canvas
    private fun drawBar(w: DesktopWindow, focused: Boolean): Bitmap {
        val bw = 1024; val bh = (bw * w.shownBarHeight() / w.shownWidth()).toInt().coerceIn(40, 120)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (focused) 0xF01B2530.toInt() else 0xD0141A22.toInt(); c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        val slot = bw * Desktop.BUTTON_FRACTION.toFloat(); val cy = bh / 2f; val r = bh * 0.2f
        p.color = Color.WHITE; p.textSize = bh * 0.46f; p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val maxText = bw - slot * 4.4f
        val title = android.text.TextUtils.ellipsize(w.title.ifBlank { "Window" }, android.text.TextPaint(p), maxText - bh * 0.5f, android.text.TextUtils.TruncateAt.END).toString()
        c.drawText(title, bh * 0.5f, cy + p.textSize * 0.35f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = bh * 0.06f; p.strokeCap = Paint.Cap.ROUND
        fun cx(i: Int) = bw - slot * (i + 0.5f)
        // close
        p.color = 0xFFFF8A80.toInt(); c.drawLine(cx(0) - r, cy - r, cx(0) + r, cy + r, p); c.drawLine(cx(0) - r, cy + r, cx(0) + r, cy - r, p)
        p.color = Color.WHITE
        // maximize / restore
        if (w.maximized) { c.drawRect(cx(1) - r * 0.6f, cy - r * 0.6f, cx(1) + r * 0.6f, cy + r * 0.6f, p) } else c.drawRect(cx(1) - r, cy - r, cx(1) + r, cy + r, p)
        // minimize
        c.drawLine(cx(2) - r, cy + r, cx(2) + r, cy + r, p)
        // follow head (filled dot in a ring) or locked in space (pin)
        if (w.follow) { p.color = 0xFF7BE6FF.toInt(); c.drawCircle(cx(3), cy, r, p); p.style = Paint.Style.FILL; c.drawCircle(cx(3), cy, r * 0.45f, p) }
        else { c.drawCircle(cx(3), cy - r * 0.35f, r * 0.55f, p); c.drawLine(cx(3), cy + r * 0.2f, cx(3), cy + r * 1.1f, p) }
        return b
    }

    private fun drawHome(minute: String): Bitmap {
        val bw = 1280; val bh = 640
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xCC0E141B.toInt(); c.drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), 56f, 56f, p)
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); p.textSize = 150f; c.drawText(minute, bw / 2f, 200f, p)
        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL); p.textSize = 34f; p.color = 0xFF9FB3C8.toInt()
        c.drawText(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date()), bw / 2f, 262f, p)
        val apps = HomeApps.all; val tile = 230f; val gap = 40f; val total = apps.size * tile + (apps.size - 1) * gap; var x = (bw - total) / 2f
        for (a in apps) {
            p.color = 0xFF18222D.toInt(); c.drawRoundRect(RectF(x, 330f, x + tile, 530f), 40f, 40f, p)
            p.color = a.color; c.drawCircle(x + tile / 2, 405f, 44f, p)
            p.color = Color.WHITE; p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); p.textSize = 40f
            c.drawText(a.title.take(1), x + tile / 2, 419f, p)
            p.textSize = 30f; c.drawText(a.title, x + tile / 2, 498f, p)
            x += tile + gap
        }
        p.color = 0xFF6F8296.toInt(); p.textSize = 26f; p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText("Open an app from your phone", bw / 2f, 596f, p)
        return b
    }

    companion object { private val ZERO = doubleArrayOf(0.0, 0.0, 0.0) }
}
