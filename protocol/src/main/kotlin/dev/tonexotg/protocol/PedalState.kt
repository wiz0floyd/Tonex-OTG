package dev.tonexotg.protocol

/**
 * An unforgeable token identifying one connected pedal session.
 *
 * Minted exactly once per successful handshake (by the connection state machine, S9, on
 * reaching [ConnectionState.Ready]) and held for the lifetime of that connection. A new
 * connection — even to the exact same physical pedal moments later — gets a new, distinct
 * `SessionId`.
 *
 * The constructor is `internal`: nothing outside the `:protocol` module can create a
 * `SessionId`. That is what turns [PedalState]'s session provenance from a convention into a
 * structural guarantee — see [PedalState] for why this matters. Deliberately not a `data class`:
 * identity equality (two instances are equal only if they are the same instance) means a
 * `SessionId` cannot be forged by constructing another one with "the same" contents, because it
 * has no contents to copy.
 */
class SessionId internal constructor()

/**
 * A wrapper around one read of the pedal's opaque, whole-device state blob, carrying proof of
 * *when and from where* it was read.
 *
 * ## Why this type exists
 * `Builty/TonexOneController` v1.0.0.2 shipped 20 hardcoded state blobs captured via Wireshark
 * from the author's own pedal, and replayed them byte-for-byte to "select a preset." The
 * "set preset" message is not a preset-select command — it is a **whole-device-state write**
 * carrying every global the pedal has (input trim, stomp/AB mode, cab-sim bypass, tuning mode,
 * the preset colour table, BPM, tempo source, direct monitor, tuning reference, current slot,
 * bypass mode, all three slot assignments). Replaying a captured blob overwrote the receiving
 * user's entire global configuration with the capture rig's. The upstream fix was
 * read-modify-write: read *live* state, patch only the intended bytes, echo everything else
 * back untouched (see S8, which owns that patching logic — this type stays deliberately opaque
 * to it, exposing only [copyOfBytes] and [size]).
 *
 * ## The guarantee this type makes
 * A `PedalState` can **only** be constructed from inside `:protocol`, by code that has actually
 * read bytes off the wire for the [sessionId] it stamps them with — there is no public
 * constructor, no builder, and no way to synthesize one from scratch or from a value hardcoded
 * elsewhere (e.g. a Wireshark capture pasted into source, or a blob replayed from a different
 * pedal or an earlier session). That structurally rules out the upstream bug at the type level:
 * code outside this module cannot produce a `PedalState` to write no matter what it does.
 *
 * Within `:protocol`, a second layer catches a *stale* blob — one that was genuinely read from
 * the wire, but during a session that has since ended: any write path must compare
 * [sessionId] against the connection's *current* [SessionId] and refuse with
 * [TonexError.StaleSessionState] on mismatch. Because [SessionId] has identity equality and no
 * public constructor anywhere, that comparison cannot be spoofed by constructing a
 * matching-looking id.
 *
 * @property sessionId the session this blob was read during. See [SessionId].
 */
class PedalState internal constructor(
    val sessionId: SessionId,
    bytes: ByteArray,
) {
    init {
        require(bytes.size <= MAX_STATE_BYTES) {
            "PedalState blob is ${bytes.size} bytes, exceeds MAX_STATE_BYTES ($MAX_STATE_BYTES)"
        }
    }

    private val bytes: ByteArray = bytes.copyOf()

    /** The blob's length in bytes. Must never exceed [MAX_STATE_BYTES]. */
    val size: Int get() = bytes.size

    /**
     * A defensive copy of the raw blob bytes.
     *
     * Deliberately opaque beyond this: no offset accessors live here. Interpreting or patching
     * specific bytes (slot assignments, current slot, bypass mode, ...) is S8's job, at
     * explicitly named, firmware-version-pinned offsets — this type does not know or care what
     * the bytes mean.
     */
    fun copyOfBytes(): ByteArray = bytes.copyOf()

    companion object {
        /** The pedal's maximum state blob size (`MAX_STATE_DATA` upstream), in bytes. */
        const val MAX_STATE_BYTES: Int = 512
    }
}
