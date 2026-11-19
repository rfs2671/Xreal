package dev.ultraxr.desktop

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import dev.ultraxr.tracking.Tracking

/**
 * One desktop window's content: a WebView living on its own private virtual display, which
 * renders into a Surface the 3D renderer samples as a texture. A virtual display (rather than
 * drawing the view into a canvas) gives the page a real hardware-accelerated window, so video
 * and WebGL work. Everything here runs on the main thread.
 *
 * A window of kind "app" holds another app instead of a page: its address is "app://package/activity". The virtual
 * display is made the same way, with nothing of ours on it, and the shell helper ([dev.ultraxr.shell.AppShell]) is
 * asked to start that app's activity there and to pass it touches and keys; Android lets only the shell user do both.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebWindow(private val context: Context, val id: Int, private val kind: String, initialUrl: String, private val darkPages: Boolean, private val onChanged: (WebWindow) -> Unit) {
    val pixelWidth = PIXEL_WIDTH
    var pixelHeight = PIXEL_HEIGHT; private set
    var title: String = ""; private set
    var url: String = initialUrl; private set
    var loading = false; private set
    var progress = 0; private set
    var lastError = ""; private set

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var web: WebView? = null
    private var pendingUrl: String? = initialUrl
    private var downTime = 0L
    private var touching = false
    val isApp = kind == "app"
    /** For an app window: "package/activity". */
    private val component = if (isApp) initialUrl.removePrefix("app://") else ""
    private val displayId: Int get() = display?.display?.displayId ?: -1
    private var lastMoveMs = 0L

    /** Attaches (or re-attaches after the renderer's GL context was recreated) the output surface. */
    fun attach(surface: Surface) {
        val d = display
        if (d != null) { d.surface = surface; return }
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay("ultraxr-window-$id", pixelWidth, pixelHeight, DENSITY, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            display = vd
            if (isApp) {
                title = title.ifBlank { component.substringBefore('/') }
                dev.ultraxr.shell.AppShell.send("launch " + vd.display.displayId + " " + component) { a ->
                    if (!a.startsWith("ok")) { lastError = if (a.contains("no helper")) "The app helper is not running" else a.removePrefix("error ").take(120) }
                    Tracking.log("apps: " + component + " on display " + vd.display.displayId + ": " + a.take(160))
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onChanged(this) }
                }
                return
            }
            val p = Page(context, vd.display)
            p.show()
            presentation = p
        } catch (e: Exception) {
            lastError = e.toString(); Tracking.log("desktop: window $id could not be created: $e"); onChanged(this)
        }
    }

    fun detachSurface() { try { display?.surface = null } catch (_: Exception) {} }

    fun load(target: String) { if (isApp) return; url = target; val w = web; if (w != null) w.loadUrl(target) else pendingUrl = target }
    fun back() { if (isApp) { key(KeyEvent.KEYCODE_BACK); return }; web?.let { if (it.canGoBack()) it.goBack() } }
    fun reload() { web?.reload() }
    fun canGoBack(): Boolean = web?.canGoBack() ?: false

    /** Inserts text at the page's focused field, as typing on a keyboard would; then Enter if asked. */
    fun typeText(text: String, enter: Boolean) {
        if (isApp) {
            if (text.isNotEmpty() && displayId >= 0) dev.ultraxr.shell.AppShell.send("text $displayId $text")
            if (enter) key(KeyEvent.KEYCODE_ENTER)
            return
        }
        val w = web ?: return
        if (text.isEmpty()) { if (enter) key(KeyEvent.KEYCODE_ENTER); return }
        val js = org.json.JSONObject.quote(text)
        // Enter is sent only after the text has gone in: the script runs asynchronously.
        w.evaluateJavascript("(function(t){var e=document.activeElement;if(!e)return;try{if(!document.execCommand('insertText',false,t)){if('value' in e){e.value+=t;e.dispatchEvent(new Event('input',{bubbles:true}));}}}catch(x){}})($js)") { if (enter) key(KeyEvent.KEYCODE_ENTER) }
    }

    fun key(code: Int) {
        if (isApp) { if (displayId >= 0) dev.ultraxr.shell.AppShell.send("key $displayId $code"); return }
        val w = web ?: return
        val t = SystemClock.uptimeMillis()
        w.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); w.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Keyboard and gamepad events from the phone's activity, passed to the page (typing, Gamepad API). */
    fun forwardKey(e: KeyEvent): Boolean = web?.dispatchKeyEvent(e) ?: false
    fun forwardMotion(e: MotionEvent): Boolean = web?.dispatchGenericMotionEvent(e) ?: false

    /** A touch at ([u], [v]) of the content, 0..1 from the top-left. [action] is a MotionEvent action. */
    fun touch(action: Int, u: Double, v: Double) {
        if (isApp) {
            val id = displayId; if (id < 0) return
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) touching = true else if (!touching) return
            // moves are passed on at most every 12 ms: each is a message to the helper
            if (action == MotionEvent.ACTION_MOVE) { if (now - lastMoveMs < 12) return; lastMoveMs = now }
            dev.ultraxr.shell.AppShell.send("touch $id $action " + (u.coerceIn(0.0, 1.0) * pixelWidth).toInt() + " " + (v.coerceIn(0.0, 1.0) * pixelHeight).toInt())
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touching = false
            return
        }
        val w = web ?: return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) { downTime = now; touching = true } else if (!touching) return
        val x = (u.coerceIn(0.0, 1.0) * w.width).toFloat(); val y = (v.coerceIn(0.0, 1.0) * w.height).toFloat()
        val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        try { w.dispatchTouchEvent(e) } finally { e.recycle() }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touching = false
    }

    fun tap(u: Double, v: Double) { touch(MotionEvent.ACTION_DOWN, u, v); touch(MotionEvent.ACTION_UP, u, v) }

    /** Scrolls the page at ([u], [v]) by [dx], [dy] pixels of content. */
    fun scroll(u: Double, v: Double, dx: Float, dy: Float) {
        if (isApp) {
            // a short drag of the finger the other way, as on a phone
            val id = displayId; if (id < 0 || touching) return
            val x = (u.coerceIn(0.0, 1.0) * pixelWidth).toInt(); val y = (v.coerceIn(0.0, 1.0) * pixelHeight).toInt()
            val sh = dev.ultraxr.shell.AppShell
            sh.send("touch $id 0 $x $y"); sh.send("touch $id 2 " + (x - dx / 2).toInt() + " " + (y - dy / 2).toInt()); sh.send("touch $id 2 " + (x - dx).toInt() + " " + (y - dy).toInt()); sh.send("touch $id 1 " + (x - dx).toInt() + " " + (y - dy).toInt())
            return
        }
        val w = web ?: return
        w.scrollBy(0, 0) // keeps the view awake for nested scrollers below
        w.evaluateJavascript("(function(x,y,dx,dy){var e=document.elementFromPoint(x,y);while(e&&e!==document.body){var s=getComputedStyle(e);if((e.scrollHeight>e.clientHeight&&/(auto|scroll)/.test(s.overflowY))||(e.scrollWidth>e.clientWidth&&/(auto|scroll)/.test(s.overflowX))){e.scrollBy(dx,dy);return;}e=e.parentElement;}window.scrollBy(dx,dy);})(" +
            "${u * w.width / w.resources.displayMetrics.density},${v * w.height / w.resources.displayMetrics.density},${dx / w.resources.displayMetrics.density},${dy / w.resources.displayMetrics.density})", null)
    }

    fun pause() { web?.onPause() }
    fun resume() { web?.onResume() }

    fun destroy() {
        if (isApp && component.isNotEmpty()) dev.ultraxr.shell.AppShell.send("stop " + component.substringBefore('/'))
        try { presentation?.dismiss() } catch (_: Exception) {}
        presentation = null
        try { web?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() } } catch (_: Exception) {}
        web = null
        try { display?.release() } catch (_: Exception) {}
        display = null
    }

    // A dark theme, so the page is told the system is in dark mode and darkening applies.
    private inner class Page(c: Context, d: Display) : Presentation(c, d, android.R.style.Theme_Material_NoActionBar) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val w = WebView(context)
            w.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setSupportMultipleWindows(false)
                // On see-through glasses a white page is a bright slab and black is transparent, so pages are
                // asked for their dark theme and darkened when they have none.
                if (darkPages && android.os.Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = true
                // Xbox Cloud Gaming supports Chrome on Android but refuses the embedded-WebView
                // user agent; give that one site the plain Chrome form of the same string.
                if (kind == "xbox") userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
            }
            w.setBackgroundColor(if (darkPages) 0xFF000000.toInt() else 0xFF101418.toInt())
            w.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) { loading = true; if (u != null) url = u; onChanged(this@WebWindow) }
                override fun onPageFinished(view: WebView, u: String?) { loading = false; if (u != null) url = u; title = view.title ?: title; onChanged(this@WebWindow) }
                override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) { if (u != null) { url = u; onChanged(this@WebWindow) } }
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) { lastError = error.description?.toString() ?: "error"; onChanged(this@WebWindow) }
                }
            }
            w.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank()) { title = t; onChanged(this@WebWindow) } }
                override fun onProgressChanged(view: WebView, p: Int) { progress = p }
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    // Protected media (for streaming sites) only; camera and microphone are not granted.
                    val ok = request.resources.filter { it == android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    if (ok.isNotEmpty()) request.grant(ok.toTypedArray()) else request.deny()
                }
            }
            val root = FrameLayout(context)
            root.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setContentView(root)
            web = w
            pendingUrl?.let { w.loadUrl(it); pendingUrl = null }
        }
    }

    companion object {
        const val PIXEL_WIDTH = 1280
        const val PIXEL_HEIGHT = 720
        const val DENSITY = 200
    }
}
