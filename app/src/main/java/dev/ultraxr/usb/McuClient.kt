package dev.ultraxr.usb

import dev.ultraxr.proto.McuProtocol

/** Request/response exchange with the glasses' MCU (HID interface 0 on the Air 2 Ultra). */
class McuClient(private val link: HidLink) {
    var lastWritePath = ""; private set

    /** Returns the response payload, or null on timeout / write failure. */
    @Synchronized
    fun command(cmdId: Int, payload: ByteArray = ByteArray(0)): ByteArray? {
        val (n, path) = link.write(McuProtocol.serialize(cmdId, payload))
        lastWritePath = path
        if (n < 0) return null
        val buf = ByteArray(1024)
        val deadline = System.nanoTime() + 1_500_000_000L
        var tries = 0
        while (System.nanoTime() < deadline && tries++ < 64) {
            val r = link.read(buf, 250)
            if (r <= 0) continue
            val pkt = McuProtocol.parse(buf, r) ?: continue
            if (pkt.cmdId == cmdId) return pkt.payload
        }
        return null
    }

    fun getDisplayMode(): Int? = command(McuProtocol.CMD_GET_DISPLAY_MODE)?.takeIf { it.size >= 2 }?.get(1)?.toInt()?.and(0xFF)

    fun setDisplayMode(mode: Int): Boolean {
        val r = command(McuProtocol.CMD_SET_DISPLAY_MODE, byteArrayOf(mode.toByte()))
        return r != null && r.isNotEmpty() && r[0].toInt() == 0
    }
}
