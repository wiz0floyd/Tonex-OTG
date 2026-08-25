package dev.tonexotg.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Small, screen-agnostic on/off preferences that only ever need "did the user permanently
 * dismiss this" persistence — starting with the first-destructive-write notice's "Don't show
 * again" (D3 §5.2 follow-up). Deliberately its own tiny interface rather than reusing
 * [dev.tonexotg.app.data.alias.PresetAliasStore]'s shape: that store is keyed per [dev.tonexotg.protocol.PresetIndex]
 * and holds strings; this one is keyed by nothing (one flag, one app) and holds booleans.
 */
interface UiPreferencesStore {
    /** True once the user has checked "Don't show again" on the first-destructive-write notice. */
    val firstWriteWarningDismissed: Flow<Boolean>

    suspend fun setFirstWriteWarningDismissed(dismissed: Boolean)
}

/** [UiPreferencesStore] backed by AndroidX Preferences DataStore — see [uiPreferencesDataStore]. */
class DataStoreUiPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : UiPreferencesStore {

    override val firstWriteWarningDismissed: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[FIRST_WRITE_WARNING_DISMISSED_KEY] == true }

    override suspend fun setFirstWriteWarningDismissed(dismissed: Boolean) {
        dataStore.edit { prefs -> prefs[FIRST_WRITE_WARNING_DISMISSED_KEY] = dismissed }
    }

    private companion object {
        val FIRST_WRITE_WARNING_DISMISSED_KEY = booleanPreferencesKey("first_write_warning_dismissed")
    }
}

/**
 * In-memory [UiPreferencesStore] — the default for [dev.tonexotg.app.ui.screens.parameters.ParameterEditorViewModel]
 * so every existing test and preview keeps constructing it with no DataStore/Android dependency;
 * production wiring (`TonexApp`) passes [DataStoreUiPreferencesStore] instead.
 */
class InMemoryUiPreferencesStore(initiallyDismissed: Boolean = false) : UiPreferencesStore {
    private val state = MutableStateFlow(initiallyDismissed)

    override val firstWriteWarningDismissed: Flow<Boolean> = state

    override suspend fun setFirstWriteWarningDismissed(dismissed: Boolean) {
        state.value = dismissed
    }
}

/** File name (under the app's `datastore/` directory) for [UiPreferencesStore]'s production instance. */
private const val UI_PREFERENCES_DATASTORE_NAME = "ui_preferences"

/**
 * The app's single production `DataStore<Preferences>` instance backing [DataStoreUiPreferencesStore].
 * Same shape as [dev.tonexotg.app.data.alias.presetAliasDataStore] — its own file, unrelated
 * lifecycle/schema to the preset-alias and parameter-bounds stores.
 */
val Context.uiPreferencesDataStore by preferencesDataStore(name = UI_PREFERENCES_DATASTORE_NAME)
