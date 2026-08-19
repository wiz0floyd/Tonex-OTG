/*
 * Ported/adapted from: TonexOneController
 * Upstream repository: https://github.com/Builty/TonexOneController
 * Upstream file:        source/main/usb_tonex_one.c
 * Upstream licence:     Apache-2.0 — Copyright 2025 Greg Smith
 * Full licence text:    LICENSE
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream project named above. See /CREDITS.md for full attribution.
 */
package dev.tonexotg.protocol.state

import dev.tonexotg.protocol.FootswitchSnapshot
import dev.tonexotg.protocol.PedalState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.SessionId
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult

/**
 * Turns a live-read [PedalState] blob into the patched bytes for a "change a preset slot" or
 * "change the active slot" write, without disturbing any other byte of the blob.
 *
 * ## What this fixes
 * `Builty/TonexOneController` v1.0.0.2 replayed 20 hardcoded, Wireshark-captured whole-state
 * blobs to change presets, overwriting the receiving pedal's entire global configuration with
 * the capture rig's. This object is the read-modify-write replacement: every function here
 * takes a [PedalState] that can only have come from an actual read of the pedal *this session*
 * (see [PedalState] and [SessionId] for how that is enforced structurally), copies its bytes,
 * overwrites only the named offsets in [StateBlobOffsets], and returns the result. There is no
 * function anywhere in this file that can produce a state-write payload from anything other than
 * a [PedalState] the caller actually holds — nothing here accepts a raw [ByteArray] as its
 * starting point, and nothing here can synthesize one.
 *
 * ## What this deliberately does not do
 * - **No side effects.** Upstream's `set_preset_in_slot()` unconditionally forces
 *   `DIRECT_MONITOR = 1`, may force stomp/AB mode, and — when upstream's `TOGGLE_BYPASS` config is
 *   enabled — also writes `END_BYPASS_MODE` (see [StateBlobOffsets]'s "Fields intentionally NOT
 *   modelled here" for the full three-offset list, confirmed against upstream source per issue
 *   #27). None of the three offsets is even named in [StateBlobOffsets], so there is no write path
 *   here capable of touching any of them.
 * - **No message framing.** The `{0xb9, 0x03, 0x81, 0x06, 0x03, 0x82, ...}` wrapper that turns
 *   these bytes into an outgoing wire message is [dev.tonexotg.protocol.message]'s concern, not
 *   this module's — everything here returns plain patched bytes, ready for that layer to frame
 *   and send.
 * - **No mutation just to read.** Nothing here ever needs to write a byte in order to observe
 *   pedal state; that upstream anti-pattern (writing a fake preset into Slot A just to provoke a
 *   name response, fixed upstream in V1.0.8.2) has no equivalent here at all. [StateBlobReader] is
 *   the dedicated read-only accessor for "which preset/slot is active right now" — its own KDoc
 *   explains why it cannot be used to synthesize a write, cross-referencing this same claim.
 *
 * ## Byte-exactness contract
 * Every function below returns a fresh copy of the input blob's bytes with **at most the bytes
 * documented on that function** changed — every other byte, including bytes this module does not
 * know the meaning of, is bit-identical to what [PedalState.copyOfBytes] returned. This is
 * asserted exhaustively (over the *entire* array, not just near the patched offsets) in
 * `StateBlobPatcherTest`.
 */
object StateBlobPatcher {

