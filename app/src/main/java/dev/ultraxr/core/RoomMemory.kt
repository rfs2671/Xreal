// Ultra XR: the room's surfaces, kept with the saved map. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

import java.util.Locale

/**
 * The floor, walls and table tops the cameras found, kept in the saved map's coordinates (core/MapAlign) so that a
 * later session has them the moment the room is recognised, in place of finding each again by looking at it.
 *
 * What is kept is what [RoomTidy] makes of everything seen so far: the same wall seen again grows the one kept, a
 * new one is added. What a session shows a game is the surfaces it finds itself together with the kept ones it has
 * not (yet) found again. Each carries how far it is to be believed ([confidence]):
 *
 *   1.0   kept from an earlier session and found again in this one
 *   0.7   found in this session only
 *   0.4   kept from an earlier session, not seen in this one: it is where it was, if the room has not changed
 *
 * A kept wall or table top is dropped when the cameras look at where it should be and see through it ([look]):
 * of the points on it that a depth picture has an answer for, most are seen at least 30 cm further away, in eight
 * pictures more than it is seen in place. So a wardrobe that was moved, or a door that now stands open, goes after a
 * few seconds of being looked at, and one bad picture does not take a wall away.
 */
class RoomMemory {
    /** In map coordinates (y up). */
    var kept: List<RoomSurface> = emptyList(); private set
    /** Surfaces kept when this session began: what counts as "from an earlier session". */
    private var before: List<RoomSurface> = emptyList()
    var changed = false; private set

    /** For each kept surface (by its place in [kept]): pictures that saw through it less pictures that saw it in place. */
    private var through = IntArray(0)
    var dropped = 0; private set

    fun clear() { kept = emptyList(); before = emptyList(); changed = false; through = IntArray(0); dropped = 0 }

