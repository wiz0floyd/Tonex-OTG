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
     * Used by [dev.tonexotg.protocol.codec.MessageHeaderCodec] for a frame whose declared body
     * length does not match the bytes actually present. [StateBlobPatcher] does **not** use this
     * case — it has its own, more specific error types ([BlobTooShortToPatch],
     * [BlobSizeChangedSinceHandshake], [ImplausibleStateBlobShape]) precisely because collapsing
     * "too short", "shape looks wrong", and "size changed since handshake" into one case with a
     * nullable [expectedSize] made it impossible for a caller — or a user reading [message] mid-
     * gig — to tell which of three very different problems actually happened (issue #12 review).
     *
     * @property expectedSize the size the caller expected, if it had a specific expectation.
     * @property actualSize the size the blob actually had.
     */
    data class UnexpectedBlobShape(val expectedSize: Int?, val actualSize: Int) : TonexError() {
        override val message: String
            get() = "Unexpected state blob size: expected $expectedSize, got $actualSize"
    }

    /**
     * A [PedalState] blob was too short for [StateBlobPatcher] to safely patch — shorter than
     * [minimumSize], the smallest length a *real* state blob can plausibly be (derived from the
     * pedal's known field layout: everything before the preset colour table, the colour table
     * itself at its smallest possible encoding, and the tail this module actually indexes into —
     * see `StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE`). This is a floor on real blob shapes, not
     * merely "long enough to index without an out-of-bounds read" — an 18-byte blob used to pass
     * this check (issue #12 review) despite being nowhere near a real state blob's size.
     *
     * Distinct from [BlobSizeChangedSinceHandshake] (was previously a plausible length, has since
     * changed) and [ImplausibleStateBlobShape] (plausible length, wrong-looking bytes at the
     * patched offsets).
     *
     * @property minimumSize the smallest blob length [StateBlobPatcher] will attempt to patch.
     * @property actualSize the blob's actual length.
     */
    data class BlobTooShortToPatch(val minimumSize: Int, val actualSize: Int) : TonexError() {
        override val message: String
            get() = "State blob is too short to patch safely: need at least $minimumSize bytes, got $actualSize"
    }

    /**
     * A [PedalState] blob's length differs from the length observed at this session's first
     * successful state read (the handshake `GetState` step; see [SessionId]). A firmware layout
     * shift almost always changes the blob's overall length, so a length change partway through
     * a session is this module's strongest available signal that the pinned offsets in
     * `StateBlobOffsets` are no longer trustworthy for this pedal at all — a much more specific
     * diagnosis than "the shape looks a bit odd" ([ImplausibleStateBlobShape]).
     *
     * @property pinnedSize the blob length observed at this session's first state read.
     * @property actualSize the blob's actual length now.
     */
    data class BlobSizeChangedSinceHandshake(val pinnedSize: Int, val actualSize: Int) : TonexError() {
        override val message: String
            get() = "State blob size changed from $pinnedSize bytes (seen when this session connected) " +
                "to $actualSize bytes now — refusing to patch a blob whose layout may have moved"
    }

    /**
     * A [PedalState] blob is a plausible length, but the bytes currently sitting at the offsets
     * [StateBlobPatcher] is about to patch do not look like the field they are documented to hold
     * (e.g. an implausible preset index, an implausible slot number). This does not prove the
     * offsets are correct for this blob — a coincidentally plausible value at a shifted offset
     * would still pass — only that they are not *obviously* wrong; see the offset-drift caveat in
     * `StateBlobOffsets`'s KDoc.
     *
     * @property actualSize the blob's actual length, kept for logging context even though this
     *   case is not itself a length failure.
     */
    data class ImplausibleStateBlobShape(val actualSize: Int) : TonexError() {
        override val message: String
            get() = "State blob's slot-region bytes don't look like preset/slot data (length " +
                "$actualSize bytes) — refusing to patch a blob whose layout may not match the " +
                "offsets this module has pinned"
    }

    /**
     * A byte array offered as pedal state exceeds [PedalState.MAX_STATE_BYTES] — larger than the
     * pedal's own state blob (`MAX_STATE_DATA` upstream) could ever legitimately be.
     *
     * Surfaced as a typed error rather than thrown, per this module's contract (every fallible
     * `:protocol` operation returns a [TonexResult] — see this file's top-level KDoc): a
     * malformed or corrupted read must not be able to crash the reader with an uncaught
     * `IllegalArgumentException` (issue #12 review).
     *
     * @property maxSize the largest size a [PedalState] will accept ([PedalState.MAX_STATE_BYTES]).
     * @property actualSize the size actually offered.
     */
    data class OversizedStateBlob(val maxSize: Int, val actualSize: Int) : TonexError() {
        override val message: String
            get() = "State blob is $actualSize bytes, exceeds the pedal's maximum of $maxSize bytes"
    }

    /**
     * A [dev.tonexotg.protocol.PresetIndex] passed to a state-blob patch function held a `value`
     * outside `PresetIndex.VALID_RANGE` at the moment it was actually used to patch a byte,
     * despite [dev.tonexotg.protocol.PresetIndex]'s own constructor guard.
     *
     * This exists as defense-in-depth, not paranoia: `PresetIndex` is a `@JvmInline value class`,
     * and Kotlin represents inline value classes as their raw underlying primitive at many JVM
     * call boundaries — a caller that never goes through `PresetIndex`'s Kotlin constructor (e.g.
     * a Java or reflection caller supplying a raw `int` where a `PresetIndex` is expected) can
     * make `preset.value` hold an out-of-range value that `PresetIndex`'s own `init { require(...) }`
     * never ran for. This error is [StateBlobPatcher] re-checking the value explicitly, with an
     * ordinary runtime comparison that cannot be erased at the same boundary, immediately before
     * it is written into a byte that reaches the pedal.
     *
     * This closes the specific gap `StateBlobPatcher` is exposed to; it does **not** retroactively
     * make every other consumer of a `PresetIndex` elsewhere in this codebase safe against the
     * same erasure — any other call site that treats `PresetIndex.value` as pre-validated by the
     * type alone carries the same residual risk and needs its own explicit check (issue #12
     * review).
     *
     * @property value the out-of-range preset index that was rejected.
     */
    data class InvalidPresetIndex(val value: Int) : TonexError() {
        override val message: String
            get() = "Preset index $value is outside the valid 0..19 range — refusing to patch"
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
    /**
     * The operation completed and produced [value].
     *
     * ⚠️ **`equals`/`hashCode` caveat when `T` is (or contains) a [ByteArray].** This is an
     * ordinary Kotlin `data class`, so its generated `equals` compares [value] with `T`'s own
     * `equals` — and [ByteArray] does not override `equals`, so two arrays with identical
     * contents are unequal unless they are the same instance. `assertEquals(Success(expected),
     * result)` against a freshly-computed `ByteArray` will fail even when the bytes match, and
     * the corresponding `assertNotEquals` will pass vacuously and prove nothing. Compare
     * `(result as Success).value.contentEquals(expected)` instead (as this module's own tests
     * do), not `result == Success(expected)`.
     */
    data class Success<out T>(val value: T) : TonexResult<T>

    /** The operation did not complete; [error] explains why. */
    data class Failure(val error: TonexError) : TonexResult<Nothing>
}
