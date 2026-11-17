// Copyright 2026, Ultra XR contributors. Modelled on Pocket XR's OpenXrRuntimeService.kt (GPL-3.0).
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.openxr

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
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
    private var monado: UltraMonado? = null

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != CONNECT_ACTION) return null
        val runtime = monado ?: runCatching {
            // Before the server starts: it makes its devices once, from what is configured now.
            OpenXrGlassesLayer.host?.configureRuntime()
            UltraMonado(this)
        }.getOrElse {
            Log.w(TAG, "the OpenXR runtime did not start: ${it.message}")
            // Undo what configureRuntime() took (tracking hold, shell window).
            OpenXrGlassesLayer.host?.onApplicationDisconnected()
            return null
        }.also { monado = it }
        OpenXrGlassesLayer.open(runtime)
        return runtime
    }

    /** Android calls onBind once and keeps its binder: later applications arrive here, after [onUnbind] returned true. */
    override fun onRebind(intent: Intent) {
        monado?.let { OpenXrGlassesLayer.open(it) }
    }

    override fun onUnbind(intent: Intent): Boolean {
        OpenXrGlassesLayer.close("the application left")
        return true
    }

    override fun onDestroy() {
        OpenXrGlassesLayer.close("the runtime stopped")
        // Off the main thread: after a lost surface Monado's shutdown can wait a long time (Pocket XR saw an ANR).
        monado?.let { runtime -> Thread({ runtime.shutdown() }, "openxr-shutdown").apply { isDaemon = true }.start() }
        monado = null
        super.onDestroy()
    }

    /**
     * Monado's binder. The application's side (org.freedesktop.monado.ipc.Client, loaded from this APK into the
     * application) asks canDrawOverOtherApps: yes means "the runtime has its own surface, do not make one in your
     * activity". We say yes whenever the glasses layer can show, and hand Monado that layer's surface.
     *
     * This app must NOT hold the "display over other apps" permission (SYSTEM_ALERT_WINDOW): with it Monado's
     * compositor makes its own overlay window on display 0, the phone, and never looks at the surface passed here
     * (comp_window_android.c, comp_window_android_init_swapchain).
     */
    private class UltraMonado(context: Context) : MonadoImpl(context) {
        private val packages = context.packageManager
        override fun canDrawOverOtherApps(): Boolean {
            // The first thing an application asks over Binder: note who it is, so the host can feed it suitably.
            OpenXrGlassesLayer.clientPackage = runCatching { packages.getNameForUid(android.os.Binder.getCallingUid()) }.getOrNull() ?: ""
            return OpenXrGlassesLayer.canShow()
        }
    }

    private companion object {
        const val TAG = "UltraXrOpenXr"
        const val CONNECT_ACTION = "org.freedesktop.monado.ipc.CONNECT"
    }
}
