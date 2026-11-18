package dev.ultraxr

import dev.ultraxr.core.EyeImage
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import dev.ultraxr.core.RoomModel
import dev.ultraxr.core.RoomSurface
import dev.ultraxr.core.StereoPoints
import dev.ultraxr.core.StereoRig
import dev.ultraxr.proto.CameraDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The room model (floor, table tops, walls) and the stereo point finder that feeds it, on made-up rooms and made-up
 * camera pictures. Simulation only: what the real cameras give is measured with a recording (see [aRecordingIfGiven]).
 */
class RoomTest {
    private val rig = StereoRig.fromBasalt(File("src/main/assets/calibration/basalt_calib.json").readText())

    // ------------------------------------------------------------ camera model

    @Test fun aPixelGoesToItsSightLineAndBack() {
        val ray = DoubleArray(3); val uv = DoubleArray(2)
        for ((u, v) in listOf(240.0 to 320.0, 60.0 to 100.0, 430.0 to 560.0, 100.0 to 500.0)) {
            rig.k0.unproject(u, v, ray)
            assertEquals(1.0, sqrt(ray[0] * ray[0] + ray[1] * ray[1] + ray[2] * ray[2]), 1e-9)
            assertTrue(rig.k0.project(ray[0] * 2, ray[1] * 2, ray[2] * 2, uv))
            assertEquals(u, uv[0], 1e-4); assertEquals(v, uv[1], 1e-4)
        }
    }

    // ------------------------------------------------------------ stereo points from rendered pictures

    /** A texture with corners everywhere: smooth noise in 4 cm cells, the same wherever a surface point is seen from. */
    private fun texture(a: Double, b: Double): Int {
        fun h(i: Long, j: Long): Double { var x = i * 374761393L + j * 668265263L; x = (x xor (x shr 13)) * 1274126177L; return ((x xor (x shr 16)) and 0xFFFF) / 65535.0 }
        val s = 0.04; val fa = a / s; val fb = b / s; val i = floor(fa).toLong(); val j = floor(fb).toLong(); val ta = fa - i; val tb = fb - j
        val v = h(i, j) * (1 - ta) * (1 - tb) + h(i + 1, j) * ta * (1 - tb) + h(i, j + 1) * (1 - ta) * tb + h(i + 1, j + 1) * ta * tb
        return (30 + 190 * v).toInt()
    }

    /** Both cameras' pictures of a wall [wallZ] metres ahead and a floor [floorDown] metres below the left camera. */
    private fun render(wallZ: Double, floorDown: Double, noise: Random?): Pair<ByteArray, ByteArray> {
        val w = 480; val h = 640
        val out = arrayOf(ByteArray(w * h), ByteArray(w * h))
        val cam1InCam0 = rig.cam0InCam1.inverse()
        val ray = DoubleArray(3)
        for (cam in 0..1) {
            val k = if (cam == 0) rig.k0 else rig.k1
            val o = if (cam == 0) doubleArrayOf(0.0, 0.0, 0.0) else cam1InCam0.p
            for (v in 0 until h) for (u in 0 until w) {
                k.unproject(u.toDouble(), v.toDouble(), ray)
                val d = if (cam == 0) ray.copyOf() else Q.rotate(cam1InCam0.q, ray)
                // camera frame: x right, y down, z forward
                val tWall = if (d[2] > 1e-6) (wallZ - o[2]) / d[2] else Double.MAX_VALUE
                val tFloor = if (d[1] > 1e-6) (floorDown - o[1]) / d[1] else Double.MAX_VALUE
                var g = 0
                if (tWall < tFloor && tWall < 50) g = texture(o[0] + d[0] * tWall + 100, o[1] + d[1] * tWall + 100)
                else if (tFloor < 50) g = texture(o[0] + d[0] * tFloor + 300, o[2] + d[2] * tFloor + 300)
                if (noise != null) g = (g + noise.nextGaussian() * 3).toInt().coerceIn(0, 255)
                out[cam][v * w + u] = g.toByte()
            }
        }
        return out[0] to out[1]
    }

