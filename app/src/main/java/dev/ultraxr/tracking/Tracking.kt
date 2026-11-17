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
    /** Points of the last frame matched in both cameras, and 3D landmarks held; -1 if not reported. */
    @Volatile var vioStereoMatches = -1; private set
    @Volatile var vioLandmarks = -1; private set
    val positionConfidence = dev.ultraxr.core.PositionConfidence()
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

    @Volatile var calibrationSource = "not loaded yet"; private set
    @Volatile var calibrationIsFactory = false; private set
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

    /**
     * Starts the pipeline (reference counted: screens, the service and the runtime each hold it).
     * The native trackers and worker threads are created once and live for the process: only the
     * USB link starts and stops, so there is no window in which a handle is destroyed under a
     * thread that is still using it.
     */
    @Synchronized fun acquire(context: Context) {
        users++
        val app = context.applicationContext
        if (appContext == null) {
            appContext = app
            settings = Settings(app)
            running = true
            link = GlassesLink(app, this)
            // Every hot-plug and mode change of an external display, whichever screen is open.
            try {
                val dm = app.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                dm.registerDisplayListener(object : android.hardware.display.DisplayManager.DisplayListener {
                    override fun onDisplayAdded(id: Int) { dm.getDisplay(id)?.let { noteGraphics("display $id ADDED: " + dev.ultraxr.glasses.DisplayDiagnostics.mode(it.mode) + ", " + it.supportedModes.size + " modes, state " + dev.ultraxr.glasses.DisplayDiagnostics.stateName(it.state)) } }
                    override fun onDisplayRemoved(id: Int) { noteGraphics("display $id REMOVED") }
                    override fun onDisplayChanged(id: Int) { if (id != android.view.Display.DEFAULT_DISPLAY) dm.getDisplay(id)?.let { noteGraphics("display $id changed: " + dev.ultraxr.glasses.DisplayDiagnostics.mode(it.mode) + ", state " + dev.ultraxr.glasses.DisplayDiagnostics.stateName(it.state)) } }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (t: Throwable) { log("display listener: $t") }
            thread(name = "tracking-init") { initNative(app) }
            vioThread = thread(name = "vio-poll", isDaemon = true) { vioLoop() }
            handsThread = thread(name = "hands", isDaemon = true) { handsLoop() }
        }
        if (users > 1) return
        link?.let { it.wantedDisplayMode = wantedMode(); it.sideBySideDisplayPresent = { dev.ultraxr.glasses.DisplayDiagnostics.sideBySidePresent(app) || dev.ultraxr.glasses.DisplayDiagnostics.sideBySideOffered(app) }
            it.externalDisplaySize = { dev.ultraxr.glasses.DisplayDiagnostics.glassesSize(app) }; it.start() }
    }

    @Synchronized fun release() {
        if (users == 0) return
        users--
        if (users > 0) return
        link?.stop()
    }

    /**
     * Called when an activity of this app leaves the foreground. A background app's threads are
     * throttled (measured: about 8 estimator states a second instead of 30, a reset every 4 to 10 s),
     * so while the glasses are streaming the tracking service keeps the process in the
     * foreground-service state. The launcher stops the service again when it returns.
     */
    fun keepAliveInBackground(c: Context) {
        if (link?.state == GlassesLink.State.STREAMING && !TrackingService.running) TrackingService.start(c.applicationContext)
    }

    /** The glasses display mode the settings ask for. */
    fun wantedMode(): Int = when {
        !settings.use3d -> McuProtocol.MODE_2D_60
        settings.refresh90 -> McuProtocol.MODE_SBS_90
        else -> McuProtocol.MODE_SBS_60
    }

    /** Graphics facts recorded by the renderers, for the hardware report. */
    private val graphicsNotes = ArrayDeque<String>()
    fun noteGraphics(msg: String) { synchronized(graphicsNotes) { graphicsNotes.addLast("%tT ".format(System.currentTimeMillis()) + msg); while (graphicsNotes.size > 150) graphicsNotes.removeFirst() }; log(msg) }
    fun graphicsText(): String = synchronized(graphicsNotes) { if (graphicsNotes.isEmpty()) "no renderer has started" else graphicsNotes.joinToString("\n") }

    /** Copies the newest upright 480x640 images handed to the hand tracker. Returns their timestamp, 0 if none yet. */
    fun copyTrackerImages(left: ByteArray, right: ByteArray): Long = synchronized(handLock) {
        System.arraycopy(handLeft, 0, left, 0, handLeft.size); System.arraycopy(handRight, 0, right, 0, handRight.size); handT
    }

    fun recenter() { head.recenter(); log("recenter") }

    /**
     * Who wants the glasses in half-width 3D right now ("desktop", "game"). 3D is asked for while
     * anyone does and the glasses go back to 2D when nobody does; the attempts and the fallback are
     * the stereo switch's (GlassesLink.stereo).
     */
    private val stereoWanters = HashSet<String>()
    @Synchronized fun stereoWant(who: String, on: Boolean) {
        if (on) stereoWanters.add(who) else stereoWanters.remove(who)
        link?.stereo?.want(stereoWanters.isNotEmpty() && settings.tryHalf3d)
    }
    /** True while the glasses are showing half-width 3D (or a test build pretends they are). */
    @Volatile var forceHalfStereo = false
    /** Test builds on an emulator: feed the runtime a made-up head and hands (xr/SimWearer) instead of real tracking. */
    @Volatile var simulateWearer = false
    val halfStereoActive: Boolean get() = forceHalfStereo || link?.stereo?.state == dev.ultraxr.glasses.StereoSwitch.State.ACTIVE

    private val trackerLock = Object()
    private var activeFactoryJson: String? = null

    private fun initNative(app: Context) {
        if (!Native.loaded) { vioError = "native library failed to load: ${Native.loadError}"; handsError = vioError; log(vioError); return }
        // The glasses' factory calibration from an earlier connection, if there was one.
        val cached = try { File(app.filesDir, "factory_config.json").takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
        startTrackers(app, cached)
    }

    /**
     * Creates (or re-creates) both trackers with the best calibration available: this unit's
     * factory calibration when [factoryJson] is usable, otherwise the reference unit's.
     */
    private fun startTrackers(app: Context, factoryJson: String?) = synchronized(trackerLock) {
        val oldVio = vio; val oldHands = handsHandle
        var calib = ""
        try {
            val reference = app.assets.open("calibration/basalt_calib.json").bufferedReader().readText()
            var config = app.assets.open("calibration/basalt_vio_config.json").bufferedReader().readText()
            // Estimator settings under measurement: "config.name=value" lines in files/vio_options.txt replace the
            // shipped values, so a replay of a recording can compare settings over adb without a new build.
            try {
                val lines = File(app.getExternalFilesDir(null), "vio_options.txt").takeIf { it.isFile }?.readLines().orEmpty()
                val changed = ArrayList<String>()
                if (lines.isNotEmpty()) {
                    val root = JSONObject(config); val v0 = root.getJSONObject("value0")
                    for (line in lines) {
                        val k = line.substringBefore('=').trim(); val v = line.substringAfter('=', "").trim()
                        if (k.startsWith("#") || v.isEmpty() || !v0.has(k)) continue
                        val old = v0.get(k)
                        when (old) {
                            is Boolean -> v0.put(k, v == "true" || v == "1")
                            is Int, is Long -> v.toLongOrNull()?.let { v0.put(k, it) }
                            is Number -> v.toDoubleOrNull()?.let { v0.put(k, it) }
                            else -> v0.put(k, v)
                        }
                        changed += "$k: $old -> ${v0.get(k)}"
                    }
                    config = root.toString()
                }
                if (changed.isNotEmpty()) log("6DoF settings changed by vio_options.txt: " + changed.joinToString("; "))
            } catch (e: Exception) { log("vio_options.txt ignored: $e") }
            calib = reference
            calibrationSource = "reference unit (XR Gate ZBBM5DZFMP): the glasses' own calibration has not been read yet"
            calibrationIsFactory = false
            if (factoryJson != null) {
                val r = dev.ultraxr.core.FactoryCalibration.build(factoryJson, reference)
                if (r.basaltJson != null) { calib = r.basaltJson; calibrationSource = r.summary; calibrationIsFactory = true }
                else calibrationSource = "reference unit (XR Gate ZBBM5DZFMP), because: " + r.summary
            }
            activeFactoryJson = factoryJson
            log("calibration: $calibrationSource")
            // The exact calibration handed to the estimator, for offline analysis of captures.
            try { java.io.File(appContext?.getExternalFilesDir(null), "basalt_calib.json").writeText(calib) } catch (_: Exception) {}
            parseRig(calib)
            val h = Native.vioCreate(calib, config)
            if (h == 0L) { vioError = Native.lastError(); log("6DoF init failed: $vioError") }
            else {
                // Basalt starts with zero velocity and gravity from one sample, so a start while the
                // head moves can diverge (seen in simulation). Start only when the IMU says "still".
                Native.vioSetOption(h, "startup_imu_gate", 1.0)
                Native.vioSetOption(h, "num_threads", 2.0)
                // A phone's USB stack can hold IMU reports back for longer than a PC's: do not restart
                // the estimator for gaps it can integrate across.
                Native.vioSetOption(h, "max_imu_gap_s", 0.2)
                // Where the left-to-right search of a new point starts (see STEREO_GUESS_DEPTH_M).
                // A number in files/stereo_guess_depth.txt overrides it, for A/B tests over adb.
                val guess = try { java.io.File(appContext?.getExternalFilesDir(null), "stereo_guess_depth.txt").readText().trim().toDouble() } catch (_: Exception) { STEREO_GUESS_DEPTH_M }
                val guessOk = Native.vioSetOption(h, "stereo_guess_depth", guess)
                log("6DoF stereo search start depth: $guess m" + if (guessOk) "" else " (not supported by this native library)")
                vioError = ""
                vio = h; log("6DoF ready: ${Native.vioVersion()}")
                if (oldVio != 0L) head.onVioEpochReset()
            }
        } catch (e: Throwable) { vioError = e.toString(); log("6DoF init failed: $e") }
        if (settings.handTracking) try {
            handOptions = dev.ultraxr.core.HandOptions.parse(try { File(app.getExternalFilesDir(null), "hand_options.txt").takeIf { it.isFile }?.readText() } catch (_: Exception) { null })
            handOptions.qualityFilter?.let { try { android.system.Os.setenv("MERCURY_XR_QUALITY_FILTER", if (it) "1" else "0", true) } catch (_: Throwable) {} }
            log("hand options: $handOptions")
            val dir = File(app.filesDir, "hand-models").apply { mkdirs() }
            for (name in app.assets.list("hand-models").orEmpty()) {
                val out = File(dir, name)
                // Size check when the asset is stored uncompressed; otherwise just copy again.
                val len = try { app.assets.openFd("hand-models/$name").use { it.length } } catch (_: Exception) { -1L }
                if (!out.exists() || len < 0 || out.length() != len) app.assets.open("hand-models/$name").use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
            // Mercury reads its calibration from a file path, not from text; same layout as Basalt's.
            val calibFile = File(dir, "mercury_calib.json")
            if (calib.isNotEmpty()) calibFile.writeText(calib) else app.assets.open("calibration/mercury_calib.json").use { i -> calibFile.outputStream().use { o -> i.copyTo(o) } }
            val h = Native.handsCreate(calibFile.absolutePath, dir.absolutePath, 2)
            if (h == 0L) { handsError = Native.lastError(); log("hand tracking init failed: $handsError") }
            else { handsError = ""; handsHandle = h; log("hand tracking ready: ${Native.handsVersion()}") }
        } catch (e: Throwable) { handsError = e.toString(); log("hand tracking init failed: $e") }
        // Trackers that were replaced: worker threads may still be inside a call, so free them later.
        if ((oldVio != 0L && oldVio != vio) || (oldHands != 0L && oldHands != handsHandle)) thread(name = "tracker-retire", isDaemon = true) {
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            if (oldVio != 0L && oldVio != vio) Native.vioDestroy(oldVio)
            if (oldHands != 0L && oldHands != handsHandle) Native.handsDestroy(oldHands)
        }
        notifyObservers()
    }

    /** The glasses' factory configuration arrived: switch to this unit's calibration if not already on it. */
    private fun onFactoryConfig(app: Context, json: String) {
        if (json == activeFactoryJson && calibrationIsFactory) return
        if (json == activeFactoryJson) return // already tried this exact data and it was not usable
        try { File(app.filesDir, "factory_config.json").writeText(json) } catch (_: Exception) {}
        thread(name = "tracking-recalibrate") { startTrackers(app, json) }
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
        // Glasses unplugged while only the keep-alive service holds tracking: nothing left to keep alive.
        if (state == GlassesLink.State.NO_GLASSES && TrackingService.running && !dev.ultraxr.xr.XrHost.applicationConnected) appContext?.let { TrackingService.stop(it) }
        if (state == GlassesLink.State.STREAMING) link?.stats?.configJson?.let { json -> applyFactoryConfig(json); appContext?.let { if (Native.loaded) onFactoryConfig(it, json) } }
        notifyObservers()
    }

    override fun onImu(tNs: Long, gyro: FloatArray, accel: FloatArray) {
        head.onImu(tNs, gyro, accel)
        trace.onImu(gyro, accel)
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
            if (handOptions.stretch) { dev.ultraxr.core.HandImage.stretch(handLeft); dev.ultraxr.core.HandImage.stretch(handRight) }
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

    /** The estimator's last seconds before each reset, and a periodic drift line, for diagnosis. */
    val trace = VioTrace { log(it) }

    private fun vioLoop() {
        val out = DoubleArray(24).also { it[22] = -1.0; it[23] = -1.0 }   // 22, 23: stereo matches, landmarks (-1 = not reported)
        var lastSeq = 0L
        var lastResetCount = -1.0
        var lastMode = HeadTracker.Mode.NONE
        var tick = 0
        while (running) {
            try { Thread.sleep(5) } catch (_: InterruptedException) {}
            val v = vio
            val now = System.nanoTime()
            if (v != 0L && (link?.state == GlassesLink.State.STREAMING || link?.replaying == true)) {
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
                    trace.onState(out)
                    vioStereoMatches = out[22].toInt(); vioLandmarks = out[23].toInt()
                    replayLog?.vio(link?.replaySeconds ?: 0.0, out, positionConfidence.trusted)
                    val wasTrusted = positionConfidence.trusted
                    val trusted = positionConfidence.update(now, vioLandmarks, vioStereoMatches)
                    if (trusted != wasTrusted) log(if (trusted) "6DoF position trusted ($vioLandmarks landmarks, $vioStereoMatches seen by both cameras)"
                        else "6DoF position not trusted ($vioLandmarks landmarks, $vioStereoMatches seen by both cameras): rotation only")
                    if (supervisor.onState(now, out[0].toLong(), true, p, vel, vioFeatures)) resetVio(v)
                    else if (trusted) head.onVio(out[0].toLong(), p, q, vel, doubleArrayOf(out[11], out[12], out[13]))
                    // Not trusted: the head keeps its last position and turns from the IMU. When trust
                    // returns the estimator's world is re-aligned to where the head was held.
                    else if (wasTrusted) head.onVioEpochReset()
                } else if (supervisor.onState(now, 0, false, ZERO, ZERO, -1)) resetVio(v)
            }
            if (++tick % 100 == 0) {
                governor()
                val mode = head.predict(now).mode
                if (mode != lastMode) { lastMode = mode; log("head tracking: $mode"); notifyObservers() }
            }
        }
    }

    /** Hand tracking settings under measurement (files/hand_options.txt). */
    @Volatile var handOptions = dev.ultraxr.core.HandOptions(); private set

    /** Set while a recording is replayed: every position state and every hand result is written to CSV files. */
    @Volatile var replayLog: ReplayLog? = null

    /** Replays [file] through the trackers and writes what they report next to it in [outDir]. */
    fun replay(file: File, outDir: File, tag: String, onDone: (String) -> Unit) {
        val l = link ?: run { onDone("tracking has not been started"); return }
        val rl = try { ReplayLog(outDir, tag) } catch (e: Exception) { onDone("cannot write the replay log: $e"); return }
        replayLog = rl
        positionConfidence.reset()
        l.replay(file) { note -> replayLog = null; rl.close(note); onDone(note) }
    }

    private fun resetVio(v: Long) {
        replayLog?.note(link?.replaySeconds ?: 0.0, "reset: " + supervisor.lastReason)
        log("6DoF reset: ${supervisor.lastReason}")
        positionConfidence.reset()
        trace.dump(appContext, supervisor.lastReason)?.let { log("6DoF trace: $it") }
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
            replayLog?.hands(link?.replaySeconds ?: 0.0, mask, out, handsProcessMs)
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
        // The phone never offered a side-by-side mode: remember it, so the glasses are not sent black on every connect.
        if (link?.stats?.sbsFellBack == true && settings.use3d) { settings.use3d = false; log("3D display mode turned off in Settings: the phone offers no 3840x1080 mode") }
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
    /**
     * Depth at which a new point is assumed to lie when it is first looked for in the other camera.
     * The Air 2 Ultra's cameras are angled 9.7 degrees apart on a 137 mm baseline, so a point sits
     * 40 to 75 pixels apart in the two images; the estimator's own search starts at the same pixel
     * and reaches about 30. 0 keeps that upstream behaviour.
     */
    private const val STEREO_GUESS_DEPTH_M = 1.7
    private val HALF_TURN_X = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    private val poses26 = arrayOfNulls<FloatArray>(2)

    /** Latest full joint set of one hand in the app world (26 x px,py,pz,qx,qy,qz,qw), or null. */
    fun handPoses26(side: Int): FloatArray? = poses26[side]
}
