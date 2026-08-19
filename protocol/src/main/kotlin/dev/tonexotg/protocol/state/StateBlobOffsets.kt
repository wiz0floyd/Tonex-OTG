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
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.SessionId

/**
 * The firmware-pinned byte offsets within a [PedalState] blob that [StateBlobPatcher] is allowed
 * to touch, and nothing else in this codebase should ever hardcode independently.
 *
 * ## ⚠️ THESE OFFSETS ARE FIRMWARE-VERSION DEPENDENT — read this before changing anything here
 *
 * The pedal's whole-device state blob has no self-describing layout; every field lives at a
 * fixed byte offset that only upstream reverse-engineering (via Wireshark capture) has ever
 * documented. Offsets in this region **have moved at least once already**, and there is direct
 * evidence of it: `DIRECT_MONITOR` was a *start-relative* offset (`StateData[14]`) in upstream
 * `V1.0.6.1` and `V1.0.7.2`, and is an *end-relative* offset (`StateDataLength - 7`) in IK's
 * current `main` branch (pinned below) — the same field moved from one end of the blob's
 * addressing scheme to the other between those revisions. That field is not modelled in this
 * file (see "Fields intentionally NOT modelled here" below) precisely because this module never
 * writes it, but its drift is the concrete, citable precedent for why the four offsets that
 * *are* named here cannot be assumed stable across firmware revisions either. (An earlier
 * version of this note claimed a `V1.0.1.2` release used offsets `-12/-10/-8/-5` for these four
 * fields; that tag does not exist upstream and no available upstream revision — `V1.0.6.1`,
 * `V1.0.7.2`, `V1.0.82`, `V1.0.9.2`, `V1.0.10.2`, `V2.0.x` — ever uses those values. That claim
 * was uncorroborated and has been removed; the earliest tag actually available already uses
 * `-18/-16/-14/-11`, the values pinned below.)
 *
 * There is no way to detect a *silent* further layout shift from the blob alone — the sanity
 * checks in [StateBlobPatcher] catch an *implausible* value at these offsets, and a length
 * mismatch against the size pinned at this session's largest plausible-sized read (not literally
 * the handshake blob — see [TonexError.BlobSizeChangedSinceHandshake]'s KDoc)
 * ([StateBlobPatcher] / [TonexError.BlobSizeChangedSinceHandshake]) catches most real shifts
 * (a layout shift almost always changes the blob's overall length) — but a shift that happens to
 * preserve the blob's total length *and* leave plausible-looking values behind at these four
 * offsets would still not be caught. That residual risk is accepted rather than solved here.
 *
 * ### Pin
 * Read from **`TonexOneController`**, upstream repository
 * <https://github.com/Builty/TonexOneController>, commit
 * `7079f157107a7bc91f171e51e3da0d799d31fcfb` (2026-08-06), file
 * `source/main/usb_tonex_one.c`:
 * - `MAX_STATE_DATA` — line 76 (matches [PedalState.MAX_STATE_BYTES] = 512).
 * - `TONEX_STATE_OFFSET_START_*` / `TONEX_STATE_OFFSET_END_*` `#define`s — lines 105-119.
 *
 * This is the **only** firmware revision these offsets are known to be correct for. If a
 * connected pedal's actual firmware predates or postdates the revision IK shipped around that
 * commit, these offsets may silently point at the wrong bytes; the length and sanity checks in
 * [StateBlobPatcher] are this module's only defence against that, and they are a floor, not a
 * guarantee — see the offset-drift caveat above.
 *
 * ### Confirmed against a real captured blob (issue #25, S20)
 * All four offsets checked by hand against a real `GetState` response (160-byte `StateData`,
 * `type=0x0306`) captured from an actual pedal, independently of this codebase's own
 * `StateBlobReader` — CRC verified, header decoded from raw bytes with a throwaway script, then
 * indexed at these exact offsets: `END_SLOT_A_PRESET`→`0`, `END_SLOT_B_PRESET`→`10`,
 * `END_SLOT_C_PRESET`→`3`, `END_CURRENT_SLOT`→`1` (slot B). Slot B's preset (`10`) matches the
 * harness's independently-reported active preset for that same session exactly. This is the one
 * firmware/session this has been confirmed against, not a standing guarantee for every unit.
 *
 * ### Fields intentionally NOT modelled here
 * Upstream's `set_preset_in_slot()` also touches `TONEX_STATE_OFFSET_START_STOMP_MODE` (line 106)
 * and `TONEX_STATE_OFFSET_END_DIRECT_MONITOR` (line 113) as unconditional side effects of
 * selecting a preset — forcing A/B or stomp mode, and forcing direct monitor on. Per this
 * project's mandate (see [PedalState] and issue #12), those are side effects to strip, not to
 * port, so this module never writes them and does not even name their offsets as constants here
 * — there is nothing in this file capable of producing that write.
 */
internal object StateBlobOffsets {

    /** End-relative offset of Slot A's assigned preset-index byte. */
    const val END_SLOT_A_PRESET: Int = 18

