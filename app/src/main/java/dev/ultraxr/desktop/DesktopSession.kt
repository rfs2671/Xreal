package dev.ultraxr.desktop

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.InputDevice
import android.view.KeyEvent
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
    // The one focus is desktop.focus. Each pointer remembers what it was last over, so that it moves the focus when it
    // comes over something new and not while it rests there (the d-pad may have taken the focus elsewhere since).
    private var handTarget: Focus? = null; private var handPinchWas = false; private var handTile = -1
    /** A pinch began on a corner of a page, at this place of the window's plane: a drag from here sizes the window. */
    private var handCorner: DoubleArray? = null; private var handSizeWindow = -1
    /** The window being moved was taken up by a hand's pinch on "move": it follows the hand's ray. */
    private var moveByHand = false
    private var gazeDone = false; private var gazeHover = -1
    private val handles = HashMap<Int, Pair<Bitmap, String>>(); private val handleVersions = HashMap<Int, Long>()
    private var dockLit = -2; private var dockVersion = 0L
    private var loggedFocus: Focus? = null
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
    private val firstPage: List<HomeItem> = run {
        val games = try { dev.ultraxr.core.GameLibrary.parse(activity.assets.open("webxr_pages.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { emptyList() }
        val tints = intArrayOf(0xFF5B8DEF.toInt(), 0xFF34C7A5.toInt(), 0xFFF2A65A.toInt(), 0xFFB57BEE.toInt(), 0xFFEF6F8E.toInt(), 0xFF5BC0EB.toInt())
        (games.filter { it.state != dev.ultraxr.core.WebGame.State.BLOCKED }.mapIndexed { i, g ->
            HomeItem(g.name.substringBefore(" (").trim(), HomeItem.Kind.GAME, g.url, tints[i % tints.size], enter = g.enter, verified = g.state == dev.ultraxr.core.WebGame.State.VERIFIED)
        } + HomeApps.all.map { HomeItem(it.title, HomeItem.Kind.WEB, it.url, it.color, it.kind) }).take(HomeGrid.MAX - 2) +
            HomeItem("All apps", HomeItem.Kind.NAV, "nav:apps", 0xFF607D8B.toInt()) + HomeItem("System", HomeItem.Kind.NAV, "nav:system", 0xFF455A64.toInt())
    }
    /** What the home view shows now: its first page (games, the web apps, "All apps"), or a page of the phone's apps. */
    @Volatile var homeItems: List<HomeItem> = firstPage; private set
    private var homePage = 0
    private var installedApps: List<dev.ultraxr.shell.AppShell.App>? = null
    private val appIcons = HashMap<String, Bitmap?>()
    private fun appIcon(pkg: String): Bitmap? = appIcons.getOrPut(pkg) {
        try {
            val d = activity.packageManager.getApplicationIcon(pkg); val b = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 144, 144); d.draw(Canvas(b)); b
        } catch (_: Throwable) { null }
    }
    /** The system page: what otherwise needs the phone's screen. Each tile says what it is now and does the other. */
    private fun systemPage(): List<HomeItem> = listOf(
        HomeItem(if (Tracking.handsOn) "Hands: on" else "Hands: off", HomeItem.Kind.NAV, "sys:hands", if (Tracking.handsOn) 0xFF2E7D32.toInt() else 0xFF8D6E63.toInt()),
        HomeItem("Recentre", HomeItem.Kind.NAV, "sys:recentre", 0xFF1E88E5.toInt()),
        HomeItem("Gather windows", HomeItem.Kind.NAV, "sys:gather", 0xFF8E24AA.toInt()),
        HomeItem("Close all", HomeItem.Kind.NAV, "sys:closeall", 0xFFE53935.toInt()),
        HomeItem(if (dev.ultraxr.shell.AppShell.available) "Apps: ready" else "Apps: helper off", HomeItem.Kind.NAV, "sys:apps", if (dev.ultraxr.shell.AppShell.available) 0xFF2E7D32.toInt() else 0xFF8D6E63.toInt()),
        HomeItem("Home", HomeItem.Kind.NAV, "nav:home", 0xFF607D8B.toInt()))
    private fun system(what: String) {
        when (what) {
            "sys:hands" -> Tracking.setHandTracking(!Tracking.handsOn)
            "sys:recentre" -> recenter()
            "sys:gather" -> gather()
            "sys:closeall" -> for (w in desktop.windows.toList()) close(w.id)
        }
        showHomePage(-1)
    }

    /** Page 0 is the first page; 1 and up are the phone's apps; -1 is the system page. */
    fun showHomePage(page: Int) {
        val per = HomeGrid.MAX - 2
        val apps = installedApps ?: dev.ultraxr.shell.AppShell.installed(activity).also { installedApps = it }
        val pages = maxOf(1, (apps.size + per - 1) / per)
        homePage = page.coerceIn(0, pages)
        val tints = intArrayOf(0xFF5B8DEF.toInt(), 0xFF34C7A5.toInt(), 0xFFF2A65A.toInt(), 0xFFB57BEE.toInt(), 0xFFEF6F8E.toInt(), 0xFF5BC0EB.toInt())
        homeItems = if (page < 0) systemPage() else if (homePage == 0) firstPage else
            apps.drop((homePage - 1) * per).take(per).mapIndexed { i, a -> HomeItem(a.label, HomeItem.Kind.APP, "app://" + a.component, tints[i % tints.size], icon = appIcon(a.pkg)) } +
                HomeItem("Home", HomeItem.Kind.NAV, "nav:home", 0xFF607D8B.toInt()) +
                (if (homePage < pages) listOf(HomeItem("More", HomeItem.Kind.NAV, "nav:next", 0xFF607D8B.toInt())) else emptyList())
        homeDrawnHover = -2; changed()
    }
    @Volatile var homeHover = -1; private set
    private var homeDrawnHover = -2
    private var homeProgress = 0.0; private var homeDrawnStep = -1
    private var homePinchWas = false
    /** A game was chosen in the home view: the activity starts it in the XR browser. */
    var onLaunchGame: ((HomeItem) -> Unit)? = null
    /** An installed immersive app (an OpenXR app, a converted Quest game) was chosen: the activity hands it the glasses. */
    var onLaunchNative: ((String) -> Unit)? = null

    /** Opens what the icon stands for. */
    fun activateHome(i: Int) {
        val item = homeItems.getOrNull(i) ?: return
        if (item.kind == HomeItem.Kind.NAV) {
            if (item.url.startsWith("sys:")) system(item.url)
            else showHomePage(when (item.url) { "nav:apps" -> 1; "nav:next" -> homePage + 1; "nav:system" -> -1; else -> 0 })
            onLookPress?.invoke(); return
        }
        if (item.kind == HomeItem.Kind.APP) {
            // an immersive app is not opened in a window: it gets the glasses, as a game does
            val pkg = item.url.removePrefix("app://").substringBefore('/')
            if (dev.ultraxr.xr.XrRuntime.nativeApp(activity, pkg) != null && onLaunchNative != null) { onLaunchNative?.invoke(pkg); onLookPress?.invoke(); return }
            if (dev.ultraxr.shell.AppShell.available) openApp(item.title, item.url.removePrefix("app://"))
            else Tracking.log("apps: " + item.title + " not opened: the app helper is not running (tools/pixel/uxr_helper.sh)")
            onLookPress?.invoke(); return
        }
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
        current = this
    }

    fun stop() {
        save()
        if (current === this) current = null
        for (w in web.values) w.destroy()
        web.clear(); surfaces = HashMap()
    }

    fun pause() { resumed = false; save(); web.values.forEach { it.pause() } }
    fun resume() { resumed = true; web.values.forEach { it.resume() } }

    private fun save() { prefs.edit().putString("layout", desktop.toJson().toString()).apply(); savedRevision = desktop.revision }

    private fun ensureWeb(w: DesktopWindow): WebWindow = web.getOrPut(w.id) {
        WebWindow(activity, w.id, w.kind, w.url, darkPages) { ww ->
            desktop.find(ww.id)?.let { m ->
                val t = if (ww.isApp) m.title else ww.title.ifBlank { m.title }
                if (m.title != t || m.url != ww.url) { m.title = t; m.url = ww.url; desktop.touch() }
            }
            onChanged?.invoke()
        }.also { ww -> DesktopWindow.pixels(w.aspect).let { ww.setPixels(it[0], it[1]) }; surfaces[w.id]?.let { ww.attach(it) } }
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
        if (app.kind == "nav") { showHomePage(0); showHome(); return true }
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
    /** The focused window stands up like a phone, or lies down again; it keeps its height. */
    fun toggleShape() {
        val w = desktop.focused ?: return
        val h = w.height
        w.aspect = if (w.aspect >= 0.8) 9.0 / 16.0 else 16.0 / 9.0
        w.width = (h * w.aspect).coerceIn(DesktopWindow.MIN_WIDTH, DesktopWindow.MAX_WIDTH)
        applyShape(w.id); cursors[w.id] = doubleArrayOf(0.5, 0.5); desktop.touch(); changed()
    }
    fun close(id: Int) { web.remove(id)?.destroy(); bars.remove(id); handles.remove(id); cursors.remove(id); desktop.close(id); changed() }
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

    // ---- the one focus (desktop.focus): keys from a d-pad, and what follows from a press
    /** The shelf is, as things are drawn today, the home view's icons when no window shows and the dock's tiles when one does. */
    private fun shelfCount() = if (desktop.visible.isEmpty()) homeItems.size else HomeApps.dock.size
    private fun activateShelf(i: Int) { if (desktop.visible.isEmpty()) activateHome(i) else HomeApps.dock.getOrNull(i)?.let { open(it); onLookPress?.invoke() } }
    /** The page's picture is given the window's shape (after a free sizing, or standing a window up). */
    private fun applyShape(id: Int) { val w = desktop.find(id) ?: return; val p = DesktopWindow.pixels(w.aspect); web[id]?.setPixels(p[0], p[1]) }

    /**
     * One press of a key of the focus: a direction, Select, Back, nearer or further. Everything a d-pad does goes
     * through here, and so do a pinch, a tap and a completed look when they act as Select.
     */
    fun press(key: FocusKey) {
        val was = desktop.focus
        val a = desktop.press(key, shelfCount(), headYaw())
        when (a) {
            is FocusAction.OpenApp -> activateShelf(a.index)
            is FocusAction.CloseWindow -> close(a.id)
            is FocusAction.PageKey -> FocusKeys.pageKey(a.key)?.let { web[a.id]?.key(it) }
            is FocusAction.PutDown -> applyShape(a.id)
            else -> {}
        }
        if (desktop.focus !is Focus.Moving) moveByHand = false
        // a tick under the thumb for every press that did something, so the d-pad can be used without looking
        // (opening ticks by itself; a key that went to a page does not tick)
        if (desktop.focus != was || (a !is FocusAction.None && a !is FocusAction.OpenApp && a !is FocusAction.PageKey)) onLookPress?.invoke()
        changed()
    }

    /**
     * The phone's own d-pad: [down] is true when one of its keys goes down and false when it comes up. It does what a
     * gamepad's keys do: a key acts when it goes down, except that while a game's page is in use Back leaves the page
     * only when held for a second.
     */
    fun dpad(key: FocusKey, down: Boolean) {
        val f = desktop.focus
        if (key == FocusKey.BACK && f is Focus.Page && desktop.find(f.id)?.isGame == true) { backDown = null; if (down) backHold.down(System.nanoTime()) else backHold.up(); return }
        if (down) press(key)
    }

    /**
     * A key from a Bluetooth gamepad or keyboard, or one of the phone's own keys. Returns whether it was taken.
     * The directions, Select (d-pad centre, Enter, A), Back (Back, Escape, B) and the shoulder keys move the focus by
     * the table. While a page is in use the arrows and Enter go to it as they are; while it is a game's page every
     * key of a gamepad goes to it, and Back held for a second leaves it.
     */
    fun key(e: KeyEvent): Boolean {
        val k = FocusKeys.of(e.keyCode); val f = desktop.focus
        val down = e.action == KeyEvent.ACTION_DOWN
        val fromPad = KeyEvent.isGamepadButton(e.keyCode) || e.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        // the phone's own Back key cannot be held (a swipe is over at once): it leaves a game's page as it leaves any page
        val phoneBack = e.keyCode == KeyEvent.KEYCODE_BACK && !fromPad
        if (f is Focus.Page && desktop.find(f.id)?.isGame == true && !phoneBack) {
            if (k == null && !fromPad) return false      // a keyboard's letters, the volume keys: as everywhere
            if (k == FocusKey.BACK) { if (down) { backHold.down(System.nanoTime()); backDown = e } else if (e.action == KeyEvent.ACTION_UP) { backHold.up(); backDown = null } }
            web[f.id]?.forwardKey(e)
            return true
        }
        if (k == null) return false
        // a page in use gets the arrows and Enter as real keys (a gamepad's A becomes Enter below)
        val page = if (f is Focus.Page) web[f.id] else null
        if (page != null && !page.isApp && k != FocusKey.BACK && e.keyCode != KeyEvent.KEYCODE_BUTTON_A) { page.forwardKey(e); return true }
        // a direction held down repeats (a window being moved keeps going); Select and Back act once
        if (down && (e.repeatCount == 0 || (k != FocusKey.SELECT && k != FocusKey.BACK))) press(k)
        return true
    }
    private val backHold = BackHold(); private var backDown: KeyEvent? = null
    /** Back was held for a second in a game: the game is told the key came up, and the focus leaves its page. */
    private fun leaveGame() {
        val f = desktop.focus
        if (f !is Focus.Page) return
        backDown?.let { web[f.id]?.forwardKey(KeyEvent.changeAction(it, KeyEvent.ACTION_UP)) }; backDown = null
        press(FocusKey.BACK)
    }

    /** Whether a game controller's sticks and triggers belong to the page now: only while a page is in use. */
    val pageInUse: Boolean get() = desktop.focus is Focus.Page

    // ---- keys that Android gives to another app
    /** Set by the phone's screen: whether its window has the keys, and whether one of its dialogs is open. */
    var phoneFocus = true
    var dialogOpen = false
    private var resumed = false
    /**
     * With another app in a window, Android may give the keys to that app instead of this one. The pointer service
     * sees every key first (PointerService.onKeyEvent) and hands them here in that case, and only in that case.
     */
    val keysElsewhere: Boolean get() = resumed && !phoneFocus && !dialogOpen && desktop.visible.any { it.kind == "app" }

    // ---- touchpad (phone) input. Distances are fractions of the pad's width.
    /** A window is being carried: the touchpad and the d-pad move it until it is put down. */
    val moving: Boolean get() = desktop.focus is Focus.Moving
    /** The phone's Move button: takes up the window in focus, or puts down the one being carried. */
    fun toggleMove() { if (moving) press(FocusKey.SELECT) else desktop.focused?.let { desktop.startMove(it.id); changed() } }

    fun padMove(dx: Double, dy: Double) {
        padTouchNs = System.nanoTime()
        val w = desktop.focused ?: return
        val f = desktop.focus
        if (f is Focus.Moving) { desktop.move(f.id, -dx * 1.2, -dy * 1.2); return }
        // sizing: a slide to the right widens the window and a slide down makes it taller, as the d-pad does
        if (f is Focus.Sizing) { desktop.find(f.id)?.let { desktop.sizeTo(it.id, it.width * Math.exp(dx * 1.5), it.height * Math.exp(dy * 1.5)) }; return }
        // a corner is held (double-tap and hold on it): it follows the finger and the window's middle stays where it is
        padSizing?.let { s -> desktop.sizeTo(w.id, w.width * (1 + 2 * s[0] * dx * 0.9), w.height * (1 + 2 * s[1] * dy * 0.9 * w.aspect)); return }
        val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        // The cursor can leave the page upwards onto the window's title bar, where a tap presses the button under
        // it (close, maximise, minimise, lock), and downwards onto the handle's words. A press that began in the
        // page stays in the page.
        val held = pressed || desktop.fullscreenId == w.id
        val top = if (held) 0.0 else -barSpan(w); val bottom = if (held) 1.0 else 1.0 + handleSpan(w)
        c[0] = (c[0] + dx * 0.9).coerceIn(0.0, 1.0); c[1] = (c[1] + dy * 0.9 * w.aspect).coerceIn(top, bottom)
        if (pressed) web[w.id]?.touch(MotionEvent.ACTION_MOVE, c[0], c[1])
        // the focus is on what the cursor is over: the page, the window (its title bar), or a word of the handle
        else if (desktop.point(desktop.focusAt(w, c[0], c[1]))) changed()
    }
    /** The title bar's height as a fraction of the window's: the cursor's v runs from minus this to 1. */
    private fun barSpan(w: DesktopWindow) = w.shownBarHeight() / w.shownHeight()
    /** The handle's, with the space above it: the cursor's v runs on to 1 plus this. */
    private fun handleSpan(w: DesktopWindow) = (HandleStrip.GAP + w.shownHandleHeight()) / w.shownHeight()
    /** The corner of the page the cursor has hold of, as the signs of its x and y (right and down are 1), or null. */
    private fun cornerAt(w: DesktopWindow, c: DoubleArray): DoubleArray? {
        if (desktop.fullscreenId == w.id || w.maximized) return null
        val ru = w.shownCornerReach() / w.shownWidth(); val rv = w.shownCornerReach() / w.shownHeight()
        if ((c[0] > ru && c[0] < 1 - ru) || (c[1] > rv && c[1] < 1 - rv) || c[1] < 0.0 || c[1] > 1.0) return null
        return doubleArrayOf(if (c[0] > 0.5) 1.0 else -1.0, if (c[1] > 0.5) 1.0 else -1.0)
    }

    /** A tap is Select on the thing in focus. On a page in use it is a touch where the cursor is. */
    fun padTap() {
        padTouchNs = System.nanoTime()
        // the cursor may have been left on the title bar or the handle while something else chose the page
        desktop.focused?.let { fw -> val c = cursors.getOrPut(fw.id) { doubleArrayOf(0.5, 0.5) }; if (desktop.focus is Focus.Page && (c[1] < 0.0 || c[1] > 1.0)) desktop.point(desktop.focusAt(fw, c[0], c[1])) }
        val f = desktop.focus
        if (f !is Focus.Page && f !is Focus.Window) { press(FocusKey.SELECT); return }
        val w = desktop.find(f.windowId ?: -1) ?: return; val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        if (f is Focus.Window) {
            // with the cursor on a title-bar button the tap presses that button, as before; on the bare bar it does nothing
            if (c[1] < 0.0) Desktop.barButtonAt(c[0])?.let { barAction(w.id, it); onLookPress?.invoke() } else press(FocusKey.SELECT)
            return
        }
        web[w.id]?.tap(c[0], c[1])
    }

    /** The main menu: every window is put away (not closed) and the home's icons show. */
    fun showHome() { for (w in desktop.windows) if (!w.minimized) desktop.minimize(w.id); changed() }

    private var padSizing: DoubleArray? = null
    fun padPress(down: Boolean) {
        val w = desktop.focused ?: return; val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        if (!down && padSizing != null) { padSizing = null; desktop.endDrag(); applyShape(w.id); return }
        if (down == pressed) return
        if (down && (desktop.focus is Focus.Moving || desktop.focus is Focus.Sizing)) return
        if (down && (c[1] < 0.0 || c[1] > 1.0)) return      // on the title bar or the handle: they answer to a tap
        // double-tap and hold on a corner of the page takes hold of the corner: the slide that follows sizes the window
        if (down) cornerAt(w, c)?.let { padSizing = it; desktop.beginDrag(w.id); return }
        pressed = down; web[w.id]?.touch(if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, c[0], c[1])
    }
    fun padScroll(dx: Double, dy: Double) {
        padTouchNs = System.nanoTime()
        val w = desktop.focused ?: return
        if (moving) { desktop.push(w.id, Math.exp(dy * 1.5)); return }      // two fingers while carrying a window: nearer and further
        val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        web[w.id]?.scroll(c[0].coerceIn(0.0, 1.0), c[1].coerceIn(0.0, 1.0), (-dx * 1400).toFloat(), (-dy * 1400).toFloat())
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
        // a window being moved or sized keeps the focus until it is put down or back: looks and rays do not take it
        val adjusting = desktop.focus is Focus.Moving || desktop.focus is Focus.Sizing
        // Gaze focus: the window a look comes to rest on gets the focus, unless the pad is in use. Once for each
        // arrival: while the look stays there the d-pad is free to take the focus elsewhere.
        if (gazeFocus && !full && !adjusting && nowNs - padTouchNs > 700_000_000L && !handDown) {
            val g = desktop.hit(origin, forward, anchor)
            val id = g?.window?.id ?: -1
            if (id != gazeCandidate) { gazeCandidate = id; gazeSinceNs = nowNs; gazeDone = false }
            else if (id >= 0 && !gazeDone && nowNs - gazeSinceNs > 450_000_000L) { gazeDone = true; if (desktop.look(id)) changed() }
        }
        if (handGestures && !full) hands(nowNs, head, origin, anchor, tracked) else if (handDown) releaseHand()
        if (backHold.due(nowNs)) leaveGame()

        // The home view (shown when no window is open): the icon in focus is the one that a pinch, a tap on the
        // phone, Select or a longer look opens. A look that arrives on an icon brings the focus to it.
        val showHome = desktop.visible.isEmpty()
        var looking = -1
        if (showHome) {
            val yaw = desktop.followAnchor.yaw
            val h = Geometry.planeHit(origin, forward, Geometry.centre(anchor, yaw, 0.0, DesktopRenderer.HOME_DISTANCE), yaw, 0.0)
            if (h != null) looking = HomeGrid.hit(h[0], h[1], homeItems.size) ?: -1
        }
        if (looking != gazeHover) { gazeHover = looking; if (looking >= 0 && desktop.point(Focus.Shelf(looking))) changed() }
        val onShelf = desktop.focus as? Focus.Shelf
        val hover = if (showHome && onShelf != null && homeItems.isNotEmpty()) onShelf.index.coerceIn(0, homeItems.size - 1) else -1
        homeHover = hover
        if (showHome && handGestures) {
            val pinching = Tracking.hands.any { it.pinching }
            if (pinching && !homePinchWas && hover >= 0) press(FocusKey.SELECT)
            homePinchWas = pinching
        } else homePinchWas = false

        // Look-to-press: the home's icons, the dock's apps, the title-bar buttons and the handle's words.
        var looked: String? = null; var lookedAt: Focus? = null
        if (lookToPress && !full && !adjusting && nowNs - padTouchNs > 700_000_000L && !handDown) {
            val tile = if (showHome) null else Rail.dockTile(origin, forward, anchor, desktop.followAnchor.yaw, HomeApps.dock.size)
            // an icon opens by looking only when there is a head to look with (not in the phone preview) and it is the one in focus
            if (showHome && looking >= 0 && looking == hover && tracked) { looked = "home:$looking"; ringDistance = DesktopRenderer.HOME_DISTANCE }
            else if (showHome) { }
            else if (tile != null) { looked = "dock:$tile"; lookedAt = Focus.Shelf(tile); ringDistance = Rail.DOCK_DISTANCE }
            else {
                val g = desktop.hit(origin, forward, anchor)
                // only the focused window shows its buttons, so only those can be pressed
                if (g != null && g.barButton != null && g.window.id == desktop.focusedId) { looked = "bar:${g.window.id}:${g.barButton.name}"; ringDistance = g.distance }
                else if (g != null && g.handleWord != null) { looked = "handle:${g.window.id}:${g.handleWord.name}"; lookedAt = Focus.Handle(g.window.id, g.handleWord); ringDistance = g.distance }
            }
        }
        // An icon of the home opens on a pinch or a tap at once; by looking alone it takes two seconds, so that
        // glancing across the home does not start things.
        val onHome = looked?.startsWith("home:") == true
        val fire = if (onHome) { dwell.update(nowNs, null); homeDwell.update(nowNs, looked) } else { homeDwell.update(nowNs, null); dwell.update(nowNs, looked) }
        ringYaw = Geometry.yawOf(forward); ringPitch = Geometry.pitchOf(forward); ringProgress = if (looked == null || onHome) 0.0 else dwell.progress
        homeProgress = if (onHome) homeDwell.progress else 0.0
        // a look that has rested on a mark of the dock or a word of the handle long enough for the ring to start has the focus
        if (lookedAt != null && dwell.progress > 0.0 && desktop.point(lookedAt)) changed()
        if (fire != null) {
            // the completed look is Select, and Select acts on the thing in focus: nothing happens if the focus has gone elsewhere meanwhile
            if (fire.startsWith("bar:")) { val p = fire.split(":"); barAction(p[1].toInt(), BarButton.valueOf(p[2])); onLookPress?.invoke() }
            else if (onHome) press(FocusKey.SELECT)      // the look was only counted while it was on the icon in focus
            else if (lookedAt != null) { desktop.point(lookedAt); if (desktop.focus == lookedAt) press(FocusKey.SELECT) }
        }
        if (desktop.focus != loggedFocus) { loggedFocus = desktop.focus; Tracking.log("desktop: focus on " + desktop.focus.describe()) }

        if (desktop.revision != savedRevision && !handDown && !pressed && padSizing == null && !adjusting) save()
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
        if (hand == null) { lastHandRay = null; handTarget = null; handPinchWas = false; if (handDown) releaseHand(); return }
        // Hand positions are in the app world. Without trusted position the view sits at the origin,
        // so the hand ray is taken relative to the head instead.
        val o = if (origin === ZERO) doubleArrayOf(hand.aimOrigin[0] - head.pose.p[0], hand.aimOrigin[1] - head.pose.p[1], hand.aimOrigin[2] - head.pose.p[2]) else hand.aimOrigin
        lastHandRay = o to hand.aimDir
        val pinchStarts = hand.pinching && !handPinchWas; handPinchWas = hand.pinching
        val f = desktop.focus
        if (f is Focus.Moving || f is Focus.Sizing) {
            // a window taken up by pinching "move" follows the hand's ray; a pinch is Select: it puts the window down
            if (f is Focus.Moving && moveByHand && !hand.pinching) desktop.aim(f.id, Geometry.yawOf(hand.aimDir), Geometry.pitchOf(hand.aimDir))
            if (pinchStarts) { press(FocusKey.SELECT); handDown = true; handDownHit = null; handTile = -1; handCorner = null }      // the rest of this pinch does nothing more
            else if (!hand.pinching && handDown) releaseHand()
            return
        }
        val hit = desktop.hit(o, hand.aimDir, anchor)
        if (handDragWindow >= 0) {   // a window is being carried by its title bar
            if (hand.pinching) { desktop.aim(handDragWindow, Geometry.yawOf(hand.aimDir), Geometry.pitchOf(hand.aimDir) - 0.0); handMoved = true } else releaseHand()
            return
        }
        if (handSizeWindow >= 0) {   // a corner is held: it follows the ray, and the window's middle stays where it is
            val w = desktop.find(handSizeWindow)
            if (hand.pinching && w != null) planePoint(w, o, hand.aimDir, anchor)?.let { desktop.sizeTo(w.id, 2 * Math.abs(it[0]), 2 * Math.abs(it[1])) }
            else releaseHand()
            return
        }
        val tile = if (hit == null && desktop.visible.isNotEmpty()) Rail.dockTile(o, hand.aimDir, anchor, desktop.followAnchor.yaw, HomeApps.dock.size) ?: -1 else -1
        val target: Focus? = if (hit != null) desktop.focusAt(hit) else if (tile >= 0) Focus.Shelf(tile) else null
        // the ray moves the focus when it comes over something new, not while it rests there: the d-pad may have moved on since
        if (target != handTarget) { handTarget = target; if (target != null && !handDown && desktop.point(target)) changed() }
        if (hit != null && hit.onPage) cursors[hit.window.id] = doubleArrayOf(hit.u, hit.v)
        if (hand.pinching && !handDown) {
            handDown = true; handDownHit = hit; handMoved = false; handTile = tile; handCorner = null
            if (target != null && desktop.point(target)) changed()
            if (hit != null) {
                if (hit.onBar && hit.barButton == null) handDragWindow = hit.window.id
                // on a corner it is decided when the hand moves: a drag sizes the window, a pinch let go where it began is a touch
                else if (hit.corner >= 0) handCorner = doubleArrayOf((hit.u - 0.5) * hit.window.shownWidth(), (0.5 - hit.v) * hit.window.shownHeight())
                else if (hit.onPage) web[hit.window.id]?.touch(MotionEvent.ACTION_DOWN, hit.u, hit.v)
            }
        } else if (hand.pinching && handDown) {
            val d = handDownHit; val start = handCorner
            if (d != null && start != null) {
                val p = planePoint(d.window, o, hand.aimDir, anchor)
                if (p != null && Math.hypot(p[0] - start[0], p[1] - start[1]) > CORNER_DRAG_M) { handCorner = null; handSizeWindow = d.window.id; desktop.beginDrag(d.window.id) }
            } else if (d != null && d.onPage && hit != null && hit.window.id == d.window.id) web[d.window.id]?.touch(MotionEvent.ACTION_MOVE, hit.u.coerceIn(0.0, 1.0), hit.v.coerceIn(0.0, 1.0))
        } else if (!hand.pinching && handDown) {
            val d = handDownHit
            if (d != null && d.onBar && d.barButton != null && hit != null && hit.window.id == d.window.id && hit.barButton == d.barButton && d.window.id == desktop.focusedId) barAction(d.window.id, d.barButton)
            else if (d != null && d.handleWord != null) {
                // a pinch on a word of the handle is Select on that word, if the hand is still on it when it lets go
                val word = Focus.Handle(d.window.id, d.handleWord)
                if (hit != null && hit.window.id == d.window.id && hit.handleWord == d.handleWord) { desktop.point(word); if (desktop.focus == word) { press(FocusKey.SELECT); moveByHand = desktop.focus is Focus.Moving } }
            }
            else if (d != null && handCorner != null) { if (d.onPage) web[d.window.id]?.tap(d.u, d.v) }
            else if (d != null && d.onPage) { val c = cursors[d.window.id] ?: doubleArrayOf(d.u, d.v); web[d.window.id]?.touch(MotionEvent.ACTION_UP, c[0], c[1]) }
            else if (d == null && handTile >= 0 && tile == handTile) { val mark = Focus.Shelf(tile); desktop.point(mark); if (desktop.focus == mark) press(FocusKey.SELECT) }
            releaseHand()
        }
    }

    /** Where a ray meets the plane of window [w]: metres to the right of and above its middle, also beyond its edges. */
    private fun planePoint(w: DesktopWindow, o: DoubleArray, dir: DoubleArray, anchor: DoubleArray): DoubleArray? {
        val yaw = desktop.worldYaw(w)
        return Geometry.planeHit(o, dir, Geometry.centre(anchor, yaw, w.shownPitch(), w.shownDistance()), yaw, w.shownPitch())
    }

    private fun releaseHand() {
        val d = handDownHit
        if (handDown && d != null && d.onPage && handDragWindow < 0) web[d.window.id]?.touch(MotionEvent.ACTION_CANCEL, d.u, d.v)
        if (handSizeWindow >= 0) { desktop.endDrag(); applyShape(handSizeWindow) }      // the window keeps the size the hand gave it
        handDown = false; handDragWindow = -1; handDownHit = null; handTile = -1; handCorner = null; handSizeWindow = -1
    }

    fun barAction(id: Int, b: BarButton) {
        when (b) { BarButton.FOLLOW -> toggleFollow(id); BarButton.MINIMIZE -> minimize(id); BarButton.MAXIMIZE -> toggleMaximize(id); BarButton.CLOSE -> close(id) }
    }

    private fun publish(nowNs: Long, anchor: DoubleArray, positionOk: Boolean, head: HeadTracker.State) {
        val full = desktop.fullscreenId
        val list = ArrayList<RenderWindow>()
        val fo = desktop.focus
        for (w in desktop.visible) {
            val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
            val focused = w.id == desktop.focusedId
            val pw = web[w.id]?.pixelWidth ?: WebWindow.PIXEL_WIDTH; val ph = web[w.id]?.pixelHeight ?: WebWindow.PIXEL_HEIGHT
            if (w.id == full) {
                val tanH = Tracking.displayTanH; val tanV = Tracking.displayTanV
                val d = 2.0; val width = minOf(2 * d * tanH, 2 * d * tanV * w.aspect) * 0.98
                list += RenderWindow(w.id, 0.0, 0.0, d, width, width / w.aspect, 0.0, true, c[0], c[1], true, pressed, null, 0, true, outline = false, pixelW = pw, pixelH = ph)
            } else {
                // the strips are drawn for the window's shape, which a free sizing changes
                val shape = (w.shownBarHeight() / w.shownWidth() * 2000).toInt()
                val key = "${w.title}|${w.follow}|${w.maximized}|$focused|$shape"
                val bar = bars[w.id]
                if (bar == null || bar.second != key) { bars[w.id] = drawBar(w, focused) to key; barVersions[w.id] = (barVersions[w.id] ?: 0) + 1 }
                // What shows the focus, until the look is redone: the line round the window when the focus is on the
                // window itself (or it is being moved or sized), the cursor when its page is in use, the lit word when
                // it is on the handle. The cursor also shows where the touchpad took it onto the title bar or the handle.
                val inFocus = fo.windowId == w.id
                val adjusting = fo is Focus.Moving || fo is Focus.Sizing
                val outline = inFocus && (fo is Focus.Window || adjusting)
                val showCursor = inFocus && !adjusting && (fo is Focus.Page || c[1] < 0.0 || c[1] > 1.0)
                var handle: Bitmap? = null
                if (inFocus) {
                    val hk = "${fo.describe()}|${w.follow}|$shape"
                    val h = handles[w.id]
                    if (h == null || h.second != hk) { handles[w.id] = drawHandle(w, fo) to hk; handleVersions[w.id] = (handleVersions[w.id] ?: 0) + 1 }
                    handle = handles[w.id]!!.first
                }
                list += RenderWindow(w.id, desktop.worldYaw(w), w.shownPitch(), w.shownDistance(), w.shownWidth(), w.shownHeight(), w.shownBarHeight(),
                    focused, c[0], c[1], showCursor, pressed || handDown, bars[w.id]!!.first, barVersions[w.id]!!, false,
                    outline, handle, handleVersions[w.id] ?: 0, w.shownHandleHeight(), pw, ph)
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
        // the dock's tile in focus is lit, as the home's icon is
        val lit = if (!showHome && fo is Focus.Shelf) fo.index.coerceIn(0, HomeApps.dock.size - 1) else -1
        if (dockBitmap == null || lit != dockLit) { dockBitmap = drawDock(lit); dockLit = lit; dockVersion++ }
        val f = desktop.focused?.takeIf { !it.minimized && full < 0 }
        val frame = RenderFrame(list, anchor, if (showHome) homeBitmap else null, homeVersion, desktop.followAnchor.yaw, positionOk, if (dimUnfocused) 0.55f else 1f,
            dock = if (full < 0 && !showHome) dockBitmap else null, dockVersion = dockVersion, dockYaw = desktop.followAnchor.yaw,
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

    /**
     * The handle under the window in focus: its four words, in the title bar's type. The word in focus is lit (the
     * one for closing in the colour of closing), as is "move" or "size" while the window is being moved or sized.
     * "pin" has a dot before it while the window is pinned to the room.
     */
    private fun drawHandle(w: DesktopWindow, f: Focus): Bitmap {
        val bw = 1024; val bh = (bw * w.shownHandleHeight() / w.shownWidth()).toInt().coerceIn(40, 120)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.textAlign = Paint.Align.CENTER; p.textSize = bh * 0.5f
        val lit = when (f) { is Focus.Handle -> f.word; is Focus.Moving -> HandleWord.MOVE; is Focus.Sizing -> HandleWord.SIZE; else -> null }
        for (word in HandleWord.values()) {
            val on = word == lit; val x = bw * HandleStrip.centre(word).toFloat(); val cy = bh * 0.46f
            p.typeface = if (on) bold else regular
            p.color = if (!on) Ink.WHITE_INT else if (word == HandleWord.CLOSE) Ink.CORAL_INT else Ink.AMBER_INT
            c.drawText(word.label, x, cy + p.textSize * 0.34f, p)
            if (word == HandleWord.PIN && !w.follow) c.drawCircle(x - p.measureText(word.label) / 2 - bh * 0.22f, cy, bh * 0.07f, p)
        }
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
            val ic = item.icon
            if (ic != null) {      // an installed app: its own icon, cut round like the others
                val save = c.save(); val clip = android.graphics.Path().apply { addCircle(cx, cy, r, android.graphics.Path.Direction.CW) }
                c.clipPath(clip); p.isFilterBitmap = true; c.drawBitmap(ic, null, RectF(cx - r, cy - r, cx + r, cy + r), p); c.restoreToCount(save)
            } else HomeIcon.draw(c, p, cx, cy, r, item.color, item.kind == HomeItem.Kind.GAME, if (item.kind == HomeItem.Kind.NAV) (when { item.url == "nav:home" -> "⌂"; item.url == "nav:apps" || item.url == "nav:next" -> "⋯"; item.url == "nav:system" -> "⚙"; else -> item.title.take(1) }) else item.title.take(1), item.verified, on)
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
    private fun drawDock(lit: Int = -1): Bitmap {
        val bw = 1100; val bh = 220
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.textAlign = Paint.Align.CENTER
        val apps = HomeApps.dock; val tile = bw / apps.size.toFloat()
        // One glass bar with the same round icons as the home view. Where each app sits is unchanged (Rail.dockTile).
        val bar = RectF(3f, 3f, bw - 3f, bh - 3f)
        p.style = Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, bar.top, 0f, bar.bottom, 0x2CFFFFFF, 0x12FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(bar, 70f, 70f, p)
        p.shader = android.graphics.LinearGradient(0f, bar.top, 0f, bar.bottom, 0x8CFFFFFF.toInt(), 0x24FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; c.drawRoundRect(bar, 70f, 70f, p); p.shader = null; p.style = Paint.Style.FILL
        for ((i, a) in apps.withIndex()) {
            val x = tile * (i + 0.5f)
            HomeIcon.draw(c, p, x, 84f, 56f, a.color, false, if (a.kind == "nav") "⌂" else a.title.take(1), false, i == lit)
            p.textAlign = Paint.Align.CENTER; p.typeface = if (i == lit) bold else regular; p.textSize = 34f; p.color = Ink.WHITE_INT
            c.drawText(a.title, x, 190f, p)
        }
        return b
    }

    companion object {
        private val ZERO = doubleArrayOf(0.0, 0.0, 0.0)
        /** A pinch on a corner becomes a drag once the hand's ray has moved this far across the window, metres. */
        private const val CORNER_DRAG_M = 0.03
        /** The desktop that is running, for the pointer service to hand keys to (see [keysElsewhere]). */
        @Volatile var current: DesktopSession? = null; private set
    }
}
