@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.diagnostics

import dev.tonexotg.protocol.connection.FakeTonexTransport
import dev.tonexotg.protocol.connection.helloResponse
import dev.tonexotg.protocol.connection.plausibleBlob
import dev.tonexotg.protocol.connection.presetDetailsSummary
import dev.tonexotg.protocol.connection.stateUpdateMessage
import dev.tonexotg.protocol.message.TonexMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #27 (S22) — [MessageCaptureTap] is the read-only side channel every safety drill capture
 * goes through. These tests exercise it standalone (no [dev.tonexotg.protocol.connection.DefaultTonexController]
 * involved), the same way `:app`'s real usage will feed it raw bytes from a shared USB transport.
 */
class MessageCaptureTapTest {

    @Test
    fun `a message emitted before awaitMessage subscribes is still captured, not dropped`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }

        // Emit and let the reassembler/decoder run BEFORE awaitMessage is ever called -- this is
        // exactly the race a zero-replay SharedFlow would lose and an unbounded Channel does not.
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        testScheduler.runCurrent()

        val message = tap.awaitMessage(1_000) { it is TonexMessage.StateUpdate }
        assertIs<TonexMessage.StateUpdate>(message)

        collectJob.cancel()
    }

    @Test
    fun `awaitMessage discards non-matching messages and returns the first real match`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }

        fake.emitMessage(helloResponse())
        fake.emitMessage(presetDetailsSummary("Not it"))
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        testScheduler.runCurrent()

        val message = tap.awaitMessage(1_000) { it is TonexMessage.StateUpdate }
        assertIs<TonexMessage.StateUpdate>(message)

        collectJob.cancel()
    }

    @Test
    fun `drain discards buffered messages so the next awaitMessage never sees stale traffic`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }

        // Simulates a handshake's worth of leftover traffic sitting in the tap before a drill
        // starts its own request/capture cycle.
        fake.emitMessage(helloResponse())
        fake.emitMessage(stateUpdateMessage(plausibleBlob(a = 0)))
        testScheduler.runCurrent()

        tap.drain()

        // Nothing left to match -- times out rather than returning the drained blob.
        val timedOut = tap.awaitMessage(100) { it is TonexMessage.StateUpdate }
        assertNull(timedOut)

        // A genuinely fresh message after drain() is still captured normally.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(a = 5)))
        testScheduler.runCurrent()
        val fresh = tap.awaitMessage(1_000) { it is TonexMessage.StateUpdate }
        assertIs<TonexMessage.StateUpdate>(fresh)
        assertTrue((fresh as TonexMessage.StateUpdate).payload.contentEquals(plausibleBlob(a = 5)))

        collectJob.cancel()
    }

    @Test
    fun `awaitMessage times out when nothing matching ever arrives`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }

        fake.emitMessage(helloResponse()) // never a StateUpdate
        testScheduler.runCurrent()

        val result = tap.awaitMessage(1_000) { it is TonexMessage.StateUpdate }
        assertNull(result)

        collectJob.cancel()
    }

    @Test
    fun `a malformed frame is counted as a decode failure and does not crash capture`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }

        // A lone 0x7D (escape) immediately before a closing flag -- HdlcFrame's documented
        // dangling-escape malformed-frame case.
        fake.emitRaw(byteArrayOf(0x7E, 0x01, 0x7D, 0x7E))
        testScheduler.runCurrent()

        assertTrue(tap.decodeFailureCount >= 1)

        // The tap keeps working correctly afterward -- one bad frame does not wedge it.
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        testScheduler.runCurrent()
        val message = tap.awaitMessage(1_000) { it is TonexMessage.StateUpdate }
        assertIs<TonexMessage.StateUpdate>(message)

        collectJob.cancel()
    }
}
