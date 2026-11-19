package dev.ultraxr.shell

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The helper that lets other apps run inside Ultra XR's windows. It is not part of the app's own process: it is
 * started with the phone's debugging access (adb), as the "shell" user,
 *
 *   CLASSPATH=<this apk> app_process / dev.ultraxr.shell.ShellMain
 *
 * and does the two things Android allows that user and not an ordinary app: start another app's activity on a
 * display this app owns (the virtual display behind a window), and send touches and keys to it. It listens on this
 * phone only (127.0.0.1) and obeys only a caller that knows the token the app wrote to its own folder.
 *
 * One line per command, answered with one line ("ok ..." or "error ..."):
 *   launch DISPLAY COMPONENT        start that activity on the display (its app is stopped first, so it moves there)
 *   touch DISPLAY ACTION X Y        a finger: ACTION is 0 down, 1 up, 2 move, 3 cancel
 *   key DISPLAY KEYCODE             a key pressed and released
 *   text DISPLAY WORDS...           typed text
 *   stop PACKAGE                    end an app
 *   ping                            "ok" and this helper's version
 *   quit                            this helper ends (the app starts the one from its newest file)
 */
object ShellMain {
    const val PORT = 47811
    const val VERSION = 1
    private const val FILES = "/sdcard/Android/data/dev.ultraxr/files"

    @JvmStatic
    fun main(args: Array<String>) {
        val token = try { File(FILES, "shell_token.txt").readText().trim() } catch (e: Exception) { System.err.println("no token: $e"); return }
        if (token.length < 16) { System.err.println("token too short"); return }
        val server = try { ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { System.err.println("already running? $e"); return }
        println("ultraxr shell helper $VERSION listening on 127.0.0.1:$PORT")
        while (true) {
            val s = try { server.accept() } catch (e: Exception) { break }
            Thread { serve(s, token) }.start()
        }
    }

    private fun serve(s: Socket, token: String) {
        try {
            s.tcpNoDelay = true
            val r = BufferedReader(InputStreamReader(s.getInputStream())); val w = s.getOutputStream()
            if (r.readLine()?.trim() != token) { w.write("error token\n".toByteArray()); return }
            // which installed file this helper runs from: after the app is updated it is an old one, and the app replaces it
            w.write(("ok $VERSION " + (System.getenv("CLASSPATH") ?: "") + "\n").toByteArray())
            while (true) {
                val line = r.readLine() ?: break
                val answer = try { command(line) } catch (t: Throwable) { "error " + t.toString().replace('\n', ' ') }
                w.write((answer + "\n").toByteArray()); w.flush()
            }
        } catch (_: Exception) {
        } finally { try { s.close() } catch (_: Exception) {} }
    }

    private val safe = Regex("[A-Za-z0-9_.$/]+")
    private val downTimes = HashMap<Int, Long>()

    private fun command(line: String): String {
        val p = line.trim().split(' ')
        when (p[0]) {
            "ping" -> return "ok $VERSION"
            "quit" -> { Thread { Thread.sleep(200); System.exit(0) }.start(); return "ok" }
            "launch" -> {
                val display = p[1].toInt(); val component = p[2]
                if (!safe.matches(component) || !component.contains('/')) return "error component"
                val pkg = component.substringBefore('/')
                if (pkg != "dev.ultraxr") run("am", "force-stop", pkg)
                // 0x10000000 new task: the activity starts as its own task on that display
                val out = run("am", "start", "--display", display.toString(), "-f", "0x10000000", "-n", component)
                return if (out.contains("Error") || out.contains("Exception")) "error " + out.replace('\n', ' ').take(300) else "ok"
            }
            "stop" -> { if (!safe.matches(p[1]) || p[1] == "dev.ultraxr") return "error package"; run("am", "force-stop", p[1]); return "ok" }
            "touch" -> {
                val display = p[1].toInt(); val action = p[2].toInt(); val x = p[3].toFloat(); val y = p[4].toFloat()
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTimes[display] = now
                val down = downTimes[display] ?: now
                val e = MotionEvent.obtain(down, now, action, x, y, 0)
                e.source = InputDevice.SOURCE_TOUCHSCREEN
                val ok = inject(e, display); e.recycle()
                return if (ok) "ok" else "error inject"
            }
            "key" -> {
                val display = p[1].toInt(); val code = p[2].toInt(); val now = SystemClock.uptimeMillis()
                val a = inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), display)
                val b = inject(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), display)
                return if (a && b) "ok" else "error inject"
            }
            "text" -> {
                val display = p[1].toInt(); val text = line.trim().split(' ', limit = 3).getOrElse(2) { "" }
                run("input", "-d", display.toString(), "text", text.replace(" ", "%s")); return "ok"
            }
        }
        return "error unknown"
    }

    private fun run(vararg cmd: String): String {
        val pr = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = pr.inputStream.bufferedReader().readText(); pr.waitFor(); return out
    }

    // InputManager.injectInputEvent and InputEvent.setDisplayId are not public; this process runs as the shell user,
    // which holds the permission to inject, and is not an app, so the calls are allowed.
    private val setDisplayId by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }
    private val injector: Pair<Any, java.lang.reflect.Method> by lazy {
        val owner = try { Class.forName("android.hardware.input.InputManagerGlobal") } catch (_: Throwable) { Class.forName("android.hardware.input.InputManager") }
        val im = owner.getDeclaredMethod("getInstance").invoke(null)!!
        im to owner.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private fun inject(e: InputEvent, display: Int): Boolean {
        setDisplayId.invoke(e, display)
        return injector.second.invoke(injector.first, e, 0) as Boolean      // 0: asynchronous
    }
}
