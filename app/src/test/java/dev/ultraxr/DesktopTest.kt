package dev.ultraxr

import dev.ultraxr.desktop.BarButton
import dev.ultraxr.desktop.Desktop
import dev.ultraxr.desktop.DesktopWindow
import dev.ultraxr.desktop.FollowAnchor
import dev.ultraxr.desktop.Geometry
import dev.ultraxr.desktop.HomeApps
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTest {
    private val origin = doubleArrayOf(0.0, 0.0, 0.0)

    @Test fun aWindowStraightAheadIsHitInItsCentreAndMissedBesideIt() {
        val d = Desktop(); val w = d.add("A", "https://a.example")!!
        val h = d.hit(origin, Geometry.direction(0.0, 0.0), origin)!!
        assertEquals(w.id, h.window.id); assertEquals(0.5, h.u, 1e-6); assertEquals(0.5, h.v, 1e-6); assertFalse(h.onBar)
        assertEquals(w.distance, h.distance, 1e-6)
        assertNull(d.hit(origin, Geometry.direction(Math.toRadians(40.0), 0.0), origin))
        assertNull("looking away from it", d.hit(origin, doubleArrayOf(0.0, 0.0, 1.0), origin))
    }

    @Test fun cornersMapToTheRightTextureCoordinates() {
        val d = Desktop(); val w = d.add("A", "u")!!
        // a ray through a point 40% of the width to the right and 40% of the height up from the centre
        val target = doubleArrayOf(0.4 * w.width, 0.4 * w.height, -w.distance)
        val h = d.hit(origin, target, origin)!!
        assertEquals(0.9, h.u, 1e-6); assertEquals(0.1, h.v, 1e-6)
    }

    @Test fun titleBarAndItsButtonsAreHitAboveTheContent() {
        val d = Desktop(); val w = d.add("A", "u")!!
        fun ray(u: Double) = doubleArrayOf((u - 0.5) * w.width, w.height / 2 + w.barHeight / 2, -w.distance)
        val drag = d.hit(origin, ray(0.3), origin)!!
        assertTrue(drag.onBar); assertNull(drag.barButton)
        assertEquals(BarButton.CLOSE, d.hit(origin, ray(0.97), origin)!!.barButton)
        assertEquals(BarButton.MAXIMIZE, d.hit(origin, ray(0.90), origin)!!.barButton)
        assertEquals(BarButton.MINIMIZE, d.hit(origin, ray(0.83), origin)!!.barButton)
        assertEquals(BarButton.FOLLOW, d.hit(origin, ray(0.76), origin)!!.barButton)
    }

    @Test fun theNearestOfTwoOverlappingWindowsWins() {
        val d = Desktop(); val a = d.add("A", "u")!!; val b = d.add("B", "u")!!
        b.yaw = 0.0; a.yaw = 0.0; b.distance = 1.0; a.distance = 2.5
        assertEquals(b.id, d.hit(origin, Geometry.direction(0.0, 0.0), origin)!!.window.id)
    }

    @Test fun newWindowsFanOutAndTheSeventhIsRefused() {
        val d = Desktop()
        val yaws = (1..Desktop.MAX_WINDOWS).map { d.add("W$it", "u")!!.yaw }
        assertEquals(0.0, yaws[0], 1e-9); assertTrue(yaws[1] < 0 && yaws[2] > 0)
        assertNull(d.add("too many", "u"))
    }

    @Test fun minimizeHidesRestoreShowsAndFocusMovesOn() {
        val d = Desktop(); val a = d.add("A", "u")!!; val b = d.add("B", "u")!!
        d.minimize(b.id)
        assertEquals(listOf(a.id), d.visible.map { it.id }); assertEquals(a.id, d.focusedId)
        d.restore(b.id)
        assertEquals(2, d.visible.size); assertEquals(b.id, d.focusedId)
        d.close(b.id); assertEquals(a.id, d.focusedId)
        d.close(a.id); assertEquals(-1, d.focusedId); assertTrue(d.visible.isEmpty())
    }

    @Test fun maximizeAndFullscreenChangeWhatIsShownNotWhatIsStored() {
        val d = Desktop(); val a = d.add("A", "u")!!; val b = d.add("B", "u")!!
        val width = a.width
        d.toggleMaximize(a.id)
        assertEquals(DesktopWindow.MAXIMIZED_WIDTH, a.shownWidth(), 1e-9); assertEquals(width, a.width, 1e-9)
        d.resize(a.id, 2.0); d.move(a.id, 1.0, 0.0)
        assertEquals("a maximized window is not resized or moved", width, a.width, 1e-9)
        d.toggleMaximize(a.id); assertEquals(width, a.shownWidth(), 1e-9)
        d.toggleFullscreen(b.id); assertEquals(listOf(b.id), d.visible.map { it.id })
        d.toggleFullscreen(b.id); assertEquals(2, d.visible.size)
    }

    @Test fun sizesDistancesAndPitchAreClamped() {
        val d = Desktop(); val a = d.add("A", "u")!!
        repeat(30) { d.resize(a.id, 1.5) }; assertEquals(DesktopWindow.MAX_WIDTH, a.width, 1e-9)
        repeat(30) { d.resize(a.id, 0.5) }; assertEquals(DesktopWindow.MIN_WIDTH, a.width, 1e-9)
        repeat(30) { d.push(a.id, 0.5) }; assertEquals(DesktopWindow.MIN_DISTANCE, a.distance, 1e-9)
        repeat(30) { d.move(a.id, 0.0, 1.0) }; assertEquals(DesktopWindow.MAX_PITCH, a.pitch, 1e-9)
    }

    @Test fun switchingBetweenFollowAndLockDoesNotMoveTheWindow() {
        val d = Desktop(); val a = d.add("A", "u")!!
        a.yaw = 0.3; d.followAnchor.yaw = 1.0
        val before = d.worldYaw(a)
        d.toggleFollow(a.id); assertTrue(a.follow); assertEquals(before, d.worldYaw(a), 1e-9)
        d.toggleFollow(a.id); assertFalse(a.follow); assertEquals(before, d.worldYaw(a), 1e-9)
    }

    @Test fun aFollowedWindowTurnsWithTheAnchorAndALockedOneStays() {
        val d = Desktop(); val locked = d.add("L", "u")!!; val follow = d.add("F", "u")!!
        locked.yaw = 0.0; follow.yaw = 0.0; d.toggleFollow(follow.id)
        d.followAnchor.yaw = Math.toRadians(90.0)
        assertEquals(Math.toRadians(90.0), d.worldYaw(follow), 1e-9); assertEquals(0.0, d.worldYaw(locked), 1e-9)
    }

    @Test fun theFollowAnchorWaitsInsideTheComfortZoneThenCatchesUp() {
        val f = FollowAnchor()
        repeat(120) { f.update(Math.toRadians(20.0), 1.0 / 60) }
        assertEquals("a small head turn does not drag the windows along", 0.0, f.yaw, 1e-9)
        repeat(180) { f.update(Math.toRadians(80.0), 1.0 / 60) }
        assertEquals(Math.toRadians(80.0), f.yaw, Math.toRadians(3.5))
        // across the +-180 degree seam it takes the short way round
        val g = FollowAnchor(Math.toRadians(170.0))
        repeat(180) { g.update(Math.toRadians(-150.0), 1.0 / 60) }
        assertEquals(0.0, Geometry.wrap(g.yaw - Math.toRadians(-150.0)), Math.toRadians(3.5))
    }

    @Test fun gatherBringsLockedWindowsInFrontKeepingTheirArrangement() {
        val d = Desktop(); val a = d.add("A", "u")!!; val b = d.add("B", "u")!!
        a.yaw = Math.toRadians(100.0); b.yaw = Math.toRadians(140.0)
        d.gather(Math.toRadians(-30.0))
        assertEquals(Math.toRadians(40.0), Geometry.wrap(b.yaw - a.yaw), 1e-9)
        assertEquals(Math.toRadians(-30.0), (a.yaw + b.yaw) / 2, 1e-9)
    }

    @Test fun layoutSurvivesSavingAndLoading() {
        val d = Desktop(); val a = d.add("YouTube", "https://m.youtube.com/", "youtube")!!; val b = d.add("B", "https://b.example")!!
        a.yaw = 0.4; a.pitch = -0.1; a.width = 2.0; a.distance = 3.0; d.toggleFollow(a.id); d.minimize(b.id)
        val e = Desktop(); e.load(JSONObject(d.toJson().toString()))
        assertEquals(2, e.windows.size)
        val a2 = e.find(a.id)!!
        assertEquals("YouTube", a2.title); assertEquals("https://m.youtube.com/", a2.url); assertEquals("youtube", a2.kind)
        assertEquals(a.yaw, a2.yaw, 1e-9); assertEquals(-0.1, a2.pitch, 1e-9); assertEquals(2.0, a2.width, 1e-9); assertEquals(3.0, a2.distance, 1e-9)
        assertTrue(a2.follow); assertTrue(e.find(b.id)!!.minimized); assertEquals(a.id, e.focusedId)
        assertNotNull("ids keep counting up after a load", e.add("C", "u")); assertEquals(3, e.windows.last().id)
    }

    @Test fun aDamagedLayoutLoadsAsFarAsItCan() {
        val e = Desktop()
        e.load(JSONObject("{\"windows\":[{\"id\":4,\"width\":99,\"distance\":0.01,\"aspect\":0},{\"id\":5,\"url\":\"https://x.example\"}],\"focused\":77}"))
        assertEquals(2, e.windows.size)
        assertEquals(DesktopWindow.MAX_WIDTH, e.windows[0].width, 1e-9); assertEquals(DesktopWindow.MIN_DISTANCE, e.windows[0].distance, 1e-9)
        assertTrue(e.focusedId == 4 || e.focusedId == 5)
        Desktop().load(JSONObject("{}"))
    }

    @Test fun modelMatrixPutsTheQuadWhereTheHitTestExpectsIt() {
        val yaw = Math.toRadians(35.0); val pitch = Math.toRadians(-12.0); val c = Geometry.centre(origin, yaw, pitch, 2.0)
        val m = FloatArray(16); Geometry.modelMatrix(c, yaw, pitch, 1.6, 0.9, m)
        // the quad's top-right corner (0.5, 0.5) in the world, then hit-tested back
        val p = doubleArrayOf(m[0] * 0.5 + m[4] * 0.5 + m[12], m[1] * 0.5 + m[5] * 0.5 + m[13], m[2] * 0.5 + m[6] * 0.5 + m[14])
        val h = Geometry.planeHit(origin, p, c, yaw, pitch)!!
        assertEquals(0.8, h[0], 1e-5); assertEquals(0.45, h[1], 1e-5); assertEquals(1.0, h[2], 1e-5)
    }

    @Test fun newAndMaximizedWindowsFitInsideTheGlassesFieldOfView() {
        // display half-angles from the factory calibration: tan 0.3875 horizontally, 0.2191 vertically
        val w = DesktopWindow(1, "A", "u")
        assertTrue(w.width / 2 / w.distance < 0.3875 * 0.8); assertTrue((w.height / 2 + w.barHeight) / w.distance < 0.2191)
        w.maximized = true
        assertTrue(w.shownWidth() / 2 / w.shownDistance() < 0.3875); assertTrue((w.shownHeight() / 2 + w.shownBarHeight()) / w.shownDistance() < 0.2191 * 1.12)
    }

    @Test fun typedTextBecomesAUrlOrASearch() {
        assertEquals("https://example.com", HomeApps.toUrl("example.com"))
        assertEquals("https://a.b/c?d=1", HomeApps.toUrl(" https://a.b/c?d=1 "))
        assertEquals("https://www.google.com/search?q=xreal+air+2", HomeApps.toUrl("xreal air 2"))
        assertEquals("https://www.google.com/search?q=hello", HomeApps.toUrl("hello"))
        assertEquals("about:blank", HomeApps.toUrl(""))
    }
}
