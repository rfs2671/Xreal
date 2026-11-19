package dev.ultraxr.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds the room again after position was lost.
 *
 * While position is lost the head is held where it was, and when it comes back the world is joined on at that
 * place: if the wearer walked meanwhile, everything a game has put in the room is off by those steps. The walls do
 * not walk. So the walls as they stood before the loss are remembered, and once two walls that are not parallel
 * have been found again, the one shift of the floor plan that lays the new ones onto the remembered ones is how far
 * the world slipped. Heading is not touched: it comes from the gyroscope, which does not lose its way in seconds.
 *
 * All on the floor plan of the app's world (x and z, metres). Not thread safe by itself.
 */
class RoomRecall {
    /** A wall on the floor plan, from (x0, z0) to (x1, z1). */
    class Line(val x0: Double, val z0: Double, val x1: Double, val z1: Double) {
        val length = sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0))
        val ux = (x1 - x0) / length; val uz = (z1 - z0) / length          // along it
        val mx = (x0 + x1) / 2; val mz = (z0 + z1) / 2
        fun shifted(dx: Double, dz: Double) = Line(x0 + dx, z0 + dz, x1 + dx, z1 + dz)
    }

    private var remembered: List<Line> = emptyList()
    private var waitingSinceNs = 0L; private var looking = false
    private var maxShift = 0.0
    /** True from a loss of position until the room is found again or given up. */
    val waiting: Boolean get() = looking
    val walls: Int get() = remembered.size

    fun clear() { remembered = emptyList(); looking = false; maxShift = 0.0 }

    /** The walls as they stand now, with position good: remembered if they could place the room (two not parallel). */
    fun see(now: List<Line>) { if (!waiting && placeable(now)) remembered = now }

    /**
     * Position is back after [awaySeconds] without it. Returns whether there is a room to look for. A second loss
     * before the room is found adds to how far the wearer may have gone.
     */
    fun lost(nowNs: Long, awaySeconds: Double): Boolean {
        if (!placeable(remembered)) { clear(); return false }
        maxShift = minOf(MAX_SHIFT_M, maxShift + WALKING_M_PER_S * awaySeconds + 0.3)
        if (!looking) waitingSinceNs = nowNs
        looking = true
        return true
    }

    /** The wearer recentred: the remembered walls move with the world. */
    fun moved(delta: Pose) {
        remembered = remembered.map { val a = delta.apply(doubleArrayOf(it.x0, 0.0, it.z0)); val b = delta.apply(doubleArrayOf(it.x1, 0.0, it.z1)); Line(a[0], a[2], b[0], b[2]) }
    }

    /**
     * While waiting: the walls found since position came back. Returns the shift (dx, dz) that puts the world back
     * where it was, once it is known; null while it is not. After [GIVE_UP_NS] the old room is given up (this may be
     * another room) and the new walls are the ones remembered from then on.
     */
    fun find(nowNs: Long, now: List<Line>): DoubleArray? {
        if (!waiting) return null
        val d = solve(remembered, now, maxShift)
        if (d != null) { looking = false; maxShift = 0.0; return d }
        if (nowNs - waitingSinceNs > GIVE_UP_NS) { looking = false; maxShift = 0.0; remembered = emptyList(); see(now) }
        return null
    }

    companion object {
        const val WALKING_M_PER_S = 1.5
        const val MAX_SHIFT_M = 6.0
        const val GIVE_UP_NS = 60_000_000_000L
        internal const val PARALLEL_SIN = 0.17          // 10 degrees: the same wall seen again
        internal const val CROSSING_SIN = 0.5           // 30 degrees: two walls that fix a place between them
        private const val ON_WALL_M = 0.15
        private const val OVERLAP_M = 0.5

        internal fun cross(a: Line, b: Line) = abs(a.ux * b.uz - a.uz * b.ux)
        private fun placeable(w: List<Line>): Boolean { for (i in w.indices) for (j in i + 1 until w.size) if (cross(w[i], w[j]) > CROSSING_SIN) return true; return false }

        /** How far [n] has to go across [o] (along o's normal) to lie on o's line. */
        internal fun across(o: Line, n: Line) = -(o.mx - n.mx) * o.uz + (o.mz - n.mz) * o.ux

        /** Whether [n] lies on [o]: same direction, on its line, and sharing a stretch of it. */
        internal fun on(o: Line, n: Line): Boolean {
            if (cross(o, n) > PARALLEL_SIN || abs(across(o, n)) > ON_WALL_M) return false
            val t = (n.mx - o.mx) * o.ux + (n.mz - o.mz) * o.uz
            return o.length / 2 + n.length / 2 - abs(t) >= minOf(OVERLAP_M, 0.5 * minOf(o.length, n.length))
        }

        /**
         * The shift that lays the walls [now] onto the walls [old], no longer than [maxShift]; null when two walls
         * that cross do not agree on one, or when two different shifts do equally well (a room looks the same from
         * two of its corners).
         */
        fun solve(old: List<Line>, now: List<Line>, maxShift: Double): DoubleArray? {
            var best: DoubleArray? = null; var bestScore = 0; var bestLen = 0.0; var tied = false
            for (i1 in now.indices) for (j1 in old.indices) {
                if (cross(old[j1], now[i1]) > PARALLEL_SIN) continue
                for (i2 in i1 + 1 until now.size) for (j2 in old.indices) {
                    if (j2 == j1 || cross(old[j2], now[i2]) > PARALLEL_SIN || cross(old[j1], old[j2]) < CROSSING_SIN) continue
                    // normals of the two old walls, and how far across each the new wall has to go
                    val ax = -old[j1].uz; val az = old[j1].ux; val bx = -old[j2].uz; val bz = old[j2].ux
                    val ca = across(old[j1], now[i1]); val cb = across(old[j2], now[i2])
                    val det = ax * bz - az * bx
                    var dx = (ca * bz - az * cb) / det; var dz = (ax * cb - ca * bx) / det
                    if (dx * dx + dz * dz > maxShift * maxShift) continue
                    // every new wall that lands on an old one counts, and all of them together settle the shift
                    var score = 0; var sxx = 0.0; var sxz = 0.0; var szz = 0.0; var rx = 0.0; var rz = 0.0
                    for (n in now) {
                        val m = n.shifted(dx, dz)
                        val o = old.firstOrNull { on(it, m) } ?: continue
                        score++
                        val nx = -o.uz; val nz = o.ux; val c = across(o, n)
                        sxx += nx * nx; sxz += nx * nz; szz += nz * nz; rx += nx * c; rz += nz * c
                    }
                    if (score < 2) continue
                    val d2 = sxx * szz - sxz * sxz
                    if (abs(d2) > 0.05) { dx = (rx * szz - sxz * rz) / d2; dz = (sxx * rz - rx * sxz) / d2 }
                    val len = sqrt(dx * dx + dz * dz)
                    val b = best
                    val same = b != null && abs(b[0] - dx) < 0.3 && abs(b[1] - dz) < 0.3
                    if (b == null || score > bestScore) { best = doubleArrayOf(dx, dz); bestScore = score; bestLen = len; tied = false }
                    else if (score == bestScore && !same) {
                        // the nearer of two is believed when it is clearly nearer: people stop when they cannot see
                        if (len < bestLen - 1.0) { best = doubleArrayOf(dx, dz); bestLen = len; tied = false }
                        else if (len < bestLen + 1.0) tied = true
                    }
                }
            }
            return if (tied) null else best
        }
    }
}
