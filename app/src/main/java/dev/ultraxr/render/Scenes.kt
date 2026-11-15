package dev.ultraxr.render

import dev.ultraxr.core.Hand
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Q
import dev.ultraxr.game.Game
import dev.ultraxr.tracking.Tracking
import kotlin.math.cos
import kotlin.math.sin

/** Things every scene shares: the floor grid, the hands, and a tracking status line. */
abstract class BaseScene : Scene {
    protected var time = 0.0
    protected var floorY = -1.6

    protected fun drawFloor(d: Draw, head: HeadTracker.State) {
        val cx = Math.round(head.pose.p[0]).toDouble(); val cz = Math.round(head.pose.p[2]).toDouble()
        val n = 5
        for (i in -n..n) {
            d.line(cx + i, floorY, cz - n, cx + i, floorY, cz + n)
            d.line(cx - n, floorY, cz + i, cx + n, floorY, cz + i)
        }
        d.flushLines(0.05f, 0.22f, 0.3f)
    }

    protected fun drawHands(d: Draw) {
        if (!Tracking.settings.showHands) return
        for (h in Tracking.handJoints) {
            if (System.nanoTime() - h.timestampNs > 400_000_000L) continue
            val c = if (h.isRight) floatArrayOf(0.35f, 0.75f, 1f) else floatArrayOf(1f, 0.6f, 0.35f)
            for (j in 0 until Hand.JOINTS) { val p = h.joint(j); d.sphere(p[0], p[1], p[2], if (j == Hand.INDEX_TIP || j == Hand.THUMB_TIP) 0.008 else 0.006, c[0], c[1], c[2]) }
            var i = 0
            while (i < Hand.BONES.size) { d.tube(h.joint(Hand.BONES[i]), h.joint(Hand.BONES[i + 1]), 0.0035, c[0] * 0.6f, c[1] * 0.6f, c[2] * 0.6f); i += 2 }
        }
    }

    /** Point in front of the head: [right], [up], [forward] metres in the head frame. */
    protected fun inFront(head: HeadTracker.State, right: Double, up: Double, forward: Double): DoubleArray = head.pose.apply(doubleArrayOf(right, up, -forward))

    protected fun drawStatus(d: Draw, head: HeadTracker.State) {
        val msg = when (head.mode) {
            HeadTracker.Mode.SIX_DOF -> return
            HeadTracker.Mode.ORIENTATION_ONLY -> if (Tracking.vioStates > 0) "Position tracking lost: hold still for a moment" else "Hold still for a moment to start position tracking"
            HeadTracker.Mode.NONE -> "Waiting for the glasses"
        }
        val p = inFront(head, 0.0, -0.3, 1.6)
        d.text(msg, p[0], p[1], p[2], 0.035, 1f, 0.75f, 0.3f)
    }
}

/** What the glasses show while the launcher is open on the phone. */
class HomeScene : BaseScene() {
    @Volatile var subtitle = "Pick a game on your phone"
    private var anchor: DoubleArray? = null
    private var anchorYaw = 0.0

    override fun update(dt: Double, head: HeadTracker.State) {
        time += dt
        if (anchor == null && head.mode != HeadTracker.Mode.NONE) {
            anchor = inFront(head, 0.0, 0.0, 2.0); anchorYaw = Q.yawY(head.pose.q); floorY = head.pose.p[1] - Tracking.settings.standingHeightM
        }
    }

    fun reanchor() { anchor = null }

    override fun draw(d: Draw, head: HeadTracker.State) {
        drawFloor(d, head)
        val a = anchor ?: inFront(head, 0.0, 0.0, 2.0)
        d.text("ULTRA XR", a[0], a[1] + 0.16, a[2], 0.16, 0.85f, 0.95f, 1f)
        d.text(subtitle, a[0], a[1] - 0.02, a[2], 0.05, 0.55f, 0.8f, 0.9f)
        // A slow ring of orbs around the title, to give the eyes real depth to lock on to.
        for (i in 0 until 8) {
            val ang = time * 0.25 + i * Math.PI / 4
            val x = a[0] + cos(ang) * 0.9 * cos(anchorYaw) + sin(ang) * 0.5 * sin(anchorYaw) * -1
            val z = a[2] - cos(ang) * 0.9 * sin(anchorYaw) + sin(ang) * 0.5 * cos(anchorYaw) * -1
            val y = a[1] - 0.35 + 0.08 * sin(time + i)
            val c = Draw.hue(0.5f + 0.06f * i)
            d.sphere(x, y, z, 0.035, c[0], c[1], c[2])
        }
        drawHands(d)
        drawStatus(d, head)
    }
}

/** The built-in game. */
class GameScene(val game: Game) : BaseScene() {
    @Volatile var paused = false

    override fun update(dt: Double, head: HeadTracker.State) {
        time += dt
        if (paused || head.mode == HeadTracker.Mode.NONE) return
        game.update(dt, Game.Input(head.pose, head.mode == HeadTracker.Mode.SIX_DOF, Tracking.hands.filter { System.nanoTime() - it.timestampNs < 400_000_000L }))
        floorY = game.floorY
    }

