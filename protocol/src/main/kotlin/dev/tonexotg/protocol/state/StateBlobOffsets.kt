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

/**
 * The firmware-pinned byte offsets within a [PedalState] blob that [StateBlobPatcher] is allowed
 * to touch, and nothing else in this codebase should ever hardcode independently.
 *
 * ## ⚠️ THESE OFFSETS ARE FIRMWARE-VERSION DEPENDENT — read this before changing anything here
 *
 * The pedal's whole-device state blob has no self-describing layout; every field lives at a
 * fixed byte offset that only upstream reverse-engineering (via Wireshark capture) has ever
 * documented. Those offsets **moved once already** between upstream releases: V1.0.1.2 used
 * end-relative offsets `-12/-10/-8/-5` for slot A/B/C/current-slot; a later firmware revision
 * changed the state layout and IK's own `main` branch (pinned below) uses `-18/-16/-14/-11`
 * for the same four fields. There is no way to detect a *silent* third layout shift from the
 * blob alone — the sanity checks in [StateBlobPatcher] catch an *implausible* value at these
 * offsets, but a shift that happens to leave plausible-looking values behind would not be
 * caught. See the "Uncertainty" note in this module's PR/report for why that residual risk is
 * accepted rather than solved here.
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
     * than this cannot safely be indexed at any of the offsets above without risking either an
     * out-of-bounds read or (worse) silently patching a byte that belongs to a different,
     * unrelated field — so [StateBlobPatcher] treats `size < MAX_END_OFFSET` as a hard rejection.
     */
    const val MAX_END_OFFSET: Int = END_SLOT_A_PRESET

    /**
     * The end-relative offset of [slot]'s assigned-preset byte.
     */
    fun endOffsetForSlotPreset(slot: PresetSlot): Int = when (slot) {
        PresetSlot.A -> END_SLOT_A_PRESET
        PresetSlot.B -> END_SLOT_B_PRESET
        PresetSlot.C -> END_SLOT_C_PRESET
    }
}
