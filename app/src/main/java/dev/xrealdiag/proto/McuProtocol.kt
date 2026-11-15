package dev.xrealdiag.proto

import java.util.zip.CRC32

/**
 * XREAL Air-family MCU HID packet framing (64-byte reports, head 0xFD).
 * Layout taken from XR Gate's xreal_display_helper (MIT) and ar-drivers-rs (MIT):
 *   [0]     0xFD
 *   [1..4]  CRC-32 (LE) over bytes [5 .. 5+length)
 *   [5..6]  length (LE) = payload + 17
 *   [7..10] request id   [11..14] timestamp   [15..16] cmd id   [17..21] reserved
 *   [22..]  payload (max 42 bytes)
 */
object McuProtocol {
    const val PACKET_SIZE = 64
    const val CMD_GET_DISPLAY_MODE = 0x07
    const val CMD_SET_DISPLAY_MODE = 0x08
    const val CMD_GET_SERIAL = 0x15

    const val MODE_2D_60 = 1
    const val MODE_SBS_60 = 3
    const val MODE_HALF_SBS = 8
    const val MODE_SBS_90 = 9
    const val MODE_2D_90 = 10
    const val MODE_2D_120 = 11

    class Packet(val cmdId: Int, val payload: ByteArray)

    fun modeName(mode: Int): String = when (mode) {
        MODE_2D_60 -> "2D 1920x1080@60"
        MODE_SBS_60 -> "SBS 3840x1080@60"
        MODE_HALF_SBS -> "half-SBS"
        MODE_SBS_90 -> "SBS 3840x1080@90"
        MODE_2D_90 -> "2D@90"
        MODE_2D_120 -> "2D@120"
        else -> "unknown($mode)"
    }

    fun serialize(cmdId: Int, payload: ByteArray = ByteArray(0), requestId: Int = 0x1337): ByteArray {
        require(payload.size <= 42) { "MCU payload too large" }
        val out = ByteArray(PACKET_SIZE)
        val length = payload.size + 17
        out[0] = 0xFD.toByte()
        putLe16(out, 5, length)
        putLe32(out, 7, requestId)
        putLe32(out, 11, 0)
        putLe16(out, 15, cmdId)
        payload.copyInto(out, 22)
        val crc = CRC32().apply { update(out, 5, length) }.value.toInt()
        putLe32(out, 1, crc)
        return out
    }

    /** Parses a report; returns null if it is not a well-formed MCU packet. */
    fun parse(data: ByteArray, size: Int): Packet? {
        if (size < PACKET_SIZE || data[0] != 0xFD.toByte()) return null
        val length = le16(data, 5)
        if (length < 17 || length > 59) return null
        return Packet(le16(data, 15), data.copyOfRange(22, 22 + (length - 17)))
    }

    private fun putLe16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun putLe32(b: ByteArray, o: Int, v: Int) { for (i in 0..3) b[o + i] = (v shr (8 * i)).toByte() }
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
