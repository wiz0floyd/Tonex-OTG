package dev.tonexotg.app.usb

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
 * Finds, requests permission for, opens, and configures the ToneX One's USB connection.
 *
 * Promoted from the S20 probe harness's `dev.tonexotg.app.probe.UsbDeviceOpener` (issue #25),
 * which hardware-verified every non-obvious step here: the VID/PID, the CDC-ACM Communications
 * vs. Data interface split, and that asserting DTR (not the line-coding values) is what the pedal
 * actually requires. That class stays in `dev.tonexotg.app.probe` as the diagnostic harness's own
 * copy (its KDoc's hardware-verification narrative is a real record worth keeping intact); this
 * is the same logic, unchanged in substance, given a production home so S11's
 * [TonexUsbTransport] and S12's (issue #17) full attach/detach/permission lifecycle both have a
 * single shared, non-probe-scoped implementation to depend on rather than two copies drifting
 * apart.
 *
 * ### Interface discovery, not "interface index 0"
 * A CDC-ACM function normally splits across two interfaces: a **Communications** interface (the
 * `SET_LINE_CODING`/`SET_CONTROL_LINE_STATE` control target, at most one interrupt IN endpoint,
 * `USB_CLASS_COMM`) and a **Data** interface (the bulk IN/OUT pair the wire protocol actually
 * uses). On this pedal those are indices 0 and 1 respectively, confirmed by its own Union
 * Functional Descriptor (`24 06 00 01`: master interface 0, slave interface 1) -- but
 * [requestPermissionAndOpen] does not hardcode either index. It searches every interface the
 * device reports: the Data interface by which one exposes both a bulk IN and bulk OUT endpoint,
 * the Communications interface by [UsbConstants.USB_CLASS_COMM]. Sending the DTR/line-coding
 * control requests to the wrong interface is silently accepted by `controlTransfer` (no error)
 * but DTR never actually lands -- confirmed the hard way during S20 (issue #16's original text
 * said "interface index 0" for the whole function; issue #16's comment thread has the correction
 * and the commit references).
 */
object UsbTonexDeviceOpener {

    /** ToneX One vendor ID, verified on issue #16. */
    const val VENDOR_ID: Int = 0x1963

    /** ToneX One product ID, verified on issue #16. */
    const val PRODUCT_ID: Int = 0x00D1

    private const val ACTION_USB_PERMISSION = "dev.tonexotg.app.usb.USB_PERMISSION"

    /** CDC class request codes (USB CDC 1.2 spec, table 46). */
    private const val REQUEST_SET_LINE_CODING = 0x20
    private const val REQUEST_SET_CONTROL_LINE_STATE = 0x22
    private const val CONTROL_LINE_DTR_RTS = 0x03

    /** `bmRequestType` for a host-to-device, class, interface-recipient control transfer. */
    private const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21

    sealed interface OpenResult {
        data class Success(
            val connection: UsbDeviceConnection,
            val dataInterface: UsbInterface,
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
     * Requests USB permission for [device] (suspending until the user answers the system dialog,
     * or a caller-supplied [timeoutMillis] elapses), then opens the connection, finds and claims
     * the Data interface (bulk IN/OUT) and the Communications interface (`USB_CLASS_COMM`)
     * separately -- see class KDoc -- sends the CDC line-coding/control-line dance to assert DTR,
     * and returns the raw config descriptors alongside the opened connection, both interfaces,
     * and both bulk endpoints.
     *
     * Every step here is read-only / connection-setup -- nothing in this function writes a single
     * byte to the pedal's own protocol.
     *
     * The returned [OpenResult.Success.connection]/interfaces are the caller's responsibility to
     * eventually release -- e.g. by handing them to [TonexUsbTransport]'s public constructor,
     * whose [TonexUsbTransport.close] releases both interfaces and closes the connection.
     */
    suspend fun requestPermissionAndOpen(context: Context, device: UsbDevice, timeoutMillis: Long = 30_000L): OpenResult {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        if (!manager.hasPermission(device)) {
            val granted = requestPermission(context, manager, device, timeoutMillis)
            if (!granted) {
                return OpenResult.Failure(
                    "USB permission was denied or timed out for VID=0x%04X/PID=0x%04X".format(VENDOR_ID, PRODUCT_ID),
                )
            }
        }

        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        val (dataInterface, inEndpoint, outEndpoint) = interfaces
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

        if (!connection.claimInterface(dataInterface, true)) {
            connection.close()
            return OpenResult.Failure("claimInterface(interface ${dataInterface.id}, force=true) returned false")
        }
        if (!connection.claimInterface(commInterface, true)) {
            connection.releaseInterface(dataInterface)
            connection.close()
            return OpenResult.Failure("claimInterface(interface ${commInterface.id}, force=true) returned false")
        }

        // Line coding is cosmetic per issue #16 ("both work"), but every known-working upstream
        // implementation sets *some* value before asserting DTR, so this does the same rather
        // than skipping straight to DTR on an unconfigured line -- 115200 8N1, arbitrarily.
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
            connection.releaseInterface(dataInterface)
            connection.close()
            return OpenResult.Failure("SET_CONTROL_LINE_STATE (assert DTR) failed, controlTransfer returned $dtrResult")
        }

        return OpenResult.Success(connection, dataInterface, commInterface, inEndpoint, outEndpoint, rawDescriptors)
    }

    /**
     * The permission dialog's answer, the cancellation path, and the timeout fallback below are
     * three independent sources that could all try to resolve/unregister at once (e.g. the user
     * answers right as the timeout fires) -- [resolved] ensures exactly one of them actually
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

        // No built-in timeout on the platform call; this adds one so a connect attempt that's
        // never answered fails loud instead of hanging forever.
        android.os.Handler(context.mainLooper).postDelayed({ resolve(false) }, timeoutMillis)
    }

    /** Hex-dumps [bytes] as space-separated byte pairs, 16 per line, for a raw descriptor log. */
    fun hexDump(bytes: ByteArray): String =
        bytes.toList().chunked(16).joinToString("\n") { row ->
            row.joinToString(" ") { "%02X".format(it) }
        }

    /**
     * This interface's bulk IN and bulk OUT endpoint, or `null` if it doesn't have both. A CDC-ACM
     * function normally splits across two interfaces -- a Communications interface (at most one
     * interrupt IN endpoint) and a Data interface (the bulk pair) -- so this is the actual
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
