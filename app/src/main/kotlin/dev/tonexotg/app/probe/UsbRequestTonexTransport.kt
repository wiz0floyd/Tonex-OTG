package dev.tonexotg.app.probe

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import dev.tonexotg.protocol.TonexTransport
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

/**
 * ⚠️ DIAGNOSTIC-ONLY. S21's (issue #26) `UsbRequest`/`requestWait()`-based alternative to
 * [UsbTonexTransport]'s `bulkTransfer` loop, built specifically so the probe harness can measure
 * both against the same pedal and let a human compare — issue #26 asks whether "`UsbRequest` beats
 * `bulkTransfer` substantially on Android P90 latency" holds for this app's own traffic pattern,
 * and the honest answer requires a real, working second implementation to measure, not a guess.
 * Exactly as diagnostic-only and throwaway as [UsbTonexTransport] — see that class's KDoc for the
 * full "do not promote into production" reasoning, which applies here identically. This is why
 * `minSdk` was set to 26 in the first place (see `app/build.gradle.kts`): that is the floor where
 * [UsbRequest.queue] (the `ByteBuffer`-only overload) and [UsbDeviceConnection.requestWait] (the
 * timeout-bounded overload) exist — below it, only the forever-blocking no-arg `requestWait()` is
 * available, which cannot be interrupted (AOSP issue 39522) and would make this class impossible to
 * shut down safely between the probe's reconnect cycles.
 *
 * ## Why one dedicated thread drives BOTH directions, not one thread per direction
 *
 * [UsbDeviceConnection.requestWait] is a single call that waits for **any** [UsbRequest] queued on
 * this [connection] (any endpoint) to complete, and returns whichever one did — there is no
 * per-endpoint variant. If a read-loop thread and this class's [write] caller each called
 * `requestWait` concurrently from different threads, either could receive the other's completed
 * request, silently misrouting a write completion as a read or vice versa — a real correctness
 * hazard, not a hypothetical one. So exactly one thread ([loopThread]) ever calls `requestWait` on
 * [connection]: it demuxes the returned request by identity ([inRequest] vs [outRequest], compared
 * with `===`) and services both directions from that single loop. [write] itself runs on
 * `Dispatchers.IO` (an arbitrary thread pool thread) and only ever *hands off* the bytes to queue —
 * via [pendingWrites] — never calls `requestWait` itself.
 *
 * ## A genuine limitation versus [UsbTonexTransport]: no short-write detection on OUT transfers
 *
 * [UsbTonexTransport.write] returns [UsbDeviceConnection.bulkTransfer]'s actual return value, which
 * `DefaultTonexController.writeFramed` compares against the intended length to detect a short
 * write. The `UsbRequest`/`requestWait` completion API used here provides no equivalent — once
 * [connection].`requestWait` reports [outRequest] complete, there is no API queried here that says
 * how many bytes of the queued buffer actually transferred. [write] below therefore reports the
 * full requested size on every successful completion; a short OUT write, if this hardware or driver
 * stack can even produce one, would NOT be caught the way [UsbTonexTransport] catches it. This is a
 * real, load-bearing difference between the two transports, not an oversight — surfaced here so
 * whoever reads a probe log comparing them knows to weigh it, not just the latency numbers.
 *
 * ## IN-transfer byte count: read from the buffer's position after completion
 *
 * For a queued read, this class relies on the buffer's `position()` reflecting the number of bytes
 * the platform actually delivered once [inRequest] completes (the buffer is `clear()`-ed — position
 * 0 — immediately before every `queue()` call). This is standard documented `UsbRequest.queue(ByteBuffer)`
 * behavior, but — like everything else in this probe harness — has not been independently confirmed
 * against this project's real pedal in this session (no hardware is available here). If a probe run
 * using [TransportKind.USB_REQUEST] logs reads that look truncated, garbled, or entirely silent
 * where [TransportKind.BULK_TRANSFER] on the same pedal does not, this assumption is the first place
 * to look — please note that contrast as a comment on issue #26.
 */
