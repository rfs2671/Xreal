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
 */
@SuppressLint("SetJavaScriptEnabled")
class WebWindow(private val context: Context, val id: Int, private val kind: String, initialUrl: String, private val onChanged: (WebWindow) -> Unit) {
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

    /** Attaches (or re-attaches after the renderer's GL context was recreated) the output surface. */
    fun attach(surface: Surface) {
        val d = display
        if (d != null) { d.surface = surface; return }
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay("ultraxr-window-$id", pixelWidth, pixelHeight, DENSITY, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            display = vd
            val p = Page(context, vd.display)
            p.show()
            presentation = p
        } catch (e: Exception) {
            lastError = e.toString(); Tracking.log("desktop: window $id could not be created: $e"); onChanged(this)
        }
    }

    fun detachSurface() { try { display?.surface = null } catch (_: Exception) {} }

    fun load(target: String) { url = target; val w = web; if (w != null) w.loadUrl(target) else pendingUrl = target }
    fun back() { web?.let { if (it.canGoBack()) it.goBack() } }
    fun reload() { web?.reload() }
    fun canGoBack(): Boolean = web?.canGoBack() ?: false

    /** Inserts text at the page's focused field, as typing on a keyboard would. */
    fun typeText(text: String, enter: Boolean) {
        val w = web ?: return
        if (text.isNotEmpty()) {
            val js = org.json.JSONObject.quote(text)
            w.evaluateJavascript("(function(t){var e=document.activeElement;if(!e)return;try{if(!document.execCommand('insertText',false,t)){if('value' in e){e.value+=t;e.dispatchEvent(new Event('input',{bubbles:true}));}}}catch(x){}})($js)", null)
        }
        if (enter) key(KeyEvent.KEYCODE_ENTER)
    }

    fun key(code: Int) {
        val w = web ?: return
        val t = SystemClock.uptimeMillis()
        w.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); w.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Gamepad and keyboard events from the phone's activity, passed to the page (Gamepad API). */
    fun forwardKey(e: KeyEvent): Boolean = web?.dispatchKeyEvent(e) ?: false
    fun forwardMotion(e: MotionEvent): Boolean = web?.dispatchGenericMotionEvent(e) ?: false

    /** A touch at ([u], [v]) of the content, 0..1 from the top-left. [action] is a MotionEvent action. */
    fun touch(action: Int, u: Double, v: Double) {
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
        val w = web ?: return
        w.scrollBy(0, 0) // keeps the view awake for nested scrollers below
        w.evaluateJavascript("(function(x,y,dx,dy){var e=document.elementFromPoint(x,y);while(e&&e!==document.body){var s=getComputedStyle(e);if((e.scrollHeight>e.clientHeight&&/(auto|scroll)/.test(s.overflowY))||(e.scrollWidth>e.clientWidth&&/(auto|scroll)/.test(s.overflowX))){e.scrollBy(dx,dy);return;}e=e.parentElement;}window.scrollBy(dx,dy);})(" +
            "${u * w.width / w.resources.displayMetrics.density},${v * w.height / w.resources.displayMetrics.density},${dx / w.resources.displayMetrics.density},${dy / w.resources.displayMetrics.density})", null)
    }

    fun pause() { web?.onPause() }
    fun resume() { web?.onResume() }

    fun destroy() {
        try { presentation?.dismiss() } catch (_: Exception) {}
        presentation = null
        try { web?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() } } catch (_: Exception) {}
        web = null
        try { display?.release() } catch (_: Exception) {}
        display = null
    }

    private inner class Page(c: Context, d: Display) : Presentation(c, d) {
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
                // Xbox Cloud Gaming supports Chrome on Android but refuses the embedded-WebView
                // user agent; give that one site the plain Chrome form of the same string.
                if (kind == "xbox") userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
            }
            w.setBackgroundColor(0xFF101418.toInt())
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
