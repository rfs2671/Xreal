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
    @Volatile private var journal: dev.ultraxr.core.SessionJournal? = null

    /** Appends the finished session's record to journal.jsonl in the app's external files folder (readable over adb). */
    private fun closeJournal() {
        val j = journal ?: return
        journal = null
        try {
            val line = j.toJson(System.currentTimeMillis()).toString()
            java.io.File(app.getExternalFilesDir(null), "journal.jsonl").appendText(line + 10.toChar())
            Tracking.log("session record: $line")
        } catch (e: Throwable) { Tracking.log("session record not written: $e") }
    }

    /**
     * How hands are given to the running application. A browser gets the hand joints only: it turns a pinch into
     * a click and a WebXR "select" itself, and passes the joints on to pages. With Touch-shaped controllers
     * present as well, it uses those and hides the hands from pages. Other applications get both, because games
     * written for controllers need the controllers.
     */
    val handsOnly: Boolean get() = when (Tracking.settings.xrInput) {
        "hands" -> true
        "controllers" -> false
        else -> BROWSERS.any { OpenXrGlassesLayer.clientPackage.startsWith(it) }
    }
    private val BROWSERS = listOf("com.igalia.wolvic")
    /** Whether the glasses were in a 3D mode when the runtime was configured for the running application. */
    @Volatile var stereoAtStart = false; private set
    /** The session is drawing one view per eye for the glasses' half-width 3D mode. */
    @Volatile private var halfStereoRunning = false
    private var stereoCheckNs = 0L

    /**
     * The glasses can drop out of 3D during a session (seen on the Pixel one second after it started). In 2D they
     * show the whole picture to both eyes, so two eye views side by side must stop at once: one view, no depth.
     */
    private fun dropToOneViewIfStereoLost() {
        val now = System.nanoTime()
        if (!halfStereoRunning || now - stereoCheckNs < 300_000_000L) return
        stereoCheckNs = now
        if (Tracking.halfStereoActive) return
        halfStereoRunning = false; stereoAtStart = false
        UltraXrFeed.configureSingleView(true); UltraXrFeed.configureEyes(0f, 0f)
        lastEvent = "the glasses left 3D during the session: now one picture for both eyes, no depth"
        Tracking.log("OpenXR: $lastEvent")
    }
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
        // Read by the compositor when it is created, a moment from now, in this process.
        try { android.system.Os.setenv("ULTRAXR_DISPLAY_TIMING", if (s.paceByDisplay) "1" else "0", true) } catch (_: Throwable) {}
        val d = shell?.display ?: glassesDisplay()
        val scale = s.fovScale
        val tanH = (Tracking.displayTanH * scale).toFloat(); val tanV = (Tracking.displayTanV * scale).toFloat()
        if (d != null) {
            val m = d.mode
            val sideBySide = m.physicalWidth >= m.physicalHeight * 3
            // Half-width 3D: the picture is 1920x1080 and the glasses stretch each half over one eye.
            // The runtime draws the left eye into the left half either way, so each eye's view is
            // 960x1080 with that eye's whole field of view.
            val half = !sideBySide && Tracking.halfStereoActive
            stereoAtStart = sideBySide || half
            halfStereoRunning = half
            UltraXrFeed.configureDisplay(m.physicalWidth, m.physicalHeight, m.refreshRate, tanH, tanV)
            // In 2D the glasses show the whole picture to both eyes: the runtime then draws one view over all of
            // it. The two eye pictures are never put side by side in front of both eyes.
            UltraXrFeed.configureSingleView(!stereoAtStart)
            if (stereoAtStart) UltraXrFeed.configureEyes(s.ipdMm / 1000f, XrRenderer.VIRTUAL_SCREEN_M.toFloat()) else UltraXrFeed.configureEyes(0f, 0f)
            lastEvent = "runtime configured for ${m.physicalWidth}x${m.physicalHeight}@" + dev.ultraxr.core.Fmt.f(m.refreshRate, 0) +
                (if (sideBySide) ", side-by-side 3D" else if (half) ", half-width 3D, eyes " + s.ipdMm.toInt() + " mm apart" else ", glasses in 2D: one picture for both eyes, no depth")
        } else {
            // No glasses display: the application shows Monado's side-by-side picture on the phone.
            UltraXrFeed.configureSingleView(false)
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
        lastEvent = "an OpenXR application connected (" + OpenXrGlassesLayer.clientPackage.ifEmpty { "unknown" } + (if (handsOnly) ", hands as joints only" else ", hands as joints and controllers") + ")" + if (onGlasses) " and owns the glasses" else " (drawing on the phone: no glasses window)"
        Tracking.log("OpenXR: $lastEvent")
        journal = dev.ultraxr.core.SessionJournal(OpenXrGlassesLayer.clientPackage, if (!onGlasses) "phone screen" else if (stereoAtStart) "glasses, one view per eye" else "glasses, one picture (2D)",
            if (handsOnly) "joints" else "joints and controllers", System.currentTimeMillis())
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
        closeJournal()
        Tracking.log("OpenXR: $lastEvent")
        try { wake?.release() } catch (_: Throwable) {}
        wake = null
        Tracking.stereoWant("game", false)
        closeShell()
        if (holdsTracking) { Tracking.release(); holdsTracking = false }
    }

    // ------------------------------------------------------------ feed

    /**
     * Presses a page's own start button with the touchpad pointer once the browser is up: the pointer goes to each of
     * [spots] in turn and taps. A tap after the game has started is an ordinary select in the game. Only called when
     * the owner has switched "Enter games automatically" on.
     */
    fun autoEnter(spots: List<List<Double>>, pageLoadMs: Long = 12_000) {
        if (spots.isEmpty()) return
        thread(name = "auto-enter") {
            val give = System.nanoTime() + 40_000_000_000L
            while (!applicationConnected && System.nanoTime() < give) Thread.sleep(200)
            if (!applicationConnected) return@thread
            Thread.sleep(pageLoadMs)
            // Three rounds, because how long a page takes to load is not known. A tap after the game has started is a
            // plain select in the game.
            for (round in 0..2) {
                if (round > 0) Thread.sleep(7000)
                for (s in spots) {
                    if (!applicationConnected) return@thread
                    var now = System.nanoTime()
                    dev.ultraxr.core.PointerInput.center(now); dev.ultraxr.core.PointerInput.move(now, s[0], s[1])
                    Thread.sleep(700)
                    now = System.nanoTime(); dev.ultraxr.core.PointerInput.tap(now)
                    Tracking.log("OpenXR: pressed the game's start button for the wearer at " + s[0] + ", " + s[1])
                    Thread.sleep(2000)
                }
            }
            dev.ultraxr.core.PointerInput.center(System.nanoTime())
        }
    }

    @Volatile private var recentreRequested = false
    /** Recentre the running session (the touchpad's volume-up key). */
    fun recentreNow() { recentreRequested = true }

    private fun feedLoop() {
        var lastHandNs = 0L
        var pointerFedNs = 0L; var pointerSaid = false
        var anchorP: DoubleArray? = null; var anchorQ: DoubleArray? = null
        var recentred = false
        val pressed = booleanArrayOf(false, false)
        val joints = FloatArray(UltraXrFeed.HAND_FLOATS)
        val ho = Tracking.handOptions
        val pinch = arrayOf(dev.ultraxr.core.PinchState(ho.pressM, ho.releaseM, frames = 2), dev.ultraxr.core.PinchState(ho.pressM, ho.releaseM, frames = 2))
        // The last hand given to the application, kept through a loss of a quarter of a second at most.
        val heldJoints = arrayOf(FloatArray(UltraXrFeed.HAND_FLOATS), FloatArray(UltraXrFeed.HAND_FLOATS))
        val heldSinceNs = longArrayOf(0L, 0L); val haveHeld = booleanArrayOf(false, false)
        val steady = arrayOf(dev.ultraxr.core.HandSteady(), dev.ultraxr.core.HandSteady()); val palm = DoubleArray(3); val off = DoubleArray(3)
        val recenter = dev.ultraxr.core.RecenterGesture()
        var vioTickNs = 0L
        while (feeding) {
            try { Thread.sleep(3) } catch (_: InterruptedException) {}
            dropToOneViewIfStereoLost()
            if (recentreRequested) { recentreRequested = false; Tracking.recenter(); recentred = false; journal?.recentred(); Tracking.log("OpenXR: recentred from the touchpad") }
            if (Tracking.simulateWearer) {   // test builds on an emulator: a made-up head and hands
                val now = System.nanoTime()
                val hands = now - lastHandNs > 33_000_000L
                SimWearer.feed(now, hands, controllers = !handsOnly); if (hands) lastHandNs = now
                if (!recentred) recentred = UltraXrFeed.recenter()
                continue
            }
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
            journal?.let { j ->
                j.head(s.mode == HeadTracker.Mode.SIX_DOF, p)
                if (t - vioTickNs > 1_000_000_000L) { vioTickNs = t; if (Tracking.vioLandmarks >= 0) { j.vioSamples++; j.stereoMatchesSum += Tracking.vioStereoMatches; j.landmarksSum += Tracking.vioLandmarks } }
            }

            // The touchpad pointer stands in for the right hand while it is in use, tracked hand or not.
            val nowNs = System.nanoTime()
            val pointer = dev.ultraxr.core.PointerInput.active(nowNs)
            if (!pointer) anchorP = null
            if (pointer && nowNs - pointerFedNs > 16_000_000L) {
                pointerFedNs = nowNs
                // The pointing hand goes where the head is only while the finger is moving it (and at the first use);
                // otherwise it stays put in the room, so a tap is a tap however the head sways.
                if (anchorP == null || dev.ultraxr.core.PointerInput.movedWithin(nowNs, 150_000_000L)) { anchorP = p.copyOf(); anchorQ = q.copyOf() }
                SimWearer.feedHand(1, t, anchorP!!, anchorQ!!, dev.ultraxr.core.PointerInput.offset(), 0.0, 0.0, dev.ultraxr.core.PointerInput.pressed(nowNs), controllers = false)
                if (!pointerSaid) { pointerSaid = true; Tracking.log("OpenXR: the touchpad pointer is the right hand") }
                journal?.let { j -> val c = dev.ultraxr.core.PointerInput.clicks; if (c > j.pointerSelections) j.pointerSelections = c }
            }

            val hands = Tracking.handJoints
            val newest = hands.maxOfOrNull { it.timestampNs } ?: 0L
            if (newest == lastHandNs) continue
            lastHandNs = newest
            val states = Tracking.hands
            for (side in 0..1) {
                if (side == 1 && pointer) continue
                val hand = hands.firstOrNull { it.isRight == (side == 1) && it.timestampNs == newest }
                val poses = Tracking.handPoses26(side)
                if (hand == null || poses == null) {
                    // Lost for a moment (fingers hiding each other in a pinch, the hand at the edge of the view): the
                    // application keeps the hand where it was, pinch included, for a quarter of a second.
                    if (haveHeld[side]) {
                        if (heldSinceNs[side] == 0L) heldSinceNs[side] = nowNs
                        if (nowNs - heldSinceNs[side] <= 250_000_000L) { pinch[side].lost(nowNs); UltraXrFeed.hand(side, newest, heldJoints[side], true); journal?.let { j -> j.heldFrames[side]++ }; continue }
                    }
                    haveHeld[side] = false; heldSinceNs[side] = 0L; steady[side].reset()
                    UltraXrFeed.hand(side, newest, null, false); pressed[side] = false; pinch[side].lost(); continue
                }
                val tt = hand.joint(Hand.THUMB_TIP); val it2 = hand.joint(Hand.INDEX_TIP)
                val gap = sqrt((tt[0] - it2[0]) * (tt[0] - it2[0]) + (tt[1] - it2[1]) * (tt[1] - it2[1]) + (tt[2] - it2[2]) * (tt[2] - it2[2])).toFloat()
                // Index finger length along its bones (knuckle, two joints, tip): the hand's own ruler.
                var finger = 0f
                for (b in 5..7) { val p0 = hand.joint(b); val p1 = hand.joint(b + 1); finger += sqrt((p0[0] - p1[0]) * (p0[0] - p1[0]) + (p0[1] - p1[1]) * (p0[1] - p1[1]) + (p0[2] - p1[2]) * (p0[2] - p1[2])).toFloat() }
                val began = pinch[side].update(gap, finger)
                journal?.let { j -> j.handFrames[side]++; if (began) j.pinches[side]++; j.gap(side, gap) }
                // While our own (measured) rule says "pinching", the application is given fingertips that touch: a
                // browser decides a pinch by its own, tighter distance, which a tracked hand may never reach.
                val snap = ho.snap && pinch[side].pinching
                val ti = UltraXrFeed.Joint.THUMB_METACARPAL + 3; val ii = UltraXrFeed.Joint.INDEX_METACARPAL + 4
                val mx = (poses[ti * 7] + poses[ii * 7]) / 2; val my = (poses[ti * 7 + 1] + poses[ii * 7 + 1]) / 2; val mz = (poses[ti * 7 + 2] + poses[ii * 7 + 2]) / 2
                // The whole hand is moved by one offset: its place steadied, its shape untouched (core/HandSteady).
                var ox = 0f; var oy = 0f; var oz = 0f
                if (ho.steady) {
                    val pj = UltraXrFeed.Joint.PALM * 7
                    palm[0] = poses[pj].toDouble(); palm[1] = poses[pj + 1].toDouble(); palm[2] = poses[pj + 2].toDouble()
                    steady[side].offset(hand.timestampNs, palm, began, pinch[side].pinching, off)
                    ox = off[0].toFloat(); oy = off[1].toFloat(); oz = off[2].toFloat()
                }
                for (j in 0 until UltraXrFeed.Joint.COUNT) {
                    val o = j * 7
                    if (snap && (j == ti || j == ii)) UltraXrFeed.putJoint(joints, j, mx + ox, my + oy, mz + oz, poses[o + 3], poses[o + 4], poses[o + 5], poses[o + 6], 0.008f)
                    else UltraXrFeed.putJoint(joints, j, poses[o] + ox, poses[o + 1] + oy, poses[o + 2] + oz, poses[o + 3], poses[o + 4], poses[o + 5], poses[o + 6], if (j == 0) 0.02f else 0.008f)
                }
                UltraXrFeed.hand(side, hand.timestampNs, joints, true)
                System.arraycopy(joints, 0, heldJoints[side], 0, joints.size); haveHeld[side] = true; heldSinceNs[side] = 0L
                journal?.let { j -> j.finger(side, finger) }
                val st = states.firstOrNull { it.isRight == (side == 1) } ?: continue
                if (!handsOnly) feedController(side, hand, st, poses, pressed)
            }
            // Recentre from inside a game: pinch with both hands and hold for a second and a half.
            if (recenter.update(newest, pinch[0].pinching, pinch[1].pinching)) {
                Tracking.recenter(); recentred = false; journal?.recentred()
                Tracking.log("OpenXR: recentred by the two-hand pinch")
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
