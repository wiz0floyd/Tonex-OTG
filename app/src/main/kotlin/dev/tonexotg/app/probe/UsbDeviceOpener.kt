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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * ⚠️ DIAGNOSTIC-ONLY (see [UsbTonexTransport]'s class KDoc — same caveat applies here).
 *
 * Finds, requests permission for, opens, and configures the ToneX One's USB connection, using
 * the device facts already verified and recorded on issue #16 (S11) rather than rediscovering
 * them: VID `0x1963`, PID `0x00D1`, CDC-ACM, interface index `0`, and "asserting DTR is what
 * matters, baud rate is cosmetic."
 */
object UsbDeviceOpener {

    /** ToneX One vendor ID, verified on issue #16. */
    const val VENDOR_ID: Int = 0x1963

    /** ToneX One product ID, verified on issue #16. */
    const val PRODUCT_ID: Int = 0x00D1

    /** Interface index the pedal's CDC-ACM function lives on, per issue #16. */
    const val INTERFACE_INDEX: Int = 0

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
     * dialog, or a caller-supplied [timeoutMillis] elapses), then opens the connection, claims
     * interface [INTERFACE_INDEX], sends the CDC line-coding/control-line dance to assert DTR,
     * and returns the raw config descriptors alongside the opened connection and endpoints.
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

        val usbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == INTERFACE_INDEX }
            ?: return OpenResult.Failure(
                "No interface with id=$INTERFACE_INDEX found (device reports ${device.interfaceCount} interface(s))",
            )

        val connection = manager.openDevice(device)
            ?: return OpenResult.Failure("UsbManager.openDevice returned null")

        val rawDescriptors = connection.rawDescriptors ?: ByteArray(0)

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            return OpenResult.Failure("claimInterface(interface $INTERFACE_INDEX, force=true) returned false")
        }

        val inEndpoint = (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        val outEndpoint = (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

        if (inEndpoint == null || outEndpoint == null) {
            connection.releaseInterface(usbInterface)
            connection.close()
            return OpenResult.Failure(
                "Interface $INTERFACE_INDEX did not expose both a bulk IN and bulk OUT endpoint " +
                    "(found ${usbInterface.endpointCount} endpoint(s) total)",
            )
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
            usbInterface.id,
            lineCoding,
            lineCoding.size,
            1000,
        )

        // Asserting DTR is the part issue #16 says actually matters.
        val dtrResult = connection.controlTransfer(
            REQUEST_TYPE_CLASS_INTERFACE_OUT,
            REQUEST_SET_CONTROL_LINE_STATE,
            CONTROL_LINE_DTR_RTS,
            usbInterface.id,
            null,
            0,
            1000,
        )
        if (dtrResult < 0) {
            connection.releaseInterface(usbInterface)
            connection.close()
            return OpenResult.Failure("SET_CONTROL_LINE_STATE (assert DTR) failed, controlTransfer returned $dtrResult")
        }

        return OpenResult.Success(connection, usbInterface, inEndpoint, outEndpoint, rawDescriptors)
    }

    private suspend fun requestPermission(
        context: Context,
        manager: UsbManager,
        device: UsbDevice,
        timeoutMillis: Long,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                context.unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (cont.isActive) cont.resume(granted)
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        manager.requestPermission(device, pendingIntent)

        // No built-in timeout on the platform call; this harness adds one so a probe run that's
        // never answered fails loud instead of hanging the "Connect" button forever.
        android.os.Handler(context.mainLooper).postDelayed(
            {
                if (cont.isActive) {
                    runCatching { context.unregisterReceiver(receiver) }
                    cont.resume(false)
                }
            },
            timeoutMillis,
        )
    }

    /** Hex-dumps [bytes] as space-separated byte pairs, 16 per line, for the raw descriptor log. */
    fun hexDump(bytes: ByteArray): String =
        bytes.toList().chunked(16).joinToString("\n") { row ->
            row.joinToString(" ") { "%02X".format(it) }
        }
}
