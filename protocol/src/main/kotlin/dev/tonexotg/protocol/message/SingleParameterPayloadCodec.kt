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
 *   inbound [dev.tonexotg.protocol.codec.MessageType.ParameterChanged] notification the pedal is
 *   **confirmed by hardware capture (#106) to send `0x02`** — [SingleParameterPayloadCodec.KIND_PARAMETER],
 *   not the `0x03` upstream's own `param_start_marker` (`usb_tonex_one.c:833`) searches for. This
 *   codec does not enforce any particular value here as a decode-time invariant: a future firmware
 *   sending something else is not this codec's business to reject.
 * @property index the wire-position parameter index this payload concerns, read from a single wire
 *   byte on both the read and the write side (see [SingleParameterPayloadCodec]'s "Index byte").
 *   `index == 0` means **master volume** specifically — upstream (`usb_tonex_one.c:856`,
 *   `if (param_index == 0x00)`) uses it that way, and master volume has no ordinary preset/global
 *   slot in this message's index space. Every other index lines up with
 *   [dev.tonexotg.protocol.ParameterId]'s own 0-108/110-116 numbering; #106 confirmed that
 *   directly for index `20` (`MODEL_GAIN`) by sweeping the pedal's physical knob and reading the
 *   resulting notifications.
 * @property value the parameter's new value, in whatever units the wire uses for this `kind`/`index`
 *   pair — for master volume specifically, this is the pedal's **native** `0..10` range, not the
 *   engineering `-40..3` dB range [dev.tonexotg.protocol.params.ParameterRegistry] stores; see
 *   [MasterVolumeMessage] for the conversion.
 */
data class SingleParameterPayload(val kind: Int, val index: Int, val value: Float)

/**
 * Codec for the wire shape shared by every "one parameter, one value" Tonex One message: the *safe*
 * single-parameter write ([ParameterWriteMessage]), the master-volume write
 * ([MasterVolumeMessage]), and inbound `TYPE_PARAM_CHANGED` notifications
 * ([dev.tonexotg.protocol.codec.MessageType.ParameterChanged]) all share this exact 10-byte payload
 * shape:
 *
 * ```
 * B9 04 <kind> 00 <index> 88 <value: float32, little-endian>
 * ```
 *
 * [encode] and [decode] are inverses of each other. Both place the index in `payload[4]` alone and
 * leave `payload[3]` as `0x00` padding.
 *
 * ## Index byte — `payload[4]` alone, confirmed by hardware (#106)
 *
 * Upstream's writer (`usb_tonex_one.c:265-295` `usb_tonex_one_send_single_parameter`, and
 * `:304-331` `usb_tonex_one_send_master_volume`) builds
 * `{0xB9, 0x04, kind, 0x00, 0x00, 0x88, 0x00, 0x00, 0x00, 0x00}` and executes exactly one
 * assignment into the index field: **`payload[4] = index;`**. `payload[3]` is never written.
 *
 * Upstream's *reader* (`usb_tonex_one_parse_param_changed`, `:827-877`) disagrees with its own
 * writer: it performs a 2-byte little-endian read, `param_index = *temp_ptr++;
 * param_index |= (*temp_ptr << 8);`. That read is dead code in upstream's own control flow —
 * `param_index`'s only use is `if (param_index == 0x00)` (`:856`), with no `else` branch, so every
 * nonzero index it computes is discarded unexamined.
 *
 * **Real hardware settles it in the writer's favour.** The #106 capture (a physical `MODEL_GAIN`
 * knob sweep, decoded by hand in issue #104) shows `payload[3..4] == 00 14`: the index, 20
 * (`MODEL_GAIN`), sits in `payload[4]`, and `payload[3]` is `0x00`. Upstream's 2-byte read would
 * have decoded that same frame as `0x1400` = 5120, which is not a valid
 * [dev.tonexotg.protocol.ParameterId] at all. [decode] therefore reads `payload[4]` alone, matching
 * [encode] and upstream's writer, and this codec round-trips its own output for every valid index.
 *
 * The same capture also settles the `kind` byte: inbound notifications carry `0x02`
 * ([KIND_PARAMETER]), **not** the `0x03` upstream's 3-byte `B9 04 03` marker search requires — so
 * upstream's own marker would never have matched a real inbound frame. This codec's 2-byte `B9 04`
 * scan (see [decode]) is what makes these notifications decodable at all.
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
     * "Index byte" section: `B9 04 <kind> 00 <index> 88 <value: float32, little-endian>` — i.e.
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
     * some future context. This is the exact inverse of [encode] — see the class KDoc's
     * "Index byte" section.
     *
     * ## Marker matching: 2 bytes, with retry — not upstream's 3-byte marker
     *
     * Upstream's own search is for the **3**-byte marker `B9 04 03`, i.e. `kind` fixed to `0x03`.
     * Real inbound notifications carry `kind = 0x02` (#106), so that marker would never match one;
     * matching only `B9 04 03` would also make [decode] unable to read back this app's own
     * [encode]d writes. It therefore matches the shorter 2-byte `B9 04` and
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
            // The index is `payload[kindPos + 2]` alone (`payload[4]` for a marker at offset 0) --
            // `payload[kindPos + 1]` is padding and is always 0x00. Confirmed against real hardware
            // in #106; see the class KDoc's "Index byte" section.
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
