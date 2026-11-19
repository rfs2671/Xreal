package dev.ultraxr

import dev.ultraxr.core.DenseStereo
import dev.ultraxr.core.EyeImage
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Kb4
import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import dev.ultraxr.core.StereoRig
import dev.ultraxr.proto.CameraDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class DenseTest {
    /** Two ordinary cameras side by side, 10 cm apart, looking the same way: what a drawn wall and floor must come out as. */
    private fun rig(): StereoRig {
        val k = Kb4(240.0, 240.0, 240.0, 320.0, 0.0, 0.0, 0.0, 0.0)
        return StereoRig(k, k, Pose(), Pose(doubleArrayOf(0.10, 0.0, 0.0)))
    }

    @Test fun aTexturedWallComesOutAtItsDistance() {
        val r = rig(); val ds = DenseStereo(r)
        // a wall 1.5 m away covered in a random pattern, drawn into both cameras
        val rnd = java.util.Random(3); val tex = ByteArray(512 * 512) { (rnd.nextInt(200) + 28).toByte() }
        fun draw(camX: Double): ByteArray {
            val img = ByteArray(480 * 640); val ray = DoubleArray(3)
            for (v in 0 until 640) for (u in 0 until 480) {
                r.k0.unproject(u.toDouble(), v.toDouble(), ray)
                if (ray[2] < 0.2) continue
                val s = 1.5 / ray[2]; val x = camX + ray[0] * s; val y = ray[1] * s
                // pattern cells of 3 cm, smoothed a little by sampling between them
                val tx = ((x + 20) / 0.03); val ty = ((y + 20) / 0.03)
                val ix = tx.toInt(); val iy = ty.toInt(); val fx = tx - ix; val fy = ty - iy
                fun t(a: Int, b: Int) = (tex[((b and 511) * 512) + (a and 511)].toInt() and 0xff)
                img[v * 480 + u] = ((t(ix, iy) * (1 - fx) + t(ix + 1, iy) * fx) * (1 - fy) + (t(ix, iy + 1) * (1 - fx) + t(ix + 1, iy + 1) * fx) * fy).toInt().toByte()
            }
            return img
        }
        val n = ds.compute(draw(0.0), draw(0.10))
        assertTrue("answers for most of the picture: $n", n > DenseStereo.W * DenseStereo.H / 2)
        val p = DoubleArray(3); var sum = 0.0; var k = 0; var worst = 0.0
        for (v in 40 until DenseStereo.H - 40 step 7) for (u in 70 until DenseStereo.W - 20 step 7) {
            if (!ds.point(u, v, ds.disparity[v * DenseStereo.W + u], p)) continue
            sum += p[2]; k++; worst = maxOf(worst, abs(p[2] - 1.5))
        }
        assertEquals(1.5, sum / k, 0.03)
        assertTrue("no point far off the wall: $worst", worst < 0.15)
    }

    /**
     * Not part of the automatic tests. With UXR_ROOM_RECORDING, UXR_ROOM_VIO, UXR_ROOM_CALIB (as in RoomTest) and
     * UXR_DENSE_OUT=folder set: dense depth for a picture pair every two seconds, written as pictures (the rectified
     * left view beside its distances, near = bright), with the time each took and how level the floor comes out.
     */
    @Test fun aRecordingIfGiven() {
        val rec = System.getenv("UXR_ROOM_RECORDING"); val vioCsv = System.getenv("UXR_ROOM_VIO"); val calib = System.getenv("UXR_ROOM_CALIB"); val outDir = System.getenv("UXR_DENSE_OUT")
        assumeTrue(rec != null && vioCsv != null && calib != null && outDir != null)
        val r0 = StereoRig.fromBasalt(File(calib!!).readText())
        val fixDeg = System.getenv("UXR_ROOM_ROTFIX")?.split(",")?.map { it.trim().toDouble() }
        val r = if (fixDeg == null) r0 else {
            val x = Math.toRadians(fixDeg[0]); val y = Math.toRadians(fixDeg[1]); val z = Math.toRadians(fixDeg[2]); val ang = sqrt(x * x + y * y + z * z)
            StereoRig(r0.k0, r0.k1, r0.cam0InImu, Pose(r0.cam1InImu.p, Q.normalize(Q.mul(r0.cam1InImu.q, Q.conj(Q.axisAngle(x / ang, y / ang, z / ang, ang))))))
        }
        val ds = DenseStereo(r); File(outDir!!).mkdirs()
        val every = (System.getenv("UXR_DENSE_EVERY") ?: "2.0").toDouble(); val pictures = every >= 1.0
        val model = dev.ultraxr.core.RoomModel(); val world = FloatArray(3 * 6000); var frameNo = 0; val denseStep = (System.getenv("UXR_DENSE_STEP") ?: "4").toInt(); val denseRange = (System.getenv("UXR_DENSE_RANGE") ?: "3.0").toDouble()
        var lastFindings: dev.ultraxr.core.RoomFindings? = null; var lastPose: Pose? = null
        val wallMap = dev.ultraxr.core.WallMap()
        val G = 240; val grid = IntArray(G * G); val track = ArrayList<DoubleArray>(); var gy0 = Double.NaN
        val mesh = dev.ultraxr.core.RoomMesh((System.getenv("UXR_MESH_VOXEL") ?: "0.05").toDouble(), (System.getenv("UXR_MESH_MINW") ?: "0.9").toFloat()); var meshMs = 0L; var firstY = Double.NaN
        val rows = File(vioCsv!!).readLines().drop(1).map { it.split(",") }.filter { it.size >= 18 }
        val vt = DoubleArray(rows.size) { rows[it][0].toDouble() }
        val raw = ByteArray(CameraDecoder.IMAGE_BYTES)
        val lefts = HashMap<Int, ByteArray>(); val rights = HashMap<Int, ByteArray>()
        var first = -1L; var lastUsed = -1e9; var shots = 0; var ms = 0L
        val heights = ArrayList<Double>(); val p = DoubleArray(3)
        // UXR_FLOOR_BELOW: the floor's known depth under the first head height; then the scatter of floor points is printed by distance
        val floorBelow = System.getenv("UXR_FLOOR_BELOW")?.toDouble(); val bands = doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5); val errs = Array(5) { ArrayList<Double>() }; var y0 = Double.NaN
        DataInputStream(File(rec!!).inputStream().buffered(1 shl 20)).use { inp ->
            val head = ByteArray(7); inp.readFully(head)
            while (true) {
                val kind = inp.read(); if (kind < 0) break
                val t = try { inp.readLong() } catch (_: Exception) { break }; val n = inp.readInt()
                if (first < 0) first = t
                if (kind != 1) { inp.skipBytes(n); continue }
                val data = ByteArray(n); try { inp.readFully(data) } catch (_: Exception) { break }
                val s = (t - first) / 1e9
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
                val t0 = System.nanoTime(); val n3 = ds.compute(left, right); val took = (System.nanoTime() - t0) / 1_000_000; ms += took
                val pose = Pose(Q.rotate(HeadTracker.Z_TO_Y, doubleArrayOf(row[1].toDouble(), row[2].toDouble(), row[3].toDouble())),
                    Q.mul(HeadTracker.Z_TO_Y, doubleArrayOf(row[4].toDouble(), row[5].toDouble(), row[6].toDouble(), row[7].toDouble()))) * r.cam0InImu
                val W = DenseStereo.W; val H = DenseStereo.H
                val im = ByteArray(W * 2 * H * 3); val rl = ds.rectifiedLeft()
                fun px(x: Int, y: Int, r: Int, g: Int, b: Int) { val o = (y * W * 2 + x) * 3; im[o] = r.toByte(); im[o + 1] = g.toByte(); im[o + 2] = b.toByte() }
                for (v in 0 until H) for (u in 0 until W) {
                    val g = rl[v * W + u].toInt() and 0xff; px(u, v, g, g, g)
                    val d = ds.disparity[v * W + u]
                    if (d > 0f) {
                        val z = ds.focal * ds.baseline / d; val c = (255 * (1.0 - ((z - 0.3) / 4.2).coerceIn(0.0, 1.0))).toInt()
                        px(W + u, v, c, (255 - abs(c - 128) * 2).coerceIn(0, 255), 255 - c)
                        if (ds.point(u, v, d, p)) { val wpt = pose.apply(p); if (y0.isNaN()) y0 = pose.p[1]
                            if (floorBelow != null) { val e = wpt[1] - (y0 - floorBelow); val rng = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); if (abs(e) < 0.3 && rng >= 1.0 && rng < 3.5) errs[((rng - 1.0) / 0.5).toInt()].add(e) }
 val below = pose.p[1] - wpt[1]; if (below > 1.2 && below < 2.2 && z < 3.5) heights.add(below) }
                    }
                }
                run {      // the same depth, thinned, into the plane finder
                    var k = 0; val q = DoubleArray(3)
                    var vv = 3; while (vv < H - 3) { var uu = 3; while (uu < W - 3) {
                        if (k < 6000 && ds.point(uu, vv, ds.disparity[vv * W + uu], q) && sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]) < denseRange) { val wq = pose.apply(q); world[k * 3] = wq[0].toFloat(); world[k * 3 + 1] = wq[1].toFloat(); world[k * 3 + 2] = wq[2].toFloat(); k++ }
                        uu += denseStep }; vv += denseStep }
                    if (gy0.isNaN()) gy0 = pose.p[1]
                    // from above: everything between 0.4 and 1.9 m over the floor (taken as 1.70 m under the first head height), all pixels
                    run { val qq = DoubleArray(3); for (v2 in 3 until H - 3 step 2) for (u2 in 3 until W - 3 step 2) {
                        if (!ds.point(u2, v2, ds.disparity[v2 * W + u2], qq) || sqrt(qq[0] * qq[0] + qq[1] * qq[1] + qq[2] * qq[2]) > 3.5) continue
                        val wq = pose.apply(qq); val up = wq[1] - (gy0 - 1.70); if (up < 0.4 || up > 1.9) continue
                        val gx = (G / 2 + wq[0] / 0.05).toInt(); val gz = (G / 2 + wq[2] / 0.05).toInt(); if (gx in 0 until G && gz in 0 until G) grid[gz * G + gx]++ } }
                    track.add(doubleArrayOf(pose.p[0], pose.p[2]))
                    wallMap.add(ds, pose, gy0 - 1.70)
                    model.add(world, k, frameNo++)
                    val look = Q.rotate(pose.q, doubleArrayOf(0.0, 0.0, 1.0))
                    lastFindings = model.analyse(pose.p[1], pose.p[0], pose.p[2], look[0], look[2]); lastPose = pose
                }
                val tm = System.nanoTime(); mesh.integrate(ds, pose); meshMs += (System.nanoTime() - tm) / 1_000_000; if (firstY.isNaN()) firstY = pose.p[1]
                if (pictures) File(outDir, "dense-%03d.ppm".format(s.toInt())).outputStream().use { it.write(("P6 " + (W * 2) + " " + H + " 255" + 10.toChar()).toByteArray()); it.write(im) }
                if (pictures) println("%.0f s: %d of %d pixels answered (%.0f %%), %d ms".format(s, n3, W * H, 100.0 * n3 / (W * H), took)); shots++
            }
        }
        if (heights.size > 500) {
            heights.sort(); val med = heights[heights.size / 2]
            val near = heights.count { abs(it - med) < 0.05 }
            println("floor: %d points between 1.2 and 2.2 m below the cameras, median %.3f m, %.0f %% within 5 cm of it".format(heights.size, med, 100.0 * near / heights.size))
        }
        if (floorBelow != null) for (b in 0 until 5) { val e = errs[b]; if (e.size < 50) continue; run { val hh = IntArray(30); for (x in e) hh[((x + 0.3) / 0.02).toInt().coerceIn(0, 29)]++; println("floor heights %.1f m, 2 cm bins from -30 cm: ".format(bands[b]) + hh.joinToString(" ")) }; e.sort(); println("floor scatter %.1f-%.1f m: %d points, median %+.0f mm, middle 68 %% spread +-%.0f mm".format(bands[b], bands[b + 1], e.size, e[e.size / 2] * 1000, (e[(e.size * 0.84).toInt()] - e[(e.size * 0.16).toInt()]) * 500)) }
        println("%d pairs, %d ms each on this machine".format(shots, ms / maxOf(1, shots)))
        lastFindings?.let { g -> val cp = lastPose!!
            println("planes from dense depth: floor %s; %s".format(if (g.floorY.isNaN()) "not found" else "%.2f m below the head".format(cp.p[1] - g.floorY),
                g.surfaces.filter { it.kind != dev.ultraxr.core.RoomSurface.Kind.FLOOR }.joinToString("; ") { "%s %.1f x %.1f m, centre %.1f m away, %.2f m below the head".format(it.kind, it.halfU * 2, it.halfV * 2, sqrt((it.cx - cp.p[0]) * (it.cx - cp.p[0]) + (it.cz - cp.p[2]) * (it.cz - cp.p[2])), cp.p[1] - it.cy) }))
        }
        run { val img = ByteArray(G * G * 3); val mx = grid.max().coerceAtLeast(1)
            for (i in 0 until G * G) { val c = (255.0 * Math.pow(grid[i].toDouble() / mx, 0.35)).toInt().coerceIn(0, 255).toByte(); img[i * 3] = c; img[i * 3 + 1] = c; img[i * 3 + 2] = c }
            for (t in track) { val gx = (G / 2 + t[0] / 0.05).toInt(); val gz = (G / 2 + t[1] / 0.05).toInt(); if (gx in 0 until G && gz in 0 until G) { img[(gz * G + gx) * 3] = 255.toByte(); img[(gz * G + gx) * 3 + 1] = 0; img[(gz * G + gx) * 3 + 2] = 0 } }
            File(outDir, "above.ppm").outputStream().use { it.write(("P6 " + G + " " + G + " 255" + 10.toChar()).toByteArray()); it.write(img) } }
        run {
            val t0 = System.nanoTime(); val walls = wallMap.walls(); val took = (System.nanoTime() - t0) / 1_000_000
            println("walls from dense depth (%d ms): ".format(took) + walls.joinToString("; ") { "%.1f m long from (%.1f, %.1f) to (%.1f, %.1f), support %.0f".format(it.length, it.x0, it.z0, it.x1, it.z1, it.support) })
            val n = wallMap.planSize; val plan = wallMap.plan(); val o = wallMap.planOrigin(); val img = ByteArray(n * n * 3); val mx = plan.max().coerceAtLeast(1e-6f)
            for (i in 0 until n * n) { val c = (255.0 * Math.pow((plan[i] / mx).toDouble(), 0.35)).toInt().coerceIn(0, 255).toByte(); img[i * 3] = c; img[i * 3 + 1] = c; img[i * 3 + 2] = c }
            for (w in walls) for (k in 0..400) { val f = k / 400.0; val gx = (n / 2 + (w.x0 + (w.x1 - w.x0) * f - o[0]) / 0.05).toInt(); val gz = (n / 2 + (w.z0 + (w.z1 - w.z0) * f - o[1]) / 0.05).toInt()
                if (gx in 0 until n && gz in 0 until n) { img[(gz * n + gx) * 3] = 255.toByte(); img[(gz * n + gx) * 3 + 1] = 40; img[(gz * n + gx) * 3 + 2] = 40 } }
            File(outDir, "walls.ppm").outputStream().use { it.write(("P6 " + n + " " + n + " 255" + 10.toChar()).toByteArray()); it.write(img) }
        }
        // the mesh: how much of it, how long it took, how level its floor is, and a picture from above
        val t1 = System.nanoTime(); val normals = ArrayList<Float>(); val tri = mesh.triangles(400_000, normals); val triMs = (System.nanoTime() - t1) / 1_000_000
        val nTri = tri.size / 9
        println("mesh: %d blocks, %d triangles; adding a picture took %d ms, making the triangles %d ms".format(mesh.blockCount, nTri, meshMs / maxOf(1, shots), triMs))
        val floorH = ArrayList<Double>(); var up = 0; var side = 0
        val size = 600; val img = ByteArray(size * size * 3); val scale = 60.0
        for (t in 0 until nTri) {
            val cx = (tri[t * 9] + tri[t * 9 + 3] + tri[t * 9 + 6]) / 3.0; val cy = (tri[t * 9 + 1] + tri[t * 9 + 4] + tri[t * 9 + 7]) / 3.0; val cz = (tri[t * 9 + 2] + tri[t * 9 + 5] + tri[t * 9 + 8]) / 3.0
            val ny = normals[t * 3 + 1]
            if (ny != 0f) { up++; val below = firstY - cy; if (below > 1.2 && below < 2.2) floorH.add(below) } else side++
            val px = (size / 2 + cx * scale).toInt(); val pz = (size / 2 + cz * scale).toInt()
            if (px in 0 until size && pz in 0 until size) {
                val o = (pz * size + px) * 3; val below = firstY - cy
                if (ny != 0f && below > 1.2) { img[o + 1] = 200.toByte() }                       // level and low: floor, green
                else if (ny != 0f) { img[o] = 60; img[o + 1] = 160.toByte(); img[o + 2] = 255.toByte() }   // level and higher: tops, blue
                else { img[o] = 255.toByte(); img[o + 1] = (img[o + 1].toInt() and 0xff).coerceAtLeast(90).toByte(); img[o + 2] = 200.toByte() }   // upright: walls and sides
            }
        }
        File(outDir, "mesh-top.ppm").outputStream().use { it.write(("P6 " + size + " " + size + " 255" + 10.toChar()).toByteArray()); it.write(img) }
        run {      // level squares by height below the starting head, 10 cm bands
            val hist = IntArray(30); val sideHist = IntArray(30)
            for (t in 0 until nTri) { val cy = (tri[t * 9 + 1] + tri[t * 9 + 4] + tri[t * 9 + 7]) / 3.0; val b = ((firstY - cy + 0.5) / 0.1).toInt(); if (b in 0 until 30) { if (normals[t * 3 + 1] != 0f) hist[b]++ else sideHist[b]++ } }
            println("mesh: level squares by height, from 0.5 m above the head down in 10 cm bands: " + hist.joinToString(" "))
            println("mesh: upright squares, same bands: " + sideHist.joinToString(" "))
        }
        if (floorH.size > 200) {
            floorH.sort(); val med = floorH[floorH.size / 2]
            println("mesh: %d level squares, %d upright; floor %.3f m below the starting head height, %.0f %% of the low level squares within 3 cm of it, %.0f %% within 6 cm".format(
                up, side, med, 100.0 * floorH.count { abs(it - med) < 0.03 } / floorH.size, 100.0 * floorH.count { abs(it - med) < 0.06 } / floorH.size))
        }
    }
}
