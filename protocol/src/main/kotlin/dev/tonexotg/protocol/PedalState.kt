package dev.tonexotg.protocol

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * An unforgeable token identifying one connected pedal session.
 *
 * Minted exactly once per successful handshake (by the connection state machine, S9, on
 * reaching [ConnectionState.Ready]) and held for the lifetime of that connection. A new
 * connection — even to the exact same physical pedal moments later — gets a new, distinct
 * `SessionId`.
 *
 * The constructor is `private`, with [SessionId.create] as the only way to obtain an instance
 * from inside `:protocol`. This is what turns [PedalState]'s session provenance from a
 * convention into a structural guarantee — see [PedalState] for why this matters. **Do not
 * "helpfully" add a public constructor, a public factory, or make this a `data class`** — see
 * the ⚠️ below for exactly why each of those would quietly defeat the guarantee this type exists
 * for.
 *
 * ## ⚠️ Why `private constructor` + an `internal` factory, not just `internal constructor`
 * An earlier version of this type used `internal constructor()`. That looked sufficient — and
 * *is* sufficient to stop other Kotlin modules at compile time — but Kotlin does not mangle
 * constructors, only `internal` **functions**. `javap` on the compiled class showed a plain
 * `public SessionId()`, callable directly from ordinary `javac`-compiled Java code outside this
 * module with no reflection at all (issue #12 review). `private constructor` compiles to a
 * genuinely `private` JVM constructor — not merely a Kotlin-compiler-enforced one — and
 * [SessionId.create] is an `internal` **function**, which *is* name-mangled, stopping an
 * *accidental* or ordinary Java call. That mangled name is not itself a hard runtime barrier
 * against a caller who goes looking for it — see the honest-scope note on [SessionId.create]
 * below (re-demonstrated with plain `javac`, zero reflection, in issue #12's round-3 review),
 * and the ⚠️ on [PedalState]'s own KDoc for why that residual reachability still does not reopen
 * the bug this type exists to prevent.
 *
 * ## Deliberately not a `data class`
 * Identity equality (two instances are equal only if they are the same instance) means a
 * `SessionId` cannot be forged by constructing another one with "the same" contents, because it
 * has no contents to copy. A `data class` would gain a generated `copy()` and structural
 * `equals`/`hashCode`, either of which would silently defeat the `!==` identity comparison
 * [dev.tonexotg.protocol.state.StateBlobPatcher] relies on for provenance.
 */
class SessionId private constructor() {

    private val generationCounter = AtomicLong(0L)

    /**
     * Mints and returns the next [ReadGeneration] for this session.
     *
     * Called automatically by [PedalState.create] every time `:protocol` obtains a fresh copy of
     * the pedal's state blob for this session — see the generation contract on [PedalState] for
     * the full explanation of what "fresh" must include.
     */
    internal fun mintReadGeneration(): ReadGeneration = ReadGeneration(generationCounter.incrementAndGet())

    /**
     * The most recently minted [ReadGeneration] for this session — i.e. how fresh the freshest
     * [PedalState] this connection has actually observed is. [dev.tonexotg.protocol.state.StateBlobPatcher]
     * compares an inbound state's [PedalState.readGeneration] against this value and refuses the
     * write on any mismatch; see [PedalState]'s generation contract.
     */
    internal fun latestReadGeneration(): ReadGeneration = ReadGeneration(generationCounter.get())

    /**
     * Atomically advances this session's generation counter past [generation], but only if
     * [generation] is still exactly the session's current counter value — i.e. only if nothing
     * else (a concurrent write, or a newer read superseding it) has already moved the counter
     * since [generation] was minted. Returns `true` if the advance succeeded, `false` if a race
     * meant [generation] was no longer current by the time this ran.
     *
     * This is what turns a [PedalState] into a **single-use** write authorization rather than
     * merely "the freshest read this session has ever produced": [dev.tonexotg.protocol.state.StateBlobPatcher]
     * calls this as the last step of every successful patch (see `prepareForPatch`), so the exact
     * [PedalState] just spent can never be spent again — a second patch attempt reusing it finds
     * [latestReadGeneration] has moved past [PedalState.readGeneration] and fails the freshness
     * check, even though nothing about the blob's *contents* changed between the two calls. Closes
     * the blocker where calling a patch function twice against the same read (no re-read in
     * between) let the second call silently revert the first (issue #12 round-3 review).
     *
     * On success, also records [generation] as [lastSpentGeneration] — see that property for why.
     */
    internal fun consumeReadGeneration(generation: ReadGeneration): Boolean {
        val advanced = generationCounter.compareAndSet(generation.value, generation.value + 1)
        if (advanced) {
            lastSpentGenerationRef.set(generation.value)
        }
        return advanced
    }

    private val lastSpentGenerationRef = AtomicLong(NEVER_SPENT)

    /**
     * `true` iff [generation] is the *most recently* consumed-by-a-successful-write generation for
     * this session — i.e. [consumeReadGeneration] most recently succeeded with exactly this value.
     *
     * Exists so [dev.tonexotg.protocol.state.StateBlobPatcher]'s stale-generation rejection can
     * report an accurate reason: without this, "the blob is no longer this session's current read"
     * is reported identically whether the blob was superseded by a newer read/failed observation,
     * or was itself already spent on a successful patch — a dedicated "already used for a write"
     * message existed but was unreachable in practice, because the ordinary freshness check (same
     * numeric outcome either way: the counter has simply moved past [generation]) always caught
     * reuse first with the generic wording (issue #12 round-4 review, LOW finding #1).
     *
     * ## Honest scope
     * This tracks only the single *most recently* spent generation, not the full history of every
     * generation ever spent. If a session spends generation 1, then reads and spends generation 2,
     * a later attempt to reuse generation 1 reports as "superseded" rather than "already spent" —
     * which is a defensible simplification, not a wrong answer: by the time that reuse is
     * attempted, generation 2 genuinely *is* a later read that supersedes generation 1, regardless
     * of whether generation 1 was also independently spent. The case this exists to fix — an
     * immediate second patch attempt reusing the read that was *just* spent, with nothing else
     * having happened in between — is exactly the case this correctly distinguishes.
     */
    internal fun wasMostRecentlySpentByWrite(generation: ReadGeneration): Boolean =
        lastSpentGenerationRef.get() == generation.value

    private val pinnedBlobSizeRef = AtomicInteger(UNPINNED)

    /**
     * Pins (or widens) this session's expected state-blob length to [size].
     *
     * The pin tracks the **largest** plausible size observed so far, not merely the first:
     * - If nothing is pinned yet, [size] becomes the pin.
     * - If [size] is larger than the current pin, the pin widens to [size]. Growth is treated as
     *   evidence that a *smaller* previous pin was itself established by a corrupt or truncated
     *   read, not evidence that the pedal's real blob shrank and then grew back — the pedal's
     *   actual state-blob length does not change during a connection, so the largest plausible
     *   size ever observed is the best available estimate of the true length.
     * - If [size] is smaller than or equal to the current pin, this is a no-op: a **shrink** is
     *   the layout-drift signal [TonexError.BlobSizeChangedSinceHandshake] exists to catch, so it
     *   must not silently move the pin down — [dev.tonexotg.protocol.state.StateBlobPatcher]
     *   compares against the (unmoved) pin and rejects the shrink.
     *
     * Fixes a narrower bug in the previous "pin on first plausible read only" version: pinning is
     * one-way only in the "resistant to shrink" sense now, but it is no longer a one-way *lock* to
     * whatever value happened to be pinned first — a corrupt-but-plausible-sized read (e.g. a
     * partial reassembly landing anywhere in `[MIN_PLAUSIBLE_BLOB_SIZE, MAX_STATE_BYTES)`, not
     * just below the floor) used to pin permanently and then reject-forever every subsequent
     * *legitimate*, larger read for the rest of the connection's life, recoverable only by a
     * reconnect (issue #12 round-4 review, HIGH finding). [PedalState.create] only calls this for
     * a [size] that is at least [MIN_PLAUSIBLE_BLOB_SIZE] at all — see that constant.
     *
     * The pin is also **not** literally "the handshake blob's size": a `SessionId` is minted only
     * once [ConnectionState.Ready] is reached (see this class's KDoc), and `Ready` is reached only
     * *after* the handshake's `GetState` blob has already been read — so no [PedalState], and
     * therefore no pin, can ever be anchored to that specific read; the true first-pinned blob is
     * necessarily some plausible-sized read observed strictly after [ConnectionState.Ready]. See
     * [TonexError.BlobSizeChangedSinceHandshake]'s KDoc, which states this precisely rather than
     * claiming to be about "the blob seen when this session connected."
     */
    internal fun pinOrWidenBlobSize(size: Int) {
        while (true) {
            val current = pinnedBlobSizeRef.get()
            if (current != UNPINNED && size <= current) return
            if (pinnedBlobSizeRef.compareAndSet(current, size)) return
        }
    }

    /**
     * The blob length pinned (or widened) by [pinOrWidenBlobSize], or `null` if no
     * *plausible-sized* [PedalState] has been built for this session yet (a too-short first read
     * does not pin — see [pinOrWidenBlobSize]).
     */
    internal fun pinnedBlobSize(): Int? = pinnedBlobSizeRef.get().takeIf { it != UNPINNED }

    companion object {
        /** Sentinel meaning "no size pinned yet" — a real blob length is never negative. */
        private const val UNPINNED: Int = -1

        /**
         * Sentinel meaning "no generation has been spent by a successful write yet" —
         * [ReadGeneration] values start at `1` ([mintReadGeneration] is `incrementAndGet()` from
         * `0`), so `0` never collides with a real generation.
         */
        private const val NEVER_SPENT: Long = 0L

        /**
         * The smallest a *real* pedal state blob can plausibly be — see
         * [dev.tonexotg.protocol.state.StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE]'s KDoc for the
         * full field-layout derivation (colour-table size, indexed tail, etc.); that object
         * defines its own constant in terms of this one, not the other way around.
         *
         * Owned here — by [SessionId], the type that actually enforces it via
         * [pinOrWidenBlobSize] — rather than in `dev.tonexotg.protocol.state.StateBlobOffsets`,
         * purely to avoid a circular package dependency: [PedalState.create] needs this floor, and
         * `dev.tonexotg.protocol.state` already imports from `dev.tonexotg.protocol` (e.g.
         * [PedalState] itself), so importing the other way around here would both create the cycle
         * and mean [PedalState] — which documents itself elsewhere as deliberately opaque to the
         * patching layer's field-layout knowledge — ends up consulting a patcher-layer constant
         * directly (issue #12 round-4 review, LOW finding #2).
         */
        internal const val MIN_PLAUSIBLE_BLOB_SIZE: Int = 100

        /**
         * The only way to obtain a [SessionId]. Intended caller: the connection state machine
         * (S9), exactly once per successful handshake.
         *
         * Honest scope of what `internal` buys here: this is a name-mangled JVM method (its
         * compiled name has a module-derived suffix, e.g. `create$protocol`), which stops
         * ordinary/accidental use from Java and from other Gradle modules at compile time — but
         * unlike a `private` constructor, the mangled name is still a legal, guessable-if-you-
         * know-the-module-name Java identifier, so it is not a hard runtime barrier the way
         * [SessionId]'s constructor being genuinely `private` is. The property that actually
         * matters is unaffected either way: [TonexController] never hands a caller outside
         * `:protocol` a [SessionId] or [PedalState] to begin with, so there is nothing for even a
         * caller who found this method to do anything useful with.
         */
        internal fun create(): SessionId = SessionId()
    }
}

/**
 * An opaque, monotonically increasing marker of *which read* of the pedal's state a [PedalState]
 * came from, scoped to one [SessionId].
 *
 * ## Why this exists — the bug a [SessionId] check alone does not catch
 * [SessionId] proves a [PedalState] was read during *this connection* at all; it says nothing
 * about *when* during that (possibly hours-long) connection. A connection's `SessionId` is
 * minted once and held for the connection's entire life, so comparing only `state.sessionId` to
 * the current session lets a [PedalState] read at, say, 20:00 pass a provenance check performed
 * at 20:31 just as happily as one read a second ago — even though the pedal's actual global
 * state may have changed in between (e.g. the user tapped tempo at the footswitch at 20:30,
 * changing BPM). Patching that stale blob and echoing it back would silently revert every byte
 * this module doesn't touch, including the BPM the user just set — the same failure class as the
 * bug this project exists to not repeat (see [PedalState]'s "why this type exists"), just with a
 * 31-minute-old capture instead of a shipped one. Session is the wrong granularity for
 * freshness; `ReadGeneration` is the per-read granularity that closes that gap.
 *
 * `internal constructor` (not `private` + factory like [SessionId]/[PedalState]): a
 * `ReadGeneration`'s value carries no security-relevant secret and is not itself a forgeable
 * write authorization — [PedalState.readGeneration] is only ever compared for equality against
 * [SessionId.latestReadGeneration], which only [SessionId] itself can advance, so a forged
 * `ReadGeneration` value is merely wrong, never a way to smuggle a stale write past the check
 * without also forging a matching [PedalState] and [SessionId] (already ruled out — see those
 * types).
 *
 * @property value the raw generation counter value. Exposed for logging/debugging only — never
 *   compare this directly; compare whole [ReadGeneration] instances (or go through
 *   [dev.tonexotg.protocol.state.StateBlobPatcher], which does this for you).
 */
@JvmInline
value class ReadGeneration internal constructor(val value: Long)

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
 * ## The guarantee this type makes — and its honest limits
 * A `PedalState` cannot be constructed by ordinary code outside `:protocol`, and cannot be
 * synthesized from scratch or from a value hardcoded elsewhere (e.g. a Wireshark capture pasted
 * into source, or a blob replayed from a different pedal or an earlier session) by any code
 * anywhere. The constructor is `private` — a genuine JVM-level `private`, defeatable only by
 * reflection, never by plain `javac`-compiled Java source with no reflection at all (see
 * [SessionId]'s KDoc for exactly why `private constructor` was chosen over `internal
 * constructor`, which does *not* have this property). [PedalState.create] is the only *intended*
 * way in, even from other code in this module.
 *
 * **What this does not claim**: [create] itself is an `internal` **function**, and Kotlin
 * compiles `internal` to a `public`, name-mangled JVM method (e.g. `create$protocol`) — mangled
 * using `$`, which is a legal Java identifier character. A caller outside `:protocol` who goes
 * looking for that exact mangled name can spell `SessionId.Companion.create$protocol()` and
 * `PedalState.Companion.create$protocol(...)` directly from plain Java source, with **zero**
 * reflection — re-demonstrated in issue #12's round-3 review. This does *not* reopen the upstream
 * bug in practice: nothing on `:protocol`'s public surface (see [TonexController]) ever hands a
 * caller outside this module a [SessionId] or a [PedalState] to build on in the first place, so
 * there is no legitimate caller with anything to gain by finding this path — but it is a
 * "nothing to reach it with," not a "cannot be reached," and this KDoc previously overclaimed the
 * latter. Do not repeat that overclaim if this section is edited again.
 *
 * Within `:protocol`, two further layers catch a blob that *was* genuinely read from the wire
 * but is no longer trustworthy to write back:
 *
 * 1. **Stale session** — [sessionId] is from a session that has since ended. Any write path must
 *    compare [sessionId] against the connection's *current* [SessionId] and refuse with
 *    [TonexError.StaleSessionState] on mismatch. Because [SessionId] has identity equality and no
 *    public constructor anywhere, that comparison cannot be spoofed by constructing a
 *    matching-looking id.
 * 2. **Single-use within the same session** — [sessionId] matches, but [readGeneration] is not
 *    the session's *current* generation. This is a distinct, finer-grained check from (1), both
 *    must pass, and — since issue #12's round-3 review — this check is not just "was this ever
 *    the most recent read," it is "has this exact [PedalState] not already been spent on a
 *    successful patch": a successful patch consumes [readGeneration] as part of its own success
 *    (see [dev.tonexotg.protocol.state.StateBlobPatcher]'s `prepareForPatch`), so reusing the
 *    same [PedalState] for a second write — even with no intervening read — fails this check too.
 *
 * ## ⚠️ [readGeneration] — a contract S9 (issue #13) MUST honour
 * [readGeneration] is stamped automatically by [create] from [SessionId.mintReadGeneration], so
 * every `PedalState` this module ever builds is self-numbering — **but only if every fresh
 * observation of pedal state goes through [create] at all.** Concretely, S9 must call [create]
 * (and therefore mint a new generation):
 * - on every explicit read of pedal state, including a deliberate re-read immediately before a
 *   patch (S9's write path **must** re-read state immediately before every call into
 *   [dev.tonexotg.protocol.state.StateBlobPatcher] — do not reuse a [PedalState] retained from
 *   an earlier point in the connection);
 * - on **every** unsolicited state push from the pedal — this pedal has no host UI, and changes
 *   made directly at the footswitch (FR6) arrive as the pedal pushing its own updated state
 *   blob. If S9's push-handling path does anything other than build a fresh `PedalState` (via
 *   [create]) from that pushed blob, this whole freshness mechanism silently stops tracking
 *   reality the moment the user touches the pedal by hand, and the exact bug this story exists
 *   to prevent reappears at footswitch granularity instead of session granularity.
 *
 * @property sessionId the session this blob was read during. See [SessionId].
 * @property readGeneration this read's position in [sessionId]'s monotonic read sequence. See
 *   the freshness contract above and [ReadGeneration].
 */
class PedalState private constructor(
    val sessionId: SessionId,
    val readGeneration: ReadGeneration,
    bytes: ByteArray,
) {

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

        /**
         * The only way to obtain a [PedalState]. Takes a defensive copy of [bytes] so a caller
         * mutating its own array afterwards cannot retroactively corrupt the retained state. See
         * [SessionId.create]'s KDoc for the honest scope of what `internal` mangling does and
         * does not guarantee here — the hard guarantee is [PedalState]'s constructor being
         * genuinely `private`, not this factory's name being merely inconvenient to spell.
         *
         * Mints a fresh [ReadGeneration] for [sessionId] on **every call, success or failure** —
         * see the freshness contract in this class's KDoc, which S9 (issue #13) MUST honour for
         * every fresh observation of pedal state, not only explicit reads. The failure case
         * matters just as much as success: if this call fails (e.g. S9's mandatory pre-patch
         * re-read hits a timeout, a CRC failure, a malformed frame, or an oversized frame) but the
         * caller goes on to patch anyway using a [PedalState] it obtained from an earlier,
         * successful call, that earlier state must not still be fully write-authorized — otherwise
         * a *failed* observation reopens the exact stale-whole-device-echo bug this story exists to
         * prevent, just via a failed re-read instead of a stale one (issue #12 round-4 review,
         * MEDIUM finding). Minting unconditionally, before the size check below can return early,
         * is what closes that: [SessionId.latestReadGeneration] moves forward on this call
         * regardless of its outcome, so any [PedalState] the caller was previously holding for
         * [sessionId] now fails [dev.tonexotg.protocol.state.StateBlobPatcher]'s freshness check.
         *
         * @return [TonexResult.Success] with the new [PedalState], or
         *   [TonexResult.Failure]([TonexError.OversizedStateBlob]) if [bytes] is longer than
         *   [MAX_STATE_BYTES] — never throws (this module's contract: every fallible operation
         *   returns a [TonexResult], see [TonexError]'s top-level KDoc).
         */
        internal fun create(sessionId: SessionId, bytes: ByteArray): TonexResult<PedalState> {
            // Minted unconditionally, before the oversized check can return early - see the KDoc
            // paragraph above for why a failed attempt must advance the generation too.
            val generation = sessionId.mintReadGeneration()

            if (bytes.size > MAX_STATE_BYTES) {
                return TonexResult.Failure(
                    TonexError.OversizedStateBlob(maxSize = MAX_STATE_BYTES, actualSize = bytes.size),
                )
            }
            // Only a plausible-sized read pins (or widens) the session's expected blob length -
            // see pinOrWidenBlobSize's KDoc for both why a too-short read must not pin at all, and
            // why a larger later read widens the pin rather than being rejected against it.
            if (bytes.size >= SessionId.MIN_PLAUSIBLE_BLOB_SIZE) {
                sessionId.pinOrWidenBlobSize(bytes.size)
            }
            return TonexResult.Success(PedalState(sessionId, generation, bytes))
        }
    }
}
