/*
 * Ported/adapted from: TonexOneController
 * Upstream repository: https://github.com/Builty/TonexOneController
 * Upstream file:        source/main/usb_tonex_one.c
 * Upstream licence:     Apache-2.0 — Copyright 2025 Greg Smith
 * Full licence text:    LICENSE
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream project named above. See /CREDITS.md for full attribution.
 */
package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.PresetSnapshot
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.codec.TonexVarint
import dev.tonexotg.protocol.codec.VarintValue

/**
 * Extracts the 109 preset-parameter floats from a preset-details response payload
 * ([TonexMessage.PresetDetails], `full = false` — [PresetDetailsKind.SUMMARY]; see below for why
 * `FULL` is never the right request here).
 *
 * ## Why `SUMMARY`, not `FULL` (S9b / issue #14 §0)
 *
 * A dispatch briefing for this design originally assumed the *full* 109-parameter response
 * ([PresetDetailsKind.FULL], wire type `0x0303`) was required. That was wrong, caught only by
 * hand-checking upstream per this project's review-rigor rule (CLAUDE.md): upstream's own
 * `usb_tonex_one_parse_preset_parameters` is called from inside `case TYPE_STATE_PRESET_DETAILS:`
 * (`usb_tonex_one.c:1232`) — the **summary** response (`0x0304`) — and `TYPE_STATE_PRESET_DETAILS_FULL`
 * (`0x0303`) is handled as a deliberate no-op in *two* separate places in upstream
 * (`usb_tonex_one.c:1112-1116` and `:1282-1286`, both literally "don't need to process this
 * anymore"/"ignore"). There is no upstream code anywhere that parses a `0x0303` payload, so this
 * extractor reads the same ~2 KB `SUMMARY` response [PresetNameExtractor] already reads — the
 * request is byte-identical to the existing preset-name harvest's. Do not build a caller that
 * requests `PresetDetailsKind.FULL` for this; it is unused, and correctly so.
 *
 * ## Wire format
 *
 * Ported from `usb_tonex_one.c:955-1005` (`usb_tonex_one_parse_preset_parameters`):
 *
 * ```c
 * uint8_t param_start_marker[] = {0xBA, 0x03, 0xBA, 0x6D};
 * // ... located via memmem over the response buffer, not a fixed offset ...
 * for (uint32_t loop = 0; loop < TONEX_PARAM_LAST; loop++) {
 *     if (*temp_ptr == 0x88) {
 *         temp_ptr++;
 *         memcpy((void*)&param_ptr[loop].Value, (void*)temp_ptr, sizeof(float));
 *         temp_ptr += sizeof(float);
 *     } else {
 *         // upstream logs a warning and breaks, leaving the remaining parameters at whatever
 *         // stale values were already in param_ptr - see "Why this extractor does not reproduce
 *         // upstream's break" below.
 *         break;
 *     }
 * }
 * ```
 *
 * - The block marker is `BA 03 BA 6D` (4 bytes), located by forward search over the payload —
 *   the same discipline [PresetNameExtractor] already uses for its own marker, not a fixed
 *   offset.
 * - Immediately after the marker: [PresetSnapshot.PARAMETER_COUNT] (109, `TONEX_PARAM_LAST`)
 *   repetitions of [BYTES_PER_PARAMETER] bytes each — a `0x88` float32 marker followed by a
 *   little-endian `float32`. 545 bytes total.
 * - **Ordering is positional**: the *n*-th float in the block (`param_ptr[loop]` for
 *   `loop = 0..108`) is the value of `ParameterId(n)` — the same "defined in the same order as
 *   they are sent by the Pedal" convention [dev.tonexotg.protocol.ParameterId]'s own KDoc states,
 *   and the indexing convention [extract]'s result must be read with.
 * - `0x88` + little-endian `float32` is not a new encoding: it is exactly
 *   [TonexVarint.MARKER_FLOAT32] / [VarintValue.FloatValue], already implemented and tested by
 *   [TonexVarint], and reused here rather than hand-rolled.
 *
 * ## One deliberate divergence from upstream, and why it is a fix, not a risk
 *
 * Upstream runs `memmem` over the raw, still-HDLC-framed, still-byte-stuffed buffer
 * (`usb_tonex_one.c:1232` passes the frame's raw `data`, not an already-unstuffed payload). This
 * object runs [extract] over the *decoded* payload
 * ([TonexMessage.PresetDetails.payload], post-unstuffing, post-header-strip) — exactly as
 * [PresetNameExtractor] already does. The marker bytes themselves (`BA 03 BA 6D`) contain neither
 * `0x7E` nor `0x7D`, so the marker is never escaped and both approaches find it, but *float*
 * payload bytes routinely do contain those bytes and are escaped on the wire. Upstream's raw-buffer
 * walk would silently misparse any float containing such a byte and then hit a non-`0x88` byte and
 * `break`; a decoded-payload walk cannot have that bug.
 *
 * ## Why this extractor does not reproduce upstream's `break`
 *
 * Upstream's `break` on an unexpected byte leaves the remaining `param_ptr[loop..108]` entries
 * holding whatever was already there and reports nothing to the caller — a partially-populated
 * table that is indistinguishable from a complete one. That is "patch-and-hope," which this
 * project's error model exists to avoid. [extract] instead fails the *entire* extraction with a
 * typed [TonexError] and never returns a partially-populated array — see [extract]'s KDoc.
 */
