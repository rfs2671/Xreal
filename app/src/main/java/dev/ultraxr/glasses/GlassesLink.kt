package dev.ultraxr.glasses

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import dev.ultraxr.core.SensorPipeline
import dev.ultraxr.proto.CameraDecoder
import dev.ultraxr.proto.ImuCodec
import dev.ultraxr.proto.McuProtocol
import dev.ultraxr.proto.UvcDescriptors
import dev.ultraxr.usb.HidLink
import dev.ultraxr.usb.McuClient
import dev.ultraxr.usb.UvcStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Owns the USB connection to the glasses: detection, permission, display mode, IMU stream,
 * SLAM camera stream, timestamp mapping onto the phone clock, and automatic reconnection.
 * All sensor output is delivered through [Listener] on the stream threads.
 */
class GlassesLink(private val context: Context, private val listener: Listener) {
    enum class State { NO_GLASSES, NEED_PERMISSION, CONNECTING, STREAMING, FAILED }

    interface Listener {
        fun onState(state: State, detail: String)
        /** IMU sample on the host clock (ns), IMU frame, rad/s and m/s^2. */
        fun onImu(tNs: Long, gyro: FloatArray, accel: FloatArray)
        /** Synchronized stereo pair, 480x640 grey each, upright. Buffers are reused: copy if kept. */
        fun onStereo(tNs: Long, left: ByteArray, right: ByteArray)
        /** The glasses' clock restarted or the link was re-established: restart tracking. */
        fun onStreamRestart()
        fun onLog(msg: String)
    }

    class Stats {
        @Volatile var imuWritePath = ""
        @Volatile var transport = ""
        @Volatile var cameraFormat = ""
        @Volatile var displayMode = -1
        /** Mode the glasses reported when the connection opened, before this app changed anything. */
        @Volatile var displayModeAtConnect = -1
        /** True once this app gave up on 3D because the phone never offered a side-by-side mode. */
        @Volatile var sbsFellBack = false
        @Volatile var mcuWritePath = ""
        /** Answers to the read-only status queries, one per line. */
        @Volatile var controllerStatus = ""
        private val history = ArrayDeque<String>()
        private val events = LinkedHashMap<Int, String>()
        private val t0 = System.nanoTime()
        fun note(msg: String) = synchronized(history) { history.addLast("t+" + dev.ultraxr.core.Fmt.f((System.nanoTime() - t0) / 1e9, 1) + "s " + msg); while (history.size > 60) history.removeFirst() }
        fun event(cmd: Int, payload: ByteArray) = synchronized(history) {
            val n = (events[cmd]?.substringBefore(' ')?.toIntOrNull() ?: 0) + 1
            events[cmd] = "$n times (" + McuProtocol.eventName(cmd) + "), last payload " + payload.take(12).joinToString("") { dev.ultraxr.core.Fmt.hex(it.toInt() and 0xFF) }
            // Screen/panel power changes and the firmware's own log lines are kept in order of arrival.
            if (cmd == McuProtocol.EVT_DISPLAY_TOGGLED || cmd == McuProtocol.EVT_PANEL_POWER)
                history.addLast("t+" + dev.ultraxr.core.Fmt.f((System.nanoTime() - t0) / 1e9, 1) + "s glasses say: " + McuProtocol.eventName(cmd) + " = " + (payload.firstOrNull()?.toInt() ?: -1))
            if (cmd == McuProtocol.EVT_TEXT_LOG) {
                val text = String(payload, Charsets.ISO_8859_1).filter { it.code in 0x20..0x7E }
                if (text.isNotBlank()) history.addLast("t+" + dev.ultraxr.core.Fmt.f((System.nanoTime() - t0) / 1e9, 1) + "s glasses log: " + text)
            }
            while (history.size > 120) history.removeFirst()
            while (events.size > 24) events.remove(events.keys.first())
        }
        private val reconnectReasons = ArrayDeque<String>()
        fun noteReconnect(why: String) = synchronized(reconnectReasons) { reconnectReasons.addLast("t+" + dev.ultraxr.core.Fmt.f((System.nanoTime() - t0) / 1e9, 1) + "s " + why); while (reconnectReasons.size > 20) reconnectReasons.removeFirst() }
        fun reconnectHistory(): String = synchronized(reconnectReasons) { if (reconnectReasons.isEmpty()) "none" else reconnectReasons.joinToString("\n") }
        /** Every display-mode read and write on the glasses' controller, and unsolicited messages from it. */
        fun displayHistory(): String = synchronized(history) {
            history.joinToString("\n") + (if (events.isEmpty()) "" else "\nunsolicited controller messages:\n" + events.entries.joinToString("\n") { "  cmd 0x" + dev.ultraxr.core.Fmt.hex(it.key, 4) + ": " + it.value })
        }
        @Volatile var mcuOk = false
        @Volatile var reconnects = 0
        @Volatile var serial = ""
        @Volatile var productId = 0
        @Volatile var descriptors = ""
        @Volatile var configJson: String? = null
        @Volatile var lastError = ""
    }

