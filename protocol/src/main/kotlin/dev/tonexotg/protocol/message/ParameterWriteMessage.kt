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
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.codec.MessageHeader
import dev.tonexotg.protocol.codec.MessageHeaderCodec
import dev.tonexotg.protocol.codec.MessageType
import dev.tonexotg.protocol.params.EffectiveParameterBounds
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
 * ## Value range: clamped to the *effective* bounds, not passed through raw
 *
 * [encode] clamps [value] to `[id]`'s `min..effectiveMax` before it ever reaches the wire, where
 * `effectiveMax` comes from the [EffectiveParameterBounds] the caller supplies (default
 * [EffectiveParameterBounds.STATIC], i.e. the registry's own static `max`). [ParameterRegistry]
 * documents its `min`/`max` as "the load-bearing bounds for clamping and scaling" — this is that
 * clamping actually happening, rather than those bounds existing only as descriptive metadata a
 * caller has to remember to apply itself. Upstream itself performs no such clamp; this project's
 * premise is being *safer* than upstream, not merely byte-compatible with it, and letting an
 * out-of-range value (a UI slider bug, a bad deserialize, a unit mixup — e.g. passing a raw dB
 * value somewhere native units were expected) travel unmodified from caller to pedal is exactly
 * the kind of silent, hard-to-diagnose failure that premise exists to prevent. Clamping (rather
 * than rejecting with a typed error) is the deliberate choice here: an out-of-range value has one
 * unambiguous, safe interpretation — the nearest in-range value — so there is a well-defined
 * "safe" thing to do rather than merely a "reject" thing to do, and a clamped write still moves
 * the pedal in the direction the caller asked for. Contrast this with the scope check above,
 * which throws: there [id] itself is invalid input with no safe reinterpretation, not a value
 * that merely needs bounding.
 *
 * **Threading the effective bound through here matters** (issue #80): for
 * [ParameterRegistry.SELF_WIDENING_PARAMETER_IDS], `DefaultTonexController`'s write-validation
 * already accepts a self-widened value above the *static* registry max — if this encoder still
 * clamped to the static max unconditionally, it would silently truncate an already-validated
 * widened value back down right before it reaches the wire, defeating the entire feature at the
 * last possible step.
 *
 * ## Firmware dependency
 *
 * Upstream comments this write path *"only supported in newer Pedal firmware that came with Editor
 * support!"* (`usb_tonex_one.c:269`). This module has no way to detect that at encode time — that is
 * a capability fact learned at runtime (a failed/ignored write, or an explicit probe), not something
 * the raw byte-builder can know in advance. That is why this is modelled as a [FirmwareCapabilities]
 * the caller must supply, not an assumption this object bakes in — and why it is enforced
 * structurally, not just by convention: the raw, unconditional byte-builder is `internal fun encode`
 * (visible only within `:protocol`, including this file's own tests, via Kotlin's friend-compilation
 * of a module's `src/test` against its own `src/main`), and the only function this object exposes
 * publicly, [encode] (three-arg, taking [FirmwareCapabilities]), is the one that actually checks the
 * capability and surfaces [dev.tonexotg.protocol.TonexError.UnsupportedByFirmware] rather than
 * letting a caller outside this module send a write the pedal will silently ignore. S20 probes for
 * this on real hardware.
 */
object ParameterWriteMessage {

    /**
     * Encodes a write of [value] to the preset-scoped parameter [id], unconditionally — this
     * function has no way to know whether the connected pedal's firmware actually honours a
     * single-parameter write (see class KDoc "Firmware dependency"). `internal`, deliberately: the
     * only way to reach this from outside `:protocol` is through the public, capability-gated
     * three-arg [encode] overload below.
     *
     * [value] is clamped to [id]'s `min..effectiveMax` before encoding — see class KDoc "Value
     * range". [effectiveBounds] defaults to [EffectiveParameterBounds.STATIC] (the registry's own
     * static max) so every pre-existing caller is unaffected.
     *
     * @throws IllegalArgumentException if [id] is not a [ParameterScope.PRESET] parameter (i.e. it
     *   is a global parameter, including master volume) — see the class KDoc for why this path is
     *   scoped to preset parameters only. This is a programmer error (the caller must route master
     *   volume through [MasterVolumeMessage] and other globals through full state-blob patching),
     *   not a protocol-level failure, so it throws rather than returning a [dev.tonexotg.protocol.TonexResult] —
     *   matching the `require()` convention this module already uses for constructor-time /
     *   caller-contract invariants (see e.g. [dev.tonexotg.protocol.codec.TonexVarint.encodeInt]).
     */
    internal fun encode(
        id: ParameterId,
        value: Float,
        effectiveBounds: EffectiveParameterBounds = EffectiveParameterBounds.STATIC,
    ): ByteArray {
        val spec = ParameterRegistry.byIndex(id.index)
        require(spec != null && spec.scope == ParameterScope.PRESET) {
            "ParameterWriteMessage.encode: $id is not a preset-scoped parameter (spec=$spec). " +
                "Master volume must be written via MasterVolumeMessage; other global parameters are " +
                "written by patching the full state blob, not this single-parameter write path."
        }

        val payload = SingleParameterPayloadCodec.encode(
            kind = SingleParameterPayloadCodec.KIND_PARAMETER,
            index = id.index,
            value = value.coerceIn(spec.min, effectiveBounds.effectiveMax(id)),
        )
        val header = MessageHeader(
            type = MessageType.ParameterChanged,
            declaredSize = payload.size.toLong(),
            unknownA = 11L,
            unknownB = 3L,
        )
        return MessageHeaderCodec.encode(header, payload)
    }

    /**
     * The capability-gated entry point, and the only public way to encode a single-parameter write
     * from outside `:protocol`: encodes a write of [value] to [id] only when [capabilities] confirms
     * [FirmwareCapabilities.supportsSingleParameterWrite]; otherwise fails with
     * [dev.tonexotg.protocol.TonexError.UnsupportedByFirmware]`("single-parameter-write")` rather
     * than producing bytes for a write the pedal may silently ignore. [capabilities] is a required
     * parameter with no default — there is deliberately no shorter, capability-free way to reach this
     * from outside this module. [effectiveBounds] defaults to [EffectiveParameterBounds.STATIC];
     * `DefaultTonexController` supplies its own session-scoped instance so a self-widened value
     * (issue #80) is not clamped back down at encode time.
     *
     * @throws IllegalArgumentException if [capabilities] confirms support but [id] is not a
     *   [ParameterScope.PRESET] parameter — same condition, same rationale, as the internal
     *   byte-builder above. The capability check runs first: an out-of-scope [id] against
     *   unsupported firmware surfaces [dev.tonexotg.protocol.TonexError.UnsupportedByFirmware], not
     *   this exception, since the byte-builder is never called to discover the scope problem in that
     *   case.
     */
    fun encode(
        id: ParameterId,
        value: Float,
        capabilities: FirmwareCapabilities,
        effectiveBounds: EffectiveParameterBounds = EffectiveParameterBounds.STATIC,
    ): TonexResult<ByteArray> =
        encodeGatedBySingleParameterWriteSupport(capabilities, operation = "single-parameter-write") {
            encode(id, value, effectiveBounds)
        }
}
