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

    /** What a Meta Quest game's main activity is marked with (a converted Quest game keeps it). */
    const val CATEGORY_QUEST = "com.oculus.intent.category.VR"

    /** The immersive apps on this phone: OpenXR's own category, and Quest games. One entry a package. */
    fun installedApps(c: Context): List<ResolveInfo> {
        val found = ArrayList<ResolveInfo>()
        for (cat in arrayOf(CATEGORY_IMMERSIVE, CATEGORY_QUEST))
            try { found.addAll(c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) } catch (_: Exception) {}
        return found.filter { it.activityInfo != null && it.activityInfo.packageName != c.packageName }.distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(c.packageManager).toString().lowercase() }
    }

    /** The immersive activity of [pkg], or null when that package is an ordinary app. */
    fun nativeApp(c: Context, pkg: String): ResolveInfo? = installedApps(c).firstOrNull { it.activityInfo.packageName == pkg }

    /** The WebXR browser, if one that uses this runtime is installed (Wolvic's generic OpenXR builds). */
    fun browser(c: Context): ResolveInfo? = installedApps(c).firstOrNull { it.activityInfo.packageName.startsWith("com.igalia.wolvic") }

    /** Opens a web page in the WebXR browser with tracking kept alive behind it. */
    fun launchUrl(c: Context, browser: ResolveInfo, url: String, wantStereo: Boolean = true) {
        Tracking.stereoWant("game", wantStereo)
        TrackingService.start(c)
        XrHost.openShell()
        // The launcher knows what it is starting: say so now, in case the application's name cannot be read later.
        dev.ultraxr.openxr.OpenXrGlassesLayer.clientPackage = browser.activityInfo.packageName
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setClassName(browser.activityInfo.packageName, browser.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(i) } catch (e: Exception) { Tracking.log("could not open $url in ${browser.activityInfo.packageName}: $e") }
    }

    fun statusLine(c: Context): String = (if (available) "Runtime packaged and loaded" else "Runtime unavailable") + ": $detail; ${installedApps(c).size} OpenXR app(s) installed; ${XrHost.lastEvent}"

    /** Starts an OpenXR app with tracking kept alive behind it. */
    fun launch(c: Context, info: ResolveInfo, wantStereo: Boolean = true) {
        // 3D is kept until the application leaves (XrHost) or the launcher takes over again. After a failed
        // switch it is not asked for again behind a running application: the glasses stay in 2D.
        Tracking.stereoWant("game", wantStereo)
        TrackingService.start(c)
        XrHost.openShell()
        dev.ultraxr.openxr.OpenXrGlassesLayer.clientPackage = info.activityInfo.packageName
        // On the phone's own screen: the game's window is only where Android keeps it; its picture goes to the glasses
        // through the runtime. (From the home view, which is on the glasses, it would otherwise open there.)
        val i = Intent(Intent.ACTION_MAIN).setClassName(info.activityInfo.packageName, info.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val onPhone = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()
        // An instance of the game still running from an earlier start (the glasses were not there then, or went away) keeps the OpenXR
        // session it made: the game would resume drawing to the phone and nothing would come on the glasses. It is ended first.
        // (Only a process in the background can be ended this way: the game, behind the desktop or the launcher, is.)
        try { (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(info.activityInfo.packageName) } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { c.startActivity(i, onPhone); Tracking.log("OpenXR: started ${info.activityInfo.packageName} (native)") } catch (e: Exception) { Tracking.log("could not start ${info.activityInfo.packageName}: $e") }
        }, 400)
    }
}
