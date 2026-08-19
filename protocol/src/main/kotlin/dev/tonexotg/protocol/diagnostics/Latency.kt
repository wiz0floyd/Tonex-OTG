package dev.tonexotg.protocol.diagnostics

/**
 * S21 (issue #26) latency-measurement instrumentation: pure, transport-agnostic timing logic that
 * lives here (rather than in `:app`) specifically so it can be unit-tested against
 * [dev.tonexotg.protocol.connection.FakeTonexTransport] with an injectable delay, per this
 * module's existing test discipline — see `DefaultTonexControllerLatencyTest` in `:protocol`'s
 * test source. Real USB latency obviously cannot be measured here; this only proves the
 * measurement arithmetic itself is correct. `:app`'s `ProbeSession` wraps real
 * `DefaultTonexController.selectPreset`/`setParameter` calls (against a real USB transport) with
 * [measureLatency] to produce the numbers a human reads off a real pedal.
 *
 * ⚠️ Per the product owner's issue #26 comment (recalibrating the story after it was written):
 * this is informational instrumentation, not a pass/fail gate. There is no hard millisecond target
 * these types encode or enforce — they just measure and report.
 */

/**
 * A source of "now," in microseconds, injectable so [measureLatency] can be exercised against a
 * virtual clock in tests (e.g. `kotlinx.coroutines.test.TestScope.currentTime`, which advances
 * deterministically with a [FakeTonexTransport]-simulated `delay()`, unlike a real wall clock
 * inside `runTest`'s auto-advanced virtual time) without ever sleeping the test thread for real.
 * Production callers use [SYSTEM], which is what actually runs on a real pedal connection.
 *
 * Microseconds, not milliseconds: a bulk USB transfer of a few dozen bytes plausibly completes in
 * well under 1ms, and millisecond-truncated samples collapse to 0 and destroy the signal a burst
 * measurement ([BurstStats]) depends on — see issue #26's adversarial review, finding S2.
 */
fun interface ElapsedClock {
    fun nowMicros(): Long

    companion object {
        /**
         * `System.nanoTime()`-derived, not `System.currentTimeMillis()` — this measures elapsed
         * duration, not wall-clock position, and must not be perturbed by a concurrent clock
         * adjustment (NTP sync, user changing the device clock) mid-measurement.
         */
        val SYSTEM: ElapsedClock = ElapsedClock { System.nanoTime() / 1_000L }
    }
}

/** The result of one [measureLatency] call: [value] is whatever [block] returned, timed by [elapsedMicros]. */
data class TimedResult<T>(val value: T, val elapsedMicros: Long)

/**
 * Runs [block], returning its result paired with how long it took per [clock]. [clock] defaults to
 * a real wall clock ([ElapsedClock.SYSTEM]) for production use; tests inject a virtual one (see
 * [ElapsedClock]'s KDoc) to prove this arithmetic is correct without a real sleep.
 *
 * Deliberately does not catch or interpret any exception [block] throws — this is a pure timing
 * wrapper, not a result-classifying one. Callers measuring a
 * [dev.tonexotg.protocol.connection.DefaultTonexController] operation get a
 * [dev.tonexotg.protocol.TonexResult] back as `value` and inspect that themselves, matching this
 * codebase's "fail fast and loud, surface a typed error" house style rather than this wrapper
 * inventing a second one.
 */
suspend fun <T> measureLatency(clock: ElapsedClock = ElapsedClock.SYSTEM, block: suspend () -> T): TimedResult<T> {
    val start = clock.nowMicros()
    val value = block()
    val end = clock.nowMicros()
    return TimedResult(value, end - start)
}

