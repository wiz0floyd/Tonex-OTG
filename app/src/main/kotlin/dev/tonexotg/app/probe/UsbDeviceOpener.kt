package dev.tonexotg.app.probe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ⚠️ DIAGNOSTIC-ONLY (see [UsbTonexTransport]'s class KDoc — same caveat applies here).
 *
 * Finds, requests permission for, opens, and configures the ToneX One's USB connection, using
 * the device facts already verified and recorded on issue #16 (S11) rather than rediscovering
 * them: VID `0x1963`, PID `0x00D1`, CDC-ACM, and "asserting DTR is what matters, baud rate is
 * cosmetic."
 *
 * ### Correction to issue #16's "interface index 0" (found on real hardware, issue #25)
 * Issue #16 recorded the pedal's CDC-ACM function as living on interface index `0`. On the real
 * pedal that index is the CDC **Communications** interface (the control/notification interface a
 * standard CDC-ACM function exposes), which carries at most one interrupt IN endpoint — not the
 * bulk IN/OUT pair. The bulk pair lives on the CDC **Data** interface, a different interface
 * index (confirmed `1` on this pedal, but [requestPermissionAndOpen] does not hardcode that
 * either — it searches every interface the device reports and claims whichever one actually
 * exposes both a bulk IN and a bulk OUT endpoint).
 *
 * That split matters for the CDC class control requests too, not just the bulk endpoints. Per the
 * CDC 1.2 spec, `SET_LINE_CODING`/`SET_CONTROL_LINE_STATE` are addressed to the *Communications*
 * interface, never the Data interface — and this pedal's own descriptor dump confirms the two are
 * genuinely separate here: the Union Functional Descriptor (`24 06 00 01`) declares master
 * interface `0`, slave interface `1`. A first attempt at this fix reused the bulk-endpoint
 * interface's id (`1`) for the control requests too, on the reasoning that both used to be
 * `INTERFACE_INDEX` before this fix existed; `controlTransfer` accepted that without error, but
 * the pedal never responded to the opening "hello" handshake — consistent with DTR never actually
 * having been asserted. [requestPermissionAndOpen] now locates the Communications interface
 * separately (by [UsbConstants.USB_CLASS_COMM]) and claims and targets both interfaces
 * independently. (Diagnosis from the descriptor bytes and the CDC spec; awaiting a hardware run
 * to confirm the "hello" handshake actually completes with this fix.)
 */
object UsbDeviceOpener {

    /** ToneX One vendor ID, verified on issue #16. */
    const val VENDOR_ID: Int = 0x1963

    /** ToneX One product ID, verified on issue #16. */
    const val PRODUCT_ID: Int = 0x00D1

    private const val ACTION_USB_PERMISSION = "dev.tonexotg.app.probe.USB_PERMISSION"

    /** CDC class request codes (USB CDC 1.2 spec, table 46). */
    private const val REQUEST_SET_LINE_CODING = 0x20
    private const val REQUEST_SET_CONTROL_LINE_STATE = 0x22
    private const val CONTROL_LINE_DTR_RTS = 0x03

    /** `bmRequestType` for a host-to-device, class, interface-recipient control transfer. */
    private const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21

    sealed interface OpenResult {
        data class Success(
            val connection: UsbDeviceConnection,
            val usbInterface: UsbInterface,
            val commInterface: UsbInterface,
            val inEndpoint: UsbEndpoint,
            val outEndpoint: UsbEndpoint,
            val rawDescriptors: ByteArray,
        ) : OpenResult

        data class Failure(val reason: String) : OpenResult
    }

    /** The pedal, if currently attached and enumerated by the OS, else `null`. */
    fun findDevice(context: Context): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.firstOrNull { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }
    }

    /**
     * Requests USB permission for [device] (suspending until the user answers the system
     * dialog, or a caller-supplied [timeoutMillis] elapses), then opens the connection, finds
     * and claims whichever interface exposes both a bulk IN and bulk OUT endpoint (see the class
     * KDoc's correction note — this is not assumed to be interface index 0), sends the CDC
     * line-coding/control-line dance to assert DTR, and returns the raw config descriptors
     * alongside the opened connection and endpoints.
     *
     * Every step here is read-only / connection-setup — nothing in this function writes a
     * single byte to the pedal's own protocol; see issue #25's "do all reads first" safety
     * protocol.
     */
    suspend fun requestPermissionAndOpen(context: Context, device: UsbDevice, timeoutMillis: Long = 30_000L): OpenResult {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        if (!manager.hasPermission(device)) {
            val granted = requestPermission(context, manager, device, timeoutMillis)
            if (!granted) {
                return OpenResult.Failure("USB permission was denied or timed out for VID=0x1963/PID=0x00D1")
            }
        }

        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        val (usbInterface, inEndpoint, outEndpoint) = interfaces
            .firstNotNullOfOrNull { candidate -> candidate.bulkInOutEndpoints()?.let { (i, o) -> Triple(candidate, i, o) } }
            ?: return OpenResult.Failure(
                "None of the device's ${device.interfaceCount} interface(s) expose both a bulk IN " +
                    "and bulk OUT endpoint (per-interface endpoint counts: " +
                    interfaces.joinToString { "id=${it.id}:${it.endpointCount}" } + ")",
            )
        // The Communications interface (see class KDoc) is where the CDC line-coding/control-line
        // control requests below must be addressed, not the Data interface found above.
        val commInterface = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            ?: return OpenResult.Failure(
                "No interface with class USB_CLASS_COMM (0x02) found (device reports " +
                    "${device.interfaceCount} interface(s)); cannot address SET_LINE_CODING/" +
                    "SET_CONTROL_LINE_STATE without it",
            )

        val connection = manager.openDevice(device)
            ?: return OpenResult.Failure("UsbManager.openDevice returned null")

        val rawDescriptors = connection.rawDescriptors ?: ByteArray(0)

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            return OpenResult.Failure("claimInterface(interface ${usbInterface.id}, force=true) returned false")
        }
        if (!connection.claimInterface(commInterface, true)) {
            connection.releaseInterface(usbInterface)
            connection.close()
            return OpenResult.Failure("claimInterface(interface ${commInterface.id}, force=true) returned false")
        }

        // Line coding is cosmetic per issue #16 ("both work"), but every known-working upstream
        // implementation sets *some* value before asserting DTR, so this harness does the same
        // rather than skipping straight to DTR on an unconfigured line — 115200 8N1, arbitrarily.
        val lineCoding = byteArrayOf(
            0x00, 0xC2.toByte(), 0x01, 0x00, // dwDTERate = 115200, little-endian
            0x00, // bCharFormat = 1 stop bit
            0x00, // bParityType = none
            0x08, // bDataBits = 8
        )
        connection.controlTransfer(
            REQUEST_TYPE_CLASS_INTERFACE_OUT,
            REQUEST_SET_LINE_CODING,
            0,
            commInterface.id,
            lineCoding,
            lineCoding.size,
            1000,
        )

        // Asserting DTR is the part issue #16 says actually matters.
        val dtrResult = connection.controlTransfer(
            REQUEST_TYPE_CLASS_INTERFACE_OUT,
            REQUEST_SET_CONTROL_LINE_STATE,
            CONTROL_LINE_DTR_RTS,
            commInterface.id,
            null,
            0,
            1000,
        )
        if (dtrResult < 0) {
            connection.releaseInterface(commInterface)
            connection.releaseInterface(usbInterface)
            connection.close()
            return OpenResult.Failure("SET_CONTROL_LINE_STATE (assert DTR) failed, controlTransfer returned $dtrResult")
        }

        return OpenResult.Success(connection, usbInterface, commInterface, inEndpoint, outEndpoint, rawDescriptors)
    }

    /**
     * The permission dialog's answer, the cancellation path, and the timeout fallback below are
     * three independent sources that could all try to resolve/unregister at once (e.g. the user
     * answers right as the timeout fires) — [resolved] ensures exactly one of them actually
     * resumes [cont] and unregisters [receiver]; every unregister is also wrapped in
     * [runCatching] as a second line of defense, since [Context.unregisterReceiver] throws
     * `IllegalArgumentException` if called on an already-unregistered receiver, and that would
     * otherwise crash the one interactive step a human has to click through.
     */
    private suspend fun requestPermission(
        context: Context,
        manager: UsbManager,
        device: UsbDevice,
        timeoutMillis: Long,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val resolved = AtomicBoolean(false)
        lateinit var receiver: BroadcastReceiver

        fun resolve(granted: Boolean) {
            if (!resolved.compareAndSet(false, true)) return
            runCatching { context.unregisterReceiver(receiver) }
            if (cont.isActive) cont.resume(granted)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                resolve(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        cont.invokeOnCancellation {
            resolved.set(true) // cont is already cancelled; only unregister, never resume it again
            runCatching { context.unregisterReceiver(receiver) }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        manager.requestPermission(device, pendingIntent)

        // No built-in timeout on the platform call; this harness adds one so a probe run that's
        // never answered fails loud instead of hanging the "Connect" button forever.
        android.os.Handler(context.mainLooper).postDelayed({ resolve(false) }, timeoutMillis)
    }

    /** Hex-dumps [bytes] as space-separated byte pairs, 16 per line, for the raw descriptor log. */
    fun hexDump(bytes: ByteArray): String =
        bytes.toList().chunked(16).joinToString("\n") { row ->
            row.joinToString(" ") { "%02X".format(it) }
        }

    /**
     * This interface's bulk IN and bulk OUT endpoint, or `null` if it doesn't have both. A CDC-ACM
     * function normally splits across two interfaces — a Communications interface (at most one
     * interrupt IN endpoint) and a Data interface (the bulk pair) — so this is the actual
     * selection criterion, not "interface index 0"; see the class KDoc's correction note.
     */
    private fun UsbInterface.bulkInOutEndpoints(): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until endpointCount).map { getEndpoint(it) }
        val inEndpoint = endpoints.firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
        }
        val outEndpoint = endpoints.firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
        }
        return if (inEndpoint != null && outEndpoint != null) inEndpoint to outEndpoint else null
    }
}
