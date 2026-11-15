package dev.xrealdiag.usb

import dev.xrealdiag.proto.ImuCodec
import java.io.OutputStream
import kotlin.concurrent.thread

/** Starts the IMU stream on HID interface 2 and decodes motion reports on its own thread. */
class ImuReader(private val link: HidLink, private val onSample: (ImuCodec.Sample, Long) -> Unit) {
    @Volatile private var running = false
    private var worker: Thread? = null

    @Volatile var reports = 0L; private set
    @Volatile var samples = 0L; private set
    @Volatile var otherReports = 0L; private set
    @Volatile var lastReportSize = 0; private set
    @Volatile var startPath = ""; private set
    @Volatile var startResult = 0; private set
    @Volatile var readErrors = 0L; private set
    @Volatile var firstDeviceTs = 0L; private set
    @Volatile var lastDeviceTs = 0L; private set
    @Volatile var firstHostNs = 0L; private set
    @Volatile var lastHostNs = 0L; private set
    /** Largest gap between consecutive device timestamps, ns. */
    @Volatile var maxDeviceGapNs = 0L; private set
    /** When set, raw reports are appended here (length-prefixed) until [dumpLimit] reports. */
    @Volatile var dump: OutputStream? = null
    @Volatile var dumpLimit = 0

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "imu") {
            // Pocket XR pads the command to a full 64-byte report; XR Gate sends it bare.
            val padded = ImuCodec.START_COMMAND.copyOf(64)
            val (n, path) = link.write(padded)
            startResult = n; startPath = "$path/64"
            val buf = ByteArray(512)
            var resent = false
            val t0 = System.nanoTime()
            while (running) {
                val r = link.read(buf, 200)
                val now = System.nanoTime()
                if (r < 0) {
                    readErrors++
                    if (!resent && samples == 0L && now - t0 > 1_000_000_000L) {
                        val (n2, p2) = link.write(ImuCodec.START_COMMAND)
                        startResult = n2; startPath = "$p2/9 (retry)"; resent = true
                    }
                    continue
                }
                if (r == 0) continue
                reports++; lastReportSize = r
                val d = dump
                if (d != null && reports <= dumpLimit) {
                    try { d.write(r and 0xFF); d.write(r shr 8); d.write(buf, 0, r) } catch (_: Exception) {}
                }
                val s = ImuCodec.decode(buf, r)
                if (s == null) { otherReports++; continue }
                if (samples == 0L) { firstDeviceTs = s.timestampNs; firstHostNs = now }
                else { val gap = s.timestampNs - lastDeviceTs; if (gap > maxDeviceGapNs) maxDeviceGapNs = gap }
                lastDeviceTs = s.timestampNs; lastHostNs = now
                samples++
                onSample(s, now)
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(1000)
        worker = null
        link.write(ImuCodec.streamCommand(false).copyOf(64))
        link.release()
    }
}
