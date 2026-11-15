package dev.ultraxr.proto

/**
 * Air 2 Ultra IMU HID reports (interface 2). Offsets from XR Gate's xreal_imu_codec (MIT):
 * motion report = 0x01 0x02, LE64 device timestamp (ns) at 4, s24 gyro at 18/21/24,
 * s24 accel at 33/36/39. Gyro full scale 2000 dps, accel full scale 16 g, over 2^23.
 */
object ImuCodec {
    /** "Start streaming" command, without the leading hidapi report-id byte. */
    val START_COMMAND = byteArrayOf(
        0xaa.toByte(), 0xc5.toByte(), 0xd1.toByte(), 0x21, 0x42, 0x04, 0x00, 0x19, 0x01
    )
    const val CMD_CONFIG_LENGTH = 0x14
    const val CMD_CONFIG_PART = 0x15
    const val CMD_STREAM = 0x19

    /**
     * Builds a command for the IMU interface (layout from ar-drivers-rs, MIT):
     * 0xAA, CRC-32 LE over everything after it, u16 length = data + 3, cmd id, data.
     */
    fun command(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val len = data.size + 3
        val body = byteArrayOf(len.toByte(), (len shr 8).toByte(), cmd.toByte()) + data
        val crc = java.util.zip.CRC32().apply { update(body) }.value.toInt()
        return byteArrayOf(0xaa.toByte()) + ByteArray(4) { (crc shr (8 * it)).toByte() } + body
    }

    /** Parses a command response; returns (cmd id, data) or null if this is not one. */
    fun parseResponse(p: ByteArray, size: Int): Pair<Int, ByteArray>? {
        if (size < 8 || p[0] != 0xaa.toByte()) return null
        val len = (p[5].toInt() and 0xFF) or ((p[6].toInt() and 0xFF) shl 8)
        val dataLen = len - 3
        if (dataLen < 0 || 8 + dataLen > size) return null
        return (p[7].toInt() and 0xFF) to p.copyOfRange(8, 8 + dataLen)
    }

    fun streamCommand(on: Boolean): ByteArray = command(CMD_STREAM, byteArrayOf(if (on) 1 else 0))

    const val MIN_REPORT = 42
    private const val GYRO_SCALE = 2000.0 / 8388608.0 * Math.PI / 180.0 // rad/s per LSB
    private const val ACCEL_SCALE = 16.0 / 8388608.0 * 9.80665          // m/s^2 per LSB

    class Sample(val timestampNs: Long, val gyro: FloatArray, val accel: FloatArray)

    fun isMotionReport(p: ByteArray, size: Int) =
        size >= MIN_REPORT && p[0] == 0x01.toByte() && p[1] == 0x02.toByte()

    fun decode(p: ByteArray, size: Int): Sample? {
        if (!isMotionReport(p, size)) return null
        var ts = 0L
        for (i in 0..7) ts = ts or ((p[4 + i].toLong() and 0xFF) shl (8 * i))
        if (ts <= 0) return null
        val g = FloatArray(3) { (s24(p, 18 + 3 * it) * GYRO_SCALE).toFloat() }
        val a = FloatArray(3) { (s24(p, 33 + 3 * it) * ACCEL_SCALE).toFloat() }
        return Sample(ts, g, a)
    }

    private fun s24(p: ByteArray, o: Int): Int {
        val v = (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8) or
            ((p[o + 2].toInt() and 0xFF) shl 16)
        return if (v and 0x800000 != 0) v - 0x1000000 else v
    }
}
