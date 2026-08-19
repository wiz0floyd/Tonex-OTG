package dev.tonexotg.app.usb

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic, pure-JVM [UsbIoPort] fake for [TonexUsbTransportTest]. Exists because
 * Robolectric's real shadow (see [UsbIoPort]'s class KDoc) cannot simulate a `requestWait` that
 * ignores its own timeout (the wedge/watchdog scenario), a queue failure, or a short/failed
 * write -- this fake can produce all three on demand.
 */
internal class FakeUsbIoPort : UsbIoPort {

    /** Feed with [enqueueRead] before the outcome is needed; [requestWait] blocks until one arrives. */
    private val readOutcomes = LinkedBlockingQueue<ReadOutcome>()

    /**
     * How [requestWait] behaves when [wedgeMode] is not [WedgeMode.NONE] -- both simulate a
     * `requestWait` that never honors [UsbIoPort.requestWait]'s own `timeoutMillis` contract, the
     * one platform-bug precondition the watchdog exists for (see
     * [TonexUsbTransport]'s watchdog KDoc: a well-behaved bounded call can't actually wedge).
     */
    enum class WedgeMode {
        /** Normal operation: [requestWait] honors [readOutcomes]/the timeout as usual. */
        NONE,

        /**
         * Blocks on an interrupt-responsive wait -- represents a wedge that `cancelRead()` +
         * `Thread.interrupt()` genuinely unblocks, matching the realistic case: `requestWait`'s
         * timeout is honored right up until the exact edge case being tested.
         */
        INTERRUPTIBLE,

        /**
         * Blocks in a loop that swallows `InterruptedException` for [uninterruptibleForMillis] --
         * represents a thread that does NOT respond to interrupt within a bounded join, the case
         * [TonexUsbTransport.teardown] must refuse to close into (leak-and-fail-loud) rather than
         * risk a native use-after-free. Stops ignoring interrupts after the budget elapses so the
         * background thread doesn't outlive the test process.
         */
        UNINTERRUPTIBLE,
    }

    val wedgeMode = AtomicReference(WedgeMode.NONE)
    var uninterruptibleForMillis: Long = 5_000L

    val queueReadCallCount = AtomicInteger(0)
    val queueReadResult = AtomicBoolean(true)

    val cancelReadCallCount = AtomicInteger(0)
    val releaseAndCloseCallCount = AtomicInteger(0)

    /** Every [writeBulk] call's requested bytes, in order, for assertions. */
    val writesReceived = CopyOnWriteArrayList<ByteArray>()

    /** Next [writeBulk] return value(s); consumed in order, then defaults to a full-size write. */
    private val writeResults = LinkedBlockingQueue<Int>()

    /** When true, [writeBulk] throws instead of returning. */
    val writeThrows = AtomicBoolean(false)

    /** Invoked synchronously at the start of every [writeBulk] call -- lets a test synchronize on it. */
    var onWriteBulk: ((bytes: ByteArray) -> Unit)? = null

    fun enqueueRead(outcome: ReadOutcome) = readOutcomes.put(outcome)

    fun enqueueWriteResult(result: Int) = writeResults.put(result)

    override fun queueRead(): Boolean {
        queueReadCallCount.incrementAndGet()
        return queueReadResult.get()
    }

    override fun requestWait(timeoutMillis: Long): ReadOutcome {
        when (wedgeMode.get()) {
            WedgeMode.INTERRUPTIBLE -> {
                Thread.sleep(Long.MAX_VALUE) // throws InterruptedException when interrupted
                error("unreachable")
            }
            WedgeMode.UNINTERRUPTIBLE -> {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(uninterruptibleForMillis)
                while (System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        // Simulates a native call that does not respond to interrupt: keep blocking.
                    }
                }
                return ReadOutcome.TimedOut
            }
            WedgeMode.NONE -> Unit
        }
        return readOutcomes.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: ReadOutcome.TimedOut
    }

    override fun cancelRead() {
        cancelReadCallCount.incrementAndGet()
    }

    override fun writeBulk(bytes: ByteArray, timeoutMillis: Int): Int {
        writesReceived.add(bytes)
        onWriteBulk?.invoke(bytes)
        if (writeThrows.get()) throw RuntimeException("FakeUsbIoPort: writeBulk configured to throw")
        return writeResults.poll() ?: bytes.size
    }

    override fun releaseAndClose() {
        releaseAndCloseCallCount.incrementAndGet()
    }
}
