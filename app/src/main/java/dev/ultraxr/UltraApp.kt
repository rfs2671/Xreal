package dev.ultraxr

import android.app.Application
import dev.ultraxr.openxr.OpenXrGlassesLayer
import dev.ultraxr.xr.XrHost

class UltraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The runtime service can be bound into a freshly started process, so the host is set here.
        XrHost.init(this)
        OpenXrGlassesLayer.host = XrHost
        // Pages on this phone read the room (floor, table tops, walls) from here while a game runs.
        dev.ultraxr.xr.LocalSite.start(this)
    }
}
