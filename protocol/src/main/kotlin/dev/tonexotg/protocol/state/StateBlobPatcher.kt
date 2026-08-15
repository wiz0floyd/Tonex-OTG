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
 *   `DIRECT_MONITOR = 1` and may force stomp/AB mode. Neither offset is even named in
 *   [StateBlobOffsets], so there is no write path here capable of touching them.
 * - **No message framing.** The `{0xb9, 0x03, 0x81, 0x06, 0x03, 0x82, ...}` wrapper that turns
 *   these bytes into an outgoing wire message is [dev.tonexotg.protocol.message]'s concern, not
 *   this module's — everything here returns plain patched bytes, ready for that layer to frame
 *   and send.
 * - **No mutation just to read.** Nothing here ever needs to write a byte in order to observe
 *   pedal state; that upstream anti-pattern (writing a fake preset into Slot A just to provoke a
 *   name response, fixed upstream in V1.0.8.2) has no equivalent here at all.
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
     * @param state a blob read from the pedal during [currentSession]. Never synthesize one.
     * @param currentSession the session the caller believes is live right now; compared against
     *   [PedalState.sessionId] by reference. See [TonexError.StaleSessionState].
     * @return the patched bytes, or a [TonexResult.Failure] — never throws for an out-of-range,
     *   stale, or implausible input; see [TonexError.StaleSessionState] and
     *   [TonexError.UnexpectedBlobShape].
     */
    fun patchSlotAssignment(
        state: PedalState,
        currentSession: SessionId,
        slot: PresetSlot,
        preset: PresetIndex,
    ): TonexResult<ByteArray> {
        val prepared = prepareForPatch(state, currentSession)
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
     * @param state a blob read from the pedal during [currentSession]. Never synthesize one.
     * @param currentSession the session the caller believes is live right now; compared against
     *   [PedalState.sessionId] by reference. See [TonexError.StaleSessionState].
     * @return the patched bytes, or a [TonexResult.Failure]; see [patchSlotAssignment].
     */
    fun patchActiveSlot(
        state: PedalState,
        currentSession: SessionId,
        slot: PresetSlot,
    ): TonexResult<ByteArray> {
        val prepared = prepareForPatch(state, currentSession)
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
     * `set_preset_in_slot()`'s forced-mode or forced-direct-monitor side effects.
     *
     * Implemented as two writes over one shared copy (not as two chained calls to
     * [patchSlotAssignment] then [patchActiveSlot]) because those functions consume a
     * [PedalState] and this module has no way — deliberately — to wrap intermediate bytes back
     * into one; see the "no synthesis" contract on [StateBlobPatcher].
     *
     * @return the patched bytes, or a [TonexResult.Failure]; see [patchSlotAssignment].
     */
    fun selectPreset(
        state: PedalState,
        currentSession: SessionId,
        slot: PresetSlot,
        preset: PresetIndex,
    ): TonexResult<ByteArray> {
        val prepared = prepareForPatch(state, currentSession)
        if (prepared is TonexResult.Failure) return prepared
        val bytes = (prepared as TonexResult.Success).value

        bytes[bytes.size - StateBlobOffsets.endOffsetForSlotPreset(slot)] = preset.value.toByte()
        bytes[bytes.size - StateBlobOffsets.END_CURRENT_SLOT] = slot.ordinal.toByte()
        return TonexResult.Success(bytes)
    }

    /**
     * The shared entry gate every patch function above runs through, in this fixed order:
     *
     * 1. **Provenance** — [state] must carry the exact [currentSession] instance, or the write is
     *    refused with [TonexError.StaleSessionState]. Reference (`===`) equality only, matching
     *    [SessionId]'s deliberate lack of a public constructor or structural equality.
     * 2. **Length** — [state] must be at least [StateBlobOffsets.MAX_END_OFFSET] bytes, or every
     *    offset this module indexes cannot be trusted to exist at all, and the write is refused
     *    with [TonexError.UnexpectedBlobShape].
     * 3. **Shape sanity** — the bytes currently sitting at the slot-preset and active-slot
     *    offsets must look like what those fields are documented to hold (plausible preset
     *    indices, a plausible slot number). A firmware whose state layout has moved again since
     *    the pin in [StateBlobOffsets] is far more likely to fail this check than to coincidentally
     *    produce four plausible-looking bytes, and failing here is the difference between a loud,
     *    typed error and a silent write to the wrong field.
     *
     * Returns a *fresh, defensive copy* of the blob's bytes on success — callers patch that copy
     * directly; [state] itself is never mutated (it has no mutable surface to mutate).
     */
    private fun prepareForPatch(state: PedalState, currentSession: SessionId): TonexResult<ByteArray> {
        if (state.sessionId !== currentSession) {
            return TonexResult.Failure(
                TonexError.StaleSessionState(
                    "state blob's SessionId is not the same instance as the session presented for this write",
                ),
            )
        }

        val bytes = state.copyOfBytes()
        if (bytes.size < StateBlobOffsets.MAX_END_OFFSET) {
            return TonexResult.Failure(TonexError.UnexpectedBlobShape(expectedSize = null, actualSize = bytes.size))
        }

        if (!looksLikeSlotRegion(bytes)) {
            return TonexResult.Failure(TonexError.UnexpectedBlobShape(expectedSize = null, actualSize = bytes.size))
        }

        return TonexResult.Success(bytes)
    }

    /**
     * `true` iff the bytes at every offset [StateBlobPatcher] is prepared to write to currently
     * hold a value that field is documented to be able to hold: a preset index for each of the
     * three slot-assignment bytes, and a valid [PresetSlot] ordinal for the active-slot byte.
     *
     * This does not prove the offsets are correct for this blob — a coincidentally plausible
     * value at a shifted offset would still pass — only that they are not *obviously* wrong. See
     * the offset-drift caveat in [StateBlobOffsets]'s KDoc.
     */
    private fun looksLikeSlotRegion(bytes: ByteArray): Boolean {
        val slotPresetOffsets = intArrayOf(
            StateBlobOffsets.END_SLOT_A_PRESET,
            StateBlobOffsets.END_SLOT_B_PRESET,
            StateBlobOffsets.END_SLOT_C_PRESET,
        )
        for (offset in slotPresetOffsets) {
            val value = bytes[bytes.size - offset].toInt() and 0xFF
            if (value !in PresetIndex.VALID_RANGE) return false
        }

        val activeSlotValue = bytes[bytes.size - StateBlobOffsets.END_CURRENT_SLOT].toInt() and 0xFF
        if (activeSlotValue !in PresetSlot.entries.indices) return false

        return true
    }
}
