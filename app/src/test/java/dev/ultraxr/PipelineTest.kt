package dev.ultraxr

import dev.ultraxr.core.EyeImage
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Q
import dev.ultraxr.core.SensorPipeline
import dev.ultraxr.proto.CameraDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Drives the whole phone-side sensor path with a simulated pair of glasses: byte-exact IMU
 * reports and shuffled camera frames with footers, delivered with USB-like delay and jitter.
 * This is simulation only; it checks our decoding, timing and fusion logic against the
 * documented stream format, not the real device.
 */
class PipelineTest {
    private val map: IntArray = CameraDecoder::class.java.getDeclaredField("CHUNK_MAP").apply { isAccessible = true }.get(CameraDecoder) as IntArray

    private fun imuReport(deviceNs: Long, gyro: DoubleArray, accel: DoubleArray): ByteArray {
        val p = ByteArray(64)
        p[0] = 1; p[1] = 2
        for (i in 0..7) p[4 + i] = (deviceNs shr (8 * i)).toByte()
        fun s24(o: Int, v: Int) { p[o] = v.toByte(); p[o + 1] = (v shr 8).toByte(); p[o + 2] = (v shr 16).toByte() }
        for (i in 0..2) s24(18 + 3 * i, Math.round(Math.toDegrees(gyro[i]) / 2000.0 * 8388608.0).toInt())
        for (i in 0..2) s24(33 + 3 * i, Math.round(accel[i] / 9.80665 / 16.0 * 8388608.0).toInt())
        return p
    }

    private fun cameraFrame(img: ByteArray, deviceNs: Long, counter: Int, right: Boolean, rotation: Int): ByteArray {
        val f = ByteArray(CameraDecoder.FRAME_BYTES)
        for (i in 0 until 128) System.arraycopy(img, 2400 * i, f, 2400 * map[(rotation + i) % 128], 2400)
        val o = CameraDecoder.IMAGE_BYTES
        for (i in 0..7) { f[o + i] = (deviceNs shr (8 * i)).toByte(); f[o + 62 + i] = (deviceNs shr (8 * i)).toByte() }
        f[o + 18] = counter.toByte(); f[o + 19] = (counter shr 8).toByte(); f[o + 59] = if (right) 1 else 0
        return f
    }

    private fun image(seed: Int) = ByteArray(CameraDecoder.IMAGE_BYTES) { i -> if (i < 2400) 0 else (50 + ((i % 640) / 4 + (i / 640) / 3 + seed) % 180).toByte() }

    @Test fun simulatedGlassesStreamComesOutTimedPairedAndOriented() {
        class Got(val t: Long, val left: ByteArray, val right: ByteArray)
        val pairs = ArrayList<Got>(); val imuT = ArrayList<Long>(); var restarts = 0
        val head = HeadTracker()
        val p = SensorPipeline(object : SensorPipeline.Sink {
            override fun onImu(tNs: Long, gyro: FloatArray, accel: FloatArray) { imuT += tNs; head.onImu(tNs, gyro, accel); if (imuT.size == 1000) head.predict(tNs) /* recentre after 1 s */ }
            override fun onStereo(tNs: Long, left: ByteArray, right: ByteArray) { pairs += Got(tNs, left.copyOf(), right.copyOf()) }
            override fun onRestart() { restarts++ }
        })
        val rnd = Random(7)
        val hostOffset = 123_456_789_000L   // host clock = device clock + offset (true mapping)
        val devStart = 50_000_000_000L
        val yawRate = Math.toRadians(45.0)  // turning left at 45 deg/s about the IMU's up axis
        val leftImg = image(3); val rightImg = image(90)
        var nextFrameMs = 40; var counter = 100
        // Events are (host arrival, action); camera frames arrive ~20 ms late, IMU ~0.3 ms late.
        val events = ArrayList<Pair<Long, () -> Unit>>()
        val seconds = 6
        for (ms in 0 until seconds * 1000) {
            val dev = devStart + ms * 1_000_000L
            val arrive = dev + hostOffset + 250_000L + (abs(rnd.nextGaussian()) * 300_000).toLong()
            val rep = imuReport(dev, doubleArrayOf(0.0, 0.0, yawRate), doubleArrayOf(0.0, 0.0, 9.80665))
            events += arrive to { p.onImuReport(rep, 64, arrive) }
            if (ms == nextFrameMs) {
                nextFrameMs += 33; counter++
                val c = counter
                val a1 = dev + hostOffset + 18_000_000L + (abs(rnd.nextGaussian()) * 2_000_000).toLong()
                val lf = cameraFrame(leftImg, dev, c, false, rnd.nextInt(128)); val rf = cameraFrame(rightImg, dev, c, true, rnd.nextInt(128))
                events += a1 to { p.onCameraFrame(lf, a1) }
                events += (a1 + 8_000_000L) to { p.onCameraFrame(rf, a1 + 8_000_000L) }
            }
        }
        events.sortBy { it.first }
        for (e in events) e.second()

        val expectedPairs = (seconds * 1000 - 40) / 33 + 1
        assertEquals(seconds * 1000L, p.imuSamples)
        assertTrue("pairs ${pairs.size} of $expectedPairs", pairs.size >= expectedPairs - 2)
        assertEquals(0L, p.pairsUnsynced); assertEquals(0L, p.noMeta); assertEquals(0, restarts)
        assertEquals("every pair timed on the shared clock", 0L, p.pairsOnCameraClock)
        assertTrue(p.describe().contains("pairing: complete"))
        assertTrue(p.seamLeft.isFinite() && p.seamRight.isFinite())

        // Images: unshuffled and turned upright exactly as the calibration expects.
        val wantL = ByteArray(EyeImage.OUT_BYTES).also { EyeImage.rotateCcw90(leftImg, it) }
        val wantR = ByteArray(EyeImage.OUT_BYTES).also { EyeImage.rotateCw90(rightImg, it) }
        assertArrayEquals(wantL, pairs.last().left); assertArrayEquals(wantR, pairs.last().right)

        // Timing: a frame captured at device time d must be stamped d + offset on the host clock,
        // even though it arrived ~20 ms later than the IMU samples of the same moment.
        var worst = 0L
        for ((i, g) in pairs.withIndex()) {
            if (i < 30) continue // first second: the clock model is still settling
            val dev = devStart + (40 + 33L * (pairs.size - expectedPairs + i).coerceAtLeast(i)) * 1_000_000L
            worst = maxOf(worst, abs(g.t - (dev + hostOffset)))
        }
        assertTrue("camera timestamp error ${worst / 1e6} ms", worst < 700_000)
        for (i in 1 until imuT.size) assertTrue(imuT[i] > imuT[i - 1])

        // Fusion: five more seconds at 45 deg/s after recentring is 225 degrees of left turn.
        val yaw = Math.toDegrees(Q.yawY(head.predict(imuT.last()).pose.q))
        val turned = ((yaw % 360) + 360) % 360
        assertEquals(225.0, turned, 3.0)

        // Jittered IMU timestamps (a report 2 ms in the past) must not reset anything.
        val late = imuReport(devStart + (seconds * 1000 - 3) * 1_000_000L, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 9.80665))
        p.onImuReport(late, 64, events.last().first + 1_000_000L)
        assertEquals(0, restarts); assertEquals(1L, p.clock.imuOutOfOrder)

        // The glasses' clock restarting (re-plug, firmware reset) is reported, not silently mixed in.
        val rep = imuReport(1_000_000L, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 9.80665))
        p.onImuReport(rep, 64, events.last().first + 5_000_000L)
        assertEquals(1, restarts)
    }
}
