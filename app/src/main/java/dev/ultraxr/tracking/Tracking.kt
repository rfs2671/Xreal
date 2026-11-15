package dev.ultraxr.tracking

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.ultraxr.core.GestureTracker
import dev.ultraxr.core.Hand
import dev.ultraxr.core.HandState
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import dev.ultraxr.core.Settings
import dev.ultraxr.core.VioSupervisor
import dev.ultraxr.glasses.GlassesLink
import dev.ultraxr.proto.McuProtocol
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Process-wide tracking pipeline: glasses -> (Basalt 6DoF, Mercury hands) -> head pose and
 * hand states for the renderer, the built-in game and the OpenXR runtime feed.
 */
object Tracking : GlassesLink.Listener {
    class Snapshot(
        val link: GlassesLink.State, val linkDetail: String, val head: HeadTracker.Mode,
        val vioActive: Boolean, val handsActive: Boolean, val handsVisible: Int,
    )

    interface Observer { fun onTrackingChanged() }

    lateinit var settings: Settings; private set
    var link: GlassesLink? = null; private set
    @Volatile var head = HeadTracker(); private set
    val supervisor = VioSupervisor()
    private val observers = CopyOnWriteArrayList<Observer>()
    private val logLines = ArrayDeque<String>()
    private var appContext: Context? = null
    private var users = 0

    // --- 6DoF
    @Volatile private var vio = 0L
    @Volatile var vioError = ""; private set
    @Volatile var vioStates = 0L; private set
    @Volatile var vioFeatures = -1; private set
    @Volatile var vioProcessMs = 0.0; private set
    @Volatile var vioDropped = 0L; private set
    @Volatile var vioFramesIn = 0L; private set
    @Volatile var vioAutoResets = 0L; private set
    private var vioThread: Thread? = null
    @Volatile private var running = false

    // --- hands
    @Volatile private var handsHandle = 0L
    @Volatile var handsError = ""; private set
    @Volatile var handsFrames = 0L; private set
    @Volatile var handsProcessMs = 0.0; private set
    @Volatile var hands: List<HandState> = emptyList(); private set
    @Volatile var handJoints: List<Hand> = emptyList(); private set
    @Volatile private var handsSeenNs = 0L
    private var handsThread: Thread? = null
    private val handLock = Object()
    private var handPending = false
    private var handT = 0L
    private val handLeft = ByteArray(480 * 640)
    private val handRight = ByteArray(480 * 640)
    private var pairIndex = 0L
    private val gestures = arrayOf(GestureTracker(false), GestureTracker(true))
    private var cam0InImu = Pose()

    // --- thermal governor
    /** 0 = full rate; higher values skip more hand-tracking frames. */
    @Volatile var throttle = 0; private set
    @Volatile var thermalStatus = 0; private set

    val calibrationSource = "reference unit (XR Gate ZBBM5DZFMP)"
    /** Half-FOV tangents of one eye's display, before the user's scale. */
    @Volatile var displayTanH = 0.3976; private set
    @Volatile var displayTanV = 0.2238; private set
    @Volatile var displayFovSource = "default"; private set

    fun addObserver(o: Observer) { observers.addIfAbsent(o) }
    fun removeObserver(o: Observer) { observers.remove(o) }
    private fun notifyObservers() { for (o in observers) o.onTrackingChanged() }

    fun snapshot(): Snapshot {
        val l = link
        val mode = head.predict(System.nanoTime()).mode
        return Snapshot(l?.state ?: GlassesLink.State.NO_GLASSES, l?.stateDetail ?: "", mode,
            vio != 0L, handsHandle != 0L, if (System.nanoTime() - handsSeenNs < 500_000_000L) hands.size else 0)
    }

