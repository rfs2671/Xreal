package dev.ultraxr.shell

import android.content.Context
import android.util.Base64
import dev.ultraxr.tracking.Tracking
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * Starts the shell helper ([ShellMain]) from the phone itself, without a computer: it speaks the plain adb protocol
 * to this phone's own debugging service on 127.0.0.1:5555 and runs one command there.
 *
 * That port is open only while the phone's owner has switched debugging over the network on ("adb tcpip 5555" from
 * a computer; it lasts until the phone restarts). This class changes no setting. The first time, Android asks its
 * owner on the screen whether this app's key may debug the phone; nothing runs until that is allowed there.
 */
object AdbStarter {
    private const val A_CNXN = 0x4e584e43; private const val A_AUTH = 0x48545541; private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f; private const val A_CLSE = 0x45534c43; private const val A_WRTE = 0x45545257
    private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    @Volatile var lastResult = ""; private set

    private fun key(context: Context): RSAPrivateCrtKey {
        val f = File(context.filesDir, "adb_key.pk8")
        if (!f.isFile) { val g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); f.writeBytes(g.generateKeyPair().private.encoded) }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(f.readBytes())) as RSAPrivateCrtKey
    }

    /** The public key in the form adbd keeps: 2048-bit modulus as 64 little-endian words, with the two values its arithmetic wants. */
    private fun adbPublicKey(priv: RSAPrivateCrtKey): ByteArray {
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)) as RSAPublicKey
        val n = pub.modulus; val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n)
        val b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(64); b.putInt(n0inv.toInt())
        fun words(v: BigInteger) { var x = v; for (i in 0 until 64) { b.putInt(x.mod(r32).toInt()); x = x.shiftRight(32) } }
        words(n); words(rr); b.putInt(pub.publicExponent.toInt())
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " ultraxr@phone").toByteArray() + 0
    }

    private fun send(o: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        var sum = 0; for (x in data) sum += x.toInt() and 0xff
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd); h.putInt(a0); h.putInt(a1); h.putInt(data.size); h.putInt(sum); h.putInt(cmd.inv())
        o.write(h.array()); o.write(data); o.flush()
    }
    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)
    private fun read(i: DataInputStream): Msg {
        val hb = ByteArray(24); i.readFully(hb); val h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int; val a0 = h.int; val a1 = h.int; val len = h.int
        if (len < 0 || len > 1 shl 20) throw java.io.IOException("bad length")
        val d = ByteArray(len); i.readFully(d); return Msg(cmd, a0, a1, d)
    }

    /** Runs [command] as the shell user. Returns its output, or null when debugging over the network is not there or not allowed. */
    fun shell(context: Context, command: String, waitForOwnerMs: Int = 60_000): String? {
        var s: Socket? = null
        try {
            s = Socket(); s.connect(InetSocketAddress("127.0.0.1", 5555), 600); s.soTimeout = 5000
            val i = DataInputStream(s.getInputStream()); val o = s.getOutputStream()
            val priv = key(context)
            send(o, A_CNXN, 0x01000000, 4096, "host::\u0000".toByteArray())
            var signed = false
            while (true) {
                val m = read(i)
                if (m.cmd == A_CNXN) break
                if (m.cmd != A_AUTH || m.a0 != 1) throw java.io.IOException("unexpected answer")
                if (!signed) {
                    val c = Cipher.getInstance("RSA/ECB/PKCS1Padding"); c.init(Cipher.ENCRYPT_MODE, priv)
                    send(o, A_AUTH, 2, 0, c.doFinal(SHA1_PREFIX + m.data)); signed = true
                } else {
                    // not known to the phone yet: offer the key; Android now asks its owner on the screen
                    Tracking.log("apps: asking the phone's owner to allow this app to start its helper (the \"Allow USB debugging?\" question)")
                    send(o, A_AUTH, 3, 0, adbPublicKey(priv)); s.soTimeout = waitForOwnerMs
                }
            }
            s.soTimeout = 8000
            send(o, A_OPEN, 1, 0, ("shell:$command\u0000").toByteArray())
            val out = StringBuilder(); var remote = 0
            while (true) {
                val m = read(i)
                when (m.cmd) {
                    A_OKAY -> remote = m.a0
                    A_WRTE -> { out.append(String(m.data)); send(o, A_OKAY, 1, m.a0) }
                    A_CLSE -> { send(o, A_CLSE, 1, m.a0); lastResult = "ran"; return out.toString() }
                }
                if (out.length > 65536) break
            }
            return out.toString()
        } catch (e: Exception) {
            lastResult = e.toString(); return null
        } finally { try { s?.close() } catch (_: Exception) {} }
    }

    /** Starts the helper from this app's own installed file. True if the command was run. */
    fun startHelper(context: Context): Boolean {
        val apk = context.applicationInfo.sourceDir
        val out = shell(context, "(CLASSPATH=$apk nohup app_process / dev.ultraxr.shell.ShellMain >/data/local/tmp/uxr-shell.log 2>&1 &); sleep 1; echo started") ?: return false
        Tracking.log("apps: helper started from the phone itself (" + out.trim().takeLast(40) + ")")
        return out.contains("started")
    }
}
