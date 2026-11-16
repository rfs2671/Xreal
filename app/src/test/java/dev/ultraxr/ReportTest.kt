package dev.ultraxr

import dev.ultraxr.core.Fmt
import dev.ultraxr.core.ReportBuilder
import dev.ultraxr.core.ReportExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class ReportTest {
    private fun entries(zip: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = String(z.readBytes(), Charsets.UTF_8) }
        }
        return out
    }

    /** The defect that broke Share and Save: a literal percent sign inside a formatted template. */
    @Test fun theOriginalBatteryLineReallyThrows() {
        val start = 81; val end = 79
        var thrown: Throwable? = null
        try { "battery: $start% -> $end%, over %.0f s".format(12.0) } catch (t: Throwable) { thrown = t }
        assertTrue("this is the bug being guarded against", thrown is java.util.IllegalFormatException)
    }

    @Test fun numbersAreFormattedWithoutTemplates() {
        assertEquals("12.35", Fmt.f(12.345678, 2))
        assertEquals("60", Fmt.f(59.94f, 0))
        assertEquals("n/a", Fmt.f(Double.NaN, 1))
        assertEquals("0a", Fmt.hex(10)); assertEquals("0426", Fmt.hex(0x426, 4))
        // Locale independent: always a dot.
        val old = java.util.Locale.getDefault()
        try { java.util.Locale.setDefault(java.util.Locale.GERMANY); assertEquals("1.5", Fmt.f(1.5, 1)) } finally { java.util.Locale.setDefault(old) }
    }

    @Test fun percentSignsAndFormatDirectivesInDataAreJustText() {
        val nasty = "100% %s %d %n %-> 50%"
        val r = ReportBuilder().line("level 81% -> 79%")
            .section("DISPLAY") { it.append("display 2 '").append(nasty).append("'\n") }
            .section("BATTERY") { it.append("level ").append(81).append(" percent -> ").append(79).append(" percent over ").append(Fmt.f(61.2, 0)).append(" s") }
            .build()
        assertTrue(r.contains(nasty)); assertTrue(r.contains("level 81% -> 79%")); assertTrue(r.contains("over 61 s\n"))
    }

    @Test fun aFailingSectionIsRecordedAndTheRestSurvives() {
        val b = ReportBuilder()
        val r = b.section("ONE") { it.append("first\n") }
            .section("BROKEN") { it.append("partial\n"); "50% -> 40%".format(1.0); it.append("never\n") }
            .section("THREE") { it.append("third\n") }
            .build()
        assertEquals(1, b.failedSections)
        assertTrue(r.contains("first")); assertTrue(r.contains("partial")); assertTrue(r.contains("third"))
        assertTrue(r.contains("!! this section failed")); assertTrue(r.contains("FormatConversion"))
        assertFalse(r.contains("never"))
    }

    @Test fun exportAlwaysHoldsTheReportAndTheSensorFiles() {
        val dir = createTempDir()
        File(dir, "camera_raw_frames.bin").writeBytes(ByteArray(1000) { it.toByte() })
        File(dir, "imu_reports.bin").writeBytes(ByteArray(10))
        File(dir, "report.txt").writeText("stale copy from an earlier write")
        val out = ByteArrayOutputStream()
        val names = ReportExport.writeZip(out, { "fresh report 100%" }, dir.listFiles()!!.toList())
        val e = entries(out.toByteArray())
        assertEquals("fresh report 100%", e["report.txt"])
        assertEquals(setOf("report.txt", "camera_raw_frames.bin", "imu_reports.bin"), e.keys)
        assertEquals(e.keys.toList().sorted(), names.sorted())
        dir.deleteRecursively()
    }

    @Test fun exportExplainsItselfWhenTheReportCannotBeBuilt() {
        val dir = createTempDir()
        File(dir, "imu_reports.bin").writeBytes(ByteArray(10))
        val gone = File(dir, "vanished.bin") // listed but unreadable
        val out = ByteArrayOutputStream()
        val names = ReportExport.writeZip(out, { "battery: 81% -> 79% %.0f".format(1.0) }, listOf(File(dir, "imu_reports.bin"), gone, dir))
        val e = entries(out.toByteArray())
        assertFalse(e.containsKey("report.txt"))
        assertTrue(e["report_error.txt"]!!.startsWith("Report generation failed."))
        assertTrue(e["report_error.txt"]!!.contains("FormatConversion"))
        assertTrue("sensor data still exported", e.containsKey("imu_reports.bin"))
        assertTrue(names.contains("report_error.txt"))
        val (text, ok) = ReportExport.safeText { throw IllegalStateException("boom") }
        assertFalse(ok); assertTrue(text.contains("boom"))
        dir.deleteRecursively()
    }

    /**
     * Source audit: no String.format on a template that also interpolates data. That combination
     * is what crashed the report; device strings (display names, serials) can contain '%'.
     */
    @Test fun noFormatCallOnAnInterpolatedTemplate() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val literalThenFormat = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\.format\\(")
        val offenders = ArrayList<String>()
        var scanned = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                for (m in literalThenFormat.findAll(line)) {
                    scanned++
                    val template = m.groupValues[1]
                    // Interpolation, or a '%' that is not a recognised directive (e.g. "50% of").
                    val interpolates = Regex("(?<!\\\\)\\$[{A-Za-z_]").containsMatchIn(template)
                    val stray = Regex("%(?![-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%])").containsMatchIn(template)
                    if (interpolates || stray) offenders += "${f.path}:${i + 1}: ${line.trim().take(140)}"
                }
            }
        }
        assertTrue("the audit found the source tree", scanned > 10)
        assertTrue("unsafe format calls:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
