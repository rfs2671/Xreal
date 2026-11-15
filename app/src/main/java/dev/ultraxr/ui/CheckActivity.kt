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
import dev.ultraxr.core.HeadTracker
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
        Item("usb", "Glasses found over USB"), Item("mcu", "Display switched to 3D mode"), Item("imu", "Motion sensor"),
        Item("camera", "Tracking cameras"), Item("decode", "Camera images"), Item("sync", "Camera and motion sensor in sync"),
        Item("display", "Glasses display in side-by-side 3D"), Item("render", "Smooth rendering"), Item("sixdof", "Position tracking (6DoF)"),
        Item("hands", "Hand tracking"), Item("stereo", "3D seen by you"), Item("openxr", "OpenXR runtime"),
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
                if (st.mcuOk) set("mcu", if (st.displayMode == McuProtocol.MODE_SBS_60 || st.displayMode == McuProtocol.MODE_SBS_90) V.PASS else V.FAIL, "Glasses report: ${McuProtocol.modeName(st.displayMode)}")
                else set("mcu", V.FAIL, "The display controller did not answer")
                set("imu", if (imuHz > 100) V.PASS else V.FAIL, "%.0f samples a second (report size %d, via %s)".format(imuHz, pl.imuReportSize, st.imuWritePath))
                set("camera", if (pairHz > 10) V.PASS else V.FAIL, "%.1f stereo pairs a second, left %d / right %d frames, %s".format(pairHz, pl.camLeft, pl.camRight, st.transport))
                val seamOk = pl.seamLeft < 1.6 && pl.seamRight < 1.6
                set("decode", if (pl.seamLeft.isNaN() || pl.seamRight.isNaN()) V.FAIL else if (seamOk) V.PASS else V.FAIL,
                    "Seam score left %.2f, right %.2f (1 is ideal). Brightness %.0f / %.0f of 255.".format(pl.seamLeft, pl.seamRight, pl.brightLeft, pl.brightRight))
                set("sync", if (pl.pairs > 30 && pl.pairsUnsynced < pl.pairs / 10) V.PASS else V.FAIL,
                    "Camera minus sensor clock %.1f ms, drift %.0f ppm, %d of %d pairs could not be timed".format(pl.camMinusImuMs, link.clock.driftPpm, pl.pairsUnsynced, pl.pairs + pl.pairsUnsynced))
                set("display", if (stage.onGlasses && stage.isSideBySide) V.PASS else V.FAIL, if (stage.onGlasses) "Android drives the glasses at ${stage.displayInfo}" + (if (stage.isSideBySide) "" else ": not the 3840x1080 mode 3D needs") else "Android reports no external display")
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
                    say("Last one. In the glasses: close one eye, then the other. Does one eye see the word LEFT and the other RIGHT, and do the three balls look near, middle and far?", "")
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
        set("stereo", when (yes) { true -> V.PASS; false -> V.FAIL; null -> V.SKIP }, when (yes) { true -> "Confirmed by you"; false -> "You reported that 3D did not look right"; null -> "Not answered" })
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

    private fun report(): String {
        val sb = StringBuilder()
        val st = Tracking.link?.stats
        sb.append("Ultra XR hardware check\n")
        sb.append("app ${packageManager.getPackageInfo(packageName, 0).versionName}; phone ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}, build ${Build.DISPLAY}\n")
        if (Build.VERSION.SDK_INT >= 31) sb.append("soc ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}; page size ${android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)}\n")
        sb.append("\n== RESULTS ==\n")
        for (it in items) sb.append("${it.v.name.padEnd(7)} ${it.key}: ${it.title}. ${it.detail}\n")
        sb.append("\n== DETAILS ==\n")
        sb.append("all three streams live together for $liveSeconds s\n")
        sb.append("native: loaded=${Native.loaded} ${Native.loadError}; 6DoF error='${Tracking.vioError}'; hands error='${Tracking.handsError}'\n")
        sb.append("calibration: ${Tracking.calibrationSource}; display FOV tan %.4f x %.4f (%s)\n".format(Tracking.displayTanH, Tracking.displayTanV, Tracking.displayFovSource))
        sb.append("6DoF: states=${Tracking.vioStates} frames_in=${Tracking.vioFramesIn} dropped=${Tracking.vioDropped} restarts=${Tracking.supervisor.resets} last='${Tracking.supervisor.lastReason}'\n")
        sb.append("thermal: status=${Tracking.thermalStatus} throttle=${Tracking.throttle}\n")
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        sb.append("battery: $startPct% -> ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%, charge counter $startUah -> ${bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)} uAh over %.0f s\n".format((System.nanoTime() - t0) / 1e9))
        if (st != null) {
            sb.append("camera: ${st.cameraFormat}; ${st.transport}; noMeta=${Tracking.link?.pipeline?.noMeta} reconnects=${st.reconnects} lastError='${st.lastError}'\n")
            sb.append("\n== DISPLAYS ==\n").append(stage.describeDisplays()).append('\n')
            sb.append("\n== USB ==\n").append(st.descriptors)
            sb.append("\n== FACTORY CONFIG (from the glasses) ==\n").append(st.configJson ?: "not read").append('\n')
        }
        sb.append("\n== OPENXR ==\n").append(XrRuntime.statusLine(this)).append('\n')
        sb.append("\n== TIME SERIES (1 s) ==\n").append(series)
        sb.append("\n== LOG ==\n").append(Tracking.logText()).append('\n')
        return sb.toString()
    }

    private fun writeReport() { try { File(runDir ?: return, "report.txt").writeText(report()) } catch (_: Exception) {} }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Ultra XR hardware check").putExtra(Intent.EXTRA_TEXT, report()), "Share report"))
    }

    private fun save() {
        val dir = runDir ?: return
        writeReport()
        thread {
            val msg = try {
                val name = "ultra-xr-${dir.name}.zip"
                val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/zip") }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("no Downloads access")
                contentResolver.openOutputStream(uri)!!.use { os -> ZipOutputStream(os).use { zip -> for (f in dir.listFiles().orEmpty()) { zip.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() } } }
                "Saved to Downloads as $name (report plus raw sensor samples)."
            } catch (e: Exception) { "Could not save: $e" }
            ui.post { instruction.text = msg }
        }
    }

}
