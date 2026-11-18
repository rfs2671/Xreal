package dev.ultraxr.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A flat piece of the room: a rectangle with its centre, the unit vector along its long side ([ux], [uy], [uz]), the
 * unit vector along its other side, and half its size along each. A floor or table top lies level (its normal is up);
 * a wall stands (its second side is up). World: metres, +y up.
 */
class RoomSurface(val kind: Kind, val cx: Double, val cy: Double, val cz: Double,
                  val ux: Double, val uy: Double, val uz: Double, val vx: Double, val vy: Double, val vz: Double,
                  val halfU: Double, val halfV: Double, val support: Int) {
    enum class Kind { FLOOR, TABLE, WALL }
    val nx get() = uy * vz - uz * vy
    val ny get() = uz * vx - ux * vz
    val nz get() = ux * vy - uy * vx

    /**
     * Where the ray from ([ox], [oy], [oz]) along the unit direction (dx, dy, dz) meets this rectangle, grown by
     * [grow] metres on every side: the distance along the ray, or NaN. The floor has no edge: it is met anywhere.
     */
    fun hit(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, grow: Double = 0.0): Double {
        val den = dx * nx + dy * ny + dz * nz
        if (abs(den) < 1e-6) return Double.NaN
        val t = ((cx - ox) * nx + (cy - oy) * ny + (cz - oz) * nz) / den
        if (t <= 0) return Double.NaN
        if (kind == Kind.FLOOR) return t
        val px = ox + dx * t - cx; val py = oy + dy * t - cy; val pz = oz + dz * t - cz
        val a = px * ux + py * uy + pz * uz; val b = px * vx + py * vy + pz * vz
        return if (abs(a) <= halfU + grow && abs(b) <= halfV + grow) t else Double.NaN
    }
}

/** What [RoomModel.analyse] found. [floorY] is NaN until a floor has been found. */
class RoomFindings(val floorY: Double, val floorSupport: Int, val surfaces: List<RoomSurface>, val voxels: Int, val confirmed: Int)

/**
 * The room as the cameras have seen it so far: every point they placed (core/StereoPoints) is dropped into a grid of
 * 5 cm boxes, and a box counts once it was hit in two different pictures, which removes most wrong matches. From the
 * boxes that count, [analyse] finds
 *
 *   the floor     the lowest height at which boxes are spread over at least three quarters of a square metre,
 *                 and at least 1.05 m under the highest the head has been (so a counter is never the floor)
 *   table tops    other heights that stand out, where the boxes form a patch at least 30 cm wide
 *   walls         upright planes with boxes over at least 0.8 m along and 0.6 m up
 *
 * Only the last 20 seconds of pictures count, so what is reported is what is around the wearer now, placed by the
 * position estimator as it is now; a table top or wall is reported once it has been found three times running.
 *
 * Coordinates are the application's world (+y up). When that world is recentred, [transform] moves everything along.
 * No Android code: tested on the build machine with made-up rooms and, by hand, with recordings.
 */
class RoomModel(private val voxel: Double = 0.05, private val capacity: Int = 90_000) {
    private class Box(var hits: Int, var last: Int, var sx: Double, var sy: Double, var sz: Double) {
        val x get() = sx / hits; val y get() = sy / hits; val z get() = sz / hits
    }
    private val boxes = HashMap<Long, Box>()
    private var floorHeld = Double.NaN; private var floorPending = Double.NaN; private var floorPendingCount = 0
    var pointsAdded = 0L; private set
    /**
     * How far under the standing head the floor is expected (the wearer's height setting). A level about there is
     * believed the third time it is found. One far from it (a bed, a sofa or a low table seen before the floor has
     * been, or the real floor for a wearer who sits) has to be found [FAR_FLOOR_FINDS] times running, about half a minute.
     */
    @Volatile var expectedBelowHead = 1.6
    /** The highest the head has been: the floor is looked for well below that, wherever the head is now. */
    private var headTop = Double.NaN
    /** The newest picture's number: only what was seen in the last [WINDOW] pictures (about 20 s) counts. */
    private var latest = 0
    /** How far the floor was below the head at the last looks, to carry the floor over a loss of position. */
    private val below = DoubleArray(30); private var belowN = 0
    /** A table top or wall is shown once it has been found three times in about the same place, and dropped when it stops being found. */
    private class Kept(var s: RoomSurface, var seen: Int, var missed: Int)
    private val kept = ArrayList<Kept>()

