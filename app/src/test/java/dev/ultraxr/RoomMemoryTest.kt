package dev.ultraxr

import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import dev.ultraxr.core.RoomFindings
import dev.ultraxr.core.RoomMemory
import dev.ultraxr.core.RoomSurface
import dev.ultraxr.core.RoomSurface.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The room's surfaces kept with the saved map ([RoomMemory]), on a made-up room. */
class RoomMemoryTest {
    private fun yUp(x: Double, y: Double, z: Double, yawDeg: Double) = Pose(doubleArrayOf(x, y, z), Q.axisAngle(0.0, 1.0, 0.0, Math.toRadians(yawDeg)))
    /** A wall 2.3 m high standing on the floor (y = -1.5) from (x0, z0) to (x1, z1). */
    private fun wall(x0: Double, z0: Double, x1: Double, z1: Double, support: Int = 200): RoomSurface {
        val l = Math.hypot(x1 - x0, z1 - z0)
        return RoomSurface(Kind.WALL, (x0 + x1) / 2, -1.5 + 1.15, (z0 + z1) / 2, (x1 - x0) / l, 0.0, (z1 - z0) / l, 0.0, 1.0, 0.0, l / 2, 1.15, support)
    }
    private fun table(x: Double, z: Double, y: Double = -0.75) = RoomSurface(Kind.TABLE, x, y, z, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.6, 0.4, 120)
    private fun found(vararg s: RoomSurface, floor: Double = -1.5) = RoomFindings(floor, 300, s.toList(), 1000, 500)
    private fun dist(a: RoomSurface, b: RoomSurface) = Math.sqrt((a.cx - b.cx) * (a.cx - b.cx) + (a.cy - b.cy) * (a.cy - b.cy) + (a.cz - b.cz) * (a.cz - b.cz))

    @Test fun whatOneSessionFoundIsThereInTheNextWhereItStood() {
        val one = RoomMemory(); val map1 = yUp(0.4, 0.1, -0.3, 25.0)
        val w = wall(-2.0, -1.5, 2.0, -1.5); val t = table(1.0, 0.5)
        one.see(map1, found(w, t)); assertTrue(one.changed)
        val two = RoomMemory(); assertTrue(two.read(one.write()))
        // the next session's world lies elsewhere: where the first session's things are in it
        val map2 = yUp(-1.2, -0.2, 2.0, -70.0); val carry = map2 * map1.inverse()
        val (shown, conf) = two.shown(map2, null)
        assertEquals(2, shown!!.surfaces.size); assertTrue(conf.all { it == RoomMemory.KEPT })
        val w2 = shown.surfaces.first { it.kind == Kind.WALL }; val t2 = shown.surfaces.first { it.kind == Kind.TABLE }
        assertEquals(0.0, dist(RoomMemory.moved(carry, w), w2), 2e-3); assertEquals(0.0, dist(RoomMemory.moved(carry, t), t2), 2e-3)
        assertEquals(-1.5 + carry.p[1], shown.floorY, 2e-3)
        assertEquals(w.halfU, w2.halfU, 2e-3)
    }

    @Test fun aKeptWallFoundAgainIsOneWallAndFullyBelieved() {
        val one = RoomMemory(); val map = yUp(0.0, 0.0, 0.0, 0.0)
        one.see(map, found(wall(-2.0, -1.5, 2.0, -1.5), wall(2.0, -1.5, 2.0, 1.5)))
        val two = RoomMemory(); two.read(one.write())
        // this session sees the first wall again, 6 cm off and a little shorter, and a wall nobody had seen
        val again = wall(-1.5, -1.44, 1.8, -1.44); val fresh = wall(-2.0, 1.5, 2.0, 1.5)
        val (shown, conf) = two.shown(map, found(again, fresh))
        val walls = shown!!.surfaces.filter { it.kind == Kind.WALL }
        assertEquals(3, walls.size)
        assertEquals(RoomMemory.BOTH, conf[shown.surfaces.indexOf(again)], 0.0)
        assertEquals(RoomMemory.LIVE, conf[shown.surfaces.indexOf(fresh)], 0.0)
        assertEquals(1, conf.count { it == RoomMemory.KEPT })
        val apart = two.agreement(map, found(again, fresh))
        assertEquals(1, apart.size); assertEquals(0.06, apart[0], 1e-6)
        // and what is kept afterwards is three walls, not four
        two.see(map, found(again, fresh))
        assertEquals(3, two.kept.count { it.kind == Kind.WALL })
    }

