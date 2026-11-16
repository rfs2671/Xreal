package dev.ultraxr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ultraxr.glasses.GlassesLink
import dev.ultraxr.tracking.Native
import dev.ultraxr.tracking.Tracking
import dev.ultraxr.ui.CheckActivity
import dev.ultraxr.ui.LauncherActivity
import dev.ultraxr.ui.PlayActivity
import dev.ultraxr.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Runs on an emulator in CI. There are no glasses there, so this proves only that the app
 * starts, its screens render, the native tracking libraries load and run on empty input,
 * and the game renders in the phone preview. It says nothing about the real hardware.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun <T : Activity> launch(cls: Class<T>): Activity {
        val a = inst.startActivitySync(Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        inst.waitForIdleSync()
        return a
    }

    private fun shot(name: String) {
        try {
            val bmp = inst.uiAutomation.takeScreenshot() ?: return
            val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (_: Throwable) {}
    }

    @Test fun launcherStartsWithoutGlasses() {
        val a = launch(LauncherActivity::class.java)
        Thread.sleep(2500)
        shot("launcher")
        assertEquals(GlassesLink.State.NO_GLASSES, Tracking.snapshot().link)
        a.finish()
    }

    @Test fun settingsAndCheckOpen() {
        val s = launch(SettingsActivity::class.java); Thread.sleep(800); shot("settings"); s.finish()
        val c = launch(CheckActivity::class.java); Thread.sleep(800); shot("check"); c.finish()
    }

    @Test fun nativeTrackingLoadsAndRunsOnEmptyInput() {
        assertTrue("native library: ${Native.loadError}", Native.loaded)
        val calib = ctx.assets.open("calibration/basalt_calib.json").bufferedReader().readText()
        val config = ctx.assets.open("calibration/basalt_vio_config.json").bufferedReader().readText()
        val h = Native.vioCreate(calib, config)
        assertTrue("vioCreate: ${Native.lastError()}", h != 0L)
        val img = ByteArray(480 * 640)
        var t = 1_000_000_000L
        repeat(20) { f ->
            repeat(33) { Native.vioPushImu(h, t, 0f, 0f, 0f, 0f, 0f, 9.80665f); t += 1_000_000L }
            Native.vioPushStereo(h, t, img, img, 480, 640)
        }
        Thread.sleep(1500)
        val out = DoubleArray(22)
        Native.vioGetState(h, out)
        assertTrue("frames accepted", out[15] >= 1.0)
        Native.vioReset(h)
        Native.vioDestroy(h)
    }

    @Test fun handTrackingRunsAndSeesNoHandsInBlankImages() {
        assertTrue("native library: ${Native.loadError}", Native.loaded)
        val dir = File(ctx.filesDir, "hand-models-test").apply { mkdirs() }
        for (name in ctx.assets.list("hand-models").orEmpty()) ctx.assets.open("hand-models/$name").use { i -> File(dir, name).outputStream().use { o -> i.copyTo(o) } }
        val calib = File(dir, "mercury_calib.json")
        ctx.assets.open("calibration/mercury_calib.json").use { i -> calib.outputStream().use { o -> i.copyTo(o) } }
        val h = Native.handsCreate(calib.absolutePath, dir.absolutePath, 2)
        assertTrue("handsCreate: ${Native.lastError()}", h != 0L)
        val img = ByteArray(480 * 640) { 40 }
        val out = FloatArray(2 * 21 * 3)
        var mask = -1
        repeat(3) { mask = Native.handsProcess(h, 1_000_000_000L + it * 33_000_000L, img, img, 480, 640, out, FloatArray(2 * 26 * 7)) }
        assertEquals("no hands in a flat grey image", 0, mask)
        Native.handsDestroy(h)
    }

    /** The app's own start-up path: assets copied out of the APK, both trackers created. */
    @Test fun appStartsBothTrackersFromItsAssets() {
        Tracking.acquire(ctx)
        try {
            val until = System.currentTimeMillis() + 90_000
            var snap = Tracking.snapshot()
            while (System.currentTimeMillis() < until && !(snap.vioActive && snap.handsActive) && Tracking.vioError.isEmpty() && Tracking.handsError.isEmpty()) {
                Thread.sleep(500); snap = Tracking.snapshot()
            }
            assertEquals("6DoF start-up error", "", Tracking.vioError)
            assertEquals("hand tracking start-up error", "", Tracking.handsError)
            assertTrue("6DoF estimator created", snap.vioActive)
            assertTrue("hand tracker created", snap.handsActive)
            assertTrue("OpenXR runtime library loaded: ${dev.ultraxr.xr.XrRuntime.detail}", dev.ultraxr.xr.XrRuntime.available)
        } finally { Tracking.release() }
    }

    /** Regression: the report used to throw on a literal percent sign, which broke Share. */
    @Test fun shareBuildsACompleteReportAndOpensTheShareSheet() {
        val a = launch(CheckActivity::class.java) as CheckActivity
        Thread.sleep(800)
        val text = a.reportTextSafely()
        assertTrue("report was generated, not an error text: ${text.take(300)}", text.startsWith("Ultra XR hardware check"))
        for (section in listOf("== RESULTS ==", "== DISPLAY: GLASSES CONTROLLER ==", "== DISPLAY: ANDROID ==", "== DISPLAY: THIS APP'S OUTPUT ==", "== BATTERY ==", "== LOG ==")) assertTrue("has $section", text.contains(section))
        assertTrue("no section failed:\n" + text.lines().filter { it.startsWith("!!") }.joinToString("\n"), !text.contains("!! this section failed"))
        assertTrue("battery line present", Regex("level \\d+ percent -> \\d+ percent").containsMatchIn(text))
        assertTrue("phone display described", text.contains("display 0 '"))
        // The button's own code path: must not throw or finish the screen.
        inst.runOnMainSync { a.share() }
        Thread.sleep(1500)
        assertTrue("check screen survived Share", !a.isFinishing && !a.isDestroyed)
        assertTrue("no share failure logged", !Tracking.logText().contains("share failed"))
        inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(500)
        a.finish()
    }

    /** Regression: Save used to produce a zip without report.txt. */
    @Test fun saveWritesAZipWithTheReportToDownloads() {
        val a = launch(CheckActivity::class.java) as CheckActivity
        Thread.sleep(800)
        // The archive as the button builds it.
        val bytes = java.io.ByteArrayOutputStream().also { a.exportZip(it) }.toByteArray()
        val names = ArrayList<String>()
        var report = ""
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name; if (e.name == "report.txt") report = String(z.readBytes()) } }
        assertTrue("zip entries: $names", "report.txt" in names)
        assertTrue(report.startsWith("Ultra XR hardware check"))
        // And the full button path into the Downloads collection.
        val latch = java.util.concurrent.CountDownLatch(1)
        var message = ""
        inst.runOnMainSync { a.save { message = it; latch.countDown() } }
        assertTrue("save finished", latch.await(30, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("save message: $message", message.startsWith("Saved to Downloads as ultra-xr-") && message.contains("report.txt"))
        val name = Regex("ultra-xr-[^ ]+\\.zip").find(message)!!.value
        val uri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var found = false
        ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Downloads._ID), android.provider.MediaStore.Downloads.DISPLAY_NAME + "=?", arrayOf(name), null)?.use { c ->
            if (c.moveToFirst()) {
                val item = android.content.ContentUris.withAppendedId(uri, c.getLong(0))
                val saved = ArrayList<String>()
                ctx.contentResolver.openInputStream(item)!!.use { i -> java.util.zip.ZipInputStream(i).use { z -> while (true) { val e = z.nextEntry ?: break; saved += e.name } } }
                found = "report.txt" in saved
                ctx.contentResolver.delete(item, null, null)
            }
        }
        assertTrue("the saved zip in Downloads holds report.txt", found)
        a.finish()
    }

    @Test fun gameRendersInPhonePreview() {
        val a = launch(PlayActivity::class.java) as PlayActivity
        Thread.sleep(4000)
        shot("play")
        val r = a.stageForTest().renderer
        assertTrue("renderer exists", r != null)
        assertEquals("no GL error", "", r!!.glError)
        assertTrue("frames drawn: ${r.frames}", r.frames > 10)
        a.finish()
    }
}
