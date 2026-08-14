/*
 * Ported/adapted from: TonexOneController
 * Upstream repository: https://github.com/Builty/TonexOneController
 * Upstream file:        source/main/usb_tonex_one.c and usb_tonex_one.h
 * Upstream licence:     Apache-2.0 — Copyright 2025 Greg Smith
 * Full licence text:    LICENSE
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream project named above. See /CREDITS.md for full attribution.
 *
 * NOTE ON PROVENANCE: the message-type wire IDs in [MessageType] correspond to the
 * `TYPE_*` constants in the cited upstream header (the same file CREDITS.md already
 * attributes the preset-details `TYPE_STATE_PRESET_DETAILS*` handling to). The four-varint
 * `B9 03 <type> <size> <unknownA> <unknownB>` envelope shape was corrected from issue #9's
 * original (incorrect) three-varint description by testing a four-varint model against every
 * complete message literal in `usb_tonex_one.c` (lines 204, 224, 246, 347) — all five match,
 * including the size field now being an exact body-length match rather than an approximation.
 * See github.com/wiz0floyd/tonex-otg issue #9 for the corrected writeup. The varint encoding
 * itself (see [dev.tonexotg.protocol.codec.TonexVarint]) still traces to `vit3k/tonex_controller`'s
 * `protocol.md`.
 */
package dev.tonexotg.protocol.codec

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult

/**
 * A Tonex message's type, decoded from the header's `type` field.
 *
 * The catalogued wire IDs below are the ones issue #9 lists as verified. A pedal is free to send
 * a message type that hasn't been catalogued yet — firmware evolves, and this app must not treat
 * "we don't recognise this ID" as a reason to drop the connection (that would be a field failure
 * over a cosmetic gap in our own lookup table) — so any other ID decodes successfully as
 * [Unknown] rather than failing.
 */
sealed class MessageType {

    /** The numeric identifier as it appears on the wire, in the header's `type` varint. */
    abstract val wireId: Long

    /** Wire ID `0x02` — Hello response. */
    data object Hello : MessageType() {
        override val wireId: Long = 0x02L
    }

    /** Wire ID `0x0303` — full preset details (~30 KB). */
    data object FullPresetDetails : MessageType() {
        override val wireId: Long = 0x0303L
    }

    /** Wire ID `0x0304` — preset details summary (~2 KB). */
    data object PresetDetailsSummary : MessageType() {
        override val wireId: Long = 0x0304L
    }

    /** Wire ID `0x0306` — state update. */
    data object StateUpdate : MessageType() {
        override val wireId: Long = 0x0306L
    }

    /** Wire ID `0x0309` — a single parameter changed. */
    data object ParameterChanged : MessageType() {
        override val wireId: Long = 0x0309L
    }

    /**
     * Any wire ID not in the catalogued set above. Carries the raw [wireId] through unchanged so
     * a caller can still log it, or a future story can extend the catalogue without this codec
     * needing to change.
     */
    data class Unknown(override val wireId: Long) : MessageType()

    companion object {
        // `by lazy` (rather than an eagerly-initialized property) deliberately: building this
        // list eagerly in the companion object's own initializer raced the JVM class-init order
        // of the sibling `data object`s above (Hello, FullPresetDetails, ...) and silently
        // captured them as null - a real failure observed while writing this codec's tests, not
        // a hypothetical. Deferring construction to first actual call sidesteps that entirely.
        private val KNOWN: List<MessageType> by lazy(LazyThreadSafetyMode.NONE) {
            listOf(Hello, FullPresetDetails, PresetDetailsSummary, StateUpdate, ParameterChanged)
        }

        /** Maps a raw wire ID to its [MessageType], falling back to [Unknown]. Never fails. */
        fun fromWireId(wireId: Long): MessageType =
            KNOWN.firstOrNull { it.wireId == wireId } ?: Unknown(wireId)
    }
}

