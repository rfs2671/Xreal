package dev.ultraxr.proto

/**
 * Reassembles UVC payloads (iso packets or bulk transfers) into video frames.
 * A frame ends when the FID bit toggles or EOF is set. Only frames of exactly
 * [frameBytes] are delivered; everything else is counted. The delivered array is reused for
 * the next frame, so the callback must finish with it (or copy it) before returning.
 */
class UvcFrameAssembler(private val frameBytes: Int, private val onFrame: (ByteArray) -> Unit) {
    private val buf = ByteArray(frameBytes)
    private var fill = 0
    private var overflow = false
    private var lastFid = -1
    var goodFrames = 0L; private set
    var shortFrames = 0L; private set
    var longFrames = 0L; private set
    var errorPayloads = 0L; private set
    var badHeaders = 0L; private set
    var payloads = 0L; private set
    /** Sizes of the last few rejected frames, for the report. */
    val rejectedSizes = ArrayDeque<Int>()

    fun push(data: ByteArray, offset: Int, length: Int) {
        if (length < 2) { if (length > 0) badHeaders++; return }
        val hlen = data[offset].toInt() and 0xFF
        val flags = data[offset + 1].toInt() and 0xFF
        if (hlen < 2 || hlen > length) { badHeaders++; return }
        payloads++
        if (flags and 0x40 != 0) errorPayloads++
        val fid = flags and 1
        if (lastFid >= 0 && fid != lastFid && fill > 0) finish()
        lastFid = fid
        val n = length - hlen
        if (n > 0) {
            if (fill + n > frameBytes) { overflow = true; fill = frameBytes }
            else { System.arraycopy(data, offset + hlen, buf, fill, n); fill += n }
        }
        if (flags and 0x02 != 0) finish()
    }

    private fun finish() {
        when {
            overflow -> { longFrames++; reject(frameBytes + 1) }
            fill == frameBytes -> { goodFrames++; onFrame(buf) }
            fill > 0 -> { shortFrames++; reject(fill) }
        }
        fill = 0; overflow = false
    }

    private fun reject(size: Int) {
        rejectedSizes.addLast(size)
        while (rejectedSizes.size > 8) rejectedSizes.removeFirst()
    }
}
