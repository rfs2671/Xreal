package dev.ultraxr.core

/**
 * Maps the glasses' own nanosecond clock onto the phone's monotonic clock:
 * host = scale * device + offset. Port of XR Gate's AffineDeviceClockMapper (MIT):
 * USB delivery only ever adds delay, so each 250 ms window keeps its lowest-delay
 * sample and the model is a bounded least-squares fit through those.
 */
class AffineClockMapper {
    private class Anchor(val device: Long, val host: Long)

    var initialized = false; private set
    var scale = 1.0; private set
    var offsetNs = 0.0; private set
    var resetCount = 0L; private set
    val anchorCount get() = anchors.size
    val driftPpm get() = (scale - 1.0) * 1e6

    private var windowActive = false
    private var windowStart = 0L
    private var windowBest = Anchor(0, 0)
    private val anchors = ArrayDeque<Anchor>()
    private var lastDevice = 0L
    private var lastMapped = 0L

    fun map(deviceNs: Long, hostReceiveNs: Long): Long {
        if (initialized && deviceNs < lastDevice) reset()
        if (!initialized) {
            initialized = true; scale = 1.0
            offsetNs = hostReceiveNs.toDouble() - deviceNs.toDouble()
            startWindow(deviceNs, hostReceiveNs)
        } else if (!windowActive) startWindow(deviceNs, hostReceiveNs)
        else if (deviceNs - windowStart >= WINDOW_NS) { commitWindow(); startWindow(deviceNs, hostReceiveNs) }
        else updateWindow(deviceNs, hostReceiveNs)

        lastDevice = deviceNs
        var mapped = scale * deviceNs + offsetNs
        // A sample cannot have happened after the host received it.
        if (mapped > hostReceiveNs) { offsetNs -= mapped - hostReceiveNs; mapped = hostReceiveNs.toDouble() }
        var out = if (mapped > 0) mapped.toLong() else hostReceiveNs
        if (out <= lastMapped) out = lastMapped + 1
        lastMapped = out
        return out
    }

    /** Evaluates the model without training it (used for camera frames). */
    fun project(deviceNs: Long): Long? {
        if (!initialized) return null
        val m = scale * deviceNs + offsetNs
        return if (m.isFinite() && m > 0) m.toLong() else null
    }

    fun reset() {
        initialized = false; windowActive = false; windowStart = 0
        anchors.clear(); scale = 1.0; offsetNs = 0.0; lastDevice = 0; lastMapped = 0; resetCount++
    }

    private fun startWindow(d: Long, h: Long) { windowActive = true; windowStart = d; windowBest = Anchor(d, h) }

    private fun updateWindow(d: Long, h: Long) {
        val cur = h - (scale * d + offsetNs)
        val best = windowBest.host - (scale * windowBest.device + offsetNs)
        if (cur < best) windowBest = Anchor(d, h)
    }

    private fun commitWindow() {
        if (!windowActive) return
        anchors.addLast(windowBest)
        while (anchors.size > MAX_ANCHORS) anchors.removeFirst()
        refit()
        windowActive = false
    }

    private fun refit() {
        if (anchors.isEmpty()) return
        if (anchors.size == 1) { offsetNs = anchors.first().host - scale * anchors.first().device; return }
        val x0 = anchors.first().device; val y0 = anchors.first().host
        var mx = 0.0; var my = 0.0
        for (a in anchors) { mx += (a.device - x0).toDouble(); my += (a.host - y0).toDouble() }
        mx /= anchors.size; my /= anchors.size
        var cov = 0.0; var varr = 0.0
        for (a in anchors) { val x = (a.device - x0) - mx; val y = (a.host - y0) - my; cov += x * y; varr += x * x }
        if (varr <= 0) return
        val fitScale = (cov / varr).coerceIn(1.0 - MAX_SCALE_ERROR, 1.0 + MAX_SCALE_ERROR)
        // Evaluate the intercept relative to the first anchor to keep precision with ns-sized values.
        val fitOffset = (y0 + my) - fitScale * (x0 + mx)
        val alpha = if (anchors.size <= 2) 1.0 else 0.2
        scale += alpha * (fitScale - scale)
        offsetNs += alpha * (fitOffset - offsetNs)
    }

    companion object {
        const val WINDOW_NS = 250_000_000L
        const val MAX_ANCHORS = 32
        const val MAX_SCALE_ERROR = 0.002
    }
}

