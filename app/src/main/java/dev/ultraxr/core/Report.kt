package dev.ultraxr.core

import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Number formatting that is safe to mix with arbitrary text. Rule for this codebase: never call
 * String.format on a template that also contains interpolated data (a '%' in a display name,
 * a serial number or a battery line would be read as a format directive and throw).
 * Format the numbers here, then concatenate.
 */
object Fmt {
    fun f(v: Double, digits: Int = 1): String = if (v.isNaN()) "n/a" else String.format(Locale.US, "%." + digits.coerceIn(0, 9) + "f", v)
    fun f(v: Float, digits: Int = 1): String = f(v.toDouble(), digits)
    fun hex(v: Int, width: Int = 2): String = Integer.toHexString(v).padStart(width, '0')
}

/** Builds a text report section by section; a section that throws is recorded, not fatal. */
class ReportBuilder {
    private val sb = StringBuilder()
    var failedSections = 0; private set

    fun line(text: String): ReportBuilder { sb.append(text).append('\n'); return this }

    fun section(title: String, body: (StringBuilder) -> Unit): ReportBuilder {
        sb.append("\n== ").append(title).append(" ==\n")
        val part = StringBuilder()
        try { body(part); sb.append(part) }
        catch (t: Throwable) {
            failedSections++
            sb.append(part) // keep whatever was written before the failure
            sb.append("\n!! this section failed: ").append(ReportExport.stackTrace(t)).append('\n')
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
        return this
    }

    fun build(): String = sb.toString()
}

object ReportExport {
    const val REPORT_NAME = "report.txt"
    const val ERROR_NAME = "report_error.txt"

    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    /** The report text, or a description of why it could not be produced. Never throws. */
    fun safeText(produce: () -> String): Pair<String, Boolean> =
        try { produce() to true } catch (t: Throwable) { ("Report generation failed.\n" + stackTrace(t)) to false }

    /**
     * Writes the export archive. It always contains [REPORT_NAME], or [ERROR_NAME] explaining why
     * the report could not be generated. Extra files that cannot be read are listed in
     * "export_notes.txt" instead of aborting the export. Returns the entry names written.
     */
    fun writeZip(out: OutputStream, produce: () -> String, extras: List<File>): List<String> {
        val names = ArrayList<String>()
        val notes = StringBuilder()
        ZipOutputStream(out).use { zip ->
            val (text, ok) = safeText(produce)
            val name = if (ok) REPORT_NAME else ERROR_NAME
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry(); names += name
            for (f in extras) {
                if (f.name == REPORT_NAME || f.name == ERROR_NAME || !f.isFile) continue
                try {
                    val bytes = f.readBytes() // read fully first so a failure leaves no half-written entry
                    zip.putNextEntry(ZipEntry(f.name)); zip.write(bytes); zip.closeEntry(); names += f.name
                } catch (t: Throwable) { notes.append(f.name).append(": ").append(t).append('\n') }
            }
            if (notes.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("export_notes.txt")); zip.write(("Files that could not be added:\n$notes").toByteArray(Charsets.UTF_8)); zip.closeEntry(); names += "export_notes.txt"
            }
        }
        return names
    }
}
