package dev.ultraxr

import dev.ultraxr.core.DenseStereo
import dev.ultraxr.core.EyeImage
import dev.ultraxr.core.Furniture
import dev.ultraxr.core.FurnitureBox
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import dev.ultraxr.core.RoomFindings
import dev.ultraxr.core.RoomMemory
import dev.ultraxr.core.RoomMesh
import dev.ultraxr.core.RoomSurface
import dev.ultraxr.core.StereoRig
import dev.ultraxr.core.WallMap
import dev.ultraxr.proto.CameraDecoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The room's geometry from a recording, on this machine (no phone): the mesh kept between sessions, the furniture
 * found in it, and the dropping of kept surfaces that are no longer there. Runs only when told where a recording is:
 *
 *   UXR_GEO_RECORDING  a .uxrec        UXR_GEO_VIO   TAG-vio.csv of a replay of it (tools/pixel/lab.py)
 *   UXR_GEO_CALIB      the calibration the estimator had (files/basalt_calib.json)      UXR_GEO_OUT   a folder for pictures
 *
 * Nothing here knows where the room's things really are: what is printed is how much there is, how long it takes
 * (on this machine, not the phone), whether it is the same when read back, whether two halves of the recording agree,
 * and whether walls that are there stay while made-up ones in the middle of the room go.
 */
class GeometryRecordingTest {
    private fun eachPicture(rec: String, vioCsv: String, r: StereoRig, ds: DenseStereo, every: Double, from: Double, to: Double, f: (Double, Pose) -> Unit) {
        val rows = File(vioCsv).readLines().drop(1).map { it.split(",") }.filter { it.size >= 18 }
        val vt = DoubleArray(rows.size) { rows[it][0].toDouble() }
        val raw = ByteArray(CameraDecoder.IMAGE_BYTES); val lefts = HashMap<Int, ByteArray>(); val rights = HashMap<Int, ByteArray>()
        var first = -1L; var lastUsed = -1e9
        DataInputStream(File(rec).inputStream().buffered(1 shl 20)).use { inp ->
            val head = ByteArray(7); inp.readFully(head)
            while (true) {
                val kind = inp.read(); if (kind < 0) break
                val t = try { inp.readLong() } catch (_: Exception) { break }; val n = inp.readInt()
                if (first < 0) first = t
                if (kind != 1) { inp.skipBytes(n); continue }
                val data = ByteArray(n); try { inp.readFully(data) } catch (_: Exception) { break }
                val s = (t - first) / 1e9
                if (s < from) continue
                if (s >= to) break
                val meta = CameraDecoder.parseMeta(data) ?: continue
                if (s - lastUsed < every) continue
                val mine = if (meta.isRight) rights else lefts; val other = if (meta.isRight) lefts else rights
                val img = ByteArray(EyeImage.OUT_BYTES); CameraDecoder.decode(data, raw); EyeImage.transform(raw, meta.isRight, img)
                val o = other.remove(meta.pairCounter)
                if (o == null) { mine[meta.pairCounter] = img; if (mine.size > 6) mine.remove(mine.keys.min()); continue }
                val left = if (meta.isRight) o else img; val right = if (meta.isRight) img else o
                var i = vt.binarySearch(s + 0.07); if (i < 0) i = -i - 1; if (i >= vt.size) i = vt.size - 1
                val row = rows[i]
                if (abs(vt[i] - s - 0.07) > 0.15 || row[17] != "1") continue
                lastUsed = s; lefts.clear(); rights.clear()
                ds.compute(left, right)
                val pose = Pose(Q.rotate(HeadTracker.Z_TO_Y, doubleArrayOf(row[1].toDouble(), row[2].toDouble(), row[3].toDouble())),
                    Q.mul(HeadTracker.Z_TO_Y, doubleArrayOf(row[4].toDouble(), row[5].toDouble(), row[6].toDouble(), row[7].toDouble()))) * r.cam0InImu
                f(s, pose)
            }
        }
    }
    private fun surface(w: WallMap.Wall, floorY: Double): RoomSurface {
        val l = sqrt((w.x1 - w.x0) * (w.x1 - w.x0) + (w.z1 - w.z0) * (w.z1 - w.z0))
        return RoomSurface(RoomSurface.Kind.WALL, (w.x0 + w.x1) / 2, floorY + 1.15, (w.z0 + w.z1) / 2, (w.x1 - w.x0) / l, 0.0, (w.z1 - w.z0) / l, 0.0, 1.0, 0.0, l / 2, 1.15, w.support.toInt().coerceAtLeast(1))
    }
    private fun said(b: FurnitureBox, floorY: Double) = "%s %.2f x %.2f m, %.2f m high, at (%.2f, %.2f), %d cells, believed %.1f".format(b.label, b.hx * 2, b.hz * 2, b.top - floorY, b.cx, b.cz, b.cells, b.confidence)

