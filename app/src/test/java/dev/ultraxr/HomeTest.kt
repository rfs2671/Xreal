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

/** The home (the wall of covers): that its tiles are in the picture and apart, that the d-pad reaches every one of them, and that a ray at a tile finds that tile. */
class HomeTest {
    private val origin = doubleArrayOf(0.0, 0.0, 0.0)
    private fun item(t: String, kind: HomeItem.Kind) = HomeItem(t, kind, "u:$t", 0xFF5B8DEF.toInt())
    private val web = listOf("YouTube", "Xbox Cloud", "Browser", "3D web").map { item(it, HomeItem.Kind.WEB) }
    private val games = listOf("Ball shooter", "Room check", "Place on your floor", "AR cones", "Hand cubes").map { item(it, HomeItem.Kind.GAME) }
    private fun model(section: String, games: List<HomeItem> = this.games, apps: Int = 0, windows: Int = 0, page: Int = 0, pages: Int = 1) = HomeModel(section, web, games,
        (0 until apps).map { item("App $it", HomeItem.Kind.APP) }, page, pages, (0 until windows).map { Triple(it + 1, "Window $it", it % 2 == 0) }, emptyMap(), null,
        emptyList(), false, false, "no position", 80, "7:42 PM", "Wed, Nov 19", { null })
    private val sections = listOf(model("home"), model("home", emptyList()), model("games"), model("apps", apps = Home.APPS_A_PAGE, page = 1, pages = 3), model("apps"), model("windows", windows = 6), model("windows", windows = 2), model("windows"))
    private fun shown(t: List<HomeTile>) = t.filter { it.w > 0f }

    @Test fun everyTileInViewIsInThePictureAndNoTwoOverlap() {
        for (m in sections) for (centre in 0..8) {
            val t = Home.layout(m, centre)
            assertEquals("the sections come first, in every section", listOf("side:home", "side:games", "side:apps", "side:windows", "side:settings"), t.take(Home.SIDE).map { it.id })
            assertEquals("one of them is the section shown", 1, t.take(Home.SIDE).count { it.chosen })
            val v = shown(t)
            for (a in v) {
                assertTrue(a.id + " is in the picture", a.x >= 0 && a.y >= 0 && a.x + a.w <= Home.W && a.y + a.h <= Home.H && a.w > 40 && a.h > 40)
                for (b in v) if (a !== b) assertTrue(a.id + " and " + b.id + " overlap", a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y)
            }
            assertEquals("no two tiles have the same name", t.size, t.map { it.id }.toSet().size)
            val n = Home.coverCount(t)
            assertTrue("at most five covers are in view", v.count { it.kind == HomeTile.Kind.ART || it.kind == HomeTile.Kind.PLAIN } <= 2 * Home.EACH_SIDE + 1)
            if (n > 0) {      // the cover in the middle is the large one, in the middle
                val mid = t[Home.SIDE + centre.coerceIn(0, n - 1)]
                assertEquals(Home.W / 2f, mid.cx, 0.5f); assertEquals(Home.COVER_W * Home.COVER_FOCUS, mid.w, 0.5f)
            }
        }
    }

    @Test fun theDirectionsReachEveryTileAndKeepToTheirRows() {
        val keys = listOf(FocusKey.LEFT, FocusKey.RIGHT, FocusKey.UP, FocusKey.DOWN)
        for (m in sections) {
            val t = Home.layout(m)
            val seen = HashSet<Int>(); val todo = ArrayDeque(listOf(Home.first(t)))
            while (todo.isNotEmpty()) { val i = todo.removeFirst(); if (!seen.add(i)) continue; for (k in keys) { val n = Home.neighbour(t, i, k, maxOf(0, i - Home.SIDE)); assertTrue(n in t.indices); todo += n } }
            assertEquals("from the first tile every tile of " + m.section + " can be reached", t.indices.toSet(), seen)
        }
        val t = Home.layout(model("home"), 2)
        fun id(i: Int): String = t[i].id
        fun at(name: String): Int = t.indexOfFirst { it.id == name }
        assertEquals("the focus begins on the cover in the middle", "item:Browser", id(Home.first(t, 2)))
        assertEquals("right of it is the next cover", "item:3D web", id(Home.neighbour(t, at("item:Browser"), FocusKey.RIGHT, 2)))
        assertEquals("the wall goes round: left of the first is the last", "item:Hand cubes", id(Home.neighbour(t, at("item:YouTube"), FocusKey.LEFT, 0)))
        assertEquals("and right of the last the first", "item:YouTube", id(Home.neighbour(t, at("item:Hand cubes"), FocusKey.RIGHT, 8)))
        assertEquals("above the covers is the section shown", "side:home", id(Home.neighbour(t, at("item:Browser"), FocusKey.UP, 2)))
        assertEquals("the sections are walked along", "side:games", id(Home.neighbour(t, at("side:home"), FocusKey.RIGHT, 2)))
        assertEquals("and stop at their end", "side:settings", id(Home.neighbour(t, at("side:settings"), FocusKey.RIGHT, 2)))
        assertEquals("down from a section is the cover in the middle", "item:Browser", id(Home.neighbour(t, at("side:apps"), FocusKey.DOWN, 2)))
        assertEquals("under the covers are the actions", "sys:recentre", id(Home.neighbour(t, at("item:Browser"), FocusKey.DOWN, 2)))
        assertEquals("with the room to their left", "room", id(Home.neighbour(t, at("sys:recentre"), FocusKey.LEFT, 2)))
        assertEquals("and up from them the middle cover again", "item:Browser", id(Home.neighbour(t, at("sys:closeall"), FocusKey.UP, 2)))
        assertEquals("a key that is no direction leaves the focus", at("item:Browser"), Home.neighbour(t, at("item:Browser"), FocusKey.SELECT, 2))
        val few = Home.layout(model("windows", windows = 2))
        assertEquals("a short wall does not go round", "win:1", few[Home.neighbour(few, Home.SIDE, FocusKey.LEFT, 0)].id)
        assertEquals("a section with nothing in it begins on its own tile", "side:windows", Home.layout(model("windows")).let { it[Home.first(it)].id })
    }

