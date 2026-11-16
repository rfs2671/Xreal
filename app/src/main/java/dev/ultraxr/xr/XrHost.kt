package dev.ultraxr.xr

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import dev.ultraxr.core.Hand
import dev.ultraxr.core.HandState
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Q
import dev.ultraxr.openxr.OpenXrGlassesLayer
import dev.ultraxr.openxr.UltraXrFeed
import dev.ultraxr.render.XrRenderer
import dev.ultraxr.tracking.Tracking
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Connects the tracking pipeline to the embedded Monado runtime: gives Monado a window on
 * the glasses to present into, and feeds it the head pose, hand joints and gesture-driven
 * controller input while an OpenXR application is running.
 */
object XrHost : OpenXrGlassesLayer.Host {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var shell: Presentation? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var feeding = false
    private var holdsTracking = false
    @Volatile var applicationConnected = false; private set
    @Volatile var lastEvent = "no OpenXR application has connected yet"; private set

    fun init(context: Context) {
        app = context.applicationContext
        try {
            System.loadLibrary("monado-service")
            XrRuntime.available = true; XrRuntime.detail = "Monado with Pocket XR's Android driver"
        } catch (e: Throwable) { XrRuntime.available = false; XrRuntime.detail = "runtime library did not load: $e" }
    }

    private fun glassesDisplay(): Display? {
        val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull() ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    }

