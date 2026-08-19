package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.TonexTransport
import dev.tonexotg.protocol.codec.DecodedMessage
import dev.tonexotg.protocol.codec.MessageHeaderCodec
import dev.tonexotg.protocol.framing.HdlcFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A fake [TonexTransport] for testing [DefaultTonexController] without a real USB connection.
 *
 * `Channel`-backed, not `MutableSharedFlow`-backed — this matters. A `SharedFlow` never completes
 * (making the clean-detach test impossible) and drops emissions made before the collector
 * subscribes (making every test racy). `Channel(UNLIMITED).receiveAsFlow()` buffers
 * pre-subscription emissions and completes on [close][Channel.close], and closing with a cause
 * gives the throwing-detach case for free.
 */
class FakeTonexTransport : TonexTransport {

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)

    /** Every raw byte array handed to [write], in call order. */
    val written = mutableListOf<ByteArray>()

    /** Override to simulate a short write; defaults to "accepted every byte." */
    var writeBehavior: (ByteArray) -> Int = { it.size }

    /** Set to make the next (and every subsequent) [write] call throw instead of returning. */
    var writeThrows: Throwable? = null

    /**
     * Set to make [write] suspend forever instead of returning — for exercising
     * [ConnectionTimeouts.transportWriteMillis]'s timeout. `write`'s KDoc on [dev.tonexotg.protocol.TonexTransport]
     * permits it to "suspend for as long as the underlying transport needs," so this is a
     * legitimate fake behaviour, not an edge case invented for testing's sake.
     */
    var writeHangs: Boolean = false

    /** `true` once [close] has been called at least once. */
    var closed: Boolean = false
        private set

    override suspend fun write(bytes: ByteArray): Int {
        writeThrows?.let { throw it }
        written += bytes
        if (writeHangs) kotlinx.coroutines.awaitCancellation()
        return writeBehavior(bytes)
    }

    override fun incoming(): Flow<ByteArray> = channel.receiveAsFlow()

    override fun close() {
        closed = true
    }

    /** Frames [messageBytes] (an already-header-encoded message) as an HDLC frame and delivers it. */
    fun emitMessage(messageBytes: ByteArray) {
        channel.trySend(HdlcFrame.encode(messageBytes))
    }

    /** Delivers [bytes] to the incoming stream exactly as given — no framing applied. */
    fun emitRaw(bytes: ByteArray) {
        channel.trySend(bytes)
    }

    /** Ends the incoming stream cleanly (as if the transport detached without error). */
    fun endStream() {
        channel.close()
    }

    /** Ends the incoming stream with [cause] (as if the transport failed). */
    fun failStream(cause: Throwable) {
        channel.close(cause)
    }

    /**
     * Decodes every captured [write] call back into a [DecodedMessage] via
     * [HdlcFrame.decode] + [MessageHeaderCodec.decodeOutbound], in call order.
     *
     * Uses `decodeOutbound`, not `decode`: a captured [write] is always something this app itself
     * encoded via [MessageHeaderCodec.encode] (the 4-field outbound shape) — never a real inbound
     * pedal response, which `decode` (3-field) is for. See [MessageHeaderCodec]'s class KDoc.
     *
     * Fails loudly (via [kotlin.test.fail]) if any captured write does not round-trip as a valid
     * framed message — a test relying on this should never have to debug a silently-empty list.
     */
    fun writtenMessages(): List<DecodedMessage> = written.map { framed ->
        val unframed = when (val r = HdlcFrame.decode(framed)) {
            is TonexResult.Success -> r.value
            is TonexResult.Failure -> kotlin.test.fail("captured write did not decode as a valid HDLC frame: ${r.error.message}")
        }
        when (val r = MessageHeaderCodec.decodeOutbound(unframed)) {
            is TonexResult.Success -> r.value
            is TonexResult.Failure -> kotlin.test.fail("captured write did not decode as a valid message header: ${r.error.message}")
        }
    }
}
