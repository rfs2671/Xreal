package dev.ultraxr.xr

import android.content.Context
import dev.ultraxr.core.RoomSurface
import dev.ultraxr.openxr.UltraXrFeed
import dev.ultraxr.tracking.Tracking
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A small web server on this phone's own localhost (127.0.0.1 only; nothing off the phone can reach it). A browser
 * on the phone treats localhost as a secure origin, so pages from here can use WebXR. It serves
 *
 *   /room.json            the room as the tracking cameras see it: the floor, table tops and walls, and where the
 *                         head is, all in one frame (metres, +y up, the floor at y = 0 once it is measured). Only
 *                         while an XR application is running; a page reads it a few times a second.
 *   /ultraxr-room.js      the page-side half (assets/site): lines the room up with the page's own reference space
 *                         and gives it ray casts against the surfaces, and, for pages written for phones' AR, the
 *                         standard WebXR hit-test, plane-detection and anchor calls, which the browser lacks.
 *   /room.html, ...       Ultra XR's own pages (assets/site)
 *   /p/https/HOST/PATH    another site's page with ultraxr-room.js added to it, so a game written for the standard
 *                         hit test works unchanged. Only for sites of the pages in the game list.
 *
 * and, in test builds when a test switches it on, files pushed to the app's external folder "site" and a /report
 * the test page posts its results to.
 */
object LocalSite {
    const val PORT = 8765
    @Volatile private var server: ServerSocket? = null
    @Volatile private var app: Context? = null
    /** Test builds: also serve files/site and accept /report. */
    @Volatile var testFiles = false
    /** Sites the /p/ route may fetch: the hosts of the pages in the game list. */
    @Volatile var proxyHosts: Set<String> = emptySet()

