package dev.ultraxr.core

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The room as a surface: what many depth pictures ([DenseStereo]) say, taken together.
 *
 * Space is cut into 5 cm cells. Each cell near something that was seen keeps one number: how far it is in front of
 * (+) or behind (-) the nearest surface, averaged over every picture that looked at it (a truncated signed distance
 * field, Curless and Levoy 1996; kept in blocks of 8 x 8 x 8 cells found by a hash of their place, as in Niessner
 * 2013 and Klingensmith's Chisel 2015, which ran this way on a phone). One picture's mistakes are averaged away by
 * the others; a cell a sight line passes through on its way to something further is pushed towards "empty", so
 * things that were only ever seen once fade. The surface is where the number changes sign, and [triangles] gives it
 * as flat squares between neighbouring cells, placed where the sign changes.
 *
 * Everything is in the app's world (metres, y up). Not thread safe by itself: callers hold a lock.
 */
class RoomMesh(val voxel: Double = 0.05, private val minWeight: Float = MIN_WEIGHT) {
    private class Block { val d = FloatArray(512) { 1f }; val w = FloatArray(512) }
    private val blocks = HashMap<Long, Block>()
    private val inv = 1.0 / voxel
    var integrations = 0; private set
    val blockCount: Int get() = blocks.size

    fun clear() { blocks.clear(); integrations = 0 }

    private fun key(bx: Int, by: Int, bz: Int): Long = ((bx + OFF).toLong() shl 42) or ((by + OFF).toLong() shl 21) or (bz + OFF).toLong()
    private fun cell(v: Double) = floor(v * inv).toInt()

    private fun update(x: Double, y: Double, z: Double, sd: Float, weight: Float, make: Boolean) {
        val ix = cell(x); val iy = cell(y); val iz = cell(z)
        val k = key(ix shr 3, iy shr 3, iz shr 3)
        var b = blocks[k]
        if (b == null) { if (!make || blocks.size >= MAX_BLOCKS) return; b = Block(); blocks[k] = b }
        val i = ((ix and 7) shl 6) or ((iy and 7) shl 3) or (iz and 7)
        val w0 = b.w[i]; val w1 = w0 + weight
        b.d[i] = (b.d[i] * w0 + sd * weight) / w1; b.w[i] = if (w1 > MAX_WEIGHT) MAX_WEIGHT else w1
    }

    /**
     * Adds one depth picture, taken with camera 0 at [cam] in the world. Every [step]-th pixel each way is used.
     * Returns how many sight lines were added.
     */
    fun integrate(ds: DenseStereo, cam: Pose, step: Int = 2, maxRange: Double = 3.5): Int {
        val p = DoubleArray(3); var n = 0
        val ox = cam.p[0]; val oy = cam.p[1]; val oz = cam.p[2]
        var v = 2
        while (v < ds.h - 2) {
            var u = 2
            while (u < ds.w - 2) {
                val disp = ds.disparity[v * ds.w + u]
                if (ds.point(u, v, disp, p)) {
                    val wp = cam.apply(p)
                    val dx = wp[0] - ox; val dy = wp[1] - oy; val dz = wp[2] - oz
                    val dist = sqrt(dx * dx + dy * dy + dz * dz)
                    if (dist > 0.3 && dist < maxRange) {
                        val ux = dx / dist; val uy = dy / dist; val uz = dz / dist
                        // a distance is known less well the further it is (error grows with its square): a thicker band, a lighter vote
                        val trunc = 0.10 + 0.025 * dist * dist; val weight = (1.0 / (1.0 + dist * dist)).toFloat()
                        var t = dist - trunc
                        while (t <= dist + trunc * 0.6) {
                            update(ox + ux * t, oy + uy * t, oz + uz * t, ((dist - t) / trunc).coerceIn(-1.0, 1.0).toFloat(), weight, true)
                            t += voxel * 0.7
                        }
                        // the sight line reached that far: what it passed through is empty (only cells already kept are told so)
                        t = 0.35
                        while (t < dist - trunc) { update(ox + ux * t, oy + uy * t, oz + uz * t, 1f, weight * 0.35f, false); t += voxel * 2.5 }
                        n++
                    }
                }
                u += step
            }
            v += step
        }
        integrations++
        return n
    }

    private fun at(ix: Int, iy: Int, iz: Int, out: FloatArray): Boolean {
        val b = blocks[key(ix shr 3, iy shr 3, iz shr 3)] ?: return false
        val i = ((ix and 7) shl 6) or ((iy and 7) shl 3) or (iz and 7)
        out[0] = b.d[i]; out[1] = b.w[i]; return b.w[i] >= minWeight
    }

    /**
     * The surface as triangles: x, y, z of three corners each, nine numbers a triangle, at most [maxTriangles].
     * A square is put between two neighbouring cells whose numbers have different signs, across the line between
     * them, where the sign changes. [normals] (if given, three numbers a triangle) gets which way each faces.
     */
    fun triangles(maxTriangles: Int = 60_000, normals: ArrayList<Float>? = null): FloatArray {
        val out = ArrayList<Float>(1 shl 16); val a = FloatArray(2); val b = FloatArray(2); val h = (voxel / 2).toFloat()
        var count = 0
        loop@ for ((k, blk) in blocks) {
            val bx = ((k shr 42) and 0x1FFFFF).toInt() - OFF; val by = ((k shr 21) and 0x1FFFFF).toInt() - OFF; val bz = (k and 0x1FFFFF).toInt() - OFF
            for (i in 0 until 512) {
                if (blk.w[i] < minWeight) continue
                val ix = (bx shl 3) + (i shr 6); val iy = (by shl 3) + ((i shr 3) and 7); val iz = (bz shl 3) + (i and 7)
                a[0] = blk.d[i]
                for (axis in 0..2) {
                    if (!at(ix + (if (axis == 0) 1 else 0), iy + (if (axis == 1) 1 else 0), iz + (if (axis == 2) 1 else 0), b)) continue
                    val da = a[0]; val db = b[0]
                    if ((da < 0f) == (db < 0f)) continue
                    // both ends must be near the surface: a sign change between "far in front" and "far behind" is a gap, not a surface
                    if (da >= 0.99f || db >= 0.99f || da <= -0.99f || db <= -0.99f) continue
                    val f = da / (da - db)
                    val cx = ((ix + 0.5) * voxel).toFloat(); val cy = ((iy + 0.5) * voxel).toFloat(); val cz = ((iz + 0.5) * voxel).toFloat()
                    val s = (f * voxel).toFloat()
                    val px = cx + if (axis == 0) s else 0f; val py = cy + if (axis == 1) s else 0f; val pz = cz + if (axis == 2) s else 0f
                    // the square's two in-plane axes
                    val ux = if (axis == 0) 0f else h; val uy = 0f; val uz = if (axis == 0) h else 0f
                    val vx = 0f; val vy = if (axis == 1) 0f else h; val vz = if (axis == 1) h else 0f
                    val q0x = px - ux - vx; val q0y = py - uy - vy; val q0z = pz - uz - vz
                    val q1x = px + ux - vx; val q1y = py + uy - vy; val q1z = pz + uz - vz
                    val q2x = px + ux + vx; val q2y = py + uy + vy; val q2z = pz + uz + vz
                    val q3x = px - ux + vx; val q3y = py - uy + vy; val q3z = pz - uz + vz
                    out.add(q0x); out.add(q0y); out.add(q0z); out.add(q1x); out.add(q1y); out.add(q1z); out.add(q2x); out.add(q2y); out.add(q2z)
                    out.add(q0x); out.add(q0y); out.add(q0z); out.add(q2x); out.add(q2y); out.add(q2z); out.add(q3x); out.add(q3y); out.add(q3z)
                    if (normals != null) {
                        // it faces the side where the number is positive (in front of the surface)
                        val sgn = if (da > 0f) -1f else 1f
                        repeat(2) { normals.add(if (axis == 0) sgn else 0f); normals.add(if (axis == 1) sgn else 0f); normals.add(if (axis == 2) sgn else 0f) }
                    }
                    count += 2
                    if (count >= maxTriangles) break@loop
                }
            }
        }
        val r = FloatArray(out.size); for (i in r.indices) r[i] = out[i]
        return r
    }

    /**
     * The centres of the cells the surface runs through (x, y, z one after another): cells seen often enough whose
     * number is near zero. What stands in the room is found from these ([Furniture]).
     */
    fun cells(max: Int = 400_000, weight: Float = minWeight, band: Float = 0.5f): DoubleArray {
        val out = ArrayList<Double>(1 shl 14); var n = 0
        loop@ for ((k, blk) in blocks) {
            val bx = ((k shr 42) and 0x1FFFFF).toInt() - OFF; val by = ((k shr 21) and 0x1FFFFF).toInt() - OFF; val bz = (k and 0x1FFFFF).toInt() - OFF
            for (i in 0 until 512) {
                if (blk.w[i] < weight || blk.d[i] > band || blk.d[i] < -band) continue
                out.add(((bx shl 3) + (i shr 6) + 0.5) * voxel); out.add(((by shl 3) + ((i shr 3) and 7) + 0.5) * voxel); out.add(((bz shl 3) + (i and 7) + 0.5) * voxel)
                if (++n >= max) break@loop
            }
        }
        return out.toDoubleArray()
    }

    /** The number kept at a place and how much it was seen: false where nothing was ever seen. [out]: distance (-1 behind .. +1 in front or empty), weight. */
    fun value(x: Double, y: Double, z: Double, out: FloatArray): Boolean {
        val ix = cell(x); val iy = cell(y); val iz = cell(z)
        val b = blocks[key(ix shr 3, iy shr 3, iz shr 3)] ?: return false
        val i = ((ix and 7) shl 6) or ((iy and 7) shl 3) or (iz and 7)
        out[0] = b.d[i]; out[1] = b.w[i]; return b.w[i] > 0f
    }

    /**
     * Everything, to keep between sessions (with the saved map of the room, in whose coordinates it then is): the
     * cell size, then every block that has anything in it. About 4 kB a block.
     */
    fun write(o: java.io.DataOutputStream) {
        o.writeInt(MAGIC); o.writeDouble(voxel); o.writeInt(integrations)
        val live = blocks.filter { e -> e.value.w.any { it > 0f } }
        o.writeInt(live.size)
        val buf = java.nio.ByteBuffer.allocate(512 * 8)
        for ((k, b) in live) {
            o.writeLong(k); buf.clear(); val fb = buf.asFloatBuffer(); fb.put(b.d); fb.put(b.w); o.write(buf.array())
        }
    }
    /** Reads what [write] wrote in place of what is held; false (and nothing held) if it is not that, or was made with another cell size. */
    fun read(inp: java.io.DataInputStream): Boolean {
        clear()
        try {
            if (inp.readInt() != MAGIC || Math.abs(inp.readDouble() - voxel) > 1e-9) return false
            val was = inp.readInt(); val n = inp.readInt(); if (n < 0 || n > MAX_BLOCKS) return false
            val raw = ByteArray(512 * 8)
            repeat(n) {
                val k = inp.readLong(); inp.readFully(raw); val b = Block(); val fb = java.nio.ByteBuffer.wrap(raw).asFloatBuffer(); fb.get(b.d); fb.get(b.w); blocks[k] = b
            }
            integrations = was
            return true
        } catch (_: Exception) { clear(); return false }
    }

    /** Moves everything by [delta] (the world was recentred): the cells are put where they now are. Coarse, and rare. */
    fun transform(delta: Pose) {
        val old = HashMap(blocks); blocks.clear()
        for ((k, blk) in old) {
            val bx = ((k shr 42) and 0x1FFFFF).toInt() - OFF; val by = ((k shr 21) and 0x1FFFFF).toInt() - OFF; val bz = (k and 0x1FFFFF).toInt() - OFF
            for (i in 0 until 512) {
                if (blk.w[i] <= 0f) continue
                val p = delta.apply(doubleArrayOf(((bx shl 3) + (i shr 6) + 0.5) * voxel, ((by shl 3) + ((i shr 3) and 7) + 0.5) * voxel, ((bz shl 3) + (i and 7) + 0.5) * voxel))
                val ix = cell(p[0]); val iy = cell(p[1]); val iz = cell(p[2])
                val nk = key(ix shr 3, iy shr 3, iz shr 3); val nb = blocks.getOrPut(nk) { Block() }
                val j = ((ix and 7) shl 6) or ((iy and 7) shl 3) or (iz and 7)
                if (blk.w[i] > nb.w[j]) { nb.d[j] = blk.d[i]; nb.w[j] = blk.w[i] }
            }
        }
    }

    companion object {
        private const val OFF = 1 shl 20
        private const val MAGIC = 0x55584d31      // "UXM1"
        /** A cell counts once sight lines worth this much have agreed on it: a few pictures at two metres. */
        const val MIN_WEIGHT = 0.9f
        const val MAX_WEIGHT = 40f
        /** About 60 MB of cells at the very most: a large flat. */
        const val MAX_BLOCKS = 15_000
    }
}
