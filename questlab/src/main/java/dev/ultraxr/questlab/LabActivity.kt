package dev.ultraxr.questlab

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Runs the synthetic Quest API test once and shows (and saves) what each call answered. */
class LabActivity : Activity() {
    override fun onDestroy() { android.util.Log.i("QuestLab", "screen destroyed"); super.onDestroy() }
    override fun onStop() { android.util.Log.i("QuestLab", "screen stopped"); super.onStop() }
    private external fun run(activity: Activity, seconds: Int, roomSeconds: Int, drawSeconds: Int, vulkan: Boolean, setupSeconds: Int): String
    /** Words for the glasses, drawn here because Android can: 1024 x 256 pixels, white on nothing, a line a line of [text]. Called from the test's own thread. */
    @Suppress("unused") fun textPixels(text: String): IntArray {
        val w = 1024; val h = 256
        val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = -1; textSize = 42f; textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        text.split('\n').forEachIndexed { i, l -> c.drawText(l, w / 2f, 62f + i * 80f, p) }
        val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h); return px
    }
    companion object { private val started = java.util.concurrent.atomic.AtomicBoolean(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("QuestLab", "screen made (" + (if (savedInstanceState == null) "new" else "again") + ")")
        val text = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24); text = "Running the Quest API test..." }
        setContentView(ScrollView(this).apply { addView(text) })
        val seconds = intent.getIntExtra("seconds", 8); val roomSeconds = intent.getIntExtra("room_seconds", 0)
        val drawSeconds = intent.getIntExtra("draw_seconds", 0); val vulkan = intent.getBooleanExtra("vulkan", false)
        val setupSeconds = intent.getIntExtra("setup_seconds", 0)      // room set-up: the wearer marks the corners of the room
        getExternalFilesDir(null)      // makes the folder the native side writes its files in
        // once a process: if Android makes this screen anew (a turn of the phone), the test already running goes on
        if (!started.compareAndSet(false, true)) { text.text = "The test is already running; its report will be in quest_api_report.txt"; return }
        Thread {
            val report = try { System.loadLibrary("questlab"); run(this, seconds, roomSeconds, drawSeconds, vulkan, setupSeconds) } catch (t: Throwable) { "FAIL  the test could not run: $t" }
            try { File(getExternalFilesDir(null), if (vulkan) "quest_api_report_vulkan.txt" else "quest_api_report.txt").writeText(report) } catch (_: Exception) {}
            runOnUiThread { text.text = report }
            // This thread made the OpenXR calls, and the runtime's library (unloaded when the instance was destroyed)
            // left thread-local clean-up behind on it: if the thread ended, that clean-up would run code that is no
            // longer there and the process would die. A game's render thread lives as long as its process; so does this one.
            try { java.util.concurrent.CountDownLatch(1).await() } catch (_: InterruptedException) {}
        }.start()
    }
}
