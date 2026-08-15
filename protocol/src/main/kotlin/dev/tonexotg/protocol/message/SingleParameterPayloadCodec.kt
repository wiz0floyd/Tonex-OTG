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
 * ([dev.tonexotg.protocol.codec.MessageType.ParameterChanged]) all share this exact payload shape,
 * differing only in the `kind` byte and (for writes) which command they arrive wrapped in.
 *
 * ## Wire format
 *
 * ```
 * B9 04 <kind: 1 byte> <index: 2 bytes, little-endian> 88 <value: float32, little-endian>
 * ```
 *
 * 10 bytes total. Confirmed against two independent upstream sites:
 * - the write side, `usb_tonex_one.c:265-295` (`usb_tonex_one_send_single_parameter`) and
 *   `usb_tonex_one.c:304-331` (`usb_tonex_one_send_master_volume`) — both build
 *   `{0xB9, 0x04, kind, 0x00, 0x00, 0x88, 0x00, 0x00, 0x00, 0x00}` and patch the index and value
 *   bytes in place. The write side always uses a single-byte index (`payload[4] = index`,
 *   `payload[3]` — the index's high byte in this codec's 2-byte reading — is left at its initial
 *   `0x00`), which round-trips correctly through this codec's 2-byte little-endian read/write
 *   since every valid parameter index (0-116) fits in the low byte.
 * - the read side, `usb_tonex_one.c:827-871` (`usb_tonex_one_parse_param_changed`) — reads a 3-byte
 *   marker (`0xB9, 0x04, 0x03`), then explicitly 2 index bytes (`param_index = *temp_ptr++;
 *   param_index |= (*temp_ptr << 8);`), confirming the index field's width independently of the
 *   write side's single-byte usage of it.
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

    /** Encodes [kind]/[index]/[value] into the 10-byte wire shape described in the class KDoc. */
    fun encode(kind: Int, index: Int, value: Float): ByteArray {
        require(kind in 0..0xFF) { "SingleParameterPayloadCodec.encode: kind $kind is not a byte (0..0xFF)" }
        require(index in 0..0xFFFF) { "SingleParameterPayloadCodec.encode: index $index is not representable (0..0xFFFF)" }
        return byteArrayOf(
            MARKER_0.toByte(),
            MARKER_1.toByte(),
            kind.toByte(),
            (index and 0xFF).toByte(),
            ((index shr 8) and 0xFF).toByte(),
        ) + TonexVarint.encodeFloat(value)
    }

    /**
     * Decodes a [SingleParameterPayload] from [payload].
     *
     * Scans for the `B9 04` marker rather than assuming it starts at offset 0 — mirroring
     * upstream's own `memmem`-based search (`usb_tonex_one.c:836`) rather than a fixed-offset read,
     * since nothing in the reverse-engineered protocol rules out leading bytes before this shape in
     * some future context. For a payload this app itself just encoded via [encode], the marker is
     * always at offset 0 and this is equivalent to a direct read.
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
