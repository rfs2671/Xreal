package dev.ultraxr.desktop

import kotlin.math.hypot

/** One thing the in-glasses home can open: a WebXR game (started in the XR browser) or a web app (opened as a window). */
class HomeItem(val title: String, val kind: Kind, val url: String, val color: Int, val webKind: String = "web",
    val enter: List<List<Double>> = emptyList(), val verified: Boolean = false, val icon: android.graphics.Bitmap? = null) {
    /** GAME: a WebXR game. WEB: a site (or the installed app that stands for it). APP: an installed app, [url] is "app://package/activity". NAV: another page of the home view. */
    enum class Kind { GAME, WEB, APP, NAV }
}

/**
 * The home view in the glasses: round icons floating in staggered rows, with nothing behind them. It is drawn with
 * light on black, because black is see-through on the glasses, so "glass" here is a faint milky fill and a bright
 * rim, never a shadow.
 *
 * The geometry is plain numbers so that where an icon is drawn and where a look lands on it cannot disagree: both
 * come from [centres]. Pixels are those of the home bitmap ([PX_W] x [PX_H]), which the renderer stretches over
 * [WIDTH_M] metres at the home's distance.
 */
object HomeGrid {
    const val PX_W = 1600
    const val PX_H = 900
    /** 1.3 m wide at 2 m is 36 degrees: inside the glasses' field of view with room to spare. */
    const val WIDTH_M = 1.3
    const val HEIGHT_M = WIDTH_M * PX_H / PX_W
    const val ICON_R = 84f
    const val PITCH_X = 236f
    const val PITCH_Y = 252f
    /** Looking at the icon or its label counts: this far from the icon's centre, in icon radii. */
    const val REACH = 1.38f
    /** Three rows (4, 5, 4) are what fits the picture with room for names: the home shows this many at most. */
    const val MAX = 13

    /** How many icons go in each row: rows of four and five alternate, like bricks, and the last row takes the rest. */
    fun rows(n: Int): IntArray {
        if (n <= 0) return IntArray(0)
        if (n <= 5) return intArrayOf(n)
        val out = ArrayList<Int>(); var left = n; var four = true
        while (left > 0) { val take = minOf(left, if (four) 4 else 5); out += take; left -= take; four = !four }
        // a lone icon on the last row looks dropped: give it a neighbour from the row above
        if (out.size >= 2 && out.last() == 1 && out[out.size - 2] > 2) { out[out.size - 2]--; out[out.size - 1]++ }
        return out.toIntArray()
    }

    /** Icon centres in bitmap pixels, in order. Rows are centred; the block of rows is centred a little above the middle to leave room for labels. */
    fun centres(n: Int): List<FloatArray> {
        val r = rows(n); val out = ArrayList<FloatArray>(n)
        val top = PX_H / 2f - (r.size - 1) * PITCH_Y / 2f - 18f
        for ((row, count) in r.withIndex()) {
            val y = top + row * PITCH_Y
            val left = PX_W / 2f - (count - 1) * PITCH_X / 2f
            for (i in 0 until count) out += floatArrayOf(left + i * PITCH_X, y)
        }
        return out
    }

    /**
     * The icon a look lands on. ([xM], [yM]) is where the line of sight meets the home's plane, in metres right and
     * up of its centre. Null when it is on none.
     */
    fun hit(xM: Double, yM: Double, n: Int): Int? {
        val px = ((xM / WIDTH_M + 0.5) * PX_W).toFloat(); val py = ((0.5 - yM / HEIGHT_M) * PX_H).toFloat()
        var best = -1; var bestD = Float.MAX_VALUE
        for ((i, c) in centres(n).withIndex()) {
            // the label hangs below the icon, so the catch area reaches further down than up
            val d = hypot(px - c[0], (py - (c[1] + ICON_R * 0.25f)) * 0.85f)
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD <= ICON_R * REACH) best else null
    }
}
