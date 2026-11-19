package dev.ultraxr.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The walls of the room, from dense depth ([DenseStereo]) seen from above.
 *
 * Every measured place between knee and head height is dropped onto a floor plan of 5 cm squares and counted. One
 * depth picture is rough (several centimetres at two metres), but an upright surface puts its places on the same
 * line of the plan picture after picture, from wherever it is seen, so a wall becomes a bright straight line while
 * clutter stays a smudge. Lines are found the classic way (Hough 1962): every busy square votes for all the lines
 * through it, the line with most votes wins, its squares are fitted with a straight line, the longest unbroken run
 * along it is the wall, and those squares are taken out before the next line is looked for.
 *
 * In the app's world (metres, y up). Not thread safe by itself.
 */
class WallMap(private val cell: Double = 0.05, private val size: Int = 280) {
    private val count = FloatArray(size * size)
    private var ox = 0.0; private var oz = 0.0; private var placed = false
    var pictures = 0; private set

    fun clear() { count.fill(0f); placed = false; pictures = 0 }

    /** The plan is centred on where the wearer is when the first picture comes. */
    private fun index(x: Double, z: Double): Int {
        val gx = (size / 2 + (x - ox) / cell).toInt(); val gz = (size / 2 + (z - oz) / cell).toInt()
        return if (gx < 0 || gz < 0 || gx >= size || gz >= size) -1 else gz * size + gx
    }

    /**
     * Adds one depth picture taken with camera 0 at [cam]. [floorY] is the floor's height in the world; places from
     * 0.4 to 1.9 m above it and nearer than [maxRange] count, each less the further away it was measured.
     */
    fun add(ds: DenseStereo, cam: Pose, floorY: Double, maxRange: Double = 3.5, step: Int = 2) {
        if (!placed) { ox = cam.p[0]; oz = cam.p[2]; placed = true }
        val p = DoubleArray(3)
        var v = 3
        while (v < ds.h - 3) {
            var u = 3
            while (u < ds.w - 3) {
                if (ds.point(u, v, ds.disparity[v * ds.w + u], p)) {
                    val r2 = p[0] * p[0] + p[1] * p[1] + p[2] * p[2]
                    if (r2 < maxRange * maxRange && r2 > 0.64) {      // nearer than 0.8 m is the wearer's own arm and what it holds
                        val w = cam.apply(p); val up = w[1] - floorY
                        if (up > 0.4 && up < 1.9) { val i = index(w[0], w[2]); if (i >= 0) count[i] += (1.0 / (1.0 + r2)).toFloat() }
                    }
                }
                u += step
            }
            v += step
        }
        pictures++
    }

