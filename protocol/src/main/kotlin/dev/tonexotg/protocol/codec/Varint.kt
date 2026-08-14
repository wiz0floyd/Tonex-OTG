/*
 * Ported/adapted from: tonex_controller
 * Upstream repository: https://github.com/vit3k/tonex_controller
 * Upstream file:        protocol.md
 * Upstream licence:     MIT — Copyright (c) 2024 vit3k
 * Full licence text:    LICENSES/MIT-vit3k.txt
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream project named above. See /CREDITS.md for full attribution.
 *
 * NOTE ON PROVENANCE: `protocol.md` is a documentation file, not upstream source code — the
 * varint scheme it describes is "single-sourced, not confirmed in code" per
 * github.com/wiz0floyd/tonex-otg issue #9, with the sole exception of the `0x88` float32 form,
 * which the issue states *is* confirmed via the upstream single-parameter write path. See the
 * per-declaration KDoc below for which parts carry which confidence level.
 */
package dev.tonexotg.protocol.codec

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult

/**
 * The decoded value of one Tonex wire varint: either a plain integer or — when the lead byte was
 * the `0x88` marker — an IEEE-754 `float32`.
 *
 * These are two different wire *shapes*, not just two ranges of the same number, so callers that
 * expect an integer field (e.g. the three fields of the message header, see [MessageHeaderCodec])
 * must explicitly reject a [FloatValue] rather than silently coercing it — a coercion here is
 * exactly the kind of silent-corruption bug this project's error model (see `TonexError` KDoc)
 * exists to prevent.
 */
sealed class VarintValue {
    /** An integer value, always non-negative (`0..65535` — the largest value any wire form can carry). */
    data class IntValue(val value: Long) : VarintValue()

    /** A `float32` value decoded from a `0x88`-prefixed varint. */
    data class FloatValue(val value: Float) : VarintValue()
}

/** One decoded varint, together with how many bytes of the source buffer it occupied. */
data class DecodedVarint(val value: VarintValue, val bytesConsumed: Int)

/**
 * Codec for the Tonex wire protocol's variable-length integer / float encoding.
 *
 * ## Wire format
 *
 * A varint is one lead byte followed by zero or more data bytes, decoded as follows (from
 * issue #9, itself sourced from the upstream `vit3k/tonex_controller` project):
 *
 * | Lead byte | Meaning | Total bytes |
 * |---|---|---|
 * | `0x80` | the value is the next **1** byte | 2 |
 * | `0x81` or `0x82` | the value is the next **2** bytes, **little-endian** | 3 |
 * | `0x88` | the next **4** bytes are an IEEE-754 `float32`, **little-endian** | 5 |
 * | anything else | the lead byte itself **is** the value | 1 |
 *
 * The `0x80`/`0x81`/`0x82` integer forms and the "anything else is a literal" fallback are the
 * core rule verified against the upstream reverse-engineering effort. The `0x88` float form is
 * separately confirmed in upstream code (not just documentation) per issue #9 and is treated
 * with the same confidence as the integer forms here.
 *
 * Decoding deliberately accepts **either** `0x81` or `0x82` as the 2-byte marker — both are
 * documented as meaning the same thing (next 2 bytes, little-endian) and nothing in the source
 * material distinguishes their semantics. See [encodeInt] for the corresponding, and much more
 * consequential, *encoding*-side judgement call.
 *
 * ## Little-endian, explicitly
 *
 * Both the 2-byte integer form and the 4-byte float form are little-endian. This is called out
 * explicitly (and pinned down with dedicated tests) because a byte-swap here is silent — it
 * produces a plausible-looking but wrong number rather than a crash — and is exactly the kind of
 * bug issue #9 warns is "awful to find" after the fact.
 */
object TonexVarint {

    /** Lead byte meaning "the value is the next 1 byte." */
    const val MARKER_UINT8: Int = 0x80

    /** One of two lead bytes meaning "the value is the next 2 bytes, little-endian." */
    const val MARKER_UINT16_A: Int = 0x81

    /** The other lead byte meaning "the value is the next 2 bytes, little-endian." */
    const val MARKER_UINT16_B: Int = 0x82

    /** Lead byte meaning "the next 4 bytes are a little-endian IEEE-754 float32." */
    const val MARKER_FLOAT32: Int = 0x88

    /** The largest integer value any wire form of this varint can carry. */
    const val MAX_INT_VALUE: Long = 0xFFFF

