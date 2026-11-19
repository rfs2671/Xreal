package dev.ultraxr.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns raw sensor streams into the head pose used for rendering.
 *
 * Orientation is always the gyro-integrated IMU orientation (smooth, ~1 kHz, low latency),
 * tilt-corrected by the accelerometer. When the visual-inertial estimator (Basalt) delivers
 * a state, the difference between its orientation and the gyro orientation at that same
 * instant is kept as a correction, and its position/velocity are extrapolated to "now".
 * If the estimator stops (tracking loss), the head keeps rotating from the IMU and holds
 * its last position: a 3DoF fallback that needs no mode switch. When the estimator comes
 * back in a new world frame, a yaw + translation fix keeps the app's world where it was.
 *
 * Frames: IMU body I (x left, y back, z up on the Air 2 Ultra, per the XR Gate calibration),
 * head H (OpenXR view: x right, y up, -z forward), app world A (y up, metres).
 */
class HeadTracker(private val headInImu: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0)) {
    enum class Mode { NONE, ORIENTATION_ONLY, SIX_DOF }
    class State(val pose: Pose, val mode: Mode, val velocity: DoubleArray, val vioAgeNs: Long,
        /** Angular velocity about the app world's axes, rad/s. */
        val angularVelocity: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0))

    /** Host time of the newest IMU sample (0 before the first). */
    val latestImuNs: Long @Synchronized get() = lastImuNs

    // --- gyro/accel orientation, z-up world G
    private var qGI = Q.identity()
    private var haveGravity = false
    private var imuCount = 0L
    private var lastImuNs = 0L
    private val lastGyro = DoubleArray(3)
    private val gyroBias = DoubleArray(3)
    private val histT = LongArray(HIST)
    private val histQ = DoubleArray(HIST * 4)
    private var histN = 0L

    // --- visual-inertial state, y-up
    private var vioNs = 0L
    private var vioP = doubleArrayOf(0.0, 0.0, 0.0)
    private var vioV = doubleArrayOf(0.0, 0.0, 0.0)
    private var corr = Q.identity()          // vio orientation = corr * gyro orientation
    private var haveVio = false
    private var epochPending = false
    private var heldP = doubleArrayOf(0.0, 0.0, 0.0)  // last good position, world before fix

    // --- app world fix (recenter + recovery): yaw about Y plus translation
    private var fix = Pose()
    private var recenterPending = true

    /** Degrees between the estimator's down and the accelerometer's at the last state, and for how many states running it has been past the limit. */
    @Volatile var vioTiltErrorDeg = 0.0; private set
    @Volatile var vioTiltBad = 0; private set
    /** Degrees between the tilt held here and what the accelerometer says, at rest; how often the tilt had to be taken afresh. */
    @Volatile var imuTiltErrorDeg = 0.0; private set
    @Volatile var tiltReseeds = 0; private set
    private var farWrong = 0
    @Volatile var vioStates = 0L; private set
    @Volatile var epochs = 0L; private set

    /** IMU sample on the host clock, in the IMU frame (rad/s, m/s^2). */
    @Synchronized fun onImu(tNs: Long, gyro: FloatArray, accel: FloatArray) {
        val dt = if (lastImuNs == 0L) 0.0 else (tNs - lastImuNs) * 1e-9
        lastImuNs = tNs
        val wx = gyro[0] - gyroBias[0]; val wy = gyro[1] - gyroBias[1]; val wz = gyro[2] - gyroBias[2]
        lastGyro[0] = wx; lastGyro[1] = wy; lastGyro[2] = wz
        val an = sqrt((accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2]).toDouble())
        if (!haveGravity) {
            if (an > 8.0 && an < 11.6) { qGI = tiltFromAccel(accel[0] / an, accel[1] / an, accel[2] / an); haveGravity = true }
        } else if (dt > 0 && dt < 0.1) {
            var ex = 0.0; var ey = 0.0; var ez = 0.0
            if (abs(an - G) < 1.5) {
                // Error between measured "up" (specific force at rest) and estimated up, in body frame.
                val up = Q.rotate(Q.conj(qGI), doubleArrayOf(0.0, 0.0, 1.0))
                val ax = accel[0] / an; val ay = accel[1] / an; val az = accel[2] / an
                ex = ay * up[2] - az * up[1]; ey = az * up[0] - ax * up[2]; ez = ax * up[1] - ay * up[0]
            }
            // Far wrong (samples were missing while the glasses were turned over, say): the correction above works
            // on the sine of the error, which is nothing at half a turn, so an upside-down estimate would stay.
            // A fifth of a second of quiet readings that all say so, and the tilt is taken from them afresh.
            if (abs(an - G) < 1.0) {
                val up = Q.rotate(Q.conj(qGI), doubleArrayOf(0.0, 0.0, 1.0))
                val agree = (accel[0] * up[0] + accel[1] * up[1] + accel[2] * up[2]) / an
                imuTiltErrorDeg = Math.toDegrees(Math.acos(agree.coerceIn(-1.0, 1.0)))
                if (agree < 0.5) { if (++farWrong > 200) { qGI = tiltFromAccel(accel[0] / an, accel[1] / an, accel[2] / an); farWrong = 0; tiltReseeds++ } } else farWrong = 0
            }
            val kp = if (imuCount < 1500) 2.0 else 0.15
            qGI = Q.normalize(Q.mul(qGI, Q.exp((wx + kp * ex) * dt, (wy + kp * ey) * dt, (wz + kp * ez) * dt)))
        }
        imuCount++
        val i = (histN % HIST).toInt()
        histT[i] = tNs
        histQ[i * 4] = qGI[0]; histQ[i * 4 + 1] = qGI[1]; histQ[i * 4 + 2] = qGI[2]; histQ[i * 4 + 3] = qGI[3]
        histN++
    }

    /** State from the visual-inertial estimator: body in its z-up world, on the host clock. */
    @Synchronized fun onVio(tNs: Long, p: DoubleArray, q: DoubleArray, v: DoubleArray, gyroBiasEst: DoubleArray?) {
        if (!p.all { it.isFinite() } || !q.all { it.isFinite() }) return
        val gyroAt = gyroOrientationAt(tNs) ?: return
        val pY = Q.rotate(Z_TO_Y, p); val vY = Q.rotate(Z_TO_Y, v)
        val qY = Q.normalize(Q.mul(Z_TO_Y, q))
        var newCorr = Q.normalize(Q.mul(qY, Q.conj(Q.mul(Z_TO_Y, gyroAt))))
        // The estimator's "down" against the accelerometer's own. A few degrees apart is ordinary; tens of degrees
        // is an estimator that started wrong (it takes its first reading as down, moving or not). Then only its
        // heading is taken, the tilt stays the accelerometer's, and the caller is told so that it can start it afresh.
        val swing = 2.0 * Math.toDegrees(Math.acos(Math.sqrt(newCorr[1] * newCorr[1] + newCorr[3] * newCorr[3]).coerceIn(0.0, 1.0)))
        vioTiltErrorDeg = swing
        if (swing > VIO_TILT_LIMIT_DEG) {
            vioTiltBad++
            val n = Math.sqrt(newCorr[1] * newCorr[1] + newCorr[3] * newCorr[3])
            newCorr = if (n > 1e-6) doubleArrayOf(0.0, newCorr[1] / n, 0.0, newCorr[3] / n) else corr
        } else vioTiltBad = 0
        if (epochPending || !haveVio) {
            if (!recenterPending) {
                // Keep the app world where it was: old estimate of the body at tNs must equal the new one.
                val oldQ = Q.mul(fix.q, Q.mul(corr, Q.mul(Z_TO_Y, gyroAt)))
                val oldP = fix.apply(heldP)
                val newQ = Q.mul(newCorr, Q.mul(Z_TO_Y, gyroAt))
                // Both worlds are gravity-aligned, so they differ by a turn about the vertical:
                // take the twist about Y of the relative rotation.
                val rel = Q.mul(oldQ, Q.conj(newQ))
                val fq = Q.normalize(doubleArrayOf(0.0, rel[1], 0.0, rel[3]))
                val rp = Q.rotate(fq, pY)
                fix = Pose(doubleArrayOf(oldP[0] - rp[0], oldP[1] - rp[1], oldP[2] - rp[2]), fq)
            }
            epochPending = false
            epochs++
            corr = newCorr
        } else {
            // Blend the correction a little to hide estimator jitter between frames.
            corr = Q.normalize(Q.slerp(corr, newCorr, 0.5))
        }
        gyroBiasEst?.let { if (it.all { b -> b.isFinite() && abs(b) < 0.2 }) { gyroBias[0] = it[0]; gyroBias[1] = it[1]; gyroBias[2] = it[2] } }
        vioNs = tNs; vioP = pY; vioV = vY; heldP = pY; heldValid = true
        haveVio = true
        vioStates++
    }
    private var heldValid = false

    /** The estimator was restarted: its next state lives in a new world frame. */
    @Synchronized fun onVioEpochReset() { if (haveVio) epochPending = true; haveVio = false }

    /** Makes the current head position and heading the origin of the app world. */
    @Synchronized fun recenter() { recenterPending = true }

    /**
     * Moves the app world along the floor by ([dx], [dz]) without turning it: the room was found again after a loss
     * of position, this far from where the world had been joined on ([RoomRecall]).
     */
    @Synchronized fun shiftWorld(dx: Double, dz: Double) = moveWorld(Pose(doubleArrayOf(dx, 0.0, dz)))

    /** Moves the app world by [moved] (a turn about the vertical and a shift along the floor): every point p of it is then at moved applied to p. */
    @Synchronized fun moveWorld(moved: Pose) {
        fix = moved * fix
        worldVersion++
        onWorldMoved?.invoke(moved)
    }

    /** Called when a recentre moves the app world: every point p of the old world is now at the given pose applied to p. */
    @Volatile var onWorldMoved: ((Pose) -> Unit)? = null
    /** Counts recentres, so work begun in the old world can be told from work begun in the new one. */
    @Volatile var worldVersion = 0; private set
    /** The estimator's world (turned y-up) in the app world, now. */
    @Synchronized fun appFromEstimator(): Pose = fix

    /** Head pose in the app world predicted for host time [tNs]. */
    @Synchronized fun predict(tNs: Long): State {
        if (!haveGravity) return State(Pose(), Mode.NONE, doubleArrayOf(0.0, 0.0, 0.0), Long.MAX_VALUE)
        val ahead = ((tNs - lastImuNs) * 1e-9).coerceIn(0.0, MAX_GYRO_EXTRAPOLATION_S)
        val qNow = Q.mul(qGI, Q.exp(lastGyro[0] * ahead, lastGyro[1] * ahead, lastGyro[2] * ahead))
        val qBody = Q.normalize(Q.mul(corr, Q.mul(Z_TO_Y, qNow)))
        val age = if (haveVio) tNs - vioNs else Long.MAX_VALUE
        val sixDof = haveVio && age < VIO_STALE_NS
        val vel = if (sixDof) vioV else doubleArrayOf(0.0, 0.0, 0.0)
        val pBody = if (sixDof) {
            val dt = (age * 1e-9).coerceIn(0.0, MAX_POSITION_EXTRAPOLATION_S)
            doubleArrayOf(vioP[0] + vel[0] * dt, vioP[1] + vel[1] * dt, vioP[2] + vel[2] * dt).also { heldP = it }
        } else heldP
        var head = Pose(pBody, qBody) * Pose(headInImu, IMU_TO_HEAD)
        if (recenterPending) {
            val h = fix * head
            val k = Q.axisAngle(0.0, 1.0, 0.0, -Q.yawY(h.q))
            val kp = Q.rotate(k, h.p)
            val moved = Pose(doubleArrayOf(-kp[0], -kp[1], -kp[2]), k)
            fix = moved * fix
            recenterPending = false
            worldVersion++
            onWorldMoved?.invoke(moved)
        }
        head = fix * head
        val w = Q.rotate(Q.mul(fix.q, qBody), lastGyro)
        return State(head, if (sixDof) Mode.SIX_DOF else Mode.ORIENTATION_ONLY, Q.rotate(fix.q, vel), age, w)
    }

    /** Transform from the IMU body frame at host time [tNs] to the app world (for hand joints). */
    @Synchronized fun bodyInApp(tNs: Long): Pose? {
        if (!haveGravity) return null
        val g = gyroOrientationAt(tNs) ?: qGI
        val qBody = Q.normalize(Q.mul(corr, Q.mul(Z_TO_Y, g)))
        return fix * Pose(heldP, qBody)
    }

    private fun gyroOrientationAt(tNs: Long): DoubleArray? {
        if (histN == 0L) return null
        val n = minOf(histN, HIST.toLong()).toInt()
        // Newest to oldest; samples are ~1 ms apart so a short linear scan from the end is enough.
        var best = -1
        var bestD = Long.MAX_VALUE
        for (k in 0 until n) {
            val i = ((histN - 1 - k) % HIST).toInt()
            val d = abs(histT[i] - tNs)
            if (d < bestD) { bestD = d; best = i } else if (histT[i] < tNs) break
        }
        if (best < 0 || bestD > 100_000_000L) return null
        return doubleArrayOf(histQ[best * 4], histQ[best * 4 + 1], histQ[best * 4 + 2], histQ[best * 4 + 3])
    }

    /** Orientation whose body "up" matches the measured specific force direction, yaw zero. */
    private fun tiltFromAccel(ax: Double, ay: Double, az: Double): DoubleArray {
        // Rotation taking body vector a to world (0,0,1).
        val d = az
        if (d > 0.999999) return Q.identity()
        if (d < -0.999999) return doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        // axis = a x z = (ay, -ax, 0), angle = acos(d)
        return Q.axisAngle(ay, -ax, 0.0, Math.acos(d))
    }

    companion object {
        private const val HIST = 1024
        /** Past this many degrees between the estimator's down and the accelerometer's, the estimator's tilt is not used. */
        const val VIO_TILT_LIMIT_DEG = 25.0
        private const val G = 9.80665
        const val VIO_STALE_NS = 400_000_000L
        const val MAX_GYRO_EXTRAPOLATION_S = 0.05
        const val MAX_POSITION_EXTRAPOLATION_S = 0.12
        /** z-up world -> y-up world: (x, y, z) -> (x, z, -y). */
        val Z_TO_Y: DoubleArray = Q.axisAngle(1.0, 0.0, 0.0, -Math.PI / 2)
        /** Head axes in the IMU frame: right = -x, up = +z, back = +y. */
        val IMU_TO_HEAD: DoubleArray = Q.fromMatrix(-1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0)
    }
}

