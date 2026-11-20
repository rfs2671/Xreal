package dev.ultraxr

import dev.ultraxr.desktop.FocusKey
import dev.ultraxr.desktop.Geometry
import dev.ultraxr.desktop.Home
import dev.ultraxr.desktop.HomeItem
import dev.ultraxr.desktop.HomeModel
import dev.ultraxr.desktop.HomeTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home panel: that its tiles are in the picture and apart, that the d-pad reaches every one of them, and that a ray at a tile finds that tile. */
class HomeTest {
    private val origin = doubleArrayOf(0.0, 0.0, 0.0)
    private fun item(t: String, kind: HomeItem.Kind) = HomeItem(t, kind, "u:$t", 0xFF5B8DEF.toInt())
    private val web = listOf("YouTube", "Xbox Cloud", "Browser", "3D web").map { item(it, HomeItem.Kind.WEB) }
    private val games = listOf("Ball shooter", "Room check", "Place on your floor", "AR cones", "Hand cubes").map { item(it, HomeItem.Kind.GAME) }
    private fun model(section: String, games: List<HomeItem> = this.games, apps: Int = 0, windows: Int = 0, page: Int = 0, pages: Int = 1) = HomeModel(section, web, games,
        (0 until apps).map { item("App $it", HomeItem.Kind.APP) }, page, pages, (0 until windows).map { Triple(it + 1, "Window $it", it % 2 == 0) }, emptyMap(), null,
        emptyList(), false, false, "no position", 80, "7:42 PM", "Wed, Nov 19", { null })
    private val sections = listOf(model("home"), model("home", emptyList()), model("games"), model("apps", apps = Home.APPS_A_PAGE, page = 1, pages = 3), model("apps"), model("windows", windows = 6), model("windows"))

    @Test fun everyTileIsInThePictureAndNoTwoOverlap() {
        for (m in sections) {
            val t = Home.layout(m)
            assertEquals("the sidebar comes first, in every section", listOf("side:home", "side:games", "side:apps", "side:windows", "side:settings"), t.take(Home.SIDE).map { it.id })
            assertEquals("one tile of the sidebar is the section shown", 1, t.take(Home.SIDE).count { it.chosen })
            for (a in t) {
                assertTrue(a.id + " is in the picture", a.x >= 0 && a.y >= 0 && a.x + a.w <= Home.W && a.y + a.h <= Home.H && a.w > 40 && a.h > 40)
                for (b in t) if (a !== b) assertTrue(a.id + " and " + b.id + " overlap", a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y)
            }
            assertEquals("no two tiles have the same name", t.size, t.map { it.id }.toSet().size)
        }
    }

    @Test fun theDirectionsReachEveryTileAndNeverLeaveThePanel() {
        val keys = listOf(FocusKey.LEFT, FocusKey.RIGHT, FocusKey.UP, FocusKey.DOWN)
        for (m in sections) {
            val t = Home.layout(m)
            val seen = HashSet<Int>(); val todo = ArrayDeque(listOf(Home.first(t)))
            while (todo.isNotEmpty()) { val i = todo.removeFirst(); if (!seen.add(i)) continue; for (k in keys) { val n = Home.neighbour(t, i, k); assertTrue(n in t.indices); todo += n } }
            assertEquals("from the first tile every tile of " + m.section + " can be reached", t.indices.toSet(), seen)
            for (i in t.indices) for (k in keys) {
                val n = Home.neighbour(t, i, k); if (n == i) continue
                val a = t[i]; val b = t[n]
                when (k) { FocusKey.LEFT -> assertTrue(b.cx < a.cx); FocusKey.RIGHT -> assertTrue(b.cx > a.cx); FocusKey.UP -> assertTrue(b.cy < a.cy); else -> assertTrue(b.cy > a.cy) }
            }
        }
        val t = Home.layout(model("home"))
        fun id(i: Int): String = t[i].id
        fun at(name: String): Int = t.indexOfFirst { it.id == name }
        assertEquals("the focus begins on the large card", "hero", id(Home.first(t)))
        assertEquals("left of it is the sidebar", "side:home", id(Home.neighbour(t, at("hero"), FocusKey.LEFT)))
        assertEquals("right of it the quick games", "quick:Ball shooter", id(Home.neighbour(t, at("hero"), FocusKey.RIGHT)))
        assertTrue("under it the library", id(Home.neighbour(t, at("hero"), FocusKey.DOWN)).startsWith("lib:"))
        assertEquals("the sidebar is walked downwards", "side:games", id(Home.neighbour(t, at("side:home"), FocusKey.DOWN)))
        assertEquals("and stops at its end", "side:settings", id(Home.neighbour(t, at("side:settings"), FocusKey.DOWN)))
        assertEquals("along the library", "lib:Xbox Cloud", id(Home.neighbour(t, at("lib:YouTube"), FocusKey.RIGHT)))
        assertEquals("a key that is no direction leaves the focus", at("hero"), Home.neighbour(t, at("hero"), FocusKey.SELECT))
        assertEquals("a section with nothing in it begins on the sidebar", 0, Home.first(Home.layout(model("windows"))))
    }