/**
 * One clock model per pair of glasses. IMU reports train it (they are frequent and
 * low-latency); camera timestamps are projected through the same model so the relative
 * timing between images and IMU samples survives the different USB delays.
 * Based on XR Gate's XrealDeviceClock, with two differences learnt on a real phone:
 *  - a slightly out-of-order IMU timestamp is dropped instead of being treated as a device
 *    restart (a restart resets pairing and the estimator, so false ones are fatal);
 *  - a camera timestamp that cannot be placed on the IMU clock is not discarded: it is mapped
 *    with a second model trained on the camera stream itself, and counted, so tracking
 *    still gets images (with looser camera-to-IMU timing) and the report says why.
 */
class DeviceClock {
    enum class CameraPath { IMU_CLOCK, CAMERA_CLOCK }
    class CameraTime(val hostNs: Long, val path: CameraPath)

    private val mapper = AffineClockMapper()
    private val cameraMapper = AffineClockMapper()
    private var lastImuDevice = 0L
    /** Large backward jumps of the IMU clock: the glasses restarted. */
    var clockRollbacks = 0L; private set
    /** IMU samples dropped for a small backward step (reordering or jitter). */
    @Volatile var imuOutOfOrder = 0L; private set
    @Volatile var imuDuplicates = 0L; private set
    // Why camera frames could not use the IMU clock.
    @Volatile var camNoImuYet = 0L; private set
    @Volatile var camEpochMismatch = 0L; private set
    @Volatile var camHostMismatch = 0L; private set
    @Volatile var camOnImuClock = 0L; private set
    @Volatile var camOnOwnClock = 0L; private set
    /** Last camera device time minus last IMU device time, ms: shows whether they share a clock. */
    @Volatile var lastCamMinusImuMs = Double.NaN; private set
    /** Last (IMU-clock projection minus arrival time) of a camera frame, ms: the transport delay, negative. */
    @Volatile var lastCamProjectionMinusArrivalMs = Double.NaN; private set

    val driftPpm get() = synchronized(this) { mapper.driftPpm }
    val anchors get() = synchronized(this) { mapper.anchorCount }

    /** Returns the host-clock time of an IMU sample, or null for a duplicate/out-of-order timestamp. */
    @Synchronized fun mapImu(deviceNs: Long, hostReceiveNs: Long): Long? {
        if (deviceNs <= 0) return null
        if (lastImuDevice != 0L && deviceNs < lastImuDevice) {
            if (lastImuDevice - deviceNs < RESTART_JUMP_NS) { imuOutOfOrder++; return null }
            // The glasses' clock really restarted: the caller must restart tracking on the new epoch.
            clockRollbacks++; mapper.reset(); cameraMapper.reset(); lastImuDevice = 0
        }
        if (deviceNs == lastImuDevice) { imuDuplicates++; return null }
        lastImuDevice = deviceNs
        return mapper.map(deviceNs, hostReceiveNs)
    }

    /** Host-clock time of a camera frame. Never null for a positive timestamp. */
    @Synchronized fun mapCamera(deviceNs: Long, hostReceiveNs: Long): CameraTime? {
        if (deviceNs <= 0) return null
        var reason = 0
        if (lastImuDevice == 0L) reason = 1
        else {
            lastCamMinusImuMs = (deviceNs - lastImuDevice) / 1e6
            if (Math.abs(deviceNs - lastImuDevice) > MAX_DELTA_NS) reason = 2 // different epoch or units
            else {
                val mapped = mapper.project(deviceNs)
                if (mapped != null) lastCamProjectionMinusArrivalMs = (mapped - hostReceiveNs) / 1e6
                if (mapped == null || Math.abs(mapped - hostReceiveNs) > MAX_DELTA_NS) reason = 3
                else { camOnImuClock++; cameraMapper.map(deviceNs, hostReceiveNs); return CameraTime(mapped, CameraPath.IMU_CLOCK) }
            }
        }
        when (reason) { 1 -> camNoImuYet++; 2 -> camEpochMismatch++; else -> camHostMismatch++ }
        camOnOwnClock++
        // The camera stream's own lowest-delay mapping. It carries the frame's USB transfer time as
        // a bias (the image is stamped late by roughly that much relative to the IMU).
        return CameraTime(cameraMapper.map(deviceNs, hostReceiveNs), CameraPath.CAMERA_CLOCK)
    }

    @Synchronized fun reset() { mapper.reset(); cameraMapper.reset(); lastImuDevice = 0 }

    companion object {
        const val MAX_DELTA_NS = 2_000_000_000L
        /** A backward step smaller than this is jitter, not a restart. */
        const val RESTART_JUMP_NS = 1_000_000_000L
    }
}
