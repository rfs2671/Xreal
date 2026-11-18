package dev.ultraxr.render

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.WindowManager
import dev.ultraxr.tracking.Tracking

/**
 * Puts a [Scene] in front of the user's eyes: full-screen stereo on the glasses' display
 * when there is one (following display changes, e.g. after the panel switches to 3D mode),
 * otherwise a mono preview inside [previewHost] on the phone.
 */
class GlassesStage(private val activity: Activity, private val previewHost: ViewGroup?, private var scene: Scene) {
    private val displays = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val ui = Handler(Looper.getMainLooper())
    private var presentation: StagePresentation? = null
    private var preview: GLSurfaceView? = null
    private var active = false
    var renderer: XrRenderer? = null; private set
    /** Called on the UI thread whenever the output changes (glasses <-> preview). */
    var onOutputChanged: (() -> Unit)? = null

    val onGlasses get() = presentation != null
    val displayInfo: String get() = presentation?.display?.let { d -> d.mode.let { "${it.physicalWidth}x${it.physicalHeight}@" + dev.ultraxr.core.Fmt.f(it.refreshRate, 0) } } ?: "phone preview"
    val isSideBySide: Boolean get() = presentation?.display?.mode?.let { it.physicalWidth >= it.physicalHeight * 3 } ?: false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { ui.postDelayed({ refresh() }, 400) }
        override fun onDisplayRemoved(id: Int) { if (presentation?.display?.displayId == id) { dismiss(); refresh() } }
        override fun onDisplayChanged(id: Int) {
            val p = presentation ?: return
            if (p.display.displayId == id) { Tracking.log("glasses display changed: $displayInfo"); ui.postDelayed({ refresh() }, 400) }
        }
    }

    fun setScene(s: Scene) { scene = s; renderer?.scene = s }

    fun start() {
        if (active) return
        active = true
        displays.registerDisplayListener(listener, ui)
        refresh()
    }

    fun stop() {
        if (!active) return
        active = false
        displays.unregisterDisplayListener(listener)
        dismiss()
        removePreview()
    }

    fun describeDisplays(): String = dev.ultraxr.glasses.DisplayDiagnostics.describeAll(activity)

    private fun external(): Display? = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        ?: displays.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }

    private var tries = 0
    private fun refresh() {
        if (!active || activity.isFinishing || activity.isDestroyed) return
        val d = external()
        if (d == null) { dismiss(); showPreview(); onOutputChanged?.invoke(); return }
        val wantHz = if (Tracking.settings.refresh90) 90f else 60f
        // Widest side-by-side mode at the wanted refresh rate, else the fastest side-by-side mode.
        val sbs = d.supportedModes.filter { it.physicalWidth == 3840 && it.physicalHeight == 1080 }
        val mode = sbs.minByOrNull { Math.abs(it.refreshRate - wantHz) }
        val cur = presentation
        if (cur != null && cur.display.displayId == d.displayId && cur.modeId == (mode?.modeId ?: 0)) return
        dismiss(); removePreview()
        try {
            // Split into two eyes only when the panel really is in a side-by-side mode.
            val wide = mode != null || d.mode.physicalWidth >= d.mode.physicalHeight * 3
            val r = XrRenderer(scene, stereo = wide, preview = false)
            r.periodNs = (1e9 / (mode?.refreshRate ?: d.refreshRate)).toLong()
            val p = StagePresentation(activity, d, mode?.modeId ?: 0, mode?.refreshRate ?: 0f, r)
            p.show()
            presentation = p; renderer = r; tries = 0
            Tracking.noteGraphics("window shown on glasses display ${d.displayId}: $displayInfo, requested mode " + (mode?.let { dev.ultraxr.glasses.DisplayDiagnostics.mode(it) } ?: "none (no 3840x1080 mode offered)") + ", " + (if (wide) "two-eye" else "single-view") + " rendering, display state " + dev.ultraxr.glasses.DisplayDiagnostics.stateName(d.state))
        } catch (e: Exception) {
            Tracking.noteGraphics("could not open a window on the glasses: $e")
            showPreview()
            // a game that was just left may still hold the glasses for a moment: try again
            if (++tries <= 12) ui.postDelayed({ refresh() }, 700)
        }
        onOutputChanged?.invoke()
    }

    private fun showPreview() {
        val host = previewHost ?: return
        if (preview != null) return
        val r = XrRenderer(scene, stereo = false)
        val v = GLSurfaceView(activity).apply { setEGLContextClientVersion(3); setRenderer(r); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY }
        host.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview = v; renderer = r
    }

    private fun removePreview() {
        val v = preview ?: return
        v.onPause(); previewHost?.removeView(v); preview = null
        if (presentation == null) renderer = null
    }

    private fun dismiss() {
        try { presentation?.dismiss() } catch (_: Exception) {}
        presentation = null
    }

    private class StagePresentation(context: Context, display: Display, val modeId: Int, private val hz: Float, private val r: XrRenderer) : Presentation(context, display) {
        private var view: GLSurfaceView? = null
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (modeId != 0) w.attributes = w.attributes.apply { preferredDisplayModeId = modeId }
            }
            val v = GLSurfaceView(context).apply { setEGLContextClientVersion(3); setRenderer(r); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY }
            if (hz > 0 && Build.VERSION.SDK_INT >= 30) v.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { try { h.surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) } catch (_: Exception) {} }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
            view = v
            setContentView(v)
        }
        override fun onStop() { view?.onPause(); super.onStop() }
    }
}
