package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ParameterId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rate-limits inbound `ParameterChanged` notifications on their way into
 * [dev.tonexotg.protocol.TonexController.parameterValues] (issue #104, scope item 4).
 *
 * The pedal streams roughly **one frame every 10 ms (~100 Hz)** for as long as a physical knob is
 * being turned (#106). Applying each one straight into a `StateFlow<Map<...>>` means ~100 map
 * copies and ~100 recompositions a second for a change no eye can follow at that rate. This is the
 * mirror image of what PR #101 did for the opposite direction (write-on-release instead of a write
 * per drag tick) — the inbound side needs the same treatment.
 *
 * ## Leading edge, not trailing — deliberately
 *
 * The **first** submission in an idle window is applied **synchronously, inside [submit]**, before
 * it returns. Only submissions arriving while that window is still open are deferred, coalesced
 * per [ParameterId] (last write wins), and flushed as one batch when the window closes.
 *
 * That leading-edge behaviour is load-bearing, not a nicety: `DefaultTonexController` treats "the
 * reader has processed this `ParameterChanged` frame" as equivalent to "`parameterValues` now
 * carries its value" — `harvestMasterVolume` awaits the frame and then relies on the reader having
 * already applied it, and several existing tests assert on `parameterValues` immediately after
 * feeding a single frame in. A purely trailing/sampling throttle would break both by introducing a
 * delay before a one-off frame lands. A knob *sweep*, on the other hand, is by definition many
 * frames, so it is the sweep — and only the sweep — that gets coalesced.
 *
 * The worst-case added latency for any single value is therefore [intervalMillis], and only for a
 * value that is already being superseded by a newer one.
 *
 * ## Threading
 *
 * [submit] is called from `DefaultTonexController`'s single reader coroutine, but [flushJob] runs
 * on [scope] and mutates the same state, so both are guarded by one plain `synchronized` monitor.
 * `:protocol` is JVM-only (issue #15), so a monitor is available and is simpler here than a
 * `Mutex`: [submit] must stay non-suspending to keep the leading-edge apply synchronous.
 *
 * [apply] is invoked **outside** the monitor, so a slow consumer can never hold the lock the
 * reader loop needs.
 */
internal class InboundParameterThrottle(
    private val scope: CoroutineScope,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val apply: (Map<ParameterId, Float>) -> Unit,
) {

    private val lock = Any()
    private val pending = mutableMapOf<ParameterId, Float>()
    private var windowOpen = false
    private var flushJob: Job? = null

    /**
     * Records [value] for [id]. Applies immediately (and opens a coalescing window) if no window is
     * currently open; otherwise defers it to that window's flush, replacing any value already
     * pending for the same [id].
     */
    fun submit(id: ParameterId, value: Float) {
        val applyNow: Boolean
        synchronized(lock) {
            if (windowOpen) {
                pending[id] = value
                applyNow = false
            } else {
                windowOpen = true
                applyNow = true
                flushJob = scope.launch { runWindow() }
            }
        }
        if (applyNow) apply(mapOf(id to value))
    }

    /**
     * Drops everything pending and stops the open window — for a disconnect/teardown, where the
     * values buffered here describe a session that no longer exists. Deliberately does **not**
     * flush them first: `DefaultTonexController` clears `parameterValues` wholesale on disconnect,
     * so flushing would write stale values back into a map that was just emptied.
     */
    fun cancelAll() {
        val job: Job?
        synchronized(lock) {
            pending.clear()
            windowOpen = false
            job = flushJob
            flushJob = null
        }
        job?.cancel()
    }

    /**
     * One coalescing window: wait [intervalMillis], flush whatever accumulated, and repeat for as
     * long as values keep arriving. The loop (rather than a single delay-and-close) is what keeps a
     * sustained knob sweep pinned at one flush per interval instead of alternating between a
     * leading-edge apply and a flush for every other frame.
     */
    private suspend fun runWindow() {
        while (true) {
            delay(intervalMillis)
            val batch: Map<ParameterId, Float>
            synchronized(lock) {
                if (pending.isEmpty()) {
                    windowOpen = false
                    flushJob = null
                    return
                }
                batch = pending.toMap()
                pending.clear()
            }
            apply(batch)
        }
    }

    internal companion object {
        /** ~30 Hz: one frame's worth of latency at 60 fps, against an inbound stream arriving at ~100 Hz. */
        const val DEFAULT_INTERVAL_MILLIS: Long = 33L
    }
}