    /**
     * Opens a black full-screen window on the glasses, owned by the app process rather than by an
     * activity, so it stays up while the game's activity is in front on the phone. Monado's
     * compositor surface is attached to it as a sub-window. Main thread.
     */
    fun openShell(): Boolean {
        if (shell != null) return true
        val d = glassesDisplay() ?: return false
        return try {
            val sbs = d.supportedModes.filter { it.physicalWidth == 3840 && it.physicalHeight == 1080 }
            val want = if (Tracking.settings.refresh90) 90f else 60f
            val mode = sbs.minByOrNull { Math.abs(it.refreshRate - want) }
            val p = object : Presentation(app, d) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    window?.let { w ->
                        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (mode != null) w.attributes = w.attributes.apply { preferredDisplayModeId = mode.modeId }
                    }
                    setContentView(View(context).apply { setBackgroundColor(0xFF000000.toInt()) })
                }
            }
            // The system dismisses it when the display goes away (e.g. the glasses re-enumerate on a mode switch).
            p.setOnDismissListener { if (shell === p) shell = null }
            p.show()
            shell = p
            Tracking.log("OpenXR shell window open on the glasses")
            true
        } catch (e: Throwable) {
            Tracking.log("could not open the OpenXR shell window on the glasses: $e")
            false
        }
    }

    fun closeShell() {
        try { shell?.dismiss() } catch (_: Throwable) {}
        shell = null
    }

    // ------------------------------------------------------------ OpenXrGlassesLayer.Host

    override fun glassesWindow(): Window? = shell?.window

    override fun glassesDisplayOfWindow(): Display? = shell?.display

    override fun configureRuntime() {
        // Runs in Service.onBind (main thread) when an application binds the runtime, before Monado starts.
        val cold = !holdsTracking && Tracking.link?.state != dev.ultraxr.glasses.GlassesLink.State.STREAMING
        if (!holdsTracking) { Tracking.acquire(app); holdsTracking = true }
        if (cold) {
            // The game was started before the launcher: give the glasses a moment to connect and
            // switch to their 3D mode, so Monado is not configured for the 2D mode they start in.
            val until = System.nanoTime() + 2_500_000_000L
            while (System.nanoTime() < until) {
                val m = glassesDisplay()?.mode
                if (Tracking.link?.state == dev.ultraxr.glasses.GlassesLink.State.STREAMING && m != null && m.physicalWidth >= m.physicalHeight * 3) break
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) openShell()
        else {
            val latch = java.util.concurrent.CountDownLatch(1)
            main.post { openShell(); latch.countDown() }
            try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        }
        val s = Tracking.settings
        val d = shell?.display ?: glassesDisplay()
        val scale = s.fovScale
        val tanH = (Tracking.displayTanH * scale).toFloat(); val tanV = (Tracking.displayTanV * scale).toFloat()
        if (d != null) {
            val m = d.mode
            val sideBySide = m.physicalWidth >= m.physicalHeight * 3
            UltraXrFeed.configureDisplay(m.physicalWidth, m.physicalHeight, m.refreshRate, tanH, tanV)
            if (sideBySide) UltraXrFeed.configureEyes(s.ipdMm / 1000f, XrRenderer.VIRTUAL_SCREEN_M.toFloat()) else UltraXrFeed.configureEyes(0f, 0f)
            lastEvent = "runtime configured for ${m.physicalWidth}x${m.physicalHeight}@" + dev.ultraxr.core.Fmt.f(m.refreshRate, 0) + if (sideBySide) "" else " (not side-by-side)"
        } else {
            // No glasses display: the application shows Monado's side-by-side picture on the phone.
            UltraXrFeed.configureDisplay(3840, 1080, 60f, tanH, tanV)
            UltraXrFeed.configureEyes(s.ipdMm / 1000f, 0f)
            lastEvent = "runtime configured without a glasses display"
        }
        // Feed heights relative to the head's start; Monado adds the wearer's height for the floor.
        UltraXrFeed.setOriginOffset(0f, s.standingHeightM, 0f)
        UltraXrFeed.configureHands(30f, 250f)
        Tracking.log("OpenXR: $lastEvent")
    }

    override fun onApplicationConnected(onGlasses: Boolean) {
        applicationConnected = true
        lastEvent = "an OpenXR application connected" + if (onGlasses) " and owns the glasses" else " (drawing on the phone: no glasses window)"
        Tracking.log("OpenXR: $lastEvent")
        Tracking.recenter()
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wake = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ultraxr:openxr").apply { acquire(4 * 60 * 60 * 1000L) }
        } catch (_: Throwable) {}
        if (!feeding) { feeding = true; thread(name = "openxr-feed") { feedLoop() } }
    }

    override fun onApplicationDisconnected() {
        applicationConnected = false
        feeding = false
        lastEvent = "the OpenXR application left"
        Tracking.log("OpenXR: $lastEvent")
        try { wake?.release() } catch (_: Throwable) {}
        wake = null
        closeShell()
        if (holdsTracking) { Tracking.release(); holdsTracking = false }
    }

    // ------------------------------------------------------------ feed

    private fun feedLoop() {
        var lastHandNs = 0L
        var recentred = false
        val pressed = booleanArrayOf(false, false)
        val joints = FloatArray(UltraXrFeed.HAND_FLOATS)
        while (feeding) {
            try { Thread.sleep(3) } catch (_: InterruptedException) {}
            val head = Tracking.head
            val t = head.latestImuNs
            if (t == 0L) continue
            // Poses are fed at the time they are true; Monado does the look-ahead from the velocities.
            val s = head.predict(t)
            if (s.mode == HeadTracker.Mode.NONE) continue
            val p = s.pose.p; val q = s.pose.q; val w = s.angularVelocity; val v = s.velocity
            // Below ~15 deg/s the gyro rate is mostly noise and makes the picture shake when extrapolated.
            val rate = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
            val k = ((rate - 0.1) / 0.16).coerceIn(0.0, 1.0)
            UltraXrFeed.head(t, p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat(),
                (w[0] * k).toFloat(), (w[1] * k).toFloat(), (w[2] * k).toFloat(), v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), s.mode == HeadTracker.Mode.SIX_DOF)
            if (!recentred) { recentred = UltraXrFeed.recenter() }

            val hands = Tracking.handJoints
            val newest = hands.maxOfOrNull { it.timestampNs } ?: 0L
            if (newest == lastHandNs) continue
            lastHandNs = newest
            val states = Tracking.hands
            for (side in 0..1) {
                val hand = hands.firstOrNull { it.isRight == (side == 1) && it.timestampNs == newest }
                val poses = Tracking.handPoses26(side)
                if (hand == null || poses == null) { UltraXrFeed.hand(side, newest, null, false); pressed[side] = false; continue }
                for (j in 0 until UltraXrFeed.Joint.COUNT) {
                    val o = j * 7
                    UltraXrFeed.putJoint(joints, j, poses[o], poses[o + 1], poses[o + 2], poses[o + 3], poses[o + 4], poses[o + 5], poses[o + 6], if (j == 0) 0.02f else 0.008f)
                }
                UltraXrFeed.hand(side, hand.timestampNs, joints, true)
                val st = states.firstOrNull { it.isRight == (side == 1) } ?: continue
                feedController(side, hand, st, poses, pressed)
            }
        }
    }

    /** Hand gestures as a Touch controller: pinch is the trigger, a fist is the grip. */
    private fun feedController(side: Int, hand: Hand, st: HandState, poses: FloatArray, pressed: BooleanArray) {
        val thumb = hand.joint(Hand.THUMB_TIP); val index = hand.joint(Hand.INDEX_TIP)
        val gap = sqrt((thumb[0] - index[0]) * (thumb[0] - index[0]) + (thumb[1] - index[1]) * (thumb[1] - index[1]) + (thumb[2] - index[2]) * (thumb[2] - index[2])).toFloat()
        val trigger = UltraXrFeed.pinchToTrigger(gap, pressed[side]); pressed[side] = trigger >= 1f
        val squeeze = if (st.fist) 1f else 0f
        val buttons = (if (trigger > 0.1f) UltraXrFeed.Button.TRIGGER_TOUCH else 0)
        // Grip pose: the palm joint as tracked.
        UltraXrFeed.controller(side, hand.timestampNs, poses[0], poses[1], poses[2], poses[3], poses[4], poses[5], poses[6],
            velX = st.velocity[0].toFloat(), velY = st.velocity[1].toFloat(), velZ = st.velocity[2].toFloat(), buttons = buttons, trigger = trigger, squeeze = squeeze)
        // Aim pose: from the palm along the wrist-to-knuckles direction, -z forward, y up as far as possible.
        val d = st.aimDir
        val z = doubleArrayOf(-d[0], -d[1], -d[2])
        var x = cross(doubleArrayOf(0.0, 1.0, 0.0), z)
        val xn = sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2])
        x = if (xn < 1e-3) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(x[0] / xn, x[1] / xn, x[2] / xn)
        val y = cross(z, x)
        val aq = Q.fromMatrix(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])
        UltraXrFeed.controllerAim(side, hand.timestampNs, st.aimOrigin[0].toFloat(), st.aimOrigin[1].toFloat(), st.aimOrigin[2].toFloat(),
            aq[0].toFloat(), aq[1].toFloat(), aq[2].toFloat(), aq[3].toFloat())
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}