    @Test fun pointsFromRenderedPicturesLieOnTheWallAndTheFloor() {
        val (left, right) = render(1.8, 1.4, Random(3))
        val sp = StereoPoints(rig); val pts = FloatArray(900)
        val n = sp.find(left, right, pts)
        assertTrue("found $n points of ${sp.lastCorners} corners", n > 120)
        // Each point against the truth along its own sight line: where that line meets the wall or the floor.
        var onWall = 0; var onFloor = 0; val err = ArrayList<Double>()
        for (i in 0 until n) {
            val x = pts[i * 3].toDouble(); val y = pts[i * 3 + 1].toDouble(); val z = pts[i * 3 + 2].toDouble()
            val d = sqrt(x * x + y * y + z * z)
            val tWall = if (z > 1e-6) 1.8 / (z / d) else Double.MAX_VALUE; val tFloor = if (y > 1e-6) 1.4 / (y / d) else Double.MAX_VALUE
            val truth = minOf(tWall, tFloor); if (tWall < tFloor) onWall++ else onFloor++
            err += abs(d - truth) / truth
        }
        err.sort()
        val report = "wall $onWall, floor $onFloor; distance off by %.1f %% in the middle, %.1f %% at the 90th percentile, %d of $n wrong by over 10 %%".format(err[n / 2] * 100, err[n * 9 / 10] * 100, err.count { it > 0.1 })
        println(report)
        assertTrue(report, onWall > 40 && onFloor > 30 && err[n / 2] < 0.015 && err[n * 9 / 10] < 0.05 && err.count { it > 0.1 } <= n / 30)
    }

    // ------------------------------------------------------------ the room model on a made-up room

    /** A kitchen-sized room: floor at -1.45, a counter top at -0.55 (1.6 x 0.6 m), a wall 2.2 m ahead, and clutter. */
    private fun madeUpRoom(model: RoomModel, floorY: Double = -1.45, frames: Int = 40, seed: Long = 5) {
        val r = Random(seed); val pts = FloatArray(3 * 400)
        // the same spots are seen again and again, each time a little off: fixed spots, per-picture noise
        val floorSpots = Array(500) { doubleArrayOf(-2 + 4 * r.nextDouble(), floorY, -3 + 4 * r.nextDouble()) }
        val topSpots = Array(260) { doubleArrayOf(0.4 + 1.6 * r.nextDouble(), floorY + 0.90, -1.2 + 0.6 * r.nextDouble()) }
        val wallSpots = Array(400) { doubleArrayOf(-1.5 + 3 * r.nextDouble(), floorY + 0.3 + 1.8 * r.nextDouble(), -2.2) }
        for (f in 0 until frames) {
            var n = 0
            fun put(s: DoubleArray, sigma: Double) { pts[n * 3] = (s[0] + r.nextGaussian() * sigma).toFloat(); pts[n * 3 + 1] = (s[1] + r.nextGaussian() * sigma).toFloat(); pts[n * 3 + 2] = (s[2] + r.nextGaussian() * sigma).toFloat(); n++ }
            repeat(120) { put(floorSpots[r.nextInt(floorSpots.size)], 0.015) }
            repeat(90) { put(topSpots[r.nextInt(topSpots.size)], 0.012) }
            repeat(120) { put(wallSpots[r.nextInt(wallSpots.size)], 0.015) }
            // wrong matches: anywhere, including under the floor
            repeat(30) { put(doubleArrayOf(-3 + 6 * r.nextDouble(), floorY - 1 + 3.5 * r.nextDouble(), -3 + 5 * r.nextDouble()), 0.0) }
            model.add(pts, n, f)
        }
    }

    @Test fun theFloorOfAMadeUpRoomIsFoundWithinThreeCentimetres() {
        val m = RoomModel(); madeUpRoom(m)
        m.analyse(0.0); m.analyse(0.0); val f = m.analyse(0.0)
        assertEquals(-1.45, f.floorY, 0.03)
        assertNotNull(f.surfaces.firstOrNull { it.kind == RoomSurface.Kind.FLOOR })
    }

