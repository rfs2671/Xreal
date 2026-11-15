package dev.ultraxr.game

import dev.ultraxr.core.GestureTracker.Companion.dist
import dev.ultraxr.core.HandState
import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Lumen": a room-scale game for see-through glasses. Glowing orbs hang in the space around
 * you. Touch one to burst it, or pinch to pick it up and throw it through a floating ring
 * for a bigger score. Comets drift towards your head and have to be dodged by actually
 * moving. With no hands in view you can still play by looking at an orb for a moment, and
 * with rotation-only tracking everything stays within reach of where you stand.
 *
 * Pure logic: no Android or GL types, so it runs in unit tests with scripted input.
 */
class Game(seed: Long = System.nanoTime()) {
    enum class Phase { READY, PLAYING, FINISHED }
    class Orb(val id: Int, val p: DoubleArray, val home: DoubleArray, val hue: Float, var age: Double = 0.0) {
        val v = doubleArrayOf(0.0, 0.0, 0.0)
        var heldBy = -1          // hand index holding it, or -1
        var thrown = false
        var gaze = 0.0           // seconds looked at
    }
    class Ring(val p: DoubleArray, val normal: DoubleArray, val radius: Double, var flash: Double = 0.0)
    class Comet(val p: DoubleArray, val v: DoubleArray, var age: Double = 0.0)
    class Spark(val p: DoubleArray, val v: DoubleArray, val hue: Float, var life: Double)
    class Input(val head: Pose, val sixDof: Boolean, val hands: List<HandState>)

    val orbs = ArrayList<Orb>()
    val rings = ArrayList<Ring>()
    val comets = ArrayList<Comet>()
    val sparks = ArrayList<Spark>()
    var phase = Phase.READY; private set
    var score = 0; private set
    var best = 0
    var combo = 0; private set
    var timeLeft = ROUND_SECONDS; private set
    var floorY = -1.6; private set
    /** Seconds since the last comet hit, for a screen flash. */
    var hitFlash = 0.0; private set
    var popped = 0; private set
    var ringHits = 0; private set
    var dodged = 0; private set
    /** The single orb that starts a round. */
    var startOrb: Orb? = null; private set

    private val rnd = Random(seed)
    private var nextId = 1
    private var spawnTimer = 0.0
    private var cometTimer = 6.0
    private var origin = doubleArrayOf(0.0, 0.0, 0.0)
    private var handsSeenAgo = 100.0
    private var originSet = false

    /** Whether looking at an orb pops it: only when no hand has been seen for a while. */
    val gazeActive get() = handsSeenAgo > GAZE_FALLBACK_AFTER

    fun update(dt: Double, input: Input) {
        val h = input.head.p
        if (!originSet) { origin = h.copyOf(); floorY = h[1] - STANDING_HEIGHT; originSet = true }
        handsSeenAgo = if (input.hands.isEmpty()) handsSeenAgo + dt else 0.0
        hitFlash = (hitFlash - dt).coerceAtLeast(0.0)
        for (r in rings) r.flash = (r.flash - dt).coerceAtLeast(0.0)
        updateSparks(dt)
        when (phase) {
            Phase.READY, Phase.FINISHED -> {
                val o = startOrb ?: spawnStartOrb(input.head).also { startOrb = it }
                o.age += dt
                if (interact(o, input, dt, canGrab = false)) begin(input)
            }
            Phase.PLAYING -> play(dt, input)
        }
    }

    /** Forget the play-space origin (after a recenter) so it is re-taken from the head. */
    fun resetOrigin() { originSet = false; startOrb = null; if (phase == Phase.PLAYING) phase = Phase.READY; orbs.clear(); comets.clear(); rings.clear() }

    private fun spawnStartOrb(head: Pose): Orb {
        val f = forwardFlat(head)
        val p = doubleArrayOf(head.p[0] + f[0] * 0.55, head.p[1] - 0.12, head.p[2] + f[2] * 0.55)
        return Orb(0, p, p.copyOf(), 0.55f)
    }

    private fun begin(input: Input) {
        burst(startOrb!!.p, 0.55f, 24)
        startOrb = null
        phase = Phase.PLAYING
        score = 0; combo = 0; popped = 0; ringHits = 0; dodged = 0
        timeLeft = ROUND_SECONDS; spawnTimer = 0.0; cometTimer = 6.0
        orbs.clear(); comets.clear(); rings.clear()
        origin = input.head.p.copyOf(); floorY = origin[1] - STANDING_HEIGHT
        if (input.sixDof) {
            // Three rings a few steps away, at different heights and bearings.
            val f = forwardFlat(input.head)
            val base = Math.atan2(f[0], f[2])
            for (i in 0..2) {
                val a = base + (i - 1) * 0.9
                val d = 2.4 + 0.4 * i
                val p = doubleArrayOf(origin[0] + sin(a) * d, origin[1] - 0.15 + 0.3 * i, origin[2] + cos(a) * d)
                val n = norm(doubleArrayOf(origin[0] - p[0], 0.0, origin[2] - p[2]))
                rings += Ring(p, n, 0.32)
            }
        }
    }

