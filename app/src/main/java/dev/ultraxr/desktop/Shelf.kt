package dev.ultraxr.desktop

import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.tan

/** One thing the shelf can open: a WebXR game (started in the XR browser), a web app or an installed app (opened as a window), or another page of the shelf. */
class HomeItem(val title: String, val kind: Kind, val url: String, val color: Int, val webKind: String = "web",
    val enter: List<List<Double>> = emptyList(), val verified: Boolean = false) {
    /** GAME: a WebXR game. WEB: a site (or the installed app that stands for it). APP: an installed app, [url] is "app://package/activity". NAV: another page of the shelf, or something the desktop does itself. */
    enum class Kind { GAME, WEB, APP, NAV }
}

/**
 * Sizes in the glasses are angles, not pixels (docs/INTERFACE.md, "Type"): how large a thing looks does not depend on
 * how far away it is drawn. These are the angles everything the desktop draws is measured in, in radians.
 */
object Sight {
    /** The x-height of body text, a window's title and the handle's words: 0.55 degrees. */
    val BODY = Math.toRadians(0.55)
    /** The x-height of the name under the focused mark: 0.7 degrees. */
    val NAME = Math.toRadians(0.7)
    /** A resting line (a bracket of a window not in focus), and the one in focus, which is thicker. */
    val LINE = Math.toRadians(0.075)
    val LINE_LIT = Math.toRadians(0.13)
    /** A page's corners are rounded by this much, and the light a window in focus stands in reaches this far out. */
    val CORNER = Math.toRadians(0.45)
    val HALO = Math.toRadians(1.1)
    /** A piece of text is drawn on a strip this many x-heights high, the x-height in its middle. */
    const val STRIP = 3.0
    /** The space between a page's corner and its bracket. */
    val BRACKET_GAP = Math.toRadians(0.2)
    /** How far above the top bracket the middle of a title's strip is, in strip heights: just far enough for the letters' tails to clear the bracket. */
    const val TITLE_RISE = 0.38

    /** The length, in metres, that looks [angle] long at [distance] metres. */
    fun metres(angle: Double, distance: Double) = distance * tan(angle)
}

/**
 * The shelf (docs/INTERFACE.md, "In the glasses"): home is one row of marks on an arc round the wearer, [DISTANCE]
 * metres away and 8 degrees under the line of sight, the same whether or not windows are open. At most [IN_VIEW] marks
 * are in view; the row slides when the focus passes its end. The mark in focus is a quarter larger and has its name
 * beneath it. The status line stands on the same arc at the top of the view.
 *
 * Plain numbers, so that where a mark is drawn and where a ray or a look finds it cannot disagree: both come from
 * [markCentre]. The shelf faces the heading the followed windows are placed against ([FollowAnchor]).
 */
object Shelf {
    const val DISTANCE = 2.0
    val PITCH = Math.toRadians(-8.0)
    /** From one mark to the next round the wearer: seven marks span 30 degrees of the 46 an eye sees. */
    val SPACING = Math.toRadians(5.0)
    const val IN_VIEW = 7
    /** A mark is 3 degrees across. */
    val MARK_ANGLE = Math.toRadians(1.5)
    val MARK_RADIUS = DISTANCE * tan(MARK_ANGLE)
    const val FOCUS_SCALE = 1.25
    /** A ray this far from a mark's middle is still on it: a little more than the mark, less than half way to the next. */
    val REACH = MARK_RADIUS * 1.45
    /** The name under the mark in focus is no wider than this (it is shortened if it would be), so that the name of the last mark in view stays in view. */
    val NAME_WIDTH = Math.toRadians(10.0)
    /** The middle of the name's strip: under the enlarged mark, with a little air. */
    val NAME_PITCH = PITCH - MARK_ANGLE * FOCUS_SCALE - Math.toRadians(0.3) - Sight.NAME * Sight.STRIP / 2
    /** The middle of the status line: its text ends half a degree inside the top of the picture (12.6 degrees up). */
    val STATUS_PITCH = Math.toRadians(11.5)
    /** Nothing of a window in focus (its handle) comes lower than this, so that it stays clear of the resting marks. */
    val TOP = PITCH + MARK_ANGLE * DOCK + Math.toRadians(0.2)
    /** The dock's pane of glass reaches this many mark radii above and below the middle of the row: the marks and the dots under them are on it. */
    const val DOCK = 1.3