    @Test fun theCounterTopAndTheWallAreFound() {
        val m = RoomModel(); madeUpRoom(m)
        // a wall is reported the third time it is found, a table top the fourth
        assertTrue(m.analyse(0.0).surfaces.none { it.kind != RoomSurface.Kind.FLOOR })
        m.analyse(0.0); m.analyse(0.0); val f = m.analyse(0.0)
        val top = f.surfaces.firstOrNull { it.kind == RoomSurface.Kind.TABLE }
        assertNotNull("surfaces: " + f.surfaces.map { it.kind.toString() + " at " + it.cy }, top)
        assertEquals(-0.55, top!!.cy, 0.03)
        assertEquals("its middle, across", 1.2, top.cx, 0.12); assertEquals("its middle, in depth", -0.9, top.cz, 0.08)
        assertEquals("half its long side", 0.8, top.halfU, 0.12); assertEquals("half its short side", 0.3, top.halfV, 0.08)
        assertTrue("its normal points up", top.ny > 0.99)
        val wall = f.surfaces.firstOrNull { it.kind == RoomSurface.Kind.WALL }
        assertNotNull(wall)
        assertTrue("the wall faces along z: normal " + wall!!.nx + ", " + wall.nz, abs(wall.nz) > 0.995)
        assertEquals(-2.2, wall.cz, 0.04)
        assertTrue("about 3 m long and 1.8 m high: " + wall.halfU * 2 + " x " + wall.halfV * 2, wall.halfU > 1.2 && wall.halfV > 0.7)
    }

    @Test fun aCounterTopIsNeverTheFloor() {
        // Looking at a counter only (no floor in view yet): its top is 0.55 m under the head. Called the floor, it
        // would lift a game's floor to waist height, so nothing closer than 0.95 m under the head is a floor.
        val m = RoomModel(); val r = Random(2); val pts = FloatArray(3 * 200)
        for (f in 0 until 20) {
            for (i in 0 until 200) { pts[i * 3] = (r.nextDouble() * 1.6).toFloat(); pts[i * 3 + 1] = (-0.55 + r.nextGaussian() * 0.012).toFloat(); pts[i * 3 + 2] = (-1.2 + r.nextDouble() * 0.6).toFloat() }
            m.add(pts, 200, f)
        }
        var f = m.analyse(0.0); f = m.analyse(0.0)
        assertTrue("no floor from the counter alone", f.floorY.isNaN())
        // the glasses are put down on the counter: the head is now 5 cm above it, and it is still not the floor
        f = m.analyse(-0.5); f = m.analyse(-0.5)
        assertTrue(f.floorY.isNaN())
        madeUpRoom(m)
        repeat(4) { f = m.analyse(0.0) }
        assertEquals("the real floor, once seen", -1.45, f.floorY, 0.03)
    }

    @Test fun aRecentreMovesTheRoomAlong() {
        val m = RoomModel(); madeUpRoom(m)
        repeat(4) { m.analyse(0.0) }
        // the wearer sat down 0.4 m and turned a quarter turn, then recentred: the world's origin is the head again
        m.transform(Pose(doubleArrayOf(0.0, 0.4, 0.0), Q.axisAngle(0.0, 1.0, 0.0, Math.PI / 2)))
        val f = m.analyse(0.0)
        assertEquals(-1.05, f.floorY, 0.03)
        val wall = f.surfaces.first { it.kind == RoomSurface.Kind.WALL }
        assertTrue("the wall now faces along x", abs(wall.nx) > 0.995)
    }

    @Test fun whatIsNoLongerSeenIsForgotten() {
        // The wearer walks on: twenty seconds of pictures of somewhere else. The table and wall of the first room are
        // not reported any more (the estimator's errors add up, so they would be shown in the wrong place); the floor,
        // which is the same floor, stays.
        val m = RoomModel(); madeUpRoom(m)
        repeat(4) { m.analyse(0.0) }
        val r = Random(9); val pts = FloatArray(3 * 150)
        for (f in 40 until 40 + RoomModel.WINDOW + 30) {
            for (i in 0 until 150) { pts[i * 3] = (10 + 4 * r.nextDouble()).toFloat(); pts[i * 3 + 1] = (-1.45 + r.nextGaussian() * 0.015).toFloat(); pts[i * 3 + 2] = (-3 + 4 * r.nextDouble()).toFloat() }
            m.add(pts, 150, f)
            if (f % 3 == 0) m.analyse(0.0)
        }
        val f = m.analyse(0.0)
        assertEquals(-1.45, f.floorY, 0.03)
        assertTrue("left behind: " + f.surfaces.map { it.kind }, f.surfaces.none { it.kind != RoomSurface.Kind.FLOOR })
    }

