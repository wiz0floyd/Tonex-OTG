package dev.tonexotg.app.data.alias

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tonexotg.protocol.PresetIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [PresetAliasStore] backed by AndroidX Preferences DataStore.
 *
 * Takes the `DataStore<Preferences>` itself as a constructor parameter rather than an Android
 * `Context` — this keeps construction test-friendly (a test can point it at a temp-file-backed
 * DataStore with no Android framework involved) and keeps this class from acquiring any
 * dependency beyond "a place to persist key/value pairs". See [presetAliasDataStore] for the
 * app's actual production instance.
 */
class DataStorePresetAliasStore(
    private val dataStore: DataStore<Preferences>,
) : PresetAliasStore {

    override fun alias(preset: PresetIndex): Flow<String?> =
        dataStore.data.map { prefs -> prefs[keyFor(preset)] }

    override suspend fun setAlias(preset: PresetIndex, alias: String) {
        require(alias.isNotBlank()) {
            "Alias for preset ${preset.value} must not be blank; call clearAlias() to remove an alias"
        }
        dataStore.edit { prefs -> prefs[keyFor(preset)] = alias }
    }

    override suspend fun clearAlias(preset: PresetIndex) {
        dataStore.edit { prefs -> prefs.remove(keyFor(preset)) }
    }

    private fun keyFor(preset: PresetIndex): Preferences.Key<String> =
        stringPreferencesKey(KEY_PREFIX + preset.value)

    private companion object {
        /** One string key per preset slot, e.g. "preset_alias_0" .. "preset_alias_19". */
        const val KEY_PREFIX = "preset_alias_"
    }
}
