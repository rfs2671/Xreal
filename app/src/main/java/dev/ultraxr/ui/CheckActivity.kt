package dev.ultraxr.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ultraxr.core.Fmt
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.ReportBuilder
import dev.ultraxr.core.ReportExport
import dev.ultraxr.glasses.DisplayDiagnostics
import dev.ultraxr.glasses.GlassesLink
import dev.ultraxr.proto.McuProtocol
import dev.ultraxr.render.CheckScene
import dev.ultraxr.render.GlassesStage
import dev.ultraxr.tracking.Native
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.ui.Ui.add
import dev.ultraxr.xr.XrRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * One-tap hardware check. Runs every part of the pipeline against the real glasses, tells
 * the user what to do in plain words, and produces a report that can be shared.
 */
class CheckActivity : Activity() {
    private enum class V { PENDING, RUNNING, PASS, FAIL, SKIP }
    private class Item(val key: String, val title: String) { var v = V.PENDING; var detail = ""; lateinit var icon: TextView; lateinit var sub: TextView }

    private val ui = Handler(Looper.getMainLooper())
    private val scene = CheckScene()
    private lateinit var stage: GlassesStage
    private lateinit var headline: TextView
    private lateinit var instruction: TextView
    private lateinit var startButton: TextView
    private lateinit var yesNo: LinearLayout
    private lateinit var shareRow: LinearLayout
    private val items = listOf(
        Item("usb", "Glasses found over USB"), Item("mcu", "Glasses display controller"), Item("imu", "Motion sensor"),
        Item("camera", "Tracking cameras"), Item("decode", "Camera images"), Item("sync", "Camera and motion sensor in sync"),
        Item("display", "Phone drives the glasses' display"), Item("render", "Smooth rendering"), Item("sixdof", "Position tracking (6DoF)"),
        Item("hands", "Hand tracking"), Item("stereo", "Picture seen by you"), Item("openxr", "OpenXR runtime"),
    )
    private fun item(k: String) = items.first { it.key == k }