    @Test fun afterPositionWasLostTheFloorIsWhereItWasUnderTheHead() {
        // Position is lost while the wearer walks, and comes back in a world 0.7 m higher than before. The old floor
        // height would now be 0.75 m under the head (above a table); the floor is put 1.45 m under the head again.
        val m = RoomModel(); madeUpRoom(m)
        repeat(6) { m.analyse(0.0) }
        m.regained(0.7)
        val f = m.analyse(0.7)
        assertEquals(0.7 - 1.45, f.floorY, 0.04)
        assertTrue(f.surfaces.none { it.kind != RoomSurface.Kind.FLOOR })
        // and without a floor ever found, none is made up
        val n = RoomModel(); n.regained(0.3)
        assertTrue(n.analyse(0.3).floorY.isNaN())
    }

    @Test fun aRayMeetsTheTableOnlyOverTheTable() {
        val top = RoomSurface(RoomSurface.Kind.TABLE, 1.0, -0.5, -1.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.8, 0.3, 100)
        assertTrue(top.ny > 0.99)
        assertEquals(0.5, top.hit(1.0, 0.0, -1.0, 0.0, -1.0, 0.0), 1e-9)
        assertTrue(top.hit(2.5, 0.0, -1.0, 0.0, -1.0, 0.0).isNaN())
        assertTrue("from below, pointing away", top.hit(1.0, -1.0, -1.0, 0.0, -1.0, 0.0).isNaN())
        val floor = RoomSurface(RoomSurface.Kind.FLOOR, 0.0, -1.4, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 1.0, 1.0, 100)
        assertEquals("the floor has no edge", 1.4, floor.hit(40.0, 0.0, 7.0, 0.0, -1.0, 0.0), 1e-9)
    }

    // ------------------------------------------------------------ a real recording, when one is given

