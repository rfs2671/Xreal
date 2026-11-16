package dev.ultraxr.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Per-unit calibration from the glasses themselves. The Air 2 Ultra stores its factory
 * calibration as JSON (read over the IMU interface). Its "SLAM_camera" section holds, for each
 * tracking camera, a "fisheye624" model (6 radial, 2 tangential, 4 thin-prism coefficients) and
 * the camera's pose relative to the IMU.
 *
 * Basalt and Mercury are fed a Kannala-Brandt ("kb4") model in XR Gate's calibration layout, so
 * this fits kb4 to the factory model and converts the pose conventions:
 *  - factory poses use a camera-like IMU frame F (x right, y down, z forward); the IMU samples the
 *    glasses send use the raw frame R (x left, y back, z up). v_R = (-x, -z, -y) of v_F.
 *  - imu_q_cam is a JPL quaternion: the camera-to-IMU rotation is the transpose of the matrix a
 *    Hamilton reading of the same numbers gives.
 * Both conventions were checked against XR Gate's independent calibration of another unit, which
 * they reproduce to about one degree and one centimetre (see FactoryCalibrationTest).
 */
object FactoryCalibration {
    class Camera(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val k: DoubleArray, val pImu: DoubleArray, val qImuJpl: DoubleArray)
    class Kb4(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val k1: Double, val k2: Double, val k3: Double, val k4: Double, val meanErrorPx: Double, val maxErrorPx: Double)
    class Result(val basaltJson: String?, val summary: String)

    const val WIDTH = 480
    const val HEIGHT = 640

    /** fisheye624 projection of a unit ray (camera frame: x right, y down, z forward) to pixels. */
    fun projectFisheye624(c: Camera, x: Double, y: Double, z: Double, out: DoubleArray) {
        val rr = hypot(x, y)
        val th = atan2(rr, z)
        val t2 = th * th
        var poly = 1.0; var p = t2
        for (i in 0..5) { poly += c.k[i] * p; p *= t2 }
        val thd = th * poly
        val ux = if (rr > 1e-12) x / rr else 0.0; val uy = if (rr > 1e-12) y / rr else 0.0
        val xr = thd * ux; val yr = thd * uy; val r2 = xr * xr + yr * yr
        val dx = (2 * xr * xr + r2) * c.k[6] + 2 * xr * yr * c.k[7] + c.k[8] * r2 + c.k[9] * r2 * r2
        val dy = (2 * yr * yr + r2) * c.k[7] + 2 * xr * yr * c.k[6] + c.k[10] * r2 + c.k[11] * r2 * r2
        out[0] = c.fx * (xr + dx) + c.cx; out[1] = c.fy * (yr + dy) + c.cy
    }

    private fun projectKb4(p: DoubleArray, x: Double, y: Double, z: Double, out: DoubleArray) {
        val rr = hypot(x, y)
        val th = atan2(rr, z)
        val t2 = th * th
        val thd = th * (1 + p[4] * t2 + p[5] * t2 * t2 + p[6] * t2 * t2 * t2 + p[7] * t2 * t2 * t2 * t2)
        val ux = if (rr > 1e-12) x / rr else 0.0; val uy = if (rr > 1e-12) y / rr else 0.0
        out[0] = p[0] * thd * ux + p[2]; out[1] = p[1] * thd * uy + p[3]
    }

