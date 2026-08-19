package dev.tonexotg.app.usb

import app.cash.turbine.test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [TonexUsbTransport] against a deterministic [FakeUsbIoPort] rather than
 * Robolectric's real (but limited -- see [UsbIoPort]'s class KDoc) `ShadowUsbDeviceConnection`.
 * This is where S11's "wedged-reader watchdog covered by a test" acceptance criterion actually
 * gets pinned -- the Robolectric shadow cannot simulate a `requestWait` that ignores its own
 * timeout at all.
 *
 * A short [watchdogTimeoutMillis]/[watchdogPollMillis] pair is used throughout so these tests run
 * in well under a second of real wall-clock time each; [TonexUsbTransport]'s own dedicated
 * threads do real (non-virtual) `Thread.sleep`/blocking calls, so there is no virtual-time
 * scheduler to fast-forward here the way `kotlinx-coroutines-test` normally would.
 */
class TonexUsbTransportTest {

    private fun newTransport(
        port: FakeUsbIoPort,
        watchdogTimeoutMillis: Long = 300L,
        watchdogPollMillis: Long = 50L,
    ): TonexUsbTransport = TonexUsbTransport(port, watchdogTimeoutMillis, watchdogPollMillis)

    // ---------------------------------------------------------------------------------------
    // write()
    // ---------------------------------------------------------------------------------------

