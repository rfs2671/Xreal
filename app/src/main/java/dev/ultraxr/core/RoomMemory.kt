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
 * Nothing is ever dropped yet: a wardrobe that was moved stays where it stood until the room is learnt anew.
 */
class RoomMemory {
    /** In map coordinates (y up). */
    var kept: List<RoomSurface> = emptyList(); private set
    /** Surfaces kept when this session began: what counts as "from an earlier session". */
    private var before: List<RoomSurface> = emptyList()
    var changed = false; private set

    fun clear() { kept = emptyList(); before = emptyList(); changed = false }

    /** What this session found, with where the map lies in the app's world: taken into what is kept. */
    fun see(appFromMap: Pose, found: RoomFindings) {
        val inv = appFromMap.inverse()
        val now = found.surfaces.filter { it.kind != RoomSurface.Kind.FLOOR }.map { moved(inv, it) }
        val floor = if (found.floorY.isNaN()) kept.firstOrNull { it.kind == RoomSurface.Kind.FLOOR }
            else RoomSurface(RoomSurface.Kind.FLOOR, 0.0, found.floorY + inv.p[1], 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 4.0, 4.0, found.floorSupport)
        // of two that are the same wall the one seen more stays where it is and grows to cover the other (RoomTidy): a
        // wall kept for long is not turned by one poor sighting of it
        val all = RoomTidy.tidy(kept.filter { it.kind != RoomSurface.Kind.FLOOR } + now).map { capped(it) }
        val next = (if (floor != null) listOf(floor) else emptyList()) + all
        if (text(next) != text(kept)) { kept = next; changed = true }
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
        /** [s] moved by [t]: a turn about the vertical and a shift. */
        fun moved(t: Pose, s: RoomSurface): RoomSurface {
            val c = t.apply(doubleArrayOf(s.cx, s.cy, s.cz)); val u = Q.rotate(t.q, doubleArrayOf(s.ux, s.uy, s.uz)); val v = Q.rotate(t.q, doubleArrayOf(s.vx, s.vy, s.vz))
            return RoomSurface(s.kind, c[0], c[1], c[2], u[0], u[1], u[2], v[0], v[1], v[2], s.halfU, s.halfV, s.support)
        }
    }
}