/**
 * The fixed four-field envelope every Tonex message opens with, immediately after the `B9 03`
 * prefix (see [MessageHeaderCodec]).
 *
 * @property type the message's [MessageType], decoded from the header's first varint.
 * @property declaredSize the header's second varint. This **is** an exact byte count: the number
 *   of bytes in [DecodedMessage.payload] always equals this value — [MessageHeaderCodec.decode]
 *   rejects the frame otherwise. (An earlier version of this codec treated `size` as merely a
 *   lower bound, based on a three-varint reading of the header that mis-attributed [unknownB] to
 *   the payload; that reading was confirmed wrong against upstream source and has been corrected.
 *   See [MessageHeaderCodec] for the full story and the five confirmed data points behind it.)
 * @property unknownA the header's third varint. Its meaning is **not documented upstream**.
 *   Observed value across every known message: `11`, with no variation seen yet.
 * @property unknownB the header's fourth varint. Its meaning is **not documented upstream**.
 *   Observed values: `1` for a hello request, `3` for every other message type seen so far —
 *   consistent with, but not confirmed as, a "is this the first message of the session" flag.
 *   Named positionally rather than guessing at that or any other meaning.
 */
data class MessageHeader(
    val type: MessageType,
    val declaredSize: Long,
    val unknownA: Long,
    val unknownB: Long,
)

/**
 * A fully decoded Tonex message: its [header] and the payload bytes that followed it, exactly
 * [MessageHeader.declaredSize] bytes long (see [MessageHeaderCodec.decode]'s size validation).
 * Interpreting the payload's internal structure — full/summary preset details, state update
 * fields, a changed parameter — is downstream work (S7, S8), not this codec's job.
 *
 * Overrides `equals`/`hashCode`/`toString` by hand (rather than being a `data class`) because
 * `payload` is a `ByteArray`, whose auto-generated `data class` semantics would compare by
 * reference, not content — exactly the kind of silent bug a round-trip test could pass despite
 * being wrong.
 */
class DecodedMessage(val header: MessageHeader, val payload: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is DecodedMessage && header == other.header && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()

    override fun toString(): String =
        "DecodedMessage(header=$header, payload=[${payload.joinToString(" ") { "%02X".format(it) }}])"
}

/**
 * Codec for the fixed header every Tonex message opens with.
 *
 * ## Wire format
 *
 * ```
 * B9 03 <type: varint> <size: varint> <unknownA: varint> <unknownB: varint> <payload...>
 * ```
 *
 * The two literal bytes `B9 03` are followed by **four** [TonexVarint]-encoded integers — `type`,
 * `size`, and two further fields whose meaning is not documented upstream (see
 * [MessageHeader.unknownA] / [MessageHeader.unknownB]) — and then exactly `size` bytes of payload.
 *
 * This is a correction to this codec's original three-varint reading (issue #9's initial writeup
 * of "type, size, and one opaque field"). Testing a four-varint model against every complete
 * message literal in upstream `usb_tonex_one.c` confirms it exactly, `size` included — it is a
 * precise body-length match once the fourth field is accounted for as part of the header rather
 * than folded into the payload:
 *
 * | Source | type | size | unknownA | unknownB | body length |
 * |---|---|---|---|---|---|
 * | `usb_tonex_one.c:204` (hello) | `0x0000` | `4` | `11` | `1` | `4` |
 * | `usb_tonex_one.c:224` (request state) | `0x0000` | `6` | `11` | `3` | `6` |
 * | `usb_tonex_one.c:246` (preset details) | `0x0300` | `6` | `11` | `3` | `6` |
 * | `usb_tonex_one.c:347` | `0x030D` | `5` | `11` | `3` | `5` |
 * | `usb_tonex_one.c:272` (header-only template) | `0x0309` | `10` | `11` | `3` | `0` (body appended at runtime) |
 *
 * The last row is a header upstream constructs and then appends a body to at runtime, not a
 * complete message — decoding it as-is (`size = 10`, zero bytes following) correctly fails this
 * codec's size validation below; that is expected, not a counterexample.
 *
 * ## Validation
 *
 * [decode] rejects:
 * - a buffer shorter than the minimum possible header (`B9 03` + 4 single-byte literal varints
 *   = 6 bytes) — [TonexError.MalformedFrame];
 * - a buffer whose first two bytes are not exactly `B9 03` — [TonexError.MalformedFrame];
 * - a `size` field that does not exactly equal the number of bytes remaining after the header —
 *   [TonexError.UnexpectedBlobShape]. `size` is an exact body length (see the table above), so
 *   both too few bytes (truncation) and too many (trailing garbage / a framing bug upstream)
 *   are rejected the same way.
 *
 * A truncated varint *within* the header (e.g. a `0x82` marker with no data bytes following) is
 * also rejected, as a [TonexError.MalformedFrame] surfaced from [TonexVarint.decode] — never a
 * raw `IndexOutOfBoundsException`.
 */
