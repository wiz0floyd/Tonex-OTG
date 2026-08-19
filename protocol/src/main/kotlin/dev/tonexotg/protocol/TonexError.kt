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
     * A write was attempted against a [PedalState] that is not currently authorized to write —
     * either because it was never read during the *current* session, or because it was read
     * during the current session but is no longer usable (superseded by a later read, or already
     * spent on an earlier successful patch).
     *
     * This is the runtime backstop behind [PedalState]'s structural provenance guarantee: the
     * type system already makes it impossible for code outside `:protocol` to construct a
     * [PedalState] at all; [SessionId] equality catches a *stale* blob retained from a previous,
     * since-ended session of this same module; and [SessionId]'s read-generation tracking catches
     * a blob that is genuinely from *this* session but is no longer the authorized one to write —
     * either because a later read has superseded it, or because it has already been used for one
     * successful write (a [PedalState] is single-use as a write authorization; see
     * [dev.tonexotg.protocol.state.StateBlobPatcher]'s `prepareForPatch`, issue #12 round-3
     * review).
     *
     * @property details a specific, human-readable explanation of which of the above actually
     *   happened — not meant to be parsed, but always accurate for the case it describes.
     * @property sameSession `true` when [details] describes a same-session-but-no-longer-current
     *   rejection (superseded-by-a-later-read, or already-spent), `false` when it describes a
     *   genuinely cross-session rejection. Exists so [message] never claims a blob "is not from
     *   the current session" when it demonstrably is — an inaccuracy the round-2 fix left in
     *   place for the generation-stale case (issue #12 round-3 review) and this property closes.
     */
    data class StaleSessionState(val details: String, val sameSession: Boolean) : TonexError() {
        override val message: String
            get() = if (sameSession) {
                "Refused write: state blob IS from the current session, but is not currently " +
                    "authorized to write with — it may have been superseded by a later read, or " +
                    "already used for an earlier write ($details)"
            } else {
                "Refused write: state blob is not from the current session ($details)"
            }
    }

    /**
     * A pedal state blob, or any other length-prefixed structure this module decodes, had an
     * unexpected size or shape for what the caller was about to do with it.
     *
     * Surfacing this as a typed error — instead of patching or indexing blindly at fixed offsets
     * — is the direct fix for the bug this project must not repeat (see [PedalState]): offsets
     * are firmware-version dependent, and a length mismatch means they are not trustworthy.
     *
     * This case is shared across several structurally-similar-but-unrelated size checks —
     * [dev.tonexotg.protocol.codec.MessageHeaderCodec] (a frame's declared body length vs. bytes
     * actually present), [dev.tonexotg.protocol.message.PresetNameExtractor], [dev.tonexotg.protocol.message.PresetParameterExtractor],
     * and [dev.tonexotg.protocol.message.SingleParameterPayloadCodec] — and a message reading
     * only "expected N, got M" gives no way to tell which of those actually failed (issue #25,
     * first real-hardware run: this ambiguity cost real debugging time telling a generic framing
     * mismatch apart from an actual state-blob-shape problem). [context] exists to close that
     * gap; every call site names the specific thing it was decoding. [StateBlobPatcher] does
     * **not** use this case — it has its own, more specific error types ([BlobTooShortToPatch],
     * [BlobSizeChangedSinceHandshake], [ImplausibleStateBlobShape]) precisely because collapsing
     * "too short", "shape looks wrong", and "size changed since handshake" into one case with a
     * nullable [expectedSize] made it impossible for a caller — or a user reading [message] mid-
     * gig — to tell which of three very different problems actually happened (issue #12 review).
     *
     * @property context short, stable identifier for what was being decoded (e.g. `"message
     *   header body"`, `"preset name field"`), so [message] alone is enough to find the call
     *   site without a stack trace — these are data, not thrown exceptions (see class KDoc).
     * @property expectedSize the size the caller expected, if it had a specific expectation.
     * @property actualSize the size the blob actually had.
     */
    data class UnexpectedBlobShape(val context: String, val expectedSize: Int?, val actualSize: Int) : TonexError() {
        override val message: String
            get() = "Unexpected size decoding $context: expected $expectedSize, got $actualSize"
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
     * A [PedalState] blob's length is *shorter* than the largest plausible-sized length observed
     * so far this session (see [SessionId.pinOrWidenBlobSize]).
     *
     * That pin is **not** literally "the blob seen when this session connected" or "the
     * handshake blob" — despite this error's name, kept for continuity with earlier rounds of
     * issue #12's review. A [SessionId] does not exist until [ConnectionState.Ready] is reached,
     * and `Ready` is only reached *after* the handshake's `GetState` blob has already been read
     * (see [SessionId]'s KDoc) — so no [PedalState], and therefore no pin, can ever be anchored
     * to that specific read. It is also **not** necessarily "the first" plausible-sized read
     * anymore, as of issue #12's round-4 review: the pin self-corrects upward — a later,
     * *larger* plausible-sized read widens it, on the reasoning that a smaller earlier pin was
     * itself more likely to have come from a corrupt/truncated read than the pedal's actual blob
     * length changing mid-session (see [SessionId.pinOrWidenBlobSize]'s KDoc for the full
     * reasoning and the HIGH finding this closes). Only a *shrink* below the established pin
     * trips this error — that remains this module's strongest available signal that the pinned
     * offsets in `StateBlobOffsets` are no longer trustworthy for this pedal at all — a much more
     * specific diagnosis than "the shape looks a bit odd" ([ImplausibleStateBlobShape]).
     *
     * @property pinnedSize the largest plausible-sized blob length observed so far this session.
     * @property actualSize the blob's actual length now, shorter than [pinnedSize].
     */
    data class BlobSizeChangedSinceHandshake(val pinnedSize: Int, val actualSize: Int) : TonexError() {
        override val message: String
            get() = "State blob size shrank from $pinnedSize bytes (this session's largest " +
                "plausible-sized read so far, observed after the connection reached Ready — not " +
                "necessarily the handshake blob itself) to $actualSize bytes now — refusing to " +
                "patch a blob whose layout may have moved"
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

    /**
     * [TonexController.revertActivePreset] was called but no [PresetSnapshot] has been captured for
     * the currently active preset this session.
     *
     * Reverting to a snapshot of a *different* preset would itself be data loss (see S9b / issue
     * #14), so this refuses rather than substituting the nearest available snapshot. This is a
     * permanent member of the error taxonomy, not an S9 placeholder: even once S9b's capture path
     * lands, a preset whose snapshot read failed (or which became active before capture completed)
     * legitimately has no snapshot, and revert must still refuse loudly.
     *
     * @property presetIndex the preset that has no captured snapshot.
     */
    data class NoSnapshotAvailable(val presetIndex: PresetIndex) : TonexError() {
        override val message: String
            get() = "No snapshot has been captured for preset ${presetIndex.value} during this " +
                "session — refusing to revert, because reverting to another preset's values would " +
                "itself be data loss"
    }

    /**
     * [TonexController.revertActivePreset]'s replay stopped after a per-parameter write failed,
     * partway through restoring the snapshot's 109 values.
     *
     * This exists as its own case, rather than surfacing the underlying [cause] directly, because
     * FR11 requires surfacing a failure rather than assuming success, and a bare
     * [TransportFailure]/[Timeout] from write 47 of 109 tells a caller nothing about the 46
     * writes that *did* land. This case is what lets a caller — ultimately the UI — say something
     * true about a revert that is now half-applied.
     *
     * [appliedCount] writes, for `ParameterId(0)` through `ParameterId(appliedCount - 1)`, are
     * known to have been accepted by the transport; [failedParameter] is `ParameterId(appliedCount)`.
     * This exactness depends on the replay writing in strict ascending
     * [ParameterId.PRESET_RANGE] order and aborting at the first failure — see
     * [TonexController.revertActivePreset]'s KDoc.
     *
     * ⚠️ **"Accepted by the transport" is not "confirmed applied by the pedal."** Issue #14
     * explicitly rejects per-write read-back verification; this project does not pay that
     * verification round trip. Do not read [appliedCount] as a pedal-confirmed count.
     *
     * The snapshot backing this revert is deliberately **not** discarded on this failure —
     * retrying [TonexController.revertActivePreset] is safe and idempotent, since the replay
     * always re-issues all 109 writes from the same immutable snapshot.
     *
     * @property presetIndex the preset the revert was restoring.
     * @property appliedCount how many of the 109 per-parameter writes succeeded before the failure.
     * @property totalCount the total number of parameters a full revert writes ([PresetSnapshot.PARAMETER_COUNT]).
     * @property failedParameter the parameter whose write failed and stopped the replay.
     * @property cause the underlying [TonexError] from the failing write, unwrapped and preserved.
     */
    data class RevertIncomplete(
        val presetIndex: PresetIndex,
        val appliedCount: Int,
        val totalCount: Int,
        val failedParameter: ParameterId,
        val cause: TonexError,
    ) : TonexError() {
        override val message: String
            get() = "Revert of preset ${presetIndex.value} stopped after $appliedCount of " +
                "$totalCount parameter writes: parameter ${failedParameter.index} failed " +
                "(${cause.message}). The preset is now in a mixed state — $appliedCount snapshot " +
                "values were restored and the remainder are as they were. The snapshot is retained; " +
                "retrying revert is safe."
    }

    /**
     * [TonexController.revertActivePreset]'s replay was aborted because the pedal's active preset
     * changed out from under it — a footswitch press, MIDI program change, or an external editor —
     * partway through restoring the snapshot's 109 values.
     *
     * Per-parameter writes are **not** preset-indexed on the wire: they apply to whatever preset
     * is active on the pedal at the moment they arrive, not the preset the snapshot was captured
     * from. Continuing the replay after the active preset moved would have written the remaining
     * snapshot values onto [observedPreset] — silently corrupting a preset nobody asked to touch.
     * Aborting is the only honest outcome.
     *
     * This is deliberately a **separate case from [RevertIncomplete]**, not a reuse of it:
     * [RevertIncomplete] means a write failed and nothing else is true about the world, but here
     * nothing failed — [nextParameter] was never attempted, there is no underlying transport
     * [cause] to carry, and — the important difference — [RevertIncomplete]'s retained-snapshot,
     * "retrying revert is safe" guarantee does **not** hold in this scenario (see below).
     *
     * ⚠️ **The check that produces this error narrows the corruption window; it cannot close it.**
     * There is no preset-indexed write on the wire, so the check and the write it guards are not,
     * and cannot be, atomic: the pedal can change preset in the microseconds between reading
     * [TonexController]'s active-preset state and the write frame landing. There is also inherent
     * reporting latency — the pedal's own state update has to arrive and be parsed before that
     * state reflects reality — so in practice a small number of writes right at the changeover may
     * land on [observedPreset] anyway. This narrows "up to the remaining writes" down to "at most a
     * couple," not to zero.
     *
     * ⚠️ **Unlike [RevertIncomplete], retrying [TonexController.revertActivePreset] right now is
     * NOT automatically safe.** Calling it again targets whatever preset is *currently* active —
     * [observedPreset], not [intendedPreset] — so a naive retry reverts the wrong preset ([observedPreset]).
     *
     * **Reselecting [intendedPreset] and retrying IS safe (issue #46).** Snapshot retention is
     * first-arrival-wins: a preset's snapshot is recorded once per session, the first time it
     * becomes active, and is never overwritten by a later re-arrival at that same preset — see
     * [dev.tonexotg.protocol.connection.DefaultTonexController.captureSnapshotLocked]'s KDoc. So
     * the caller's obvious recovery — reselect [intendedPreset], then call
     * [TonexController.revertActivePreset] again — is exactly right: [intendedPreset]'s original,
     * still-good snapshot survives being re-arrived at while in this mixed, half-reverted state,
     * and the retried revert replays that original snapshot in full. (Before issue #46 this used to
     * be actively unsafe, because re-arriving at [intendedPreset] would silently overwrite its
     * snapshot with a fresh capture of the half-reverted state — the original values would be gone,
     * not merely stale. That hazard is what first-arrival-wins retention exists to close.)
     *
     * @property intendedPreset the preset the revert was restoring — the preset captured as
     *   `active` when [TonexController.revertActivePreset] began.
     * @property observedPreset the preset actually active on the pedal when the change was
     *   detected, or `null` if the session tore down mid-replay (`_activePreset` is cleared on
     *   teardown, and a disconnect mid-replay legitimately lands in this same branch).
     * @property appliedCount how many of the 109 per-parameter writes had already gone out before
     *   the change was detected.
     * @property totalCount the total number of parameters a full revert writes ([PresetSnapshot.PARAMETER_COUNT]).
     * @property nextParameter the parameter whose write was withheld — the first one this abort
     *   prevented from reaching the wire.
     */
    data class ActivePresetChangedDuringRevert(
        val intendedPreset: PresetIndex,
        val observedPreset: PresetIndex?,
        val appliedCount: Int,
        val totalCount: Int,
        val nextParameter: ParameterId,
    ) : TonexError() {
        override val message: String
            get() = "Revert of preset ${intendedPreset.value} was aborted after $appliedCount of " +
                "$totalCount parameter writes: the pedal's active preset changed to " +
                "${observedPreset?.value?.toString() ?: "unknown (session disconnected)"} mid-revert " +
                "(footswitch, MIDI, or an external editor). Preset ${intendedPreset.value} is now in " +
                "a mixed state — $appliedCount snapshot values were restored and the rest are as " +
                "they were. The remaining writes were withheld so they would not land on preset " +
                "${observedPreset?.value?.toString() ?: "unknown"}; a small number of writes made " +
                "around the changeover may have reached it anyway. Retrying revert now would target " +
                "preset ${observedPreset?.value?.toString() ?: "unknown"}, not ${intendedPreset.value}."
    }

    /**
     * [TonexController.restoreFootswitches] was called but the three footswitch slot assignments
     * (A/B/C) were never successfully captured this session, or the capture on hand no longer
     * belongs to the live session.
     *
     * Per issue #36's own acceptance criteria, this must be a loud, typed refusal rather than a
     * silent no-op: a caller offering a "restore my footswitches" affordance needs to be able to
     * tell the difference between "nothing to restore because nothing changed" (not this case)
     * and "there is nothing captured to restore *to*" (this case).
     *
     * ## Honest scope of when this is actually reachable
     * [TonexController.restoreFootswitches] being called before a connection ever reached
     * [ConnectionState.Ready] does **not** reach this case — that returns
     * [ProtocolStateViolation] instead (guard 1 runs first). And the handshake blob failing to
     * decode as a plausible slot region cannot happen for a *Ready* session either: reaching Ready
     * at all requires [dev.tonexotg.protocol.state.StateBlobReader.activePreset] to have already
     * decoded that exact blob successfully (`connect()`'s `initialActive` step), which runs the
     * identical `looksLikeSlotRegion` structural check over the same three slot-assignment bytes
     * plus the active-slot byte that footswitch-snapshot capture reads — so a `Ready` session's
     * handshake blob is, by construction, always decodable for both purposes. The genuinely
     * reachable case is narrower: a race between [DefaultTonexController.teardown] (which clears
     * the captured snapshot without holding `operationMutex` when torn down via
     * `onTransportEnded`, not [TonexController.disconnect]'s own locked path — see `teardown`'s own
     * KDoc) and an in-flight `restoreFootswitches` call that already passed the `Ready` check.
     * [DefaultTonexController.restoreFootswitches] compares the captured snapshot's `SessionId` by
     * identity, not mere presence, so a torn-down/foreign-session snapshot is refused here rather
     * than replayed. A permanent member of the error taxonomy for that reason, not a placeholder.
     */
    data object NoFootswitchSnapshotAvailable : TonexError() {
        override val message: String
            get() = "No footswitch slot assignments were captured this session (before any write " +
                "could have changed them) — refusing to restore, because there is nothing captured " +
                "to restore to"
    }

    /**
     * A value handed to [TonexController.setParameter] fell outside its [ParameterSpec.min]..[ParameterSpec.max].
     *
     * [TonexController.setParameter]'s contract is explicit that "out-of-range values are rejected
     * rather than silently clamped, so a caller cannot mistake a clamped write for the value it
     * asked for" — this is that rejection. Note this is a *different* policy from the message
     * layer's: [dev.tonexotg.protocol.message.ParameterWriteMessage]/[dev.tonexotg.protocol.message.MasterVolumeMessage]
     * clamp, because at that layer an out-of-range value has one unambiguous safe interpretation.
     * The controller rejects first, so the clamp there is unreachable for values arriving through
     * this API and remains a backstop for direct callers.
     *
     * @property id the parameter whose value was rejected.
     * @property value the out-of-range value that was rejected.
     * @property min the parameter's valid lower bound.
     * @property max the parameter's valid upper bound.
     */
    data class ParameterValueOutOfRange(
        val id: ParameterId,
        val value: Float,
        val min: Float,
        val max: Float,
    ) : TonexError() {
        override val message: String
            get() = "Parameter ${id.index} value $value is outside its valid range $min..$max — " +
                "refusing the write (this layer rejects out-of-range values rather than clamping them)"
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
