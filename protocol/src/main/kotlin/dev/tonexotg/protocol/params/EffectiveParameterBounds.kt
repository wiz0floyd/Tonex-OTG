package dev.tonexotg.protocol.params

import dev.tonexotg.protocol.ParameterId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The effective upper bound for a parameter — the value every write-validation and UI-range call
 * site must consult, instead of reading [ParameterSpec.max] off [ParameterRegistry] directly.
 *
 * For nearly all 116 parameters this is simply the registry's static `max`. The one deliberate
 * exception is [ParameterRegistry.SELF_WIDENING_PARAMETER_IDS] (issue #80): a small,
 * explicitly-reviewed allowlist of SELECT parameters whose true option count depends on
 * per-pedal installed content, not a firmware constant — for those, a genuine pedal read
 * observing a value above the current ceiling permanently raises it for the rest of the session
 * (and, via `:app`'s DataStore persistence, across restarts too).
 *
 * This exists so `:protocol` never needs a mutable [ParameterRegistry] (a runtime-shared
 * `object` singleton — mutating it risks order-dependent tests, see CLAUDE.md's S5/S7 note) and
 * never needs to know about DataStore (`:protocol` must stay Android-free, issue #15). `:app`
 * owns persistence; `:protocol` only owns the in-session widening logic and reports newly
 * observed values back up via [SelfWideningParameterBounds.widenedMaxima] for `:app` to persist.
 */
fun interface EffectiveParameterBounds {
    /**
     * The effective upper bound to validate writes and size UI ranges against for [id]. Always
     * `>=` [ParameterRegistry.byIndex]'s static `max` for the same id — the static value is a
     * floor that never lowers.
     *
     * @throws IllegalArgumentException if [id] is not a registered parameter.
     */
    fun effectiveMax(id: ParameterId): Float

    companion object {
        /**
         * The trivial implementation: always the registry's static max, for every parameter.
         * Used as the default everywhere a caller doesn't care about self-widening (most tests,
         * and any construction path that predates this feature).
         */
        val STATIC: EffectiveParameterBounds = EffectiveParameterBounds { id ->
            ParameterRegistry.byIndex(id.index)?.max
                ?: throw IllegalArgumentException("Invalid parameter id: $id")
        }
    }
}

/**
 * The stateful, session-scoped implementation of [EffectiveParameterBounds]: self-widens the
 * ceiling for exactly [ParameterRegistry.SELF_WIDENING_PARAMETER_IDS], seeded from whatever
 * `:app` has already persisted, and republishes every subsequent widening via [widenedMaxima] so
 * `:app` can persist it forward.
 *
 * ## Observation, not construction, is how the ceiling grows
 * [observeRead] is the only way this class's effective max for an allowlisted id ever increases.
 * It must be called **only** from a genuine pedal state read (a state-blob/preset-parameter
 * extraction) — never from the optimistic post-write cache update
 * ([dev.tonexotg.protocol.connection.DefaultTonexController]'s `writeParameterLocked`), which
 * would let an unvalidated caller-supplied value silently raise the ceiling for itself. See
 * issue #80.
 *
 * ## Not general — deliberately
 * Calling [observeRead] for a non-allowlisted id is a harmless no-op: this class does not widen
 * anything outside [ParameterRegistry.SELF_WIDENING_PARAMETER_IDS], by design (CLAUDE.md's
 * "Review rigor" section — a blanket "any out-of-range read widens its bound" mechanism would
 * swallow real state-blob decode bugs on the other ~106 preset parameters instead of surfacing
 * [dev.tonexotg.protocol.TonexError.ParameterValueOutOfRange] loudly).
 *
 * @param initialWidened a seed map of already-observed values (typically loaded from `:app`'s
 *   DataStore before the controller is constructed), keyed by [ParameterId]. Only entries whose
 *   id is in [ParameterRegistry.SELF_WIDENING_PARAMETER_IDS] and whose value exceeds that id's
 *   static registry max have any effect — anything else is silently ignored rather than
 *   rejected, since a stale/foreign persisted entry is not this class's problem to validate.
 */
class SelfWideningParameterBounds(
    initialWidened: Map<ParameterId, Float> = emptyMap(),
) : EffectiveParameterBounds {

    private val _widenedMaxima = MutableStateFlow(
        initialWidened
            .filterKeys { it.index in ParameterRegistry.SELF_WIDENING_PARAMETER_IDS }
            .mapNotNull { (id, value) ->
                // Same plausibility guard as observeRead (see that function's KDoc, "Plausibility
                // guard") — a seed value is not a "genuine pedal read" this class trusts more than
                // a live one; a stray NaN/Infinity/huge-finite/non-integer value that somehow
                // reached a persisted DataStore entry (or a corrupted prefs file) must not
                // resurrect the write-path bypass this guard exists to close.
                if (!isPlausibleSelfWideningValue(value)) return@mapNotNull null
                val staticMax = ParameterRegistry.byIndex(id.index)?.max ?: return@mapNotNull null
                if (value > staticMax) id to value else null
            }
            .toMap(),
    )

    /**
     * The current widened ceiling for every allowlisted id that has ever been observed (this
     * session, or seeded from persistence) above its static max. Absence means "not yet widened
     * beyond the static max" — `:app` should treat this as the full set of rows worth persisting,
     * merged into whatever it already has on disk (this flow only ever grows, per id).
     */
    val widenedMaxima: StateFlow<Map<ParameterId, Float>> = _widenedMaxima.asStateFlow()

    override fun effectiveMax(id: ParameterId): Float {
        val staticMax = ParameterRegistry.byIndex(id.index)?.max
            ?: throw IllegalArgumentException("Invalid parameter id: $id")
        val widened = _widenedMaxima.value[id] ?: return staticMax
        return maxOf(staticMax, widened)
    }

    /**
     * Records a genuine pedal read of [value] for [id]. A no-op unless [id] is in
     * [ParameterRegistry.SELF_WIDENING_PARAMETER_IDS], [value] is a
     * [plausible option index][isPlausibleSelfWideningValue], and [value] exceeds the current
     * effective max — the ceiling only ever grows, never shrinks, and never for a non-allowlisted
     * id.
     *
     * ## Plausibility guard (Opus review, PR #81)
     * `NaN`/`Infinity` must be rejected outright, not merely fail to widen. Both `<=` and `>`
     * comparisons against `NaN` are always `false` in IEEE 754 (and by extension Kotlin `Float`),
     * so the naive `if (value <= staticMax) return` guard alone would let a `NaN` [value] fall
     * through and get stored as the ceiling — [effectiveMax] would then return `NaN`, which
     * silently defeats *every* downstream upper-bound check that consults it:
     * `DefaultTonexController.writeParameterLocked`'s `value > effectiveMax` is `false` for any
     * `value` when `effectiveMax` is `NaN` (upper-bound validation fully bypassed), and
     * `ParameterWriteMessage.encode`'s `value.coerceIn(spec.min, effectiveMax)` returns the value
     * unmodified — `coerceIn`'s own `minimumValue > maximumValue` guard is likewise `false`
     * against a `NaN` `maximumValue`. This is not theoretical: `Varint`'s float decode has no
     * downstream finiteness check, so a state-blob offset drift (the same failure class as S5/S8)
     * on one of the three allowlisted ids would produce exactly this.
     *
     * `isFinite()` alone does not close a *finite*-but-implausibly-huge value (e.g. `1e30`)
     * reaching the wire as a "widened ceiling" — the same durable-corruption risk as `Infinity`
     * (both survive [initialWidened]'s seed filter and get persisted to `:app`'s DataStore
     * forever once observed, since the ceiling only ever grows). [isPlausibleSelfWideningValue]
     * additionally rejects anything outside a generous, deliberately-loose plausible range for a
     * `SELECT` option index — all three allowlisted ids are integer-valued option indices, never
     * a continuous quantity, so requiring `value == floor(value)` and `value in
     * 0f..MAX_PLAUSIBLE_SELF_WIDENING_VALUE` costs nothing for a genuine widened read while
     * closing this off completely.
     */
    fun observeRead(id: ParameterId, value: Float) {
        if (id.index !in ParameterRegistry.SELF_WIDENING_PARAMETER_IDS) return
        if (!isPlausibleSelfWideningValue(value)) return
        val staticMax = ParameterRegistry.byIndex(id.index)?.max ?: return
        if (value <= staticMax) return
        _widenedMaxima.update { current ->
            val existing = current[id]
            if (existing != null && existing >= value) current else current + (id to value)
        }
    }

    private companion object {
        /**
         * A deliberately generous ceiling on what a self-widened `SELECT` option index can
         * plausibly be — comfortably above any realistic IK/third-party IR/cab/mic content pack
         * (the existing entries top out at 38), while still closing off a finite-but-absurd value
         * (e.g. `1e30` from a state-blob offset drift, see [observeRead]'s KDoc) from durably
         * poisoning the ceiling. Not tied to any known firmware limit — if a real pedal is ever
         * observed with a genuinely higher option count than this, raise it; that is a far better
         * problem to have than the one this guard closes.
         */
        const val MAX_PLAUSIBLE_SELF_WIDENING_VALUE = 999f

        /**
         * Whether [value] is a plausible `SELECT` option index for the self-widening mechanism to
         * trust: finite, a non-negative whole number, and no larger than
         * [MAX_PLAUSIBLE_SELF_WIDENING_VALUE]. See [observeRead]'s KDoc for why each condition is
         * needed — this exists as one shared predicate so [observeRead] and the constructor's
         * [initialWidened] seed filter can never independently drift out of sync with each other.
         */
        fun isPlausibleSelfWideningValue(value: Float): Boolean =
            value.isFinite() && value >= 0f && value <= MAX_PLAUSIBLE_SELF_WIDENING_VALUE && value == kotlin.math.floor(value)
    }
}
