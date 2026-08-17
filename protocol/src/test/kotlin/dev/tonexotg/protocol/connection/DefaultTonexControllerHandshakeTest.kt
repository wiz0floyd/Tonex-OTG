package dev.tonexotg.protocol.connection

import app.cash.turbine.test
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.codec.MessageType
import dev.tonexotg.protocol.message.FirmwareCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Happy-path handshake coverage for [DefaultTonexController], step 5 of the S9 build order: the
 * full `Idle -> Connecting -> Hello -> GetState -> Ready` sequence, observed via Turbine. Harvest
 * (preset names, master volume) lands in a later step conceptually, but its production code is
 * already wired into `connect()` — this test drives it with trivially-answered fixtures.
 *
 * ## Why every response emission is preceded by `testScheduler.runCurrent()`
 *
 * [FakeTonexTransport] is `Channel`-backed, so an emitted response sits in the channel the instant
 * `emitMessage` is called, regardless of whether the reader coroutine (a separate coroutine on
 * `backgroundScope`) has actually reached the point of subscribing to `inbound` for the stage that
 * response is meant to satisfy. `requestAndAwait`'s `onSubscription`-before-write ordering protects
 * a *single* round trip from losing its own response to that race — it does not protect a *test
 * harness* that queues up a later stage's response before the state machine has reached that
 * stage. `runCurrent()` (not `advanceUntilIdle()` — that would fast-forward virtual time straight
 * past the in-flight stage's timeout, since nothing else is scheduled) lets the reader and
 * `connect()` coroutines run every currently-schedulable step to their next genuine suspension
 * point (i.e. actually subscribed and waiting) before the next response is injected, matching how
 * a real pedal only ever responds after receiving a request.
 */
class DefaultTonexControllerHandshakeTest {

    @Test
    fun `connect drives connectionState through the full handshake sequence to Ready`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities.NONE_CONFIRMED,
        )

        controller.connectionState.test {
            assertEquals(ConnectionState.Idle, awaitItem())

            val connectDeferred = async { controller.connect(fake) }

            assertEquals(ConnectionState.Connecting, awaitItem())
            assertEquals(ConnectionState.Hello, awaitItem())

            testScheduler.runCurrent()
            fake.emitMessage(helloResponse())
            assertEquals(ConnectionState.GetState, awaitItem())

            testScheduler.runCurrent()
            val handshakeBlob = plausibleBlob(activeSlot = PresetSlot.B, a = 3, b = 4, c = 5)
            fake.emitMessage(stateUpdateMessage(handshakeBlob))
            assertEquals(ConnectionState.Ready, awaitItem())

            for (i in PresetIndex.VALID_RANGE) {
                testScheduler.runCurrent()
                fake.emitMessage(presetDetailsSummary("Preset $i"))
            }

            val result = connectDeferred.await()
            assertIs<TonexResult.Success<Unit>>(result)
        }
    }

    @Test
    fun `connect returns Success and activePreset reflects the handshake blob`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities.NONE_CONFIRMED,
        )

        val connectDeferred = async { controller.connect(fake) }
        respondToHandshake(this, fake, activeSlot = PresetSlot.C, presetInActiveSlot = 7)

        val result = connectDeferred.await()

        assertIs<TonexResult.Success<Unit>>(result)
        assertEquals(ConnectionState.Ready, controller.connectionState.value)
        assertEquals(PresetIndex(7), controller.activePreset.value)
    }

    @Test
    fun `the written request sequence is Hello, RequestState, then 20x RequestPresetDetails`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities.NONE_CONFIRMED,
        )

        val connectDeferred = async { controller.connect(fake) }
        respondToHandshake(this, fake)
        connectDeferred.await()

        val written = fake.writtenMessages()
        // Hello + RequestState + 20x RequestPresetDetails; NONE_CONFIRMED means no master-volume request.
        assertEquals(22, written.size)
        // Hello and RequestState's OUTBOUND envelope type is 0x0000 (Unknown) for both -- see
        // HelloMessage/RequestStateMessage's own KDoc -- so identify them by their fixed opaque
        // payload content instead of by header.type.
        assertContentEquals(byteArrayOf(0xB9.toByte(), 0x02, 0x02, 0x0B), written[0].payload)
        assertContentEquals(byteArrayOf(0xB9.toByte(), 0x02, 0x81.toByte(), 0x06, 0x03, 0x0B), written[1].payload)
        for (i in 2 until 22) {
            assertEquals(MessageType.Unknown(0x0300L), written[i].header.type)
        }
    }

    // ---- shared handshake driver -----------------------------------------------------------

    private suspend fun respondToHandshake(
        scope: TestScope,
        fake: FakeTonexTransport,
        activeSlot: PresetSlot = PresetSlot.A,
        presetInActiveSlot: Int = 0,
    ) {
        scope.testScheduler.runCurrent()
        fake.emitMessage(helloResponse())

        scope.testScheduler.runCurrent()
        val assignments = mutableMapOf(PresetSlot.A to 0, PresetSlot.B to 1, PresetSlot.C to 2)
        assignments[activeSlot] = presetInActiveSlot
        val blob = plausibleBlob(
            activeSlot = activeSlot,
            a = assignments.getValue(PresetSlot.A),
            b = assignments.getValue(PresetSlot.B),
            c = assignments.getValue(PresetSlot.C),
        )
        fake.emitMessage(stateUpdateMessage(blob))

        for (i in PresetIndex.VALID_RANGE) {
            scope.testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        scope.testScheduler.runCurrent()
    }
}
