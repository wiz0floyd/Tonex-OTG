package dev.tonexotg.app.data.alias

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** File name (under the app's `datastore/` directory) for the S14 preset-alias store. */
private const val PRESET_ALIASES_DATASTORE_NAME = "preset_aliases"

/**
 * The app's single production `DataStore<Preferences>` instance backing [DataStorePresetAliasStore].
 *
 * `preferencesDataStore` is a property delegate that hands back the same DataStore instance for
 * the lifetime of the process, no matter how many times this property is accessed — the usual
 * AndroidX pattern, and the reason this is a top-level extension property rather than something
 * constructed ad hoc per call site. Not wired into any screen or view model yet (that's S16,
 * blocked on this story) — this is the construction seam that S16 is expected to use:
 * `DataStorePresetAliasStore(context.presetAliasDataStore)`.
 */
val Context.presetAliasDataStore by preferencesDataStore(name = PRESET_ALIASES_DATASTORE_NAME)
