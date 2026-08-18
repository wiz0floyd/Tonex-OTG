package dev.tonexotg.app.ui.screens.connection

import dev.tonexotg.protocol.TonexError

/**
 * A [TonexError], translated into something a player can actually read and act on mid-set.
 *
 * This is S18's direct answer to FR11 ("surface an error rather than assume success") and to
 * this issue's acceptance criterion that every [TonexError] variant gets "a distinct, actionable
 * presentation... not a generic 'something went wrong'." Three fields, each doing a different
 * job:
 *
 * @property title A short (2-4 word), variant-specific category label — what shows next to the
 *   error glyph at a glance, before anyone reads the detail. Never "Error" or "Something went
 *   wrong" on its own; always names the actual failure category.
 * @property detail The full, specific explanation — this is always [TonexError.message] itself.
 *   Every `TonexError` variant in `protocol/.../TonexError.kt` already carries a precise,
 *   accurate, human-readable message (hardened through two rounds of adversarial review — see
 *   that file's KDoc); this presentation layer does not paraphrase or shorten it, only adds a
 *   [title] and [advice] around it.
 * @property advice What, if anything, the player can actually do about it right now. Phrased as
 *   a concrete next step, not a vague reassurance. Several variants ([RevertIncomplete] vs
 *   [ActivePresetChangedDuringRevert] in particular) have advice that is deliberately NOT "just
 *   retry" — see each `when` branch below for why.
 * @property recommendReconnect Whether a reconnect affordance should be offered alongside this
 *   presentation. `true` for failures that end (or already ended) the transport-level
 *   connection; `false` for operation-level failures where the connection is still
 *   [dev.tonexotg.protocol.ConnectionState.Ready] and reconnecting would not address the actual
 *   problem (e.g. an out-of-range parameter value, a preset with no snapshot).
 */
data class ErrorPresentation(
    val title: String,
    val detail: String,
    val advice: String,
    val recommendReconnect: Boolean,
)

/**
 * Maps every [TonexError] variant to its [ErrorPresentation]. Deliberately an exhaustive `when`
 * with no `else` branch: adding a new [TonexError] subtype must fail this file's compilation
 * until it is given its own considered presentation here, rather than silently falling through
 * to a generic message — the same "fail fast and loud" reasoning `TonexError` itself is built
 * on.
 */
fun TonexError.toPresentation(): ErrorPresentation = when (this) {
    is TonexError.TransportFailure -> ErrorPresentation(
        title = "Connection Lost",
        detail = message,
        advice = "The USB link to the pedal dropped. Reconnect to continue.",
        recommendReconnect = true,
    )

    is TonexError.Timeout -> ErrorPresentation(
        title = "Pedal Didn't Respond",
        detail = message,
        advice = "The pedal didn't answer in time. Check the cable and reconnect.",
        recommendReconnect = true,
    )

    is TonexError.MalformedFrame -> ErrorPresentation(
        title = "Corrupted Data Received",
        detail = message,
        advice = "Data from the pedal couldn't be understood. Reconnect to start a clean session.",
        recommendReconnect = true,
    )

    is TonexError.CrcMismatch -> ErrorPresentation(
        title = "Data Check Failed",
        detail = message,
        advice = "A message from the pedal failed its integrity check and was discarded. " +
            "Reconnect to start a clean session.",
        recommendReconnect = true,
    )

    is TonexError.ProtocolStateViolation -> ErrorPresentation(
        title = "Not Ready For That Yet",
        detail = message,
        advice = "That action isn't available in the connection's current state. " +
            "Wait for the connection to finish, or reconnect.",
        recommendReconnect = true,
    )

    is TonexError.UnsupportedByFirmware -> ErrorPresentation(
        title = "Not Supported By This Pedal",
        detail = message,
        advice = "This pedal's firmware doesn't support this feature. " +
            "Update the pedal's firmware via the manufacturer's own editor to enable it.",
        recommendReconnect = false,
    )

    is TonexError.StaleSessionState -> ErrorPresentation(
        title = "Write Blocked — Outdated Data",
        detail = message,
        advice = "This write was refused to avoid overwriting the pedal with stale data. " +
            "No data was changed; the app will use fresh data automatically on the next read.",
        recommendReconnect = false,
    )

    is TonexError.UnexpectedBlobShape -> ErrorPresentation(
        title = "Unexpected Pedal Data",
        detail = message,
        advice = "The pedal returned data in a shape the app didn't expect. " +
            "Reconnect to start a clean session.",
        recommendReconnect = true,
    )

    is TonexError.BlobTooShortToPatch -> ErrorPresentation(
        title = "Incomplete Pedal Data",
        detail = message,
        advice = "The pedal's state data was too short to edit safely, so nothing was changed. " +
            "Reconnect to start a clean session.",
        recommendReconnect = true,
    )

    is TonexError.BlobSizeChangedSinceHandshake -> ErrorPresentation(
        title = "Pedal Data Changed Unexpectedly",
        detail = message,
        advice = "The pedal's state data shrank mid-session, so nothing was changed. " +
            "Reconnect to start a clean session.",
        recommendReconnect = true,
    )

    is TonexError.ImplausibleStateBlobShape -> ErrorPresentation(
        title = "Pedal Data Looks Wrong",
        detail = message,
        advice = "The pedal's state data didn't look right at the fields this app was about to " +
            "edit, so nothing was changed. Reconnect to start a clean session.",
        recommendReconnect = true,
    )

    is TonexError.OversizedStateBlob -> ErrorPresentation(
        title = "Pedal Data Too Large",
        detail = message,
        advice = "The pedal returned more data than it should be able to, so nothing was " +
            "changed. Reconnect to start a clean session.",
        recommendReconnect = true,
    )

    is TonexError.InvalidPresetIndex -> ErrorPresentation(
        title = "Invalid Preset Index",
        detail = message,
        advice = "An internal check refused this write to protect the pedal's state. " +
            "Nothing was changed. If this keeps happening, it's a bug — please report it.",
        recommendReconnect = false,
    )

    is TonexError.NoSnapshotAvailable -> ErrorPresentation(
        title = "No Snapshot To Revert To",
        detail = message,
        advice = "This preset has no captured snapshot this session, so there's nothing to " +
            "revert to yet.",
        recommendReconnect = false,
    )

    is TonexError.RevertIncomplete -> ErrorPresentation(
        title = "Revert Stopped Partway",
        detail = message,
        advice = "The revert stopped partway through — the preset is now a mix of old and new " +
            "values. The snapshot is still intact; it's safe to try Revert again.",
        recommendReconnect = false,
    )

    is TonexError.ActivePresetChangedDuringRevert -> ErrorPresentation(
        title = "Revert Interrupted By Pedal",
        detail = message,
        advice = "The pedal switched presets mid-revert (footswitch, MIDI, or an external " +
            "editor), so the revert was stopped to avoid corrupting the wrong preset. Do not " +
            "retry immediately — retrying now would target the pedal's new active preset, not " +
            "the one this revert was restoring, and the original snapshot may already be gone.",
        recommendReconnect = false,
    )

    is TonexError.ParameterValueOutOfRange -> ErrorPresentation(
        title = "Value Out Of Range",
        detail = message,
        advice = "That value is outside this parameter's valid range. Adjust it and try again.",
        recommendReconnect = false,
    )
}
