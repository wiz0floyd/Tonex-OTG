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

        // BPM's registered range is 40..240; 120 is squarely inside it.
        fake.emitMessage(parameterChanged(index = 110, value = 120f))
        testScheduler.runCurrent()

        assertEquals(120f, controller.parameterValues.value[ParameterId(110)])
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

    // ---- Opus review of PR #108: the pedal echoes our own writes back ---------------------------

    @Test
    fun `the pedal's echo of our own write is recognised, not treated as a knob turn`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveHandshakeWithMasterVolume(this, fake)
        connectDeferred.await()

        // Two conflated drag writes, v1 then v2 -- exactly what ParameterWriteThrottler produces.
        controller.setParameter(ParameterId(20), 6f)
        testScheduler.runCurrent()
        controller.setParameter(ParameterId(20), 8f)
        testScheduler.runCurrent()
        assertEquals(8f, controller.parameterValues.value[ParameterId(20)])

        // v1's echo arrives LATE, after v2 already landed. Left unrecognised it would be deferred
        // by the throttle and flushed over v2, leaving the app showing 6 while the pedal is at 8.
        fake.emitMessage(parameterChanged(index = 20, value = 6f))
        testScheduler.advanceTimeBy(InboundParameterThrottle.DEFAULT_INTERVAL_MILLIS * 2)
        testScheduler.runCurrent()

        assertEquals(8f, controller.parameterValues.value[ParameterId(20)], "an echo must never overwrite a newer local write")
    }

    @Test
    fun `a genuine knob turn to a different value still lands while an echo is outstanding`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(fake) }
        driveHandshakeWithMasterVolume(this, fake)
        connectDeferred.await()

        controller.setParameter(ParameterId(20), 6f)
        testScheduler.runCurrent()

        // Echo suppression is per exact value, so it must not swallow a real, different value.
        fake.emitMessage(parameterChanged(index = 20, value = 2.5f))
        testScheduler.advanceTimeBy(InboundParameterThrottle.DEFAULT_INTERVAL_MILLIS * 2)
        testScheduler.runCurrent()

        assertEquals(2.5f, controller.parameterValues.value[ParameterId(20)])
    }

    @Test
    fun `index 0 of kind 0x02 is NOISE_GATE_POST, not master volume`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        // Routing on the index alone would send this to master volume as
        // nativeToDecibels(1f) = -35.7 dB. The pedal's own master-volume frames carry kind 0x03
        // (verified in docs/hardware-probes/tonexprobe20260819_220308.log.txt:18936-18938).
        fake.emitMessage(parameterChanged(index = 0, value = 1f))
        testScheduler.runCurrent()

        assertEquals(1f, controller.parameterValues.value[ParameterId(0)], "kind 0x02 index 0 is ParameterId(0)")
        assertNull(controller.parameterValues.value[masterVolumeSpec.id], "it must NOT be routed to master volume")
    }

    @Test
    fun `a value outside the parameter's own range is refused loudly, not displayed`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        val events = mutableListOf<TonexEvent>()
        val collector = backgroundScope.launch { controller.events.collect { events += it } }
        testScheduler.runCurrent()

        // The units collision: MASTER_VOLUME is ParameterId(116), inside GLOBAL_RANGE, so index 116
        // is a second route to the same id. A NATIVE 5.0 arriving there would be rendered as +5 dB
        // in a -40..3 dB slot -- and written back from the home screen. It must be refused instead.
        fake.emitMessage(parameterChanged(index = 116, value = 5f))
        testScheduler.runCurrent()

        assertNull(controller.parameterValues.value[masterVolumeSpec.id], "an out-of-range value must not be displayed")
        assertTrue(
            events.filterIsInstance<TonexEvent.UnroutableParameterNotification>().any { it.index == 116 },
            "an out-of-range value must be reported, not silently dropped",
        )
        collector.cancel()
    }

    @Test
    fun `an in-range value for index 116 is still accepted`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        fake.emitMessage(parameterChanged(index = 116, value = -6f))
        testScheduler.runCurrent()

        assertEquals(-6f, controller.parameterValues.value[masterVolumeSpec.id])
    }

    @Test
    fun `a flush that survives teardown cannot repopulate the cleared parameterValues`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveHandshake(this, fake)
        connectDeferred.await()

        fake.emitMessage(parameterChanged(index = 20, value = 3f)) // opens the throttle window
        testScheduler.runCurrent()
        fake.emitMessage(parameterChanged(index = 20, value = 4f)) // deferred into the window
        testScheduler.runCurrent()

        controller.disconnect()
        testScheduler.advanceTimeBy(InboundParameterThrottle.DEFAULT_INTERVAL_MILLIS * 2)
        testScheduler.runCurrent()

        assertTrue(
            controller.parameterValues.value.isEmpty(),
            "a stale flush must not write into a torn-down session; got ${controller.parameterValues.value}",
        )
    }

    // ---- shared handshake drivers (same sequence as the other controller tests) -----------------

    private suspend fun driveHandshakeWithMasterVolume(scope: TestScope, fake: FakeTonexTransport) {
        scope.testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        scope.testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        for (i in PresetIndex.VALID_RANGE) {
            scope.testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        scope.testScheduler.runCurrent()
        fake.emitMessage(masterVolumeChanged(MasterVolumeMessage.decibelsToNative(0f)))
        scope.testScheduler.runCurrent()
    }


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
