package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult

/**
 * What this app currently knows about the connected pedal's support for write paths that are not
 * confirmed to exist on every firmware version.
 *
 * The single known case at design time: the *safe* single-parameter write
 * ([ParameterWriteMessage], [MasterVolumeMessage]) is, per upstream's own comment, *"only
 * supported in newer Pedal firmware that came with Editor support!"* (`usb_tonex_one.c:269`).
 * Nothing in this package can observe a connected pedal's firmware version directly — that is a
 * fact learned elsewhere (a successful/failed probe write, or an explicit version check) and
 * handed in here, not derived. This type exists so that fact is a **capability that must be
 * supplied**, never a default this package assumes on a caller's behalf: there is deliberately no
 * "assume supported" constructor or default value — see [encodeIfSupported] on
 * [ParameterWriteMessage] / [MasterVolumeMessage], which require an explicit
 * [FirmwareCapabilities] argument rather than defaulting to `true`.
 *
 * @property supportsSingleParameterWrite whether the connected pedal's firmware is currently known
 *   to accept the single-parameter write path. `false` is the only safe default for a caller that
 *   has not yet established this some other way (e.g. before [dev.tonexotg.protocol.ConnectionState.Ready],
 *   or before a capability probe has run) — treating "unknown" as "supported" risks exactly the
 *   silently-ignored write this type exists to prevent (see [TonexError.UnsupportedByFirmware]).
 */
data class FirmwareCapabilities(
    val supportsSingleParameterWrite: Boolean,
) {
    companion object {
        /**
         * The conservative starting point for a connection that has not yet established firmware
         * capabilities: every optional capability is `false`. Callers must explicitly upgrade this
         * once a capability is actually confirmed — this is not a placeholder meant to be treated
         * as "probably fine."
         */
        val NONE_CONFIRMED: FirmwareCapabilities = FirmwareCapabilities(supportsSingleParameterWrite = false)
    }
}

/**
 * Fails with [TonexError.UnsupportedByFirmware] (naming [operation]) when
 * [FirmwareCapabilities.supportsSingleParameterWrite] is `false`; otherwise succeeds with the
 * result of [encode].
 *
 * Shared by [ParameterWriteMessage.encodeIfSupported] and [MasterVolumeMessage.encodeIfSupported]
 * so both gate on the exact same capability flag and produce the exact same shape of failure —
 * see [FirmwareCapabilities] for why this is a capability a caller must supply, not an assumption
 * this package makes.
 */
internal fun encodeGatedBySingleParameterWriteSupport(
    capabilities: FirmwareCapabilities,
    operation: String,
    encode: () -> ByteArray,
): TonexResult<ByteArray> =
    if (capabilities.supportsSingleParameterWrite) {
        TonexResult.Success(encode())
    } else {
        TonexResult.Failure(TonexError.UnsupportedByFirmware(operation))
    }