class UsbRequestTonexTransport(
    private val connection: UsbDeviceConnection,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : TonexTransport {

    private val closed = AtomicBoolean(false)
    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val pendingWrites = Channel<PendingWrite>(Channel.UNLIMITED)

    private val inRequest = UsbRequest().apply { initialize(connection, inEndpoint) }
    private val outRequest = UsbRequest().apply { initialize(connection, outEndpoint) }

    private val loopThread: Thread

    init {
        loopThread = Thread({ eventLoop() }, "UsbRequestTonexTransport-loop").apply {
            isDaemon = true
            start()
        }
    }

    private class PendingWrite(val buffer: ByteBuffer, val sizeBytes: Int, val completion: CompletableDeferred<Int>)

    /**
     * The single thread that ever calls [UsbDeviceConnection.requestWait] on [connection] — see
     * class KDoc for why that is load-bearing, not a style choice. Owns queuing and re-queuing
     * [inRequest] for the next read, and dequeuing+queuing at most one [PendingWrite] at a time onto
     * [outRequest] (matching [dev.tonexotg.protocol.TonexTransport]'s documented contract that
     * callers above this seam serialize writes — this loop never queues a second OUT transfer while
     * one is still in flight).
     */
    private fun eventLoop() {
        val readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE)
        if (!inRequest.queue(readBuffer)) {
            incomingChannel.close(IOException("UsbRequestTonexTransport: initial IN queue() failed"))
            return
        }

        var inFlightWrite: PendingWrite? = null
        while (!closed.get()) {
            if (inFlightWrite == null) {
                val pending = pendingWrites.tryReceive().getOrNull()
                if (pending != null) {
                    if (outRequest.queue(pending.buffer)) {
                        inFlightWrite = pending
                    } else {
                        pending.completion.completeExceptionally(IOException("UsbRequestTonexTransport.write: OUT queue() failed"))
                    }
                }
            }

            val completed = try {
                connection.requestWait(LOOP_WAIT_TIMEOUT_MILLIS)
            } catch (t: TimeoutException) {
                null // nothing completed within the poll window; loop around to re-check closed/pending writes
            } catch (t: Throwable) {
                if (closed.get()) break
                incomingChannel.close(IOException("UsbRequestTonexTransport: requestWait failed", t))
                inFlightWrite?.completion?.completeExceptionally(t)
                inFlightWrite = null
                break
            } ?: continue

            when {
                completed === inRequest -> {
                    val n = readBuffer.position()
                    if (n > 0) {
                        readBuffer.flip()
                        val bytes = ByteArray(n)
                        readBuffer.get(bytes)
                        incomingChannel.trySend(bytes)
                    }
                    readBuffer.clear()
                    if (!closed.get() && !inRequest.queue(readBuffer)) {
                        incomingChannel.close(IOException("UsbRequestTonexTransport: re-queue of IN request failed"))
                        break
                    }
                }
                completed === outRequest -> {
                    // No transferred-byte-count API exists for a completed UsbRequest -- see class
                    // KDoc's "no short-write detection" section. A successful completion is trusted
                    // to mean the whole buffer went out.
                    inFlightWrite?.completion?.complete(inFlightWrite?.sizeBytes ?: 0)
                    inFlightWrite = null
                }
                else -> Unit // a stale/cancelled request draining through close(); nothing to do
            }
        }

        runCatching { inRequest.cancel() }
        runCatching { outRequest.cancel() }
        runCatching { inRequest.close() }
        runCatching { outRequest.close() }
        inFlightWrite?.completion?.completeExceptionally(IOException("UsbRequestTonexTransport: transport closed with a write still in flight"))
        incomingChannel.close()
    }

    override suspend fun write(bytes: ByteArray): Int = withContext(Dispatchers.IO) {
        if (closed.get()) {
            throw IOException("UsbRequestTonexTransport.write: transport is closed")
        }
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
        val completion = CompletableDeferred<Int>()
        val offered = pendingWrites.trySend(PendingWrite(buffer, bytes.size, completion)).isSuccess
        if (!offered) {
            throw IOException("UsbRequestTonexTransport.write: could not enqueue write (transport closing)")
        }
        completion.await()
    }

    override fun incoming(): Flow<ByteArray> = incomingChannel.receiveAsFlow()

    /**
     * Stops [loopThread] and waits for it to actually exit before returning — see
     * [UsbTonexTransport.close]'s KDoc for why this join is load-bearing (the same physical
     * [connection] is reused by the probe's next reconnect cycle moments later) and identically
     * applicable here. [loopThread] never blocks longer than [LOOP_WAIT_TIMEOUT_MILLIS] per
     * `requestWait` call regardless of whether [Thread.interrupt] takes effect, so this bound
     * reliably completes well inside that budget. Does NOT close [connection] — same lifecycle
     * split as [UsbTonexTransport]; the probe Activity/session owns that.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        loopThread.interrupt()
        loopThread.join(LOOP_WAIT_TIMEOUT_MILLIS * 2)
    }

    companion object {
        private const val READ_BUFFER_SIZE: Int = 4096

        /**
         * Bounds each [UsbDeviceConnection.requestWait] call so [eventLoop] periodically re-checks
         * [closed] and [pendingWrites] even with nothing completing — the same role
         * [UsbTonexTransport]'s `READ_TIMEOUT_MILLIS` plays for its `bulkTransfer` poll loop. Not a
         * protocol timeout: a transfer that never completes within this window simply gets polled
         * again next iteration, it is not failed.
         */
        private const val LOOP_WAIT_TIMEOUT_MILLIS: Long = 500L
    }
}
