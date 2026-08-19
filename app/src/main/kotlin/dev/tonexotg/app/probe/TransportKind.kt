package dev.tonexotg.app.probe

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import dev.tonexotg.protocol.TonexTransport

/**
 * S21 (issue #26): which low-level Android USB transfer API backs a diagnostic
 * [dev.tonexotg.protocol.TonexTransport] for one probe run — [UsbTonexTransport]'s existing
 * `bulkTransfer` loop, or [UsbRequestTonexTransport]'s `UsbRequest`/`requestWait` loop. See both
 * classes' KDoc for what each does and, for [USB_REQUEST], a genuine limitation versus the other.
 *
 * Exists so [ProbeSession]'s latency-measurement pass can be run twice against the same pedal —
 * once per [TransportKind] — and a human compares the two saved logs, which is issue #26's
 * "compare UsbRequest vs bulkTransfer... for our own traffic pattern" acceptance criterion.
 */
enum class TransportKind(val label: String) {
    BULK_TRANSFER("bulkTransfer"),
    USB_REQUEST("UsbRequest"),
    ;

    fun create(connection: UsbDeviceConnection, inEndpoint: UsbEndpoint, outEndpoint: UsbEndpoint): TonexTransport =
        when (this) {
            BULK_TRANSFER -> UsbTonexTransport(connection, inEndpoint, outEndpoint)
            USB_REQUEST -> UsbRequestTonexTransport(connection, inEndpoint, outEndpoint)
        }
}
