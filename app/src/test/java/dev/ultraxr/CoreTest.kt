package dev.ultraxr

import dev.ultraxr.core.AffineClockMapper
import dev.ultraxr.core.DeviceClock
import dev.ultraxr.core.EyeImage
import dev.ultraxr.core.GestureTracker
import dev.ultraxr.core.Hand
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import dev.ultraxr.core.StereoPairer
import dev.ultraxr.core.VioSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class CoreTest {
    private fun near(a: DoubleArray, b: DoubleArray, eps: Double, msg: String = "") {
        for (i in a.indices) assertEquals("$msg [$i] got ${a.toList()} want ${b.toList()}", b[i], a[i], eps)
    }

    @Test fun quaternionBasics() {
        val q = Q.axisAngle(0.0, 1.0, 0.0, Math.PI / 2)
        near(Q.rotate(q, doubleArrayOf(0.0, 0.0, -1.0)), doubleArrayOf(-1.0, 0.0, 0.0), 1e-9)
        assertEquals(Math.PI / 2, Q.yawY(q), 1e-9)
        val p = Pose(doubleArrayOf(1.0, 2.0, 3.0), q)
        val id = p * p.inverse()
        near(id.p, doubleArrayOf(0.0, 0.0, 0.0), 1e-9); assertEquals(0.0, Q.angleBetween(id.q, Q.identity()), 1e-9)
        near(Q.rotate(HeadTracker.Z_TO_Y, doubleArrayOf(1.0, 2.0, 3.0)), doubleArrayOf(1.0, 3.0, -2.0), 1e-9)
        // Head axes expressed in the IMU frame.
        near(Q.rotate(HeadTracker.IMU_TO_HEAD, doubleArrayOf(1.0, 0.0, 0.0)), doubleArrayOf(-1.0, 0.0, 0.0), 1e-9)
        near(Q.rotate(HeadTracker.IMU_TO_HEAD, doubleArrayOf(0.0, 1.0, 0.0)), doubleArrayOf(0.0, 0.0, 1.0), 1e-9)
        near(Q.rotate(HeadTracker.IMU_TO_HEAD, doubleArrayOf(0.0, 0.0, 1.0)), doubleArrayOf(0.0, 1.0, 0.0), 1e-9)
    }

    @Test fun clockMapperRecoversOffsetAndDriftThroughJitter() {
        val m = AffineClockMapper()
        val rnd = Random(3)
        val drift = 1.0 + 60e-6            // device clock runs 60 ppm slow relative to host
        val offset = 5_000_000_000L
        var worst = 0.0
        for (i in 0 until 20_000) {         // 20 s at 1 kHz
            val dev = 1_000_000_000L + i * 1_000_000L
            val trueHost = (dev * drift).toLong() + offset
            val delay = 150_000L + (abs(rnd.nextGaussian()) * 400_000).toLong() // USB delay, always positive
            val mapped = m.map(dev, trueHost + delay)
            if (i > 5000) worst = maxOf(worst, abs(mapped - trueHost).toDouble())
        }
        assertTrue("worst error ${worst / 1e6} ms", worst < 600_000) // within the minimum transport delay band
        assertEquals(60.0, m.driftPpm, 25.0)
        // A device clock restart re-initialises instead of mapping into the past.
        val before = m.resetCount
        m.map(5_000L, 99_000_000_000L)
        assertEquals(before + 1, m.resetCount)
    }

    @Test fun deviceClockProjectsCameraThroughImuModel() {
        val c = DeviceClock()
        // Before any IMU sample the camera is still timed, by its own stream, and counted.
        val early = c.mapCamera(1000, 5000)!!
        assertEquals(DeviceClock.CameraPath.CAMERA_CLOCK, early.path); assertEquals(1L, c.camNoImuYet)
        c.reset()
        for (i in 0 until 3000) c.mapImu(10_000_000_000L + i * 1_000_000L, 70_000_000_000L + i * 1_000_000L + 200_000)
        assertNull("duplicate imu timestamp", c.mapImu(10_000_000_000L + 2999 * 1_000_000L, 0))
        assertEquals(1L, c.imuDuplicates)
        val cam = c.mapCamera(10_000_000_000L + 2990 * 1_000_000L, 70_000_000_000L + 3010 * 1_000_000L)!!
        assertEquals(DeviceClock.CameraPath.IMU_CLOCK, cam.path)
        assertEquals((70_000_000_000L + 2990 * 1_000_000L).toDouble(), cam.hostNs.toDouble(), 400_000.0)
    }

    @Test fun smallBackwardImuStepIsJitterNotARestart() {
        val c = DeviceClock()
        var t = 10_000_000_000L; var host = 70_000_000_000L
        repeat(500) { c.mapImu(t, host); t += 1_000_000L; host += 1_000_000L }
        // An out-of-order report 3 ms in the past: dropped, clock model kept.
        assertNull(c.mapImu(t - 3_000_000L, host))
        assertEquals(1L, c.imuOutOfOrder); assertEquals(0L, c.clockRollbacks)
        assertEquals(DeviceClock.CameraPath.IMU_CLOCK, c.mapCamera(t - 5_000_000L, host + 15_000_000L)!!.path)
        // The glasses restarting (clock back near zero) is a restart.
        assertNotNull(c.mapImu(5_000_000L, host + 1_000_000L))
        assertEquals(1L, c.clockRollbacks)
    }

    @Test fun cameraOnAnotherClockIsStillTimedAndCounted() {
        val c = DeviceClock()
        for (i in 0 until 2000) c.mapImu(10_000_000_000L + i * 1_000_000L, 70_000_000_000L + i * 1_000_000L)
        // Camera timestamps from a different epoch (an hour away), arriving every 33 ms.
        var last = 0L
        for (k in 0 until 60) {
            val arrive = 70_000_000_000L + (2000 + k * 33) * 1_000_000L
            val r = c.mapCamera(3_600_000_000_000L + k * 33_000_000L, arrive)!!
            assertEquals(DeviceClock.CameraPath.CAMERA_CLOCK, r.path)
            assertTrue("mapped time near arrival", abs(r.hostNs - arrive) < 1_000_000L)
            assertTrue(r.hostNs > last); last = r.hostNs
        }
        assertEquals(60L, c.camEpochMismatch); assertEquals(60L, c.camOnOwnClock); assertEquals(0L, c.camOnImuClock)
    }

    @Test fun eyeRotationsMatchOpenCvDefinitions() {
        val src = ByteArray(640 * 480) { ((it % 640) * 3 + (it / 640) * 7).toByte() }
        val ccw = ByteArray(EyeImage.OUT_BYTES); val cw = ByteArray(EyeImage.OUT_BYTES)
        EyeImage.rotateCcw90(src, ccw); EyeImage.rotateCw90(src, cw)
        for ((r, c) in listOf(0 to 0, 0 to 479, 639 to 0, 639 to 479, 123 to 45, 600 to 400)) {
            assertEquals(src[c * 640 + (639 - r)], ccw[r * 480 + c])   // ROTATE_90_COUNTERCLOCKWISE
            assertEquals(src[(479 - c) * 640 + r], cw[r * 480 + c])    // ROTATE_90_CLOCKWISE
            // "ccw90 then flip both axes" (XR Gate's right-eye setting) equals cw90.
            assertEquals(ccw[(639 - r) * 480 + (479 - c)], cw[r * 480 + c])
        }
    }

    @Test fun stereoPairerPairsAlternatingEyes() {
        val p = StereoPairer()
        val ms = 1_000_000L
        assertEquals(StereoPairer.Result.PENDING, p.push(100 * ms, 1, false))
        assertEquals(StereoPairer.Result.COMPLETE, p.push(100 * ms, 1, true))
        assertEquals(100 * ms, p.pairTimeNs)
        assertEquals(StereoPairer.Result.PENDING, p.push(133 * ms, 2, true))
        assertEquals(StereoPairer.Result.PENDING, p.push(166 * ms, 3, false))     // left of pair 2 was lost
        assertEquals(StereoPairer.Result.COMPLETE, p.push(166 * ms, 3, true))
        assertEquals(2L, p.complete); assertEquals(1L, p.missingPartner)
        assertEquals(StereoPairer.Result.DISCARD, p.push(50 * ms, 0, false))       // older than what was published
        assertEquals(1L, p.stale)
        assertEquals(1L, p.counterGapsLeft)                                        // left jumped 1 -> 3
    }

    @Test fun stereoPairerToleratesInexactTimestampsAndCounters() {
        val ms = 1_000_000L
        // Eyes stamped 0.7 ms apart with equal counters, either eye first.
        val a = StereoPairer()
        var done = 0
        for (k in 0 until 100) {
            val t = (1000 + k * 33) * ms
            val first = if (k % 2 == 0) a.push(t, k, false) else a.push(t + 700_000, k, true)
            val second = if (k % 2 == 0) a.push(t + 700_000, k, true) else a.push(t, k, false)
            assertEquals(StereoPairer.Result.PENDING, first); if (second == StereoPairer.Result.COMPLETE) done++
        }
        assertEquals(100, done); assertEquals(0L, a.missingPartner); assertEquals(700_000L, a.maxPairDeltaNs)
        // Counters that differ between the eyes (right runs one ahead), identical times.
        val b = StereoPairer()
        done = 0
        for (k in 0 until 100) { val t = (1000 + k * 33) * ms; b.push(t, k, false); if (b.push(t, k + 1, true) == StereoPairer.Result.COMPLETE) done++ }
        assertEquals(100, done); assertEquals(100L, b.counterMismatchAccepted)
        // Neighbouring exposures are never joined: left of frame k with right of frame k+1.
        val c = StereoPairer()
        done = 0
        for (k in 0 until 50) { val t = (1000 + k * 33) * ms; c.push(t, 2 * k, false); if (c.push(t + 33 * ms, 2 * k + 1, true) == StereoPairer.Result.COMPLETE) done++ }
        assertEquals(0, done)
        // Random loss of 20 percent of frames still pairs every exposure that has both eyes.
        val d = StereoPairer(); val rnd = Random(5)
        var both = 0; done = 0
        for (k in 0 until 1000) {
            val t = (1000 + k * 33) * ms
            val l = rnd.nextInt(5) != 0; val r = rnd.nextInt(5) != 0
            if (l && r) both++
            var res = StereoPairer.Result.PENDING
            if (l) res = d.push(t, k, false)
            if (r) res = d.push(t, k, true)
            if (l && r && res == StereoPairer.Result.COMPLETE) done++
        }
        assertEquals(both, done)
        assertTrue(d.counterGapsLeft > 100 && d.counterGapsRight > 100)
    }

    private val up = floatArrayOf(0f, 0f, 9.80665f)
    private fun still(t: HeadTracker, fromMs: Int, toMs: Int, gyro: FloatArray = floatArrayOf(0f, 0f, 0f)) {
        for (ms in fromMs until toMs) t.onImu(ms * 1_000_000L, gyro, up)
    }

    @Test fun headIsUprightAndRecentredFacingMinusZ() {
        val t = HeadTracker()
        assertEquals(HeadTracker.Mode.NONE, t.predict(0).mode)
        still(t, 1, 500)
        val s = t.predict(500_000_000L)
        assertEquals(HeadTracker.Mode.ORIENTATION_ONLY, s.mode)
        near(Q.rotate(s.pose.q, doubleArrayOf(0.0, 1.0, 0.0)), doubleArrayOf(0.0, 1.0, 0.0), 1e-6, "up")
        near(Q.rotate(s.pose.q, doubleArrayOf(0.0, 0.0, -1.0)), doubleArrayOf(0.0, 0.0, -1.0), 1e-6, "forward")
        near(s.pose.p, doubleArrayOf(0.0, 0.0, 0.0), 1e-9)
    }

    @Test fun turningLeftYawsLeftAndTiltIsCorrectedByGravity() {
        val t = HeadTracker()
        still(t, 1, 300); t.predict(300_000_000L)
        // IMU z is up: +90 deg/s about it for one second is a turn to the left.
        still(t, 300, 1300, floatArrayOf(0f, 0f, (Math.PI / 2).toFloat()))
        val s = t.predict(1_300_000_000L)
        assertEquals(Math.PI / 2, Q.yawY(s.pose.q), 0.02)
        near(Q.rotate(s.pose.q, doubleArrayOf(0.0, 0.0, -1.0)), doubleArrayOf(-1.0, 0.0, 0.0), 0.03, "facing -x after a left turn")
        // A gyro bias about the forward axis would roll the horizon; gravity must hold it level.
        val t2 = HeadTracker()
        still(t2, 1, 300); t2.predict(300_000_000L)
        still(t2, 300, 10_300, floatArrayOf(0f, 0.02f, 0f))
        val upNow = Q.rotate(t2.predict(10_300_000_000L).pose.q, doubleArrayOf(0.0, 1.0, 0.0))
        assertTrue("roll drift ${Math.toDegrees(Math.acos(upNow[1]))} deg", upNow[1] > Math.cos(Math.toRadians(9.0)))
    }

    @Test fun positionComesFromVioAndIsPredictedForward() {
        val t = HeadTracker()
        still(t, 1, 400); t.predict(400_000_000L)
        val idQ = Q.identity()
        // Body moves along IMU -y (forward) at 1 m/s in the estimator's z-up world.
        for (ms in 400..1000 step 33) {
            still(t, ms, ms + 33)
            val sec = (ms - 400) / 1000.0
            t.onVio(ms * 1_000_000L, doubleArrayOf(0.0, -sec, 0.0), idQ, doubleArrayOf(0.0, -1.0, 0.0), null)
        }
        val last = 400 + 33 * 18 // 994 ms
        still(t, last + 33, last + 53)
        val s = t.predict((last + 50) * 1_000_000L)
        assertEquals(HeadTracker.Mode.SIX_DOF, s.mode)
        // Walked forward ~0.594 m plus 50 ms of prediction at 1 m/s; forward is -z after recentring.
        near(s.pose.p, doubleArrayOf(0.0, 0.0, -(0.594 + 0.05)), 0.01, "position")
        near(s.velocity, doubleArrayOf(0.0, 0.0, -1.0), 1e-6, "velocity")
        // Estimator goes quiet: orientation keeps working, position holds.
        still(t, last + 53, last + 1000)
        val lost = t.predict((last + 1000) * 1_000_000L)
        assertEquals(HeadTracker.Mode.ORIENTATION_ONLY, lost.mode)
        near(lost.pose.p, s.pose.p, 0.08, "held position")
    }

    @Test fun estimatorRestartKeepsTheWorldInPlace() {
        val t = HeadTracker()
        still(t, 1, 400); t.predict(400_000_000L)
        t.onVio(400_000_000L, doubleArrayOf(0.3, -1.0, 0.1), Q.identity(), doubleArrayOf(0.0, 0.0, 0.0), null)
        still(t, 400, 420)
        val before = t.predict(420_000_000L)
        t.onVioEpochReset()
        still(t, 420, 900)
        assertEquals(HeadTracker.Mode.ORIENTATION_ONLY, t.predict(900_000_000L).mode)
        // New epoch: origin somewhere else and the world yawed by 130 degrees.
        val yaw = Q.axisAngle(0.0, 0.0, 1.0, Math.toRadians(130.0))
        t.onVio(900_000_000L, doubleArrayOf(5.0, 4.0, 0.0), yaw, doubleArrayOf(0.0, 0.0, 0.0), null)
        still(t, 900, 920)
        val after = t.predict(920_000_000L)
        assertEquals(HeadTracker.Mode.SIX_DOF, after.mode)
        near(after.pose.p, before.pose.p, 1e-6, "position continuous")
        assertEquals(0.0, Q.angleBetween(after.pose.q, before.pose.q), 1e-6)
        // Motion in the new epoch is still mapped into the old app world consistently:
        // the body moves 1 m along its own forward axis (-y), expressed in the yawed world.
        val fwd = Q.rotate(yaw, doubleArrayOf(0.0, -1.0, 0.0))
        t.onVio(940_000_000L, doubleArrayOf(5.0 + fwd[0], 4.0 + fwd[1], 0.0), yaw, doubleArrayOf(0.0, 0.0, 0.0), null)
        still(t, 920, 945)
        val moved = t.predict(945_000_000L)
        val headFwd = Q.rotate(before.pose.q, doubleArrayOf(0.0, 0.0, -1.0))
        near(moved.pose.p, DoubleArray(3) { before.pose.p[it] + headFwd[it] }, 1e-6, "forward step")
    }

    @Test fun recenterMovesOriginToTheHead() {
        val t = HeadTracker()
        still(t, 1, 400); t.predict(400_000_000L)
        t.onVio(400_000_000L, doubleArrayOf(1.0, 2.0, 0.5), Q.axisAngle(0.0, 0.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0), null)
        still(t, 400, 420)
        // The first estimator state keeps the head where the app already had it; then it moves.
        near(t.predict(420_000_000L).pose.p, doubleArrayOf(0.0, 0.0, 0.0), 1e-6, "no jump on the first state")
        t.onVio(420_000_000L, doubleArrayOf(1.7, 2.4, 0.5), Q.axisAngle(0.0, 0.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0), null)
        still(t, 420, 440)
        assertTrue(abs(t.predict(440_000_000L).pose.p[0]) + abs(t.predict(440_000_000L).pose.p[2]) > 0.5)
        t.recenter()
        val s = t.predict(440_000_000L)
        near(s.pose.p, doubleArrayOf(0.0, 0.0, 0.0), 1e-9)
        assertEquals(0.0, Q.yawY(s.pose.q), 1e-9)
    }

    @Test fun supervisorResetsOnDivergenceAndStall() {
        val s = VioSupervisor()
        val z = doubleArrayOf(0.0, 0.0, 0.0)
        var now = 10_000_000_000L
        fun step(p: DoubleArray, v: DoubleArray = z, f: Int = 80, fresh: Boolean = true): Boolean {
            now += 33_000_000L; s.onFrame(now); return s.onState(now, now, fresh, p, v, f)
        }
        repeat(100) { assertFalse(step(doubleArrayOf(it * 0.01, 0.0, 0.0))) }
        assertTrue("jump", step(doubleArrayOf(3.0, 0.0, 0.0)))
        assertFalse("cooldown", step(doubleArrayOf(9.0, 0.0, 0.0)))
        repeat(60) { step(z) }
        assertTrue("speed", step(z, doubleArrayOf(0.0, 9.0, 0.0)))
        repeat(60) { step(z) }
        var stalled = false
        repeat(80) { if (step(z, fresh = false)) stalled = true }
        assertTrue("stall", stalled)
        repeat(60) { step(z) }
        var starved = false
        repeat(120) { if (step(z, f = 2)) starved = true }
        assertTrue("few features", starved)
        assertEquals(4L, s.resets)
    }

    private fun hand(pinchGap: Double, curlIndex: Boolean, curlOthers: Boolean, at: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0), t: Long = 0): Hand {
        val j = FloatArray(63)
        fun set(i: Int, x: Double, y: Double, z: Double) { j[i * 3] = (x + at[0]).toFloat(); j[i * 3 + 1] = (y + at[1]).toFloat(); j[i * 3 + 2] = (z + at[2]).toFloat() }
        set(0, 0.0, 0.0, 0.0)
        val xs = doubleArrayOf(-0.03, -0.01, 0.01, 0.03) // index..little knuckle offsets
        for (f in 0..3) {
            val mcp = 5 + f * 4
            set(mcp, xs[f], 0.09, 0.0)
            val curl = if (f == 0) curlIndex else curlOthers
            if (curl) { set(mcp + 1, xs[f], 0.11, -0.02); set(mcp + 2, xs[f], 0.09, -0.04); set(mcp + 3, xs[f], 0.06, -0.03) }
            else { set(mcp + 1, xs[f], 0.13, 0.0); set(mcp + 2, xs[f], 0.155, 0.0); set(mcp + 3, xs[f], 0.175, 0.0) }
        }
        val tip = doubleArrayOf(j[8 * 3].toDouble() - at[0], j[8 * 3 + 1].toDouble() - at[1], j[8 * 3 + 2].toDouble() - at[2])
        set(1, -0.03, 0.02, 0.0); set(2, -0.045, 0.05, 0.0); set(3, -0.05, 0.08, 0.0)
        set(4, tip[0] - pinchGap, tip[1], tip[2])
        return Hand(true, j, t)
    }

    @Test fun gesturesWithHysteresis() {
        val g = GestureTracker(true)
        assertFalse(g.update(hand(0.08, false, false)).pinching)
        assertTrue(g.update(hand(0.012, false, false)).pinching)
        assertTrue("stays pinched inside the release band", g.update(hand(0.033, false, false)).pinching)
        assertFalse(g.update(hand(0.06, false, false)).pinching)
        val point = g.update(hand(0.09, false, true))
        assertTrue(point.pointing); assertFalse(point.fist)
        assertTrue(g.update(hand(0.09, true, true)).fist)
        // Velocity of the pinch point: 0.3 m in 0.1 s along x, filtered.
        val g2 = GestureTracker(true)
        var hs = g2.update(hand(0.01, false, false, t = 0))
        for (i in 1..10) hs = g2.update(hand(0.01, false, false, doubleArrayOf(0.03 * i, 0.0, 0.0), i * 10_000_000L))
        assertEquals(3.0, hs.velocity[0], 0.05)
        near(hs.aimDir, doubleArrayOf(-0.1104, 0.9939, 0.0), 0.01, "aim along wrist->knuckle")
    }
}
