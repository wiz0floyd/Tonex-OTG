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
 * Requests a full state snapshot from the pedal (current preset, slot assignments, global
 * parameters, ...). Sent after [HelloMessage] completes, and again whenever the app needs to
 * resynchronise.
 *
 * ## Wire format
 *
 * Byte-exact against `usb_tonex_one.c:224` (`usb_tonex_one_request_state`):
 *
 * ```
 * b9 03 00 82 06 00 80 0b 03 b9 02 81 06 03 0b
 * ```
 *
 * Like [HelloMessage], the outer envelope's `type` field is `0x0000`, decoding as
 * [MessageType.Unknown] — expected for an outbound request. The six-byte payload
 * (`b9 02 81 06 03 0b`) is an opaque fixed literal, reproduced exactly.
 */
object RequestStateMessage {

    /** The fixed opaque payload that follows the header — see class KDoc. */
    private val PAYLOAD: ByteArray = byteArrayOf(0xB9.toByte(), 0x02, 0x81.toByte(), 0x06, 0x03, 0x0B)

    /**
     * Encodes the request-state message. Takes no parameters — this message is a fixed literal,
     * byte-exact against `usb_tonex_one.c:224` on every call.
     */
    fun encode(): ByteArray {
        val header = MessageHeader(
            type = MessageType.fromWireId(0x0000L),
            declaredSize = PAYLOAD.size.toLong(),
            unknownA = 11L,
            unknownB = 3L,
        )
        return MessageHeaderCodec.encode(header, PAYLOAD)
    }
}