    /**
     * One depth picture. [camFromMap] takes a point of the map into the camera's frame; [range] is how far the
     * picture saw along the line to a point in that frame (NaN: no answer there; negative: outside the picture), as
     * [DenseStereo.rangeToward]. Kept surfaces the picture looks through are counted, and dropped when it is plain.
     * Returns how many were dropped by this picture.
     */
    fun look(camFromMap: Pose, range: (DoubleArray) -> Double): Int {
        if (through.size != kept.size) through = IntArray(kept.size)
        val gone = ArrayList<Int>()
        for ((i, s) in kept.withIndex()) {
            if (s.kind == RoomSurface.Kind.FLOOR) continue
            var seen = 0; var past = 0; var asked = 0
            val nu = Math.max(1, Math.round(s.halfU / LOOK_STEP).toInt()); val nv = Math.max(1, Math.round(s.halfV / LOOK_STEP).toInt())
            for (a in -nu..nu) for (b in -nv..nv) {
                // the rim is left out: a wall's end and a table's edge are the least sure part of it
                val fu = a.toDouble() / (nu + 0.5); val fv = b.toDouble() / (nv + 0.5)
                val p = camFromMap.apply(doubleArrayOf(s.cx + fu * s.halfU * s.ux + fv * s.halfV * s.vx, s.cy + fu * s.halfU * s.uy + fv * s.halfV * s.vy, s.cz + fu * s.halfU * s.uz + fv * s.halfV * s.vz))
                val d = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); if (d < 0.6 || d > LOOK_RANGE) continue
                val r = range(p); if (r < 0.0) continue
                asked++
                if (r.isNaN()) continue
                if (r > d + LOOK_PAST) past++ else if (r > d - LOOK_PAST) seen++
            }
            // enough of it in view and answered for the picture to have a say
            if (asked < LOOK_MIN_POINTS || seen + past < LOOK_MIN_POINTS / 2) continue
            if (past >= 0.7 * (seen + past)) through[i]++ else if (seen >= 0.5 * (seen + past)) through[i] = Math.max(0, through[i] - 2)
            if (through[i] >= LOOK_PICTURES) gone.add(i)
        }
        if (gone.isEmpty()) return 0
        val goneSurfaces = gone.map { kept[it] }
        through = through.filterIndexed { i, _ -> i !in gone }.toIntArray()
        kept = kept.filterIndexed { i, _ -> i !in gone }
        // and it is not shown as "from an earlier session" any more
        before = before.filter { b -> goneSurfaces.none { g -> b.kind == g.kind && RoomTidy.tidy(listOf(b, g)).size == 1 } }
        dropped += gone.size; changed = true
        return gone.size
    }

    /** What this session found, with where the map lies in the app's world: taken into what is kept. */
    fun see(appFromMap: Pose, found: RoomFindings) {
        val inv = appFromMap.inverse()
        val now = found.surfaces.filter { it.kind != RoomSurface.Kind.FLOOR }.map { moved(inv, it) }
        // Only a floor the cameras are looking at is kept (support: the boxes on it now). One held from before, or put
        // under the head after a loss of position, is not: on 22 November 2026 such a stand-in, 1.5 m too low, was
        // written over the kept floor and given to games as the room's floor in the next session.
        val floor = if (found.floorY.isNaN() || found.floorSupport <= 0) kept.firstOrNull { it.kind == RoomSurface.Kind.FLOOR }
            else RoomSurface(RoomSurface.Kind.FLOOR, 0.0, found.floorY + inv.p[1], 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 4.0, 4.0, found.floorSupport)
        // of two that are the same wall the one seen more stays where it is and grows to cover the other (RoomTidy): a
        // wall kept for long is not turned by one poor sighting of it
        val all = RoomTidy.tidy(kept.filter { it.kind != RoomSurface.Kind.FLOOR } + now).map { capped(it) }
        val next = (if (floor != null) listOf(floor) else emptyList()) + all
        if (text(next) != text(kept)) {
            // the counts of [look] go with the surfaces they belong to (matched by place: tidy keeps the kept one where it is)
            val t = IntArray(next.size); for ((i, s) in next.withIndex()) { val j = kept.indexOfFirst { it.kind == s.kind && Math.abs(it.cx - s.cx) + Math.abs(it.cy - s.cy) + Math.abs(it.cz - s.cz) < 0.5 }; if (j >= 0 && j < through.size) t[i] = through[j] }
            kept = next; through = t; changed = true
        }
    }

    /**
     * What to show: [found] (the app's world) and the kept surfaces it does not have, brought into the app's world.
     * [found] may be null (nothing found yet). Returns the findings and, for each surface in them, its confidence.
     */
    fun shown(appFromMap: Pose?, found: RoomFindings?): Pair<RoomFindings?, List<Double>> {
        if (appFromMap == null || before.isEmpty()) return Pair(found, List(found?.surfaces?.size ?: 0) { LIVE })
        val old = before.filter { it.kind != RoomSurface.Kind.FLOOR }.map { moved(appFromMap, it) }
        val live = found?.surfaces?.filter { it.kind != RoomSurface.Kind.FLOOR } ?: emptyList()
        val floors = found?.surfaces?.filter { it.kind == RoomSurface.Kind.FLOOR } ?: emptyList()
        val out = ArrayList<RoomSurface>(); val conf = ArrayList<Double>()
        for (f in floors) { out.add(f); conf.add(LIVE) }
        val usedOld = BooleanArray(old.size)
        for (s in live) {
            var both = false
            for (i in old.indices) if (old[i].kind == s.kind && RoomTidy.tidy(listOf(old[i], s)).size == 1) { usedOld[i] = true; both = true }
            out.add(s); conf.add(if (both) BOTH else LIVE)
        }
        for (i in old.indices) if (!usedOld[i]) { out.add(old[i]); conf.add(KEPT) }
        val keptFloor = before.firstOrNull { it.kind == RoomSurface.Kind.FLOOR }
        val floorY = if (found != null && !found.floorY.isNaN()) found.floorY else if (keptFloor != null) keptFloor.cy + appFromMap.p[1] else Double.NaN
        return Pair(RoomFindings(floorY, found?.floorSupport ?: 0, out, found?.voxels ?: 0, found?.confirmed ?: 0), conf)
    }

    /** How the surfaces found in this session lie against the kept ones they were taken for: metres between the planes at the found one's centre. */
    fun agreement(appFromMap: Pose, found: RoomFindings): List<Double> {
        val old = before.filter { it.kind != RoomSurface.Kind.FLOOR }.map { moved(appFromMap, it) }; val out = ArrayList<Double>()
        for (s in found.surfaces) {
            if (s.kind == RoomSurface.Kind.FLOOR) continue
            val o = old.firstOrNull { it.kind == s.kind && RoomTidy.tidy(listOf(it, s)).size == 1 } ?: continue
            val n = Math.sqrt(o.nx * o.nx + o.ny * o.ny + o.nz * o.nz); if (n < 1e-9) continue
            out.add(Math.abs((s.cx - o.cx) * o.nx + (s.cy - o.cy) * o.ny + (s.cz - o.cz) * o.nz) / n)
        }
        return out
    }
    val keptFloorInMap: Double get() = before.firstOrNull { it.kind == RoomSurface.Kind.FLOOR }?.cy ?: Double.NaN
    val keptBefore: Int get() = before.count { it.kind != RoomSurface.Kind.FLOOR }

    fun write(): String { changed = false; return text(kept) }
    /** Reads what an earlier session kept; false if the text is not that. */
    fun read(s: String?): Boolean {
        val out = ArrayList<RoomSurface>()
        for (line in s?.lines().orEmpty()) {
            val w = line.trim().split(" "); if (w.size != 14) continue
            val kind = when (w[0]) { "floor" -> RoomSurface.Kind.FLOOR; "wall" -> RoomSurface.Kind.WALL; "table" -> RoomSurface.Kind.TABLE; else -> null } ?: continue
            val v = w.drop(1).map { it.toDoubleOrNull() ?: return false }
            if (kind == RoomSurface.Kind.FLOOR && v[11].toInt() <= 0) continue      // a floor nobody saw (see [see]): not kept
            out.add(RoomSurface(kind, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11].toInt()))
        }
        kept = out; before = out; changed = false
        return out.isNotEmpty()
    }

    private fun text(l: List<RoomSurface>) = l.joinToString("\n") { s ->
        String.format(Locale.US, "%s %.3f %.3f %.3f %.4f %.4f %.4f %.4f %.4f %.4f %.3f %.3f %d 0", s.kind.name.lowercase(Locale.US), s.cx, s.cy, s.cz, s.ux, s.uy, s.uz, s.vx, s.vy, s.vz, s.halfU, s.halfV, s.support) }
    private fun capped(s: RoomSurface) = if (s.support <= MAX_SUPPORT) s else RoomSurface(s.kind, s.cx, s.cy, s.cz, s.ux, s.uy, s.uz, s.vx, s.vy, s.vz, s.halfU, s.halfV, MAX_SUPPORT)

    companion object {
        const val BOTH = 1.0; const val LIVE = 0.7; const val KEPT = 0.4
        const val MAX_SUPPORT = 100_000
        const val LOOK_STEP = 0.15
        const val LOOK_RANGE = 3.0
        const val LOOK_PAST = 0.30
        const val LOOK_MIN_POINTS = 12
        const val LOOK_PICTURES = 8
        /** [s] moved by [t]: a turn about the vertical and a shift. */
        fun moved(t: Pose, s: RoomSurface): RoomSurface {
            val c = t.apply(doubleArrayOf(s.cx, s.cy, s.cz)); val u = Q.rotate(t.q, doubleArrayOf(s.ux, s.uy, s.uz)); val v = Q.rotate(t.q, doubleArrayOf(s.vx, s.vy, s.vz))
            return RoomSurface(s.kind, c[0], c[1], c[2], u[0], u[1], u[2], v[0], v[1], v[2], s.halfU, s.halfV, s.support)
        }
    }
}