    val stats = Stats()
    @Volatile var state = State.NO_GLASSES; private set
    @Volatile var stateDetail = ""; private set
    /** Requested panel mode; applied on connect and when changed. */
    @Volatile var wantedDisplayMode = McuProtocol.MODE_SBS_60
    /** If set, the first raw camera frames and IMU reports of the next connection are written here. */
    @Volatile var captureDir: File? = null
    /** Decoding, clock mapping and pairing; also carries the stream counters. */
    val pipeline = SensorPipeline(object : SensorPipeline.Sink {
        override fun onImu(tNs: Long, gyro: FloatArray, accel: FloatArray) = listener.onImu(tNs, gyro, accel)
        override fun onStereo(tNs: Long, left: ByteArray, right: ByteArray) = listener.onStereo(tNs, left, right)
        override fun onRestart() = listener.onStreamRestart()
    })
    val clock get() = pipeline.clock

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val worker = HandlerThread("glasses").apply { start() }
    private val handler = Handler(worker.looper)
    private var started = false

    private var conn: UsbDeviceConnection? = null
    private var mcuLink: HidLink? = null
    private var mcu: McuClient? = null
    private var imuLink: HidLink? = null
    private var imuThread: Thread? = null
    @Volatile private var imuRunning = false
    private var uvc: UvcStream? = null
    private var permissionAsked: String? = null
    private var permissionDenied = false
    private var healthySeconds = 0
    private var camerasDown = false
    private var sbsWaitSeconds = 0
    private var pollTick = 0
    /** Supplied by the owner: is an external display currently in a side-by-side mode? */
    @Volatile var sideBySideDisplayPresent: (() -> Boolean)? = null
    private var failures = 0

    private var watchImu = 0L
    private var watchPairs = 0L
    private var watchStallSeconds = 0
    private var frameDump: FileOutputStream? = null
    private var imuDump: FileOutputStream? = null
    private var dumpedFrames = 0
    private var dumpedReports = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERMISSION -> {
                    // On a refusal keep the request marked as asked: asking again straight away would
                    // loop (Android answers "denied" instantly when the camera permission is missing).
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    handler.post { if (granted) { permissionAsked = null; connectIfPossible() } else { permissionDenied = true; setState(State.NEED_PERMISSION, "USB access was not granted") } }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handler.post { failures = 0; permissionAsked = null; permissionDenied = false; connectIfPossible() }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handler.post { if (findDevice() == null) { teardown(); setState(State.NO_GLASSES, "") } }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        val f = IntentFilter().apply { addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, f)
        handler.post { connectIfPossible() }
        handler.postDelayed(watchdog, 1000)
    }

    fun stop() {
        if (!started) return
        started = false
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        // Same handler as connect, so a later start() cannot overtake this teardown.
        handler.post { teardown(); setState(State.NO_GLASSES, "") }
    }

