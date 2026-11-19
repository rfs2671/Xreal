package dev.ultraxr.questlab

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Runs the synthetic Quest API test once and shows (and saves) what each call answered. */
class LabActivity : Activity() {
    private external fun run(activity: Activity, seconds: Int): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24); text = "Running the Quest API test..." }
        setContentView(ScrollView(this).apply { addView(text) })
        val seconds = intent.getIntExtra("seconds", 8)
        Thread {
            val report = try { System.loadLibrary("questlab"); run(this, seconds) } catch (t: Throwable) { "FAIL  the test could not run: $t" }
            try { File(getExternalFilesDir(null), "quest_api_report.txt").writeText(report) } catch (_: Exception) {}
            runOnUiThread { text.text = report }
        }.start()
    }
}
