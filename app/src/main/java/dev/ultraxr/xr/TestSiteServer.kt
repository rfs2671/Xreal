package dev.ultraxr.xr

import android.content.Context
import dev.ultraxr.tracking.Tracking
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Test builds only: serves the WebXR test pages on this device's own localhost, so a browser on the
 * device loads them from a secure context (WebXR is hidden from plain http pages elsewhere) without
 * any port forwarding. Files come from the app's external files folder, "site" (pushed there by the
 * test); what a page POSTs to /report is kept as "report.json" beside it for the test to read.
 *
 * Listens on 127.0.0.1 only. Started by the launcher's test switch in debuggable builds.
 */
object TestSiteServer {
    @Volatile private var server: ServerSocket? = null

    fun start(context: Context, port: Int = 8765) {
        if (server != null) return
        val root = File(context.getExternalFilesDir(null), "site")
        root.mkdirs()
        val report = File(context.getExternalFilesDir(null), "report.json")
        try {
            val s = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1")); server = s
            thread(name = "test-site", isDaemon = true) {
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { try { handle(c, root, report) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} } }
                }
            }
            Tracking.log("test site served from " + root + " on http://localhost:" + port)
        } catch (e: Exception) { Tracking.log("test site server did not start: $e") }
    }

    private fun handle(c: Socket, root: File, report: File) {
        c.soTimeout = 5000
        val input = c.getInputStream().buffered(); val out = c.getOutputStream()
        val head = StringBuilder()
        while (true) {      // read the header, up to the blank line
            val b = input.read(); if (b < 0) return
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n") || head.length > 16384) break
        }
        val lines = head.lines(); val first = lines.firstOrNull()?.split(" ") ?: return
        if (first.size < 2) return
        val method = first[0]; val path = first[1].substringBefore('?')
        val length = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (method == "POST") {
            val body = ByteArray(length.coerceIn(0, 1 shl 20)); var n = 0
            while (n < body.size) { val r = input.read(body, n, body.size - n); if (r < 0) break; n += r }
            if (path == "/report") { val tmp = File(report.path + ".tmp"); tmp.writeBytes(body.copyOf(n)); tmp.renameTo(report) }
            out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
        }
        val name = path.trimStart('/').ifEmpty { "index.html" }
        val f = File(root, name)
        if (name.contains("..") || !f.isFile) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return }
        val type = when (f.extension.lowercase()) {
            "html" -> "text/html; charset=utf-8"; "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "svg" -> "image/svg+xml"; "wasm" -> "application/wasm"
            "glb" -> "model/gltf-binary"; "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
        val bytes = f.readBytes()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes); out.flush()
    }
}