    @Test fun aRayAtATileFindsThatTileAndBesideTheHomeNothing() {
        for (yaw in listOf(0.0, Math.toRadians(70.0), Math.toRadians(-150.0))) for (m in sections) {
            val t = Home.layout(m, 1)
            for ((i, a) in t.withIndex()) for ((fx, fy) in listOf(0.5 to 0.5, 0.1 to 0.1, 0.9 to 0.9)) {
                if (a.w <= 0f) continue
                val x = ((a.x + a.w * fx) / Home.W - 0.5) * Home.WIDTH; val y = (0.5 - (a.y + a.h * fy) / Home.H) * Home.HEIGHT
                val c = Geometry.centre(origin, yaw, Home.PITCH, Home.DISTANCE)
                val cy = Math.cos(yaw); val sy = Math.sin(yaw); val cp = Math.cos(Home.PITCH); val sp = Math.sin(Home.PITCH)
                val p = doubleArrayOf(c[0] + cy * x + sy * sp * y, c[1] + cp * y, c[2] - sy * x + cy * sp * y)
                assertEquals(a.id, i, Home.hit(t, origin, p, origin, yaw))
            }
            assertNull("above the home", Home.hit(t, origin, Geometry.direction(yaw, Math.toRadians(20.0)), origin, yaw))
            assertNull("behind the wearer", Home.hit(t, origin, Geometry.direction(yaw + Math.PI, 0.0), origin, yaw))
        }
    }

    @Test fun theHomeIsInViewAndTheWallCurvesRoundTheWearer() {
        // half of what an eye sees: tan 0.3875 across, 0.2191 up and down
        assertTrue(Home.WIDTH / 2 / Home.DISTANCE < 0.3875)
        assertTrue(Math.abs(Home.PITCH) + Math.atan(Home.HEIGHT / 2 / Home.DISTANCE) < Math.atan(0.2191))
        // a picture pixel is about a pixel of the glasses (1920 across 2 atan 0.3976), so that text is as sharp as they can show it
        val pixelsADegree = Home.W / Math.toDegrees(2 * Math.atan(Home.WIDTH / 2 / Home.DISTANCE)); val glasses = 1920 / Math.toDegrees(2 * Math.atan(0.3976))
        assertEquals(glasses, pixelsADegree, glasses * 0.1)
        // the middle cover is the nearest and faces the wearer; its neighbours stand back, smaller, turned inwards
        assertEquals(0.0, Home.coverTurn(0.0), 1e-9); assertTrue(Home.coverDepth(0.0) > Home.coverDepth(1.0)); assertTrue(Home.coverDepth(1.0) > Home.coverDepth(2.0))
        assertTrue(Home.coverTurn(-1.0) > 0.3 && Home.coverTurn(1.0) < -0.3); assertEquals(Home.coverTurn(-2.0), -Home.coverTurn(2.0), 1e-9)
        assertTrue(Home.coverScale(0.0) > Home.coverScale(1.0)); assertEquals(1.0, Home.coverScale(2.0), 1e-9)
        assertTrue(Home.coverAlpha(0.0) > Home.coverAlpha(2.0)); assertEquals(0.0, Home.coverAlpha(2.6), 1e-9)
        // round a wall of nine, the cover before the first is the last
        assertEquals(-1.0, Home.slot(8, 0.0, 9), 1e-9); assertEquals(1.0, Home.slot(0, 8.0, 9), 1e-9); assertEquals(3.0, Home.slot(3, 0.0, 4), 1e-9)
    }

    @Test fun aTileKnowsWhatAPressDoes() {
        val t = Home.layout(model("home"))
        fun of(name: String): HomeTile = t.first { it.id == name }
        assertEquals("sec:games", of("side:games").action); assertEquals("settings", of("side:settings").action)
        assertEquals("item", of("item:Browser").action); assertEquals("Browser", of("item:Browser").item?.title)
        assertEquals("sys:recentre", of("sys:recentre").action); assertEquals("room", of("room").action)
        val apps = Home.layout(model("apps", apps = Home.APPS_A_PAGE, page = 1, pages = 3)).map { it.action }
        assertTrue("a middle page of apps leads both ways", "apps:prev" in apps && "apps:next" in apps)
        assertTrue(Home.layout(model("windows", windows = 3)).map { it.action }.containsAll(listOf("win:1", "win:2", "win:3", "sys:closeall")))
    }
}