    /**
     * Patches [slot]'s assigned preset in [state] to [preset] — the one byte at
     * [StateBlobOffsets.endOffsetForSlotPreset] for [slot] — leaving every other byte, including
     * the active-slot byte, untouched.
     *
     * Use [selectPreset] instead if the caller also wants [slot] to become the pedal's active
     * slot in the same write (the common "press a footswitch button" case); this function alone
     * only reassigns which preset [slot] points at.
     *
     * @param state a blob read from the pedal during [currentSession] — and, per [PedalState]'s
     *   freshness contract, the *most recent* such read this session has produced, not already
     *   spent on an earlier successful patch. Never synthesize one, and never hold one across
     *   other work: S9 must re-read immediately before calling this, and — since issue #12's
     *   round-3 review — that is no longer merely a caller convention: a successful patch
     *   consumes [state]'s generation as part of its own success, so reusing the same [state] for
     *   a second call (even with zero other work in between) is rejected with
     *   [TonexError.StaleSessionState], not silently accepted. See [PedalState]'s KDoc.
     * @param currentSession the session the caller believes is live right now; compared against
     *   [PedalState.sessionId] by reference. See [TonexError.StaleSessionState].
     * @return the patched bytes, or a [TonexResult.Failure] — never throws for an out-of-range,
     *   stale, or implausible input; see [prepareForPatch] for the exhaustive list of rejections.
     */
    fun patchSlotAssignment(
        state: PedalState,
        currentSession: SessionId,
        slot: PresetSlot,
        preset: PresetIndex,
    ): TonexResult<ByteArray> {
        val prepared = prepareForPatch(state, currentSession, preset)
        if (prepared is TonexResult.Failure) return prepared
        val bytes = (prepared as TonexResult.Success).value

        val index = bytes.size - StateBlobOffsets.endOffsetForSlotPreset(slot)
        bytes[index] = preset.value.toByte()
        return TonexResult.Success(bytes)
    }

    /**
     * Patches the pedal's active-slot byte in [state] to [slot] — the one byte at
     * [StateBlobOffsets.END_CURRENT_SLOT] — leaving every slot assignment and every other byte
     * untouched.
     *
     * @param state a blob read from the pedal during [currentSession] — and, per [PedalState]'s
     *   freshness contract, the *most recent* such read this session has produced, not already
     *   spent on an earlier successful patch. Never synthesize one, and never hold one across
     *   other work: S9 must re-read immediately before calling this, and — since issue #12's
     *   round-3 review — that is no longer merely a caller convention: a successful patch
     *   consumes [state]'s generation as part of its own success, so reusing the same [state] for
     *   a second call (even with zero other work in between) is rejected with
     *   [TonexError.StaleSessionState], not silently accepted. See [PedalState]'s KDoc.
     * @param currentSession the session the caller believes is live right now; compared against
     *   [PedalState.sessionId] by reference. See [TonexError.StaleSessionState].
     * @return the patched bytes, or a [TonexResult.Failure]; see [patchSlotAssignment].
     */
    fun patchActiveSlot(
        state: PedalState,
        currentSession: SessionId,
        slot: PresetSlot,
    ): TonexResult<ByteArray> {
        val prepared = prepareForPatch(state, currentSession, preset = null)
        if (prepared is TonexResult.Failure) return prepared
        val bytes = (prepared as TonexResult.Success).value

        bytes[bytes.size - StateBlobOffsets.END_CURRENT_SLOT] = slot.ordinal.toByte()
        return TonexResult.Success(bytes)
    }

    /**
     * Assigns [preset] to [slot] **and** makes [slot] the active slot, in a single patched copy
     * — exactly the two bytes touched by [patchSlotAssignment] and [patchActiveSlot] combined,
     * and nothing else. This is the byte-level equivalent of pressing a footswitch button to
     * select a preset that is not already on that footswitch, with none of upstream
     * `set_preset_in_slot()`'s forced-mode, forced-direct-monitor, or forced-bypass-mode side
     * effects (see [StateBlobOffsets]'s "Fields intentionally NOT modelled here" for all three).
     *
     * Implemented as two writes over one shared copy (not as two chained calls to
     * [patchSlotAssignment] then [patchActiveSlot]) because those functions consume a
     * [PedalState] and this module has no way — deliberately — to wrap intermediate bytes back
     * into one; see the "no synthesis" contract on [StateBlobPatcher].
     *
     * @param state a blob read from the pedal during [currentSession] — and, per [PedalState]'s
     *   freshness contract, the *most recent* such read this session has produced, not already
     *   spent on an earlier successful patch. Never synthesize one, and never hold one across
     *   other work: S9 must re-read immediately before calling this, and — since issue #12's
     *   round-3 review — that is no longer merely a caller convention: a successful patch
     *   consumes [state]'s generation as part of its own success, so reusing the same [state] for
     *   a second call (even with zero other work in between) is rejected with
     *   [TonexError.StaleSessionState], not silently accepted. See [PedalState]'s KDoc.
     * @return the patched bytes, or a [TonexResult.Failure]; see [patchSlotAssignment].
     */
    fun selectPreset(
        state: PedalState,
        currentSession: SessionId,
        slot: PresetSlot,
        preset: PresetIndex,
    ): TonexResult<ByteArray> {
        val prepared = prepareForPatch(state, currentSession, preset)
        if (prepared is TonexResult.Failure) return prepared
        val bytes = (prepared as TonexResult.Success).value

        bytes[bytes.size - StateBlobOffsets.endOffsetForSlotPreset(slot)] = preset.value.toByte()
        bytes[bytes.size - StateBlobOffsets.END_CURRENT_SLOT] = slot.ordinal.toByte()
        return TonexResult.Success(bytes)
    }