    /** End-relative offset of Slot B's assigned preset-index byte. */
    const val END_SLOT_B_PRESET: Int = 16

    /** End-relative offset of Slot C's assigned preset-index byte. */
    const val END_SLOT_C_PRESET: Int = 14

    /**
     * End-relative offset of the currently-active-slot byte (`0` = A, `1` = B, `2` = C — the
     * same ordering as [PresetSlot], upstream's `Slot` enum).
     */
    const val END_CURRENT_SLOT: Int = 11

    /**
     * The largest end-relative offset this module ever indexes. A blob whose length is smaller
     * than this cannot safely be indexed at any of the offsets above at all — indexing it would
     * throw `ArrayIndexOutOfBoundsException` rather than land on a wrong-but-in-bounds byte. This
     * is purely an indexing-safety floor, not a plausibility floor — see [MIN_PLAUSIBLE_BLOB_SIZE]
     * for the latter, which [StateBlobPatcher] actually enforces as its length rejection.
     */
    const val MAX_END_OFFSET: Int = END_SLOT_A_PRESET

    /** Start-relative offset where the 20-entry preset colour table begins. */
    const val START_COLOUR_TABLE: Int = 22

    /**
     * Number of colour-table entries — one per onboard preset (20 presets; see
     * [dev.tonexotg.protocol.PresetIndex]).
     *
     * `internal`, not `private`: [StateBlobOffsetsTest] references this directly so that a change
     * here is caught by the [MIN_PLAUSIBLE_BLOB_SIZE] derivation test below, rather than only by
     * whatever (if anything) still hardcodes `20` elsewhere.
     */
    internal const val COLOUR_TABLE_ENTRY_COUNT: Int = 20

    /**
     * Bytes per colour-table entry (an R/G/B triple, each a `TonexVarint` — minimum 1 byte apiece
     * when small).
     *
     * `internal`, not `private` — see [COLOUR_TABLE_ENTRY_COUNT]'s KDoc for why.
     */
    internal const val COLOUR_TABLE_MIN_BYTES_PER_ENTRY: Int = 3

    /**
     * The smallest a *real* state blob can plausibly be, derived from the pedal's known field
     * layout rather than merely "long enough to index without a crash" ([MAX_END_OFFSET], the
     * previous — and far too permissive — floor: an 18-byte blob satisfied it and was accepted
     * for patching, per issue #12's review).
     *
     * Composed of: everything before the preset colour table ([START_COLOUR_TABLE] bytes), the
     * colour table itself at its smallest possible varint encoding
     * ([COLOUR_TABLE_ENTRY_COUNT] × [COLOUR_TABLE_MIN_BYTES_PER_ENTRY]), and the
     * [MAX_END_OFFSET]-byte tail this module actually indexes into. A blob shorter than this is
     * rejected with [dev.tonexotg.protocol.TonexError.BlobTooShortToPatch] before any indexing is
     * attempted, regardless of whether every individual offset would technically still be
     * in-bounds.
     *
     * ## This is the actual single source of truth for this value
     * This `const val` is a genuine computed expression over this object's own local constants
     * (all four listed above already live here), not a bare literal. [SessionId.MIN_PLAUSIBLE_BLOB_SIZE]
     * holds a separately-maintained *mirror* of this same value (`100`) — it cannot simply
     * reference this constant, because [SessionId] lives in `dev.tonexotg.protocol`, which this
     * package already imports from (see the imports above), and importing the other way around
     * here would create a circular package dependency and mean [PedalState] — deliberately opaque
     * to this package's field-layout knowledge — ends up consulting a patcher-layer constant
     * directly (issue #12 round-4 review, LOW finding #2). Because that mirror cannot simply
     * reference this expression, `StateBlobOffsetsTest` is what actually keeps the two in sync: it
     * asserts [SessionId.MIN_PLAUSIBLE_BLOB_SIZE] against a fresh computation over
     * [START_COLOUR_TABLE], [COLOUR_TABLE_ENTRY_COUNT], [COLOUR_TABLE_MIN_BYTES_PER_ENTRY], and
     * [MAX_END_OFFSET] — the named constants, not bare duplicated literals — so changing any one of
     * those four is what actually fails the test if [SessionId]'s mirror is not updated to match
     * (issue #12 round-5 review, LOW finding: an earlier version of that test asserted against
     * bare literals disconnected from these constants, so it silently stopped catching drift in
     * three of the four inputs — only [MAX_END_OFFSET] was still genuinely pinned).
     */
    const val MIN_PLAUSIBLE_BLOB_SIZE: Int =
        START_COLOUR_TABLE + (COLOUR_TABLE_ENTRY_COUNT * COLOUR_TABLE_MIN_BYTES_PER_ENTRY) + MAX_END_OFFSET

    /**
     * The end-relative offset of [slot]'s assigned-preset byte.
     */
    fun endOffsetForSlotPreset(slot: PresetSlot): Int = when (slot) {
        PresetSlot.A -> END_SLOT_A_PRESET
        PresetSlot.B -> END_SLOT_B_PRESET
        PresetSlot.C -> END_SLOT_C_PRESET
    }
}
