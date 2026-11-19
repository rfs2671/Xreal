package dev.ultraxr.shell

import android.content.Context
import android.content.Intent
import dev.ultraxr.tracking.Tracking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * This app's line to the shell helper ([ShellMain]): commands are queued and sent from one thread, in order. Without
 * the helper running nothing here does anything, and [available] is false: real apps then cannot be shown in windows.
 */
object AppShell {
    @Volatile var available = false; private set
    @Volatile var lastError = ""; private set
    private val queue = LinkedBlockingQueue<Pair<String, ((String) -> Unit)?>>()
    private var thread: Thread? = null
    private var token = ""
    private var app: Context? = null
    private var startTriedNs = 0L

    /** Writes the token the helper must be given (it reads the file when it starts) and starts trying to reach it. */
    @Synchronized fun start(context: Context) {
        if (thread != null) return
        val f = File(context.getExternalFilesDir(null), "shell_token.txt")
        token = try { f.readText().trim() } catch (_: Exception) { "" }
        if (token.length < 16) {
            val r = java.security.SecureRandom(); token = (1..32).map { "0123456789abcdef"[r.nextInt(16)] }.joinToString("")
            try { f.writeText(token) } catch (e: Exception) { lastError = e.toString() }
        }
        app = context.applicationContext
        thread = Thread({ loop() }, "app-shell").apply { isDaemon = true; start() }
    }

    fun send(command: String, answer: ((String) -> Unit)? = null) { queue.offer(command to answer) }

    private fun loop() {
        while (true) {
            var s: Socket? = null
            try {
                s = Socket(); s.connect(InetSocketAddress("127.0.0.1", ShellMain.PORT), 800); s.tcpNoDelay = true
                val r = BufferedReader(InputStreamReader(s.getInputStream())); val w = s.getOutputStream()
                w.write((token + "\n").toByteArray()); w.flush()
                val hello = r.readLine() ?: throw java.io.IOException("closed")
                if (!hello.startsWith("ok")) throw java.io.IOException("refused: $hello")
                // a helper left from before an update runs from a file that is gone: it ends, and the new one is started
                val from = hello.split(' ').getOrElse(2) { "" }; val mine = app?.applicationInfo?.sourceDir ?: ""
                if (from.isNotEmpty() && mine.isNotEmpty() && from != mine) { w.write("quit\n".toByteArray()); w.flush(); r.readLine(); throw java.io.IOException("helper from an older install, replaced") }
                if (!available) Tracking.log("apps: the shell helper answers ($hello): other apps can run in windows")
                available = true; lastError = ""
                while (true) {
                    val c = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: ("ping" to null)
                    w.write((c.first + "\n").toByteArray()); w.flush()
                    val a = r.readLine() ?: throw java.io.IOException("closed")
                    if (a.startsWith("error")) Tracking.log("apps: \"" + c.first.take(60) + "\" -> " + a)
                    try { c.second?.invoke(a) } catch (_: Throwable) {}
                }
            } catch (e: Exception) {
                if (available) Tracking.log("apps: the shell helper is gone ($e)")
                available = false; lastError = e.toString()
                // what was asked of a helper that is not there is not kept for one that may come later
                while (true) { val c = queue.poll() ?: break; try { c.second?.invoke("error no helper") } catch (_: Throwable) {} }
            } finally { try { s?.close() } catch (_: Exception) {} }
            // No helper: start it from the phone itself, if its owner has debugging over the network on. Not more
            // often than every 20 s; the first time Android asks the owner on the screen.
            val c = app; val now = System.nanoTime()
            if (!available && c != null && now - startTriedNs > 20_000_000_000L) {
                startTriedNs = now
                if (AdbStarter.startHelper(c)) { try { Thread.sleep(1500) } catch (_: InterruptedException) { return }; continue }
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    /** One installed app that has a launcher icon. */
    class App(val label: String, val pkg: String, val component: String)

    /** The apps on this phone that can be started from a launcher, by name. */
    fun installed(context: Context): List<App> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(main, 0).mapNotNull { ri ->
                val a = ri.activityInfo ?: return@mapNotNull null
                if (a.packageName == context.packageName) null
                else App(ri.loadLabel(pm).toString(), a.packageName, a.packageName + "/" + a.name)
            }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        } catch (e: Exception) { emptyList() }
    }

    /** The launcher activity of [pkg], or null when it is not installed. */
    fun componentOf(context: Context, pkg: String): String? =
        try { context.packageManager.getLaunchIntentForPackage(pkg)?.component?.let { it.packageName + "/" + it.className } } catch (_: Exception) { null }
}
