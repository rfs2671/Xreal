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
 * One clock model per pair of glasses. Only IMU reports train it (they are frequent and
 * low-latency); camera timestamps are projected through the same model so the relative
 * timing between images and IMU samples survives the different USB delays.
 * Port of XR Gate's XrealDeviceClock.
 */
class DeviceClock {
    private val mapper = AffineClockMapper()
    private var lastImuDevice = 0L
    var clockRollbacks = 0L; private set

    val driftPpm get() = synchronized(this) { mapper.driftPpm }
    val anchors get() = synchronized(this) { mapper.anchorCount }

    /** Returns the host-clock time of an IMU sample, or null for a duplicate/invalid timestamp. */
    @Synchronized fun mapImu(deviceNs: Long, hostReceiveNs: Long): Long? {
        if (deviceNs <= 0) return null
        if (lastImuDevice != 0L && deviceNs < lastImuDevice) {
            // The glasses' clock restarted: the caller must restart tracking on the new epoch.
            clockRollbacks++; mapper.reset(); lastImuDevice = 0
        }
        if (deviceNs == lastImuDevice) return null
        lastImuDevice = deviceNs
        return mapper.map(deviceNs, hostReceiveNs)
    }

    /** Returns the host-clock time of a camera frame, or null if it cannot be trusted. */
    @Synchronized fun mapCamera(deviceNs: Long, hostReceiveNs: Long): Long? {
        if (lastImuDevice == 0L || deviceNs <= 0) return null
        if (Math.abs(deviceNs - lastImuDevice) > MAX_DELTA_NS) return null // different epoch or units
        val mapped = mapper.project(deviceNs) ?: return null
        if (Math.abs(mapped - hostReceiveNs) > MAX_DELTA_NS) return null
        return mapped
    }

    @Synchronized fun reset() { mapper.reset(); lastImuDevice = 0 }

    companion object { const val MAX_DELTA_NS = 2_000_000_000L }
}
