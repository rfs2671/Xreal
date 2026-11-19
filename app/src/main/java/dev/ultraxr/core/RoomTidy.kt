// Ultra XR: one wall is one wall. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The room's surfaces with the doubles taken out. The wall finder and the table finder each report what they see
 * picture by picture, and the same wall or table top often comes out twice, a few centimetres apart (worn on
 * 19 November 2026: two walls 1 cm apart, two table tops 15 cm apart). A game that gets the room through the scene
 * API would build both.
 *
 *   walls   are the same when they face the same way within 12 degrees (either side), lie within 25 cm of each
 *           other across, and overlap along their length or nearly touch (20 cm);
 *   tables  are the same when their tops are within 10 cm in height and one's centre is over the other (15 cm of
 *           margin).
 *
 * Of two that are the same, the one seen more ([RoomSurface.support]) stays, where it is and facing as it does, and
 * grows to cover the other. The floor is left alone.
 */
object RoomTidy {
    const val WALL_COS = 0.978      // 12 degrees
    const val WALL_ACROSS_M = 0.25
    const val WALL_GAP_M = 0.20
    const val TABLE_HEIGHT_M = 0.10
    const val TABLE_MARGIN_M = 0.15

    fun tidy(surfaces: List<RoomSurface>): List<RoomSurface> {
        val out = ArrayList<RoomSurface>()
        for (s in surfaces.sortedByDescending { it.support }) {
            if (s.kind == RoomSurface.Kind.FLOOR) { out.add(s); continue }
            var merged = false
            for (i in out.indices) {
                val k = out[i]
                if (k.kind != s.kind || !same(k, s)) continue
                out[i] = cover(k, s); merged = true; break
            }
            if (!merged) out.add(s)
        }
        return out
    }

    private fun same(k: RoomSurface, s: RoomSurface): Boolean {
        val dx = s.cx - k.cx; val dy = s.cy - k.cy; val dz = s.cz - k.cz
        val alongU = dx * k.ux + dy * k.uy + dz * k.uz
        if (k.kind == RoomSurface.Kind.WALL) {
            val kn = len(k.nx, k.ny, k.nz); val sn = len(s.nx, s.ny, s.nz)
            if (kn < 1e-9 || sn < 1e-9) return false
            if (abs(k.nx * s.nx + k.ny * s.ny + k.nz * s.nz) / (kn * sn) < WALL_COS) return false
            val across = abs(dx * k.nx + dy * k.ny + dz * k.nz) / kn
            return across <= WALL_ACROSS_M && abs(alongU) <= k.halfU + s.halfU + WALL_GAP_M
        }
        if (abs(dy) > TABLE_HEIGHT_M) return false
        val alongV = dx * k.vx + dy * k.vy + dz * k.vz
        if (abs(alongU) <= k.halfU + TABLE_MARGIN_M && abs(alongV) <= k.halfV + TABLE_MARGIN_M) return true
        // or the other way about: the kept one's centre over the other
        val bu = -(dx * s.ux + dy * s.uy + dz * s.uz); val bv = -(dx * s.vx + dy * s.vy + dz * s.vz)
        return abs(bu) <= s.halfU + TABLE_MARGIN_M && abs(bv) <= s.halfV + TABLE_MARGIN_M
    }

    /** [k], grown in its own plane to cover [s]'s four corners as well. */
    private fun cover(k: RoomSurface, s: RoomSurface): RoomSurface {
        var u0 = -k.halfU; var u1 = k.halfU; var v0 = -k.halfV; var v1 = k.halfV
        for (a in intArrayOf(-1, 1)) for (b in intArrayOf(-1, 1)) {
            val px = s.cx + a * s.halfU * s.ux + b * s.halfV * s.vx - k.cx
            val py = s.cy + a * s.halfU * s.uy + b * s.halfV * s.vy - k.cy
            val pz = s.cz + a * s.halfU * s.uz + b * s.halfV * s.vz - k.cz
            val u = px * k.ux + py * k.uy + pz * k.uz; val v = px * k.vx + py * k.vy + pz * k.vz
            u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
        }
        val mu = (u0 + u1) / 2; val mv = (v0 + v1) / 2
        return RoomSurface(k.kind, k.cx + mu * k.ux + mv * k.vx, k.cy + mu * k.uy + mv * k.vy, k.cz + mu * k.uz + mv * k.vz,
            k.ux, k.uy, k.uz, k.vx, k.vy, k.vz, (u1 - u0) / 2, (v1 - v0) / 2, k.support + s.support)
    }

    private fun len(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
}
