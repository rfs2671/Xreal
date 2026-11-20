package dev.ultraxr.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.ultraxr.glasses.GlassesLink
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.ui.Ui.add

/**
 * The settings on the phone: one column of rows (docs/INTERFACE.md, "Settings"). A row is a name, its value, and one
 * line of plain words saying what it does. The rows of the design come first, in its order; then the ones about the
 * display and games that were here before; then the check, the recording and the set-up.
 */
class SettingsActivity : Activity() {
    private lateinit var list: LinearLayout
    /** "Forget the room" was tapped once: the second tap does it. */
    private var forgetAsked = false
    private var roomNote = ""
    private var recordNote = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        Tracking.acquire(this)
        val root = Ui.column(this)
        Ui.fitBars(root, 20, 16, 28)
        root.add(Ui.text(this, "Settings", 30f, bold = true))
        list = Ui.column(this)
        root.add(list, topDp = 10)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    // Written again whenever the screen comes back: the room may have been marked, or Android's own settings changed, meanwhile.
    override fun onResume() { super.onResume(); forgetAsked = false; fill() }
    override fun onDestroy() { Tracking.release(); super.onDestroy() }

    private fun fill() {
        val s = Tracking.settings
        list.removeAllViews()
        fun group(name: String) { list.add(Ui.label(this, name), topDp = 30) }
        fun onOff(name: String, line: String, get: () -> Boolean, set: (Boolean) -> Unit): ListRow {
            val r = ListRow(this).set(name, "", line).onOff(get()); r.onTap { set(!get()); r.onOff(get()) }; list.add(r); return r
        }
        fun steps(name: String, line: String, min: Int, max: Int, now: Int, by: Int = 1, label: (Int) -> String, set: (Int) -> Unit) { list.add(ListRow(this).set(name, "", line).stepper(min, max, now, label, by, set)) }
        fun act(name: String, value: String, line: String, colour: Int = Ui.CHALK, run: () -> Unit): ListRow {
            val r = ListRow(this).set(name, value, line); r.name.setTextColor(colour); r.onTap(run); list.add(r); return r
        }

        // ---- the rows of the design, in its order
        steps("Eye distance", "The distance between your pupils. Most adults: 58 to 70 mm.", 54, 74, s.ipdMm.toInt(), label = { "$it mm" }) { s.ipdMm = it.toFloat() }
        steps("World scale", "100 %: what is drawn sits on the real room and on your hands. More zooms out, so an app made for a wide headset fits, but nothing lines up with the room any more. Takes effect when the app is next started.",
            80, 200, Math.round(s.fovScale * 100), label = { "$it %" }) { s.fovScale = it / 100f }
        steps("Picture brightness", "How bright the desktop draws in the glasses. The glasses' own brightness keys are apart from it.", 30, 100, Math.round(s.pictureBrightness * 10f) * 10, by = 10, label = { "$it %" }) { s.pictureBrightness = it / 100f }
        steps("Window distance", "How far away a new window opens. Its size follows, so it looks as large at any distance.", 10, 40, Math.round(s.windowDistanceM * 10), label = { "${it / 10}.${it % 10} m" }) { s.windowDistanceM = it / 10f }
        // These two do what the same rows do in the glasses (desktop/DesktopSession.setHands, setLooking). The desktop keeps
        // its part of them with its own settings ("desktop": handGestures, lookToPress, gazeFocus) and reads it afresh every time.
        val desk = getSharedPreferences("desktop", Context.MODE_PRIVATE)
        onOff("Hand tracking", "Point with a hand, pinch to select, carry windows by hand. Off saves battery: the touchpad, the d-pad and looking still work. Takes effect at once.",
            { s.handTracking && desk.getBoolean("handGestures", true) }) { Tracking.setHandTracking(it); if (it) desk.edit().putBoolean("handGestures", true).apply() }
        onOff("Pointer by looking", "Look at a thing to bring the focus to it; rest your gaze on it to press it. No phone and no hands needed.",
            { desk.getBoolean("lookToPress", true) }) { desk.edit().putBoolean("lookToPress", it).putBoolean("gazeFocus", it).apply() }
        val android = androidRemovesAnimations()
        onOff("Reduce motion", if (android) "Android's own \"remove animations\" is on, so nothing in the glasses moves by itself whatever is set here." else "Nothing in the glasses moves by itself. Android's own \"remove animations\" does the same.", { s.reduceMotion || android }) { s.reduceMotion = it }

        val marked = SetupActivity.roomMarked(this)
        val markRow = act("Mark the room", if (marked) "marked" else "not marked", roomNote.ifEmpty { if (marked) "Windows and games keep their place against the real walls. Tap to mark the room again." else "With the glasses on, look at each corner where a wall meets the floor and pinch, going round once." }) {
            roomNote = if (Tracking.link?.state != GlassesLink.State.STREAMING) "Plug in the glasses and put them on first." else if (RoomSetup.start(this, marked)) "" else "The room set-up is not on this phone."
            if (roomNote.isNotEmpty()) fill()
        }
        if (marked) markRow.value.setTextColor(Ui.TIDE)
        if (roomNote.isNotEmpty()) markRow.line.setTextColor(Ui.EMBER)
        if (marked) {
            val forget = act("Forget the room", "", if (forgetAsked) "Tap once more to forget the marked room. It cannot be brought back." else "Throws the marked room away. The cameras' own finding of walls and floor goes on.", colour = Ui.EMBER) {
                if (forgetAsked) { Tracking.clearRoomPlan(); forgetAsked = false; roomNote = "" } else forgetAsked = true
                fill()
            }
            if (forgetAsked) forget.line.setTextColor(Ui.EMBER)
        } else list.add(ListRow(this).set("Forget the room", "", "No room is marked, so there is nothing to forget.").also { it.name.setTextColor(Ui.DUSK) })
        list.add(ListRow(this).set("About", "Ultra XR ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }}", "GPL-3.0. Built on Basalt, Monado, XR Gate and Pocket XR; see the project page for licences."))

        // ---- the display and games
        group("Display and games")
        onOff("3D display mode", "Switches the glasses to side-by-side 3D. Turn off if the glasses stay black: they then show the same picture to both eyes.", { s.use3d }) { s.use3d = it; Tracking.link?.applyDisplayMode(Tracking.wantedMode()) }
        onOff("Smoother display (90 Hz)", "Uses more battery. Falls back to 60 Hz if the phone does not offer it.", { s.refresh90 }) { s.refresh90 = it; Tracking.link?.applyDisplayMode(Tracking.wantedMode()) }
        onOff("Try half-width 3D (experimental)", "Leave off on the Pixel 10: the glasses drop back to 2D after two seconds and then stay black until they are unplugged and plugged in again.", { s.tryHalf3d }) { s.tryHalf3d = it }
        onOff("Pace frames by the display (experimental)", "Times each frame by when the glasses really showed the last ones. Smoother on the test device, but measured worse on the Pixel 10: leave off there. Takes effect at the next game.", { s.paceByDisplay }) { s.paceByDisplay = it }
        onOff("Enter games automatically", "After a game's page has loaded, Ultra XR presses its start button for you. Switch this on after you have accepted the browser's terms of service once: until then that screen is where the button will be.", { s.autoEnter }) { s.autoEnter = it }
        onOff("Show my hands", "Draws a light outline over your tracked hands.", { s.showHands }) { s.showHands = it }
        onOff("Battery saver", "Tracks hands half as often.", { s.batterySaver }) { s.batterySaver = it }
        steps("Your height", "Sets where the floor grid sits.", 120, 200, Math.round(s.standingHeightM * 100), label = { "$it cm" }) { s.standingHeightM = it / 100f }

        // ---- when something is wrong
        group("Checks")
        act("Hardware check", "", "A one-minute check of the glasses' display, cameras, motion sensor and hand tracking, with a report.") { startActivity(Intent(this, CheckActivity::class.java)) }
        // For tracking work without the glasses: one recording of real motion to replay offline.
        val rec = act("Record tracking data", "", recordNote.ifEmpty { "Saves 60 seconds of raw camera and motion data (about 550 MB) in the app's folder. Look around, then walk a few steps and come back to exactly where you started." }) {
            val link = Tracking.link
            if (link == null || link.state != GlassesLink.State.STREAMING) recordNote = "The glasses are not streaming, so nothing was recorded."
            else {
                val name = "track-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".uxrec"
                link.record(java.io.File(getExternalFilesDir("recordings"), name), 60)
                recordNote = "Recording for 60 seconds: look around, walk a few steps, come back to where you started."
            }
            fill()
        }
        if (recordNote.startsWith("The glasses")) rec.line.setTextColor(Ui.EMBER) else if (recordNote.isNotEmpty()) rec.line.setTextColor(Ui.TIDE)
        act("Set-up again", "", "The five steps of the first day: the pointer, the glasses, eye distance, the room.") { SetupActivity.step = 0; startActivity(Intent(this, SetupActivity::class.java)) }
    }

    /** Android's own "remove animations" (its animation scale at nought), which the glasses honour as well. */
    private fun androidRemovesAnimations(): Boolean = try { android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false }
}
