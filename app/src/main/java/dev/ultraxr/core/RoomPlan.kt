package dev.ultraxr.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The room as its owner marked it: the corners where the walls meet the floor, pointed at and pinched one after the
 * other, going round the room once. Kept from one day to the next, where the room the cameras find by themselves is
 * forgotten whenever position is lost and has walls only where the wearer has looked.
 *
 * The plan lives in a frame of its own (the application's world as it was when the plan was made). In a later
 * session the world is another one, turned and shifted; [fit] finds how the plan lies in it from the walls and table
 * tops the cameras do find. Not thread safe by itself; no Android code.
 */
class RoomPlan(val corners: List<DoubleArray>, val tables: List<DoubleArray> = emptyList(), val ceilingM: Double = 2.5) {
    /** The walls: one from each corner to the next, and from the last back to the first. */
    val walls: List<RoomRecall.Line> = corners.indices.map { i -> val a = corners[i]; val b = corners[(i + 1) % corners.size]; RoomRecall.Line(a[0], a[1], b[0], b[1]) }.filter { it.length > 0.3 }
    val usable: Boolean get() = corners.size >= 3 && walls.size >= 3

    /** How the plan lies in a world: a point (x, z) of the plan is at [apply] in that world. */
    class Fit(val yaw: Double, val tx: Double, val tz: Double, val walls: Int, val tables: Int) {
        fun apply(x: Double, z: Double) = doubleArrayOf(cos(yaw) * x + sin(yaw) * z + tx, -sin(yaw) * x + cos(yaw) * z + tz)
        fun line(l: RoomRecall.Line): RoomRecall.Line { val a = apply(l.x0, l.z0); val b = apply(l.x1, l.z1); return RoomRecall.Line(a[0], a[1], b[0], b[1]) }
        /** The same fit after the world itself was moved: every point p of the old world is now at [moved] applied to p. */
        fun moved(moved: Pose): Fit {
            val o = moved.apply(doubleArrayOf(tx, 0.0, tz)); val x = moved.apply(doubleArrayOf(tx + cos(yaw), 0.0, tz - sin(yaw)))
            return Fit(atan2(-(x[2] - o[2]), x[0] - o[0]), o[0], o[2], walls, tables)
        }
        /** The move of the world that turns [other] into this fit (for putting a slipped world back). */
        fun worldMoveFrom(other: Fit): Pose {
            // p_this = A(p_plan), p_other = B(p_plan): the move is A after the inverse of B, a turn about the vertical and a shift
            val q = Q.axisAngle(0.0, 1.0, 0.0, yaw - other.yaw)
            val r = Q.rotate(q, doubleArrayOf(other.tx, 0.0, other.tz))
            return Pose(doubleArrayOf(tx - r[0], 0.0, tz - r[2]), q)
        }
    }

    /**
     * How the plan lies in the world in which the cameras now find the walls [now] and table tops [tablesNow]
     * (x, z, height above the floor); null when two walls that cross do not settle it, or when two different places
     * do equally well (a plain rectangle looks the same from both ends: its table tops, or [near], have to say).
     * [near], when known (the fit before a loss of position: heading comes from the gyroscope and does not slip),
     * keeps the answer within [yawWithin] radians of it and the shift within [maxShift] metres of it.
     */
    fun fit(now: List<RoomRecall.Line>, tablesNow: List<DoubleArray> = emptyList(), near: Fit? = null, yawWithin: Double = 0.35, maxShift: Double = 1e9): Fit? {
        if (!usable || now.size < 2) return null
        class Cand(val f: Fit, val score: Int)
        val cands = ArrayList<Cand>()
        val yaws = ArrayList<Double>()
        for (p in walls) for (n in now) for (flip in 0..1) {
            // a turn of the plan by yaw takes a direction at angle a (atan2(uz, ux)) to the angle a - yaw
            val y = wrap(atan2(p.uz, p.ux) - atan2(n.uz, n.ux) + flip * Math.PI)
            if (near != null && abs(wrap(y - near.yaw)) > yawWithin) continue
            if (yaws.none { abs(wrap(it - y)) < 0.035 }) yaws.add(y)
        }
        for (y0 in yaws) {
            var yaw = y0
            for (pass in 0..1) {
                val turned = walls.map { Fit(yaw, 0.0, 0.0, 0, 0).line(it) }
                var best: DoubleArray? = null; var bestScore = 0
                for (i1 in now.indices) for (j1 in turned.indices) {
                    if (RoomRecall.cross(turned[j1], now[i1]) > RoomRecall.PARALLEL_SIN) continue
                    for (i2 in i1 + 1 until now.size) for (j2 in turned.indices) {
                        if (j2 == j1 || RoomRecall.cross(turned[j2], now[i2]) > RoomRecall.PARALLEL_SIN || RoomRecall.cross(turned[j1], turned[j2]) < RoomRecall.CROSSING_SIN) continue
                        // the shift of the plan that lays its two walls onto the two found ones
                        val ax = -now[i1].uz; val az = now[i1].ux; val bx = -now[i2].uz; val bz = now[i2].ux
                        val ca = RoomRecall.across(now[i1], turned[j1]); val cb = RoomRecall.across(now[i2], turned[j2])
                        val det = ax * bz - az * bx; if (abs(det) < 1e-6) continue
                        var sx = (ca * bz - az * cb) / det; var sz = (ax * cb - ca * bx) / det
                        var score = 0; var sxx = 0.0; var sxz = 0.0; var szz = 0.0; var rx = 0.0; var rz = 0.0
                        for (n in now) {
                            val o = turned.firstOrNull { landed(it.shifted(sx, sz), n) } ?: continue
                            score++
                            val nx = -n.uz; val nz = n.ux; val c = RoomRecall.across(n, o)
                            sxx += nx * nx; sxz += nx * nz; szz += nz * nz; rx += nx * c; rz += nz * c
                        }
                        if (score < 2) continue
                        val d2 = sxx * szz - sxz * sxz
                        if (abs(d2) > 0.05) { sx = (rx * szz - sxz * rz) / d2; sz = (sxx * rz - rx * sxz) / d2 }
                        val b0 = best
                        if (score > bestScore || (score == bestScore && b0 != null && near != null && dist(sx, sz, near) < dist(b0[0], b0[1], near))) { best = doubleArrayOf(sx, sz); bestScore = score }
                    }
                }
                val b = best ?: break
                if (pass == 0) {
                    // the turn, from all the walls that landed: the mean of how far each is still turned from its own
                    var sum = 0.0; var k = 0
                    for (n in now) { val o = turned.firstOrNull { landed(it.shifted(b[0], b[1]), n) } ?: continue
                        var d = wrap(atan2(o.uz, o.ux) - atan2(n.uz, n.ux)); if (d > Math.PI / 2) d -= Math.PI; if (d < -Math.PI / 2) d += Math.PI; sum += d; k++ }
                    if (k > 0) yaw = wrap(yaw + sum / k)
                    continue
                }
                if (near != null && dist(b[0], b[1], near) > maxShift) break
                val f0 = Fit(yaw, b[0], b[1], bestScore, 0)
                var t = 0
                for (pt in tables) { val w = f0.apply(pt[0], pt[1]); if (tablesNow.any { abs(it[0] - w[0]) < 0.4 && abs(it[1] - w[1]) < 0.4 && abs(it[2] - pt[2]) < 0.2 }) t++ }
                cands.add(Cand(Fit(yaw, b[0], b[1], bestScore, t), bestScore * 10 + t))
            }
        }
        val top = cands.maxByOrNull { it.score } ?: return null
        // another place that does as well and is not the same place: not known
        for (c in cands) if (c !== top && c.score == top.score && (abs(wrap(c.f.yaw - top.f.yaw)) > 0.17 || abs(c.f.tx - top.f.tx) > 0.4 || abs(c.f.tz - top.f.tz) > 0.4)) return null
        return top.f
    }
    /** A found wall [n] lies on the wall [o] of the plan: on its line, and inside its length (a marked wall is whole; what the cameras find is a part of it). */
    private fun landed(o: RoomRecall.Line, n: RoomRecall.Line): Boolean {
        if (!RoomRecall.on(o, n)) return false
        val t = (n.mx - o.mx) * o.ux + (n.mz - o.mz) * o.uz
        return abs(t) + n.length / 2 <= o.length / 2 + 0.3
    }
    private fun dist(sx: Double, sz: Double, near: Fit) = sqrt((sx - near.tx) * (sx - near.tx) + (sz - near.tz) * (sz - near.tz))

    /** A few lines of text, one thing a line: kept in the files folder of the app. */
    fun toText(): String {
        val sb = StringBuilder("ultraxr room plan 1\n")
        for (c in corners) sb.append("corner ").append(c[0]).append(' ').append(c[1]).append('\n')
        for (t in tables) sb.append("table ").append(t[0]).append(' ').append(t[1]).append(' ').append(t[2]).append('\n')
        return sb.append("ceiling ").append(ceilingM).append('\n').toString()
    }

    companion object {
        fun wrap(a: Double): Double { var x = a; while (x > Math.PI) x -= 2 * Math.PI; while (x < -Math.PI) x += 2 * Math.PI; return x }
        fun parse(text: String?): RoomPlan? {
            if (text == null || !text.startsWith("ultraxr room plan 1")) return null
            val corners = ArrayList<DoubleArray>(); val tables = ArrayList<DoubleArray>(); var ceiling = 2.5
            for (l in text.lines()) {
                val w = l.trim().split(' '); val v = w.drop(1).mapNotNull { it.toDoubleOrNull() }
                if (v.any { !it.isFinite() || abs(it) > 100 }) return null
                when (w[0]) { "corner" -> if (v.size == 2) corners.add(v.toDoubleArray()); "table" -> if (v.size == 3) tables.add(v.toDoubleArray()); "ceiling" -> if (v.size == 1) ceiling = v[0] }
            }
            return RoomPlan(corners, tables, ceiling).takeIf { it.usable }
        }
    }
}
