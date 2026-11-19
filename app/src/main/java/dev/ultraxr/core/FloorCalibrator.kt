package dev.ultraxr.core

/**
 * Watches whether the floor the cameras measure is flat, and says by how much the angle between the two cameras
 * would have to be trimmed to make it so.
 *
 * A floor is level. If the calibration's angle between the cameras (about the axis square to the baseline and the
 * view) is off by d radians, every distance Z comes out wrong by about Z * Z * d / baseline, and a floor point
 * measured from Z metres away comes out too high by Z * d * h / baseline (h: the cameras' height above the floor):
 * the floor rises, or falls, in a straight line with distance. So the slope of floor height against distance,
 * times baseline / h, is the error. Measured on the owner's glasses on 18 November 2026: +94 mm per metre with the
 * factory angle, flat with the right camera turned by -0.4 degrees (tools/tracking/floor_flat.py).
 *
 * A rig tilted against gravity gives the same straight line, so this cannot tell the two apart; it corrects the
 * thing the room needs (a flat floor), in small steps, and never far from the value its owner's unit was given.
 */
class FloorCalibrator(private val baselineM: Double) {
    private val edges = doubleArrayOf(1.5, 1.8, 2.1, 2.4, 2.7, 3.0, 3.5, 4.0)
    private val cells = 41                      // 1 cm cells from -20 to +20 cm around the floor
    private val hist = Array(edges.size - 1) { IntArray(cells) }
    private val count = IntArray(edges.size - 1)
    private var heightSum = 0.0; private var heightN = 0

    @Synchronized fun reset() { for (h in hist) h.fill(0); count.fill(0); heightSum = 0.0; heightN = 0 }

    /** One measured point: how far from the cameras it was measured, and how high it is above the floor as it is believed. */
    @Synchronized fun add(rangeM: Double, aboveFloorM: Double) {
        if (aboveFloorM < -0.20 || aboveFloorM > 0.20 || rangeM < edges[0] || rangeM >= edges[edges.size - 1]) return
        var b = 0; while (rangeM >= edges[b + 1]) b++
        hist[b][((aboveFloorM + 0.20) / 0.01).toInt().coerceIn(0, cells - 1)]++; count[b]++
    }
    /** The cameras' height above the floor at a moment points were added. */
    @Synchronized fun addCameraHeight(h: Double) { if (h > 0.5 && h < 2.5) { heightSum += h; heightN++ } }

    /** How many floor points each distance band holds, nearest first. */
    @Synchronized fun status(): String = count.joinToString(" ")

    class Result(val slopeMmPerM: Double, val errorDeg: Double, val bins: Int, val points: Int)

    /**
     * The slope of the floor with distance and the camera-angle error that explains it, or null while too little of
     * the floor has been seen at enough distances (four distance bands with 100 points each, spanning 0.9 m).
     */
    @Synchronized fun result(): Result? {
        if (heightN == 0) return null
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); var pts = 0
        for (b in hist.indices) {
            if (count[b] < MIN_PER_BIN) continue
            // the middle of the fullest 5 cm: the floor itself, not the things that stand on it
            var best = 0; var bestSum = -1
            for (i in 2 until cells - 2) { val s = hist[b][i - 2] + hist[b][i - 1] + hist[b][i] + hist[b][i + 1] + hist[b][i + 2]; if (s > bestSum) { bestSum = s; best = i } }
            if (bestSum < MIN_PER_BIN / 3) continue
            var w = 0.0; var n = 0
            for (i in best - 2..best + 2) { w += hist[b][i] * (-0.20 + (i + 0.5) * 0.01); n += hist[b][i] }
            xs.add((edges[b] + edges[b + 1]) / 2); ys.add(w / n); pts += count[b]
        }
        if (xs.size < 4 || xs.last() - xs.first() < 0.85) return null
        val mx = xs.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in xs.indices) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx) }
        val slope = sxy / sxx
        val h = heightSum / heightN
        return Result(slope * 1000.0, Math.toDegrees(slope * baselineM / h), xs.size, pts)
    }

    companion object {
        const val MIN_PER_BIN = 100
        /** An error smaller than this is left alone: it is within what one session can measure. */
        const val LEAVE_DEG = 0.04
        /** The most one session may change the correction, and the furthest it may ever be from none. */
        const val STEP_DEG = 0.06
        const val LIMIT_DEG = 1.0

        /**
         * The new correction (degrees about the right camera's y axis, as in stereo_fix_unit.txt) from the one in
         * use and the error measured with it; the same value when nothing should change.
         */
        fun trimmed(currentYDeg: Double, errorDeg: Double): Double {
            if (Math.abs(errorDeg) < LEAVE_DEG) return currentYDeg
            // half the measured error, and a small step at most: one session's floor is a few thousand points, and two
            // ways of matching the same recording differed by a tenth of a degree (18 November 2026)
            val step = (0.5 * errorDeg).coerceIn(-STEP_DEG, STEP_DEG)
            return (currentYDeg - step).coerceIn(-LIMIT_DEG, LIMIT_DEG)
        }
    }
}