    private var running = false
    private var t0 = 0L
    private var phaseStart = 0L
    private var phase = 0
    private var runDir: File? = null
    private val series = StringBuilder()
    private var prevImu = 0L; private var prevPairs = 0L; private var prevFrames = 0L; private var prevNs = 0L
    private var imuHz = 0.0; private var pairHz = 0.0; private var fps = 0.0
    private var minP = doubleArrayOf(9e9, 9e9, 9e9); private var maxP = doubleArrayOf(-9e9, -9e9, -9e9)
    private var sixDofSeconds = 0; private var handSeconds = 0; private var liveSeconds = 0
    private var startPct = 0; private var startUah = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Tracking.acquire(this)
        val root = Ui.column(this)
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 56), Ui.dp(this, 20), Ui.dp(this, 28))
        headline = Ui.text(this, "Hardware check", 30f, bold = true)
        root.add(headline)
        instruction = Ui.text(this, "Plug the glasses into the phone, put them on in a normally lit room, and press Start. It takes about a minute.", 15f, 0xFFC9D6E6.toInt()).apply { setLineSpacing(0f, 1.25f) }
        root.add(instruction, topDp = 10)
        startButton = Ui.button(this, "Start", primary = true) { begin() }
        root.add(startButton, topDp = 18)
        yesNo = Ui.row(this).apply { visibility = View.GONE }
        yesNo.add(Ui.button(this, "Yes", primary = true) { answerStereo(true) }, 0, weight = 1f)
        yesNo.add(Ui.button(this, "No") { answerStereo(false) }, 0, weight = 1f, startDp = 10)
        root.add(yesNo, topDp = 18)
        shareRow = Ui.row(this).apply { visibility = View.GONE }
        shareRow.add(Ui.button(this, "Share report", primary = true) { share() }, 0, weight = 1f)
        shareRow.add(Ui.button(this, "Save to Downloads") { save() }, 0, weight = 1f, startDp = 10)
        root.add(shareRow, topDp = 18)
        // Manual display-mode switches: each press is recorded in the report with what the glasses answered.
        root.add(Ui.text(this, "Glasses black or picture wrong? Try a display mode, wait five seconds, then share the report.", 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }, topDp = 18)
        val modes = Ui.row(this)
        modes.add(Ui.button(this, "2D") { tryMode(McuProtocol.MODE_2D_60) }, 0, weight = 1f)
        modes.add(Ui.button(this, "3D 60 Hz") { tryMode(McuProtocol.MODE_SBS_60) }, 0, weight = 1f, startDp = 8)
        modes.add(Ui.button(this, "3D 90 Hz") { tryMode(McuProtocol.MODE_SBS_90) }, 0, weight = 1f, startDp = 8)
        root.add(modes, topDp = 8)
        root.add(Ui.button(this, "Display test (pattern in the glasses)") { startActivity(Intent(this, DisplayTestActivity::class.java)) }, topDp = 8)
        root.add(Ui.button(this, "Share report now") { share() }, topDp = 8)
        val list = Ui.column(this)
        for (it in items) {
            val row = Ui.row(this).apply { background = Ui.rounded(Ui.SURFACE, 18f, this@CheckActivity); val p = Ui.dp(this@CheckActivity, 14); setPadding(p, p, p, p) }
            it.icon = Ui.text(this, "·", 20f, Ui.MUTED, bold = true).apply { minWidth = Ui.dp(this@CheckActivity, 28) }
            val col = Ui.column(this)
            col.add(Ui.text(this, it.title, 15f, bold = true))
            it.sub = Ui.text(this, "", 12f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f); visibility = View.GONE }
            col.add(it.sub, topDp = 4)
            row.add(it.icon, ViewGroup.LayoutParams.WRAP_CONTENT); row.add(col, 0, weight = 1f)
            list.add(row, topDp = 8)
        }
        root.add(list, topDp = 14)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
        stage = GlassesStage(this, null, scene)
    }

    override fun onResume() { super.onResume(); stage.start() }
    override fun onPause() { stage.stop(); ui.removeCallbacks(tick); if (running) { running = false; startButton.visibility = View.VISIBLE } ; super.onPause() }
    override fun onDestroy() { Tracking.release(); super.onDestroy() }

    private fun tryMode(mode: Int) {
        val link = Tracking.link
        if (link == null || link.stats.mcuOk.not()) { instruction.text = "The glasses' display controller is not connected yet."; return }
        Tracking.settings.use3d = mode != McuProtocol.MODE_2D_60
        link.applyDisplayMode(mode)
        instruction.text = "Asked the glasses for ${McuProtocol.modeName(mode)}. Wait five seconds, look in the glasses, then share the report."
    }

    private fun set(k: String, v: V, detail: String = "") {
        val it = item(k)
        it.v = v; it.detail = detail
        val (ch, col) = when (v) { V.PASS -> "✓" to Ui.GOOD; V.FAIL -> "✕" to Ui.BAD; V.RUNNING -> "…" to Ui.ACCENT; V.SKIP -> "–" to Ui.WARN; V.PENDING -> "·" to Ui.MUTED }
        it.icon.text = ch; it.icon.setTextColor(col)
        it.sub.text = detail; it.sub.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun say(text: String, inGlasses: String = text) { instruction.text = text; scene.prompt = inGlasses }

    private fun begin() {
        if (running) return
        running = true
        for (it in items) set(it.key, V.PENDING)
        startButton.visibility = View.GONE; shareRow.visibility = View.GONE; yesNo.visibility = View.GONE
        series.setLength(0)
        series.append("t_s,imu_hz,pairs_hz,render_fps,late,vio_states,vio_ms,features,hands_ms,battery_pct,current_uA,batt_C,thermal,headroom\n")
        runDir = File(getExternalFilesDir(null), "check-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())).apply { mkdirs() }
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        startPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); startUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        minP = doubleArrayOf(9e9, 9e9, 9e9); maxP = doubleArrayOf(-9e9, -9e9, -9e9); sixDofSeconds = 0; handSeconds = 0; liveSeconds = 0
        t0 = System.nanoTime(); phase = 0; phaseStart = t0; prevNs = t0
        Tracking.link?.let { it.captureDir = runDir; it.reconnect() }
        set("usb", V.RUNNING)
        say("Connecting to the glasses…", "")
        headline.text = "Checking…"
        ui.postDelayed(tick, 1000)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            if (running) ui.postDelayed(this, 1000)
        }
    }

    private fun step() {
        val now = System.nanoTime()
        val link = Tracking.link
        val st = link?.stats
        val pl = link?.pipeline
        val dt = (now - prevNs) / 1e9; prevNs = now
        val r = stage.renderer
        if (pl != null) {
            imuHz = (pl.imuSamples - prevImu) / dt; pairHz = (pl.pairs - prevPairs) / dt
            prevImu = pl.imuSamples; prevPairs = pl.pairs
        }
        val frames = r?.frames ?: 0
        fps = ((frames - prevFrames) / dt).coerceAtLeast(0.0); prevFrames = frames
        val head = Tracking.head.predict(now)
        val snap = Tracking.snapshot()
        if (imuHz > 50 && pairHz > 5 && fps > 20) liveSeconds++
        sample(now)
        val inPhase = (now - phaseStart) / 1e9

        when (phase) {
            0 -> { // connection and sensors
                when (link?.state) {
                    GlassesLink.State.STREAMING -> set("usb", V.PASS, "Air 2 Ultra, serial ${st?.serial?.ifEmpty { "unknown" }}")
                    GlassesLink.State.NO_GLASSES -> if (inPhase > 6) set("usb", V.FAIL, "Android does not see the glasses on USB. " + (link.stateDetail))
                    GlassesLink.State.NEED_PERMISSION -> say("Tap Allow on the USB access prompt.", "")
                    GlassesLink.State.FAILED -> set("usb", V.FAIL, link.stateDetail)
                    else -> {}
                }
                if (item("usb").v == V.FAIL) { finish(false); return }
                if (link?.state != GlassesLink.State.STREAMING || st == null || pl == null) { if (inPhase > 25) { set("usb", V.FAIL, "Timed out: ${link?.stateDetail} ${st?.lastError}"); finish(false) }; return }
                say("Reading the sensors. Look around the room.", "Look around the room")
                if (inPhase < 6) return
                if (st.mcuOk) set("mcu", if (st.displayMode == link.wantedDisplayMode) V.PASS else V.FAIL, "Glasses report ${McuProtocol.modeName(st.displayMode)}; asked for ${McuProtocol.modeName(link.wantedDisplayMode)}")
                else set("mcu", V.FAIL, "The display controller did not answer")
                set("imu", if (imuHz > 100) V.PASS else V.FAIL, "%.0f samples a second (report size %d, via %s)".format(imuHz, pl.imuReportSize, st.imuWritePath))
                set("camera", if (pairHz > 10) V.PASS else V.FAIL, "%.1f stereo pairs a second, left %d / right %d frames, %s".format(pairHz, pl.camLeft, pl.camRight, st.transport))
                val seamOk = pl.seamLeft < 1.6 && pl.seamRight < 1.6
                set("decode", if (pl.seamLeft.isNaN() || pl.seamRight.isNaN()) V.FAIL else if (seamOk) V.PASS else V.FAIL,
                    "Seam score left %.2f, right %.2f (1 is ideal). Brightness %.0f / %.0f of 255.".format(pl.seamLeft, pl.seamRight, pl.brightLeft, pl.brightRight))
                set("sync", if (pl.pairs > 30 && pl.pairsOnCameraClock < pl.pairs / 10) V.PASS else V.FAIL,
                    "${pl.pairs} pairs timed, ${pl.pairsOnCameraClock} of them without the shared clock. Camera minus sensor clock " + Fmt.f(link.clock.lastCamMinusImuMs, 1) + " ms, drift " + Fmt.f(link.clock.driftPpm, 0) + " ppm, IMU out of order ${link.clock.imuOutOfOrder}, clock restarts ${link.clock.clockRollbacks}")
                val offered = DisplayDiagnostics.sideBySideOffered(this)
                set("display", if (stage.onGlasses) V.PASS else V.FAIL,
                    if (!stage.onGlasses) "Android reports no external display"
                    else "Android drives the glasses at ${stage.displayInfo}; glasses report ${McuProtocol.modeName(st.displayMode)}. " +
                        (if (stage.isSideBySide) "Side-by-side 3D is active" else if (offered) "A 3840x1080 mode is offered but not active" else "No 3840x1080 mode is offered, so output is 2D") +
                        ". Whether the panels actually light up is the last question of this check")
                val late = r?.lateFrames ?: 0
                set("render", if (stage.onGlasses && fps > 0.9e9 / (r?.periodNs ?: 16_666_667L)) V.PASS else V.FAIL, "%.0f frames a second, %d late, %.1f ms per frame on the CPU".format(fps, late, r?.avgCpuMs ?: 0.0))
                set("openxr", if (XrRuntime.available) V.PASS else V.SKIP, XrRuntime.statusLine(this))
                next(1)
                set("sixdof", V.RUNNING)
                say("Hold your head still for two seconds, then look around slowly and take a step to each side.", "Hold still 2 s, then look around and step sideways")
            }
            1 -> { // 6DoF
                if (head.mode == HeadTracker.Mode.SIX_DOF) {
                    sixDofSeconds++
                    for (i in 0..2) { minP[i] = minOf(minP[i], head.pose.p[i]); maxP[i] = maxOf(maxP[i], head.pose.p[i]) }
                }
                val range = if (sixDofSeconds > 0) sqrt((0..2).sumOf { (maxP[it] - minP[it]) * (maxP[it] - minP[it]) }) else 0.0
                val detail = "%d s of position tracking, moved %.2f m, %d estimator states, %.0f ms per frame, %d features, %d restarts".format(sixDofSeconds, range, Tracking.vioStates, Tracking.vioProcessMs, Tracking.vioFeatures, Tracking.supervisor.resets)
                if (!snap.vioActive) { set("sixdof", V.FAIL, "Estimator not running: ${Tracking.vioError.ifEmpty { "native library missing" }}"); next(2) }
                else if (sixDofSeconds >= 5 && range in 0.15..4.0) { set("sixdof", V.PASS, detail); next(2) }
                else if (inPhase > 30) { set("sixdof", V.FAIL, if (sixDofSeconds == 0) "No position came out. $detail" else if (range < 0.15) "Tracking ran but measured almost no movement. $detail" else "Position jumped implausibly. $detail"); next(2) }
                if (phase == 2) { set("hands", V.RUNNING); say("Hold one open hand in front of your face, about 40 cm away.", "Hold an open hand in front of you") }
            }
            2 -> { // hands
                if (snap.handsVisible > 0) handSeconds++
                val detail = "%.0f ms per frame, %d frames processed".format(Tracking.handsProcessMs, Tracking.handsFrames)
                if (!snap.handsActive) { set("hands", if (Tracking.settings.handTracking) V.FAIL else V.SKIP, if (Tracking.settings.handTracking) "Not running: ${Tracking.handsError.ifEmpty { "native library missing" }}" else "Turned off in Settings"); next(3) }
                else if (handSeconds >= 3) { set("hands", V.PASS, "Hand seen for $handSeconds s. $detail"); next(3) }
                else if (inPhase > 20) { set("hands", V.FAIL, "No hand was detected. $detail"); next(3) }
                if (phase == 3) {
                    set("stereo", V.RUNNING)
                    say(if (stage.isSideBySide) "Last one. In the glasses: close one eye, then the other. Does one eye see the word LEFT and the other RIGHT, and do the three balls look near, middle and far?" else "Last one. Do you see anything at all in the glasses (a grid, three balls, the words LEFT and RIGHT)? Answer No if they are black.", "")
                    yesNo.visibility = View.VISIBLE
                }
            }
            3 -> if (inPhase > 90) answerStereo(null)
        }
    }

    private fun next(p: Int) { phase = p; phaseStart = System.nanoTime() }

    private fun answerStereo(yes: Boolean?) {
        if (!running) return
        yesNo.visibility = View.GONE
        set("stereo", when (yes) { true -> V.PASS; false -> V.FAIL; null -> V.SKIP }, when (yes) { true -> "Confirmed by you"; false -> "You reported no correct picture in the glasses"; null -> "Not answered" })
        finish(true)
    }

    private fun finish(complete: Boolean) {
        running = false
        scene.prompt = ""
        val fails = items.count { it.v == V.FAIL }; val passes = items.count { it.v == V.PASS }
        for (it in items) if (it.v == V.PENDING || it.v == V.RUNNING) set(it.key, V.SKIP, "Not reached")
        headline.text = if (fails == 0 && complete) "Everything works" else if (complete) "$passes passed, $fails need attention" else "Could not finish"
        instruction.text = if (fails == 0 && complete) "Your phone and glasses passed every check. You are ready to play."
            else "Share the report so the failing steps can be diagnosed. Rows marked ✕ explain what was seen."
        Tracking.settings.firstRunDone = Tracking.settings.firstRunDone || (complete && item("imu").v == V.PASS && item("camera").v == V.PASS)
        Tracking.settings.lastCheckSummary = items.joinToString("; ") { "${it.key}=${it.v}" }
        startButton.text = "Run again"; startButton.visibility = View.VISIBLE; shareRow.visibility = View.VISIBLE
        writeReport()
    }

    private fun sample(now: Long) {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else Float.NaN
        series.append("%.0f,%.0f,%.1f,%.1f,%d,%d,%.1f,%d,%.1f,%d,%d,%.1f,%d,%.2f\n".format(Locale.US, (now - t0) / 1e9, imuHz, pairHz, fps, stage.renderer?.lateFrames ?: 0,
            Tracking.vioStates, Tracking.vioProcessMs, Tracking.vioFeatures, Tracking.handsProcessMs, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0, pm.currentThermalStatus, headroom))
    }

    /** Builds the report. Each section is independent: one that fails is noted and the rest still appear. */
    private fun report(): String {
        val st = Tracking.link?.stats
        val r = ReportBuilder()
        r.line("Ultra XR hardware check")
        r.section("DEVICE") { sb ->
            sb.append("app ").append(packageManager.getPackageInfo(packageName, 0).versionName).append('\n')
            sb.append("phone ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}, build ${Build.DISPLAY}\n")
            if (Build.VERSION.SDK_INT >= 31) sb.append("soc ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}; page size ${android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)}\n")
        }
        r.section("RESULTS") { sb -> for (it in items) sb.append(it.v.name.padEnd(7)).append(' ').append(it.key).append(": ").append(it.title).append(". ").append(it.detail).append('\n') }
        r.section("DISPLAY: GLASSES CONTROLLER") { sb ->
            if (st == null) { sb.append("no USB link\n"); return@section }
            sb.append("controller answered: ").append(st.mcuOk).append(" (write path ").append(st.mcuWritePath).append(")\n")
            sb.append("mode when connected: ").append(McuProtocol.modeName(st.displayModeAtConnect)).append(" (raw ").append(st.displayModeAtConnect).append(")\n")
            sb.append("mode now: ").append(McuProtocol.modeName(st.displayMode)).append(" (raw ").append(st.displayMode).append(")\n")
            sb.append("mode this app asks for: ").append(McuProtocol.modeName(Tracking.link?.wantedDisplayMode ?: -1)).append("; 3D setting=").append(Tracking.settings.use3d).append(", 90 Hz setting=").append(Tracking.settings.refresh90).append('\n')
            sb.append("fell back to 2D because the phone offered no side-by-side mode: ").append(st.sbsFellBack).append('\n')
            sb.append("display test, last answer from the user: ").append(Tracking.settings.lastDisplayAnswer.ifEmpty { "none" }).append('\n')
            sb.append("controller status:\n").append(st.controllerStatus.ifEmpty { "not read" }).append('\n')
            sb.append("history:\n").append(st.displayHistory()).append('\n')
        }
        r.section("DISPLAY: ANDROID") { sb ->
            sb.append(DisplayDiagnostics.describeAll(this))
            sb.append("side-by-side mode current on an external display: ").append(DisplayDiagnostics.sideBySidePresent(this)).append("; offered: ").append(DisplayDiagnostics.sideBySideOffered(this)).append('\n')
        }
        r.section("DISPLAY: THIS APP'S OUTPUT") { sb ->
            val rr = stage.renderer
            sb.append("output: ").append(if (stage.onGlasses) "window on the glasses' display" else "no window on the glasses").append(", ").append(stage.displayInfo).append(", side-by-side=").append(stage.isSideBySide).append('\n')
            if (rr != null) {
                sb.append("renderer: surface ").append(rr.width).append('x').append(rr.height).append(", frames ").append(rr.frames).append(", late ").append(rr.lateFrames)
                    .append(", last frame ").append(if (rr.lastFrameNs == 0L) "never" else Fmt.f((System.nanoTime() - rr.lastFrameNs) / 1e6, 0) + " ms ago").append('\n')
                sb.append("GL: ").append(rr.glInfo.ifEmpty { "context not created" }).append("; error: ").append(rr.glError.ifEmpty { "none" }).append('\n')
            } else sb.append("renderer: none\n")
            sb.append("graphics log:\n").append(Tracking.graphicsText()).append('\n')
        }
        r.section("TRACKING") { sb ->
            sb.append("all three streams live together for ").append(liveSeconds).append(" s\n")
            sb.append("native: loaded=").append(Native.loaded).append(' ').append(Native.loadError).append("; 6DoF error='").append(Tracking.vioError).append("'; hands error='").append(Tracking.handsError).append("'\n")
            sb.append("calibration: ").append(Tracking.calibrationSource).append("; display FOV tan ").append(Fmt.f(Tracking.displayTanH, 4)).append(" x ").append(Fmt.f(Tracking.displayTanV, 4)).append(" (").append(Tracking.displayFovSource).append(")\n")
            sb.append("6DoF: states=${Tracking.vioStates} frames_in=${Tracking.vioFramesIn} dropped=${Tracking.vioDropped} restarts=${Tracking.supervisor.resets} last='${Tracking.supervisor.lastReason}'\n")
            sb.append("thermal: status=${Tracking.thermalStatus} throttle=${Tracking.throttle}\n")
            if (st != null) sb.append("camera: ${st.cameraFormat}; ${st.transport}; noMeta=${Tracking.link?.pipeline?.noMeta} reconnects=${st.reconnects} lastError='${st.lastError}'\n")
        }
        r.section("CAMERA AND IMU STREAM") { sb ->
            val link = Tracking.link
            if (link == null) { sb.append("no USB link\n"); return@section }
            sb.append(link.describeCameraUsb())
            sb.append(link.pipeline.describe())
            sb.append("reconnects: ").append(link.stats.reconnects).append("; reasons:\n").append(link.stats.reconnectHistory()).append('\n')
            sb.append("estimator: frames in ").append(Tracking.vioFramesIn).append(", dropped before processing ").append(Tracking.vioDropped).append(", states out ").append(Tracking.vioStates)
                .append(", self-restarts ").append(Tracking.vioAutoResets).append(", app-requested restarts ").append(Tracking.supervisor.resets).append(" (last: ").append(Tracking.supervisor.lastReason.ifEmpty { "none" }).append(")\n")
        }
        r.section("BATTERY") { sb ->
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            sb.append("level ").append(startPct).append(" percent -> ").append(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).append(" percent; charge counter ")
                .append(startUah).append(" -> ").append(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)).append(" uAh over ").append(Fmt.f((System.nanoTime() - t0) / 1e9, 0)).append(" s\n")
        }
        r.section("USB") { sb -> sb.append(st?.descriptors ?: "no USB link\n") }
        r.section("FACTORY CONFIG (from the glasses)") { sb -> sb.append(st?.configJson ?: "not read") }
        r.section("OPENXR") { sb -> sb.append(XrRuntime.statusLine(this)) }
        r.section("TIME SERIES (1 s)") { sb -> sb.append(series) }
        r.section("LOG") { sb -> sb.append(Tracking.logText()) }
        if (r.failedSections > 0) Tracking.log("hardware report: ${r.failedSections} section(s) failed, see the report")
        return r.build()
    }

    /** Report text that can always be shared: on failure, the reason instead. For Share and for tests. */
    fun reportTextSafely(): String {
        val (text, ok) = ReportExport.safeText { report() }
        if (!ok) Tracking.log("hardware report failed: " + text.take(400))
        return text
    }

    private fun writeReport() {
        val dir = runDir ?: return
        try { File(dir, ReportExport.REPORT_NAME).writeText(reportTextSafely()) } catch (e: Exception) { Tracking.log("could not write the report file: $e") }
    }

    /** Same code the Share button runs. */
    fun share() {
        try {
            // Intent extras travel through Binder, which fails above roughly 500 KB: keep the tail (the log) short.
            var text = reportTextSafely()
            if (text.length > 180_000) text = text.take(180_000) + "\n[truncated for sharing: use Save to Downloads for the full report]\n"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Ultra XR hardware check").putExtra(Intent.EXTRA_TEXT, text), "Share report"))
        } catch (t: Throwable) {
            Tracking.log("share failed: " + ReportExport.stackTrace(t).take(600))
            instruction.text = "Could not open the share sheet ($t). Use Save to Downloads instead."
        }
    }

    /** Writes the export archive: always holds report.txt, or report_error.txt saying why not. */
    fun exportZip(out: java.io.OutputStream): List<String> {
        val extras = runDir?.listFiles()?.toList().orEmpty()
        return ReportExport.writeZip(out, { report() }, extras)
    }

    /** Same code the Save button runs; [onDone] gets the message shown to the user. */
    fun save(onDone: ((String) -> Unit)? = null) {
        thread {
            val msg = try {
                val stamp = runDir?.name ?: ("check-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))
                val name = "ultra-xr-$stamp.zip"
                val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/zip") }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("no access to Downloads")
                val names = contentResolver.openOutputStream(uri)!!.use { exportZip(it) }
                if (ReportExport.REPORT_NAME in names) "Saved to Downloads as $name (${names.joinToString()})."
                else "Saved to Downloads as $name, but the report itself could not be generated: the zip holds ${ReportExport.ERROR_NAME} with the reason."
            } catch (t: Throwable) {
                Tracking.log("save failed: " + ReportExport.stackTrace(t).take(600))
                "Could not save: $t"
            }
            ui.post { instruction.text = msg; onDone?.invoke(msg) }
        }
    }
}