object MessageHeaderCodec {

    private const val PREFIX_0: Int = 0xB9
    private const val PREFIX_1: Int = 0x03

    /** `B9 03` + the smallest possible encoding (1 byte each) of the four header varints. */
    private const val MIN_FRAME_LENGTH = 6

    /**
     * Decodes a full message (header + payload) from [bytes]. See the class KDoc for the
     * validation rules applied. On success, [DecodedMessage.payload] is exactly
     * [MessageHeader.declaredSize] bytes long.
     */
    fun decode(bytes: ByteArray): TonexResult<DecodedMessage> {
        if (bytes.size < MIN_FRAME_LENGTH) {
            return TonexResult.Failure(
                TonexError.MalformedFrame(
                    "message header: frame is ${bytes.size} byte(s), need at least $MIN_FRAME_LENGTH",
                ),
            )
        }

        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 != PREFIX_0 || b1 != PREFIX_1) {
            return TonexResult.Failure(
                TonexError.MalformedFrame(
                    "message header: expected prefix B9 03, got %02X %02X".format(b0, b1),
                ),
            )
        }

        var cursor = 2

        val type = when (val result = decodeIntField(bytes, cursor, "type")) {
            is TonexResult.Failure -> return result
            is TonexResult.Success -> result.value
        }
        cursor += type.bytesConsumed

        val size = when (val result = decodeIntField(bytes, cursor, "size")) {
            is TonexResult.Failure -> return result
            is TonexResult.Success -> result.value
        }
        cursor += size.bytesConsumed

        val unknownA = when (val result = decodeIntField(bytes, cursor, "unknownA")) {
            is TonexResult.Failure -> return result
            is TonexResult.Success -> result.value
        }
        cursor += unknownA.bytesConsumed

        val unknownB = when (val result = decodeIntField(bytes, cursor, "unknownB")) {
            is TonexResult.Failure -> return result
            is TonexResult.Success -> result.value
        }
        cursor += unknownB.bytesConsumed

        val remaining = bytes.size - cursor
        if (size.value != remaining.toLong()) {
            return TonexResult.Failure(
                TonexError.UnexpectedBlobShape(expectedSize = size.value.toInt(), actualSize = remaining),
            )
        }
        val payload = bytes.copyOfRange(cursor, bytes.size)