    /**
     * Patches all three footswitch slot-assignment bytes in [state] to [assignments] — the write
     * side of issue #36's footswitch-restore safety net. Touches exactly the three bytes at
     * [StateBlobOffsets.endOffsetForSlotPreset] for each [PresetSlot], leaving the active-slot
     * byte and every other byte untouched — unlike [selectPreset], this never changes which slot
     * is active, only what each slot points at.
     *
     * @param assignments must contain exactly one entry per [PresetSlot] — see
     *   [FootswitchSnapshot.toMap], the only intended source of this map. A map missing an entry
     *   is an internal-invariant violation caught by `require` (not a runtime
     *   [TonexResult.Failure]), because [FootswitchSnapshot]'s own constructor already guarantees
     *   completeness structurally; a caller reaching this function at all can only have gotten a
     *   complete map. Each value IS separately re-checked against [PresetIndex.VALID_RANGE] below,
     *   the same defense-in-depth [patchSlotAssignment]/[selectPreset] apply to their own bare
     *   `preset: PresetIndex` parameter — a boxed `PresetIndex` stored inside a `Map` value
     *   position is **not** immune to the `@JvmInline` erasure gap [PresetIndex]'s KDoc describes:
     *   `javap` on the compiled class shows `box-impl(int)` calling the `private` constructor
     *   directly, never `constructor-impl(int)` — the synthetic static method that actually runs
     *   `init { require(...) }` — so a caller who invokes the compiled `box-impl` method via
     *   reflection (the same class of bypass the existing `patchSlotAssignment`/`selectPreset`
     *   reflection tests already exercise) can hand this function a `Map` whose boxed values never
     *   passed that check. An earlier version of this KDoc claimed boxing made this unreachable;
     *   that was not verified against the actual bytecode and was wrong — corrected after review.
     * @param state a blob read from the pedal during [currentSession] — and, per [PedalState]'s
     *   freshness contract, the *most recent* such read this session has produced. See
     *   [patchSlotAssignment]'s KDoc for the identical freshness/single-use contract.
     * @param currentSession the session the caller believes is live right now; compared against
     *   [PedalState.sessionId] by reference. See [TonexError.StaleSessionState].
     * @return the patched bytes, or a [TonexResult.Failure] — never throws except for the
     *   completeness `require` above; see [prepareForPatch] for the exhaustive list of rejections
     *   (session provenance, read freshness, length, and shape).
     */
    fun restoreSlotAssignments(
        state: PedalState,
        currentSession: SessionId,
        assignments: Map<PresetSlot, PresetIndex>,
    ): TonexResult<ByteArray> {
        require(assignments.keys == PresetSlot.entries.toSet()) {
            "restoreSlotAssignments requires an assignment for every slot (${PresetSlot.entries}), got ${assignments.keys}"
        }

        // Defense-in-depth against @JvmInline erasure (see the KDoc above and PresetIndex's own),
        // for all three values — checked here, BEFORE prepareForPatch's generation-consuming step,
        // so an invalid value is rejected without spending the session's read generation on a patch
        // that was never going to succeed.
        for (preset in assignments.values) {
            if (preset.value !in PresetIndex.VALID_RANGE) {
                return TonexResult.Failure(TonexError.InvalidPresetIndex(preset.value))
            }
        }

        val prepared = prepareForPatch(state, currentSession, preset = null)
        if (prepared is TonexResult.Failure) return prepared
        val bytes = (prepared as TonexResult.Success).value

        for ((slot, preset) in assignments) {
            bytes[bytes.size - StateBlobOffsets.endOffsetForSlotPreset(slot)] = preset.value.toByte()
        }
        return TonexResult.Success(bytes)
    }

