package dev.xrealdiag.proto

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
    /** Builds the stream on/off command (cmd 0x19): 0xAA, CRC-32 LE over the rest, len, cmd, flag. */
    fun streamCommand(on: Boolean): ByteArray {
        val body = byteArrayOf(0x04, 0x00, 0x19, if (on) 1 else 0)
        val crc = java.util.zip.CRC32().apply { update(body) }.value.toInt()
        return byteArrayOf(0xaa.toByte()) + ByteArray(4) { (crc shr (8 * it)).toByte() } + body
    }

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