    /** One wall on the plan: from (x0, z0) to (x1, z1), how much speaks for it. */
    class Wall(val x0: Double, val z0: Double, val x1: Double, val z1: Double, val support: Double) {
        val length: Double get() = sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0))
    }

    /** The walls found now: at most [max], each at least [minLength] metres long. */
    fun walls(max: Int = 6, minLength: Double = 1.2): List<Wall> {
        if (pictures < 6) return emptyList()
        // the busy squares: well above what clutter and noise leave everywhere
        var sum = 0.0; var n = 0
        for (c in count) if (c > 0f) { sum += c; n++ }
        if (n < 200) return emptyList()
        val busy = (3.0 * sum / n).toFloat()
        val xs = ArrayList<Double>(); val zs = ArrayList<Double>(); val ws = ArrayList<Double>()
        for (gz in 0 until size) for (gx in 0 until size) {
            val c = count[gz * size + gx]; if (c < busy) continue
            xs.add((gx - size / 2 + 0.5) * cell); zs.add((gz - size / 2 + 0.5) * cell); ws.add(minOf(c / busy, 4f).toDouble())
        }
        val used = BooleanArray(xs.size)
        val span = size * cell; val nr = (2 * span / cell).toInt() + 1
        val out = ArrayList<Wall>()
        val cosT = DoubleArray(ANGLES) { cos(Math.PI * it / ANGLES) }; val sinT = DoubleArray(ANGLES) { sin(Math.PI * it / ANGLES) }
        val acc = FloatArray(ANGLES * nr)
        repeat(max) {
            acc.fill(0f)
            for (i in xs.indices) {
                if (used[i]) continue
                for (a in 0 until ANGLES) { val r = ((xs[i] * cosT[a] + zs[i] * sinT[a] + span) / cell).toInt(); if (r in 0 until nr) acc[a * nr + r] += ws[i].toFloat() }
            }
            var best = 0f; var bi = -1
            for (i in acc.indices) if (acc[i] > best) { best = acc[i]; bi = i }
            if (bi < 0 || best < MIN_VOTES) return out
            val a = bi / nr; val rho = (bi % nr + 0.5) * cell - span
            // the squares within 10 cm of that line, fitted with their own straight line
            val idx = ArrayList<Int>()
            for (i in xs.indices) if (!used[i] && abs(xs[i] * cosT[a] + zs[i] * sinT[a] - rho) < 0.10) idx.add(i)
            if (idx.size < 12) return out
            var sw = 0.0; var mx = 0.0; var mz = 0.0
            for (i in idx) { sw += ws[i]; mx += ws[i] * xs[i]; mz += ws[i] * zs[i] }
            mx /= sw; mz /= sw
            var sxx = 0.0; var sxz = 0.0; var szz = 0.0
            for (i in idx) { val dx = xs[i] - mx; val dz = zs[i] - mz; sxx += ws[i] * dx * dx; sxz += ws[i] * dx * dz; szz += ws[i] * dz * dz }
            val th = 0.5 * Math.atan2(2 * sxz, sxx - szz); val dx = cos(th); val dz = sin(th)       // along the wall
            // the longest stretch along it without a gap of more than 35 cm
            val ts = idx.map { (xs[it] - mx) * dx + (zs[it] - mz) * dz to it }.sortedBy { it.first }
            var runStart = 0; var bestStart = 0; var bestEnd = 0
            for (k in 1..ts.size) {
                if (k == ts.size || ts[k].first - ts[k - 1].first > 0.35) {
                    if (ts[k - 1].first - ts[runStart].first > ts[bestEnd].first - ts[bestStart].first) { bestStart = runStart; bestEnd = k - 1 }
                    runStart = k
                }
            }
            val t0 = ts[bestStart].first; val t1 = ts[bestEnd].first
            // Everything within 35 cm of that stretch is spoken for, wall or not: a wall measured this roughly is a
            // band on the plan, and its edges must not come back as walls of their own.
            for (i in xs.indices) {
                if (used[i]) continue
                val t = (xs[i] - mx) * dx + (zs[i] - mz) * dz; val off = abs(-(xs[i] - mx) * dz + (zs[i] - mz) * dx)
                if (off < 0.35 && t > t0 - 0.25 && t < t1 + 0.25) used[i] = true
            }
            for (i in idx) used[i] = true
            if (t1 - t0 < minLength) return@repeat
            var support = 0.0; for (k in bestStart..bestEnd) support += ws[ts[k].second]
            if (support / ((t1 - t0) / cell) < 0.8) return@repeat                                      // a dotted line is not a wall
            out.add(Wall(mx + dx * t0 + ox, mz + dz * t0 + oz, mx + dx * t1 + ox, mz + dz * t1 + oz, support))
        }
        return out
    }

    /** The plan as it stands (for pictures and tests): size x size numbers, row by row, and where its middle is. */
    fun plan(): FloatArray = count
    val planSize: Int get() = size
    fun planOrigin(): DoubleArray = doubleArrayOf(ox, oz)

    companion object {
        private const val ANGLES = 90
        private const val MIN_VOTES = 20f
    }
}
