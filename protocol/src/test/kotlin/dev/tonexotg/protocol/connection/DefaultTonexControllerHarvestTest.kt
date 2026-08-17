@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.message.MasterVolumeMessage
import dev.tonexotg.protocol.params.ParameterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Step 6 of the S9 build order: dedicated coverage for the post-`Ready` harvest already wired into
 * `connect()` (§7.2/§7.3 of the architecture plan) — preset-name harvest (fatal on failure) and
 * master-volume harvest (best-effort).
 */
class DefaultTonexControllerHarvestTest {

    private val masterVolumeSpec = requireNotNull(ParameterRegistry.byEnumName("MASTER_VOLUME"))

    // ---- preset-name harvest is strictly sequential -------------------------------------------

    @Test
    fun `preset-name harvest requests all 20 presets in ascending order, one at a time`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        val presetDetailsRequests = fake.writtenMessages().drop(2) // Hello, RequestState first
        assertEquals(20, presetDetailsRequests.size)
        for ((i, msg) in presetDetailsRequests.withIndex()) {
            // Payload prefix is fixed (b9 04 0b 01); the trailing two bytes are index, kind(=0 SUMMARY).
            assertEquals(i, msg.payload[4].toInt() and 0xFF, "request $i should ask for preset index $i")
            assertEquals(0, msg.payload[5].toInt() and 0xFF, "request $i should ask for SUMMARY (kind 0)")
        }
    }

    @Test
    fun `connect returns Success with exactly 20 named presets in order`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        val result = connectDeferred.await()

        assertIs<TonexResult.Success<Unit>>(result)
        val presets = controller.presets.value
        assertEquals(20, presets.size)
        for (i in PresetIndex.VALID_RANGE) {
            assertEquals(PresetIndex(i), presets[i].index)
            assertEquals("Preset $i", presets[i].pedalName)
        }
    }

    // ---- preset-name harvest failure is fatal --------------------------------------------------

    @Test
    fun `preset-name harvest failure is fatal - connect fails, state is Error, presets stays empty`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))

        // Preset 0 through 6 answer normally; preset 7's response is missing the name marker.
        for (i in 0..6) {
            testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryMissingMarker())

        val result = connectDeferred.await()

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.MalformedFrame>(error)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
        assertTrue(controller.presets.value.isEmpty(), "presets must not be partially filled on harvest failure")
    }

    // ---- master-volume harvest: best-effort, gated on capability -------------------------------

    @Test
    fun `master-volume harvest is skipped entirely when the capability is not confirmed`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        val result = connectDeferred.await()

        assertIs<TonexResult.Success<Unit>>(result)
        assertEquals(22, fake.writtenMessages().size) // no RequestMasterVolume written
        assertFalse(controller.parameterValues.value.containsKey(masterVolumeSpec.id))
    }

    @Test
    fun `master-volume harvest succeeds - RequestMasterVolume is written and parameterValues is populated in dB`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )

        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        testScheduler.runCurrent()
        fake.emitMessage(masterVolumeChanged(native = 5f)) // native 5 -> dB via nativeToDecibels

        val result = connectDeferred.await()

        assertIs<TonexResult.Success<Unit>>(result)
        assertEquals(23, fake.writtenMessages().size) // Hello + RequestState + 20 + RequestMasterVolume
        val expectedDb = MasterVolumeMessage.nativeToDecibels(5f)
        val actualDb = controller.parameterValues.value.getValue(masterVolumeSpec.id)
        assertTrue(abs(actualDb - expectedDb) < 1e-3f, "expected ~$expectedDb dB, got $actualDb")
    }

    @Test
    fun `master-volume harvest timeout does not fail connect - key stays absent, state stays Ready`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )

        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        testScheduler.runCurrent()
        // No master-volume response ever arrives - let virtual time pass the timeout.
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.masterVolumeMillis + 1)
        testScheduler.runCurrent()

        val result = connectDeferred.await()

        assertIs<TonexResult.Success<Unit>>(result)
        assertEquals(ConnectionState.Ready, controller.connectionState.value)
        assertFalse(controller.parameterValues.value.containsKey(masterVolumeSpec.id))
    }

    // ---- nonzero-index ParameterChanged is ignored (§6.4 restraint) ----------------------------

    @Test
    fun `a nonzero-index ParameterChanged notification is ignored, not applied to parameterValues`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        val before = controller.parameterValues.value
        fake.emitMessage(parameterChanged(index = 3, value = 42f))
        testScheduler.runCurrent()

        assertEquals(before, controller.parameterValues.value, "a nonzero-index notification must not change parameterValues")
    }

    // ---- shared handshake driver -----------------------------------------------------------

    private suspend fun driveHandshake(scope: TestScope, fake: FakeTonexTransport) {
        scope.testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        scope.testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        for (i in PresetIndex.VALID_RANGE) {
            scope.testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        scope.testScheduler.runCurrent()
    }
}