    fun start(context: Context) {
        if (server != null) return
        app = context.applicationContext
        try {
            proxyHosts = dev.ultraxr.core.GameLibrary.parse(context.assets.open("webxr_pages.json").bufferedReader().use { it.readText() }).mapNotNull { g ->
                val u = g.url
                if (u.startsWith("http://localhost:$PORT/p/")) u.substringAfter("/p/").split("/").getOrNull(1) else null
            }.toSet()
        } catch (_: Exception) {}
        try {
            val s = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1")); server = s
            thread(name = "local-site", isDaemon = true) {
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { try { handle(c) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} } }
                }
            }
            Tracking.log("local site on http://localhost:$PORT (room.json, ultraxr-room.js; other sites allowed through /p/: $proxyHosts)")
        } catch (e: Exception) { Tracking.log("local site did not start: $e") }
    }

    // Any page may read the room while a session runs: the page of a game on another site has to. The second header
    // answers the browser's question before it lets a public page talk to this phone itself.
    private const val CORS = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Private-Network: true\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\n"

    private fun send(out: java.io.OutputStream, status: String, type: String, body: ByteArray) {
        out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\n${CORS}Connection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    private fun type(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"; "js", "mjs" -> "text/javascript; charset=utf-8"; "css" -> "text/css"; "json" -> "application/json"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "svg" -> "image/svg+xml"; "wasm" -> "application/wasm"
        "glb" -> "model/gltf-binary"; "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private fun handle(c: Socket) {
        val context = app ?: return
        c.soTimeout = 8000
        val input = c.getInputStream().buffered(); val out = c.getOutputStream()
        val head = StringBuilder()
        while (true) {      // read the header, up to the blank line
            val b = input.read(); if (b < 0) return
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n") || head.length > 16384) break
        }
        val lines = head.lines(); val first = lines.firstOrNull()?.split(" ") ?: return
        if (first.size < 2) return
        val method = first[0]; val full = first[1]; val path = full.substringBefore('?')
        val length = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (method == "OPTIONS") { out.write(("HTTP/1.1 204 No Content\r\n${CORS}Connection: close\r\n\r\n").toByteArray()); out.flush(); return }
        if (method == "POST") {
            val body = ByteArray(length.coerceIn(0, 1 shl 20)); var n = 0
            while (n < body.size) { val r = input.read(body, n, body.size - n); if (r < 0) break; n += r }
            if (path == "/report" && testFiles) { val report = File(context.getExternalFilesDir(null), "report.json"); val tmp = File(report.path + ".tmp"); tmp.writeBytes(body.copyOf(n)); tmp.renameTo(report) }
            out.write(("HTTP/1.1 204 No Content\r\n${CORS}Connection: close\r\n\r\n").toByteArray()); out.flush(); return
        }
        if (path == "/room.json") { send(out, "200 OK", "application/json", roomJson().toByteArray()); return }
        if (path.startsWith("/p/")) { proxy(full.removePrefix("/p/"), out); return }
        val name = path.trimStart('/').ifEmpty { "index.html" }
        if (name.contains("..")) { send(out, "404 Not Found", "text/plain", ByteArray(0)); return }
        if (testFiles) {
            val f = File(File(context.getExternalFilesDir(null), "site"), name)
            if (f.isFile) { send(out, "200 OK", type(name), f.readBytes()); return }
        }
        val bytes = try { context.assets.open("site/$name").use { it.readBytes() } } catch (_: Exception) { null }
        if (bytes == null) send(out, "404 Not Found", "text/plain", ByteArray(0)) else send(out, "200 OK", type(name), bytes)
    }

    /** [rest] is "https/HOST/PATH?QUERY". The page comes back with our script added; anything else comes back as it is. */
    private fun proxy(rest: String, out: java.io.OutputStream) {
        val parts = rest.split("/", limit = 3)
        if (parts.size < 2 || parts[0] != "https" || parts[1] !in proxyHosts) { send(out, "403 Forbidden", "text/plain", "this site is not in Ultra XR's game list".toByteArray()); return }
        val url = "https://" + parts[1] + "/" + (parts.getOrNull(2) ?: "")
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 15000; conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) UltraXR")
            val code = conn.responseCode
            val ct = conn.contentType ?: "application/octet-stream"
            var body = (if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)).use { it.readBytes() }
            if (code in 200..299 && ct.startsWith("text/html")) body = inject(String(body, Charsets.UTF_8), url.substringBefore('?').substringBeforeLast('/') + "/").toByteArray(Charsets.UTF_8)
            send(out, "$code " + (conn.responseMessage ?: ""), if (ct.startsWith("text/html")) "text/html; charset=utf-8" else ct, body)
        } catch (e: Exception) {
            Tracking.log("local site: could not fetch $url: $e")
            send(out, "502 Bad Gateway", "text/plain", ("could not fetch " + url + ": " + e).toByteArray())
        }
    }

    /** [html] with a base address (so the page's own files still come from its site) and our script, first thing in its head. */
    fun inject(html: String, base: String): String {
        val add = "<base href=\"$base\"><script src=\"http://localhost:$PORT/ultraxr-room.js\"></script>"
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (m != null) html.substring(0, m.range.last + 1) + add + html.substring(m.range.last + 1) else add + html
    }

    private fun f(v: Double) = String.format(Locale.US, "%.4f", v)

    /**
     * The room for a page. Everything is in the frame the runtime is fed in, lifted by the floor offset given to the
     * runtime: the frame in which the measured floor is y = 0. The page lines this frame up with its own from the head
     * pose, which is in both.
     */
    fun roomJson(): String {
        val sb = StringBuilder("{\"v\":1,\"t_ms\":").append(System.currentTimeMillis())
        if (!XrHost.applicationConnected) return sb.append(",\"session\":false}").toString()
        val h = XrHost.originHeight.toDouble()
        val head = UltraXrFeed.lastHead
        sb.append(",\"session\":true,\"head\":{\"age_ms\":").append((System.nanoTime() - UltraXrFeed.lastHeadNs) / 1_000_000)
            .append(",\"p\":[").append(f(head[0].toDouble())).append(',').append(f(head[1] + h)).append(',').append(f(head[2].toDouble()))
            .append("],\"q\":[").append(f(head[3].toDouble())).append(',').append(f(head[4].toDouble())).append(',').append(f(head[5].toDouble())).append(',').append(f(head[6].toDouble())).append("]}")
        // table tops and walls are only where they are said to be while position is tracked
        val tracked = Tracking.simulateWearer || Tracking.head.predict(System.nanoTime()).mode == dev.ultraxr.core.HeadTracker.Mode.SIX_DOF
        sb.append(",\"tracked\":").append(tracked)
        val r = Tracking.roomFindings
        val measured = r != null && !r.floorY.isNaN()
        sb.append(",\"floor\":{\"measured\":").append(measured).append(",\"y\":").append(f(if (measured) r!!.floorY - XrHost.floorTrimM + h else 0.0)).append("}")
        sb.append(",\"surfaces\":[")
        var n = 0
        if (r != null) for (s in r.surfaces) {
            if (!tracked && s.kind != RoomSurface.Kind.FLOOR) continue
            if (n++ > 0) sb.append(',')
            sb.append("{\"kind\":\"").append(when (s.kind) { RoomSurface.Kind.FLOOR -> "floor"; RoomSurface.Kind.TABLE -> "table"; RoomSurface.Kind.WALL -> "wall" })
                .append("\",\"c\":[").append(f(s.cx)).append(',').append(f(s.cy + h)).append(',').append(f(s.cz))
                .append("],\"u\":[").append(f(s.ux)).append(',').append(f(s.uy)).append(',').append(f(s.uz))
                .append("],\"v\":[").append(f(s.vx)).append(',').append(f(s.vy)).append(',').append(f(s.vz))
                .append("],\"n\":[").append(f(s.nx)).append(',').append(f(s.ny)).append(',').append(f(s.nz))
                .append("],\"hu\":").append(f(s.halfU)).append(",\"hv\":").append(f(s.halfV)).append(",\"support\":").append(s.support).append('}')
        }
        sb.append("]")
        // where the cameras measured things in their newest picture, while it is fresh: a page can draw them on the things
        val pts = Tracking.roomPoints
        if (tracked && pts.isNotEmpty() && (Tracking.simulateWearer || System.nanoTime() - Tracking.roomPointsNs < 1_500_000_000L)) {
            sb.append(",\"points\":[")
            for (i in 0 until pts.size / 3) { if (i > 0) sb.append(','); sb.append(f(pts[i * 3].toDouble())).append(',').append(f(pts[i * 3 + 1] + h)).append(',').append(f(pts[i * 3 + 2].toDouble())) }
            sb.append("]")
        }
        return sb.append("}").toString()
    }
}