    /** Try again after a failure (also called when the app gains camera permission). */
    fun retry() { handler.post { failures = 0; permissionAsked = null; permissionDenied = false; if (state != State.STREAMING && state != State.CONNECTING) connectIfPossible() } }

    /** Drop the connection and open it again (used by the hardware check to capture a clean start). */
    fun reconnect() { handler.post { teardown(); failures = 0; setState(State.CONNECTING, ""); connectIfPossible() } }

    fun applyDisplayMode(mode: Int) {
        wantedDisplayMode = mode
        handler.post {
            val m = mcu ?: return@post
            val ok = m.setDisplayMode(mode)
            stats.displayMode = m.getDisplayMode() ?: -1
            stats.note("set " + McuProtocol.modeName(mode) + ": " + (if (ok) "accepted" else "REJECTED or no answer") + "; glasses now report " + McuProtocol.modeName(stats.displayMode))
            sbsWaitSeconds = 0
            listener.onLog("display mode ${McuProtocol.modeName(mode)}: " + if (ok) "accepted" else "rejected")
        }
    }

    /** Reads what the controller will tell about itself. Read-only commands; results go to the report. */
    fun readControllerStatus() {
        handler.post {
            val m = mcu ?: return@post
            var unanswered = 0
            val sb = StringBuilder()
            for ((name, cmd) in McuProtocol.STATUS_QUERIES) {
                val r = m.command(cmd)
                sb.append(name).append(" (0x").append(dev.ultraxr.core.Fmt.hex(cmd)).append("): ")
                if (r == null) { sb.append("no answer"); if (++unanswered >= 3) { sb.append("\n(stopped: the controller is not answering)\n"); break } } else {
                    sb.append(r.joinToString("") { dev.ultraxr.core.Fmt.hex(it.toInt() and 0xFF) })
                    val text = String(r, Charsets.ISO_8859_1).filter { it.code in 0x20..0x7E }
                    if (text.length >= 3) sb.append(" \"").append(text).append('"')
                }
                sb.append('\n')
            }
            stats.controllerStatus = sb.toString()
            stats.note("controller status read")
        }
    }

    /** Read-only: version reads in 512-byte reports, result appended to the controller status. */
    fun probeWideReports() {
        handler.post {
            val m = mcu ?: return@post
            val out = m.probeWideReports()
            stats.controllerStatus = stats.controllerStatus + "\n512-byte report test (read-only version reads):\n" + out
            stats.note("512-byte report test done")
        }
    }

    /**
     * Writes the display mode the glasses already report once more. On the Air family a mode
     * write makes the glasses drop and re-raise hot-plug, so the phone restarts the video link.
     */
    fun rewriteCurrentMode() {
        handler.post {
            val m = mcu ?: return@post
            val cur = m.getDisplayMode() ?: run { stats.note("re-run link start: the controller did not answer"); return@post }
            val ok = m.setDisplayMode(cur)
            stats.note("re-run link start: wrote " + McuProtocol.modeName(cur) + " again: " + if (ok) "accepted" else "REJECTED")
        }
    }

    /** Sets the panels to their brightest level, if the brightness command is known for this model. */
    fun setBrightnessMax() {
        handler.post {
            val m = mcu ?: return@post
            val cmd = McuProtocol.CMD_SET_BRIGHTNESS
            if (cmd < 0) { stats.note("brightness: no known command for this model, nothing sent"); return@post }
            val before = m.command(McuProtocol.CMD_GET_BRIGHTNESS)
            val r = m.command(cmd, byteArrayOf(McuProtocol.BRIGHTNESS_MAX.toByte()))
            val after = m.command(McuProtocol.CMD_GET_BRIGHTNESS)
            fun hx(b: ByteArray?) = b?.joinToString("") { dev.ultraxr.core.Fmt.hex(it.toInt() and 0xFF) } ?: "no answer"
            stats.note("brightness: before " + hx(before) + ", set max answered " + hx(r) + ", after " + hx(after))
        }
    }