    fun log(msg: String) {
        synchronized(logLines) { logLines.addLast("%tT %s".format(System.currentTimeMillis(), msg)); while (logLines.size > 400) logLines.removeFirst() }
        android.util.Log.i("UltraXR", msg)
    }
    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    /** Starts the pipeline (reference counted: the service and foreground screens each hold it). */
    @Synchronized fun acquire(context: Context) {
        users++
        if (users > 1) return
        val app = context.applicationContext
        appContext = app
        settings = Settings(app)
        running = true
        thread(name = "tracking-init") { initNative(app) }
        val l = GlassesLink(app, this)
        l.wantedDisplayMode = if (settings.refresh90) McuProtocol.MODE_SBS_90 else McuProtocol.MODE_SBS_60
        link = l
        l.start()
        vioThread = thread(name = "vio-poll") { vioLoop() }
        handsThread = thread(name = "hands") { handsLoop() }
    }

    @Synchronized fun release() {
        if (users == 0) return
        users--
        if (users > 0) return
        running = false
        link?.stop(); link = null
        synchronized(handLock) { handLock.notifyAll() }
        val v = vio; val h = handsHandle
        vio = 0; handsHandle = 0
        thread(name = "tracking-close") {
            try { vioThread?.join(500); handsThread?.join(1500) } catch (_: Exception) {}
            if (v != 0L) Native.vioDestroy(v)
            if (h != 0L) Native.handsDestroy(h)
        }
    }

    fun recenter() { head.recenter(); log("recenter") }

