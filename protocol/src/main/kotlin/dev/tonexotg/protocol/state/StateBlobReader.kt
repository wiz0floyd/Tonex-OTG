package dev.tonexotg.protocol.state

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PedalState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult

/**
 * The missing read side of [StateBlobOffsets]: decodes the four slot-region bytes
 * [StateBlobPatcher] patches, without authorizing anything.
 *
 * The connection state machine (S9, issue #13) must answer "which preset is active?" from a raw
 * state blob in three places: the handshake `GetState` response (before a [dev.tonexotg.protocol.SessionId]
 * exists at all), every inbound unsolicited state push, and `selectPreset`'s slot-choice logic.
 * [StateBlobOffsets] is `internal` (reachable from this package), but nothing in `:protocol`
 * exposed a read accessor before this file — [StateBlobPatcher] only ever patches.
 *
 * ## Global-parameter read path (issue #83)
 * [globalParameterValues] decodes the same six GLOBAL-scope offsets ([StateBlobOffsets.END_BPM],
 * [StateBlobOffsets.START_INPUT_TRIM], [StateBlobOffsets.START_CAB_BYPASS],
 * [StateBlobOffsets.END_TEMPO_SOURCE], [StateBlobOffsets.END_TUNING_REF],
 * [StateBlobOffsets.END_BYPASS_MODE]) that [StateBlobPatcher]'s six `patch*` functions write,
 * mirroring upstream's `usb_tonex_one_parse_state` — added so a home-screen control can display the
 * pedal's actual live value instead of a [dev.tonexotg.protocol.ParameterSpec.default] placeholder.
 *
 * ## This does not authorize a write, and cannot be used to synthesize one
 *
 * [StateBlobPatcher]'s file-level KDoc claims *"nothing here accepts a raw [ByteArray] as its
 * starting point, and nothing here can synthesize one."* That claim is about *producing a write
 * payload* and remains true: this object lives in its own file, returns only decoded scalars
 * ([PresetSlot], [PresetIndex], or a `Map` of them), and has no code path that emits bytes. A
 * caller holding only what this object returns has nothing it could hand to
 * [dev.tonexotg.protocol.message.SetStateMessage] — there is no reconstruction path back to a
 * writable blob.
 *
 * Consistent with that read-only scope, validation here deliberately **omits** the
 * write-authorization checks [StateBlobPatcher.prepareForPatch] runs (session provenance, read
 * freshness, the pinned-size comparison, and generation consumption) — a read authorizes nothing,
 * so there is nothing to consume or compare against a [dev.tonexotg.protocol.SessionId]. Only the
 * two structural checks that apply equally to a read or a write are shared, via
 * [looksLikeSlotRegion]:
 *
 * 1. **Minimum plausible length** — [StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE] — else
 *    [TonexError.BlobTooShortToPatch].
 * 2. **Shape sanity** — the four slot-region bytes must look like what they are documented to hold
 *    (three preset indices in [PresetIndex.VALID_RANGE], one active-slot ordinal in
 *    [PresetSlot.entries.indices]) — else [TonexError.ImplausibleStateBlobShape].
 *
 * Both error types are deliberately reused from the patcher's own vocabulary (rather than minted
 * fresh for this object) so a malformed blob is diagnosed identically whether it was hit on a read
 * or a write.
 */
internal object StateBlobReader {

    /** The pedal's currently active footswitch slot, decoded from [bytes]. */
    fun activeSlot(bytes: ByteArray): TonexResult<PresetSlot> {
        val validated = validate(bytes)
        if (validated is TonexResult.Failure) return validated

        val activeSlotValue = bytes[bytes.size - StateBlobOffsets.END_CURRENT_SLOT].toInt() and 0xFF
        // validate() (via looksLikeSlotRegion) already confirmed this ordinal is in range.
        return TonexResult.Success(PresetSlot.entries[activeSlotValue])
    }

    /** [slot]'s currently assigned preset, decoded from [bytes]. */
    fun presetInSlot(bytes: ByteArray, slot: PresetSlot): TonexResult<PresetIndex> {
        val validated = validate(bytes)
        if (validated is TonexResult.Failure) return validated

        val value = bytes[bytes.size - StateBlobOffsets.endOffsetForSlotPreset(slot)].toInt() and 0xFF
        // validate() already confirmed this value is in PresetIndex.VALID_RANGE.
        return TonexResult.Success(PresetIndex(value))
    }

    /** Every footswitch slot's currently assigned preset, decoded from [bytes]. */
    fun slotAssignments(bytes: ByteArray): TonexResult<Map<PresetSlot, PresetIndex>> {
        val validated = validate(bytes)
        if (validated is TonexResult.Failure) return validated

        val assignments = PresetSlot.entries.associateWith { slot ->
            val value = bytes[bytes.size - StateBlobOffsets.endOffsetForSlotPreset(slot)].toInt() and 0xFF
            PresetIndex(value)
        }
        return TonexResult.Success(assignments)
    }

