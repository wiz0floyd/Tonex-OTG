package dev.tonexotg.app.data.alias

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A minimal in-memory [DataStore] fake, backed by nothing but a [MutableStateFlow] — no real
 * DataStore file, no `Context`, no Android framework involvement at all.
 *
 * Per issue #19's acceptance criteria ("unit tested without a real DataStore file where
 * practical"), this is what [PresetAliasStoreTest] uses for the bulk of its cases: it exercises
 * exactly the same `DataStore<Preferences>` contract [DataStorePresetAliasStore] depends on
 * (`data` / `updateData`), just without ever touching disk. The one exception is the
 * restart-persistence test, which deliberately *does* use a real temp-file-backed DataStore,
 * because "survives process death" is a claim about the disk-backed implementation that an
 * in-memory fake cannot demonstrate by construction.
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
        }
}
