package dev.ultraxr

import dev.ultraxr.desktop.HomeGrid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-glasses home: where icons go, and that a look at an icon finds that icon. */
class HomeGridTest {
    @Test fun rowsAlternateFourAndFiveAndNeverLeaveOneIconAlone() {
        assertArrayEquals(intArrayOf(), HomeGrid.rows(0))
        assertArrayEquals(intArrayOf(3), HomeGrid.rows(3))
        assertArrayEquals(intArrayOf(4, 2), HomeGrid.rows(6))
        assertArrayEquals(intArrayOf(4, 5), HomeGrid.rows(9))
        assertArrayEquals(intArrayOf(4, 4, 2), HomeGrid.rows(10))     // not 4, 5, 1
        assertArrayEquals(intArrayOf(4, 5, 4), HomeGrid.rows(13))
        for (n in 1..20) assertEquals(n, HomeGrid.rows(n).sum())
    }

    @Test fun everyIconIsInsideThePictureAndClearOfItsNeighbours() {
        for (n in 1..HomeGrid.MAX) {
            assertTrue("three rows at most", HomeGrid.rows(n).size <= 3)
            val c = HomeGrid.centres(n)
            assertEquals(n, c.size)
            for (p in c) {
                assertTrue(p[0] - HomeGrid.ICON_R > 0 && p[0] + HomeGrid.ICON_R < HomeGrid.PX_W)
                assertTrue("room for the label under the last row too", p[1] - HomeGrid.ICON_R > 60 && p[1] + HomeGrid.ICON_R + 70 < HomeGrid.PX_H)
            }
            for (i in c.indices) for (j in i + 1 until c.size)
                assertTrue(Math.hypot((c[i][0] - c[j][0]).toDouble(), (c[i][1] - c[j][1]).toDouble()) > 2.3 * HomeGrid.ICON_R)
        }
    }

    @Test fun lookingAtAnIconOrItsLabelFindsItAndLookingBetweenFindsNothing() {
        val n = 9; val c = HomeGrid.centres(n)
        fun metres(px: Float, py: Float) = doubleArrayOf((px / HomeGrid.PX_W - 0.5) * HomeGrid.WIDTH_M, (0.5 - py / HomeGrid.PX_H) * HomeGrid.HEIGHT_M)
        for (i in 0 until n) {
            var m = metres(c[i][0], c[i][1]); assertEquals(i, HomeGrid.hit(m[0], m[1], n))
            m = metres(c[i][0], c[i][1] + HomeGrid.ICON_R + 40f); assertEquals("its label", i, HomeGrid.hit(m[0], m[1], n))
        }
        // far outside the grid: nothing
        assertNull(HomeGrid.hit(-HomeGrid.WIDTH_M, 0.0, n)); assertNull(HomeGrid.hit(0.0, HomeGrid.HEIGHT_M, n))
        // a look that lands on one icon never reports another
        for (i in 0 until n) for (dx in listOf(-60f, 0f, 60f)) for (dy in listOf(-60f, 0f, 60f)) {
            val m = metres(c[i][0] + dx, c[i][1] + dy); assertEquals(i, HomeGrid.hit(m[0], m[1], n))
        }
    }
}
