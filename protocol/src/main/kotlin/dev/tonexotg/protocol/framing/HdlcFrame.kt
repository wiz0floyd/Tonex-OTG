/*
 * Ported/adapted from: TonexOneController and tonex_controller
 * (equivalent logic appears independently in both upstream projects)
 *
 * - TonexOneController
 *   Repository:      https://github.com/Builty/TonexOneController
 *   Upstream file:    source/main/usb_tonex_common.c
 *   Licence:          Apache-2.0 — Copyright 2025 Greg Smith
 *   Full licence text: LICENSE
 *
 * - tonex_controller
 *   Repository:       https://github.com/vit3k/tonex_controller
 *   Upstream file:    main/hdlc.cpp
 *   Licence:          MIT — Copyright (c) 2024 vit3k
 *   Full licence text: LICENSES/MIT-vit3k.txt
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream projects named above. See /CREDITS.md for full attribution.
 *
 * Note: unlike upstream, [HdlcFrame.decode] (and [FrameReassembler] alongside it) scans for the
 * closing delimiter in an escape-aware way — see [FrameReassembler]'s KDoc for why upstream's
 * shortcut was not ported.
 */
package dev.tonexotg.protocol.framing

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import java.io.ByteArrayOutputStream

/** The `0x7E` byte that opens and closes every frame. */
internal const val FRAME_FLAG: Byte = 0x7E.toByte()

/** The `0x7D` byte that marks the following byte as escaped. */
internal const val FRAME_ESCAPE: Byte = 0x7D.toByte()

/** XOR mask applied to an escaped byte's real value to obtain (or recover) its stuffed form. */
internal const val FRAME_ESCAPE_XOR: Byte = 0x20.toByte()

/**
 * Encodes and decodes single, already-delimited HDLC-style frames.
 *
 * See the `dev.tonexotg.protocol.framing` package documentation for the full wire format. This
 * object operates on one complete frame at a time (a [ByteArray] starting and ending with the
 * `0x7E` delimiter, with nothing before or after it). For reassembling frames out of a live,
 * arbitrarily-chunked byte stream — where a frame may be split across reads or several frames may
 * arrive in one read — use [FrameReassembler] instead.
 */
object HdlcFrame {

    /**
     * Encodes [payload] into a complete wire frame: leading delimiter, byte-stuffed payload,
     * byte-stuffed little-endian CRC-16/X-25 of the *unstuffed* payload, trailing delimiter.
     *
     * @param payload the raw, unframed bytes to send. May contain any byte value, including
     *   `0x7E` and `0x7D` — those are byte-stuffed automatically.
     * @return the complete framed byte sequence, ready to hand to
     *   [dev.tonexotg.protocol.TonexTransport.write].
     */
    fun encode(payload: ByteArray): ByteArray {
        val crc = Crc16X25.compute(payload)
        val crcBytes = byteArrayOf((crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte())

        val out = ByteArrayOutputStream(payload.size + crcBytes.size + 4)
        out.write(FRAME_FLAG.toInt())
        stuffInto(out, payload)
        stuffInto(out, crcBytes)
        out.write(FRAME_FLAG.toInt())
        return out.toByteArray()
    }

    /**
     * Decodes a single complete frame — [frame] must start and end with the `0x7E` delimiter,
     * with no leading or trailing bytes outside those delimiters and no bare (unescaped) `0x7E`
     * in between.
     *
     * This never throws. Malformed input (missing/misplaced delimiters, a dangling escape byte,
     * a frame too short to hold a CRC) produces [TonexError.MalformedFrame]; a well-formed frame
     * whose CRC does not match its payload produces [TonexError.CrcMismatch] — the payload is
     * never returned in that case, per S4's requirement that corruption never be silently
     * accepted.
     *
     * @param frame exactly one delimited frame, e.g. one element of what [FrameReassembler]
     *   would emit as a raw frame, or a hand-built test fixture.
     */
    fun decode(frame: ByteArray): TonexResult<ByteArray> {
        if (frame.size < 2) {
            return TonexResult.Failure(
                TonexError.MalformedFrame(
                    "frame too short: ${frame.size} byte(s), need at least the two delimiters"
                )
            )
        }
        if (frame[0] != FRAME_FLAG) {
            return TonexResult.Failure(TonexError.MalformedFrame("frame does not start with 0x7E delimiter"))
        }
        if (frame[frame.size - 1] != FRAME_FLAG) {
            return TonexResult.Failure(TonexError.MalformedFrame("frame does not end with 0x7E delimiter"))
        }

        val unstuffed = ByteArrayOutputStream(frame.size)
        var i = 1
        val bodyEnd = frame.size - 1
        while (i < bodyEnd) {
            val b = frame[i]
            when (b) {
                FRAME_ESCAPE -> {
                    if (i + 1 >= bodyEnd) {
                        return TonexResult.Failure(
                            TonexError.MalformedFrame("dangling escape byte (0x7D) at end of frame")
                        )
                    }
                    val escaped = frame[i + 1]
                    unstuffed.write((escaped.toInt() xor FRAME_ESCAPE_XOR.toInt()) and 0xFF)
                    i += 2
                }
                FRAME_FLAG -> {
                    return TonexResult.Failure(
                        TonexError.MalformedFrame("unescaped 0x7E delimiter found inside frame body")
                    )
                }
                else -> {
                    unstuffed.write(b.toInt() and 0xFF)
                    i += 1
                }
            }
        }

        return finishDecoding(unstuffed.toByteArray())
    }

    /** Byte-stuffs [bytes] (escaping `0x7E`/`0x7D`) and appends the result to [out]. */
    private fun stuffInto(out: ByteArrayOutputStream, bytes: ByteArray) {
        for (b in bytes) {
            if (b == FRAME_FLAG || b == FRAME_ESCAPE) {
                out.write(FRAME_ESCAPE.toInt())
                out.write((b.toInt() xor FRAME_ESCAPE_XOR.toInt()) and 0xFF)
            } else {
                out.write(b.toInt() and 0xFF)
            }
        }
    }
}

/**
 * Splits already-unstuffed frame content into payload + CRC, and verifies the CRC.
 *
 * Shared by [HdlcFrame.decode] and [FrameReassembler], both of which independently unstuff their
 * input before calling this.
 */
internal fun finishDecoding(content: ByteArray): TonexResult<ByteArray> {
    if (content.size < 2) {
        return TonexResult.Failure(
            TonexError.MalformedFrame(
                "frame too short to contain a CRC: ${content.size} content byte(s) after unstuffing"
            )
        )
    }
    val payload = content.copyOfRange(0, content.size - 2)
    val crcLo = content[content.size - 2].toInt() and 0xFF
    val crcHi = content[content.size - 1].toInt() and 0xFF
    val actualCrc = (crcHi shl 8) or crcLo
    val expectedCrc = Crc16X25.compute(payload)

    return if (actualCrc != expectedCrc) {
        TonexResult.Failure(TonexError.CrcMismatch(expected = expectedCrc, actual = actualCrc))
    } else {
        TonexResult.Success(payload)
    }
}
