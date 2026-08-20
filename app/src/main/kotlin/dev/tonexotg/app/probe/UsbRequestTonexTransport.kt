package dev.tonexotg.app.probe

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.util.Log
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
 *
 * ## Issue #27: a cancelled request's completion must be reaped before its [UsbRequest] is freed
 *
 * A real S22 probe run hit a native SIGSEGV (tombstone confirmed — see issue #27) inside
 * [UsbDeviceConnection.requestWait], caused by a use-after-free. [UsbRequest.cancel] only issues a
 * kernel `USBDEVFS_DISCARDURB` — it does not synchronously wait for that URB's completion to be
 * reaped via [UsbDeviceConnection.requestWait]/`REAPURB`. The original [eventLoop] `finally` block
 * called `.cancel()` then immediately `.close()` on both [inRequest] and [outRequest], freeing their
 * native structs regardless of whether the kernel still held a pending completion for either one.
 * `ProbeSession`'s sequential S22 drills construct a fresh [UsbRequestTonexTransport] on the *same*
 * [UsbDeviceConnection] for each drill; the next transport's own [loopThread] then calls
 * `requestWait` on that shared connection and can reap a stale completion still carrying a pointer
 * into the *previous* transport's already-freed [UsbRequest] — the use-after-free the tombstone
 * showed.
 *
 * The fix ([drainCancelledRequests]) lives entirely in this class's own `close`/[eventLoop] path, not
 * in `ProbeSession`: after cancelling both requests, [eventLoop]'s `finally` polls
 * [UsbDeviceConnection.requestWait] a small bounded number of times, discarding whatever it returns,
 * until every request that still had an outstanding URB ([inRequest], [outRequest], or neither —
 * the class tracks which) has been observed completing, or the poll budget runs out — *before*
 * either is closed. This keeps the hazard fully self-contained: any caller that
 * reuses the same [UsbDeviceConnection] for a subsequent transport (as `ProbeSession` does) is
 * protected without needing to know this class's internals. See [drainCancelledRequests]'s own KDoc
 * for why the drain proceeds to close anyway, loudly logged, if the budget is exhausted without
 * confirming both.
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

    /**
     * Whether [outRequest] may still have an un-reaped URB in the kernel — i.e. whether
     * [drainCancelledRequests] actually has to wait for it (issue #27). Set by [write] on every
     * successful [UsbRequest.queue]; cleared **only** by [loopThread] when it observes [outRequest]
     * coming back from [UsbDeviceConnection.requestWait].
     *
     * Deliberately NOT derived from [inFlightWrite]: [write]'s `CancellationException` handler
     * calls `outRequest.cancel()` and then clears [inFlightWrite] *without* waiting for the reap,
     * so `inFlightWrite == null` does not imply "no URB outstanding" — that is precisely the case
     * the drain must still cover. This flag tracks kernel-side URB ownership; [inFlightWrite]
     * tracks the caller-visible write, and the two intentionally diverge on the cancel path.
     */
    private val outMaybeQueued = AtomicBoolean(false)

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
        // Whether inRequest currently has an un-reaped URB in the kernel. Only this thread ever
        // queues or reaps inRequest, so a plain local is sufficient (no atomic needed) -- unlike
        // [outMaybeQueued], which write() sets from its own thread. Read by the finally block below
        // to decide whether the drain actually has to wait for inRequest at all (issue #27).
        var inMaybeQueued = false
        try {
            if (!inRequest.queue(readBuffer)) {
                incomingChannel.close(IOException("UsbRequestTonexTransport: initial IN queue() failed"))
                return
            }
            inMaybeQueued = true

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
                        inMaybeQueued = false // its URB has now been reaped by this requestWait
                        val n = readBuffer.position()
                        if (n > 0) {
                            readBuffer.flip()
                            val bytes = ByteArray(n)
                            readBuffer.get(bytes)
                            incomingChannel.trySend(bytes)
                        }
                        readBuffer.clear()
                        if (!closed.get()) {
                            if (!inRequest.queue(readBuffer)) {
                                incomingChannel.close(
                                    IOException("UsbRequestTonexTransport: re-queue of IN request failed"),
                                )
                                break
                            }
                            inMaybeQueued = true
                        }
                    }
                    completed === outRequest -> {
                        outMaybeQueued.set(false) // its URB has now been reaped by this requestWait
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
            // Must run BEFORE close() below -- see class KDoc's "Issue #27" section and this
            // function's own KDoc. Cancelling does not synchronously reap the kernel's completion;
            // closing before that reap is what produced the confirmed UAF tombstone. Only the
            // requests that actually still have an outstanding URB are awaited, so the common
            // teardown (no write in flight) neither burns the poll budget nor logs a false alarm.
            drainCancelledRequests(awaitIn = inMaybeQueued, awaitOut = outMaybeQueued.get())
            runCatching { inRequest.close() }
            runCatching { outRequest.close() }
            inFlightWrite.getAndSet(null)?.completion?.completeExceptionally(
                IOException("UsbRequestTonexTransport: transport closed with a write still in flight"),
            )
            incomingChannel.close()
        }
    }

    /**
     * Drains any kernel-level completion still pending for [inRequest]/[outRequest] after they were
     * just cancelled, so [eventLoop]'s `finally` does not free either request's native struct while a
     * completion for it might still be reaped later by a *different* [UsbRequestTonexTransport]'s
     * [loopThread] sharing the same [connection] (issue #27's confirmed use-after-free — see class
     * KDoc).
     *
     * [awaitIn]/[awaitOut] say which requests actually still have an un-reaped URB in the kernel —
     * `inMaybeQueued` (a [eventLoop]-local, since only [loopThread] queues or reaps [inRequest]) and
     * [outMaybeQueued] respectively. Only those are waited for. This matters: [outRequest] is queued
     * only while a [write] is in flight, so on the large majority of `close()` calls there is no OUT
     * URB to reap at all, and an unconditional "wait for both" would (a) burn the whole poll budget
     * every teardown and (b) fire the [Log.e] below every teardown — destroying the diagnostic value
     * of the one line whose entire job is to be the canary for a recurrence of issue #27's crash. It
     * is also common for *neither* to be outstanding (a read that completes on the same iteration
     * `closed` flips is not re-queued), in which case this function polls zero times and returns
     * immediately.
     *
     * Polls [UsbDeviceConnection.requestWait] up to [DRAIN_MAX_POLLS] times with a short
     * [DRAIN_POLL_TIMEOUT_MILLIS] bound each, discarding whatever request identity comes back, until
     * every awaited request has been observed completing. Deliberately bounded rather
     * than looped until both are confirmed: this class's own house rule (this project's CLAUDE.md,
     * "fail fast and loud") is reject-and-explain, not an unbounded wait that could itself hang
     * [close] -- and [close] already promises callers ([UsbTonexTransport]'s KDoc, referenced from
     * this class's own [close]) that it returns within a bounded window because the very next thing a
     * caller like `ProbeSession` does is reuse the same [connection]. A cancelled URB's completion is
     * a near-instant kernel-side event (not a network round trip), so [DRAIN_MAX_POLLS] short polls is
     * a generous budget for it to surface if it is ever going to.
     *
     * An *awaited* request not being drained by the time the budget runs out is exactly the
     * hazardous case: a completion still sitting in the kernel's queue that a later
     * [UsbRequestTonexTransport] on this same [connection] could reap after the native struct is
     * freed. Rather than silently hoping for the best, or leaking the native struct forever (this
     * class is reconstructed once per probe drill -- see `ProbeSession` -- so an unbounded leak here
     * would accumulate across a session), this function logs loudly via [Log.e] and proceeds to
     * close anyway, so a real occurrence is visible in logcat/the probe log's real-time file sink
     * (issue #69). Because only genuinely-outstanding URBs are awaited, that line firing is a real
     * signal, not routine noise.
     *
     * Known residual gap (pre-existing, not introduced here): a [write] racing `close()` can queue
     * [outRequest] after [awaitOut] was sampled, in which case its URB is not awaited. That window
     * is the same close-vs-write race [write] already documents; closing it needs the same
     * per-URB/generation machinery the class KDoc declines to build for a diagnostic-only class.
     * See issue #27.
     */
    private fun drainCancelledRequests(awaitIn: Boolean, awaitOut: Boolean) {
        var inDrained = !awaitIn
        var outDrained = !awaitOut
        var pollsUsed = 0
        while (pollsUsed < DRAIN_MAX_POLLS && !(inDrained && outDrained)) {
            pollsUsed++
            val completed = try {
                connection.requestWait(DRAIN_POLL_TIMEOUT_MILLIS)
            } catch (t: TimeoutException) {
                null // nothing completed within this poll window; try again if polls remain
            } catch (t: Throwable) {
                // The connection is very likely already invalid (e.g. the device was detached
                // concurrently) -- nothing further can be drained through it. Stop polling rather
                // than spin through the remaining budget on a connection that cannot answer.
                Log.w(
                    TAG,
                    "UsbRequestTonexTransport: drain aborted after $pollsUsed poll(s), " +
                        "requestWait threw (${t.javaClass.simpleName}: ${t.message}) -- connection " +
                        "likely already invalid; closing UsbRequest(s) anyway",
                )
                return
            }
            // Identity comparison (===), not structural -- matching every other demux in this
            // file (see class KDoc's "one dedicated thread" section). UsbRequest doesn't override
            // equals() so `==` would behave the same today, but this function's whole job is
            // identity matching and should say so explicitly rather than rely on that coincidence.
            when {
                completed === inRequest -> inDrained = true
                completed === outRequest -> outDrained = true
                else -> Unit // a completion for some other/stale request; not ours to track
            }
        }
        if (!inDrained || !outDrained) {
            Log.e(
                TAG,
                "UsbRequestTonexTransport: drain incomplete after $pollsUsed poll(s) " +
                    "(awaitIn=$awaitIn, inDrained=$inDrained, awaitOut=$awaitOut, " +
                    "outDrained=$outDrained) -- closing native UsbRequest(s) " +
                    "anyway. A cancelled request's completion may still be pending in the kernel and " +
                    "could later be reaped by a different UsbRequestTonexTransport sharing this " +
                    "UsbDeviceConnection, dereferencing this now-freed UsbRequest (issue #27). If this " +
                    "fires and is followed by a native crash, that is the mechanism -- see issue #27.",
            )
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
            // The kernel now owns a URB for outRequest. Cleared only by loopThread when it reaps it
            // -- NOT on the cancellation path below, which cancels without waiting for the reap.
            // See outMaybeQueued's KDoc and drainCancelledRequests (issue #27).
            outMaybeQueued.set(true)
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
     * `requestWait` call regardless of whether [Thread.interrupt] takes effect, plus
     * [drainCancelledRequests]'s own small bounded budget ([DRAIN_MAX_POLLS] *
     * [DRAIN_POLL_TIMEOUT_MILLIS], issue #27) that now runs in [eventLoop]'s `finally` before the
     * requests are closed, so this join bound reliably completes well inside that combined budget.
     * Does NOT close [connection] — same lifecycle split as [UsbTonexTransport]; the probe
     * Activity/session owns that.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        loopThread.interrupt()
        loopThread.join(LOOP_WAIT_TIMEOUT_MILLIS * 2)
    }

    companion object {
        private const val TAG = "UsbRequestTonexTransport"

        private const val READ_BUFFER_SIZE: Int = 4096

        /**
         * Bounds each [UsbDeviceConnection.requestWait] call so [eventLoop] periodically re-checks
         * [closed] even with nothing completing — the same role
         * [UsbTonexTransport]'s `READ_TIMEOUT_MILLIS` plays for its `bulkTransfer` poll loop. Not a
         * protocol timeout: a transfer that never completes within this window simply gets polled
         * again next iteration, it is not failed.
         */
        private const val LOOP_WAIT_TIMEOUT_MILLIS: Long = 500L

        /**
         * Per-poll timeout used by [drainCancelledRequests] while draining a just-cancelled request's
         * completion -- see issue #27. Deliberately much shorter than [LOOP_WAIT_TIMEOUT_MILLIS]: a
         * cancelled URB's completion, if it is coming at all, is a near-instant kernel-side event, not
         * something worth a full poll cycle to wait for, and [close] needs this whole drain to stay
         * well inside its own documented bound.
         */
        private const val DRAIN_POLL_TIMEOUT_MILLIS: Long = 50L

        /**
         * Maximum number of [DRAIN_POLL_TIMEOUT_MILLIS]-bounded polls [drainCancelledRequests] will
         * issue per [close] before giving up and closing anyway (loudly logged) -- see issue #27. Not
         * unbounded, matching this project's "fail fast and loud" house philosophy over an
         * open-ended wait; 3 polls at 50ms is a generous 150ms budget for a same-kernel completion
         * that should surface in microseconds if it is going to.
         */
        private const val DRAIN_MAX_POLLS: Int = 3
    }
}
