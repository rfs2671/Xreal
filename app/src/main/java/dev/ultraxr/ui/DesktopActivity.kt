package dev.ultraxr.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Presentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.ultraxr.desktop.DesktopRenderer
import dev.ultraxr.desktop.DesktopSession
import dev.ultraxr.desktop.HomeApps
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.ui.Ui.add
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The spatial desktop's phone side: the glasses show the windows; this screen is the remote.
 * A touchpad moves the pointer in the focused window (tap to click, two fingers to scroll,
 * double-tap and hold to drag), and buttons open sites and arrange windows. Without glasses the
 * same scene is shown in a preview at the top, which can be dragged to look around.
 */
class DesktopActivity : Activity() {
    private lateinit var session: DesktopSession
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var displays: DisplayManager
    private lateinit var previewHost: FrameLayout
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var urlBox: EditText
    private lateinit var moveButton: TextView
    private lateinit var followButton: TextView
    private lateinit var previewHint: TextView
    private var preview: GLSurfaceView? = null
    private var previewRenderer: DesktopRenderer? = null
    private var glasses: StagePresentation? = null
    private var moveMode = false
    private var pocket: View? = null
    private var rootView: View? = null
    private var backPressedAt = 0L
    private var active = false
    private var lastChips = ""

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!active) return
            session.tick(System.nanoTime())
            if (status.text != session.statusLine) status.text = session.statusLine
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
        session.onLookPress = { tick(heavy = false) }
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
        if (intent.getBooleanExtra(EXTRA_STEREO_PREVIEW, false) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) session.forceStereo = true
        build()
        session.start()
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
    private fun external(): Display? = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.name?.startsWith("ultraxr-window-") != true }

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
                previewHint.text = "Showing on the glasses"; previewHint.visibility = View.VISIBLE
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
        if (preview != null) { preview?.onResume(); return }
        val r = DesktopRenderer(true) { id, s -> session.onSurface(id, s) }
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

    private fun removePreview() { val v = preview ?: return; v.onPause(); previewHost.removeView(v); preview = null; previewRenderer = null }
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
        val root = Ui.column(this)
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 44), Ui.dp(this, 16), Ui.dp(this, 16))
        // Something other than the address box can hold focus, so keys go to the focused window.
        root.isFocusable = true; root.isFocusableInTouchMode = true; rootView = root
        val head = Ui.row(this)
        head.add(Ui.text(this, "Spatial desktop", 24f, bold = true), 0, weight = 1f)
        status = Ui.chip(this); head.add(status, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.add(head)

        previewHost = FrameLayout(this).apply { background = Ui.rounded(0xFF05080B.toInt(), 22f, this@DesktopActivity); clipToOutline = true }
        previewHint = Ui.text(this, "", 12f, 0xFF8FA3B8.toInt()).apply { setPadding(Ui.dp(this@DesktopActivity, 12), Ui.dp(this@DesktopActivity, 8), 0, 0) }
        previewHost.addView(previewHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.add(previewHost, h = Ui.dp(this, 170), topDp = 12)

        // open apps
        val apps = Ui.row(this)
        HomeApps.all.forEachIndexed { i, a ->
            apps.add(small(a.title) { if (!session.open(a)) toast("Six windows is the limit: close one first") }, 0, weight = 1f, startDp = if (i == 0) 0 else 6)
        }
        root.add(apps, topDp = 10)

        // open windows
        chips = Ui.row(this)
        root.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, topDp = 10)

        // address / text
        val bar = Ui.row(this)
        urlBox = EditText(this).apply {
            hint = "Search, address, or text to type"; setSingleLine(); textSize = 15f; setTextColor(Ui.TEXT); setHintTextColor(0xFF6F8296.toInt())
            background = Ui.rounded(Ui.SURFACE_HI, 16f, this@DesktopActivity); setPadding(Ui.dp(this@DesktopActivity, 14), 0, Ui.dp(this@DesktopActivity, 14), 0)
            imeOptions = EditorInfo.IME_ACTION_GO; contentDescription = "Address"
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        bar.add(urlBox, 0, Ui.dp(this, 46), weight = 1f)
        bar.add(small("Go") { go() }, ViewGroup.LayoutParams.WRAP_CONTENT, startDp = 6)
        bar.add(small("Type") { session.type(urlBox.text.toString(), true); urlBox.setText(""); hideKeyboard() }, ViewGroup.LayoutParams.WRAP_CONTENT, startDp = 6)
        root.add(bar, topDp = 10)

        // window controls
        val c1 = Ui.row(this)
        c1.add(small("Back") { session.back() }, 0, weight = 1f)
        c1.add(small("Reload") { session.reload() }, 0, weight = 1f, startDp = 6)
        followButton = small("Lock") { session.desktop.focused?.let { session.toggleFollow(it.id) } }
        c1.add(followButton, 0, weight = 1.3f, startDp = 6)
        c1.add(small("Min") { session.desktop.focused?.let { session.minimize(it.id) } }, 0, weight = 1f, startDp = 6)
        c1.add(small("Max") { session.desktop.focused?.let { session.toggleMaximize(it.id) } }, 0, weight = 1f, startDp = 6)
        root.add(c1, topDp = 10)
        val c2 = Ui.row(this)
        c2.add(small("Full") { session.desktop.focused?.let { session.toggleFullscreen(it.id) } }, 0, weight = 1f)
        c2.add(small("Close") { session.desktop.focused?.let { session.close(it.id) } }, 0, weight = 1f, startDp = 6)
        moveButton = small("Move") { moveMode = !moveMode; refreshChips() }
        c2.add(moveButton, 0, weight = 1f, startDp = 6)
        c2.add(small("Gather") { session.gather() }, 0, weight = 1f, startDp = 6)
        c2.add(small("More") { settings() }, 0, weight = 1f, startDp = 6)
        root.add(c2, topDp = 6)
        root.add(small("Pocket mode") { enterPocket() }.apply { contentDescription = "Pocket mode" }, topDp = 6)

        // touchpad
        val pad = TextView(this).apply {
            background = Ui.rounded(Ui.SURFACE, 26f, this@DesktopActivity, 0x22FFFFFF)
            gravity = Gravity.CENTER; setTextColor(0xFF52667A.toInt()); textSize = 13f; contentDescription = "Touchpad"
            text = "Touchpad\nTap to click  ·  two fingers to scroll\nDouble-tap and hold to drag"
        }
        pad.setOnTouchListener(Pad())
        root.add(pad, h = 0, weight = 1f, topDp = 10)
        setContentView(root)
    }

    private fun small(label: String, onClick: () -> Unit): TextView = Ui.button(this, label) { onClick() }.apply {
        textSize = 13f; minHeight = Ui.dp(this@DesktopActivity, 42); setPadding(Ui.dp(this@DesktopActivity, 8), 0, Ui.dp(this@DesktopActivity, 8), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun go() { val t = urlBox.text.toString(); if (t.isBlank()) return; if (session.desktop.focused == null) session.open("Browser", HomeApps.toUrl(t)) else session.navigate(t); urlBox.setText(""); hideKeyboard() }
    private fun hideKeyboard() { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBox.windowToken, 0); urlBox.clearFocus(); rootView?.requestFocus() }
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun refreshChips() {
        val d = session.desktop
        val key = d.windows.joinToString("|") { "${it.id}:${it.title}:${it.minimized}:${it.id == d.focusedId}:${it.follow}:${it.maximized}" } + "/$moveMode/${d.fullscreenId}"
        if (key == lastChips) return
        lastChips = key
        chips.removeAllViews()
        if (d.windows.isEmpty()) chips.add(Ui.text(this, "No windows open. Pick an app above.", 13f, 0xFF8FA3B8.toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.windows.forEachIndexed { i, w ->
            val focused = w.id == d.focusedId
            val t = Ui.text(this, (if (w.minimized) "▁ " else "") + w.title.ifBlank { "Window" }.take(18), 13f, if (w.minimized) 0xFF7A8C9E.toInt() else Ui.TEXT, bold = focused).apply {
                background = Ui.pressable(this@DesktopActivity, Ui.rounded(if (focused) 0xFF1F3B52.toInt() else Ui.SURFACE_HI, 18f, this@DesktopActivity, if (focused) 0xFF7BE6FF.toInt() else 0))
                setPadding(Ui.dp(this@DesktopActivity, 14), Ui.dp(this@DesktopActivity, 9), Ui.dp(this@DesktopActivity, 14), Ui.dp(this@DesktopActivity, 9))
                contentDescription = "Window ${w.id}"
                setOnClickListener { if (w.minimized) session.desktop.restore(w.id) else session.focus(w.id); refreshChips() }
            }
            chips.add(t, ViewGroup.LayoutParams.WRAP_CONTENT, startDp = if (i == 0) 0 else 8)
        }
        val f = d.focused
        followButton.text = if (f?.follow == true) "Following" else "Locked"
        moveButton.text = if (moveMode) "Moving ✓" else "Move"
        if (f != null && !urlBox.hasFocus()) urlBox.hint = f.url.removePrefix("https://").removePrefix("www.").take(40).ifBlank { "Search or address" }
    }

    private fun settings() {
        val col = Ui.column(this, 18)
        fun box(label: String, value: Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply { text = label; isChecked = value; setTextColor(Ui.TEXT); textSize = 15f; setOnCheckedChangeListener { _, b -> set(b) } }.also { col.add(it, topDp = 4) }
        box("Focus the window I look at", session.gazeFocus) { session.gazeFocus = it }
        box("Look to press: rest your gaze on an app or a title-bar button", session.lookToPress) { session.lookToPress = it }
        box("Hand gestures (pinch to click, pinch a title bar to move)", session.handGestures) { session.handGestures = it }
        box("Dim windows that are not focused", session.dimUnfocused) { session.dimUnfocused = it }
        box("Dark pages (easier on see-through glasses; applies to new windows)", session.darkPages) { session.darkPages = it }
        box("Use position tracking (experimental; off = rotation only)", session.usePosition) { session.usePosition = it }
        box("3D depth (each eye gets half the sharpness across; returns to 2D by itself if the link fails)", session.depth3d) {
            session.depth3d = it; Tracking.link?.stereo?.reset(); Tracking.stereoWant("desktop", it)
        }
        box("Swap left and right eye (if depth looks inside out)", session.swapEyes) { session.swapEyes = it }
        col.add(small("Recenter view") { session.recenter() }, topDp = 12)
        col.add(small("Close all windows") { session.resetLayout() }, topDp = 6)
        AlertDialog.Builder(this).setTitle("Desktop settings").setView(col).setPositiveButton("Done", null).show()
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
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun tick(heavy: Boolean) {
        val v = pocket ?: window.decorView
        v.performHapticFeedback(if (heavy) android.view.HapticFeedbackConstants.LONG_PRESS else android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Game controllers and keyboards reach the focused page, so the Gamepad API works there. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A hardware keyboard types into the focused window, unless the address box is being used.
        if (!urlBox.hasFocus() && event.keyCode != KeyEvent.KEYCODE_BACK && !KeyEvent.isGamepadButton(event.keyCode)
            && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && (event.source == 0 || event.source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD)
            && session.focusedWeb()?.forwardKey(event) == true) return true
        // The volume keys scroll the focused window, so it can be done by feel with the glasses on.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (session.desktop.focused != null) {
                if (event.action == KeyEvent.ACTION_DOWN) session.padScroll(0.0, if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) -0.22 else 0.22, false)
                return true
            }
        }
        val pad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (pad && session.focusedWeb()?.forwardKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    private var mouseX = Float.NaN; private var mouseY = Float.NaN

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // A mouse moves the pointer in the focused window; its wheel scrolls it.
        if (event.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    if (!mouseX.isNaN()) session.padMove(((event.x - mouseX) / 900f).toDouble(), ((event.y - mouseY) / 900f).toDouble(), moveMode)
                    mouseX = event.x; mouseY = event.y; return true
                }
                MotionEvent.ACTION_SCROLL -> { session.padScroll(0.0, (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 0.12).toDouble(), false); return true }
                MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_HOVER_ENTER -> { mouseX = Float.NaN; mouseY = Float.NaN }
            }
        }
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && session.focusedWeb()?.forwardMotion(event) == true) return true
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
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.x; ly = e.y; sx = e.x; sy = e.y; downAt = e.eventTime; moved = false; multi = false; maxPointers = 1
                    if (e.eventTime - lastTapAt < 320 && !moveMode) { dragging = true; session.padPress(true) }
                    else if (pocketMode) { cancelLong(); longPress = Runnable { if (!moved && maxPointers == 1) { moveMode = !moveMode; refreshChips(); tick(heavy = true); moved = true } }.also { ui.postDelayed(it, 650) } }
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
                        else session.padScroll(((x - lx) / w).toDouble(), ((y - ly) / w).toDouble(), moveMode)
                        lx = x; ly = y; moved = true; cancelLong()
                    } else if (!multi) {
                        if (!moved && hypot(e.x - sx, e.y - sy) > slop) { moved = true; cancelLong() }
                        if (moved) session.padMove(((e.x - lx) / w).toDouble(), ((e.y - ly) / w).toDouble(), moveMode)
                        lx = e.x; ly = e.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLong()
                    val quick = e.actionMasked == MotionEvent.ACTION_UP && e.eventTime - downAt < 320
                    if (dragging) { dragging = false; session.padPress(false) }
                    else if (!moved && !multi && quick) { if (!moveMode) session.padTap(); lastTapAt = e.eventTime; if (pocketMode) tick(heavy = false) }
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

    companion object { const val EXTRA_AUTOMATION = "automation"; const val EXTRA_STEREO_PREVIEW = "stereo_preview" }
}
