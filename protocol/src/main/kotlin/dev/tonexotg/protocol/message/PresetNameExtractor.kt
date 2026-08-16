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

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult

/**
 * Extracts a preset's display name from a preset-details response payload
 * ([dev.tonexotg.protocol.codec.MessageType.PresetDetailsSummary] or
 * [dev.tonexotg.protocol.codec.MessageType.FullPresetDetails]).
 *
 * ## Read-only, deliberately
 *
 * The ToneX One has no name-*write* command anywhere in the reverse-engineered protocol — only
 * this read path exists. This object therefore offers [extract] only; do not add an encoder here.
 *
 * ## Wire format
 *
 * Ported from `usb_tonex_one.c:68` (`ToneOnePresetByteMarker`) and `:71`
 * (`TONEX_ONE_RESP_OFFSET_PRESET_NAME_LEN`):
 *
 * ```c
 * static const uint8_t ToneOnePresetByteMarker[] = {0xB9, 0x04, 0xB9, 0x02, 0xBC, 0x21};
 * #define TONEX_ONE_RESP_OFFSET_PRESET_NAME_LEN 32
 * ```
 *
 * The name is the fixed-length 32-byte field immediately following the 6-byte marker above,
 * wherever that marker occurs in the response — upstream locates it with `memmem` over the whole
 * response buffer (`usb_tonex_one.c:1198`), not a fixed offset, so [extract] does the same rather
 * than assuming the marker starts at any particular position.
 *
 * Upstream copies the 32 bytes into a plain `char[]` buffer with no length check on the source
 * content (`memcpy`, `usb_tonex_one.c:1204`) and no guarantee that byte 32 is itself `0x00` — a
 * short name is padded with trailing `0x00` bytes, but a name that fills the full 32 bytes carries
 * no terminator at all. [extract] trims *trailing* padding rather than assuming NUL-termination:
 * it looks for the first `0x00` byte within the 32-byte field and truncates there if one exists,
 * and otherwise keeps the full 32 bytes verbatim.
 */
object PresetNameExtractor {

    /**
     * `ToneOnePresetByteMarker` — see class KDoc. `private`: a `ByteArray` is mutable, and a public
     * `val` of one is a shared, writable array every caller aliases — any caller that mutated it
     * (even accidentally, e.g. `MARKER[0] = 0`) would silently corrupt name extraction for every
     * other caller in the process, forever, since [ByteArray] has no immutable variant to fall back
     * on. [marker] is the public accessor, and it hands out a fresh defensive copy every call so a
     * caller can freely mutate what it gets back without affecting [extract] or any other caller.
     */
    private val MARKER: ByteArray = byteArrayOf(0xB9.toByte(), 0x04, 0xB9.toByte(), 0x02, 0xBC.toByte(), 0x21)

    /** `TONEX_ONE_RESP_OFFSET_PRESET_NAME_LEN` — see class KDoc. */
    const val NAME_FIELD_LENGTH: Int = 32

    /** A fresh defensive copy of `ToneOnePresetByteMarker` — see [MARKER]'s KDoc for why. */
    fun marker(): ByteArray = MARKER.copyOf()

    /**
     * Locates [MARKER] in [payload] and extracts the 32-byte name field that follows it.
     *
     * Never throws:
     * - if [MARKER] is not found anywhere in [payload], fails with [TonexError.MalformedFrame].
     * - if [MARKER] is found but fewer than [NAME_FIELD_LENGTH] bytes remain after it, fails with
     *   [TonexError.UnexpectedBlobShape] (the response was truncated or is not actually a
     *   preset-details payload, despite the marker coincidentally matching).
     */
    fun extract(payload: ByteArray): TonexResult<String> {
        val markerIndex = indexOfMarker(payload)
            ?: return TonexResult.Failure(
                TonexError.MalformedFrame("preset name: marker not found in payload"),
            )

        val nameStart = markerIndex + MARKER.size
        val nameEnd = nameStart + NAME_FIELD_LENGTH
        if (nameEnd > payload.size) {
            return TonexResult.Failure(
                TonexError.UnexpectedBlobShape(
                    expectedSize = NAME_FIELD_LENGTH,
                    actualSize = (payload.size - nameStart).coerceAtLeast(0),
                ),
            )
        }

        val rawNameField = payload.copyOfRange(nameStart, nameEnd)
        val trimmed = rawNameField.takeWhile { it != 0.toByte() }.toByteArray()
        return TonexResult.Success(trimmed.toString(Charsets.UTF_8))
    }

    private fun indexOfMarker(bytes: ByteArray): Int? {
        if (MARKER.size > bytes.size) return null
        outer@ for (i in 0..bytes.size - MARKER.size) {
            for (j in MARKER.indices) {
                if (bytes[i + j] != MARKER[j]) continue@outer
            }
            return i
        }
        return null
    }
}
