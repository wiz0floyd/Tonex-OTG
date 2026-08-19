package dev.tonexotg.app.ui.screens.parameters

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.TonexResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The NFR2 write-conflation path for continuous slider drags (D3 §3, §6.1's "Latency" note on
 * the issue this implements): "Continuous slider drags must not queue an unbounded write
 * backlog. Throttle or conflate in-flight parameter writes — the last value wins."
 *
 * ## Why a conflated channel, not a debounce timer
 *
 * A time-based debounce (wait Nms of silence before writing) trades one problem for a worse one:
 * it either delays every write by the debounce window (fighting NFR2's own sub-200ms target) or,
 * if the debounce window is short, still lets a fast drag queue several writes back-to-back once
 * the pointer briefly pauses. [Channel.CONFLATED] instead gives an exact, well-understood
 * guarantee with no timer at all: **at most one value is ever buffered per parameter**, and
 * offering a new value silently replaces whatever was buffered and not yet picked up. Combined
 * with exactly one long-lived worker coroutine per parameter id that loops
 * `write(id, receive())`, this means the number of writes actually in flight or queued for a
 * given parameter is bounded at 2 (one executing, one conflated-and-waiting) regardless of how
 * many times [submit] is called — never an unbounded backlog — and the value that worker sees
 * once it's free to pick up the next one is always the *most recent* [submit], i.e. last value
 * wins. There is deliberately no read-back after `write` (issue #22 / S9b: "do not add per-write
 * read-back verification").
 *
 * One worker is created lazily per [ParameterId] on its first [submit] and lives for this
 * throttler's lifetime (or until [cancelAll] tears it down) — a drag on one slider never blocks
 * or is conflated against a drag on a different slider, since each gets its own channel.
 *
 * @param scope the coroutine scope each parameter's worker is launched into — cancelling [scope]
 *   (e.g. the owning view model's `onCleared`) stops all outstanding workers.
 * @param write performs the actual write, e.g. `controller::setParameter`. Suspends for the
 *   duration of one write; the next conflated value (if any) is only picked up once this returns.
 * @param onResult observed after each [write] completes — e.g. so a caller can clear an optimistic
 *   local override once the controller's own state has caught up, or surface a failure.
 */
class ParameterWriteThrottler(
    private val scope: CoroutineScope,
    private val write: suspend (ParameterId, Float) -> TonexResult<Unit>,
    private val onResult: (ParameterId, Float, TonexResult<Unit>) -> Unit = { _, _, _ -> },
) {
    private val lock = Any()
    private val channels = HashMap<Int, Channel<Float>>()
    private val workers = HashMap<Int, Job>()

    /**
     * Enqueues [value] as the latest value to write for [id]. Never suspends: if a write for
     * [id] is already in flight, [value] simply replaces whatever was previously buffered and
     * not yet picked up (or becomes the buffered value if none was) — see the class KDoc for why
     * this is safe.
     */
    fun submit(id: ParameterId, value: Float) {
        val channel = channelFor(id)
        channel.trySend(value)
    }

    private fun channelFor(id: ParameterId): Channel<Float> = synchronized(lock) {
        channels.getOrPut(id.index) {
            val channel = Channel<Float>(capacity = Channel.CONFLATED)
            workers[id.index] = scope.launch {
                for (value in channel) {
                    val result = write(id, value)
                    onResult(id, value, result)
                }
            }
            channel
        }
    }

    /**
     * Stops every worker and discards every buffered-but-not-yet-sent value, without waiting for
     * any write currently in flight to finish (that write already left this process; there is no
     * way to unsend it). Per D3 §6.3: when the pedal's active preset changes out from under an
     * open editor, an in-flight drag "does not send a final write for the stale touch" — this is
     * that cancellation. Workers are recreated lazily on the next [submit], so this throttler
     * remains usable afterward (e.g. for the newly-active preset).
     */
    fun cancelAll() {
        synchronized(lock) {
            workers.values.forEach { it.cancel() }
            channels.values.forEach { it.close() }
            workers.clear()
            channels.clear()
        }
    }
}