    override fun draw(d: Draw, head: HeadTracker.State) {
        drawFloor(d, head)
        val g = game
        for (r in g.rings.toTypedArray()) {
            val f = (r.flash / 0.6).toFloat()
            d.ring(r.p, r.normal, r.radius, 0.3f + 0.7f * f, 0.85f, 1f - 0.5f * f)
        }
        for (o in g.orbs.toTypedArray()) orb(d, o, 1f)
        g.startOrb?.let { o ->
            val pulse = 1f + 0.12f * sin(time * 3).toFloat()
            orb(d, o, pulse)
            val label = when (g.phase) {
                Game.Phase.FINISHED -> "Score ${g.score}   Best ${g.best}"
                else -> "LUMEN"
            }
            d.text(label, o.p[0], o.p[1] + 0.2, o.p[2], 0.06, 0.9f, 0.95f, 1f)
            d.text(if (g.gazeActive) "Look at the orb to start" else "Touch the orb to start", o.p[0], o.p[1] + 0.12, o.p[2], 0.03, 0.6f, 0.8f, 0.9f)
        }
        for (c in g.comets.toTypedArray()) {
            d.sphere(c.p[0], c.p[1], c.p[2], 0.09, 1f, 0.25f, 0.15f)
            for (k in 1..4) d.sphere(c.p[0] - c.v[0] * 0.07 * k, c.p[1] - c.v[1] * 0.07 * k, c.p[2] - c.v[2] * 0.07 * k, 0.07 - 0.012 * k, 1f, 0.45f, 0.1f, lit = false)
            d.halo(c.p[0], c.p[1], c.p[2], 0.16, 1f, 0.2f, 0.1f, 0.25f)
        }
        for (s in g.sparks.toTypedArray()) {
            val c = Draw.hue(s.hue, s.life.coerceIn(0.0, 1.0).toFloat())
            d.sphere(s.p[0], s.p[1], s.p[2], 0.008, c[0], c[1], c[2], lit = false)
        }
        drawHands(d)
        if (g.phase == Game.Phase.PLAYING) {
            val p = inFront(head, 0.0, 0.2, 1.8)
            d.text("${g.score}", p[0], p[1], p[2], 0.07, 1f, 1f, 1f, 0.9f)
            val t = inFront(head, 0.0, 0.135, 1.8)
            val secs = g.timeLeft.toInt()
            d.text("%d:%02d%s".format(secs / 60, secs % 60, if (g.combo > 1) "   x${1 + (g.combo - 1).coerceAtMost(9) / 3}" else ""), t[0], t[1], t[2], 0.03, 0.6f, 0.85f, 1f, 0.85f)
            if (g.hitFlash > 0) { val h = inFront(head, 0.0, 0.0, 1.2); d.text("HIT", h[0], h[1], h[2], 0.12, 1f, 0.2f, 0.15f, (g.hitFlash * 2).toFloat().coerceAtMost(1f)) }
        }
        drawStatus(d, head)
    }

    private fun orb(d: Draw, o: Game.Orb, scale: Float) {
        val c = Draw.hue(o.hue)
        val grow = if (o.gaze > 0) 1f + (o.gaze / Game.GAZE_DWELL).toFloat() * 0.5f else 1f
        val r = Game.ORB_RADIUS * scale * grow
        d.sphere(o.p[0], o.p[1], o.p[2], r, c[0], c[1], c[2])
        d.halo(o.p[0], o.p[1], o.p[2], r * 1.7, c[0], c[1], c[2], 0.18f)
    }
}

/** Shown during the hardware check: one label per eye and objects at known distances. */
class CheckScene : BaseScene() {
    private var anchor: dev.ultraxr.core.Pose? = null
    @Volatile var prompt = ""

    override fun update(dt: Double, head: HeadTracker.State) {
        time += dt
        if (anchor == null && head.mode != HeadTracker.Mode.NONE) {
            val yaw = Q.yawY(head.pose.q)
            anchor = dev.ultraxr.core.Pose(head.pose.p.copyOf(), Q.axisAngle(0.0, 1.0, 0.0, yaw)); floorY = head.pose.p[1] - Tracking.settings.standingHeightM
        }
    }

    override fun draw(d: Draw, head: HeadTracker.State) {
        drawFloor(d, head)
        val a = anchor ?: head.pose
        val dist = doubleArrayOf(0.7, 1.5, 3.0)
        val label = arrayOf("0.7 m", "1.5 m", "3 m")
        for (i in 0..2) {
            val p = a.apply(doubleArrayOf(-0.35 + 0.35 * i, -0.1 + 0.05 * i, -dist[i]))
            val c = Draw.hue(0.1f + 0.3f * i)
            d.sphere(p[0], p[1], p[2], 0.05 * dist[i], c[0], c[1], c[2])
            d.text(label[i], p[0], p[1] + 0.08 * dist[i], p[2], 0.035 * dist[i], 0.8f, 0.9f, 1f)
        }
        // Each eye gets its own label: if you can read both words at once, both eyes see their own image.
        val l = inFront(head, -0.22, 0.2, 1.5); val r = inFront(head, 0.22, 0.2, 1.5)
        if (d.eyeIndex == 0 || d.eyeIndex == -1) d.text("LEFT", l[0], l[1], l[2], 0.06, 1f, 0.35f, 0.3f)
        if (d.eyeIndex == 1 || d.eyeIndex == -1) d.text("RIGHT", r[0], r[1], r[2], 0.06, 0.3f, 0.6f, 1f)
        if (prompt.isNotEmpty()) { val p = inFront(head, 0.0, -0.22, 1.5); d.text(prompt, p[0], p[1], p[2], 0.04, 1f, 1f, 1f) }
        drawHands(d)
    }
}