object PresetParameterExtractor {

    /**
     * `param_start_marker` — `usb_tonex_one.c:957`. Private for the same reason as
     * [PresetNameExtractor.MARKER]: a public `ByteArray val` is a shared mutable array; [marker]
     * hands out a fresh defensive copy instead so a caller can never corrupt extraction for every
     * other caller by mutating a shared instance.
     */
    private val MARKER: ByteArray = byteArrayOf(0xBA.toByte(), 0x03, 0xBA.toByte(), 0x6D)

    /** Bytes per parameter in the block: the `0x88` float32 marker plus its four float bytes. */
    const val BYTES_PER_PARAMETER: Int = 5

    /** A fresh defensive copy of `param_start_marker` — see [MARKER]'s KDoc for why. */
    fun marker(): ByteArray = MARKER.copyOf()

    /**
     * Extracts the [PresetSnapshot.PARAMETER_COUNT] (109) preset-parameter floats from
     * [payload]. Returns a `FloatArray` of exactly that many entries, positionally indexed:
     * `result[n]` is the value of `ParameterId(n)` — see the class KDoc's "Wire format" section.
     *
     * Never throws:
     * - if [MARKER] is not found anywhere in [payload], fails with [TonexError.MalformedFrame].
     * - if [MARKER] is found but fewer than `PARAMETER_COUNT * BYTES_PER_PARAMETER` (545) bytes
     *   remain after it, fails with [TonexError.UnexpectedBlobShape].
     * - if any parameter's leading byte is not the `0x88` float32 marker, or the varint decoded
     *   there does not consume exactly [BYTES_PER_PARAMETER] bytes, fails with
     *   [TonexError.MalformedFrame] naming the offending parameter index — this is the case
     *   upstream silently `break`s on (see class KDoc); this extractor fails the whole extraction
     *   instead.
     *
     * Structurally cannot return a partially-populated array: the result `FloatArray` is built
     * locally and only returned from the single success path at the end.
     */
    fun extract(payload: ByteArray): TonexResult<FloatArray> {
        val markerIndex = indexOfMarker(payload)
            ?: return TonexResult.Failure(
                TonexError.MalformedFrame("preset parameters: marker not found in payload"),
            )

        val blockStart = markerIndex + MARKER.size
        val needed = PresetSnapshot.PARAMETER_COUNT * BYTES_PER_PARAMETER
        if (blockStart + needed > payload.size) {
            return TonexResult.Failure(
                TonexError.UnexpectedBlobShape(
                    expectedSize = needed,
                    actualSize = (payload.size - blockStart).coerceAtLeast(0),
                ),
            )
        }

        val values = FloatArray(PresetSnapshot.PARAMETER_COUNT)
        for (i in 0 until PresetSnapshot.PARAMETER_COUNT) {
            val offset = blockStart + i * BYTES_PER_PARAMETER
            val decoded = when (val r = TonexVarint.decode(payload, offset)) {
                is TonexResult.Failure -> return r
                is TonexResult.Success -> r.value
            }
            val floatValue = decoded.value
            if (floatValue !is VarintValue.FloatValue || decoded.bytesConsumed != BYTES_PER_PARAMETER) {
                return TonexResult.Failure(
                    TonexError.MalformedFrame(
                        "preset parameters: expected a float32 (0x88) marker for parameter $i at " +
                            "offset $offset, got $decoded",
                    ),
                )
            }
            values[i] = floatValue.value
        }
        return TonexResult.Success(values)
    }

    private fun indexOfMarker(bytes: ByteArray): Int? {
        if (MARKER.size > bytes.size) return null
        outer@ for (i in 0..bytes.size - MARKER.size) {
            for (j in MARKER.indices) {
                if (bytes[i + j] != MARKER[j]) continue@outer
            }
            return i
        }
        return null
    }
}