    @Test fun aRecordingIfGiven() {
        val rec = System.getenv("UXR_GEO_RECORDING"); val vio = System.getenv("UXR_GEO_VIO"); val calib = System.getenv("UXR_GEO_CALIB"); val outDir = System.getenv("UXR_GEO_OUT")
        assumeTrue(rec != null && vio != null && calib != null && outDir != null)
        val r = StereoRig.fromBasalt(File(calib!!).readText()); val ds = DenseStereo(r); File(outDir!!).mkdirs()
        val every = (System.getenv("UXR_GEO_EVERY") ?: "1.0").toDouble()

        // ---- one pass: the mesh of the whole recording, of its first and its second half; the walls; the floor
        val whole = RoomMesh(); val firstHalf = RoomMesh(); val secondHalf = RoomMesh(); val wallMap = WallMap()
        val heights = ArrayList<Double>(); val p = DoubleArray(3); val track = ArrayList<DoubleArray>(); var y0 = Double.NaN
        var pictures = 0; var meshNs = 0L; var end = 0.0
        eachPicture(rec!!, vio!!, r, ds, every, 0.0, 1e9) { s, _ -> end = s }
        eachPicture(rec, vio, r, ds, every, 0.0, 1e9) { s, pose ->
            if (y0.isNaN()) y0 = pose.p[1]
            for (v in 3 until ds.h - 3 step 4) for (u in 3 until ds.w - 3 step 4) if (ds.point(u, v, ds.disparity[v * ds.w + u], p)) { val w = pose.apply(p); val below = pose.p[1] - w[1]; if (below > 1.2 && below < 2.2 && p[2] < 3.5) heights.add(w[1]) }
            val t0 = System.nanoTime(); whole.integrate(ds, pose); meshNs += System.nanoTime() - t0
            (if (s < end / 2) firstHalf else secondHalf).integrate(ds, pose)
            wallMap.add(ds, pose, y0 - 1.70); track.add(doubleArrayOf(pose.p[0], pose.p[1], pose.p[2])); pictures++
        }
        heights.sort(); val floorY = if (heights.size > 200) heights[heights.size / 2] else y0 - 1.70
        val walls = wallMap.walls().map { surface(it, floorY) }
        println("%d depth pictures over %.0f s; floor %.2f m under the first head height; %d walls".format(pictures, end, y0 - floorY, walls.size))

        // ---- the mesh: size, time, and whether it is the same when written and read back
        val bytes = ByteArrayOutputStream(); val tw = System.nanoTime(); whole.write(DataOutputStream(bytes)); val writeMs = (System.nanoTime() - tw) / 1e6
        val back = RoomMesh(); val tr = System.nanoTime(); val ok = back.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))); val readMs = (System.nanoTime() - tr) / 1e6
        val cw = System.getenv("UXR_GEO_MINW")?.toFloat() ?: Furniture.CELL_WEIGHT; val cb = System.getenv("UXR_GEO_BAND")?.toFloat() ?: Furniture.CELL_BAND
        val cells = whole.cells(weight = cw, band = cb); val cellsBack = back.cells(weight = cw, band = cb); val tri = whole.triangles(400_000).size / 9; val triBack = back.triangles(400_000).size / 9
        println("mesh: %d blocks, %d surface cells, %d triangles; adding a picture takes %.0f ms here; kept as %.1f MB (written in %.0f ms, read in %.0f ms); read back: %s, %d cells, %d triangles".format(
            whole.blockCount, cells.size / 3, tri, meshNs / 1e6 / maxOf(1, pictures), bytes.size() / 1e6, writeMs, readMs, if (ok) "accepted" else "REFUSED", cellsBack.size / 3, triBack))
        File(outDir, "room_mesh.bin").writeBytes(bytes.toByteArray())

        // ---- furniture: from the whole, and from each half by itself
        val tf = System.nanoTime(); val all = Furniture.detect(cells, floorY, walls); val furnMs = (System.nanoTime() - tf) / 1e6
        val a = Furniture.detect(firstHalf.cells(weight = cw / 2, band = cb), floorY, walls); val b = Furniture.detect(secondHalf.cells(weight = cw / 2, band = cb), floorY, walls)
        println("furniture from the whole recording (%.0f ms): %d things".format(furnMs, all.size)); for (f in all) println("   " + said(f, floorY))
        val inBoth = a.count { x -> b.any { Furniture.same(x, it) } }
        println("from the first half alone %d things, from the second half alone %d; %d of the first half's are in the second half's too; of the whole's %d, %d are in the first half's and %d in the second half's".format(
            a.size, b.size, inBoth, all.size, all.count { x -> a.any { Furniture.same(x, it) } }, all.count { x -> b.any { Furniture.same(x, it) } }))

        // ---- kept surfaces that are gone: the walls found, and two made up across where the wearer walked
        val mem = RoomMemory(); val map = Pose()
        val mid = track[track.size / 2]; val third = track[track.size / 4]
        val fake = listOf(RoomSurface(RoomSurface.Kind.WALL, mid[0], floorY + 1.15, mid[2], 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.8, 1.15, 500),
            RoomSurface(RoomSurface.Kind.WALL, third[0], floorY + 1.15, third[2], 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.8, 1.15, 500))
        mem.see(map, RoomFindings(floorY, 100, walls + fake, 0, 0)); val kept = RoomMemory(); kept.read(mem.write())
        val startCount = kept.kept.count { it.kind == RoomSurface.Kind.WALL }; var lookNs = 0L; val droppedAt = ArrayList<String>()
        eachPicture(rec, vio, r, ds, every, 0.0, 1e9) { s, pose ->
            val before = kept.kept.filter { it.kind == RoomSurface.Kind.WALL }
            val t0 = System.nanoTime(); val n = kept.look(pose.inverse()) { q -> ds.rangeToward(q) }; lookNs += System.nanoTime() - t0
            if (n > 0) { val after = kept.kept; for (w in before) if (after.none { it === w }) droppedAt.add("%.0f s: the %s wall at (%.2f, %.2f)".format(s, if (fake.any { f -> abs(f.cx - w.cx) + abs(f.cz - w.cz) < 0.05 }) "made-up" else "REAL", w.cx, w.cz)) }
        }
        println("kept surfaces looked at in %d pictures (%.1f ms a picture here): of %d walls kept (%d found, 2 made up in the middle of the room), dropped: %s".format(
            pictures, lookNs / 1e6 / maxOf(1, pictures), startCount, walls.size, if (droppedAt.isEmpty()) "none" else droppedAt.joinToString("; ")))

        // ---- a picture from above: surface cells between 0.12 and 1.9 m over the floor, walls red, furniture green, the walk blue
        val size = 700; val scale = 70.0; val img = ByteArray(size * size * 3)
        val cxm = track.map { it[0] }.average(); val czm = track.map { it[2] }.average()
        fun px(x: Double, z: Double, rr: Int, g: Int, bb: Int, add: Boolean = false) {
            val ix = (size / 2 + (x - cxm) * scale).toInt(); val iz = (size / 2 + (z - czm) * scale).toInt(); if (ix !in 0 until size || iz !in 0 until size) return
            val o = (iz * size + ix) * 3
            if (add) { val v = minOf(255, (img[o].toInt() and 0xff) + rr).toByte(); img[o] = v; img[o + 1] = v; img[o + 2] = v } else { img[o] = rr.toByte(); img[o + 1] = g.toByte(); img[o + 2] = bb.toByte() }
        }
        var i = 0; while (i + 2 < cells.size) { val up = cells[i + 1] - floorY; if (up > 0.12 && up < 1.9) px(cells[i], cells[i + 2], 45, 0, 0, add = true); i += 3 }
        for (w in walls) for (k in 0..300) { val f = k / 150.0 - 1.0; px(w.cx + f * w.halfU * w.ux, w.cz + f * w.halfU * w.uz, 255, 50, 50) }
        for (f in all) { val c = Math.cos(f.yaw); val s = Math.sin(f.yaw)
            for (k in 0..120) { val t = k / 60.0 - 1.0
                for ((u, v) in listOf(Pair(t * f.hx, f.hz), Pair(t * f.hx, -f.hz), Pair(f.hx, t * f.hz), Pair(-f.hx, t * f.hz))) px(f.cx + c * u + s * v, f.cz - s * u + c * v, 60, 255, 90) } }
        for (t in track) px(t[0], t[2], 80, 140, 255)
        File(outDir, "geometry_above.ppm").outputStream().use { it.write(("P6 $size $size 255" + 10.toChar()).toByteArray()); it.write(img) }
    }
}
