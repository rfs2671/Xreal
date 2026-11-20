package dev.ultraxr.ui

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ultraxr.desktop.DesktopRenderer
import dev.ultraxr.desktop.DesktopSession
import dev.ultraxr.desktop.Focus
import dev.ultraxr.desktop.FocusKey
import dev.ultraxr.desktop.HomeApps
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.ui.Ui.add
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The spatial desktop's phone side: the glasses show the windows; this screen is the controller, made to be used
 * without looking at it (docs/INTERFACE.md, "On the phone"). One line of state at the top. The upper two thirds are
 * a touchpad that moves the pointer in the focused window (tap to select, two fingers to scroll, double-tap and hold
 * to drag). The lower third is a d-pad with Select in its middle, "back" and "home" above it either side and "keys"
 * and "more" below. Everything else (opening apps, the address, sizes, pocket mode, how the desktop behaves) is a
 * plain list behind "more". Without glasses the same scene is shown in a preview above the touchpad, which can be
 * dragged to look around.
 */
class DesktopActivity : Activity() {
    private lateinit var session: DesktopSession
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var displays: DisplayManager
    private lateinit var previewHost: FrameLayout
    private lateinit var status: TextView
    private lateinit var urlBox: EditText
    private lateinit var keysBox: EditText
    private lateinit var keysBar: LinearLayout
    private lateinit var sheet: ScrollView
    private lateinit var sheetList: LinearLayout
    private lateinit var previewHint: TextView
    private var preview: GLSurfaceView? = null
    private var previewRenderer: DesktopRenderer? = null
    private var glasses: StagePresentation? = null
    private val moveMode: Boolean get() = session.moving
    private var pocket: View? = null
    private var rootView: View? = null
    private var backPressedAt = 0L
    private var active = false
    private var lastChips = ""
    /** When a key of the phone's own d-pad last went down: it ticks by itself, so the session's tick for the same press is not given twice. */
    private var padKeyAt = 0L
    /** One of this screen's own text boxes is being typed into: keys are its own, not the focused window's. */
    private val typing: Boolean get() = urlBox.hasFocus() || keysBox.hasFocus()
    private val sheetOpen: Boolean get() = sheet.visibility == View.VISIBLE
    private val keysOpen: Boolean get() = keysBar.visibility == View.VISIBLE

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!active) return
            session.tick(System.nanoTime())
            // the state in words: what the session says of tracking, and what the keys do at this moment
            val words = session.statusLine + (if (session.moving) " · moving a window" else if (session.pageInUse) " · keys go to the page" else "")
            if (status.text.toString() != words) status.text = words
            // Machine-readable state for UI automation and accessibility tools.
            val d = session.debugState(); if (status.contentDescription != d) status.contentDescription = d
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { ui.postDelayed({ refreshStage() }, 400) }
        override fun onDisplayRemoved(id: Int) { if (glasses?.display?.displayId == id) { dismissGlasses(); refreshStage() } }
        override fun onDisplayChanged(id: Int) { if (glasses?.display?.displayId == id) ui.postDelayed({ refreshStage() }, 400) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Test builds only: lets UI automation run while the phone is locked.
        if (intent.getBooleanExtra(EXTRA_AUTOMATION, false) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { setShowWhenLocked(true); setTurnScreenOn(true) }
        Ui.edgeToEdge(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        displays = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        Tracking.acquire(this)
        session = DesktopSession(this)
        session.onChanged = { refreshChips() }
        session.onLookPress = { if (android.os.SystemClock.uptimeMillis() - padKeyAt > 150) tick(heavy = false) }
        // A game chosen in the home view starts in the XR browser, as it does from the launcher.
        session.onLaunchGame = { item ->
            runOnUiThread {
                val browser = dev.ultraxr.xr.XrRuntime.browser(this)
                if (browser == null) toast("The WebXR browser is not installed on this phone")
                else {
                    // The glasses can hold one window of ours. The home view's has to be gone before the game's
                    // runtime asks for its own, or Android refuses that one and the game is drawn on the phone
                    // (seen on the Pixel: the picture in the glasses froze). So: take the home view down, then start.
                    handedOver = true
                    dismissGlasses()
                    Tracking.noteGraphics("desktop left the glasses for " + item.url)
                    ui.postDelayed({
                        dev.ultraxr.xr.XrRuntime.launchUrl(this, browser, item.url, false)
                        if (Tracking.settings.autoEnter) dev.ultraxr.xr.XrHost.autoEnter(item.enter)
                    }, 300)
                }
            }
        }
        // An installed OpenXR app or converted Quest game: the same hand-over, then the app itself is started.
        session.onLaunchNative = { pkg ->
            runOnUiThread {
                val info = dev.ultraxr.xr.XrRuntime.nativeApp(this, pkg)
                if (info != null) {
                    handedOver = true
                    dismissGlasses()
                    Tracking.noteGraphics("desktop left the glasses for $pkg")
                    ui.postDelayed({ dev.ultraxr.xr.XrRuntime.launch(this, info, true) }, 300)
                }
            }
        }
        if (intent.getBooleanExtra(EXTRA_STEREO_PREVIEW, false) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) session.forceStereo = true
        build()
        dev.ultraxr.shell.AppShell.start(applicationContext)
        session.start()
        if (intent.getIntExtra("home_page", 0) != 0) window.decorView.postDelayed({ session.showHomePage(intent.getIntExtra("home_page", 0)) }, 2000)
        // "--es open_app package" (through the launcher): that app in a window, for a test without hands on the phone
        intent.getStringExtra("open_app")?.let { pkg -> window.decorView.postDelayed({ dev.ultraxr.shell.AppShell.componentOf(this, pkg)?.let { session.openApp(pkg.substringAfterLast('.'), it) } ?: Tracking.log("apps: $pkg is not installed") }, 4000) }
        // Windows from last time are where they were left (docs/INTERFACE.md: things stay where they are put).
        // A mark of the shelf on the main screen was tapped: that app opens as soon as this screen is up. (A moment
        // later, so that the helper that runs Android apps in windows has had time to answer.)
        HomeApps.all.getOrNull(intent.getIntExtra(EXTRA_OPEN_SHELF, -1))?.let { a -> window.decorView.postDelayed({ if (!isFinishing && !isDestroyed) session.open(a) }, 800) }
        refreshChips()
    }

    override fun onDestroy() { session.stop(); Tracking.release(); super.onDestroy() }

    // 3D is only asked for while the desktop is on screen: anything else Android shows on the
    // glasses (its own mirror of the phone) is a flat picture and needs them in 2D.
    override fun onStart() { super.onStart(); Tracking.stereoWant("desktop", session.depth3d) }
    override fun onStop() { Tracking.stereoWant("desktop", false); super.onStop() }

    override fun onResume() {
        super.onResume()
        active = true
        displays.registerDisplayListener(displayListener, ui)
        handedOver = false
        session.resume(); refreshStage()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        Tracking.keepAliveInBackground(this)
        active = false
        displays.unregisterDisplayListener(displayListener)
        session.pause()
        preview?.onPause()
        super.onPause()
    }

    // ---- where the scene is shown: the glasses when attached, otherwise the preview on this screen
    private fun external(): Display? = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.name?.startsWith("ultraxr-window-") != true && it.flags and Display.FLAG_PRIVATE == 0 }      // never one of this app's own window displays

    /** A game was started from here and owns the glasses until this screen is in front again. */
    private var handedOver = false

    private var stageTries = 0
    private fun refreshStage() {
        if (isFinishing || isDestroyed || handedOver) return
        val d = external()
        if (d != null) {
            if (glasses?.display?.displayId == d.displayId) return
            dismissGlasses(); removePreview()
            try {
                val r = DesktopRenderer(false) { id, s -> session.onSurface(id, s) }
                r.periodNs = (1e9 / d.refreshRate.coerceAtLeast(30f)).toLong()
                val p = StagePresentation(this, d, r); p.show(); glasses = p
                session.renderers = listOf(r)
                Tracking.noteGraphics("desktop shown on glasses display ${d.displayId}")
                stageTries = 0
            } catch (e: Exception) {
                Tracking.noteGraphics("desktop could not open on the glasses: $e"); showPreview()
                // a game that was just left may still hold the glasses for a moment: try again
                if (++stageTries <= 12) window.decorView.postDelayed({ refreshStage() }, 700)
            }
        } else { dismissGlasses(); showPreview() }
    }

    private fun showPreview() {
        previewHost.visibility = View.VISIBLE
        if (preview != null) { preview?.onResume(); return }
        val r = DesktopRenderer(true) { id, s -> session.onSurface(id, s) }
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) r.eyeCapture = java.io.File(filesDir, "eye_capture.png")
        val v = GLSurfaceView(this).apply { setEGLContextClientVersion(3); setRenderer(r); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY }
        var lx = 0f; var ly = 0f
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y }
                MotionEvent.ACTION_MOVE -> { r.previewYaw += (e.x - lx) / view.width * 1.6; r.previewPitch = (r.previewPitch + (e.y - ly) / view.height * 1.0).coerceIn(-1.2, 1.2); lx = e.x; ly = e.y }
            }
            true
        }
        previewHost.addView(v, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview = v; previewRenderer = r; session.renderers = listOf(r)
        previewHint.text = "Preview (no glasses). Drag to look around."; previewHint.visibility = View.VISIBLE
    }

    /** With the glasses on, the preview and its place on the screen are gone: the touchpad has the whole upper two thirds. */
    private fun removePreview() { previewHost.visibility = View.GONE; val v = preview ?: return; v.onPause(); previewHost.removeView(v); preview = null; previewRenderer = null }
    private fun dismissGlasses() { try { glasses?.dismiss() } catch (_: Exception) {}; glasses = null }

    private class StagePresentation(c: Context, d: Display, private val r: DesktopRenderer) : Presentation(c, d) {
        private var view: GLSurfaceView? = null
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val v = GLSurfaceView(context).apply { setEGLContextClientVersion(3); setRenderer(r); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY }
            view = v; setContentView(v)
        }
        override fun onStop() { view?.onPause(); super.onStop() }
    }

    // ---- phone interface
    private fun build() {
        val all = FrameLayout(this)
        val root = Ui.column(this)
        Ui.fitBars(root, 16, 10, 10)
        // Something other than a text box can hold focus, so keys go to the focused window.
        root.isFocusable = true; root.isFocusableInTouchMode = true; rootView = root

        // one line: the name, and the state in words
        val head = Ui.row(this)
        head.add(Ui.text(this, "Ultra XR", 17f, bold = true), ViewGroup.LayoutParams.WRAP_CONTENT)
        status = Ui.text(this, "", 14f, Ui.DUSK).apply { gravity = Gravity.END; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        head.add(status, 0, weight = 1f, startDp = 12)
        root.add(head)

        // "keys": a line to type into, at the top so that Android's keyboard never covers it. Hidden until asked for.
        keysBar = Ui.row(this).apply { visibility = View.GONE }
        keysBox = field("Text for the page in focus", "Text to type", EditorInfo.IME_ACTION_SEND) { typeKeys() }
        keysBar.add(keysBox, 0, weight = 1f)
        keysBar.add(Ui.word(this, "type") { typeKeys() }.apply { contentDescription = "Type" }, ViewGroup.LayoutParams.WRAP_CONTENT, startDp = 6)
        root.add(keysBar, topDp = 6)

        // the preview: only while there are no glasses to show the scene
        previewHost = FrameLayout(this).apply { visibility = View.GONE }
        previewHint = Ui.text(this, "", 13f, Ui.DUSK).apply { setPadding(Ui.dp(this@DesktopActivity, 10), Ui.dp(this@DesktopActivity, 8), 0, 0) }
        previewHost.addView(previewHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.add(previewHost, h = Ui.dp(this, 170), topDp = 10)

        // the touchpad: the upper two thirds. Four corner brackets, as round a window in the glasses; Lamp under a finger.
        val pad = Ui.text(this, "Slide to point  ·  tap to select\nTwo fingers to scroll\nDouble-tap and hold to drag", 14f, Ui.SLATE).apply {
            background = Brackets(this@DesktopActivity, Ui.SLATE, armDp = 20f, closeOnPress = false)
            gravity = Gravity.CENTER; contentDescription = "Touchpad"; setLineSpacing(0f, 1.4f)
        }
        pad.setOnTouchListener(Pad())
        root.add(pad, h = 0, weight = 2f, topDp = 10)

        // the lower third: the d-pad, with "back" and "home" above it either side and "keys" and "more" below
        val low = Ui.row(this)
        val dpad = DpadView(this)
        dpad.onKey = { key, down -> if (down) padKeyAt = android.os.SystemClock.uptimeMillis(); session.dpad(key, down) }
        val left = Ui.column(this); val right = Ui.column(this)
        // Back goes down and comes up, as a key does: held for a second it leaves a game's page (DesktopSession.dpad).
        val back = side("back", "Back") { session.dpad(FocusKey.BACK, true); session.dpad(FocusKey.BACK, false) }
        back.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; padKeyAt = android.os.SystemClock.uptimeMillis(); tick(heavy = false); session.dpad(FocusKey.BACK, true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; session.dpad(FocusKey.BACK, false) }
            }
            true
        }
        left.add(back, h = 0, weight = 1f)
        left.add(side("keys", "Keys: type into the page in focus") { tick(heavy = false); if (keysOpen) closeKeys() else openKeys() }, h = 0, weight = 1f)
        right.add(side("home", "Home: put the windows away and show the shelf") { tick(heavy = false); session.showHome() }, h = 0, weight = 1f)
        right.add(side("more", "More: everything else, as a list") { tick(heavy = false); openSheet() }, h = 0, weight = 1f)
        low.add(left, Ui.dp(this, 76), ViewGroup.LayoutParams.MATCH_PARENT)
        low.add(dpad, 0, ViewGroup.LayoutParams.MATCH_PARENT, weight = 1f)
        low.add(right, Ui.dp(this, 76), ViewGroup.LayoutParams.MATCH_PARENT)
        root.add(low, h = 0, weight = 1f, topDp = 8)
        all.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // "more": a second screen over this one, a plain list. Its ground is the screen's own, so nothing shows through.
        val col = Ui.column(this)
        Ui.fitBars(col, 16, 10, 28)
        val top = Ui.row(this)
        top.add(Ui.text(this, "More", 28f, bold = true), 0, weight = 1f)
        top.add(Ui.word(this, "close") { closeSheet() }.apply { contentDescription = "Close the list" }, ViewGroup.LayoutParams.WRAP_CONTENT)
        col.add(top)
        val bar = Ui.row(this)
        urlBox = field("Search or address", "Address", EditorInfo.IME_ACTION_GO) { go() }
        bar.add(urlBox, 0, weight = 1f)
        bar.add(Ui.word(this, "go") { go() }.apply { contentDescription = "Go" }, ViewGroup.LayoutParams.WRAP_CONTENT, startDp = 6)
        col.add(bar, topDp = 8)
        sheetList = Ui.column(this)
        col.add(sheetList)
        sheet = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Ui.GROUND); isClickable = true; visibility = View.GONE; addView(col) }
        all.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(all); focusGate()
    }

    /**
     * Android gives the first d-pad key after a touch to one of the screen's own buttons (it "leaves touch mode") and the
     * key is lost to the desktop. Nothing on this screen is worked with a d-pad: the keys belong to the one focus in the
     * glasses. So the screen's views take no key focus, except while the list or the line to type on is open.
     */
    private fun focusGate() { (window.decorView as ViewGroup).descendantFocusability = if (sheetOpen || keysOpen) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS }

    /** One of the four words beside the d-pad. It fills its quarter of the side, so the thumb finds it without a look; its letters shrink before they would be cut. */
    private fun side(label: String, says: String, onClick: () -> Unit): TextView = Ui.word(this, label, onClick).apply {
        contentDescription = says; maxLines = 1; setPadding(0, 0, 0, 0)
        background = WordLine(this@DesktopActivity, belowCentreDp = 15f * resources.configuration.fontScale.coerceAtMost(1.3f))
        setAutoSizeTextTypeUniformWithConfiguration(11, 17, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    /** A line to type on: no box, a rule under it that turns to Lamp while it is typed into. */
    private fun field(hint: String, says: String, action: Int, done: () -> Unit): EditText = EditText(this).apply {
        this.hint = hint; setSingleLine(); textSize = 16f; typeface = Ui.face(this@DesktopActivity); setTextColor(Ui.CHALK); setHintTextColor(Ui.DUSK)
        background = FieldLine(this@DesktopActivity); minHeight = Ui.dp(this@DesktopActivity, 48)
        setPadding(Ui.dp(this@DesktopActivity, 2), Ui.dp(this@DesktopActivity, 8), Ui.dp(this@DesktopActivity, 2), Ui.dp(this@DesktopActivity, 10))
        imeOptions = action; contentDescription = says
        highlightColor = (Ui.LAMP and 0x00FFFFFF) or 0x55000000
        textCursorDrawable = GradientDrawable().apply { setColor(Ui.LAMP); setSize(Ui.dp(this@DesktopActivity, 2), 0) }
        setOnEditorActionListener { _, _, _ -> done(); true }
    }

    private fun go() { val t = urlBox.text.toString(); if (t.isBlank()) return; if (session.desktop.focused == null) session.open("Browser", HomeApps.toUrl(t)) else session.navigate(t); urlBox.setText(""); closeSheet() }
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBox.windowToken, 0); urlBox.clearFocus(); keysBox.clearFocus(); rootView?.requestFocus()
    }
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    // ---- "keys": Android's keyboard for the page in focus
    private fun openKeys() {
        if (session.desktop.focused == null) { toast("No window is in focus: open one first"); return }
        keysBar.visibility = View.VISIBLE; focusGate(); keysBox.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(keysBox, 0)
    }
    private fun closeKeys() { hideKeyboard(); keysBar.visibility = View.GONE; window.decorView.clearFocus(); focusGate() }
    /** What was typed goes to the page in focus, followed by Enter (an empty line is Enter alone). */
    private fun typeKeys() { session.type(keysBox.text.toString(), true); keysBox.setText(""); closeKeys() }

    // ---- "more": the list
    private fun openSheet() { if (keysOpen) closeKeys(); fillSheet(); sheet.scrollTo(0, 0); sheet.visibility = View.VISIBLE; focusGate() }
    private fun closeSheet() { hideKeyboard(); sheet.visibility = View.GONE; window.decorView.clearFocus(); focusGate() }

    /** Called whenever the windows change. The list behind "more" shows them, so it is written again if it is open. */
    private fun refreshChips() {
        val d = session.desktop
        val key = d.windows.joinToString("|") { "${it.id}:${it.title}:${it.minimized}:${it.id == d.focusedId}:${it.follow}:${it.maximized}" } + "/$moveMode/${d.fullscreenId}"
        if (key == lastChips) return
        lastChips = key
        if (sheetOpen) fillSheet()
        val f = d.focused
        if (!urlBox.hasFocus()) urlBox.hint = f?.url?.removePrefix("https://")?.removePrefix("www.")?.take(40)?.ifBlank { null } ?: "Search or address"
    }

    /**
     * Everything that is not the touchpad or the d-pad, as one plain list: the open windows, the apps, what can be
     * done with the window in focus and with all of them, pocket mode, how the desktop behaves, and the settings.
     * A row that does something closes the list, so the touchpad is under the thumb again; a row that is on or off
     * stays.
     */
    private fun fillSheet() {
        val l = sheetList; l.removeAllViews()
        val d = session.desktop; val f = d.focused
        fun group(s: String) { l.add(Ui.label(this, s), topDp = 28) }
        fun act(name: String, line: String, value: String = "", colour: Int = Ui.CHALK, run: () -> Unit): ListRow {
            val r = ListRow(this).set(name, value, line); r.name.setTextColor(colour); r.onTap { run(); closeSheet() }; l.add(r); return r
        }
        fun onOff(name: String, line: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val r = ListRow(this).set(name, "", line).onOff(get()); r.onTap { val n = !get(); set(n); r.onOff(n) }; l.add(r)
        }

        group("Windows")
        if (d.windows.isEmpty()) l.add(Ui.text(this, "No windows are open. Open one below.", 15f, Ui.DUSK), topDp = 12)
        for (w in d.windows) {
            val focused = w.id == d.focusedId
            val r = act(w.title.ifBlank { "Window" }.take(40), "", if (w.minimized) "put away" else if (focused) "in focus" else "") { if (w.minimized) session.desktop.restore(w.id) else session.focus(w.id); refreshChips() }
            if (w.minimized) r.value.setTextColor(Ui.DUSK)
            r.contentDescription = "Window ${w.id}, ${w.title.ifBlank { "untitled" }}" + (if (w.minimized) ", put away" else if (focused) ", in focus" else "")
        }

        group("Open")
        for (a in HomeApps.all) act(a.title, "") { if (!session.open(a)) toast("Six windows is the limit: close one first") }.lead(MarkView(this, a.color, false, dev.ultraxr.desktop.HomeIcon.sign(a.title, a.kind)), 40)

        group("The window in focus")
        if (f == null) l.add(Ui.text(this, "No window is in focus, so these do nothing yet.", 15f, Ui.DUSK), topDp = 12)
        act("Move", "Carry it with the touchpad or the d-pad. Select puts it down.", if (moveMode) "moving" else "") { session.toggleMove(); refreshChips() }
        act("Min", "Puts it away. It stays in the list above.") { session.desktop.focused?.let { session.minimize(it.id) } }
        act("Max", "As large as the view allows, and back again.", if (f?.maximized == true) "on" else "") { session.desktop.focused?.let { session.toggleMaximize(it.id) } }
        act("Full", "Only this window, filling the view, and back again.", if (f != null && d.fullscreenId == f.id) "on" else "") { session.desktop.focused?.let { session.toggleFullscreen(it.id) } }
        act("Pin", "A pinned window stays where it is in the room. Otherwise it follows your head.", if (f == null) "" else if (f.follow) "follows your head" else "pinned to the room") { session.desktop.focused?.let { session.toggleFollow(it.id) } }
            .also { if (f?.follow == false) it.value.setTextColor(Ui.TIDE) }      // Tide: pinned to the room, as its pin is in the glasses
        act("Tall or wide", "Stands the window up, or lays it down again.") { session.toggleShape() }
        act("Page back", "The page before this one.") { session.back() }
        act("Reload", "Loads the page again.") { session.reload() }
        act("Close", "Closes this window.", colour = Ui.EMBER) { session.desktop.focused?.let { session.close(it.id) } }

        group("All windows")
        act("Gather", "Brings every window in front of you.") { session.gather() }
        act("Main menu", "Puts the windows away and shows the shelf. The same as \"home\".") { session.showHome() }
        act("Recentre", "Where you face now becomes straight ahead.") { session.recenter() }
        act("Close all windows", "Every window is closed, not put away.", colour = Ui.EMBER) { session.resetLayout() }

        group("This phone")
        act("Pocket mode", "The whole screen becomes a black touchpad that works by feel. Back, twice, leaves it.") { enterPocket() }
        act("Settings", "Eye distance, the room, hand tracking and the rest.") { startActivity(Intent(this, SettingsActivity::class.java)) }

        group("How the desktop behaves")
        onOff("Focus the window I look at", "Looking at a window is enough to bring the focus to it.", { session.gazeFocus }) { session.gazeFocus = it }
        onOff("Pointer by looking", "Resting your gaze on a mark or a word presses it: no phone and no hands needed.", { session.lookToPress }) { session.lookToPress = it }
        onOff("Hand gestures", "Pinch to select; pinch \"move\" to carry a window.", { session.handGestures }) { session.handGestures = it }
        onOff("Dim other windows", "Windows that are not in focus are drawn fainter.", { session.dimUnfocused }) { session.dimUnfocused = it }
        onOff("Dark pages", "Easier on see-through glasses. Applies to windows opened from now on.", { session.darkPages }) { session.darkPages = it }
        onOff("Position tracking", "Experimental. Off: the windows follow turning only.", { session.usePosition }) { session.usePosition = it }
        onOff("Hand tracking", "Off: the touchpad, the d-pad and looking only.", { Tracking.settings.handTracking }) { Tracking.setHandTracking(it) }
        onOff("3D depth", "Each eye gets half the sharpness across. Goes back to 2D by itself if the link fails.", { session.depth3d }) {
            session.depth3d = it; Tracking.link?.stereo?.reset(); Tracking.stereoWant("desktop", it)
        }
        onOff("Swap left and right eye", "If depth looks inside out.", { session.swapEyes }) { session.swapEyes = it }
    }

    // ---- pocket mode: the whole screen is a black touchpad that works by feel
    /**
     * Gestures (each answered with a haptic tick):
     * slide = pointer, tap = click, double-tap and hold = drag, two fingers = scroll, pinch = resize,
     * two-finger tap = next window, long press = Move on/off, three-finger tap = bring everything in
     * front of you. Leave with a three-finger long press, or Back twice.
     */
    private fun enterPocket() {
        if (pocket != null) return
        val v = View(this).apply { setBackgroundColor(0xFF000000.toInt()); contentDescription = "Pocket touchpad"; isClickable = true }
        v.setOnTouchListener(Pad(pocketMode = true))
        addContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pocket = v
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        window.decorView.windowInsetsController?.let { it.hide(android.view.WindowInsets.Type.systemBars()); it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        hideKeyboard(); tick(heavy = true)
        Tracking.log("desktop: pocket mode on")
    }

    private fun exitPocket() {
        val v = pocket ?: return
        (v.parent as? ViewGroup)?.removeView(v); pocket = null
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        window.decorView.windowInsetsController?.show(android.view.WindowInsets.Type.systemBars())
        tick(heavy = true)
        Tracking.log("desktop: pocket mode off")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pocket != null) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - backPressedAt < 1500) exitPocket() else { backPressedAt = now; tick(heavy = false) }
            return
        }
        // The list, then the line to type on, are what Back leaves first.
        if (sheetOpen) { closeSheet(); return }
        if (keysOpen) { closeKeys(); return }
        // Back as Android reports it when it does not send the key (a swipe from the edge on newer versions): the same
        // Back of the one focus. Only from the shelf, where Back does nothing, does it leave this screen.
        if (session.desktop.focus !is Focus.Shelf) { session.press(FocusKey.BACK); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun tick(heavy: Boolean) {
        val v = pocket ?: window.decorView
        v.performHapticFeedback(if (heavy) android.view.HapticFeedbackConstants.LONG_PRESS else android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * Keys from the phone, a Bluetooth gamepad, a remote or a keyboard. The directions, Select (d-pad centre, Enter,
     * A), Back (Back, Escape, B) and the shoulder keys move the one focus (docs/INTERFACE.md, DesktopSession.key);
     * while a page is in use they go to the page. What the focus does not take goes on as before: a keyboard types
     * into the focused window, the volume keys scroll it. Menu shows the main menu.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!typing) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU && pocket == null) { if (event.action == KeyEvent.ACTION_UP) session.showHome(); return true }
            // The phone's own Back key still leaves pocket mode (pressed twice), the list behind "more" and the line to
            // type on, and, with the focus on the shelf, where Back does nothing, this screen. A gamepad's B and a
            // keyboard's Escape never leave the screen.
            val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            val phoneBack = event.keyCode == KeyEvent.KEYCODE_BACK && !fromPad
            if (!(phoneBack && (pocket != null || sheetOpen || keysOpen || session.desktop.focus is Focus.Shelf)) && session.key(event)) return true
        }
        // A hardware keyboard types into the focused window, unless one of this screen's text boxes is being used.
        if (!typing && event.keyCode != KeyEvent.KEYCODE_BACK && !KeyEvent.isGamepadButton(event.keyCode)
            && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && (event.source == 0 || event.source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD)
            && session.focusedWeb()?.forwardKey(event) == true) return true
        // The volume keys scroll the focused window, so it can be done by feel with the glasses on.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (session.desktop.focused != null) {
                if (event.action == KeyEvent.ACTION_DOWN) session.padScroll(0.0, if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) -0.22 else 0.22)
                return true
            }
        }
        val pad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        // a controller's other keys (X, Y, the triggers, the sticks' clicks) belong to the page only while it is in use
        if (pad && session.pageInUse && session.focusedWeb()?.forwardKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    /** Whether this screen's window has the keys: when it has not, they may be going to an app in one of the windows. */
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); session.phoneFocus = hasFocus }

    private var mouseX = Float.NaN; private var mouseY = Float.NaN

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // A mouse moves the pointer in the focused window; its wheel scrolls it.
        if (event.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    if (!mouseX.isNaN()) session.padMove(((event.x - mouseX) / 900f).toDouble(), ((event.y - mouseY) / 900f).toDouble())
                    mouseX = event.x; mouseY = event.y; return true
                }
                MotionEvent.ACTION_SCROLL -> { session.padScroll(0.0, (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 0.12).toDouble()); return true }
                MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_HOVER_ENTER -> { mouseX = Float.NaN; mouseY = Float.NaN }
            }
        }
        // A controller's sticks and its d-pad (which many report as a stick) go to the page only while it is in use.
        // Otherwise they are left alone, and Android turns them into the arrow keys that move the focus.
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && session.pageInUse && session.focusedWeb()?.forwardMotion(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /** Touchpad gestures. */
    private inner class Pad(private val pocketMode: Boolean = false) : View.OnTouchListener {
        private var maxPointers = 0; private var longPress: Runnable? = null
        private var lx = 0f; private var ly = 0f; private var sx = 0f; private var sy = 0f
        private var downAt = 0L; private var lastTapAt = 0L
        private var moved = false; private var dragging = false; private var multi = false
        private var span = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val w = v.width.coerceAtLeast(1).toFloat(); val slop = Ui.dp(this@DesktopActivity, 10)
            // the pad's brackets show the finger on it (nothing else here depends on this)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.isPressed = true else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) v.isPressed = false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.x; ly = e.y; sx = e.x; sy = e.y; downAt = e.eventTime; moved = false; multi = false; maxPointers = 1
                    if (e.eventTime - lastTapAt < 320 && !moveMode) { dragging = true; session.padPress(true) }
                    else if (pocketMode) { cancelLong(); longPress = Runnable { if (!moved && maxPointers == 1) { session.toggleMove(); refreshChips(); tick(heavy = true); moved = true } }.also { ui.postDelayed(it, 650) } }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true; maxPointers = maxOf(maxPointers, e.pointerCount); if (dragging) { dragging = false; session.padPress(false) }; lx = cx(e); ly = cy(e); span = span(e); sx = lx; sy = ly
                    if (pocketMode && e.pointerCount == 3) { cancelLong(); longPress = Runnable { if (!moved) { moved = true; exitPocket() } }.also { ui.postDelayed(it, 900) } }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val x = cx(e); val y = cy(e); val s = span(e)
                        if (!moved && hypot(x - sx, y - sy) < slop && (span <= 0 || abs(s / span - 1f) < 0.035f)) return true   // still a possible tap
                        if (span > 0 && s > 0 && abs(s / span - 1f) > 0.035f) { session.padPinch((s / span).toDouble()); span = s }
                        else session.padScroll(((x - lx) / w).toDouble(), ((y - ly) / w).toDouble())
                        lx = x; ly = y; moved = true; cancelLong()
                    } else if (!multi) {
                        if (!moved && hypot(e.x - sx, e.y - sy) > slop) { moved = true; cancelLong() }
                        if (moved) session.padMove(((e.x - lx) / w).toDouble(), ((e.y - ly) / w).toDouble())
                        lx = e.x; ly = e.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLong()
                    val quick = e.actionMasked == MotionEvent.ACTION_UP && e.eventTime - downAt < 320
                    if (dragging) { dragging = false; session.padPress(false) }
                    else if (!moved && !multi && quick) { session.padTap(); lastTapAt = e.eventTime; if (pocketMode) tick(heavy = false) }
                    else if (pocketMode && !moved && quick && maxPointers == 2) { if (session.cycleFocus()) tick(heavy = false) }
                    else if (pocketMode && !moved && quick && maxPointers >= 3) { session.gather(); tick(heavy = true) }
                }
            }
            return true
        }
        private fun cancelLong() { longPress?.let { ui.removeCallbacks(it) }; longPress = null }
        private fun cx(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
        private fun cy(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2
        private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    companion object { const val EXTRA_AUTOMATION = "automation"; const val EXTRA_STEREO_PREVIEW = "stereo_preview"; const val EXTRA_OPEN_SHELF = "open_shelf" }
}