    /**
     * Decodes one varint starting at [offset] in [bytes].
     *
     * Never throws: a marker that demands more bytes than remain in the buffer, or an [offset]
     * outside the buffer, is reported as a [TonexError.MalformedFrame] failure rather than an
     * `IndexOutOfBoundsException` — truncated input is an expected, recoverable condition on a
     * live USB link, not a programming error.
     */
    fun decode(bytes: ByteArray, offset: Int = 0): TonexResult<DecodedVarint> {
        if (offset < 0 || offset >= bytes.size) {
            return TonexResult.Failure(
                TonexError.MalformedFrame(
                    "varint: offset $offset is out of bounds for a ${bytes.size}-byte buffer",
                ),
            )
        }

        val marker = bytes[offset].toInt() and 0xFF
        return when (marker) {
            MARKER_UINT8 -> decodeFixed(bytes, offset, dataBytes = 1) { data ->
                VarintValue.IntValue((data[0].toInt() and 0xFF).toLong())
            }

            MARKER_UINT16_A, MARKER_UINT16_B -> decodeFixed(bytes, offset, dataBytes = 2) { data ->
                val lo = data[0].toInt() and 0xFF
                val hi = data[1].toInt() and 0xFF
                VarintValue.IntValue((lo or (hi shl 8)).toLong())
            }

            MARKER_FLOAT32 -> decodeFixed(bytes, offset, dataBytes = 4) { data ->
                var bits = 0
                for (i in 0..3) {
                    bits = bits or ((data[i].toInt() and 0xFF) shl (8 * i))
                }
                VarintValue.FloatValue(Float.fromBits(bits))
            }

            else -> TonexResult.Success(DecodedVarint(VarintValue.IntValue(marker.toLong()), bytesConsumed = 1))
        }
    }

    private inline fun decodeFixed(
        bytes: ByteArray,
        offset: Int,
        dataBytes: Int,
        build: (ByteArray) -> VarintValue,
    ): TonexResult<DecodedVarint> {
        val need = dataBytes + 1
        val have = bytes.size - offset
        if (have < need) {
            val marker = "0x%02X".format(bytes[offset])
            return TonexResult.Failure(
                TonexError.MalformedFrame(
                    "varint: $marker marker needs $need bytes but only $have remain in the buffer",
                ),
            )
        }
        val data = bytes.copyOfRange(offset + 1, offset + 1 + dataBytes)
        return TonexResult.Success(DecodedVarint(build(data), bytesConsumed = need))
    }

    /**
     * Encodes [value] as an integer varint, choosing the **minimal correct marker** for its
     * magnitude:
     *
     * - `0..0x7F` → the literal single-byte form (never collides with a marker: every marker
     *   byte is `>= 0x80`).
     * - `0x80..0xFF` → the `0x80` marker plus 1 data byte.
     * - `0x100..0xFFFF` → a 2-byte-marker plus 2 little-endian data bytes.
     *
     * **Judgement call — which of `0x81`/`0x82` to emit:** the source material documents both
     * lead bytes as meaning the same thing but does not say which a real encoder should prefer.
     * The two confirmed byte-exact handshake fixtures (issue #9) both use `0x82` for their
     * 2-byte fields and never use `0x81`, so this method always emits `0x82`. [decode] still
     * accepts either on the way in. If a future capture shows `0x81` being emitted by the pedal
     * or the app in some other context, this choice should be revisited — it is inferred from
     * two data points, not a documented rule.
     *
     * @throws IllegalArgumentException if [value] is negative or exceeds [MAX_INT_VALUE] — no
     *   wire form can represent it. This is a programmer error (an invariant the caller failed to
     *   uphold before calling), not a protocol-level failure, so it is signalled by throwing
     *   rather than by [TonexResult], matching the `require()` convention used elsewhere in this
     *   module for constructor-time invariants (see e.g. `PresetIndex`).
     */
    fun encodeInt(value: Long): ByteArray {
        require(value in 0..MAX_INT_VALUE) {
            "TonexVarint.encodeInt: $value is not representable (must be in 0..$MAX_INT_VALUE)"
        }
        return when {
            value <= 0x7F -> byteArrayOf(value.toByte())
            value <= 0xFF -> byteArrayOf(MARKER_UINT8.toByte(), value.toByte())
            else -> byteArrayOf(
                MARKER_UINT16_B.toByte(),
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
            )
        }
    }

    /**
     * Encodes [value] as a `0x88`-prefixed little-endian `float32` varint. Unlike [encodeInt]
     * there is no marker choice to make — this is the only wire form for a float, so this method
     * is unconditionally correct (no judgement call involved).
     */
    fun encodeFloat(value: Float): ByteArray {
        val bits = value.toRawBits()
        return byteArrayOf(
            MARKER_FLOAT32.toByte(),
            (bits and 0xFF).toByte(),
            ((bits shr 8) and 0xFF).toByte(),
            ((bits shr 16) and 0xFF).toByte(),
            ((bits shr 24) and 0xFF).toByte(),
        )
    }
}