        val header = MessageHeader(
            type = MessageType.fromWireId(type.value),
            declaredSize = size.value,
            unknownA = unknownA.value,
            unknownB = unknownB.value,
        )
        return TonexResult.Success(DecodedMessage(header, payload))
    }

    /**
     * Encodes [header] and [payload] back into the wire form [decode] parses.
     *
     * **Judgement call — per-field encoding width:** [TonexVarint.encodeInt] alone, applied
     * uniformly, would encode `unknownA = 11` as its minimal single literal byte — but neither
     * confirmed handshake fixture (issue #9) does that: both always encode `unknownA` via the
     * 1-byte `0x80` marker even though the value would fit a shorter literal form, while `size`
     * always uses the 2-byte `0x82` marker and `type`/`unknownB` both use plain minimal encoding
     * (both fixtures happen to have small `type`/`unknownB` values, so minimal for those two also
     * matches the observed marker-form fields — see [MessageHeader.unknownB] for the specific
     * values). To reproduce those fixtures byte-for-byte, this method encodes `size` and
     * `unknownA` using their observed *marker* forms unconditionally (falling back to the 2-byte
     * form only if a value exceeds what the 1-byte form of that marker can hold), and encodes
     * `type` and `unknownB` via [TonexVarint.encodeInt]'s ordinary minimal-form rule. This is
     * inferred from the confirmed fixtures, not a documented rule — flagged here, and in the
     * story report, rather than papered over. In particular: upstream source shows `type` using
     * the `0x81` 2-byte marker for at least one larger value (`0x0300`), which this encoder does
     * not reproduce (it always emits `0x82` for a 2-byte `type`, per the same "no documented rule,
     * pick the fixture-confirmed choice" reasoning used for the ambiguous `0x81`/`0x82` pair
     * generally) — no byte-exact fixture with a wide `type` value is available to verify this
     * against directly, only the decoded field values, so this is a known, called-out gap rather
     * than a silently-guessed one.
     */
    fun encode(header: MessageHeader, payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(2 + 8 + payload.size)
        out.add(PREFIX_0.toByte())
        out.add(PREFIX_1.toByte())
        out.addAll(TonexVarint.encodeInt(header.type.wireId).asIterable())
        out.addAll(encodeSizeField(header.declaredSize).asIterable())
        out.addAll(encodeUnknownAField(header.unknownA).asIterable())
        out.addAll(TonexVarint.encodeInt(header.unknownB).asIterable())
        out.addAll(payload.asIterable())
        return out.toByteArray()
    }

    private data class IntField(val value: Long, val bytesConsumed: Int)

    private fun decodeIntField(bytes: ByteArray, offset: Int, fieldName: String): TonexResult<IntField> {
        return when (val result = TonexVarint.decode(bytes, offset)) {
            is TonexResult.Failure -> result
            is TonexResult.Success -> when (val decoded = result.value.value) {
                is VarintValue.IntValue -> TonexResult.Success(IntField(decoded.value, result.value.bytesConsumed))
                is VarintValue.FloatValue -> TonexResult.Failure(
                    TonexError.MalformedFrame(
                        "message header: $fieldName decoded as float32 (${decoded.value}), expected an integer",
                    ),
                )
            }
        }
    }

    /** Always the 2-byte `0x82` marker form — see [encode] KDoc for why. */
    private fun encodeSizeField(value: Long): ByteArray {
        require(value in 0..TonexVarint.MAX_INT_VALUE) {
            "size $value out of representable range 0..${TonexVarint.MAX_INT_VALUE}"
        }
        return byteArrayOf(
            TonexVarint.MARKER_UINT16_B.toByte(),
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        )
    }

    /**
     * Always the `0x80` 1-byte marker form for `0..0xFF` — matching both confirmed fixtures —
     * falling back to the 2-byte marker form beyond that so an unusually large decoded value can
     * still round-trip instead of throwing. See [encode] KDoc for why this is not simply minimal.
     */
    private fun encodeUnknownAField(value: Long): ByteArray {
        require(value in 0..TonexVarint.MAX_INT_VALUE) {
            "unknownA $value out of representable range 0..${TonexVarint.MAX_INT_VALUE}"
        }
        return if (value <= 0xFF) {
            byteArrayOf(TonexVarint.MARKER_UINT8.toByte(), value.toByte())
        } else {
            byteArrayOf(
                TonexVarint.MARKER_UINT16_B.toByte(),
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
            )
        }
    }
}
