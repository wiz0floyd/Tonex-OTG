package dev.tonexotg.protocol

/**
 * Every way a `:protocol` operation can fail.
 *
 * This is the type FR11 exists for: "if a USB write fails or times out, app must not silently
 * corrupt pedal state — surface an error rather than assume success." Nothing in this module
 * signals failure by returning `false`, `null`, or swallowing an exception — every fallible
 * operation returns a [TonexResult] whose failure case is one of these, so a caller (ultimately
 * the UI, per S18) can always show a specific, honest reason rather than a generic "something
 * went wrong."
 *
 * `TonexError` intentionally does **not** extend `Throwable`. These are data, not control flow:
 * constructing one must not capture a stack trace on every failed parameter write during a
 * continuous slider drag (NFR2 targets sub-200ms end-to-end). A [TransportFailure] still carries
 * the underlying [Throwable] when one exists, so nothing is lost.
 *
 * Every subtype exposes a human-readable [message] suitable for direct display or logging; it is
 * not meant to be parsed.
 */
sealed class TonexError {

    /** A short, human-readable description of what went wrong. Not meant to be parsed. */
    abstract val message: String

    /**
     * The [TonexTransport] itself failed — e.g. the underlying USB connection dropped, the
     * pedal was unplugged mid-write, or the platform I/O call threw.
     *
     * This is distinct from [Timeout]: a `TransportFailure` means the transport told us
     * something went wrong; a timeout means it told us nothing at all within budget.
     */
    data class TransportFailure(val cause: Throwable) : TonexError() {
        override val message: String
            get() = "Transport failure: ${cause.message ?: cause::class.simpleName ?: "unknown"}"
    }

    /**
     * An operation did not complete within its allotted time budget.
     *
     * @property operation short, stable identifier for what timed out (e.g. `"hello"`,
     *   `"get-state"`, `"parameter-write"`), useful for logging and for per-stage timeout
     *   tests (S9) that assert on which stage timed out, not just that something did.
     * @property timeoutMillis the budget that was exceeded, in milliseconds.
     */
    data class Timeout(val operation: String, val timeoutMillis: Long) : TonexError() {
        override val message: String
            get() = "\"$operation\" timed out after ${timeoutMillis}ms"
    }

    /**
     * Bytes read from the transport could not be reassembled into a valid HDLC frame (S4) —
     * e.g. an unescaped delimiter, a dangling escape byte, or a frame that ended without ever
     * starting.
     */
    data class MalformedFrame(val reason: String) : TonexError() {
        override val message: String
            get() = "Malformed frame: $reason"
    }

    /**
     * A frame's trailing CRC-16/X-25 did not match the CRC computed over its (unstuffed)
     * payload. The frame is discarded; this is never treated as an accepted-but-suspect
     * payload.
     *
     * @property expected the CRC computed locally over the received payload.
     * @property actual the CRC actually present in the frame.
     */
    data class CrcMismatch(val expected: Int, val actual: Int) : TonexError() {
        override val message: String
            get() = "CRC mismatch: expected 0x%04X, got 0x%04X".format(expected, actual)
    }

    /**
     * A message was sent or received that is not valid for the connection's current
     * [ConnectionState] — e.g. a parameter write attempted before [ConnectionState.Ready], or
     * a state-update frame arriving mid-handshake out of order (S9).
     *
     * This is also the error used when a caller attempts an operation the state machine
     * structurally cannot service right now (e.g. `selectPreset` while still `Connecting`).
     */
    data class ProtocolStateViolation(val state: ConnectionState, val details: String) : TonexError() {
        override val message: String
            get() = "Not valid in state $state: $details"
    }

    /**
     * The connected pedal's firmware does not support the requested operation.
     *
     * The known case at design time: per-parameter writes are "only supported in newer Pedal
     * firmware that came with Editor support" (upstream comment). When unsupported, callers
     * (notably [PresetSnapshot] revert, S9b) must surface this rather than silently falling
     * back to a whole-state write — that fallback is exactly the dangerous path this project
     * exists to avoid (see [PedalState]).
     *
     * @property operation short, stable identifier for the unsupported operation (e.g.
     *   `"single-parameter-write"`).
     */
    data class UnsupportedByFirmware(val operation: String) : TonexError() {
        override val message: String
            get() = "\"$operation\" is not supported by this pedal's firmware"
    }

    /**
     * A write was attempted against a [PedalState] that does not carry proof of having been
     * read from the pedal during the *current* session.
     *
     * This is the runtime backstop behind [PedalState]'s structural provenance guarantee: the
     * type system already makes it impossible for code outside `:protocol` to construct a
     * [PedalState] at all, and [SessionId] equality further catches a *stale* blob retained
     * from a previous, since-ended session of this same module. This error is what a caller
     * sees if that second check trips.
     */
    data class StaleSessionState(val details: String) : TonexError() {
        override val message: String
            get() = "Refused write: state blob is not from the current session ($details)"
    }

    /**
     * A pedal state blob had an unexpected size or shape for the offsets a write path is about
     * to patch.
     *
     * Surfacing this as a typed error — instead of patching blindly at fixed offsets from the
     * end of the array — is the direct fix for the bug this project must not repeat (see
     * [PedalState]): the patch offsets are firmware-version dependent, and a length mismatch
     * means the offsets are not trustworthy for this blob.
     *
     * @property expectedSize the size the caller expected, if it had a specific expectation.
     * @property actualSize the size the blob actually had.
     */
    data class UnexpectedBlobShape(val expectedSize: Int?, val actualSize: Int) : TonexError() {
        override val message: String
            get() = "Unexpected state blob size: expected $expectedSize, got $actualSize"
    }
}

/**
 * The outcome of a `:protocol` operation that can fail: exactly one of a successful [value][Success.value]
 * or a typed [error][Failure.error].
 *
 * Used in place of a bare `Boolean` (which cannot say *why*) or a thrown exception (which is
 * easy to forget to catch across a `suspend` boundary and is not exhaustively checkable by the
 * compiler). Every public `:protocol` operation that can fail for more than one reason returns
 * this instead.
 *
 * Callers are expected to exhaustively `when` on this — `sealed interface` makes that a compile
 * error to skip a branch on, which is deliberate.
 */
sealed interface TonexResult<out T> {
    /** The operation completed and produced [value]. */
    data class Success<out T>(val value: T) : TonexResult<T>

    /** The operation did not complete; [error] explains why. */
    data class Failure(val error: TonexError) : TonexResult<Nothing>
}
