package dev.ultraxr.core

import android.content.Context
import android.content.SharedPreferences

/** User settings, persisted. Read from any thread. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * Ask the glasses for their side-by-side 3D mode. Off (the default) leaves them in plain 2D
     * 1920x1080@60, the one mode every phone that drives them at all can show.
     */
    var use3d: Boolean get() = p.getBoolean("use3d_v2", false); set(v) = p.edit().putBoolean("use3d_v2", v).apply()
    /** The user's last answer on the display test screen: "seen", "black" or "". */
    var lastDisplayAnswer: String get() = p.getString("displayAnswer", "") ?: ""; set(v) = p.edit().putString("displayAnswer", v).apply()
    var refresh90: Boolean get() = p.getBoolean("refresh90", false); set(v) = p.edit().putBoolean("refresh90", v).apply()
    var handTracking: Boolean get() = p.getBoolean("hands", true); set(v) = p.edit().putBoolean("hands", v).apply()
    var showHands: Boolean get() = p.getBoolean("showHands", true); set(v) = p.edit().putBoolean("showHands", v).apply()
    var ipdMm: Float get() = p.getFloat("ipd", 64f); set(v) = p.edit().putFloat("ipd", v).apply()
    /** Multiplies the display field of view; tune until virtual objects stay fixed against the room. */
    var fovScale: Float get() = p.getFloat("fovScale", 1f); set(v) = p.edit().putFloat("fovScale", v).apply()
    var standingHeightM: Float get() = p.getFloat("height", 1.6f); set(v) = p.edit().putFloat("height", v).apply()
    /** How hands reach OpenXR applications: "auto" (by application), "hands" (joints only) or "controllers" (joints and Touch-shaped controllers). */
    /**
     * Whether to try the glasses' half-width 3D mode. Off by default: on the Pixel 10 the glasses fall back to 2D
     * two seconds after the switch and then show no picture at all until they are plugged in again (17 Nov 2026).
     */
    var tryHalf3d: Boolean get() = p.getBoolean("tryHalf3d", false); set(v) = p.edit().putBoolean("tryHalf3d", v).apply()
    /**
     * Press a game's own start button for the wearer after the page has loaded, so a game opens straight into its
     * session. Off until the owner switches it on: the first time the browser runs it shows its terms of service
     * where that button will be, and those are the owner's to accept, not the app's.
     */
    var autoEnter: Boolean get() = p.getBoolean("autoEnter", false); set(v) = p.edit().putBoolean("autoEnter", v).apply()
    /**
     * Frame pacing of the XR compositor by the present times the display reports, instead of by its own clock.
     * On the emulator this took the compositor from 30 to 59 frames a second; on the Pixel 10 it was worse than the
     * default (65 % of frames on time against 90 %), so it stays off.
     */
    var paceByDisplay: Boolean get() = p.getBoolean("paceByDisplay", false); set(v) = p.edit().putBoolean("paceByDisplay", v).apply()
    var xrInput: String get() = p.getString("xrInput", "auto") ?: "auto"; set(v) = p.edit().putString("xrInput", v).apply()
    var batterySaver: Boolean get() = p.getBoolean("saver", false); set(v) = p.edit().putBoolean("saver", v).apply()
    var firstRunDone: Boolean get() = p.getBoolean("firstRun", false); set(v) = p.edit().putBoolean("firstRun", v).apply()
    var bestScore: Int get() = p.getInt("best", 0); set(v) = p.edit().putInt("best", v).apply()
    var lastCheckSummary: String get() = p.getString("lastCheck", "") ?: ""; set(v) = p.edit().putString("lastCheck", v).apply()
}
