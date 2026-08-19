package dev.tonexotg.app.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * [UsbIoPort] backed by the real Android USB Host API.
 *
 * Adapted from the S20 probe harness's `UsbRequestTonexTransport` (issue #25), with one
 * deliberate structural change: writes go through [UsbDeviceConnection.bulkTransfer] rather than
 * a second [UsbRequest]. That means this class only ever has ONE [UsbRequest] alive on
 * [connection] -- the persistent IN [readRequest] -- which removes a real hazard, not just a
 * theoretical one:
 *
 * - `UsbRequestTonexTransport` needed one thread to own `requestWait()` and demux its result by
 *   identity (`completed === inRequest` vs `=== outRequest`), because `requestWait()` can return
 *   *any* request queued on the connection. With only one [UsbRequest] ever queued here, that
 *   demux is structurally unnecessary. It is also provably safe even in principle:
 *   `bulkTransfer()` issues `USBDEVFS_BULK`, a *synchronous* ioctl that never enters the async URB
 *   submit/reap queue `requestWait()`/`REAPURBNDELAY` drains -- a `bulkTransfer` completion cannot
 *   be handed back by `requestWait()` (confirmed against AOSP `usbhost.c` and
 *   `android_hardware_UsbDeviceConnection.cpp` this session, not assumed). [requestWait] below
 *   still asserts the identity anyway, purely as a "the assumption above turned out to be wrong"
 *   tripwire -- fail loud rather than silently misroute.
 * - It also buys back honest short-write detection for free: the `UsbRequest` completion API has
 *   no transferred-byte-count query at all (`UsbRequestTonexTransport`'s KDoc, "no short-write
 *   detection"), so a `UsbRequest`-based write can only ever report the full requested size --
 *   which silently disarms [dev.tonexotg.protocol.TonexTransport.write]'s caller-side short-write
 *   check. [UsbDeviceConnection.bulkTransfer] returns the real transferred count.
 *
 * ### Concurrent `bulkTransfer` (write) + `requestWait` (read) on the same [connection] is safe
 * Neither takes [UsbDeviceConnection]'s internal `mLock` at the Java level -- only
 * `queueRequest`/`cancelRequest`/`close` do (confirmed against AOSP
 * `UsbDeviceConnection.java`, `master`, this session). At the native level they issue disjoint
 * ioctls (`USBDEVFS_BULK` vs `USBDEVFS_REAPURB`/`REAPURBNDELAY`) against the same fd with no
 * shared userspace state. So [TonexUsbTransport] running its reader and writer on separate
 * dedicated threads never needs to serialize the two against each other at this layer -- only
 * writes against other writes (the interface's own contract).
 *
 * ### `close()` is not a safe way to unblock a stuck native call
 * [UsbDeviceConnection.close] *does* take `mLock`, but a thread blocked inside
 * `native_request_wait` or `native_bulk_request` already read the native `usb_device*` before
 * blocking and is not holding any lock while parked -- so `close()` (which frees that struct)
 * can run concurrently with a still-blocked native call, which then dereferences freed memory.
 * [releaseAndClose] must only be invoked once the caller has confirmed (by joining, not just
 * interrupting) that no thread is still inside [requestWait] or [writeBulk] -- see
 * [TonexUsbTransport]'s watchdog KDoc, which owns that sequencing.
 *
 * [readBufferSize] defaults to a generous production value but is a constructor parameter (not a
 * constant) because Robolectric's `ShadowUsbRequest.queue(buffer)` blocks until it has read
 * *exactly* `buffer.remaining()` bytes from the shadow's backing pipe -- a Robolectric test using
 * the production default would hang waiting for 4096 bytes no real message is anywhere near.
 */
internal class AndroidUsbIoPort(
    private val connection: UsbDeviceConnection,
    private val dataInterface: UsbInterface,
    private val commInterface: UsbInterface,
    inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
) : UsbIoPort {

    private val readRequest = UsbRequest().apply {
        check(initialize(connection, inEndpoint)) { "AndroidUsbIoPort: readRequest.initialize() failed" }
    }
    private val readBuffer = ByteBuffer.allocateDirect(readBufferSize)

    override fun queueRead(): Boolean {
        readBuffer.clear()
        return readRequest.queue(readBuffer)
    }

    override fun requestWait(timeoutMillis: Long): ReadOutcome {
        val completed = try {
            connection.requestWait(timeoutMillis)
        } catch (t: TimeoutException) {
            return ReadOutcome.TimedOut
        }
        if (completed !== readRequest) {
            // Should be unreachable -- see class KDoc's demux-removal argument. Fail loud rather
            // than silently treat an unrecognized UsbRequest as a completed read.
            throw IOException(
                "AndroidUsbIoPort: requestWait() returned an unrecognized UsbRequest; this " +
                    "connection is only ever expected to have the single IN readRequest queued",
            )
        }
        val n = readBuffer.position()
        if (n == 0) return ReadOutcome.Completed(EMPTY_BYTES)
        readBuffer.flip()
        val bytes = ByteArray(n)
        readBuffer.get(bytes)
        return ReadOutcome.Completed(bytes)
    }

    override fun cancelRead() {
        runCatching { readRequest.cancel() }
    }

    override fun writeBulk(bytes: ByteArray, timeoutMillis: Int): Int =
        connection.bulkTransfer(outEndpoint, bytes, bytes.size, timeoutMillis)

    override fun releaseAndClose() {
        runCatching { readRequest.cancel() }
        runCatching { readRequest.close() }
        runCatching { connection.releaseInterface(dataInterface) }
        runCatching { connection.releaseInterface(commInterface) }
        runCatching { connection.close() }
    }

    companion object {
        /**
         * Deliberately NOT sized to either endpoint's reported `wMaxPacketSize` -- issue #16
         * records that the pedal's descriptors are malformed (bulk IN declares 64 bytes, bulk OUT
         * declares 512, on hardware that doesn't actually run high-speed), so trusting either
         * value for buffer sizing would build on the exact fact that's in question. `bulkTransfer`
         * (writes) and a queued `UsbRequest` (reads) both report however many bytes actually
         * arrived, up to this ceiling; chunk boundaries carry no protocol meaning (see
         * [dev.tonexotg.protocol.TonexTransport.incoming]'s KDoc) so an oversized read buffer is
         * harmless for correctness, only memory.
         */
        const val DEFAULT_READ_BUFFER_SIZE: Int = 4096

        private val EMPTY_BYTES = ByteArray(0)
    }
}
