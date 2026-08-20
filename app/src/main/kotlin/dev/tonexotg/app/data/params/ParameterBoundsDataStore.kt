package dev.tonexotg.app.data.params

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** File name (under the app's `datastore/` directory) for the S80/issue #80 widened-bounds store. */
private const val PARAMETER_BOUNDS_DATASTORE_NAME = "parameter_bounds"

/**
 * The app's single production `DataStore<Preferences>` instance backing
 * [WidenedParameterBoundsStore] — issue #80's self-widening ceiling persistence for
 * [dev.tonexotg.protocol.params.ParameterRegistry.SELF_WIDENING_PARAMETER_IDS].
 *
 * Same shape as [dev.tonexotg.app.data.alias.presetAliasDataStore]: a `preferencesDataStore`
 * property delegate handing back one process-lifetime instance, kept as its own separate
 * DataStore file (not folded into the preset-alias one) since the two stores have unrelated
 * lifecycles and schemas — no reason to couple their read/write contention or migration paths.
 */
val Context.parameterBoundsDataStore by preferencesDataStore(name = PARAMETER_BOUNDS_DATASTORE_NAME)