/**
 * Decides when the visual-inertial estimator has gone wrong and must be restarted.
 * Fed with every estimator state; returns true when a reset should be issued.
 */
class VioSupervisor {
    private var lastStateNs = 0L
    private var lastP: DoubleArray? = null
    private var lowFeatureSinceNs = 0L
    private var lastResetNs = 0L
    private var lastFrameNs = 0L
    var resets = 0L; private set
    var lastReason = ""; private set

    /** A stereo pair was handed to the estimator at [tNs]. */
    fun onFrame(tNs: Long) { lastFrameNs = tNs; if (lastStateNs == 0L) lastStateNs = tNs }

    /** Returns true if the estimator should be reset now. */
    fun onState(nowNs: Long, stateNs: Long, fresh: Boolean, p: DoubleArray, v: DoubleArray, features: Int): Boolean {
        if (nowNs - lastResetNs < COOLDOWN_NS) { if (fresh) accept(stateNs, p); return false }
        var reason: String? = null
        if (fresh) {
            val speed = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            val prev = lastP
            when {
                !p.all { it.isFinite() } || !v.all { it.isFinite() } -> reason = "non-finite state"
                speed > MAX_SPEED -> reason = "speed %.1f m/s".format(speed)
                prev != null && dist(prev, p) > MAX_JUMP -> reason = "position jump %.2f m".format(dist(prev, p))
            }
            if (features in 0 until MIN_FEATURES) {
                if (lowFeatureSinceNs == 0L) lowFeatureSinceNs = nowNs
                else if (nowNs - lowFeatureSinceNs > LOW_FEATURE_NS) reason = reason ?: "too few features for 3 s"
            } else lowFeatureSinceNs = 0
            if (reason == null) accept(stateNs, p)
        } else if (lastP != null && lastFrameNs != 0L && nowNs - lastFrameNs < 500_000_000L && nowNs - lastStateNs > STALL_NS) {
            // Only once this epoch has produced a state: before that the estimator may simply be
            // waiting for the head to hold still, and resetting would restart that wait.
            reason = "no output for %.1f s while frames arrive".format((nowNs - lastStateNs) * 1e-9)
        }
        if (reason == null) return false
        lastReason = reason; resets++; lastResetNs = nowNs; lastP = null; lowFeatureSinceNs = 0; lastStateNs = nowNs
        return true
    }

    private fun accept(stateNs: Long, p: DoubleArray) { lastStateNs = maxOf(lastStateNs, stateNs); lastP = p.copyOf() }
    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    companion object {
        const val COOLDOWN_NS = 1_500_000_000L
        const val STALL_NS = 2_000_000_000L
        const val LOW_FEATURE_NS = 3_000_000_000L
        const val MIN_FEATURES = 8
        const val MAX_SPEED = 6.0
        const val MAX_JUMP = 0.5
    }
}
