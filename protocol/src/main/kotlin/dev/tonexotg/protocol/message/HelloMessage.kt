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

import dev.tonexotg.protocol.codec.MessageHeader
import dev.tonexotg.protocol.codec.MessageHeaderCodec
import dev.tonexotg.protocol.codec.MessageType

/**
 * The opening handshake message: the first thing this app sends after connecting.
 *
 * ## Wire format
 *
 * Byte-exact against `usb_tonex_one.c:204` (`usb_tonex_one_hello`):
 *
 * ```
 * b9 03 00 82 04 00 80 0b 01 b9 02 02 0b
 * ```
 *
 * The outer envelope's `type` field is `0x0000` here — it does not match any wire ID in
 * [MessageType]'s known-response catalogue (which upstream documents as *response* IDs; see
 * [MessageHeaderCodec] KDoc), so it decodes as [MessageType.Unknown]. That is expected for an
 * outbound request, not a gap in the catalogue. The four-byte payload (`b9 02 02 0b`) is an opaque
 * fixed literal — nothing in the reverse-engineered protocol explains its internal structure, so
 * it is reproduced exactly rather than reconstructed field-by-field.
 */
object HelloMessage {

    /** The fixed opaque payload that follows the header — see class KDoc. */
    private val PAYLOAD: ByteArray = byteArrayOf(0xB9.toByte(), 0x02, 0x02, 0x0B)

    /**
     * Encodes the Hello request. Takes no parameters — this message is a fixed literal, byte-exact
     * against `usb_tonex_one.c:204` on every call.
     */
    fun encode(): ByteArray {
        val header = MessageHeader(
            type = MessageType.fromWireId(0x0000L),
            declaredSize = PAYLOAD.size.toLong(),
            unknownA = 11L,
            unknownB = 1L,
        )
        return MessageHeaderCodec.encode(header, PAYLOAD)
    }
}
