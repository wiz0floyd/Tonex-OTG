@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import app.cash.turbine.test
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.message.FirmwareCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Step 7 of the S9 build order: dedicated coverage for [DefaultTonexController.disconnect],
 * `teardown`, and transport-detach handling — the production code landed as part of step 5, per
 * §6.8/§6.9/§6.10 of the architecture plan.
 */
class DefaultTonexControllerDisconnectTest {

    // ---- no-op cases -----------------------------------------------------------------------

    @Test
    fun `disconnect from Idle is a documented no-op`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        controller.disconnect()

        assertEquals(ConnectionState.Idle, controller.connectionState.value)
        assertTrue(!fake.closed, "no transport was ever attached")
    }

    @Test
    fun `disconnect twice is a no-op the second time`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        for (i in PresetIndex.VALID_RANGE) {
            testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        connectDeferred.await()

        controller.disconnect()
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
        controller.disconnect() // must not throw, must not change anything
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
    }

    // ---- disconnect at each handshake stage returns cleanly to Idle, without waiting out any timeout ---

    @Test
    fun `disconnect while Connecting returns cleanly to Idle`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        controller.connectionState.test {
            assertEquals(ConnectionState.Idle, awaitItem())
            val connectDeferred = async { controller.connect(fake) }
            assertEquals(ConnectionState.Connecting, awaitItem())

            val timeBefore = testScheduler.currentTime
            controller.disconnect()

            val result = connectDeferred.await()
            assertIs<TonexResult.Failure>(result)
            assertTrue(fake.closed)
            assertEquals(timeBefore, testScheduler.currentTime, "must not wait out any timeout")
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
    }

    @Test
    fun `disconnect while Hello returns cleanly to Idle, not Error, without waiting out the hello timeout`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent() // connect() reaches Hello and subscribes
        assertEquals(ConnectionState.Hello, controller.connectionState.value)
        val timeBefore = testScheduler.currentTime

        controller.disconnect()
        val result = connectDeferred.await()

        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.ProtocolStateViolation>((result as TonexResult.Failure).error)
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
        assertTrue(fake.closed)
        assertEquals(timeBefore, testScheduler.currentTime, "must not wait out helloMillis")
    }

    @Test
    fun `disconnect while GetState returns cleanly to Idle without waiting out the get-state timeout`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        assertEquals(ConnectionState.GetState, controller.connectionState.value)
        val timeBefore = testScheduler.currentTime

        controller.disconnect()
        val result = connectDeferred.await()

        assertIs<TonexResult.Failure>(result)
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
        assertTrue(fake.closed)
        assertEquals(timeBefore, testScheduler.currentTime, "must not wait out getStateMillis")
    }

    @Test
    fun `disconnect while Ready returns cleanly to Idle and resets published state`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()
        assertEquals(ConnectionState.Ready, controller.connectionState.value)
        assertEquals(20, controller.presets.value.size)

        controller.disconnect()

        assertEquals(ConnectionState.Idle, controller.connectionState.value)
        assertTrue(controller.presets.value.isEmpty())
        assertEquals(null, controller.activePreset.value)
        assertTrue(controller.parameterValues.value.isEmpty())
        assertTrue(fake.closed)
    }

    @Test
    fun `disconnect mid-harvest returns cleanly to Idle - presets stays empty, not partially filled`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        for (i in 0..5) {
            testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        testScheduler.runCurrent() // now mid-harvest, awaiting preset 6's response

        controller.disconnect()
        val result = connectDeferred.await()

        assertIs<TonexResult.Failure>(result)
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
        assertTrue(controller.presets.value.isEmpty(), "presets must not be partially filled by a mid-harvest disconnect")
    }

    // ---- teardown publishes finalState LAST ---------------------------------------------------

    @Test
    fun `at the moment Idle is observed, presets is already empty - finalState is published last`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()

        controller.connectionState.test {
            assertEquals(ConnectionState.Ready, awaitItem())
            controller.disconnect()
            assertEquals(ConnectionState.Idle, awaitItem())
            assertTrue(controller.presets.value.isEmpty(), "presets must already be reset by the time Idle is observed")
        }
    }

    // ---- transport detach: clean end vs. throw ------------------------------------------------

    @Test
    fun `a clean transport detach (endStream) while Ready returns to Idle, not Error`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()

        fake.endStream()
        testScheduler.runCurrent()

        assertEquals(ConnectionState.Idle, controller.connectionState.value)
        assertTrue(fake.closed)
    }

    @Test
    fun `a throwing transport detach (failStream) while an operation is in-flight reports TransportFailure and lands on Idle`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()

        // selectPreset() re-read is an in-flight requestAndAwait subscriber at the moment the
        // transport fails - the same shape of race any Ready-state operation would hit.
        val selectDeferred = async { controller.selectPreset(PresetIndex(5)) }
        testScheduler.runCurrent()
        fake.failStream(IOException("cable pulled"))
        testScheduler.runCurrent()

        val result = selectDeferred.await()
        assertIs<TonexResult.Failure>(result)
        assertIs<TonexError.TransportFailure>((result as TonexResult.Failure).error)
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
    }

    // ---- shared driver ------------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.driveToReady(fake: FakeTonexTransport) {
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        for (i in PresetIndex.VALID_RANGE) {
            testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        testScheduler.runCurrent()
    }
}
