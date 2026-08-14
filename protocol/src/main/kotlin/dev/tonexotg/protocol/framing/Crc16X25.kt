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
 */
package dev.tonexotg.protocol.framing

/**
 * CRC-16/X-25 (a.k.a. CRC-16/IBM-SDLC, HDLC FCS-16): reflected polynomial `0x8408`, init
 * `0xFFFF`, refin = true, refout = true, xorout `0xFFFF`.
 *
 * ### This is *not* CRC-CCITT-FALSE
 *
 * Upstream documentation (`vit3k/protocol.md`) calls this algorithm "CRC-CCITT", which is
 * imprecise and, if taken literally, produces the wrong checksum. CRC-16/CCITT-FALSE uses the
 * *non-reflected* polynomial `0x1021`, initial value `0xFFFF`, and no final XOR — a different
 * algorithm that happens to look similar on paper (both are nominally "16-bit CCITT-family")
 * but yields different output for the same input. See [Crc16X25Test] for a pinned regression
 * that asserts the two algorithms diverge, so a well-meaning future "fix" (e.g. someone
 * "correcting" this to a textbook CCITT implementation) fails loudly instead of silently
 * corrupting every frame's checksum.
 *
 * The reference/"check" value for this algorithm — the CRC of the ASCII bytes `"123456789"` —
 * is `0x906E`, matching the standard CRC-16/X-25 catalogue entry.
 */
object Crc16X25 {

    /** Reflected polynomial for CRC-16/X-25 (bit-reversal of the normal-form `0x1021`). */
    private const val POLYNOMIAL: Int = 0x8408

    /** Initial register value. */
    private const val INITIAL: Int = 0xFFFF

    /** Final XOR applied to the register before it is returned. */
    private const val XOR_OUT: Int = 0xFFFF

    /**
     * Computes the CRC-16/X-25 checksum of [bytes].
     *
     * @return the 16-bit checksum, held in the low 16 bits of the returned [Int] (values `0` to
     *   `0xFFFF`). Callers that need the two wire bytes should split this little-endian (low
     *   byte first) per the frame format documented in the `framing` package.
     */
    fun compute(bytes: ByteArray): Int {
        var crc = INITIAL
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) {
                    (crc ushr 1) xor POLYNOMIAL
                } else {
                    crc ushr 1
                }
            }
        }
        return (crc xor XOR_OUT) and 0xFFFF
    }
}
