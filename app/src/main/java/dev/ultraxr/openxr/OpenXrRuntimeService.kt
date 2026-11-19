// Copyright 2026, Ultra XR contributors. Modelled on Pocket XR's OpenXrRuntimeService.kt (GPL-3.0).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.openxr

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.freedesktop.monado.ipc.IMonado
import org.freedesktop.monado.ipc.MonadoImpl

/**
 * Ultra XR as an OpenXR runtime, built on Monado. An OpenXR application's loader asks the runtime broker
 * ([OpenXrBrokerProvider], or the Khronos broker app) which runtime is active, loads libopenxr_monado.so from this
 * app into the application, and that library binds this service with Monado's connect action. Monado's server then
 * runs inside this app's process (libmonado-service.so), where the tracking code feeds it through [UltraXrFeed].
 *
 * The other intent filter, org.khronos.openxr.OpenXRRuntimeService, only marks the package as a runtime for brokers
 * and for the application's package visibility: nothing binds with it, and it gets no binder.
 */
class OpenXrRuntimeService : Service() {
    private var runtime: RuntimeBinder? = null

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != CONNECT_ACTION) return null
        val b = runtime ?: RuntimeBinder(this).also { runtime = it }
        if (b.ensure("an application bound") == RuntimeBinder.FAILED) {
            // Undo what configureRuntime() took (tracking hold, shell window).
            OpenXrGlassesLayer.host?.onApplicationDisconnected()
            runtime = null
            return null
        }
        OpenXrGlassesLayer.open(b)
        return b
    }

    /** Android calls onBind once and keeps its binder: later applications arrive here, after [onUnbind] returned true. */
    override fun onRebind(intent: Intent) {
        runtime?.let { if (it.ensure("an application bound again") != RuntimeBinder.FAILED) OpenXrGlassesLayer.open(it) }
    }

    override fun onUnbind(intent: Intent): Boolean {
        OpenXrGlassesLayer.close("the application left")
        return true
    }

    override fun onDestroy() {
        OpenXrGlassesLayer.close("the runtime stopped")
        // Off the main thread: after a lost surface Monado's shutdown can wait a long time (Pocket XR saw an ANR).
        runtime?.let { r -> Thread({ r.shutdown() }, "openxr-shutdown").apply { isDaemon = true }.start() }
        runtime = null
        super.onDestroy()
    }

    /**
     * The binder applications get. Android hands the same binder to every application for as long as the service
     * lives, and Monado's server behind it makes its devices once, from the display there is when it starts. So the
     * binder stays and the server behind it can be made again: when an application arrives and the glasses' display
     * is not what the server was made for (seen on 19 November 2026: an application whose old connection Android
     * restored while the glasses were unplugged had the server made for "no glasses"; with the glasses back in, the
     * next game got no glasses window, no head, no room and no hands, and nothing said so).
     *
     * The application's side (org.freedesktop.monado.ipc.Client, loaded from this APK into the application) asks
     * canDrawOverOtherApps: yes means "the runtime has its own surface, do not make one in your activity". We say
     * yes whenever the glasses layer can show, and hand Monado that layer's surface.
     *
     * This app must NOT hold the "display over other apps" permission (SYSTEM_ALERT_WINDOW): with it Monado's
     * compositor makes its own overlay window on display 0, the phone, and never looks at the surface passed here
     * (comp_window_android.c, comp_window_android_init_swapchain).
     */
    private class RuntimeBinder(private val context: Context) : IMonado.Stub() {
        private val packages = context.packageManager
        private var server: MonadoImpl? = null
        private var madeFor = ""
        private var madeAtNs = 0L

        /** Makes the server if there is none, or again if the display changed since. Returns [SAME], [MADE] or [FAILED]. */
        @Synchronized fun ensure(why: String): Int {
            val host = OpenXrGlassesLayer.host
            val old = server
            if (old != null) {
                val now = host?.displaySignature() ?: "unknown"
                // The mode may still be settling for a moment after the server was made (the glasses switching to 3D).
                if (now == madeFor || System.nanoTime() - madeAtNs < 5_000_000_000L) return SAME
                Log.w(TAG, "the runtime was made for display '$madeFor' and the display is now '$now': making it again ($why)")
                OpenXrGlassesLayer.closeNow("the display changed since the runtime was made")
                val t = Thread({ runCatching { old.shutdown() } }, "openxr-shutdown").apply { isDaemon = true; start() }
                try { t.join(3000) } catch (_: InterruptedException) {}
                server = null
            }
            return runCatching {
                // Before the server starts: it makes its devices once, from what is configured now.
                host?.configureRuntime()
                MonadoImpl(context)
            }.map { server = it; madeFor = host?.displaySignature() ?: "unknown"; madeAtNs = System.nanoTime(); MADE }
                .getOrElse { Log.w(TAG, "the OpenXR runtime did not start: ${it.message}"); FAILED }
        }

        /** An application's call arrived: the server it gets is one made for the display there is now. */
        private fun current(why: String): MonadoImpl? {
            val had = synchronized(this) { server != null }
            if (ensure(why) == MADE && had) OpenXrGlassesLayer.open(this)
            return synchronized(this) { server }
        }

        override fun connect(fd: android.os.ParcelFileDescriptor) { current("an application connected")?.connect(fd) }

        override fun passAppSurface(surface: android.view.Surface) { synchronized(this) { server }?.passAppSurface(surface) }

        override fun canDrawOverOtherApps(): Boolean {
            // The first thing an application asks over Binder: note who it is, so the host can feed it suitably.
            // A name that cannot be resolved (seen on the Pixel: the session was then treated as an unknown application,
            // given controllers as well as hands, and the browser aimed by the controller instead of by the hand) must
            // not wipe out the one the launcher set when it started the application.
            val name = runCatching { packages.getNameForUid(android.os.Binder.getCallingUid()) }.getOrNull()
            if (!name.isNullOrEmpty()) OpenXrGlassesLayer.clientPackage = name
            current("an application asked where to draw")
            return OpenXrGlassesLayer.canShow()
        }

        fun shutdown() { val s = synchronized(this) { server.also { server = null } }; runCatching { s?.shutdown() } }

        companion object { const val FAILED = 0; const val SAME = 1; const val MADE = 2 }
    }

    private companion object {
        const val TAG = "UltraXrOpenXr"
        const val CONNECT_ACTION = "org.freedesktop.monado.ipc.CONNECT"
    }
}
