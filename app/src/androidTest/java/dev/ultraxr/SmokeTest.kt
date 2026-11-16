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
        for (section in listOf("== RESULTS ==", "== DISPLAY: GLASSES CONTROLLER ==", "== DISPLAY: ANDROID ==", "== DISPLAY: THIS APP'S OUTPUT ==", "== CAMERA AND IMU STREAM ==", "== BATTERY ==", "== LOG ==")) assertTrue("has $section", text.contains(section))
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

    @Test fun displayTestScreenOpensWithoutGlasses() {
        val a = launch(dev.ultraxr.ui.DisplayTestActivity::class.java)
        Thread.sleep(1500)
        shot("display_test")
        assertTrue(!a.isFinishing && !a.isDestroyed)
        assertTrue("its start is recorded for the report", Tracking.graphicsText().contains("display test: opened"))
        a.finish()
    }

    /** The calibration generated from a real unit's factory data must be accepted by both native trackers. */
    @Test fun trackersAcceptACalibrationBuiltFromFactoryData() {
        assertTrue(Native.loaded)
        val factory = """{"SLAM_camera":{"device_1":{"camera_model":"fisheye624","cc":[232.02590342647227,324.5942928446298],"fc":[240.86077123058172,240.92024063661967],"imu_p_cam":[-0.04967959960321735,0.00433374155753588,0.011311651098580188],"imu_q_cam":[0.06285692797149893,0.04924957365816318,0.001175119260441107,0.9968059516250889],"kc":[0.001347044168864677,0.09292397210330587,-0.12608944688553617,0.0757612423218236,-0.023502690616296274,0.002989744184794442,-0.0019208937268457163,0.0017076183796845344,0.005894207643521132,0.0004602924841651806,-0.003783511580892559,-0.0003513204828333599],"resolution":[480,640],"rolling_shutter_time":0},"device_2":{"camera_model":"fisheye624","cc":[233.92784790800658,316.4366062235467],"fc":[240.28730376723564,240.24853943907846],"imu_p_cam":[0.08760451646709011,0.004303619448099218,0.0118758365375769],"imu_q_cam":[0.06146633533269075,-0.03541011556855502,0.0029204333288411336,0.9974765583237347],"kc":[0.005502320066261727,0.0897843944656842,-0.12937333860709999,0.08215155564733244,-0.027032739862332553,0.00365380668616319,-0.0024892367183085877,-0.0016342062321606054,0.004216314726284504,0.00018120033256338274,0.005170384286702674,0.00011869369843011571],"resolution":[480,640],"rolling_shutter_time":0},"num_of_cameras":2}}"""
        val reference = ctx.assets.open("calibration/basalt_calib.json").bufferedReader().readText()
        val built = dev.ultraxr.core.FactoryCalibration.build(factory, reference)
        assertTrue(built.summary, built.basaltJson != null)
        val config = ctx.assets.open("calibration/basalt_vio_config.json").bufferedReader().readText()
        val v = Native.vioCreate(built.basaltJson!!, config)
        assertTrue("vioCreate with factory calibration: ${Native.lastError()}", v != 0L)
        Native.vioDestroy(v)
        val dir = File(ctx.filesDir, "hand-models-factory").apply { mkdirs() }
        for (name in ctx.assets.list("hand-models").orEmpty()) ctx.assets.open("hand-models/$name").use { i -> File(dir, name).outputStream().use { o -> i.copyTo(o) } }
        val calib = File(dir, "mercury_calib.json").apply { writeText(built.basaltJson) }
        val h = Native.handsCreate(calib.absolutePath, dir.absolutePath, 2)
        assertTrue("handsCreate with factory calibration: ${Native.lastError()}", h != 0L)
        Native.handsDestroy(h)
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