    @Test
    fun write_returnsActualBulkTransferCount() = runBlocking {
        val port = FakeUsbIoPort()
        val transport = newTransport(port)
        val n = transport.write(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(5, n)
        assertEquals(1, port.writesReceived.size)
        assertTrue(port.writesReceived[0].contentEquals(byteArrayOf(1, 2, 3, 4, 5)))
        transport.close()
    }

    @Test
    fun write_shortWriteIsReturnedNotThrown() = runBlocking {
        val port = FakeUsbIoPort()
        port.enqueueWriteResult(3) // fewer bytes than requested
        val transport = newTransport(port)
        val n = transport.write(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(3, n) // a legitimate short write, per TonexTransport.write's contract
        transport.close()
    }

    @Test
    fun write_negativeReturnCodeThrowsWithRcInMessage() = runBlocking {
        val port = FakeUsbIoPort()
        port.enqueueWriteResult(-5)
        val transport = newTransport(port)
        val error = runCatching { transport.write(byteArrayOf(1, 2, 3)) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertTrue("expected rc in message, got: ${error?.message}", error!!.message!!.contains("rc=-5"))
        transport.close()
    }

    @Test
    fun write_oversizedFailedWriteMessageFlagsUntestedHardwareHazard() = runBlocking {
        val port = FakeUsbIoPort()
        port.enqueueWriteResult(-1)
        val transport = newTransport(port)
        val bigPayload = ByteArray(200)
        val error = runCatching { transport.write(bigPayload) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("issue #25"))
        transport.close()
    }

    @Test
    fun write_afterCloseThrows() = runBlocking {
        val port = FakeUsbIoPort()
        val transport = newTransport(port)
        transport.close()
        val error = runCatching { transport.write(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }

    @Test
    fun write_callsAreSerialized() = runBlocking {
        val port = FakeUsbIoPort()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        port.onWriteBulk = { bytes ->
            if (bytes.contentEquals(byteArrayOf(1))) {
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
        }
        val transport = newTransport(port)

        val job1 = async(Dispatchers.Default) { transport.write(byteArrayOf(1)) }
        assertTrue("first write should have started", firstStarted.await(2, TimeUnit.SECONDS))

        val job2 = async(Dispatchers.Default) { transport.write(byteArrayOf(2)) }
        delay(150) // give job2 every chance to (incorrectly) race ahead if writes weren't serialized
        assertEquals(
            "second write must not reach writeBulk while the first is still in flight",
            1,
            port.writesReceived.size,
        )

        releaseFirst.countDown()
        job1.await()
        job2.await()
        assertEquals(2, port.writesReceived.size)
        assertTrue(port.writesReceived[0].contentEquals(byteArrayOf(1)))
        assertTrue(port.writesReceived[1].contentEquals(byteArrayOf(2)))
        transport.close()
    }

    // ---------------------------------------------------------------------------------------
    // incoming()
    // ---------------------------------------------------------------------------------------

    @Test
    fun incoming_emitsBytesFromReadCompletions() = runBlocking {
        val port = FakeUsbIoPort()
        val transport = newTransport(port)
        port.enqueueRead(ReadOutcome.Completed(byteArrayOf(0x0A, 0x0B)))
        port.enqueueRead(ReadOutcome.Completed(byteArrayOf(0x0C)))

        transport.incoming().test {
            assertTrue(awaitItem().contentEquals(byteArrayOf(0x0A, 0x0B)))
            assertTrue(awaitItem().contentEquals(byteArrayOf(0x0C)))
            transport.close()
            awaitComplete()
        }
    }

    @Test
    fun incoming_emptyCompletionsAreNotEmitted() = runBlocking {
        val port = FakeUsbIoPort()
        val transport = newTransport(port)
        port.enqueueRead(ReadOutcome.Completed(ByteArray(0)))
        port.enqueueRead(ReadOutcome.Completed(byteArrayOf(0x01)))

        transport.incoming().test {
            // The empty completion above must be skipped -- only the non-empty one is delivered.
            assertTrue(awaitItem().contentEquals(byteArrayOf(0x01)))
            transport.close()
            awaitComplete()
        }
    }

    // ---------------------------------------------------------------------------------------
    // close()
    // ---------------------------------------------------------------------------------------

    @Test
    fun close_isIdempotent() {
        val port = FakeUsbIoPort()
        val transport = newTransport(port)
        transport.close()
        transport.close() // must not throw
        assertEquals(1, port.releaseAndCloseCallCount.get())
    }

    @Test
    fun close_releasesConnectionWhenReaderAndWriterAreJoinable() {
        val port = FakeUsbIoPort()
        val transport = newTransport(port)
        transport.close()
        assertEquals(1, port.releaseAndCloseCallCount.get())
        assertTrue(port.cancelReadCallCount.get() >= 1)
    }

    // ---------------------------------------------------------------------------------------
    // Watchdog
    // ---------------------------------------------------------------------------------------

    /**
     * The literal acceptance criterion: a reader that stops making progress gets force-closed by
     * the watchdog. [FakeUsbIoPort.WedgeMode.INTERRUPTIBLE] represents the realistic case per the
     * design's own reasoning (`TonexUsbTransport.teardown`'s KDoc) -- `requestWait` is always
     * called with a bounded timeout, so a "wedge" here means the platform call itself violated
     * that bound, and `cancelRead()` + `Thread.interrupt()` is what actually recovers it.
     */
    @Test
    fun watchdog_forceClosesAnInterruptibleWedgedReader() = runBlocking {
        val port = FakeUsbIoPort()
        port.wedgeMode.set(FakeUsbIoPort.WedgeMode.INTERRUPTIBLE)
        val transport = newTransport(port, watchdogTimeoutMillis = 200L, watchdogPollMillis = 50L)

        withTimeout(5_000) {
            while (port.releaseAndCloseCallCount.get() == 0) delay(20)
        }

        assertTrue(port.cancelReadCallCount.get() >= 1)
        assertEquals(1, port.releaseAndCloseCallCount.get())

        // incoming() should complete with the watchdog's own explanatory cause.
        transport.incoming().test {
            val thrown = awaitError()
            assertTrue(thrown.message!!.contains("watchdog"))
        }
    }

    /**
     * The safety property found during design review: if a thread cannot be confirmed to have
     * actually exited (joined) within its bounded timeout, [TonexUsbTransport] must NOT call
     * `releaseAndClose()` -- doing so while that thread might still be inside a blocking native
     * call would be a use-after-free. It must instead leak the handle and fail loud everywhere
     * else. [FakeUsbIoPort.WedgeMode.UNINTERRUPTIBLE] simulates a thread that doesn't respond to
     * interrupt within the join bound (representing a genuinely stuck native call).
     */
    @Test
    fun watchdog_leaksRatherThanCloseIntoAnUnjoinableReader() = runBlocking {
        val port = FakeUsbIoPort()
        port.wedgeMode.set(FakeUsbIoPort.WedgeMode.UNINTERRUPTIBLE)
        port.uninterruptibleForMillis = 3_000L // comfortably longer than TonexUsbTransport's internal join bound
        val transport = newTransport(port, watchdogTimeoutMillis = 100L, watchdogPollMillis = 50L)

        // Poll write()'s rejection message until teardown has actually finished attempting (and
        // failing) its joins -- closed flips true immediately when the watchdog fires, well before
        // the join attempts resolve, so a naive single check right after the watchdog's poll
        // interval would race the leak-specific message.
        var lastMessage = ""
        withTimeout(6_000) {
            while (!lastMessage.contains("leaking")) {
                lastMessage = runCatching { transport.write(byteArrayOf(1)) }
                    .exceptionOrNull()?.message ?: ""
                delay(50)
            }
        }

        assertEquals(
            "must never release/close a connection a thread might still be blocked inside",
            0,
            port.releaseAndCloseCallCount.get(),
        )
        assertTrue(lastMessage.contains("use-after-free"))
    }

    // ---------------------------------------------------------------------------------------
    // queueRead() failure
    // ---------------------------------------------------------------------------------------

    @Test
    fun initialQueueReadFailure_closesIncomingWithError() = runBlocking {
        val port = FakeUsbIoPort()
        port.queueReadResult.set(false)
        val transport = newTransport(port)

        transport.incoming().test {
            val error = awaitError()
            assertTrue(error.message!!.contains("initial IN queue"))
        }
    }
}