    private fun initNative(app: Context) {
        if (!Native.loaded) { vioError = "native library failed to load: ${Native.loadError}"; handsError = vioError; log(vioError); return }
        try {
            val calib = app.assets.open("calibration/basalt_calib.json").bufferedReader().readText()
            val config = app.assets.open("calibration/basalt_vio_config.json").bufferedReader().readText()
            parseRig(calib)
            val h = Native.vioCreate(calib, config)
            if (h == 0L) { vioError = Native.lastError(); log("6DoF init failed: $vioError") }
            else { vio = h; log("6DoF ready: ${Native.vioVersion()}") }
        } catch (e: Throwable) { vioError = e.toString(); log("6DoF init failed: $e") }
        if (settings.handTracking) try {
            val dir = File(app.filesDir, "hand-models").apply { mkdirs() }
            for (name in app.assets.list("hand-models").orEmpty()) {
                val out = File(dir, name)
                val len = app.assets.openFd("hand-models/$name").use { it.length }
                if (!out.exists() || out.length() != len) app.assets.open("hand-models/$name").use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
            // Mercury reads its calibration from a file path, not from text.
            val calibFile = File(dir, "mercury_calib.json")
            app.assets.open("calibration/mercury_calib.json").use { i -> calibFile.outputStream().use { o -> i.copyTo(o) } }
            val h = Native.handsCreate(calibFile.absolutePath, dir.absolutePath, 2)
            if (h == 0L) { handsError = Native.lastError(); log("hand tracking init failed: $handsError") }
            else { handsHandle = h; log("hand tracking ready: ${Native.handsVersion()}") }
        } catch (e: Throwable) { handsError = e.toString(); log("hand tracking init failed: $e") }
        notifyObservers()
    }

    /** Camera and head geometry from the Basalt calibration (T_imu_cam). */
    private fun parseRig(calibJson: String) {
        val arr = JSONObject(calibJson).getJSONObject("value0").getJSONArray("T_imu_cam")
        fun pose(i: Int): Pose {
            val o = arr.getJSONObject(i)
            return Pose(doubleArrayOf(o.getDouble("px"), o.getDouble("py"), o.getDouble("pz")),
                Q.normalize(doubleArrayOf(o.getDouble("qx"), o.getDouble("qy"), o.getDouble("qz"), o.getDouble("qw"))))
        }
        val c0 = pose(0); val c1 = pose(1)
        cam0InImu = c0
        // Head origin between the two cameras.
        head = HeadTracker(doubleArrayOf((c0.p[0] + c1.p[0]) / 2, (c0.p[1] + c1.p[1]) / 2, (c0.p[2] + c1.p[2]) / 2))
    }

    /** Display optics from the glasses' factory configuration, when it is there and plausible. */
    private fun applyFactoryConfig(json: String) {
        try {
            val d = JSONObject(json).optJSONObject("display") ?: return
            val res = d.optJSONArray("resolution") ?: return
            val k = d.optJSONArray("k_left_display") ?: return
            val w = res.getDouble(0); val h = res.getDouble(1)
            val fx = k.getDouble(0); val fy = k.getDouble(4)
            val th = (w / 2) / fx; val tv = (h / 2) / fy
            // Accept only values that fit a ~45-55 degree diagonal panel; anything else is a
            // different convention than assumed and the default is safer.
            if (th in 0.33..0.5 && tv in 0.18..0.29) { displayTanH = th; displayTanV = tv; displayFovSource = "factory" }
            else log("factory display intrinsics ignored (tan %.3f x %.3f outside expected range)".format(th, tv))
        } catch (e: Exception) { log("factory config parse: $e") }
    }

    // ------------------------------------------------------------ GlassesLink.Listener

    override fun onState(state: GlassesLink.State, detail: String) {
        log("glasses: $state $detail")
        if (state == GlassesLink.State.STREAMING) link?.stats?.configJson?.let { applyFactoryConfig(it) }
        notifyObservers()
    }

    override fun onImu(tNs: Long, gyro: FloatArray, accel: FloatArray) {
        head.onImu(tNs, gyro, accel)
        val v = vio
        if (v != 0L) Native.vioPushImu(v, tNs, gyro[0], gyro[1], gyro[2], accel[0], accel[1], accel[2])
    }

    override fun onStereo(tNs: Long, left: ByteArray, right: ByteArray) {
        val v = vio
        if (v != 0L) { Native.vioPushStereo(v, tNs, left, right, 480, 640); supervisor.onFrame(System.nanoTime()) }
        pairIndex++
        // Hands at 30, 15 or 10 Hz depending on heat and the battery saver.
        val every = 1 + throttle + if (settings.batterySaver) 1 else 0
        if (handsHandle != 0L && pairIndex % every == 0L) synchronized(handLock) {
            System.arraycopy(left, 0, handLeft, 0, left.size); System.arraycopy(right, 0, handRight, 0, right.size)
            handT = tNs; handPending = true; handLock.notifyAll()
        }
    }

    override fun onStreamRestart() {
        val v = vio
        if (v != 0L) Native.vioReset(v)
        head.onVioEpochReset()
        for (g in gestures) g.reset()
    }

    override fun onLog(msg: String) = log(msg)

    // ------------------------------------------------------------ worker loops

    private fun vioLoop() {
        val out = DoubleArray(22)
        var lastSeq = 0L
        var lastResetCount = -1.0
        var lastMode = HeadTracker.Mode.NONE
        var tick = 0
        while (running) {
            try { Thread.sleep(5) } catch (_: InterruptedException) {}
            val v = vio
            val now = System.nanoTime()
            if (v != 0L && link?.state == GlassesLink.State.STREAMING) {
                val seq = Native.vioGetState(v, out)
                // The estimator restarted (by itself or on request): its world frame is new.
                if (out[20] != lastResetCount) { if (lastResetCount >= 0) head.onVioEpochReset(); lastResetCount = out[20] }
                vioAutoResets = out[21].toLong()
                val fresh = seq != lastSeq && out[19] > 0.5
                if (fresh) {
                    lastSeq = seq
                    val p = doubleArrayOf(out[1], out[2], out[3]); val q = doubleArrayOf(out[4], out[5], out[6], out[7])
                    val vel = doubleArrayOf(out[8], out[9], out[10])
                    vioFeatures = out[14].toInt(); vioFramesIn = out[15].toLong(); vioDropped = out[16].toLong(); vioStates = out[17].toLong(); vioProcessMs = out[18]
                    if (supervisor.onState(now, out[0].toLong(), true, p, vel, vioFeatures)) resetVio(v)
                    else head.onVio(out[0].toLong(), p, q, vel, doubleArrayOf(out[11], out[12], out[13]))
                } else if (supervisor.onState(now, 0, false, ZERO, ZERO, -1)) resetVio(v)
            }
            if (++tick % 100 == 0) {
                governor()
                val mode = head.predict(now).mode
                if (mode != lastMode) { lastMode = mode; log("head tracking: $mode"); notifyObservers() }
            }
        }
    }

    private fun resetVio(v: Long) {
        log("6DoF reset: ${supervisor.lastReason}")
        Native.vioReset(v)
        head.onVioEpochReset()
    }

    private fun handsLoop() {
        val l = ByteArray(480 * 640); val r = ByteArray(480 * 640)
        val out = FloatArray(2 * Hand.JOINTS * 3)
        val out26 = FloatArray(2 * 26 * 7)
        while (running) {
            var t: Long
            synchronized(handLock) {
                while (running && !handPending) try { handLock.wait(500) } catch (_: InterruptedException) {}
                if (!running) return
                System.arraycopy(handLeft, 0, l, 0, l.size); System.arraycopy(handRight, 0, r, 0, r.size)
                t = handT; handPending = false
            }
            val h = handsHandle
            if (h == 0L) continue
            val t0 = System.nanoTime()
            val mask = try { Native.handsProcess(h, t, l, r, 480, 640, out, out26) } catch (e: Throwable) { handsError = e.toString(); -1 }
            handsProcessMs = (System.nanoTime() - t0) / 1e6
            handsFrames++
            if (mask < 0) continue
            val body = head.bodyInApp(t)
            val found = ArrayList<Hand>(2); val states = ArrayList<HandState>(2)
            if (body != null) for (side in 0..1) {
                if (mask and (1 shl side) == 0) { gestures[side].reset(); poses26[side] = null; continue }
                val world = FloatArray(Hand.JOINTS * 3)
                val toApp = body * cam0InImu
                for (j in 0 until Hand.JOINTS) {
                    val o = (side * Hand.JOINTS + j) * 3
                    // Mercury reports in the camera's OpenXR-style frame (x right, y up, -z forward);
                    // the calibration's camera frame is x right, y down, z forward.
                    val p = toApp.apply(doubleArrayOf(out[o].toDouble(), -out[o + 1].toDouble(), -out[o + 2].toDouble()))
                    world[j * 3] = p[0].toFloat(); world[j * 3 + 1] = p[1].toFloat(); world[j * 3 + 2] = p[2].toFloat()
                }
                // Full joint poses for OpenXR: Mercury frame -> camera frame is a half turn about X.
                val w26 = FloatArray(26 * 7)
                for (j in 0 until 26) {
                    val o = (side * 26 + j) * 7
                    val pose = toApp * Pose(doubleArrayOf(0.0, 0.0, 0.0), HALF_TURN_X) *
                        Pose(doubleArrayOf(out26[o].toDouble(), out26[o + 1].toDouble(), out26[o + 2].toDouble()),
                            Q.normalize(doubleArrayOf(out26[o + 3].toDouble(), out26[o + 4].toDouble(), out26[o + 5].toDouble(), out26[o + 6].toDouble())))
                    val k = j * 7
                    w26[k] = pose.p[0].toFloat(); w26[k + 1] = pose.p[1].toFloat(); w26[k + 2] = pose.p[2].toFloat()
                    w26[k + 3] = pose.q[0].toFloat(); w26[k + 4] = pose.q[1].toFloat(); w26[k + 5] = pose.q[2].toFloat(); w26[k + 6] = pose.q[3].toFloat()
                }
                poses26[side] = w26
                val hand = Hand(side == 1, world, t)
                found += hand; states += gestures[side].update(hand)
            }
            handJoints = found; hands = states
            if (found.isNotEmpty()) handsSeenNs = System.nanoTime()
        }
    }

    /** Backs off optional work as the phone heats up. */
    private fun governor() {
        val ctx = appContext ?: return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = pm.currentThermalStatus
        thermalStatus = status
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else Float.NaN
        val t = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 2
            status >= PowerManager.THERMAL_STATUS_MODERATE || (!headroom.isNaN() && headroom > 0.95f) -> 1
            else -> 0
        }
        if (t != throttle) { throttle = t; log("thermal status $status, headroom $headroom: hand tracking every ${1 + t} frames") }
    }

    private val ZERO = doubleArrayOf(0.0, 0.0, 0.0)
    private val HALF_TURN_X = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    private val poses26 = arrayOfNulls<FloatArray>(2)

    /** Latest full joint set of one hand in the app world (26 x px,py,pz,qx,qy,qz,qw), or null. */
    fun handPoses26(side: Int): FloatArray? = poses26[side]
}
