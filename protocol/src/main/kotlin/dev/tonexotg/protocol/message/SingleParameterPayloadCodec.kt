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

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.codec.TonexVarint
import dev.tonexotg.protocol.codec.VarintValue

/**
 * One decoded "single parameter" payload: a `kind` byte, a wire-position `index`, and a `float32`
 * `value`. See [SingleParameterPayloadCodec] for the wire shape this decodes.
 *
 * @property kind [SingleParameterPayloadCodec.KIND_PARAMETER] or
 *   [SingleParameterPayloadCodec.KIND_MASTER_VOLUME] on messages this app itself constructs. On an
 *   inbound [dev.tonexotg.protocol.codec.MessageType.ParameterChanged] notification, hardware
 *   confirms (#106 raw `[knob-listen] READ` frames, hand-decoded for issue #104) `kind` is `0x02`
 *   (**not** `0x03` — upstream's own 3-byte `B9 04 03` marker search would never match a real
 *   inbound frame; that's exactly why this codec matches only the shorter 2-byte `B9 04` marker,
 *   see [decode]'s "Marker matching" section).
 * @property index the wire-position parameter index this payload concerns, read from a single byte
 *   at `payload[4]` (relative to the `B9 04` marker). On an inbound parameter-changed notification,
 *   upstream is confirmed (`usb_tonex_one.c:856`, `if (param_index == 0x00)`) to use `index == 0`
 *   specifically to mean master volume — master volume has no ordinary preset/global slot in this
 *   message's own index space. For a nonzero index, hardware confirms (#106: `payload[3..4] == 00
 *   14`, index 20 decoding as `MODEL_GAIN`) that it lines up directly with
 *   [dev.tonexotg.protocol.ParameterId]'s own 0-108/110-116 numbering. This field is live and read
 *   on every decode, not dead code.
 * @property value the parameter's new value, in whatever units the wire uses for this `kind`/`index`
 *   pair — for master volume specifically, this is the pedal's **native** `0..10` range, not the
 *   engineering `-40..3` dB range [dev.tonexotg.protocol.params.ParameterRegistry] stores; see
 *   [MasterVolumeMessage] for the conversion. For an ordinary parameter, hardware confirms (#106:
 *   `MODEL_GAIN` swept 1.9 → 4.8, inside its `0f..10f` range) the value is already in engineering
 *   units, decoded as-is.
 */
data class SingleParameterPayload(val kind: Int, val index: Int, val value: Float)

/**
 * Codec for the wire shape shared by every "one parameter, one value" Tonex One message: the *safe*
 * single-parameter write ([ParameterWriteMessage]), the master-volume write
 * ([MasterVolumeMessage]), and inbound `TYPE_PARAM_CHANGED` notifications
 * ([dev.tonexotg.protocol.codec.MessageType.ParameterChanged]) all share this exact 10-byte payload
 * shape — `B9 04 <kind> 00 <index> 88 <value: float32, little-endian>` — and, now confirmed against
 * real hardware (#106, hand-decoded for issue #104), [encode] and [decode] **are** inverses of each
 * other: both read/write a single index byte at `payload[4]`, with `payload[3]` always `0x00`
 * padding.
 *
 * ## Write side (used by [encode]) — `usb_tonex_one_send_single_parameter`/`_master_volume`
 *
 * Confirmed against `usb_tonex_one.c:265-295` (`usb_tonex_one_send_single_parameter`) and
 * `usb_tonex_one.c:304-331` (`usb_tonex_one_send_master_volume`) — both build
 * `{0xB9, 0x04, kind, 0x00, 0x00, 0x88, 0x00, 0x00, 0x00, 0x00}` and then execute exactly one
 * assignment into the index field: **`payload[4] = index;`**. `payload[3]` is never written and
 * keeps its initial `0x00`. Every valid parameter index (0-116, see [ParameterId]) fits in one
 * byte, so this single assignment is upstream's complete index-write logic.
 *
 * ## Read side (used by [decode])
 *
 * Hardware (#106) confirms the inbound frame carries `kind = 0x02` (this codec's
 * [KIND_PARAMETER]) — **not** `0x03` as upstream's `usb_tonex_one_parse_param_changed`
 * (`usb_tonex_one.c:827-877`) would have you expect from its own 3-byte `B9 04 03` marker search;
 * that search would never match a real inbound frame, which is exactly why this codec's [decode]
 * matches only the shorter 2-byte `B9 04` marker and reads `kind` as data (see [decode]'s "Marker
 * matching" section below). The index is read from a single byte at `payload[4]`; `payload[3]` is
 * confirmed padding, always `0x00` (#106: `payload[3..4] == 00 14`, index 20 decoding as
 * `MODEL_GAIN`). `index` is live and read on every decode, matching the write side byte-for-byte.
 *
 * The `0x88`-prefixed `float32` form ([TonexVarint.encodeFloat] / [TonexVarint.decode]) is reused
 * for the value field rather than reimplemented here.
 */
object SingleParameterPayloadCodec {

    /** `kind` byte for the *safe* single-parameter write ([ParameterWriteMessage]). */
    const val KIND_PARAMETER: Int = 0x02

    /** `kind` byte for the master-volume write ([MasterVolumeMessage]). */
    const val KIND_MASTER_VOLUME: Int = 0x03

    private const val MARKER_0: Int = 0xB9
    private const val MARKER_1: Int = 0x04

