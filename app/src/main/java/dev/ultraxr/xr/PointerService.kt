package dev.ultraxr.xr

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.ultraxr.core.PointerInput
import dev.ultraxr.tracking.Tracking
import kotlin.math.abs

/**
 * Turns the phone into a touchpad while an XR application is running, so a game can be entered and played without
 * hand tracking. Android gives touches and keys to the application in front (the XR application), not to Ultra XR;
 * an accessibility service is the supported way for another app to receive them. It reads no screen content and
 * asks Android for one kind of event only, which app's window came to the front, so that the touchpad can leave the
 * screen when the game is left; it reads nothing of any window. It shows its own touchpad window and listens to the volume keys.
 *
 * Drag: move the pointer. Tap: select. Two fingers down, or volume-down held: press and keep pressed (grab and
 * carry). Volume-up: recentre the view and the pointer. A mouse moves and clicks the same pointer.
 */
class PointerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pad: Pad? = null
    private var keysLogged = 0
    private val tick = object : Runnable {
        override fun run() { sync(); pad?.invalidate(); handler.postDelayed(this, 500) }
    }

    override fun onServiceConnected() {
        running = true
        // The one thing asked of Android: which app's window came to the front (its package name, nothing in it),
        // so the touchpad leaves the screen when the wearer leaves the game.
        try { serviceInfo = serviceInfo.apply { eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED; notificationTimeout = 100 } } catch (t: Throwable) { Tracking.log("pointer: cannot watch which app is in front: $t") }
        handler.post(tick)
    }
    override fun onUnbind(intent: android.content.Intent?): Boolean { running = false; handler.removeCallbacks(tick); hide(); return super.onUnbind(intent) }
    /** False once another app (or Ultra XR's own screens) is in front of the game: the touchpad is then taken away. */
    @Volatile private var gameInFront = true
    private var wasConnected = false
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return; val cls = event.className?.toString() ?: ""
        val game = dev.ultraxr.openxr.OpenXrGlassesLayer.clientPackage
        val front = when {
            game.isNotEmpty() && pkg == game -> true
            pkg == packageName -> if (cls.startsWith("dev.ultraxr.ui.")) false else return       // our own touchpad window is not "another app"
            pkg == "com.android.systemui" || pkg == "android" || cls.contains("InputMethod") -> return   // the notification shade, the volume panel, a keyboard
            else -> false
        }
        if (front != gameInFront) { gameInFront = front; Tracking.log("pointer: " + (if (front) "the game is in front again" else "$pkg is in front, touchpad put away")); sync() }
    }
    override fun onInterrupt() {}

    private fun wanted() = XrHost.applicationConnected

    private fun sync() {
        // a game that has just connected is in front, whatever was seen before
        val c = wanted(); if (c && !wasConnected) gameInFront = true; wasConnected = c
        val want = c && gameInFront
        if (want && pad == null) show() else if (!want && pad != null) hide()
    }

    private fun show() {
        val v = Pad(this)
        v.onLeave = { leave() }
        // The picture's tilt is turned a degree only by two taps on the same button within 0.6 s. A single tap did
        // it until 19 November 2026, when ten stray touches of the phone during a game, a second or more apart,
        // left everything drawn 10 degrees low without the wearer knowing.
        var floorTapNs = 0L; var floorTapStep = 0f
        v.onFloor = { step ->
            val now = System.nanoTime()
            if (step == floorTapStep && now - floorTapNs < 600_000_000L) { floorTapNs = 0L; XrHost.nudgePictureTilt(step) }
            else { floorTapNs = now; floorTapStep = step; dev.ultraxr.tracking.Tracking.log("OpenXR: one tap on a picture-tilt button, ignored (two quick taps turn the picture a degree)") }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp); pad = v; Tracking.log("pointer: touchpad shown on the phone") }
        catch (t: Throwable) { Tracking.log("pointer: touchpad could not be shown: $t") }
    }

    /**
     * The "Leave the game" button on the touchpad: the touchpad goes and the phone shows its home screen. Without
     * it the touchpad covered the phone for as long as the browser kept its session, with no way past it.
     */
    private fun leave() {
        Tracking.log("pointer: Leave pressed, touchpad put away and the phone sent to its home screen")
        gameInFront = false; hide()
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (t: Throwable) { Tracking.log("pointer: could not go home: $t") }
        // The game is now in the background but keeps its session with the runtime, and with it the window on the
        // glasses: Android then refuses every other window there, and the home view and the next game end up on the
        // phone's screen. Android does not let one app end another, and the runtime cannot give a running session a
        // new window. What is known to work is a runtime that starts afresh (as after every update of this app): the
        // game finds the runtime gone and starts a new session next time. So this app's process ends here, a moment
        // after the phone has gone to its home screen; Android starts it again at once for the touchpad service.
        if (XrHost.applicationConnected) handler.postDelayed({
            Tracking.log("pointer: the game was left with its session open; Ultra XR starts afresh so the glasses are free")
            try { XrHost.onApplicationDisconnected() } catch (_: Throwable) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500)
    }

    private fun hide() {
        val v = pad ?: return; pad = null
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
        PointerInput.reset()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!wanted()) return false
        val now = System.nanoTime()
        if (event.repeatCount == 0 && keysLogged++ < 6) Tracking.log("pointer: key " + event.keyCode + (if (event.action == KeyEvent.ACTION_DOWN) " down" else " up"))
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { if (event.repeatCount == 0) PointerInput.hold(now, 1, event.action == KeyEvent.ACTION_DOWN); pad?.invalidate(); return true }
            KeyEvent.KEYCODE_VOLUME_UP -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { XrHost.recentreNow(); PointerInput.center(now) }; return true }
        }
        return false
    }

    /** The touchpad: a dark screen with the instructions, so the phone can be used without looking at it. */
    private class Pad(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastX = 0f; private var lastY = 0f; private var downNs = 0L; private var travelled = 0f; private var many = false
        private val density = resources.displayMetrics.density
        // A swipe across the phone's short side carries the pointing hand about 0.7 m: across the whole view.
        private val metresPerPixel = 0.7 / minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).coerceAtLeast(1)
        init { setBackgroundColor(0xFF05070A.toInt()); keepScreenOn = true }
        var onLeave: (() -> Unit)? = null
        /** The Picture buttons: called with degrees to turn everything drawn down (negative: up). */
        var onFloor: ((Float) -> Unit)? = null
        private fun inFloorUp(ux: Float, uy: Float, w: Int, h: Int) = ux > w * 0.08f && ux < w * 0.48f && uy > h * 0.66f && uy < h * 0.75f
        private fun inFloorDown(ux: Float, uy: Float, w: Int, h: Int) = ux > w * 0.52f && ux < w * 0.92f && uy > h * 0.66f && uy < h * 0.75f
        /** Where the Leave button is, as fractions of the upright page: across its middle, near the bottom. */
        private fun inLeave(ux: Float, uy: Float, w: Int, h: Int) = ux > w * 0.2f && ux < w * 0.8f && uy > h * 0.80f && uy < h * 0.89f
        /** A place on the screen as a place on the upright page that [onDraw] draws (the inverse of its turning). */
        private fun toPage(x: Float, y: Float, out: FloatArray) {
            val t = turn(); val w = if (t == 1 || t == 3) height else width; val h = if (t == 1 || t == 3) width else height
            val dx = x - width / 2f; val dy = y - height / 2f
            when (t) { 1 -> { out[0] = -dy; out[1] = dx }; 3 -> { out[0] = dy; out[1] = -dx }; 2 -> { out[0] = -dx; out[1] = -dy }; else -> { out[0] = dx; out[1] = dy } }
            out[0] += w / 2f; out[1] += h / 2f
        }
        private val page = FloatArray(2)

        /**
         * The browser holds the phone's screen in landscape, but the phone is held upright. The touchpad therefore
         * reads and works upright whatever way the screen is turned: 0 for an upright screen, 1 when the screen is
         * turned so its top is at the phone's left (Surface.ROTATION_90), 3 for the other way.
         */
        private fun turn(): Int = try { (display?.rotation ?: 0) } catch (_: Throwable) { 0 }

        /** A finger's movement on the screen as right and up for someone holding the phone upright, in pixels. */
        private fun upright(dx: Float, dy: Float, out: FloatArray) {
            when (turn()) {
                1 -> { out[0] = -dy; out[1] = -dx }
                3 -> { out[0] = dy; out[1] = dx }
                2 -> { out[0] = -dx; out[1] = dy }
                else -> { out[0] = dx; out[1] = -dy }
            }
        }
        private val moved = FloatArray(2)

        override fun onDraw(canvas: Canvas) {
            val t = turn()
            canvas.save()
            // Draw as on an upright phone: turn the page about its middle, so the text reads from the phone's bottom edge.
            val w = if (t == 1 || t == 3) height else width; val h = if (t == 1 || t == 3) width else height
            canvas.translate(width / 2f, height / 2f)
            canvas.rotate(when (t) { 1 -> -90f; 3 -> 90f; 2 -> 180f; else -> 0f })
            canvas.translate(-w / 2f, -h / 2f)
            drawPad(canvas, w, h)
            canvas.restore()
        }

        private fun drawPad(canvas: Canvas, width: Int, height: Int) {
            val lines = listOf("Touchpad" to 26f, "" to 10f, "Drag: move the pointer" to 17f, "Tap: select" to 17f, "Two fingers, or hold volume-down:" to 17f, "grab and carry" to 17f,
                "Volume-up: recentre" to 17f, "" to 10f, "Selections so far: " + PointerInput.clicks to 14f)
            var y = height * 0.30f
            for ((text, size) in lines) {
                paint.textSize = size * density; paint.color = if (size > 20f) 0xFFE8F0FA.toInt() else 0xFF9FB0C4.toInt()
                paint.textAlign = Paint.Align.CENTER
                if (text.isNotEmpty()) canvas.drawText(text, width / 2f, y, paint)
                y += size * density * 1.7f
            }
            if (PointerInput.pressed(System.nanoTime())) { paint.color = 0xFF3D8BFF.toInt(); canvas.drawRect(0f, 0f, width.toFloat(), 6 * density, paint) }
            // the way out, always there
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * density; paint.color = 0xFF9FB0C4.toInt()
            canvas.drawRoundRect(width * 0.2f, height * 0.80f, width * 0.8f, height * 0.89f, 24 * density, 24 * density, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 18f * density; paint.color = 0xFFE8F0FA.toInt(); paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Hold to leave the game", width / 2f, height * 0.845f + 6 * density, paint)
            // the picture, for when what is drawn sits above or below the real thing: one degree a press, remembered
            paint.style = Paint.Style.STROKE; paint.color = 0xFF9FB0C4.toInt()
            canvas.drawRoundRect(width * 0.08f, height * 0.66f, width * 0.48f, height * 0.75f, 24 * density, 24 * density, paint)
            canvas.drawRoundRect(width * 0.52f, height * 0.66f, width * 0.92f, height * 0.75f, 24 * density, 24 * density, paint)
            paint.style = Paint.Style.FILL; paint.color = 0xFFE8F0FA.toInt(); paint.textSize = 17f * density
            canvas.drawText("Hold: picture up", width * 0.28f, height * 0.705f + 6 * density, paint)
            canvas.drawText("Hold: picture down", width * 0.72f, height * 0.705f + 6 * density, paint)
            // what is set and what the cameras measure, in numbers the wearer can read out
            paint.textSize = 17f * density; paint.color = 0xFFFFFFFF.toInt()
            val tilt = XrHost.pictureTiltDeg
            canvas.drawText("Picture turned " + dev.ultraxr.core.Fmt.f(Math.abs(tilt).toDouble(), 0) + " degrees " + (if (tilt >= 0) "down" else "up"), width / 2f, height * 0.60f, paint)
            val below = XrHost.measuredBelowGlassesM
            canvas.drawText(if (below.isNaN()) "Floor not found yet" else "Cameras: floor " + dev.ultraxr.core.Fmt.f(below, 2) + " m under the glasses", width / 2f, height * 0.635f, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val now = System.nanoTime()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; downNs = now; travelled = 0f; many = false }
                MotionEvent.ACTION_POINTER_DOWN -> { many = true; PointerInput.hold(now, 0, true) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX; val dy = e.y - lastY; lastX = e.x; lastY = e.y; travelled += abs(dx) + abs(dy)
                    upright(dx, dy, moved)
                    PointerInput.move(now, moved[0] * metresPerPixel, moved[1] * metresPerPixel)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // the finger that stays becomes the reference, so the pointer does not jump
                    if (e.actionIndex == 0 && e.pointerCount > 1) { lastX = e.getX(1); lastY = e.getY(1) }
                    if (e.pointerCount <= 2) PointerInput.hold(now, 0, false)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    PointerInput.hold(now, 0, false)
                    if (e.actionMasked == MotionEvent.ACTION_UP && !many && travelled < 24 * density) {
                        val held = now - downNs
                        // A quick tap is a select wherever it lands: the touchpad is used without looking at it. The
                        // buttons answer only to a finger held on them for over half a second.
                        if (held < 300_000_000L) PointerInput.tap(now)
                        else if (held > 550_000_000L && held < 4_000_000_000L) {
                            toPage(e.x, e.y, page)
                            val t = turn(); val w = if (t == 1 || t == 3) height else width; val h = if (t == 1 || t == 3) width else height
                            if (inLeave(page[0], page[1], w, h)) { onLeave?.invoke(); return true }
                            if (inFloorUp(page[0], page[1], w, h)) onFloor?.invoke(-1f)
                            if (inFloorDown(page[0], page[1], w, h)) onFloor?.invoke(1f)
                        }
                    }
                }
            }
            invalidate(); postInvalidateDelayed(260)      // and once more when a tap's short press is over
            return true
        }

        /** A mouse (Bluetooth or USB): moving it moves the pointer, its main button presses. */
        override fun onGenericMotionEvent(e: MotionEvent): Boolean {
            if (e.source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE) return super.onGenericMotionEvent(e)
            val now = System.nanoTime()
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> { lastX = e.x; lastY = e.y }
                // A mouse pointer moves with the screen as it is turned, not with the phone: no turning here.
                MotionEvent.ACTION_HOVER_MOVE -> { PointerInput.move(now, (e.x - lastX) * metresPerPixel, -(e.y - lastY) * metresPerPixel); lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_BUTTON_PRESS -> PointerInput.hold(now, 2, true)
                MotionEvent.ACTION_BUTTON_RELEASE -> PointerInput.hold(now, 2, false)
            }
            invalidate()
            return true
        }
    }

    companion object {
        @Volatile var running = false; private set

        /** Whether the owner has switched the touchpad on in Android's accessibility settings. */
        fun enabled(c: Context): Boolean {
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(c.packageName + "/") && it.endsWith("PointerService") }
        }
    }
}
