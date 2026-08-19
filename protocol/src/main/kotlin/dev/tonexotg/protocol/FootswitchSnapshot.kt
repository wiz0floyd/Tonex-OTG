package dev.tonexotg.protocol

/**
 * An immutable capture of the pedal's three footswitch slot assignments (A/B/C), taken once per
 * session at handshake — before any write has occurred.
 *
 * ## Why this exists
 * `selectPreset`'s whole-state write is inherent to the "set preset" wire message (see
 * [PedalState]): every call overwrites the pedal's slot A/B/C assignments as a side effect,
 * because that message carries the entire device state, not just "which preset is active." A
 * user who has A/B/C configured for a set loses that configuration simply by browsing presets in
 * the app — the app's headline feature (O1: browse all 20 presets past the 2-3 slot limit) is
 * destructive to the footswitch configuration nothing else captures. Issue #36's agreed
 * mitigation: capture the three slot assignments once, at handshake, before any write can
 * possibly have touched them, and expose an explicit, user-invoked restore
 * ([TonexController.restoreFootswitches]) — never an automatic restore on disconnect, since that
 * cannot be relied on if the app crashes or the cable is pulled (issue #36's rationale).
 *
 * ## Scope and immutability
 * Covers exactly the three [PresetSlot] assignments — nothing else in the state blob. The
 * constructor requires an entry for every [PresetSlot] and takes a defensive copy of the map it's
 * given; [toMap] and [presetFor] only ever hand back values or copies, never the backing map
 * itself, so a caller mutating what they get back cannot retroactively corrupt the retained
 * snapshot. This mirrors [PresetSnapshot]'s own immutability discipline and the same rationale:
 * a restore is only trustworthy if what it restores *to* cannot itself have drifted.
 *
 * Like [PresetSnapshot], construction is confined to `:protocol` and stamped with a [SessionId]:
 * a snapshot only makes sense against the live pedal session that captured it, and restoring
 * across a session boundary (e.g. after a reconnect) is not a supported operation — a fresh
 * connection captures its own snapshot at its own handshake.
 *
 * @property sessionId the session during which this snapshot was captured.
 */
class FootswitchSnapshot internal constructor(
    val sessionId: SessionId,
    assignments: Map<PresetSlot, PresetIndex>,
) {
    init {
        require(assignments.keys == REQUIRED_SLOTS) {
            "FootswitchSnapshot requires an assignment for every slot ($REQUIRED_SLOTS), got ${assignments.keys}"
        }
    }

    private val assignments: Map<PresetSlot, PresetIndex> = assignments.toMap()

    /** This snapshot's captured preset assignment for [slot]. */
    fun presetFor(slot: PresetSlot): PresetIndex = assignments.getValue(slot)

    /** This snapshot's assignments as a slot-keyed map; a fresh copy (a new `Map` every call), safe to mutate. */
    fun toMap(): Map<PresetSlot, PresetIndex> = assignments.toMap()

    private companion object {
        val REQUIRED_SLOTS: Set<PresetSlot> = PresetSlot.entries.toSet()
    }
}
