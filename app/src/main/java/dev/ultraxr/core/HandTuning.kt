package dev.ultraxr.core

/**
 * Settings for hand tracking that are being measured rather than assumed. They come from the text file
 * "hand_options.txt" in the app's files folder (key=value lines), so a replay of a recording can be run with
 * different values over adb and the results compared, without a new build.
 *
 *   stretch=1          brighten and spread the camera pictures before the hand tracker sees them (dim rooms)
 *   quality_filter=0|1 XR Gate's hand quality filter off or on (unset: the library's own default)
 *   press_mm=18        thumb-tip to index-tip distance below which a pinch starts
 *   release_mm=32      distance above which it ends
 *   snap=1             while pinching, hand the application a hand whose two fingertips touch, so an
 *                      application with a tighter threshold of its own (a browser) sees the pinch too
 */
data class HandOptions(val stretch: Boolean = false, val qualityFilter: Boolean? = null, val pressM: Float = 0.018f, val releaseM: Float = 0.032f, val snap: Boolean = true) {
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
