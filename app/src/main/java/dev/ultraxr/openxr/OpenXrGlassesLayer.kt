// Copyright 2026, Ultra XR contributors. Modelled on Pocket XR's OpenXrGlassesLayer.kt (GPL-3.0).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.openxr

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import org.freedesktop.monado.ipc.MonadoImpl

/**
 * The application's picture on the glasses: a sub-window of the app's own window on the glasses' display, holding
 * the surface Monado's compositor presents to. Monado composes both eyes side by side into it (the left eye in the
 * left half), so on a 3840 x 1080 side by side panel it must cover the whole display.
 *
 * A sub-window, as in Pocket XR: Android refuses a Presentation on a display where the app's activity already is,
 * and a TYPE_APPLICATION_OVERLAY window would need SYSTEM_ALERT_WINDOW, which this app must not hold (see
 * [OpenXrRuntimeService]). The sub-window takes no focus and no touches.
 */
object OpenXrGlassesLayer {
    private const val TAG = "UltraXrOpenXr"

    /** What the app provides. Set it once, from Application.onCreate or the glasses activity. */
    interface Host {
        /** The window of the app's activity (or Presentation) on the glasses' display now; null when there is none. */
        fun glassesWindow(): Window?

        /** The display that window is on, known before the window's first layout; null when there is none. */
        fun glassesDisplayOfWindow(): Display?

        /**
         * Just before Monado starts and makes its devices: call [UltraXrFeed.configureDisplay] and
         * [UltraXrFeed.configureEyes] here. [Defaults.configure] does it from a Display.
         */
        fun configureRuntime()

        /**
         * An OpenXR application connected. Start feeding the head, hands and controllers ([UltraXrFeed]). With
         * [onGlasses] true it also owns the glasses now: stop drawing the app's own scene under it, feed the vblank,
         * keep the phone awake. With false there was no window on the glasses, and the application shows Monado's
         * side by side picture in its own activity on the phone. Main thread.
         */
        fun onApplicationConnected(onGlasses: Boolean)

        /** The application left (or the runtime stopped): undo [onApplicationConnected]. Main thread. */
        fun onApplicationDisconnected()
    }

    @Volatile var host: Host? = null

    private val main = Handler(Looper.getMainLooper())
    private var layer: View? = null
    private var layerManager: WindowManager? = null
    private var connected = false
    private var generation = 0

    /** Called from Monado's binder thread as well as the main thread. */
    fun canShow(): Boolean {
        val window = host?.glassesWindow() ?: return false
        // decorView.display is null until the first traversal; the client may ask before that.
        val display = window.decorView.display ?: host?.glassesDisplayOfWindow() ?: return false
        return display.displayId != Display.DEFAULT_DISPLAY
    }

    fun open(monado: MonadoImpl) = open(monado, 0, generation)

    private fun open(monado: MonadoImpl, attempt: Int, gen: Int) {
        main.postDelayed({
            // close() bumps the generation: an application that left during the retries gets no layer.
            if (gen != generation || layer != null || connected) return@postDelayed
            val window = host?.glassesWindow()
            val token = window?.decorView?.windowToken
            // A window that was only just shown has no token until its first layout pass. Monado waits
            // up to 2 s for the surface, so retry for most of that before giving up on the glasses.
            if (window != null && token == null && attempt < 25) { open(monado, attempt + 1, gen); return@postDelayed }
            if (window == null || token == null || !canShow()) {
                // The application then shows Monado's side by side picture in its own activity, on the phone.
                Log.i(TAG, "no window on the glasses: the application draws on its own screen")
                connected(false)
                return@postDelayed
            }
            val surface = SurfaceView(window.context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.OPAQUE)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        Log.i(TAG, "the application's surface is ready")
                        // Monado's compositor waits at most 2 s for this after it starts (100 polls of 20 ms).
                        monado.passAppSurface(h.surface)
                    }

                    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                        Log.i(TAG, "the application's surface is ${width}x$height")
                    }

                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        Log.i(TAG, "the application's surface is gone")
                    }
                })
            }
            val root = FrameLayout(window.context).apply {
                addView(surface, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                this.token = token
                title = "Ultra XR OpenXR application"
            }
            val manager = window.context.getSystemService(WindowManager::class.java)
            val shown = runCatching { manager.addView(root, params) }
            if (shown.isFailure) {
                Log.w(TAG, "no window for the application: ${shown.exceptionOrNull()?.message}")
                connected(false)
                return@postDelayed
            }
            layer = root
            layerManager = manager
            connected(true)
            Log.i(TAG, "an OpenXR application owns the glasses")
        }, if (attempt == 0) 0L else 60L)
    }

    private fun connected(onGlasses: Boolean) {
        if (connected) return
        connected = true
        host?.onApplicationConnected(onGlasses)
    }

    fun close(why: String) {
        main.post {
            generation++
            layer?.let { view -> runCatching { layerManager?.removeView(view) } }
            layer = null
            layerManager = null
            if (connected) host?.onApplicationDisconnected()
            connected = false
            // The pacer goes back to its own clock, whatever the host did.
            runCatching { UltraXrFeed.vblank(0L, 0L) }
            Log.i(TAG, "the glasses are back with the app: $why")
        }
    }

    /** Starting values for an XREAL Air 2 Ultra; measure and replace them. */
    object Defaults {
        /**
         * One eye's field of view as half-angle tangents, from the published 52 degree diagonal of a 16:9 picture:
         * tan(26 degrees) = 0.4877 along the diagonal, so 0.4251 across (46.1 degrees) and 0.2391 up (26.9 degrees).
         */
        const val TAN_HALF_FOV_X = 0.4251f
        const val TAN_HALF_FOV_Y = 0.2391f

        /** The distance of the glasses' virtual screen in 3D mode, metres (Pocket XR uses 4 m for XREAL glasses). */
        const val VIRTUAL_SCREEN_DISTANCE_M = 4.0f

        /** Configures the runtime from the glasses' display: its current mode's size and refresh rate. */
        @Suppress("DEPRECATION")
        fun configure(display: Display, ipdM: Float = 0.063f) {
            val mode = display.mode
            val sideBySide = mode.physicalWidth >= mode.physicalHeight * 3
            UltraXrFeed.configureDisplay(mode.physicalWidth, mode.physicalHeight, mode.refreshRate, TAN_HALF_FOV_X, TAN_HALF_FOV_Y)
            // A mirrored 1920 x 1080 mode shows one picture to both eyes: the driver still splits it into two halves,
            // so only the side by side mode is right for OpenXR. No convergence correction without it.
            if (sideBySide) UltraXrFeed.configureEyes(ipdM, VIRTUAL_SCREEN_DISTANCE_M) else UltraXrFeed.configureEyes(0f, 0f)
        }
    }
}
