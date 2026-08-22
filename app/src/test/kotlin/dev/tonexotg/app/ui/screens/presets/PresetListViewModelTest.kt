package dev.tonexotg.app.ui.screens.presets

import app.cash.turbine.test
import dev.tonexotg.app.data.alias.DataStorePresetAliasStore
import dev.tonexotg.app.data.alias.InMemoryPreferencesDataStore
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [PresetListViewModel] against issue #21's acceptance criteria:
 *  - all 20 presets load and display real pedal names
 *  - the active preset is unambiguous in the projected state (visual verification is S16's
 *    on-device AC item, not testable here — see the PR/issue status notes)
 *  - footswitch-initiated (external) changes update the list with no call through this
 *    view model at all (FR6)
 *  - disconnected behaviour shows aliases without pretending to be live
 *  - a failed tap-to-load is surfaced, never silently dropped (fail fast and loud)
 */
class PresetListViewModelTest {

    private fun newViewModel(
        controller: FakeTonexController,
        scope: kotlinx.coroutines.CoroutineScope,
    ): PresetListViewModel =
        PresetListViewModel(
            controller = controller,
            aliasStore = DataStorePresetAliasStore(InMemoryPreferencesDataStore()),
            scope = scope,
        )

    /**
     * [PresetListViewModel.uiState] is a [kotlinx.coroutines.flow.SharingStarted.WhileSubscribed]
     * [kotlinx.coroutines.flow.stateIn] — its internal sharing coroutine is a long-lived job that
     * outlives any single [app.cash.turbine.ReceiveTurbine.test] block (it only stops
     * `stopTimeoutMillis` after the *last* subscriber goes away). Every test below constructs its
     * view model with [backgroundScope], the `runTest` coroutine intended for exactly this: a job
     * that keeps running across the test body but is force-cancelled when the test ends, rather
     * than the test's own scope (`this`), which `runTest` instead requires to have completed all
     * its own children before the test is allowed to finish (see
     * [kotlinx.coroutines.test.UncompletedCoroutinesError]'s own message).
     */
    private fun kotlinx.coroutines.test.TestScope.newViewModel(
        controller: FakeTonexController,
    ): PresetListViewModel = newViewModel(controller, backgroundScope)

    // --- All 20 presets load and display real pedal names ---------------------------------

    @Test
    fun `uiState lists all 20 presets with the pedal's real names once ready`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        val viewModel = newViewModel(controller)

