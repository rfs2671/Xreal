package dev.ultraxr.core

/**
 * Ties the app's world to the saved map of the room (native/slam).
 *
 * The map library says where the estimator's world lies in the map's ([corr], both with z up). The head tracker says
 * where the estimator's world (turned y-up) lies in the app's ([fix]). Together they say where the map lies in the
 * app's world: fix * Z_TO_Y * corr^-1. The first time that is known in a session it is taken as it is ([appFromMap]):
 * things saved in map coordinates can be put back where they were. After that the map should stay where it is in the
 * app's world; when it is seen somewhere else, the estimator has drifted or position was lost and joined on wrongly,
 * and the app's world is moved by the difference: a small one in small steps, so that nothing is seen to jump, a large
 * one at once, because what is shown is then plainly in the wrong place anyway.
 *
 * Both worlds have the same "up", so a move is a turn about the vertical and a shift; whatever tilt the difference
 * holds is noise and is dropped.
 *
 * One reading is not believed. Measured on the phone (20 November 2026, docs/SPATIAL.md): single readings of one
 * session scatter by 3 cm (median) and a few degrees, and one in fifty was 33 cm out. So the last few are kept, and
 * what is used is the one of them the others agree with best, once at least two others agree with it.
 */
class MapAlign(private val zToY: DoubleArray) {
    /** The map's world (turned y-up) in the app's world, once known in this session. */
    var appFromMap: Pose? = null; private set
    /** Counts first alignments and large corrections: things placed from the map are placed again. */
    var version = 0; private set
    var steps = 0; private set
    var lastOffM = 0.0; private set
    var lastOffDeg = 0.0; private set

    private val recent = ArrayDeque<Pose>()
    /** Readings taken in so far that had too few others agreeing: none was used. */
    var unsure = 0; private set

    private fun apart(a: Pose, b: Pose, near: DoubleArray): Double { val there = b.apply(a.inverse().apply(near)); return Math.sqrt((there[0] - near[0]) * (there[0] - near[0]) + (there[1] - near[1]) * (there[1] - near[1]) + (there[2] - near[2]) * (there[2] - near[2])) }
    private fun turned(a: Pose, b: Pose) = Math.abs(Math.IEEEremainder(Q.yawY(a.q) - Q.yawY(b.q), 2 * Math.PI))
    /** The recent reading most of the others agree with, about the point [near]; null while fewer than two others agree with any. */
    private fun agreed(near: DoubleArray): Pose? {
        var best: Pose? = null; var bestN = 0; var bestSum = Double.MAX_VALUE
        for (a in recent) {
            var n = 0; var sum = 0.0
            for (b in recent) { if (a === b) continue; val d = apart(a, b, near); if (d < AGREE_M && turned(a, b) < AGREE_RAD) { n++; sum += d } }
            if (n > bestN || (n == bestN && n > 0 && sum < bestSum)) { best = a; bestN = n; bestSum = sum }
        }
        return if (bestN >= AGREE_OTHERS) best else null
    }

    /** Where the map's world (y-up) lies in the app's, by this reading. */
    fun seen(fix: Pose, corr: Pose): Pose = fix * Pose(q = zToY) * corr.inverse() * Pose(q = Q.conj(zToY))

    /**
     * A new reading from the map. Returns the move to make to the app's world now (every point p of it goes to the
     * move applied to p), or null for none. After a move the caller's [fix] is the move times the old one.
     */
    fun onReading(fix: Pose, corr: Pose): Pose? {
        recent.addLast(flat(seen(fix, corr))); if (recent.size > KEEP) recent.removeFirst()
        val now = agreed(fix.p) ?: run { unsure++; return null }
        val held = appFromMap
        if (held == null) { appFromMap = now; version++; lastOffM = 0.0; lastOffDeg = 0.0; return null }
        val whole = flat(held * now.inverse())
        // how far the wearer's surroundings are off: the shift of the map's origin says little by itself when the turn
        // is large and the origin far, so the measure is taken where the map is looked at from, the origin of [fix]'s
        // estimator world being as good a nearby point as any
        val near = fix.p; val there = whole.apply(near)
        val off = Math.sqrt((there[0] - near[0]) * (there[0] - near[0]) + (there[1] - near[1]) * (there[1] - near[1]) + (there[2] - near[2]) * (there[2] - near[2]))
        val turn = Math.abs(Q.yawY(whole.q))
        lastOffM = off; lastOffDeg = Math.toDegrees(turn)
        if (off < IGNORE_M && turn < IGNORE_RAD) return null
        steps++
        if (off > AT_ONCE_M || turn > AT_ONCE_RAD) { version++; return whole }
        // a part of it: the same turn and shift scaled down, about the nearby point, so that it moves by no more than a step
        val f = minOf(1.0, STEP_M / maxOf(off, 1e-9), STEP_RAD / maxOf(turn, 1e-9))
        val q = Q.axisAngle(0.0, 1.0, 0.0, Q.yawY(whole.q) * f)
        val target = doubleArrayOf(near[0] + (there[0] - near[0]) * f, near[1] + (there[1] - near[1]) * f, near[2] + (there[2] - near[2]) * f)
        val r = Q.rotate(q, near)
        return Pose(doubleArrayOf(target[0] - r[0], target[1] - r[1], target[2] - r[2]), q)
    }

    /**
     * The app's world was moved. By something else (a recentre): the map goes with it. By a move this class asked
     * for ([own]): the map stays where it is held, and only the readings kept, which were taken in the world as it
     * was, are carried over.
     */
    fun onWorldMoved(moved: Pose, own: Boolean = false) {
        if (!own) appFromMap = appFromMap?.let { flat(moved * it) }
        val carried = recent.map { flat(moved * it) }; recent.clear(); recent.addAll(carried)
    }

    /** A new session of the estimator or a new map: nothing is known. */
    fun clear() { appFromMap = null; lastOffM = 0.0; lastOffDeg = 0.0; recent.clear() }

    private fun flat(p: Pose) = Pose(p.p, Q.axisAngle(0.0, 1.0, 0.0, Q.yawY(p.q)))

    companion object {
        const val IGNORE_M = 0.02
        val IGNORE_RAD = Math.toRadians(0.3)
        const val STEP_M = 0.015
        val STEP_RAD = Math.toRadians(0.25)
        const val KEEP = 5
        const val AGREE_OTHERS = 2
        const val AGREE_M = 0.12
        val AGREE_RAD = Math.toRadians(3.0)
        const val AT_ONCE_M = 0.30
        val AT_ONCE_RAD = Math.toRadians(6.0)

        /** A place and heading in the app's world as map coordinates (x, y, z, yaw), and back. */
        fun toMap(appFromMap: Pose, p: DoubleArray, yaw: Double): DoubleArray {
            val inv = appFromMap.inverse(); val m = inv.apply(p)
            return doubleArrayOf(m[0], m[1], m[2], yaw + Q.yawY(inv.q))
        }
        fun fromMap(appFromMap: Pose, m: DoubleArray): DoubleArray {
            val p = appFromMap.apply(doubleArrayOf(m[0], m[1], m[2]))
            return doubleArrayOf(p[0], p[1], p[2], m[3] + Q.yawY(appFromMap.q))
        }
    }
}
