package dev.tonexotg.app.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Happy-path smoke test against Robolectric's real platform shadow
 * (`ShadowUsbDeviceConnection`/`ShadowUsbRequest`), which is what actually pins "this compiles
 * and runs against the real `android.hardware.usb` classes, not just an interface this project
 * made up." This is deliberately the ONLY thing this test is for -- [TonexUsbTransportTest]
 * (against [FakeUsbIoPort]) is where the watchdog/wedge/short-write/serialization acceptance
 * criteria actually get pinned, because the real shadow cannot simulate any of them; see
 * [UsbIoPort]'s class KDoc for the specific gaps (confirmed against Robolectric 4.16 and its
 * `android-all-instrumented` API 34 jar's actual bytecode this session, not assumed):
 * - `ShadowUsbDeviceConnection.requestWait(timeout)` ignores its own timeout argument.
 * - `bulkTransfer`/`controlTransfer` always report the full requested length as a success.
 * - `ShadowUsbRequest.queue(buffer)` blocks until it has read *exactly* `buffer.remaining()`
 *   bytes from its backing pipe -- no short-read simulation, and a production-sized read buffer
 *   would hang this test forever waiting for a write no real message is anywhere near. Hence the
 *   small `readBufferSize` below, sized to match this test's own payload exactly.
 *
 * [UsbDevice], [UsbInterface], and [UsbEndpoint] all have real public constructors under
 * Robolectric's instrumented runtime (confirmed against the actual `android-all-instrumented`
 * jar's decompiled bytecode this session) even though the compileSdk stub jar this module
 * compiles against hides all three behind a package-private no-arg constructor -- so this file
 * constructs them via reflection (looking the constructor up on the runtime-loaded, Robolectric-
 * instrumented `Class` rather than referencing it directly, which the compiler would reject).
 * Production code never constructs these itself either way -- the platform hands them out via
 * [UsbManager.getDeviceList]/[UsbDevice.getInterface]/[UsbInterface.getEndpoint] -- so this is
 * purely a test-construction detail, not something [UsbTonexDeviceOpener] or [AndroidUsbIoPort]
 * need to care about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TonexUsbTransportRobolectricTest {

    @Test
    fun writeAndReadRoundTripAgainstTheRealShadow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        val device = newUsbDevice()
        val connection = manager.openDevice(device)
        checkNotNull(connection) { "ShadowUsbManager.openDevice returned null" }

        val dataInterface = newUsbInterface(1, 0, "data", UsbConstants.USB_CLASS_CDC_DATA, 0, 0)
        val commInterface = newUsbInterface(0, 0, "comm", UsbConstants.USB_CLASS_COMM, 0, 0)
        val inEndpoint = newUsbEndpoint(0x87, UsbConstants.USB_ENDPOINT_XFER_BULK, 64, 0)
        val outEndpoint = newUsbEndpoint(0x07, UsbConstants.USB_ENDPOINT_XFER_BULK, 64, 0)

        // The outgoing pipe bulkTransfer writes into is created inside claimInterface() by the
        // shadow -- calling bulkTransfer before this NPEs.
        assertTrue(connection.claimInterface(dataInterface, true))
        assertTrue(connection.claimInterface(commInterface, true))

        val writePayload = byteArrayOf(1, 2, 3, 4)
        val readPayload = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)

        // See class KDoc: must exactly match readPayload.size, or ShadowUsbRequest.queue() blocks
        // forever waiting for bytes that will never arrive.
        val io = AndroidUsbIoPort(connection, dataInterface, commInterface, inEndpoint, outEndpoint, readBufferSize = readPayload.size)
        val transport = TonexUsbTransport(io, watchdogTimeoutMillis = 30_000L, watchdogPollIntervalMillis = 5_000L)

        try {
            // Write path: TonexUsbTransport.write -> bulkTransfer -> the shadow's outgoing pipe.
            val written = transport.write(writePayload)
            assertEquals(writePayload.size, written)
            val observedOnWire = shadowOf(connection).outgoingDataStream.readNBytes(writePayload.size)
            assertTrue(observedOnWire.contentEquals(writePayload))

            // Read path: the shadow's writeIncomingData -> the queued UsbRequest -> incoming().
            shadowOf(connection).writeIncomingData(readPayload)
            val received = withTimeout(10_000) { transport.incoming().first() }
            assertTrue(received.contentEquals(readPayload))
        } finally {
            transport.close()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Reflection-based construction -- see class KDoc for why this is necessary at all.
    // -----------------------------------------------------------------------------------------

    private fun newUsbDevice(): UsbDevice =
        UsbDevice::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun newUsbInterface(id: Int, alternateSetting: Int, name: String, ifaceClass: Int, subclass: Int, protocol: Int): UsbInterface =
        UsbInterface::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(id, alternateSetting, name, ifaceClass, subclass, protocol)

    private fun newUsbEndpoint(address: Int, attributes: Int, maxPacketSize: Int, interval: Int): UsbEndpoint =
        UsbEndpoint::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(address, attributes, maxPacketSize, interval)
}