        viewModel.uiState.test {
            val state = awaitItem { it.items.all { item -> item.pedalName != null } }
            assertEquals(20, state.items.size)
            assertTrue(state.isLive)
            state.items.forEachIndexed { i, item ->
                assertEquals(PresetIndex(i), item.index)
                assertEquals("PRESET %02d".format(i + 1), item.pedalName)
                assertEquals("PRESET %02d".format(i + 1), item.displayName)
            }
        }
    }

    @Test
    fun `a local alias overrides the pedal name in displayName but not pedalName itself`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        val viewModel = newViewModel(controller)

        viewModel.setAlias(PresetIndex(6), "Set Opener")

        viewModel.uiState.test {
            val state = awaitItem { it.items[6].localAlias == "Set Opener" }
            val item = state.items[6]
            assertEquals("Set Opener", item.displayName)
            assertEquals("PRESET 07", item.pedalName)
            assertEquals("Set Opener", item.localAlias)
        }
    }

    // --- Active preset is unambiguous in the projected state ------------------------------

    @Test
    fun `exactly one item is active, matching the controller's activePreset`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setActivePreset(PresetIndex(3))
        val viewModel = newViewModel(controller)

        viewModel.uiState.test {
            val state = awaitItem { it.items.count { item -> item.isActive } == 1 }
            assertTrue(state.items[3].isActive)
            assertEquals(1, state.items.count { it.isActive })
        }
    }

    // --- Tap-to-load ------------------------------------------------------------------------

    @Test
    fun `selectPreset calls through to the controller while live`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setActivePreset(PresetIndex(0))
        val viewModel = newViewModel(controller)

        // selectPreset() launches on backgroundScope (see PresetListViewModel's kdoc on why:
        // it's fire-and-forget, observable via uiState). A bare `testScheduler.advanceUntilIdle()`
        // does not by itself drain a coroutine launched on a *different* CoroutineScope than the
        // test's own -- only actually suspending on the flow (as Turbine's awaitItem does below)
        // gives the dispatcher's event loop a chance to run it, the same mechanism every other
        // selectPreset-observing test in this class already relies on.
        viewModel.uiState.test {
            awaitItem { it.items[0].isActive }

            viewModel.selectPreset(PresetIndex(9))

            val state = awaitItem { it.items[9].isActive }
            assertTrue(state.items[9].isActive)
        }

        assertEquals(listOf(PresetIndex(9)), controller.selectPresetCalls)
    }

    @Test
    fun `selectPreset is a no-op when not live`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Idle)
        val viewModel = newViewModel(controller)

        viewModel.selectPreset(PresetIndex(9))
        testScheduler.advanceUntilIdle()

        assertTrue(controller.selectPresetCalls.isEmpty())
    }

    @Test
    fun `a failed selectPreset surfaces the TonexError rather than being swallowed`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.nextSelectPresetResult = TonexResult.Failure(
            TonexError.ProtocolStateViolation(state = ConnectionState.Connecting, details = "not ready"),
        )
        val viewModel = newViewModel(controller)

        viewModel.selectPreset(PresetIndex(2))

        viewModel.uiState.test {
            val state = awaitItem { it.selectPresetError != null }
            assertTrue(state.selectPresetError is TonexError.ProtocolStateViolation)
        }
    }

    // --- FR6: footswitch-initiated external changes update the list with no local call ----

    @Test
    fun `an externally-initiated preset change updates uiState with no selectPreset call`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setActivePreset(PresetIndex(0))
        val viewModel = newViewModel(controller)

        viewModel.uiState.test {
            awaitItem { it.items[0].isActive }

            // Simulate a footswitch press: the controller updates activePreset on its own,
            // exactly as it would from an external-change observation, never via this view
            // model's selectPreset().
            controller.emitExternalPresetChange(PresetIndex(14))

            val state = awaitItem { it.items[14].isActive }
            assertTrue(state.items[14].isActive)
            assertFalse(state.items[0].isActive)
        }
        // The view model itself never called selectPreset for this change.
        assertTrue(controller.selectPresetCalls.isEmpty())
    }

    // --- Disconnected behaviour: shows aliases, does not pretend to be live ----------------

    @Test
    fun `while disconnected, isLive is false and no item is active`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Idle)
        val viewModel = newViewModel(controller)

        viewModel.uiState.test {
            val state = awaitItem { !it.isLive || true } // first emission is always non-live here
            assertFalse(state.isLive)
            assertTrue(state.items.none { it.isActive })
            assertEquals(20, state.items.size)
        }
    }

    @Test
    fun `while disconnected, a previously-set local alias is still shown`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Idle)
        val viewModel = newViewModel(controller)
        viewModel.setAlias(PresetIndex(5), "My Lead Tone")

        viewModel.uiState.test {
            val state = awaitItem { it.items[5].localAlias == "My Lead Tone" }
            assertFalse(state.isLive)
            assertEquals("My Lead Tone", state.items[5].displayName)
            assertNull(state.items[5].pedalName)
        }
    }

    @Test
    fun `a slot with neither an alias nor a known pedal name falls back to a numbered placeholder`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Idle)
        val viewModel = newViewModel(controller)

        viewModel.uiState.test {
            val state = awaitItem { it.items.isNotEmpty() }
            assertEquals("Preset 1", state.items[0].displayName)
            assertEquals("Preset 20", state.items[19].displayName)
        }
    }

    // --- Slot badges (S85 part 2a) ----------------------------------------------------------

    @Test
    fun `assignedSlots reflects the controller's live slot assignments`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setSlotAssignments(
            mapOf(
                PresetSlot.A to PresetIndex(3),
                PresetSlot.B to PresetIndex(3),
                PresetSlot.C to PresetIndex(9),
            ),
        )
        val viewModel = newViewModel(controller)

        viewModel.uiState.test {
            val state = awaitItem { it.items[3].assignedSlots.isNotEmpty() }
            assertEquals(setOf(PresetSlot.A, PresetSlot.B), state.items[3].assignedSlots)
            assertEquals(setOf(PresetSlot.C), state.items[9].assignedSlots)
        }
    }

    @Test
    fun `a preset assigned to no slot has an empty assignedSlots`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        controller.setSlotAssignments(mapOf(PresetSlot.A to PresetIndex(0)))
        val viewModel = newViewModel(controller)

        viewModel.uiState.test {
            val state = awaitItem { it.items[0].assignedSlots.isNotEmpty() }
            assertTrue(state.items[1].assignedSlots.isEmpty())
        }
    }

    @Test
    fun `assignedSlots is empty while not live even if the controller has stale slot data`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Idle)
        controller.setSlotAssignments(mapOf(PresetSlot.A to PresetIndex(3)))
        val viewModel = newViewModel(controller)

        viewModel.uiState.test {
            val state = awaitItem { it.items.isNotEmpty() }
            assertFalse(state.isLive)
            assertTrue(state.items.all { it.assignedSlots.isEmpty() })
        }
    }

    // --- setAlias: blank input clears rather than throwing ----------------------------------

    @Test
    fun `setAlias with blank text clears the alias instead of throwing`() = runTest {
        val controller = FakeTonexController(initialState = ConnectionState.Ready)
        controller.setPresets(fakePresetInfoList())
        val viewModel = newViewModel(controller)

        viewModel.setAlias(PresetIndex(1), "Custom")
        viewModel.uiState.test {
            awaitItem { it.items[1].localAlias == "Custom" }

            viewModel.setAlias(PresetIndex(1), "   ")
            val state = awaitItem { it.items[1].localAlias == null }
            assertNull(state.items[1].localAlias)
            assertEquals("PRESET 02", state.items[1].displayName)
        }
    }
}

/**
 * Turbine's [app.cash.turbine.ReceiveTurbine.awaitItem] takes no predicate; this repeatedly
 * awaits items until [predicate] matches, so tests aren't coupled to exactly how many
 * intermediate [PresetListUiState] emissions a `combine()` of five flows happens to produce
 * before settling (e.g. the initial value plus one emission per input flow's first value).
 */
private suspend fun app.cash.turbine.ReceiveTurbine<PresetListUiState>.awaitItem(
    predicate: (PresetListUiState) -> Boolean,
): PresetListUiState {
    var item = awaitItem()
    var guard = 0
    while (!predicate(item)) {
        guard++
        check(guard < 50) { "predicate never matched within 50 emissions; last item: $item" }
        item = awaitItem()
    }
    return item
}
