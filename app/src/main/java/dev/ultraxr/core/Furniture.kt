// Ultra XR: things standing in the room, as boxes. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A thing standing on the floor: an upright box turned by [yaw] about the vertical, with half sizes along its own x, up, and its own z. */
class FurnitureBox(val cx: Double, val cy: Double, val cz: Double, val yaw: Double, val hx: Double, val hy: Double, val hz: Double,
                   val label: String, val cells: Int, val confidence: Double) {
    val top: Double get() = cy + hy
    /** Whether the point is inside the box grown by [grow] metres: what a game may not walk or put things through. */
    fun holds(x: Double, y: Double, z: Double, grow: Double = 0.0): Boolean {
        if (abs(y - cy) > hy + grow) return false
        val dx = x - cx; val dz = z - cz; val c = cos(yaw); val s = sin(yaw)
        return abs(c * dx - s * dz) <= hx + grow && abs(s * dx + c * dz) <= hz + grow
    }
}

/**
 * Furniture from the room's surface cells ([RoomMesh.cells]): whatever stands between the floor and head height and
 * is not a wall.
 *
 * The cells (5 cm) between 12 cm and 1.9 m above the floor that are not within 15 cm of a known wall are dropped
 * onto a floor plan of 10 cm squares; a square counts when cells stand in it over at least 20 cm of height. Squares
 * that touch make one thing. A thing is kept when it covers at least 0.12 square metres, is at least 30 cm high and
 * is made of at least 40 cells; it is given the upright box that fits its cells most tightly (tried every five
 * degrees) from the floor to its highest cell (the 95th percentile, so that one stray cell does not raise it).
 *
 * What it is called is a guess from its size alone: "low" under 55 cm (a seat, a low table, a bed), "table" to
 * 1.05 m (a table, desk or counter), "tall" above (a cupboard, shelves, a fridge). A person standing still for the
 * whole of a scan is a "tall" thing. How far it is to be believed grows with how many cells it has.
 */
object Furniture {
    const val SQUARE = 0.10
    const val MIN_AREA = 0.12
    const val MIN_HEIGHT = 0.30
    const val MIN_CELLS = 40
    const val WALL_CLEAR = 0.15
    /**
     * Which cells of the mesh to give [detect] ([RoomMesh.cells]): seen about four times as often as a cell needs to
     * count at all, and close to where the surface runs. With every counted cell the whole of a real room hangs
     * together as one thing (measured on a recording, 20 November 2026): the depth pictures are that rough.
     */
    const val CELL_WEIGHT = 4f
    const val CELL_BAND = 0.25f

