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
 * The whole-device-state write: frames an already-patched state blob (see
 * [dev.tonexotg.protocol.state.StateBlobPatcher]) for the wire, with no other transformation.
 *
 * ## Byte-exact against three identical upstream call sites
 *
 * `usb_tonex_one_set_active_slot` (`usb_tonex_one.c:412`),
 * `usb_tonex_one_set_preset_in_slot` (`:450`), and `usb_tonex_one_set_ab_slots` (`:565`) all build
 * this exact envelope — `b9 03 81 06 03 82 <lenLo> <lenHi> 80 0b 03` — immediately followed by the
 * raw `StateData[StateDataLength]` bytes, with nothing in between:
 *
 * ```c
 * uint8_t message[] = {0xb9,0x03,0x81,0x06,0x03,0x82,0x00,0x00,0x80,0x0b,0x03};
 * // message[6]/message[7] <- StateDataLength, little-endian
 * // followed by memcpy(..., StateData, StateDataLength)
 * ```
 *
 * Verified 2026-08-17 against upstream commit `7079f157107a7bc91f171e51e3da0d799d31fcfb` (S9
 * architecture plan §0 — the commit was fetched 2026-08-17, matching this citation date; the same
 * commit [dev.tonexotg.protocol.state.StateBlobOffsets] is pinned to). Independently re-derived
 * byte-for-byte from the same upstream source during the PR #43 adversarial review (2026-08-17).
 *
 * ## Three things here that look like bugs to a future reader and are not
 *
 * 1. **`internal`, with no public overload — deliberately, and unlike every other message object
 *    in this package.** [ParameterWriteMessage]/[MasterVolumeMessage] are `internal` byte-builders
 *    with a *public*, capability-gated `encode` overload; this object has no public entry point at
 *    all. This is the whole-device write the entire [dev.tonexotg.protocol.PedalState]/
 *    [dev.tonexotg.protocol.SessionId] apparatus exists to constrain (see [dev.tonexotg.protocol.PedalState]'s
 *    "why this type exists"). The only legitimate caller is the connection state machine's
 *    `selectPreset`, immediately after [dev.tonexotg.protocol.state.StateBlobPatcher] returns a
 *    patched blob — there is deliberately no way for a caller outside `:protocol` to reach this.
 * 2. **The outbound `type` is `0x0306` — the same wire ID the pedal uses for its own inbound state
 *    pushes ([MessageType.StateUpdate]).** This is genuinely what upstream sends at all three call
 *    sites above; do not "fix" it to a distinct request ID, there isn't one. It is also *why* the
 *    connection state machine cannot correlate a state-update response to this specific write —
 *    there is no way to distinguish "the pedal echoed our write" from "the pedal is pushing an
 *    unrelated update" by message shape alone.
 * 3. **The payload is the patched blob verbatim, with no prefix or transformation.** Upstream
 *    `memcpy`s the header, then the raw `StateData` — nothing lives between them. [encode] does
 *    exactly that: [MessageHeaderCodec.encode] with [patchedStateBlob] as the payload, unchanged.
 */
object SetStateMessage {

    /**
     * Frames [patchedStateBlob] (the output of a successful
     * [dev.tonexotg.protocol.state.StateBlobPatcher] call) as an outbound whole-state write. See
     * the class KDoc for why this is `internal` with no public counterpart.
     */
    internal fun encode(patchedStateBlob: ByteArray): ByteArray {
        val header = MessageHeader(
            type = MessageType.StateUpdate,
            declaredSize = patchedStateBlob.size.toLong(),
            unknownA = 11L,
            unknownB = 3L,
        )
        return MessageHeaderCodec.encode(header, patchedStateBlob)
    }
}
