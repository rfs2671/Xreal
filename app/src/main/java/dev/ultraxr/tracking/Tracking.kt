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
    /** vio_options.txt: carry=1 has the accelerometer carry the head's place between states ([HeadTracker.blendOn]); carry_speed is [HeadTracker.speedShare]. */
    // On since 23 November 2026: on three worn recordings replayed through the app the head's place shook 0.62 to 0.80 mm a
    // frame moving slowly against 0.66 to 1.13 without, and 0.73 to 1.59 against 1.32 to 1.90 walking; never more. carry=0 in vio_options.txt turns it off.
    @Volatile private var headBlend = true
    @Volatile private var headSpeedShare = 1.0
    val supervisor = VioSupervisor()
    private val observers = CopyOnWriteArrayList<Observer>()
    private val logLines = ArrayDeque<String>()
    private var appContext: Context? = null
    fun appContextOrNull(): Context? = appContext
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
    /** Whether hands are being tracked now. Off: no pictures go to the hand tracker, no hand is reported, and the battery is spared. */
    @Volatile var handsOn = true; private set
    fun setHandTracking(on: Boolean) { settings.handTracking = on; if (handsOn != on) { handsOn = on; if (!on) hands = emptyList() /* no hand, and no pinch, is left standing */; log(if (on) "hand tracking switched on" else "hand tracking switched off") } }
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

    // --- the room: floor, table tops and walls from the same two cameras (core/StereoPoints, core/RoomModel)
    val room = dev.ultraxr.core.RoomModel()
    /** The correction to the right camera's rotation in use this run (degrees), and whether it is the unit's standing one (not a replay's trial). */
    @Volatile private var stereoFixInUse = doubleArrayOf(0.0, 0.0, 0.0)
    @Volatile private var stereoFixIsUnit = true
    @Volatile private var floorCalibrator: dev.ultraxr.core.FloorCalibrator? = null
    @Volatile var floorSlopeNote = ""; private set
    /** Walls from dense depth (core/WallMap): on unless files/vio_options.txt says dense_walls=0. */
    @Volatile var denseWalls = true
    @Volatile private var wallMapReset = false
    /** The walls as they stood before position was lost, to put the world back on them (core/RoomRecall). Used under its own lock. */
    private val recall = dev.ultraxr.core.RoomRecall(); @Volatile private var recallShifting = false
    /**
     * The room as the owner marked it (core/RoomPlan, files/room_plan.txt), how it lies in the app's world now
     * ([planFit], null until the cameras have found enough of it), and how it lay before position was last lost
     * ([planBefore]: the world is put back so that it lies there again). All under [planLock].
     */
    private val planLock = Any()
    private var plan: dev.ultraxr.core.RoomPlan? = null
    private var planFit: dev.ultraxr.core.RoomPlan.Fit? = null
    private var planBefore: dev.ultraxr.core.RoomPlan.Fit? = null
    private var planLostNs = 0L; private var planMaxShift = 0.0; private var planSaidNs = 0L
    private var planTried = 0; private var planAgreed = 0; private var planCorrected = 0; private var planCorrectedM = 0.0
    @Volatile private var planShifting = false
    /** For whoever tells applications about the room: corners marked, and whether the plan is placed in the world now. */
    val roomPlanCorners: Int get() = synchronized(planLock) { plan?.corners?.size ?: 0 }
    /** The marked room's corners as (x, z) in the plan's own frame, in the order they were marked; empty with no room marked. */
    val roomPlanOutline: List<DoubleArray> get() = synchronized(planLock) { plan?.corners?.map { doubleArrayOf(it[0], it[1]) } ?: emptyList() }
    val roomPlanPlaced: Boolean get() = synchronized(planLock) { plan != null && planFit != null }

    /**
     * The owner has marked the room: [corners] are (x, z) in the app's world as it is now, going round the room once.
     * The table tops the cameras see at this moment are kept with it as landmarks. Returns what was kept, as a line for the log.
     */
    fun saveRoomPlan(corners: List<DoubleArray>): String {
        val f = roomFindings; val floor = f?.floorY ?: Double.NaN
        val tables = if (f == null || floor.isNaN()) emptyList() else f.surfaces.filter { it.kind == dev.ultraxr.core.RoomSurface.Kind.TABLE }.map { doubleArrayOf(it.cx, it.cz, it.cy - floor) }
        val p = dev.ultraxr.core.RoomPlan(corners, tables)
        if (!p.usable) return "room plan refused: " + corners.size + " corners, " + p.walls.size + " walls (three of each are needed)"
        synchronized(planLock) { plan = p; planFit = dev.ultraxr.core.RoomPlan.Fit(0.0, 0.0, 0.0, p.walls.size, tables.size); planBefore = null; planMaxShift = 0.0 }
        try { appContext?.getExternalFilesDir(null)?.let { File(it, "room_plan.txt").writeText(p.toText()) } } catch (e: Exception) { log("room plan not written: $e") }
        val said = "room plan kept: " + corners.size + " corners, " + p.walls.size + " walls (" + p.walls.joinToString(" ") { dev.ultraxr.core.Fmt.f(it.length, 1) } + " m), " + tables.size + " table tops as landmarks"
        log(said); return said
    }
    /**
     * The wearer has looked at corners of the room and tapped: [taps] are (x, z) in the app's world as it is now.
     * Places the plan when they settle where it lies (core/RoomPlan.fitCorners). Returns a line for the log that
     * begins "room plan placed" when it is.
     */
    fun placeRoomPlan(taps: List<DoubleArray>): String {
        val said = synchronized(planLock) {
            val p = plan ?: return@synchronized "no room plan to place"
            val f = p.fitCorners(taps) ?: return@synchronized "room plan not placed yet from " + taps.size + " corners (four at least, and one more when two places fit)"
            planFit = f; planBefore = null; planMaxShift = 0.0
            "room plan placed from " + taps.size + " corners the wearer looked at"
        }
        log(said); return said
    }
    fun clearRoomPlan(): String {
        synchronized(planLock) { plan = null; planFit = null; planBefore = null }
        try { appContext?.getExternalFilesDir(null)?.let { File(it, "room_plan.txt").delete() } } catch (_: Exception) {}
        log("room plan forgotten"); return "room plan forgotten"
    }
    /** The marked walls as surfaces of the app's world, when the plan is placed; null when there is none or it is not placed. */
    private fun planWalls(floorY: Double): List<dev.ultraxr.core.RoomSurface>? {
        val p: dev.ultraxr.core.RoomPlan; val f: dev.ultraxr.core.RoomPlan.Fit
        synchronized(planLock) { p = plan ?: return null; f = planFit ?: return null }
        if (floorY.isNaN()) return null
        var mx = 0.0; var mz = 0.0
        for (c in p.corners) { val w = f.apply(c[0], c[1]); mx += w[0] / p.corners.size; mz += w[1] / p.corners.size }
        return p.walls.map { l0 -> val l = f.line(l0)
            var ux = l.ux; var uz = l.uz
            // the face of a wall is towards the inside of the room: normal = u x up = (-uz, 0, ux)
            if (-uz * (mx - l.mx) + ux * (mz - l.mz) < 0) { ux = -ux; uz = -uz }
            dev.ultraxr.core.RoomSurface(dev.ultraxr.core.RoomSurface.Kind.WALL, l.mx, floorY + p.ceilingM / 2, l.mz, ux, 0.0, uz, 0.0, 1.0, 0.0, l.length / 2, p.ceilingM / 2, 999) }
    }
    @Volatile var denseMs = 0.0; private set
    @Volatile var denseWallCount = 0; private set
    /** What the last look at the room found; null before the first. */
    @Volatile var roomFindings: dev.ultraxr.core.RoomFindings? = null; private set
    /** The floor's height in the app's world (the head's is 0 at a recentre). NaN until the cameras have found it. */
    val floorY: Double get() = roomFindings?.floorY ?: Double.NaN
    @Volatile var roomPictures = 0L; private set
    @Volatile var roomFindMs = 0.0; private set
    @Volatile private var stereoRig: dev.ultraxr.core.StereoRig? = null
    private val roomLock = Object()
    private val roomLeft = ByteArray(480 * 640); private val roomRight = ByteArray(480 * 640)
    private var roomT = 0L; private var roomHave = false; private var roomReady = false
    private var roomP = DoubleArray(3); private var roomQ = Q.identity(); private var roomFix = Pose(); private var roomWorld = 0
    private var roomLastNs = 0L; private var roomUsedNs = 0L
    private var roomThread: Thread? = null
    /** Test builds on an emulator: a made-up room given to the room model's users instead of one from cameras. */
    fun setRoomForTest(f: dev.ultraxr.core.RoomFindings?) { roomFindings = f }
    /** The places the cameras have measured several times over and seen in the last ten seconds (x, y, z in the app's world) and when. */
    @Volatile var roomPoints = FloatArray(0); private set
    @Volatile var roomPointsNs = 0L; private set
    fun setRoomPointsForTest(p: FloatArray) { roomPoints = p; roomPointsNs = System.nanoTime() }

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
        // Also kept in a file (log.txt beside the journal, the one before it as log.1.txt): the system's log is gone
        // when the phone restarts, as it did on 18 November right after a session.
        try {
            val dir = appContext?.getExternalFilesDir(null) ?: return
            synchronized(logLines) {
                val f = java.io.File(dir, "log.txt")
                if (f.length() > 1_500_000L) { val old = java.io.File(dir, "log.1.txt"); old.delete(); f.renameTo(old) }
                f.appendText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS ", java.util.Locale.US).format(java.util.Date()) + msg + 10.toChar())
            }
        } catch (_: Throwable) {}
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
            roomThread = thread(name = "room", isDaemon = true) { roomLoop() }
            thread(name = "map-search", isDaemon = true) { mapSearchLoop() }
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
        thread(name = "room-map-save", isDaemon = true) { try { saveRoomMap() } catch (e: Throwable) { log("room map: not saved: $e") } }
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
                // Measured 21 November 2026, glasses on the table, desktop open: the cameras gave 55 pictures a second
                // of 60 (25 pairs of 30) with four windows, because the reader stood in line behind the windows' own
                // work; read ahead of them it gives 60, with six windows too ("cam_priority=0": as before). The hand
                // tracker was still slow with six windows (36 to 125 ms a result). Putting the trackers first as well
                // ("work_priority=-8") gave 29 to 58 ms in the one run there was battery for, with more late display
                // frames (12 a second against 8): not shown to be better, so it is off until it is.
                dev.ultraxr.usb.UvcStream.PRIORITY = lines.firstOrNull { it.startsWith("cam_priority=") }?.substringAfter('=')?.trim()?.toIntOrNull() ?: -16
                workPriority = lines.firstOrNull { it.startsWith("work_priority=") }?.substringAfter('=')?.trim()?.toIntOrNull() ?: WORK_PRIORITY
                try { android.os.Process.setThreadPriority(workPriority) } catch (_: Throwable) {}
                holdWhenStill = lines.none { it.replace(" ", "") == "hold_when_still=0" }
                denseWalls = lines.none { it.replace(" ", "") == "dense_walls=0" }
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
            // A small correction to the right camera's rotation, measured from real stereo pairs
            // (tools/tracking/stereo_check.py). "x,y,z" degrees in files/stereo_rotation_fix.txt, for replays.
            try {
                // stereo_fix_unit.txt is this pair of glasses' standing correction (it stays); stereo_rotation_fix.txt is
                // a replay's trial value and wins while it is there. Measured for the owner's unit on 18 November 2026
                // (tools/tracking/floor_flat.py): with the factory angle between the cameras the floor came out 16 cm
                // too high under the wearer and rose 10 cm with every metre of distance; turned by 0.4 degrees it is flat.
                var from = "stereo_rotation_fix.txt"
                var fix = File(app.getExternalFilesDir(null), from).takeIf { it.isFile }?.readText()?.trim()?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
                if (fix == null || fix.size != 3) { from = "stereo_fix_unit.txt"; fix = File(app.getExternalFilesDir(null), from).takeIf { it.isFile }?.readText()?.trim()?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() } }
                stereoFixInUse = if (fix != null && fix.size == 3) doubleArrayOf(fix[0], fix[1], fix[2]) else doubleArrayOf(0.0, 0.0, 0.0)
                stereoFixIsUnit = fix == null || fix.size != 3 || from == "stereo_fix_unit.txt"
                if (fix != null && fix.size == 3) { calib = applyStereoRotationFix(calib, fix[0], fix[1], fix[2]); log("calibration: right camera turned by " + fix.joinToString(", ") + " degrees (" + from + ")") }
            } catch (e: Exception) { log("stereo_rotation_fix.txt ignored: $e") }
            // The exact calibration handed to the estimator, for offline analysis of captures.
            try { java.io.File(appContext?.getExternalFilesDir(null), "basalt_calib.json").writeText(calib) } catch (_: Exception) {}
            parseRig(calib)
            val h = Native.vioCreate(calib, config)
            if (h == 0L) { vioError = Native.lastError(); log("6DoF init failed: $vioError") }
            else {
                // Basalt starts with zero velocity and gravity from one sample. XR Gate's gate waits for a whole second
                // in which the motion sensor looks at rest; a head that is worn seldom gives one (measured on five
                // recordings, 20 November 2026, tools/tracking/vio_gate.py: the first start took 8 to 20 s, and a
                // restart while the wearer moves about waited as long or never came back). Three frames of a head
                // that is not turning fast are enough: replays of the same recordings give the same path, to within
                // what two runs of one setting differ by, from about one second in (docs/SPATIAL.md).
                Native.vioSetOption(h, "startup_imu_gate", 1.0)
                Native.vioSetOption(h, "startup_gate_frames", 3.0); Native.vioSetOption(h, "startup_gate_gyro", 1.0); Native.vioSetOption(h, "startup_gate_gyro_std", 0.5)
                Native.vioSetOption(h, "startup_gate_accel_err", 2.0); Native.vioSetOption(h, "startup_gate_accel_std", 1.5)
                // Measured twice on the S21 Ultra (20 November 2026, night; tools/tracking/map_cost.py, runs e_mix and e_mixb
                // against e_base*): the estimator's own threads ahead of ordinary ones, three of them, and five optimiser
                // rounds in place of seven took a frame from 40 to 42 ms to 32 ms on a warm phone, at 30.0 states a second,
                // with the same path (median 0.4 to 0.8 cm from the old settings' over two seconds, as two runs of the old
                // settings are from each other). The hand tracker pays about 3 ms a result for it.
                Native.vioSetOption(h, "num_threads", 3.0)
                Native.vioSetOption(h, "thread_nice", -8.0)
                // Under measurement: "native.KEY=VALUE" lines in files/vio_options.txt go to the library as they are
                // (startup_imu_gate, startup_gate_frames, startup_gate_gyro, ...), after the settings above.
                try {
                    for (line in File(app.getExternalFilesDir(null), "vio_options.txt").takeIf { it.isFile }?.readLines().orEmpty()) {
                        if (!line.startsWith("native.")) continue
                        val k = line.substringAfter("native.").substringBefore('=').trim(); val v = line.substringAfter('=', "").trim().toDoubleOrNull() ?: continue
                        log("6DoF library option $k = $v" + if (Native.vioSetOption(h, k, v)) "" else " (not taken)")
                    }
                } catch (e: Exception) { log("vio_options.txt native options ignored: $e") }
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
                roomMapReadOnly = try { File(app.getExternalFilesDir(null), "vio_options.txt").takeIf { it.isFile }?.readLines().orEmpty().any { it.replace(" ", "") == "room_map=find" } } catch (_: Exception) { false }
                roomMapOn = try { File(app.getExternalFilesDir(null), "vio_options.txt").takeIf { it.isFile }?.readLines().orEmpty().none { it.replace(" ", "") == "room_map=off" } } catch (_: Exception) { true }
                val freshMap = try { File(app.getExternalFilesDir(null), "vio_options.txt").takeIf { it.isFile }?.readLines().orEmpty().any { it.replace(" ", "") == "room_map=new" } } catch (_: Exception) { false }
                mapTestSlip = try { File(app.getExternalFilesDir(null), "vio_options.txt").takeIf { it.isFile }?.readLines().orEmpty().firstOrNull { it.startsWith("map_test_slip=") }?.substringAfter('=')?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 4 }?.toDoubleArray() } catch (_: Exception) { null }
                mapSlipDone = false
                try {
                    val lines = File(app.getExternalFilesDir(null), "vio_options.txt").takeIf { it.isFile }?.readLines().orEmpty()
                    recoveryOn = lines.none { it.replace(" ", "") == "recovery=0" }
                    mapGlide = lines.none { it.replace(" ", "") == "map_glide=0" }; restartAfterLoss = lines.any { it.replace(" ", "") == "restart_after_loss=1" }; roomMeshOn = lines.any { it.replace(" ", "") == "room_mesh=1" }
                    testBlind = lines.firstOrNull { it.startsWith("test_blind=") }?.substringAfter('=')?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }?.toDoubleArray()
                    testLight = lines.firstOrNull { it.startsWith("test_light=") }?.substringAfter('=')?.trim()?.toDoubleOrNull() ?: 1.0
                    testNoise = lines.firstOrNull { it.startsWith("test_blind_noise=") }?.substringAfter('=')?.trim()?.toIntOrNull() ?: 0
                    starveOn = lines.none { it.replace(" ", "") == "starve=0" }
                    head.glideOn = lines.none { it.replace(" ", "") == "glide=0" }
                    headBlend = lines.none { it.replace(" ", "") == "carry=0" }; headSpeedShare = lines.firstOrNull { it.startsWith("carry_speed=") }?.substringAfter('=')?.trim()?.toDoubleOrNull()?.coerceIn(0.02, 1.0) ?: 1.0
                    head.blendOn = headBlend; head.speedShare = headSpeedShare
                    handTone = lines.firstOrNull { it.startsWith("hand_tone=") }?.substringAfter('=')?.trim()?.toDoubleOrNull() ?: HAND_TONE
                    handToneLut = handTone.takeIf { it > 0 }?.let { tau -> ByteArray(256) { i -> (255.0 * (1 - Math.exp(-i / tau)) / (1 - Math.exp(-255.0 / tau))).toInt().coerceIn(0, 255).toByte() } }
                    if (testBlind != null || testLight != 1.0) log("test: " + (testBlind?.let { "the cameras see nothing from " + it[0] + " to " + it[1] + " s of a replay; " } ?: "") + (if (testLight != 1.0) "pictures at $testLight of their brightness" else ""))
                } catch (_: Exception) { testBlind = null; testLight = 1.0 }
                slamRig = slamRigOf(calib)
                try { openRoomMap(freshMap) } catch (e: Throwable) { log("room map: not started: $e") }
                if (oldVio != 0L) head.onVioEpochReset()
            }
        } catch (e: Throwable) { vioError = e.toString(); log("6DoF init failed: $e") }
        // The hand tracker is always made ready; whether it is given pictures is the switch ([handsOn]), which can
        // then be turned at any moment, in a game too.
        handsOn = settings.handTracking
        try {
            handOptions = dev.ultraxr.core.HandOptions.parse(try { File(app.getExternalFilesDir(null), "hand_options.txt").takeIf { it.isFile }?.readText() } catch (_: Exception) { null })
            // Each switch is set or cleared every time tracking starts: the process outlives one start, and a switch
            // set for one replay must not stay for the next.
            fun env(name: String, value: String?) { try { if (value == null) android.system.Os.unsetenv(name) else android.system.Os.setenv(name, value, true) } catch (_: Throwable) {} }
            fun onOff(b: Boolean?) = b?.let { if (it) "1" else "0" }
            env("MERCURY_XR_QUALITY_FILTER", onOff(handOptions.qualityFilter))
            env("ULTRA_HANDS_TILES", handOptions.tiles?.toString())
            env("ULTRA_HANDS_TILES_MIN_CONF", handOptions.tilesMinConf?.toString())
            env("ULTRA_HANDS_KEEP", onOff(handOptions.keep))
            // Off since 23 November 2026. The second looks were there to keep things that are not hands from beginning; a new
            // hand now has to settle before it is shown, which does that. Cold starts on the recording of 22 November (48 moments
            // with a hand plainly in both pictures): a hand within a second in 29 with them off against 19, never within four in 1
            // against 8, both hands within four seconds in 10 of 12 against none. Where the two differ over the whole recording
            // the pictures show a hand each time. On the labelled recording of 17 November a hand is reported where the labels
            // have none in 9 % and 1 % of results (two runs) against 3 % and 1 %. verify=1 in hand_options.txt brings them back.
            env("ULTRA_HANDS_VERIFY", onOff(handOptions.verify ?: false))
            env("ULTRA_HANDS_ONE_VIEW", onOff(handOptions.oneView))
            env("ULTRA_HANDS_XNNPACK", handOptions.xnnpack?.toString())
            env("ULTRA_HANDS_STEREO_SEEN", onOff(handOptions.begin)); env("ULTRA_HANDS_SETTLE", onOff(handOptions.begin))
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
            // The library's own threads are started here and stay on the cores this thread is on while it starts them.
            val cores = if (handOptions.fastCores) try { Native.fastCores(true) } catch (_: Throwable) { 0 } else 0
            val h = try { Native.handsCreate(calibFile.absolutePath, dir.absolutePath, 2) } finally { if (cores != 0) try { Native.fastCores(false) } catch (_: Throwable) {} }
            handCores = cores
            log(if (cores != 0) "hand tracking on the fast cores (mask " + Integer.toBinaryString(cores) + ")" else "hand tracking on any core")
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

    /**
     * The calibration with the right camera turned by the rotation vector ([xDeg], [yDeg], [zDeg]) in its own frame:
     * R(camera 0 in camera 1) becomes R(w) * R, which is R(imu from camera 1) * R(w) transposed.
     */
    fun applyStereoRotationFix(calibJson: String, xDeg: Double, yDeg: Double, zDeg: Double): String {
        val root = JSONObject(calibJson); val c1 = root.getJSONObject("value0").getJSONArray("T_imu_cam").getJSONObject(1)
        val q1 = doubleArrayOf(c1.getDouble("qx"), c1.getDouble("qy"), c1.getDouble("qz"), c1.getDouble("qw"))
        val x = Math.toRadians(xDeg); val y = Math.toRadians(yDeg); val z = Math.toRadians(zDeg)
        val angle = Math.sqrt(x * x + y * y + z * z)
        if (angle < 1e-9) return calibJson
        val w = Q.axisAngle(x / angle, y / angle, z / angle, angle)
        val q = Q.normalize(Q.mul(q1, Q.conj(w)))
        c1.put("qx", q[0]); c1.put("qy", q[1]); c1.put("qz", q[2]); c1.put("qw", q[3])
        return root.toString()
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
        stereoRig = try { dev.ultraxr.core.StereoRig.fromBasalt(calibJson) } catch (e: Exception) { log("room: calibration not usable: $e"); null }
        // Head origin between the two cameras.
        head = HeadTracker(doubleArrayOf((c0.p[0] + c1.p[0]) / 2, (c0.p[1] + c1.p[1]) / 2, (c0.p[2] + c1.p[2]) / 2))
        head.blendOn = headBlend; head.speedShare = headSpeedShare
        // A recentre moves the app's world; the room found so far moves with it.
        head.onWorldMoved = { moved ->
            // a step of a centimetre or two towards the saved map is drift being taken out: the room model's boxes are
            // coarser than that, and clearing the wall plan at every step would leave it empty while the wearer walks
            if (!(mapShifting && mapSmall)) { room.transform(moved); wallMapReset = true; roomFindings = room.analyse(0.0) }
            if (!recallShifting && !mapShifting) synchronized(recall) { recall.moved(moved) }
            // a recentre carries the marked room along; putting a slipped world back is exactly what must not
            if (!planShifting && !mapShifting) synchronized(planLock) { planFit = planFit?.moved(moved); planBefore = planBefore?.moved(moved) }
            // and it carries the saved map along
            if (!mapSilent) synchronized(mapAlign) { mapAlign.onWorldMoved(moved, own = mapShifting) } }
        room.clear(); wallMapReset = true; roomFindings = null; synchronized(recall) { recall.clear() }
        synchronized(planLock) {
            planFit = null; planBefore = null; planMaxShift = 0.0
            plan = dev.ultraxr.core.RoomPlan.parse(try { appContext?.getExternalFilesDir(null)?.let { File(it, "room_plan.txt").takeIf { f -> f.isFile }?.readText() } } catch (_: Exception) { null })
            plan?.let { log("room plan read: " + it.corners.size + " corners, " + it.tables.size + " table tops as landmarks; it is placed when the cameras have found two of its walls") }
        }
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
        stillness.onImu(tNs, gyro, accel)
        trace.onImu(gyro, accel)
        val v = vio
        if (v != 0L) Native.vioPushImu(v, tNs, gyro[0], gyro[1], gyro[2], accel[0], accel[1], accel[2])
    }

    // Tests of what happens when things go wrong, for replays only (files/vio_options.txt):
    //   test_blind=FROM,TO   between those seconds of the recording the cameras see nothing (both pictures a flat grey):
    //                        position is lost as when the glasses are covered or the room goes dark, and must come back
    //   test_light=GAIN      every picture is made darker (under 1) or brighter by that factor: a session in another light
    @Volatile private var testBlind: DoubleArray? = null
    @Volatile private var testLight = 1.0
    //   test_blind_noise=N   the blind pictures are not flat but dark with noise of 0 to N grey levels, new in every
    //                        picture and each camera its own: what a hand over the cameras gives (worn, 21 November:
    //                        the corner count stays at 108 while the landmarks go)
    //   starve=0             the loss is not told from the landmarks (the build of the morning of 21 November)
    @Volatile private var testNoise = 0
    @Volatile private var starveOn = true
    @Volatile private var handTone = HAND_TONE; @Volatile private var handToneLut: ByteArray? = null
    private val testRandom = java.util.Random(7)
    private fun testPictures(left: ByteArray, right: ByteArray) {
        val l = link ?: return; if (!l.replaying) return
        val t = l.replaySeconds; val b = testBlind
        if (b != null && t >= b[0] && t < b[1]) {
            val n = testNoise
            if (n <= 0) { java.util.Arrays.fill(left, 40.toByte()); java.util.Arrays.fill(right, 40.toByte()) }
            else for (a in arrayOf(left, right)) { testRandom.nextBytes(a); for (i in a.indices) a[i] = (12 + (a[i].toInt() and 0xff) % (n + 1)).toByte() }
            return
        }
        val g = testLight
        if (g != 1.0) for (a in arrayOf(left, right)) for (i in a.indices) a[i] = ((a[i].toInt() and 0xff) * g).toInt().coerceIn(0, 255).toByte()
    }

    override fun onStereo(tNs: Long, left: ByteArray, right: ByteArray) {
        if (testBlind != null || testLight != 1.0) testPictures(left, right)
        val v = vio
        if (v != 0L) { Native.vioPushStereo(v, tNs, left, right, 480, 640); supervisor.onFrame(System.nanoTime()) }
        if (link?.replaying != true) lossPictures.onStereo(tNs, left, right)
        pairIndex++
        // While the head is being looked for in the saved map (a new session, or after a loss), the map gets pictures
        // of its own, seven a second: it must not wait for the room's loop (measured 20 November: the map then saw its
        // first picture seconds after the cameras did, and answered nine seconds after a loss; the library alone
        // finds the place in 0.4 s).
        if (slam != 0L && (mapSearching || recovering)) synchronized(mapPicLock) {
            if (mapPicHave && !mapPicReady && tNs - mapPicT > 400_000_000L) mapPicHave = false
            if (!mapPicHave && tNs - mapPicLastNs >= 140_000_000L) {
                System.arraycopy(left, 0, mapPicLeft, 0, left.size); System.arraycopy(right, 0, mapPicRight, 0, right.size)
                mapPicT = tNs; mapPicLastNs = tNs; mapPicHave = true; mapPicReady = false
            }
        }
        // The room: three pictures a second (fewer when the phone is warm), each kept until the estimator says where
        // the head was when it was taken.
        if (settings.roomScan && stereoRig != null) synchronized(roomLock) {
            if (roomHave && !roomReady && tNs - roomT > 400_000_000L) roomHave = false      // the estimator skipped that picture
            // warm: the room is looked at a third as often first (walls do not move); hands keep their rate
            if (!roomHave && tNs - roomLastNs >= 330_000_000L * (1 + 2 * throttle)) {
                System.arraycopy(left, 0, roomLeft, 0, left.size); System.arraycopy(right, 0, roomRight, 0, right.size)
                roomT = tNs; roomLastNs = tNs; roomHave = true; roomReady = false
            }
        }
        // Hands at 30 or 15 Hz: halved only when the phone is hot (thermal status severe), or by the battery saver.
        // Merely warm, they keep their rate: a pinch is two or three results long at 15 a second, and at half
        // that it is missed. (Until 19 November they were halved from "moderate" on.)
        val every = 1 + (if (throttle >= 2) 1 else 0) + if (settings.batterySaver) 1 else 0
        if (handsHandle != 0L && handsOn && pairIndex % every == 0L) synchronized(handLock) {
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
    /** The cameras' last four seconds, written with the estimator's states when position is lost (core/LossPictures). */
    private val lossPictures = dev.ultraxr.core.LossPictures()
    @Volatile private var lossDueNs = 0L; private var lossWhy = ""; private var lossSavedNs = 0L
    /** Called from the estimator's loop: 0.8 s after a loss was told, what led up to it goes to files/loss-pictures. */
    private fun saveLossIfDue(now: Long) {
        if (lossDueNs == 0L || now < lossDueNs) return
        lossDueNs = 0L
        val app = appContext ?: return; val why = lossWhy
        if (link?.replaying == true || (lossSavedNs != 0L && now - lossSavedNs < 10_000_000_000L)) return      // one set in ten seconds at most
        lossSavedNs = now
        thread(name = "loss-pictures", isDaemon = true) {
            try {
                val dir = File(app.getExternalFilesDir(null), "loss-pictures"); val name = "loss-" + System.currentTimeMillis()
                val n = lossPictures.write(File(dir, "$name.uxloss"), why); val s = trace.snapshot(File(dir, "$name.csv"), why)
                dev.ultraxr.core.LossPictures.prune(dir)
                log("6DoF loss: $n pairs of pictures and $s states saved as $name")
            } catch (e: Throwable) { log("6DoF loss: not saved: $e") }
        }
    }

    private fun vioLoop() {
        try { android.os.Process.setThreadPriority(workPriority) } catch (_: Throwable) {}
        val out = DoubleArray(27).also { it[22] = -1.0; it[23] = -1.0 }   // 22, 23: stereo matches, landmarks (-1 = not reported); 24 to 26: the accelerometer's bias
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
                if (out[20] != lastResetCount) {
                    if (lastResetCount >= 0) {
                        log("6DoF: the estimator began afresh (start " + out[20].toLong() + ", " + out[21].toLong() + " of them its own doing)")
                        head.onVioEpochReset(); slam.let { if (it != 0L) Native.slamNewEpoch(it) }
                        supervisor.onNewEpoch(now)
                        health.reset(); recovering = true; stableSinceNs = 0L; mapRestoreFix = null; mapSearching = true; synchronized(mapAlign) { mapAlign.restoreClear() }
                        if (seesAgainNs == 0L || now - seesAgainNs > 5_000_000_000L) seesAgainNs = now
                    }
                    lastResetCount = out[20]
                }
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
                    val counted = positionConfidence.update(now, vioLandmarks, vioStereoMatches)
                    // Is this state's position fit to be used at all (core/TrackingHealth)? Blind or flying: not from this
                    // very state on, and the count of good landmarks starts again from nothing.
                    val fit = !recoveryOn || (if (starveOn) health.update(out[0].toLong(), p, vel, vioFeatures, vioLandmarks, vioStereoMatches) else health.update(out[0].toLong(), p, vel, vioFeatures))
                    if (recoveryOn && !fit && health.why != dev.ultraxr.core.TrackingHealth.Why.SETTLING) {
                        if (counted || wasUsing) { val said = "6DoF: position unfit (" + (if (health.why == dev.ultraxr.core.TrackingHealth.Why.BLIND) "the cameras see nothing: $vioFeatures corners, $vioLandmarks landmarks, $vioStereoMatches seen by both" else "the estimator says the head flies") + "): held from this state on"; log(said); replayLog?.note(link?.replaySeconds ?: 0.0, said); if (lossDueNs == 0L) { lossWhy = said; lossDueNs = now + 800_000_000L } }
                        positionConfidence.reset()
                    }
                    val trusted = counted && fit
                    // the head at the moment of a picture the map search is waiting on: the estimator need not be in use
                    // yet, only see and not fly (two readings of the map must agree before anything is done with them)
                    // (and only an estimator that sees, on a head that is worn: lying on a table on 21 November it was
                    // started afresh every five seconds, and each time the map was handed its wanderings as places)
                    if (slam != 0L && (mapSearching || recovering) && (fit || health.why == dev.ultraxr.core.TrackingHealth.Why.SETTLING)
                        && !(holdWhenStill && stillness.still) && (!starveOn || (vioLandmarks >= dev.ultraxr.core.TrackingHealth.SEE_LANDMARKS && vioStereoMatches >= dev.ultraxr.core.TrackingHealth.SEE_STEREO))) synchronized(mapPicLock) {
                        if (mapPicHave && !mapPicReady && Math.abs(out[0].toLong() - mapPicT) < 6_000_000L) { mapPicP = p.copyOf(); mapPicQ = q.copyOf(); mapPicRestoring = recoveryOn && recovering; mapPicReady = true; mapPicLock.notifyAll() }
                    }
                    // Covered cameras, by replay (20 November 2026, night): one second after the pictures came back the
                    // estimator's position was believed again, while it was still flying off at up to 6 and 11 m/s from six
                    // seconds of the motion sensor alone; the world ended 6 m from where it belonged. With the switch on,
                    // an estimator that sees again after more than a second and a half without position is started afresh
                    // (it is going again a tenth of a second later, from rest) in place of being believed.
                    if (trusted) untrustedSinceNs = 0L else {
                        if (untrustedSinceNs == 0L) untrustedSinceNs = now
                        val seesAgain = vioLandmarks >= dev.ultraxr.core.PositionConfidence.GOOD_LANDMARKS && vioStereoMatches >= dev.ultraxr.core.PositionConfidence.GOOD_STEREO
                        if (!recoveryOn && restartAfterLoss && seesAgain && now - untrustedSinceNs > 1_500_000_000L && now - lossRestartNs > 3_000_000_000L) {
                            val away = (now - untrustedSinceNs) / 1e9; lossRestartNs = now; untrustedSinceNs = 0L; mapSearching = true
                            restartVio(v, "position was away for " + dev.ultraxr.core.Fmt.f(away, 1) + " s and the cameras see again: started afresh rather than believed")
                        }
                    }
                    if (trusted != wasTrusted) log(if (trusted) "6DoF position trusted ($vioLandmarks landmarks, $vioStereoMatches seen by both cameras)"
                        else "6DoF position not trusted ($vioLandmarks landmarks, $vioStereoMatches seen by both cameras): rotation only")
                    // Lying perfectly still (put down): the position is not changing, whatever the cameras make of
                    // the table top. Held exactly like an untrusted position, and re-aligned when motion returns.
                    val lying = holdWhenStill && stillness.still
                    var restarted = false
                    // After a loss the estimator is not carried on with: it is started afresh the moment it sees again.
                    if (recoveryOn && health.takeRestart() && now - lossRestartNs > 1_000_000_000L) {
                        lossRestartNs = now; seesAgainNs = now
                        restartVio(v, "the cameras see again after a loss: started afresh rather than believed"); restarted = true
                    }
                    // The estimator's idea of down is far from the accelerometer's (it began while the glasses were
                    // being picked up or turned over): the picture would hang tilted or upside down. Half a second
                    // of that and it is started afresh; meanwhile the head tracker keeps the accelerometer's tilt.
                    if (head.vioTiltBad > 15 && now - tiltRestartNs > 3_000_000_000L) {
                        tiltRestartNs = now; restartVio(v, "its down is " + dev.ultraxr.core.Fmt.f(head.vioTiltErrorDeg, 0) + " degrees from the accelerometer's"); restarted = true
                    }
                    if (head.tiltReseeds != saidReseeds) { saidReseeds = head.tiltReseeds; log("head: the tilt was far wrong and has been taken afresh from the accelerometer (" + saidReseeds + " times now)") }
                    if (now - tiltSaidNs > 10_000_000_000L) { tiltSaidNs = now; log("head tilt check: estimator " + dev.ultraxr.core.Fmt.f(head.vioTiltErrorDeg, 1) + " degrees from the accelerometer's down, the gyroscope's tilt " + dev.ultraxr.core.Fmt.f(head.imuTiltErrorDeg, 1)) }
                    if (lying != wasLying) {
                        wasLying = lying
                        if (lying) lyingSinceNs = now else lainNs = now - lyingSinceNs
                        log(if (lying) "6DoF: the glasses are lying still, position held" else "6DoF: moving again after " + dev.ultraxr.core.Fmt.f(lainNs / 1e9, 1) + " s")
                        if (lying) lyingAt = p.copyOf()
                        // Picked up again: if the estimator wandered while nothing moved, what it holds now is wrong
                        // (it has run away by metres in replays). Start it afresh rather than hand that to a game.
                        else if (lyingAt != null && dist(p, lyingAt!!) > LYING_WANDER_ON_PICKUP_M) { restartVio(v, "it had wandered " + dev.ultraxr.core.Fmt.f(dist(p, lyingAt!!), 2) + " m while the glasses lay still"); restarted = true }
                    } else if (lying && lyingAt != null && dist(p, lyingAt!!) > LYING_WANDER_M && now - lyingRestartNs > 5_000_000_000L) {
                        // Still lying there and the estimator is walking off: restart it now, so it cannot build up speed.
                        lyingRestartNs = now; restartVio(v, "wandering " + dev.ultraxr.core.Fmt.f(dist(p, lyingAt!!), 2) + " m with the glasses lying still"); lyingAt = null; restarted = true
                    } else if (lying && lyingAt == null) lyingAt = p.copyOf()
                    var use = trusted && !lying && !restarted
                    // A new world of the estimator's after a loss. If the saved map is held in this session, it is asked
                    // where this world lies BEFORE the position is used (the room's pictures go to it meanwhile), and the
                    // world is joined on there: the wearer is where the map has them, with nothing to glide or jump
                    // afterwards. Only if the map says nothing within a second and a half of the estimator being fit is
                    // the world joined on where the head was held, as before.
                    if (recoveryOn && recovering) {
                        val mapHeld = slam != 0L && appFromMap() != null
                        if (use) {
                            if (stableSinceNs == 0L) stableSinceNs = now
                            val target = mapRestoreFix
                            if (target != null) {
                                head.joinNextAt(target); mapRestoreFix = null; recovering = false; recoveries++
                                val said = "6DoF: position is back " + dev.ultraxr.core.Fmt.f((now - seesAgainNs) / 1e9, 1) + " s after the cameras saw again, where the saved map has the head"; log(said); replayLog?.note(link?.replaySeconds ?: 0.0, said)
                            } else if (mapHeld && now - stableSinceNs < MAP_FIRST_NS) use = false
                            else {
                                recovering = false; recoveries++
                                val said = "6DoF: position is back " + dev.ultraxr.core.Fmt.f((now - seesAgainNs) / 1e9, 1) + " s after the cameras saw again, joined on where the head was held" + (if (mapHeld) " (the map said nothing in time)" else " (no map is held)"); log(said); replayLog?.note(link?.replaySeconds ?: 0.0, said)
                            }
                        }
                    }
                    // Picked up and tracking again: while the glasses lay still their position was held at the table,
                    // and the way up to the face is not tracked (the estimator is starting afresh). So the world is
                    // recentred on the wearer once position has been good for a second, as when a game starts.
                    // Only after the glasses really lay there: a wearer who stands very still for a second or two
                    // can pass for glasses on a table, and recentring then would carry the whole world along with
                    // them every time they pause. Held still for a short while, nothing has moved and nothing is redone.
                    if (wasLying != lyingBefore && !lying && lainNs >= LYING_RECENTRE_AFTER_NS) recentreDueNs = -1L
                    lyingBefore = lying
                    if (recentreDueNs == -1L && use) recentreDueNs = now + 1_000_000_000L
                    if (recentreDueNs > 0L && !use) recentreDueNs = -1L
                    if (recentreDueNs > 0L && now >= recentreDueNs) {
                        recentreDueNs = 0L
                        log("6DoF: picked up and tracking again, view recentred")
                        val hook = onPickedUp; if (hook != null) hook() else recenter()
                    }
                    if (restarted) wasUsing = false
                    else if (supervisor.onState(now, out[0].toLong(), true, p, vel, vioFeatures)) { resetVio(v); wasUsing = false }
                    else if (use) {
                        head.onVio(out[0].toLong(), p, q, vel, doubleArrayOf(out[11], out[12], out[13]), doubleArrayOf(out[24], out[25], out[26])); wasUsing = true
                        // After more than two seconds without position the wearer may be somewhere else: what was
                        // found of the room is dropped (the floor's height is kept until a new one is found).
                        // Worn, 21 November 2026: position was away for 0.9 s as a game started, the head was held where it
                        // had been while its wearer straightened up, and the game's floor stood 50 cm too high and came
                        // back at 4 cm a second. Half a second without position is enough for the world to have shifted
                        // (it was two seconds), and the application is told to take the floor afresh ([roomRegains]).
                        if (roomUsedNs != 0L && now - roomUsedNs > ROOM_AWAY_NS) {
                            val headNow = head.appFromEstimator().apply(Q.rotate(HeadTracker.Z_TO_Y, p))[1]
                            room.regained(headNow); wallMapReset = true; roomFindings = room.analyse(headNow); roomRegains++
                            log("room: position was away for " + (now - roomUsedNs) / 1_000_000_000L + " s: surfaces forgotten, floor " + (if (floorY.isNaN()) "unknown again" else dev.ultraxr.core.Fmt.f(headNow - floorY, 2) + " m below the head as before"))
                            val planned = synchronized(planLock) {
                                if (plan == null) false else {
                                    if (planFit != null) { planBefore = planFit; planFit = null; planMaxShift = 0.0; planLostNs = now }
                                    if (planBefore != null) planMaxShift = minOf(6.0, planMaxShift + 1.5 * (now - roomUsedNs) / 1e9 + 0.3)
                                    true } }
                            if (planned) log("room: the marked room has to be found again; the world is put back on it then")
                            else {
                                val look = synchronized(recall) { if (recall.lost(now, (now - roomUsedNs) / 1e9)) recall.walls else 0 }
                                if (look > 0) log("room: " + look + " walls are remembered; the world is put back on them when they are found again")
                            }
                        }
                        roomUsedNs = now
                        // This state is the head at the moment of a picture the room is waiting on: hand both over.
                        synchronized(roomLock) {
                            if (roomHave && !roomReady && Math.abs(out[0].toLong() - roomT) < 6_000_000L) {
                                roomP = p; roomQ = q; roomFix = head.appFromEstimator(); roomWorld = head.worldVersion; roomMapOnly = false; roomReady = true; roomLock.notifyAll()
                            }
                        }
                    }
                    // Not used: the head keeps its last position and turns from the IMU. When it is used
                    // again the estimator's world is re-aligned to where the head was held.
                    else if (wasUsing) { head.onVioEpochReset(); wasUsing = false }
                    replayLog?.app(link?.replaySeconds ?: 0.0, head.predict(System.nanoTime()).pose.p, lying, use)
                } else if (supervisor.onState(now, 0, false, ZERO, ZERO, -1)) resetVio(v)
            }
            if (tick % 5 == 0) mapPartStep()
            saveLossIfDue(now)
            if (++tick % 100 == 0) {
                governor()
                val mode = head.predict(now).mode
                if (mode != lastMode) { lastMode = mode; log("head tracking: $mode"); notifyObservers() }
            }
        }
    }

    /** Whether the glasses are lying perfectly still, from the motion sensor (core/Stillness.kt). */
    val stillness = dev.ultraxr.core.StillnessDetector()
    /** Hold position while they are. On unless files/vio_options.txt says "hold_when_still=0" (for A/B replays). */
    @Volatile var holdWhenStill = true
    private var lyingSinceNs = 0L; private var lainNs = 0L
    private const val LYING_RECENTRE_AFTER_NS = 8_000_000_000L
    private var wasLying = false
    private var wasUsing = false
    private var lyingAt: DoubleArray? = null
    private var lyingBefore = false
    private var recentreDueNs = 0L
    /** Called when the glasses have been picked up again and position is tracked; a running XR session recentres itself. */
    @Volatile var onPickedUp: (() -> Unit)? = null
    private var lyingRestartNs = 0L; private var tiltRestartNs = 0L; private var tiltSaidNs = 0L; private var saidReseeds = 0
    private fun dist(a: DoubleArray, b: DoubleArray) = Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    private const val LYING_WANDER_M = 0.10
    private const val LYING_WANDER_ON_PICKUP_M = 0.03
    private const val ROOM_AWAY_NS = 500_000_000L
    /** How often what was found of the room was dropped because position had been away. */
    @Volatile var roomRegains = 0; private set

    /** Starts the position estimator afresh; the head keeps its place and the new estimate is aligned to it. */
    private fun restartVio(v: Long, why: String) {
        replayLog?.note(link?.replaySeconds ?: 0.0, "restart: $why")
        log("6DoF restarted: $why")
        positionConfidence.reset(); health.reset()
        Native.vioReset(v)
        head.onVioEpochReset()
    }

    /** Hand tracking settings under measurement (files/hand_options.txt). */
    @Volatile var handOptions = dev.ultraxr.core.HandOptions(); private set

    /**
     * Creates both trackers again, reading the option files afresh. For replays: the options of the next run are
     * picked up without ending the app's process (which Android would answer by switching the touchpad service off).
     */
    fun reloadTrackers() {
        val app = appContext ?: return
        val json = activeFactoryJson ?: try { File(app.filesDir, "factory_config.json").takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
        startTrackers(app, json)
    }

    /** Set while a recording is replayed: every position state and every hand result is written to CSV files. */
    @Volatile var replayLog: ReplayLog? = null

    // ------------------------------------------------------------ the saved map of the room (native/slam, core/MapAlign)
    // The estimator has no memory: every session, and every restart of it, begins a world of its own. RTAB-Map keeps
    // places (corners, their descriptors and 3D positions) in files/room_map.db. With no file a session makes one
    // from the room pictures and writes it when tracking stops; with one, the head is looked for in it, and once it
    // is found the app knows where the saved map lies in this session's world.
    private val slamLock = Any()
    @Volatile private var slam = 0L
    /** 0 making a map afresh, 1 finding the head in the saved one (it is not changed), 2 finding it and adding to the map. */
    @Volatile private var slamMode = 0
    private val slamFinding: Boolean get() = slamMode == 1
    private var slamResultsSeen = 0.0
    /** "find" in files/vio_options.txt ("room_map=find"): a saved map is only read, as until 20 November. */
    @Volatile private var roomMapReadOnly = false
    private var slamRig: String? = null
    private var mapTick = 0
    private var slamFoundSeen = -1.0; private var slamEpochSeen = -1.0; private var slamOpenedNs = 0L
    val mapAlign = dev.ultraxr.core.MapAlign(HeadTracker.Z_TO_Y)
    @Volatile var mapPlaces = 0; private set
    @Volatile var mapResults = 0L; private set
    @Volatile var mapFound = 0L; private set
    @Volatile var mapMs = 0.0; private set
    @Volatile private var mapFoundNs = 0L
    @Volatile private var mapShifting = false; @Volatile private var mapSmall = false
    /** Off when files/vio_options.txt says "room_map=off" (for A/B replays). */
    @Volatile private var roomMapOn = true
    /** The saved map's world (y up) in the app's world, once the head has been found in it this session (or while it is being made). */
    fun appFromMap(): Pose? = synchronized(mapAlign) { mapAlign.appFromMap }
    val mapVersion: Int get() = mapAlign.version
    /** "making", "finding" or "" for none. */
    val mapMode: String get() = if (slam == 0L) "" else when (slamMode) { 1 -> "finding"; 2 -> "growing"; else -> "making" }
    private fun mapLive() = mapFoundNs != 0L && System.nanoTime() - mapFoundNs < 10_000_000_000L
    private fun mapFile(): File? = appContext?.getExternalFilesDir(null)?.let { File(it, "room_map.db") }

    /** The calibration the estimator was given, as the map library reads it: a line a camera. */
    private fun slamRigOf(calib: String): String? = try {
        val v = JSONObject(calib).getJSONObject("value0"); val sb = StringBuilder()
        for (i in 0..1) {
            val k = v.getJSONArray("intrinsics").getJSONObject(i).getJSONObject("intrinsics"); val t = v.getJSONArray("T_imu_cam").getJSONObject(i)
            for (n in listOf("fx", "fy", "cx", "cy", "k1", "k2", "k3", "k4")) sb.append(k.getDouble(n)).append(' ')
            for (n in listOf("px", "py", "pz", "qx", "qy", "qz", "qw")) sb.append(t.getDouble(n)).append(' ')
            sb.append('\n')
        }
        sb.toString()
    } catch (e: Exception) { log("room map: the calibration could not be read for it: $e"); null }

    /** Opens the saved map to find the head in, or begins a new one when there is none ([fresh]: forget the one there). */
    private fun openRoomMap(fresh: Boolean): Unit = synchronized(slamLock) {
        val old = slam; slam = 0L
        if (old != 0L) Native.slamDestroy(old, false)
        synchronized(mapAlign) { mapAlign.clear() }
        slamFoundSeen = -1.0; slamEpochSeen = -1.0; mapFound = 0; mapResults = 0; mapFoundNs = 0L
        val rig = slamRig; val f = mapFile()
        if (!roomMapOn || rig == null || f == null || !settings.roomScan) return@synchronized
        if (fresh) { mapFreshCount++; try { File(f.parentFile, "kept_places.txt").delete() } catch (_: Exception) {} }
        if (fresh && f.isFile) f.delete()
        synchronized(roomMemory) {
            roomMemory.clear(); roomMemorySaid = false
            synchronized(meshLock) {
                roomMesh.clear(); meshDirty = false; furnitureInMap = emptyList(); roomMeshBlocks = 0
                if (fresh || !f.isFile) roomMeshFile()?.delete()
                else if (roomMeshOn) try { roomMeshFile()?.takeIf { it.isFile }?.let { mf -> java.io.DataInputStream(mf.inputStream().buffered(1 shl 16)).use { if (roomMesh.read(it)) { roomMeshBlocks = roomMesh.blockCount; log("room mesh: " + roomMesh.blockCount + " blocks are kept with the saved map") } } } } catch (e: Exception) { log("room mesh: not read: $e") }
            }
            if (fresh || !f.isFile) roomMemoryFile()?.delete()
            else if (roomMemory.read(try { roomMemoryFile()?.takeIf { it.isFile }?.readText() } catch (_: Exception) { null })) log("room memory: " + roomMemory.keptBefore + " walls and table tops are kept with the saved map; they are shown once the room is recognised")
        }
        val finding = f.isFile && f.length() > 0
        val mode = if (!finding) 0 else if (roomMapReadOnly) 1 else 2
        // (a map that is added to is added to in a copy, which takes the saved one's place when the session ends tied to it: native/slam)
        val h = try { Native.slamCreate(rig, f.absolutePath, mode, slamParams()) } catch (e: Throwable) { log("room map: not available: $e"); 0L }
        if (h == 0L) { log("room map: could not be opened: " + Native.lastError()); return@synchronized }
        val st = DoubleArray(18); Native.slamGetState(h, st); mapPlaces = st[13].toInt()
        // A saved map with no place in it can never be recognised, so nothing would ever be added to it (worn, 21
        // November 2026: three sessions ran on "a saved map of 0 places", and a loss had no map to come back by).
        if (finding && mapPlaces == 0 && !roomMapReadOnly) {
            Native.slamDestroy(h, false); log("room map: the saved map is empty; a new one is begun")
            openRoomMap(true); return@synchronized
        }
        slamMode = mode; slamResultsSeen = 0.0; slamOpenedNs = System.nanoTime(); slam = h
        log(if (finding) "room map: a saved map of " + mapPlaces + " places is open; looking for the head in it" + (if (mode == 2) ", and adding to it once it is found" else "") + " (" + Native.slamVersion() + ")" else "room map: none saved, making one from this session (" + Native.slamVersion() + ")")
    }

    /** The map library's settings: this app's own, then "slam.Key=value" lines of files/vio_options.txt (for A/B replays). */
    private fun slamParams(): String {
        val sb = StringBuilder(SLAM_DEFAULTS)
        try { for (l in File(appContext?.getExternalFilesDir(null), "vio_options.txt").takeIf { it.isFile }?.readLines().orEmpty()) if (l.startsWith("slam.")) sb.append(l.substring(5).trim()).append('\n') } catch (_: Exception) {}
        return sb.toString()
    }
    // Measured by replay on the S21 Ultra (20 November 2026, tools/tracking/map_cost.py): with OpenCV free to use
    // every core for the map's pictures the estimator took 46 to 48 ms a frame against 35 to 43 without a map; with one
    // OpenCV thread and the worker on the small cores, 36 to 38 ms, 29.9 states a second, and the hands no slower.
    private const val SLAM_DEFAULTS = "Ultra/Cores=little\nUltra/CvThreads=1\n"

    /** Counts the maps begun afresh in this run of the app: what was held in an older map's coordinates is void after a change. */
    @Volatile var mapFreshCount = 0; private set
    /** A place kept in the saved map's coordinates under [name] (x, y, z), for things that are to be where they were left after the app is started again. */
    fun keptPlace(name: String): DoubleArray? = try {
        File(appContext?.getExternalFilesDir(null), "kept_places.txt").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("$name ") }?.split(" ")?.drop(1)?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }?.toDoubleArray()
    } catch (_: Exception) { null }
    fun keepPlace(name: String, m: DoubleArray) {
        try {
            val f = File(appContext?.getExternalFilesDir(null) ?: return, "kept_places.txt")
            val others = if (f.isFile) f.readLines().filter { it.isNotBlank() && !it.startsWith("$name ") } else emptyList()
            f.writeText((others + (name + " " + m[0] + " " + m[1] + " " + m[2])).joinToString("\n") + "\n")
        } catch (e: Exception) { log("kept place not written: $e") }
    }

    /** Forgets the saved map and makes a new one from now on. */
    fun newRoomMap() { thread(name = "room-map", isDaemon = true) { openRoomMap(true) } }

    /**
     * Writes the map being made and goes on by finding the head in it. Called when tracking stops and when a replay
     * ends; takes a second or two. Nothing to do while a saved map is only being read.
     */
    fun saveRoomMap() = synchronized(slamLock) {
        val h = slam
        saveRoomMemory(); saveRoomMesh()
        if (h == 0L || slamFinding) return@synchronized
        val st = DoubleArray(18); Native.slamGetState(h, st); val places = st[13].toInt()
        // nothing new since it was opened or last written: leave it be
        if (st[0] < 1.0) return@synchronized
        slam = 0L
        if (slamMode == 0 && places < MAP_MIN_PLACES) {
            Native.slamDestroy(h, false)
            log("room map: only $places places were seen, too few to keep; a map is made next time")
        } else {
            val t0 = System.nanoTime(); Native.slamDestroy(h, true)
            log("room map: " + (if (slamMode == 2) "written with what this session added (unless it never saw the saved map again: then it is left as it was)" else "saved") + ", $places places, " + ((mapFile()?.length() ?: 0L) / 1024) + " kB, in " + (System.nanoTime() - t0) / 1_000_000 + " ms")
            // The same session goes on in the same world: the map is its own, so what is known of where it lies stays.
            val rig = slamRig; val f = mapFile()
            if (rig != null && f != null && f.isFile) {
                val mode = if (roomMapReadOnly) 1 else 2
                val n = try { Native.slamCreate(rig, f.absolutePath, mode, slamParams()) } catch (_: Throwable) { 0L }
                if (n != 0L) { slamMode = mode; slamResultsSeen = 0.0; slamFoundSeen = -1.0; slamEpochSeen = -1.0; slamOpenedNs = System.nanoTime(); slam = n }
            }
        }
    }
    /** The hand tracker's pictures as they come until the tone curve is measured through the whole tracker. */
    private const val HAND_TONE = 0.0
    private const val MAP_MIN_PLACES = 8
    private const val ROOM_NEW_AFTER = 60L
    @Volatile private var newRoomPending = false

    /** One room picture and the estimator's pose for it go to the map; what the map has found since is taken up. */
    private val mapPicLock = Object(); private val mapPicLeft = ByteArray(480 * 640); private val mapPicRight = ByteArray(480 * 640)
    private var mapPicT = 0L; private var mapPicLastNs = 0L; private var mapPicHave = false; private var mapPicReady = false
    private var mapPicP = DoubleArray(3); private var mapPicQ = DoubleArray(4); private var mapPicRestoring = false
    private var mapSaidResults = -1.0

    /** The search for the head in the saved map, on a thread of its own (see [onStereo]). While a new world of the
     *  estimator's waits to be joined on, what the map says is read here too; otherwise the pictures are only handed
     *  over, and the room's loop reads the answers as it always did (it moves the room, which is its own to move). */
    private fun mapSearchLoop() {
        val l = ByteArray(480 * 640); val r = ByteArray(480 * 640)
        while (running) {
            var p: DoubleArray; var q: DoubleArray; var t: Long; var restoring: Boolean
            synchronized(mapPicLock) {
                while (running && !mapPicReady) try { mapPicLock.wait(500) } catch (_: InterruptedException) {}
                if (!running) return
                System.arraycopy(mapPicLeft, 0, l, 0, l.size); System.arraycopy(mapPicRight, 0, r, 0, r.size)
                p = mapPicP; q = mapPicQ; t = mapPicT; restoring = mapPicRestoring; mapPicHave = false; mapPicReady = false
            }
            try {
                if (restoring && recovering) mapStep(t, l, r, p, q, true)
                else synchronized(slamLock) { val h = slam; if (h != 0L) Native.slamFeed(h, t, l, r, 480, 640, doubleArrayOf(p[0], p[1], p[2], q[0], q[1], q[2], q[3])) }
            } catch (e: Throwable) { log("room map: $e") }
        }
    }

    private fun mapStep(tNs: Long, l: ByteArray, r: ByteArray, p: DoubleArray, q: DoubleArray, restoring: Boolean = false) {
        val h = slam; if (h == 0L) return
        val st = DoubleArray(18)
        synchronized(slamLock) {
            if (slam != h) return
            // Every room picture (three a second) until the head is found in a saved map; after that one in three, and
            // one in two while a map is made: places are a step apart, and the estimator needs the processor more.
            val every = if (mapSearching) 1 else when (slamMode) { 1 -> if (mapAlign.version == 0) 1 else 3; 2 -> if (mapFoundNs == 0L) 1 else 3; else -> 2 }
            if (mapTick++ % every == 0) Native.slamFeed(h, tNs, l, r, 480, 640, doubleArrayOf(p[0], p[1], p[2], q[0], q[1], q[2], q[3]))
            Native.slamGetState(h, st)
        }
        mapPlaces = st[13].toInt(); mapResults = st[0].toLong(); mapMs = st[16]
        // the estimator began a new world: where the head is in the map is asked with every picture until it is known again
        if (st[17] != slamEpochSeen) { if (slamEpochSeen >= 0) mapSearching = true; slamEpochSeen = st[17]; slamFoundSeen = 0.0 }
        // making a map, every result is a reading (the map is this session's own world, bent a little where a place
        // was seen again); finding, only a pair that was recognised is one
        if (st[17] != slamEpochSeen) slamResultsSeen = 0.0
        // finding, a pair that was recognised is a reading; making or adding, every pair worked through is one while
        // the newest place hangs together with the map (the map is then this session's own world, bent where a place
        // was seen again)
        val reading = st[5] > 0.5 && (if (slamFinding) st[1] > slamFoundSeen else st[0] > slamResultsSeen)
        slamResultsSeen = st[0]
        if (st[1] > slamFoundSeen) { mapFound += (st[1] - slamFoundSeen).toLong(); if (slamFinding) mapFoundNs = System.nanoTime() }
        if (slamMode == 2 && reading) mapFoundNs = System.nanoTime()
        slamFoundSeen = st[1]
        // A saved map of some other room (or of this one in another light) is never recognised, and a session that never
        // recognises it adds nothing to it: worn on 23 November 2026, 199 pictures in 85 seconds were looked at against a map
        // of 187 places and none was recognised, as in every replay of the recordings of 22 and 23 November, while replays of
        // the 17, 18 and 21 November recordings found the head in it within 4.6 to 6.2 seconds. So after $ROOM_NEW_AFTER pictures
        // with none recognised this is taken to be a room not yet scanned: the saved map, surfaces and mesh are kept once
        // over as *.prev and a new map is begun from here.
        if (slamMode == 2 && mapFound == 0L && mapResults >= ROOM_NEW_AFTER && !roomMapReadOnly && !newRoomPending) {
            newRoomPending = true
            log("room map: " + mapResults + " pictures and the saved map of " + mapPlaces + " places was not recognised; it is kept as room_map.db.prev and this room is scanned afresh")
            thread(name = "room-map", isDaemon = true) {
                try { for (f in listOf(mapFile(), roomMemoryFile(), roomMeshFile())) if (f != null && f.isFile) f.copyTo(File(f.path + ".prev"), overwrite = true) } catch (e: Exception) { log("room map: the old one could not be kept: " + e) }
                try { openRoomMap(true) } finally { newRoomPending = false }
            }
            return
        }
        // for the log of a worn session: what the map is doing, every ten seconds
        val nowNs = System.nanoTime()
        if (nowNs - mapSaidNs > 10_000_000_000L) {
            mapSaidNs = nowNs
            log("room map: " + mapMode + ", " + mapPlaces + " places, " + mapResults + " pictures looked at, " + mapFound + " recognised" +
                (if (mapFoundNs != 0L) " (last " + dev.ultraxr.core.Fmt.f((nowNs - mapFoundNs) / 1e9, 1) + " s ago)" else "") + ", a picture takes " + dev.ultraxr.core.Fmt.f(mapMs, 0) + " ms; " +
                (if (mapAlign.version == 0) "the map's place in this session is not known" + (if (mapAlign.unsure > 0) " (" + mapAlign.unsure + " readings without enough agreement)" else "")
                 else "last seen " + dev.ultraxr.core.Fmt.f(mapAlign.lastOffM * 100, 1) + " cm and " + dev.ultraxr.core.Fmt.f(mapAlign.lastOffDeg, 2) + " degrees from where it is held; " + mapSmallSteps + " small steps (" + dev.ultraxr.core.Fmt.f(mapStepSumM * 100, 0) + " cm in all), " + mapBigMoves + " large ones put back (" + (if (mapGlide) dev.ultraxr.core.Fmt.f(mapGlideParts * 0.025, 1) + " s of gliding" else "at once") + ")") +
                "; estimator " + dev.ultraxr.core.Fmt.f(vioProcessMs, 0) + " ms a frame; losses " + health.losses + " (" + health.blindStates + " states blind, " + health.flyingStates + " flying), " + recoveries + " recoveries, " + head.joinsFromMap + " of them from the map; room: " + synchronized(roomMemory) { roomMemory.kept.size.toString() + " surfaces kept, " + roomMemory.dropped + " dropped" } +
                ", mesh " + roomMeshBlocks + " blocks (" + dev.ultraxr.core.Fmt.f(roomMeshMs, 0) + " ms a picture), " + furnitureInMap.size + " pieces of furniture")
        }
        if (restoring && st[0] != mapSaidResults) { mapSaidResults = st[0]; replayLog?.note(link?.replaySeconds ?: 0.0, "room map, before the world is joined on: picture " + st[0].toInt() + " of this world, place " + st[14].toInt() + ", " + st[15].toInt() + " points agree, " + st[16].toInt() + " ms" + (if (st[5] > 0.5) "" else ", no place in the map from it")) }
        if (!reading) return
        // while the world glides back nothing new is asked of the map: what it says now is of a world on its way
        if (restoring) {
            // the estimator's new world is not in use yet: this reading says where it must be joined on
            val corrNew = Pose(doubleArrayOf(st[6], st[7], st[8]), doubleArrayOf(st[9], st[10], st[11], st[12]))
            val target = synchronized(mapAlign) { mapAlign.restoreReading(corrNew, p) }
            if (target != null) { mapRestoreFix = target; mapSearching = false; replayLog?.note(link?.replaySeconds ?: 0.0, "room map: the head is found again in the saved map (place " + st[14].toInt() + ", " + st[15].toInt() + " points agree)") }
            return
        }
        mapSearching = false
        if (mapGliding) return
        val corr = Pose(doubleArrayOf(st[6], st[7], st[8]), doubleArrayOf(st[9], st[10], st[11], st[12]))
        val before = mapAlign.version
        val move = synchronized(mapAlign) { mapAlign.onReading(head.appFromEstimator(), corr) }
        val t = link?.replaySeconds ?: 0.0
        if (before == 0 && mapAlign.version == 1) {
            val said = if (slamMode != 0) "room map: the head is found in the saved map " + dev.ultraxr.core.Fmt.f((System.nanoTime() - slamOpenedNs) / 1e9, 1) + " s after it was opened (place " + st[14].toInt() + ", " + st[15].toInt() + " points agree)"
                else "room map: this session's world is the map's"
            log(said); replayLog?.note(t, said)
        }
        val am = appFromMap()
        if (am != null) replayLog?.note(t, String.format(java.util.Locale.US, (if (slamMode != 0) "found " else "making ") + "map-in-app %.4f %.4f %.4f yaw %.3f off %.3f m %.2f deg place %d inliers %d ms %.0f", am.p[0], am.p[1], am.p[2], Math.toDegrees(Q.yawY(am.q)), mapAlign.lastOffM, mapAlign.lastOffDeg, st[14].toInt(), st[15].toInt(), st[16]))
        if (move != null) {
            val big = mapAlign.version != before
            if (big) {
                val said = "room map: the world was " + dev.ultraxr.core.Fmt.f(mapAlign.lastOffM, 2) + " m and " + dev.ultraxr.core.Fmt.f(mapAlign.lastOffDeg, 1) + " degrees from where the map has it, and is put back"; log(said); replayLog?.note(t, said)
                mapBigMoves++
                // Not at once either (until 20 November, late, it was: a jump of a metre). The world glides back at no
                // more than half a metre and twenty degrees a second, everything on an arc to where it belongs: plainly
                // seen, but nothing leaps. What the room finder holds is put where the map has it now, in one go.
                // (Built and unit-tested, not yet measured on the phone: on only with "map_glide=1" in vio_options.txt.)
                // Measured once by replay (a push of 1 m and 25 degrees: glided back in 2.3 s, within 2 cm of the map 6 s
                // after the push). Beyond a metre and a half nothing shown is anywhere near where it belongs, and a glide
                // would take many seconds (measured: 14 s for 6 m after the cameras were covered): that is put back at once.
                val headNow = head.predict(System.nanoTime()).pose.p
                if (!mapGlide || mapAlign.lastOffM > MAP_GLIDE_MOST_M) synchronized(mapMoveLock) { mapPart = null; mapPartsLeft = 0; mapSmall = false; mapShifting = true; try { head.moveWorld(move) } finally { mapShifting = false } }
                else synchronized(mapMoveLock) {
                    // slices are sized by how far they carry the wearer's own head, not the world's origin
                    val n = dev.ultraxr.core.MapAlign.slices(move, headNow, MAP_GLIDE_PART_M, MAP_GLIDE_PART_RAD, MAP_STEP_PARTS, 600)
                    room.transform(move); wallMapReset = true; roomFindings = room.analyse(0.0); head.bumpWorld()
                    mapPart = dev.ultraxr.core.MapAlign.slice(move, n); mapPartsLeft = n; mapGliding = true; mapGlideParts += n
                }
            } else synchronized(mapMoveLock) {
                // a small step is not made at once but in parts over a third of a second (the estimator's loop makes them)
                mapPart = dev.ultraxr.core.MapAlign.slice(move, MAP_STEP_PARTS); mapPartsLeft = MAP_STEP_PARTS; mapGliding = false
                mapSmallSteps++; mapStepSumM += (mapAlign.lastOffM * dev.ultraxr.core.MapAlign.STEP_SHARE).coerceIn(dev.ultraxr.core.MapAlign.STEP_M, dev.ultraxr.core.MapAlign.STEP_MAX_M).coerceAtMost(mapAlign.lastOffM)
            }
        }
        // A test of the correction (files/vio_options.txt, "map_test_slip=T,dx,dz,yaw degrees"): at second T of a replay
        // the app's world is pushed aside as a wrong join after a loss of position would push it, and nobody is told.
        val slip = mapTestSlip
        if (slip != null && !mapSlipDone && t >= slip[0] && mapAlign.version > 0) {
            mapSlipDone = true
            val said = "map test: the world is pushed " + slip[1] + " m, " + slip[2] + " m and " + slip[3] + " degrees aside now"; log(said); replayLog?.note(t, said)
            val q = Q.axisAngle(0.0, 1.0, 0.0, Math.toRadians(slip[3])); val near = head.appFromEstimator().p; val r = Q.rotate(q, near)
            synchronized(mapMoveLock) { mapSmall = false; mapShifting = true; mapSilent = true; try { head.moveWorld(Pose(doubleArrayOf(near[0] + slip[1] - r[0], near[1] - r[1], near[2] + slip[2] - r[2]), q)) } finally { mapShifting = false; mapSilent = false } }
        }
    }
    private val mapMoveLock = Any()
    @Volatile private var mapSilent = false      // the test's push: the aligner is not told, as it is not told of a wrong join
    private var mapPart: Pose? = null; private var mapPartsLeft = 0
    private var mapSaidNs = 0L
    private const val MAP_STEP_PARTS = 12
    private const val MAP_GLIDE_PART_M = 0.0125      // a part every 25 ms: half a metre a second
    private val MAP_GLIDE_PART_RAD = Math.toRadians(0.5)
    @Volatile private var mapGliding = false
    @Volatile private var mapGlide = true
    private const val MAP_GLIDE_MOST_M = 1.5
    /** After position was away the estimator is started afresh when it sees again ("restart_after_loss=1"): built on what the covered-camera replays showed, not yet measured. */
    @Volatile private var restartAfterLoss = false
    private var untrustedSinceNs = 0L; private var lossRestartNs = 0L
    @Volatile private var mapSearching = false
    // ---- recovery after a loss (core/TrackingHealth, MapAlign.restoreReading). "recovery=0" in vio_options.txt is the way
    // of before 21 November 2026: position believed again a second after enough landmarks are seen, whatever it says.
    val health = dev.ultraxr.core.TrackingHealth()
    @Volatile private var recoveryOn = true
    @Volatile private var recovering = false
    private var stableSinceNs = 0L; private var seesAgainNs = 0L
    @Volatile private var mapRestoreFix: Pose? = null
    @Volatile private var roomMapOnly = false
    @Volatile var recoveries = 0; private set
    private const val MAP_FIRST_NS = 1_500_000_000L
    /** The mesh kept with the map, furniture, and dropping of kept surfaces seen through: on with "room_mesh=1" in vio_options.txt until its cost on the phone is measured. */
    @Volatile private var roomMeshOn = false
    @Volatile var mapGlideParts = 0; private set
    @Volatile private var mapTestSlip: DoubleArray? = null; private var mapSlipDone = false
    /** For the diagnostics: corrections of the world against the saved map so far in this session. */
    @Volatile var mapBigMoves = 0; private set
    @Volatile var mapSmallSteps = 0; private set
    @Volatile var mapStepSumM = 0.0; private set
    /** One part of a small step towards the saved map, if one is due. Called from the estimator's loop every 25 ms. */
    private fun mapPartStep() {
        if (mapPartsLeft <= 0) return
        synchronized(mapMoveLock) {
            val p = mapPart ?: return
            if (mapPartsLeft-- <= 0) return
            mapSmall = true; mapShifting = true
            try { head.moveWorld(p, quiet = true) } finally { mapShifting = false; mapSmall = false }
            if (mapPartsLeft == 0 && mapGliding) { mapGliding = false; head.bumpWorld(); replayLog?.note(link?.replaySeconds ?: 0.0, "room map: the world is where the map has it again") }
        }
    }

    // ---- the room's surfaces kept with the map (core/RoomMemory): files/room_surfaces.txt, in the map's coordinates
    val roomMemory = dev.ultraxr.core.RoomMemory()
    private var roomMemorySavedNs = 0L; private var roomMemorySaid = false
    private fun roomMemoryFile(): File? = appContext?.getExternalFilesDir(null)?.let { File(it, "room_surfaces.txt") }
    /** What games and pages are shown of the room: what this session found and what is kept from earlier ones, with how far each is to be believed. */
    fun roomShown(): Pair<dev.ultraxr.core.RoomFindings?, List<Double>> = synchronized(roomMemory) { roomMemory.shown(appFromMap(), roomFindings) }
    private fun roomMemoryStep(f: dev.ultraxr.core.RoomFindings) {
        val am = appFromMap() ?: return
        synchronized(roomMemory) {
            roomMemory.see(am, f)
            if (!roomMemorySaid && roomMemory.keptBefore > 0) {
                roomMemorySaid = true
                val said = "room memory: " + roomMemory.keptBefore + " walls and table tops" + (if (roomMemory.keptFloorInMap.isNaN()) "" else " and the floor") + " are back from the saved room"
                log(said); replayLog?.note(link?.replaySeconds ?: 0.0, said)
            }
            val t = link?.replaySeconds ?: 0.0
            if (replayLog != null && roomMemory.keptBefore > 0) {
                val apart = roomMemory.agreement(am, f).sorted()
                if (apart.isNotEmpty()) replayLog?.note(t, String.format(java.util.Locale.US, "room-agree n %d median %.3f worst %.3f live %d", apart.size, apart[apart.size / 2], apart.last(), f.surfaces.count { it.kind != dev.ultraxr.core.RoomSurface.Kind.FLOOR }))
            }
            val now = System.nanoTime()
            if (roomMemory.changed && now - roomMemorySavedNs > 20_000_000_000L) { roomMemorySavedNs = now; saveRoomMemory() }
        }
    }
    // ---- the room as a surface, kept with the map (core/RoomMesh in the map's coordinates): files/room_mesh.bin.
    // Every depth picture is added while the map's place is known; being in map coordinates it needs no moving when the
    // world is corrected, and it is there again when the room is recognised. Furniture is found in it (core/Furniture),
    // and kept walls and table tops the pictures see through are dropped (RoomMemory.look).
    private val roomMesh = dev.ultraxr.core.RoomMesh()
    private val meshLock = Any()
    private var meshSavedNs = 0L; private var meshDirty = false; private var furnitureNs = 0L
    @Volatile var roomMeshMs = 0.0; private set
    @Volatile var roomMeshBlocks = 0; private set
    /** Things standing in the room, in the map's coordinates. */
    @Volatile var furnitureInMap: List<dev.ultraxr.core.FurnitureBox> = emptyList(); private set
    private fun roomMeshFile(): File? = appContext?.getExternalFilesDir(null)?.let { File(it, "room_mesh.bin") }
    private fun roomMeshStep(ds: dev.ultraxr.core.DenseStereo, cam: Pose) {
        if (!roomMeshOn) return
        val am = appFromMap() ?: return
        val camInMap = am.inverse() * cam; val t0 = System.nanoTime()
        synchronized(meshLock) { roomMesh.integrate(ds, camInMap); meshDirty = true; roomMeshBlocks = roomMesh.blockCount }
        val gone = synchronized(roomMemory) { roomMemory.look(camInMap.inverse()) { q -> ds.rangeToward(q) } }
        if (gone > 0) { val said = "room memory: $gone kept surfaces are not there any more (the cameras see through where they stood) and are dropped"; log(said); replayLog?.note(link?.replaySeconds ?: 0.0, said) }
        val now = System.nanoTime()
        if (now - furnitureNs > 5_000_000_000L) {
            furnitureNs = now
            val floor = roomFindings?.floorY?.takeIf { !it.isNaN() }?.let { it - am.p[1] } ?: synchronized(roomMemory) { roomMemory.keptFloorInMap }
            val walls = synchronized(roomMemory) { roomMemory.kept }
            val cells = synchronized(meshLock) { roomMesh.cells(weight = dev.ultraxr.core.Furniture.CELL_WEIGHT, band = dev.ultraxr.core.Furniture.CELL_BAND) }
            val found = dev.ultraxr.core.Furniture.detect(cells, floor, walls)
            if (found.size != furnitureInMap.size) replayLog?.note(link?.replaySeconds ?: 0.0, "furniture: " + found.size + " things: " + found.take(8).joinToString("; ") { String.format(java.util.Locale.US, "%s %.2fx%.2f h%.2f", it.label, it.hx * 2, it.hz * 2, it.hy * 2) })
            furnitureInMap = found
        }
        roomMeshMs = (System.nanoTime() - t0) / 1e6
        if (meshDirty && now - meshSavedNs > 120_000_000_000L) saveRoomMesh()
    }
    private fun saveRoomMesh() {
        val f = roomMeshFile() ?: return
        try {
            val bytes = java.io.ByteArrayOutputStream(1 shl 20)
            synchronized(meshLock) { if (!meshDirty) return; roomMesh.write(java.io.DataOutputStream(bytes)); meshDirty = false; meshSavedNs = System.nanoTime() }
            val tmp = File(f.parentFile, f.name + ".new"); tmp.writeBytes(bytes.toByteArray()); tmp.renameTo(f)
        } catch (e: Exception) { log("room mesh: not written: $e") }
    }
    /** Furniture in the app's world, once the map's place is known. */
    fun furnitureShown(): List<dev.ultraxr.core.FurnitureBox> {
        val am = appFromMap() ?: return emptyList(); val turn = Q.yawY(am.q)
        return furnitureInMap.map { b -> val c = am.apply(doubleArrayOf(b.cx, b.cy, b.cz)); dev.ultraxr.core.FurnitureBox(c[0], c[1], c[2], b.yaw + turn, b.hx, b.hy, b.hz, b.label, b.cells, b.confidence) }
    }
    /** The room's surface as triangles in the app's world (nine numbers each), at most [max]; empty until the map's place is known. */
    fun roomMeshTriangles(max: Int = 60_000): FloatArray {
        val am = appFromMap() ?: return FloatArray(0)
        val t = synchronized(meshLock) { roomMesh.triangles(max) }; val v = DoubleArray(3)
        var i = 0; while (i + 2 < t.size) { v[0] = t[i].toDouble(); v[1] = t[i + 1].toDouble(); v[2] = t[i + 2].toDouble(); val w = am.apply(v); t[i] = w[0].toFloat(); t[i + 1] = w[1].toFloat(); t[i + 2] = w[2].toFloat(); i += 3 }
        return t
    }

    private fun saveRoomMemory() = synchronized(roomMemory) {
        if (!roomMemory.changed && roomMemoryFile()?.isFile == true) return@synchronized
        try { val f = roomMemoryFile() ?: return@synchronized; val tmp = File(f.parentFile, f.name + ".new"); tmp.writeText(roomMemory.write()); tmp.renameTo(f) } catch (e: Exception) { log("room memory: not written: $e") }
    }

    /** Replays [file] through the trackers and writes what they report next to it in [outDir]. */
    fun replay(file: File, outDir: File, tag: String, onDone: (String) -> Unit) {
        val l = link ?: run { onDone("tracking has not been started"); return }
        val rl = try { ReplayLog(outDir, tag) } catch (e: Exception) { onDone("cannot write the replay log: $e"); return }
        replayLog = rl
        positionConfidence.reset()
        thread(name = "replay-render", isDaemon = true) {      // stands in for the renderer: the head 60 times a second, on the recording's own clock
            val t0 = System.nanoTime(); var n = 0L
            head.probeOn = true
        head.onState = { tNs, at -> try { rl.state((tNs - t0) / 1e9, at, head.carryNote) } catch (_: Throwable) {} }
            while (replayLog === rl) {
                val now = System.nanoTime(); val s = head.predict(now)
                try { rl.render((now - t0) / 1e9, s.pose.p, s.pose.q, s.mode == HeadTracker.Mode.SIX_DOF) } catch (_: Throwable) { break }
                n++; val wait = t0 + n * 16_666_667L - System.nanoTime(); if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait)
            }
        }
        l.replay(file) { note -> try { saveRoomMap() } catch (e: Throwable) { log("room map: not saved: $e") }; replayLog = null; rl.close(note); onDone(note) }
    }

    private fun resetVio(v: Long) {
        replayLog?.note(link?.replaySeconds ?: 0.0, "reset: " + supervisor.lastReason)
        log("6DoF reset: ${supervisor.lastReason}")
        positionConfidence.reset()
        trace.dump(appContext, supervisor.lastReason)?.let { log("6DoF trace: $it") }
        Native.vioReset(v)
        head.onVioEpochReset()
    }

    /** Places what one picture pair shows in the room model, and looks the model over for the floor and surfaces. */
    private fun roomLoop() {
        val l = ByteArray(480 * 640); val r = ByteArray(480 * 640)
        val pts = FloatArray(3 * 300); val world = FloatArray(3 * 300)
        var finder: dev.ultraxr.core.StereoPoints? = null; var finderRig: dev.ultraxr.core.StereoRig? = null
        var frame = 0; var saidFloor = Double.NaN
        var calibratedThisRun = false; var calibCheckFrame = 0
        var dense: dev.ultraxr.core.DenseStereo? = null; val wallMap = dev.ultraxr.core.WallMap(); var denseWallsNow: List<dev.ultraxr.core.RoomSurface> = emptyList(); var saidWalls = -1
        val ranges = DoubleArray(300)
        while (running) {
            var p: DoubleArray; var q: DoubleArray; var fix: Pose; var version: Int; var tPic: Long; var mapOnly: Boolean
            synchronized(roomLock) {
                while (running && !roomReady) try { roomLock.wait(500) } catch (_: InterruptedException) {}
                if (!running) return
                System.arraycopy(roomLeft, 0, l, 0, l.size); System.arraycopy(roomRight, 0, r, 0, r.size)
                p = roomP; q = roomQ; fix = roomFix; version = roomWorld; roomHave = false; roomReady = false; tPic = roomT; mapOnly = roomMapOnly
            }
            try { mapStep(tPic, l, r, p, q, mapOnly) } catch (e: Throwable) { log("room map: $e") }
            if (mapOnly) continue      // the estimator's position is not in use yet: nothing of this picture is put into the room
            val rig = stereoRig ?: continue
            if (rig !== finderRig) {
                finder = dev.ultraxr.core.StereoPoints(rig); finderRig = rig
                val a = rig.cam0InImu.p; val b = rig.cam1InImu.p
                dense = dev.ultraxr.core.DenseStereo(rig); wallMap.clear(); denseWallsNow = emptyList()
                floorCalibrator = dev.ultraxr.core.FloorCalibrator(Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])))
                calibratedThisRun = false
            }
            try {
                val t0 = System.nanoTime()
                val n = finder!!.find(l, r, pts)
                roomFindMs = (System.nanoTime() - t0) / 1e6
                // camera 0 in the app's world: recentre fix * the estimator's body pose (turned y-up) * camera in body
                val cam = fix * Pose(Q.rotate(HeadTracker.Z_TO_Y, p), Q.mul(HeadTracker.Z_TO_Y, q)) * rig.cam0InImu
                var k = 0; val v = DoubleArray(3)
                for (i in 0 until n) {
                    v[0] = pts[i * 3].toDouble(); v[1] = pts[i * 3 + 1].toDouble(); v[2] = pts[i * 3 + 2].toDouble()
                    if (v[0] * v[0] + v[1] * v[1] + v[2] * v[2] > ROOM_MAX_RANGE_M * ROOM_MAX_RANGE_M) continue      // too far to place well
                    val w = cam.apply(v)
                    ranges[k] = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                    world[k * 3] = w[0].toFloat(); world[k * 3 + 1] = w[1].toFloat(); world[k * 3 + 2] = w[2].toFloat(); k++
                }
                if (version != head.worldVersion) continue           // recentred meanwhile: these points are in the old world
                room.expectedBelowHead = settings.standingHeightM.toDouble()
                room.add(world, k, frame++); roomPictures++
                roomPoints = room.lastingPoints(cam.p[0], cam.p[1], cam.p[2]); roomPointsNs = System.nanoTime()
                run {
                    val fc0 = floorCalibrator; val fy0 = roomFindings?.floorY ?: Double.NaN
                    if (fc0 != null && !fy0.isNaN() && (roomFindings?.floorSupport ?: 0) >= 60) { for (i in 0 until k) fc0.add(ranges[i], world[i * 3 + 1] - fy0); fc0.addCameraHeight(cam.p[1] - fy0) }
                }
                // Dense depth, about once a second: every measured place between knee and head height goes onto a floor
                // plan, and the straight bright lines on it are the walls (core/WallMap). Far more of a wall is found
                // this way than from corners alone, and it stays: the plan remembers.
                if (wallMapReset) { wallMapReset = false; wallMap.clear(); denseWallsNow = emptyList() }
                val dsNow = dense; val floorNow = roomFindings?.floorY ?: Double.NaN
                if (denseWalls && dsNow != null && !floorNow.isNaN() && frame % 3 == 0) {
                    val td = System.nanoTime()
                    dsNow.compute(l, r); wallMap.add(dsNow, cam, floorNow)
                    try { roomMeshStep(dsNow, cam) } catch (e: Throwable) { log("room mesh: $e") }
                    if (wallMap.pictures % 2 == 0) {
                        val found = wallMap.walls()
                        // Position had been lost: the walls say how far the world slipped meanwhile, and it is put back.
                        val lines = found.map { dev.ultraxr.core.RoomRecall.Line(it.x0, it.z0, it.x1, it.z1) }
                        // The marked room: placed in this world from the walls and table tops found, and after a loss of
                        // position the world is moved (turned and shifted) so that it lies where it lay before.
                        var planMove: Pose? = null; var planSaid: String? = null; var planDrift = false
                        synchronized(planLock) {
                            val pl = plan; val placed = planFit
                            if (pl != null && placed != null && lines.size >= 2) {
                                // The plan is placed: it is the map. The position estimator has no map of its own and
                                // wanders as the wearer walks (worn on 19 November: walls that matched were "completely
                                // off" after a walk, with no loss of position logged). The walls found in the last few
                                // seconds are laid against the plan near where it is believed to lie; what they are off
                                // by is how far the world has wandered, and most of that is taken out at once.
                                planTried++
                                val fit = pl.fit(lines, emptyList(), near = placed, yawWithin = 0.17, maxShift = 1.2)
                                if (fit != null) {
                                    val mv = placed.worldMoveFrom(fit)
                                    val far = Math.sqrt(mv.p[0] * mv.p[0] + mv.p[2] * mv.p[2]); val turn = 2 * Math.atan2(mv.q[1], mv.q[3])
                                    planAgreed++
                                    if (far > 0.05 || Math.abs(turn) > 0.017) {
                                        planMove = Pose(doubleArrayOf(mv.p[0] * 0.7, 0.0, mv.p[2] * 0.7), Q.axisAngle(0.0, 1.0, 0.0, turn * 0.7)); planDrift = true
                                        planCorrected++; planCorrectedM += far
                                    }
                                }
                                // a wall plan that is some seconds old was drawn in a world that has wandered since: begun afresh
                                if (wallMap.pictures >= 12) wallMapReset = true
                                val nowNs = System.nanoTime()
                                if (nowNs - planSaidNs > 20_000_000_000L) { planSaidNs = nowNs
                                    planSaid = "room: kept on the marked room: " + planTried + " looks at the walls, " + planAgreed + " could be laid on the plan, " + planCorrected + " corrections, " + dev.ultraxr.core.Fmt.f(planCorrectedM, 2) + " m in all" }
                            }
                            if (pl != null && planFit == null) {
                                val fy = roomFindings?.floorY ?: Double.NaN
                                val tablesNow = roomFindings?.surfaces?.filter { it.kind == dev.ultraxr.core.RoomSurface.Kind.TABLE && !fy.isNaN() }?.map { doubleArrayOf(it.cx, it.cz, it.cy - fy) } ?: emptyList()
                                val before = planBefore
                                if (before != null && System.nanoTime() - planLostNs > 120_000_000_000L) { planBefore = null; planSaid = "room: the marked room was not found again within two minutes where it should be; it is looked for anywhere now (things placed before may have moved)" }
                                val fit = pl.fit(lines, tablesNow, near = planBefore, maxShift = if (planBefore != null) planMaxShift else 1e9)
                                // what the cameras have to go on, written down now and then while the plan is not placed: a fit that
                                // does not come can then be looked into from the log alone
                                val nowNs = System.nanoTime()
                                if (fit == null && lines.size >= 2 && nowNs - planSaidNs > 8_000_000_000L) { planSaidNs = nowNs
                                    planSaid = "room: the marked room is not placed yet; walls found now: " + lines.joinToString("; ") { dev.ultraxr.core.Fmt.f(it.x0, 2) + "," + dev.ultraxr.core.Fmt.f(it.z0, 2) + " to " + dev.ultraxr.core.Fmt.f(it.x1, 2) + "," + dev.ultraxr.core.Fmt.f(it.z1, 2) } + " | table tops: " + tablesNow.joinToString("; ") { dev.ultraxr.core.Fmt.f(it[0], 2) + "," + dev.ultraxr.core.Fmt.f(it[1], 2) + "," + dev.ultraxr.core.Fmt.f(it[2], 2) } }
                                if (fit != null) {
                                    val b = planBefore
                                    if (b != null) { planMove = b.worldMoveFrom(fit); planFit = b; planBefore = null; planMaxShift = 0.0 }
                                    else { planFit = fit; planSaid = "room: the marked room is found (" + fit.walls + " walls, " + fit.tables + " table tops agree)" }
                                }
                            }
                        }
                        planSaid?.let { log(it) }
                        val pm = if (mapLive()) null else planMove      // the saved map says where the world is: it is the better witness
                        if (pm != null) {
                            val far = Math.sqrt(pm.p[0] * pm.p[0] + pm.p[2] * pm.p[2]); val turn = Math.toDegrees(2 * Math.atan2(pm.q[1], pm.q[3]))
                            if (!planDrift) log("room: the marked room is found again; the world had slipped " + dev.ultraxr.core.Fmt.f(far, 2) + " m and " + dev.ultraxr.core.Fmt.f(turn, 1) + " degrees and is put back")
                            else if (far > 0.15) log("room: drift taken out against the marked room: " + dev.ultraxr.core.Fmt.f(far, 2) + " m, " + dev.ultraxr.core.Fmt.f(turn, 1) + " degrees")
                            planShifting = true; recallShifting = true; try { head.moveWorld(pm) } finally { planShifting = false; recallShifting = false }
                            continue
                        }
                        val back = if (synchronized(planLock) { plan != null }) null else synchronized(recall) { if (recall.waiting) recall.find(System.nanoTime(), lines) else { recall.see(lines); null } }
                        if (back != null && !mapLive()) {
                            val far = Math.sqrt(back[0] * back[0] + back[1] * back[1])
                            if (far < 0.10) log("room: found again where it was (" + dev.ultraxr.core.Fmt.f(far, 2) + " m off)")
                            else {
                                log("room: found again " + dev.ultraxr.core.Fmt.f(far, 2) + " m from where the world was joined on: world put back")
                                recallShifting = true; try { head.shiftWorld(back[0], back[1]) } finally { recallShifting = false }
                                continue
                            }
                        }
                        denseWallsNow = found.map { w ->
                            var ux = (w.x1 - w.x0) / w.length; var uz = (w.z1 - w.z0) / w.length
                            val cx = (w.x0 + w.x1) / 2; val cz = (w.z0 + w.z1) / 2
                            // its face is towards the wearer: normal = u x up = (-uz, 0, ux)
                            if (-uz * (cam.p[0] - cx) + ux * (cam.p[2] - cz) < 0) { ux = -ux; uz = -uz }
                            dev.ultraxr.core.RoomSurface(dev.ultraxr.core.RoomSurface.Kind.WALL, cx, floorNow + 1.15, cz, ux, 0.0, uz, 0.0, 1.0, 0.0, w.length / 2, 1.15, w.support.toInt())
                        }
                        denseWallCount = denseWallsNow.size
                    }
                    denseMs = (System.nanoTime() - td) / 1e6
                    if (denseWallsNow.size != saidWalls) { saidWalls = denseWallsNow.size; log("room: " + saidWalls + " walls from dense depth (" + wallMap.pictures + " depth pictures, " + dev.ultraxr.core.Fmt.f(denseMs, 0) + " ms each)") }
                }
                if (frame % 2 != 0) continue
                // where the cameras look, seen from above: a surface is only given up when it was looked at and not found
                val look = Q.rotate(cam.q, doubleArrayOf(0.0, 0.0, 1.0))
                val f0 = room.analyse(cam.p[1], cam.p[0], cam.p[2], look[0], look[2])
                if (version != head.worldVersion) continue
                // the walls of the floor plan take the place of the ones found from corners, once there are any
                // and what comes out twice (the same wall or table top, a few centimetres apart) is made one (core/RoomTidy)
                // and where the owner has marked the room and it is placed, its walls are the walls: whole, and always there
                val marked = planWalls(f0.floorY)
                val f = if (marked != null) dev.ultraxr.core.RoomFindings(f0.floorY, f0.floorSupport, f0.surfaces.filter { it.kind != dev.ultraxr.core.RoomSurface.Kind.WALL } + marked, f0.voxels, f0.confirmed)
                    else dev.ultraxr.core.RoomFindings(f0.floorY, f0.floorSupport, dev.ultraxr.core.RoomTidy.tidy(
                    if (denseWallsNow.isEmpty()) f0.surfaces else f0.surfaces.filter { it.kind != dev.ultraxr.core.RoomSurface.Kind.WALL } + denseWallsNow), f0.voxels, f0.confirmed)
                roomFindings = f
                try { roomMemoryStep(f) } catch (e: Throwable) { log("room memory: $e") }
                // The floor is flat: what it does with distance says whether the angle between the cameras is right.
                val fc = floorCalibrator
                if (fc != null && !f.floorY.isNaN() && f.floorSupport >= 60) {
                    if (!calibratedThisRun && (calibCheckFrame + 1) % 60 == 0 && fc.result() == null) log("calibration: not enough floor seen at a distance yet (points by distance band: " + fc.status() + ")")
                    if (!calibratedThisRun && ++calibCheckFrame % 30 == 0) fc.result()?.let { r ->
                        if (r.points < 1200) return@let
                        calibratedThisRun = true
                        val now = stereoFixInUse[1]; val next = dev.ultraxr.core.FloorCalibrator.trimmed(now, r.errorDeg)
                        floorSlopeNote = dev.ultraxr.core.Fmt.f(r.slopeMmPerM, 0) + " mm per m over " + r.bins + " distances"
                        if (!stereoFixIsUnit || simulateWearer) log("calibration: the floor changes by " + floorSlopeNote + " (" + r.points + " points), an angle error of " + dev.ultraxr.core.Fmt.f(r.errorDeg, 2) + " degrees; a trial calibration is in use, nothing is changed")
                        else if (next == now) log("calibration: the floor is flat to " + floorSlopeNote + " (" + r.points + " points): camera angle left as it is")
                        else try {
                            val text = dev.ultraxr.core.Fmt.f(stereoFixInUse[0], 3) + "," + dev.ultraxr.core.Fmt.f(next, 3) + "," + dev.ultraxr.core.Fmt.f(stereoFixInUse[2], 3)
                            java.io.File(appContext?.getExternalFilesDir(null), "stereo_fix_unit.txt").writeText(text)
                            log("calibration: the floor rises " + floorSlopeNote + " (" + r.points + " points), an angle error of " + dev.ultraxr.core.Fmt.f(r.errorDeg, 2) +
                                " degrees between the cameras: correction " + dev.ultraxr.core.Fmt.f(now, 3) + " -> " + dev.ultraxr.core.Fmt.f(next, 3) + " degrees, from the next start")
                        } catch (e: Exception) { log("calibration: could not save the correction: $e") }
                    }
                }
                replayLog?.room(link?.replaySeconds ?: 0.0, k, roomFindMs, cam.p[1], f)
                val below = cam.p[1] - f.floorY
                if (!f.floorY.isNaN() && (saidFloor.isNaN() || Math.abs(below - saidFloor) > 0.05)) {
                    saidFloor = below
                    log("room: floor " + dev.ultraxr.core.Fmt.f(below, 2) + " m below the cameras (" + f.floorSupport + " boxes), " +
                        f.surfaces.count { it.kind == dev.ultraxr.core.RoomSurface.Kind.TABLE } + " table tops, " + f.surfaces.count { it.kind == dev.ultraxr.core.RoomSurface.Kind.WALL } + " walls, " + roomPictures + " pictures")
                }
            } catch (e: Throwable) { log("room: $e") }
        }
    }
    private const val ROOM_MAX_RANGE_M = 4.5

    private const val WORK_PRIORITY = 0
    @Volatile private var workPriority = WORK_PRIORITY
    @Volatile private var handCores = 0
    private fun handsLoop() {
        try { android.os.Process.setThreadPriority(workPriority) } catch (_: Throwable) {}
        var pinned = 0
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
            if (pinned != handCores) { pinned = handCores; try { Native.fastCores(pinned != 0) } catch (_: Throwable) {} }      // this thread runs the fit
            val t0 = System.nanoTime()
            // A hand in front of a bright window (worn, 21 November 2026: the arm stretched out before a doorway full of
            // daylight) is a dark shape whose fingers the models do not make out. The hand tracker's own copies of the
            // pictures are lifted in the dark and pressed together in the bright ("hand_tone=TAU", 0: as they come).
            handToneLut?.let { lut -> for (a in arrayOf(l, r)) for (i in a.indices) a[i] = lut[a[i].toInt() and 0xff] }
            val mask = try { Native.handsProcess(h, t, l, r, 480, 640, out, out26) } catch (e: Throwable) { handsError = e.toString(); -1 }
            handsProcessMs = (System.nanoTime() - t0) / 1e6
            handsFrames++
            replayLog?.let { it.hands(link?.replaySeconds ?: 0.0, mask, out, handsProcessMs); it.joints(link?.replaySeconds ?: 0.0, mask, out26) }
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
        if (t != throttle) { throttle = t; log("thermal status $status, headroom $headroom: the room looked at every ${1 + 2 * t} thirds of a second, hand tracking every ${if (t >= 2) 2 else 1} frames") }
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
