package dev.ultraxr.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import dev.ultraxr.proto.UvcDescriptors
import dev.ultraxr.proto.UvcFrameAssembler
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * User-space UVC reader for the Ultra's SLAM camera interface: standard probe/commit,
 * then isochronous (via usbdevfs) or bulk streaming, whichever the device exposes.
 */
class UvcStream(
    private val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    private val info: UvcDescriptors.Info,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var streamAlt0: UsbInterface? = null
    private var claimed = ArrayList<UsbInterface>()

    @Volatile var transport = "none"; private set
    @Volatile var badIsoPackets = 0L; private set
    @Volatile var transferErrors = 0L; private set
    @Volatile var bytes = 0L; private set
    @Volatile var fatal: String? = null; private set
    var assembler: UvcFrameAssembler? = null; private set
    var negotiatedFrameBytes = 0; private set
    var negotiatedPayloadSize = 0; private set
    var chosen = ""; private set

    private fun interfaces(id: Int): List<UsbInterface> =
        (0 until device.interfaceCount).map { device.getInterface(it) }.filter { it.id == id }

    /** Negotiates [width]x[height] and starts streaming. Returns an error string, or null on success. */
    fun start(width: Int, height: Int, frameBytes: Int, onFrame: (ByteArray) -> Unit): String? {
        val alts = interfaces(info.streamInterface)
        val alt0 = alts.firstOrNull { it.alternateSetting == 0 } ?: return "no alt 0 on VS interface"
        streamAlt0 = alt0
        if (info.controlInterface >= 0) {
            interfaces(info.controlInterface).firstOrNull()?.let {
                if (conn.claimInterface(it, true)) claimed += it else log("camera: claim VC interface failed")
            }
        }
        if (!conn.claimInterface(alt0, true)) return "claim VS interface failed (kernel driver busy?)"
        claimed += alt0

        // Pick format/frame: exact size if advertised, otherwise the first frame.
        var fmt: UvcDescriptors.Format? = null
        var frm: UvcDescriptors.Frame? = null
        for (f in info.formats) for (fr in f.frames) if (fr.width == width && fr.height == height && frm == null) { fmt = f; frm = fr }
        if (frm == null) { fmt = info.formats.firstOrNull { it.frames.isNotEmpty() }; frm = fmt?.frames?.first() }
        if (fmt == null || frm == null) return "no usable UVC format/frame descriptors"
        // 60 fps is the mode XR Gate uses; fall back to the fastest advertised one.
        val interval = frm.intervals100ns.firstOrNull { it == 166666 } ?: frm.intervals100ns.minOrNull() ?: 166666
        chosen = "format#${fmt.index} ${fmt.guid} frame#${frm.index} ${frm.width}x${frm.height} " +
            "interval=${interval} (%.1f fps)".format(1e7 / interval)
        log("camera: requesting $chosen")

        val sizes = when {
            info.bcdUvc >= 0x0150 -> intArrayOf(48, 34, 26)
            info.bcdUvc >= 0x0110 -> intArrayOf(34, 26)
            else -> intArrayOf(26, 34)
        }
        var negotiated: ByteArray? = null
        for (size in sizes) {
            val probe = UvcDescriptors.probeBlock(size, fmt.index, frm.index, interval)
            val set = conn.controlTransfer(0x21, 0x01, 0x0100, info.streamInterface, probe, size, 1000)
            if (set < 0) { log("camera: PROBE SET_CUR($size) failed"); continue }
            val got = ByteArray(size)
            val n = conn.controlTransfer(0xA1, 0x81, 0x0100, info.streamInterface, got, size, 1000)
            if (n < 26) { log("camera: PROBE GET_CUR($size) returned $n"); continue }
            val commit = got.copyOf(n)
            val c = conn.controlTransfer(0x21, 0x01, 0x0200, info.streamInterface, commit, n, 1000)
            if (c < 0) { log("camera: COMMIT($n) failed"); continue }
            negotiated = commit
            break
        }
        if (negotiated == null) return "UVC probe/commit rejected by the device"
        negotiatedFrameBytes = UvcDescriptors.probeMaxVideoFrameSize(negotiated)
        negotiatedPayloadSize = UvcDescriptors.probeMaxPayloadTransferSize(negotiated)
        log("camera: negotiated dwMaxVideoFrameSize=$negotiatedFrameBytes dwMaxPayloadTransferSize=$negotiatedPayloadSize")
        if (negotiatedFrameBytes != 0 && negotiatedFrameBytes != frameBytes) {
            log("camera: WARNING device frame size $negotiatedFrameBytes != expected $frameBytes")
        }

        val asm = UvcFrameAssembler(frameBytes, onFrame)
        assembler = asm
        val iso = info.endpoints.filter { it.isIso && it.altSetting != 0 }
        val bulk = info.endpoints.firstOrNull { it.isBulk }
        running = true
        if (iso.isNotEmpty()) {
            val need = negotiatedPayloadSize
            val ep = iso.filter { it.payloadPerInterval >= need }.minByOrNull { it.payloadPerInterval }
                ?: iso.maxByOrNull { it.payloadPerInterval }!!
            val altIntf = alts.firstOrNull { it.alternateSetting == ep.altSetting }
                ?: return "alt setting ${ep.altSetting} not exposed by Android"
            if (!conn.setInterface(altIntf)) return "SET_INTERFACE alt ${ep.altSetting} failed"
            transport = "isochronous alt=${ep.altSetting} ep=0x%02x %d bytes/microframe".format(ep.address, ep.payloadPerInterval)
            log("camera: $transport")
            val pkts = 32
            val handle = IsoNative.start(conn.fileDescriptor, ep.address, ep.payloadPerInterval, pkts, 8)
            if (handle in -4095L..0L) { running = false; return "usbdevfs iso submit failed (errno ${-handle})" }
            worker = thread(name = "uvc-iso") { isoLoop(handle, ep.payloadPerInterval, pkts, asm) }
        } else if (bulk != null) {
            val ep = alt0.let { f -> (0 until f.endpointCount).map { f.getEndpoint(it) }.firstOrNull { it.address == bulk.address } }
                ?: alts.flatMap { f -> (0 until f.endpointCount).map { f.getEndpoint(it) } }.firstOrNull { it.address == bulk.address }
                ?: return "bulk endpoint not exposed by Android"
            transport = "bulk ep=0x%02x".format(bulk.address)
            log("camera: $transport")
            // One transfer per payload, as uvcvideo does: a larger buffer could merge payloads whose
            // size is a multiple of the packet size, putting headers into the image.
            val size = if (negotiatedPayloadSize > 0) negotiatedPayloadSize.coerceAtMost(1 shl 20) else 16384
            worker = thread(name = "uvc-bulk") {
                val buf = ByteArray(size)
                while (running) {
                    val n = conn.bulkTransfer(ep, buf, buf.size, 500)
                    if (n < 0) { transferErrors++; continue }
                    bytes += n
                    asm.push(buf, 0, n)
                }
            }
        } else {
            running = false
            return "VS interface has neither an isochronous nor a bulk endpoint"
        }
        return null
    }

    private fun isoLoop(handle: Long, pktSize: Int, pkts: Int, asm: UvcFrameAssembler) {
        val direct = ByteBuffer.allocateDirect(pktSize * pkts)
        val meta = IntArray(pkts + 2)
        val heap = ByteArray(pktSize * pkts)
        var failures = 0
        while (running) {
            val n = IsoNative.reap(handle, direct, meta, 500)
            if (n < 0) {
                transferErrors++
                if (++failures > 20) { fatal = "iso reap errno ${-n}"; break }
                continue
            }
            if (n == 0) continue
            failures = 0
            badIsoPackets += meta[1]
            bytes += n
            direct.position(0); direct.get(heap, 0, n)
            var off = 0
            for (i in 0 until meta[0]) { val len = meta[2 + i]; asm.push(heap, off, len); off += len }
        }
        IsoNative.stop(handle)
    }

    fun stop() {
        running = false
        worker?.join(1500)
        worker = null
        streamAlt0?.let { conn.setInterface(it) }
        for (f in claimed) conn.releaseInterface(f)
        claimed.clear()
    }

    companion object {
        fun hasVideoInterface(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
    }
}
