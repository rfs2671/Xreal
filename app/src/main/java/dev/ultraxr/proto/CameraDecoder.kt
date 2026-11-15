package dev.ultraxr.proto

/**
 * Air 2 Ultra SLAM camera frame decoder. Each UVC frame (advertised as 640x241 at
 * 2 bytes/pixel = 308480 bytes) carries ONE eye: 640x480 8-bit grey, shuffled in 128
 * chunks of 2400 bytes, followed by a 1280-byte footer with metadata.
 * Chunk map and footer offsets are from XR Gate's xreal_camera_decoder /
 * xreal_camera_metadata (MIT, "Measured Air 2 Ultra footer layout").
 */
object CameraDecoder {
    const val EYE_W = 640
    const val EYE_H = 480
    const val IMAGE_BYTES = EYE_W * EYE_H           // 307200
    const val FRAME_BYTES = EYE_W * (EYE_H + 2)     // 308480
    private const val CHUNK = 2400
    private const val CHUNKS = 128
    private const val SUM_LEN = 128

    private val CHUNK_MAP = intArrayOf(
        119, 54, 21, 0, 108, 22, 51, 63, 93, 99, 67, 7, 32, 112, 52,
        43, 14, 35, 75, 116, 64, 71, 44, 89, 18, 88, 26, 61, 70, 56,
        90, 79, 87, 120, 81, 101, 121, 17, 72, 31, 53, 124, 127, 113, 111,
        36, 48, 19, 37, 83, 126, 74, 109, 5, 84, 41, 76, 30, 110, 29,
        12, 115, 28, 102, 105, 62, 103, 20, 3, 68, 49, 77, 117, 125, 106,
        60, 69, 98, 9, 16, 78, 47, 40, 2, 118, 34, 13, 50, 46, 80,
        85, 66, 42, 123, 122, 96, 11, 25, 97, 39, 6, 86, 1, 8, 82,
        92, 59, 104, 24, 15, 73, 65, 38, 58, 10, 23, 33, 55, 57, 107,
        100, 94, 27, 95, 45, 91, 4, 114
    )
    private val REVERSE = IntArray(CHUNKS).also { r -> CHUNK_MAP.forEachIndexed { i, v -> r[v] = i } }

    class Meta(val t0Ns: Long, val t62Ns: Long, val pairCounter: Int, val isRight: Boolean)

    fun parseMeta(frame: ByteArray): Meta? {
        if (frame.size < FRAME_BYTES) return null
        val f = IMAGE_BYTES
        val eye = frame[f + 59].toInt() and 0xFF
        if (eye > 1) return null
        val t0 = le(frame, f, 8)
        val t62 = le(frame, f + 62, 8)
        if (t0 <= 0 || t62 <= 0) return null
        return Meta(t0, t62, le(frame, f + 18, 2).toInt(), eye == 1)
    }

    /** Unshuffles [frame] into [out] (640x480 grey). Returns false on a wrong-sized frame. */
    fun decode(frame: ByteArray, out: ByteArray): Boolean {
        if (frame.size != FRAME_BYTES || out.size != IMAGE_BYTES) return false
        // The chunk with the darkest first 128 bytes marks the rotation of the shuffle.
        var offset = 0
        var best = Int.MAX_VALUE
        for (i in 0 until CHUNKS) {
            var sum = 0
            val base = CHUNK * i
            for (j in 0 until SUM_LEN) sum += frame[base + j].toInt() and 0xFF
            if (sum < best) { best = sum; offset = i }
        }
        offset = REVERSE[offset]
        for (i in 0 until CHUNKS) {
            System.arraycopy(frame, CHUNK * CHUNK_MAP[(offset + i) % CHUNKS], out, CHUNK * i, CHUNK)
        }
        return true
    }

    /**
     * Objective check that the unshuffle produced a real image: ratio of the mean absolute
     * step across chunk seams to the mean step between ordinary neighbouring pixels.
     * A correct decode of a natural scene gives roughly 1; a wrong one gives clearly more.
     * Returns NaN for a flat image (covered lens / darkness), where it says nothing.
     */
    fun seamScore(img: ByteArray): Double {
        var seam = 0L; var seamN = 0
        for (i in 1 until CHUNKS) {
            val p = CHUNK * i
            if (p % EYE_W == 0) continue // seam falls on a row boundary, not comparable
            seam += Math.abs((img[p].toInt() and 0xFF) - (img[p - 1].toInt() and 0xFF)); seamN++
        }
        var gen = 0L; var genN = 0
        var p = 1
        while (p < IMAGE_BYTES) {
            if (p % EYE_W != 0 && p % CHUNK != 0) {
                gen += Math.abs((img[p].toInt() and 0xFF) - (img[p - 1].toInt() and 0xFF)); genN++
            }
            p += 7
        }
        if (seamN == 0 || genN == 0 || gen == 0L) return Double.NaN
        return (seam.toDouble() / seamN) / (gen.toDouble() / genN)
    }

    /** Mean and standard deviation of brightness, sampled. */
    fun brightness(img: ByteArray): Pair<Double, Double> {
        var s = 0.0; var s2 = 0.0; var n = 0
        var p = 0
        while (p < img.size) { val v = (img[p].toInt() and 0xFF).toDouble(); s += v; s2 += v * v; n++; p += 13 }
        val mean = s / n
        return mean to Math.sqrt(Math.max(0.0, s2 / n - mean * mean))
    }

    private fun le(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
