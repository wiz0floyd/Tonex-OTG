package dev.tonexotg.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One onboard preset as known to the controller: its position and the pedal's own name for it.
 *
 * Deliberately does not include a user-assigned local alias — per FR10/S14, aliasing is an
 * override layer resolved in the UI/view-model as `localAlias ?: pedalName`, not something
 * `:protocol` merges in itself. `:protocol` only ever reports what the pedal actually said.
 *
 * @property index the preset's onboard position; see [PresetIndex].
 * @property pedalName the pedal's own name for this preset, as read from the preset-details
 *   response (a 32-byte fixed field, trailing padding trimmed). Names are read-only on this
 *   pedal — there is no name-write command — so this can change only by re-reading from the
 *   pedal, never by a local edit.
 */
data class PresetInfo(
    val index: PresetIndex,
    val pedalName: String,
)

/**
 * Notable happenings a caller cannot infer from [TonexController.connectionState] alone —
 * things that occur *within* [ConnectionState.Ready] rather than being a transition between
 * states.
 */
sealed interface TonexEvent {

    /**
     * The session's first parameter write against a snapshotted preset has **just succeeded** —
     * a post-hoc notice, not a pre-write gate. Fires at most once per session, immediately after
     * [TonexController.setParameter] returns `Success` for the first time against a preset that
     * has a captured [PresetSnapshot]; a caller must not treat this as something to acknowledge
     * before the write proceeds — the write has already happened, and is recoverable via
     * [TonexController.revertActivePreset].
     *
     * Deliberately post-hoc, not pre-write (S9b architecture plan §F.3): `events` is a
     * zero-replay [SharedFlow] with no suspending-confirmation mechanism, a pre-write gate would
     * have to block inside the controller's write lock (wedging every other operation for as
     * long as a confirmation dialog takes to answer), and firing before the write would be
     * dishonest when the write then fails. Presentation of this signal is a UI concern (D3), but
     * the underlying fact — this pedal has no undo, and this is the first change to it this
     * session — originates here.
     */
    data class FirstDestructiveWrite(val presetIndex: PresetIndex) : TonexEvent

    /**
     * The pedal's active preset changed for a reason other than this controller's own
     * [TonexController.selectPreset] call — i.e. it was changed externally, at the footswitch
     * (FR6). Observing this is also what should trigger a re-snapshot (S9b): a snapshot taken
     * of the *previous* active preset is not valid for this one.
     */
    data class ExternalPresetChange(val newIndex: PresetIndex) : TonexEvent
}

/**
 * The facade the UI layer consumes: everything above the wire-protocol details, exposed as
 * suspend functions and [Flow]s rather than callbacks.
 *
 * `TonexController` is where the pieces built across S4-S9b compose — framing, codecs, the
 * parameter registry, state-blob patching, the connection state machine, and the snapshot
 * safety net — into the single object the UI needs. It does not expose [PedalState] or
 * [SessionId] at all: those stay internal to how preset selection and reverting are implemented
 * safely, never surfaced as something a UI-layer caller could misuse.
 *
 * Every operation that can fail for more than one reason returns a [TonexResult] rather than
 * throwing or returning a bare `Boolean` — per FR11, a write that fails or times out must never
 * be reported as success, and a UI cannot show a specific reason for a failure it cannot
 * distinguish from any other.
 */
interface TonexController {

    /** The connection's current lifecycle state. See [ConnectionState]. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * The pedal's currently active preset, or `null` before it is known (i.e. before
     * [ConnectionState.Ready]). Reflects changes made through this controller *and* changes
     * made externally at the footswitch (FR6) — see [events] for a distinguishable signal when
     * it's the latter.
     */
    val activePreset: StateFlow<PresetIndex?>

    /**
     * All 20 onboard presets, harvested immediately after reaching [ConnectionState.Ready].
     * Empty before then.
     */
    val presets: StateFlow<List<PresetInfo>>

    /**
     * The pedal's three footswitch slot assignments (A/B/C), or an empty map before
     * [ConnectionState.Ready]. Reflects changes made through this controller (e.g.
     * [assignPresetToSlot], or [selectPreset]'s own side-effect of reassigning a slot) *and*
     * changes made externally at the footswitch or another editor (FR6-style live projection) —
     * the same guarantee [activePreset] and [presets] make, applied to slot assignments instead.
     * Like [activePreset], this is push-driven only: neither [assignPresetToSlot] nor
     * [selectPreset] updates it optimistically on a successful write — it only ever changes when
     * the pedal's own confirming state push is observed.
     */
    val slotAssignments: StateFlow<Map<PresetSlot, PresetIndex>>

    /**
     * The last known value of every parameter belonging to the active preset, plus the 7
     * globals, keyed by [ParameterId]. An id absent from this map means its value is not yet
     * known (e.g. before [ConnectionState.Ready]) — absence is not the same as a value of zero.
     * Updated on preset load/change and after each successful [setParameter].
     */
    val parameterValues: StateFlow<Map<ParameterId, Float>>

