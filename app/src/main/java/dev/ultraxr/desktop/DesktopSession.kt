package dev.ultraxr.desktop

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
    /** Videos in windows (kind [VIDEO]): the player of each, and how its picture is laid out. A video's address is its file's, with the layout after a "#": file...#sbs-half. */
    private val video = HashMap<Int, VideoPlayer>()
    private val videoMode = HashMap<Int, VideoMode>()
    /** The activity is to ask for a video file (the phone's file picker): the file comes back to [openVideo]. */
    var onPickVideo: (() -> Unit)? = null
    private fun publishSbs() { val ids = videoMode.filter { it.value.sbs }.keys.toSet(); for (r in renderers) r.sbsIds = ids }
    private val cursors = HashMap<Int, DoubleArray>()
    private val prefs = activity.getSharedPreferences("desktop", Context.MODE_PRIVATE)
    /** The pictures drawn with the 2D canvas (marks, names, titles, the handle's words, the status line), by what they show. The ones not asked for for longest are dropped. */
    private val pictures = object : LinkedHashMap<String, Sprite>(64, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sprite>?) = size > 96 }
    private fun picture(key: String, make: () -> Bitmap): Sprite = pictures[key] ?: Sprite(key, make()).also { pictures[key] = it }
    private var savedRevision = -1L
    private var lastTickNs = 0L
    private var pressed = false
    private var gazeCandidate = -1; private var gazeSinceNs = 0L
    private var padTouchNs = 0L
    private var handDown = false; private var handDownHit: WindowHit? = null
    private var lastHandRay: Pair<DoubleArray, DoubleArray>? = null
    // The one focus is desktop.focus. Each pointer remembers what it was last over, so that it moves the focus when it
    // comes over something new and not while it rests there (the d-pad may have taken the focus elsewhere since).
    private var handTarget: Focus? = null; private var handPinchWas = false; private var handTile = -1
    /** A pinch began on a corner of a page, at this place of the window's plane: a drag from here sizes the window. */
    private var handCorner: DoubleArray? = null; private var handSizeWindow = -1
    /** The window being moved was taken up by a hand's pinch on "move": it follows the hand's ray. */
    private var moveByHand = false
    /** The hand as a pointer: the arm's ray and the measured pinch, as in the games ([HandPointer]). */
    private val pointer by lazy { HandPointer(Tracking.handOptions.pressM, Tracking.handOptions.releaseM, Tracking.handOptions.anticipate, Tracking.handOptions.predict) }
    /** A window pinched on its handle and carried: it follows the hand while the fingers hold, this far from the ray. */
    private var handTargetNs = 0L; private var handPointed = true
    @Volatile private var phoneTouchNs = 0L
    /** The phone's screen was touched just now. */
    fun phoneTouched() { phoneTouchNs = System.nanoTime() }
    private var handGrab = -1; private var grabYaw = 0.0; private var grabPitch = 0.0; private var handDownDir: DoubleArray? = null
    /** How far the hand was from the head when it took the window up, and how far the window stood then (asked for 21 November 2026: push a held window away, pull it nearer). */
    private var grabReach = -1.0; private var grabDistance = 0.0
    private var handShown: RenderHand? = null; private var handLogNs = 0L; private var handSeenFrames = 0
    /**
     * Where the home and the dock stand in the room. They keep their place while the wearer moves about where they
     * are (leaning in, shifting on a chair) and come along, gliding, when the wearer walks off.
     */
    private val stage = doubleArrayOf(0.0, 0.0, 0.0); private var stageMoving = false
    /** The windows have been arranged around where the wearer is: once at the start, and again when the view is recentred. */
    private var anchored = false
    private var ringAnchor: DoubleArray = ZERO
    private val anchorsShown = HashMap<Int, DoubleArray>()
    private var gazeDone = false
    private var loggedFocus: Focus? = null
    @Volatile private var surfaces = HashMap<Int, Surface>()
    var onChanged: (() -> Unit)? = null
    /** Set by the stage: where frames go. */
    var renderers: List<DesktopRenderer> = emptyList()
    var statusLine = ""; private set

    // ---- settings (persisted)
    var usePosition: Boolean get() = prefs.getBoolean("usePosition", true); set(v) { prefs.edit().putBoolean("usePosition", v).apply() }
    var gazeFocus: Boolean get() = prefs.getBoolean("gazeFocus", true); set(v) { prefs.edit().putBoolean("gazeFocus", v).apply() }
    var handGestures: Boolean get() = prefs.getBoolean("handGestures", true); set(v) { prefs.edit().putBoolean("handGestures", v).apply() }
    var dimUnfocused: Boolean get() = prefs.getBoolean("dim", true); set(v) { prefs.edit().putBoolean("dim", v).apply() }
    /** Pages in their dark theme (black is see-through on the glasses). Applies to windows opened afterwards. */
    var darkPages: Boolean get() = prefs.getBoolean("darkPages", true); set(v) { prefs.edit().putBoolean("darkPages", v).apply() }
    /** Resting the gaze on a mark of the shelf or a word of the handle presses it: no phone and no hands needed. */
    var lookToPress: Boolean get() = prefs.getBoolean("lookToPress", true); set(v) { prefs.edit().putBoolean("lookToPress", v).apply() }

    /** Real depth: the glasses' half-width 3D mode, when the link to them holds. Falls back to 2D by itself. */
    var depth3d: Boolean get() = prefs.getBoolean("depth3d", true); set(v) { prefs.edit().putBoolean("depth3d", v).apply() }
    var swapEyes: Boolean get() = prefs.getBoolean("swapEyes", false); set(v) { prefs.edit().putBoolean("swapEyes", v).apply() }
    /** Test builds: draw two eye views on the attached display without glasses, to check the picture. */
    var forceStereo = false

    init { Type.load(activity) }
    private val dwell = Dwell()
    /** A mark of the shelf opens by looking alone only after a longer look, so that glancing along the shelf does not start things. */
    private val shelfDwell = Dwell(holdNs = 1_700_000_000L, armNs = 300_000_000L)

    // ---- the shelf: what it offers
    /**
     * What is used most comes first, so that it is in view without sliding the row: the web apps (YouTube, Xbox Cloud,
     * the browser), the phone's apps and the settings; then the WebXR games that are not known to be broken; last
     * what the desktop does itself.
     */
    private val firstPage: List<HomeItem> = run {
        val games = try { dev.ultraxr.core.GameLibrary.parse(activity.assets.open("webxr_pages.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { emptyList() }
        listOf(nav("Home", "nav:panel")) + nav("Videos", "nav:videos") + HomeApps.all.map { HomeItem(it.title, HomeItem.Kind.WEB, it.url, it.color, it.kind) } + nav("All apps", "nav:apps") + nav("Settings", "nav:settings") +
            games.filter { it.state != dev.ultraxr.core.WebGame.State.BLOCKED }.mapIndexed { i, g ->
                HomeItem(g.name.substringBefore(" (").trim(), HomeItem.Kind.GAME, g.url, TINTS[i % TINTS.size], enter = g.enter, verified = g.state == dev.ultraxr.core.WebGame.State.VERIFIED)
            } + installedXrGames() + nav("System", "nav:system")
    }
    /**
     * The immersive apps on this phone (OpenXR apps, converted Quest games: the user's own games among them) stand on the shelf with the
     * WebXR games, so that they are in view, not behind "All apps". They are started the way "All apps" starts them ([onLaunchNative]).
     * The WebXR browser is not one of them (it has its mark already), nor are the runtimes' sample apps (Khronos's hello_xr, Unity's examples): they stay in "All apps".
     */
    private fun installedXrGames(): List<HomeItem> = try {
        dev.ultraxr.xr.XrRuntime.installedApps(activity).filter { val n = it.activityInfo.packageName; !n.startsWith("com.igalia.wolvic") && !n.startsWith("org.khronos.") && !n.startsWith("com.unity.example.") && !n.startsWith("dev.ultraxr.") }.mapIndexed { i, ri ->
            HomeItem(ri.loadLabel(activity.packageManager).toString(), HomeItem.Kind.GAME, "app://" + ri.activityInfo.packageName + "/" + ri.activityInfo.name, TINTS[(i + 3) % TINTS.size])
        }
    } catch (_: Exception) { emptyList() }
    /** A mark that is no app has no colour of its own: its ring is Chalk. */
    private fun nav(title: String, what: String) = HomeItem(title, HomeItem.Kind.NAV, what, Ink.CHALK_INT)
    /** What the shelf shows now: its first page (games, the web apps, "All apps", "System", "Settings"), or a page of the phone's apps. */
    @Volatile var homeItems: List<HomeItem> = firstPage; private set
    private var homePage = 0
    private var installedApps: List<dev.ultraxr.shell.AppShell.App>? = null
    private val appTints = HashMap<String, Int>()
    /**
     * An installed app's own colour, for the ring of its mark: the mean of the strongly coloured parts of its icon. The
     * icon itself is not drawn in the glasses: it is a filled disc of colour, and there everything is drawn in line.
     */
    private fun appTint(pkg: String): Int = appTints.getOrPut(pkg) {
        try {
            val d = activity.packageManager.getApplicationIcon(pkg); val n = 24; val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, n, n); d.draw(Canvas(b))
            var r = 0.0; var g = 0.0; var bl = 0.0; var sum = 0.0; val hsv = FloatArray(3)
            for (y in 0 until n) for (x in 0 until n) {
                val c = b.getPixel(x, y); if (c ushr 24 < 128) continue
                android.graphics.Color.colorToHSV(c, hsv); val weight = (hsv[1] * hsv[2]).toDouble()
                r += (c shr 16 and 255) * weight; g += (c shr 8 and 255) * weight; bl += (c and 255) * weight; sum += weight
            }
            b.recycle()
            if (sum < 1.0) Ink.CHALK_INT else android.graphics.Color.rgb((r / sum).toInt(), (g / sum).toInt(), (bl / sum).toInt())
        } catch (_: Throwable) { Ink.CHALK_INT }
    }
    /** The system page: what otherwise needs the phone's screen. */
    private fun systemPage(): List<HomeItem> = listOf(nav("Recentre", "sys:recentre"), nav("Gather windows", "sys:gather"), nav("Close all", "sys:closeall"),
        nav(if (dev.ultraxr.shell.AppShell.available) "Apps: ready" else "Apps: helper off", "sys:apps"), nav("Home", "nav:home"))
    private fun system(what: String) {
        when (what) {
            "sys:recentre" -> recenter()
            "sys:gather" -> gather()
            "sys:closeall" -> for (w in desktop.windows.toList()) close(w.id)
        }
        showHomePage(if (panelShown) 0 else -1)
    }

    // ---- the home panel (Home): what is in view while no window is open. Its tiles are what the focus is on then:
    // Focus.Shelf(i) is tile i of the panel while it shows, and mark i of the dock while a window is open.
    val panelShown: Boolean get() = desktop.fullscreenId < 0 && desktop.visible.isEmpty()
    private var homeSection = "home"; private var appsPage = 0
    @Volatile private var tiles: List<HomeTile> = emptyList()
    private var homeModel: HomeModel? = null; private var homeNs = 0L; private var panelWas = false; private var panelSinceNs = 0L
    /** The cover in the middle of the home's wall. The d-pad turns the wall; a ray or a look takes the focus to a cover without turning it. */
    private var coverCentre = 0; private val coverGlide = Glide(0.0); private val coverPop = HashMap<String, Glide>(); private var coverSection = ""
    /** The d-pad (or a slide) has brought the focus to tile [i]: when that is a cover, the wall turns to it. */
    private fun centreOn(i: Int) {
        val m = homeModel ?: return; val n = Home.coverCount(tiles)
        if (i < Home.SIDE || i >= Home.SIDE + n || i - Home.SIDE == coverCentre) return
        coverCentre = i - Home.SIDE; tiles = Home.layout(m, coverCentre)
        // round the wall the short way
        if (n >= Home.ROUND_FROM) { if (coverCentre - coverGlide.value > n / 2.0) coverGlide.value += n else if (coverCentre - coverGlide.value < -n / 2.0) coverGlide.value -= n }
    }
    private val appIcons = HashMap<String, Bitmap?>()
    private fun appIcon(item: HomeItem): Bitmap? {
        if (item.kind != HomeItem.Kind.APP && !(item.kind == HomeItem.Kind.GAME && item.url.startsWith("app://"))) return null
        val pkg = item.url.removePrefix("app://").substringBefore('/')
        return appIcons.getOrPut(pkg) { try { val d = activity.packageManager.getApplicationIcon(pkg); val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888); d.setBounds(0, 0, 128, 128); d.draw(Canvas(b)); b } catch (_: Throwable) { null } }
    }
    /** What the panel shows is looked up twice a second, and at once when something was chosen on it. */
    private fun refreshHome(nowNs: Long, head: HeadTracker.State) {
        if (homeModel != null && nowNs - homeNs < 500_000_000L) return
        homeNs = nowNs
        val web = firstPage.filter { it.kind == HomeItem.Kind.WEB }; val games = firstPage.filter { it.kind == HomeItem.Kind.GAME }
        var phone = emptyList<HomeItem>(); var pages = 1
        if (homeSection == "apps") {
            val apps = installedApps ?: dev.ultraxr.shell.AppShell.installed(activity).also { installedApps = it }
            pages = maxOf(1, (apps.size + Home.APPS_A_PAGE - 1) / Home.APPS_A_PAGE); appsPage = appsPage.coerceIn(0, pages - 1)
            phone = apps.drop(appsPage * Home.APPS_A_PAGE).take(Home.APPS_A_PAGE).map { a -> HomeItem(a.label, HomeItem.Kind.APP, "app://" + a.component, appTint(a.pkg)) }
        }
        val running = HashMap<String, Int>(); for (w in desktop.windows) if (w.origin.isNotEmpty()) running[w.origin] = maxOf(running[w.origin] ?: 0, if (w.minimized) 1 else 2)
        val lastTitle = prefs.getString("last", null); val now = Date()
        val battery = try { (activity.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { -1 }
        val m = HomeModel(homeSection, web, games, phone, appsPage, pages, desktop.windows.map { Triple(it.id, it.title.ifBlank { it.origin.ifBlank { "Window" } }, it.minimized) }, running,
            (web + games).firstOrNull { it.title == lastTitle }, Tracking.roomPlanOutline, Tracking.roomPlanPlaced, head.mode != HeadTracker.Mode.NONE, trackingWord(head), battery,
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(now), SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now), ::appIcon)
        if (m.key() != homeModel?.key()) {
            val before = tiles.getOrNull(desktop.shelfIndex)?.id
            val covers = when (m.section) { "games" -> m.games.size; "apps" -> m.phoneApps.size + 2; "windows" -> m.windows.size; else -> m.web.size + m.games.size }
            if (m.section != coverSection) {      // another section: its wall begins in the middle of what was used last, or at its first cover
                coverSection = m.section
                coverCentre = if (m.section == "home") (m.web + m.games).indexOfFirst { it.title == m.last?.title }.coerceAtLeast(0) else 0
                coverGlide.value = coverCentre.toDouble()
            }
            coverCentre = coverCentre.coerceIn(0, maxOf(0, covers - 1))
            homeModel = m; tiles = Home.layout(m, coverCentre)
            // the focus stays on the same thing when the tiles change under it, and goes to the first when that thing has gone
            if (panelShown && desktop.focus is Focus.Shelf) { val i = tiles.indexOfFirst { it.id == before }; if (i < 0) desktop.point(Focus.Shelf(Home.first(tiles, coverCentre))) else if (i != desktop.shelfIndex) desktop.point(Focus.Shelf(i)) }
        }
    }
    private fun trackingWord(head: HeadTracker.State) = when { head.mode != HeadTracker.Mode.SIX_DOF -> "no position"
        (Tracking.mapMode == "finding" || Tracking.mapMode == "growing") && Tracking.appFromMap() != null -> "room recognised"; Tracking.mapMode == "making" && Tracking.mapPlaces > 0 -> "learning the room"; Tracking.roomPlanPlaced || Tracking.roomPlanCorners == 0 -> "room found"; else -> "position tracked" }

    /** Select on a tile of the panel. */
    private fun activateTile(i: Int) {
        val t = tiles.getOrNull(i) ?: return; val a = t.action
        openingFrom = t.item?.title
        try {
            when {
                a.startsWith("sec:") -> { homeSection = a.substring(4); appsPage = 0 }
                a == "settings" -> { openingFrom = "Settings"; openSettings() }
                a == "item" -> t.item?.let { activate(it) }
                a.startsWith("win:") -> a.substring(4).toIntOrNull()?.let { bringBack(it) }
                a.startsWith("sys:") -> system(a)
                a == "apps:next" -> appsPage++
                a == "apps:prev" -> appsPage--
                a == "room" -> say(if (Tracking.roomPlanCorners > 0) "The room is marked. Mark it anew from the phone: Settings, Mark the room" else "Mark the room from the phone: Settings, Mark the room")
            }
        } finally { openingFrom = null }
        homeNs = 0; onLookPress?.invoke(); changed()
    }
    /** A window that was put away comes back in front of the wearer. */
    private fun bringBack(id: Int) { stageMoving = true; desktop.restore(id); desktop.find(id)?.let { desktop.bringFront(id, headYaw(), clearPitch(it)) }; changed() }
    /** A slide on the touchpad steps the focus over the panel as the d-pad does. */
    private var padStepX = 0.0; private var padStepY = 0.0

    /** Page 0 is the first page; 1 and up are the phone's apps; -1 is the system page. */
    fun showHomePage(page: Int) {
        val per = APPS_A_PAGE
        val apps = installedApps ?: dev.ultraxr.shell.AppShell.installed(activity).also { installedApps = it }
        val pages = maxOf(1, (apps.size + per - 1) / per)
        homePage = page.coerceIn(0, pages)
        val next = if (page < 0) systemPage() else if (homePage == 0) firstPage else
            apps.drop((homePage - 1) * per).take(per).map { a -> HomeItem(a.label, HomeItem.Kind.APP, "app://" + a.component, appTint(a.pkg)) } +
                nav("Home", "nav:home") + (if (homePage < pages) listOf(nav("More", "nav:next")) else emptyList())
        // another page of marks begins at its first mark; the same page shown again (the system page after one of its marks was used) keeps its place
        val another = next.size != homeItems.size || next.indices.any { next[it].url != homeItems[it].url }
        homeItems = next
        if (another && desktop.focus is Focus.Shelf) desktop.point(Focus.Shelf(0))
        changed()
    }
    /** A game was chosen on the shelf: the activity starts it in the XR browser. */
    var onLaunchGame: ((HomeItem) -> Unit)? = null
    /** An installed immersive app (an OpenXR app, a converted Quest game) was chosen: the activity hands it the glasses. */
    var onLaunchNative: ((String) -> Unit)? = null

    /** Opens what the mark stands for. */
    fun activateHome(i: Int) {
        if (panelShown) { activateTile(i); return }
        val item = homeItems.getOrNull(i) ?: return
        openingFrom = item.title
        try {
            if (item.kind == HomeItem.Kind.NAV) {
                if (item.url.startsWith("sys:")) system(item.url)
                else if (item.url == "nav:settings") openSettings()
                else if (item.url == "nav:videos") { onPickVideo?.invoke() ?: say("No file picker here") }
                else if (item.url == "nav:panel") { homeSection = "home"; showHome() }
                else showHomePage(when (item.url) { "nav:apps" -> 1; "nav:next" -> homePage + 1; "nav:system" -> -1; else -> 0 })
                onLookPress?.invoke(); return
            }
            activate(item)
        } finally { openingFrom = null }
    }
    /** Opens a game, a web app or one of the phone's apps, and remembers it as the thing to continue with. */
    private fun activate(item: HomeItem) {
        if (item.kind != HomeItem.Kind.APP) prefs.edit().putString("last", item.title).apply()
        if (openingFrom == null) openingFrom = item.title
        run {
            if (item.kind == HomeItem.Kind.APP) {
                // an immersive app is not opened in a window: it gets the glasses, as a game does
                val pkg = item.url.removePrefix("app://").substringBefore('/')
                if (dev.ultraxr.xr.XrRuntime.nativeApp(activity, pkg) != null && onLaunchNative != null) { onLaunchNative?.invoke(pkg); onLookPress?.invoke(); return@run }
                if (dev.ultraxr.shell.AppShell.available) openApp(item.title, item.url.removePrefix("app://"))
                else { Tracking.log("apps: " + item.title + " not opened: the app helper is not running (tools/pixel/uxr_helper.sh)"); say("Phone apps need the helper: it is not running") }
                onLookPress?.invoke(); return@run
            }
            if (item.kind == HomeItem.Kind.GAME && item.url.startsWith("app://")) {
                val pkg = item.url.removePrefix("app://").substringBefore('/')
                if (onLaunchNative != null) onLaunchNative?.invoke(pkg) else Tracking.log("games: " + item.title + " not opened: no launcher for immersive apps")
            } else if (item.kind == HomeItem.Kind.GAME) onLaunchGame?.invoke(item) else (HomeApps.all.firstOrNull { it.url == item.url }?.let { open(it) } ?: open(item.title, item.url, item.webKind))
            onLookPress?.invoke()
        }
    }
    /** The name of the mark that is being opened: the window that comes of it grows from that mark. */
    private var openingFrom: String? = null
    private var ringYaw = 0.0; private var ringPitch = 0.0; private var ringDistance = 2.0; private var ringProgress = 0.0; private var ringSize = 0.0
    /** Called when something was pressed by looking, so the phone can give a haptic tick. */
    var onLookPress: (() -> Unit)? = null

    fun start() {
        dev.ultraxr.xr.XrHost.loadPictureTilt()
        try { prefs.getString("layout", null)?.let { desktop.load(JSONObject(it)) } } catch (e: Exception) { Tracking.log("desktop: saved layout ignored: $e") }
        // once: windows saved while they were raised above the dock come down to eye level with the new ones
        if (!prefs.getBoolean("lowered_1121", false)) { for (w in desktop.windows) if (!w.maximized) w.pitch = WINDOW_PITCH; desktop.touch(); prefs.edit().putBoolean("lowered_1121", true).apply() }
        // Windows are where they were left, and as they were left: open ones open, the ones put away put away.
        for (w in desktop.windows) { if (w.kind != SETTINGS && !w.minimized) ensureWeb(w); if (w.origin.isNotEmpty()) fromMark[w.id] = w.origin }
        // Windows kept from before the shelf stood 8 degrees under the line of sight are lifted clear of it, once.
        if (!prefs.getBoolean("clearOfShelf", false)) { settle(); prefs.edit().putBoolean("clearOfShelf", true).apply() }
        savedRevision = desktop.revision
        current = this
    }

    fun stop() {
        save()
        if (current === this) current = null
        for (w in web.values) w.destroy()
        for (v in video.values) v.release()
        web.clear(); video.clear(); videoMode.clear(); surfaces = HashMap()
    }

    fun pause() {
        resumed = false; save(); web.values.forEach { it.pause() }; video.values.forEach { it.pause() }
        // the map of the room being learnt is written now: the app may not be told when it is closed for good
        kotlin.concurrent.thread(name = "room-map-save", isDaemon = true) { try { Tracking.saveRoomMap() } catch (e: Throwable) { Tracking.log("room map: not saved: $e") } }
    }
    fun resume() { resumed = true; web.values.forEach { it.resume() }; video.values.forEach { it.resume() } }

    private var mapSeen = 0
    private var mapArmed = false
    private fun save() { prefs.edit().putString("layout", desktop.toJson().toString()).apply(); savedRevision = desktop.revision }

    private fun ensureWeb(w: DesktopWindow): WebWindow? {
        if (w.kind == VIDEO) { ensureVideo(w); return null }
        return ensureWebPage(w)
    }

    private fun ensureVideo(w: DesktopWindow) {
        if (video.containsKey(w.id)) return
        val uri = android.net.Uri.parse(w.url.substringBefore('#'))
        val mode = when (w.url.substringAfter('#', "")) { "sbs-half" -> VideoMode.SBS_HALF; "sbs-full" -> VideoMode.SBS_FULL; "flat" -> VideoMode.FLAT; else -> null }
        if (mode != null) videoMode[w.id] = mode
        val player = VideoPlayer(activity, w.id, uri, { vw, vh ->
            activity.runOnUiThread {
                val m = videoMode.getOrPut(w.id) { VideoMode.detect(w.title, vw, vh) }
                desktop.find(w.id)?.let { win -> win.aspect = m.aspect(vw, vh); win.url = uri.toString() + "#" + modeKey(m); applyShape(w.id); desktop.touch() }
                publishSbs(); say(m.label.replaceFirstChar { it.uppercase() }); onChanged?.invoke()
            }
        }, { msg -> activity.runOnUiThread { say(msg); close(w.id) } })
        video[w.id] = player
        surfaces[w.id]?.let { player.attach(it) }
    }
    private fun modeKey(m: VideoMode) = when (m) { VideoMode.FLAT -> "flat"; VideoMode.SBS_HALF -> "sbs-half"; VideoMode.SBS_FULL -> "sbs-full" }

    /** A video file was chosen (the phone's file picker, or a start with the file named): it opens in a window. Side by side or flat is worked out from its name and size, and can be changed by tapping the corner at the top right. */
    fun openVideo(uri: android.net.Uri, name: String): Boolean {
        val title = name.substringBeforeLast('.').ifBlank { "Video" }.take(40)
        return open(title, uri.toString(), VIDEO)
    }

    private fun ensureWebPage(w: DesktopWindow): WebWindow = web.getOrPut(w.id) {
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
            if (surface == null) { next.remove(id); web[id]?.detachSurface(); video[id]?.detachSurface() } else { next[id] = surface; web[id]?.attach(surface); video[id]?.attach(surface) }
            surfaces = next
            settingsDrawn = -1      // the settings draw themselves into their window's surface: a new one is empty
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
        // The same thing, put away earlier, comes back as it was. A window that is put away cannot be seen in the
        // glasses, so it must never be the reason a new one does not open: the one put away longest makes room.
        val from = openingFrom ?: title
        desktop.windows.firstOrNull { it.minimized && it.kind == kind && (it.origin == from || it.url == url) }?.let { old -> bringBack(old.id); return true }
        if (desktop.windows.size >= Desktop.MAX_WINDOWS) desktop.windows.firstOrNull { it.minimized }?.let { close(it.id) }
        // Worn, 21 November 2026: six windows stood somewhere in the room out of sight, nothing new would open and
        // there was no finding them to close one. A window asked for always opens: the one furthest from where the
        // wearer looks (and not the one in use) makes room, and it is said which.
        if (desktop.windows.size >= Desktop.MAX_WINDOWS) {
            val hy = headYaw()
            desktop.windows.filter { it.id != desktop.focusedId && it.kind != SETTINGS }.maxByOrNull { Math.abs(Geometry.wrap(desktop.worldYaw(it) - hy)) }?.let { far -> val t = far.title; close(far.id); say("Closed $t to make room") }
        }
        stageMoving = true      // the dock comes to where the wearer is now, so that the new window stands clear of it
        val w = desktop.add(title, url, kind, headYaw())
        if (w == null) { say("Six windows are open: close one first"); return false }
        if (w.follow) w.yaw = 0.0
        // A new window opens at the distance of the settings, as large to the eye at any distance, and as high as
        // keeps all of it in view between the status line and the shelf.
        val d = Tracking.settings.windowDistanceM.toDouble().coerceIn(DesktopWindow.MIN_DISTANCE, DesktopWindow.FAR_DISTANCE)
        w.distance = d; w.width = (DesktopWindow.DEFAULT_WIDTH * d / DesktopWindow.DEFAULT_DISTANCE).coerceIn(DesktopWindow.MIN_WIDTH, DesktopWindow.MAX_WIDTH)
        // The settings are asked for to be used now: they open straight ahead whatever stands there, nearer than the
        // windows, so that they are in front of them.
        if (kind == SETTINGS) { val near = (d * 0.7).coerceAtLeast(DesktopWindow.MIN_DISTANCE); w.width = w.width * near / d; w.distance = near; w.yaw = if (w.follow) 0.0 else headYaw() }
        w.pitch = clearPitch(w)
        w.origin = from; fromMark[w.id] = from
        if (kind != SETTINGS) ensureWeb(w)
        // on the settings the cursor begins on the row in focus, so that the two are never apart
        cursors[w.id] = if (kind == SETTINGS) doubleArrayOf(0.3, settings.rowCentre(settings.index).coerceIn(0.0, 1.0)) else doubleArrayOf(0.5, 0.5)
        changed(); return true
    }
    /** As high as a window stands to be clear of the shelf, but no higher than is comfortable to look at. */
    // Worn, 21 November 2026: "everything is still extremely high". A window used to be raised until its handle
    // stood clear of the dock, up to 8 degrees: its middle 28 cm above the eyes at 2 m and its top out of the picture.
    // A window now has its middle at eye level, and the dock steps aside under it ([shelf]).
    @Suppress("UNUSED_PARAMETER") private fun clearPitch(w: DesktopWindow) = WINDOW_PITCH
    /** The settings, as a window like any other. One at most: asked for again, it is shown. */
    fun openSettings(): Boolean {
        desktop.windows.firstOrNull { it.kind == SETTINGS }?.let { w -> if (w.minimized) desktop.restore(w.id); desktop.focus(w.id); changed(); return true }
        return open("Settings", "ultraxr://settings", SETTINGS)
    }
    /** The focused window stands up like a phone, or lies down again; it keeps its height. */
    fun toggleShape() {
        val w = desktop.focused ?: return
        val h = w.height
        w.aspect = if (w.aspect >= 0.8) 9.0 / 16.0 else 16.0 / 9.0
        w.width = (h * w.aspect).coerceIn(DesktopWindow.MIN_WIDTH, DesktopWindow.MAX_WIDTH)
        applyShape(w.id); cursors[w.id] = doubleArrayOf(0.5, 0.5); desktop.touch(); changed()
    }
    fun close(id: Int) { web.remove(id)?.destroy(); video.remove(id)?.release(); videoMode.remove(id); publishSbs(); cursors.remove(id); desktop.close(id); changed() }
    fun focus(id: Int) { desktop.focus(id); changed() }
    fun minimize(id: Int) { desktop.minimize(id); changed() }
    fun toggleMaximize(id: Int) { desktop.toggleMaximize(id); changed() }
    fun toggleFullscreen(id: Int) { desktop.toggleFullscreen(id); changed() }
    fun toggleFollow(id: Int) { desktop.toggleFollow(id); changed() }
    fun recenter() { Tracking.recenter(); desktop.gather(0.0); settle(); anchored = false; changed() }
    fun gather() { desktop.gather(headYaw()); settle(); changed() }
    /** Windows brought back in front of the wearer stand clear of the shelf, as new ones do. */
    private fun settle() { for (w in desktop.windows) if (!w.follow && !w.maximized) w.pitch = clearPitch(w); desktop.touch() }
    fun navigate(text: String) { val w = desktop.focused ?: return; if (w.kind == SETTINGS) return; val u = HomeApps.toUrl(text); w.url = u; web[w.id]?.load(u); changed() }
    fun back() { desktop.focused?.let { web[it.id]?.back() } }
    fun reload() { desktop.focused?.let { web[it.id]?.reload() } }
    fun type(text: String, enter: Boolean) { desktop.focused?.let { web[it.id]?.typeText(text, enter) } }
    fun focusedWeb(): WebWindow? = desktop.focused?.let { web[it.id] }
    fun resetLayout() { for (id in desktop.windows.map { it.id }) close(id); changed() }

    /** Focus the next window that is showing (wraps around). Returns false if there is none to move to. */
    /** The window an action from the phone is meant for: the one in focus, or else the one nearest to where the wearer looks. */
    private fun target(): DesktopWindow? = desktop.focused?.takeIf { !it.minimized } ?: run {
        val hy = headYaw(); desktop.windows.filter { !it.minimized }.minByOrNull { Math.abs(Geometry.wrap(desktop.worldYaw(it) - hy)) }
    }
    /** "close" on the phone: one press, whatever has the focus. */
    fun closeFocused() {
        val w = target() ?: run { say("No window is open"); return }
        val t = w.title; val next = FocusModel.afterClose(FocusScene(shelfCount(), desktop.windows.filter { !it.minimized }.map { FocusWindow(it.id, desktop.worldYaw(it)) }, headYaw(), desktop.shelfIndex), w.id)
        close(w.id); (next as? Focus.Window)?.let { desktop.focus(it.id) }; say("Closed $t"); changed()
    }
    /** "full" on the phone: the window fills the view and stays with the head; again, and it is back where it stood. */
    fun toggleFull() {
        val w = target() ?: run { say("No window is open"); return }
        if (moving || sizing) press(FocusKey.SELECT)
        desktop.toggleFullscreen(w.id); say(if (desktop.fullscreenId == w.id) w.title + " fills the view: press full again to put it back" else w.title + " is back in its place"); changed()
    }
    /** "here" on the phone: every window, put away or left anywhere in the room, comes in front of the wearer. */
    fun bringHere() {
        val n = desktop.windows.size
        if (n == 0) { say("No window is open"); return }
        if (desktop.fullscreenId != -1) desktop.toggleFullscreen(desktop.fullscreenId)
        for (w in desktop.windows) if (w.minimized) desktop.restore(w.id)
        gather(); target()?.let { desktop.focus(it.id) }
        say(if (n == 1) "The window is in front of you" else "$n windows are in front of you"); changed()
    }
    /** "next" on the phone: the focus goes to the next window, and it is said which, so that it can be found. */
    fun nextWindow() {
        val vis = desktop.windows.filter { !it.minimized }
        if (vis.isEmpty()) { say("No window is open"); return }
        if (moving || sizing) press(FocusKey.SELECT)
        if (vis.size == 1) desktop.focus(vis[0].id) else cycleFocus()
        desktop.focused?.let { say(it.title + " is in focus") }; changed()
    }

    fun cycleFocus(): Boolean {
        val vis = desktop.windows.filter { !it.minimized }
        if (vis.size < 2) return false
        val i = vis.indexOfFirst { it.id == desktop.focusedId }
        desktop.focus(vis[(i + 1) % vis.size].id); changed(); return true
    }

    private fun changed() { onChanged?.invoke() }

    /** One line per window: id;title;follow;minimized;maximized;fullscreen;focused;yaw deg;pitch deg;width;distance. */
    fun debugState(): String = "focus;" + desktop.focus.describe() + ";" + (if (panelShown) tiles.getOrNull(desktop.shelfIndex)?.id else homeItems.getOrNull(desktop.shelfIndex)?.title) + ";" + (if (panelShown) "panel:$homeSection" else "dock") + "|" + desktop.windows.joinToString("|") { w ->
        String.format(java.util.Locale.US, "%d;%s;%b;%b;%b;%b;%b;%.1f;%.1f;%.2f;%.2f;%.2f;%s", w.id, w.title.replace(';', ' ').replace('|', ' '), w.follow, w.minimized, w.maximized,
            desktop.fullscreenId == w.id, desktop.focusedId == w.id, Math.toDegrees(desktop.worldYaw(w)), Math.toDegrees(w.shownPitch()), w.shownWidth(), w.shownDistance(), w.shownHeight(), w.kind)
    }

    // ---- the one focus (desktop.focus): keys from a d-pad, and what follows from a press
    /** The shelf is one row of marks, the same whether or not windows are open. */
    private fun shelfCount() = if (panelShown) tiles.size else homeItems.size
    /** The tile of the panel, or the mark of the dock, a ray is on. */
    private val dockGlide = Glide(1.0); private var dockShown = true
    private fun shelfHit(o: DoubleArray, dir: DoubleArray, anchor: DoubleArray): Int? =
        if (!panelShown && !dockShown) null else if (panelShown) Home.hit(tiles, o, dir, anchor, desktop.followAnchor.yaw) else Shelf.hit(o, dir, anchor, desktop.followAnchor.yaw, shelfFirst, homeItems.size)
    /** The first mark in view: the row slides when the focus passes its end ([Shelf.first]). */
    private var shelfFirst = 0
    /** The page's picture is given the window's shape (after a free sizing, or standing a window up). */
    private fun applyShape(id: Int) { val w = desktop.find(id) ?: return; val p = DesktopWindow.pixels(w.aspect); web[id]?.setPixels(p[0], p[1]); settingsDrawn = -1 }

    /**
     * One press of a key of the focus: a direction, Select, Back, nearer or further. Everything a d-pad does goes
     * through here, and so do a pinch, a tap and a completed look when they act as Select.
     */
    fun press(key: FocusKey) {
        val was = desktop.focus
        // on the home panel the four directions lead to the tile that lies that way
        if (panelShown && was is Focus.Shelf && (key == FocusKey.LEFT || key == FocusKey.RIGHT || key == FocusKey.UP || key == FocusKey.DOWN)) {
            val n = Home.neighbour(tiles, was.index.coerceIn(0, maxOf(0, tiles.size - 1)), key, coverCentre)
            centreOn(n)
            if (n != was.index && desktop.point(Focus.Shelf(n))) { onLookPress?.invoke(); changed() }
            return
        }
        val a = desktop.press(key, shelfCount(), headYaw())
        when (a) {
            is FocusAction.OpenApp -> activateHome(a.index)
            is FocusAction.CloseWindow -> close(a.id)
            is FocusAction.PageKey -> if (isSettings(a.id)) settingsKey(a.id, a.key) else FocusKeys.pageKey(a.key)?.let { web[a.id]?.key(it) }
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
        // a page in use gets the arrows and Enter as real keys (a gamepad's A becomes Enter below); the settings take them as keys of the focus
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

    // ---- the settings, in their window (docs/INTERFACE.md, "Settings")
    private fun isSettings(id: Int) = desktop.find(id)?.kind == SETTINGS
    private val settings: SettingsList by lazy { buildSettings() }
    private var settingsDrawn = -1L; private var settingsDrawnNs = 0L
    /** Android's own "remove animations" (its animator duration scale is then 0). */
    private fun systemStill(): Boolean = try { android.provider.Settings.Global.getFloat(activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false }
    private fun setHands(on: Boolean) { Tracking.setHandTracking(on); if (on) handGestures = true }
    private fun setLooking(on: Boolean) { lookToPress = on; gazeFocus = on }

    private fun buildSettings(): SettingsList {
        val s = Tracking.settings
        fun onOff(on: Boolean) = if (on) "on" else "off"
        val version = try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        var armed = false; var forgotten = false
        return SettingsList(listOf(
            SettingRow("Eye distance", { "${Math.round(s.ipdMm)} mm" }, { "The distance between your eyes." }, step = { d -> s.ipdMm = (Math.round(s.ipdMm) + d).coerceIn(54, 74).toFloat() }),
            SettingRow("World scale", { "${Math.round(s.fovScale * 100)} %" }, { "The size XR apps are drawn at." }, step = { d -> s.fovScale = (Math.round(s.fovScale * 20f) + d).coerceIn(16, 40) / 20f }),
            SettingRow("Picture brightness", { "${Math.round(s.pictureBrightness * 100)} %" }, { "How bright the desktop is drawn." }, step = { d -> s.pictureBrightness = (Math.round(s.pictureBrightness * 10f) + d).coerceIn(3, 10) / 10f }),
            SettingRow("Window distance", { String.format(Locale.US, "%.1f m", s.windowDistanceM) }, { "How far away a new window opens." }, step = { d -> s.windowDistanceM = (Math.round(s.windowDistanceM * 10f) + d).coerceIn(10, 40) / 10f }),
            SettingRow("Hand tracking", { onOff(Tracking.handsOn && handGestures) }, { "Point with a hand, pinch to select." }, step = { d -> setHands(d > 0) }, select = { setHands(!(Tracking.handsOn && handGestures)) }),
            SettingRow("Pointer by looking", { onOff(lookToPress) }, { "Look at a thing to select it." }, step = { d -> setLooking(d > 0) }, select = { setLooking(!lookToPress) }),
            SettingRow("Reduce motion", { onOff(s.reduceMotion || systemStill()) }, { if (systemStill()) "On in Android's own settings." else "Windows open and close at once." },
                step = { d -> s.reduceMotion = d > 0; stillCheckedNs = 0 }, select = { s.reduceMotion = !s.reduceMotion; stillCheckedNs = 0 }),
            // marking is done with the phone in the hand, walking the room: there is nothing to do for it from here
            SettingRow("Mark the room", { if (Tracking.roomPlanCorners > 0) "marked" else "" }, { "Started from the phone." }),
            // forgetting cannot be undone, so it takes Select twice, and says so in the colour of deleting
            SettingRow("Forget the room", { "" }, { when { forgotten -> "The room is forgotten."; armed -> "Select again to forget the room."; Tracking.roomPlanCorners == 0 -> "No room is marked."; else -> "Forgets the room you marked." } },
                select = { if (Tracking.roomPlanCorners > 0) { if (!armed) armed = true else { Tracking.clearRoomPlan(); armed = false; forgotten = true } } },
                warn = { armed }, leave = { armed = false; forgotten = false }),
            // the saved map of the room (native/slam): what it is doing, and Select twice to forget it and learn the room anew
            SettingRow("Room memory", { when (Tracking.mapMode) { "making" -> "learning, " + Tracking.mapPlaces + " places"; "finding", "growing" -> if (Tracking.appFromMap() != null) "recognised, " + Tracking.mapPlaces + " places" else "looking"; else -> "off" } },
                { when { mapArmed -> "Select again to forget this room and learn it anew."; Tracking.mapMode == "making" -> "Look around the room. It is saved when you leave."; Tracking.mapMode == "finding" || Tracking.mapMode == "growing" -> "Windows go back where they stood. New rooms are added as you walk into them."; else -> "Needs the room scan." } },
                select = { if (!mapArmed) mapArmed = true else { Tracking.newRoomMap(); mapArmed = false } }, warn = { mapArmed }, leave = { mapArmed = false }),
            SettingRow("About", { version }, { "Ultra XR, set in Atkinson Hyperlegible." })))
    }

    /** A key while the settings are in use. The cursor goes with the row in focus, so that the two are never apart. */
    private fun settingsKey(id: Int, key: FocusKey) {
        if (settings.key(key)) onLookPress?.invoke()
        val c = cursors.getOrPut(id) { doubleArrayOf(0.5, 0.5) }; c[1] = settings.rowCentre(settings.index).coerceIn(0.0, 1.0)
    }

    /** A touch of a page at ([u], [v]): to the page or app in the window, or, on the settings, a press on a row when it is let go. */
    private fun pageTouch(id: Int, action: Int, u: Double, v: Double) {
        if (!isSettings(id)) { web[id]?.touch(action, u, v); return }
        if (action == MotionEvent.ACTION_UP && settings.tap(u, v)) onLookPress?.invoke()
    }
    private fun pageTap(id: Int, u: Double, v: Double) {
        if (isSettings(id)) { if (settings.tap(u, v)) onLookPress?.invoke(); return }
        video[id]?.let { p ->
            if (VideoPlayer.inModeCorner(u, v)) {
                val m = (videoMode[id] ?: VideoMode.FLAT).next(); videoMode[id] = m
                desktop.find(id)?.let { win -> win.aspect = m.aspect(p.videoWidth, p.videoHeight); win.url = win.url.substringBefore('#') + "#" + modeKey(m); applyShape(id); desktop.touch() }
                publishSbs(); say(m.label.replaceFirstChar { it.uppercase() })
            } else say(p.tapAt(u, v))
            onLookPress?.invoke(); return
        }
        web[id]?.tap(u, v)
    }

    /** The settings draw themselves into their window's surface, as a page does: when something changed, and once a second for what changed elsewhere. */
    private fun drawSettings(nowNs: Long) {
        val w = desktop.visible.firstOrNull { it.kind == SETTINGS } ?: return
        val surface = surfaces[w.id] ?: return
        val inUse = desktop.focus == Focus.Page(w.id)
        val state = settings.revision * 2 + (if (inUse) 1 else 0)
        if (state == settingsDrawn && nowNs - settingsDrawnNs < 1_000_000_000L) return
        try {
            val c = surface.lockCanvas(null)
            try { SettingsPage.draw(c, settings, inUse) } finally { surface.unlockCanvasAndPost(c) }
            settingsDrawn = state; settingsDrawnNs = nowNs
        } catch (e: Exception) { settingsDrawnNs = nowNs; Tracking.log("desktop: the settings could not be drawn: $e") }
    }

    // ---- touchpad (phone) input. Distances are fractions of the pad's width.
    /** A window is being carried: the touchpad and the d-pad move it until it is put down. */
    val moving: Boolean get() = desktop.focus is Focus.Moving
    /** The phone's Move button: takes up the window in focus, or puts down the one being carried. */
    fun toggleMove() { if (moving) { press(FocusKey.SELECT); return }; takeUp("Carrying %s: slide, then tap to put it down") { desktop.startMove(it) } }
    /** Move and size never do nothing without a word: no window, and it is said; filling the view, it is put back first. */
    private fun takeUp(says: String, start: (Int) -> Unit) {
        if (moving || sizing) press(FocusKey.SELECT)
        val w = target() ?: run { say("No window is open"); return }
        if (desktop.fullscreenId == w.id) desktop.toggleFullscreen(w.id)
        desktop.focus(w.id); start(w.id); say(String.format(says, w.title)); changed()
    }
    /** A window filling the view, for the phone to show. */
    val full: Boolean get() = desktop.fullscreenId != -1
    val sizing: Boolean get() = desktop.focus is Focus.Sizing
    /** The phone's Size button: the window in focus is sized by the touchpad or the d-pad until Select or the button again. */
    fun toggleSize() { if (sizing) { press(FocusKey.SELECT); return }; takeUp("Sizing %s: slide, then tap when it is right") { desktop.startSize(it) } }

    fun padMove(dx: Double, dy: Double) {
        padTouchNs = System.nanoTime()
        if (panelShown) {      // a slide steps the focus from tile to tile
            padStepX += dx; padStepY += dy
            while (Math.abs(padStepX) > PAD_STEP || Math.abs(padStepY) > PAD_STEP) {
                if (Math.abs(padStepX) >= Math.abs(padStepY)) { press(if (padStepX > 0) FocusKey.RIGHT else FocusKey.LEFT); padStepX -= Math.signum(padStepX) * PAD_STEP; padStepY *= 0.5 }
                else { press(if (padStepY > 0) FocusKey.DOWN else FocusKey.UP); padStepY -= Math.signum(padStepY) * PAD_STEP; padStepX *= 0.5 }
            }
            return
        }
        val w = desktop.focused ?: return
        val f = desktop.focus
        if (f is Focus.Moving) { desktop.move(f.id, -dx * 0.7, -dy * 0.7); return }
        // sizing: a slide to the right widens the window and a slide down makes it taller, as the d-pad does
        if (f is Focus.Sizing) { desktop.find(f.id)?.let { desktop.sizeTo(it.id, it.width * Math.exp(dx * 1.5), it.height * Math.exp(dy * 1.5)) }; return }
        // a corner is held (double-tap and hold on it): it follows the finger and the window's middle stays where it is
        padSizing?.let { s -> desktop.sizeTo(w.id, w.width * (1 + 2 * s[0] * dx * 0.9), w.height * (1 + 2 * s[1] * dy * 0.9 * w.aspect)); return }
        val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        // The cursor can leave the page downwards onto the handle's words, where a tap is Select on the word under
        // it. A press that began in the page stays in the page.
        val held = pressed || desktop.fullscreenId == w.id
        val bottom = if (held) 1.0 else 1.0 + handleSpan(w)
        c[0] = (c[0] + dx * 0.9).coerceIn(0.0, 1.0); c[1] = (c[1] + dy * 0.9 * w.aspect).coerceIn(0.0, bottom)
        if (pressed) pageTouch(w.id, MotionEvent.ACTION_MOVE, c[0], c[1])
        // the focus is on what the cursor is over: the page, or a word of the handle
        else if (desktop.point(desktop.focusAt(w, c[0], c[1]))) changed()
        // on the settings the row under the cursor is the one in focus
        if (w.kind == SETTINGS && desktop.focus is Focus.Page) settings.point(c[1])
    }
    /** The handle's height, with the space above it, as a fraction of the window's: the cursor's v runs on to 1 plus this. */
    private fun handleSpan(w: DesktopWindow) = (w.shownHandleGap() + w.shownHandleHeight()) / w.shownHeight()
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
        // the cursor may have been left on the handle while something else chose the page
        desktop.focused?.let { fw -> val c = cursors.getOrPut(fw.id) { doubleArrayOf(0.5, 0.5) }; if (desktop.focus is Focus.Page && c[1] > 1.0) desktop.point(desktop.focusAt(fw, c[0], c[1])) }
        val f = desktop.focus
        if (f !is Focus.Page) { press(FocusKey.SELECT); return }
        val w = desktop.find(f.id) ?: return; val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        pageTap(w.id, c[0], c[1])
    }

    /** Every window is put away (not closed): only the shelf is left. */
    fun showHome() { for (w in desktop.windows) if (!w.minimized) desktop.minimize(w.id); changed() }

    private var padSizing: DoubleArray? = null
    fun padPress(down: Boolean) {
        val w = desktop.focused ?: return; val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
        if (!down && padSizing != null) { padSizing = null; desktop.endDrag(); applyShape(w.id); return }
        if (down == pressed) return
        if (down && (desktop.focus is Focus.Moving || desktop.focus is Focus.Sizing)) return
        if (down && (c[1] < 0.0 || c[1] > 1.0)) return      // on the handle: it answers to a tap
        // double-tap and hold on a corner of the page takes hold of the corner: the slide that follows sizes the window
        if (down) cornerAt(w, c)?.let { padSizing = it; desktop.beginDrag(w.id); return }
        pressed = down; pageTouch(w.id, if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, c[0], c[1])
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
        val head = Tracking.head.predictSight(nowNs)
        val tracked = head.mode != HeadTracker.Mode.NONE
        val forward = if (tracked) headForward(head) else Geometry.direction(renderers.firstOrNull()?.previewYaw ?: 0.0, renderers.firstOrNull()?.previewPitch ?: 0.0)
        // The head's place in the room is used whenever there is a head: the tracker holds its last place while it
        // has no fresh one, so the windows do not jump. (Until 20 November 2026 this was off unless switched on, and
        // the desktop followed turning only.)
        val positionOk = usePosition && tracked
        val origin = if (positionOk) head.pose.p else ZERO
        desktop.positional = positionOk; desktop.here = origin
        if (!positionOk) anchored = false
        else {
            var placeFromMap = false
            if (!anchored) { anchored = true; desktop.anchorAll(); System.arraycopy(origin, 0, stage, 0, 3); stageMoving = false; placeFromMap = true }
            // The saved map of the room: once this session knows where it lies, every window that has a place in it
            // goes back there; from then on each window's place in the map is kept up to date, and saved.
            val am = Tracking.appFromMap(); val mv = Tracking.mapVersion
            if (am != null) {
                // Windows keep their places in the saved map's coordinates. In another map (the room was not recognised and
                // was scanned afresh, as in any new place) those places mean nothing: without this the windows were
                // "put back" at them, wherever that fell. They stay where they are now and are kept in the new map from here.
                val id = Tracking.mapId
                if (id != 0L && id != prefs.getLong("layout_map_id", 0L)) {
                    var n = 0
                    for (w in desktop.windows) if (w.map != null) { w.map = null; n++ }
                    prefs.edit().putLong("layout_map_id", id).apply(); placeFromMap = false; mapSeen = mv
                    if (n > 0) Tracking.log("desktop: this is another room map; $n windows stay where they are and are kept in it from now on")
                }
                if (mv != mapSeen) { placeFromMap = placeFromMap || mapSeen == 0; mapSeen = mv }
                if (placeFromMap) {
                    var n = 0
                    for (w in desktop.windows) { val m = w.map ?: continue; if (w.follow) continue
                        val a = dev.ultraxr.core.MapAlign.fromMap(am, m); w.ax = a[0]; w.ay = a[1]; w.az = a[2]; w.yaw = Geometry.wrap(a[3]); n++ }
                    if (n > 0) { desktop.touch(); Tracking.log("desktop: $n windows put back where they stood in the saved map") }
                }
                for (w in desktop.windows) if (!w.follow) {
                    val m = dev.ultraxr.core.MapAlign.toMap(am, doubleArrayOf(w.ax, w.ay, w.az), w.yaw); val old = w.map
                    if (old == null || Math.abs(old[0] - m[0]) + Math.abs(old[1] - m[1]) + Math.abs(old[2] - m[2]) > 0.02 || Math.abs(Geometry.wrap(old[3] - m[3])) > 0.01) { w.map = m; desktop.touch() }
                }
            }
            val dx = origin[0] - stage[0]; val dy = origin[1] - stage[1]; val dz = origin[2] - stage[2]
            if (Math.hypot(dx, dz) > STAGE_REACH || Math.abs(dy) > STAGE_RISE) stageMoving = true
            if (stageMoving) {
                val k = 1 - Math.exp(-dt * 3.0); stage[0] += dx * k; stage[1] += dy * k; stage[2] += dz * k
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) < 0.04) stageMoving = false
            }
        }
        val anchor = if (positionOk) stage else ZERO
        desktop.followAnchor.update(Geometry.yawOf(forward), dt)
        if (nowNs - stillCheckedNs > 1_000_000_000L) { stillCheckedNs = nowNs; still = Tracking.settings.reduceMotion || systemStill() }

        val full = desktop.fullscreenId >= 0
        val shelfYaw = desktop.followAnchor.yaw
        // the row slides only as far as it must for the mark the focus is on (or was last on) to be in view
        shelfFirst = Shelf.first(desktop.shelfIndex.coerceIn(0, maxOf(0, homeItems.size - 1)), shelfFirst, homeItems.size)
        // a window being moved or sized keeps the focus until it is put down or back: looks and rays do not take it
        val adjusting = desktop.focus is Focus.Moving || desktop.focus is Focus.Sizing
        val looking = !full && !adjusting && nowNs - padTouchNs > 700_000_000L && !handDown
        // The marks stand nearer than the windows, so a look or a ray that is on a mark is on the shelf, whatever is behind it.
        refreshHome(nowNs, head)
        if (panelShown != panelWas) {      // the panel has come or gone: on it the focus begins on its first tile, on the dock on its first mark
            panelWas = panelShown; panelSinceNs = nowNs
            desktop.shelfAt(if (panelShown) Home.first(tiles, coverCentre) else 0); shelfFirst = 0
        }
        val markLooked = if (full) null else shelfHit(origin, forward, anchor)
        // Gaze focus: the window a look comes to rest on gets the focus, unless the pad is in use. Once for each
        // arrival: while the look stays there the d-pad is free to take the focus elsewhere.
        if (gazeFocus && looking) {
            val g = if (markLooked != null) null else desktop.hit(origin, forward, anchor)
            val id = g?.window?.id ?: -1
            if (id != gazeCandidate) { gazeCandidate = id; gazeSinceNs = nowNs; gazeDone = false }
            else if (id >= 0 && !gazeDone && nowNs - gazeSinceNs > 450_000_000L) { gazeDone = true; if (desktop.look(id)) changed() }
        }
        if (handGestures && Tracking.handsOn && !full) hands(nowNs, head, origin, anchor, tracked) else { handShown = null; if (handDown) releaseHand() }
        if (backHold.due(nowNs)) leaveGame()

        // Look-to-press: the shelf's marks and the handle's words. A look that has rested on one for a moment brings
        // the focus to it; kept up until the ring has closed, it is Select. A mark takes a longer look than a word,
        // and opens by looking only when there is a head to look with (not in the phone's preview).
        var looked: String? = null; var lookedAt: Focus? = null
        if (lookToPress && looking) {
            if (markLooked != null) { if (tracked) { looked = "shelf:$markLooked"; lookedAt = Focus.Shelf(markLooked) } }
            else desktop.hit(origin, forward, anchor)?.let { g -> if (g.handleWord != null) { looked = "handle:${g.window.id}:${g.handleWord.name}"; lookedAt = Focus.Handle(g.window.id, g.handleWord); ringDistance = g.distance } }
        }
        val onShelf = lookedAt is Focus.Shelf
        ringAnchor = if (onShelf) anchor else origin
        val timer = if (onShelf) shelfDwell else dwell
        (if (onShelf) dwell else shelfDwell).update(nowNs, null)
        val fire = timer.update(nowNs, looked)
        ringProgress = if (looked == null) 0.0 else timer.progress
        if (onShelf && markLooked != null && panelShown) {      // the ring closes on the tile
            val t = tiles.getOrNull(markLooked)
            if (t != null) { ringYaw = Geometry.wrap(shelfYaw - Math.atan((t.cx / Home.W - 0.5) * Home.WIDTH / Home.DISTANCE)); ringPitch = Home.PITCH + Math.atan((0.5 - t.cy / Home.H) * Home.HEIGHT / Home.DISTANCE) }
            ringDistance = Home.DISTANCE; ringSize = 0.07
        } else if (onShelf && markLooked != null) {      // the ring closes round the mark itself
            ringYaw = Geometry.wrap(shelfYaw + Shelf.yawOffset((markLooked - shelfFirst).toDouble(), Shelf.shown(homeItems.size))); ringPitch = Shelf.PITCH
            ringDistance = Shelf.DISTANCE; ringSize = 2 * Shelf.MARK_RADIUS * Shelf.FOCUS_SCALE * 1.2
        } else { ringYaw = Geometry.yawOf(forward); ringPitch = Geometry.pitchOf(forward); ringSize = 0.0 }
        if (lookedAt != null && timer.progress > 0.0 && desktop.point(lookedAt)) changed()
        // the completed look is Select, and Select acts on the thing in focus: nothing happens if the focus has gone elsewhere meanwhile
        if (fire != null && lookedAt != null) { desktop.point(lookedAt); if (desktop.focus == lookedAt) press(FocusKey.SELECT) }
        if (desktop.focus != loggedFocus) { loggedFocus = desktop.focus; Tracking.log("desktop: focus on " + desktop.focus.describe()) }

        if (desktop.revision != savedRevision && !handDown && !pressed && padSizing == null && !adjusting) save()
        if (nowNs - lastPerfNs > 5_000_000_000L) {
            lastPerfNs = nowNs
            renderers.firstOrNull()?.let { r -> if (r.frames > 0) Tracking.log(String.format(java.util.Locale.US, "desktop: %.0f fps, %.1f ms per frame on the CPU, %d late of %d frames, %d windows, %dx%d", r.fps, r.avgCpuMs, r.lateFrames, r.frames, desktop.visible.size, r.width, r.height)) }
            // what the hand tracker is doing, whether or not the desktop makes a pointer of it
            val hj = Tracking.handJoints; val newest = hj.maxOfOrNull { it.timestampNs } ?: 0L
            Tracking.log("desktop hands: switch " + (if (Tracking.handsOn) "on" else "off") + ", gestures " + (if (handGestures) "on" else "off") + ", tracker results ${Tracking.handsFrames}, " +
                String.format(java.util.Locale.US, "%.0f ms each", Tracking.handsProcessMs) + ", hands in the newest ${hj.size}" + (if (newest != 0L) ", its picture " + (nowNs - newest) / 1_000_000 + " ms old" else "") +
                (if (Tracking.handsError.isNotEmpty()) ", error: " + Tracking.handsError else "") + ", head " + head.mode)
        }
        if (desktop.revision != lastUiRevision) { lastUiRevision = desktop.revision; changed() }
        drawSettings(nowNs)
        publish(nowNs, anchor, positionOk, head)
    }
    private var lastUiRevision = -1L
    private var stereoNote = ""
    private var lastPerfNs = 0L

    private fun hands(nowNs: Long, head: HeadTracker.State, origin: DoubleArray, anchor: DoubleArray, tracked: Boolean) {
        val made = madeUpHand(nowNs, head)
        // A hand that is working the phone is not pointing at the desktop (worn, 21 November 2026: the hand that held
        // the phone, low in the cameras' view, was followed and read as pinching, and moved the focus about).
        val onPhone = made == null && nowNs - phoneTouchNs < PHONE_HAND_NS
        val aim = if (onPhone) { pointer.reset(); null } else if (tracked || made != null) pointer.update(nowNs, made ?: Tracking.handJoints, head.pose.p, head.pose.q) else null
        if (nowNs - handLogNs > 5_000_000_000L) {      // every five seconds in which a hand was seen: what the pointer makes of it
            if (handSeenFrames > 0) Tracking.log("desktop: a hand in $handSeenFrames frames, ${pointer.results} results and ${pointer.pinches} pinches so far" +
                (aim?.let { String.format(Locale.US, ", %s hand, fingertips %.0f mm apart, %s, on %s", if (it.right) "right" else "left", it.gapM * 1000, if (it.pinching) "pinching" else "open", handTarget?.describe() ?: "nothing") } ?: ""))
            handLogNs = nowNs; handSeenFrames = 0
        }
        if (aim == null) { handShown = null; lastHandRay = null; handTarget = null; if (handDown) releaseHand(); return }
        handSeenFrames++
        // Hand positions are in the app world. Without position the view sits at the origin, so the hand is taken relative to the head.
        val sx = origin[0] - head.pose.p[0]; val sy = origin[1] - head.pose.p[1]; val sz = origin[2] - head.pose.p[2]
        fun seen(p: DoubleArray) = doubleArrayOf(p[0] + sx, p[1] + sy, p[2] + sz)
        val o = seen(aim.origin); val dir = aim.dir; val pinching = aim.pinching
        lastHandRay = o to dir
        // what the wearer is shown of the hand: its two fingertips, the ray, and where the ray lands
        var reach = 0.0; var onYaw = 0.0; var onPitch = 0.0
        // a tip outside what the display shows is drawn at the picture's edge on its side, as the test scenes do for a hand
        val inv = head.pose.inverse()
        fun tip(p: DoubleArray): DoubleArray {
            val m = dev.ultraxr.core.HandEdge.mark(inv.apply(p), Tracking.displayTanH, Tracking.displayTanV) ?: return seen(p)
            val r = dev.ultraxr.core.Q.rotate(head.pose.q, m); return doubleArrayOf(origin[0] + r[0], origin[1] + r[1], origin[2] + r[2])
        }
        fun show() { handShown = RenderHand(tip(aim.thumb), tip(aim.index), o, dir, origin, reach, onYaw, onPitch, pinching) }
        val f = desktop.focus
        if (handGrab >= 0) {      // a window held by its handle follows the hand until the fingers part
            val w = desktop.find(handGrab)
            if (pinching && w != null) {
                desktop.aim(w.id, Geometry.wrap(Geometry.yawOf(dir) + grabYaw), Geometry.pitchOf(dir) + grabPitch)
                // The held hand pushed away or drawn in carries the window further or nearer: 10 cm of the hand is half
                // as far again (or two thirds as far). The first 3 cm either way do nothing, so that carrying a window
                // sideways does not also push it, and the window follows smoothly: the hand's depth is the noisiest
                // thing the tracker reports.
                val hp = head.pose.p; val reach = Math.sqrt((aim.origin[0] - hp[0]) * (aim.origin[0] - hp[0]) + (aim.origin[1] - hp[1]) * (aim.origin[1] - hp[1]) + (aim.origin[2] - hp[2]) * (aim.origin[2] - hp[2]))
                if (grabReach < 0) { grabReach = reach; grabDistance = w.distance }
                val d = reach - grabReach; val past = if (Math.abs(d) < PUSH_FREE_M) 0.0 else d - Math.signum(d) * PUSH_FREE_M
                val to = (grabDistance * Math.exp(past * PUSH_GAIN)).coerceIn(DesktopWindow.MIN_DISTANCE, DesktopWindow.FAR_DISTANCE)
                val next = w.distance + (to - w.distance) * 0.2
                if (Math.abs(next - w.distance) > 0.002) desktop.push(w.id, next / w.distance)
            } else releaseHand()
            show(); return
        }
        if (f is Focus.Moving || f is Focus.Sizing) {
            // a window taken up by pinching "move" follows the hand's ray; a pinch is Select: it puts the window down
            if (f is Focus.Moving && moveByHand && !pinching) desktop.aim(f.id, Geometry.yawOf(dir), Geometry.pitchOf(dir))
            if (pinching && !handDown) { press(FocusKey.SELECT); handDown = true; handDownHit = null; handTile = -1; handCorner = null }      // the rest of this pinch does nothing more
            else if (!pinching && handDown) releaseHand()
            show(); return
        }
        if (handSizeWindow >= 0) {   // a corner is held: it follows the ray, and the window's middle stays where it is
            val w = desktop.find(handSizeWindow)
            if (pinching && w != null) planePoint(w, o, dir, anchor)?.let { desktop.sizeTo(w.id, 2 * Math.abs(it[0]), 2 * Math.abs(it[1])) }
            else releaseHand()
            show(); return
        }
        // a ray that is on a mark is on the shelf, whatever is behind the mark
        // The ray starts at the hand, lower than the eyes, so on its way to a window it can pass through the dock (and
        // the other way about): of a mark and a window that both lie on it, the hand is on the nearer.
        var tile = shelfHit(o, dir, anchor) ?: -1
        var hit = desktop.hit(o, dir, anchor, HAND_BAND)
        if (tile >= 0) {
            val yaw = desktop.followAnchor.yaw; val pitch = if (panelShown) Home.PITCH else Shelf.PITCH; val far = if (panelShown) Home.DISTANCE else Shelf.DISTANCE
            val t = Geometry.planeHit(o, dir, Geometry.centre(anchor, yaw, pitch, far), yaw, pitch)?.get(2) ?: 0.0
            if (hit != null && hit.distance < t) tile = -1 else { hit = null; reach = t; onYaw = yaw; onPitch = pitch }
        }
        if (hit != null) { reach = hit.distance; onYaw = desktop.worldYaw(hit.window); onPitch = hit.window.shownPitch() }
        // The ray's end is always shown, on nothing too (worn, 21 November 2026: beside a window the pointer was gone,
        // and with it any sign that the hand was still followed): as far off as the nearest window, facing the hand.
        else if (tile < 0) { reach = desktop.visible.minOfOrNull { it.shownDistance() } ?: 2.0; onYaw = Geometry.yawOf(dir); onPitch = Geometry.pitchOf(dir) }
        show()
        // beside a window the focus stays on whatever part of that window it was on
        val target: Focus? = if (hit != null) (if (hit.frame && desktop.focus.windowId == hit.window.id) desktop.focus else desktop.focusAt(hit)) else if (tile >= 0) Focus.Shelf(tile) else null
        // The ray moves the focus when it comes over something new, not while it rests there: the d-pad may have moved on
        // since. And only once it has rested there a moment: worn, the focus went from the handle to the window to the page
        // to the dock and back with every tremble of the arm ("stuff keep changing").
        if (target != handTarget) { handTarget = target; handTargetNs = nowNs; handPointed = false }
        if (!handPointed && !handDown && nowNs - handTargetNs >= HOVER_NS) { handPointed = true; if (target != null && desktop.point(target)) changed() }
        if (hit != null && hit.onPage) {
            cursors[hit.window.id] = doubleArrayOf(hit.u, hit.v)
            if (hit.window.kind == SETTINGS && !handDown && desktop.focus is Focus.Page) settings.point(hit.v)      // the row under the ray is the one in focus
        }
        if (pinching && !handDown) {
            handDown = true; handDownHit = hit; handTile = tile; handCorner = null; handDownDir = dir.copyOf()
            if (target != null && desktop.point(target)) changed()
            if (hit != null) {
                // on a corner it is decided when the hand moves: a drag sizes the window, a pinch let go where it began is a touch
                if (hit.corner >= 0) handCorner = doubleArrayOf((hit.u - 0.5) * hit.window.shownWidth(), (0.5 - hit.v) * hit.window.shownHeight())
                else if (hit.onPage) pageTouch(hit.window.id, MotionEvent.ACTION_DOWN, hit.u, hit.v)
            }
        } else if (pinching && handDown) {
            val d = handDownHit; val start = handCorner; val from = handDownDir
            // by the handle, or by its edge anywhere round it
            if (d != null && (d.onHandle || (d.frame && d.corner < 0)) && from != null && Math.acos((from[0] * dir[0] + from[1] * dir[1] + from[2] * dir[2]).coerceIn(-1.0, 1.0)) > GRAB_ANGLE) {
                // pinched on the handle and carried off: the window comes along, and stays where the fingers let it go
                desktop.beginDrag(d.window.id); handGrab = d.window.id; grabReach = -1.0
                grabYaw = Geometry.wrap(desktop.worldYaw(d.window) - Geometry.yawOf(dir)); grabPitch = d.window.pitch - Geometry.pitchOf(dir)
                Tracking.log("desktop: window ${d.window.id} taken up by the hand")
            } else if (d != null && start != null) {
                val p = planePoint(d.window, o, dir, anchor)
                if (p != null && Math.hypot(p[0] - start[0], p[1] - start[1]) > CORNER_DRAG_M) { handCorner = null; handSizeWindow = d.window.id; desktop.beginDrag(d.window.id); Tracking.log("desktop: window ${d.window.id} sized by the hand") }
            } else if (d != null && d.onPage && hit != null && hit.window.id == d.window.id) pageTouch(d.window.id, MotionEvent.ACTION_MOVE, hit.u.coerceIn(0.0, 1.0), hit.v.coerceIn(0.0, 1.0))
        } else if (!pinching && handDown) {
            val d = handDownHit
            if (d != null && d.handleWord != null) {
                // A pinch on a word of the handle is Select on that word. The hand need not still be on the word when it
                // lets go, only not on something else (opening the fingers moves the ray; carried further than that, the
                // pinch took the window up instead and never comes here).
                val word = Focus.Handle(d.window.id, d.handleWord)
                if (hit == null || hit.window.id == d.window.id) { desktop.point(word); if (desktop.focus == word) { press(FocusKey.SELECT); moveByHand = desktop.focus is Focus.Moving } }
            }
            else if (d != null && handCorner != null) { if (d.onPage) pageTap(d.window.id, d.u, d.v) }
            else if (d != null && d.onPage) { val c = cursors[d.window.id] ?: doubleArrayOf(d.u, d.v); pageTouch(d.window.id, MotionEvent.ACTION_UP, c[0], c[1]); handDownHit = null }
            else if (d == null && handTile >= 0 && tile == handTile) { val mark = Focus.Shelf(tile); desktop.point(mark); if (desktop.focus == mark) press(FocusKey.SELECT) }
            // with nothing open, a pinch at nothing is Select on the mark in focus: looked at, it opens without aiming the hand as well
            else if (d == null && handTile < 0 && tile < 0 && hit == null && desktop.visible.isEmpty() && desktop.focus is Focus.Shelf) press(FocusKey.SELECT)
            releaseHand()
        }
    }

    /**
     * Debug builds only: a made-up right hand, so that the hand's whole path through the desktop (ray, ring, pinch,
     * carrying, sizing) can be tested on the phone without a hand. files/hand_sim.txt holds "yaw pitch gap": where its
     * arm points, in degrees left of and above straight ahead in the world, and the millimetres between its fingertips.
     */
    private val madeUpFile: java.io.File? by lazy { if (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) java.io.File(activity.filesDir, "hand_sim.txt") else null }
    private var madeUp: DoubleArray? = null; private var madeUpNs = 0L
    private fun madeUpHand(nowNs: Long, head: HeadTracker.State): List<dev.ultraxr.core.Hand>? {
        val file = madeUpFile ?: return null
        if (nowNs - madeUpNs > 100_000_000L) {
            madeUpNs = nowNs
            madeUp = try { if (file.isFile) file.readText().trim().split(" ").map { it.toDouble() }.toDoubleArray().takeIf { it.size >= 3 } else null } catch (_: Exception) { null }
        }
        val m = madeUp ?: return null
        val hp = head.pose.p
        var f = headForward(head); var fl = Math.hypot(f[0], f[2]); if (fl < 0.2) { f = doubleArrayOf(0.0, 0.0, -1.0); fl = 1.0 }
        val fx = f[0] / fl; val fz = f[2] / fl
        val sh = doubleArrayOf(hp[0] - fz * 0.17 - fx * 0.06, hp[1] - 0.22, hp[2] + fx * 0.17 - fz * 0.06)      // the right shoulder, as ArmRay takes it
        val d = Geometry.direction(Math.toRadians(m[0]), Math.toRadians(m[1]))
        val to = doubleArrayOf(hp[0] + d[0] * 2.0 - sh[0], hp[1] + d[1] * 2.0 - sh[1], hp[2] + d[2] * 2.0 - sh[2]); val n = Math.sqrt(to[0] * to[0] + to[1] * to[1] + to[2] * to[2])
        val u = doubleArrayOf(to[0] / n, to[1] / n, to[2] / n); val side = doubleArrayOf(-u[2], 0.0, u[0])
        val far = if (m.size >= 4) m[3] else 0.5
        val palm = doubleArrayOf(sh[0] + u[0] * far, sh[1] + u[1] * far, sh[2] + u[2] * far)
        val j = FloatArray(dev.ultraxr.core.Hand.JOINTS * 3)
        fun put(i: Int, along: Double, across: Double, up: Double = 0.0) { j[i * 3] = (palm[0] + u[0] * along + side[0] * across).toFloat(); j[i * 3 + 1] = (palm[1] + u[1] * along + up).toFloat(); j[i * 3 + 2] = (palm[2] + u[2] * along + side[2] * across).toFloat() }
        for (i in 0 until dev.ultraxr.core.Hand.JOINTS) put(i, 0.0, 0.0)
        put(dev.ultraxr.core.Hand.WRIST, -0.04, 0.0); put(dev.ultraxr.core.Hand.LITTLE_MCP, 0.02, 0.02); put(dev.ultraxr.core.Hand.MIDDLE_MCP, 0.03, 0.0)
        put(5, 0.02, -0.02); put(6, 0.045, -0.02); put(7, 0.065, -0.02); put(8, 0.085, -0.02)
        put(dev.ultraxr.core.Hand.THUMB_TIP, 0.085, -0.02, -m[2] / 1000.0)
        return listOf(dev.ultraxr.core.Hand(true, j, nowNs))
    }

    /** Where a ray meets the plane of window [w]: metres to the right of and above its middle, also beyond its edges. */
    private fun planePoint(w: DesktopWindow, o: DoubleArray, dir: DoubleArray, anchor: DoubleArray): DoubleArray? {
        val yaw = desktop.worldYaw(w)
        return Geometry.planeHit(o, dir, Geometry.centre(desktop.anchorFor(w, anchor), yaw, w.shownPitch(), w.shownDistance()), yaw, w.shownPitch())
    }

    private fun releaseHand() {
        val d = handDownHit
        if (handDown && d != null && d.onPage && handCorner == null && handSizeWindow < 0) pageTouch(d.window.id, MotionEvent.ACTION_CANCEL, d.u, d.v)
        if (handSizeWindow >= 0) { desktop.endDrag(); applyShape(handSizeWindow) }      // the window keeps the size the hand gave it
        if (handGrab >= 0) { desktop.find(handGrab)?.let { Tracking.log(String.format(Locale.US, "desktop: window %d put down by the hand at %.1f degrees left, %.1f up, %.2f m away (it was taken up at %.2f m)", it.id, Math.toDegrees(desktop.worldYaw(it)), Math.toDegrees(it.pitch), it.distance, grabDistance)) }; desktop.endDrag(); handGrab = -1 }      // and the place
        handDown = false; handDownHit = null; handTile = -1; handCorner = null; handSizeWindow = -1; handDownDir = null
    }

    // ---- what moves (docs/INTERFACE.md, "Opening and closing"; Motion says for how long)
    /** Nothing moves by itself: the setting here, or Android's own. Looked up once a second. */
    private var still = false; private var stillCheckedNs = 0L
    /** The windows as they stood in the last frame, to see which have come and which have gone. */
    private var shown: Map<Int, RenderWindow> = emptyMap()
    private var lastFull = -1
    /** When a window began to grow from its mark, and the windows that are shrinking back into theirs. */
    private val born = HashMap<Int, Long>()
    private class Leaving(val window: RenderWindow, val startNs: Long, val markYaw: Double)
    private val leaving = HashMap<Int, Leaving>()
    /** The name of the mark each window came from (and goes back into). */
    private val fromMark = HashMap<Int, String>()
    /** How far each window's brackets are lit, each mark is enlarged, and the row has slid: they follow the focus in [Motion.FOCUS_NS]. */
    private val glows = HashMap<Int, Glide>(); private val swells = HashMap<Int, Glide>(); private val slide = Glide(0.0)
    private var swellsFor: List<HomeItem>? = null

    /** Which way the mark called [title] lies: its place in the row if it is in view, the nearer end of the row if it is not, the middle if there is none. */
    private fun markYaw(title: String?): Double {
        val n = Shelf.shown(homeItems.size); val i = homeItems.indexOfFirst { it.title == title }
        val slot = if (i < 0 || n == 0) (n - 1) / 2.0 else (i - shelfFirst).coerceIn(0, n - 1).toDouble()
        return Geometry.wrap(desktop.followAnchor.yaw + Shelf.yawOffset(slot, maxOf(n, 1)))
    }

    /** A window on its way between its mark ([e] 0) and its place ([e] 1): no brackets, no words, only the page (or its outline) growing. */
    private fun between(r: RenderWindow, markYaw: Double, e: Double): RenderWindow {
        val k = e.coerceIn(0.03, 1.0)
        return RenderWindow(r.id, Geometry.wrap(markYaw + Geometry.wrap(r.yaw - markYaw) * e), Motion.mix(Shelf.PITCH, r.pitch, e), Motion.mix(Shelf.DISTANCE, r.distance, e), r.width * k, r.height * k,
            r.focused, 0.5, 0.5, false, false, false, bracket = 0f, arm = r.arm, growing = true, pixelW = r.pixelW, pixelH = r.pixelH)
    }

    private fun publish(nowNs: Long, anchor: DoubleArray, positionOk: Boolean, head: HeadTracker.State) {
        val full = desktop.fullscreenId
        val list = ArrayList<RenderWindow>()
        val resting = HashMap<Int, RenderWindow>()
        val fo = desktop.focus
        // a window that fills the view hides the others and shows them again: that is not opening and closing
        val moves = !still && full == lastFull; lastFull = full
        for (w in desktop.visible) {
            val c = cursors.getOrPut(w.id) { doubleArrayOf(0.5, 0.5) }
            anchorsShown[w.id] = desktop.anchorFor(w, anchor)
            val focused = w.id == desktop.focusedId
            if (w.kind != SETTINGS && !web.containsKey(w.id)) ensureWeb(w)      // a window put away before the desktop was started loads its page when it is brought back
            // the settings have no page of their own making the picture: its size follows the window's shape directly
            val shape = if (w.kind == SETTINGS) DesktopWindow.pixels(w.aspect) else null
            val pw = shape?.get(0) ?: web[w.id]?.pixelWidth ?: WebWindow.PIXEL_WIDTH; val ph = shape?.get(1) ?: web[w.id]?.pixelHeight ?: WebWindow.PIXEL_HEIGHT
            if (w.id == full) {
                val tanH = Tracking.displayTanH; val tanV = Tracking.displayTanV
                val d = 2.0; val width = minOf(2 * d * tanH, 2 * d * tanV * w.aspect) * 0.98
                val r = RenderWindow(w.id, 0.0, 0.0, d, width, width / w.aspect, true, c[0], c[1], true, pressed, true, pixelW = pw, pixelH = ph)
                resting[w.id] = r; list += r; born.remove(w.id); continue
            }
            // What shows the focus: the brackets, lit, when it is on the window itself (or the window is being moved or
            // sized); the cursor when its page is in use; the lit word when it is on the handle. The cursor also shows
            // where the touchpad took it onto the handle.
            val inFocus = fo.windowId == w.id
            val adjusting = fo is Focus.Moving || fo is Focus.Sizing
            val lit = inFocus && (fo is Focus.Window || adjusting)
            val glow = glows.getOrPut(w.id) { Glide(if (lit) 1.0 else 0.0) }.toward(if (lit) 1.0 else 0.0, nowNs, Motion.FOCUS_NS, still).toFloat()
            val showCursor = inFocus && !adjusting && (fo is Focus.Page || c[1] > 1.0)
            val r = RenderWindow(w.id, desktop.worldYaw(w), w.shownPitch(), w.shownDistance(), w.shownWidth(), w.shownHeight(), focused, c[0], c[1], showCursor, pressed || handDown, false,
                glow, inFocus, 1f, w.shownCornerReach(), if (inFocus) titlePicture(w) else null, if (inFocus) handlePicture(w, fo) else null, w.shownHandleWidth(), w.shownHandleHeight(), w.shownHandleGap(), false, pw, ph, corners = inFocus && fo is Focus.Sizing)
            resting[w.id] = r; leaving.remove(w.id)
            if (!fromMark.containsKey(w.id)) fromMark[w.id] = w.title
            // a window that was not there in the last frame grows from its mark; when it has grown, its brackets draw themselves
            if (moves && !shown.containsKey(w.id) && !born.containsKey(w.id)) born[w.id] = nowNs
            val began = born[w.id]
            if (began == null || still) { born.remove(w.id); list += r; continue }
            val t = Motion.progress(began, nowNs, Motion.OPEN_NS, false)
            val b = Motion.progress(began + Motion.OPEN_NS, nowNs, Motion.BRACKET_NS, false)
            if (t < 1.0) list += between(r, markYaw(fromMark[w.id]), Motion.ease(t))
            else if (b < 1.0) list += RenderWindow(r.id, r.yaw, r.pitch, r.distance, r.width, r.height, r.focused, r.cursorU, r.cursorV, r.showCursor, r.pressed, false,
                r.glow, r.near, Motion.ease(b).toFloat(), r.arm, null, null, pixelW = r.pixelW, pixelH = r.pixelH)
            else { born.remove(w.id); list += r }
        }
        // a window that was there in the last frame and is not now (closed, or put away) shrinks back into its mark
        if (moves) for ((id, r) in shown) if (!resting.containsKey(id) && !leaving.containsKey(id) && !r.headLocked) leaving[id] = Leaving(r, nowNs, markYaw(fromMark[id]))
        val gone = leaving.entries.iterator()
        while (gone.hasNext()) {
            val g = gone.next().value; val t = Motion.progress(g.startNs, nowNs, Motion.CLOSE_NS, still || full >= 0)
            if (t >= 1.0) { gone.remove(); if (desktop.find(g.window.id) == null) { fromMark.remove(g.window.id); glows.remove(g.window.id) } }
            else list += between(g.window, g.markYaw, 1 - Motion.ease(t))
        }
        shown = resting

        val frame = RenderFrame(list, anchor, if (full < 0) shelf(nowNs, fo, head) else null, positionOk, if (dimUnfocused) 0.55f else 1f, Tracking.settings.pictureBrightness,
            dwellYaw = ringYaw, dwellPitch = ringPitch, dwellDistance = ringDistance, dwellProgress = ringProgress, dwellSize = ringSize,
            anchors = HashMap(anchorsShown), hand = if (full < 0) handShown else null, dwellAnchor = ringAnchor)
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
            HeadTracker.Mode.ORIENTATION_ONLY -> if (positionOk) "Position held" else "Rotation tracking"
            HeadTracker.Mode.SIX_DOF -> if (positionOk) "Position tracking" else "Rotation tracking"
        }) + stereoNote
    }

    /** The shelf for this frame: the marks in view, the one in focus a quarter larger with its name beneath, and the status line above. */
    private fun shelf(nowNs: Long, fo: Focus, head: HeadTracker.State): RenderShelf {
        val yaw = desktop.followAnchor.yaw
        val pm = homeModel
        if (panelShown && pm != null) {
            val ts = tiles; val t = if (fo is Focus.Shelf) ts.getOrNull(fo.index) else null
            val panel = pictures[pm.key()] ?: run { val drop = pictures.keys.filter { it.startsWith("panel|") }; for (k in drop) pictures.remove(k); picture(pm.key()) { Home.draw(pm, ts) } }
            val arrive = Motion.ease(Motion.progress(panelSinceNs, nowNs, Motion.OPEN_NS * 2, still))
            // ---- the wall of covers: each stands by itself, by how many places it is from the middle while the wall turns
            val k = Home.WIDTH / Home.W; val count = Home.coverCount(ts)
            val pos = coverGlide.toward(coverCentre.toDouble(), nowNs, Motion.COVER_NS, still)
            val seconds = nowNs * 1e-9
            val pieces = ArrayList<RenderPiece>(8)
            for (i in 0 until count) {
                val slot = Home.slot(i, pos, count); if (Math.abs(slot) > 2.6) continue
                val tile = ts[Home.SIDE + i]; val inFocus = fo is Focus.Shelf && fo.index == Home.SIDE + i
                // a cover the focus is on comes forward a little wherever it stands (a ray or a look can be on one at the side)
                val pop = coverPop.getOrPut(tile.id) { Glide(0.0) }.toward(if (inFocus) 1.0 else 0.0, nowNs, Motion.FOCUS_NS * 2, still)
                val scale = Home.coverScale(slot) * (1 + 0.07 * pop * minOf(1.0, Math.abs(slot)))
                val w = Home.COVER_W * scale * k; val h = Home.COVER_H * scale * k
                val bob = if (still) 0.0 else pop * Math.sin(seconds * 1.7) * 0.004      // the one in focus floats, slowly
                val x = (Home.coverX(slot) - Home.W / 2.0) * k; val y = (Home.H / 2.0 - Home.COVER_Y) * k + bob
                // arriving, the wall comes forward out of the room, the outer covers last
                val depth = Home.coverDepth(slot) + 0.07 * pop - (1 - arrive) * (0.5 + 0.25 * Math.abs(slot))
                val sprite = picture("cover|${pm.section}|${tile.id}|${tile.label}|${tile.sub}|${tile.running}|${tile.chosen}|$inFocus") { Home.cover(tile, pm, inFocus) }
                pieces += RenderPiece(sprite, x, y, w, h, Home.coverTurn(slot), depth, (Home.coverAlpha(slot) * arrive + (1 - Home.coverAlpha(slot)) * pop * 0.6).toFloat())
                if (pop > 0.01) {
                    val pulse = if (still) 1.0 else 0.88 + 0.12 * Math.sin(seconds * 1.1)
                    pieces += RenderPiece(picture("coverglow") { Home.glow() }, x, y, w * 2.5, h * 1.75, Home.coverTurn(slot), depth - 0.1, (0.9 * pop * pulse * arrive).toFloat())
                }
            }
            val flat = t == null || (fo as Focus.Shelf).index in Home.SIDE until Home.SIDE + count      // a cover shows the focus itself
            if (flat) return RenderShelf(emptyList(), null, 0.0, statusPicture(nowNs, head, true), yaw, panel = panel, panelAlpha = arrive.toFloat(), pieces = pieces)
            t!!
            // the light glides from tile to tile
            val x = ringGlide[0].toward(t.cx.toDouble(), nowNs, Motion.FOCUS_NS, still); val y = ringGlide[1].toward(t.cy.toDouble(), nowNs, Motion.FOCUS_NS, still)
            val w = ringGlide[2].toward(t.w.toDouble(), nowNs, Motion.FOCUS_NS, still); val h = ringGlide[3].toward(t.h.toDouble(), nowNs, Motion.FOCUS_NS, still)
            val ring = picture("ring|${t.w}|${t.h}|${t.radius}") { Home.ring(t.w, t.h, t.radius) }
            return RenderShelf(emptyList(), null, 0.0, statusPicture(nowNs, head, true), yaw, panel = panel, ring = ring,
                ringX = (x - Home.W / 2.0) * k, ringY = (Home.H / 2.0 - y) * k, ringW = (w + 2 * Home.RING_PAD) * k, ringH = (h + 2 * Home.RING_PAD) * k, panelAlpha = arrive.toFloat(), pieces = pieces)
        }
        val items = homeItems; val n = Shelf.shown(items.size)
        if (swellsFor !== items) { swellsFor = items; swells.clear(); slide.value = shelfFirst.toDouble() }      // another page of marks: nothing of the old one is left to move
        val first = slide.toward(shelfFirst.toDouble(), nowNs, Motion.FOCUS_NS, still)
        val on = if (fo is Focus.Shelf && items.isNotEmpty()) fo.index.coerceIn(0, items.size - 1) else -1
        val marks = ArrayList<RenderMark>(n + 1); var name: Sprite? = null; var nameYaw = 0.0
        // Worn, 21 November 2026: "bottom nav menu is too high overlaping with other screens". It cannot go lower (the
        // name under a mark ends where the picture does), so it steps aside: while a window comes down over the dock
        // the dock is not drawn, unless it is in use (the focus or the hand is on it) or looked down at.
        val reach = Shelf.SPACING * (n - 1) / 2 + Shelf.MARK_ANGLE * 2.2
        val covered = desktop.visible.any { w -> w.id != desktop.fullscreenId && w.shownPitch() - Math.atan(w.shownBelow() / w.shownDistance()) < Shelf.TOP &&
            Math.abs(Geometry.wrap(desktop.worldYaw(w) - yaw)) < reach + Math.atan(w.shownWidth() / 2 / w.shownDistance()) }
        dockShown = !covered || fo is Focus.Shelf || handTarget is Focus.Shelf || Geometry.pitchOf(headForward(head)) < DOCK_LOOK
        val show = dockGlide.toward(if (dockShown) 1.0 else 0.0, nowNs, Motion.FOCUS_NS, still).toFloat()
        for ((i, item) in items.withIndex()) {
            val slot = i - first
            val swell = swells.getOrPut(i) { Glide(if (i == on) 1.0 else 0.0) }.toward(if (i == on) 1.0 else 0.0, nowNs, Motion.FOCUS_NS, still)
            if (slot <= -1.0 || slot >= n) continue
            // a mark sliding out of the row fades as it goes, and the one sliding in comes up
            val alpha = (if (slot < 0) 1 + slot else if (slot > n - 1) n - slot else 1.0).toFloat()
            val at = Geometry.wrap(yaw + Shelf.yawOffset(slot, n))
            // a mark that has a window says so: a Tide dot under it, hollow while the window is put away
            val has = desktop.windows.filter { it.origin == item.title }; val running = if (has.isEmpty()) 0 else if (has.any { !it.minimized }) 2 else 1
            if (show > 0.02f) marks += RenderMark(at, 1 + (Shelf.FOCUS_SCALE - 1) * swell, markPicture(item, i == on, running), alpha * show)
            if (i == on && show > 0.5f) { name = namePicture(item.title); nameYaw = at }
        }
        val more = n - 0.4      // a dot a little past the last mark where the row goes on
        return RenderShelf(marks, name, nameYaw, statusPicture(nowNs, head), yaw,
            if (shelfFirst > 0 && show > 0.5f) Geometry.wrap(yaw + Shelf.yawOffset(-0.6, n)) else Double.NaN, if (shelfFirst + n < items.size && show > 0.5f) Geometry.wrap(yaw + Shelf.yawOffset(more, n)) else Double.NaN, alpha = show)
    }

    // ---- pictures drawn with the 2D canvas. Everything is light on a transparent ground: on the glasses black is
    // see-through, so there is no panel behind anything. Text is sized by its x-height, as an angle (Sight).
    /** A mark: a ring in the app's colour with its letter or sign, in a picture half as large again as the mark so that the halo of the one in focus has room. */
    private fun markPicture(item: HomeItem, lit: Boolean, running: Int = 0): Sprite = picture("mark|${item.title}|${item.color}|${item.kind}|${item.verified}|$lit|$running") {
        val side = MARK_PX * 3; val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val sign = HomeIcon.sign(item.title, if (item.url == "nav:panel") "nav:home" else if (item.kind == HomeItem.Kind.NAV) item.url else if (item.kind == HomeItem.Kind.WEB) item.webKind else "")
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val own = appIcon(item)
        HomeIcon.draw(c, p, side / 2f, side / 2f, MARK_PX.toFloat(), item.color, item.kind == HomeItem.Kind.GAME, if (own != null) "" else sign, item.verified, lit)
        if (own != null) {      // an app of the phone shows its own icon in its mark, not a letter
            val r = MARK_PX * 0.74f; val clip = android.graphics.Path(); clip.addCircle(side / 2f, side / 2f, r, android.graphics.Path.Direction.CW)
            val save = c.save(); c.clipPath(clip); p.isFilterBitmap = true; p.alpha = 255
            c.drawBitmap(own, null, android.graphics.RectF(side / 2f - r, side / 2f - r, side / 2f + r, side / 2f + r), p); c.restoreToCount(save)
        }
        if (running > 0) {
            p.color = Ink.TIDE_INT; p.style = if (running == 2) Paint.Style.FILL else Paint.Style.STROKE; p.strokeWidth = MARK_PX * 0.035f
            c.drawCircle(side / 2f, side / 2f + MARK_PX * 1.12f, MARK_PX * 0.06f, p); p.style = Paint.Style.FILL
        }
        b
    }

    /** The name under the mark in focus. The ring is what glows; its name is plain text. */
    private fun namePicture(title: String): Sprite = picture("name|$title") { Type.label(title, NAME_PX, Ink.CHALK_INT, Type.bold, (NAME_PX * Shelf.NAME_WIDTH / Sight.NAME).toFloat()) }

    /** The title above the top-left bracket of the window in focus, with a Tide pin before it when the window is pinned to the room. */
    private fun titlePicture(w: DesktopWindow): Sprite {
        // no wider than the window, in steps so that sizing a window does not draw a new picture for every frame
        val room = Math.round(w.shownWidth() / Sight.metres(Sight.BODY, w.shownDistance()) * TEXT_PX / 48.0).toInt().coerceAtLeast(4) * 48
        val text = w.title.ifBlank { "Window" }; val pinned = !w.follow
        return picture("title|$text|$pinned|$room") {
            Type.label(text, TEXT_PX, Ink.CHALK_INT, Type.regular, room.toFloat(), ground = Glass.PANE, before = if (!pinned) null else { c, p, cx, cy ->
                // a pin: a round head and its point, in line
                val x = TEXT_PX; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = x * 0.16f; p.color = Ink.TIDE_INT
                c.drawCircle(cx, cy - x * 0.2f, x * 0.36f, p); c.drawLine(cx, cy + x * 0.16f, cx, cy + x * 0.95f, p)
            })
        }
    }

    /**
     * The handle under the window in focus: its four words, each in the middle of a quarter of the strip
     * ([HandleStrip]). The word in focus is Lamp ("close" is Ember), as is "move" or "size" while the window is being
     * moved or sized; the others are Dusk. "pin" has a Tide dot before it while the window is pinned to the room.
     */
    private fun handlePicture(w: DesktopWindow, f: Focus): Sprite {
        val lit = when (f) { is Focus.Handle -> f.word; is Focus.Moving -> HandleWord.MOVE; is Focus.Sizing -> HandleWord.SIZE; else -> null }
        val bw = 1024; val bh = Math.round(bw * w.shownHandleHeight() / w.shownHandleWidth()).toInt().coerceIn(24, 512)
        // the words' x-height as the angle body text has, unless the window is so narrow that they would touch: then as large as fits
        val xh = Math.round(Sight.metres(Sight.BODY, w.shownDistance()) / w.shownHandleWidth() * bw).toInt()
        return picture("handle|${lit?.name}|${w.follow}|$bh|$xh") {
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG); p.textAlign = Paint.Align.CENTER
            // a pane of glass under the words
            p.color = Glass.PANE; c.drawRoundRect(1f, bh * 0.08f, bw - 1f, bh * 0.92f, bh * 0.42f, bh * 0.42f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = Glass.PANE_RIM; c.drawRoundRect(1f, bh * 0.08f, bw - 1f, bh * 0.92f, bh * 0.42f, bh * 0.42f, p); p.style = Paint.Style.FILL
            p.typeface = Type.bold; p.textSize = Type.sizeFor(Type.bold, xh.toFloat())
            val quarter = bw / HandleWord.values().size.toFloat(); val widest = HandleWord.values().maxOf { p.measureText(it.label) } + xh * 1.6f
            val fit = minOf(1f, quarter / widest)
            for (word in HandleWord.values()) {
                val on = word == lit; val x = bw * HandleStrip.centre(word).toFloat(); val base = bh / 2f + xh * fit / 2f
                p.typeface = if (on) Type.bold else Type.regular; p.textSize = Type.sizeFor(p.typeface, xh * fit)
                p.color = if (!on) Ink.DUSK_INT else if (word == HandleWord.CLOSE) Ink.EMBER_INT else Ink.LAMP_INT
                c.drawText(word.label, x, base, p)
                if (word == HandleWord.PIN && !w.follow) { p.color = Ink.TIDE_INT; c.drawCircle(x - p.measureText(word.label) / 2 - xh * fit * 0.55f, bh / 2f, xh * fit * 0.17f, p) }
            }
            b
        }
    }

    /** The status line: the time, the phone's battery, and one word for tracking. Dusk; nothing to press. Its words are looked up once a second. */
    private fun statusPicture(nowNs: Long, head: HeadTracker.State, onlyWhatIsSaid: Boolean = false): Sprite? {
        val said = notice
        if (said.isNotEmpty() && nowNs < noticeUntilNs) return picture("notice|$said") { Type.label(said, TEXT_PX, Ink.EMBER_INT, Type.regular, ground = Glass.PANE) }
        if (onlyWhatIsSaid) return null      // the home panel has the time, the battery and the tracking in its own head
        if (statusText.isEmpty() || nowNs - statusNs > 1_000_000_000L) {
            statusNs = nowNs
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val battery = try { (activity.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { -1 }
            // position is what the room is found by. With a room marked, it is found when the marked walls are placed in
            // the world; with none marked, when the cameras hold their position.
            val tracking = trackingWord(head)
            // a step of the set-up that has come undone is said here, in a few words; the phone's main screen offers the step that puts it right
            val undone = if (!dev.ultraxr.xr.PointerService.enabled(activity)) "      pointer off: see the phone" else ""
            statusText = time + (if (battery in 0..100) "      $battery %" else "") + "      " + tracking + undone
        }
        val text = statusText
        return picture("status|$text") { Type.label(text, TEXT_PX, Glass.SOFT, Type.regular, ground = Glass.PANE) }
    }
    private var statusText = ""; private var statusNs = 0L
    private val ringGlide = Array(4) { Glide(0.0) }
    /**
     * One line that takes the status line's place for a few seconds, in Ember: something that was asked for did not
     * happen, and what to do. It is the only way the glasses ever interrupt.
     */
    fun say(text: String) { notice = text; noticeUntilNs = System.nanoTime() + 5_000_000_000L; Tracking.log("desktop: said \"$text\"") }
    @Volatile private var notice = ""; @Volatile private var noticeUntilNs = 0L

    companion object {
        private val ZERO = doubleArrayOf(0.0, 0.0, 0.0)
        /** A pinch on a corner becomes a drag once the hand's ray has moved this far across the window, metres. */
        private const val CORNER_DRAG_M = 0.03
        /** How far beside a window the hand's ray still has hold of it. */
        private val HAND_BAND = Math.toRadians(3.5)
        private const val HOVER_NS = 180_000_000L
        /** Where a window's middle stands when it is opened or brought back: at eye level. */
        private const val WINDOW_PITCH = 0.0
        /** Looking this far down brings back a dock that stepped aside. */
        private val DOCK_LOOK = Math.toRadians(-7.0)
        private const val PHONE_HAND_NS = 1_500_000_000L
        private const val PUSH_FREE_M = 0.03
        private const val PUSH_GAIN = 4.0
        /** A pinch on a window's handle becomes carrying the window once the hand's ray has turned this far. */
        private val GRAB_ANGLE = Math.toRadians(2.5)
        /** The home and the dock stay put while the head is within this of where they were placed, along the floor and up or down, metres. */
        private const val STAGE_REACH = 0.35; private const val STAGE_RISE = 0.10
        /** A slide this long on the touchpad (in pad widths) is one step of the focus on the home panel. */
        private const val PAD_STEP = 0.16
        /** The kind of the window the settings are in. */
        const val SETTINGS = "settings"
        const val VIDEO = "video"
        /** The phone's apps are offered this many to a page of the shelf. */
        private const val APPS_A_PAGE = 11
        /** The colours WebXR games are told apart by on their marks (they have none of their own). */
        private val TINTS = intArrayOf(0xFF5B8DEF.toInt(), 0xFF34C7A5.toInt(), 0xFFF2A65A.toInt(), 0xFFB57BEE.toInt(), 0xFFEF6F8E.toInt(), 0xFF5BC0EB.toInt())
        // How large the pictures are drawn, in pixels: a little finer than the glasses show them (an x-height of 0.55 degrees is 23 of their pixels).
        private const val TEXT_PX = 36f
        private const val NAME_PX = 44f
        private const val MARK_PX = 84
        /** The desktop that is running, for the pointer service to hand keys to (see [keysElsewhere]). */
        @Volatile var current: DesktopSession? = null; private set
    }
}
