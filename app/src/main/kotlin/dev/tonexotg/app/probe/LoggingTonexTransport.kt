package dev.tonexotg.app.probe

import dev.tonexotg.protocol.TonexTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * ⚠️ DIAGNOSTIC-ONLY. Wraps a real [TonexTransport] and mirrors every byte written to, and read
 * from, the pedal into [log] as a hex dump, tagged with [tag].
 *
 * Exists because a protocol-layer decode failure (e.g. [dev.tonexotg.protocol.TonexError.UnexpectedBlobShape]
 * or [dev.tonexotg.protocol.TonexError.MalformedFrame]) only surfaces the error's own summary —
 * not the bytes that triggered it — so there is currently no way to tell whether a given
 * decode failure is a real firmware/protocol difference or a transport-level data loss without
 * capturing the raw wire traffic directly. See issue #25's `UnexpectedBlobShape` finding, which
 * this wrapper exists to get raw bytes for. [dev.tonexotg.protocol.TonexError.UnexpectedBlobShape]'s
 * `context` field (added for the same "which call site, from the log alone" reason) narrows
 * which of several structurally-similar checks actually failed once this wrapper's captured
 * bytes and that field are read together.
 *
 * [quiet], when `true`, skips both the hex-dump string building and the [ProbeLog] append entirely
 * — for S21 (issue #26) latency measurements, where those are systematic, monotonically-increasing
 * overhead sitting inside the very window being timed (`ProbeLog.append`'s list copy grows with
 * every prior entry). See issue #26's adversarial review, finding S4.
 */
class LoggingTonexTransport(
    private val delegate: TonexTransport,
    private val log: ProbeLog,
    private val tag: String,
) : TonexTransport {

    /**
     * Mutable rather than a constructor-only flag: callers toggle this around just the specific
     * calls whose overhead must not be measured (e.g. [ProbeSession.runLatencyMeasurements]'s
     * timed calls), while still getting normal hex-dump logging for the surrounding
     * connect/disconnect traffic, which is exactly what a human would want to see if a measurement
     * run fails to connect at all. Suppresses BOTH [write] and [incoming]'s logging — the flow
     * side matters just as much as writes, since a read landing during a "quiet" window still goes
     * through the same [ProbeLog.append] the flag exists to keep out of the measured window.
     *
     * `@Volatile`: written from the toggling caller's thread/coroutine context and read from
     * [write]'s caller and separately from [incoming]'s long-lived `onEach` collector, with no
     * other synchronization between those sites. A stale read wouldn't corrupt anything, but it
     * would silently let a hex-dump append slip back into a timed window — exactly the bias this
     * flag exists to eliminate — so this is made correct by construction rather than "usually
     * correct" via incidental dispatcher-handoff publication. See PR #60 review.
     */
    @Volatile
    var quiet: Boolean = false

    override suspend fun write(bytes: ByteArray): Int {
        if (!quiet) {
            log.info("[$tag] WRITE ${bytes.size} byte(s):\n${UsbDeviceOpener.hexDump(bytes)}")
        }
        return delegate.write(bytes)
    }

    override fun incoming(): Flow<ByteArray> = delegate.incoming().onEach { bytes ->
        if (!quiet) {
            log.info("[$tag] READ ${bytes.size} byte(s):\n${UsbDeviceOpener.hexDump(bytes)}")
        }
    }

    override fun close() = delegate.close()
}
