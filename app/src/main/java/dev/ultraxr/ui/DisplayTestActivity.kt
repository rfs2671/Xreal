package dev.ultraxr.ui

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ultraxr.core.Fmt
import dev.ultraxr.glasses.DisplayDiagnostics
import dev.ultraxr.proto.McuProtocol
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.ui.Ui.add

/**
 * Display bring-up screen. Puts a bright, unmistakable test pattern on the glasses with the
 * plainest possible path (an ordinary Android view, no OpenGL, no tracking) in a stable 2D
 * 1920x1080@60 mode, and offers one-step experiments. Nothing here loops or toggles by itself:
 * every change is a button press, and every press and its outcome goes into the report.
 */
class DisplayTestActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var displays: DisplayManager
    private lateinit var status: TextView
    private var pattern: PatternPresentation? = null
    private var modeIndex = -1
    private var secure = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { note("display $id added"); ui.postDelayed({ show(null) }, 500) }
        override fun onDisplayRemoved(id: Int) { note("display $id removed"); if (pattern?.display?.displayId == id) { pattern = null }; refresh() }
        override fun onDisplayChanged(id: Int) { if (id != Display.DEFAULT_DISPLAY) { displays.getDisplay(id)?.let { note("display $id changed: now " + DisplayDiagnostics.mode(it.mode) + ", state " + DisplayDiagnostics.stateName(it.state)) }; refresh() } }
    }

    private fun note(msg: String) = Tracking.noteGraphics("display test: $msg")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        displays = getSystemService(DISPLAY_SERVICE) as DisplayManager
        Tracking.acquire(this)
        val root = Ui.column(this)
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 56), Ui.dp(this, 20), Ui.dp(this, 28))
        root.add(Ui.text(this, "Display test", 30f, bold = true))
        root.add(Ui.text(this, "A bright test pattern is sent to the glasses in plain 2D. If you see it, tap Yes. If they stay black, try one step at a time below, waiting five seconds after each, then share the report from the Check screen.", 14f, 0xFFC9D6E6.toInt()).apply { setLineSpacing(0f, 1.25f) }, topDp = 10)
        status = Ui.text(this, "", 12f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }
        root.add(status, topDp = 14)
        val yn = Ui.row(this)
        yn.add(Ui.button(this, "Yes, I see it", primary = true) { answer(true) }, 0, weight = 1f)
        yn.add(Ui.button(this, "Still black") { answer(false) }, 0, weight = 1f, startDp = 10)
        root.add(yn, topDp = 14)

        root.add(Ui.text(this, "Glasses mode (one command each)", 15f, bold = true), topDp = 22)
        val g1 = Ui.row(this)
        g1.add(Ui.button(this, "2D 60 Hz") { glasses(McuProtocol.MODE_2D_60) }, 0, weight = 1f)
        g1.add(Ui.button(this, "2D 72 Hz") { glasses(McuProtocol.MODE_2D_72) }, 0, weight = 1f, startDp = 8)
        g1.add(Ui.button(this, "2D 90 Hz") { glasses(McuProtocol.MODE_2D_90) }, 0, weight = 1f, startDp = 8)
        g1.add(Ui.button(this, "2D 120 Hz") { glasses(McuProtocol.MODE_2D_120) }, 0, weight = 1f, startDp = 8)
        root.add(g1, topDp = 8)
        val g2 = Ui.row(this)
        g2.add(Ui.button(this, "3D 60 Hz") { glasses(McuProtocol.MODE_SBS_60) }, 0, weight = 1f)
        g2.add(Ui.button(this, "Brightness max") { brightness() }, 0, weight = 1f, startDp = 8)
        g2.add(Ui.button(this, "Read status") { readStatus() }, 0, weight = 1f, startDp = 8)
        root.add(g2, topDp = 8)
        val g2b = Ui.row(this)
        g2b.add(Ui.button(this, "512-byte read test (read-only)") { val l = Tracking.link; if (l != null) { note("read-only 512-byte version reads"); l.probeWideReports() } }, 0, weight = 1f)
        root.add(g2b, topDp = 8)
        val g3 = Ui.row(this)
        g3.add(Ui.button(this, "Half-SBS 60 Hz") { glasses(McuProtocol.MODE_HALF_SBS) }, 0, weight = 1f)
        g3.add(Ui.button(this, "Restart video link") { val l = Tracking.link; if (l != null) { note("asking the glasses to restart the video link (same mode written again)"); l.rewriteCurrentMode() } }, 0, weight = 1f, startDp = 8)
        root.add(g3, topDp = 8)

        root.add(Ui.text(this, "Phone output (one change each)", 15f, bold = true), topDp = 22)
        val p1 = Ui.row(this)
        p1.add(Ui.button(this, "Next Android mode") { nextMode() }, 0, weight = 1f)
        p1.add(Ui.button(this, "Protected on/off") { secure = !secure; note("protected-content flag " + if (secure) "on" else "off"); show(currentModeId()) }, 0, weight = 1f, startDp = 8)
        root.add(p1, topDp = 8)
        val p2 = Ui.row(this)
        p2.add(Ui.button(this, "Remove pattern (mirror)") { note("pattern removed: Android mirrors the phone"); dismiss(); refresh() }, 0, weight = 1f)
        p2.add(Ui.button(this, "Show pattern") { show(currentModeId()) }, 0, weight = 1f, startDp = 8)
        root.add(p2, topDp = 8)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        displays.registerDisplayListener(listener, ui)
        note("opened; glasses report " + McuProtocol.modeName(Tracking.link?.stats?.displayMode ?: -1))
        show(null)
        ui.post(tick)
    }

    override fun onPause() { ui.removeCallbacks(tick); displays.unregisterDisplayListener(listener); dismiss(); super.onPause() }
    override fun onDestroy() { Tracking.release(); super.onDestroy() }
    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 1000) } }

    private fun currentModeId(): Int? = pattern?.modeId?.takeIf { it != 0 }

    /** Shows the pattern, asking Android for [modeId], or for 1920x1080@60 when null. */
    private fun show(modeId: Int?) {
        if (isFinishing || isDestroyed) return
        val d = DisplayDiagnostics.external(this)
        if (d == null) { dismiss(); refresh(); return }
        val want = modeId?.let { id -> d.supportedModes.firstOrNull { it.modeId == id } }
            ?: d.supportedModes.filter { it.physicalWidth == 1920 && it.physicalHeight == 1080 }.minByOrNull { Math.abs(it.refreshRate - 60f) }
        dismiss()
        try {
            val p = PatternPresentation(this, d, want?.modeId ?: 0, secure)
            p.show()
            pattern = p
            note("pattern shown on display ${d.displayId}, requested " + (want?.let { DisplayDiagnostics.mode(it) } ?: "no particular mode") + ", display is at " + DisplayDiagnostics.mode(d.mode) + ", state " + DisplayDiagnostics.stateName(d.state))
        } catch (t: Throwable) { note("could not show the pattern: $t") }
        refresh()
    }

    private fun dismiss() { try { pattern?.dismiss() } catch (_: Throwable) {}; pattern = null }

    private fun nextMode() {
        val d = DisplayDiagnostics.external(this) ?: return
        val modes = d.supportedModes.sortedWith(compareBy({ it.physicalWidth }, { it.refreshRate }))
        if (modes.isEmpty()) return
        modeIndex = (modeIndex + 1) % modes.size
        note("asking Android for " + DisplayDiagnostics.mode(modes[modeIndex]) + " (${modeIndex + 1} of ${modes.size})")
        show(modes[modeIndex].modeId)
    }

    private fun glasses(mode: Int) {
        val link = Tracking.link
        if (link == null || !link.stats.mcuOk) { status.text = "The glasses' display controller is not connected."; return }
        Tracking.settings.use3d = mode == McuProtocol.MODE_SBS_60 || mode == McuProtocol.MODE_SBS_90
        note("asking the glasses for " + McuProtocol.modeName(mode))
        link.applyDisplayMode(mode)
    }

    private fun brightness() { val l = Tracking.link ?: return; note("asking the glasses for maximum brightness"); l.setBrightnessMax() }
    private fun readStatus() { val l = Tracking.link ?: return; note("reading the glasses' controller status"); l.readControllerStatus() }

    private fun answer(seen: Boolean) {
        val d = DisplayDiagnostics.external(this)
        note("USER: " + (if (seen) "SEES the pattern" else "glasses still BLACK") + " with display at " + (d?.let { DisplayDiagnostics.mode(it.mode) } ?: "none") +
            ", glasses reporting " + McuProtocol.modeName(Tracking.link?.stats?.displayMode ?: -1) + ", pattern " + (if (pattern != null) "shown" else "not shown (mirroring)") + ", protected " + secure)
        Tracking.settings.lastDisplayAnswer = if (seen) "seen" else "black"
        status.text = if (seen) "Recorded: the pattern is visible in this configuration." else "Recorded. Try one step below, wait five seconds, and answer again."
    }

    private fun refresh() {
        val d = DisplayDiagnostics.external(this)
        val st = Tracking.link?.stats
        val sb = StringBuilder()
        sb.append("Glasses over USB: ").append(Tracking.link?.state ?: "no link").append("; controller mode ").append(McuProtocol.modeName(st?.displayMode ?: -1)).append('\n')
        if (d == null) sb.append("Android reports NO external display.\n")
        else {
            sb.append("Android display ").append(d.displayId).append(" '").append(d.name).append("': ").append(DisplayDiagnostics.mode(d.mode)).append(", state ").append(DisplayDiagnostics.stateName(d.state)).append('\n')
            sb.append("Modes offered: ").append(d.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@" + Fmt.f(it.refreshRate, 0) }).append('\n')
        }
        val p = pattern
        sb.append(if (p != null) "Pattern: shown, ${p.view.frames} frames drawn, view ${p.view.width}x${p.view.height}" else "Pattern: not shown")
        status.text = sb
    }

    /** The pattern: bright border, colour bars, a moving block and a frame counter. */
    class PatternView(context: Context) : View(context) {
        var frames = 0L; private set
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bars = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE)
        override fun onDraw(c: Canvas) {
            frames++
            val w = width.toFloat(); val h = height.toFloat()
            c.drawColor(Color.rgb(40, 40, 40))
            val bw = w / bars.size
            for (i in bars.indices) { paint.color = bars[i]; c.drawRect(i * bw, h * 0.25f, (i + 1) * bw, h * 0.6f, paint) }
            paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 12f
            c.drawRect(6f, 6f, w - 6f, h - 6f, paint)
            paint.style = Paint.Style.FILL
            val x = (frames * 8 % (w.toLong().coerceAtLeast(1))).toFloat()
            c.drawRect(x, h * 0.65f, x + 80f, h * 0.72f, paint)
            paint.textSize = h * 0.09f
            c.drawText("ULTRA XR DISPLAY TEST", w * 0.06f, h * 0.16f, paint)
            paint.textSize = h * 0.05f
            c.drawText("${width}x$height  frame $frames", w * 0.06f, h * 0.85f, paint)
            postInvalidateOnAnimation()
        }
    }

    private class PatternPresentation(context: Context, display: Display, val modeId: Int, private val secure: Boolean) : Presentation(context, display) {
        lateinit var view: PatternView
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (secure) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                w.attributes = w.attributes.apply { if (modeId != 0) preferredDisplayModeId = modeId; screenBrightness = 1f }
            }
            view = PatternView(context)
            setContentView(view)
        }
    }
}
