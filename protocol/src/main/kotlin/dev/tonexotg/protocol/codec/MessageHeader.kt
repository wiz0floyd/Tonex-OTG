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
 * The envelope every Tonex message opens with, immediately after the `B9 03` prefix (see
 * [MessageHeaderCodec]). **Field count is direction-dependent** — see [MessageHeaderCodec]'s class
 * KDoc for the confirmed evidence: [encode] always writes all four fields (the real, confirmed
 * outbound shape); [decode] only ever reads three off the wire and always sets [unknownB] to
 * `null`, since a real inbound frame has no fourth header field at all.
 *
 * @property type the message's [MessageType], decoded from the header's first varint.
 * @property declaredSize the header's second varint. This **is** an exact byte count: the number
 *   of bytes in [DecodedMessage.payload] always equals this value — [MessageHeaderCodec.decode]
 *   rejects the frame otherwise.
 * @property unknownA the header's third varint — present in both directions. Its meaning is **not
 *   documented upstream**. Observed value across every known outbound message: `11`, with no
 *   variation seen yet; for a decoded inbound header this is the single field upstream's own
 *   `usb_tonex_one_parse` calls `header.unknown`.
 * @property unknownB the header's fourth varint, **outbound only**. `null` for any [MessageHeader]
 *   produced by [MessageHeaderCodec.decode] — there is no fourth field to decode. Non-null and
 *   required by [MessageHeaderCodec.encode], which always produces the 4-field outbound shape.
 *   Observed outbound values: `1` for a hello request, `3` for every other message type seen so
 *   far — consistent with, but not confirmed as, a "is this the first message of the session"
 *   flag. Named positionally rather than guessing at that or any other meaning.
 */
