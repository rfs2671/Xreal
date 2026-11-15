package dev.xrealdiag.proto

/**
 * Minimal USB / UVC descriptor walker over the raw configuration descriptor
 * (UsbDeviceConnection.getRawDescriptors()). Only what the diagnostic needs:
 * the VideoStreaming interface, its formats/frames, and its streaming endpoints.
 */
object UvcDescriptors {
    class Frame(val index: Int, val width: Int, val height: Int, val intervals100ns: List<Int>)
    class Format(val index: Int, val subtype: Int, val guid: String, val bitsPerPixel: Int, val frames: MutableList<Frame>)
    class StreamEndpoint(val altSetting: Int, val address: Int, val attributes: Int, val wMaxPacketSize: Int) {
        val isIso get() = attributes and 3 == 1
        val isBulk get() = attributes and 3 == 2
        /** Bytes per (micro)frame: high-speed iso can carry 1-3 transactions. */
        val payloadPerInterval get() = (wMaxPacketSize and 0x7FF) * (((wMaxPacketSize shr 11) and 3) + 1)
    }
    class Info(
        val bcdUvc: Int,
        val controlInterface: Int,
        val streamInterface: Int,
        val formats: List<Format>,
        val endpoints: List<StreamEndpoint>,
    )

    private const val DT_INTERFACE = 4
    private const val DT_ENDPOINT = 5
    private const val DT_CS_INTERFACE = 0x24
    private const val CC_VIDEO = 0x0E
    private const val SC_VIDEOCONTROL = 1
    private const val SC_VIDEOSTREAMING = 2

    /** Returns the first video function found, or null if the device has no UVC interface. */
    fun parse(raw: ByteArray): Info? {
        var p = 0
        var curClass = -1; var curSub = -1; var curNum = -1; var curAlt = 0
        var bcd = 0; var vc = -1; var vs = -1
        val formats = ArrayList<Format>()
        val eps = ArrayList<StreamEndpoint>()
        while (p + 2 <= raw.size) {
            val len = u8(raw, p)
            if (len < 2 || p + len > raw.size) break
            when (u8(raw, p + 1)) {
                DT_INTERFACE -> if (len >= 9) {
                    curNum = u8(raw, p + 2); curAlt = u8(raw, p + 3)
                    curClass = u8(raw, p + 5); curSub = u8(raw, p + 6)
                    if (curClass == CC_VIDEO && curSub == SC_VIDEOCONTROL && vc < 0) vc = curNum
                    if (curClass == CC_VIDEO && curSub == SC_VIDEOSTREAMING && vs < 0) vs = curNum
                }
                DT_CS_INTERFACE -> if (curClass == CC_VIDEO && len >= 3) {
                    val st = u8(raw, p + 2)
                    if (curSub == SC_VIDEOCONTROL && st == 1 && len >= 5) bcd = u16(raw, p + 3)
                    if (curSub == SC_VIDEOSTREAMING && curNum == vs) when (st) {
                        // VS_FORMAT_UNCOMPRESSED(4) / VS_FORMAT_FRAME_BASED(0x10): GUID at 5, bpp at 21
                        4, 0x10 -> if (len >= 22) formats += Format(u8(raw, p + 3), st, guid(raw, p + 5), u8(raw, p + 21), ArrayList())
                        6 -> if (len >= 11) formats += Format(u8(raw, p + 3), st, "MJPEG", 0, ArrayList())
                        // VS_FRAME_UNCOMPRESSED(5) / VS_FRAME_MJPEG(7): default interval 21, count 25, list 26
                        5, 7 -> if (len >= 26 && formats.isNotEmpty()) formats.last().frames += frame(raw, p, len, 21, 25)
                        // VS_FRAME_FRAME_BASED(0x11): default interval 17, count 21, list 26
                        0x11 -> if (len >= 26 && formats.isNotEmpty()) formats.last().frames += frame(raw, p, len, 17, 21)
                    }
                }
                DT_ENDPOINT -> if (len >= 7 && curClass == CC_VIDEO && curSub == SC_VIDEOSTREAMING && curNum == vs) {
                    eps += StreamEndpoint(curAlt, u8(raw, p + 2), u8(raw, p + 3), u16(raw, p + 4))
                }
            }
            p += len
        }
        if (vs < 0) return null
        return Info(bcd, vc, vs, formats, eps)
    }

    private fun frame(raw: ByteArray, p: Int, len: Int, defaultIntervalOff: Int, countOff: Int): Frame {
        val listOff = 26
        val n = u8(raw, p + countOff)
        val list = ArrayList<Int>()
        if (n == 0) {
            // continuous: min, max, step
            if (listOff + 4 <= len) list += u32(raw, p + listOff)
        } else {
            for (i in 0 until n) if (listOff + 4 * i + 4 <= len) list += u32(raw, p + listOff + 4 * i)
        }
        if (list.isEmpty()) list += u32(raw, p + defaultIntervalOff)
        return Frame(u8(raw, p + 3), u16(raw, p + 5), u16(raw, p + 7), list)
    }

    private fun guid(b: ByteArray, o: Int): String {
        val fourcc = String(b, o, 4, Charsets.ISO_8859_1)
        val printable = fourcc.all { it.code in 0x20..0x7E }
        val hex = (0 until 16).joinToString("") { "%02x".format(b[o + it]) }
        return if (printable) "$fourcc ($hex)" else hex
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    /** Builds a VS probe/commit control block of [size] bytes (26 = UVC 1.0, 34 = 1.1, 48 = 1.5). */
    fun probeBlock(size: Int, formatIndex: Int, frameIndex: Int, interval100ns: Int): ByteArray {
        val b = ByteArray(size)
        b[0] = 1 // bmHint: keep dwFrameInterval fixed
        b[2] = formatIndex.toByte()
        b[3] = frameIndex.toByte()
        for (i in 0..3) b[4 + i] = (interval100ns shr (8 * i)).toByte()
        return b
    }

    fun probeMaxVideoFrameSize(b: ByteArray) = if (b.size >= 22) u32(b, 18) else 0
    fun probeMaxPayloadTransferSize(b: ByteArray) = if (b.size >= 26) u32(b, 22) else 0
}
