package dev.ultraxr.xr

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.tracking.TrackingService

/** The embedded OpenXR runtime as the launcher sees it: which apps can use it, and starting them. */
object XrRuntime {
    const val CATEGORY_IMMERSIVE = "org.khronos.openxr.intent.category.IMMERSIVE_HMD"

    /** True when the Monado runtime libraries are packaged and loadable. */
    @Volatile var available = false
    @Volatile var detail = "not initialised"

    fun installedApps(c: Context): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_IMMERSIVE)
        return c.packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != c.packageName }
            .sortedBy { it.loadLabel(c.packageManager).toString().lowercase() }
    }

    fun statusLine(c: Context): String = (if (available) "Runtime ready" else "Runtime unavailable") + ": $detail; ${installedApps(c).size} OpenXR app(s) installed"

    /** Starts an OpenXR app with tracking kept alive behind it. */
    fun launch(c: Context, info: ResolveInfo) {
        TrackingService.start(c)
        val i = Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_IMMERSIVE).setClassName(info.activityInfo.packageName, info.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(i) } catch (e: Exception) { Tracking.log("could not start ${info.activityInfo.packageName}: $e") }
    }
}
