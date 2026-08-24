@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.message.MasterVolumeMessage
import dev.tonexotg.protocol.params.ParameterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #104: unsolicited `ParameterChanged` notifications — the pedal's own knobs moving the app's
 * controls. Covers the routing table in `DefaultTonexController.applyParameterChanged`: index 0 is
 * master volume (with the native→dB conversion), every other valid index is that `ParameterId`
 * as-is, and anything else is dropped with a loud [TonexEvent.UnroutableParameterNotification].
 */
class DefaultTonexControllerParameterChangedTest {

    private val masterVolumeSpec = requireNotNull(ParameterRegistry.byEnumName("MASTER_VOLUME"))

    @Test
    fun `index 0 routes to master volume with the native-to-decibels conversion applied`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        // 7.5 in the pedal's native 0..10 range - deliberately NOT a value that happens to be
        // valid in the engineering -40..3 dB range too, so an unconverted value would be visible.
        val native = 7.5f
        fake.emitMessage(masterVolumeChanged(native))
        testScheduler.runCurrent()

        val expected = MasterVolumeMessage.nativeToDecibels(native)
        val actual = assertNotNull(controller.parameterValues.value[masterVolumeSpec.id])
        assertTrue(
            abs(expected - actual) < 1e-4f,
            "master volume must be stored in dB ($expected), not the pedal's native units ($native); got $actual",
        )
    }

    @Test
    fun `index 0 is master volume, not ParameterId 0`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        val before = controller.parameterValues.value[ParameterId(0)]
        fake.emitMessage(masterVolumeChanged(7.5f))
        testScheduler.runCurrent()

        assertEquals(before, controller.parameterValues.value[ParameterId(0)], "index 0 must not land on ParameterId(0)")
    }

    @Test
    fun `a preset-scoped index routes to that ParameterId with the value unconverted`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        // The exact case #106 captured: MODEL_GAIN (index 20), swept into its 0f..10f range.
        fake.emitMessage(parameterChanged(index = 20, value = 3.4f))
        testScheduler.runCurrent()

        assertEquals(3.4f, controller.parameterValues.value[ParameterId(20)])
    }

    @Test
    fun `a GLOBAL-scope index routes to that ParameterId`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        fake.emitMessage(parameterChanged(index = 110, value = 1f))
        testScheduler.runCurrent()

        assertEquals(1f, controller.parameterValues.value[ParameterId(110)])
    }

    @Test
    fun `an index outside both valid ranges is dropped and reported, not applied and not thrown`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        val events = mutableListOf<TonexEvent>()
        val collector = backgroundScope.launch { controller.events.collect { events += it } }
        testScheduler.runCurrent()

        val before = controller.parameterValues.value
        // 109 is the one index between PRESET_RANGE (0..108) and GLOBAL_RANGE (110..116).
        fake.emitMessage(parameterChanged(index = 109, value = 42f))
        testScheduler.runCurrent()

        assertEquals(before, controller.parameterValues.value, "an unroutable index must not change parameterValues")
        val reported = events.filterIsInstance<TonexEvent.UnroutableParameterNotification>().singleOrNull()
        assertNotNull(reported, "an unroutable index must be reported loudly, not silently swallowed")
        assertEquals(109, reported.index)
        // The raw payload, decodable by hand from a debug dump: B9 04 02 00 6D 88 <float32 LE>.
        assertEquals("B9 04 02 00 6D 88 00 00 28 42", reported.payloadHex)
        collector.cancel()
    }

    @Test
    fun `an unroutable index above the byte range is also dropped, not crashed on`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        val before = controller.parameterValues.value
        fake.emitMessage(parameterChanged(index = 200, value = 1f))
        testScheduler.runCurrent()

        assertEquals(before, controller.parameterValues.value)
        assertNull(controller.parameterValues.value[masterVolumeSpec.id]?.takeIf { it == 1f })
    }

    @Test
    fun `a burst of notifications for one parameter settles on the last value`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        // A knob sweep: ~100 Hz, far faster than the throttle's window. The intermediate values may
        // be coalesced away, but the value the knob came to rest at must always win.
        for (v in listOf(1.9f, 2.4f, 3.1f, 3.9f, 4.8f)) {
            fake.emitMessage(parameterChanged(index = 20, value = v))
            testScheduler.runCurrent()
        }
        testScheduler.advanceTimeBy(InboundParameterThrottle.DEFAULT_INTERVAL_MILLIS * 2)
        testScheduler.runCurrent()

        assertEquals(4.8f, controller.parameterValues.value[ParameterId(20)])
    }

    // ---- shared handshake driver (same sequence as the other controller tests) -----------------

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