/**
 * Summary statistics over a burst of [measureLatency] samples (microseconds each) — issue #26's
 * "sustained slider-drag throughput" measurement: does per-call latency grow or does work
 * queue/backlog over a rapid-fire burst of writes with no pacing between them?
 *
 * Percentiles use the nearest-rank method over the samples sorted ascending — simple, exact for
 * small burst sizes (tens of samples), and not worth a more elaborate estimator for a diagnostic
 * tool a human reads once per run.
 *
 * [growthRatio] compares the mean of the last third of the burst against the mean of the first
 * third: a ratio near 1.0 means latency held steady across the burst; growing well above 1.0 is
 * exactly the "queues/backs up under sustained load" signal issue #26 asks about. [firstThirdMeanMicros]
 * and [lastThirdMeanMicros] are reported alongside it so a human reading the log sees the actual
 * numbers, not just the derived ratio — the raw values are the ground truth; [growthRatio] is a
 * convenience.
 *
 * [growthRatio] is [Double.NaN] when the first-third mean is exactly zero (division by zero) —
 * this is a genuine "cannot compute a ratio" state, not "no growth," and callers must not collapse
 * it into [backlogSuspected]`== false`'s "steady" framing. See issue #26's adversarial review,
 * finding S1: `ProbeSession` reports this as its own distinct "ratio undefined" state rather than
 * folding it into the "no backlog signal" message.
 */
data class BurstStats(
    val count: Int,
    val minMicros: Long,
    val maxMicros: Long,
    val meanMicros: Double,
    val p50Micros: Long,
    val p90Micros: Long,
    val p99Micros: Long,
    val firstThirdMeanMicros: Double,
    val lastThirdMeanMicros: Double,
    val growthRatio: Double,
) {
    /**
     * A heuristic flag, not a protocol constant and not derived from any measurement — this is a
     * diagnostic-log convenience only, exactly the kind of "not derived from an upstream delay"
     * distinction [dev.tonexotg.protocol.connection.ConnectionTimeouts.DEFAULT]'s own KDoc draws
     * for its timing constants. [GROWTH_RATIO_FLAG_THRESHOLD] just decides whether the log calls
     * this out as a finding; the raw [growthRatio] and per-sample numbers are always logged
     * regardless, so a human is never dependent on this flag's threshold being "right." `false`
     * when [growthRatio] is non-finite (see class KDoc) — that case is a distinct "undefined," not
     * a positive "no backlog" result, and callers must report it as such rather than via this flag.
     */
    val backlogSuspected: Boolean get() = growthRatio.isFinite() && growthRatio >= GROWTH_RATIO_FLAG_THRESHOLD

    companion object {
        /** See [backlogSuspected]'s KDoc — a diagnostic-log threshold, not a measured or protocol value. */
        const val GROWTH_RATIO_FLAG_THRESHOLD: Double = 1.5

        /** @throws IllegalArgumentException if [samplesMicros] is empty — there is no meaningful summary of zero samples. */
        fun of(samplesMicros: List<Long>): BurstStats {
            require(samplesMicros.isNotEmpty()) { "BurstStats.of: samplesMicros must not be empty" }
            val sorted = samplesMicros.sorted()
            val thirdSize = maxOf(1, sorted.size / 3)
            val firstThird = samplesMicros.take(thirdSize)
            val lastThird = samplesMicros.takeLast(thirdSize)
            val firstThirdMean = firstThird.average()
            val lastThirdMean = lastThird.average()
            return BurstStats(
                count = sorted.size,
                minMicros = sorted.first(),
                maxMicros = sorted.last(),
                meanMicros = samplesMicros.average(),
                p50Micros = percentile(sorted, 50),
                p90Micros = percentile(sorted, 90),
                p99Micros = percentile(sorted, 99),
                firstThirdMeanMicros = firstThirdMean,
                lastThirdMeanMicros = lastThirdMean,
                growthRatio = if (firstThirdMean > 0.0) lastThirdMean / firstThirdMean else Double.NaN,
            )
        }

        /** Nearest-rank percentile over an already-ascending-sorted list. */
        private fun percentile(sortedAscending: List<Long>, pct: Int): Long {
            val rank = ((pct / 100.0) * sortedAscending.size).let { kotlin.math.ceil(it).toInt() }
                .coerceIn(1, sortedAscending.size)
            return sortedAscending[rank - 1]
        }
    }
}
