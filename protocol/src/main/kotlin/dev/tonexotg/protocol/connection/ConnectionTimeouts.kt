package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.TonexError

/**
 * Every time budget [DefaultTonexController] enforces, named and justified — the acceptance
 * criterion behind this type is "no timing constant is a bare magic number."
 *
 * Each field corresponds one-to-one with a [TonexError.Timeout.operation] string a caller can
 * match on. See [DEFAULT] for the reasoning behind every default value, and the two subsections
 * below for timing decisions this type deliberately does **not** encode.
 *
 * @property helloMillis budget for the opening [dev.tonexotg.protocol.message.HelloMessage]
 *   round trip. `TonexError.Timeout.operation == "hello"`.
 * @property getStateMillis budget for the handshake's `RequestState` round trip.
 *   `operation == "get-state"`.
 * @property stateReadMillis budget for the mandatory pre-patch re-read inside `selectPreset`.
 *   `operation == "state-read"`.
 * @property presetDetailsMillis budget for one preset-name harvest round trip.
 *   `operation == "preset-details"`.
 * @property presetParametersMillis budget for the snapshot-capture preset-details round trip.
 *   `operation == "preset-parameters"`.
 * @property masterVolumeMillis budget for the master-volume harvest round trip.
 *   `operation == "master-volume"`.
 * @property transportWriteMillis budget for a single [dev.tonexotg.protocol.TonexTransport.write]
 *   call. `operation == "transport-write"`.
 */
data class ConnectionTimeouts(
    val helloMillis: Long,
    val getStateMillis: Long,
    val stateReadMillis: Long,
    val presetDetailsMillis: Long,
    val presetParametersMillis: Long,
    val masterVolumeMillis: Long,
    val transportWriteMillis: Long,
) {
    companion object {
        /**
         * The engineering-estimate defaults every field below is justified against. None of these
         * are derived from a measured upstream delay — see the "What is deliberately absent"
         * section for the two timing behaviours considered and rejected during S9's design.
         *
         * | Constant | Value | Justification |
         * |---|---|---|
         * | [ConnectionTimeouts.helloMillis] | 2000 | A 13-byte request; the response is tiny. A USB CDC-ACM round trip is sub-millisecond in principle, so this is roughly 3 orders of magnitude of headroom — chosen to absorb Android USB permission/enumeration jitter immediately after attach, while still failing fast enough that a later story can say something useful when the wrong device is plugged in. **Not derived from any upstream delay.** |
         * | [ConnectionTimeouts.getStateMillis] | 2000 | Same shape; the response is capped at [dev.tonexotg.protocol.PedalState.MAX_STATE_BYTES] (512 B) — a handful of transport chunks. |
         * | [ConnectionTimeouts.stateReadMillis] | 2000 | The mandatory pre-patch re-read. Numerically equal to [getStateMillis] but kept **separate**: it is a different operation with a different `operation` string, so a timeout here is diagnosable as "the re-read failed" rather than "the handshake failed", and later tuning can adjust the two independently. |
         * | [ConnectionTimeouts.presetDetailsMillis] | 3000 | The summary response is ~2 KB — many more transport chunks and reassembly steps than the small messages, so it gets more headroom. Still not upstream-derived. |
         * | [ConnectionTimeouts.presetParametersMillis] | 3000 | The snapshot-capture read (S9b). The request is byte-identical to the name harvest's and the response is the same ~2 KB summary, so the budget is numerically equal to [presetDetailsMillis] — but it is kept as a **separate field for the same reason [stateReadMillis] is separate from [getStateMillis]**: it is a different operation with a different `operation` string, so a timeout here is diagnosable as "the snapshot capture failed" (benign — revert is unavailable) rather than "the preset-name harvest failed" (fatal to `connect`), and the two can be tuned independently later. |
         * | [ConnectionTimeouts.masterVolumeMillis] | 2000 | Response is a single 10-byte `0x0309` payload. |
         * | [ConnectionTimeouts.transportWriteMillis] | 1000 | **Not a protocol delay at all.** [dev.tonexotg.protocol.TonexTransport.write]'s KDoc permits it to "suspend for as long as the underlying transport needs"; without a bound, one wedged write hangs `setParameter` forever. This bounds the seam, not the pedal. |
         *
         * ## What is deliberately *absent*
         *
         * **No inter-message delay constant.** Upstream's `vTaskDelay(pdMS_TO_TICKS(100))` after
         * open, `250`ms after line-state, `200`ms elsewhere, and the scattered `2`/`5`ms delays in
         * its RX loop are ESP32 task-watchdog and scheduling artifacts, not protocol requirements
         * — one of those delays' own upstream comment says exactly this ("make sure we don't trip
         * the task watchdog"). This connection state machine paces itself naturally: every request
         * awaits its response before the next is sent, so there is nothing for an artificial delay
         * to protect against here.
         *
         * **No inter-byte reassembly timeout.** Issue #13 raises this as "a real framing concern
         * that belongs in the design" — here is the design position: **do not add it.** Reasoning:
         * - [dev.tonexotg.protocol.framing.FrameReassembler] already has a *size* cap
         *   (`MAX_FRAME_CONTENT_BYTES`, 8 KB) but no time cap. The failure such a timer would guard
         *   against — a frame half-delivered, then silence — is already bounded, and bounded
         *   *better*, by the per-stage timeouts above: those name the operation in the resulting
         *   [TonexError.Timeout], which an anonymous framing-layer timer structurally cannot do.
         * - Every operation that can be waiting on a partial frame is already inside one of the
         *   per-stage timeouts above. There is no case an inter-byte timer would catch that the
         *   stage timeout does not.
         * - Adding one would make [dev.tonexotg.protocol.framing.FrameReassembler] — currently a
         *   pure, synchronous, fully-tested class with no coroutine or clock dependency —
         *   time-aware. That is real architectural cost for zero additional coverage.
         * - Memory is already bounded by the 8 KB cap, and `decodeFrames`' KDoc already documents
         *   that a dangling partial frame at stream end is dropped.
         */
        val DEFAULT = ConnectionTimeouts(
            helloMillis = 2_000L,
            getStateMillis = 2_000L,
            stateReadMillis = 2_000L,
            presetDetailsMillis = 3_000L,
            presetParametersMillis = 3_000L,
            masterVolumeMillis = 2_000L,
            transportWriteMillis = 1_000L,
        )
    }
}
