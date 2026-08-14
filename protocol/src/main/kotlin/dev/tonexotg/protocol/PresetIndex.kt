package dev.tonexotg.protocol

/**
 * The pedal's onboard preset slot number, `0..19` — the ToneX One stores exactly 20 presets
 * (O1: "switch between all 20 stored presets directly").
 *
 * This is a position on the pedal, not a name: preset names (when the pedal exposes them, see
 * S7/S14) and any user-assigned local alias (FR10) are layered on top of this index, never used
 * in place of it.
 *
 * The range is enforced at construction so an out-of-range index cannot be represented at all,
 * rather than relying on every call site to remember to check it.
 */
@JvmInline
value class PresetIndex(val value: Int) {

    init {
        require(value in VALID_RANGE) {
            "PresetIndex must be in $VALID_RANGE, was $value"
        }
    }

    companion object {
        /** The full, inclusive range of valid preset indices: 20 presets, 0-based. */
        val VALID_RANGE: IntRange = 0..19
    }
}

/**
 * One of the pedal's three physical footswitch slots, each of which holds an assignment to a
 * [PresetIndex]. Distinct from [PresetIndex]: a slot is a *physical stomp position* (the
 * 2-3 slot limitation O1 exists to bypass), while a preset index is *one of the 20 stored
 * presets* that a slot can point at.
 *
 * The underlying wire representation and the byte offsets at which slot assignments live in the
 * pedal's state blob are owned by [PedalState] patching (S8), not by this type — this is
 * intentionally just the closed set of three physical slots.
 */
enum class PresetSlot {
    A,
    B,
    C,
}
