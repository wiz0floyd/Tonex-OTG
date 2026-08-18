package dev.tonexotg.protocol

/**
 * An immutable capture of one preset's parameter values at a point in time — the write-safety
 * net for a pedal that has no save step and no undo.
 *
 * ## Why this exists
 * The ToneX One auto-saves every write; upstream's `USB_COMMAND_SAVE_PRESET` handler is
 * literally empty ("Tonex One uses auto save, nothing needed"). There is no save step and no
 * undo — every parameter edit permanently alters the preset the instant it's sent. On a live
 * rig that is a real hazard (NFR1). The agreed mitigation (S9b) is: snapshot a preset's values
 * the moment it becomes active, and support reverting to that snapshot by replaying it as
 * per-parameter writes — **never** as a whole-state write (see [PedalState] for why whole-state
 * writes are the dangerous path).
 *
 * ## Scope and immutability
 * A snapshot covers exactly the 109 [ParameterScope.PRESET]-scoped parameters
 * ([PARAMETER_COUNT]) — globals are not part of a preset and are not captured here. The
 * constructor takes a defensive copy of the values it's given, and exposes them only through
 * [valueOf]/[toMap], both of which return values or copies rather than the backing array — a
 * later edit to the array a caller originally passed in (or to a map/array this type hands
 * back) cannot retroactively corrupt the retained snapshot. This matters because reverting is
 * only trustworthy if the thing being reverted to cannot itself have drifted.
 *
 * Like [PedalState], construction is confined to `:protocol` and stamped with a [SessionId]:
 * a snapshot only makes sense against the live pedal state of the session that captured it, and
 * reverting across a session boundary (e.g. after a reconnect) is not a supported operation —
 * S9b requires re-snapshotting whenever the active preset changes, including externally at the
 * footswitch (FR6), precisely so a stale snapshot is never mistaken for a current one.
 *
 * @property presetIndex which onboard preset this snapshot was taken of.
 * @property sessionId the session during which this snapshot was captured.
 */
class PresetSnapshot internal constructor(
    val presetIndex: PresetIndex,
    val sessionId: SessionId,
    parameterValues: FloatArray,
) {
    init {
        require(parameterValues.size == PARAMETER_COUNT) {
            "PresetSnapshot requires exactly $PARAMETER_COUNT values, got ${parameterValues.size}"
        }
    }

    private val values: FloatArray = parameterValues.copyOf()

    /**
     * This snapshot's value for [id], in the same engineering units as the corresponding
     * [ParameterSpec.min]/[ParameterSpec.max].
     *
     * **Indexing convention (load-bearing):** `values[n]` is the value of `ParameterId(n)` —
     * position *is* identity. This is forced by upstream's `param_ptr[loop]` positional layout
     * (see [dev.tonexotg.protocol.message.PresetParameterExtractor]'s KDoc) and by
     * [ParameterId]'s own "index is positional and load-bearing" contract. Because
     * [ParameterId.PRESET_RANGE] is exactly `0..108` and [PARAMETER_COUNT] is exactly 109,
     * `id.index` *is* the array index directly — no offset table, no registry lookup.
     *
     * @throws IllegalArgumentException if [id] is not [ParameterScope.PRESET]-scoped, or is not
     *   present in this snapshot.
     */
    fun valueOf(id: ParameterId): Float {
        require(id.index in ParameterId.PRESET_RANGE) {
            "PresetSnapshot covers only the $PARAMETER_COUNT PRESET-scoped parameters " +
                "(${ParameterId.PRESET_RANGE}); $id is not one of them"
        }
        return values[id.index]
    }

    /** This snapshot's values as an id-keyed map; a fresh copy (a new `Map` every call), safe to mutate. */
    fun toMap(): Map<ParameterId, Float> =
        ParameterId.PRESET_RANGE.associate { ParameterId(it) to values[it] }

    companion object {
        /** The number of per-preset parameters every snapshot must contain — asserted, not sampled. */
        const val PARAMETER_COUNT: Int = 109
    }
}

/**
 * Holds the most recently captured [PresetSnapshot] for each preset touched this session, and
 * the single-fire signal that guards a session's first destructive write.
 *
 * This is intentionally storage-only: it does not itself talk to the pedal. Capturing a
 * snapshot requires reading live values off the wire (the read-only `request_preset_details`
 * path, S7/S9b — never a mutating read, per S8's invariant against mutating state just to
 * observe it), and reverting requires issuing per-parameter writes; both belong to
 * [TonexController], which uses a `SnapshotStore` as its bookkeeping rather than embedding that
 * bookkeeping itself.
 *
 * Session-scoped and in-memory only by design: a snapshot is only meaningful against the live
 * state of the session that captured it (see [PresetSnapshot]), so there is deliberately no
 * persistence API here.
 */
interface SnapshotStore {

    /** The most recently recorded snapshot for [presetIndex], or `null` if none has been captured. */
    fun snapshotFor(presetIndex: PresetIndex): PresetSnapshot?

    /**
     * Records [snapshot], replacing any prior snapshot for the same [PresetSnapshot.presetIndex].
     *
     * This method itself is unconditional — it always replaces. The retention *policy* of when to
     * call it is the caller's responsibility, not this store's:
     * [dev.tonexotg.protocol.connection.DefaultTonexController] calls it first-arrival-wins (issue
     * #46) — once per preset per session, guarded by `snapshotFor(index) == null` at the call site
     * — precisely so an already-snapshotted preset's original values survive later edits, external
     * changes, and re-arrivals (including returning to a preset after an aborted/partial revert;
     * see [TonexError.ActivePresetChangedDuringRevert]'s KDoc). A stale-forever snapshot is the
     * accepted tradeoff of that policy — see issue #46 for the alternatives considered. Do not call
     * this unconditionally on every active-preset change; that was S9b's original (issue #14)
     * behavior and is exactly the policy issue #46 replaced.
     */
    fun record(snapshot: PresetSnapshot)

    /**
     * `true` once a destructive write (a parameter write against a snapshotted preset) has
     * happened at least once this session. Backs the one-per-session "you are about to make an
     * unrecoverable change" signal (S9b); presentation of that signal is a UI concern (D3), but
     * the underlying fact — has this already been shown once — is tracked here so it is not
     * re-derived (and potentially mis-derived) in more than one place.
     */
    fun hasWarnedThisSession(): Boolean

    /** Marks the first-destructive-write warning as shown for this session. Idempotent. */
    fun markWarned()

    /** Discards all retained snapshots and the warned flag, e.g. on disconnect. */
    fun clear()
}
