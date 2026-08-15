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
import dev.tonexotg.protocol.params.ParameterRegistry

/**
 * Master volume's own single-parameter-shaped write command, and the engineering-units <->
 * native-units conversion it requires.
 *
 * ## Why master volume is special
 *
 * [dev.tonexotg.protocol.params.ParameterRegistry] stores master volume's range as **-40..+3 dB**
 * (`MASTER_VOLUME`, matching the full-size ToneX) so the same registry shape works across both
 * pedals, but the ToneX One itself natively speaks **0..10** on the wire — upstream's own comment
 * at the conversion site says exactly this: *"Big Tonex uses values -40 to +3, and One uses values
 * from 0 to 10. So, scaling the One's values to match the big Tonex."* (`usb_tonex_one.c:711-713`,
 * `:859-861`). Every write and read of master volume must convert between these two ranges; every
 * other parameter's engineering units are what the wire already speaks.
 *
 * Master volume also uses a **different `kind` byte** (`0x03`, not the ordinary
 * [SingleParameterPayloadCodec.KIND_PARAMETER]`0x02`) and **always writes index `0`** — it has no
 * ordinary preset/global wire index in this message shape, unlike a regular parameter (see
 * `usb_tonex_one.c:304-331`, `usb_tonex_one_send_master_volume`, where `payload[3]`/`payload[4]`
 * — the index bytes — are never touched from their initial `0x00`).
 *
 * ## Conversion — ported faithfully, direction and formula
 *
 * From `usb_tonex_one.c:711-713` (send / dB -> native) and `:859-861` (receive / native -> dB):
 *
 * ```c
 * // send (dB -> native, before writing to the wire):
 * scaled_value = ((value + 40.0f) / 43.0f) * 10.0f;
 *
 * // receive (native -> dB, after reading from the wire):
 * scaled_value = ((value / 10.0f) * 43.0f) - 40.0f;
 * ```
 *
 * [decibelsToNative] is the *send* direction (what [encode] applies before putting a value on the
 * wire); [nativeToDecibels] is the *receive* direction (what a caller applies to a raw wire value
 * read back from the pedal, e.g. via [SingleParameterPayloadCodec.decode]). `43` is
 * [ParameterRegistry]'s `MASTER_VOLUME` span (`3 - (-40)`) and `10` is the ToneX One's native span
 * (`10 - 0`) — both are looked up from the registry / hard-coded from the ToneX One's own range
 * respectively, rather than re-hard-coding the dB bounds a second time here, so the two stay in
 * sync if the registry entry ever changes.
 *
 * **This curve is assumed linear, purely on the strength of upstream's own linear formula above —
 * it has not been independently verified against real ToneX One hardware.** Whether the pedal's
 * actual 0..10 <-> -40..+3 dB mapping really is linear across its whole range (as opposed to, say,
 * a linear-in-native-but-not-really-dB approximation, or a curve upstream itself only approximates)
 * is an open question for S20's hardware verification pass, not something this module asserts as
 * fact. Do not treat the round-trip precision of this conversion's own inverse-of-itself math as
 * evidence the *pedal* agrees with it.
 */
object MasterVolumeMessage {

    private val spec = requireNotNull(ParameterRegistry.byEnumName("MASTER_VOLUME")) {
        "ParameterRegistry is missing its MASTER_VOLUME entry - this should never happen"
    }

    /** The engineering-units (dB) lower bound, from [ParameterRegistry] — upstream: `-40`. */
    private val engineeringMin: Float = spec.min

    /** The engineering-units (dB) upper bound, from [ParameterRegistry] — upstream: `3`. */
    private val engineeringMax: Float = spec.max

    /** The engineering-units span, i.e. upstream's `43.0f`. */
    private val engineeringSpan: Float = engineeringMax - engineeringMin

    /** The ToneX One's native lower bound. Not part of [ParameterRegistry] — the wire's own range. */
    private const val NATIVE_MIN: Float = 0f

    /** The ToneX One's native upper bound. Not part of [ParameterRegistry] — the wire's own range. */
    private const val NATIVE_MAX: Float = 10f

    /** The native-units span, i.e. upstream's `10.0f`. */
    private const val NATIVE_SPAN: Float = NATIVE_MAX - NATIVE_MIN

    /**
     * Converts an engineering-units (dB, [ParameterRegistry]'s `-40..3` range) value to the ToneX
     * One's native `0..10` range — the *send* direction; see class KDoc for the formula and its
     * unverified-against-hardware caveat.
     */
    fun decibelsToNative(decibels: Float): Float =
        ((decibels - engineeringMin) / engineeringSpan) * NATIVE_SPAN

    /**
     * Converts a native `0..10` value read from the wire back to engineering-units (dB,
     * [ParameterRegistry]'s `-40..3` range) — the *receive* direction; see class KDoc for the
     * formula and its unverified-against-hardware caveat.
     */
    fun nativeToDecibels(native: Float): Float =
        ((native / NATIVE_SPAN) * engineeringSpan) + engineeringMin

    /**
     * Encodes a master-volume write for [decibels] (in [ParameterRegistry]'s `-40..3` dB range).
     * Internally converts to the pedal's native `0..10` range via [decibelsToNative] before placing
     * it on the wire — callers pass engineering units in, this function handles the wire's native
     * units entirely internally.
     *
     * ## Wire format
     *
     * Byte-exact against `usb_tonex_one.c:304-331` (`usb_tonex_one_send_master_volume`):
     *
     * ```c
     * uint8_t message[] = {0xb9,0x03,0x81,0x09,0x03,0x82,0x0A,0x00,0x80,0x0B,0x03};
     * uint8_t payload[] = {0xB9,0x04,0x03,0x00,0x00,0x88,0x00,0x00,0x00,0x00};
     * memcpy(&payload[6], &value, 4);   // little-endian float32, native 0..10 units
     * ```
     *
     * Same outer envelope shape as [ParameterWriteMessage] (`type = 0x0309`,
     * [MessageType.ParameterChanged]) — only the payload's `kind` byte
     * ([SingleParameterPayloadCodec.KIND_MASTER_VOLUME]) differs from the ordinary single-parameter
     * write.
     *
     * See [ParameterWriteMessage] KDoc's "Firmware dependency" section — the same
     * newer-firmware-only caveat applies to this write, per the same upstream source comment.
     */
    fun encode(decibels: Float): ByteArray {
        val native = decibelsToNative(decibels)
        val payload = SingleParameterPayloadCodec.encode(
            kind = SingleParameterPayloadCodec.KIND_MASTER_VOLUME,
            index = 0,
            value = native,
        )
        val header = MessageHeader(
            type = MessageType.ParameterChanged,
            declaredSize = payload.size.toLong(),
            unknownA = 11L,
            unknownB = 3L,
        )
        return MessageHeaderCodec.encode(header, payload)
    }
}
