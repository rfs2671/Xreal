package dev.ultraxr

import dev.ultraxr.desktop.Stereo
import dev.ultraxr.glasses.StereoSwitch
import dev.ultraxr.glasses.StereoSwitch.Action
import dev.ultraxr.glasses.StereoSwitch.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoTest {
    private val tanH = 0.3976

    // ---- geometry
    @Test fun theLeftHalfOfThePictureIsTheLeftEye() {
        assertTrue(Stereo.eyeOffset(0, 0.064, false) < 0)
        assertTrue(Stereo.eyeOffset(1, 0.064, false) > 0)
        assertEquals(0.064, Stereo.eyeOffset(1, 0.064, false) - Stereo.eyeOffset(0, 0.064, false), 1e-12)
        assertTrue("swapping exchanges them", Stereo.eyeOffset(0, 0.064, true) > 0)
    }

    @Test fun somethingAtTheGlassesFocusDistanceLandsOnTheSamePixelInBothEyes() {
        val l = Stereo.eyeOffset(0, 0.064, false); val r = Stereo.eyeOffset(1, 0.064, false)
        for (x in listOf(-0.5, 0.0, 0.8))
            assertEquals(Stereo.pictureX(x, Stereo.CONVERGENCE_M, l, tanH), Stereo.pictureX(x, Stereo.CONVERGENCE_M, r, tanH), 1e-12)
    }

    @Test fun nearerThingsAreCrossedAndFartherOnesUncrossed() {
        val l = Stereo.eyeOffset(0, 0.064, false); val r = Stereo.eyeOffset(1, 0.064, false)
        // a window 2 m ahead: the left eye sees it further right than the right eye does
        val near = Stereo.pictureX(0.0, 2.0, l, tanH) - Stereo.pictureX(0.0, 2.0, r, tanH)
        assertTrue(near > 0)
        val far = Stereo.pictureX(0.0, 40.0, l, tanH) - Stereo.pictureX(0.0, 40.0, r, tanH)
        assertTrue(far < 0)
        // at 2 m and 64 mm the disparity is 0.064 * (1/2 - 1/4) as a tangent
        assertEquals(0.064 * 0.25 / tanH, near, 1e-12)
        // a wider eye distance gives more of it, and no eye distance gives none
        assertTrue(Stereo.pictureX(0.0, 2.0, -0.035, tanH) - Stereo.pictureX(0.0, 2.0, 0.035, tanH) > near)
        assertEquals(0.0, Stereo.pictureX(0.3, 2.0, 0.0, tanH) - Stereo.pictureX(0.3, 2.0, 0.0, tanH), 0.0)
    }

    // ---- the switch. One step is one second; arguments are (glasses in half-width 3D?, width, height).
    private fun StereoSwitch.run(n: Int, half: Boolean?, w: Int, h: Int): List<Action> = (1..n).mapNotNull { step(half, w, h) }

    @Test fun nothingIsSentUntil3dIsWantedAndA2dPictureIsShowing() {
        val s = StereoSwitch()
        assertTrue(s.run(10, false, 1920, 1080).isEmpty())
        s.want(true)
        assertTrue("no picture yet", s.run(10, false, 0, 0).isEmpty())
        assertEquals(listOf(Action.SET_3D), s.run(2, false, 1920, 1080))
        assertEquals(State.STARTING, s.state)
    }

    @Test fun aGoodSwitchBecomesActiveOnlyAfterTheLinkRestarted() {
        val s = StereoSwitch(); s.want(true); s.run(2, false, 1920, 1080)
        // the old picture is still reported for a moment, with the glasses already in 3D
        assertTrue(s.run(3, true, 1920, 1080).isEmpty()); assertEquals(State.STARTING, s.state)
        s.run(1, true, 0, 0)
        assertTrue(s.run(StereoSwitch.HOLD_SECONDS - 1, true, 1920, 1080).isEmpty()); assertEquals("not yet: the glasses' screen is not on yet", State.STARTING, s.state)
        assertTrue(s.run(1, true, 1920, 1080).isEmpty())
        assertEquals(State.ACTIVE, s.state); assertEquals(1, s.attempts); assertEquals(1, s.commands)
        assertTrue("and stays so", s.run(600, true, 1920, 1080).isEmpty())
        assertEquals(State.ACTIVE, s.state)
    }

    @Test fun aNarrowLinkIsRetriedFromAWorking2dPictureAndThenGivenUp() {
        val s = StereoSwitch(); s.want(true)
        val sent = ArrayList<Action>()
        sent += s.run(2, false, 1920, 1080)                 // attempt 1
        repeat(3) {
            sent += s.run(1, true, 0, 0)
            sent += s.run(2, true, 640, 480)                // came up on two lanes
            assertTrue(s.state == State.RESTING || s.state == State.GAVE_UP)
            sent += s.run(1, false, 0, 0)
            sent += s.run(6, false, 1920, 1080)             // 2D is back; next attempt, if any is left
        }
        assertEquals(listOf(Action.SET_3D, Action.SET_2D, Action.SET_3D, Action.SET_2D, Action.SET_3D, Action.SET_2D), sent)
        assertEquals(State.GAVE_UP, s.state); assertEquals(3, s.attempts); assertEquals(6, s.commands)
        assertTrue(s.lastReason.isNotEmpty())
        // and then it leaves the link alone for good
        assertTrue(s.run(3600, false, 1920, 1080).isEmpty())
        assertEquals(6, s.commands)
    }

    @Test fun givingUpIsNotUndoneByLeavingAndReopeningTheDesktop() {
        val s = StereoSwitch(1); s.want(true); s.run(2, false, 1920, 1080)
        assertEquals(listOf(Action.SET_2D), s.run(StereoSwitch.START_TIMEOUT_SECONDS + 1, true, 0, 0)); assertEquals(State.GAVE_UP, s.state)
        s.want(false); s.run(2, false, 1920, 1080); s.want(true)
        assertTrue(s.run(60, false, 1920, 1080).isEmpty()); assertEquals(State.GAVE_UP, s.state)
        s.reset()                                            // the user ticked the box again
        assertEquals(listOf(Action.SET_3D), s.run(3, false, 1920, 1080))
    }

    @Test fun noPictureIn3dTimesOutBackTo2d() {
        val s = StereoSwitch(); s.want(true); s.run(2, false, 1920, 1080)
        assertEquals(listOf(Action.SET_2D), s.run(StereoSwitch.START_TIMEOUT_SECONDS, true, 0, 0))
        assertEquals(State.RESTING, s.state)
        // if 2D never returns either, stop without sending anything more
        assertTrue(s.run(StereoSwitch.REST_TIMEOUT_SECONDS, false, 0, 0).isEmpty())
        assertEquals(State.GAVE_UP, s.state)
    }

    @Test fun losingThePictureWhileActiveGoesBackTo2d() {
        val s = StereoSwitch(); s.want(true); s.run(2, false, 1920, 1080); s.run(1, true, 0, 0); s.run(StereoSwitch.HOLD_SECONDS, true, 1920, 1080)
        assertEquals(State.ACTIVE, s.state)
        assertTrue("a short blink is ignored", s.run(2, true, 0, 0).isEmpty()); s.run(1, true, 1920, 1080)
        assertEquals(listOf(Action.SET_2D), s.run(StereoSwitch.LOST_SECONDS, true, 0, 0))
        assertEquals(State.RESTING, s.state)
    }

    @Test fun leavingTheDesktopReturnsTheGlassesTo2dOnce() {
        val s = StereoSwitch(); s.want(true); s.run(2, false, 1920, 1080); s.run(1, true, 0, 0); s.run(StereoSwitch.HOLD_SECONDS, true, 1920, 1080)
        s.want(false)
        assertEquals(listOf(Action.SET_2D), s.run(5, true, 1920, 1080))
        assertEquals(State.OFF, s.state)
        assertTrue(s.run(30, false, 1920, 1080).isEmpty())
    }

    @Test fun theGlassesLeaving3dThemselvesIsAccepted() {
        val s = StereoSwitch(); s.want(true); s.run(2, false, 1920, 1080); s.run(1, true, 0, 0); s.run(StereoSwitch.HOLD_SECONDS, true, 1920, 1080)
        assertNull(s.step(false, 1920, 1080)); assertEquals(State.GAVE_UP, s.state)
        assertTrue(s.run(60, false, 1920, 1080).isEmpty())
    }

    @Test fun glassesThatFallBackTo2dWhenTheirScreenComesOnAreAFailedAttempt() {
        // The Pixel, 17 November 2026: 3D accepted, link back at 1920x1080, three seconds later the glasses report 2D.
        val s = StereoSwitch(); s.want(true); s.run(2, false, 1920, 1080)
        s.run(3, true, 0, 0); assertTrue(s.run(3, true, 1920, 1080).isEmpty())
        assertEquals(listOf(Action.SET_2D), s.run(1, false, 1920, 1080))
        assertEquals(State.RESTING, s.state); assertTrue(s.lastReason.contains("2D"))
        // never ACTIVE in between, so no game was started with two eye pictures
    }

    @Test fun unpluggingStartsOver() {
        val s = StereoSwitch(1); s.want(true); s.run(2, false, 1920, 1080); s.run(StereoSwitch.START_TIMEOUT_SECONDS + 1, true, 0, 0)
        assertEquals(State.GAVE_UP, s.state)
        s.unplugged(); assertEquals(State.OFF, s.state)
        assertEquals(listOf(Action.SET_3D), s.run(3, false, 1920, 1080))
    }
}
