@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.framing.HdlcFrame
import dev.tonexotg.protocol.message.FirmwareCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression coverage for the Opus review of PR #43 (2026-08-17), findings 1 and 2:
 * [ConnectionState.Error] must never be a permanent dead end. The review proved this empirically —
 * hello timeout → `Error` → `disconnect()` → still `Error` → reconnect rejected → still `Error` —
 * because `teardown()`'s idempotence guard gated *state publication*, not just resource release, so
 * a `disconnect()` racing (or following) an already-torn-down failing `connect()` published nothing.
 *
 * Every test below drives the machine into a *genuine* [ConnectionState.Error] via a distinct
 * failure stage (not a stub or a directly-set field), then proves the full documented recovery path
 * actually works: [DefaultTonexController.disconnect] reaches [ConnectionState.Idle], and a
 * subsequent [DefaultTonexController.connect] on a fresh transport reaches [ConnectionState.Ready].
 */
class DefaultTonexControllerErrorRecoveryTest {

    // ---- finding 1: Error must not be a permanent dead end, from every stage that can produce it ----

    @Test
    fun `recovers from Error after a hello timeout`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.helloMillis + 1)
        testScheduler.runCurrent()
        assertIs<TonexResult.Failure>(connectDeferred.await())

        assertRecoversToReady(controller)
    }

    @Test
    fun `recovers from Error after a get-state timeout`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.getStateMillis + 1)
        testScheduler.runCurrent()
        assertIs<TonexResult.Failure>(connectDeferred.await())

        assertRecoversToReady(controller)
    }

    @Test
    fun `recovers from Error after a preset-details harvest timeout`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        testScheduler.runCurrent() // preset 0's request written, awaiting response
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.presetDetailsMillis + 1)
        testScheduler.runCurrent()
        assertIs<TonexResult.Failure>(connectDeferred.await())

        assertRecoversToReady(controller)
    }

    @Test
    fun `recovers from Error after a malformed (CRC-corrupt) frame during Hello`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        val corrupt = HdlcFrame.encode(helloResponse()).also {
            it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte()
        }
        fake.emitRaw(corrupt)
        testScheduler.runCurrent()
        assertIs<TonexResult.Failure>(connectDeferred.await())

        assertRecoversToReady(controller)
    }

    @Test
    fun `recovers from Error after an out-of-order message during Hello`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob())) // wrong stage entirely
        testScheduler.runCurrent()
        assertIs<TonexResult.Failure>(connectDeferred.await())

        assertRecoversToReady(controller)
    }

    /**
     * Shared assertion: from a genuinely-reached [ConnectionState.Error], [DefaultTonexController.disconnect]
     * must reach [ConnectionState.Idle], and a subsequent [DefaultTonexController.connect] on a fresh
     * transport must reach [ConnectionState.Ready] — the exact recovery path the review's finding 1
     * proved broken empirically. Asserting `Error` first (not just trusting the caller) makes sure
     * this is exercising a real wedge, not a tautology.
     */
    private suspend fun TestScope.assertRecoversToReady(controller: DefaultTonexController) {
        assertIs<ConnectionState.Error>(controller.connectionState.value, "test setup did not actually reach Error")

        controller.disconnect()
        assertEquals(ConnectionState.Idle, controller.connectionState.value, "disconnect() must reach Idle from Error")

        val freshFake = FakeTonexTransport()
        val reconnectDeferred = async { controller.connect(freshFake) }
        driveToReady(freshFake)
        val result = reconnectDeferred.await()

        assertIs<TonexResult.Success<Unit>>(result, "reconnect after Error must succeed")
        assertEquals(ConnectionState.Ready, controller.connectionState.value)
        assertEquals(20, controller.presets.value.size)
    }

    // ---- finding 2: disconnect() racing a protocol-level connect() failure must still reach Idle ----

    @Test
    fun `disconnect() racing an in-flight connect() that fails on the protocol side still lands on Idle, not Error`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent() // connect() reaches Hello and subscribes
        assertEquals(ConnectionState.Hello, controller.connectionState.value)

        // Queue a message that fails connect() on the protocol side (a genuine
        // ProtocolStateViolation, not one manufactured by disconnect() itself) and race disconnect()
        // against it landing - matching the review's finding 2: "disconnect() called while an
        // in-flight connect() is failing on the protocol side". Both are enqueued before the
        // scheduler is allowed to run either, so their relative ordering is exercised for real
        // rather than forced by test sequencing.
        fake.emitMessage(stateUpdateMessage(plausibleBlob())) // wrong stage - fails Hello
        val disconnectDeferred = async { controller.disconnect() }
        testScheduler.runCurrent()

        val connectResult = connectDeferred.await()
        disconnectDeferred.await()

        assertIs<TonexResult.Failure>(connectResult, "the raced connect() must still fail")
        assertEquals(
            ConnectionState.Idle,
            controller.connectionState.value,
            "disconnect() must win the race and leave the machine at Idle, never wedged in Error",
        )
    }
}