    /**
     * Not part of the automatic tests (recordings are private and stay out of the repository). With
     *   UXR_ROOM_RECORDING=...uxrec  UXR_ROOM_VIO=...-vio.csv  UXR_ROOM_CALIB=...basalt_calib.json
     * set, the recording's pictures go through the same code as on the phone, with the head positions a replay
     * reported, and the floor and surfaces found are printed every ten seconds.
     */
    @Test fun aRecordingIfGiven() {
        val rec = System.getenv("UXR_ROOM_RECORDING"); val vioCsv = System.getenv("UXR_ROOM_VIO"); val calib = System.getenv("UXR_ROOM_CALIB")
        assumeTrue(rec != null && vioCsv != null && calib != null)
        val every = (System.getenv("UXR_ROOM_EVERY") ?: "0.33").toDouble()
        // UXR_ROOM_ROTFIX="x,y,z": the right camera turned by that many degrees first (what a calibration error would look like)
        val r0 = StereoRig.fromBasalt(File(calib!!).readText())
        val fixDeg = System.getenv("UXR_ROOM_ROTFIX")?.split(",")?.map { it.trim().toDouble() }
        val r = if (fixDeg == null) r0 else {
            val x = Math.toRadians(fixDeg[0]); val y = Math.toRadians(fixDeg[1]); val z = Math.toRadians(fixDeg[2]); val ang = sqrt(x * x + y * y + z * z)
            StereoRig(r0.k0, r0.k1, r0.cam0InImu, Pose(r0.cam1InImu.p, Q.normalize(Q.mul(r0.cam1InImu.q, Q.conj(Q.axisAngle(x / ang, y / ang, z / ang, ang))))))
        }
        val sp = StereoPoints(r); val model = RoomModel()
        val rows = File(vioCsv!!).readLines().drop(1).map { it.split(",") }.filter { it.size >= 18 }
        val vt = DoubleArray(rows.size) { rows[it][0].toDouble() }
        val raw = ByteArray(CameraDecoder.IMAGE_BYTES); val left = ByteArray(EyeImage.OUT_BYTES); val right = ByteArray(EyeImage.OUT_BYTES)
        val pts = FloatArray(3 * 400); val world = FloatArray(3 * 400)
        // UXR_ROOM_DUMP=file: every placed point as time, metres below the camera, distance, and its place in the camera's frame
        val dump = System.getenv("UXR_ROOM_DUMP")?.let { java.io.PrintWriter(File(it)) }
        var first = -1L; var pendCounter = -1; var lastUsed = -1e9; var frame = 0; var nextPrint = 10.0; var placed = 0L; var ms = 0L; var epoch = -1
        DataInputStream(File(rec!!).inputStream().buffered(1 shl 20)).use { inp ->
            val head = ByteArray(7); inp.readFully(head)
            while (true) {
                val kind = inp.read(); if (kind < 0) break
                val t = inp.readLong(); val n = inp.readInt()
                if (first < 0) first = t
                if (kind != 1) { inp.skipBytes(n); continue }
                val data = ByteArray(n); inp.readFully(data)
                val s = (t - first) / 1e9
                val meta = CameraDecoder.parseMeta(data) ?: continue
                if (!meta.isRight) {
                    if (s - lastUsed < every) { pendCounter = -1; continue }
                    CameraDecoder.decode(data, raw); EyeImage.transform(raw, false, left); pendCounter = meta.pairCounter; continue
                }
                if (meta.pairCounter != pendCounter) continue
                pendCounter = -1; lastUsed = s
                CameraDecoder.decode(data, raw); EyeImage.transform(raw, true, right)
                var i = vt.binarySearch(s + 0.07); if (i < 0) i = -i - 1; if (i >= vt.size) i = vt.size - 1
                val row = rows[i]
                if (abs(vt[i] - s - 0.07) > 0.15 || row[17] != "1") continue
                if (row[15].toInt() != epoch) { if (epoch >= 0) { println("%.0f s: the estimator restarted, the room is forgotten".format(s)); model.clear() }; epoch = row[15].toInt() }
                val p = doubleArrayOf(row[1].toDouble(), row[2].toDouble(), row[3].toDouble()); val q = doubleArrayOf(row[4].toDouble(), row[5].toDouble(), row[6].toDouble(), row[7].toDouble())
                val t0 = System.nanoTime()
                val n3 = sp.find(left, right, pts)
                ms += (System.nanoTime() - t0) / 1_000_000
                val camInWorld = Pose(Q.rotate(HeadTracker.Z_TO_Y, p), Q.mul(HeadTracker.Z_TO_Y, q)) * r.cam0InImu
                var k = 0
                for (j in 0 until n3) {
                    val d = sqrt((pts[j * 3] * pts[j * 3] + pts[j * 3 + 1] * pts[j * 3 + 1] + pts[j * 3 + 2] * pts[j * 3 + 2]).toDouble())
                    if (d > 4.5) continue
                    val wp = camInWorld.apply(doubleArrayOf(pts[j * 3].toDouble(), pts[j * 3 + 1].toDouble(), pts[j * 3 + 2].toDouble()))
                    world[k * 3] = wp[0].toFloat(); world[k * 3 + 1] = wp[1].toFloat(); world[k * 3 + 2] = wp[2].toFloat(); k++
                    dump?.println("%.2f,%.4f,%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, s, camInWorld.p[1] - wp[1], d, pts[j * 3], pts[j * 3 + 1], pts[j * 3 + 2]))
                }
                model.add(world, k, frame++); placed += k
                val headY = camInWorld.p[1]
                val f = if (frame % 3 == 0) model.analyse(headY, camInWorld.p[0], camInWorld.p[2]) else null
                if (s >= nextPrint) {
                    nextPrint += 10.0
                    val g = f ?: model.analyse(headY, camInWorld.p[0], camInWorld.p[2])
                    println("%.0f s: %d pictures, %d points a picture (%d ms each), %d boxes confirmed; floor %s; %s".format(s, frame, placed / maxOf(1, frame), ms / maxOf(1, frame), g.confirmed,
                        if (g.floorY.isNaN()) "not found" else "%.2f m below the head".format(headY - g.floorY),
                        g.surfaces.filter { it.kind == RoomSurface.Kind.TABLE }.joinToString("; ") { "top %.2f m below the head, %.1f x %.1f m".format(headY - it.cy, it.halfU * 2, it.halfV * 2) } + "; walls: " +
                        g.surfaces.filter { it.kind == RoomSurface.Kind.WALL }.joinToString("; ") { "%.1f m away facing %.0f deg, %.1f x %.1f m".format(abs((it.cx - camInWorld.p[0]) * it.nx + (it.cz - camInWorld.p[2]) * it.nz), Math.toDegrees(Math.atan2(it.nx, it.nz)).let { a -> ((a % 180) + 180) % 180 }, it.halfU * 2, it.halfV * 2) }))
                }
            }
        }
        dump?.close()
    }
}
