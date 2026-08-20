package dev.tonexotg.app.data.params

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.tonexotg.app.data.alias.InMemoryPreferencesDataStore
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.params.SelfWideningParameterBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises [WidenedParameterBoundsStore] against issue #80's persistence acceptance criteria:
 * the widened ceiling persists across an app restart, and never regresses. Mirrors
 * `PresetAliasStoreTest`'s pattern -- [InMemoryPreferencesDataStore] for the bulk of cases, one
 * real disk-backed [PreferenceDataStoreFactory] instance for the "survives process death" case.
 */
class WidenedParameterBoundsStoreTest {

    private val virCabinetModel = ParameterId(25)
    private val virMic1 = ParameterId(27)

    @Test
    fun `loadSeed on an empty store returns an empty map`() = runTest {
        val seed = WidenedParameterBoundsStore.loadSeed(InMemoryPreferencesDataStore())
        assertEquals(emptyMap<ParameterId, Float>(), seed)
    }

    @Test
    fun `persistForward then loadSeed round-trips a widened value`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val bounds = SelfWideningParameterBounds()
        WidenedParameterBoundsStore.persistForward(scope, dataStore, bounds)

        bounds.observeRead(virCabinetModel, 42f)
        advanceUntilIdle()

        val seed = WidenedParameterBoundsStore.loadSeed(dataStore)
        assertEquals(mapOf(virCabinetModel to 42f), seed)
        scope.cancel()
    }

    @Test
    fun `persistForward writes every allowlisted id independently`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val bounds = SelfWideningParameterBounds()
        WidenedParameterBoundsStore.persistForward(scope, dataStore, bounds)

        bounds.observeRead(virCabinetModel, 42f)
        bounds.observeRead(virMic1, 5f)
        advanceUntilIdle()

        val seed = WidenedParameterBoundsStore.loadSeed(dataStore)
        assertEquals(mapOf(virCabinetModel to 42f, virMic1 to 5f), seed)
        scope.cancel()
    }

    @Test
    fun `a loaded seed immediately widens a fresh SelfWideningParameterBounds - the restart contract`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val firstSessionBounds = SelfWideningParameterBounds()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        WidenedParameterBoundsStore.persistForward(scope, dataStore, firstSessionBounds)
        firstSessionBounds.observeRead(virCabinetModel, 42f)
        advanceUntilIdle()
        scope.cancel()

        // Simulates the app restarting: a brand new SelfWideningParameterBounds, seeded from
        // whatever the prior "session" persisted, with no read this session yet.
        val seed = WidenedParameterBoundsStore.loadSeed(dataStore)
        val secondSessionBounds = SelfWideningParameterBounds(initialWidened = seed)

        assertEquals(42f, secondSessionBounds.effectiveMax(virCabinetModel))
    }

    @Test
    fun `the floor never lowers - a later smaller observation does not overwrite a larger persisted value`() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val bounds = SelfWideningParameterBounds()
        WidenedParameterBoundsStore.persistForward(scope, dataStore, bounds)

        bounds.observeRead(virCabinetModel, 50f)
        advanceUntilIdle()
        bounds.observeRead(virCabinetModel, 45f) // lower -- SelfWideningParameterBounds itself is a no-op here
        advanceUntilIdle()

        val seed = WidenedParameterBoundsStore.loadSeed(dataStore)
        assertEquals(50f, seed[virCabinetModel])
        scope.cancel()
    }

    // ---- Restart persistence: the one deliberate real-file case, mirroring PresetAliasStoreTest ----

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a widened value persisted before a simulated restart is still loadable after it`() = runTest {
        val file = File.createTempFile("parameter_bounds_test", ".preferences_pb")
        file.deleteOnExit()

        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val firstProcessDataStore = PreferenceDataStoreFactory.create(
            scope = writeScope,
            produceFile = { file },
        )
        val firstProcessBounds = SelfWideningParameterBounds()
        WidenedParameterBoundsStore.persistForward(writeScope, firstProcessDataStore, firstProcessBounds)
        firstProcessBounds.observeRead(virCabinetModel, 42f)
        advanceUntilIdle()
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val secondProcessDataStore = PreferenceDataStoreFactory.create(
            scope = readScope,
            produceFile = { file },
        )

        val seed = WidenedParameterBoundsStore.loadSeed(secondProcessDataStore)
        assertEquals(42f, seed[virCabinetModel])
        assertTrue("only the allowlisted id should be present", seed.keys.all { it == virCabinetModel })
        readScope.cancel()
    }
}
