package dev.tonexotg.app.data.alias

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import dev.tonexotg.protocol.PresetIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Exercises [PresetAliasStore] / [DataStorePresetAliasStore] against issue #19's acceptance
 * criteria:
 *  - alias overrides the pedal name; clearing restores it (not an empty string)
 *  - persists across process death
 *  - readable with no pedal ever connected (every method here only ever takes a [PresetIndex]
 *    and a caller-supplied pedal name — nothing here talks to a pedal)
 *  - no attempt to write a name back to the pedal (there is no such call in this package at all)
 *
 * Most cases run against [InMemoryPreferencesDataStore] (no real DataStore file); the
 * [restart persistence][`alias set before a simulated restart is still readable after it`] case
 * is the deliberate exception, since only a real disk-backed DataStore can demonstrate that.
 */
class PresetAliasStoreTest {

    private val presetZero = PresetIndex(0)
    private val presetOne = PresetIndex(1)

    // --- resolveDisplayName: the pure override rule, no store involved at all ---

    @Test
    fun `resolveDisplayName falls back to the pedal name when no local alias is set`() {
        assertEquals("Clean Boost", resolveDisplayName(localAlias = null, pedalName = "Clean Boost"))
    }

    @Test
    fun `resolveDisplayName prefers the local alias over the pedal name`() {
        assertEquals("My Lead Tone", resolveDisplayName(localAlias = "My Lead Tone", pedalName = "Clean Boost"))
    }

    // --- DataStorePresetAliasStore, backed by an in-memory fake ---

    @Test
    fun `alias emits null for a preset that has never been set`() = runTest {
        val store = DataStorePresetAliasStore(InMemoryPreferencesDataStore())

        store.alias(presetZero).test {
            assertNull(awaitItem())
        }
    }

    @Test
    fun `alias is readable with no pedal ever having been supplied or connected`() = runTest {
        // Construction takes only a DataStore; alias() takes only a PresetIndex. No pedal
        // handle, connection, or name is required anywhere in this call chain.
        val store: PresetAliasStore = DataStorePresetAliasStore(InMemoryPreferencesDataStore())

        store.alias(presetZero).test {
            assertNull(awaitItem())
        }
    }

    @Test
    fun `setAlias then alias emits the newly set value`() = runTest {
        val store = DataStorePresetAliasStore(InMemoryPreferencesDataStore())

        store.setAlias(presetZero, "My Lead Tone")

        store.alias(presetZero).test {
            assertEquals("My Lead Tone", awaitItem())
        }
    }

    @Test
    fun `displayName overrides the pedal name once an alias is set`() = runTest {
        val store = DataStorePresetAliasStore(InMemoryPreferencesDataStore())

        store.setAlias(presetZero, "My Lead Tone")

        store.displayName(presetZero, pedalName = "Clean Boost").test {
            assertEquals("My Lead Tone", awaitItem())
        }
    }

    @Test
    fun `clearAlias restores the pedal name, not an empty string`() = runTest {
        val store = DataStorePresetAliasStore(InMemoryPreferencesDataStore())

        store.setAlias(presetZero, "My Lead Tone")
        store.clearAlias(presetZero)

        store.alias(presetZero).test {
            assertNull(awaitItem())
        }
        store.displayName(presetZero, pedalName = "Clean Boost").test {
            val shown = awaitItem()
            assertEquals("Clean Boost", shown)
            assert(shown.isNotEmpty()) { "cleared alias must fall back to the pedal name, never an empty string" }
        }
    }

    @Test
    fun `clearAlias on a preset with no alias set is a harmless no-op`() = runTest {
        val store = DataStorePresetAliasStore(InMemoryPreferencesDataStore())

        store.clearAlias(presetZero)

        store.alias(presetZero).test {
            assertNull(awaitItem())
        }
    }

    @Test
    fun `setAlias rejects a blank alias rather than silently treating it as clear`() = runTest {
        val store = DataStorePresetAliasStore(InMemoryPreferencesDataStore())

        try {
            store.setAlias(presetZero, "   ")
            fail("expected setAlias(\"   \") to throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `aliases for different preset indices are independent`() = runTest {
        val store = DataStorePresetAliasStore(InMemoryPreferencesDataStore())

        store.setAlias(presetZero, "My Lead Tone")
        store.setAlias(presetOne, "Rhythm Crunch")

        store.alias(presetZero).test { assertEquals("My Lead Tone", awaitItem()) }
        store.alias(presetOne).test { assertEquals("Rhythm Crunch", awaitItem()) }
    }

    @Test
    fun `setAlias overwrites a previously set alias for the same preset`() = runTest {
        val store = DataStorePresetAliasStore(InMemoryPreferencesDataStore())

        store.setAlias(presetZero, "First Name")
        store.setAlias(presetZero, "Second Name")

        store.alias(presetZero).test {
            assertEquals("Second Name", awaitItem())
        }
    }

    // --- Restart persistence: the one deliberate real-file case ---

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `alias set before a simulated restart is still readable after it`() = runTest {
        // Two independent DataStore instances (and two independent coroutine scopes) standing in
        // for "the app process died and was relaunched" — nothing here is shared in-process
        // between the write and the read. Each gets its own backing file (rather than two handles
        // to one file) so this doesn't depend on the OS's file-locking semantics: Windows enforces
        // exclusive locks more strictly than POSIX, so a second concurrent handle to the same file
        // can fail there even after the first scope is cancelled, since cancellation doesn't
        // guarantee the underlying file handle is closed synchronously. Copying the first file's
        // bytes into a second, fully independent file reproduces "still readable after a restart"
        // without relying on concurrent access to one file at all.
        //
        // File.createTempFile() itself already creates the physical (empty) file. DataStore's
        // write path does an atomic rename of a ".tmp" file onto that target; renaming *onto an
        // already-existing file* is exactly the case Windows' stricter file locking makes flaky
        // (POSIX allows a rename to silently replace an existing file; Windows does not always).
        // Deleting the pre-created placeholder immediately, before DataStore ever opens it, means
        // the first write's rename lands on a path that doesn't exist yet — sidestepping the
        // rename-over-existing-file case entirely rather than depending on timing.
        val firstFile = File.createTempFile("preset_aliases_test", ".preferences_pb")
        firstFile.deleteOnExit()
        firstFile.delete()

        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val firstProcessDataStore = PreferenceDataStoreFactory.create(
            scope = writeScope,
            produceFile = { firstFile },
        )
        val firstProcessStore = DataStorePresetAliasStore(firstProcessDataStore)
        firstProcessStore.setAlias(presetZero, "Survives Restart")
        writeScope.cancel()

        val secondFile = File.createTempFile("preset_aliases_test", ".preferences_pb")
        secondFile.deleteOnExit()
        firstFile.copyTo(secondFile, overwrite = true)

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val secondProcessDataStore = PreferenceDataStoreFactory.create(
            scope = readScope,
            produceFile = { secondFile },
        )
        val secondProcessStore = DataStorePresetAliasStore(secondProcessDataStore)

        secondProcessStore.alias(presetZero).test {
            assertEquals("Survives Restart", awaitItem())
        }
        readScope.cancel()
    }
}
