package dev.tonexotg.app.usb

/**
 * The exact set of blocking USB primitives [TonexUsbTransport] calls, abstracted behind an
 * interface so its threading, watchdog, and cancellation-safety logic can be exercised with a
 * deterministic fake in a pure-JVM test.
 *
 * This exists because Robolectric's `ShadowUsbDeviceConnection`/`ShadowUsbRequest` (4.16, read
 * from source this session, not assumed) cannot simulate several of the exact failure modes S11's
 * acceptance criteria require a test for:
 * - `UsbDeviceConnection.requestWait(timeout)`'s shadow ignores the timeout argument entirely and
 *   delegates to the never-times-out no-arg form, so a wedge can never be simulated through it.
 * - `UsbRequest.queue(buffer)`'s shadow blocks until it has read exactly `buffer.remaining()`
 *   bytes from a `PipedInputStream` -- no partial-read/short-read simulation.
 * - `bulkTransfer`/`controlTransfer` shadows always report success and the full requested length.
 *
 * A pure-JVM fake behind this seam sidesteps all three. Robolectric is still used (see
 * `TonexUsbTransportRobolectricTest`) for a happy-path smoke test against the real platform
 * shadow, which is what actually pins "this compiles and runs against the real Android USB
 * classes, not just an interface I made up" -- but it cannot carry the watchdog/short-write
 * acceptance criteria on its own, hence this seam.
 *
 * Keep this interface's surface exactly as small as [TonexUsbTransport] actually needs -- it is
 * not a general-purpose USB abstraction and must not grow into one.
 */
internal interface UsbIoPort {

    /**
     * (Re-)queues the single persistent IN read request. `false` means queueing failed
     * synchronously (e.g. the connection is already closed); the caller treats that as fatal.
     */
    fun queueRead(): Boolean

    /**
     * Blocks up to [timeoutMillis] ms for the queued IN request to complete.
     *
     * Returns [ReadOutcome.TimedOut] if nothing completed within the window -- not an error, the
     * caller loops and tries again (this is also how the reader loop periodically re-checks
     * whether it should stop). Returns [ReadOutcome.Completed] with whatever bytes arrived
     * (possibly empty) otherwise. Throws on a real platform I/O error, including the connection
     * having been closed out from under a blocked call.
     *
     * [timeoutMillis] must never be `0`: unlike [writeBulk] (where `0` means "block forever"),
     * `UsbDeviceConnection.requestWait`'s own javadoc defines `0` as "do not wait at all" -- the
     * two platform APIs this class wraps give the same literal opposite meanings to the same
     * value. See [TonexUsbTransport]'s companion object, which names its timeout constants by
     * which API they feed rather than reusing one number for both.
     */
    fun requestWait(timeoutMillis: Long): ReadOutcome

    /**
     * Cancels the in-flight IN request, if any, causing a blocked [requestWait] elsewhere to
     * return rather than stay parked. Never throws. This -- not [releaseAndClose] -- is the
     * actual escape hatch for a wedged reader; see [TonexUsbTransport]'s watchdog KDoc for why
     * closing the connection out from under a still-blocked native call is not safe to do
     * instead.
     */
    fun cancelRead()

    /**
     * Synchronous bulk OUT transfer, bounded by [timeoutMillis] ms. Returns the platform's raw
     * return value: the actual byte count transferred on success (possibly less than
     * `bytes.size` -- a legitimate short write, see [dev.tonexotg.protocol.TonexTransport.write]'s
     * KDoc) or a negative value on failure. Never throws for an ordinary transfer failure --
     * [TonexUsbTransport] inspects the sign itself so the failed `rc` ends up in the thrown
     * message.
     *
     * [timeoutMillis] must never be `0`: `UsbDeviceConnection.bulkTransfer`'s javadoc defines `0`
     * as "block forever," the opposite of what `0` means to [requestWait] above.
     */
    fun writeBulk(bytes: ByteArray, timeoutMillis: Int): Int

    /**
     * Releases both claimed interfaces (Data and Communications) and closes the connection.
     * Idempotent; never throws.
     *
     * MUST only be called once every thread that could still be inside a blocking call on this
     * connection ([requestWait] or [writeBulk]) has been confirmed to have actually returned
     * (joined, not just interrupted) -- see [TonexUsbTransport]'s watchdog KDoc. Closing while
     * either call is still in flight on another thread is a native use-after-free, not a graceful
     * cancellation.
     */
    fun releaseAndClose()
}

/** The result of one [UsbIoPort.requestWait] call. */
internal sealed interface ReadOutcome {
    /** Nothing completed within the requested window. Not an error. */
    data object TimedOut : ReadOutcome

    /** The IN request completed with [bytes] (possibly empty). */
    data class Completed(val bytes: ByteArray) : ReadOutcome
}
