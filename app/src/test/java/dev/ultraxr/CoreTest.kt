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

    @Test fun aStateWhoseSpeedIsWrongDoesNotMakeTheHeadsPlaceJump() {
        // Walking at 0.3 m/s; every state's speed is wrong by 0.1 m/s, one way and then the other, and each state
        // arrives 30 ms after its picture. The place is asked for 60 times a second, as the renderer does.
        fun worstStep(glide: Boolean): Pair<Double, Double> {
            val t = HeadTracker(); t.glideOn = glide
            still(t, 1, 400); t.predict(400_000_000L)
            var worst = 0.0; var last: DoubleArray? = null; var prev: DoubleArray? = null; var k = 0; var end = 0.0
            for (ms in 400..2400) {
                still(t, ms, ms + 1)
                if ((ms - 400) % 33 == 30) {      // the state of the picture taken 30 ms ago
                    val sec = (ms - 30 - 400) / 1000.0; val wrong = if (ms > 2000) 0.0 else if (k++ % 2 == 0) 0.1 else -0.1
                    t.onVio((ms - 30) * 1_000_000L, doubleArrayOf(0.0, -0.3 * sec, 0.0), Q.identity(), doubleArrayOf(0.0, -0.3 - wrong, 0.0), null)
                }
                if (ms % 16 == 0 && ms > 600) {
                    val p = t.predict(ms * 1_000_000L).pose.p; val a = last; val b = prev
                    if (a != null && b != null && ms <= 2000) worst = maxOf(worst, Math.abs(p[2] - 2 * a[2] + b[2]))      // what is not smooth motion
                    prev = a; last = p; end = p[2]
                }
            }
            return worst to end
        }
        val (before, _) = worstStep(false); val (after, end) = worstStep(true)
        assertTrue("steps of $before m before", before > 0.004)
        assertTrue("steps of $after m with the glide, $before m without", after < before * 0.6)
        // and it ends where the head is: 0.3 m/s for the two seconds to 2400 ms
        assertEquals(-0.6, end, 0.004)
    }

    @Test fun theAccelerometerCarriesTheHeadsPlaceBetweenStates() {
        // The head sways 5 cm each way one and a half times a second (4.4 m/s2 at the turns). Every state arrives
        // 50 ms after its picture with its speed 5 cm/s wrong, one way and then the other, and its place 1 mm wrong.
        // The accelerometer reads true but for a bias of 0.1 m/s2 that nobody has told the head tracker of.
        val w = 2 * Math.PI * 1.5
        fun y(sec: Double) = 0.05 * Math.sin(w * sec)
        fun run(carry: Boolean): Pair<Double, Double> {
            val t = HeadTracker(); t.blendOn = carry
            still(t, 1, 400); t.predict(400_000_000L)
            var sum = 0.0; var n = 0; var shake = 0.0; var lastE = Double.NaN; var k = 0
            for (ms in 400..4400) {
                val sec = (ms - 400) / 1000.0
                t.onImu(ms * 1_000_000L, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, (-0.05 * w * w * Math.sin(w * sec) + 0.1).toFloat(), 9.81f))
                if ((ms - 400) % 33 == 17 && ms >= 450) {
                    val s = sec - 0.050; val wrong = if (k++ % 2 == 0) 1.0 else -1.0
                    t.onVio((ms - 50) * 1_000_000L, doubleArrayOf(0.0, y(s) + 0.001 * wrong, 0.0), Q.identity(), doubleArrayOf(0.0, 0.05 * w * Math.cos(w * s) + 0.05 * wrong, 0.0), null)
                }
                if (ms % 16 == 0 && ms > 1400) {
                    val e = t.predict(ms * 1_000_000L).pose.p[2] - y(sec)
                    sum += e * e; n++; if (!lastE.isNaN()) shake += (e - lastE) * (e - lastE); lastE = e
                }
            }
            return Math.sqrt(sum / n) to Math.sqrt(shake / n)
        }
        val (errNow, shakeNow) = run(false); val (err, shake) = run(true)
        assertTrue("off by $errNow m without, $err m with", errNow > 0.004 && err < errNow * 0.5)
        // (each state's speed is taken whole, which on the worn recordings left the place nearer the truth and shook no more; here, with
        // the speed wrong one way and then the other at every state, it leaves six tenths of the shake where a quarter of it left under half)
        assertTrue("shakes $shakeNow m a frame without, $shake m with", shake < shakeNow * 0.7)
    }

    @Test fun theCarriedPlaceCrossesAGapInTheMotionSamples() {
        // Walking at 0.7 m/s; every state is true and 50 ms late; the motion samples stop for 30 ms now and then.
        val t = HeadTracker(); t.blendOn = true
        still(t, 1, 400); t.predict(400_000_000L)
        var worst = 0.0
        for (ms in 400..2400) {
            val sec = (ms - 400) / 1000.0
            if ((ms % 400) in 1..29) continue
            t.onImu(ms * 1_000_000L, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 9.81f))
            if ((ms - 400) % 33 == 17 && ms >= 450) t.onVio((ms - 50) * 1_000_000L, doubleArrayOf(0.0, 0.7 * (sec - 0.05), 0.0), Q.identity(), doubleArrayOf(0.0, 0.7, 0.0), null)
            if (ms > 1000) worst = maxOf(worst, Math.abs(Math.abs(t.predict(ms * 1_000_000L).pose.p[2]) - 0.7 * sec))
        }
        assertTrue("off by $worst m after a gap", worst < 0.003)
    }

    @Test fun theCarriedPlaceStaysWhenTheStatesStop() {
        val t = HeadTracker(); t.blendOn = true
        still(t, 1, 400); t.predict(400_000_000L)
        t.onVio(400_000_000L, doubleArrayOf(0.0, 0.0, 0.0), Q.identity(), doubleArrayOf(0.0, 0.5, 0.0), null)
        // the accelerometer says the head speeds up, and no further state comes: the place may be carried for 0.12 s and no longer
        for (ms in 401..700) t.onImu(ms * 1_000_000L, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 3f, 9.81f))
        val p = t.predict(700_000_000L); val far = Math.abs(p.pose.p[2])
        assertTrue("carried $far m", far < 0.5 * 0.12 + 0.5 * 3 * 0.12 * 0.12 + 0.03)
        assertEquals(HeadTracker.Mode.SIX_DOF, p.mode)
        for (ms in 701..900) t.onImu(ms * 1_000_000L, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 3f, 9.81f))
        assertEquals(HeadTracker.Mode.ORIENTATION_ONLY, t.predict(900_000_000L).mode)
        assertEquals(far, Math.abs(t.predict(900_000_000L).pose.p[2]), 1e-9)
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

    private fun probe(right: Boolean, at: DoubleArray, pinching: Boolean, dir: DoubleArray? = null) = dev.ultraxr.core.HandProbe(right, at, pinching, at, dir)

    @Test fun theBallIsTakenByAPinchAtItAndStaysWhereItIsLetGo() {
        val b = dev.ultraxr.core.GrabBall(doubleArrayOf(0.0, 1.5, -0.45)); var now = 0L
        fun step(vararg h: dev.ultraxr.core.HandProbe) { now += 33_000_000L; b.update(h.toList(), now) }
        // nothing near: it does not move, whatever the hands do elsewhere
        step(probe(true, doubleArrayOf(0.4, 1.2, -0.3), false)); step(probe(true, doubleArrayOf(0.4, 1.2, -0.3), true)); step(probe(true, doubleArrayOf(0.5, 1.3, -0.3), true))
        assertEquals(-1, b.heldBy); assertEquals(0, b.grabs); near(b.p, doubleArrayOf(0.0, 1.5, -0.45), 1e-12)
        // an open hand at it: it could be taken, and is not yet
        step(probe(true, doubleArrayOf(0.4, 1.2, -0.3), false)); step(probe(true, doubleArrayOf(0.03, 1.5, -0.45), false))
        assertEquals(1, b.hover); assertEquals(-1, b.heldBy)
        // the pinch begins there: taken, and it goes where the fingers go, keeping its place against them
        step(probe(true, doubleArrayOf(0.03, 1.5, -0.45), true)); assertEquals(1, b.heldBy); assertFalse(b.far)
        step(probe(true, doubleArrayOf(0.33, 1.3, -0.35), true)); near(b.p, doubleArrayOf(0.30, 1.3, -0.35), 1e-9)
        // the hand is not seen for a quarter of a second (outside the cameras for a moment): still held
        repeat(7) { step() }; assertEquals(1, b.heldBy)
        step(probe(true, doubleArrayOf(0.43, 1.1, -0.35), true)); near(b.p, doubleArrayOf(0.40, 1.1, -0.35), 1e-9)
        // let go: it stays there
        step(probe(true, doubleArrayOf(0.43, 1.1, -0.35), false)); assertEquals(-1, b.heldBy)
        step(probe(true, doubleArrayOf(0.1, 1.6, -0.2), false)); near(b.p, doubleArrayOf(0.40, 1.1, -0.35), 1e-9)
        assertEquals(1, b.grabs); assertTrue(b.draggedM > 0.4)
        // a hand that is gone for longer lets go by itself
        step(probe(false, doubleArrayOf(0.43, 1.1, -0.35), false)); step(probe(false, doubleArrayOf(0.43, 1.1, -0.35), true)); assertEquals(0, b.heldBy)
        repeat(14) { step() }; assertEquals(-1, b.heldBy)
    }

    @Test fun theBallIsTakenFromAfarByTheRayAndKeepsItsDistance() {
        val b = dev.ultraxr.core.GrabBall(doubleArrayOf(0.0, 1.5, -2.0)); var now = 0L
        fun step(vararg h: dev.ultraxr.core.HandProbe) { now += 33_000_000L; b.update(h.toList(), now) }
        val hand = doubleArrayOf(0.2, 1.2, -0.4); fun to(x: Double, y: Double, z: Double): DoubleArray { val v = doubleArrayOf(x - hand[0], y - hand[1], z - hand[2]); val n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) }
        // pointing past it, pinching: nothing
        step(probe(true, hand, false, to(1.0, 1.5, -2.0))); step(probe(true, hand, true, to(1.0, 1.5, -2.0))); assertEquals(-1, b.heldBy)
        step(probe(true, hand, false, to(0.0, 1.5, -2.0))); assertEquals(1, b.hover)
        step(probe(true, hand, true, to(0.0, 1.5, -2.0))); assertEquals(1, b.heldBy); assertTrue(b.far)
        val reach = Math.sqrt(0.2 * 0.2 + 0.3 * 0.3 + 1.6 * 1.6)
        val d2 = to(0.8, 1.9, -1.5); step(probe(true, hand, true, d2))
        near(b.p, doubleArrayOf(hand[0] + d2[0] * reach, hand[1] + d2[1] * reach, hand[2] + d2[2] * reach), 1e-6)
        step(probe(true, hand, false, d2)); assertEquals(-1, b.heldBy)
    }

    @Test fun aThingIsPressedOnceForEachPinchBegunOnIt() {
        val t = dev.ultraxr.core.PressTarget(doubleArrayOf(0.0, 1.5, -1.4)); var now = 0L
        val hand = doubleArrayOf(0.1, 1.2, -0.4); val on = doubleArrayOf(-0.1, 0.3, -1.0).let { v -> val n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) }
        fun step(pinching: Boolean, dir: DoubleArray) { now += 33_000_000L; t.update(listOf(probe(true, hand, pinching, dir)), now) }
        step(false, on); assertEquals(1, t.hover); assertEquals(0, t.presses)
        step(true, on); step(true, on); step(true, on); assertEquals(1, t.presses)       // held: once
        step(false, on); step(true, on); assertEquals(2, t.presses)
        step(false, doubleArrayOf(1.0, 0.0, 0.0)); step(true, doubleArrayOf(1.0, 0.0, 0.0)); assertEquals(2, t.presses)      // pinched elsewhere
    }

    @Test fun lossPicturesKeepTheLastPairsOldestFirst() {
        val lp = dev.ultraxr.core.LossPictures(pairs = 4, every = 3, size = 6)
        val f = java.io.File.createTempFile("loss", ".uxloss")
        assertEquals(0, lp.write(f, "nothing yet"))
        for (k in 0 until 30) lp.onStereo(1000L + k, ByteArray(6) { k.toByte() }, ByteArray(6) { (100 + k).toByte() })      // kept: 0, 3, ..., 27
        assertEquals(4, lp.held())
        assertEquals(4, lp.write(f, "why\nit was lost"))
        val b = f.readBytes(); val head = "UXLOSS1\nwhy it was lost\n".toByteArray()
        assertEquals(head.size + 4 * (8 + 12), b.size)
        assertTrue(b.copyOf(head.size).contentEquals(head))
        for ((i, k) in listOf(18, 21, 24, 27).withIndex()) {
            val o = head.size + i * 20
            assertEquals(1000L + k, java.nio.ByteBuffer.wrap(b, o, 8).long)
            assertEquals(k.toByte(), b[o + 8]); assertEquals((100 + k).toByte(), b[o + 14])
        }
        f.delete()
    }

    /** 22 November 2026: every restart the app asked for was followed by a second one, "position jump", 0.2 s later. */
    @Test fun supervisorDoesNotTakeANewWorldsOriginForAJump() {
        val s = VioSupervisor()
        val z = doubleArrayOf(0.0, 0.0, 0.0)
        var now = 10_000_000_000L
        fun step(p: DoubleArray): Boolean { now += 33_000_000L; s.onFrame(now); return s.onState(now, now, true, p, z, 80) }
        repeat(100) { assertFalse(step(doubleArrayOf(it * 0.012, 0.0, 0.0))) }      // walked 1.2 m from the estimator's origin
        s.onNewEpoch(now)                                                             // the estimator is started afresh: it begins at its origin
        repeat(100) { assertFalse("the new world's states are no jump", step(doubleArrayOf(it * 0.001, 0.0, 0.0))) }
        assertTrue("a jump inside one world still is one", step(doubleArrayOf(3.0, 0.0, 0.0)))
        assertEquals(1L, s.resets)
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

    @Test fun aTiltLeftUpsideDownIsTakenAfreshFromTheAccelerometer() {
        // The glasses are turned over while no samples come (a starved link): the tilt held is half a turn wrong,
        // where the ordinary correction, which works on the sine of the error, does nothing.
        val t = HeadTracker(); var ns = 1_000_000L
        for (i in 0 until 2000) { t.onImu(ns, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 9.81f)); ns += 1_000_000L }
        assertTrue(t.imuTiltErrorDeg < 1.0)
        ns += 2_000_000_000L                                    // two seconds of nothing, and now it lies the other way up
        for (i in 0 until 150) { t.onImu(ns, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -9.81f)); ns += 1_000_000L }
        assertTrue("not yet: 0.15 s could be a knock", t.imuTiltErrorDeg > 170.0)
        for (i in 0 until 200) { t.onImu(ns, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -9.81f)); ns += 1_000_000L }
        assertTrue("" + t.imuTiltErrorDeg, t.imuTiltErrorDeg < 1.0); assertEquals(1, t.tiltReseeds)
    }

    @Test fun anEstimatorWhoseDownIsWrongDoesNotTiltTheHead() {
        val t = HeadTracker(); var ns = 1_000_000L
        for (i in 0 until 2000) { t.onImu(ns, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 9.81f)); ns += 1_000_000L }
        val up0 = Q.rotate(t.predict(ns).pose.q, doubleArrayOf(0.0, 1.0, 0.0))
        // an estimator that agrees with the accelerometer, then one that has the world upside down (half a turn about x)
        t.onVio(ns - 1_000_000L, doubleArrayOf(0.0, 0.0, 0.0), Q.identity(), doubleArrayOf(0.0, 0.0, 0.0), null)
        assertEquals(0, t.vioTiltBad); assertTrue(t.vioTiltErrorDeg < 2.0)
        for (k in 0 until 20) { t.onImu(ns, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 9.81f)); ns += 1_000_000L
            t.onVio(ns - 1_000_000L, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0), null) }
        assertEquals(20, t.vioTiltBad); assertTrue(t.vioTiltErrorDeg > 170.0)
        val up1 = Q.rotate(t.predict(ns).pose.q, doubleArrayOf(0.0, 1.0, 0.0))
        assertTrue("the head's up has not moved: " + up1.toList(), up0[0] * up1[0] + up0[1] * up1[1] + up0[2] * up1[2] > 0.99)
    }
}
