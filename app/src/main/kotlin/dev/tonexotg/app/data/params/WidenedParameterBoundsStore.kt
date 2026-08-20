package dev.tonexotg.app.data.params

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.params.ParameterRegistry
import dev.tonexotg.protocol.params.SelfWideningParameterBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * `:app`'s DataStore-backed persistence for issue #80's self-widening ceiling
 * ([ParameterRegistry.SELF_WIDENING_PARAMETER_IDS]) — the piece `:protocol` deliberately does not
 * own, since `:protocol` must stay Android-free (issue #15) and [ParameterRegistry] must stay
 * immutable (see [SelfWideningParameterBounds]'s own KDoc). One float preference key per
 * allowlisted parameter id, e.g. `"widened_max_25"` for `VIR_CABINET_MODEL`.
 *
 * ## Construction-time sequencing this depends on (see `TonexSessionHolder.build`)
 * [loadSeed] must run — and its result must be handed to [SelfWideningParameterBounds]'s
 * constructor — **before** that instance is passed into `DefaultTonexController`. Otherwise a
 * previously-widened ceiling from a prior session would not be honoured until some new read
 * happened to re-observe the same high value, contradicting issue #80's "persists across app
 * restart" acceptance criterion. [persistForward] should then be started immediately afterwards,
 * for the life of the session's own [CoroutineScope].
 */
object WidenedParameterBoundsStore {

    /**
     * Loads whatever widened maxima were persisted from a prior session, keyed by [ParameterId].
     * Only allowlisted ids are ever read or written here — see [keyFor] — so this can never pick
     * up a stray/foreign preference key even if one somehow existed in this DataStore file.
     */
    suspend fun loadSeed(dataStore: DataStore<Preferences>): Map<ParameterId, Float> {
        val prefs = dataStore.data.first()
        return ParameterRegistry.SELF_WIDENING_PARAMETER_IDS.mapNotNull { index ->
            val value = prefs[keyFor(index)] ?: return@mapNotNull null
            ParameterId(index) to value
        }.toMap()
    }

    /**
     * Persists every subsequent widening [bounds] reports, for the life of [scope]. `widenedMaxima`
     * only ever grows per id ([SelfWideningParameterBounds]'s own contract — "the ceiling only
     * ever grows, never shrinks"), so each emission is written straight through with no
     * read-modify-write reconciliation needed: a later, larger value for the same id simply
     * overwrites the smaller one already on disk, and the floor this represents can therefore
     * never lower across restarts either.
     */
    fun persistForward(scope: CoroutineScope, dataStore: DataStore<Preferences>, bounds: SelfWideningParameterBounds) {
        bounds.widenedMaxima
            .onEach { widened ->
                if (widened.isEmpty()) return@onEach
                dataStore.edit { prefs ->
                    widened.forEach { (id, value) -> prefs[keyFor(id.index)] = value }
                }
            }
            .launchIn(scope)
    }

    private fun keyFor(index: Int) = floatPreferencesKey("widened_max_$index")
}
