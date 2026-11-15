package dev.ultraxr.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Quaternion (x, y, z, w) and vector helpers on DoubleArray. Hamilton convention, q rotates body -> world. */
object Q {
    fun identity() = doubleArrayOf(0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    fun conj(q: DoubleArray) = doubleArrayOf(-q[0], -q[1], -q[2], q[3])

    fun normalize(q: DoubleArray): DoubleArray {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (n < 1e-12) identity() else doubleArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
    }

    fun rotate(q: DoubleArray, v: DoubleArray): DoubleArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val tx = 2 * (y * v[2] - z * v[1]); val ty = 2 * (z * v[0] - x * v[2]); val tz = 2 * (x * v[1] - y * v[0])
        return doubleArrayOf(
            v[0] + w * tx + (y * tz - z * ty),
            v[1] + w * ty + (z * tx - x * tz),
            v[2] + w * tz + (x * ty - y * tx),
        )
    }

    /** Rotation of [angle] radians about unit-or-not [axis]. */
    fun axisAngle(ax: Double, ay: Double, az: Double, angle: Double): DoubleArray {
        val n = sqrt(ax * ax + ay * ay + az * az)
        if (n < 1e-12) return identity()
        val s = sin(angle / 2) / n
        return doubleArrayOf(ax * s, ay * s, az * s, cos(angle / 2))
    }

    /** Exponential map: rotation vector (rad) -> quaternion. */
    fun exp(rx: Double, ry: Double, rz: Double): DoubleArray {
        val a = sqrt(rx * rx + ry * ry + rz * rz)
        if (a < 1e-9) return normalize(doubleArrayOf(rx / 2, ry / 2, rz / 2, 1.0))
        val s = sin(a / 2) / a
        return doubleArrayOf(rx * s, ry * s, rz * s, cos(a / 2))
    }

    /** Angle in radians between two orientations. */
    fun angleBetween(a: DoubleArray, b: DoubleArray): Double {
        val d = mul(conj(a), b)
        return 2 * atan2(sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), abs(d[3]))
    }

    /** Quaternion from a rotation matrix given as rows. */
    fun fromMatrix(m00: Double, m01: Double, m02: Double, m10: Double, m11: Double, m12: Double, m20: Double, m21: Double, m22: Double): DoubleArray {
        val tr = m00 + m11 + m22
        val q = when {
            tr > 0 -> { val s = sqrt(tr + 1.0) * 2; doubleArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s) }
            m00 > m11 && m00 > m22 -> { val s = sqrt(1.0 + m00 - m11 - m22) * 2; doubleArrayOf(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s) }
            m11 > m22 -> { val s = sqrt(1.0 + m11 - m00 - m22) * 2; doubleArrayOf((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s) }
            else -> { val s = sqrt(1.0 + m22 - m00 - m11) * 2; doubleArrayOf((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s) }
        }
        return normalize(q)
    }

    /** Heading (rotation about +Y, radians) of the direction this orientation's -Z axis points, Y-up world. */
    fun yawY(q: DoubleArray): Double {
        val f = rotate(q, doubleArrayOf(0.0, 0.0, -1.0))
        return atan2(-f[0], -f[2])
    }

    fun slerp(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
        var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        val bb = if (d < 0) { d = -d; doubleArrayOf(-b[0], -b[1], -b[2], -b[3]) } else b
        if (d > 0.9995) return normalize(DoubleArray(4) { a[it] + t * (bb[it] - a[it]) })
        val th = Math.acos(d)
        val sa = sin((1 - t) * th) / sin(th); val sb = sin(t * th) / sin(th)
        return DoubleArray(4) { sa * a[it] + sb * bb[it] }
    }

    /** Column-major 4x4 float matrix (OpenGL) of the rigid transform (q, p). */
    fun toMatrix(q: DoubleArray, p: DoubleArray, m: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        m[0] = (1 - 2 * (y * y + z * z)).toFloat(); m[1] = (2 * (x * y + w * z)).toFloat(); m[2] = (2 * (x * z - w * y)).toFloat(); m[3] = 0f
        m[4] = (2 * (x * y - w * z)).toFloat(); m[5] = (1 - 2 * (x * x + z * z)).toFloat(); m[6] = (2 * (y * z + w * x)).toFloat(); m[7] = 0f
        m[8] = (2 * (x * z + w * y)).toFloat(); m[9] = (2 * (y * z - w * x)).toFloat(); m[10] = (1 - 2 * (x * x + y * y)).toFloat(); m[11] = 0f
        m[12] = p[0].toFloat(); m[13] = p[1].toFloat(); m[14] = p[2].toFloat(); m[15] = 1f
    }
}

/** Rigid pose: position (m) and orientation (x, y, z, w). */
class Pose(val p: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0), val q: DoubleArray = Q.identity()) {
    /** this * other: applies [other] in this pose's frame. */
    operator fun times(o: Pose): Pose {
        val r = Q.rotate(q, o.p)
        return Pose(doubleArrayOf(p[0] + r[0], p[1] + r[1], p[2] + r[2]), Q.normalize(Q.mul(q, o.q)))
    }

    fun inverse(): Pose {
        val qi = Q.conj(q)
        val r = Q.rotate(qi, p)
        return Pose(doubleArrayOf(-r[0], -r[1], -r[2]), qi)
    }

    fun apply(v: DoubleArray): DoubleArray {
        val r = Q.rotate(q, v)
        return doubleArrayOf(r[0] + p[0], r[1] + p[1], r[2] + p[2])
    }
}