    private fun play(dt: Double, input: Input) {
        timeLeft -= dt
        if (timeLeft <= 0) {
            timeLeft = 0.0; phase = Phase.FINISHED; best = maxOf(best, score)
            for (o in orbs) burst(o.p, o.hue, 6)
            orbs.clear(); comets.clear()
            return
        }
        spawnTimer -= dt
        val live = orbs.count { !it.thrown }
        if (spawnTimer <= 0 && live < MAX_ORBS) { orbs += spawnOrb(input); spawnTimer = 0.9 }

        val it = orbs.iterator()
        while (it.hasNext()) {
            val o = it.next()
            o.age += dt
            if (o.thrown) {
                o.v[1] -= 2.5 * dt // gentle gravity so throws arc but stay catchable by the rings
                val prev = o.p.copyOf()
                for (i in 0..2) o.p[i] += o.v[i] * dt
                val ring = rings.firstOrNull { crossed(it, prev, o.p) }
                if (ring != null) { ring.flash = 0.6; ringHits++; add(50); burst(o.p, o.hue, 30); it.remove(); continue }
                if (o.p[1] < floorY || dist(o.p, origin) > 8.0 || o.age > 20) { burst(o.p, o.hue, 8); combo = 0; it.remove() }
                continue
            }
            if (o.heldBy >= 0) {
                val hs = input.hands.getOrNull(o.heldBy)
                if (hs == null) { o.heldBy = -1 }
                else if (hs.pinching) { for (i in 0..2) o.p[i] = hs.pinchPoint[i] }
                else {
                    // Released: a real throw keeps going, a gentle release just lets go.
                    o.heldBy = -1
                    val sp = sqrt(hs.velocity[0] * hs.velocity[0] + hs.velocity[1] * hs.velocity[1] + hs.velocity[2] * hs.velocity[2])
                    if (sp > 0.6) { o.thrown = true; o.age = 0.0; for (i in 0..2) o.v[i] = hs.velocity[i] * 1.6 }
                    else { for (i in 0..2) o.home[i] = o.p[i] }
                }
                continue
            }
            // Idle bob around its home position.
            o.p[0] = o.home[0] + 0.02 * sin(o.age * 1.3 + o.id)
            o.p[1] = o.home[1] + 0.03 * sin(o.age * 0.9 + o.id * 2.1)
            o.p[2] = o.home[2] + 0.02 * cos(o.age * 1.1 + o.id)
            if (o.age > ORB_LIFETIME) { combo = 0; burst(o.p, o.hue, 4); it.remove(); continue }
            if (interact(o, input, dt, canGrab = true)) { popped++; add(10); burst(o.p, o.hue, 18); it.remove() }
        }

        if (input.sixDof) {
            cometTimer -= dt
            if (cometTimer <= 0) { comets += spawnComet(input.head); cometTimer = 5.0 + rnd.nextDouble() * 3.0 }
            val ci = comets.iterator()
            while (ci.hasNext()) {
                val c = ci.next()
                c.age += dt
                for (i in 0..2) c.p[i] += c.v[i] * dt
                val head = input.head.p
                if (dist(c.p, head) < 0.22) { hitFlash = 0.5; combo = 0; score = (score - 20).coerceAtLeast(0); burst(c.p, 0.0f, 20); ci.remove(); continue }
                // Passed the plane through the head: it missed.
                val toHead = doubleArrayOf(head[0] - c.p[0], head[1] - c.p[1], head[2] - c.p[2])
                if (toHead[0] * c.v[0] + toHead[1] * c.v[1] + toHead[2] * c.v[2] < 0 && c.age > 0.5) { dodged++; add(15); ci.remove(); continue }
                if (c.age > 12) ci.remove()
            }
        } else comets.clear()
    }

