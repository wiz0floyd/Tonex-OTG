package dev.tonexotg.app.probe

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import dev.tonexotg.protocol.TonexTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

/**
 * ⚠️ DIAGNOSTIC-ONLY. This is **not** the production S11 USB transport (issue #16) — it is a
 * throwaway harness for the S20 hardware probe (issue #25), a one-shot diagnostic session a
 * human runs once and closes. It deliberately skips everything S11's own KDoc calls for on the
 * production path: no [android.hardware.usb.UsbRequest] queuing, no dedicated watchdog thread,
 * no wedge detection, no `requestWait(timeout)` demuxing. A plain, synchronous
 * [UsbDeviceConnection.bulkTransfer] loop on a single background thread is judged sufficient
 * here per this project's "don't over-build" house philosophy (CLAUDE.md) — this class exists to
 * answer S20's questions once, not to survive a gig. **Do not promote this class into the real
 * app without redoing it against S11's design.**
 *
 * ## Lifecycle is deliberately unusual: [close] does NOT close the USB connection
 *
 * [TonexTransport.close]'s own KDoc says "releases the underlying connection," and ordinarily
 * that would mean calling [UsbDeviceConnection.close] here. This class does not, on purpose: the
 * S20 probe harness needs to run [dev.tonexotg.protocol.connection.DefaultTonexController.connect]
 * more than once against the *same* physical USB connection within one diagnostic session (once
 * for the read-only pass, then again — cheaply, without re-requesting permission or re-claiming
 * the interface — to independently verify a single-parameter write actually landed on the pedal,
 * per issue #25's "check the pedal's response/ack rather than assuming success"). [close] here
 * only stops this instance's own reader thread and marks it unusable; the caller (the probe
 * Activity/session, which owns [connection] and the claimed interface for the whole diagnostic
 * run) is responsible for the one real [UsbDeviceConnection.close] at the very end. Do not copy
 * this pattern into production code without re-reading this note.
 */
class UsbTonexTransport(
    private val connection: UsbDeviceConnection,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : TonexTransport {

    private val closed = AtomicBoolean(false)
    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val readerThread: Thread

    init {
        readerThread = Thread({ readLoop() }, "UsbTonexTransport-reader").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * A generously large read buffer, deliberately **not** sized to [inEndpoint]'s reported
     * `maxPacketSize` — issue #16 flags that value as coming from a malformed descriptor on this
     * device (an IN endpoint that declares 64 bytes while claiming high-speed, on a device that
     * actually runs full-speed), so trusting it for buffer sizing would be building on the exact
     * fact this probe exists to test, not a safe assumption. [bulkTransfer] returns however many
     * bytes actually arrived in one call, up to this ceiling; chunk boundaries carry no protocol
     * meaning (see [TonexTransport.incoming]'s KDoc) so an oversized buffer is harmless.
     */
    private fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (!closed.get()) {
            val n = try {
                connection.bulkTransfer(inEndpoint, buffer, buffer.size, READ_TIMEOUT_MILLIS)
            } catch (e: Throwable) {
                if (closed.get()) break
                incomingChannel.close(e)
                return
            }
            if (closed.get()) break
            if (n > 0) {
                incomingChannel.trySend(buffer.copyOf(n))
            }
            // n == 0 or n < 0: timeout or nothing available this poll — loop and try again. A
            // negative return from bulkTransfer is ambiguous between "timeout" and "real error"
            // per the platform API; this diagnostic harness does not attempt to tell them apart
            // (that distinction, if it matters, is exactly the kind of thing the probe log's raw
            // observations should surface for a human to read, not something to guess at here).
        }
        incomingChannel.close()
    }

    override suspend fun write(bytes: ByteArray): Int = withContext(Dispatchers.IO) {
        if (closed.get()) {
            throw IOException("UsbTonexTransport.write: transport is closed")
        }
        val n = connection.bulkTransfer(outEndpoint, bytes, bytes.size, WRITE_TIMEOUT_MILLIS)
        if (n < 0) {
            throw IOException("UsbTonexTransport.write: bulkTransfer OUT failed (rc=$n)")
        }
        n
    }

    override fun incoming(): Flow<ByteArray> = incomingChannel.receiveAsFlow()

    /** Stops this transport's reader thread. Does NOT close [connection] — see class KDoc. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        readerThread.interrupt()
    }

    companion object {
        private const val READ_BUFFER_SIZE: Int = 4096
        private const val READ_TIMEOUT_MILLIS: Int = 500
        private const val WRITE_TIMEOUT_MILLIS: Int = 2000
    }
}