    /** Least-squares kb4 fit to the factory model over every ray that lands inside the image. */
    fun fitKb4(c: Camera): Kb4 {
        val rays = ArrayList<DoubleArray>(); val target = ArrayList<DoubleArray>()
        val uv = DoubleArray(2)
        val n = 61
        for (i in 0 until n) for (j in 0 until n) {
            val a = -1.45 + 2.9 * i / (n - 1); val b = -1.45 + 2.9 * j / (n - 1)
            val th = hypot(a, b); if (th >= 1.5) continue
            val ph = atan2(b, a)
            val d = doubleArrayOf(Math.sin(th) * Math.cos(ph), Math.sin(th) * Math.sin(ph), Math.cos(th))
            projectFisheye624(c, d[0], d[1], d[2], uv)
            if (uv[0] >= 0 && uv[0] < WIDTH && uv[1] >= 0 && uv[1] < HEIGHT) { rays += d; target += uv.copyOf() }
        }
        require(rays.size > 200) { "factory camera model puts too few rays in the image (${rays.size})" }
        val p = doubleArrayOf(c.fx, c.fy, c.cx, c.cy, 0.0, 0.0, 0.0, 0.0)
        val m = rays.size * 2
        fun residual(q: DoubleArray, r: DoubleArray) { for (i in rays.indices) { projectKb4(q, rays[i][0], rays[i][1], rays[i][2], uv); r[2 * i] = uv[0] - target[i][0]; r[2 * i + 1] = uv[1] - target[i][1] } }
        val r = DoubleArray(m); val r2 = DoubleArray(m); val jac = Array(8) { DoubleArray(m) }
        repeat(25) {
            residual(p, r)
            for (j in 0..7) {
                val h = 1e-6 * maxOf(1.0, abs(p[j])); val q = p.copyOf(); q[j] += h
                residual(q, r2)
                for (i in 0 until m) jac[j][i] = (r2[i] - r[i]) / h
            }
            // Normal equations J^T J dp = -J^T r, solved by Gaussian elimination with pivoting.
            val a = Array(8) { DoubleArray(9) }
            for (u in 0..7) { for (v in 0..7) { var s = 0.0; for (i in 0 until m) s += jac[u][i] * jac[v][i]; a[u][v] = s + if (u == v) 1e-9 else 0.0 }; var s = 0.0; for (i in 0 until m) s += jac[u][i] * r[i]; a[u][8] = -s }
            for (col in 0..7) {
                var piv = col; for (row in col + 1..7) if (abs(a[row][col]) > abs(a[piv][col])) piv = row
                val t = a[col]; a[col] = a[piv]; a[piv] = t
                val d = a[col][col]; if (abs(d) < 1e-18) return@repeat
                for (row in 0..7) if (row != col) { val f = a[row][col] / d; for (k in col..8) a[row][k] -= f * a[col][k] }
            }
            for (j in 0..7) p[j] += a[j][8] / a[j][j]
        }
        residual(p, r)
        var sum = 0.0; var mx = 0.0
        for (i in rays.indices) { val e = hypot(r[2 * i], r[2 * i + 1]); sum += e; if (e > mx) mx = e }
        return Kb4(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], sum / rays.size, mx)
    }

    /** Camera pose in the raw IMU frame: position, and camera-to-IMU rotation as a Hamilton quaternion (x,y,z,w). */
    fun poseInRawImu(c: Camera): Pose {
        val q = c.qImuJpl
        // Rotation matrix of the Hamilton reading, transposed (JPL), then F -> R.
        val h = Q.conj(Q.normalize(q))
        fun toRaw(v: DoubleArray) = doubleArrayOf(-v[0], -v[2], -v[1])
        val cx = toRaw(Q.rotate(h, doubleArrayOf(1.0, 0.0, 0.0))); val cy = toRaw(Q.rotate(h, doubleArrayOf(0.0, 1.0, 0.0))); val cz = toRaw(Q.rotate(h, doubleArrayOf(0.0, 0.0, 1.0)))
        val rot = Q.fromMatrix(cx[0], cy[0], cz[0], cx[1], cy[1], cz[1], cx[2], cy[2], cz[2])
        return Pose(toRaw(c.pImu), rot)
    }

    fun parseCameras(factoryJson: String): List<Camera> {
        val slam = JSONObject(factoryJson).getJSONObject("SLAM_camera")
        require(slam.getInt("num_of_cameras") == 2) { "expected 2 tracking cameras, found ${slam.optInt("num_of_cameras")}" }
        return (1..2).map { i ->
            val d = slam.getJSONObject("device_$i")
            require(d.getString("camera_model") == "fisheye624") { "unsupported camera model '${d.optString("camera_model")}'" }
            val res = d.getJSONArray("resolution")
            require(res.getInt(0) == WIDTH && res.getInt(1) == HEIGHT) { "unexpected resolution ${res.getInt(0)}x${res.getInt(1)}" }
            fun arr(name: String, n: Int): DoubleArray { val a: JSONArray = d.getJSONArray(name); require(a.length() == n) { "$name has ${a.length()} values, expected $n" }; return DoubleArray(n) { a.getDouble(it) } }
            val fc = arr("fc", 2); val cc = arr("cc", 2)
            Camera(fc[0], fc[1], cc[0], cc[1], arr("kc", 12), arr("imu_p_cam", 3), arr("imu_q_cam", 4))
        }
    }

    /**
     * Builds a Basalt/Mercury calibration for this unit from its factory JSON, using
     * [referenceJson] (XR Gate's calibration of another unit) as the template for everything the
     * factory data does not cover, and as a plausibility check. Never throws: on any problem the
     * result has no JSON and the summary says why.
     */
    fun build(factoryJson: String, referenceJson: String): Result {
        return try {
            val cams = parseCameras(factoryJson)
            val root = JSONObject(referenceJson)
            val v = root.getJSONObject("value0")
            val refPoses = v.getJSONArray("T_imu_cam")
            val poses = JSONArray(); val intr = JSONArray()
            val notes = StringBuilder()
            for ((i, c) in cams.withIndex()) {
                val fit = fitKb4(c)
                require(fit.meanErrorPx < 1.5 && fit.maxErrorPx < 6.0) { "camera $i: kb4 fit too coarse (mean ${Fmt.f(fit.meanErrorPx, 2)} px, max ${Fmt.f(fit.maxErrorPx, 2)} px)" }
                val pose = poseInRawImu(c)
                val ref = refPoses.getJSONObject(i)
                val refQ = Q.normalize(doubleArrayOf(ref.getDouble("qx"), ref.getDouble("qy"), ref.getDouble("qz"), ref.getDouble("qw")))
                val rotDiff = Math.toDegrees(Q.angleBetween(refQ, pose.q))
                val posDiff = sqrt((0..2).sumOf { val d = pose.p[it] - doubleArrayOf(ref.getDouble("px"), ref.getDouble("py"), ref.getDouble("pz"))[it]; d * d })
                // Units of one model are built alike: a large difference means a convention is not what was assumed.
                require(rotDiff < 6.0 && posDiff < 0.03) { "camera $i: pose differs from the reference unit by ${Fmt.f(rotDiff, 1)} deg / ${Fmt.f(posDiff * 100, 1)} cm, conventions not as expected" }
                poses.put(JSONObject().put("px", pose.p[0]).put("py", pose.p[1]).put("pz", pose.p[2]).put("qx", pose.q[0]).put("qy", pose.q[1]).put("qz", pose.q[2]).put("qw", pose.q[3]))
                intr.put(JSONObject().put("camera_type", "kb4").put("intrinsics", JSONObject().put("fx", fit.fx).put("fy", fit.fy).put("cx", fit.cx).put("cy", fit.cy).put("k1", fit.k1).put("k2", fit.k2).put("k3", fit.k3).put("k4", fit.k4)))
                notes.append("camera ").append(i).append(": fit ").append(Fmt.f(fit.meanErrorPx, 2)).append(" px mean / ").append(Fmt.f(fit.maxErrorPx, 2)).append(" px max, fx ").append(Fmt.f(fit.fx, 1))
                    .append(" cx ").append(Fmt.f(fit.cx, 1)).append(" cy ").append(Fmt.f(fit.cy, 1)).append(", vs reference unit ").append(Fmt.f(rotDiff, 2)).append(" deg / ").append(Fmt.f(posDiff * 1000, 1)).append(" mm; ")
            }
            val base = sqrt((0..2).sumOf { val d = poses.getJSONObject(0).getDouble(listOf("px", "py", "pz")[it]) - poses.getJSONObject(1).getDouble(listOf("px", "py", "pz")[it]); d * d })
            require(base in 0.12..0.155) { "camera baseline ${Fmt.f(base * 1000, 1)} mm is implausible" }
            v.put("T_imu_cam", poses); v.put("intrinsics", intr)
            Result(root.toString(), "this unit's factory calibration (" + notes + "baseline " + Fmt.f(base * 1000, 1) + " mm)")
        } catch (t: Throwable) { Result(null, "factory calibration not usable: " + (t.message ?: t.toString())) }
    }
}
