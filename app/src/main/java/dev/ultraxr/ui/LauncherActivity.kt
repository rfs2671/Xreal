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
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.glasses.GlassesLink
import dev.ultraxr.render.GlassesStage
import dev.ultraxr.render.HomeScene
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.tracking.TrackingService
import dev.ultraxr.ui.Ui.add
import dev.ultraxr.xr.XrRuntime

/** Home screen: status at a glance, one tap to play, everything else out of the way. */
class LauncherActivity : Activity(), Tracking.Observer {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var glassesChip: TextView
    private lateinit var trackingChip: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: TextView
    private lateinit var webList: LinearLayout
    private lateinit var appsList: LinearLayout
    private lateinit var bestText: TextView
    private val home = HomeScene()
    private var stage: GlassesStage? = null
    private var bannerAction: (() -> Unit)? = null
    private var askedCamera = false

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
        // Test builds: treat the attached display as glasses in half-width 3D (used on an emulator).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            if (i?.hasExtra("force_half_stereo") == true) Tracking.forceHalfStereo = i.getBooleanExtra("force_half_stereo", false)
            if (i?.hasExtra("sim_wearer") == true) Tracking.simulateWearer = i.getBooleanExtra("sim_wearer", false)
            if (i?.getBooleanExtra("dump_egl", false) == true) dumpEglConfigs()
            if (i?.getBooleanExtra("test_site", false) == true) dev.ultraxr.xr.TestSiteServer.start(applicationContext)
        }
        if (i?.getBooleanExtra("desktop", false) != true) return
        startActivity(Intent(this, DesktopActivity::class.java).putExtra(DesktopActivity.EXTRA_AUTOMATION, i.getBooleanExtra(DesktopActivity.EXTRA_AUTOMATION, false))
            .putExtra(DesktopActivity.EXTRA_STEREO_PREVIEW, i.getBooleanExtra(DesktopActivity.EXTRA_STEREO_PREVIEW, false)))
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a game: the launcher owns tracking again.
        if (TrackingService.running) TrackingService.stop(this)
        if (!dev.ultraxr.xr.XrHost.applicationConnected) { dev.ultraxr.xr.XrHost.closeShell(); if (!startingGame) Tracking.stereoWant("game", false) }
        Tracking.addObserver(this)
        stage?.start()
        home.reanchor()
        refreshApps()
        ui.post(tick)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && !askedCamera) {
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
        val root = Ui.column(this, 20)
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 56), Ui.dp(this, 20), Ui.dp(this, 28))
        root.add(Ui.text(this, "Ultra XR", 34f, bold = true))
        val chips = Ui.row(this)
        glassesChip = Ui.chip(this); trackingChip = Ui.chip(this)
        chips.add(glassesChip, ViewGroup.LayoutParams.WRAP_CONTENT); chips.add(trackingChip, ViewGroup.LayoutParams.WRAP_CONTENT, startDp = 8)
        root.add(chips, topDp = 14)

        banner = Ui.column(this, 16).apply { background = Ui.rounded(0xFF2A2110.toInt(), 20f, this@LauncherActivity, 0x55FFB84D); visibility = View.GONE }
        bannerText = Ui.text(this, "", 14f, Ui.TEXT)
        bannerButton = Ui.button(this, "", primary = false) { bannerAction?.invoke() }
        banner.add(bannerText); banner.add(bannerButton, topDp = 12)
        root.add(banner, topDp = 18)

        // Featured game.
        val hero = Ui.column(this, 22).apply { background = Ui.gradient(0xFF10283F.toInt(), 0xFF2B1650.toInt(), 28f, this@LauncherActivity) }
        hero.add(Ui.text(this, "FEATURED", 11f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.18f })
        hero.add(Ui.text(this, "Lumen", 30f, bold = true), topDp = 8)
        hero.add(Ui.text(this, "Glowing orbs hang in your room. Reach out and burst them, pinch to throw them through rings, and step aside when a comet comes for you.", 14f, 0xFFC9D6E6.toInt()).apply { setLineSpacing(0f, 1.25f) }, topDp = 8)
        bestText = Ui.text(this, "", 13f, Ui.MUTED)
        hero.add(bestText, topDp = 10)
        hero.add(Ui.button(this, "Play", primary = true) { startActivity(Intent(this, PlayActivity::class.java)) }, topDp = 16)
        hero.add(Ui.button(this, "Spatial desktop") { startActivity(Intent(this, DesktopActivity::class.java)) }, topDp = 10)
        root.add(hero, topDp = 18)

        root.add(Ui.text(this, "WebXR games", 18f, bold = true), topDp = 28)
        webList = Ui.column(this)
        root.add(webList, topDp = 10)

        root.add(Ui.text(this, "Your XR apps", 18f, bold = true), topDp = 28)
        appsList = Ui.column(this)
        root.add(appsList, topDp = 10)

        val actions = Ui.row(this)
        actions.add(Ui.button(this, "Recenter") { Tracking.recenter(); home.reanchor() }, 0, weight = 1f)
        actions.add(Ui.button(this, "Check") { startActivity(Intent(this, CheckActivity::class.java)) }, 0, weight = 1f, startDp = 10)
        actions.add(Ui.button(this, "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }, 0, weight = 1f, startDp = 10)
        root.add(actions, topDp = 28)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(root) })
    }

    private fun update() {
        val s = Tracking.snapshot()
        val st = stage
        when (s.link) {
            GlassesLink.State.STREAMING -> Ui.setChip(glassesChip, "Glasses connected", Ui.GOOD)
            GlassesLink.State.CONNECTING -> Ui.setChip(glassesChip, "Connecting", Ui.WARN)
            GlassesLink.State.NEED_PERMISSION -> Ui.setChip(glassesChip, "Allow USB access", Ui.WARN)
            GlassesLink.State.FAILED -> Ui.setChip(glassesChip, "Connection problem", Ui.BAD)
            GlassesLink.State.NO_GLASSES -> Ui.setChip(glassesChip, "Plug in your glasses", Ui.MUTED)
        }
        val streaming = s.link == GlassesLink.State.STREAMING
        when {
            !streaming -> Ui.setChip(trackingChip, "Tracking off", Ui.MUTED)
            s.head == HeadTracker.Mode.SIX_DOF -> Ui.setChip(trackingChip, if (s.handsVisible > 0) "6DoF + hands" else "6DoF", Ui.GOOD)
            s.head == HeadTracker.Mode.ORIENTATION_ONLY -> Ui.setChip(trackingChip, "Rotation only", Ui.WARN)
            else -> Ui.setChip(trackingChip, "Starting", Ui.WARN)
        }
        home.subtitle = if (streaming && st != null && st.onGlasses && !st.isSideBySide) "Display is not in 3D mode" else "Pick a game on your phone"
        bestText.text = if (Tracking.settings.bestScore > 0) "Best score ${Tracking.settings.bestScore}" else "90 seconds a round"

        // One banner at a time, most blocking first.
        val cameraDenied = checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        when {
            startingGame || gameNote -> {}   // the game banner stays until it is answered
            cameraDenied -> showBanner("Ultra XR needs the camera permission. Android requires it before an app may use the glasses' tracking cameras.", "Allow") { requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
            s.link == GlassesLink.State.NEED_PERMISSION && s.linkDetail.startsWith("USB") -> showBanner("Ultra XR needs USB access to the glasses to read their cameras and motion sensor.", "Allow") { Tracking.link?.retry() }
            s.link == GlassesLink.State.STREAMING && s.linkDetail.isNotEmpty() -> showBanner("${s.linkDetail}. Games still work, without position tracking or hands. Run the check for details.", "Run check") { startActivity(Intent(this, CheckActivity::class.java)) }
            s.link == GlassesLink.State.FAILED -> showBanner("Could not start the glasses: ${s.linkDetail}. Unplug them, plug them back in, or run the check for details.", "Try again") { Tracking.link?.retry() }
            streaming && st != null && !st.onGlasses -> showBanner("The glasses are connected over USB, but Android reports no external display. Check that the cable is plugged straight into the phone.", "Run check") { startActivity(Intent(this, CheckActivity::class.java)) }
            streaming && !Tracking.settings.firstRunDone -> showBanner("First time with these glasses: run the one-minute check so tracking and 3D are confirmed on your hardware.", "Run check") { startActivity(Intent(this, CheckActivity::class.java)) }
            streaming && Tracking.vioError.isNotEmpty() -> showBanner("Position tracking could not start, so games run with rotation only. (${Tracking.vioError})", "Details") { startActivity(Intent(this, CheckActivity::class.java)) }
            else -> banner.visibility = View.GONE
        }
    }

    private fun showBanner(msg: String, action: String, run: () -> Unit) {
        bannerText.text = msg; bannerButton.text = action; bannerAction = run; banner.visibility = View.VISIBLE
    }

    /** The WebXR pages this build knows about, with what was actually observed for each (assets/webxr_pages.json). */
    private fun refreshWeb() {
        webList.removeAllViews()
        fun note(t: String) = Ui.text(this, t, 14f, Ui.MUTED).apply {
            background = Ui.rounded(Ui.SURFACE, 20f, this@LauncherActivity); val p = Ui.dp(this@LauncherActivity, 18); setPadding(p, p, p, p); setLineSpacing(0f, 1.2f)
        }
        val browser = XrRuntime.browser(this)
        if (browser == null) {
            webList.add(note("WebXR games run in the Wolvic browser on Ultra XR's runtime. Install the Ultra XR copy of Wolvic (tools/wolvic in the project) and the games appear here."))
            return
        }
        val pages = try { org.json.JSONArray(assets.open("webxr_pages.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { org.json.JSONArray() }
        for (k in 0 until pages.length()) {
            val o = pages.getJSONObject(k)
            val row = Ui.row(this)
            row.background = Ui.pressable(this, Ui.rounded(Ui.SURFACE, 20f, this))
            val p = Ui.dp(this, 14); row.setPadding(p, p, p, p)
            val col = Ui.column(this)
            col.add(Ui.text(this, o.optString("name"), 16f, bold = true))
            col.add(Ui.text(this, o.optString("needs") + " · " + o.optString("status"), 12f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }, topDp = 4)
            row.add(col, 0, weight = 1f)
            row.add(Ui.text(this, "Open", 14f, Ui.ACCENT, bold = true).apply { gravity = Gravity.END }, ViewGroup.LayoutParams.WRAP_CONTENT)
            row.isClickable = true
            val url = o.optString("url"); val name = o.optString("name")
            row.setOnClickListener { play(name) { stereo -> XrRuntime.launchUrl(this, browser, url, stereo) } }
            webList.add(row, topDp = 8)
        }
        val open = Ui.button(this, "Open the browser") { play(browser.loadLabel(packageManager).toString()) { stereo -> XrRuntime.launch(this, browser, stereo) } }
        webList.add(open, topDp = 10)
    }

    private fun refreshApps() {
        refreshWeb()
        appsList.removeAllViews()
        val apps = XrRuntime.installedApps(this).filter { !it.activityInfo.packageName.startsWith("com.igalia.wolvic") }   // the browser has its own section
        if (apps.isEmpty()) {
            appsList.add(Ui.text(this, "Android OpenXR games you install will show up here and start with one tap.", 14f, Ui.MUTED).apply {
                background = Ui.rounded(Ui.SURFACE, 20f, this@LauncherActivity); val p = Ui.dp(this@LauncherActivity, 18); setPadding(p, p, p, p); setLineSpacing(0f, 1.2f)
            })
            return
        }
        for (app in apps) appsList.add(appCard(app), topDp = 8)
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

    private fun appCard(info: ResolveInfo): View {
        val row = Ui.row(this)
        row.background = Ui.pressable(this, Ui.rounded(Ui.SURFACE, 20f, this))
        val p = Ui.dp(this, 14); row.setPadding(p, p, p, p)
        row.add(ImageView(this).apply { setImageDrawable(info.loadIcon(packageManager)) }, Ui.dp(this, 44), Ui.dp(this, 44))
        val col = Ui.column(this)
        col.add(Ui.text(this, info.loadLabel(packageManager), 16f, bold = true))
        col.add(Ui.text(this, "OpenXR", 12f, Ui.MUTED), topDp = 4)
        row.add(col, 0, weight = 1f, startDp = 14)
        row.add(Ui.text(this, "Play", 14f, Ui.ACCENT, bold = true).apply { gravity = Gravity.END }, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.isClickable = true
        row.setOnClickListener { play(info) }
        return row
    }
}
