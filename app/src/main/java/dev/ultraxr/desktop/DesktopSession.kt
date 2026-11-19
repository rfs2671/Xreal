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
    /** Pages in their dark theme (black is see-through on the glasses). Applies to windows opened afterwards. */
    var darkPages: Boolean get() = prefs.getBoolean("darkPages", true); set(v) { prefs.edit().putBoolean("darkPages", v).apply() }
    /** Resting the gaze on a dock app or a title-bar button presses it: no phone and no hands needed. */
    var lookToPress: Boolean get() = prefs.getBoolean("lookToPress", true); set(v) { prefs.edit().putBoolean("lookToPress", v).apply() }

    /** Real depth: the glasses' half-width 3D mode, when the link to them holds. Falls back to 2D by itself. */
    var depth3d: Boolean get() = prefs.getBoolean("depth3d", true); set(v) { prefs.edit().putBoolean("depth3d", v).apply() }
    var swapEyes: Boolean get() = prefs.getBoolean("swapEyes", false); set(v) { prefs.edit().putBoolean("swapEyes", v).apply() }
    /** Test builds: draw two eye views on the attached display without glasses, to check the picture. */
    var forceStereo = false

    private val regular: Typeface = try { Typeface.createFromAsset(activity.assets, "fonts/AtkinsonHyperlegible-Regular.ttf") } catch (_: Exception) { Typeface.SANS_SERIF }
    private val bold: Typeface = try { Typeface.createFromAsset(activity.assets, "fonts/AtkinsonHyperlegible-Bold.ttf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }
    private val dwell = Dwell()
    private val homeDwell = Dwell(holdNs = 1_700_000_000L, armNs = 500_000_000L)

    // ---- the home view: what it offers, and which icon is looked at
    /** WebXR games that are not known to be broken, then the web apps. */
    val homeItems: List<HomeItem> = run {
        val games = try { dev.ultraxr.core.GameLibrary.parse(activity.assets.open("webxr_pages.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { emptyList() }
        val tints = intArrayOf(0xFF5B8DEF.toInt(), 0xFF34C7A5.toInt(), 0xFFF2A65A.toInt(), 0xFFB57BEE.toInt(), 0xFFEF6F8E.toInt(), 0xFF5BC0EB.toInt())
        (games.filter { it.state != dev.ultraxr.core.WebGame.State.BLOCKED }.mapIndexed { i, g ->
            HomeItem(g.name.substringBefore(" (").trim(), HomeItem.Kind.GAME, g.url, tints[i % tints.size], enter = g.enter, verified = g.state == dev.ultraxr.core.WebGame.State.VERIFIED)
        } + HomeApps.all.map { HomeItem(it.title, HomeItem.Kind.WEB, it.url, it.color, it.kind) }).take(HomeGrid.MAX)
    }
    @Volatile var homeHover = -1; private set
    private var homeDrawnHover = -2
    private var homeProgress = 0.0; private var homeDrawnStep = -1
    private var homePinchWas = false
    /** A game was chosen in the home view: the activity starts it in the XR browser. */
    var onLaunchGame: ((HomeItem) -> Unit)? = null

    /** Opens what the icon stands for. */
    fun activateHome(i: Int) {
        val item = homeItems.getOrNull(i) ?: return
        if (item.kind == HomeItem.Kind.GAME) onLaunchGame?.invoke(item) else (HomeApps.all.firstOrNull { it.url == item.url }?.let { open(it) } ?: open(item.title, item.url, item.webKind))
        onLookPress?.invoke()
    }
    private var dockBitmap: Bitmap? = null
    private var ringYaw = 0.0; private var ringPitch = 0.0; private var ringDistance = 2.0; private var ringProgress = 0.0
    /** Called when something was pressed by looking, so the phone can give a haptic tick. */
    var onLookPress: (() -> Unit)? = null

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
        WebWindow(activity, w.id, w.kind, w.url, darkPages) { ww ->
            desktop.find(ww.id)?.let { m ->
                val t = if (ww.isApp) m.title else ww.title.ifBlank { m.title }
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
    fun open(app: HomeApp): Boolean {
        // the real app when it is installed and the helper that can put it in a window is running; else its site
        val comp = if (app.pkg.isNotEmpty() && dev.ultraxr.shell.AppShell.available) dev.ultraxr.shell.AppShell.componentOf(activity, app.pkg) else null
        return if (comp != null) openApp(app.title, comp) else open(app.title, app.url, app.kind)
    }
    /** Another app in a window: [component] is "package/activity". One window an app: asked for again, it is shown. */
    fun openApp(title: String, component: String): Boolean {
        val address = "app://$component"
        desktop.windows.firstOrNull { it.kind == "app" && it.url == address }?.let { w -> if (w.minimized) desktop.restore(w.id); desktop.focus(w.id); changed(); return true }
        return open(title, address, "app")
    }
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

    /** Focus the next window that is showing (wraps around). Returns false if there is none to move to. */
    fun cycleFocus(): Boolean {
        val vis = desktop.windows.filter { !it.minimized }
        if (vis.size < 2) return false
        val i = vis.indexOfFirst { it.id == desktop.focusedId }
        desktop.focus(vis[(i + 1) % vis.size].id); changed(); return true
    }

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
        // The cursor can leave the page upwards onto the window's title bar, where a tap presses the button under
        // it (close, maximise, minimise, lock). A press that began in the page stays in the page.
        val top = if (pressed || desktop.fullscreenId == w.id) 0.0 else -barSpan(w)
        c[0] = (c[0] + dx * 0.9).coerceIn(0.0, 1.0); c[1] = (c[1] + dy * 0.9 * w.aspect).coerceIn(top, 1.0)
        if (pressed) web[w.id]?.touch(MotionEvent.ACTION_MOVE, c[0], c[1])
    }
    /** The title bar's height as a fraction of the window's: the cursor's v runs from minus this to 1. */
    private fun barSpan(w: DesktopWindow) = w.shownBarHeight() / w.shownHeight()
    fun padTap() {
        padTouchNs = System.nanoTime()
        if (desktop.visible.isEmpty()) { if (homeHover >= 0) activateHome(homeHover); return }
        val w = desktop.focused ?: return; val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        if (c[1] < 0.0) { Desktop.barButtonAt(c[0])?.let { barAction(w.id, it); onLookPress?.invoke() }; return }
        web[w.id]?.tap(c[0], c[1])
    }

    /** The main menu: every window is put away (not closed) and the home's icons show. */
    fun showHome() { for (w in desktop.windows) if (!w.minimized) desktop.minimize(w.id); changed() }

    // ---- a remote's or a keyboard's arrow keys
    private var keyHover = -1; private var keyNs = 0L
    private val KEY_HOLD_NS = 8_000_000_000L
    /** One step of the arrow keys: across the home's icons when the home shows, else the cursor of the focused window. */
    fun keyMove(dx: Int, dy: Int) {
        val now = System.nanoTime()
        if (desktop.visible.isEmpty()) {
            val n = homeItems.size; if (n == 0) return
            val cs = HomeGrid.centres(n)
            val from = if (keyHover in 0 until n && now - keyNs < KEY_HOLD_NS) keyHover else if (homeHover >= 0) homeHover else -1
            if (from < 0) { keyHover = 0; keyNs = now; return }
            // the nearest icon in that direction: the step along it counts once, the step across it three times
            var best = -1; var bestD = Float.MAX_VALUE
            for (i in 0 until n) {
                if (i == from) continue
                val ax = cs[i][0] - cs[from][0]; val ay = cs[i][1] - cs[from][1]
                val along = ax * dx + ay * dy; val across = Math.abs(ax * dy) + Math.abs(ay * dx)
                if (along <= 1f) continue
                val d = along + across * 3f
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0) keyHover = best else keyHover = from
            keyNs = now
        } else padMove(dx * 0.06, dy * 0.06 / (desktop.focused?.aspect ?: 1.0), false)
    }
    /** The remote's OK key. */
    fun keySelect() {
        if (desktop.visible.isEmpty()) { val now = System.nanoTime(); if (keyHover >= 0 && now - keyNs < KEY_HOLD_NS) { activateHome(keyHover); return } }
        padTap()
    }
    fun padPress(down: Boolean) {
        val w = desktop.focused ?: return; val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        if (down == pressed) return
        if (down && c[1] < 0.0) return      // on the title bar: buttons answer to a tap
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

        // The home view (shown when no window is open): the icon looked at is the one that a pinch, a tap on the
        // phone, or a longer look opens.
        val showHome = desktop.visible.isEmpty()
        var hover = -1
        if (showHome) {
            val yaw = desktop.followAnchor.yaw
            val h = Geometry.planeHit(origin, forward, Geometry.centre(anchor, yaw, 0.0, DesktopRenderer.HOME_DISTANCE), yaw, 0.0)
            if (h != null) hover = HomeGrid.hit(h[0], h[1], homeItems.size) ?: -1
        }
        // an icon chosen with the arrow keys stays chosen for a while, wherever the head points
        if (showHome && keyHover in homeItems.indices && nowNs - keyNs < KEY_HOLD_NS) hover = keyHover
        homeHover = hover
        if (showHome && handGestures) {
            val pinching = Tracking.hands.any { it.pinching }
            if (pinching && !homePinchWas && hover >= 0) activateHome(hover)
            homePinchWas = pinching
        } else homePinchWas = false

        // Look-to-press: the home's icons, the dock's apps and the title-bar buttons.
        var looked: String? = null
        if (lookToPress && !full && nowNs - padTouchNs > 700_000_000L && !handDown) {
            val tile = if (showHome) null else Rail.dockTile(origin, forward, anchor, desktop.followAnchor.yaw, HomeApps.all.size)
            // an icon opens by looking only when there is a head to look with (not in the phone preview) and it was not chosen with the keys
            if (showHome && hover >= 0 && tracked && !(keyHover == hover && nowNs - keyNs < KEY_HOLD_NS)) { looked = "home:$hover"; ringDistance = DesktopRenderer.HOME_DISTANCE }
            else if (showHome) { }
            else if (tile != null) { looked = "dock:$tile"; ringDistance = Rail.DOCK_DISTANCE }
            else {
                val g = desktop.hit(origin, forward, anchor)
                // only the focused window shows its buttons, so only those can be pressed
                if (g != null && g.barButton != null && g.window.id == desktop.focusedId) { looked = "bar:${g.window.id}:${g.barButton.name}"; ringDistance = g.distance }
            }
        }
        // An icon of the home opens on a pinch or a tap at once; by looking alone it takes two seconds, so that
        // glancing across the home does not start things.
        val onHome = looked?.startsWith("home:") == true
        val fire = if (onHome) { dwell.update(nowNs, null); homeDwell.update(nowNs, looked) } else { homeDwell.update(nowNs, null); dwell.update(nowNs, looked) }
        ringYaw = Geometry.yawOf(forward); ringPitch = Geometry.pitchOf(forward); ringProgress = if (looked == null || onHome) 0.0 else dwell.progress
        homeProgress = if (onHome) homeDwell.progress else 0.0
        if (fire != null) {
            if (fire.startsWith("home:")) activateHome(fire.substring(5).toInt())
            else if (fire.startsWith("dock:")) { open(HomeApps.all[fire.substring(5).toInt()]); onLookPress?.invoke() }
            else { val p = fire.split(":"); barAction(p[1].toInt(), BarButton.valueOf(p[2])); onLookPress?.invoke() }
        }

        if (desktop.revision != savedRevision && !handDown && !pressed) save()
        if (nowNs - lastPerfNs > 5_000_000_000L) {
            lastPerfNs = nowNs
            renderers.firstOrNull()?.let { r -> if (r.frames > 0) Tracking.log(String.format(java.util.Locale.US, "desktop: %.0f fps, %.1f ms per frame on the CPU, %d late of %d frames, %d windows, %dx%d", r.fps, r.avgCpuMs, r.lateFrames, r.frames, desktop.visible.size, r.width, r.height)) }
        }
        if (desktop.revision != lastUiRevision) { lastUiRevision = desktop.revision; changed() }
        publish(nowNs, anchor, positionOk, head)
    }
    private var lastUiRevision = -1L
    private var stereoNote = ""
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
            if (d != null && d.onBar && d.barButton != null && hit != null && hit.window.id == d.window.id && hit.barButton == d.barButton && d.window.id == desktop.focusedId) barAction(d.window.id, d.barButton)
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
            val step = (homeProgress * 16).toInt()
            if (homeBitmap == null || minute != homeMinute || homeHover != homeDrawnHover || step != homeDrawnStep) {
                homeBitmap = drawHome(minute, homeHover, step / 16f); homeMinute = minute; homeDrawnHover = homeHover; homeDrawnStep = step; homeVersion++
            }
        }
        if (dockBitmap == null) dockBitmap = drawDock()
        val f = desktop.focused?.takeIf { !it.minimized && full < 0 }
        val frame = RenderFrame(list, anchor, if (showHome) homeBitmap else null, homeVersion, desktop.followAnchor.yaw, positionOk, if (dimUnfocused) 0.55f else 1f,
            dock = if (full < 0 && !showHome) dockBitmap else null, dockVersion = 1, dockYaw = desktop.followAnchor.yaw,
            railYaw = if (f != null) desktop.worldYaw(f) else Double.NaN, railHalf = if (f != null) Math.atan(f.shownWidth() / 2 / f.shownDistance()) else 0.0, showRail = full < 0 && !showHome,
            dwellYaw = ringYaw, dwellPitch = ringPitch, dwellDistance = ringDistance, dwellProgress = ringProgress)
        val sw = Tracking.link?.stereo
        val three = forceStereo || sw?.state == dev.ultraxr.glasses.StereoSwitch.State.ACTIVE
        val swap = swapEyes; val ipd = Tracking.settings.ipdMm / 1000.0
        for (r in renderers) { r.stereo = three; r.swapEyes = swap; r.ipdM = ipd; r.frame = frame }
        stereoNote = when {
            three -> " · 3D"
            !depth3d || sw == null -> ""
            sw.state == dev.ultraxr.glasses.StereoSwitch.State.STARTING || sw.state == dev.ultraxr.glasses.StereoSwitch.State.RESTING -> " · starting 3D (try ${sw.attempts} of 3)"
            sw.state == dev.ultraxr.glasses.StereoSwitch.State.GAVE_UP -> " · 2D (3D failed: ${sw.lastReason})"
            else -> ""
        }
        statusLine = (when (head.mode) {
            HeadTracker.Mode.NONE -> "No glasses: preview"
            HeadTracker.Mode.ORIENTATION_ONLY -> "Rotation tracking"
            HeadTracker.Mode.SIX_DOF -> if (positionOk) "Position tracking" else "Rotation tracking"
        }) + stereoNote
    }

    // ---- textures drawn with the 2D canvas. Everything is light on a transparent ground: on the
    // glasses black is see-through, so there are no panels behind text.
    private fun drawBar(w: DesktopWindow, focused: Boolean): Bitmap {
        val bw = 1024; val bh = (bw * w.shownBarHeight() / w.shownWidth()).toInt().coerceIn(40, 120)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val ink = if (focused) Ink.WHITE_INT else Ink.SLATE_INT
        val slot = bw * Desktop.BUTTON_FRACTION.toFloat(); val cy = bh * 0.46f; val r = bh * 0.19f
        p.color = ink; p.textSize = bh * 0.5f; p.typeface = if (focused) bold else regular
        val maxText = bw - slot * 4.6f
        val title = android.text.TextUtils.ellipsize(w.title.ifBlank { "Window" }, android.text.TextPaint(p), maxText, android.text.TextUtils.TruncateAt.END).toString()
        c.drawText(title, bh * 0.12f, cy + p.textSize * 0.34f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = bh * 0.055f; p.strokeCap = Paint.Cap.ROUND
        fun cx(i: Int) = bw - slot * (i + 0.5f)
        if (focused) {   // buttons only on the focused window: fewer marks, and nothing to press by accident
            p.color = Ink.CORAL_INT; c.drawLine(cx(0) - r, cy - r, cx(0) + r, cy + r, p); c.drawLine(cx(0) - r, cy + r, cx(0) + r, cy - r, p)
            p.color = ink
            if (w.maximized) c.drawRect(cx(1) - r * 0.6f, cy - r * 0.6f, cx(1) + r * 0.6f, cy + r * 0.6f, p) else c.drawRect(cx(1) - r, cy - r, cx(1) + r, cy + r, p)
            c.drawLine(cx(2) - r, cy + r, cx(2) + r, cy + r, p)
            if (w.follow) { p.color = Ink.AMBER_INT; c.drawCircle(cx(3), cy, r, p); p.style = Paint.Style.FILL; c.drawCircle(cx(3), cy, r * 0.42f, p) }
            else { c.drawCircle(cx(3), cy - r * 0.35f, r * 0.55f, p); c.drawLine(cx(3), cy + r * 0.2f, cx(3), cy + r * 1.1f, p) }
        } else if (w.follow) { p.color = Ink.SLATE_INT; c.drawCircle(cx(0), cy, r * 0.7f, p) }
        return b
    }

    /** The clock that stands on the rail when no window is open. */
    /**
     * The home view: round icons in staggered rows with their names, a small glass capsule above with the time and
     * what the glasses are doing, and one below saying how to choose. Light on black: black is see-through.
     */
    private fun drawHome(minute: String, hover: Int, progress: Float): Bitmap {
        val bw = HomeGrid.PX_W; val bh = HomeGrid.PX_H
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL); val plain = Typeface.create("sans-serif", Typeface.NORMAL)

        // A capsule of "glass": a faint milky fill, brighter towards the top, and a thin bright rim.
        fun capsule(cx: Float, cy: Float, w: Float, h: Float) {
            val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            p.style = Paint.Style.FILL
            p.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom, 0x30FFFFFF, 0x14FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(r, h / 2, h / 2, p)
            p.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom, 0x8CFFFFFF.toInt(), 0x24FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            c.drawRoundRect(r, h / 2, h / 2, p)
            p.shader = null; p.style = Paint.Style.FILL
        }

        // Above: the time, and in a quieter weight what the glasses are doing.
        val mode = Tracking.head.predict(System.nanoTime()).mode
        val state = when (mode) { HeadTracker.Mode.SIX_DOF -> "Room tracking on"; HeadTracker.Mode.ORIENTATION_ONLY -> "Turning only"; else -> "No tracking" }
        p.typeface = medium; p.textSize = 34f; val tw = p.measureText(minute)
        p.typeface = plain; p.textSize = 28f; val sw = p.measureText(state)
        val capW = tw + sw + 96f
        capsule(bw / 2f, 46f, capW, 60f)
        p.textAlign = Paint.Align.LEFT
        p.typeface = medium; p.textSize = 34f; p.color = 0xFFFFFFFF.toInt(); c.drawText(minute, bw / 2f - capW / 2 + 32f, 58f, p)
        p.typeface = plain; p.textSize = 28f; p.color = 0xC8FFFFFF.toInt(); c.drawText(state, bw / 2f - capW / 2 + 32f + tw + 32f, 56f, p)

        // The icons.
        val centres = HomeGrid.centres(homeItems.size)
        p.textAlign = Paint.Align.CENTER
        for ((i, item) in homeItems.withIndex()) {
            val cx = centres[i][0]; val cy = centres[i][1]; val on = i == hover
            val r = HomeGrid.ICON_R * (if (on) 1.12f else 1f)
            if (on) {      // the icon looked at lifts towards the viewer: larger, with a soft bright halo
                p.shader = android.graphics.RadialGradient(cx, cy, r * 1.55f, intArrayOf(0x55FFFFFF, 0x22FFFFFF, 0x00FFFFFF), floatArrayOf(0.62f, 0.8f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, r * 1.55f, p); p.shader = null
            }
            HomeIcon.draw(c, p, cx, cy, r, item.color, item.kind == HomeItem.Kind.GAME, item.title.take(1), item.verified, on)
            p.textAlign = Paint.Align.CENTER
            // looking alone: a thin arc closes around the icon, and it opens when the circle is whole
            if (on && progress > 0f) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFFFFFFFF.toInt()
                val rr = r + 13f
                c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), -90f, 360f * progress, false, p)
                p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
            }
            // the name, shortened with an ellipsis if it would run into its neighbour
            p.typeface = if (on) medium else plain; p.textSize = 31f; p.color = if (on) 0xFFFFFFFF.toInt() else 0xE0FFFFFF.toInt()
            val name = android.text.TextUtils.ellipsize(item.title, android.text.TextPaint(p), HomeGrid.PITCH_X - 22f, android.text.TextUtils.TruncateAt.END).toString()
            c.drawText(name, cx, cy + HomeGrid.ICON_R + 46f, p)
        }

        // Below: how to choose.
        val hint = if (hover >= 0) "Pinch, tap the phone, or keep looking to open " + homeItems[hover].title else "Look at an app, then pinch or tap the phone"
        p.typeface = plain; p.textSize = 27f
        capsule(bw / 2f, bh - 40f, p.measureText(hint) + 64f, 54f)
        p.color = 0xD2FFFFFF.toInt(); p.textAlign = Paint.Align.CENTER; c.drawText(hint, bw / 2f, bh - 31f, p)
        return b
    }

    /** The dock under the rail: one outlined mark and a name per app. Looked at, or opened from the phone. */
    private fun drawDock(): Bitmap {
        val bw = 1100; val bh = 220
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.textAlign = Paint.Align.CENTER
        val apps = HomeApps.all; val tile = bw / apps.size.toFloat()
        // One glass bar with the same round icons as the home view. Where each app sits is unchanged (Rail.dockTile).
        val bar = RectF(3f, 3f, bw - 3f, bh - 3f)
        p.style = Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, bar.top, 0f, bar.bottom, 0x2CFFFFFF, 0x12FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(bar, 70f, 70f, p)
        p.shader = android.graphics.LinearGradient(0f, bar.top, 0f, bar.bottom, 0x8CFFFFFF.toInt(), 0x24FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; c.drawRoundRect(bar, 70f, 70f, p); p.shader = null; p.style = Paint.Style.FILL
        for ((i, a) in apps.withIndex()) {
            val x = tile * (i + 0.5f)
            HomeIcon.draw(c, p, x, 84f, 56f, a.color, false, a.title.take(1), false, false)
            p.textAlign = Paint.Align.CENTER; p.typeface = regular; p.textSize = 34f; p.color = Ink.WHITE_INT
            c.drawText(a.title, x, 190f, p)
        }
        return b
    }

    companion object { private val ZERO = doubleArrayOf(0.0, 0.0, 0.0) }
}
