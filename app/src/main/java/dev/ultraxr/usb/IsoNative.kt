package dev.ultraxr.usb

import java.nio.ByteBuffer

/** JNI binding for cpp/iso.c. */
object IsoNative {
    init { System.loadLibrary("ultraxr") }
    @JvmStatic external fun start(fd: Int, endpoint: Int, packetSize: Int, packetsPerUrb: Int, urbs: Int): Long
    @JvmStatic external fun reap(handle: Long, out: ByteBuffer, meta: IntArray, timeoutMs: Int): Int
    @JvmStatic external fun stop(handle: Long)
}