    @Test fun aRayAtATileFindsThatTileAndBesideThePanelNothing() {
        for (yaw in listOf(0.0, Math.toRadians(70.0), Math.toRadians(-150.0))) for (m in sections) {
            val t = Home.layout(m)
            for ((i, a) in t.withIndex()) for ((fx, fy) in listOf(0.5 to 0.5, 0.1 to 0.1, 0.9 to 0.9)) {
                val x = ((a.x + a.w * fx) / Home.W - 0.5) * Home.WIDTH; val y = (0.5 - (a.y + a.h * fy) / Home.H) * Home.HEIGHT
                val c = Geometry.centre(origin, yaw, Home.PITCH, Home.DISTANCE)
                val cy = Math.cos(yaw); val sy = Math.sin(yaw); val cp = Math.cos(Home.PITCH); val sp = Math.sin(Home.PITCH)
                val p = doubleArrayOf(c[0] + cy * x + sy * sp * y, c[1] + cp * y, c[2] - sy * x + cy * sp * y)
                assertEquals(a.id, i, Home.hit(t, origin, p, origin, yaw))
            }
            assertNull("above the panel", Home.hit(t, origin, Geometry.direction(yaw, Math.toRadians(20.0)), origin, yaw))
            assertNull("behind the wearer", Home.hit(t, origin, Geometry.direction(yaw + Math.PI, 0.0), origin, yaw))
        }
    }

    @Test fun thePanelIsInViewAndItsTextIsLargeEnoughToRead() {
        // half of what an eye sees: tan 0.3875 across, 0.2191 up and down
        assertTrue(Math.atan(Home.WIDTH / 2 / Home.DISTANCE) < Math.atan(0.3875))
        assertTrue(Math.abs(Home.PITCH) + Math.atan(Home.HEIGHT / 2 / Home.DISTANCE) < Math.atan(0.2191))
        // a picture pixel is about a pixel of the glasses (1920 across 2 atan 0.3976), so that text is as sharp as they can show it
        val pixelsADegree = Home.W / Math.toDegrees(2 * Math.atan(Home.WIDTH / 2 / Home.DISTANCE)); val glasses = 1920 / Math.toDegrees(2 * Math.atan(0.3976))
        assertEquals(glasses, pixelsADegree, glasses * 0.1)
        assertTrue(Home.layout(model("apps", apps = Home.APPS_A_PAGE, page = 1, pages = 3)).size <= Home.SIDE + Home.GRID)
    }

    @Test fun aTileKnowsWhatAPressDoes() {
        val t = Home.layout(model("home"))
        fun of(name: String): HomeTile = t.first { it.id == name }
        assertEquals("sec:games", of("side:games").action); assertEquals("settings", of("side:settings").action)
        assertEquals("item", of("lib:Browser").action); assertEquals("Browser", of("lib:Browser").item?.title)
        assertEquals("sec:games", of("quick:more").action); assertEquals("sys:recentre", of("sys:recentre").action); assertEquals("room", of("room").action)
        val apps = Home.layout(model("apps", apps = Home.APPS_A_PAGE, page = 1, pages = 3)).map { it.action }
        assertTrue("a middle page of apps leads both ways", "apps:prev" in apps && "apps:next" in apps)
        assertTrue(Home.layout(model("windows", windows = 3)).map { it.action }.containsAll(listOf("win:1", "win:2", "win:3", "sys:closeall")))
    }
}
