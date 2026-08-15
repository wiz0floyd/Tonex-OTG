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
 *   [SingleParameterPayloadCodec.KIND_MASTER_VOLUME] on messages this app itself constructs; on an
 *   inbound [dev.tonexotg.protocol.codec.MessageType.ParameterChanged] notification upstream is
 *   observed to always send `0x03` here (see `usb_tonex_one.c:833`'s `param_start_marker`),
 *   regardless of which parameter changed — this codec does not enforce that as a decode-time
 *   invariant, since a future firmware sending a different value here is not this codec's business
 *   to reject.
 * @property index the wire-position parameter index this payload concerns. On an inbound
 *   parameter-changed notification, upstream is confirmed (`usb_tonex_one.c:856`,
 *   `if (param_index == 0x00)`) to use `index == 0` specifically to mean master volume — master
 *   volume has no ordinary preset/global slot in this message's own index space, unlike a regular
 *   parameter, whose `index` here is not independently confirmed by the upstream source examined
 *   for this story to line up with [dev.tonexotg.protocol.ParameterId]'s own 0-108/110-116
 *   numbering beyond that one master-volume case. Treat that wider correspondence as plausible but
 *   unconfirmed, not as fact.
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
 * *shape* — `B9 04 <kind> <2 index-field bytes> 88 <value: float32, little-endian>` — but, as detailed
 * below, [encode] (the write side) and [decode] (the read side) place the index within those 2 bytes
 * **differently**, because they are ported from two independently-sourced upstream functions that
 * are not, in fact, byte-for-byte consistent with each other. This is not a bug in this codec; it is
 * this codec faithfully reproducing an asymmetry that exists in the upstream reference itself.
 *
 * ## Write side (used by [encode]) — `usb_tonex_one_send_single_parameter`/`_master_volume`
 *
 * Confirmed against `usb_tonex_one.c:265-295` (`usb_tonex_one_send_single_parameter`) and
 * `usb_tonex_one.c:304-331` (`usb_tonex_one_send_master_volume`) — both build
 * `{0xB9, 0x04, kind, 0x00, 0x00, 0x88, 0x00, 0x00, 0x00, 0x00}` and then execute exactly one
 * assignment into the two index-field bytes: **`payload[4] = index;`**. `payload[3]` is never
 * written and keeps its initial `0x00`. Every valid parameter index (0-116, see [ParameterId])
 * fits in one byte, so this single assignment is upstream's complete index-write logic — there is
 * no confirmed write-side behaviour for an index that would need the second byte.
 *
 * ## Read side (used by [decode]) — `usb_tonex_one_parse_param_changed`
 *
 * Confirmed independently against `usb_tonex_one.c:827-871` — reads a 3-byte marker
 * (`0xB9, 0x04, 0x03`; inbound notifications are confirmed to always carry `kind = 0x03`), then
 * explicitly 2 index bytes: `param_index = *temp_ptr++; param_index |= (*temp_ptr << 8);`. The
 * *first* byte encountered after the marker (i.e. the payload position immediately after `kind`)
 * is the **low** byte; the *second* is the **high** byte — an ordinary little-endian 2-byte read.
 *
 * ## Why [encode] and [decode] are not inverses of each other for a nonzero index
 *
 * Lining the two up by wire position: the write side's sole assignment lands on what the read
 * side would call the index's *high* byte, leaving what the read side would call the *low* byte
 * at its untouched `0x00`. Decoding a payload this app just encoded with a nonzero index would
 * therefore **not** recover that index — it would recover `index * 256`. That is a real property
 * of the two independently-confirmed upstream sources, not a round-trip this codec claims to
 * support: [encode] and [decode] exist to reproduce two different upstream message flows (an
 * outbound command this app constructs vs. an inbound notification the pedal constructs), and
 * nothing in the reverse-engineered protocol says those two flows must agree on this field's byte
 * order. Do not "fix" this into a symmetric round trip without new upstream evidence that the two
 * sides actually agree — see [ParameterWriteMessage] KDoc for the acceptance-fixture byte template
 * this asymmetry was caught against.
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
     * some future context. **This is the class KDoc's "Read side" byte layout, not the inverse of
     * [encode]'s "Write side" layout** — decoding a payload this app just [encode]d does *not*
     * recover the same index for a nonzero value; see the class KDoc's "Why encode and decode are
     * not inverses" section.
     *
     * Never throws: a missing marker, or a marker with too few trailing bytes for the fixed shape,
     * is reported as a [TonexResult.Failure] rather than an `IndexOutOfBoundsException`.
     */
    fun decode(payload: ByteArray): TonexResult<SingleParameterPayload> {
        val markerIndex = indexOfMarker(payload)
            ?: return TonexResult.Failure(
                TonexError.MalformedFrame("single-parameter payload: B9 04 marker not found"),
            )

        val kindPos = markerIndex + 2
        val floatMarkerPos = kindPos + 3
        if (floatMarkerPos >= payload.size) {
            return TonexResult.Failure(
                TonexError.UnexpectedBlobShape(
                    expectedSize = floatMarkerPos + 1,
                    actualSize = payload.size,
                ),
            )
        }

        val kind = payload[kindPos].toInt() and 0xFF
        val indexLo = payload[kindPos + 1].toInt() and 0xFF
        val indexHi = payload[kindPos + 2].toInt() and 0xFF
        val index = indexLo or (indexHi shl 8)

        return when (val result = TonexVarint.decode(payload, floatMarkerPos)) {
            is TonexResult.Failure -> result
            is TonexResult.Success -> when (val decoded = result.value.value) {
                is VarintValue.FloatValue -> TonexResult.Success(SingleParameterPayload(kind, index, decoded.value))
                is VarintValue.IntValue -> TonexResult.Failure(
                    TonexError.MalformedFrame(
                        "single-parameter payload: expected a float32 (0x88) marker at offset $floatMarkerPos, " +
                            "decoded an integer instead",
                    ),
                )
            }
        }
    }

    private fun indexOfMarker(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        for (i in 0..bytes.size - 2) {
            if ((bytes[i].toInt() and 0xFF) == MARKER_0 && (bytes[i + 1].toInt() and 0xFF) == MARKER_1) {
                return i
            }
        }
        return null
    }
}