    /** How many marks are in view of [count]. */
    fun shown(count: Int) = minOf(maxOf(count, 0), IN_VIEW)

    /**
     * The first mark in view, given the mark in focus and the first that was in view before: the row slides only as
     * far as it must for the mark in focus to be in view.
     */
    fun first(focus: Int, first: Int, count: Int): Int {
        val last = maxOf(0, count - IN_VIEW)
        val f = first.coerceIn(0, last)
        return when { focus < f -> focus.coerceAtLeast(0); focus > f + IN_VIEW - 1 -> (focus - IN_VIEW + 1).coerceAtMost(last); else -> f }
    }

    /** Where place [slot] of [shown] stands against the shelf's heading (yaw grows to the left, so the first place is the leftmost). */
    fun yawOffset(slot: Double, shown: Int) = ((shown - 1) / 2.0 - slot) * SPACING

    /** The middle of the mark in place [slot], in the world. */
    fun markCentre(anchor: DoubleArray, shelfYaw: Double, slot: Double, shown: Int) = Geometry.centre(anchor, Geometry.wrap(shelfYaw + yawOffset(slot, shown)), PITCH, DISTANCE)

    /** The mark a ray is on, of [count] marks of which [first] is the first in view, or null. */
    fun hit(origin: DoubleArray, dir: DoubleArray, anchor: DoubleArray, shelfYaw: Double, first: Int, count: Int): Int? {
        val n = shown(count); var best = -1; var bestD = Double.MAX_VALUE
        for (slot in 0 until n) {
            val yaw = Geometry.wrap(shelfYaw + yawOffset(slot.toDouble(), n))
            val h = Geometry.planeHit(origin, dir, Geometry.centre(anchor, yaw, PITCH, DISTANCE), yaw, PITCH) ?: continue
            val d = hypot(h[0], h[1])
            if (d < bestD) { bestD = d; best = slot }
        }
        return if (best >= 0 && bestD <= REACH) first.coerceIn(0, maxOf(0, count - n)) + best else null
    }

    /**
     * How high the middle of a window is put when it is opened, so that all of it is in view at once with the shelf
     * and the status line: its handle ends just above the resting marks. [below] is how far the handle's lower edge is
     * under the window's middle, metres.
     */
    fun clearPitch(below: Double, distance: Double) = TOP + atan(below / distance)
}

/**
 * What moves, and for how long (docs/INTERFACE.md, "Opening and closing"): a window grows from its mark in 180 ms and
 * shrinks back into it in 140 ms, its brackets draw themselves last, the focus moves in 90 ms. Nothing else moves by
 * itself, and with "reduce motion" nothing moves at all: [still] is then true and every motion is at its end at once.
 */
object Motion {
    const val OPEN_NS = 180_000_000L
    const val CLOSE_NS = 140_000_000L
    const val FOCUS_NS = 90_000_000L
    /** The brackets draw themselves when the window has grown. */
    const val BRACKET_NS = 90_000_000L

    /** How far a motion that began at [startNs] has got, 0..1. */
    fun progress(startNs: Long, nowNs: Long, lengthNs: Long, still: Boolean): Double = if (still || lengthNs <= 0) 1.0 else ((nowNs - startNs).toDouble() / lengthNs).coerceIn(0.0, 1.0)

    /** Quick at first, settling at the end. */
    fun ease(t: Double): Double { val u = 1 - t.coerceIn(0.0, 1.0); return 1 - u * u * u }

    fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t
}

/** A number that goes to where it is sent in a fixed time, from wherever it was. */
class Glide(var value: Double) {
    private var from = value; private var target = value; private var startNs = 0L

    fun toward(to: Double, nowNs: Long, lengthNs: Long, still: Boolean): Double {
        if (to != target) { from = value; target = to; startNs = nowNs }
        value = if (still) target else Motion.mix(from, target, Motion.ease(Motion.progress(startNs, nowNs, lengthNs, false)))
        return value
    }

    val resting: Boolean get() = value == target
}
