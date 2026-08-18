package dev.tonexotg.app.data.alias

import dev.tonexotg.protocol.PresetIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local, phone-only override for a preset's display name (S14 / FR10, O5).
 *
 * The ToneX One exposes real preset names on the pedal (a 32-byte field in the preset-details
 * response, per S7) — so, per the research note on issue #19, a local alias is an **override
 * layer, not a substitute**:
 * ```
 * displayName = localAlias ?: pedalName
 * ```
 * Names are read-only on the pedal (this pedal has no name-write command, unlike the Valeton
 * GP5 driver in the same upstream project) — an alias never round-trips to hardware. It lives
 * only in this store. Nothing in this package writes to, or even depends on, the pedal
 * connection: every method here takes only a [PresetIndex] (and, for resolution, a caller-
 * supplied pedal name), so aliases are readable before a pedal is ever connected.
 *
 * Implementations MUST persist across process death (an app restart must see the same aliases)
 * and MUST NOT attempt to write an alias back to the pedal.
 */
interface PresetAliasStore {

    /**
     * The raw, locally-stored alias for [preset], or `null` if the user has never set one (or
     * has cleared it). Most callers want [displayName] instead — this exists for UI that needs
     * to know explicitly whether an override is active (e.g. to show a "reset" affordance only
     * when one exists).
     */
    fun alias(preset: PresetIndex): Flow<String?>

    /**
     * The name to actually show for [preset]: the local alias if one is set, otherwise
     * [pedalName] unchanged. [pedalName] is supplied by the caller (from the pedal's
     * preset-details response, or any placeholder the caller likes before a pedal is
     * connected) — this store has no notion of a connection and never reads it itself.
     */
    fun displayName(preset: PresetIndex, pedalName: String): Flow<String> =
        alias(preset).map { resolveDisplayName(it, pedalName) }

    /**
     * Sets the local alias for [preset] to [alias].
     *
     * @throws IllegalArgumentException if [alias] is blank — an empty override is never useful
     * (it would just have to fall back to the pedal name anyway); call [clearAlias] instead to
     * remove an override. Fail fast here rather than silently reinterpreting a blank string as
     * "clear".
     */
    suspend fun setAlias(preset: PresetIndex, alias: String)

    /**
     * Removes any local alias for [preset]. [displayName] subsequently falls back to whatever
     * pedal name the caller supplies — never to an empty string.
     */
    suspend fun clearAlias(preset: PresetIndex)
}

/**
 * The pure `displayName = localAlias ?: pedalName` resolution rule, factored out so it can be
 * unit tested with no [PresetAliasStore] implementation — real or fake — in the loop at all.
 */
internal fun resolveDisplayName(localAlias: String?, pedalName: String): String = localAlias ?: pedalName
