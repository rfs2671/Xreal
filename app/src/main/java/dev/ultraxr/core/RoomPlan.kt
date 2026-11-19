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
     * (x, z, height above the floor); null when it cannot be said.
     *
     * A marked plan follows every nook, in walls a few decimetres long; what the cameras find is the long straight
     * run of a wall, which may pass several of the plan's walls and their steps. So a found wall is not asked to be
     * one of the plan's walls: every 20 cm along each found wall a point is taken, and a place for the plan is good
     * when at least four fifths of those points come to lie within 20 cm of the plan's outline. Places to try come
     * from laying two found walls that cross along two walls of the plan that run the same way. The best place is
     * taken only when no different place does nearly as well (a plain rectangle looks the same from both ends: its
     * table tops, or [near], have to say).
     *
     * [near], when known (the fit before a loss of position: heading comes from the gyroscope and does not slip),
     * keeps the answer within [yawWithin] radians of it and the shift within [maxShift] metres of it.
     */
    fun fit(now: List<RoomRecall.Line>, tablesNow: List<DoubleArray> = emptyList(), near: Fit? = null, yawWithin: Double = 0.35, maxShift: Double = 1e9): Fit? {
        if (!usable || now.size < 2) return null
        // the points along what was found, and what there is to lay them against
        val px = ArrayList<Double>(); val pz = ArrayList<Double>()
        for (n in now) { val k = maxOf(2, (n.length / 0.2).toInt()); for (i in 0..k) { px.add(n.x0 + (n.x1 - n.x0) * i / k); pz.add(n.z0 + (n.z1 - n.z0) * i / k) } }
        if (px.size < 12) return null
        val guides = walls.filter { it.length >= 0.6 }.ifEmpty { walls }
        val pairs = ArrayList<IntArray>(); for (i in now.indices) for (j in i + 1 until now.size) if (RoomRecall.cross(now[i], now[j]) >= RoomRecall.CROSSING_SIN) pairs.add(intArrayOf(i, j))
        if (pairs.isEmpty()) return null
        val yaws = ArrayList<Double>()
        for (g in guides) for (n in now) for (flip in 0..1) {
            // a turn of the plan by yaw takes a direction at angle a (atan2(uz, ux)) to the angle a - yaw
            val y = wrap(atan2(g.uz, g.ux) - atan2(n.uz, n.ux) + flip * Math.PI)
            if (near != null && abs(wrap(y - near.yaw)) > yawWithin) continue
            if (yaws.none { abs(wrap(it - y)) < 0.05 }) yaws.add(y)
        }
        class Cand(val yaw: Double, val sx: Double, val sz: Double, val score: Double)
        val cands = ArrayList<Cand>()
        val ex0 = DoubleArray(walls.size); val ez0 = DoubleArray(walls.size); val ex1 = DoubleArray(walls.size); val ez1 = DoubleArray(walls.size)
        /** The share of the found points within 20 cm of the plan's outline, turned (already in ex, ez) and shifted by (sx, sz). */
        fun share(sx: Double, sz: Double, enough: Double): Double {
            var hit = 0; val n = px.size
            for (i in 0 until n) {
                val x = px[i] - sx; val z = pz[i] - sz; var near2 = false
                for (e in walls.indices) {
                    val dx = ex1[e] - ex0[e]; val dz = ez1[e] - ez0[e]; val l2 = dx * dx + dz * dz
                    var t = ((x - ex0[e]) * dx + (z - ez0[e]) * dz) / l2; if (t < 0) t = 0.0; if (t > 1) t = 1.0
                    val qx = ex0[e] + dx * t - x; val qz = ez0[e] + dz * t - z
                    if (qx * qx + qz * qz < 0.04) { near2 = true; break }
                }
                if (near2) hit++
                if (hit + (n - 1 - i) < enough * n) return 0.0      // cannot reach what is asked any more
            }
            return hit.toDouble() / n
        }
        for (y0 in yaws) for (dy in doubleArrayOf(0.0, -0.035, 0.035)) {
            val yaw = y0 + dy; val f0 = Fit(yaw, 0.0, 0.0, 0, 0)
            for (e in walls.indices) { val l = f0.line(walls[e]); ex0[e] = l.x0; ez0[e] = l.z0; ex1[e] = l.x1; ez1[e] = l.z1 }
            val turned = guides.map { f0.line(it) }
            val seen = HashSet<Long>()
            for (pr in pairs) {
                val n1 = now[pr[0]]; val n2 = now[pr[1]]
                for (g1 in turned) { if (RoomRecall.cross(g1, n1) > RoomRecall.PARALLEL_SIN) continue
                    for (g2 in turned) { if (g2 === g1 || RoomRecall.cross(g2, n2) > RoomRecall.PARALLEL_SIN) continue
                        // the shift of the plan that lays g1 along n1 and g2 along n2
                        val ax = -n1.uz; val az = n1.ux; val bx = -n2.uz; val bz = n2.ux
                        val ca = RoomRecall.across(n1, g1); val cb = RoomRecall.across(n2, g2)
                        val det = ax * bz - az * bx; if (abs(det) < 1e-6) continue
                        val sx = (ca * bz - az * cb) / det; val sz = (ax * cb - ca * bx) / det
                        if (near != null && sqrt((sx - near.tx) * (sx - near.tx) + (sz - near.tz) * (sz - near.tz)) > maxShift) continue
                        val key = (Math.round(sx / 0.1) + 5000) * 100000L + (Math.round(sz / 0.1) + 5000)
                        if (!seen.add(key)) continue
                        val sc = share(sx, sz, 0.8)
                        if (sc >= 0.8) cands.add(Cand(yaw, sx, sz, sc))
                    }
                }
            }
        }
        if (cands.isEmpty()) return null
        fun tablesAt(c: Cand): Int { val f = Fit(c.yaw, c.sx, c.sz, 0, 0); var t = 0
            for (pt in tables) { val w = f.apply(pt[0], pt[1]); if (tablesNow.any { abs(it[0] - w[0]) < 0.4 && abs(it[1] - w[1]) < 0.4 && abs(it[2] - pt[2]) < 0.2 }) t++ }
            return t }
        fun total(c: Cand) = c.score + 0.08 * tablesAt(c)
        val top = cands.maxByOrNull { total(it) }!!
        // another place that does nearly as well and is not the same place: not known
        for (c in cands) if (c !== top && total(top) - total(c) < 0.08 && (abs(wrap(c.yaw - top.yaw)) > 0.17 || abs(c.sx - top.sx) > 0.45 || abs(c.sz - top.sz) > 0.45)) return null
        // the places that are this place, averaged by how well they did: steadier than the best alone
        var sw = 0.0; var ax = 0.0; var az = 0.0; var ay = 0.0
        for (c in cands) if (abs(wrap(c.yaw - top.yaw)) <= 0.17 && abs(c.sx - top.sx) <= 0.45 && abs(c.sz - top.sz) <= 0.45 && c.score >= top.score - 0.05) { val w = c.score; sw += w; ax += w * c.sx; az += w * c.sz; ay += w * wrap(c.yaw - top.yaw) }
        // and settled finely: each found point is drawn to the nearest place on the outline, and the turn and shift
        // that bring them all nearest together are taken, three times over
        var yaw = wrap(top.yaw + ay / sw); var tx = ax / sw; var tz = az / sw
        repeat(3) {
            val f = Fit(yaw, tx, tz, 0, 0)
            for (e in walls.indices) { val l = f.line(walls[e]); ex0[e] = l.x0; ez0[e] = l.z0; ex1[e] = l.x1; ez1[e] = l.z1 }
            var n = 0; var mpx = 0.0; var mpz = 0.0; var mqx = 0.0; var mqz = 0.0
            val qx = DoubleArray(px.size); val qz = DoubleArray(px.size); val use = BooleanArray(px.size)
            for (i in px.indices) {
                var bd = 0.09; var bx = 0.0; var bz = 0.0; var got = false
                for (e in walls.indices) {
                    val dx = ex1[e] - ex0[e]; val dz = ez1[e] - ez0[e]; val l2 = dx * dx + dz * dz
                    var t = ((px[i] - ex0[e]) * dx + (pz[i] - ez0[e]) * dz) / l2; if (t < 0) t = 0.0; if (t > 1) t = 1.0
                    val cx = ex0[e] + dx * t; val cz = ez0[e] + dz * t; val d = (cx - px[i]) * (cx - px[i]) + (cz - pz[i]) * (cz - pz[i])
                    if (d < bd) { bd = d; bx = cx; bz = cz; got = true }
                }
                if (got) { use[i] = true; qx[i] = bx; qz[i] = bz; n++; mpx += px[i]; mpz += pz[i]; mqx += bx; mqz += bz }
            }
            if (n < 8) return@repeat
            mpx /= n; mpz /= n; mqx /= n; mqz /= n
            var sc = 0.0; var ss = 0.0
            for (i in px.indices) if (use[i]) { val ux = qx[i] - mqx; val uz = qz[i] - mqz; val vx = px[i] - mpx; val vz = pz[i] - mpz; sc += ux * vx + uz * vz; ss += ux * vz - uz * vx }
            val th = atan2(ss, sc)      // the turn (x towards z) that takes the outline's points onto the found ones
            val c = cos(th); val sn = sin(th)
            val ntx = c * tx - sn * tz + (mpx - (c * mqx - sn * mqz)); val ntz = sn * tx + c * tz + (mpz - (sn * mqx + c * mqz))
            yaw = wrap(yaw - th); tx = ntx; tz = ntz
        }
        val best = Cand(yaw, tx, tz, top.score)
        return Fit(best.yaw, best.sx, best.sz, now.size, tablesAt(best))
    }

    /**
     * How the plan lies in a world in which the wearer has shown where some of its corners are: [taps] are (x, z) of
     * corners of the room looked at and tapped, any of them, in any order, three at least. Each is a corner of the
     * plan, it is not said which: every way of taking the two taps furthest apart for two corners of the plan the
     * same distance apart is tried, and kept when every tap then lies within [tol] metres of a corner of its own.
     * Null when no way fits, or when two different ways do (then one more corner settles it).
     */
    fun fitCorners(taps: List<DoubleArray>, tol: Double = 0.25): Fit? {
        if (!usable || taps.size < 4) return null                      // three seldom settle it, and sometimes wrongly (tested on the owner's room)
        var ia = 0; var ib = 1; var far = -1.0
        for (i in taps.indices) for (j in i + 1 until taps.size) { val d = sqrt((taps[i][0] - taps[j][0]) * (taps[i][0] - taps[j][0]) + (taps[i][1] - taps[j][1]) * (taps[i][1] - taps[j][1])); if (d > far) { far = d; ia = i; ib = j } }
        if (far < 1.0) return null                                       // three corners in one nook say little about the turn
        val a = taps[ia]; val b = taps[ib]
        class Cand(val f: Fit, val err: Double)
        val cands = ArrayList<Cand>()
        for (i in corners.indices) for (j in corners.indices) {
            if (i == j) continue
            val ci = corners[i]; val cj = corners[j]
            if (abs(sqrt((ci[0] - cj[0]) * (ci[0] - cj[0]) + (ci[1] - cj[1]) * (ci[1] - cj[1])) - far) > tol) continue
            var yaw = wrap(atan2(cj[1] - ci[1], cj[0] - ci[0]) - atan2(b[1] - a[1], b[0] - a[0]))
            val r = Fit(yaw, 0.0, 0.0, 0, 0).apply(ci[0], ci[1]); var tx = a[0] - r[0]; var tz = a[1] - r[1]
            var err = 0.0; var ok = true; val which = IntArray(taps.size)
            for (pass in 0..1) {
                val f = Fit(yaw, tx, tz, 0, 0); err = 0.0; val used = BooleanArray(corners.size)
                for (k in taps.indices) {
                    var bd = tol * tol; var bi = -1
                    for (c in corners.indices) { if (used[c]) continue; val w = f.apply(corners[c][0], corners[c][1]); val d = (w[0] - taps[k][0]) * (w[0] - taps[k][0]) + (w[1] - taps[k][1]) * (w[1] - taps[k][1]); if (d < bd) { bd = d; bi = c } }
                    if (bi < 0) { ok = false; break }
                    used[bi] = true; which[k] = bi; err += bd
                }
                if (!ok || pass == 1) break
                // the turn and shift that bring every tap nearest to its own corner
                var mqx = 0.0; var mqz = 0.0; var mpx = 0.0; var mpz = 0.0; val n = taps.size
                for (k in taps.indices) { mqx += corners[which[k]][0] / n; mqz += corners[which[k]][1] / n; mpx += taps[k][0] / n; mpz += taps[k][1] / n }
                var sc = 0.0; var ss = 0.0
                for (k in taps.indices) { val ux = corners[which[k]][0] - mqx; val uz = corners[which[k]][1] - mqz; val vx = taps[k][0] - mpx; val vz = taps[k][1] - mpz; sc += ux * vx + uz * vz; ss += ux * vz - uz * vx }
                val th = atan2(ss, sc); val c = cos(th); val sn = sin(th)
                yaw = wrap(-th); tx = mpx - (c * mqx - sn * mqz); tz = mpz - (sn * mqx + c * mqz)
            }
            if (ok) cands.add(Cand(Fit(yaw, tx, tz, 0, 0), err))
        }
        val top = cands.minByOrNull { it.err } ?: return null
        // Another way that puts the plan somewhere else is no rival when its taps lie clearly worse: the right way leaves
        // each tap a few centimetres from its corner, a chance fit leaves them a hand's width off.
        for (c in cands) if ((abs(wrap(c.f.yaw - top.f.yaw)) > 0.17 || abs(c.f.tx - top.f.tx) > 0.45 || abs(c.f.tz - top.f.tz) > 0.45) && c.err < 4.0 * top.err + 0.006 * taps.size) return null
        return Fit(top.f.yaw, top.f.tx, top.f.tz, walls.size, 0)
    }

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
