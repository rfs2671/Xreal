package dev.ultraxr.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** One claimed HID interface: interrupt IN for reports, interrupt OUT or SET_REPORT for commands. */
class HidLink(private val conn: UsbDeviceConnection, val intf: UsbInterface) {
    val epIn: UsbEndpoint? = endpoint(UsbConstants.USB_DIR_IN)
    val epOut: UsbEndpoint? = endpoint(UsbConstants.USB_DIR_OUT)
    val claimed: Boolean = conn.claimInterface(intf, true)

    private fun endpoint(dir: Int): UsbEndpoint? {
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == dir) return e
        }
        return null
    }

    /** Returns bytes written (<0 on failure) and which path was used. */
    fun write(data: ByteArray): Pair<Int, String> {
        val out = epOut
        if (out != null) return conn.bulkTransfer(out, data, data.size, 500) to "interrupt-out"
        // HID SET_REPORT(Output, report id 0)
        return conn.controlTransfer(0x21, 0x09, 0x0200, intf.id, data, data.size, 500) to "set-report"
    }

    /** Size of one interrupt packet; reads ask for exactly this so each read returns one report. */
    val packetSize: Int = epIn?.maxPacketSize?.takeIf { it > 0 } ?: 64

    fun read(buf: ByteArray, timeoutMs: Int): Int {
        val e = epIn ?: return -1
        // A usbfs IN transfer only completes on a short packet or a full buffer, so a larger buffer
        // would merge several reports (or never complete for a single full-size reply).
        return conn.bulkTransfer(e, buf, minOf(buf.size, packetSize), timeoutMs)
    }

    fun release() { conn.releaseInterface(intf) }

    companion object {
        fun find(device: UsbDevice, id: Int): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                if (f.id == id && f.alternateSetting == 0) return f
            }
            return null
        }
    }
}