    /**
     * The shared entry gate every patch function above runs through, in this fixed order:
     *
     * 1. **Session provenance** — [state] must carry the exact [currentSession] instance, or the
     *    write is refused with [TonexError.StaleSessionState]. Reference (`===`) equality only,
     *    matching [SessionId]'s deliberate lack of a public constructor or structural equality.
     *    This alone only proves [state] was read *during this connection* — see the next check
     *    for why that is not enough on its own.
     * 2. **Read freshness** — [state] must be [currentSession]'s *current* generation: not
     *    superseded by a later read (or failed observation — see [PedalState.create]'s KDoc), and
     *    not already spent on an earlier successful patch (see step 7). A session can run for a
     *    long time, and the pedal has no host UI — the user can change global state directly at
     *    the footswitch (FR6) at any point, at which point every [PedalState] read before that
     *    moment is stale even though its [PedalState.sessionId] still matches. Checked as
     *    `state.readGeneration != currentSession.latestReadGeneration()`, refused with
     *    [TonexError.StaleSessionState] on mismatch — see [PedalState]'s freshness contract, which
     *    is what makes this check meaningful (it depends on S9 minting a new generation for every
     *    fresh observation of state, explicit, pushed, or *failed*, and on step 7 below consuming
     *    the generation on every successful write). The rejection message distinguishes *why* the
     *    generation is no longer current — already spent by this caller's own earlier successful
     *    patch ([SessionId.wasMostRecentlySpentByWrite]) versus superseded by something else — so a
     *    caller reusing its own just-spent [PedalState] gets an accurate explanation instead of
     *    generic "superseded" wording (issue #12 round-4 review, LOW finding #1).
     * 3. **Minimum plausible length** — [state] must be at least
     *    [StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE] bytes — a floor derived from the pedal's known
     *    field layout, not merely "long enough to index safely" — or the write is refused with
     *    [TonexError.BlobTooShortToPatch]. Checked *before* step 4 so a too-short blob is always
     *    diagnosed as "too short," even if it also happens to differ from an already-pinned size
     *    (issue #12 round-3 review — the reverse order let the less useful
     *    [TonexError.BlobSizeChangedSinceHandshake] mask this more useful diagnosis, and also let
     *    a too-short *first* read for a session pin the session to an implausible size at all; see
     *    [SessionId.pinOrWidenBlobSize]).
     * 4. **Length vs. pinned size** — [state]'s length must match the length pinned (or widened) at
     *    [currentSession]'s largest plausible-sized read so far (see [SessionId.pinOrWidenBlobSize]
     *    and [TonexError.BlobSizeChangedSinceHandshake]'s KDoc for why that is not literally "the
     *    handshake blob" despite the error's name), or the write is refused with
     *    [TonexError.BlobSizeChangedSinceHandshake]. A layout shift almost always changes the
     *    blob's overall length, so this is the primary shape-drift signal.
     * 5. **Shape sanity** — the bytes currently sitting at the slot-preset and active-slot
     *    offsets must look like what those fields are documented to hold (plausible preset
     *    indices, a plausible slot number). A firmware whose state layout has moved again since
     *    the pin in [StateBlobOffsets] is far more likely to fail this check than to coincidentally
     *    produce four plausible-looking bytes, and failing here is the difference between a loud,
     *    typed error and a silent write to the wrong field. Refused with
     *    [TonexError.ImplausibleStateBlobShape].
     * 6. **Preset range (defense-in-depth)** — if [preset] is supplied, `preset.value` must fall
     *    in `PresetIndex.VALID_RANGE`, checked again here with an ordinary runtime comparison
     *    rather than trusted from [PresetIndex]'s own constructor guard — because that guard is a
     *    `@JvmInline value class` `init` block, which does not run for a caller that reaches this
     *    boundary without going through `PresetIndex`'s Kotlin constructor (e.g. a Java or
     *    reflection caller). Refused with [TonexError.InvalidPresetIndex]. This closes the gap
     *    for this entry point specifically; it is not a blanket guarantee about every other
     *    consumer of a `PresetIndex` in this codebase — see [TonexError.InvalidPresetIndex]'s
     *    KDoc.
     * 7. **Single-use consumption** — once steps 1-6 all pass, [state]'s generation is *consumed*:
     *    [SessionId.consumeReadGeneration] atomically advances [currentSession]'s
     *    latest-read-generation counter past [PedalState.readGeneration]. This is what makes a
     *    [PedalState] single-use as a write authorization, not merely "was this the freshest read
     *    at some point": a second patch call reusing the exact same [state] — even with zero other
     *    work between the two calls — fails step 2 above, because [state]'s generation is no
     *    longer [currentSession]'s current one. Refused with [TonexError.StaleSessionState] on the
     *    (rare, race-only) chance the atomic advance itself fails. Closes the blocker where two
     *    back-to-back calls to a patch function against the same read let the second silently
     *    revert the first (issue #12 round-3 review).
     *
     * Returns a *fresh, defensive copy* of the blob's bytes on success — callers patch that copy
     * directly; [state] itself is never mutated (it has no mutable surface to mutate).
     */
    private fun prepareForPatch(
        state: PedalState,
        currentSession: SessionId,
        preset: PresetIndex?,
    ): TonexResult<ByteArray> {
        if (state.sessionId !== currentSession) {
            return TonexResult.Failure(
                TonexError.StaleSessionState(
                    details = "state blob's SessionId is not the same instance as the session presented for this write",
                    sameSession = false,
                ),
            )
        }

        if (state.readGeneration != currentSession.latestReadGeneration()) {
            // Distinguish *why* this generation is no longer current: it may have been spent by
            // this caller's own earlier successful patch, or superseded by something else (a
            // newer read, or a failed observation attempt - see PedalState.create's KDoc). Those
            // are different situations with different accurate explanations, and conflating them
            // under one generic message left the dedicated "already used for a write" wording
            // effectively unreachable in the common sequential-reuse case (issue #12 round-4
            // review, LOW finding #1) - see SessionId.wasMostRecentlySpentByWrite's KDoc.
            return if (currentSession.wasMostRecentlySpentByWrite(state.readGeneration)) {
                TonexResult.Failure(
                    TonexError.StaleSessionState(
                        details = "state blob has already been used to authorize a write - a PedalState is " +
                            "single-use for a write; re-read state and retry",
                        sameSession = true,
                    ),
                )
            } else {
                TonexResult.Failure(
                    TonexError.StaleSessionState(
                        details = "state blob is not this session's most recently observed read - it may have " +
                            "been superseded by a later read or by the pedal pushing an update (e.g. an " +
                            "external/footswitch change); re-read state immediately before patching",
                        sameSession = true,
                    ),
                )
            }
        }

        val bytes = state.copyOfBytes()

        // Minimum-length checked BEFORE the pin comparison: a too-short blob must be diagnosed as
        // "too short" (BlobTooShortToPatch), not misreported as "size changed since the pin"
        // (BlobSizeChangedSinceHandshake) just because it also happens to differ from an
        // already-established pin (issue #12 round-3 review - the more useful diagnosis was being
        // masked by the less useful one under the old ordering).
        if (bytes.size < StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE) {
            return TonexResult.Failure(
                TonexError.BlobTooShortToPatch(
                    minimumSize = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE,
                    actualSize = bytes.size,
                ),
            )
        }

        val pinnedSize = currentSession.pinnedBlobSize()
        if (pinnedSize != null && bytes.size != pinnedSize) {
            return TonexResult.Failure(
                TonexError.BlobSizeChangedSinceHandshake(pinnedSize = pinnedSize, actualSize = bytes.size),
            )
        }

        if (!StateBlobReader.looksLikeSlotRegion(bytes)) {
            return TonexResult.Failure(TonexError.ImplausibleStateBlobShape(actualSize = bytes.size))
        }

        if (preset != null && preset.value !in PresetIndex.VALID_RANGE) {
            return TonexResult.Failure(TonexError.InvalidPresetIndex(preset.value))
        }

        // Consume state's read-generation as the final step of authorization: this is what makes
        // a PedalState single-use for a write, not merely "was the freshest read at some point."
        // A concurrent or repeated call reusing the same `state` will fail here (or fail the
        // freshness check above, on a second sequential call) rather than silently succeeding a
        // second time (issue #12 round-3 review - the blocker finding).
        if (!currentSession.consumeReadGeneration(state.readGeneration)) {
            return TonexResult.Failure(
                TonexError.StaleSessionState(
                    details = "state blob has already been used to authorize a write - a PedalState is " +
                        "single-use for a write; re-read state and retry",
                    sameSession = true,
                ),
            )
        }

        return TonexResult.Success(bytes)
    }
}