    /** Touch, grab or gaze handling for one orb. Returns true if it should burst. */
    private fun interact(o: Orb, input: Input, dt: Double, canGrab: Boolean): Boolean {
        for ((i, hs) in input.hands.withIndex()) {
            if (canGrab && hs.pinching && dist(hs.pinchPoint, o.p) < ORB_RADIUS + 0.05 && orbs.none { it.heldBy == i }) { o.heldBy = i; return false }
            if (!hs.pinching && (dist(hs.indexTip, o.p) < ORB_RADIUS + 0.02 || dist(hs.palm, o.p) < ORB_RADIUS + 0.03)) return true
            if (!canGrab && hs.pinching && dist(hs.pinchPoint, o.p) < ORB_RADIUS + 0.06) return true
        }
        if (gazeActive) {
            val f = Q.rotate(input.head.q, doubleArrayOf(0.0, 0.0, -1.0))
            val to = norm(doubleArrayOf(o.p[0] - input.head.p[0], o.p[1] - input.head.p[1], o.p[2] - input.head.p[2]))
            val looking = f[0] * to[0] + f[1] * to[1] + f[2] * to[2] > GAZE_COS
            o.gaze = if (looking) o.gaze + dt else (o.gaze - dt * 2).coerceAtLeast(0.0)
            if (o.gaze >= GAZE_DWELL) return true
        } else o.gaze = 0.0
        return false
    }

    private fun spawnOrb(input: Input): Orb {
        val f = forwardFlat(input.head)
        val base = Math.atan2(f[0], f[2])
        // Mostly in front, sometimes to the side or behind so you have to turn and step.
        val a = base + (rnd.nextDouble() - 0.5) * (if (rnd.nextInt(4) == 0) 5.0 else 2.2)
        val (d, y) = if (input.sixDof) (0.5 + rnd.nextDouble() * 1.3) to (floorY + 0.7 + rnd.nextDouble() * 1.2)
        else (0.42 + rnd.nextDouble() * 0.2) to (input.head.p[1] - 0.35 + rnd.nextDouble() * 0.45)
        val c = if (input.sixDof) origin else input.head.p
        val p = doubleArrayOf(c[0] + sin(a) * d, y, c[2] + cos(a) * d)
        return Orb(nextId++, p, p.copyOf(), rnd.nextFloat())
    }

    private fun spawnComet(head: Pose): Comet {
        val f = forwardFlat(head)
        val a = Math.atan2(f[0], f[2]) + (rnd.nextDouble() - 0.5) * 1.6
        val start = doubleArrayOf(head.p[0] + sin(a) * 5.0, head.p[1] + (rnd.nextDouble() - 0.3) * 0.5, head.p[2] + cos(a) * 5.0)
        val dir = norm(doubleArrayOf(head.p[0] - start[0], head.p[1] - start[1], head.p[2] - start[2]))
        return Comet(start, doubleArrayOf(dir[0] * COMET_SPEED, dir[1] * COMET_SPEED, dir[2] * COMET_SPEED))
    }

    /** Did the segment a->b pass through the ring's disc? */
    private fun crossed(r: Ring, a: DoubleArray, b: DoubleArray): Boolean {
        val da = (a[0] - r.p[0]) * r.normal[0] + (a[1] - r.p[1]) * r.normal[1] + (a[2] - r.p[2]) * r.normal[2]
        val db = (b[0] - r.p[0]) * r.normal[0] + (b[1] - r.p[1]) * r.normal[1] + (b[2] - r.p[2]) * r.normal[2]
        if (da * db > 0 || da == db) return false
        val t = da / (da - db)
        val hit = DoubleArray(3) { a[it] + (b[it] - a[it]) * t }
        return dist(hit, r.p) < r.radius
    }

    private fun add(points: Int) { combo++; score += points * (1 + (combo - 1).coerceAtMost(9) / 3) }

    private fun burst(p: DoubleArray, hue: Float, n: Int) {
        if (sparks.size > 400) return
        repeat(n) {
            val v = norm(doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()))
            val s = 0.3 + rnd.nextDouble() * 0.9
            sparks += Spark(p.copyOf(), doubleArrayOf(v[0] * s, v[1] * s, v[2] * s), hue, 0.5 + rnd.nextDouble() * 0.5)
        }
    }

    private fun updateSparks(dt: Double) {
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.life -= dt
            if (s.life <= 0) { it.remove(); continue }
            s.v[1] -= 0.8 * dt
            for (i in 0..2) s.p[i] += s.v[i] * dt
        }
    }

    private fun forwardFlat(head: Pose): DoubleArray {
        val f = Q.rotate(head.q, doubleArrayOf(0.0, 0.0, -1.0))
        return norm(doubleArrayOf(f[0], 0.0, f[2]))
    }

    private fun norm(v: DoubleArray): DoubleArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (n < 1e-9) doubleArrayOf(0.0, 0.0, -1.0) else doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    companion object {
        const val ROUND_SECONDS = 90.0
        const val ORB_RADIUS = 0.06
        const val ORB_LIFETIME = 14.0
        const val MAX_ORBS = 7
        const val STANDING_HEIGHT = 1.6
        const val GAZE_DWELL = 0.7
        const val GAZE_COS = 0.9925 // about 7 degrees
        const val GAZE_FALLBACK_AFTER = 4.0
        const val COMET_SPEED = 1.1
    }
}
