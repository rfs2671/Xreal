package dev.ultraxr.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.ultraxr.proto.McuProtocol
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.ui.Ui.add

/** The few settings that matter, each with one line of plain explanation. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        Tracking.acquire(this)
        val s = Tracking.settings
        val root = Ui.column(this)
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 56), Ui.dp(this, 20), Ui.dp(this, 28))
        root.add(Ui.text(this, "Settings", 30f, bold = true))

        root.add(toggle("3D display mode", "Switches the glasses to side-by-side 3D. Turn off if the glasses stay black: they then show the same picture to both eyes.", s.use3d) {
            s.use3d = it; Tracking.link?.applyDisplayMode(Tracking.wantedMode())
        }, topDp = 22)
        root.add(toggle("Smoother display (90 Hz)", "Uses more battery. Falls back to 60 Hz if the phone does not offer it.", s.refresh90) {
            s.refresh90 = it; Tracking.link?.applyDisplayMode(Tracking.wantedMode())
        }, topDp = 10)
        root.add(toggle("Hand tracking", "Turn off to save battery; games then use your gaze. Takes effect next time the app starts.", s.handTracking) { s.handTracking = it }, topDp = 10)
        root.add(toggle("Show my hands", "Draws a light outline over your tracked hands.", s.showHands) { s.showHands = it }, topDp = 10)
        root.add(toggle("Battery saver", "Tracks hands half as often.", s.batterySaver) { s.batterySaver = it }, topDp = 10)

        root.add(slider("Eye distance", "Match your own (most adults: 58 to 70 mm).", 54, 74, s.ipdMm.toInt(), { "$it mm" }) { s.ipdMm = it.toFloat() }, topDp = 10)
        root.add(slider("World scale", "If virtual objects drift against the room when you turn your head, nudge this.", 80, 120, (s.fovScale * 100).toInt(), { "$it %" }) { s.fovScale = it / 100f }, topDp = 10)
        root.add(slider("Your height", "Sets where the floor grid sits.", 120, 200, (s.standingHeightM * 100).toInt(), { "$it cm" }) { s.standingHeightM = it / 100f }, topDp = 10)

        root.add(Ui.text(this, "Ultra XR ${packageManager.getPackageInfo(packageName, 0).versionName}\nGPL-3.0. Built on Basalt, Monado, XR Gate and Pocket XR; see the project page for licences.", 12f, Ui.MUTED).apply { setLineSpacing(0f, 1.3f) }, topDp = 26)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    override fun onDestroy() { Tracking.release(); super.onDestroy() }

    private fun card(title: String, sub: String): LinearLayout {
        val col = Ui.column(this, 16).apply { background = Ui.rounded(Ui.SURFACE, 20f, this@SettingsActivity) }
        col.add(Ui.text(this, title, 16f, bold = true))
        col.add(Ui.text(this, sub, 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }, topDp = 6)
        return col
    }

    private fun toggle(title: String, sub: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = Ui.row(this).apply { background = Ui.pressable(this@SettingsActivity, Ui.rounded(Ui.SURFACE, 20f, this@SettingsActivity)); val p = Ui.dp(this@SettingsActivity, 16); setPadding(p, p, p, p) }
        val col = Ui.column(this)
        col.add(Ui.text(this, title, 16f, bold = true))
        col.add(Ui.text(this, sub, 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }, topDp = 6)
        row.add(col, 0, weight = 1f)
        var on = initial
        val pill = Ui.text(this, "", 13f, bold = true).apply { gravity = Gravity.CENTER; minWidth = Ui.dp(this@SettingsActivity, 56); val p = Ui.dp(this@SettingsActivity, 8); setPadding(p, p, p, p) }
        fun paint() { pill.text = if (on) "On" else "Off"; pill.setTextColor(if (on) 0xFF041018.toInt() else Ui.MUTED); pill.background = Ui.rounded(if (on) Ui.ACCENT else Ui.SURFACE_HI, 16f, this) }
        paint()
        row.add(pill, ViewGroup.LayoutParams.WRAP_CONTENT, startDp = 12)
        row.isClickable = true
        row.setOnClickListener { on = !on; paint(); onChange(on) }
        return row
    }

    private fun slider(title: String, sub: String, min: Int, max: Int, initial: Int, label: (Int) -> String, onChange: (Int) -> Unit): LinearLayout {
        val col = card(title, sub)
        val value = Ui.text(this, label(initial.coerceIn(min, max)), 14f, Ui.ACCENT, bold = true)
        col.add(value, topDp = 10)
        col.add(SeekBar(this).apply {
            this.max = max - min; progress = initial.coerceIn(min, max) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { value.text = label(p + min); if (fromUser) onChange(p + min) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, topDp = 6)
        return col
    }
}
