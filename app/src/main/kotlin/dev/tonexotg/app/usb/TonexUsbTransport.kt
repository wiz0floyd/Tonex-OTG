package dev.tonexotg.app.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import dev.tonexotg.protocol.TonexTransport
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production [dev.tonexotg.protocol.TonexTransport] backed by [UsbDeviceConnection] (S11, issue
 * #16). Pure I/O: no protocol/framing logic lives here -- that stays in `:protocol`, per the
 * interface's own contract.
 *
 * ## Why this differs from the S20 probe harness's diagnostic transports
 * `dev.tonexotg.app.probe.UsbTonexTransport` (plain `bulkTransfer` loop) and
 * `dev.tonexotg.app.probe.UsbRequestTonexTransport` (`UsbRequest`/`requestWait`, both directions)
 * are explicitly marked "do not promote to production as-is" -- see their KDocs for the full
 * reasoning. This class reuses the *lessons*, not the code:
 * - Reads use `UsbRequest.queue()`/`UsbDeviceConnection.requestWait(timeout)` on a dedicated
 *   thread ([readerThread]), per issue #16's own recommendation and to sidestep AOSP bug 39522
 *   (the no-arg `requestWait()` blocks forever and is uninterruptible).
 * - Writes use `UsbDeviceConnection.bulkTransfer` instead of a second `UsbRequest` -- see
 *   [AndroidUsbIoPort]'s KDoc for why: it removes the identity-demux hazard
 *   `UsbRequestTonexTransport` had to accept, and it's the only way to get an honest
 *   short-write count back from the platform.
 * - Writes run on their own dedicated thread ([writerThread]), not `Dispatchers.IO`: `bulkTransfer`
 *   is a blocking JNI call coroutine cancellation cannot interrupt, and CLAUDE.md's own house
 *   philosophy (fail fast and loud) argues against parking that inside a shared dispatcher pool.
 * - A real watchdog ([watchdogThread]) force-closes the connection if [readerThread] stops making
 *   progress -- explicitly missing from both probe classes and a stated S11 acceptance criterion.
 * - [close] actually releases the connection and both claimed interfaces (Data and
 *   Communications). Both probe classes deliberately do *not* do this (their KDocs explain why,
 *   for their own throwaway-harness lifecycle) -- that lifecycle split does not belong in
 *   production, where this class is the sole owner of the connection it was handed.
 * - A cancelled [write] cannot misattribute its completion to a later write. `UsbRequestTonexTransport`
 *   has a documented, deliberately-unfixed race where a cancelled write's discarded URB can be
 *   reaped by [writerThread]'s next iteration and complete the *next* write's continuation instead
 *   -- acceptable for a throwaway probe, not for production. This class's [writerLoop] processes
 *   exactly one [WriteJob] end-to-end per iteration (there is no shared "current in-flight write"
 *   state a stale completion could stomp), so that race class does not exist here by construction.
 *
 * ### Threading and lifecycle
 * Three dedicated daemon threads, plus whatever coroutine dispatcher callers use for [write]:
 * - [readerThread] owns every call to `UsbDeviceConnection.requestWait` and re-queuing the single
 *   persistent IN [android.hardware.usb.UsbRequest]. Nothing else touches it.
 * - [writerThread] owns every call to `bulkTransfer` for the OUT endpoint, taking jobs one at a
 *   time off [writeQueue]. [write] itself never blocks in a JNI call -- it hands off a
 *   [WriteJob] and suspends on a [CancellableContinuation] that [writerThread] resumes.
 * - [watchdogThread] periodically checks [lastReaderHeartbeatNanos]; if [readerThread] hasn't
 *   completed a `requestWait` cycle within [watchdogTimeoutMillis], it force-closes (see
 *   [teardown]'s KDoc for why the *order* of that matters). **This only covers [readerThread] --
 *   a wedged [writerThread] is NOT detected here.** A stuck `bulkTransfer` call currently only
 *   surfaces as [write] hanging until the caller's own timeout/cancellation; per-write recovery
 *   (the `doneLatch` wait described under [write]) keeps that from corrupting a *later* write's
 *   result, but nothing here force-closes the connection on the writer's behalf the way the
 *   watchdog does for the reader. Flagged as a known gap (issue #16 / #25), not fixed in this
 *   pass -- `bulkTransfer` is already OS-timeout-bounded, so a genuinely stuck call would itself
 *   indicate a platform/driver bug rather than the routine "wedge" the reader-side watchdog
 *   exists for.
 *
 * [write] is serialized by [writeMutex] (the interface's own contract: not required to be safe to
 * call concurrently with itself). That mutex is held for the full round trip -- including the
 * suspend while [writerThread] performs the transfer -- so at most one [WriteJob] is ever queued
 * or in flight at a time.
 *
 * ### A hazard this class does NOT paper over: untested bulk OUT transfers above ~64 bytes
 * Per issue #25, every write exercised against real hardware so far has been <= 21 bytes. The OUT
 * endpoint's descriptor declares a 512-byte `wMaxPacketSize` on a device that does not actually
 * operate high-speed (issue #16's "malformed endpoint descriptors" hazard). [write] does **not**
 * chunk or otherwise second-guess an oversized payload -- that would be protocol-shaped policy,
 * which does not belong in this class (framing/chunking stays in `:protocol`). The realistic
 * failure mode for a single bulk OUT transfer larger than the endpoint can actually carry is a
 * negative `bulkTransfer` return code (surfaced verbatim in the thrown [IOException]'s message),
 * not a tidy short-write count -- `USBDEVFS_BULK` segments against the endpoint's *declared* max
 * packet size, which is exactly the value already known to be wrong on this device. See [write]'s
 * KDoc.
 */
class TonexUsbTransport internal constructor(
    private val io: UsbIoPort,
    private val watchdogTimeoutMillis: Long,
    private val watchdogPollIntervalMillis: Long,
) : TonexTransport {

    /** Production entry point: wraps a real, already-open, already-claimed USB connection. */
    constructor(
        connection: UsbDeviceConnection,
        dataInterface: UsbInterface,
        commInterface: UsbInterface,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
        watchdogTimeoutMillis: Long = DEFAULT_WATCHDOG_TIMEOUT_MILLIS,
        watchdogPollIntervalMillis: Long = DEFAULT_WATCHDOG_POLL_INTERVAL_MILLIS,
    ) : this(
        AndroidUsbIoPort(connection, dataInterface, commInterface, inEndpoint, outEndpoint),
        watchdogTimeoutMillis,
        watchdogPollIntervalMillis,
    )

    private val closed = AtomicBoolean(false)

    /** Set once, on the thread that wins [teardown]'s CAS; read by [write] to explain a rejection. */
    private val closeReason = AtomicReference("TonexUsbTransport: transport is closed")

    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private val writeQueue = SynchronousQueue<WriteJob>()

    /** Updated by [readerThread] every time a `requestWait` cycle completes (timeout or not). */
    private val lastReaderHeartbeatNanos = AtomicLong(System.nanoTime())

    private val readerThread = Thread({ readerLoop() }, "TonexUsbTransport-reader").apply { isDaemon = true }
    private val writerThread = Thread({ writerLoop() }, "TonexUsbTransport-writer").apply { isDaemon = true }
    private val watchdogThread = Thread({ watchdogLoop() }, "TonexUsbTransport-watchdog").apply { isDaemon = true }

    init {
        // A healthy reader's own per-iteration timeout (READER_POLL_TIMEOUT_MILLIS) must be
        // comfortably shorter than watchdogTimeoutMillis, or the watchdog cannot tell "reader is
        // fine, just between iterations" from "reader is wedged" -- it force-closes on every
        // single normal cycle regardless of anything being wrong. Found during PR #62 review: a
        // test helper's own too-low default did exactly this, and the resulting "wedge" tests
        // passed even with their wedge-simulation line deleted, because the watchdog fired
        // unconditionally either way. This constructor is the one place that can catch a
        // regression of that shape for good, for every caller including tests.
        require(watchdogTimeoutMillis > READER_POLL_TIMEOUT_MILLIS * 2) {
            "TonexUsbTransport: watchdogTimeoutMillis ($watchdogTimeoutMillis) must be more than " +
                "2x READER_POLL_TIMEOUT_MILLIS ($READER_POLL_TIMEOUT_MILLIS)ms, or a healthy " +
                "reader's own normal per-iteration timeout is indistinguishable from a wedge"
        }
        readerThread.start()
        writerThread.start()
        watchdogThread.start()
    }

    private class WriteJob(val bytes: ByteArray, val cont: CancellableContinuation<Int>) {
        /**
         * Counted down by [writerLoop] once it has fully finished processing this job (success or
         * failure) -- [write]'s `finally` waits on this, even across its own cancellation, so
         * [writeMutex] is never released to the next caller while [writerThread] might still be
         * touching [io] on this job's behalf. See [write]'s KDoc, "why write waits for the writer
         * thread to actually finish."
         */
        val doneLatch = CountDownLatch(1)
    }

    // ---------------------------------------------------------------------------------------
    // Reader
    // ---------------------------------------------------------------------------------------

    private fun readerLoop() {
        try {
            if (!io.queueRead()) {
                teardown(IOException("TonexUsbTransport: initial IN queue() failed"))
                return
            }
            while (!closed.get()) {
                val outcome = try {
                    io.requestWait(READER_POLL_TIMEOUT_MILLIS)
                } catch (t: Throwable) {
                    if (!closed.get()) teardown(IOException("TonexUsbTransport: requestWait failed", t))
                    return
                }
                lastReaderHeartbeatNanos.set(System.nanoTime())
                when (outcome) {
                    ReadOutcome.TimedOut -> Unit
                    is ReadOutcome.Completed -> {
                        if (outcome.bytes.isNotEmpty()) incomingChannel.trySend(outcome.bytes)
                        if (!closed.get() && !io.queueRead()) {
                            teardown(IOException("TonexUsbTransport: re-queue of IN request failed"))
                            return
                        }
                    }
                }
            }
        } finally {
            // Routes through teardown() -- NOT a bare incomingChannel.close() -- specifically so
            // this can never race ahead of a cause-carrying close from another thread. teardown()
            // joins readerThread before it closes the channel itself, which means readerLoop
            // returning (running this finally) always happens-before any *other* caller's
            // teardown() reaches its own incomingChannel.close(cause) -- a bare close() here would
            // therefore always win that race and silently swallow the real cause (a watchdog
            // force-close's message, for instance). Routing through teardown()'s own CAS instead
            // makes whichever call reaches teardown() FIRST -- not "whichever thread finishes
            // last" -- the one whose cause sticks; every later call (including this one, on the
            // common graceful-close path) is a correctly-suppressed no-op.
            teardown(null)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Writer
    // ---------------------------------------------------------------------------------------

    private fun writerLoop() {
        while (!closed.get()) {
            val job = try {
                writeQueue.poll(WRITER_IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } ?: continue
            try {
                val n = try {
                    io.writeBulk(job.bytes, WRITE_TIMEOUT_MILLIS)
                } catch (t: Throwable) {
                    job.cont.resumeWithException(IOException("TonexUsbTransport.write: unexpected failure", t))
                    null
                }
                if (n != null) {
                    if (n < 0) {
                        job.cont.resumeWithException(IOException(shortWriteFailureMessage(job.bytes.size, n)))
                    } else {
                        job.cont.resume(n)
                    }
                }
            } finally {
                // Always runs, on every exit from the block above -- this is the signal write()'s
                // finally waits on before it will let writeMutex release to the next caller. See
                // WriteJob.doneLatch's KDoc.
                job.doneLatch.countDown()
            }
        }
    }

    private fun shortWriteFailureMessage(requestedSize: Int, rc: Int): String {
        val base = "TonexUsbTransport.write: bulkTransfer OUT failed (rc=$rc, requested $requestedSize bytes)"
        return if (requestedSize > UNTESTED_WRITE_SIZE_THRESHOLD) {
            "$base -- no single bulk OUT transfer above $UNTESTED_WRITE_SIZE_THRESHOLD bytes has " +
                "been exercised against real hardware yet (issue #25: every write so far is <= 21 " +
                "bytes); the OUT endpoint declares a 512-byte wMaxPacketSize this device does not " +
                "actually support at full speed (issue #16), so a negative rc for an oversized " +
                "payload is the expected failure shape here, not a bug in this class -- see " +
                "TonexUsbTransport's class KDoc"
        } else {
            base
        }
    }

    // ---------------------------------------------------------------------------------------
    // TonexTransport
    // ---------------------------------------------------------------------------------------

    /**
     * See [dev.tonexotg.protocol.TonexTransport.write]'s KDoc for the general contract. This
     * implementation additionally: serializes callers via [writeMutex]; hands the transfer to
     * [writerThread] and suspends until it completes; and — deliberately — does not chunk, retry,
     * or otherwise second-guess an oversized [bytes] array. See this class's own KDoc, "A hazard
     * this class does NOT paper over."
     *
     * ### Why this waits for the writer thread to actually finish, even across cancellation
     * `bulkTransfer` cannot be interrupted early (see the `invokeOnCancellation` comment below),
     * so a cancelled `write()` call unwinds immediately while [writerThread] can legitimately
     * still be inside `io.writeBulk` for up to [WRITE_TIMEOUT_MILLIS] more. If [writeMutex] were
     * released as soon as this coroutine unwinds, the *next* `write()` call could reach its own
     * [writeQueue] handoff while the writer is still busy with the *previous*, cancelled job --
     * exactly the race PR #62's review found: the handoff would then legitimately time out well
     * before the writer frees up, and (before this fix) that timeout was reported as "transport is
     * closed" even though nothing had closed it. The `finally` below closes this by waiting
     * (bounded, and immune to this call's own cancellation via [NonCancellable] -- a cancelled
     * caller must not get to skip it) for [WriteJob.doneLatch], which only fires once
     * [writerLoop] has fully finished this exact job. That keeps the invariant [writeQueue]'s
     * bounded handoff was always meant to assume -- the writer is idle by the time a new job
     * arrives -- true again in the case that used to violate it.
     *
     * **Consequence a caller must know (found during PR #62 re-review, M4): cancelling this call
     * does not return control to the caller promptly.** A `kotlinx.coroutines` cancellation
     * (`withTimeout`, a parent job cancelling, etc.) still gets honored -- this call always
     * eventually throws [kotlinx.coroutines.CancellationException] -- but the calling coroutine
     * stays suspended for however long the `doneLatch` wait above takes, up to
     * [WRITE_JOIN_TIMEOUT_MILLIS] (~3s) in the worst case, not whatever shorter budget the caller
     * requested. This only bites on an already-degraded path (a write that was going to be
     * cancelled or time out anyway), never during ordinary operation, and nothing corrupts or
     * hangs indefinitely either way -- but it does mean
     * [dev.tonexotg.protocol.connection.ConnectionTimeouts.transportWriteMillis]'s budget (the
     * `withTimeout` `writeFramed` wraps this call in) bounds how long that caller *waits before
     * asking* to move on, not how long it actually takes to regain control on this transport. See
     * that constant's own KDoc for the same caveat from the `:protocol` side. Restructuring this
     * to wait on the *next* caller's entry instead of the cancelled caller's exit (staying
     * cancellable within that caller's own budget) is a viable follow-up; not done here per this
     * project's "don't over-build" guidance for a gap that only affects an already-failing call.
     */
    override suspend fun write(bytes: ByteArray): Int = writeMutex.withLock {
        if (closed.get()) throw IOException(closeReason.get())
        var job: WriteJob? = null
        try {
            suspendCancellableCoroutine { cont ->
                val newJob = WriteJob(bytes, cont)
                job = newJob
                // A bounded offer(), not put(): if teardown() concurrently wins the race
                // (interrupts and retires writerThread between our closed.get() check above and
                // this call), an unbounded put() would block forever waiting for a taker that
                // will never arrive -- writerThread only ever exits its loop, it never comes back.
                // Timing out and failing this write is the fail-fast-and-loud alternative to that
                // hang. Should be rare in practice now that the finally below keeps the writer
                // provably idle before any new job is offered -- see this method's KDoc.
                val accepted = try {
                    writeQueue.offer(newJob, WRITE_ENQUEUE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                if (!accepted) {
                    job = null // never queued -- writerLoop will never see it, nothing to wait for
                    cont.resumeWithException(IOException(enqueueTimeoutMessage()))
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    // bulkTransfer is a blocking JNI call; interrupting writerThread cannot
                    // unblock it early (same limitation the diagnostic UsbRequestTonexTransport
                    // documents). The transfer still runs to completion, bounded by
                    // WRITE_TIMEOUT_MILLIS, and its eventual resume()/resumeWithException() on an
                    // already-cancelled continuation is a documented safe no-op -- nothing else to
                    // do here. The finally below (not this callback) is what waits for it.
                }
            }
        } finally {
            // Skip the NonCancellable + Dispatchers.IO hop entirely when the latch is already
            // down (the overwhelmingly common case -- writerLoop's countDown() ordinarily beats
            // this finally here, since it runs right after the resume() this coroutine is already
            // resuming from). Measured ~176us/write of avoidable dispatcher round-trip otherwise;
            // this project measures exactly this kind of thing (S21), so it's worth the one line.
            job?.let { j ->
                if (j.doneLatch.count > 0L) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        j.doneLatch.await(WRITE_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
    }

    /**
     * The enqueue-timeout message for [write]'s `offer()` -- must check [closed] itself rather
     * than assume the timeout means closure. Before this fix (PR #62 review, B1) this path always
     * reported [closeReason]'s default "transport is closed" text unconditionally, which was
     * actively wrong on a transport that was still open (see [write]'s KDoc for the race that
     * produced it) -- a wrong diagnosis is worse than none, per CLAUDE.md's fail-loud philosophy.
     */
    private fun enqueueTimeoutMessage(): String = if (closed.get()) {
        closeReason.get()
    } else {
        "TonexUsbTransport.write: timed out queueing the write after " +
            "${WRITE_ENQUEUE_TIMEOUT_MILLIS}ms (the transport is NOT closed -- writerThread may " +
            "still be busy with a previous write, or genuinely stuck; see write()'s KDoc)"
    }

    override fun incoming(): Flow<ByteArray> = incomingChannel.receiveAsFlow()

    /**
     * See [dev.tonexotg.protocol.TonexTransport.close]'s KDoc for the general (idempotent,
     * must-not-throw) contract.
     *
     * **Blocking, not async, and can take a while:** in the worst case this synchronously blocks
     * for up to `READER_JOIN_TIMEOUT_MILLIS + WRITE_JOIN_TIMEOUT_MILLIS` (currently ~1.5s + 3s =
     * ~4.5s) while [teardown] waits out both threads' bounded joins. That is comfortably inside
     * Android's ANR window if called from the main thread. Callers should call this from a
     * background dispatcher/thread, the same way they would any other blocking teardown call --
     * this class does not do that hop itself because [dev.tonexotg.protocol.TonexTransport.close]
     * is declared non-`suspend`, so there is no coroutine context here to dispatch with.
     */
    override fun close() = teardown(null)

    // ---------------------------------------------------------------------------------------
    // Watchdog
    // ---------------------------------------------------------------------------------------

    private fun watchdogLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(watchdogPollIntervalMillis)
            } catch (e: InterruptedException) {
                return
            }
            if (closed.get()) return
            val staleNanos = System.nanoTime() - lastReaderHeartbeatNanos.get()
            if (staleNanos > TimeUnit.MILLISECONDS.toNanos(watchdogTimeoutMillis)) {
                teardown(
                    IOException(
                        "TonexUsbTransport: watchdog forced close -- reader made no progress for " +
                            "over ${watchdogTimeoutMillis}ms (last heartbeat " +
                            "${TimeUnit.NANOSECONDS.toMillis(staleNanos)}ms ago)",
                    ),
                )
                return
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------------------------

    /**
     * The single path every shutdown route ([close], the watchdog, and [readerLoop] noticing its
     * own fatal error) funnels through. Idempotent via [closed]'s compare-and-set: only the first
     * caller does real work.
     *
     * ### Why this does NOT simply call `io.releaseAndClose()` up front
     * `UsbDeviceConnection.close()` takes its internal lock, but `requestWait`/`bulkTransfer` do
     * not (see [AndroidUsbIoPort]'s KDoc) -- so calling it while [readerThread] or [writerThread]
     * is still genuinely blocked inside one of those native calls is a use-after-free: the native
     * `usb_device` struct gets freed while a parked thread still holds a raw pointer to it. This
     * is exactly the state the watchdog fires in, so getting the order wrong here would turn "the
     * reader wedged" into "the whole app segfaults and takes the diagnostic log down with it" --
     * strictly worse than the problem the watchdog exists to solve.
     *
     * Correct order, below: (1) [UsbIoPort.cancelRead] first -- this is the actual, safe escape
     * hatch for a blocked `requestWait` (`USBDEVFS_DISCARDURB` makes the pending URB complete with
     * an error and get reaped, so the call genuinely returns); (2) join both threads with a bound
     * generous enough that either call's own timeout parameter (`READER_POLL_TIMEOUT_MILLIS`,
     * `WRITE_TIMEOUT_MILLIS`) should always make the join succeed on its own; (3) only if both
     * joins actually succeeded, release the interfaces and close the connection.
     *
     * If a join times out anyway, that means a native call didn't honor its own timeout
     * parameter -- a platform/driver bug, not something this class should paper over by closing
     * into a thread that might still be running. Per CLAUDE.md's fail-fast-and-loud philosophy,
     * the deliberate choice is to leak the platform handle (recoverable by unplugging the pedal)
     * rather than risk a native crash (which is not recoverable and destroys the evidence of what
     * went wrong). [closeReason] and the closed [incomingChannel]'s cause both say so explicitly
     * rather than silently succeeding.
     */
    private fun teardown(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return

        io.cancelRead()

        // joinIfNotSelf() interrupts each thread itself -- an extra explicit interrupt() here
        // would be redundant (PR #62 review, L2).
        val readerJoined = joinIfNotSelf(readerThread, READER_JOIN_TIMEOUT_MILLIS)
        val writerJoined = joinIfNotSelf(writerThread, WRITE_JOIN_TIMEOUT_MILLIS)

        if (readerJoined && writerJoined) {
            // Also update closeReason on the success path, not just the leak branch below --
            // otherwise a watchdog-forced close delivers its specific cause to incoming()'s
            // collectors but a subsequent write() call only ever sees the generic default
            // "transport is closed" (PR #62 review, M2).
            cause?.let { closeReason.set(it.message ?: it.toString()) }
            io.releaseAndClose()
            incomingChannel.close(cause)
        } else {
            val leak = IOException(
                "TonexUsbTransport: reader or writer thread did not exit within its bounded join " +
                    "timeout (readerJoined=$readerJoined, writerJoined=$writerJoined) -- leaking " +
                    "the USB connection rather than risking a native use-after-free by closing " +
                    "into a thread that may still be inside a blocking call; see teardown()'s KDoc",
                cause,
            )
            closeReason.set(leak.message ?: leak.toString())
            incomingChannel.close(leak)
        }
    }

    /**
     * Joins [thread] within [timeoutMillis], returning whether it actually exited. Returns `true`
     * immediately without joining if called from [thread] itself (a self-join would deadlock) --
     * reachable when [readerLoop] tears itself down after its own fatal error, and correct: a
     * thread invoking this on itself is by definition not blocked inside a native call at that
     * moment.
     */
    private fun joinIfNotSelf(thread: Thread, timeoutMillis: Long): Boolean {
        if (Thread.currentThread() === thread) return true
        thread.interrupt()
        try {
            thread.join(timeoutMillis)
        } catch (e: InterruptedException) {
            // The thread calling teardown() (e.g. close()) was itself interrupted while waiting --
            // restore the flag and fall through to the isAlive check below rather than let this
            // propagate. TonexTransport.close()'s contract says "must not throw."
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    companion object {
        /**
         * Bounds each `requestWait` call so [readerLoop] periodically re-checks [closed] even
         * with nothing arriving. Not a protocol timeout -- a read that doesn't complete within
         * this window is simply polled again, not failed. Must never be `0`: see [UsbIoPort]'s
         * KDoc for why `requestWait`'s `0` ("do not wait") and `bulkTransfer`'s `0` ("wait
         * forever") are opposite meanings of the same literal.
         */
        const val READER_POLL_TIMEOUT_MILLIS: Long = 500L

        /** Bounds each `bulkTransfer` OUT call. Must never be `0` -- see [READER_POLL_TIMEOUT_MILLIS]'s KDoc. */
        const val WRITE_TIMEOUT_MILLIS: Int = 2000

        /** How long [writerThread] idles on [writeQueue] between jobs before re-checking [closed]. */
        private const val WRITER_IDLE_POLL_MILLIS: Long = 500L

        /**
         * Bounds [write]'s [writeQueue] handoff -- see [write]'s "bounded offer(), not put()"
         * comment for why an unbounded wait here would be a real hang, not just a slow path.
         */
        private const val WRITE_ENQUEUE_TIMEOUT_MILLIS: Long = 1_000L

        /** Generous relative to [READER_POLL_TIMEOUT_MILLIS] -- see [teardown]'s KDoc. */
        private const val READER_JOIN_TIMEOUT_MILLIS: Long = READER_POLL_TIMEOUT_MILLIS * 3

        /** Generous relative to [WRITE_TIMEOUT_MILLIS] -- see [teardown]'s KDoc. */
        private const val WRITE_JOIN_TIMEOUT_MILLIS: Long = WRITE_TIMEOUT_MILLIS + 1000L

        /** Above this size, [shortWriteFailureMessage] calls out the untested-on-hardware hazard. */
        private const val UNTESTED_WRITE_SIZE_THRESHOLD: Int = 64

        /**
         * How long [readerThread] may go without completing a `requestWait` cycle before the
         * watchdog force-closes. Several multiples of [READER_POLL_TIMEOUT_MILLIS] so ordinary
         * scheduling jitter never trips it -- this is "the reader is wedged," not a latency SLA
         * (this project explicitly does not target gig-grade latency guarantees; see CLAUDE.md).
         */
        const val DEFAULT_WATCHDOG_TIMEOUT_MILLIS: Long = 5_000L

        /** How often the watchdog checks [lastReaderHeartbeatNanos]. */
        const val DEFAULT_WATCHDOG_POLL_INTERVAL_MILLIS: Long = 1_000L
    }
}
