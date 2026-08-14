package dev.tonexotg.protocol

import kotlinx.coroutines.flow.Flow

/**
 * The single seam between [dev.tonexotg.protocol] and a real USB connection.
 *
 * This is the entire surface an Android implementation has to satisfy (see S11, which backs
 * this with `UsbDeviceConnection`/`UsbRequest` over the pedal's CDC-ACM interface). No other
 * type in this module may depend on Android — everything above this interface (framing,
 * varints, message codecs, the state machine, snapshotting) is pure Kotlin/JVM and is tested
 * against a fake implementation of exactly this interface.
 *
 * Implementations are expected to be raw and unopinionated: no framing, no retries, no protocol
 * awareness. Push bytes in, get bytes out. Error translation into [TonexError] happens above
 * this seam, not inside it.
 *
 * ### Threading and lifecycle
 * - [write] may suspend for as long as the underlying transport needs to accept the bytes (e.g.
 *   a queued bulk/serial write). It is not required to be safe to call concurrently with itself;
 *   callers above this seam are responsible for serializing writes.
 * - [incoming] represents the continuous byte stream read from the device. A single emission is
 *   not guaranteed to align with a single protocol frame — a frame may be split across multiple
 *   emissions, and one emission may contain multiple frames back to back. Framing (S4) is
 *   responsible for reassembly.
 * - [close] releases the underlying connection. After [close], [write] and [incoming] behavior
 *   is undefined; callers must not use a `TonexTransport` again after closing it.
 */
interface TonexTransport {

    /**
     * Writes [bytes] to the pedal and returns the number of bytes actually accepted.
     *
     * A return value less than `bytes.size` is a legitimate outcome (short write), not
     * necessarily a failure — callers above this seam decide how to react. Implementations
     * should throw (rather than silently return 0 or a wrong count) when the underlying
     * transport is in a state that makes the write meaningless, e.g. after [close].
     */
    suspend fun write(bytes: ByteArray): Int

    /**
     * The raw byte stream read from the pedal, as it arrives off the wire.
     *
     * Collecting this flow is expected to be long-lived (for the life of the connection).
     * Chunk boundaries carry no protocol meaning — see the class-level threading note.
     */
    fun incoming(): Flow<ByteArray>

    /**
     * Releases the underlying connection. Idempotent: calling this more than once must not
     * throw.
     */
    fun close()
}
