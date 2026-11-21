package dev.ultraxr.core

/**
 * Ties the app's world to the saved map of the room (native/slam).
 *
 * The map library says where the estimator's world lies in the map's ([corr], both with z up). The head tracker says
 * where the estimator's world (turned y-up) lies in the app's ([fix]). Together they say where the map lies in the
 * app's world: fix * Z_TO_Y * corr^-1. The first time that is known in a session it is taken as it is ([appFromMap]):
 * things saved in map coordinates can be put back where they were. After that the map should stay where it is in the
 * app's world; when it is seen somewhere else, the estimator has drifted or position was lost and joined on wrongly,
 * and the app's world is moved by the difference: a small one in steps (a quarter of what is left, between 1.5 and
 * 4 cm, each spread over a third of a second by the caller), so that nothing is seen to jump, a large one at once,
 * because what is shown is then plainly in the wrong place anyway.
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
        // a quarter of what is left at a time, but never less than the smallest step nor more than the largest: a few
        // centimetres are gone in a few readings, and the caller spreads each step over a third of a second
        val stepM = (off * STEP_SHARE).coerceIn(STEP_M, STEP_MAX_M); val stepRad = (turn * STEP_SHARE).coerceIn(STEP_RAD, STEP_MAX_RAD)
        val f = minOf(1.0, stepM / maxOf(off, 1e-9), stepRad / maxOf(turn, 1e-9))
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

    // ---- after a loss: where must a NEW world of the estimator's lie for the map to stay where it is held?
    private val fresh = ArrayList<Pose>()
    /** Readings of the estimator's new world, before it is used, are forgotten (it was started afresh again, or is in use now). */
    fun restoreClear() { fresh.clear() }
    /**
     * A reading of where the estimator's new world lies in the map ([corr]), taken while its position is not yet used.
     * Returns where that world must lie in the app's world for the map to be where it is held, once [RESTORE_AGREE]
     * readings agree about the point [near] (the head, in the estimator's world, z up); null until then, or with no map
     * held. The caller joins the new world on there ([HeadTracker.joinNextAt]): the wearer is put where the map has them,
     * not where they were when position was lost.
     */
    fun restoreReading(corr: Pose, near: DoubleArray): Pose? {
        val held = appFromMap ?: return null
        fresh.add(corr); if (fresh.size > KEEP) fresh.removeAt(0)
        var best: Pose? = null; var bestN = 0
        for (a in fresh) {
            var n = 0; val pa = a.apply(near)
            for (b in fresh) { if (a === b) continue; val pb = b.apply(near)
                val d = Math.sqrt((pa[0] - pb[0]) * (pa[0] - pb[0]) + (pa[1] - pb[1]) * (pa[1] - pb[1]) + (pa[2] - pb[2]) * (pa[2] - pb[2]))
                // both worlds have z up: the turn between two readings is about z
                val rel = Q.mul(a.q, Q.conj(b.q)); val turn = 2 * Math.abs(Math.atan2(rel[2], rel[3])).let { if (it > Math.PI / 2) Math.PI - it else it }
                if (d < AGREE_M && turn < AGREE_RAD) n++ }
            if (n > bestN) { bestN = n; best = a }
        }
        if (bestN < RESTORE_AGREE - 1 || best == null) return null
        fresh.clear(); recent.clear()
        // seen(fix, corr) == held  <=>  fix = held * zToY * corr * zToY^-1
        return flat(held * Pose(q = zToY) * best * Pose(q = Q.conj(zToY)))
    }

    /** A new session of the estimator or a new map: nothing is known. */
    fun clear() { appFromMap = null; lastOffM = 0.0; lastOffDeg = 0.0; recent.clear(); fresh.clear() }

    private fun flat(p: Pose) = Pose(p.p, Q.axisAngle(0.0, 1.0, 0.0, Q.yawY(p.q)))

    companion object {
        const val IGNORE_M = 0.02
        val IGNORE_RAD = Math.toRadians(0.3)
        const val STEP_M = 0.015
        val STEP_RAD = Math.toRadians(0.25)
        const val KEEP = 5
        /** Readings of a new world that must agree before it is joined on by them: two, for the sake of the second it saves. */
        const val RESTORE_AGREE = 2
        const val AGREE_OTHERS = 2
        const val AGREE_M = 0.12
        val AGREE_RAD = Math.toRadians(3.0)
        const val STEP_SHARE = 0.25
        const val STEP_MAX_M = 0.04
        val STEP_MAX_RAD = Math.toRadians(1.0)
        const val AT_ONCE_M = 0.30
        val AT_ONCE_RAD = Math.toRadians(6.0)

        /**
         * The fraction [f] of a move, as seen from [near]: the same turn scaled down, and the point [near] carried the
         * same fraction of the way. A step is made in many such parts, a frame or two apart, so that no single frame
         * shows it.
         */
        fun part(move: Pose, near: DoubleArray, f: Double): Pose {
            val there = move.apply(near); val q = Q.axisAngle(0.0, 1.0, 0.0, Q.yawY(move.q) * f)
            val r = Q.rotate(q, near)
            return Pose(doubleArrayOf(near[0] + (there[0] - near[0]) * f - r[0], near[1] + (there[1] - near[1]) * f - r[1], near[2] + (there[2] - near[2]) * f - r[2]), q)
        }

        /**
         * One of [n] equal slices of a move (a turn about the vertical and a shift): made [n] times over it is the
         * move, exactly. A turn and a shift along the floor are one turn about some upright line; each slice is the
         * n-th part of that turn about the same line (and of the rise), so everything travels on an arc from where it
         * was to where the move puts it. With no turn to speak of it is the n-th part of the shift.
         */
        fun slice(move: Pose, n: Int): Pose {
            val th = Q.yawY(move.q); val t = move.p
            if (Math.abs(th) < 1e-5) return Pose(doubleArrayOf(t[0] / n, t[1] / n, t[2] / n), Q.axisAngle(0.0, 1.0, 0.0, th / n))
            // the line that stays put: (I - R) c = t, with R the turn by th about +y (x' = cos x + sin z, z' = -sin x + cos z)
            val c = Math.cos(th); val s = Math.sin(th); val det = 2 * (1 - c)
            val cx = ((1 - c) * t[0] + s * t[2]) / det; val cz = (-s * t[0] + (1 - c) * t[2]) / det
            val q = Q.axisAngle(0.0, 1.0, 0.0, th / n); val r = Q.rotate(q, doubleArrayOf(cx, 0.0, cz))
            return Pose(doubleArrayOf(cx - r[0], t[1] / n, cz - r[2]), q)
        }
        /** How many slices a move takes at no more than [partM] metres (of the point [near]) and [partRad] of turn a slice. */
        fun slices(move: Pose, near: DoubleArray, partM: Double, partRad: Double, least: Int, most: Int): Int {
            val to = move.apply(near); val d = Math.sqrt((to[0] - near[0]) * (to[0] - near[0]) + (to[1] - near[1]) * (to[1] - near[1]) + (to[2] - near[2]) * (to[2] - near[2]))
            return Math.ceil(maxOf(d / partM, Math.abs(Q.yawY(move.q)) / partRad)).toInt().coerceIn(least, most)
        }

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