data class MessageHeader(
    val type: MessageType,
    val declaredSize: Long,
    val unknownA: Long,
    val unknownB: Long?,
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
 * ## Wire format is direction-dependent — [encode] emits 4 fields, [decode] reads 3
 *
 * ```
 * outbound (host -> pedal), [encode]:  B9 03 <type> <size> <unknownA> <unknownB> <payload...>
 * inbound  (pedal -> host), [decode]:  B9 03 <type> <size> <unknown>            <payload...>
 * ```
 *
 * This asymmetry is a **confirmed protocol fact**, not a guess, and this codec previously got it
 * wrong in both directions at different times — see "History" below before touching either side
 * of this again.
 *
 * **Outbound (`encode`) — 4 fields, confirmed against 5 literal request/message arrays in
 * upstream `usb_tonex_one.c`** (lines 204, 224, 246, 272, 347 — see [encode]'s own KDoc for the
 * full table). These are byte-for-byte literals from a real, working ESP32 host-controller
 * project, not derived or guessed.
 *
 * **Inbound (`decode`) — 3 fields, confirmed against `usb_tonex_one_parse()`**, the actual
 * function that same upstream project uses to parse bytes *received from* a real pedal
 * (`usb_tonex_one.c`, function starting near line 1014):
 * ```c
 * uint8_t index = 2;
 * uint16_t type = tonex_common_parse_value(FramedBuffer, &index);
 * ...
 * header.size = tonex_common_parse_value(FramedBuffer, &index);
 * header.unknown = tonex_common_parse_value(FramedBuffer, &index);   // ONE field, not two
 * ...
 * if ((out_len - index) != header.size) { ESP_LOGE(TAG, "Invalid message size"); ... }
 * ```
 * Three `parse_value` calls total (`type`, `size`, `unknown`) before the size check — never a
 * fourth. Also matches this same function's own `if (out_len < 5)` minimum-length guard: 2 prefix
 * bytes + 3 single-byte-minimum fields = 5, not 6.
 *
 * And now confirmed a third way, empirically: a real ToneX One's Hello-ack response, captured
 * byte-identical across two independent hardware connections (issue #25), decodes cleanly under
 * this 3-field model (`size=43` exactly matches 43 remaining bytes) and is "1 byte short" under
 * the old 4-field model applied to `decode`.
 *
 * ### History — why this needed re-litigating instead of trusting the last correction
 * Issue #9's original writeup described three fields (matching what's now confirmed correct for
 * inbound). Commit `03a1b3f` "corrected" this to four fields — but that correction tested its
 * model *only* against the five outbound literals above, never against `usb_tonex_one_parse`
 * itself, and predated any real hardware. It was a real fix for `encode` and an unverified,
 * silently-wrong extrapolation onto `decode`. `ConnectionTestFixtures` then built every simulated
 * inbound pedal response using that same wrong 4-field shape, so every `DefaultTonexController`
 * test round-tripped through the identical (wrong) model on both ends and passed 100% — the exact
 * "self-consistent round-trip hides a real wire-format bug" trap this project's own CLAUDE.md
 * already names from the S7 finding. Real hardware (issue #25) is what finally caught it.
 *
 * ## Validation
 *
 * [decode] rejects:
 * - a buffer shorter than the minimum possible header (`B9 03` + 3 single-byte literal varints
 *   = 5 bytes) — [TonexError.MalformedFrame];
 * - a buffer whose first two bytes are not exactly `B9 03` — [TonexError.MalformedFrame];
 * - a `size` field that does not exactly equal the number of bytes remaining after the header —
 *   [TonexError.UnexpectedBlobShape]. `size` is an exact body length, so both too few bytes
 *   (truncation) and too many (trailing garbage / a framing bug upstream) are rejected the same
 *   way.
 *
 * A truncated varint *within* the header (e.g. a `0x82` marker with no data bytes following) is
 * also rejected, as a [TonexError.MalformedFrame] surfaced from [TonexVarint.decode] — never a
 * raw `IndexOutOfBoundsException`.
 */
object MessageHeaderCodec {

    private const val PREFIX_0: Int = 0xB9
    private const val PREFIX_1: Int = 0x03

    /**
     * `B9 03` + the smallest possible encoding (1 byte each) of the three header varints
     * [decode] actually reads (`type`, `size`, `unknown`) — matches upstream's own
     * `usb_tonex_one_parse`'s `if (out_len < 5)` minimum-length guard exactly. See the class
     * KDoc's "inbound" section for why this is 3 fields, not 4.
     */
    private const val MIN_FRAME_LENGTH = 5

    /**
     * Decodes a full inbound message (header + payload) from [bytes]. See the class KDoc for the
     * validation rules applied and why this reads a 3-field header (`type`, `size`, `unknown`),
     * not the 4-field shape [encode] writes. On success, [DecodedMessage.payload] is exactly
     * [MessageHeader.declaredSize] bytes long, and [DecodedMessage.header]'s [MessageHeader.unknownB]
     * is always `null`.
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

        // Inbound frames carry exactly one further field after `size` (upstream's `header.unknown`)
        // -- NOT two. See class KDoc's "inbound" section for the confirmed evidence; a 4th field
        // here was this codec's own bug, silently masked by self-consistent round-trip tests until
        // real hardware (issue #25) caught it.
        val unknown = when (val result = decodeIntField(bytes, cursor, "unknown")) {
            is TonexResult.Failure -> return result
            is TonexResult.Success -> result.value
        }
        cursor += unknown.bytesConsumed

        val remaining = bytes.size - cursor
        if (size.value != remaining.toLong()) {
            return TonexResult.Failure(
                TonexError.UnexpectedBlobShape(
                    context = "message header body (MessageHeaderCodec.decode)",
                    expectedSize = size.value.toInt(),
                    actualSize = remaining,
                ),
            )
        }
        val payload = bytes.copyOfRange(cursor, bytes.size)

        val header = MessageHeader(
            type = MessageType.fromWireId(type.value),
            declaredSize = size.value,
            unknownA = unknown.value,
            unknownB = null,
        )
        return TonexResult.Success(DecodedMessage(header, payload))
    }

    /**
     * `B9 03` + the smallest possible encoding (1 byte each) of the four header varints
     * [decodeOutbound] reads (`type`, `size`, `unknownA`, `unknownB`).
     */
    private const val MIN_OUTBOUND_FRAME_LENGTH = 6

    /**
     * Decodes a frame this app itself produced via [encode] — the 4-field outbound shape — back
     * into a [DecodedMessage]. **Not for real inbound pedal responses**; use [decode] for those.
     *
     * Exists because [encode]'s output sometimes needs to be read back: test suites that capture
     * what [DefaultTonexController][dev.tonexotg.protocol.connection.DefaultTonexController]
     * actually wrote and assert on it (`FakeTonexTransport.writtenMessages()`), and diagnostic
     * tooling that wants to interpret captured outbound wire traffic (the S20 probe harness's
     * write-side logging) both need to parse the *outbound* shape specifically — [decode] would
     * misread it, for the same reason it would misread any 4-field frame.
     */
    fun decodeOutbound(bytes: ByteArray): TonexResult<DecodedMessage> {
        if (bytes.size < MIN_OUTBOUND_FRAME_LENGTH) {
            return TonexResult.Failure(
                TonexError.MalformedFrame(
                    "message header (outbound): frame is ${bytes.size} byte(s), need at least $MIN_OUTBOUND_FRAME_LENGTH",
                ),
            )
        }

        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 != PREFIX_0 || b1 != PREFIX_1) {
            return TonexResult.Failure(
                TonexError.MalformedFrame(
                    "message header (outbound): expected prefix B9 03, got %02X %02X".format(b0, b1),
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
                TonexError.UnexpectedBlobShape(
                    context = "message header body (MessageHeaderCodec.decodeOutbound)",
                    expectedSize = size.value.toInt(),
                    actualSize = remaining,
                ),
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
     * **Each header field has its own fixed encoding convention — this is *not* [TonexVarint]'s
     * general-purpose minimal-marker rule applied per field.** [TonexVarint.encodeInt]'s
     * "smallest marker that fits" policy is the right default for a generic varint (and is what
     * [encodeInt] itself still does, unchanged), but it does not describe how any of these four
     * header fields are actually encoded on the wire. That policy would, for instance, encode a
     * wide `type` value via the `0x82` marker — upstream never does that; see below.
     *
     * These four conventions are **observed from five reference literals in upstream
     * `usb_tonex_one.c`** (lines 204, 224, 246, 272, 347 — see [MessageHeaderCodec] class KDoc for
     * the full table), not derived from any specification, and each is confirmed consistent
     * across all five:
     *
     * | Field | Convention | Example |
     * |---|---|---|
     * | `type` | bare literal when `<= 0x7F`; otherwise the `0x81` 2-byte marker. **Never `0x82`.** No reference value falls in `0x80..0xFF`, so whether a `0x80` 1-byte form is ever used for `type` is unconfirmed — this method skips straight to the 2-byte `0x81` form above `0x7F`, which is an extrapolation beyond the confirmed data points, not itself observed. | `0x0300` → `81 00 03` |
     * | `size` | always the `0x82` 2-byte marker, even for tiny values that would fit a shorter form. | `4` → `82 04 00` |
     * | `unknownA` | always the `0x80` 1-byte marker — every reference value is `11`, which would fit a bare literal, so this is a genuine per-field convention, not a size optimisation. | `11` → `80 0b` |
     * | `unknownB` | always a bare literal. | `1` → `01`, `3` → `03` |
     *
     * **Whether the pedal actually requires these exact marker choices — as opposed to merely
     * tolerating any valid encoding of the same value — is unverified.** The reasoning for
     * reproducing them exactly anyway: this is the encoding the reference implementation
     * demonstrably ships against real hardware with, and that is the only evidence available:
     * matching it exactly cannot be wrong, where deviating from it might silently produce bytes a
     * production pedal firmware rejects. Confirming a real pedal accepts a differently-encoded
     * (but value-equal) frame is a hardware question for S20, not something this codec asserts.
     *
     * `unknownA` falls back to the 2-byte marker form beyond `0xFF` (unobserved, but needed so an
     * unusually large decoded value can still round-trip instead of throwing); `type` and `size`
     * are capped at [TonexVarint.MAX_INT_VALUE] by the same `require` [encodeInt] uses.
     */
    fun encode(header: MessageHeader, payload: ByteArray): ByteArray {
        val unknownB = checkNotNull(header.unknownB) {
            "MessageHeaderCodec.encode: header.unknownB is null. encode() always produces the " +
                "4-field outbound wire shape (see class KDoc), so unknownB is required here -- a " +
                "null header.unknownB means this header came from decode() (the 3-field inbound " +
                "shape) and should never be fed back into encode()."
        }
        val out = ArrayList<Byte>(2 + 8 + payload.size)
        out.add(PREFIX_0.toByte())
        out.add(PREFIX_1.toByte())
        out.addAll(encodeTypeField(header.type.wireId).asIterable())
        out.addAll(encodeSizeField(header.declaredSize).asIterable())
        out.addAll(encodeUnknownAField(header.unknownA).asIterable())
        out.addAll(TonexVarint.encodeInt(unknownB).asIterable())
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

    /**
     * Bare literal below `0x80`; otherwise the `0x81` 2-byte marker — **never** `0x82`. See
     * [encode] KDoc for the reference literals this convention is observed from.
     */
    private fun encodeTypeField(value: Long): ByteArray {
        require(value in 0..TonexVarint.MAX_INT_VALUE) {
            "type $value out of representable range 0..${TonexVarint.MAX_INT_VALUE}"
        }
        return if (value <= 0x7F) {
            byteArrayOf(value.toByte())
        } else {
            byteArrayOf(
                TonexVarint.MARKER_UINT16_A.toByte(),
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
            )
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
