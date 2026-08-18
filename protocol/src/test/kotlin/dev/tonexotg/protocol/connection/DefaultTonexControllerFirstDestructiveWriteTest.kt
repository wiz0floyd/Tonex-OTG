@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import app.cash.turbine.test
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.params.ParameterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * §H.7 of the S9b architecture plan: [TonexEvent.FirstDestructiveWrite] — the post-hoc,
 * once-per-session, snapshot-gated notice (§F.2/§F.3). Complements
 * [DefaultTonexControllerSnapshotTest] (capture) and [DefaultTonexControllerRevertTest] (replay,
 * which deliberately does NOT emit this event).
 */
class DefaultTonexControllerFirstDestructiveWriteTest {

    private val presetParam = requireNotNull(ParameterRegistry.byIndex(2)) // NOISE_GATE_THRESHOLD
    private val otherPresetParam = requireNotNull(ParameterRegistry.byIndex(30))
    private val masterVolumeSpec = requireNotNull(ParameterRegistry.byEnumName("MASTER_VOLUME"))

    @Test
    fun `the first setParameter on a snapshotted preset emits FirstDestructiveWrite exactly once`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)

        controller.events.test {
            assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.min))
            val event = awaitItem()
            assertIs<TonexEvent.FirstDestructiveWrite>(event)
            assertIs<TonexResult.Success<Unit>>(controller.setParameter(otherPresetParam.id, otherPresetParam.min))
            assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.max))
            expectNoEvents()
        }
    }

    @Test
    fun `a failed first write emits nothing, and the next successful write emits`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)

        controller.events.test {
            fake.writeThrows = java.io.IOException("wedged")
            assertIs<TonexResult.Failure>(controller.setParameter(presetParam.id, presetParam.min))
            expectNoEvents()

            fake.writeThrows = null
            assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.max))
            val event = awaitItem()
            assertIs<TonexEvent.FirstDestructiveWrite>(event)
        }
    }

    @Test
    fun `master volume does not emit, even as the session's very first write`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)

        controller.events.test {
            assertIs<TonexResult.Success<Unit>>(controller.setParameter(masterVolumeSpec.id, 0f))
            expectNoEvents()
        }
    }

    @Test
    fun `no snapshot means no event`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake, captureValues = null)

        controller.events.test {
            assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.min))
            expectNoEvents()
        }
    }

    @Test
    fun `a preset change does not re-arm the warning within one session`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)

        controller.events.test {
            assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.min))
            assertIs<TonexEvent.FirstDestructiveWrite>(awaitItem())

            // Change the active preset (re-captures a fresh snapshot for the new preset).
            fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = 1, c = 2)))
            testScheduler.runCurrent()
            assertIs<TonexEvent.ExternalPresetChange>(awaitItem())
            fake.emitMessage(presetDetailsSummaryWithParameters("New Active", snapshotValues()))
            testScheduler.runCurrent()

            assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.max))
            expectNoEvents()
        }
    }

    @Test
    fun `reconnecting re-arms it`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake)
        assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.min))
        controller.disconnect()

        val freshFake = FakeTonexTransport()
        connectToReady(controller, freshFake)

        controller.events.test {
            assertIs<TonexResult.Success<Unit>>(controller.setParameter(presetParam.id, presetParam.max))
            val event = awaitItem()
            assertIs<TonexEvent.FirstDestructiveWrite>(event)
        }
    }

    // ---- shared setup -------------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.connectToReady(
        controller: DefaultTonexController,
        fake: FakeTonexTransport,
        captureValues: FloatArray? = snapshotValues(),
    ) {
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, captureValues = captureValues)
        val result = connectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)
    }
}
