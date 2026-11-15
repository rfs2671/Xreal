package dev.ultraxr.core

import dev.ultraxr.proto.CameraDecoder
import dev.ultraxr.proto.ImuCodec

/**
 * Turns the glasses' raw USB data into tracking input: decodes IMU reports and camera
 * frames, puts both on the phone's clock, pairs left/right frames and orients them the way
 * the calibration expects. No Android types, so it can be driven by recorded or synthetic
 * streams in tests.
 */
class SensorPipeline(private val sink: Sink) {
    interface Sink {
        fun onImu(tNs: Long, gyro: FloatArray, accel: FloatArray)
        fun onStereo(tNs: Long, left: ByteArray, right: ByteArray)
        /** The glasses' clock restarted: everything downstream must start over. */
        fun onRestart()
    }

    val clock = DeviceClock()
    private val pairer = StereoPairer()
    val rawEye = arrayOf(ByteArray(CameraDecoder.IMAGE_BYTES), ByteArray(CameraDecoder.IMAGE_BYTES))
    private val outEye = arrayOf(ByteArray(EyeImage.OUT_BYTES), ByteArray(EyeImage.OUT_BYTES))
    @Volatile private var lastImuDeviceNs = 0L

    @Volatile var imuSamples = 0L; private set
    @Volatile var imuReports = 0L; private set
    @Volatile var imuReportSize = 0; private set
    @Volatile var camLeft = 0L; private set
    @Volatile var camRight = 0L; private set
    @Volatile var pairs = 0L; private set
    @Volatile var pairsUnsynced = 0L; private set
    @Volatile var noMeta = 0L; private set
    @Volatile var camBytes = 0L; private set
    @Volatile var seamLeft = Double.NaN; private set
    @Volatile var seamRight = Double.NaN; private set
    @Volatile var brightLeft = Double.NaN; private set
    @Volatile var brightRight = Double.NaN; private set
    @Volatile var camMinusImuMs = Double.NaN; private set
    val pairDrops get() = pairer.dropped

    fun reset() { clock.reset(); pairer.reset(); lastImuDeviceNs = 0 }

    /** One HID input report from the IMU interface, received at [hostNs]. */
    fun onImuReport(buf: ByteArray, size: Int, hostNs: Long) {
        imuReports++; imuReportSize = size
        val s = ImuCodec.decode(buf, size) ?: return
        val rollbacks = clock.clockRollbacks
        val t = clock.mapImu(s.timestampNs, hostNs) ?: return
        if (clock.clockRollbacks != rollbacks) { pairer.reset(); sink.onRestart() }
        lastImuDeviceNs = s.timestampNs
        imuSamples++
        sink.onImu(t, s.gyro, s.accel)
    }

    /** One complete UVC frame (one eye), received at [hostNs]. */
    fun onCameraFrame(frame: ByteArray, hostNs: Long) {
        camBytes += frame.size
        val meta = CameraDecoder.parseMeta(frame)
        if (meta == null) { noMeta++; return }
        val eye = if (meta.isRight) 1 else 0
        if (meta.isRight) camRight++ else camLeft++
        if (lastImuDeviceNs != 0L) camMinusImuMs = (meta.t62Ns - lastImuDeviceNs) / 1e6
        val r = pairer.push(meta.t62Ns, meta.pairCounter, meta.isRight)
        if (r == StereoPairer.Result.DISCARD) return
        if (!CameraDecoder.decode(frame, rawEye[eye])) return
        EyeImage.transform(rawEye[eye], meta.isRight, outEye[eye])
        val count = if (meta.isRight) camRight else camLeft
        if (count % 30 == 1L) {
            // Cheap, continuous evidence that the unshuffle is right and the lens sees something.
            val seam = CameraDecoder.seamScore(rawEye[eye]); val b = CameraDecoder.brightness(rawEye[eye]).first
            if (meta.isRight) { seamRight = seam; brightRight = b } else { seamLeft = seam; brightLeft = b }
        }
        if (r != StereoPairer.Result.COMPLETE) return
        val t = clock.mapCamera(meta.t62Ns, hostNs)
        if (t == null) { pairsUnsynced++; return }
        pairs++
        sink.onStereo(t, outEye[0], outEye[1])
    }
}
