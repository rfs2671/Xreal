package dev.ultraxr.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.ultraxr.glasses.GlassesLink
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.ui.Ui.add

/**
 * The set-up, once (docs/INTERFACE.md, "Set-up, once"): five screens, each one thing to do and why, and a tick when
 * it is done. The main screen shows it until its end has been reached once (core/Settings.setupDone) and never
 * again by itself.
 *
 * Two of the steps are questions Android puts to the owner: switching on an accessibility service, and letting the
 * app open for the glasses. This screen opens Android's own screen for the first and says what to tick in the second.
 * It never answers either: it only looks, with Android's ordinary calls, at whether they have been answered.
 */
class SetupActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val marks = ArrayList<TickView>()
    private lateinit var count: TextView
    private lateinit var title: TextView
    private lateinit var what: TextView
    private lateinit var why: TextView
    private lateinit var extra: LinearLayout
    private lateinit var stateRow: LinearLayout
    private lateinit var stateMark: TickView
    private lateinit var stateText: TextView
    private lateinit var primary: TextView
    private lateinit var backWord: TextView
    private lateinit var skipWord: TextView
    private var primaryRun: (() -> Unit)? = null
    private var skipRun: (() -> Unit)? = null
    private var shown = -1
    /** Android was asked for the camera and the answer was no: asking again may show nothing, so the way to its settings is offered. */
    private var cameraRefused = false
    private var roomNote = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        Tracking.acquire(this)
        open = true
        build()
    }

    override fun onDestroy() { Tracking.release(); super.onDestroy() }
    override fun onResume() { super.onResume(); ui.post(poll) }
    override fun onPause() { ui.removeCallbacks(poll); super.onPause() }

    /** What Android has been told is looked at again every second, so a tick appears as soon as the owner comes back. */
    private val poll = object : Runnable { override fun run() { render(); ui.postDelayed(this, 1000) } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        cameraRefused = checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        Tracking.link?.retry(); render()
    }

    private fun build() {
        val root = Ui.column(this)
        Ui.fitBars(root, 24, 14, 20)
        val head = Ui.row(this)
        count = Ui.text(this, "", 15f, Ui.DUSK)
        head.add(count, 0, weight = 1f)
        for (i in 0 until STEPS) { val m = TickView(this); marks.add(m); head.add(m, Ui.dp(this, 26), Ui.dp(this, 26), startDp = 4) }
        root.add(head)
        title = Ui.text(this, "", 30f, bold = true); root.add(title, topDp = 40)
        what = Ui.text(this, "", 18f).apply { setLineSpacing(0f, 1.3f) }; root.add(what, topDp = 18)
        why = Ui.text(this, "", 15f, Ui.DUSK).apply { setLineSpacing(0f, 1.3f) }; root.add(why, topDp = 14)
        extra = Ui.column(this); root.add(extra, topDp = 18)
        stateRow = Ui.row(this)
        stateMark = TickView(this); stateRow.add(stateMark, Ui.dp(this, 30), Ui.dp(this, 30))
        stateText = Ui.text(this, "", 16f); stateRow.add(stateText, 0, weight = 1f, startDp = 10)
        root.add(stateRow, topDp = 22)
        root.add(View(this), h = 0, weight = 1f)      // what is to be pressed sits at the foot of the screen, under the thumb
        primary = Ui.action(this, "") { primaryRun?.invoke() }; root.add(primary, topDp = 24)
        val foot = Ui.row(this)
        backWord = Ui.word(this, "back") { goBack() }
        skipWord = Ui.word(this, "") { skipRun?.invoke() }
        foot.add(backWord, ViewGroup.LayoutParams.WRAP_CONTENT); foot.add(View(this), 0, 1, weight = 1f); foot.add(skipWord, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.add(foot, topDp = 10)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun go(to: Int) { step = to.coerceIn(0, STEPS - 1); roomNote = ""; render() }
    private fun next() = go(step + 1)

    /** Back goes a step back; from the first step it leaves the set-up, which the main screen then offers in one line. */
    private fun goBack() { if (step > 0 && step < STEPS - 1) go(step - 1) else leave() }
    private fun leave() { open = false; finish() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goBack() }

    private fun render() {
        val s = step
        val done = booleanArrayOf(pointerOn(this), glassesAllowed(this), true, roomMarked(this), true)
        if (s == STEPS - 1 && !Tracking.settings.setupDone) Tracking.settings.setupDone = true      // the end has been reached: never shown by itself again
        count.text = "Set-up, ${s + 1} of $STEPS"
        // the five marks at the top: a tick for a step that is done, a Lamp ring for this one, a Slate ring for the rest
        marks.forEachIndexed { i, m ->
            m.state = if (i == s) (if (s == STEPS - 1) TickView.DONE else TickView.NOW) else if (i < s && done[i]) TickView.DONE else TickView.TO_COME
            m.contentDescription = "Step ${i + 1}: " + (if (i == s) "this one" else if (i < s && done[i]) "done" else if (i < s) "left for later" else "to come")
        }
        if (shown != s) { shown = s; extra.removeAllViews(); if (s == 2) extra.add(eyeRow()) }
        fun words(t: String, a: String, b: String) { title.text = t; what.text = a; why.text = b }
        fun state(ok: Boolean?, t: String) {
            stateRow.visibility = if (ok == null) View.GONE else View.VISIBLE
            stateMark.state = if (ok == true) TickView.DONE else TickView.TO_COME; stateText.text = t; stateText.setTextColor(if (ok == true) Ui.TIDE else Ui.CHALK)
        }
        fun act(label: String?, run: (() -> Unit)? = null) { primary.visibility = if (label == null) View.INVISIBLE else View.VISIBLE; if (label != null) primary.text = label; primaryRun = run }
        fun skip(label: String?, run: (() -> Unit)? = null) { skipWord.visibility = if (label == null) View.INVISIBLE else View.VISIBLE; if (label != null) skipWord.text = label; skipRun = run }
        backWord.text = if (s == 0) "not now" else "back"; backWord.visibility = if (s == STEPS - 1) View.INVISIBLE else View.VISIBLE
        when (s) {
            0 -> {
                words("Switch on the pointer", "In Android's accessibility settings, choose \"Ultra XR touchpad\" and switch it on.",
                    "It makes the phone's screen a touchpad in games, and hands the keys of a gamepad to a window when Android would give them to something else. Android lets only you switch it on, and asks you to confirm.")
                if (done[0]) { state(true, "The pointer is on"); act("Next") { next() }; skip(null) }
                else { state(false, "Not switched on yet"); act("Open Android's settings") { try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {} }; skip("skip for now") { next() } }
            }
            1 -> {
                val camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                when {
                    done[1] -> {
                        words("Plug in the glasses", "The glasses are plugged in and Ultra XR may use them.", "If you ticked \"always open Ultra XR\" in Android's question, it does not ask again.")
                        state(true, "The glasses are connected"); act("Next") { next() }; skip(null)
                    }
                    !camera -> {
                        words("Plug in the glasses", "First, Android asks whether Ultra XR may use the camera. Allow it.",
                            "The glasses' two tracking cameras count as a camera, and Android gives an app no access to the glasses without this. Ultra XR does not use the phone's own cameras.")
                        state(false, if (cameraRefused) "Android has the camera set to \"don't allow\" for Ultra XR" else "Not allowed yet")
                        if (cameraRefused) act("Open Android's settings for Ultra XR") { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))) } catch (_: Exception) {} }
                        else act("Let Android ask") { requestPermissions(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.CAMERA), 1) }
                        skip("skip for now") { next() }
                    }
                    glassesPlugged(this) -> {
                        words("Plug in the glasses", "Android asks whether Ultra XR may use the glasses. Tick the box to always open Ultra XR for this device, then confirm.",
                            "With the box ticked, Android does not ask again when the glasses are plugged in. The answer is yours: Ultra XR never gives it for you.")
                        state(false, "The glasses are plugged in. Android's question is waiting for your answer.")
                        act("Let Android ask again") { Tracking.link?.retry() }; skip("skip for now") { next() }
                    }
                    else -> {
                        words("Plug in the glasses", "Plug the glasses into the phone. Android then asks whether Ultra XR may open for them: tick the box to always open Ultra XR for this device, then confirm.",
                            "With the box ticked, Android does not ask again when the glasses are plugged in. The answer is yours: Ultra XR never gives it for you.")
                        state(false, "Waiting for the glasses"); act(null); skip("skip for now") { next() }
                    }
                }
            }
            2 -> {
                words("Eye distance", "Set the distance between your pupils.", "Each eye gets its own picture, drawn for where that eye is. Most adults: 58 to 70 mm. A prescription for glasses gives it as PD.")
                state(null, ""); act("Next") { next() }; skip(null)
            }
            3 -> {
                words("Mark the room", "Optional. With the glasses on, look at each corner of the room where a wall meets the floor and pinch, going round once; pinch at the first corner again to finish.",
                    "Windows and games then keep their place against the real walls, also after tracking was lost for a moment. It takes a few minutes and can be done later, from Settings.")
                if (done[3]) { state(true, "The room is marked"); act("Next") { next() }; skip(null) }
                else {
                    val streaming = Tracking.link?.state == GlassesLink.State.STREAMING
                    state(false, roomNote.ifEmpty { if (streaming) "Not marked" else "Not marked. It needs the glasses plugged in." })
                    if (streaming) { act("Mark the room now") { if (!RoomSetup.start(this, false)) { roomNote = "The room set-up is not on this phone. It can be done later, from Settings."; render() } }; skip("later") { next() } }
                    else { act("Later") { next() }; skip(null) }
                }
            }
            else -> {
                val left = listOfNotNull(if (!done[0]) "the pointer" else null, if (!done[1]) "the glasses" else null)
                words("All set", "That was the set-up. It is not shown again.", "If one of these is undone later, the main screen says so in one line and offers the one step that puts it right.")
                if (left.isEmpty()) state(true, "Everything is done") else state(false, "Left for later: " + left.joinToString(" and ") + ". The main screen offers the step.")
                act("Open in the glasses") { open = false; startActivity(Intent(this, DesktopActivity::class.java)); finish() }
                skip("not now") { leave() }
            }
        }
    }

    private fun eyeRow(): ListRow = ListRow(this).set("Eye distance", "", "").stepper(54, 74, Tracking.settings.ipdMm.toInt(), { "$it mm" }) { Tracking.settings.ipdMm = it.toFloat() }

    companion object {
        const val STEPS = 5
        /**
         * The set-up is on its way and its owner has not left it. Plugging the glasses in brings the main screen to the
         * front (Android starts it for the device), which takes this screen away: the main screen sees this and puts
         * the set-up back, at the step it was on.
         */
        @Volatile var open = false
        @Volatile var step = 0

        /** Whether the owner has switched the pointer service on in Android's accessibility settings. */
        fun pointerOn(c: Context): Boolean = dev.ultraxr.xr.PointerService.enabled(c)

        private fun glassesDevices(c: Context) = try { (c.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.filter { it.vendorId == GlassesLink.VID } } catch (_: Exception) { emptyList() }
        fun glassesPlugged(c: Context): Boolean = glassesDevices(c).isNotEmpty()
        /** Whether the glasses are plugged in and Android has given this app access to them. */
        fun glassesAllowed(c: Context): Boolean = try { val usb = c.getSystemService(Context.USB_SERVICE) as UsbManager; glassesDevices(c).any { usb.hasPermission(it) } } catch (_: Exception) { false }

        /** Whether a marked room is kept (tracking/Tracking.saveRoomPlan), read or not yet read in this run. */
        fun roomMarked(c: Context): Boolean = Tracking.roomPlanCorners > 0 || try { java.io.File(c.getExternalFilesDir(null), "room_plan.txt").isFile } catch (_: Exception) { false }
    }
}

/**
 * Marking the room. Today that is done by the room set-up of the test app that is installed beside this one
 * (tools/quest/room_setup.py starts it the same way): the wearer looks at each corner on the floor and pinches, and
 * the corners come back to this app, which keeps them (Tracking.saveRoomPlan).
 */
object RoomSetup {
    private const val PKG = "dev.ultraxr.questlab"

    /** Starts the room set-up; [anew] marks the room again when one is kept (otherwise a kept room is placed). False when it is not installed. */
    fun start(c: Context, anew: Boolean): Boolean {
        val i = Intent().setClassName(PKG, "$PKG.LabActivity").putExtra("seconds", 1).putExtra("room_seconds", 6).putExtra("setup_seconds", if (anew) -180 else 180).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { c.startActivity(i, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()); Tracking.log("room set-up started from the phone"); true }
        catch (e: Exception) { Tracking.log("room set-up could not be started: $e"); false }
    }
}
