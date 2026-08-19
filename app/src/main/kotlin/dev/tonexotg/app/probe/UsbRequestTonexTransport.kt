package dev.tonexotg.app.probe

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import dev.tonexotg.protocol.TonexTransport
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * ## Why one dedicated thread drives `requestWait`, but [write] queues its own OUT request directly
 *
 * [UsbDeviceConnection.requestWait] is a single call that waits for **any** [UsbRequest] queued on
 * this [connection] (any endpoint) to complete, and returns whichever one did — there is no
 * per-endpoint variant. If a read-loop thread and this class's [write] caller each called
 * `requestWait` concurrently from different threads, either could receive the other's completed
 * request, silently misrouting a write completion as a read or vice versa — a real correctness
 * hazard, not a hypothetical one. So exactly one thread ([loopThread]) ever calls `requestWait` on
 * [connection]: it demuxes the returned request by identity ([inRequest] vs [outRequest], compared
 * with `===`) and completes whichever [PendingWrite] is currently in flight ([inFlightWrite]) when
 * [outRequest] comes back.
 *
 * [write] itself, however, calls [UsbRequest.queue] on [outRequest] directly from its own
 * `Dispatchers.IO` thread rather than handing the buffer to the loop to queue on its next
 * iteration — an earlier version routed writes through a channel the loop drained only at the top
 * of each `requestWait` cycle, which meant a write issued right after the loop entered its (up to
 * [LOOP_WAIT_TIMEOUT_MILLIS]-long) `requestWait` call sat unqueued until that call timed out: a
 * uniform 0-500ms artifact added to *every* write, dwarfing the very latency this class exists to
 * measure. Queuing directly from [write]'s thread is safe without additional locking against
 * [loopThread]'s concurrent `requestWait`: `UsbRequest.queue()` routes through
 * `UsbDeviceConnection.queueRequest()`, which synchronizes on the connection's internal lock, while
 * `requestWait()` deliberately does not take that same lock — confirmed against AOSP
 * `UsbDeviceConnection.java`. [writeMutex] instead serializes concurrent [write] callers against
 * each other (matching [TonexTransport]'s documented one-write-in-flight-at-a-time contract, kept
 * explicit here rather than assumed) and against [inFlightWrite] itself, which only [write] and
 * [loopThread] ever touch and which is never read by both at once thanks to that ordering.
 *
 * ## A known limitation: a cancelled write can rarely misattribute its completion to the next write
 *
 * [write]'s `CancellationException` handler cancels [outRequest] without waiting for [loopThread]
 * to reap the discarded URB. If a following [write] call takes [writeMutex] and sets a new
 * [PendingWrite] into [inFlightWrite] before that reap happens, [loopThread] completes the *new*
 * write's [CompletableDeferred] when it drains the *old*, cancelled URB — producing one spuriously
 * near-zero latency sample and reporting a write successful before its transfer actually completed.
 * Deliberately not closed with code: doing so properly needs per-URB identity matching (a
 * generation counter, since the completion API gives no token), which is the kind of elaborate
 * automatic-recovery machinery this project's CLAUDE.md says not to build for a diagnostic-only
 * class. The precondition is a cancelled write — i.e. `DefaultTonexController.writeFramed`'s own
 * transport-write timeout firing — which already makes that run's numbers suspect on its own. See
 * PR #60 review.
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

    /** Serializes concurrent [write] callers; also guards [inFlightWrite] — see class KDoc. */
    private val writeMutex = Mutex()

    /** The write currently queued on [outRequest], if any. Set by [write], cleared by [loopThread]. */
    private val inFlightWrite = AtomicReference<PendingWrite?>(null)

    private val inRequest = UsbRequest().apply {
        check(initialize(connection, inEndpoint)) { "UsbRequestTonexTransport: inRequest.initialize() failed" }
    }

    // Wrapped so a failure initializing outRequest doesn't leak the already-initialized inRequest's
    // native context -- see PR #60 review.
    private val outRequest = try {
        UsbRequest().apply {
            check(initialize(connection, outEndpoint)) { "UsbRequestTonexTransport: outRequest.initialize() failed" }
        }
    } catch (t: Throwable) {
        inRequest.close()
        throw t
    }

    private val loopThread: Thread

    init {
        loopThread = Thread({ eventLoop() }, "UsbRequestTonexTransport-loop").apply {
            isDaemon = true
            start()
        }
    }

    private class PendingWrite(val sizeBytes: Int, val completion: CompletableDeferred<Int>)

    /**
     * The single thread that ever calls [UsbDeviceConnection.requestWait] on [connection] — see
     * class KDoc for why that is load-bearing, not a style choice. Owns queuing and re-queuing
     * [inRequest] for the next read, and completing whichever [PendingWrite] [write] left in
     * [inFlightWrite] once [outRequest] comes back. Wrapped in `try`/`finally` so any unexpected
     * throw (including an initial IN-queue failure) still runs the cleanup below rather than
     * leaking native [UsbRequest] resources and leaving [incomingChannel] open forever.
     */
    private fun eventLoop() {
        val readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE)
        try {
            if (!inRequest.queue(readBuffer)) {
                incomingChannel.close(IOException("UsbRequestTonexTransport: initial IN queue() failed"))
                return
            }

            while (!closed.get()) {
                val completed = try {
                    connection.requestWait(LOOP_WAIT_TIMEOUT_MILLIS)
                } catch (t: TimeoutException) {
                    null // nothing completed within the poll window; loop around to re-check closed
                } catch (t: Throwable) {
                    if (closed.get()) break
                    incomingChannel.close(IOException("UsbRequestTonexTransport: requestWait failed", t))
                    inFlightWrite.getAndSet(null)?.completion?.completeExceptionally(t)
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
                        val pending = inFlightWrite.getAndSet(null)
                        pending?.completion?.complete(pending.sizeBytes)
                    }
                    else -> Unit // a stale/cancelled request draining through close(); nothing to do
                }
            }
        } finally {
            runCatching { inRequest.cancel() }
            runCatching { outRequest.cancel() }
            runCatching { inRequest.close() }
            runCatching { outRequest.close() }
            inFlightWrite.getAndSet(null)?.completion?.completeExceptionally(
                IOException("UsbRequestTonexTransport: transport closed with a write still in flight"),
            )
            incomingChannel.close()
        }
    }

    override suspend fun write(bytes: ByteArray): Int = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed.get()) {
                throw IOException("UsbRequestTonexTransport.write: transport is closed")
            }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }
            val completion = CompletableDeferred<Int>()
            inFlightWrite.set(PendingWrite(bytes.size, completion))
            // Safe to call from this (IO dispatcher) thread concurrently with loopThread's
            // requestWait -- see class KDoc's "why write queues its own OUT request directly".
            val queued = try {
                outRequest.queue(buffer)
            } catch (t: IllegalStateException) {
                // AOSP's UsbRequest.queue() throws ISE rather than returning false for two distinct
                // reasons: a close() racing this call ("invalid connection"), or -- reachable
                // because the CancellationException handler below cancels outRequest without
                // waiting for loopThread to reap it -- a still-queued prior request ("this request
                // is currently queued"). Passing t.message through (rather than asserting a single
                // cause) reports whichever one actually happened instead of always blaming a close
                // race. See PR #60 review.
                inFlightWrite.set(null)
                throw IOException("UsbRequestTonexTransport.write: OUT queue() rejected (${t.message})", t)
            }
            if (!queued) {
                inFlightWrite.set(null)
                throw IOException("UsbRequestTonexTransport.write: OUT queue() failed")
            }
            try {
                completion.await()
            } catch (c: CancellationException) {
                // A cancelled await -- realistically DefaultTonexController.writeFramed's own
                // transportWriteMillis timeout -- must not leave outRequest permanently queued: the
                // next write() would otherwise hit AOSP's "this request is currently queued"
                // IllegalStateException and wedge the transport for the rest of the run. Cancel the
                // in-flight request and clear inFlightWrite so the transport recovers. See PR #60
                // review.
                outRequest.cancel()
                inFlightWrite.set(null)
                throw c
            }
        }
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
         * [closed] even with nothing completing — the same role
         * [UsbTonexTransport]'s `READ_TIMEOUT_MILLIS` plays for its `bulkTransfer` poll loop. Not a
         * protocol timeout: a transfer that never completes within this window simply gets polled
         * again next iteration, it is not failed.
         */
        private const val LOOP_WAIT_TIMEOUT_MILLIS: Long = 500L
    }
}
