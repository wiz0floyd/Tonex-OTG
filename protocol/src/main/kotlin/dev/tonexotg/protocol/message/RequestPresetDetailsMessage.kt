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

import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.codec.MessageHeader
import dev.tonexotg.protocol.codec.MessageHeaderCodec
import dev.tonexotg.protocol.codec.MessageType

/**
 * Which level of detail [RequestPresetDetailsMessage] asks the pedal for.
 *
 * @property wireValue the byte upstream calls `full_details` — `0x00` for a summary, `0x01` for
 *   the full response.
 */
enum class PresetDetailsKind(val wireValue: Int) {
    /** Approximately 2 KB — enough for [PresetNameExtractor] and top-level bookkeeping. */
    SUMMARY(0x00),

    /** Approximately 30 KB — the complete per-parameter payload for the preset. */
    FULL(0x01),
}

/**
 * Requests one preset's details by index, at either [PresetDetailsKind.SUMMARY] or
 * [PresetDetailsKind.FULL] detail level.
 *
 * ## Wire format
 *
 * Byte-exact against `usb_tonex_one.c:246` (`usb_tonex_one_request_preset_details`):
 *
 * ```c
 * uint8_t request[] = {0xb9,0x03,0x81,0x00,0x03,0x82,0x06,0x00,0x80,0x0b,0x03,
 *                      0xb9,0x04,0x0b,0x01,0x00,0x00};
 * request[15] = preset_index;   // 0..19
 * request[16] = full_details;   // 0x00 = ~2KB summary, 0x01 = ~30KB full
 * ```
 *
 * The outer envelope's `type` is `0x0300` — this is the *request* type; the pedal's *response* to
 * this request carries a different, catalogued wire ID
 * ([MessageType.PresetDetailsSummary]`0x0304` or [MessageType.FullPresetDetails]`0x0303`). `0x0300`
 * itself decodes as [MessageType.Unknown], which is expected here, not a gap.
 *
 * The 4-byte payload prefix (`b9 04 0b 01`) is an opaque fixed literal; only the trailing preset
 * index and detail-level byte vary per call.
 */
object RequestPresetDetailsMessage {

    private val PAYLOAD_PREFIX: ByteArray = byteArrayOf(0xB9.toByte(), 0x04, 0x0B, 0x01)

    /**
     * Encodes a request for [index]'s preset details at the given [kind].
     *
     * @param index which of the pedal's 20 stored presets to ask about.
     * @param kind [PresetDetailsKind.SUMMARY] (~2 KB) or [PresetDetailsKind.FULL] (~30 KB).
     */
    fun encode(index: PresetIndex, kind: PresetDetailsKind): ByteArray {
        val payload = PAYLOAD_PREFIX + byteArrayOf(index.value.toByte(), kind.wireValue.toByte())
        val header = MessageHeader(
            type = MessageType.fromWireId(0x0300L),
            declaredSize = payload.size.toLong(),
            unknownA = 11L,
            unknownB = 3L,
        )
        return MessageHeaderCodec.encode(header, payload)
    }
}
