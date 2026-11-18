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
 * asks for no accessibility events: it only shows its own touchpad window and listens to the volume keys.
 *
 * Drag: move the pointer. Tap: select. Two fingers down, or volume-down held: press and keep pressed (grab and
 * carry). Volume-up: recentre the view and the pointer. A mouse moves and clicks the same pointer.
 */
class PointerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pad: Pad? = null
    private var keysLogged = 0
    private val tick = object : Runnable {
        override fun run() { sync(); handler.postDelayed(this, 500) }
    }

    override fun onServiceConnected() { running = true; handler.post(tick) }
    override fun onUnbind(intent: android.content.Intent?): Boolean { running = false; handler.removeCallbacks(tick); hide(); return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun wanted() = XrHost.applicationConnected

    private fun sync() { if (wanted() && pad == null) show() else if (!wanted() && pad != null) hide() }

    private fun show() {
        val v = Pad(this)
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp); pad = v; Tracking.log("pointer: touchpad shown on the phone") }
        catch (t: Throwable) { Tracking.log("pointer: touchpad could not be shown: $t") }
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
                "Volume-up: recentre" to 17f, "Back: leave the game" to 17f, "" to 10f, "Selections so far: " + PointerInput.clicks to 14f)
            var y = height * 0.30f
            for ((text, size) in lines) {
                paint.textSize = size * density; paint.color = if (size > 20f) 0xFFE8F0FA.toInt() else 0xFF9FB0C4.toInt()
                paint.textAlign = Paint.Align.CENTER
                if (text.isNotEmpty()) canvas.drawText(text, width / 2f, y, paint)
                y += size * density * 1.7f
            }
            if (PointerInput.pressed(System.nanoTime())) { paint.color = 0xFF3D8BFF.toInt(); canvas.drawRect(0f, 0f, width.toFloat(), 6 * density, paint) }
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
                    if (e.actionMasked == MotionEvent.ACTION_UP && !many && now - downNs < 300_000_000L && travelled < 24 * density) PointerInput.tap(now)
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
