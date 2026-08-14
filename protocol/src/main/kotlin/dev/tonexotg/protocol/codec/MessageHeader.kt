/*
 * Ported/adapted from: TonexOneController
 * Upstream repository: https://github.com/Builty/TonexOneController
 * Upstream file:        source/main/usb_tonex_one.h
 * Upstream licence:     Apache-2.0 — Copyright 2025 Greg Smith
 * Full licence text:    LICENSE
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream project named above. See /CREDITS.md for full attribution.
 *
 * NOTE ON PROVENANCE: the message-type wire IDs in [MessageType] correspond to the
 * `TYPE_*` constants in the cited upstream header (the same file CREDITS.md already
 * attributes the preset-details `TYPE_STATE_PRESET_DETAILS*` handling to). The outer
 * `B9 03 <type> <size> <opaque>` envelope shape and the varint encoding it is built on
 * (see [dev.tonexotg.protocol.codec.TonexVarint]) come from `vit3k/tonex_controller`'s
 * `protocol.md`, per github.com/wiz0floyd/tonex-otg issue #9.
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
 * The fixed three-field envelope every Tonex message opens with, immediately after the `B9 03`
 * prefix (see [MessageHeaderCodec]).
 *
 * @property type the message's [MessageType], decoded from the header's first varint.
 * @property declaredSize the header's second varint, as sent on the wire. Despite the name, this
 *   is **not** reliably equal to the number of payload bytes that follow — see
 *   [MessageHeaderCodec] for why this codec does not use it to slice the payload, only to sanity
 *   check that enough bytes are actually present.
 * @property opaqueField the header's third varint. Its meaning is **not documented upstream**;
 *   this codec deliberately does not name it as anything more specific than that, or assume what
 *   it is for — it is decoded and preserved verbatim so a caller that later works out its meaning
 *   (or the pedal firmware that requires a particular value) is not blocked by this codec having
 *   guessed wrong. See [MessageHeaderCodec.encode] for the one observation this codec does rely
 *   on about it (its wire width), clearly marked there as inferred, not documented.
 */
data class MessageHeader(
    val type: MessageType,
    val declaredSize: Long,
    val opaqueField: Long,
)

/**
 * A fully decoded Tonex message: its [header] and the raw bytes that followed it.
 *
 * [payload] is **not** truncated to [MessageHeader.declaredSize] — see [MessageHeaderCodec] for
 * why. It is every byte of the input buffer after the header, verbatim; interpreting it further
 * (full/summary preset details, state update fields, a changed parameter) is downstream work
 * (S7, S8), not this codec's job.
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
 * B9 03 <type: varint> <size: varint> <opaque: varint> <payload...>
 * ```
 *
 * The two literal bytes `B9 03` are followed by three [TonexVarint]-encoded integers — `type`,
 * `size`, and a third field whose meaning is not documented upstream (see
 * [MessageHeader.opaqueField]) — and then the rest of the message.
 *
 * ## Validation
 *
 * Per issue #9's stated upstream validation rules, [decode] rejects:
 * - a buffer shorter than the minimum possible header (`B9 03` + 3 single-byte literal varints
 *   = 5 bytes) — [TonexError.MalformedFrame];
 * - a buffer whose first two bytes are not exactly `B9 03` — [TonexError.MalformedFrame];
 * - a `size` field declaring more bytes than actually remain after the header —
 *   [TonexError.UnexpectedBlobShape].
 *
 * A truncated varint *within* the header (e.g. a `0x82` marker with no data bytes following) is
 * also rejected, as a [TonexError.MalformedFrame] surfaced from [TonexVarint.decode] — never a
 * raw `IndexOutOfBoundsException`.
 *
 * ## Why `size` is not used to slice the payload
 *
 * The two byte-exact handshake fixtures this codec is tested against (issue #9) both have **one
 * more byte remaining after the header than `size` declares** (e.g. the "Hello" fixture declares
 * `size = 4` but 5 bytes actually follow). Both fixtures are stated to be complete, verbatim
 * framed payloads, not truncated captures — so this is not a truncation bug in the fixtures, it
 * is a real property of the wire format that this codec does not have an explanation for. Rather
 * than guess a slicing rule that happens to fit two data points and risk silently dropping a real
 * trailing byte from every future message, [decode] exposes the **entire** remainder of the
 * buffer as [DecodedMessage.payload] and only uses `size` as a lower bound (enough bytes must be
 * present) for [TonexError.UnexpectedBlobShape] detection. Interpreting the payload's internal
 * structure — including whatever this extra byte turns out to be — is left to the message-body
 * codecs downstream (S7, S8).
 */
object MessageHeaderCodec {

    private const val PREFIX_0: Int = 0xB9
    private const val PREFIX_1: Int = 0x03

    /** `B9 03` + the smallest possible encoding (1 byte each) of `type`, `size`, and `opaque`. */
    private const val MIN_FRAME_LENGTH = 5

    /**
     * Decodes a full message (header + payload) from [bytes]. See the class KDoc for the
     * validation rules applied and why `size` does not bound [DecodedMessage.payload].
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

        val opaque = when (val result = decodeIntField(bytes, cursor, "opaque field")) {
            is TonexResult.Failure -> return result
            is TonexResult.Success -> result.value
        }
        cursor += opaque.bytesConsumed

        val payload = bytes.copyOfRange(cursor, bytes.size)
        if (size.value > payload.size) {
            return TonexResult.Failure(
                TonexError.UnexpectedBlobShape(expectedSize = size.value.toInt(), actualSize = payload.size),
            )
        }

        val header = MessageHeader(
            type = MessageType.fromWireId(type.value),
            declaredSize = size.value,
            opaqueField = opaque.value,
        )
        return TonexResult.Success(DecodedMessage(header, payload))
    }

    /**
     * Encodes [header] and [payload] back into the wire form [decode] parses.
     *
     * **Judgement call — per-field encoding width:** [TonexVarint.encodeInt] alone, applied
     * uniformly, would encode `type = 0` and `opaqueField = 11` as their minimal single literal
     * byte — but neither of the two confirmed handshake fixtures (issue #9) does that: both
     * always encode `size` via the 2-byte `0x82` marker and the opaque field via the 1-byte
     * `0x80` marker, even though both values would fit a shorter literal form. To reproduce those
     * fixtures byte-for-byte, this method encodes `size` and `opaqueField` using their observed
     * *marker* forms unconditionally (falling back to the 2-byte form only if a value exceeds
     * what the 1-byte form of that marker can hold), and only `type` via [TonexVarint.encodeInt]'s
     * ordinary minimal-form rule. This is inferred from exactly two data points, not a documented
     * rule — flagged here, and in the story report, rather than papered over.
     */
    fun encode(header: MessageHeader, payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(2 + 6 + payload.size)
        out.add(PREFIX_0.toByte())
        out.add(PREFIX_1.toByte())
        out.addAll(TonexVarint.encodeInt(header.type.wireId).asIterable())
        out.addAll(encodeSizeField(header.declaredSize).asIterable())
        out.addAll(encodeOpaqueField(header.opaqueField).asIterable())
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
    private fun encodeOpaqueField(value: Long): ByteArray {
        require(value in 0..TonexVarint.MAX_INT_VALUE) {
            "opaque field $value out of representable range 0..${TonexVarint.MAX_INT_VALUE}"
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