    @Test fun withNoMapOrNothingKeptWhatIsFoundIsShownAsItIs() {
        val m = RoomMemory(); val f = found(wall(-2.0, -1.5, 2.0, -1.5))
        assertTrue(m.shown(null, f).first === f)
        assertTrue(m.shown(yUp(0.0, 0.0, 0.0, 0.0), f).first === f)
        assertFalse(m.read("nonsense"))
    }

    /** A depth picture of a room made of [walls]: how far the camera at the origin sees along the line to a point. */
    private fun picture(walls: List<RoomSurface>, hole: Double = Double.NaN): (DoubleArray) -> Double = { p ->
        val d = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); var best = Double.MAX_VALUE
        for (w in walls) { val t = w.hit(0.0, 0.0, 0.0, p[0] / d, p[1] / d, p[2] / d); if (!t.isNaN() && t < best) best = t }
        if (best == Double.MAX_VALUE) hole else best
    }

    @Test fun aKeptWallThatIsSeenThroughIsDroppedAndOneThatStandsIsNot() {
        val m = RoomMemory(); val map = yUp(0.0, 0.0, 0.0, 0.0)
        val near = wall(-1.5, -1.5, 1.5, -1.5); val far = wall(-2.0, -3.0, 2.0, -3.0); val side = wall(1.8, -3.0, 1.8, 0.5)
        m.see(map, found(near, far, side)); val kept = RoomMemory(); kept.read(m.write())
        // the room now: the near wall has gone (a partition taken away); behind it the far wall is seen
        val now = picture(listOf(far, side))
        var dropped = 0; var pictures = 0
        while (pictures < 30 && dropped == 0) { dropped += kept.look(Pose(), now); pictures++ }
        assertEquals(1, dropped); assertEquals(RoomMemory.LOOK_PICTURES, pictures)
        assertEquals(2, kept.kept.count { it.kind == Kind.WALL })
        assertTrue(kept.kept.none { Math.abs(it.cz + 1.5) < 0.01 && Math.abs(it.cx) < 0.01 })
        assertEquals(2, kept.shown(map, null).first!!.surfaces.size)
        // looked at for long, the walls that stand stay
        repeat(100) { assertEquals(0, kept.look(Pose(), now)) }
    }

    @Test fun picturesWithNoAnswerOrOneBadPictureTakeNothingAway() {
        val m = RoomMemory(); val map = yUp(0.0, 0.0, 0.0, 0.0); val w = wall(-1.5, -1.5, 1.5, -1.5)
        m.see(map, found(w)); val kept = RoomMemory(); kept.read(m.write())
        val there = picture(listOf(w)); val blank: (DoubleArray) -> Double = { Double.NaN }; val wrong: (DoubleArray) -> Double = { 6.0 }
        repeat(50) { assertEquals(0, kept.look(Pose(), blank)) }
        // seven bad pictures among good ones are not eight in a row's worth
        repeat(10) { repeat(3) { kept.look(Pose(), wrong) }; repeat(2) { kept.look(Pose(), there) } }
        assertEquals(1, kept.kept.count { it.kind == Kind.WALL })
    }

    @Test fun lookingAgainAtTheSameRoomDoesNotChangeWhatIsKept() {
        val m = RoomMemory(); val map = yUp(0.2, 0.0, 0.1, 10.0); val f = found(wall(-2.0, -1.5, 2.0, -1.5), table(0.5, 0.2))
        m.see(map, f); m.write()
        repeat(50) { m.see(map, f) }
        assertEquals(1, m.kept.count { it.kind == Kind.WALL }); assertEquals(1, m.kept.count { it.kind == Kind.TABLE })
        val w = m.kept.first { it.kind == Kind.WALL }; assertEquals(2.0, w.halfU, 1e-3)
    }
}
