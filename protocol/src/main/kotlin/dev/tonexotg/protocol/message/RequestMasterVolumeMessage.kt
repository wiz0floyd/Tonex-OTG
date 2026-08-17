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
 * Requests the pedal's current master-volume value.
 *
 * ## Wire format
 *
 * Byte-exact against `usb_tonex_one.c:347` (`usb_tonex_one_request_master_volume`):
 *
 * ```
 * b9 03 81 0d 03 82 05 00 80 0b 03 b9 03 03 00 00
 * ```
 *
 * This literal is *doubly* confirmed: it is both upstream line 347 and — independently — row 4 of
 * [MessageHeaderCodec]'s own reference table (`type = 0x030D`, `size = 5`, `unknownA = 11`,
 * `unknownB = 3`). The 5-byte payload (`b9 03 03 00 00`) is an opaque fixed literal, reproduced
 * exactly.
 *
 * ## Firmware caveat
 *
 * Upstream comments this request `// NOTE: only supported in newer Pedal firmware that came with
 * Editor support!` — the identical caveat [dev.tonexotg.protocol.message.ParameterWriteMessage]
 * and [dev.tonexotg.protocol.message.MasterVolumeMessage]'s *write* paths carry. Even though this
 * is a read, the connection state machine gates sending it on
 * [FirmwareCapabilities.supportsSingleParameterWrite] for exactly this reason — see the S9
 * architecture plan §7.3 for the full rationale and the naming-mismatch caveat that gate carries.
 *
 * The pedal answers this request with a [MessageType.ParameterChanged] (`0x0309`) frame whose
 * payload decodes via [SingleParameterPayloadCodec.decode] with `index == 0` (upstream line 856
 * confirms `index == 0` means master volume), and whose `value` is in the pedal's **native `0..10`**
 * range — convert with [MasterVolumeMessage.nativeToDecibels] before treating it as engineering
 * units.
 */
object RequestMasterVolumeMessage {

    /** The fixed opaque payload that follows the header — see class KDoc. */
    private val PAYLOAD: ByteArray = byteArrayOf(0xB9.toByte(), 0x03, 0x03, 0x00, 0x00)

    /**
     * Encodes the master-volume request. Takes no parameters — this message is a fixed literal,
     * byte-exact against `usb_tonex_one.c:347` on every call.
     */
    fun encode(): ByteArray {
        val header = MessageHeader(
            type = MessageType.fromWireId(0x030DL),
            declaredSize = PAYLOAD.size.toLong(),
            unknownA = 11L,
            unknownB = 3L,
        )
        return MessageHeaderCodec.encode(header, PAYLOAD)
    }
}