    /** [cells]: x, y, z of each surface cell's centre, one after another. [walls] in the same world. */
    fun detect(cells: DoubleArray, floorY: Double, walls: List<RoomSurface>): List<FurnitureBox> {
        if (floorY.isNaN()) return emptyList()
        // the plan: for every square the lowest and highest cell and how many
        class Sq { var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE; var n = 0; val ys = ArrayList<Float>(4); val xz = ArrayList<Float>(8); var thing = -1 }
        val plan = HashMap<Long, Sq>()
        fun key(ix: Int, iz: Int) = (ix.toLong() shl 32) xor (iz.toLong() and 0xffffffffL)
        val upright = walls.filter { it.kind == RoomSurface.Kind.WALL }
        var i = 0
        while (i + 2 < cells.size) {
            val x = cells[i]; val y = cells[i + 1]; val z = cells[i + 2]; i += 3
            val up = y - floorY
            if (up < 0.12 || up > 1.9) continue
            var onWall = false
            for (w in upright) {
                val n = sqrt(w.nx * w.nx + w.ny * w.ny + w.nz * w.nz); if (n < 1e-9) continue
                val across = abs((x - w.cx) * w.nx + (y - w.cy) * w.ny + (z - w.cz) * w.nz) / n
                val along = abs((x - w.cx) * w.ux + (y - w.cy) * w.uy + (z - w.cz) * w.uz)
                if (across < WALL_CLEAR && along < w.halfU + 1.0) { onWall = true; break }      // a wall goes on past where it was seen to end
            }
            if (onWall) continue
            val s = plan.getOrPut(key(floor(x / SQUARE).toInt(), floor(z / SQUARE).toInt())) { Sq() }
            s.lo = min(s.lo, y); s.hi = max(s.hi, y); s.n++; s.ys.add(y.toFloat()); s.xz.add(x.toFloat()); s.xz.add(z.toFloat())
        }
        val solid = plan.filter { it.value.hi - it.value.lo >= 0.20 || it.value.n >= 6 }
        // squares that touch (also corner to corner) are one thing
        val out = ArrayList<FurnitureBox>(); var thing = 0
        for ((k0, s0) in solid) {
            if (s0.thing >= 0) continue
            val members = ArrayList<Long>(); val stack = ArrayList<Long>(); stack.add(k0); s0.thing = thing
            while (stack.isNotEmpty()) {
                val k = stack.removeAt(stack.size - 1); members.add(k)
                val ix = (k shr 32).toInt(); val iz = k.toInt()
                for (dx in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dz == 0) continue
                    val nk = key(ix + dx, iz + dz); val n = solid[nk] ?: continue
                    if (n.thing < 0) { n.thing = thing; stack.add(nk) }
                }
            }
            thing++
            val count = members.sumOf { solid[it]!!.n }
            if (members.size * SQUARE * SQUARE < MIN_AREA || count < MIN_CELLS) continue
            val ys = FloatArray(count); var j = 0; for (m in members) for (y in solid[m]!!.ys) ys[j++] = y
            ys.sort(); val topY = ys[(count * 0.95).toInt().coerceAtMost(count - 1)].toDouble()
            if (topY - floorY < MIN_HEIGHT) continue
            // the tightest upright box round the cells themselves, turned in steps of five degrees (two in a hundred
            // cells may lie outside it on each side: strays)
            val px = FloatArray(count); val pz = FloatArray(count); j = 0
            for (m in members) { val q = solid[m]!!.xz; var a = 0; while (a < q.size) { px[j] = q[a]; pz[j] = q[a + 1]; j++; a += 2 } }
            val us = FloatArray(count); val vs = FloatArray(count)
            var best = Double.MAX_VALUE; var bYaw = 0.0; var bu0 = 0.0; var bu1 = 0.0; var bv0 = 0.0; var bv1 = 0.0
            for (step in 0 until 18) {
                val a = Math.toRadians(step * 5.0); val c = cos(a); val s = sin(a)
                for (k in 0 until count) { us[k] = (c * px[k] - s * pz[k]).toFloat(); vs[k] = (s * px[k] + c * pz[k]).toFloat() }
                us.sort(); vs.sort(); val cut = (count * 0.02).toInt()
                val u0 = us[cut].toDouble(); val u1 = us[count - 1 - cut].toDouble(); val v0 = vs[cut].toDouble(); val v1 = vs[count - 1 - cut].toDouble()
                val area = (u1 - u0) * (v1 - v0)
                if (area < best - 1e-9) { best = area; bYaw = a; bu0 = u0; bu1 = u1; bv0 = v0; bv1 = v1 }
            }
            val c = cos(bYaw); val s = sin(bYaw); val mu = (bu0 + bu1) / 2; val mv = (bv0 + bv1) / 2
            val height = topY - floorY
            // tall and thin is a piece of a wall nobody has found as a wall yet, or a door: not furniture
            if (height > 1.2 && min(bu1 - bu0, bv1 - bv0) < 0.30) continue
            val label = if (height < 0.55) "low" else if (height < 1.05) "table" else "tall"
            out.add(FurnitureBox(c * mu + s * mv, floorY + height / 2, -s * mu + c * mv, bYaw, (bu1 - bu0) / 2 + 0.025, height / 2, (bv1 - bv0) / 2 + 0.025, label, count,
                (count / 400.0).coerceIn(0.2, 1.0)))
        }
        return out.sortedByDescending { it.cells }
    }

    /** Whether [a] and [b] are the same thing seen twice: their centres within the larger one's footprint and their tops within 25 cm. */
    fun same(a: FurnitureBox, b: FurnitureBox): Boolean {
        val big = if (a.hx * a.hz >= b.hx * b.hz) a else b; val other = if (big === a) b else a
        return big.holds(other.cx, big.cy, other.cz, 0.15) && abs(a.top - b.top) < 0.25
    }
}
