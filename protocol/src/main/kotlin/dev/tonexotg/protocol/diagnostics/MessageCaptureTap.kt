package dev.tonexotg.protocol.diagnostics

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.TonexTransport
import dev.tonexotg.protocol.codec.MessageHeaderCodec
import dev.tonexotg.protocol.framing.FrameReassembler
import dev.tonexotg.protocol.message.TonexMessage
import dev.tonexotg.protocol.message.TonexMessageDecoder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ⚠️ DIAGNOSTIC-ONLY (issue #27, S22). Wraps a [TonexTransport] and independently decodes every
 * inbound message into a [TonexMessage], without altering what is forwarded to whoever actually
 * owns this transport (a [dev.tonexotg.protocol.connection.DefaultTonexController], typically).
 *
 * ## Why this exists — the read-only side channel S22's safety drills need
 * [dev.tonexotg.protocol.connection.DefaultTonexController] deliberately never exposes a raw state
 * blob, a raw preset-details payload, or anything else that could be replayed as a write — see
 * [dev.tonexotg.protocol.PedalState]'s KDoc: *"does not expose [PedalState] or [SessionId] at
 * all."* That opacity is exactly what stops a caller from reconstructing a write payload from a
 * read, and this class does not weaken it: every function in [SafetyDrill] that uses this tap only
 * ever *reads* the bytes it captures (to hex-dump them for a backup, or to byte-diff two captures
 * against each other) — nothing here, or in [SafetyDrill], ever hands a captured payload to
 * anything that could turn it into a write. Every actual write in a safety drill goes through
 * [dev.tonexotg.protocol.TonexController]'s own `selectPreset`/`setParameter`/`revertActivePreset`,
 * exactly as production code would.
 *
 * ## How it observes without consuming
 * [incoming] is a plain [Flow.onEach] decorator over [delegate]'s own flow — the same pattern this
 * project already uses for hex-dump logging (see `LoggingTonexTransport` in `:app`). Whoever
 * collects the flow returned by [incoming] (normally the real controller's reader loop) still sees
 * every byte unmodified; this class merely feeds a *second*, independent [FrameReassembler] off
 * the same stream of chunks to reconstruct [TonexMessage]s for its own bookkeeping. Two
 * reassemblers decoding the same byte stream independently produce identical frames — this does
 * not race with, or interfere with, the controller's own reader.
 *
 * ## Buffering: unlimited, drain before every request
 * Captured messages are held in an unbounded [Channel] rather than a zero-replay broadcast flow,
 * specifically so a response that arrives before [awaitMessage] starts waiting for it is never
 * silently dropped (the race a zero-replay `SharedFlow` would have). The cost of that choice is
 * that *stale* messages — e.g. the handshake's own Hello/GetState/preset-name harvest, all of
 * which flow through this tap the instant [dev.tonexotg.protocol.connection.DefaultTonexController.connect]
 * runs — sit in the channel until drained. [SafetyDrill]'s functions call [drain] immediately
 * before issuing each raw request specifically to discard that backlog, so the next matching
 * message [awaitMessage] returns is genuinely the fresh response to *that* request, not a leftover
 * from earlier traffic. This is diagnostic tooling for a short, human-run probe session, not a
 * gig-length connection — an unbounded channel is a deliberate, accepted simplification here (see
 * CLAUDE.md's "don't over-build" guidance), not something intended to run for hours unattended.
 */
class MessageCaptureTap(private val delegate: TonexTransport) : TonexTransport {

    private val reassembler = FrameReassembler()
    private val channel = Channel<TonexMessage>(Channel.UNLIMITED)
    private val failureCount = AtomicInteger(0)

    /** How many frame/header decode failures this tap has observed. Diagnostic only. */
    val decodeFailureCount: Int get() = failureCount.get()

    @Volatile
    private var lastDecodeFailure: TonexError? = null

    /** The most recent frame/header decode failure this tap observed, if any. Diagnostic only. */
    fun lastDecodeFailureOrNull(): TonexError? = lastDecodeFailure

    override suspend fun write(bytes: ByteArray): Int = delegate.write(bytes)

    override fun incoming(): Flow<ByteArray> = delegate.incoming().onEach { chunk -> feed(chunk) }

    override fun close() = delegate.close()

    private fun feed(chunk: ByteArray) {
        for (frameResult in reassembler.feed(chunk)) {
            when (frameResult) {
                is TonexResult.Failure -> recordFailure(frameResult.error)
                is TonexResult.Success -> {
                    when (val decoded = MessageHeaderCodec.decode(frameResult.value)) {
                        is TonexResult.Failure -> recordFailure(decoded.error)
                        is TonexResult.Success -> {
                            channel.trySend(TonexMessageDecoder.decode(decoded.value))
                        }
                    }
                }
            }
        }
    }

    private fun recordFailure(error: TonexError) {
        failureCount.incrementAndGet()
        lastDecodeFailure = error
    }

    /**
     * Discards every message currently buffered, without decoding anything new. Call this
     * immediately before issuing a raw request whose response you intend to [awaitMessage] — see
     * class KDoc for why stale backlog must be cleared first.
     */
    fun drain() {
        while (channel.tryReceive().isSuccess) {
            // discard
        }
    }

    /**
     * Suspends until a captured message satisfies [predicate], or [timeoutMillis] elapses.
     * Messages that do not satisfy [predicate] are consumed and discarded, not requeued — correct
     * for [SafetyDrill]'s sequential, one-request-at-a-time usage (see class KDoc); do not call
     * this concurrently from two callers expecting different messages, or one will steal the
     * other's response.
     *
     * @return the first matching [TonexMessage], or `null` on timeout.
     */
    suspend fun awaitMessage(timeoutMillis: Long, predicate: (TonexMessage) -> Boolean): TonexMessage? =
        withTimeoutOrNull(timeoutMillis) {
            var message: TonexMessage
            do {
                message = channel.receive()
            } while (!predicate(message))
            message
        }
}