    /** The preset currently active on the pedal — [presetInSlot] of [activeSlot], decoded from [bytes]. */
    fun activePreset(bytes: ByteArray): TonexResult<PresetIndex> {
        val slot = when (val result = activeSlot(bytes)) {
            is TonexResult.Failure -> return result
            is TonexResult.Success -> result.value
        }
        return presetInSlot(bytes, slot)
    }

    /** Convenience overload of [activePreset] taking a [PedalState] directly. */
    fun activePreset(state: PedalState): TonexResult<PresetIndex> = activePreset(state.copyOfBytes())

    /** Convenience overload of [slotAssignments] taking a [PedalState] directly. */
    fun slotAssignments(state: PedalState): TonexResult<Map<PresetSlot, PresetIndex>> =
        slotAssignments(state.copyOfBytes())

    /**
     * The pedal's six GLOBAL-scope parameter values (issue #83: `BPM`, `INPUT_TRIM`,
     * `CABSIM_BYPASS`, `TEMPO_SOURCE`, `TUNING_REFERENCE`, `BYPASS` — [ParameterId]s `110`-`115`),
     * decoded from [bytes] at the exact offsets [StateBlobPatcher]'s six `patch*` functions write —
     * see [StateBlobOffsets]'s "Global-parameter offsets" KDoc for the upstream/captured-blob
     * confirmation of byte width, endianness, and anchor for each.
     */
    fun globalParameterValues(bytes: ByteArray): TonexResult<Map<ParameterId, Float>> {
        val validated = validate(bytes)
        if (validated is TonexResult.Failure) return validated

        val values = mapOf(
            ParameterId(110) to readFloatLe(bytes, bytes.size - StateBlobOffsets.END_BPM), // BPM
            ParameterId(111) to readFloatLe(bytes, StateBlobOffsets.START_INPUT_TRIM), // INPUT_TRIM
            ParameterId(112) to // CABSIM_BYPASS
                (bytes[StateBlobOffsets.START_CAB_BYPASS].toInt() and 0xFF).toFloat(),
            ParameterId(113) to // TEMPO_SOURCE
                (bytes[bytes.size - StateBlobOffsets.END_TEMPO_SOURCE].toInt() and 0xFF).toFloat(),
            ParameterId(114) to // TUNING_REFERENCE
                readUint16Le(bytes, bytes.size - StateBlobOffsets.END_TUNING_REF).toFloat(),
            ParameterId(115) to // BYPASS
                (bytes[bytes.size - StateBlobOffsets.END_BYPASS_MODE].toInt() and 0xFF).toFloat(),
        )
        return TonexResult.Success(values)
    }

    /** Convenience overload of [globalParameterValues] taking a [PedalState] directly. */
    fun globalParameterValues(state: PedalState): TonexResult<Map<ParameterId, Float>> =
        globalParameterValues(state.copyOfBytes())

    /**
     * Reads a 4-byte little-endian IEEE-754 float from [bytes] at [index]..[index]+3 — the exact
     * inverse of [StateBlobPatcher]'s private `writeFloatLe`, kept as a separate copy in this file
     * rather than shared, since one is `private` to each object and neither is part of the other's
     * public contract.
     */
    private fun readFloatLe(bytes: ByteArray, index: Int): Float {
        val bits = (bytes[index].toInt() and 0xFF) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            ((bytes[index + 2].toInt() and 0xFF) shl 16) or
            ((bytes[index + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    /** Reads a 2-byte little-endian `uint16` from [bytes] at [index]..[index]+1. */
    private fun readUint16Le(bytes: ByteArray, index: Int): Int =
        (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)

    /**
     * The two structural checks shared by every accessor above — see class KDoc for why the
     * write-authorization checks are deliberately not part of this.
     */
    private fun validate(bytes: ByteArray): TonexResult<Unit> {
        if (bytes.size < StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE) {
            return TonexResult.Failure(
                TonexError.BlobTooShortToPatch(
                    minimumSize = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE,
                    actualSize = bytes.size,
                ),
            )
        }
        if (!looksLikeSlotRegion(bytes)) {
            return TonexResult.Failure(TonexError.ImplausibleStateBlobShape(actualSize = bytes.size))
        }
        return TonexResult.Success(Unit)
    }

    /**
     * `true` iff the bytes at every slot-region offset currently hold a value that field is
     * documented to be able to hold: a preset index for each of the three slot-assignment bytes,
     * and a valid [PresetSlot] ordinal for the active-slot byte.
     *
     * Shared by [StateBlobReader] (this object) and [StateBlobPatcher.prepareForPatch] so a
     * malformed blob is diagnosed identically on a read or a write, and so the two checks cannot
     * drift apart. This does not prove the offsets are correct for this blob — a coincidentally
     * plausible value at a shifted offset would still pass — only that they are not *obviously*
     * wrong. See the offset-drift caveat in [StateBlobOffsets]'s KDoc.
     */
    internal fun looksLikeSlotRegion(bytes: ByteArray): Boolean {
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
