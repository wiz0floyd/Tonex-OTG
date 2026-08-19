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
 *   unconfirmed, not as fact. **For a nonzero value specifically, this field is decoded by a
 *   read path upstream's own control flow never actually exercises** — see
 *   [SingleParameterPayloadCodec]'s "Read side" and "S20 hardware probe" KDoc sections.
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
 * Sourced from `usb_tonex_one.c:827-877` — reads a 3-byte marker (`0xB9, 0x04, 0x03`; inbound
 * notifications are confirmed to always carry `kind = 0x03`), then explicitly 2 index bytes:
 * `param_index = *temp_ptr++; param_index |= (*temp_ptr << 8);`. The *first* byte encountered
 * after the marker (i.e. the payload position immediately after `kind`) is read as the **low**
 * byte; the *second* as the **high** byte — an ordinary little-endian 2-byte read, *as written*.
 *
 * **This 2-byte read is never exercised for a nonzero index on real hardware, as far as upstream's
 * own source shows.** `param_index`'s only use in that function is `if (param_index == 0x00) { ...
 * } ` (`:856`) — there is no `else` branch. Every nonzero `param_index` is computed and then
 * discarded; the value is correct-by-accident for index `0` (which decodes identically regardless
 * of which byte is "low"), but nothing in upstream's own control flow has ever depended on the
 * 2-byte read's behaviour for a nonzero index. Upstream's own literal-table comment for this
 * message (`:274-275`) also labels the byte at `payload[3]` alone as `unknown`, with `param index`
 * against `payload[4]` alone — i.e. its own author's column-aligned annotation disagrees with the
 * 2-byte read this function performs. And `0x0309` is one message ID used in *both* directions
 * (this app's own outbound writes and the pedal's inbound notifications), not two message IDs with
 * independently-confirmed, structurally-distinct layouts — so "read side" and "write side" here
 * describes which upstream function was ported, not a confirmed protocol-level distinction.
 *
 * ## Why [encode] and [decode] are not inverses of each other for a nonzero index
 *
 * Lining the two up by wire position: the write side's sole assignment lands on what the read
 * side's code would call the index's *high* byte, leaving what the read side's code would call the
 * *low* byte at its untouched `0x00`. Decoding a payload this app just encoded with a nonzero index
 * would therefore **not** recover that index under [decode] as currently written — it would recover
 * `index * 256`. [decode] is deliberately left matching upstream's `usb_tonex_one_parse_param_changed`
 * source text as-written, rather than "corrected" into a symmetric round trip, because the two
 * candidate fixes (trust the 2-byte read, or conclude `payload[3]` is unrelated padding and the
 * real index is `payload[4]` alone, matching the write side) are indistinguishable from the source
 * alone and this is not resolvable without a hardware capture — see "S20 hardware probe" below for
 * the exact steps that would settle it and the exact fix each outcome implies.
 *
 * ## S20 hardware probe — the fix this is waiting on
 *
 * Trigger a change to a **non**-master-volume parameter from the pedal's own front panel or the IK
 * editor (not from this app), capture the resulting inbound `0x0309` frame, and read `payload[2]`
 * (`kind`) first — upstream's own marker search is for the 3 bytes `B9 04 03`, so a notification
 * whose `kind` is `0x02` (this codec's [KIND_PARAMETER]) rather than `0x03` would not even match
 * upstream's marker; establish first whether the pedal emits nonzero-index notifications carrying
 * `kind = 0x03` at all. Then read `payload[3]` and `payload[4]`:
 * - If `payload[4] == index` and `payload[3] == 0x00` → [decode] is wrong; move the index read to
 *   `payload[4]` alone, matching the write side, and delete the 2-byte read.
 * - If `payload[3] == index` and `payload[4] == 0x00` → the asymmetry against [encode] is real and
 *   confirmed; [decode] is already correct as written, and this KDoc's uncertainty language should
 *   be upgraded to a confirmed fact.
 * - If no nonzero-index notification is ever observed on real hardware → `index` is unreachable
 *   dead code for the nonzero case (matching upstream's own dead `param_index` branch) and the
 *   field should be deleted from [SingleParameterPayload] entirely.
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
     * ## Marker matching: 2 bytes, with retry — not upstream's 3-byte marker
     *
     * Upstream's own search is for the **3**-byte marker `B9 04 03` (`kind` fixed to `0x03`) —
     * every inbound notification it decodes carries that `kind`. This codec's [decode] is shared
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
            val indexLo = payload[kindPos + 1].toInt() and 0xFF
            val indexHi = payload[kindPos + 2].toInt() and 0xFF
            val index = indexLo or (indexHi shl 8)

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
