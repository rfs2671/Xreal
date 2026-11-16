package dev.ultraxr.proto

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
    // Message ids from nrealAirLinuxDriver's device_mcu.h (MIT). They are documented for the Air
    // family; open drivers treat the Air 2 Ultra as using the same protocol, which is unverified.
    const val CMD_GET_BRIGHTNESS = 0x03
    const val CMD_SET_BRIGHTNESS = 0x04
    const val BRIGHTNESS_MAX = 7
    const val EVT_DISPLAY_TOGGLED = 0x6C04
    const val EVT_BUTTON = 0x6C05
    const val EVT_TEXT_LOG = 0x6C09
    const val EVT_PANEL_POWER = 0x6C18

    /**
     * Read-only queries for the diagnostic fingerprint. Deliberately excludes every write and
     * every firmware-update, bootloader, activation and sleep command.
     */
    val STATUS_QUERIES: List<Pair<String, Int>> = listOf(
        "display mode" to CMD_GET_DISPLAY_MODE, "brightness" to CMD_GET_BRIGHTNESS, "glasses id" to CMD_GET_SERIAL,
        "DisplayPort bridge firmware" to 0x16, "DSP version" to 0x18, "DSP app firmware" to 0x21, "MCU app firmware" to 0x26, "activation time" to 0x29,
    )

    /** Report size the Ultra's update path uses (PID 0x0426). Only used here for read-only version reads. */
    const val WIDE_REPORT = 512
    /** The ONLY commands the wide-frame transport test may send: read-only DP-bridge and SOC version reads. */
    val WIDE_READ_ONLY: Set<Int> = setOf(0x16, 0x26)
    /** Message ids that write or change firmware. Never sent by this app; asserted in tests. */
    val UPDATE_IDS: Set<Int> = (62..68).toSet() + (110..113).toSet()

    /** Same framing as [serialize] but in a 512-byte report. Refuses anything except [WIDE_READ_ONLY]. */
    fun serializeWideReadOnly(cmdId: Int, requestId: Int = 0x1337): ByteArray {
        require(cmdId !in UPDATE_IDS) { "firmware-update commands are never sent" }
        require(cmdId in WIDE_READ_ONLY) { "only read-only version queries may use the wide test" }
        val small = serialize(cmdId, ByteArray(0), requestId)
        return small.copyOf(WIDE_REPORT)
    }

    fun eventName(cmd: Int): String = when (cmd) {
        EVT_DISPLAY_TOGGLED -> "display toggled (first byte 0 = screen off, 1 = on)"
        EVT_BUTTON -> "button pressed"
        EVT_TEXT_LOG -> "firmware text log"
        EVT_PANEL_POWER -> "panel power (0 = off, 1 = on)"
        0x6C02 -> "heartbeat start"; 0x6C12 -> "heartbeat end"; 0x1A -> "heartbeat"
        else -> "unnamed"
    }

    const val MODE_2D_60 = 1
    const val MODE_SBS_60 = 3
    const val MODE_SBS_72 = 4
    const val MODE_2D_72 = 5
    const val MODE_HALF_SBS = 8
    const val MODE_SBS_90 = 9
    const val MODE_2D_90 = 10
    const val MODE_2D_120 = 11

    class Packet(val cmdId: Int, val payload: ByteArray)

    fun modeName(mode: Int): String = when (mode) {
        MODE_2D_60 -> "2D 1920x1080@60"
        MODE_SBS_60 -> "SBS 3840x1080@60"
        MODE_SBS_72 -> "SBS 3840x1080@72"
        MODE_2D_72 -> "2D 1920x1080@72"
        MODE_HALF_SBS -> "half-SBS 1920x1080@60"
        MODE_SBS_90 -> "SBS 3840x1080@90"
        MODE_2D_90 -> "2D 1920x1080@90"
        MODE_2D_120 -> "2D 1920x1080@120"
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