    private fun key(x: Double, y: Double, z: Double): Long {
        val ix = floor(x / voxel).toLong() + OFF; val iy = floor(y / voxel).toLong() + OFF; val iz = floor(z / voxel).toLong() + OFF
        return (ix shl 42) or (iy shl 21) or iz
    }

    @Synchronized fun clear() { boxes.clear(); kept.clear(); belowN = 0; headTop = Double.NaN; floorHeld = Double.NaN; floorPending = Double.NaN; floorPendingCount = 0 }

    /** Drops every point but keeps the floor's height, which stays true while the wearer has not changed the world. */
    @Synchronized fun forgetSurfaces() { boxes.clear(); kept.clear(); floorPending = Double.NaN; floorPendingCount = 0 }

    /**
     * Position was lost and is back, with the head now at height [headY] in a world that may have shifted: nothing
     * found before can be trusted to be where it was. Every point and surface goes. The floor is put where it
     * usually was under the head (people do not change height much), until the cameras find it again.
     */
    @Synchronized fun regained(headY: Double) {
        boxes.clear(); kept.clear(); floorPending = Double.NaN; floorPendingCount = 0
        headTop = headY
        if (belowN >= 3) { val v = below.copyOf(minOf(belowN, below.size)); v.sort(); floorHeld = headY - v[v.size / 2] }
        else floorHeld = Double.NaN
    }

