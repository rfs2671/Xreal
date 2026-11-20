package dev.ultraxr

import dev.ultraxr.core.Furniture
import dev.ultraxr.core.RoomSurface
import dev.ultraxr.core.RoomSurface.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Things standing in the room found from surface cells ([Furniture]), on made-up rooms. */
class FurnitureTest {
    private val floor = -1.5
    /** The surface cells (5 cm) of a box standing on the floor: its sides and top. */
    private fun box(cx: Double, cz: Double, sx: Double, sz: Double, height: Double, yawDeg: Double = 0.0, r: Random = Random(1)): ArrayList<Double> {
        val out = ArrayList<Double>(); val c = Math.cos(Math.toRadians(yawDeg)); val s = Math.sin(Math.toRadians(yawDeg)); val v = 0.05
        fun put(u: Double, y: Double, w: Double) { out.add(cx + c * u + s * w + r.nextGaussian() * 0.01); out.add(floor + y); out.add(cz - s * u + c * w + r.nextGaussian() * 0.01) }
        var u = -sx / 2
        while (u <= sx / 2) { var w = -sz / 2
            while (w <= sz / 2) {
                put(u, height, w)                                    // the top
                if (Math.abs(Math.abs(u) - sx / 2) < v || Math.abs(Math.abs(w) - sz / 2) < v) { var y = 0.15; while (y < height) { put(u, y, w); y += v } }
                w += v }
            u += v }
        return out
    }
    private fun wallCells(x0: Double, z0: Double, x1: Double, z1: Double): ArrayList<Double> {
        val out = ArrayList<Double>(); val l = Math.hypot(x1 - x0, z1 - z0); var a = 0.0
        while (a <= l) { var y = 0.1; while (y < 2.2) { out.add(x0 + (x1 - x0) * a / l); out.add(floor + y); out.add(z0 + (z1 - z0) * a / l); y += 0.05 }; a += 0.05 }
        return out
    }
    private fun wall(x0: Double, z0: Double, x1: Double, z1: Double): RoomSurface {
        val l = Math.hypot(x1 - x0, z1 - z0)
        return RoomSurface(Kind.WALL, (x0 + x1) / 2, floor + 1.15, (z0 + z1) / 2, (x1 - x0) / l, 0.0, (z1 - z0) / l, 0.0, 1.0, 0.0, l / 2, 1.15, 200)
    }

    @Test fun aTableASeatAndACupboardAreFoundWithTheirSizesAndTheWallIsNot() {
        val cells = ArrayList<Double>()
        cells += box(0.5, -1.0, 1.2, 0.7, 0.75)                 // a table
        cells += box(-1.2, 0.3, 0.5, 0.5, 0.45)                 // a seat
        cells += box(1.6, 1.2, 0.9, 0.45, 1.8, yawDeg = 30.0)   // a cupboard, turned
        cells += wallCells(-2.5, -2.0, 2.5, -2.0)
        val found = Furniture.detect(cells.toDoubleArray(), floor, listOf(wall(-2.5, -2.0, 2.5, -2.0)))
        assertEquals(found.joinToString { it.label + " " + it.cells }, 3, found.size)
        val table = found.first { it.label == "table" }; val seat = found.first { it.label == "low" }; val tall = found.first { it.label == "tall" }
        assertEquals(0.5, table.cx, 0.08); assertEquals(-1.0, table.cz, 0.08); assertEquals(floor + 0.75, table.top, 0.06)
        assertEquals(0.6, maxOf(table.hx, table.hz), 0.06); assertEquals(0.35, minOf(table.hx, table.hz), 0.06)
        assertEquals(-1.2, seat.cx, 0.08); assertEquals(floor + 0.45, seat.top, 0.06)
        assertEquals(1.6, tall.cx, 0.1); assertEquals(1.2, tall.cz, 0.1); assertEquals(floor + 1.8, tall.top, 0.08)
        // the turned cupboard's box is turned with it: not much larger than the cupboard
        assertTrue("footprint " + tall.hx * tall.hz * 4, tall.hx * tall.hz * 4 < 0.9 * 0.45 * 1.6)
        assertTrue(table.holds(0.5, floor + 0.4, -1.0)); assertTrue(!table.holds(-1.2, floor + 0.4, 0.3)); assertTrue(!table.holds(0.5, floor + 1.2, -1.0))
    }

    @Test fun aFewStrayCellsAndAThingTooSmallAreNoFurniture() {
        val cells = ArrayList<Double>(); val r = Random(3)
        repeat(60) { cells.add(r.nextDouble() * 4 - 2); cells.add(floor + 0.2 + r.nextDouble() * 1.5); cells.add(r.nextDouble() * 4 - 2) }      // clutter in the air
        cells += box(0.0, 0.0, 0.15, 0.15, 0.4)                 // a bottle on the floor
        assertEquals(0, Furniture.detect(cells.toDoubleArray(), floor, emptyList()).size)
        assertEquals(0, Furniture.detect(box(0.0, 0.0, 1.0, 1.0, 0.8).toDoubleArray(), Double.NaN, emptyList()).size)
    }

    @Test fun theSameThingSeenTwiceIsTheSame() {
        val a = Furniture.detect(box(0.5, -1.0, 1.2, 0.7, 0.75).toDoubleArray(), floor, emptyList())[0]
        val b = Furniture.detect(box(0.56, -0.95, 1.1, 0.7, 0.78, r = Random(9)).toDoubleArray(), floor, emptyList())[0]
        val c = Furniture.detect(box(2.5, 1.0, 1.2, 0.7, 0.75).toDoubleArray(), floor, emptyList())[0]
        assertTrue(Furniture.same(a, b)); assertTrue(!Furniture.same(a, c))
    }
}
