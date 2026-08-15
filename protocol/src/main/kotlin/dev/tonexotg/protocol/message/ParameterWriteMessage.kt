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

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.ParameterScope
import dev.tonexotg.protocol.codec.MessageHeader
import dev.tonexotg.protocol.codec.MessageHeaderCodec
import dev.tonexotg.protocol.codec.MessageType
import dev.tonexotg.protocol.params.ParameterRegistry

/**
 * The *safe* single-parameter write: patches exactly one preset-scoped parameter's value on the
 * pedal, without touching anything else in the current preset or the pedal's global state.
 *
 * This is deliberately the narrowest possible write this app can make. It is the direct
 * alternative to patching a whole [dev.tonexotg.protocol.PedalState] blob and sending that back —
 * the dangerous, easy-to-get-wrong path this project exists to avoid (see [dev.tonexotg.protocol.PedalState]
 * KDoc). Prefer this whenever the pedal firmware supports it (see "Firmware dependency" below).
 *
 * ## Scope: preset-scoped parameters only
 *
 * Upstream only ever routes this exact message shape through the pedal's 0-108 preset-parameter
 * indices (`usb_tonex_one.c:1444`, `if (message.Payload < TONEX_PARAM_LAST) ... usb_tonex_one_send_single_parameter(...)`).
 * Global parameters (110-116) are instead written by patching the full state blob
 * (`usb_tonex_one_modify_global`, `usb_tonex_one.c:658`) — **except** master volume, which has its
 * own dedicated single-parameter-shaped command (`usb_tonex_one_send_master_volume`,
 * `usb_tonex_one.c:304`; see [MasterVolumeMessage]). [encode] therefore rejects any
 * [ParameterId] that is not [ParameterScope.PRESET]: routing a global write through this path is
 * not something upstream is ever observed to do, so there is no confirmed wire behaviour to
 * reproduce for it. Master volume specifically should go through [MasterVolumeMessage]; every other
 * global parameter is out of this package's scope entirely (full state-blob patching, S8/S9).
 *
 * ## Wire format
 *
 * Byte-exact against `usb_tonex_one.c:265-295` (`usb_tonex_one_send_single_parameter`):
 *
 * ```c
 * uint8_t message[] = {0xb9,0x03,0x81,0x09,0x03,0x82,0x0A,0x00,0x80,0x0B,0x03};
 * uint8_t payload[] = {0xB9,0x04,0x02,0x00,0x00,0x88,0x00,0x00,0x00,0x00};
 * payload[4] = index;                      // parameter index
 * memcpy(&payload[6], &value, 4);          // little-endian float32 after the 0x88 marker
 * ```
 *
 * The outer envelope's `type` is `0x0309` — [MessageType.ParameterChanged]. The payload is built by
 * [SingleParameterPayloadCodec] with `kind = `[SingleParameterPayloadCodec.KIND_PARAMETER].
 *
 * ## Firmware dependency
 *
 * Upstream comments this write path *"only supported in newer Pedal firmware that came with Editor
 * support!"* (`usb_tonex_one.c:269`). This module has no way to detect that at encode time — that is
 * a capability fact learned at runtime (a failed/ignored write, or an explicit probe), not something
 * [encode] can know in advance. Whether the pedal *did* support this call is the caller's concern:
 * surface [dev.tonexotg.protocol.TonexError.UnsupportedByFirmware] once that is known, rather than
 * treating an ignored write as silent success. S20 probes for this on real hardware.
 */
object ParameterWriteMessage {

    /**
     * Encodes a write of [value] to the preset-scoped parameter [id].
     *
     * @throws IllegalArgumentException if [id] is not a [ParameterScope.PRESET] parameter (i.e. it
     *   is a global parameter, including master volume) — see the class KDoc for why this path is
     *   scoped to preset parameters only. This is a programmer error (the caller must route master
     *   volume through [MasterVolumeMessage] and other globals through full state-blob patching),
     *   not a protocol-level failure, so it throws rather than returning a [dev.tonexotg.protocol.TonexResult] —
     *   matching the `require()` convention this module already uses for constructor-time /
     *   caller-contract invariants (see e.g. [dev.tonexotg.protocol.codec.TonexVarint.encodeInt]).
     */
    fun encode(id: ParameterId, value: Float): ByteArray {
        val spec = ParameterRegistry.byIndex(id.index)
        require(spec != null && spec.scope == ParameterScope.PRESET) {
            "ParameterWriteMessage.encode: $id is not a preset-scoped parameter (spec=$spec). " +
                "Master volume must be written via MasterVolumeMessage; other global parameters are " +
                "written by patching the full state blob, not this single-parameter write path."
        }

        val payload = SingleParameterPayloadCodec.encode(
            kind = SingleParameterPayloadCodec.KIND_PARAMETER,
            index = id.index,
            value = value,
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