    /**
     * Notable happenings that are not themselves [connectionState] transitions. See
     * [TonexEvent]. A [SharedFlow] (not a [StateFlow]): these are one-off occurrences, not
     * ongoing state, so there is no "current value" to replay a value for — only new collectors
     * miss events emitted before they started collecting, by design.
     */
    val events: SharedFlow<TonexEvent>

    /**
     * Begins a connection using [transport], driving [connectionState] through
     * [ConnectionState.Connecting], [ConnectionState.Hello], and [ConnectionState.GetState] to
     * [ConnectionState.Ready] (or to [ConnectionState.Error] on failure).
     *
     * Suspends until the connection either reaches [ConnectionState.Ready] or fails; callers
     * that only want to observe progress along the way should collect [connectionState]
     * concurrently rather than waiting on this call alone.
     */
    suspend fun connect(transport: TonexTransport): TonexResult<Unit>

    /**
     * Ends the current connection and returns [connectionState] to [ConnectionState.Idle].
     * Safe to call from any state, including [ConnectionState.Idle] itself (a no-op then).
     */
    suspend fun disconnect()

    /**
     * Makes [index] the pedal's active preset.
     *
     * Requires [ConnectionState.Ready]; fails with [TonexError.ProtocolStateViolation]
     * otherwise. Implemented as a read-modify-write against the pedal's current state blob
     * (patching only the relevant slot bytes) rather than a synthesized write — see
     * [PedalState] for why that distinction is load-bearing.
     */
    suspend fun selectPreset(index: PresetIndex): TonexResult<Unit>

    /**
     * Assigns [preset] to [slot] WITHOUT changing the pedal's active slot — in contrast with
     * [selectPreset], which both selects a preset as active *and* may reassign a slot to it as a
     * side effect of doing so. This is the direct "edit what footswitch slot B plays" operation.
     *
     * Never changes which *slot* is active — the active-slot byte is not part of this write. It
     * does, however, change [activePreset] in the one case where [slot] **is** the currently
     * active slot, since the active preset is by definition whatever the active slot points at;
     * assigning to any other slot leaves [activePreset] untouched.
     *
     * Requires [ConnectionState.Ready]; fails with [TonexError.ProtocolStateViolation]
     * otherwise. Implemented as a read-modify-write against the pedal's current state blob
     * (patching only [slot]'s one assignment byte), the same [PedalState] freshness discipline
     * [selectPreset] follows — see its KDoc for why that distinction is load-bearing.
     */
    suspend fun assignPresetToSlot(slot: PresetSlot, preset: PresetIndex): TonexResult<Unit>

    /**
     * Writes [value] for parameter [id] on the pedal, using the single-parameter write path
     * (never a whole-state write).
     *
     * Requires [ConnectionState.Ready]; fails with [TonexError.ProtocolStateViolation]
     * otherwise, and with [TonexError.UnsupportedByFirmware] if the connected pedal's firmware
     * predates per-parameter write support. [value] must fall within the corresponding
     * [ParameterSpec.min]/[max] for [id]; out-of-range values are rejected rather than silently
     * clamped, so a caller cannot mistake a clamped write for the value it asked for.
     *
     * This is the first operation this session that can permanently alter the active preset —
     * see [TonexEvent.FirstDestructiveWrite], which fires immediately after this call returns
     * `Success` for the first time against a preset with a captured [PresetSnapshot] (a
     * post-hoc notice; the write has already happened by the time it fires).
     */
    suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit>

    /**
     * Restores the active preset's parameters to the values captured in its most recent
     * [PresetSnapshot], by replaying them as per-parameter writes.
     *
     * Fails with [TonexError.UnsupportedByFirmware] if the pedal's firmware lacks
     * per-parameter write support — this operation never falls back to a whole-state write to
     * compensate, because that fallback is itself the dangerous path this safety net exists to
     * avoid (S9b).
     */
    suspend fun revertActivePreset(): TonexResult<Unit>

    /**
     * Restores the pedal's three footswitch slot assignments (A/B/C) to the values captured at
     * handshake, before any write occurred this session (issue #36).
     *
     * `selectPreset`'s whole-state write inherently overwrites all three slot assignments as a
     * side effect of the "set preset" wire message (see [PedalState]) — this is the deliberate,
     * explicit, user-invoked undo for that. Available at any point during
     * [ConnectionState.Ready], not only at disconnect, and never triggered automatically: an
     * automatic restore on disconnect cannot be relied on if the app crashes or the cable is
     * pulled, so a deliberate, predictable action the user can invoke at any time is the more
     * honest affordance (issue #36's rationale).
     *
     * Requires [ConnectionState.Ready]; fails with [TonexError.ProtocolStateViolation] otherwise,
     * and with [TonexError.NoFootswitchSnapshotAvailable] if the three slot assignments were never
     * successfully captured this session — this never silently no-ops.
     */
    suspend fun restoreFootswitches(): TonexResult<Unit>
}
