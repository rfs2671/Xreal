package dev.ultraxr.xr

import dev.ultraxr.core.Q
import dev.ultraxr.openxr.UltraXrFeed
import kotlin.math.cos
import kotlin.math.sin

/**
 * A made-up wearer for test builds on an emulator, where there are no glasses: a head that turns
 * slowly from side to side and sways a little, and two open hands in front of it that pinch in
 * turn. It feeds the runtime through the same calls as real tracking, so an application (or a
 * WebXR page) sees a moving head pose, hand joints and a trigger that goes on and off.
 *
 * Everything is a known function of time, so a test can check what the application reports
 * against what was fed. It says nothing about real tracking quality.
 */
object SimWearer {
    const val YAW_AMPLITUDE = 0.25          // radians, about 14 degrees each way
    const val YAW_PERIOD_S = 8.0
    const val SWAY_M = 0.05
    const val PINCH_PERIOD_S = 4.0
    /** Each hand pinches for this part of its period: 0.24 s, a tap. A pinch held for a second is a long press. */
    const val PINCH_FRACTION = 0.06
    private val joints = FloatArray(UltraXrFeed.HAND_FLOATS)
    private var startNs = 0L
    private var motionCheckedNs = 0L
    private var moving = false
    private var motionStartNs = 0L

    /**
     * Whether the head moves. Off until the test sets the system property debug.ultraxr.sim_motion to 1
     * (adb shell setprop): a pointer that drifts during a pinch is a drag, not a click, so the head and hands
     * hold still while the test clicks its way into a session, and move once it is in.
     */
    private fun motionWanted(nowNs: Long): Boolean {
        if (nowNs - motionCheckedNs > 300_000_000L) {
            motionCheckedNs = nowNs
            val on = prop("sim_motion", "0") == "1"
            if (on && !moving) motionStartNs = nowNs
            moving = on
            // Other controls a test can set with "adb shell setprop debug.ultraxr.<name> <value>":
            //   sim_look "yaw,pitch"   degrees the head is turned left and up (added to the motion)
            //   sim_aim  "yaw,pitch"   degrees the right hand points left and up of straight ahead
            //   sim_auto 0             stops the hands pinching by themselves
            //   sim_tap  <number>      each change makes the right hand pinch once, briefly
            lookYaw = pair("sim_look", 0); lookPitch = pair("sim_look", 1)
            aimYaw = pair("sim_aim", 0); aimPitch = pair("sim_aim", 1)
            autoPinch = prop("sim_auto", "1") != "0"
            val tap = prop("sim_tap", "")
            if (tap != lastTap) { if (lastTap != null && tap.isNotEmpty()) tapUntilNs = nowNs + 220_000_000L; lastTap = tap }
        }
        return moving
    }
    private var lookYaw = 0.0; private var lookPitch = 0.0; private var aimYaw = 0.0; private var aimPitch = 0.0
    private var autoPinch = true; private var lastTap: String? = null; private var tapUntilNs = 0L
    private fun prop(name: String, default: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java).invoke(null, "debug.ultraxr.$name", default) as String
    } catch (_: Throwable) { default }
    private fun pair(name: String, index: Int): Double = Math.toRadians(prop(name, "").split(",").getOrNull(index)?.trim()?.toDoubleOrNull() ?: 0.0)

    fun yawAt(t: Double) = YAW_AMPLITUDE * sin(2 * Math.PI * t / YAW_PERIOD_S)
    fun pinching(side: Int, t: Double): Boolean { val p = ((t + side * PINCH_PERIOD_S / 2) % PINCH_PERIOD_S) / PINCH_PERIOD_S; return p < PINCH_FRACTION }

    /** Feeds one head pose and both hands for [nowNs] (System.nanoTime()). */
    fun feed(nowNs: Long, withHands: Boolean, controllers: Boolean = true) {
        if (startNs == 0L) startNs = nowNs
        val t = (nowNs - startNs) * 1e-9
        val w = 2 * Math.PI / YAW_PERIOD_S
        // Motion starts from rest at the moment it is switched on, so there is no jump.
        val go = motionWanted(nowNs); val m = if (go) (nowNs - motionStartNs) * 1e-9 else 0.0; val k = if (go) 1.0 else 0.0
        val yaw = yawAt(m); val yawRate = k * YAW_AMPLITUDE * w * cos(w * m)
        val q = Q.mul(Q.axisAngle(0.0, 1.0, 0.0, yaw + lookYaw), Q.axisAngle(1.0, 0.0, 0.0, lookPitch))
        val px = SWAY_M * sin(w * m); val vx = k * SWAY_M * w * cos(w * m)
        val py = 0.02 * sin(2 * w * m); val vy = k * 0.02 * 2 * w * cos(2 * w * m)
        UltraXrFeed.head(nowNs, px.toFloat(), py.toFloat(), 0f, q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat(),
            0f, yawRate.toFloat(), 0f, vx.toFloat(), vy.toFloat(), 0f, true)
        if (!withHands) return
        val head = doubleArrayOf(px, py, 0.0)
        for (side in 0..1) {
            val pinch = (autoPinch && pinching(side, t)) || (side == 1 && nowNs < tapUntilNs)
            // The hand's own direction: the right hand can be pointed by a test, the left points straight ahead.
            val hq = if (side == 1) Q.mul(q, Q.mul(Q.axisAngle(0.0, 1.0, 0.0, aimYaw), Q.axisAngle(1.0, 0.0, 0.0, aimPitch))) else q
            val sx = if (side == 0) -1.0 else 1.0
            // In the head's frame: x right, y up, -z forward. The wrist is below and in front of the eyes.
            fun put(j: Int, x: Double, y: Double, z: Double, r: Float) {
                val p = Q.rotate(q, doubleArrayOf(x, y, z))
                UltraXrFeed.putJoint(joints, j, (head[0] + p[0]).toFloat(), (head[1] + p[1]).toFloat(), (head[2] + p[2]).toFloat(),
                    hq[0].toFloat(), hq[1].toFloat(), hq[2].toFloat(), hq[3].toFloat(), r)   // joints carry the hand's own direction
            }
            val wx = sx * 0.16; val wy = -0.22; val wz = -0.38
            put(UltraXrFeed.Joint.WRIST, wx, wy, wz, 0.02f)
            put(UltraXrFeed.Joint.PALM, wx, wy + 0.005, wz - 0.05, 0.02f)
            // Pinch point: where the thumb and index tips meet.
            val pinchX = wx - sx * 0.02; val pinchY = wy + 0.03; val pinchZ = wz - 0.12
            // Thumb: four joints from the wrist toward the inside of the hand.
            val thumb = arrayOf(doubleArrayOf(wx - sx * 0.02, wy, wz - 0.02), doubleArrayOf(wx - sx * 0.04, wy + 0.01, wz - 0.05),
                doubleArrayOf(wx - sx * 0.05, wy + 0.02, wz - 0.08), doubleArrayOf(wx - sx * 0.055, wy + 0.025, wz - 0.10))
            for (i in 0..3) {
                val p = thumb[i]
                if (i == 3 && pinch) put(UltraXrFeed.Joint.THUMB_METACARPAL + i, pinchX, pinchY, pinchZ, 0.008f)
                else put(UltraXrFeed.Joint.THUMB_METACARPAL + i, p[0], p[1], p[2], 0.008f)
            }
            // Four fingers of five joints each, side by side, pointing forward.
            for (f in 0..3) {
                val fx = wx + sx * (-0.025 + f * 0.018)
                val along = doubleArrayOf(0.02, 0.09, 0.125, 0.15, 0.17)
                for (i in 0..4) {
                    val j = UltraXrFeed.Joint.INDEX_METACARPAL + f * 5 + i
                    if (f == 0 && i == 4 && pinch) put(j, pinchX + sx * 0.004, pinchY, pinchZ, 0.007f)
                    else put(j, fx, wy + 0.01, wz - along[i], 0.007f)
                }
            }
            UltraXrFeed.hand(side, nowNs, joints, true)
            if (!controllers) continue
            // The same hand as a Touch-shaped controller: grip at the palm, trigger from the pinch.
            val g = Q.rotate(q, doubleArrayOf(wx, wy + 0.005, wz - 0.05))
            UltraXrFeed.controller(side, nowNs, (head[0] + g[0]).toFloat(), (head[1] + g[1]).toFloat(), (head[2] + g[2]).toFloat(),
                hq[0].toFloat(), hq[1].toFloat(), hq[2].toFloat(), hq[3].toFloat(),
                buttons = if (pinch) UltraXrFeed.Button.TRIGGER_TOUCH else 0, trigger = if (pinch) 1f else 0f)
        }
    }
}