    private fun findDevice(): UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == VID && it.productId == PID_ULTRA }

    private fun setState(s: State, detail: String) {
        if (s == state && detail == stateDetail) return
        state = s; stateDetail = detail
        listener.onState(s, detail)
    }

    private fun connectIfPossible() {
        if (!started || conn != null) return
        val d = findDevice()
        if (d == null) {
            val other = usb.deviceList.values.firstOrNull { it.vendorId == VID }
            setState(State.NO_GLASSES, if (other != null) "Found XREAL device %04x, but only the Air 2 Ultra (0426) is supported".format(other.productId) else "")
            return
        }
        if (!usb.hasPermission(d)) {
            val camera = context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!permissionDenied) setState(State.NEED_PERMISSION, if (camera) "" else "Camera permission needed first")
            // Android refuses USB access to a device with a video interface until CAMERA is held.
            if (camera && !permissionDenied && permissionAsked != d.deviceName) {
                permissionAsked = d.deviceName
                val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                try { usb.requestPermission(d, pi) } catch (e: Exception) { listener.onLog("USB permission request failed: $e") }
            }
            return
        }
        setState(State.CONNECTING, "")
        val err = try { connect(d) } catch (e: Throwable) { "unexpected: $e" }
        if (err == null) {
            watchStallSeconds = 0; healthySeconds = 0; camerasDown = false
            watchImu = pipeline.imuSamples; watchPairs = pipeline.cameraFrames
            setState(State.STREAMING, "")
        } else {
            stats.lastError = err
            listener.onLog("connect failed: $err")
            teardown()
            failures++
            if (failures <= MAX_RETRIES) { setState(State.CONNECTING, "Retrying ($failures)"); handler.postDelayed({ connectIfPossible() }, 800L * failures) }
            else setState(State.FAILED, err)
        }
    }

    /** Returns null on success, else what went wrong. */
    private fun connect(d: UsbDevice): String? {
        val c = usb.openDevice(d) ?: return "Android refused to open the glasses"
        conn = c
        stats.productId = d.productId
        stats.descriptors = describe(d, c)
        pipeline.reset()
        listener.onStreamRestart()
        openCapture()

        // Display controller: put the panels in side-by-side 3D.
        HidLink.find(d, MCU_INTERFACE)?.let { f ->
            val link = HidLink(c, f)
            if (link.claimed) {
                mcuLink = link
                val m = McuClient(link)
                m.onOther = { cmd, payload -> stats.event(cmd, payload) }
                mcu = m
                val mode = m.getDisplayMode()
                stats.mcuOk = mode != null
                stats.mcuWritePath = m.lastWritePath
                stats.displayModeAtConnect = mode ?: -1
                stats.note("connect: glasses report " + (mode?.let { McuProtocol.modeName(it) } ?: "no answer") + ", wanted " + McuProtocol.modeName(wantedDisplayMode))
                if (mode != null) {
                    m.command(McuProtocol.CMD_GET_SERIAL)?.let { s -> stats.serial = String(s, Charsets.ISO_8859_1).filter { ch -> ch.code in 0x21..0x7E } }
                    if (mode != wantedDisplayMode) {
                        val ok = m.setDisplayMode(wantedDisplayMode)
                        stats.note("set " + McuProtocol.modeName(wantedDisplayMode) + ": " + if (ok) "accepted" else "REJECTED or no answer")
                    }
                    stats.displayMode = m.getDisplayMode() ?: mode
                    stats.note("glasses now report " + McuProtocol.modeName(stats.displayMode))
                    sbsWaitSeconds = 0
                    readControllerStatus() // read-only fingerprint for the report, after the connection is up
                } else listener.onLog("display controller did not answer")
            }
        }

        // IMU: read the factory configuration once (stream must be off), then start streaming.
        val imuIf = HidLink.find(d, IMU_INTERFACE) ?: return "IMU interface not found"
        val il = HidLink(c, imuIf)
        if (!il.claimed || il.epIn == null) return "Could not claim the IMU interface"
        imuLink = il
        if (stats.configJson == null) {
            try { stats.configJson = readConfig(il) } catch (e: Exception) { listener.onLog("factory config read failed: $e") }
        }
        val (n, path) = il.write(ImuCodec.streamCommand(true).copyOf(64))
        stats.imuWritePath = "$path ($n)"
        if (n < 0) return "Could not send the IMU start command"
        imuRunning = true
        imuThread = thread(name = "imu") { imuLoop(il) }

        // SLAM cameras.
        val info = c.rawDescriptors?.let { UvcDescriptors.parse(it) } ?: return "The glasses expose no USB video interface"
        val s = UvcStream(d, c, info) { listener.onLog(it) }
        uvc = s
        val err = s.start(640, 241, CameraDecoder.FRAME_BYTES, ::onFrame)
        stats.transport = s.transport; stats.cameraFormat = s.chosen
        if (err != null) return "Camera: $err"
        return null
    }

    private fun hidCommand(link: HidLink, cmd: Int, data: ByteArray = ByteArray(0)): ByteArray? {
        if (link.write(ImuCodec.command(cmd, data).copyOf(64)).first < 0) return null
        val buf = ByteArray(1024)
        val deadline = System.nanoTime() + 1_200_000_000L
        while (System.nanoTime() < deadline) {
            val n = link.read(buf, 150)
            if (n > 0) ImuCodec.parseResponse(buf, n)?.let { (id, d) -> if (id == cmd) return d }
        }
        return null
    }

    private fun readConfig(link: HidLink): String? {
        hidCommand(link, ImuCodec.CMD_STREAM, byteArrayOf(0))
        val lenBytes = hidCommand(link, ImuCodec.CMD_CONFIG_LENGTH) ?: return null
        if (lenBytes.size < 4) return null
        val len = (lenBytes[0].toInt() and 0xFF) or ((lenBytes[1].toInt() and 0xFF) shl 8) or ((lenBytes[2].toInt() and 0xFF) shl 16) or ((lenBytes[3].toInt() and 0xFF) shl 24)
        if (len <= 0 || len > 1 shl 20) return null
        val out = java.io.ByteArrayOutputStream()
        val giveUp = System.nanoTime() + 6_000_000_000L
        while (out.size() < len && System.nanoTime() < giveUp) {
            val part = hidCommand(link, ImuCodec.CMD_CONFIG_PART) ?: break
            if (part.isEmpty()) break
            out.write(part)
        }
        if (out.size() < len) { listener.onLog("factory config truncated: ${out.size()}/$len"); return null }
        return String(out.toByteArray(), 0, len, Charsets.UTF_8)
    }

    private fun imuLoop(link: HidLink) {
        val buf = ByteArray(1024)
        var errors = 0
        while (imuRunning) {
            val n = link.read(buf, 200)
            val now = System.nanoTime()
            if (n < 0) { if (++errors > 2000) break; continue }
            if (n == 0) continue
            errors = 0
            val dump = imuDump
            if (dump != null && dumpedReports < CAPTURE_REPORTS) {
                try { dump.write(n and 0xFF); dump.write(n shr 8); dump.write(buf, 0, n) } catch (_: Exception) {}
                dumpedReports++
            }
            pipeline.onImuReport(buf, n, now)
        }
    }

    private fun onFrame(frame: ByteArray) {
        val now = System.nanoTime()
        val dump = frameDump
        if (dump != null && dumpedFrames < CAPTURE_FRAMES) { try { dump.write(frame) } catch (_: Exception) {}; dumpedFrames++ }
        pipeline.onCameraFrame(frame, now)
    }

    /** USB-level camera stream counters of the current connection, for the hardware report. */
    fun describeCameraUsb(): String {
        val u = uvc ?: return "camera stream not open\n"
        val a = u.assembler
        return "transport " + u.transport + "; format " + u.chosen + "\n" +
            "negotiated frame size " + u.negotiatedFrameBytes + " bytes (expected " + CameraDecoder.FRAME_BYTES + "), payload size " + u.negotiatedPayloadSize + "\n" +
            "bytes " + u.bytes + ", payloads " + a?.payloads + ", complete frames " + a?.goodFrames + ", short frames " + a?.shortFrames + ", long frames " + a?.longFrames +
            ", payloads flagged as errors by the camera " + a?.errorPayloads + ", bad headers " + a?.badHeaders + ", sizes of last rejected frames " + a?.rejectedSizes + "\n" +
            "USB: transfer timeouts " + u.timeouts + ", transfer errors " + u.transferErrors + ", bad isochronous packets " + u.badIsoPackets +
            ", longest gap between transfers " + dev.ultraxr.core.Fmt.f(u.longestGapNs / 1e6, 0) + " ms, fatal: " + (u.fatal ?: "none") + "\n"
    }

    /** Latest decoded raw eye image (640x480 grey), for previews. */
    fun copyRawEye(right: Boolean, into: ByteArray) { System.arraycopy(pipeline.rawEye[if (right) 1 else 0], 0, into, 0, CameraDecoder.IMAGE_BYTES) }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!started) return
            if (state == State.STREAMING || state == State.CONNECTING) guardDisplayMode()
            if (state == State.STREAMING) {
                val imuMoved = pipeline.imuSamples != watchImu
                val camMoved = pipeline.cameraFrames != watchPairs
                watchImu = pipeline.imuSamples; watchPairs = pipeline.cameraFrames
                val fatal = uvc?.fatal
                // With the cameras down for good but the IMU alive, stay connected: rotation-only
                // tracking is better than nothing.
                val ok = imuMoved && (camMoved || camerasDown) && (fatal == null || camerasDown)
                if (ok) {
                    watchStallSeconds = 0
                    if (camMoved && ++healthySeconds >= 5) failures = 0
                    if (camMoved && camerasDown) { camerasDown = false; setState(State.STREAMING, "") }
                } else watchStallSeconds++
                if (watchStallSeconds >= STALL_SECONDS || (fatal != null && !camerasDown)) {
                    val u = uvc
                    val why = fatal ?: if (!imuMoved) "the motion sensor stopped sending" else "no complete camera frame for $STALL_SECONDS s (" +
                        "transfer timeouts ${u?.timeouts}, transfer errors ${u?.transferErrors}, short frames ${u?.assembler?.shortFrames}, long frames ${u?.assembler?.longFrames}, payloads ${u?.assembler?.payloads})"
                    stats.lastError = why
                    if (imuMoved && failures >= MAX_RETRIES) {
                        listener.onLog("cameras unavailable after $failures attempts ($why): continuing with rotation only")
                        camerasDown = true; watchStallSeconds = 0
                        setState(State.STREAMING, "Cameras not streaming: rotation only")
                    } else {
                        listener.onLog("stream stalled ($why): reconnecting")
                        stats.reconnects++
                        stats.noteReconnect(why)
                        teardown()
                        failures++
                        if (failures <= MAX_RETRIES) { setState(State.CONNECTING, "Reconnecting"); handler.postDelayed({ connectIfPossible() }, 600) }
                        else setState(State.FAILED, why)
                    }
                }
            } else if (state == State.NO_GLASSES || state == State.NEED_PERMISSION) {
                connectIfPossible() // attach broadcasts can be missed while the app was not running
            }
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * Polls the glasses' display mode and gives up on 3D when the phone does not follow.
     * In a 3D mode the glasses expect a 3840x1080 picture. A phone that keeps sending 1920x1080
     * leaves them black, so after [SBS_GRACE_SECONDS] without a side-by-side display the glasses
     * are put back in 2D, where an ordinary 1920x1080 picture (and Android mirroring) works.
     */
    private fun guardDisplayMode() {
        val m = mcu ?: return
        m.drain(120) // unsolicited messages: screen on/off, panel power, firmware log lines
        if (++pollTick % 2 == 0) {
            val now = m.getDisplayMode()
            if (now != null && now != stats.displayMode) { stats.note("glasses changed mode by themselves: " + McuProtocol.modeName(stats.displayMode) + " -> " + McuProtocol.modeName(now)); stats.displayMode = now }
        }
        val mode = stats.displayMode
        val threeD = mode == McuProtocol.MODE_SBS_60 || mode == McuProtocol.MODE_SBS_90 || mode == McuProtocol.MODE_HALF_SBS
        val present = try { sideBySideDisplayPresent?.invoke() } catch (_: Throwable) { null } ?: return
        if (!threeD || present) { sbsWaitSeconds = 0; return }
        if (++sbsWaitSeconds < SBS_GRACE_SECONDS) return
        sbsWaitSeconds = 0
        val ok = m.setDisplayMode(McuProtocol.MODE_2D_60)
        stats.displayMode = m.getDisplayMode() ?: mode
        stats.sbsFellBack = true
        wantedDisplayMode = McuProtocol.MODE_2D_60
        stats.note("no side-by-side display from the phone after $SBS_GRACE_SECONDS s in 3D mode: back to 2D " + (if (ok) "accepted" else "REJECTED") + "; glasses now report " + McuProtocol.modeName(stats.displayMode))
        listener.onLog("the phone did not switch to 3840x1080: glasses put back in 2D so they show a picture")
    }

    private fun teardown() {
        try { uvc?.stop() } catch (_: Exception) {}
        uvc = null
        imuRunning = false
        try { imuThread?.join(800) } catch (_: Exception) {}
        imuThread = null
        try { imuLink?.let { it.write(ImuCodec.streamCommand(false).copyOf(64)); it.release() } } catch (_: Exception) {}
        imuLink = null
        try { mcuLink?.release() } catch (_: Exception) {}
        mcuLink = null; mcu = null
        try { conn?.close() } catch (_: Exception) {}
        conn = null
        try { frameDump?.close(); imuDump?.close() } catch (_: Exception) {}
        frameDump = null; imuDump = null
    }

    private fun openCapture() {
        val dir = captureDir ?: return
        captureDir = null
        try {
            dir.mkdirs()
            frameDump = FileOutputStream(File(dir, "camera_raw_frames.bin")); imuDump = FileOutputStream(File(dir, "imu_reports.bin"))
            dumpedFrames = 0; dumpedReports = 0
        } catch (e: Exception) { listener.onLog("capture open failed: $e") }
    }

    private fun describe(d: UsbDevice, c: UsbDeviceConnection): String {
        val sb = StringBuilder()
        sb.append("%04x:%04x '%s' by '%s' usb=%s\n".format(d.vendorId, d.productId, d.productName ?: "?", d.manufacturerName ?: "?", d.version))
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            val eps = (0 until f.endpointCount).joinToString { val e = f.getEndpoint(it); "0x%02x/%s/%d".format(e.address, arrayOf("ctrl", "iso", "bulk", "int")[e.type], e.maxPacketSize) }
            sb.append("  if#${f.id} alt=${f.alternateSetting} class=${f.interfaceClass}/${f.interfaceSubclass}/${f.interfaceProtocol} eps=[$eps]\n")
        }
        c.rawDescriptors?.let { raw -> sb.append("raw: ").append(raw.joinToString("") { "%02x".format(it) }).append('\n') }
        return sb.toString()
    }

    companion object {
        const val VID = 0x3318
        const val PID_ULTRA = 0x0426
        const val MCU_INTERFACE = 0
        const val IMU_INTERFACE = 2
        const val ACTION_PERMISSION = "dev.ultraxr.USB_PERMISSION"
        const val MAX_RETRIES = 4
        const val STALL_SECONDS = 3
        const val SBS_GRACE_SECONDS = 8
        const val CAPTURE_FRAMES = 8
        const val CAPTURE_REPORTS = 4000
    }
}