    /** Adds [n] points (x, y, z floats in the world) seen in picture number [frame]. */
    @Synchronized fun add(points: FloatArray, n: Int, frame: Int) {
        for (i in 0 until n) {
            val x = points[i * 3].toDouble(); val y = points[i * 3 + 1].toDouble(); val z = points[i * 3 + 2].toDouble()
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || abs(x) > 500 || abs(y) > 500 || abs(z) > 500) continue
            val k = key(x, y, z)
            val b = boxes[k]
            if (b == null) boxes[k] = Box(1, frame, x, y, z)
            else if (b.last != frame) { if (b.hits < 60) { b.hits++; b.sx += x; b.sy += y; b.sz += z }; b.last = frame }
            pointsAdded++
        }
        if (frame > latest) latest = frame
        // what has not been seen for a minute is gone: the estimator's small errors add up, and old points end up in the wrong place
        if (frame % 30 == 0) boxes.values.removeAll { latest - it.last > 180 }
        if (boxes.size > capacity) {
            // Full: boxes seen only once go first, the longest unseen of them first.
            val once = boxes.entries.filter { it.value.hits == 1 }.sortedBy { it.value.last }
            for (e in once.take(boxes.size - capacity * 3 / 4)) boxes.remove(e.key)
            if (boxes.size > capacity) { val old = boxes.entries.sortedBy { it.value.last }; for (e in old.take(boxes.size - capacity * 3 / 4)) boxes.remove(e.key) }
        }
    }

    /** The world moved (a recentre): every point p becomes [delta].apply(p). */
    @Synchronized fun transform(delta: Pose) {
        val old = ArrayList(boxes.values); boxes.clear()
        for (b in old) {
            val p = delta.apply(doubleArrayOf(b.x, b.y, b.z))
            val k = key(p[0], p[1], p[2]); val e = boxes[k]
            if (e == null) boxes[k] = Box(b.hits, b.last, p[0] * b.hits, p[1] * b.hits, p[2] * b.hits)
            else if (b.hits > e.hits) { e.hits = b.hits; e.last = maxOf(e.last, b.last); e.sx = p[0] * b.hits; e.sy = p[1] * b.hits; e.sz = p[2] * b.hits }
        }
        // a recentre turns about the vertical and moves: heights shift by the same amount everywhere
        if (!floorHeld.isNaN()) floorHeld = delta.apply(doubleArrayOf(0.0, floorHeld, 0.0))[1]
        if (!headTop.isNaN()) headTop = delta.apply(doubleArrayOf(0.0, headTop, 0.0))[1]
        for (k in kept) {
            val o = k.s; val cc = delta.apply(doubleArrayOf(o.cx, o.cy, o.cz)); val u = Q.rotate(delta.q, doubleArrayOf(o.ux, o.uy, o.uz)); val v = Q.rotate(delta.q, doubleArrayOf(o.vx, o.vy, o.vz))
            k.s = RoomSurface(o.kind, cc[0], cc[1], cc[2], u[0], u[1], u[2], v[0], v[1], v[2], o.halfU, o.halfV, o.support)
        }
        floorPending = Double.NaN; floorPendingCount = 0
    }

    /** Confirmed points as x, y, z floats, at most [max] of them (for drawing and for the record). */
    @Synchronized fun points(max: Int = 20_000): FloatArray {
        val c = boxes.values.filter { it.hits >= MIN_HITS }
        val step = maxOf(1, c.size / max); val out = FloatArray((c.size / step) * 3); var n = 0
        var i = 0
        while (i < c.size && n * 3 + 2 < out.size) { val b = c[i]; out[n * 3] = b.x.toFloat(); out[n * 3 + 1] = b.y.toFloat(); out[n * 3 + 2] = b.z.toFloat(); n++; i += step }
        return out
    }

    /** Finds the floor, table tops and walls. [headY] is how high the head is now (the floor is looked for below it). */
    @Synchronized fun analyse(headY: Double, headX: Double = Double.NaN, headZ: Double = Double.NaN): RoomFindings {
        if (headTop.isNaN() || headY > headTop) headTop = headY
        // The floor is looked for in everything kept (the last minute): its height does not suffer from the estimator's
        // sideways errors. Table tops and walls only in what the last 20 seconds showed.
        val c = boxes.values.filter { it.hits >= MIN_HITS }
        fun recent(b: Box) = latest - b.last <= WINDOW
        if (c.size < 30) return RoomFindings(floorHeld, 0, shown(emptyList()), boxes.size, c.size)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (b in c) { val y = b.y; if (y < lo) lo = y; if (y > hi) hi = y }
        val n = ((hi - lo) / BIN).toInt() + 3
        val count = IntArray(n)
        for (b in c) count[((b.y - lo) / BIN).toInt() + 1]++
        val slab = IntArray(n) { i -> count[i] + (if (i > 0) count[i - 1] else 0) + (if (i < n - 1) count[i + 1] else 0) }
        fun height(i: Int) = lo + (i - 0.5) * BIN
        fun inSlab(i: Int) = c.filter { val k = ((it.y - lo) / BIN).toInt() + 1; k >= i - 1 && k <= i + 1 }
        fun spread(list: List<Box>): Int { val s = HashSet<Long>(); for (b in list) s.add((floor(b.x / 0.25).toLong() shl 32) xor (floor(b.z / 0.25).toLong() and 0xFFFFFFFFL)); return s.size }

        // ---- the floor: from the bottom up, the first height with enough boxes spread over enough ground
        var found = Double.NaN; var support = 0; var floorBin = -1
        for (i in 1 until n - 1) {
            if (height(i) > headTop - MIN_BELOW_HEAD) break
            if (slab[i] < FLOOR_MIN_BOXES) continue
            if (spread(inSlab(i)) < FLOOR_MIN_CELLS) continue
            // That is the lower edge of the floor's scatter. Its middle is where the count peaks, a few bins up.
            var p = i
            while (p + 1 < n - 1 && p - i < 5 && slab[p + 1] > slab[p]) p++
            val s = inSlab(p); var sum = 0.0; var w = 0
            for (b in s) { sum += b.y * b.hits; w += b.hits }
            found = sum / w; support = s.size; floorBin = p
            break
        }
        // A floor is not believed at once and does not jump about: small changes are followed slowly, a different
        // height has to be found three times running before it replaces the one held.
        if (!found.isNaN()) {
            val need = if (headTop.isNaN() || abs((headTop - found) - expectedBelowHead) < FLOOR_NEAR_EXPECTED_M) 3 else FAR_FLOOR_FINDS
            if (floorHeld.isNaN() && (floorPending.isNaN() || abs(found - floorPending) < 0.06)) {
                floorPending = found
                if (++floorPendingCount >= need) { floorHeld = found; floorPending = Double.NaN; floorPendingCount = 0 }
            } else if (!floorHeld.isNaN() && abs(found - floorHeld) < 0.06) {
                floorHeld += 0.10 * (found - floorHeld); floorPending = Double.NaN; floorPendingCount = 0
            } else if (!floorPending.isNaN() && abs(found - floorPending) < 0.06) {
                if (++floorPendingCount >= need) { floorHeld = found; floorPending = Double.NaN; floorPendingCount = 0 }
            } else { floorPending = found; floorPendingCount = 1 }
        }
        val surfaces = ArrayList<RoomSurface>()
        val fy = floorHeld
        if (!fy.isNaN() && !found.isNaN() && abs(found - fy) < 0.06) { below[belowN % below.size] = headY - fy; belowN++ }
        val found2 = ArrayList<RoomSurface>()
        val used = HashSet<Box>()
        if (!fy.isNaN() && floorBin >= 0) {
            val s = inSlab(floorBin); used.addAll(s)
            rectangle(s, RoomSurface.Kind.FLOOR, fy)?.let { surfaces.add(it) }
        }

        // ---- table tops: heights above the floor that stand out from the heights around them
        val base = if (fy.isNaN()) headY - 1.5 else fy
        var i = 2
        val tops = ArrayList<Int>()
        while (i < n - 2) {
            val y = height(i)
            if (y > base + 0.30 && y < base + 1.40 && slab[i] >= TABLE_MIN_BOXES && slab[i] >= slab[i - 1] && slab[i] > slab[i + 1]) {
                var around = 0; var m = 0
                for (k in i - 10..i + 10) if (k in 0 until n && abs(k - i) > 2) { around += slab[k]; m++ }
                if (m > 0 && slab[i] > 1.6 * around / m) tops.add(i)
            }
            i++
        }
        for (t in tops.sortedByDescending { slab[it] }.take(4)) {
            val patch = largestPatch(inSlab(t).filter { it !in used && recent(it) }) ?: continue
            var sum = 0.0; for (b in patch) sum += b.y
            val r = rectangle(patch, RoomSurface.Kind.TABLE, sum / patch.size) ?: continue
            if (r.halfV < 0.18 || r.halfU * r.halfV * 4 < 0.20) continue        // a line of boxes is an edge, and a small thing is a thing, not a top
            // a top is flat: its boxes lie within a couple of centimetres of one height
            var dev = 0.0; for (b in patch) dev += (b.y - r.cy) * (b.y - r.cy)
            if (sqrt(dev / patch.size) > 0.022) continue
            if (r.halfU > 1.5 || r.halfV > 1.0) continue                         // bigger than any table: a level slice through a cluttered room
            if (!near(r, headX, headZ)) continue
            // a top is covered with boxes, not just framed by a few: half of its 20 cm squares must hold one
            val sq = HashSet<Long>(); for (b in patch) sq.add((floor(b.x / 0.2).toLong() shl 32) xor (floor(b.z / 0.2).toLong() and 0xFFFFFFFFL))
            if (sq.size < 0.6 * (r.halfU * 2 * r.halfV * 2) / 0.04) continue
            found2.add(r); used.addAll(patch)
        }

        // ---- walls: upright planes among what is left between knee and head height
        val rest = c.filter { it !in used && recent(it) && it.y > base + 0.25 && it.y < base + 2.3 }.toMutableList()
        repeat(4) {
            val w = wall(rest, headX, headZ) ?: return@repeat
            if (near(w.first, headX, headZ)) found2.add(w.first)
            // everything near that plane is spoken for, so the same wall is not found twice a few degrees apart
            val f = w.first; rest.removeAll { b -> abs((b.x - f.cx) * f.nx + (b.z - f.cz) * f.nz) < 0.12 && abs((b.x - f.cx) * f.ux + (b.z - f.cz) * f.uz) < f.halfU + 0.2 }
        }
        surfaces.addAll(shown(found2))
        return RoomFindings(fy, support, surfaces, boxes.size, c.size)
    }

    private fun near(s: RoomSurface, headX: Double, headZ: Double) = headX.isNaN() || sqrt((s.cx - headX) * (s.cx - headX) + (s.cz - headZ) * (s.cz - headZ)) < MAX_RANGE + s.halfU

    /**
     * What is shown of the table tops and walls just [found]: each has to turn up three times in about the same
     * place first, is then kept steady (its place and size follow new finds half way), and goes when it has not
     * been found six times running (six seconds).
     */
    private fun shown(found: List<RoomSurface>): List<RoomSurface> {
        val free = found.toMutableList()
        val it = kept.iterator()
        while (it.hasNext()) {
            val k = it.next(); val o = k.s
            val m = free.firstOrNull { f -> f.kind == o.kind && abs(f.nx * o.nx + f.ny * o.ny + f.nz * o.nz) > 0.94 &&
                abs((f.cx - o.cx) * o.nx + (f.cy - o.cy) * o.ny + (f.cz - o.cz) * o.nz) < 0.08 &&
                sqrt((f.cx - o.cx) * (f.cx - o.cx) + (f.cy - o.cy) * (f.cy - o.cy) + (f.cz - o.cz) * (f.cz - o.cz)) < 0.2 + maxOf(o.halfU, f.halfU) * 0.6 }
            if (m == null) { if (++k.missed > 6) it.remove(); continue }
            free.remove(m); k.seen++; k.missed = 0
            k.s = RoomSurface(o.kind, (o.cx + m.cx) / 2, (o.cy + m.cy) / 2, (o.cz + m.cz) / 2, m.ux, m.uy, m.uz, m.vx, m.vy, m.vz, (o.halfU + m.halfU) / 2, (o.halfV + m.halfV) / 2, m.support)
        }
        for (f in free) kept.add(Kept(f, 1, 0))
        // a wall is believed the third time, a table top the fourth: things on furniture make up tops more easily
        return kept.filter { it.seen >= (if (it.s.kind == RoomSurface.Kind.TABLE) 4 else 3) }.map { it.s }
    }

    /** The biggest group of boxes that touch each other on a 20 cm grid seen from above. */
    private fun largestPatch(list: List<Box>): List<Box>? {
        if (list.size < TABLE_MIN_BOXES) return null
        val g = 0.20
        val cells = HashMap<Long, ArrayList<Box>>()
        fun ck(ix: Long, iz: Long) = (ix shl 32) xor (iz and 0xFFFFFFFFL)
        for (b in list) cells.getOrPut(ck(floor(b.x / g).toLong(), floor(b.z / g).toLong())) { ArrayList() }.add(b)
        val seen = HashSet<Long>(); var best: ArrayList<Box>? = null
        for (start in cells.keys) {
            if (!seen.add(start)) continue
            val group = ArrayList<Box>(); val queue = ArrayDeque<Long>(); queue.add(start)
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst(); group.addAll(cells[k]!!)
                val ix = k shr 32; val iz = (k and 0xFFFFFFFFL).toInt().toLong()
                for (dx in -1L..1L) for (dz in -1L..1L) { val nk = ck(ix + dx, iz + dz); if (cells.containsKey(nk) && seen.add(nk)) queue.add(nk) }
            }
            if (best == null || group.size > best.size) best = group
        }
        return best?.takeIf { it.size >= TABLE_MIN_BOXES }
    }

    /** The level rectangle around [list] at height [y]: its long side along the direction the boxes are spread most. */
    private fun rectangle(list: List<Box>, kind: RoomSurface.Kind, y: Double): RoomSurface? {
        if (list.size < 6) return null
        var mx = 0.0; var mz = 0.0
        for (b in list) { mx += b.x; mz += b.z }
        mx /= list.size; mz /= list.size
        var a = 0.0; var bb = 0.0; var d = 0.0
        for (b in list) { val x = b.x - mx; val z = b.z - mz; a += x * x; bb += x * z; d += z * z }
        val ang = 0.5 * Math.atan2(2 * bb, a - d)
        val ux = cos(ang); val uz = sin(ang)
        val us = DoubleArray(list.size); val vs = DoubleArray(list.size)
        for ((k, b) in list.withIndex()) { val x = b.x - mx; val z = b.z - mz; us[k] = x * ux + z * uz; vs[k] = -x * uz + z * ux }
        us.sort(); vs.sort()
        fun at(s: DoubleArray, f: Double) = s[((s.size - 1) * f).toInt()]
        val u0 = at(us, 0.03); val u1 = at(us, 0.97); val v0 = at(vs, 0.03); val v1 = at(vs, 0.97)
        val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
        // u along the spread, v = up x u would be level too: v = (-uz, 0, ux), so that u x v points up
        return RoomSurface(kind, mx + cu * ux - cv * uz, y, mz + cu * uz + cv * ux, uz, 0.0, ux, ux, 0.0, -uz, 0.0, 0.0, 0).let {
            // (written out below with the right axes; the line above only fixes the centre)
            RoomSurface(kind, it.cx, y, it.cz, ux, 0.0, uz, -uz, 0.0, ux, (u1 - u0) / 2 + voxel / 2, (v1 - v0) / 2 + voxel / 2, list.size).flipUp()
        }
    }
    /** Level surfaces are stored with their normal pointing up. */
    private fun RoomSurface.flipUp(): RoomSurface = if (ny >= 0) this else RoomSurface(kind, cx, cy, cz, ux, uy, uz, -vx, -vy, -vz, halfU, halfV, support)

    /** The strongest upright plane in [list], with the boxes on it; null when nothing there looks like a wall. */
    private fun wall(list: List<Box>, headX: Double, headZ: Double): Pair<RoomSurface, List<Box>>? {
        if (list.size < WALL_MIN_BOXES) return null
        val angles = 45; val rhoBin = 0.05
        var minR = Double.MAX_VALUE; var maxR = -Double.MAX_VALUE
        for (b in list) { val r = sqrt(b.x * b.x + b.z * b.z); if (r > maxR) maxR = r }
        minR = -maxR
        val nr = ((maxR - minR) / rhoBin).toInt() + 2
        val votes = IntArray(angles * nr)
        val cs = DoubleArray(angles) { cos(Math.PI * it / angles) }; val sn = DoubleArray(angles) { sin(Math.PI * it / angles) }
        for (b in list) for (t in 0 until angles) votes[t * nr + ((b.x * cs[t] + b.z * sn[t] - minR) / rhoBin).toInt()]++
        // Peaks from the strongest down; the first that is tall and long enough is the wall.
        val order = votes.indices.filter { votes[it] >= WALL_MIN_BOXES / 2 }.sortedByDescending { votes[it] }.take(12)
        for (o in order) {
            val t = o / nr; val rho = minR + (o % nr + 0.5) * rhoBin
            // refine: the boxes within 6 cm, their own best line
            var on = list.filter { abs(it.x * cs[t] + it.z * sn[t] - rho) < 0.06 }
            if (on.size < WALL_MIN_BOXES) continue
            var mx = 0.0; var mz = 0.0; for (b in on) { mx += b.x; mz += b.z }; mx /= on.size; mz /= on.size
            var a = 0.0; var bb = 0.0; var d = 0.0
            for (b in on) { val x = b.x - mx; val z = b.z - mz; a += x * x; bb += x * z; d += z * z }
            val ang = 0.5 * Math.atan2(2 * bb, a - d); val ux = cos(ang); val uz = sin(ang)      // along the wall
            val nx = -uz; val nz = ux
            on = list.filter { abs((it.x - mx) * nx + (it.z - mz) * nz) < 0.05 }
            if (on.size < WALL_MIN_BOXES) continue
            // A wall is a sheet: many boxes on it, few just in front of and behind it. A slice through a shelf full
            // of things has as many boxes beside it as on it, and is not a wall.
            var beside = 0
            for (b in list) { val d = abs((b.x - mx) * nx + (b.z - mz) * nz); if (d >= 0.05 && d < 0.20) beside++ }
            if (on.size < 1.5 * beside) continue
            // and it does not pass through the wearer
            if (!headX.isNaN() && abs((headX - mx) * nx + (headZ - mz) * nz) < 0.35) continue
            val along = DoubleArray(on.size) { (on[it].x - mx) * ux + (on[it].z - mz) * uz }.also { it.sort() }
            val up = DoubleArray(on.size) { on[it].y }.also { it.sort() }
            fun at(s: DoubleArray, f: Double) = s[((s.size - 1) * f).toInt()]
            val a0 = at(along, 0.03); val a1 = at(along, 0.97); val y0 = at(up, 0.03); val y1 = at(up, 0.97)
            if (a1 - a0 < 1.2 || y1 - y0 < 1.0) continue
            // filled, not a line of boxes along one shelf edge: boxes in enough different 20 cm squares of the wall
            val squares = HashSet<Long>()
            for (b in on) squares.add((floor(((b.x - mx) * ux + (b.z - mz) * uz) / 0.2).toLong() shl 32) xor (floor(b.y / 0.2).toLong() and 0xFFFFFFFFL))
            if (squares.size < 0.33 * ((a1 - a0) * (y1 - y0)) / 0.04) continue
            val ca = (a0 + a1) / 2
            return RoomSurface(RoomSurface.Kind.WALL, mx + ca * ux, (y0 + y1) / 2, mz + ca * uz, ux, 0.0, uz, 0.0, 1.0, 0.0, (a1 - a0) / 2, (y1 - y0) / 2, on.size) to on
        }
        return null
    }

    companion object {
        private const val OFF = 1L shl 20
        const val MIN_HITS = 2
        private const val BIN = 0.025
        const val FLOOR_NEAR_EXPECTED_M = 0.35
        const val FAR_FLOOR_FINDS = 40
        /** Nobody's eyes are closer to the floor than this, sitting or standing; a counter or a desk is. */
        const val MIN_BELOW_HEAD = 1.05
        private const val FLOOR_MIN_BOXES = 36
        private const val FLOOR_MIN_CELLS = 12
        private const val TABLE_MIN_BOXES = 32
        private const val WALL_MIN_BOXES = 70
        /** Pictures that count: about 20 s at three a second. */
        const val WINDOW = 60
        /** Table tops and walls further from the wearer than this are not reported: too far to place well. */
        private const val MAX_RANGE = 3.5
    }
}
