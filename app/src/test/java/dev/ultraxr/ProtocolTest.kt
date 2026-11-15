package dev.ultraxr

import dev.ultraxr.proto.CameraDecoder
import dev.ultraxr.proto.ImuCodec
import dev.ultraxr.proto.McuProtocol
import dev.ultraxr.proto.UvcDescriptors
import dev.ultraxr.proto.UvcFrameAssembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ProtocolTest {
    @Test fun imuStartCommandMatchesReferenceBytes() {
        // Literal from XR Gate; the builder must reproduce it, which pins the CRC and layout.
        assertArrayEquals(ImuCodec.START_COMMAND, ImuCodec.streamCommand(true))
        assertFalse(ImuCodec.START_COMMAND.contentEquals(ImuCodec.streamCommand(false)))
    }

    @Test fun imuDecodesScaledValues() {
        val p = ByteArray(64)
        p[0] = 1; p[1] = 2
        p[4] = 0x40; p[5] = 0x42; p[6] = 0x0f // ts = 1_000_000 ns
        // gyro x = +2^22 -> 1000 dps ; gyro y = -2^22
        p[18] = 0; p[19] = 0; p[20] = 0x40
        p[21] = 0; p[22] = 0; p[23] = 0xC0.toByte()
        // accel z = 2^19 -> 1 g
        p[39] = 0; p[40] = 0; p[41] = 0x08
        val s = ImuCodec.decode(p, 64)!!
        assertEquals(1_000_000L, s.timestampNs)
        assertEquals(Math.toRadians(1000.0).toFloat(), s.gyro[0], 1e-3f)
        assertEquals(-Math.toRadians(1000.0).toFloat(), s.gyro[1], 1e-3f)
        assertEquals(9.80665f, s.accel[2], 1e-4f)
        p[1] = 3
        assertNull(ImuCodec.decode(p, 64))
    }

    @Test fun mcuPacketRoundTrip() {
        val pkt = McuProtocol.serialize(McuProtocol.CMD_SET_DISPLAY_MODE, byteArrayOf(3))
        assertEquals(64, pkt.size)
        assertEquals(0xFD.toByte(), pkt[0])
        assertEquals(18, pkt[5].toInt()) // 17 + 1 payload byte
        assertEquals(0x37, pkt[7].toInt()); assertEquals(0x13, pkt[8].toInt())
        val back = McuProtocol.parse(pkt, 64)!!
        assertEquals(McuProtocol.CMD_SET_DISPLAY_MODE, back.cmdId)
        assertArrayEquals(byteArrayOf(3), back.payload)
        // CRC covers [5, 5+len)
        val crc = java.util.zip.CRC32().apply { update(pkt, 5, 18) }.value.toInt()
        for (i in 0..3) assertEquals((crc shr (8 * i)).toByte(), pkt[1 + i])
        assertNull(McuProtocol.parse(ByteArray(64), 64))
    }

    /** Shuffles an image the way the glasses do, so the decoder can be checked end to end. */
    private fun scramble(img: ByteArray, rotation: Int, map: IntArray): ByteArray {
        val frame = ByteArray(CameraDecoder.FRAME_BYTES)
        for (i in 0 until 128) System.arraycopy(img, 2400 * i, frame, 2400 * map[(rotation + i) % 128], 2400)
        return frame
    }

    private fun chunkMap(): IntArray {
        val f = CameraDecoder::class.java.getDeclaredField("CHUNK_MAP").apply { isAccessible = true }
        return f.get(CameraDecoder) as IntArray
    }

    @Test fun cameraDecodeInvertsShuffle() {
        val map = chunkMap()
        assertEquals(128, map.toSet().size)
        // Smooth gradient image whose first chunk is the darkest, like a real frame's marker.
        val img = ByteArray(CameraDecoder.IMAGE_BYTES) { i ->
            val x = i % 640; val y = i / 640
            if (i < 2400) 0 else (60 + (x + y) % 160).toByte()
        }
        for (rot in intArrayOf(0, 1, 57, 127)) {
            val frame = scramble(img, rot, map)
            val out = ByteArray(CameraDecoder.IMAGE_BYTES)
            assertTrue(CameraDecoder.decode(frame, out))
            assertArrayEquals("rotation $rot", img, out)
            assertTrue(CameraDecoder.seamScore(out) < 1.6)
        }
        // A frame left scrambled must score clearly worse than a decoded one.
        val noisy = ByteArray(CameraDecoder.IMAGE_BYTES) { i -> ((i / 2400) * 37 % 200 + (i % 640) / 8).toByte() }
        assertTrue(CameraDecoder.seamScore(noisy) > 3.0)
        assertFalse(CameraDecoder.decode(ByteArray(10), ByteArray(CameraDecoder.IMAGE_BYTES)))
    }

    @Test fun cameraMetadataOffsets() {
        val frame = ByteArray(CameraDecoder.FRAME_BYTES)
        val f = CameraDecoder.IMAGE_BYTES
        assertNull(CameraDecoder.parseMeta(frame)) // zero timestamps are rejected
        frame[f] = 5; frame[f + 62] = 9; frame[f + 63] = 1; frame[f + 18] = 0x34; frame[f + 19] = 0x12; frame[f + 59] = 1
        val m = CameraDecoder.parseMeta(frame)!!
        assertEquals(5L, m.t0Ns); assertEquals(265L, m.t62Ns); assertEquals(0x1234, m.pairCounter); assertTrue(m.isRight)
        frame[f + 59] = 2
        assertNull(CameraDecoder.parseMeta(frame))
    }

    @Test fun assemblerRebuildsFramesFromPayloads() {
        val frames = ArrayList<ByteArray>()
        val asm = UvcFrameAssembler(1000) { frames += it }
        val rnd = Random(1)
        val a = ByteArray(1000).also { rnd.nextBytes(it) }
        val b = ByteArray(1000).also { rnd.nextBytes(it) }
        fun send(data: ByteArray, fid: Int, eof: Boolean, chunk: Int) {
            var off = 0
            while (off < data.size) {
                val n = minOf(chunk, data.size - off)
                val last = off + n == data.size
                val pkt = ByteArray(12 + n)
                pkt[0] = 12; pkt[1] = (0x80 or fid or (if (last && eof) 2 else 0)).toByte()
                System.arraycopy(data, off, pkt, 12, n)
                asm.push(pkt, 0, pkt.size); off += n
            }
        }
        send(a, 0, true, 300)
        send(b, 1, false, 256)          // no EOF: closed by the FID toggle of the next frame
        asm.push(byteArrayOf(2, 0x80.toByte()), 0, 2) // header-only payload, FID back to 0
        send(a.copyOf(400), 0, true, 400) // truncated frame
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0]); assertArrayEquals(b, frames[1])
        assertEquals(2L, asm.goodFrames); assertEquals(1L, asm.shortFrames)
        assertEquals(listOf(400), asm.rejectedSizes.toList())
        asm.push(byteArrayOf(40, 0), 0, 2) // header longer than the payload
        assertEquals(1L, asm.badHeaders)
    }

    @Test fun uvcDescriptorsParsed() {
        fun d(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        val raw = d(18, 1, 0, 2, 0, 0, 0, 64, 0x18, 0x33, 0x26, 0x04, 0, 1, 1, 2, 3, 1) + // device
            d(9, 2, 0, 0, 2, 1, 0, 0x80, 50) +
            d(9, 4, 0, 0, 0, 3, 0, 0, 0) +                       // HID interface, ignored
            d(9, 4, 3, 0, 0, 0x0E, 1, 0, 0) +                    // VideoControl
            d(13, 0x24, 1, 0x10, 0x01, 0, 0, 0, 0, 0, 0, 1, 4) + // VC header, bcdUVC 1.10
            d(9, 4, 4, 0, 0, 0x0E, 2, 0, 0) +                    // VideoStreaming alt 0
            (d(27, 0x24, 4, 1, 1) + "YUY2".toByteArray() + ByteArray(12) + d(16, 1, 0, 0, 0, 0)) +
            (d(30, 0x24, 5, 1, 0) + d(0x80, 0x02, 0xF1, 0x00) + ByteArray(8) + d(0, 0xB5, 0x04, 0) +
                d(0x0A, 0x8B, 0x02, 0x00, 1, 0x0A, 0x8B, 0x02, 0x00)) +
            d(9, 4, 4, 1, 1, 0x0E, 2, 0, 0) +                    // alt 1
            d(7, 5, 0x81, 0x05, 0x00, 0x14, 1)                   // iso IN, 3 x 1024
        val info = UvcDescriptors.parse(raw)
        assertNotNull(info); info!!
        assertEquals(0x0110, info.bcdUvc); assertEquals(3, info.controlInterface); assertEquals(4, info.streamInterface)
        assertEquals(1, info.formats.size)
        val fr = info.formats[0].frames.single()
        assertEquals(640, fr.width); assertEquals(241, fr.height); assertEquals(listOf(166666), fr.intervals100ns)
        assertEquals(16, info.formats[0].bitsPerPixel)
        val ep = info.endpoints.single()
        assertTrue(ep.isIso); assertEquals(1, ep.altSetting); assertEquals(3072, ep.payloadPerInterval)
        val probe = UvcDescriptors.probeBlock(34, 1, 1, 166666)
        assertEquals(34, probe.size); assertEquals(1, probe[2].toInt()); assertEquals(0x0A, probe[4].toInt())
        assertNull(UvcDescriptors.parse(raw.copyOf(27)))
    }
}
