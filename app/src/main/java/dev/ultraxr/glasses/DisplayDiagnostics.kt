package dev.ultraxr.glasses

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import dev.ultraxr.core.Fmt

/** Everything Android will tell an app about its displays, as text for the hardware report. */
object DisplayDiagnostics {
    private fun manager(c: Context) = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun external(c: Context): Display? {
        val dm = manager(c)
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull() ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    }

    /** True when an external display is currently in a mode at least three times wider than tall. */
    fun sideBySidePresent(c: Context): Boolean = external(c)?.mode?.let { it.physicalWidth >= it.physicalHeight * 3 } ?: false

    /** True when an external display offers such a mode, whether or not it is the current one. */
    fun sideBySideOffered(c: Context): Boolean = external(c)?.supportedModes?.any { it.physicalWidth >= it.physicalHeight * 3 } ?: false

    fun stateName(s: Int): String = when (s) {
        Display.STATE_OFF -> "OFF"; Display.STATE_ON -> "ON"; Display.STATE_DOZE -> "DOZE"; Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"; Display.STATE_VR -> "VR"; Display.STATE_UNKNOWN -> "UNKNOWN"; else -> "state $s"
    }

    private fun flagNames(f: Int): String {
        val names = ArrayList<String>()
        if (f and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) names += "PROTECTED_BUFFERS"
        if (f and Display.FLAG_SECURE != 0) names += "SECURE"
        if (f and Display.FLAG_PRIVATE != 0) names += "PRIVATE"
        if (f and Display.FLAG_PRESENTATION != 0) names += "PRESENTATION"
        if (f and Display.FLAG_ROUND != 0) names += "ROUND"
        return "0x" + Integer.toHexString(f) + names.joinToString(",", " [", "]")
    }

    fun mode(m: Display.Mode): String = "${m.physicalWidth}x${m.physicalHeight}@" + Fmt.f(m.refreshRate, 2) + " (id ${m.modeId})"

    fun describe(d: Display, presentation: Set<Int>): String {
        val sb = StringBuilder()
        sb.append("display ").append(d.displayId).append(" '").append(d.name).append("'")
        sb.append(if (d.displayId == Display.DEFAULT_DISPLAY) " (phone)" else " (external)").append('\n')
        sb.append("  state=").append(stateName(d.state)).append(" valid=").append(d.isValid).append(" flags=").append(flagNames(d.flags))
        sb.append(" presentationCategory=").append(d.displayId in presentation).append('\n')
        sb.append("  current mode ").append(mode(d.mode)).append(", refresh ").append(Fmt.f(d.refreshRate, 2)).append(", rotation ").append(d.rotation * 90).append('\n')
        sb.append("  modes offered: ").append(d.supportedModes.sortedWith(compareBy({ -it.physicalWidth }, { -it.refreshRate })).joinToString("; ") { mode(it) }).append('\n')
        sb.append("  side-by-side (3840x1080) offered: ").append(d.supportedModes.any { it.physicalWidth >= it.physicalHeight * 3 }).append('\n')
        try {
            @Suppress("DEPRECATION") val hdr = d.hdrCapabilities
            if (hdr != null) sb.append("  hdr types=").append(hdr.supportedHdrTypes.joinToString(",")).append(" maxLuminance=").append(Fmt.f(hdr.desiredMaxLuminance, 0)).append('\n')
            if (Build.VERSION.SDK_INT >= 31) sb.append("  product=").append(d.deviceProductInfo?.let { "${it.manufacturerPnpId} '${it.name}' model ${it.productId} year ${it.manufactureYear}" } ?: "unknown").append('\n')
        } catch (t: Throwable) { sb.append("  (extra info unavailable: ").append(t).append(")\n") }
        // Android's own dump of the display: carries type, owner and state details not exposed otherwise.
        sb.append("  raw: ").append(d.toString()).append('\n')
        return sb.toString()
    }

    /**
     * Read-only HDCP view from Android's DRM service: the level the connected displays negotiated and the
     * maximum supported. This is the closest app-visible hint of whether the glasses advertise HDCP; it is
     * system-wide, not per display, and not proof of what the bridge firmware does.
     */
    fun hdcpLevels(): String {
        val widevine = java.util.UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
        return try {
            val drm = android.media.MediaDrm(widevine)
            try {
                val connected = if (Build.VERSION.SDK_INT >= 28) drm.connectedHdcpLevel else -1
                val max = if (Build.VERSION.SDK_INT >= 28) drm.maxHdcpLevel else -1
                fun nm(v: Int) = when (v) { 1 -> "HDCP_NONE"; 2 -> "HDCP_V1"; 3 -> "HDCP_V2"; 4 -> "HDCP_V2_1"; 5 -> "HDCP_V2_2"; 6 -> "HDCP_V2_3"; 0x7FFFFFFF -> "HDCP_NO_DIGITAL_OUTPUT"; 0 -> "UNKNOWN"; else -> "level $v" }
                "DRM HDCP: connected=" + nm(connected) + ", max=" + nm(max) + " (system-wide)\n"
            } finally { if (Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release() }
        } catch (t: Throwable) { "DRM HDCP: unavailable ($t)\n" }
    }

    fun describeAll(c: Context): String {
        val dm = manager(c)
        val sb = StringBuilder()
        val pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        val all = dm.displays
        sb.append(all.size).append(" display(s); external present: ").append(all.any { it.displayId != Display.DEFAULT_DISPLAY }).append('\n')
        sb.append(hdcpLevels())
        for (d in all) try { sb.append(describe(d, pres)) } catch (t: Throwable) { sb.append("display ").append(d.displayId).append(": could not be described: ").append(t).append('\n') }
        // Whether the phone extends or mirrors is not public API; these developer settings are the nearest readable hints.
        for (key in listOf("force_desktop_mode_on_external_displays", "enable_freeform_support", "force_resizable_activities")) {
            val v = try { Settings.Global.getString(c.contentResolver, key) } catch (t: Throwable) { "unreadable ($t)" }
            sb.append("setting ").append(key).append('=').append(v ?: "unset").append('\n')
        }
        return sb.toString()
    }
}
