package dev.ultraxr

import dev.ultraxr.core.HandState
import dev.ultraxr.core.Pose
import dev.ultraxr.game.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {
    private val z = doubleArrayOf(0.0, 0.0, 0.0)
    private fun hand(at: DoubleArray, pinching: Boolean = false, vel: DoubleArray = z) =
        HandState(true, pinching, if (pinching) 1.0 else 0.0, false, false, at, at, at, vel, at, doubleArrayOf(0.0, 0.0, -1.0), 0)
    private fun far() = hand(doubleArrayOf(50.0, 50.0, 50.0))
    private fun input(head: Pose = Pose(), six: Boolean = true, hands: List<HandState> = emptyList()) = Game.Input(head, six, hands)

    private fun started(g: Game, six: Boolean = true): Game {
        g.update(0.016, input(six = six, hands = listOf(far())))
        val o = g.startOrb!!
        g.update(0.016, input(six = six, hands = listOf(hand(o.p))))
        assertEquals(Game.Phase.PLAYING, g.phase)
        return g
    }

    @Test fun touchingTheStartOrbBeginsARound() {
        val g = Game(1)
        g.update(0.016, input(hands = listOf(far())))
        assertEquals(Game.Phase.READY, g.phase)
        val o = g.startOrb!!
        assertEquals(-0.55, o.p[2], 1e-6) // half a metre in front of the head
        started(g)
        assertEquals(3, g.rings.size)
        assertEquals(-1.6, g.floorY, 1e-9)
    }

    @Test fun gazeStartsOnlyWhenNoHandsAreAround() {
        val g = Game(1)
        // Hands recently seen: looking does nothing.
        g.update(0.016, input(hands = listOf(far())))
        repeat(60) { g.update(0.016, input()) }
        assertEquals(Game.Phase.READY, g.phase)
        // After a few seconds without hands, dwelling on the orb starts the round.
        repeat(400) { g.update(0.016, input(head = lookAt(g.startOrb?.p))) }
        assertEquals(Game.Phase.PLAYING, g.phase)
    }

    private fun lookAt(p: DoubleArray?): Pose {
        if (p == null) return Pose()
        val yaw = Math.atan2(-p[0], -p[2]); val pitch = Math.atan2(p[1], Math.hypot(p[0], p[2]))
        return Pose(z, dev.ultraxr.core.Q.mul(dev.ultraxr.core.Q.axisAngle(0.0, 1.0, 0.0, yaw), dev.ultraxr.core.Q.axisAngle(1.0, 0.0, 0.0, pitch)))
    }

    @Test fun touchingOrbsScoresWithCombo() {
        val g = started(Game(2))
        var touched = 0
        repeat(2000) {
            val o = g.orbs.firstOrNull { !it.thrown && it.heldBy < 0 }
            g.update(0.016, input(hands = listOf(if (o != null && it % 20 == 0) { touched++; hand(o.p) } else far())))
        }
        assertTrue("popped ${g.popped}", g.popped >= 10)
        assertTrue(g.score > g.popped * 10) // combo multiplier kicked in
        assertTrue(g.orbs.size <= Game.MAX_ORBS)
    }

    @Test fun grabbedOrbFollowsThePinchAndScoresThroughARing() {
        val g = started(Game(3))
        repeat(80) { g.update(0.016, input(hands = listOf(far()))) }
        val o = g.orbs.first()
        g.update(0.016, input(hands = listOf(hand(o.p, pinching = true))))
        assertEquals(0, o.heldBy)
        val ring = g.rings.first()
        // Carry it to half a metre in front of the ring, then release moving towards the ring.
        val start = DoubleArray(3) { ring.p[it] + ring.normal[it] * 0.5 }
        g.update(0.016, input(hands = listOf(hand(start, pinching = true))))
        assertEquals(start[0], o.p[0], 1e-9)
        val v = DoubleArray(3) { -ring.normal[it] * 2.0 }
        g.update(0.016, input(hands = listOf(hand(start, pinching = false, vel = v))))
        assertTrue(o.thrown)
        val before = g.score
        repeat(60) { g.update(0.016, input(hands = listOf(far()))) }
        assertEquals(1, g.ringHits)
        assertTrue(g.score >= before + 50)
        assertFalse(g.orbs.contains(o))
    }

    @Test fun cometsHitAStillHeadAndMissOneThatSteppedAside() {
        val still = started(Game(4))
        repeat(1500) { still.update(0.016, input(hands = listOf(far()))) }
        assertEquals("standing still gets hit, never dodges", 0, still.dodged)

        val mover = started(Game(4))
        var t = 0.0
        repeat(1500) {
            t += 0.016
            // Step half a metre to the side whenever a comet is within two metres.
            val c = mover.comets.firstOrNull()
            val near = c != null && Math.hypot(c.p[0], c.p[2]) < 2.5
            val head = Pose(doubleArrayOf(if (near) 0.6 else 0.0, 0.0, 0.0))
            mover.update(0.016, input(head = head, hands = listOf(far())))
        }
        assertTrue("dodged ${mover.dodged}", mover.dodged >= 1)
    }

    @Test fun rotationOnlyModeKeepsEverythingWithinReach() {
        val g = started(Game(5), six = false)
        assertTrue(g.rings.isEmpty())
        repeat(600) { g.update(0.016, input(six = false, hands = listOf(far()))) }
        assertTrue(g.comets.isEmpty())
        assertTrue(g.orbs.isNotEmpty())
        for (o in g.orbs) assertTrue(Math.hypot(o.home[0], o.home[2]) < 0.7)
    }

    @Test fun roundEndsAndCanRestart() {
        val g = started(Game(6))
        repeat((Game.ROUND_SECONDS / 0.05).toInt() + 5) { g.update(0.05, input(hands = listOf(far()))) }
        assertEquals(Game.Phase.FINISHED, g.phase)
        assertEquals(g.score, g.best)
        g.update(0.016, input(hands = listOf(far())))
        assertNotNull(g.startOrb)
        g.update(0.016, input(hands = listOf(hand(g.startOrb!!.p))))
        assertEquals(Game.Phase.PLAYING, g.phase)
        assertEquals(0, g.score)
    }
}
