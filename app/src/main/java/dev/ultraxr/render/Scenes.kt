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

    // ---- hands one can see working: a ray from each, a mark at the picture's edge for a hand outside it
    private val pinch = arrayOf(dev.ultraxr.core.PinchState(), dev.ultraxr.core.PinchState())
    private val armRay = arrayOf(dev.ultraxr.core.ArmRay(false), dev.ultraxr.core.ArmRay(true))
    private val seenNs = LongArray(2)
    /** The hands of this frame as things to pinch need them. Call once a frame, from update. */
    protected var probes: List<dev.ultraxr.core.HandProbe> = emptyList()
    /** How far each hand's ray is drawn this frame (a thing it points at shortens it). */
    protected val rayLength = doubleArrayOf(1.5, 1.5)

    protected fun readHands(head: HeadTracker.State) {
        val now = System.nanoTime(); val out = ArrayList<dev.ultraxr.core.HandProbe>(2)
        for (h in Tracking.handJoints) {
            if (now - h.timestampNs > 400_000_000L) continue
            val side = if (h.isRight) 1 else 0
            val thumb = h.joint(Hand.THUMB_TIP); val index = h.joint(Hand.INDEX_TIP)
            // the tracker's own rule for a pinch, on every new result of the tracker (not on every drawn frame)
            if (h.timestampNs != seenNs[side]) {
                seenNs[side] = h.timestampNs
                var finger = 0.0; for (j in Hand.INDEX_MCP until Hand.INDEX_TIP) finger += len(h.joint(j), h.joint(j + 1))
                pinch[side].update(len(thumb, index).toFloat(), finger.toFloat())
            }
            val pp = DoubleArray(3) { (thumb[it] + index[it]) / 2 }
            val dir = if (head.mode == HeadTracker.Mode.NONE) null else armRay[side].aim(head.pose.p, head.pose.q, pp, pinch[side].pinching, now)
            out += dev.ultraxr.core.HandProbe(h.isRight, pp, pinch[side].pinching, pp, dir)
        }
        probes = out; rayLength[0] = 1.5; rayLength[1] = 1.5
    }

    private fun len(a: DoubleArray, b: DoubleArray) = Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    /** A thin ray from each hand, brighter while it pinches, with a dot at its end. */
    protected fun drawRays(d: Draw) {
        for (h in probes) {
            val dir = h.rayDir ?: continue; val side = if (h.isRight) 1 else 0
            val c = if (h.isRight) floatArrayOf(0.35f, 0.75f, 1f) else floatArrayOf(1f, 0.6f, 0.35f)
            val k = if (h.pinching) 1f else 0.45f; val l = rayLength[side]
            val end = doubleArrayOf(h.rayOrigin[0] + dir[0] * l, h.rayOrigin[1] + dir[1] * l, h.rayOrigin[2] + dir[2] * l)
            d.tube(h.rayOrigin, end, if (h.pinching) 0.0030 else 0.0018, c[0] * k, c[1] * k, c[2] * k)
            d.sphere(end[0], end[1], end[2], if (h.pinching) 0.014 else 0.009, c[0], c[1], c[2], lit = false)
        }
    }

    /**
     * A hand that is tracked outside what the display shows gets a mark at the picture's edge on its side, larger
     * while it pinches. Measured on the worn recording of 23 November 2026: three quarters of the places at which a
     * hand was tracked were outside the display's picture, and nothing told the wearer so.
     */
    protected fun drawEdgeMarks(d: Draw, head: HeadTracker.State) {
        if (head.mode == HeadTracker.Mode.NONE) return
        val inv = head.pose.inverse(); val th = Tracking.displayTanH; val tv = Tracking.displayTanV
        for (h in probes) {
            val v = inv.apply(h.pinchPoint); val f = -v[2]
            var x = if (f > 0.05) v[0] / f else Math.signum(v[0]) * 9.0; var y = if (f > 0.05) v[1] / f else Math.signum(v[1]) * 9.0
            if (f > 0.05 && Math.abs(x) < th * 0.92 && Math.abs(y) < tv * 0.92) continue
            x = x.coerceIn(-th * 0.86, th * 0.86); y = y.coerceIn(-tv * 0.80, tv * 0.80)
            val p = inFront(head, x, y, 1.0)
            val c = if (h.isRight) floatArrayOf(0.35f, 0.75f, 1f) else floatArrayOf(1f, 0.6f, 0.35f)
            d.sphere(p[0], p[1], p[2], if (h.pinching) 0.020 else 0.011, c[0], c[1], c[2], lit = false)
            d.halo(p[0], p[1], p[2], if (h.pinching) 0.05 else 0.028, c[0], c[1], c[2], if (h.pinching) 0.5f else 0.25f)
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
    /** A ball to pinch and drag, and a thing to press: both stay where they are in the room (core/HandPlay). */
    private var ball: dev.ultraxr.core.GrabBall? = null
    private var press: dev.ultraxr.core.PressTarget? = null
    private var saidHeld = -1; private var saidPresses = 0

    override fun update(dt: Double, head: HeadTracker.State) {
        time += dt
        if (anchor == null && head.mode != HeadTracker.Mode.NONE) {
            anchor = inFront(head, 0.0, 0.0, 2.0); anchorYaw = Q.yawY(head.pose.q); floorY = head.pose.p[1] - Tracking.settings.standingHeightM
            // within reach and inside the picture: 45 cm ahead, a little left and low; the thing to press further off, to the right
            ball = dev.ultraxr.core.GrabBall(inFront(head, -0.08, -0.05, 0.45)); press = dev.ultraxr.core.PressTarget(inFront(head, 0.32, -0.02, 1.4))
            saidHeld = -1; saidPresses = 0; Tracking.log("home: a ball to pinch 0.45 m ahead and a thing to press 1.4 m ahead, both fixed in the room")
        }
        readHands(head)
        val now = System.nanoTime()
        ball?.update(probes, now); press?.update(probes, now)
        // what was done with them goes to the log, so that a worn try can be read afterwards
        ball?.let { b ->
            if (b.heldBy != saidHeld) {
                val side = { s: Int -> if (s == 1) "right" else "left" }
                val hp = head.pose.p; val away = Math.sqrt((b.p[0] - hp[0]) * (b.p[0] - hp[0]) + (b.p[1] - hp[1]) * (b.p[1] - hp[1]) + (b.p[2] - hp[2]) * (b.p[2] - hp[2]))
                Tracking.log(if (b.heldBy >= 0) "ball: taken by the " + side(b.heldBy) + " hand" + (if (b.far) " with its ray" else " at the fingers") + ", " + dev.ultraxr.core.Fmt.f(away, 2) + " m from the head"
                    else "ball: let go by the " + side(saidHeld) + " hand at " + dev.ultraxr.core.Fmt.f(b.p[0], 2) + " " + dev.ultraxr.core.Fmt.f(b.p[1], 2) + " " + dev.ultraxr.core.Fmt.f(b.p[2], 2) + ", " + dev.ultraxr.core.Fmt.f(away, 2) + " m from the head; dragged " + dev.ultraxr.core.Fmt.f(b.draggedM, 2) + " m in all, taken " + b.grabs + " times")
                saidHeld = b.heldBy
            }
        }
        press?.let { t -> if (t.presses != saidPresses) { saidPresses = t.presses; Tracking.log("press target: pressed, " + t.presses + " times now") } }
        // a ray stops at what it points at
        for (h in probes) {
            val dir = h.rayDir ?: continue; val side = if (h.isRight) 1 else 0
            for (target in listOfNotNull(ball?.let { it.p to it.radius }, press?.let { it.p to it.radius })) {
                val m = dev.ultraxr.core.rayMiss(h.rayOrigin, dir, target.first)
                if (m[0] > 0.15 && m[1] < target.second + 0.04 && m[0] < rayLength[side]) rayLength[side] = m[0]
            }
        }
    }

    fun reanchor() { anchor = null }

    override fun draw(d: Draw, head: HeadTracker.State) {
        drawFloor(d, head)
        val a = anchor ?: inFront(head, 0.0, 0.0, 2.0)
        d.text("Ultra XR", a[0], a[1] + 0.16, a[2], 0.16, 0.85f, 0.95f, 1f)
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
        ball?.let { b ->
            val held = b.heldBy >= 0; val near = b.hover >= 0
            if (held) d.sphere(b.p[0], b.p[1], b.p[2], b.radius, 0.35f, 1f, 0.45f) else d.sphere(b.p[0], b.p[1], b.p[2], b.radius, 0.9f, 0.9f, 1f)
            if (held || near) d.halo(b.p[0], b.p[1], b.p[2], b.radius * 1.8, if (held) 0.3f else 1f, 1f, if (held) 0.4f else 0.6f, if (held) 0.5f else 0.3f)
            d.text(if (held) "Holding" else if (b.grabs == 0) "Pinch me and drag" else "Taken " + b.grabs + " times", b.p[0], b.p[1] + b.radius + 0.03, b.p[2], 0.016, 0.8f, 0.95f, 1f)
        }
        press?.let { t ->
            val flash = ((0.5 - (System.nanoTime() - t.pressedNs) * 1e-9) / 0.5).coerceIn(0.0, 1.0).toFloat()
            d.sphere(t.p[0], t.p[1], t.p[2], t.radius * (1.0 + 0.25 * flash), 1f, 0.75f - 0.5f * flash, 0.25f + 0.6f * flash)
            if (t.hover >= 0 || flash > 0f) d.halo(t.p[0], t.p[1], t.p[2], t.radius * 1.8, 1f, 0.8f, 0.3f, 0.3f + 0.4f * flash)
            d.text("Point and pinch: " + t.presses, t.p[0], t.p[1] + t.radius + 0.06, t.p[2], 0.04, 1f, 0.9f, 0.6f)
        }
        drawHands(d)
        drawRays(d)
        drawEdgeMarks(d, head)
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
                else -> "Lumen"
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
            d.text("${secs / 60}:${(secs % 60).toString().padStart(2, '0')}" + (if (g.combo > 1) "   x${1 + (g.combo - 1).coerceAtMost(9) / 3}" else ""), t[0], t[1], t[2], 0.03, 0.6f, 0.85f, 1f, 0.85f)
            if (g.hitFlash > 0) { val h = inFront(head, 0.0, 0.0, 1.2); d.text("Hit", h[0], h[1], h[2], 0.12, 1f, 0.2f, 0.15f, (g.hitFlash * 2).toFloat().coerceAtMost(1f)) }
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
        if (d.eyeIndex == 0 || d.eyeIndex == -1) d.text("Left", l[0], l[1], l[2], 0.06, 1f, 0.35f, 0.3f)
        if (d.eyeIndex == 1 || d.eyeIndex == -1) d.text("Right", r[0], r[1], r[2], 0.06, 0.3f, 0.6f, 1f)
        if (prompt.isNotEmpty()) { val p = inFront(head, 0.0, -0.22, 1.5); d.text(prompt, p[0], p[1], p[2], 0.04, 1f, 1f, 1f) }
        drawHands(d)
    }
}
