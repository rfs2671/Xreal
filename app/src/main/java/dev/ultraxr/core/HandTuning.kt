package dev.ultraxr.core

/**
 * Settings for hand tracking that are being measured rather than assumed. They come from the text file
 * "hand_options.txt" in the app's files folder (key=value lines), so a replay of a recording can be run with
 * different values over adb and the results compared, without a new build.
 *
 *   stretch=1          brighten and spread the camera pictures before the hand tracker sees them (dim rooms)
 *   quality_filter=0|1 XR Gate's hand quality filter off or on (unset: the library's own default)
 *   press_mm=33        thumb-tip to index-tip distance below which a pinch starts (measured, see PinchState)
 *   release_mm=46      distance above which it ends
 *   steady=1           smooth the hand's place and keep the pointer where it was while a pinch lands (core/HandSteady)
 *   steady_turn=1      smooth which way the hand is turned as well (core/HandTurnSteady)
 *   tiles=3            how many fisheye-corrected tiles the hand detector also looks through each time it runs on a
 *                      camera's picture, for a hand that is not being tracked (0: the whole picture only, as before
 *                      20 November 2026; unset: the library's own default)
 *   tiles_min_conf=0.5 the least confidence a tile's detection needs
 *   keep=0|1           a tracked hand goes on with one camera while the other is unsure of it for a frame (0: the
 *                      upstream rule, the hand is dropped at once; unset: the library's default, on)
 *   verify=0|1         a hand a tile found is looked at again before it is believed (unset: on)
 *   one_view=0|1       a find that passed its second looks twice may begin a hand from one camera; the hand is
 *                      reported once both cameras have been sure of it (unset: on)
 *   snap=1             while pinching, hand the application a hand whose two fingertips touch, so an
 *                      application with a tighter threshold of its own (a browser) sees the pinch too
 */
data class HandOptions(val stretch: Boolean = false, val qualityFilter: Boolean? = null, val pressM: Float = PinchState.PRESS_M, val releaseM: Float = PinchState.RELEASE_M, val snap: Boolean = true, val steady: Boolean = true, val steadyTurn: Boolean = true,
    val tiles: Int? = null, val tilesMinConf: Float? = null, val keep: Boolean? = null, val verify: Boolean? = null, val oneView: Boolean? = null) {
    companion object {
        fun parse(text: String?): HandOptions {
            var o = HandOptions()
            for (line in (text ?: "").lines()) {
                val k = line.substringBefore('=').trim(); val v = line.substringAfter('=', "").trim()
                if (k.isEmpty() || k.startsWith("#") || v.isEmpty()) continue
                o = when (k) {
                    "stretch" -> o.copy(stretch = v == "1")
                    "quality_filter" -> o.copy(qualityFilter = v == "1")
                    "press_mm" -> v.toFloatOrNull()?.takeIf { it in 5f..60f }?.let { o.copy(pressM = it / 1000f) } ?: o
                    "release_mm" -> v.toFloatOrNull()?.takeIf { it in 8f..90f }?.let { o.copy(releaseM = it / 1000f) } ?: o
                    "snap" -> o.copy(snap = v == "1")
                    "steady" -> o.copy(steady = v == "1")
                    "steady_turn" -> o.copy(steadyTurn = v == "1")
                    "tiles" -> v.toIntOrNull()?.takeIf { it in 0..12 }?.let { o.copy(tiles = it) } ?: o
                    "keep" -> o.copy(keep = v == "1")
                    "verify" -> o.copy(verify = v == "1")
                    "one_view" -> o.copy(oneView = v == "1")
                    "tiles_min_conf" -> v.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { o.copy(tilesMinConf = it) } ?: o
                    else -> o
                }
            }
            // A release distance at or below the press distance would make the pinch chatter.
            return if (o.releaseM <= o.pressM) o.copy(releaseM = o.pressM * 1.6f) else o
        }
    }
}

object HandImage {
    /**
     * Makes a dim grey picture easier for the hand detector: the darkest 2 % becomes black, the level that 95 % of
     * the picture is below becomes white, and the middle is lifted (gamma [gamma]). In place. Lamps and screens in
     * view are brighter than that level and simply stay white, so they do not hold the rest of the picture down.
     */
    fun stretch(img: ByteArray, gamma: Double = 0.6) {
        val hist = IntArray(256); var n = 0
        var i = 0
        while (i < img.size) { hist[img[i].toInt() and 0xFF]++; n++; i += 7 }
        var lo = 0; var hi = 255; var acc = 0
        for (v in 0..255) { acc += hist[v]; if (acc >= n * 0.02) { lo = v; break } }
        acc = 0
        for (v in 0..255) { acc += hist[v]; if (acc >= n * 0.95) { hi = v; break } }
        if (hi < lo + 40) hi = lo + 40
        val lut = ByteArray(256)
        for (v in 0..255) {
            val x = ((v - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
            lut[v] = (255.0 * Math.pow(x, gamma) + 0.5).toInt().coerceIn(0, 255).toByte()
        }
        for (k in img.indices) img[k] = lut[img[k].toInt() and 0xFF]
    }
}
