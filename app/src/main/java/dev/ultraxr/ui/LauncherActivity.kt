package dev.ultraxr.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.desktop.HomeApps
import dev.ultraxr.glasses.GlassesLink
import dev.ultraxr.render.GlassesStage
import dev.ultraxr.render.HomeScene
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.tracking.TrackingService
import dev.ultraxr.ui.Ui.add
import dev.ultraxr.xr.XrRuntime

/**
 * The main screen, before the glasses are on (docs/INTERFACE.md, "On the phone"): the state in one line of words, the
 * shelf of marks upright, and one large action, "Open in the glasses". Under them, as plain lists, what was here
 * before: the games, the other XR apps on the phone, how the hands are used. When something needs the owner, one line
 * says what and offers the one step that puts it right. Also where tests and plugging the glasses in come in.
 */
class LauncherActivity : Activity(), Tracking.Observer {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var stateLine: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: TextView
    private lateinit var webList: LinearLayout
    private lateinit var pointerRow: ListRow
    private lateinit var appsList: LinearLayout
    private lateinit var lumenRow: ListRow
    private val home = HomeScene()
    private val lab = dev.ultraxr.render.LabScene()
    private var labOn = false
    private val cover = dev.ultraxr.render.LabScene(true)
    private var covOn = false
    private var stage: GlassesStage? = null
    private var bannerAction: (() -> Unit)? = null
    private var askedCamera = false
    /** Started by a test (any of the switches below): the set-up is not put in its way. */
    private var skipSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        Tracking.acquire(this)
        build()
        openDesktopIfAsked(intent)
        stage = GlassesStage(this, null, home)
    }

    override fun onDestroy() { Tracking.release(); super.onDestroy() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); openDesktopIfAsked(intent) }

    /** "am start ... --ez desktop true [--ez automation true]" opens the desktop directly (used by UI automation). */
    private fun openDesktopIfAsked(i: Intent?) {
        // A start with any of this screen's own switches is a test or a tool, not the owner's first day: no set-up over it.
        // ("--ez skip_setup true" by itself does the same.) Plugging the glasses in carries none of them.
        if (i?.extras?.keySet()?.any { it in TEST_EXTRAS } == true) skipSetup = true
        // Test builds: treat the attached display as glasses in half-width 3D (used on an emulator).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            if (i?.hasExtra("force_half_stereo") == true) Tracking.forceHalfStereo = i.getBooleanExtra("force_half_stereo", false)
            if (i?.hasExtra("picture_tilt") == true) dev.ultraxr.xr.XrHost.setPictureTilt(i.getFloatExtra("picture_tilt", 0f))
            if (i?.hasExtra("floor_trim") == true) dev.ultraxr.xr.XrHost.setFloorTrim(i.getFloatExtra("floor_trim", 0f))
            if (i?.hasExtra("sim_wearer") == true) Tracking.simulateWearer = i.getBooleanExtra("sim_wearer", false)
            if (i?.getBooleanExtra("dump_egl", false) == true) dumpEglConfigs()
            if (i?.getBooleanExtra("test_site", false) == true) { dev.ultraxr.xr.LocalSite.testFiles = true; dev.ultraxr.xr.LocalSite.start(applicationContext) }
            // "--ez diag3d true": one bounded, logged experiment with the glasses' half-width 3D mode (GlassesLink.diag3d).
            if (i?.getBooleanExtra("diag3d", false) == true) Thread { Thread.sleep(3000); Tracking.link?.diag3d() ?: Tracking.log("diag3d: tracking is not running") }.start()
            if (i?.hasExtra("pace_by_display") == true) Tracking.settings.paceByDisplay = i.getBooleanExtra("pace_by_display", false)
            if (i?.hasExtra("auto_enter") == true) Tracking.settings.autoEnter = i.getBooleanExtra("auto_enter", false)
            // "--es replay NAME [--es replay_tag TAG]": feed files/recordings/NAME through the trackers (glasses unplugged)
            // and write what they report to files/replay/. For measuring tracking changes on recorded real motion.
            i?.getStringExtra("replay")?.let { name ->
                // In a foreground service, so it runs with the screen off and the phone locked.
                val svc = Intent(this, dev.ultraxr.tracking.ReplayService::class.java).putExtra("replay", name).putExtra("replay_tag", i.getStringExtra("replay_tag") ?: "run")
                try { startForegroundService(svc) } catch (e: Exception) { Tracking.log("replay finished: could not start: $e") }
            }
            // "--ei record_seconds N": record raw tracking data for N seconds as soon as the glasses stream (one live test, no button).
            val recSeconds = i?.getIntExtra("record_seconds", 0) ?: 0
            if (recSeconds > 0) Thread {
                for (n in 0 until 900) {
                    val l = Tracking.link
                    if (l != null && l.state == dev.ultraxr.glasses.GlassesLink.State.STREAMING) {
                        if (!l.recording) l.record(java.io.File(getExternalFilesDir("recordings"), "live-" + System.currentTimeMillis() + ".uxrec"), recSeconds)
                        break
                    }
                    Thread.sleep(1000)
                }
            }.start()
        }
        if (i?.getBooleanExtra("desktop", false) != true) return
        startActivity(Intent(this, DesktopActivity::class.java).putExtra(DesktopActivity.EXTRA_AUTOMATION, i.getBooleanExtra(DesktopActivity.EXTRA_AUTOMATION, false))
            .putExtra(DesktopActivity.EXTRA_STEREO_PREVIEW, i.getBooleanExtra(DesktopActivity.EXTRA_STEREO_PREVIEW, false)).putExtra("open_app", i.getStringExtra("open_app")).putExtra("home_page", i.getIntExtra("home_page", 0))
            .putExtra(DesktopActivity.EXTRA_OPEN_SHELF, i.getIntExtra(DesktopActivity.EXTRA_OPEN_SHELF, -1)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a game: the launcher owns tracking again.
        if (TrackingService.running) TrackingService.stop(this)
        if (!dev.ultraxr.xr.XrHost.applicationConnected) { dev.ultraxr.xr.XrHost.closeShell(); if (!startingGame) Tracking.stereoWant("game", false) }
        Tracking.addObserver(this)
        val padOn = dev.ultraxr.xr.PointerService.enabled(this)
        pointerRow.onOff(padOn).set("Touchpad in games", pointerRow.value.text, if (padOn) "In a game the phone's screen is a touchpad: drag to move the pointer, tap to select, two fingers or volume-down to grab and carry, volume-up to recentre. It works whether or not your hands are tracked."
            else "Switched on, the phone's screen becomes a touchpad in games, so you can point, select and grab without hand tracking. Tap, and switch on \"Ultra XR touchpad\" in Android's accessibility settings.")
        stage?.start()
        home.reanchor()
        refreshApps()
        ui.post(tick)
        // The set-up, until it has been gone through once (docs/INTERFACE.md, "Set-up, once"). It is offered once in a
        // run of the app: whoever backs out of it gets this screen, which says in one line that it is not finished.
        // It is also put back when it was on its way and this screen came in front of it (Android starts this screen
        // when the glasses are plugged in, which is the set-up's own second step).
        if (SetupActivity.open || (!Tracking.settings.setupDone && !setupOffered && !skipSetup)) {
            setupOffered = true
            startActivity(Intent(this, SetupActivity::class.java))
        } else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && !askedCamera) {
            askedCamera = true
            requestPermissions(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onPause() {
        // Still visible here, so Android allows the foreground service to start.
        Tracking.keepAliveInBackground(this)
        ui.removeCallbacks(tick)
        Tracking.removeObserver(this)
        stage?.stop()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { Tracking.link?.retry(); update() }
    override fun onTrackingChanged() { ui.post { update() } }
    private val tick = object : Runnable { override fun run() { update(); ui.postDelayed(this, 1000) } }

    private fun build() {
        val root = Ui.column(this)
        Ui.fitBars(root, 20, 16, 28)
        root.add(Ui.text(this, "Ultra XR", 30f, bold = true))
        // the state, in one line of words
        stateLine = Ui.text(this, "", 15f, Ui.DUSK)
        root.add(stateLine, topDp = 8)

        // when something needs the owner: one line saying what, and the one step that puts it right
        banner = Ui.column(this).apply { visibility = View.GONE }
        bannerText = Ui.text(this, "", 16f, Ui.CHALK).apply { setLineSpacing(0f, 1.25f) }
        bannerButton = Ui.button(this, "") { bannerAction?.invoke() }
        banner.add(bannerText); banner.add(bannerButton, topDp = 12)
        root.add(banner, topDp = 18)

        // The shelf, upright: the marks the glasses show, drawn the same way. A mark opens its app in the glasses; the
        // last one is the settings, as on the shelf there.
        val shelf = Ui.row(this).apply { gravity = Gravity.TOP }
        HomeApps.all.forEachIndexed { i, a ->
            shelf.add(mark(a.title, MarkView(this, a.color, false, dev.ultraxr.desktop.HomeIcon.sign(a.title, a.kind)), "Open ${a.title} in the glasses") { startActivity(Intent(this, DesktopActivity::class.java).putExtra(DesktopActivity.EXTRA_OPEN_SHELF, i)) }, 0, weight = 1f)
        }
        shelf.add(mark("Settings", MarkView(this, 0xFF607D8B.toInt(), false, "#sliders"), "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }, 0, weight = 1f)
        root.add(shelf, topDp = 30)

        // The one large action. The home view in the glasses is the main way in; the lists below do the same things.
        root.add(Ui.action(this, "Open in the glasses") { startActivity(Intent(this, DesktopActivity::class.java)) }, topDp = 22)

        // Games: favourites first, then what was played last, then the rest.
        root.add(Ui.label(this, "Games"), topDp = 36)
        webList = Ui.column(this)
        root.add(webList, topDp = 2)

        root.add(Ui.label(this, "Also on this phone"), topDp = 30)
        appsList = Ui.column(this)
        root.add(appsList, topDp = 2)
        lumenRow = ListRow(this).set("Lumen", "", "The built-in game.").onTap { startActivity(Intent(this, PlayActivity::class.java)) }
        root.add(lumenRow)

        // How to play with hands: the same in every game.
        root.add(Ui.label(this, "Playing with your hands"), topDp = 30)
        for ((what, how) in listOf("Point" to "Hold a hand up in front of your face, not at chest height: the cameras do not see it lower down. A thin line runs out through it. Move the hand to aim; turning the wrist does little.",
            "Select" to "Touch thumb and index finger together, briefly.",
            "Grab" to "Pinch and hold, move your hand, let go.",
            "Recentre" to "Pinch with both hands and hold for a second and a half.",
            "Leave a game" to "Press Back on the phone.")) root.add(ListRow(this).set(what, "", how))

        // Playing without hand tracking: the phone as a touchpad (an accessibility service the owner switches on once).
        root.add(Ui.label(this, "This phone"), topDp = 30)
        pointerRow = ListRow(this).set("Touchpad in games", "", "").onTap { try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {} }
        root.add(pointerRow)
        root.add(ListRow(this).set("Recentre", "", "Where you face now becomes straight ahead.").onTap { Tracking.recenter(); home.reanchor() })
        root.add(ListRow(this).set("Coverage lab", "", "About three minutes: hold each hand, then both, at places all round you, in the display and out of it (chest, lap, hip, arms out to the side), 5 seconds each, while the cameras are recorded. Tap again to leave it.").onTap {
            covOn = !covOn
            if (covOn) { labOn = false; cover.restart(); stage?.setScene(cover) } else stage?.setScene(home)
        })
        root.add(ListRow(this).set("Hand lab", "", "About nine minutes of cues in the glasses (pinches, holds, still hands, waving): measures what your hands do. Tap again to leave it.").onTap {
            labOn = !labOn
            if (labOn) { lab.restart(); stage?.setScene(lab) } else stage?.setScene(home)
        })
        root.add(ListRow(this).set("Settings", "", "Eye distance, the room, hand tracking and the rest.").onTap { startActivity(Intent(this, SettingsActivity::class.java)) })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    /** One mark of the shelf with its name under it. The whole of it is pressed, and the mark glows while it is. */
    private fun mark(name: String, icon: View, says: String, run: () -> Unit): View {
        val col = Ui.column(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dp(this@LauncherActivity, 2), Ui.dp(this@LauncherActivity, 8), Ui.dp(this@LauncherActivity, 2), Ui.dp(this@LauncherActivity, 8)) }
        col.addView(icon, LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)))
        col.add(Ui.text(this, name, 13f, Ui.DUSK).apply { gravity = Gravity.CENTER; maxLines = 2; setTextColor(Ui.pressedColors(Ui.DUSK)) }, topDp = 8)
        col.isClickable = true; col.isFocusable = true; col.contentDescription = says
        col.setOnClickListener { run() }
        return col
    }

    private fun update() {
        val s = Tracking.snapshot()
        val st = stage
        val streaming = s.link == GlassesLink.State.STREAMING
        // The state in words, as the glasses' own status line has it: the glasses, then one word for tracking.
        val glassesWords = when (s.link) {
            GlassesLink.State.STREAMING -> "Glasses connected"
            GlassesLink.State.CONNECTING -> "Connecting to the glasses"
            GlassesLink.State.NEED_PERMISSION -> "The glasses are waiting for USB access"
            GlassesLink.State.FAILED -> "The glasses could not start"
            GlassesLink.State.NO_GLASSES -> "No glasses plugged in"
        }
        val trackingWords = when {
            !streaming -> ""
            s.head == HeadTracker.Mode.SIX_DOF -> if (s.handsVisible > 0) "room found, hands seen" else "room found"
            s.head == HeadTracker.Mode.ORIENTATION_ONLY -> "no position"
            else -> "finding the room"
        }
        val words = glassesWords + (if (trackingWords.isEmpty()) "" else " · $trackingWords")
        if (stateLine.text.toString() != words) stateLine.text = words
        stateLine.setTextColor(if (s.link == GlassesLink.State.FAILED) Ui.EMBER else Ui.DUSK)
        home.subtitle = if (streaming && st != null && st.onGlasses && !st.isSideBySide) "Display is not in 3D mode" else "Pick a game on your phone"
        lumenRow.line.text = "The built-in game. " + (if (Tracking.settings.bestScore > 0) "Best score ${Tracking.settings.bestScore}." else "90 seconds a round.")

        // One line at a time, most blocking first.
        val cameraDenied = checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        when {
            startingGame || gameNote -> {}   // the game's line stays until it is answered
            // the set-up was left before its end: it has the camera and the glasses among its steps, so it comes first
            !Tracking.settings.setupDone && !skipSetup -> showBanner("The set-up is not finished.", "Finish the set-up") { startActivity(Intent(this, SetupActivity::class.java)) }
            cameraDenied -> showBanner("Ultra XR needs the camera permission. Android requires it before an app may use the glasses' tracking cameras.", "Let Android ask") { requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
            s.link == GlassesLink.State.NEED_PERMISSION && s.linkDetail.startsWith("USB") -> showBanner("Android has not given Ultra XR access to the glasses, so their cameras and motion sensor cannot be read.", "Let Android ask again") { Tracking.link?.retry() }
            s.link == GlassesLink.State.STREAMING && s.linkDetail.isNotEmpty() -> showBanner("${s.linkDetail}. Games still work, without position tracking or hands. Run the check for details.", "Run the check") { startActivity(Intent(this, CheckActivity::class.java)) }
            s.link == GlassesLink.State.FAILED -> showBanner("Could not start the glasses: ${s.linkDetail}. Unplug them, plug them back in, or run the check for details.", "Try again") { Tracking.link?.retry() }
            streaming && st != null && !st.onGlasses -> showBanner("The glasses are connected over USB, but Android reports no external display. Check that the cable is plugged straight into the phone.", "Run the check") { startActivity(Intent(this, CheckActivity::class.java)) }
            // a step of the set-up has been undone since: the one step that puts it right (Android's own screen; the owner switches it on)
            Tracking.settings.setupDone && !SetupActivity.pointerOn(this) -> showBanner("The pointer is switched off, so the phone is not a touchpad in games.", "Switch it on in Android's settings") { try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {} }
            // only until the set-up has been gone through once: after that the start screen asks for nothing
            streaming && !Tracking.settings.firstRunDone && !Tracking.settings.setupDone -> showBanner("First time with these glasses: run the one-minute check so tracking and 3D are confirmed on your hardware.", "Run the check") { startActivity(Intent(this, CheckActivity::class.java)) }
            streaming && Tracking.vioError.isNotEmpty() -> showBanner("Position tracking could not start, so games run with rotation only. (${Tracking.vioError})", "Details") { startActivity(Intent(this, CheckActivity::class.java)) }
            else -> banner.visibility = View.GONE
        }
    }

    private fun showBanner(msg: String, action: String, run: () -> Unit) {
        if (bannerText.text.toString() != msg) bannerText.text = msg
        if (bannerButton.text.toString() != action) bannerButton.text = action
        bannerAction = run; banner.visibility = View.VISIBLE
    }

    private val library by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private fun recent(): List<String> = (library.getString("recent", "") ?: "").split('\u0001').filter { it.isNotEmpty() }
    private fun favourites(): Set<String> = library.getStringSet("favourites", emptySet()) ?: emptySet()

    private fun note(t: String): TextView = Ui.text(this, t, 15f, Ui.DUSK).apply { setLineSpacing(0f, 1.25f); setPadding(Ui.dp(this@LauncherActivity, 12), Ui.dp(this@LauncherActivity, 14), 0, Ui.dp(this@LauncherActivity, 6)) }

    /** The WebXR games as a plain list, favourites and what was played last first. Each row says what is actually known about the game. */
    private fun refreshWeb() {
        webList.removeAllViews()
        val browser = XrRuntime.browser(this)
        val games = try { dev.ultraxr.core.GameLibrary.parse(assets.open("webxr_pages.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { emptyList() }
        val favs = favourites(); val rec = recent()
        if (browser == null) { webList.add(note("The WebXR browser is not installed on this phone, so games cannot start.")); return }

        // Each game with the mark the glasses show for it. Tap to play; press and hold to keep one first.
        val tints = intArrayOf(0xFF5B8DEF.toInt(), 0xFF34C7A5.toInt(), 0xFFF2A65A.toInt(), 0xFFB57BEE.toInt(), 0xFFEF6F8E.toInt(), 0xFF5BC0EB.toInt())
        val runnable = games.filter { it.state != dev.ultraxr.core.WebGame.State.BLOCKED }
        fun row(g: dev.ultraxr.core.WebGame): View {
            val blocked = g.state == dev.ultraxr.core.WebGame.State.BLOCKED
            val tint = tints[(runnable.indexOf(g).takeIf { it >= 0 } ?: games.indexOf(g)) % tints.size]
            val fav = g.url in favs
            val caption = when (g.state) {
                dev.ultraxr.core.WebGame.State.VERIFIED -> "Played on the glasses"
                dev.ultraxr.core.WebGame.State.TESTED -> "Tested, not yet seen"
                dev.ultraxr.core.WebGame.State.BLOCKED -> "Does not run yet"
                else -> "Not tested"
            }
            val r = ListRow(this).set((if (fav) "★ " else "") + g.name.substringBefore(" (").trim(), "", caption)
            if (blocked) r.name.setTextColor(Ui.DUSK)
            r.lead(MarkView(this, tint, true, dev.ultraxr.desktop.HomeIcon.sign(g.name.substringBefore(" (").trim()), g.state == dev.ultraxr.core.WebGame.State.VERIFIED, blocked), 44)
            r.contentDescription = "Play ${g.name}. $caption" + (if (fav) ". Kept first" else "")
            r.onTap {
                library.edit().putString("recent", dev.ultraxr.core.GameLibrary.played(recent(), g.url).joinToString("\u0001")).apply()
                play(g.name) { stereo ->
                    XrRuntime.launchUrl(this, browser, g.url, stereo)
                    if (Tracking.settings.autoEnter) dev.ultraxr.xr.XrHost.autoEnter(g.enter)
                }
            }
            r.setOnLongClickListener { library.edit().putStringSet("favourites", dev.ultraxr.core.GameLibrary.toggleFavourite(favourites(), g.url)).apply(); refreshWeb(); true }
            return r
        }
        // favourites first, then what was played last, then the rest; games that do not run yet stay last
        val order = dev.ultraxr.core.GameLibrary.ordered(games).sortedBy { g -> if (g.state == dev.ultraxr.core.WebGame.State.BLOCKED) 3 else if (g.url in favs) 0 else if (g.url in rec) 1 else 2 }
        for (g in order) webList.add(row(g))
        webList.add(ListRow(this).set("Browse the web in XR", "", "Opens the XR browser by itself.").onTap { play(browser.loadLabel(packageManager).toString()) { stereo -> XrRuntime.launch(this, browser, stereo) } })
        webList.add(note("Tap to play. Press and hold to keep a game first."))
    }

    private fun refreshApps() {
        refreshWeb()
        appsList.removeAllViews()
        val apps = XrRuntime.installedApps(this).filter { !it.activityInfo.packageName.startsWith("com.igalia.wolvic") }   // the browser has its own section
        if (apps.isEmpty()) { appsList.add(note("Other XR apps you install appear here.")); return }
        for (app in apps) appsList.add(appCard(app))
    }

    /** Test builds: lists the display's EGL configurations in the log (what a browser or game can choose from). */
    private fun dumpEglConfigs() {
        val d = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); android.opengl.EGL14.eglInitialize(d, v, 0, v, 1)
        val cfg = arrayOfNulls<android.opengl.EGLConfig>(1024); val n = IntArray(1)
        android.opengl.EGL14.eglGetConfigs(d, cfg, 0, 1024, n, 0)
        val seen = HashMap<String, Int>()
        for (k in 0 until n[0]) {
            fun a(attr: Int): Int { val o = IntArray(1); android.opengl.EGL14.eglGetConfigAttrib(d, cfg[k], attr, o, 0); return o[0] }
            val key = "rgba " + a(android.opengl.EGL14.EGL_RED_SIZE) + a(android.opengl.EGL14.EGL_GREEN_SIZE) + a(android.opengl.EGL14.EGL_BLUE_SIZE) + a(android.opengl.EGL14.EGL_ALPHA_SIZE) +
                " depth " + a(android.opengl.EGL14.EGL_DEPTH_SIZE) + " stencil " + a(android.opengl.EGL14.EGL_STENCIL_SIZE) + " samples " + a(android.opengl.EGL14.EGL_SAMPLES) +
                " renderable 0x" + Integer.toHexString(a(android.opengl.EGL14.EGL_RENDERABLE_TYPE)) + " surface 0x" + Integer.toHexString(a(android.opengl.EGL14.EGL_SURFACE_TYPE))
            seen[key] = (seen[key] ?: 0) + 1
        }
        Tracking.log("EGL " + v[0] + "." + v[1] + ": " + n[0] + " configurations")
        for ((k, c) in seen.toSortedMap()) Tracking.log("EGL config x$c: $k")
    }

    private var startingGame = false
    private var gameNote = false

    /**
     * Starts a game. A game draws one picture per eye, so with glasses attached they must be in 3D
     * before it starts (switching afterwards would take the game's picture away): ask for 3D, wait
     * for it to hold, then launch. If 3D cannot be had, say so instead of showing a doubled picture.
     */
    private fun play(info: ResolveInfo) = play(info.loadLabel(packageManager).toString()) { stereo -> XrRuntime.launch(this, info, stereo) }

    private fun play(label: String, launch: (stereo: Boolean) -> Unit) {
        if (startingGame) return
        gameNote = false
        val link = Tracking.link
        val glasses = link?.state == GlassesLink.State.STREAMING
        if (!glasses || Tracking.halfStereoActive || dev.ultraxr.glasses.DisplayDiagnostics.sideBySidePresent(this)) { stage?.stop(); launch(true); return }
        // Half-width 3D is not attempted unless switched on in Settings: on this phone the attempt leaves the glasses black.
        if (!Tracking.settings.tryHalf3d) { stage?.stop(); launch(false); return }
        startingGame = true
        link!!.stereo.reset()
        Tracking.stereoWant("game", true)
        val started = android.os.SystemClock.uptimeMillis()
        val wait = object : Runnable {
            override fun run() {
                val sw = link.stereo
                when {
                    isFinishing || isDestroyed -> { startingGame = false; Tracking.stereoWant("game", false) }
                    Tracking.halfStereoActive -> { startingGame = false; banner.visibility = View.GONE; stage?.stop(); launch(true) }
                    sw.state == dev.ultraxr.glasses.StereoSwitch.State.GAVE_UP || android.os.SystemClock.uptimeMillis() - started > 90_000 -> {
                        // 3D could not be had: the glasses are back in 2D and the game gets one picture for both eyes.
                        startingGame = false; gameNote = true; Tracking.stereoWant("game", false)
                        showBanner("3D could not be started (" + sw.lastReason.ifEmpty { "no answer from the glasses" } + "), so $label is shown in 2D: one picture for both eyes, without depth.", "Try 3D again") { play(label, launch) }
                        stage?.stop(); launch(false)
                    }
                    else -> { showBanner("Starting 3D for $label (try ${sw.attempts.coerceAtLeast(1)} of 3). The picture blanks for a few seconds.", "Cancel") { startingGame = false; Tracking.stereoWant("game", false); banner.visibility = View.GONE; ui.removeCallbacks(this) }; ui.postDelayed(this, 500) }
                }
            }
        }
        ui.post(wait)
    }

    /** One of the other XR apps on this phone, as a row: its own icon, its name, what kind it is. */
    private fun appCard(info: ResolveInfo): View {
        val quest = try { packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(XrRuntime.CATEGORY_QUEST).setPackage(info.activityInfo.packageName), 0).isNotEmpty() } catch (_: Exception) { false }
        return ListRow(this).set(info.loadLabel(packageManager), "", if (quest) "Quest game (converted)" else "OpenXR")
            .lead(ImageView(this).apply { setImageDrawable(info.loadIcon(packageManager)) }, 44).onTap { play(info) }
    }

    companion object {
        /** The set-up has been offered in this run of the app. */
        private var setupOffered = false
        /** This screen's own switches for tests and tools (see [openDesktopIfAsked]). */
        private val TEST_EXTRAS = setOf("skip_setup", "desktop", DesktopActivity.EXTRA_AUTOMATION, DesktopActivity.EXTRA_STEREO_PREVIEW, "open_app", "home_page", DesktopActivity.EXTRA_OPEN_SHELF, "force_half_stereo", "picture_tilt", "floor_trim", "sim_wearer", "dump_egl", "test_site",
            "diag3d", "pace_by_display", "auto_enter", "replay", "replay_tag", "record_seconds")
    }
}
