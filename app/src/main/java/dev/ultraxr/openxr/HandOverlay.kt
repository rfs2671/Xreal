// Ultra XR: the wearer's hands drawn over an OpenXR game's picture in the glasses. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.openxr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.ultraxr.core.Q
import dev.ultraxr.desktop.Stereo
import dev.ultraxr.tracking.Tracking
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A game that draws no hands of its own (Resonance did not) gives the wearer no sign that the glasses see them. This draws the tracked hands, 26 joints and the bones between
 * them, in a transparent surface over the game's picture, once for each eye, from the same head pose, field of view and eye distance the desktop uses. The game's own picture
 * is untouched, and the game does not know. Off for a browser that draws its own hands, or when asked ("hand_overlay=0" in hand_options.txt).
 */
class HandOverlay(context: Context) {
    val view = SurfaceView(context)
    private val thread = HandlerThread("hand-overlay", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var holder: SurfaceHolder? = null
    @Volatile private var running = false

    private val head = FloatArray(16); private val inv = FloatArray(16)
    private val eyeP = DoubleArray(3)
    private val v4 = FloatArray(4); private val out4 = FloatArray(4)
    private val px = FloatArray(26 * 2); private val depth = FloatArray(26); private val ok = BooleanArray(26)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        view.setZOrderOnTop(true)
        view.holder.setFormat(PixelFormat.TRANSLUCENT)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { holder = h; start() }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) { holder = h }
            override fun surfaceDestroyed(h: SurfaceHolder) { running = false; holder = null }
        })
    }

    private fun start() { if (running) return; running = true; handler.post(tick) }
    fun stop() { running = false; handler.removeCallbacksAndMessages(null); thread.quitSafely() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val t0 = System.nanoTime()
            try { draw() } catch (_: Throwable) {}
            // about 60 a second: the hands move at 30, the head at the glasses' rate
            handler.postDelayed(this, max(1L, 16L - (System.nanoTime() - t0) / 1_000_000L))
        }
    }

    private fun draw() {
        val h = holder ?: return
        val canvas: Canvas = try { h.surface.lockHardwareCanvas() } catch (_: Throwable) { return } ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val w = canvas.width; val ht = canvas.height
            val now = System.nanoTime()
            val newest = Tracking.handJoints.maxOfOrNull { it.timestampNs } ?: 0L
            if (newest == 0L || now - newest > 300_000_000L) return
            val sight = Tracking.head.predictSight(now + 25_000_000L)
            if (sight.mode == dev.ultraxr.core.HeadTracker.Mode.NONE) return
            val tanH = Tracking.displayTanH; val tanV = Tracking.displayTanV
            val ipd = Tracking.settings.ipdMm / 1000.0
            val two = w >= ht * 3
            val right = Q.rotate(sight.pose.q, doubleArrayOf(1.0, 0.0, 0.0))
            for (half in 0 until (if (two) 2 else 1)) {
                val off = if (two) Stereo.eyeOffset(half, ipd, false) else 0.0
                val shift = if (two) Stereo.shift(off) else 0.0
                eyeP[0] = sight.pose.p[0] + right[0] * off; eyeP[1] = sight.pose.p[1] + right[1] * off; eyeP[2] = sight.pose.p[2] + right[2] * off
                Q.toMatrix(sight.pose.q, eyeP, head); Matrix.invertM(inv, 0, head, 0)
                val vw = if (two) w / 2 else w
                val ox = if (two) half * vw else 0
                canvas.save(); canvas.clipRect(ox.toFloat(), 0f, (ox + vw).toFloat(), ht.toFloat())
                for (side in 0..1) {
                    val j = Tracking.handPoses26(side) ?: continue
                    drawHand(canvas, j, side, vw, ht, ox, tanH, tanV, shift)
                }
                canvas.restore()
            }
        } finally { try { h.surface.unlockCanvasAndPost(canvas) } catch (_: Throwable) {} }
    }

    private fun project(j: FloatArray, i: Int, vw: Int, ht: Int, ox: Int, tanH: Double, tanV: Double, shift: Double) {
        v4[0] = j[i * 7]; v4[1] = j[i * 7 + 1]; v4[2] = j[i * 7 + 2]; v4[3] = 1f
        Matrix.multiplyMV(out4, 0, inv, 0, v4, 0)
        val d = -out4[2]
        depth[i] = d
        if (d < 0.05f) { ok[i] = false; return }
        ok[i] = true
        val xn = ((out4[0] / d) - shift) / tanH; val yn = (out4[1] / d) / tanV
        px[i * 2] = (ox + (xn * 0.5 + 0.5) * vw).toFloat(); px[i * 2 + 1] = ((0.5 - yn * 0.5) * ht).toFloat()
    }

    private fun drawHand(canvas: Canvas, j: FloatArray, side: Int, vw: Int, ht: Int, ox: Int, tanH: Double, tanV: Double, shift: Double) {
        for (i in 0 until 26) project(j, i, vw, ht, ox, tanH, tanV, shift)
        // the metres to pixels factor at a given depth: the picture is 2 * tanH wide at one metre
        val pxPerM = (vw / (2 * tanH)).toFloat()
        val tt = THUMB_TIP * 7; val it = INDEX_TIP * 7
        val gap = sqrt(((j[tt] - j[it]) * (j[tt] - j[it]) + (j[tt + 1] - j[it + 1]) * (j[tt + 1] - j[it + 1]) + (j[tt + 2] - j[it + 2]) * (j[tt + 2] - j[it + 2])).toDouble())
        val pinch = gap < 0.03
        val base = if (side == 0) intArrayOf(90, 210, 255) else intArrayOf(255, 190, 70)
        val c = if (pinch) Color.argb(255, 255, 255, 255) else Color.argb(235, base[0], base[1], base[2])
        line.color = Color.argb(200, base[0], base[1], base[2]); dot.color = c
        for (chain in CHAINS) {
            for (k in 0 until chain.size - 1) {
                val a = chain[k]; val b = chain[k + 1]
                if (!ok[a] || !ok[b]) continue
                line.strokeWidth = max(2f, 0.006f * pxPerM / max(depth[a], 0.2f))
                canvas.drawLine(px[a * 2], px[a * 2 + 1], px[b * 2], px[b * 2 + 1], line)
            }
        }
        for (i in 0 until 26) {
            if (!ok[i]) continue
            val tip = i == THUMB_TIP || i == INDEX_TIP || i == MIDDLE_TIP || i == RING_TIP || i == LITTLE_TIP
            val r = max(2.5f, (if (tip) 0.0065f else 0.0050f) * pxPerM / max(depth[i], 0.2f))
            canvas.drawCircle(px[i * 2], px[i * 2 + 1], r, dot)
        }
    }

    companion object {
        // XR_EXT_hand_tracking joint order: palm, wrist, thumb (metacarpal, proximal, distal, tip), index (metacarpal, proximal, intermediate, distal, tip), middle, ring, little
        private const val THUMB_TIP = 5; private const val INDEX_TIP = 10; private const val MIDDLE_TIP = 15; private const val RING_TIP = 20; private const val LITTLE_TIP = 25
        private val CHAINS = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2, 3, 4, 5), intArrayOf(1, 6, 7, 8, 9, 10), intArrayOf(1, 11, 12, 13, 14, 15), intArrayOf(1, 16, 17, 18, 19, 20), intArrayOf(1, 21, 22, 23, 24, 25),
            intArrayOf(6, 11, 16, 21),     // across the knuckles
        )

        /** Whether this application's picture gets the hands drawn over it: not a browser that draws its own, not when switched off ("hand_overlay=0" in hand_options.txt, or a package named in "hand_overlay_skip="). */
        fun wanted(pkg: String): Boolean {
            if (!Tracking.handOverlayOn) return false
            if (pkg.startsWith("com.igalia.wolvic")) return false
            return Tracking.handOverlaySkip.none { it.isNotBlank() && pkg == it.trim() }
        }
    }
}
