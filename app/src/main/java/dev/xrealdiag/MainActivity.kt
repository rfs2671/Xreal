package dev.xrealdiag

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.Display
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.xrealdiag.display.HeadPose
import dev.xrealdiag.display.StereoPresentation
import dev.xrealdiag.display.StereoRenderer
import dev.xrealdiag.proto.CameraDecoder
import dev.xrealdiag.proto.McuProtocol
import dev.xrealdiag.proto.UvcDescriptors
import dev.xrealdiag.usb.HidLink
import dev.xrealdiag.usb.ImuReader
import dev.xrealdiag.usb.McuClient
import dev.xrealdiag.usb.UvcStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Hardware feasibility test for Pixel + XREAL Air 2 Ultra: IMU, SLAM cameras and
 * stereo display output, all at the same time, with a report to send back.
 */
class MainActivity : Activity() {
    companion object {
        const val VID = 0x3318
        const val PID_ULTRA = 0x0426
        const val MCU_INTERFACE = 0
        const val IMU_INTERFACE = 2
        private const val ACTION_USB = "dev.xrealdiag.USB_PERMISSION"
        private const val RAW_FRAMES_TO_DUMP = 8
        private const val IMU_REPORTS_TO_DUMP = 4000
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var usb: UsbManager
    private lateinit var displays: DisplayManager
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var leftView: ImageView
    private lateinit var rightView: ImageView

    private val logLines = StringBuilder()
    private val results = LinkedHashMap<String, String>()
    private val series = StringBuilder()
    private var runDir: File? = null

    @Volatile private var running = false
    private var conn: UsbDeviceConnection? = null
    private var mcu: McuClient? = null
    private var mcuLink: HidLink? = null
    private var imu: ImuReader? = null
    private var uvc: UvcStream? = null
    private var presentation: StereoPresentation? = null
    private val renderer = StereoRenderer()
    private var frameDump: FileOutputStream? = null
    private var imuDump: FileOutputStream? = null

    // Camera statistics (written on the UVC thread, read on the sampler).
    @Volatile private var camLeft = 0L
    @Volatile private var camRight = 0L
    @Volatile private var camNoMeta = 0L
    @Volatile private var camPairGaps = 0L
    @Volatile private var lastPairCounter = -1
    @Volatile private var lastT62 = 0L
    @Volatile private var camT62MinDelta = Long.MAX_VALUE
    @Volatile private var camT62MaxDelta = 0L
    @Volatile private var camMinusImuNs = 0L
    @Volatile private var seamLeft = Double.NaN
    @Volatile private var seamRight = Double.NaN
    @Volatile private var brightLeft = "-"
    @Volatile private var brightRight = "-"
    @Volatile private var dumpedFrames = 0
    private val eyeBuf = arrayOf(ByteArray(CameraDecoder.IMAGE_BYTES), ByteArray(CameraDecoder.IMAGE_BYTES))
    private val eyeBitmaps = arrayOfNulls<Bitmap>(2)
    private val eyePixels = IntArray(CameraDecoder.IMAGE_BYTES)

    // Sampler state.
    private var startNs = 0L
    private var prevSampleNs = 0L
    private var prevImu = 0L
    private var prevCam = 0L
    private var prevFrames = 0L
    private var prevBytes = 0L
    private var simultaneousSeconds = 0
    private var stereoConfirm = "not answered"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_USB) return
            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log("USB permission " + if (granted) "granted" else "DENIED")
            if (granted) findDevice()?.let { d -> thread(name = "diag") { runDiagnostic(d) } }
            else { result("usb.permission", "FAIL denied by user or system"); finishRun() }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { log("display added: $id"); if (running) ui.postDelayed({ showOnGlasses() }, 500) }
        override fun onDisplayRemoved(id: Int) { log("display removed: $id"); if (presentation?.display?.displayId == id) dismissPresentation() }
        override fun onDisplayChanged(id: Int) {
            val p = presentation ?: return
            if (p.display.displayId == id) log("display $id changed: ${describeMode(p.display)}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager
        displays = getSystemService(DISPLAY_SERVICE) as DisplayManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        val filter = IntentFilter(ACTION_USB)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(usbReceiver, filter)
        displays.registerDisplayListener(displayListener, ui)
        log("XREAL Ultra Diag ${packageManager.getPackageInfo(packageName, 0).versionName} on ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        log("Plug in the glasses, then press START.")
    }

    override fun onDestroy() {
        stopAll()
        unregisterReceiver(usbReceiver)
        displays.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        fun row(vararg b: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            b.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { action() } }
        root.addView(row(button("START") { start() }, button("Stop") { stopAll(); log("stopped") }, button("Save report") { saveReport() }))
        root.addView(row(
            button("3D 60Hz") { setMode(McuProtocol.MODE_SBS_60) },
            button("3D 90Hz") { setMode(McuProtocol.MODE_SBS_90) },
            button("2D") { setMode(McuProtocol.MODE_2D_60) },
        ))
        root.addView(TextView(this).apply { text = "In the glasses: does each eye see ONE corner marker (left red, right blue) and squares at different depths?" })
        root.addView(row(
            button("Yes, 3D") { stereoConfirm = "user: yes, separate eyes and depth"; log("stereo confirm: yes") },
            button("No") { stereoConfirm = "user: no"; log("stereo confirm: no") },
            button("Share report") { shareReport() },
        ))
        val eyes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftView = ImageView(this).apply { adjustViewBounds = true }
        rightView = ImageView(this).apply { adjustViewBounds = true }
        eyes.addView(leftView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        eyes.addView(rightView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(eyes)
        statusView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(statusView)
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 10f; setTextIsSelectable(true) }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun log(msg: String) {
        val line = "%7.2f  %s".format(if (startNs == 0L) 0.0 else (System.nanoTime() - startNs) / 1e9, msg)
        synchronized(logLines) { logLines.append(line).append('\n') }
        ui.post { logView.text = synchronized(logLines) { logLines.toString() } }
    }

    private fun result(key: String, value: String) {
        synchronized(results) { results[key] = value }
        log("[$key] $value")
    }

    // ---------------------------------------------------------------- start / stop

    private fun start() {
        if (running) { log("already running"); return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log("requesting CAMERA permission (Android requires it before granting a USB video device)")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        running = true
        startNs = System.nanoTime()
        synchronized(results) { results.clear() }
        series.setLength(0)
        series.append("t_s,imu_hz,cam_frames_hz,cam_left,cam_right,cam_mbit_s,render_fps,late_frames,battery_pct,current_uA,voltage_mV,batt_temp_C,thermal_status,thermal_headroom\n")
        runDir = File(getExternalFilesDir(null), "run-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())).apply { mkdirs() }
        HeadPose.reset()
        logAllUsbDevices()
        logCamera2()
        logDisplays()
        val device = findDevice()
        if (device == null) {
            result("usb.device", "FAIL no XREAL device (vid 0x3318) visible to Android USB host")
            finishRun(); return
        }
        result("usb.device", "PASS %04x:%04x %s%s".format(device.vendorId, device.productId, device.productName ?: "?",
            if (device.productId != PID_ULTRA) " (WARNING: not the Air 2 Ultra product id 0x0426)" else ""))
        if (usb.hasPermission(device)) thread(name = "diag") { runDiagnostic(device) }
        else {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(device, pi)
            log("waiting for USB permission dialog...")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else log("CAMERA permission denied: the camera test cannot run")
    }

    private fun findDevice(): UsbDevice? {
        val all = usb.deviceList.values
        return all.firstOrNull { it.vendorId == VID && it.productId == PID_ULTRA } ?: all.firstOrNull { it.vendorId == VID }
    }

    private fun finishRun() { running = false; writeReportFile() }

    private fun stopAll() {
        val wasRunning = running
        running = false
        ui.removeCallbacks(sampler)
        dismissPresentation()
        thread(name = "teardown") {
            try { uvc?.stop() } catch (e: Exception) { log("camera stop: $e") }
            try { imu?.stop() } catch (e: Exception) { log("imu stop: $e") }
            try { mcuLink?.release() } catch (_: Exception) {}
            try { frameDump?.close(); imuDump?.close() } catch (_: Exception) {}
            try { conn?.close() } catch (_: Exception) {}
            uvc = null; imu = null; mcu = null; mcuLink = null; conn = null; frameDump = null; imuDump = null
            if (wasRunning) writeReportFile()
        }
    }

    // ---------------------------------------------------------------- the test

    private fun runDiagnostic(device: UsbDevice) {
        try {
            val c = usb.openDevice(device)
            if (c == null) { result("usb.open", "FAIL openDevice returned null"); finishRun(); return }
            conn = c
            result("usb.open", "PASS")
            describeDevice(device, c)

            // --- MCU: display mode
            try {
                val f = HidLink.find(device, MCU_INTERFACE)
                if (f == null) result("mcu", "FAIL interface $MCU_INTERFACE not found")
                else {
                    val link = HidLink(c, f)
                    mcuLink = link
                    val m = McuClient(link)
                    mcu = m
                    if (!link.claimed) result("mcu", "FAIL could not claim interface $MCU_INTERFACE")
                    else {
                        val mode = m.getDisplayMode()
                        if (mode == null) result("mcu", "FAIL no reply to get-display-mode (write path ${m.lastWritePath})")
                        else {
                            result("mcu", "PASS display mode = $mode (${McuProtocol.modeName(mode)}), write path ${m.lastWritePath}")
                            m.command(McuProtocol.CMD_GET_SERIAL)?.let { log("glasses serial: " + String(it, Charsets.ISO_8859_1).trim { ch -> ch.code < 0x20 }) }
                            if (mode != McuProtocol.MODE_SBS_60 && mode != McuProtocol.MODE_SBS_90) {
                                val ok = m.setDisplayMode(McuProtocol.MODE_SBS_60)
                                result("mcu.set_sbs", if (ok) "PASS glasses accepted SBS 60 Hz" else "FAIL glasses rejected SBS mode")
                            } else result("mcu.set_sbs", "PASS already in an SBS mode")
                        }
                    }
                }
            } catch (e: Exception) { result("mcu", "FAIL exception $e") }

            // --- IMU
            try {
                val f = HidLink.find(device, IMU_INTERFACE)
                if (f == null) result("imu.open", "FAIL interface $IMU_INTERFACE not found")
                else {
                    val link = HidLink(c, f)
                    if (!link.claimed || link.epIn == null) result("imu.open", "FAIL claimed=${link.claimed} interruptIn=${link.epIn != null}")
                    else {
                        imuDump = FileOutputStream(File(runDir, "imu_reports.bin"))
                        val r = ImuReader(link) { s, _ -> HeadPose.integrate(s.gyro, s.timestampNs) }
                        r.dump = imuDump; r.dumpLimit = IMU_REPORTS_TO_DUMP
                        imu = r
                        r.start()
                        result("imu.open", "PASS reading interface $IMU_INTERFACE")
                    }
                }
            } catch (e: Exception) { result("imu.open", "FAIL exception $e") }

            // --- Cameras
            try {
                val info = c.rawDescriptors?.let { UvcDescriptors.parse(it) }
                if (info == null) result("camera.open", "FAIL no USB video (UVC) interface in the descriptors")
                else {
                    log("camera: UVC %x.%02x VC=%d VS=%d".format(info.bcdUvc shr 8, info.bcdUvc and 0xFF, info.controlInterface, info.streamInterface))
                    for (f in info.formats) log("camera: format#${f.index} ${f.guid} bpp=${f.bitsPerPixel} frames=" +
                        f.frames.joinToString { "#${it.index} ${it.width}x${it.height}@" + it.intervals100ns.joinToString("/") { iv -> "%.0f".format(1e7 / iv) } })
                    for (e in info.endpoints) log("camera: alt=${e.altSetting} ep=0x%02x %s maxPacket=0x%04x (%d B/interval)".format(
                        e.address, if (e.isIso) "iso" else if (e.isBulk) "bulk" else "other", e.wMaxPacketSize, e.payloadPerInterval))
                    frameDump = FileOutputStream(File(runDir, "camera_raw_frames.bin"))
                    val s = UvcStream(device, c, info, ::log)
                    uvc = s
                    val err = s.start(640, 241, CameraDecoder.FRAME_BYTES, ::onCameraFrame)
                    result("camera.open", if (err == null) "PASS streaming via ${s.transport}; ${s.chosen}" else "FAIL $err")
                }
            } catch (e: Throwable) { result("camera.open", "FAIL exception $e") }

            // --- Display (the glasses re-announce themselves after a mode switch)
            ui.postDelayed({ showOnGlasses() }, 1500)
            prevSampleNs = System.nanoTime()
            ui.postDelayed(sampler, 1000)
        } catch (e: Throwable) {
            result("diagnostic", "FAIL unexpected $e")
            finishRun()
        }
    }

    private fun onCameraFrame(frame: ByteArray) {
        if (dumpedFrames < RAW_FRAMES_TO_DUMP) {
            try { frameDump?.write(frame) } catch (_: Exception) {}
            dumpedFrames++
        }
        val meta = CameraDecoder.parseMeta(frame)
        if (meta == null) { camNoMeta++; return }
        val eye = if (meta.isRight) 1 else 0
        if (meta.isRight) camRight++ else camLeft++
        if (lastT62 != 0L && meta.t62Ns != lastT62) {
            val d = meta.t62Ns - lastT62
            if (d in 1 until camT62MinDelta) camT62MinDelta = d
            if (d > camT62MaxDelta) camT62MaxDelta = d
            if (lastPairCounter >= 0 && ((meta.pairCounter - lastPairCounter) and 0xFFFF) > 1) camPairGaps++
        }
        lastT62 = meta.t62Ns; lastPairCounter = meta.pairCounter
        imu?.let { if (it.lastDeviceTs != 0L) camMinusImuNs = meta.t62Ns - it.lastDeviceTs }
        val n = if (meta.isRight) camRight else camLeft
        if (n % 6 != 1L) return // decode/preview ~5 Hz per eye; streaming cost is measured separately
        val out = eyeBuf[eye]
        if (!CameraDecoder.decode(frame, out)) return
        val seam = CameraDecoder.seamScore(out)
        val (mean, sd) = CameraDecoder.brightness(out)
        if (eye == 0) { seamLeft = seam; brightLeft = "%.0f±%.0f".format(mean, sd) } else { seamRight = seam; brightRight = "%.0f±%.0f".format(mean, sd) }
        ui.post {
            val bmp = eyeBitmaps[eye] ?: Bitmap.createBitmap(CameraDecoder.EYE_W, CameraDecoder.EYE_H, Bitmap.Config.ARGB_8888).also { eyeBitmaps[eye] = it }
            synchronized(eyePixels) {
                for (i in eyePixels.indices) { val v = out[i].toInt() and 0xFF; eyePixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                bmp.setPixels(eyePixels, 0, CameraDecoder.EYE_W, 0, 0, CameraDecoder.EYE_W, CameraDecoder.EYE_H)
            }
            (if (eye == 0) leftView else rightView).apply { setImageBitmap(bmp); invalidate() }
        }
    }

    // ---------------------------------------------------------------- display

    private fun externalDisplay(): Display? =
        displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
            ?: displays.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    private fun describeMode(d: Display) = d.mode.let { "${it.physicalWidth}x${it.physicalHeight}@%.1f".format(it.refreshRate) }

    private fun logDisplays() {
        for (d in displays.displays) {
            log("display ${d.displayId} '${d.name}' flags=0x%x current=%s modes=[%s]".format(d.flags, describeMode(d),
                d.supportedModes.joinToString { "#${it.modeId} ${it.physicalWidth}x${it.physicalHeight}@%.0f".format(it.refreshRate) }))
        }
    }

    private fun showOnGlasses() {
        if (!running) return
        val d = externalDisplay()
        if (d == null) {
            result("display.external", "FAIL Android reports no external display (no DisplayPort output, or the phone is not exposing it)")
            return
        }
        logDisplays()
        val sbs = d.supportedModes.filter { it.physicalWidth == 3840 && it.physicalHeight == 1080 }.maxByOrNull { it.refreshRate }
        result("display.external", "PASS display ${d.displayId} '${d.name}' current ${describeMode(d)}; " +
            if (sbs != null) "3840x1080 mode offered (#${sbs.modeId} @%.0f)".format(sbs.refreshRate) else "NO 3840x1080 mode offered by Android")
        dismissPresentation()
        try {
            renderer.resetStats()
            renderer.periodNs = (1e9 / (sbs?.refreshRate ?: d.refreshRate)).toLong()
            val p = StereoPresentation(this, d, sbs?.modeId ?: 0, renderer)
            p.show()
            presentation = p
            result("display.presentation", "PASS window shown on display ${d.displayId}")
        } catch (e: Exception) {
            result("display.presentation", "FAIL $e")
        }
    }

    private fun dismissPresentation() {
        try { presentation?.dismiss() } catch (_: Exception) {}
        presentation = null
    }

    private fun setMode(mode: Int) {
        val m = mcu
        if (m == null) { log("MCU not open - press START first"); return }
        thread {
            val ok = m.setDisplayMode(mode)
            log("set display mode ${McuProtocol.modeName(mode)}: " + if (ok) "accepted" else "REJECTED / no reply")
            log("glasses now report mode: " + (m.getDisplayMode()?.let { McuProtocol.modeName(it) } ?: "no reply"))
            ui.postDelayed({ showOnGlasses() }, 2500)
        }
    }

    // ---------------------------------------------------------------- sampling

    private val sampler = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dt = (now - prevSampleNs) / 1e9
            prevSampleNs = now
            val im = imu; val cam = uvc
            val imuN = im?.samples ?: 0
            val camN = camLeft + camRight
            val frN = renderer.frames
            val byN = cam?.bytes ?: 0
            val imuHz = (imuN - prevImu) / dt
            val camHz = (camN - prevCam) / dt
            val fps = (frN - prevFrames) / dt
            val mbit = (byN - prevBytes) * 8 / dt / 1e6
            prevImu = imuN; prevCam = camN; prevFrames = frN; prevBytes = byN
            if (imuHz > 50 && camHz > 5 && fps > 20) simultaneousSeconds++

            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val uA = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val mV = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val thermal = pm.currentThermalStatus
            val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else Float.NaN
            val t = (now - startNs) / 1e9
            series.append("%.1f,%.0f,%.1f,%d,%d,%.1f,%.1f,%d,%d,%d,%d,%.1f,%d,%.2f\n".format(Locale.US,
                t, imuHz, camHz, camLeft, camRight, mbit, fps, renderer.lateFrames, pct, uA, mV, tempC, thermal, headroom))

            val asm = cam?.assembler
            val sb = StringBuilder()
            sb.append("t=%.0fs  all three streams live for %d s\n".format(t, simultaneousSeconds))
            sb.append("IMU    %.0f Hz  samples=%d other=%d size=%d start=%s(%d)\n".format(imuHz, imuN, im?.otherReports ?: 0, im?.lastReportSize ?: 0, im?.startPath ?: "-", im?.startResult ?: 0))
            sb.append("CAMERA %.1f frames/s  L=%d R=%d  %.1f Mbit/s\n".format(camHz, camLeft, camRight, mbit))
            sb.append("       short=%d long=%d errPayload=%d badIso=%d xferErr=%d noMeta=%d\n".format(
                asm?.shortFrames ?: 0, asm?.longFrames ?: 0, asm?.errorPayloads ?: 0, cam?.badIsoPackets ?: 0, cam?.transferErrors ?: 0, camNoMeta))
            sb.append("       seam L=%.2f R=%.2f (≈1 good)  brightness L=%s R=%s\n".format(seamLeft, seamRight, brightLeft, brightRight))
            sb.append("       cam-imu device clock offset %.1f ms  pairGaps=%d\n".format(camMinusImuNs / 1e6, camPairGaps))
            sb.append("RENDER %.1f fps  surface %dx%d  late=%d  max gap %.1f ms\n".format(fps, renderer.width, renderer.height, renderer.lateFrames, renderer.maxIntervalNs / 1e6))
            sb.append("POWER  %d%%  %d uA  %d mV  batt %.1f C  thermal=%d headroom=%.2f\n".format(pct, uA, mV, tempC, thermal, headroom))
            cam?.fatal?.let { sb.append("CAMERA FATAL: $it\n") }
            statusView.text = sb
            if (t.toInt() % 30 == 0) writeReportFile()
            ui.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- environment dumps

    private fun logAllUsbDevices() {
        val list = usb.deviceList.values
        log("USB devices visible: ${list.size}")
        for (d in list) log("  %04x:%04x '%s' class=%d interfaces=%d".format(d.vendorId, d.productId, d.productName ?: "?", d.deviceClass, d.interfaceCount))
    }

    private fun logCamera2() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            val ext = ids.filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL }
            log("Camera2: ${ids.size} cameras, external (UVC via Android's own camera stack): " + if (ext.isEmpty()) "none" else ext.joinToString())
        } catch (e: Exception) { log("Camera2 query failed: $e") }
    }

    private fun describeDevice(d: UsbDevice, c: UsbDeviceConnection) {
        log("device '%s' by '%s' usb=%s configs=%d".format(d.productName ?: "?", d.manufacturerName ?: "?", d.version, d.configurationCount))
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            val eps = (0 until f.endpointCount).joinToString { val e = f.getEndpoint(it); "0x%02x/%s/%d".format(e.address, arrayOf("ctrl", "iso", "bulk", "int")[e.type], e.maxPacketSize) }
            log("  if#${f.id} alt=${f.alternateSetting} class=${f.interfaceClass}/${f.interfaceSubclass}/${f.interfaceProtocol} '${f.name ?: ""}' eps=[$eps]")
        }
        c.rawDescriptors?.let { raw -> log("raw descriptors (${raw.size} bytes): " + raw.joinToString("") { "%02x".format(it) }) }
    }

    // ---------------------------------------------------------------- report

    private fun buildReport(): String {
        val sb = StringBuilder()
        sb.append("XREAL Ultra Diag report\n")
        sb.append("phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT} build ${Build.DISPLAY}\n")
        sb.append("soc: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else Build.HARDWARE}\n")
        val im = imu; val cam = uvc; val asm = cam?.assembler
        val secs = if (startNs == 0L) 0.0 else (System.nanoTime() - startNs) / 1e9
        sb.append("run length: %.0f s\n\n== VERDICTS ==\n".format(secs))
        synchronized(results) { for ((k, v) in results) sb.append("$k: $v\n") }
        if (im != null) {
            val span = (im.lastHostNs - im.firstHostNs) / 1e9
            val hz = if (span > 0) im.samples / span else 0.0
            val devSpan = (im.lastDeviceTs - im.firstDeviceTs) / 1e9
            sb.append("imu.stream: ${if (im.samples > 100 && hz > 50) "PASS" else "FAIL"} %.0f Hz average, %d samples in %.1f s host / %.1f s device clock, max gap %.2f ms, report size %d, start via %s\n"
                .format(hz, im.samples, span, devSpan, im.maxDeviceGapNs / 1e6, im.lastReportSize, im.startPath))
        }
        if (cam != null && asm != null) {
            val good = asm.goodFrames
            sb.append("camera.frames: ${if (camLeft > 30 && camRight > 30) "PASS" else "FAIL"} L=$camLeft R=$camRight complete=$good short=${asm.shortFrames} long=${asm.longFrames} " +
                "errPayloads=${asm.errorPayloads} badHeaders=${asm.badHeaders} badIso=${cam.badIsoPackets} noMeta=$camNoMeta rejectedSizes=${asm.rejectedSizes}\n")
            val seamOk = seamLeft < 1.6 && seamRight < 1.6
            sb.append("camera.decode: ${if (seamLeft.isNaN() || seamRight.isNaN()) "UNKNOWN (no image or flat image)" else if (seamOk) "PASS" else "FAIL"} seam L=%.2f R=%.2f brightness L=%s R=%s\n"
                .format(seamLeft, seamRight, brightLeft, brightRight))
            sb.append("camera.timing: frame-pair interval min %.2f ms max %.2f ms, pair counter gaps %d, cam-imu device clock offset %.1f ms\n"
                .format(if (camT62MinDelta == Long.MAX_VALUE) 0.0 else camT62MinDelta / 1e6, camT62MaxDelta / 1e6, camPairGaps, camMinusImuNs / 1e6))
        }
        val rSpan = (renderer.lastFrameNs - renderer.firstFrameNs) / 1e9
        val fps = if (rSpan > 0) renderer.frames / rSpan else 0.0
        val sbsSurface = renderer.width == 3840 && renderer.height == 1080
        sb.append("display.stereo_surface: ${if (sbsSurface) "PASS" else "FAIL"} rendering to ${renderer.width}x${renderer.height} (needs 3840x1080 for full side-by-side)\n")
        sb.append("display.render: %.1f fps average over %.0f s, late frames %d, max gap %.1f ms\n".format(fps, rSpan, renderer.lateFrames, renderer.maxIntervalNs / 1e6))
        sb.append("display.stereo_seen: $stereoConfirm\n")
        sb.append("simultaneous: ${if (simultaneousSeconds >= 10) "PASS" else "FAIL"} IMU + camera + rendering all live together for $simultaneousSeconds one-second samples (needs 10)\n")
        sb.append("\n== TIME SERIES (1 s) ==\n").append(series)
        sb.append("\n== LOG ==\n").append(synchronized(logLines) { logLines.toString() })
        return sb.toString()
    }

    private fun writeReportFile() {
        val dir = runDir ?: return
        try { File(dir, "report.txt").writeText(buildReport()) } catch (e: Exception) { log("report write failed: $e") }
    }

    /** Zips the run folder (report, raw camera frames, raw IMU reports) into Downloads. */
    private fun saveReport() {
        val dir = runDir
        if (dir == null) { log("nothing to save yet - press START first"); return }
        writeReportFile()
        thread {
            try {
                try { frameDump?.flush(); imuDump?.flush() } catch (_: Exception) {}
                val name = "xreal-diag-${dir.name}.zip"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
                contentResolver.openOutputStream(uri)!!.use { os ->
                    ZipOutputStream(os).use { zip ->
                        for (f in dir.listFiles().orEmpty()) {
                            zip.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                    }
                }
                log("saved Downloads/$name")
            } catch (e: Exception) { log("save failed: $e") }
        }
    }

    private fun shareReport() {
        val text = buildReport()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostic report"))
    }

}
