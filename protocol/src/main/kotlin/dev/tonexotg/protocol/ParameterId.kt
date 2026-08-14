package dev.tonexotg.protocol

/**
 * Which of the two groups a parameter belongs to, matching the pedal's own wire layout (S6).
 */
enum class ParameterScope {
    /** One of the 109 per-preset parameters (wire indices 0-108); saved as part of a preset. */
    PRESET,

    /**
     * One of the 7 device-wide parameters (wire indices 110-116): BPM, input trim, cab-sim
     * bypass, tempo source, tuning reference, bypass, master volume. Not part of any preset.
     */
    GLOBAL,
}

/**
 * The pedal's wire-position index for one parameter, `0..108` (preset-scoped) or `110..116`
 * (global-scoped).
 *
 * The upstream comment is explicit that these "are defined in the same order as they are sent
 * by the Pedal" — index is positional and load-bearing, not an arbitrary label (S6). Index
 * `109` is a `"LAST"` sentinel in the upstream table, not a real parameter, and is deliberately
 * excluded from [VALID_RANGE] here so it cannot be constructed as one.
 *
 * This type carries no name, type, or range information of its own — that is [ParameterSpec],
 * looked up by `ParameterId` in the registry S6 builds. Keeping the identity separate from the
 * metadata means the 116-entry registry is the single place that can get a parameter's shape
 * wrong, not every call site that merely refers to a parameter by id.
 */
@JvmInline
value class ParameterId(val index: Int) {

    init {
        require(index in PRESET_RANGE || index in GLOBAL_RANGE) {
            "ParameterId $index is not a valid preset ($PRESET_RANGE) or global ($GLOBAL_RANGE) index " +
                "(109 is the upstream LAST sentinel and is not a real parameter)"
        }
    }

    companion object {
        /** Wire indices of the 109 per-preset parameters. */
        val PRESET_RANGE: IntRange = 0..108

        /** Wire indices of the 7 global parameters. */
        val GLOBAL_RANGE: IntRange = 110..116
    }
}

/**
 * The three shapes a parameter's value can take on the pedal (S6).
 *
 * All three are transmitted as `float32` on the wire, even switches and selectors — see
 * [ParameterSpec] for why `min`/`max` still matter for every type.
 */
enum class ParameterType {
    /** A binary on/off value, encoded on the wire as `0.0f` / `1.0f`. */
    SWITCH,

    /**
     * A choice among a fixed set of named options (e.g. reverb model, cabinet type), encoded on
     * the wire as the option's index, as a `float32`. The specific named options for a given
     * `SELECT` parameter are owned by the registry that constructs its [ParameterSpec] (S6),
     * not by this type.
     */
    SELECT,

    /** A continuous value between [ParameterSpec.min] and [ParameterSpec.max]. */
    RANGE,
}

/**
 * The full description of one parameter: identity, shape, and legal range.
 *
 * Ported from the upstream `tModellerParameter` table (`tonex_params.c`/`.h`); the 116-entry
 * registry itself is built in S6, not here — this is only the shape each entry takes.
 *
 * @property id this parameter's wire position; see [ParameterId].
 * @property scope whether this is a per-preset or a global parameter.
 * @property name the parameter's short upstream name (e.g. `"GATE_THRESH"`), for logging/lookup
 *   — not necessarily fit for direct UI display.
 * @property type see [ParameterType].
 * @property min the lowest legal value, in this parameter's engineering units (see [unit]).
 *   **This, not [default], is load-bearing**: upstream clamps to `min`/`max` and (for some UI
 *   affordances) scales a 0-127 control range against them. For `SWITCH`, this is always `0.0f`;
 *   for `SELECT`, the index of the first option.
 * @property max the highest legal value, in the same units as [min]. For `SWITCH`, always
 *   `1.0f`; for `SELECT`, the index of the last option.
 * @property default the value this entry initializes to in the upstream table. Explicitly
 *   **not** authoritative pedal state — the upstream source comment notes it "is overridden by
 *   the preset on load." Use it only as a placeholder before the pedal has been read, never as
 *   a substitute for an actual read.
 * @property unit a short label for [min]/[max]/[default]'s engineering units (e.g. `"dB"`,
 *   `"Hz"`, `"ms"`, `"BPM"`, `"%"`), or the empty string when the value is dimensionless (all
 *   `SWITCH` parameters, and `SELECT` parameters, which are option indices rather than a
 *   physical quantity). One notable case for registry authors: master volume's *engineering*
 *   range is `-40..3` (matching the full-size ToneX) even though the ToneX One's own display
 *   shows `0..10` — the wire/display conversion is S7's concern, not this type's.
 */
data class ParameterSpec(
    val id: ParameterId,
    val scope: ParameterScope,
    val name: String,
    val type: ParameterType,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String,
)
