package dev.ultraxr.usb

import dev.ultraxr.proto.McuProtocol

/** Request/response exchange with the glasses' MCU (HID interface 0 on the Air 2 Ultra). */
class McuClient(private val link: HidLink) {
    var lastWritePath = ""; private set
    /** Called for packets that are not the answer being waited for (button, brightness, wear events...). */
    var onOther: ((Int, ByteArray) -> Unit)? = null

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
            onOther?.invoke(pkt.cmdId, pkt.payload)
        }
        return null
    }

    /** Listens for unsolicited messages for up to [timeoutMs] without sending anything. */
    @Synchronized
    fun drain(timeoutMs: Int) {
        val buf = ByteArray(1024)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val r = link.read(buf, 40)
            if (r <= 0) continue
            McuProtocol.parse(buf, r)?.let { onOther?.invoke(it.cmdId, it.payload) }
        }
    }

    fun getDisplayMode(): Int? = command(McuProtocol.CMD_GET_DISPLAY_MODE)?.takeIf { it.size >= 2 }?.get(1)?.toInt()?.and(0xFF)

    fun setDisplayMode(mode: Int): Boolean {
        val r = command(McuProtocol.CMD_SET_DISPLAY_MODE, byteArrayOf(mode.toByte()))
        return r != null && r.isNotEmpty() && r[0].toInt() == 0
    }
}