    /**
     * Encodes [kind]/[index]/[value] into the 10-byte wire shape described in the class KDoc's
     * "Write side" section: `B9 04 <kind> 00 <index> 88 <value: float32, little-endian>` — i.e.
     * `payload[4] = index`, byte-exact against `payload[4] = index;` in both
     * `usb_tonex_one_send_single_parameter` and `usb_tonex_one_send_master_volume`.
     */
    fun encode(kind: Int, index: Int, value: Float): ByteArray {
        require(kind in 0..0xFF) { "SingleParameterPayloadCodec.encode: kind $kind is not a byte (0..0xFF)" }
        require(index in 0..0xFF) {
            "SingleParameterPayloadCodec.encode: index $index does not fit the write side's confirmed " +
                "single-byte `payload[4] = index` encoding (0..0xFF) - see class KDoc"
        }
        return byteArrayOf(
            MARKER_0.toByte(),
            MARKER_1.toByte(),
            kind.toByte(),
            0x00,
            index.toByte(),
        ) + TonexVarint.encodeFloat(value)
    }

    /**
     * Decodes a [SingleParameterPayload] from [payload].
     *
     * Scans for the `B9 04` marker rather than assuming it starts at offset 0 — mirroring
     * upstream's own `memmem`-based search (`usb_tonex_one.c:836`) rather than a fixed-offset read,
     * since nothing in the reverse-engineered protocol rules out leading bytes before this shape in
     * some future context. This is the class KDoc's "Read side" byte layout, which is confirmed
     * (#106) to be the exact inverse of [encode]'s "Write side" layout — decoding a payload this app
     * just [encode]d recovers the same index.
     *
     * ## Marker matching: 2 bytes, with retry — not upstream's 3-byte marker
     *
     * Upstream's own search is for the **3**-byte marker `B9 04 03` (`kind` fixed to `0x03`) —
     * but hardware (#106) confirms real inbound notifications actually carry `kind = 0x02`, so
     * upstream's own marker search would never match a real frame. This codec's [decode] is shared
     * more broadly, including by this file's own tests decoding a `kind = 0x02` payload this app
     * just built with [encode] (see the class KDoc), so matching only `B9 04 03` would make [decode]
     * unable to read back its own writes at all. It therefore matches the shorter 2-byte `B9 04` and
     * reads `kind` as data rather than as part of the marker — but a *plain* first-match, no-retry
     * 2-byte scan has a real false-positive hazard: [PresetNameExtractor]'s own 6-byte
     * `ToneOnePresetByteMarker` (`B9 04 B9 02 BC 21`) itself begins with `B9 04`, and any other
     * incidental `B9 04` byte pair earlier in a buffer would wrongly claim to be *this* marker,
     * producing a spurious [TonexError.MalformedFrame] (or worse, a bogus decode) even though a
     * genuine, later occurrence of the real shape is present. To guard against that, [decode] does
     * not commit to the *first* `B9 04` it finds: it validates each candidate by checking for the
     * `0x88` float32 marker at the position this shape's fixed layout predicts, and if that
     * validation fails, retries at the *next* `B9 04` occurrence rather than failing outright. Only
     * once every candidate has been tried and none validates does [decode] report a failure — the
     * failure from the *last* candidate tried, so the reported reason reflects the most
     * marker-shaped near-miss rather than an arbitrary earlier one.
     *
     * Never throws: a missing marker, every candidate marker having too few trailing bytes, or no
     * candidate marker's trailing bytes validating as this shape, is reported as a
     * [TonexResult.Failure] rather than an `IndexOutOfBoundsException`.
     */
    fun decode(payload: ByteArray): TonexResult<SingleParameterPayload> {
        val candidates = markerCandidateIndices(payload)
        if (candidates.isEmpty()) {
            return TonexResult.Failure(
                TonexError.MalformedFrame("single-parameter payload: B9 04 marker not found"),
            )
        }

        var lastFailure: TonexResult.Failure = TonexResult.Failure(
            TonexError.MalformedFrame("single-parameter payload: B9 04 marker not found"),
        )

        for (markerIndex in candidates) {
            val kindPos = markerIndex + 2
            val floatMarkerPos = kindPos + 3
            if (floatMarkerPos >= payload.size) {
                lastFailure = TonexResult.Failure(
                    TonexError.UnexpectedBlobShape(
                        context = "single-parameter payload float marker (SingleParameterPayloadCodec)",
                        expectedSize = floatMarkerPos + 1,
                        actualSize = payload.size,
                    ),
                )
                continue
            }

            val kind = payload[kindPos].toInt() and 0xFF
            // payload[kindPos + 1] (payload[3] relative to the marker) is confirmed padding,
            // always 0x00 (#106). The index is a single byte at payload[kindPos + 2] (payload[4]).
            val index = payload[kindPos + 2].toInt() and 0xFF

            when (val result = TonexVarint.decode(payload, floatMarkerPos)) {
                is TonexResult.Failure -> lastFailure = result
                is TonexResult.Success -> when (val decoded = result.value.value) {
                    is VarintValue.FloatValue ->
                        return TonexResult.Success(SingleParameterPayload(kind, index, decoded.value))
                    is VarintValue.IntValue -> lastFailure = TonexResult.Failure(
                        TonexError.MalformedFrame(
                            "single-parameter payload: expected a float32 (0x88) marker at offset " +
                                "$floatMarkerPos, decoded an integer instead",
                        ),
                    )
                }
            }
        }

        return lastFailure
    }

    /** Every offset in [bytes] where the 2-byte `B9 04` marker occurs, in ascending order. */
    private fun markerCandidateIndices(bytes: ByteArray): List<Int> {
        if (bytes.size < 2) return emptyList()
        val indices = mutableListOf<Int>()
        for (i in 0..bytes.size - 2) {
            if ((bytes[i].toInt() and 0xFF) == MARKER_0 && (bytes[i + 1].toInt() and 0xFF) == MARKER_1) {
                indices.add(i)
            }
        }
        return indices
    }
}
