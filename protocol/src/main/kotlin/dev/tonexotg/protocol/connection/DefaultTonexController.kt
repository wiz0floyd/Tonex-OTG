package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.FootswitchSnapshot
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.ParameterScope
import dev.tonexotg.protocol.PedalState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.PresetSnapshot
import dev.tonexotg.protocol.SessionId
import dev.tonexotg.protocol.SnapshotStore
import dev.tonexotg.protocol.TonexController
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexEvent
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.TonexTransport
import dev.tonexotg.protocol.codec.MessageHeaderCodec
import dev.tonexotg.protocol.framing.HdlcFrame
import dev.tonexotg.protocol.framing.decodeFrames
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.message.HelloMessage
import dev.tonexotg.protocol.message.MasterVolumeMessage
import dev.tonexotg.protocol.message.ParameterWriteMessage
import dev.tonexotg.protocol.message.PresetDetailsKind
import dev.tonexotg.protocol.message.PresetNameExtractor
import dev.tonexotg.protocol.message.PresetParameterExtractor
import dev.tonexotg.protocol.message.RequestMasterVolumeMessage
import dev.tonexotg.protocol.message.RequestPresetDetailsMessage
import dev.tonexotg.protocol.message.RequestStateMessage
import dev.tonexotg.protocol.message.SetStateMessage
import dev.tonexotg.protocol.message.SingleParameterPayloadCodec
import dev.tonexotg.protocol.message.TonexMessage
import dev.tonexotg.protocol.message.TonexMessageDecoder
import dev.tonexotg.protocol.params.EffectiveParameterBounds
import dev.tonexotg.protocol.params.ParameterRegistry
import dev.tonexotg.protocol.params.SelfWideningParameterBounds
import dev.tonexotg.protocol.state.StateBlobPatcher
import dev.tonexotg.protocol.state.StateBlobReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The connection state machine: [TonexController]'s only implementation, and the sole owner of
 * [SessionId]/[PedalState] lifecycle for one connection.
 *
 * See `S9_ARCHITECTURE_PLAN.md` (issue #13) for the complete design rationale this class
 * implements — every non-obvious decision below (why there is no cached [PedalState] field, the
 * freshness-contract call sites, the `Error`-vs-`Idle` routing rule, `selfInitiatedPreset`'s
 * ordering) is explained there in more depth than fits in this file's KDoc, and is preserved as a
 * comment on issue #13 for posterity.
 *
 * ## `scope` is injected, not owned
 *
 * The inbound reader must outlive [connect] (which returns once [ConnectionState.Ready] is
 * reached and the post-`Ready` harvest completes, while unsolicited pushes keep arriving
 * afterwards). An internally-created `CoroutineScope(Dispatchers.Default)` would defeat
 * `runTest`'s virtual time and make timeout tests actually wait in real time. Tests pass
 * `backgroundScope`; `:app` passes a service/view-model scope.
 *
 * ## `capabilities` has no default value
 *
 * This mirrors [FirmwareCapabilities]'s own doctrine: *"there is deliberately no 'assume
 * supported' constructor or default value"*. S9 does not probe firmware (a later story does); the
 * caller supplies what it knows, [FirmwareCapabilities.NONE_CONFIRMED] being the honest starting
 * point.
 *
 * ## ⚠️ There is deliberately **no** `latestState: PedalState?` field
 *
 * This is the structural enforcement of [PedalState]'s freshness contract ("never reuse a cached
 * `PedalState` across user actions"). The [PedalState] produced by [selectPreset]'s re-read flows
 * from the awaiter straight into [StateBlobPatcher] as a local inside one function body and is
 * never stored on this object. **Do not add such a field, for any reason, including "to avoid a
 * redundant re-read."**
 */
class DefaultTonexController(
    private val scope: CoroutineScope,
    private val capabilities: FirmwareCapabilities,
    private val snapshotStore: SnapshotStore = InMemorySnapshotStore(),
    private val timeouts: ConnectionTimeouts = ConnectionTimeouts.DEFAULT,
    /**
     * The effective-bounds source (issue #80) for [ParameterRegistry.SELF_WIDENING_PARAMETER_IDS]
     * — consulted by [writeParameterLocked]'s reject-if-out-of-range check,
     * [revertActivePreset]'s snapshot pre-validation, and threaded into
     * [dev.tonexotg.protocol.message.ParameterWriteMessage.encode] so a self-widened value isn't
     * clamped back down right before it reaches the wire. `:app` constructs and owns the
     * long-lived [SelfWideningParameterBounds] instance (seeded from its DataStore-persisted
     * observations) and passes it in here; this class only ever reads from it via
     * [EffectiveParameterBounds.effectiveMax] and writes to it via
     * [SelfWideningParameterBounds.observeRead] — it never persists anything itself, keeping
     * `:protocol` Android-free (issue #15).
     */
    private val effectiveBounds: SelfWideningParameterBounds = SelfWideningParameterBounds(),
) : TonexController {

    // ---- state -----------------------------------------------------------------------------

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _activePreset = MutableStateFlow<PresetIndex?>(null)
    private val _presets = MutableStateFlow<List<PresetInfo>>(emptyList())
    private val _slotAssignments = MutableStateFlow<Map<PresetSlot, PresetIndex>>(emptyMap())
    private val _parameterValues = MutableStateFlow<Map<ParameterId, Float>>(emptyMap())
    private val _events = MutableSharedFlow<TonexEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Rate-limits the ~100 Hz inbound `ParameterChanged` stream on its way into [_parameterValues]
     * (issue #104). Leading-edge: a one-off frame still applies synchronously — see
     * [InboundParameterThrottle]'s KDoc, which explains why that is load-bearing here.
     *
     * The `Ready` guard is a teardown race, not a niceness: [cancelAll] cancels the flush
     * coroutine, but a flush that has already copied its batch and released the monitor has no
     * suspension point left before `apply`, so cancellation cannot preempt it. Without this check
     * that in-flight flush could repopulate [_parameterValues] microseconds after [tearDown]
     * cleared it — the exact stale-session write the `cancelAll()` call there exists to prevent.
     */
    private val inboundParameterThrottle = InboundParameterThrottle(scope) { updates ->
        if (_connectionState.value is ConnectionState.Ready) {
            _parameterValues.update { it + updates }
        }
    }

    /**
     * Values this controller has written to the pedal and is still expecting to see echoed back,
     * keyed by the `(kind, index)` pair they were sent with.
     *
     * **The pedal echoes every successful single-parameter write back as an unsolicited
     * `0x0309 ParameterChanged` frame, ~3 ms later, with a byte-identical payload.** This is not a
     * response to a request — [writeParameterLocked] never awaits one — so before issue #104 those
     * echoes simply fell through the reader and were discarded by `applyParameterChanged`'s old
     * `index != 0` guard. Now that inbound frames are routed for real, they must be recognised for
     * what they are. Confirmed in this repo's own captured log,
     * `docs/hardware-probes/tonexprobe20260819_220308.log.txt:19313-19318` — a write of EQ_MID
     * (index `0x0D`) at value `41 20 00 00` (10.0f), and 3 ms later a READ carrying that same
     * `B9 04 02 00 0D 88 00 00 20 41` payload back; it repeats for every write in that drill.
     *
     * Letting an echo through would not merely be redundant, it would corrupt the display. A drag
     * conflates into writes v1 then v2; v2's own `parameterValues` update lands immediately, but
     * v1's echo can still be sitting in [inboundParameterThrottle]'s pending batch and would flush
     * *over* v2 up to one interval later — leaving the app showing a value the pedal is no longer
     * at, with nothing else coming to correct it.
     *
     * Matching is on the exact float that was sent, and each match consumes one entry, so a genuine
     * knob turn to some *other* value is never swallowed. The one accepted cost: if a user's own
     * physical knob happens to land on precisely the value the app last wrote, that single frame is
     * dropped — at ~100 frames a second, its neighbours still track the knob. Bounded at
     * [MAX_PENDING_ECHOES_PER_KEY] entries per key so a write path the pedal turns out not to echo
     * can leak at most that many stale values rather than growing without limit.
     */
    private val pendingEchoes = mutableMapOf<Pair<Int, Int>, ArrayDeque<Float>>()
    private val pendingEchoesLock = Any()

    /** Records that a `(kind, index, value)` write just went out and its echo should be ignored. */
    private fun recordExpectedEcho(kind: Int, index: Int, value: Float) {
        synchronized(pendingEchoesLock) {
            val queue = pendingEchoes.getOrPut(kind to index) { ArrayDeque() }
            if (queue.size >= MAX_PENDING_ECHOES_PER_KEY) queue.removeFirst()
            queue.addLast(value)
        }
    }

    /** True if this inbound frame is the echo of one of our own writes — consuming that expectation. */
    private fun consumeExpectedEcho(kind: Int, index: Int, value: Float): Boolean =
        synchronized(pendingEchoesLock) {
            val queue = pendingEchoes[kind to index] ?: return false
            val removed = queue.remove(value)
            if (queue.isEmpty()) pendingEchoes.remove(kind to index)
            removed
        }

    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val activePreset: StateFlow<PresetIndex?> = _activePreset.asStateFlow()
    override val presets: StateFlow<List<PresetInfo>> = _presets.asStateFlow()
    override val slotAssignments: StateFlow<Map<PresetSlot, PresetIndex>> = _slotAssignments.asStateFlow()
    override val parameterValues: StateFlow<Map<ParameterId, Float>> = _parameterValues.asStateFlow()
    override val events: SharedFlow<TonexEvent> = _events.asSharedFlow()

    /**
     * Every classified inbound observation, fed by the single reader loop (see [startReader]) and
     * consumed by [requestAndAwait]. `DROP_OLDEST` with `tryEmit` (never `emit`) so the reader
     * coroutine can **never** be blocked by a slow or absent awaiter — with zero subscribers,
     * `tryEmit` simply discards, which is exactly "nothing was waiting for this, drop it." The
     * trade (an awaiter could theoretically miss an item under an 8-deep burst) degrades to that
     * stage's timeout, a loud typed error. Reader liveness beats awaiter completeness.
     */
    private val inbound = MutableSharedFlow<Inbound>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Serializes [connect]/[selectPreset]/[setParameter]/[revertActivePreset] — discharges
     * [TonexTransport]'s stated requirement that "callers above this seam are responsible for
     * serializing writes." [disconnect] deliberately does NOT take this first — see its KDoc.
     */
    private val operationMutex = Mutex()

    /** Makes [teardown] idempotent across a racing [disconnect] / transport-detach / connect failure. */
    private val teardownDone = AtomicBoolean(true)

    @Volatile private var transport: TonexTransport? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var session: SessionId? = null
    @Volatile private var selfInitiatedPreset: PresetIndex? = null

    /**
     * The three footswitch slot assignments (A/B/C) as they were the moment this session reached
     * [ConnectionState.Ready] — captured once, in [connect], from the very state blob the
     * handshake already read, before any write this controller could issue has had a chance to
     * touch them. `null` until captured, and forever `null` for this session if that capture
     * failed (an implausibly-shaped handshake blob) — [restoreFootswitches] surfaces
     * [TonexError.NoFootswitchSnapshotAvailable] rather than treating either case as "nothing to
     * do." See issue #36.
     */
    @Volatile private var footswitchSnapshot: FootswitchSnapshot? = null

    // ---- the Inbound envelope (§6.3) --------------------------------------------------------

    private sealed interface Inbound {
        /**
         * A classified message. Used for Hello/PresetDetails/ParameterChanged at any stage, and
         * for StateUpdate *only before a [SessionId] exists* (i.e. the handshake GetState response).
         */
        data class Message(val message: TonexMessage) : Inbound

        /**
         * A StateUpdate observed while a session exists — carries the freshly minted [PedalState]
         * (or the [TonexError.OversizedStateBlob] failure, whose generation was still minted).
         */
        data class State(val result: TonexResult<PedalState>) : Inbound

        /**
         * A frame or header the framing/codec layer refused. The reader has ALREADY called
         * `session?.invalidateCurrentRead()` before emitting this.
         */
        data class FrameFailure(val error: TonexError) : Inbound

        /** `transport.incoming()` completed (`cause == null`) or threw. */
        data class TransportEnded(val cause: Throwable?) : Inbound

        /** [disconnect] was called; every awaiter must unblock. */
        data object Disconnected : Inbound
    }

    private class TransportEndedException : Exception("the transport's incoming byte stream ended")

    private class ShortWriteException(written: Int, expected: Int) :
        Exception("short write: wrote $written of $expected bytes")

    // ---- the reader loop — the single choke point (§6.4) ------------------------------------

    private fun startReader(t: TonexTransport): Job = scope.launch {
        try {
            t.incoming().decodeFrames().collect { frameResult ->
                when (frameResult) {
                    is TonexResult.Failure -> {
                        session?.invalidateCurrentRead() // CONTRACT call site 1 — see PedalState/SessionId KDoc
                        inbound.tryEmit(Inbound.FrameFailure(frameResult.error))
                    }
                    is TonexResult.Success -> when (val d = MessageHeaderCodec.decode(frameResult.value)) {
                        is TonexResult.Failure -> {
                            session?.invalidateCurrentRead() // CONTRACT call site 2
                            inbound.tryEmit(Inbound.FrameFailure(d.error))
                        }
                        is TonexResult.Success -> handleMessage(TonexMessageDecoder.decode(d.value))
                    }
                }
            }
            onTransportEnded(null)
        } catch (c: CancellationException) {
            throw c // cooperative cancel — not a failure
        } catch (e: Throwable) {
            onTransportEnded(e)
        }
    }

    /**
     * `TonexMessage.Other` is always dropped, never an error, at every stage — [dev.tonexotg.protocol.codec.MessageType]'s
     * KDoc mandates it: an uncatalogued wire ID must never be treated as a reason to drop the
     * connection.
     */
    private fun handleMessage(msg: TonexMessage) {
        when (msg) {
            is TonexMessage.Hello, is TonexMessage.PresetDetails -> inbound.tryEmit(Inbound.Message(msg))

            is TonexMessage.ParameterChanged -> {
                applyParameterChanged(msg)
                inbound.tryEmit(Inbound.Message(msg))
            }

            is TonexMessage.StateUpdate -> {
                val s = session
                if (s == null) {
                    inbound.tryEmit(Inbound.Message(msg)) // handshake GetState response
                } else {
                    val result = PedalState.create(s, msg.payload) // ← THE ONLY PedalState.create() CALL SITE
                    (result as? TonexResult.Success)?.let { applyStateUpdate(it.value) }
                    inbound.tryEmit(Inbound.State(result))
                }
            }

            is TonexMessage.Other -> Unit
        }
    }

    /**
     * Updates [activePreset] from an inbound [PedalState] and emits [TonexEvent.ExternalPresetChange]
     * when the active preset changed for a reason other than this controller's own [selectPreset]
     * call. An implausible push is dropped: the generation was already minted by [PedalState.create],
     * so nothing stale stays authorized even though this function does nothing further with it.
     *
     * ## S9b/S9c: re-read on every active-preset change, first-arrival-wins retention (issue #46)
     *
     * Whenever the active preset changes — self-initiated *or* external alike, per issue #14
     * ("re-snapshot whenever the active preset changes, including changes made externally") — the
     * outgoing preset's [parameterValues] entries are dropped synchronously (before anything can
     * observe them against the new active preset), and a fresh read of the incoming preset's live
     * values is launched. This sits **outside** the self/external `if`/`else` above, deliberately:
     * a self-initiated change equally invalidates the previous preset's displayed values.
     *
     * That launched read always refreshes [parameterValues] with what the pedal reports *right
     * now* — but per issue #46's resolved design question, it only **records** a new revert
     * snapshot in [snapshotStore] the first time this session that [idx] is arrived at;
     * [captureSnapshotLocked] itself carries that first-arrival-wins guard (see its KDoc), not this
     * function — do not duplicate the check here. The product-owner-decided policy (issue #46): a
     * preset's snapshot is captured once per preset per session, on first arrival only, so
     * `revertActivePreset()` means "undo everything I did to this preset this session," and
     * "reselect the preset and retry" is honest advice again after an aborted/partial revert
     * ([TonexError.ActivePresetChangedDuringRevert]). Accepted tradeoff: a snapshot can go stale
     * against changes this module can't see (another editor, a MIDI-driven change) made *after*
     * the first in-session capture — not a bug to work around, per issue #46's decision.
     *
     * The read is [scope].[launch]ed, never called inline: this function runs on the reader
     * coroutine, and [captureSnapshotLocked] suspends on a round trip through that same reader —
     * calling it inline would have the reader wait on itself, a guaranteed hang (and this function
     * is not even a `suspend fun`, so it would not compile that way). The launched coroutine may
     * then block on [operationMutex] while e.g. [selectPreset] still holds it — that is fine, the
     * *reader* never blocks, it has already returned from this function.
     *
     * `session !== s` mirrors [onTransportEnded]'s `endedReaderJob` guard (PR #43 finding 4):
     * without it, a queued read dispatched after a teardown/reconnect could run against a fresh
     * connection. [captureSnapshotLocked]'s own liveness/session checks are a second line of
     * defence; keep both.
     */
    private fun applyStateUpdate(state: PedalState) {
        // Decoded independently of the activePreset read below -- separate reads of the same
        // blob, neither gated on the other's success/failure. This is what makes both a
        // self-initiated assignPresetToSlot write and an external footswitch reassignment show
        // up live in slotAssignments, with zero extra plumbing.
        val slots = StateBlobReader.slotAssignments(state)
        if (slots is TonexResult.Success) _slotAssignments.value = slots.value

        // issue #83: keep the six GLOBAL-scope parameters' displayed values live on every state
        // push, the same way slotAssignments already is - independent of the activePreset read
        // below, and never gated on its success/failure.
        val globals = StateBlobReader.globalParameterValues(state)
        if (globals is TonexResult.Success) _parameterValues.update { it + globals.value }

        val read = StateBlobReader.activePreset(state)
        if (read is TonexResult.Failure) return
        val idx = (read as TonexResult.Success).value
        val previous = _activePreset.value
        _activePreset.value = idx
        if (previous != null && previous != idx) {
            if (selfInitiatedPreset == idx) {
                selfInitiatedPreset = null
            } else {
                _events.tryEmit(TonexEvent.ExternalPresetChange(idx))
            }
            // ---- S9b: the previous preset's snapshot is not valid for this one -------------------
            dropPresetScopedValues()
            val s = session
            scope.launch {
                if (session !== s) return@launch // superseded by a teardown/reconnect
                operationMutex.withLock { captureSnapshotLocked(idx) }
            }
        }
    }

    /**
     * Applies a `ParameterChanged` notification to [parameterValues] (issue #104).
     *
     * The pedal streams these unsolicited, at ~100 Hz, for as long as a physical knob is being
     * turned — that is how a knob turn on the hardware becomes a moving control in the app,
     * without polling, a request/response round trip, or a preset reselect. Confirmed against real
     * hardware in #106; see [SingleParameterPayloadCodec]'s KDoc for the frame layout that capture
     * settled.
     *
     * ## Routing
     * - **`index == 0` is master volume**, not `ParameterId(0)`. Upstream uses index 0 that way
     *   specifically (`usb_tonex_one.c:891`, `if (param_index == 0x00)`), and the value arrives in
     *   the pedal's native `0..10` range, so it is converted to the engineering `-40..3` dB the
     *   registry stores via [MasterVolumeMessage.nativeToDecibels] — reused, not reimplemented.
     * - **Any other valid index** ([ParameterId.PRESET_RANGE] / [ParameterId.GLOBAL_RANGE]) routes
     *   straight to that [ParameterId], value stored as received: #106 swept `MODEL_GAIN`
     *   (index 20) from 1.9 to 4.8, inside its registered `0f..10f`, so these are already in
     *   engineering units with no conversion needed.
     * - **Anything else is dropped, loudly** — a [TonexEvent.UnroutableParameterNotification]
     *   carrying the raw payload hex, never silently swallowed and never guessed onto a nearby
     *   parameter.
     *
     * That last branch is the point. #106 proves only the preset-scoped case; that master volume
     * and the GLOBAL-scope parameters (110..116) notify through this same index space is the
     * product owner's stated working assumption, not an observation. Routing generically and
     * failing loud is the deliberate alternative to blocking the whole story on another hardware
     * session — if the assumption is wrong, the next debug dump says so in raw hex.
     *
     * Values go through [inboundParameterThrottle] rather than into [_parameterValues] directly;
     * see that class for why the first frame of a burst still applies synchronously.
     */
    private fun applyParameterChanged(msg: TonexMessage.ParameterChanged) {
        val decoded = SingleParameterPayloadCodec.decode(msg.payload)
        if (decoded !is TonexResult.Success) return
        val p = decoded.value

        // Our own write coming back at us, not the pedal reporting a knob — see [pendingEchoes].
        if (consumeExpectedEcho(p.kind, p.index, p.value)) return

        // Master volume is index 0 *of kind 0x03 only*. Qualifying on `kind` is load-bearing:
        // ParameterId(0) is NOISE_GATE_POST, a switch this app renders, and its own notifications
        // arrive as index 0 of kind 0x02 (KIND_PARAMETER) - routing on the index alone would send
        // a noise-gate toggle to the master-volume slider as nativeToDecibels(1f) = -35.7 dB.
        // Confirmed in docs/hardware-probes/tonexprobe20260819_220308.log.txt:18936-18938, where
        // the pedal answers RequestMasterVolume with `B9 04 03 00 00 88 9A 99 A9 40` - kind 0x03,
        // index 0, 5.3 native. Upstream's own `if (param_index == 0x00)` sits inside a function
        // whose 3-byte marker had already filtered to kind 0x03; that context has to be kept.
        if (p.index == 0 && p.kind == SingleParameterPayloadCodec.KIND_MASTER_VOLUME) {
            val spec = ParameterRegistry.byEnumName("MASTER_VOLUME") ?: return
            applyInboundValue(spec.id, MasterVolumeMessage.nativeToDecibels(p.value), msg.payload)
            return
        }

        if (p.index in ParameterId.PRESET_RANGE || p.index in ParameterId.GLOBAL_RANGE) {
            applyInboundValue(ParameterId(p.index), p.value, msg.payload)
            return
        }

        _events.tryEmit(
            TonexEvent.UnroutableParameterNotification(p.index, msg.payload.toSpacedHex()),
        )
    }

    /**
     * Applies one routed inbound value, but only if it is actually in range for [id] — otherwise
     * reports it as unroutable and drops it.
     *
     * The bounds check is not defensive padding; it is what keeps a wrong assumption from becoming
     * a silently wrong number. `MASTER_VOLUME` is `ParameterId(116)`, which sits inside
     * [ParameterId.GLOBAL_RANGE], so there are two routes to that one id: index 0 (native `0..10`,
     * converted above) and index 116 (taken as-is). If the pedal ever uses the latter with native
     * units, an unconverted `5.0` would land in a slot the UI renders — and the home screen then
     * writes back — as dB. Validating against the registry's own range turns that from a plausible
     * -looking wrong value into a loud [TonexEvent.UnroutableParameterNotification]: `5.0` is
     * outside master volume's `-40..3`, so it is refused rather than displayed.
     *
     * Bounding against the *effective* max (issue #80), not the static one, so a legitimately
     * self-widened value the write path would accept is not rejected on the way back in.
     */
    private fun applyInboundValue(id: ParameterId, value: Float, rawPayload: ByteArray) {
        val spec = ParameterRegistry.byIndex(id.index)
        if (spec == null || value < spec.min || value > effectiveBounds.effectiveMax(id)) {
            _events.tryEmit(TonexEvent.UnroutableParameterNotification(id.index, rawPayload.toSpacedHex()))
            return
        }
        inboundParameterThrottle.submit(id, value)
    }

    // ---- awaiting a response (§6.5) ----------------------------------------------------------

    /**
     * Writes [request] (if non-null) and awaits the first [Inbound] item [onInbound] resolves to a
     * non-null [TonexResult], within [timeoutMillis]. `null` = "not for me, keep waiting."
     *
     * `onSubscription` is load-bearing and must not be replaced with "write, then collect":
     * [inbound] has `replay = 0`, so writing the request before subscribing opens a window where
     * the pedal's response is emitted into the void and the stage times out. `onSubscription` runs
     * after the subscription is registered, closing that window deterministically.
     *
     * A [writeFramed] failure inside `onSubscription` is stashed in [writeFailure] and reported
     * with priority over whatever the (self-emitted, [Inbound.Disconnected]-triggered) wakeup
     * otherwise produces — see the body below.
     */
    private suspend fun <T> requestAndAwait(
        request: ByteArray?,
        timeoutMillis: Long,
        operation: String,
        onInbound: (Inbound) -> TonexResult<T>?,
    ): TonexResult<T> {
        var writeFailure: TonexResult.Failure? = null
        val outcome = withTimeoutOrNull(timeoutMillis) {
            inbound
                .onSubscription {
                    if (request != null) {
                        val written = writeFramed(request)
                        if (written is TonexResult.Failure) {
                            writeFailure = written
                            // Wake this collector so we don't hang until the timeout; the real
                            // cause is reported from writeFailure below, not from whatever this
                            // produces via commonInbound.
                            inbound.tryEmit(Inbound.Disconnected)
                        }
                    }
                }
                .mapNotNull(onInbound)
                .first()
        }
        writeFailure?.let { return it }
        return outcome ?: run {
            session?.invalidateCurrentRead() // CONTRACT call site 3
            TonexResult.Failure(TonexError.Timeout(operation, timeoutMillis))
        }
    }

    /**
     * The four [Inbound] cases handled identically at every stage. `null` for anything stage-
     * specific, so callers chain `commonInbound(...) ?: when { ... }`.
     *
     * A [Inbound.FrameFailure] fails the in-flight operation immediately rather than waiting out
     * the timeout: a corrupted or undecodable frame *might have been* the response being waited
     * for, and we cannot know, because it is precisely the frame that could not be decoded.
     */
    private fun commonInbound(operation: String, inb: Inbound): TonexResult<Nothing>? = when (inb) {
        is Inbound.FrameFailure -> TonexResult.Failure(inb.error)
        is Inbound.TransportEnded -> TonexResult.Failure(
            TonexError.TransportFailure(inb.cause ?: TransportEndedException()),
        )
        Inbound.Disconnected -> TonexResult.Failure(
            TonexError.ProtocolStateViolation(ConnectionState.Idle, "$operation was cancelled by disconnect()"),
        )
        else -> null
    }

    // ---- writing (§6.6) ---------------------------------------------------------------------

    /**
     * Frames [messageBytes] and writes it to [transport], bounded by [ConnectionTimeouts.transportWriteMillis].
     * A short write is a hard failure — [TonexTransport.write]'s KDoc explicitly defers that
     * decision to callers above the seam; this module's decision is to fail rather than write a
     * partial frame's remainder (patch-and-hope).
     *
     * [TimeoutCancellationException] MUST be caught before [CancellationException] — the former
     * extends the latter, so catching [CancellationException] first would rethrow a timeout as a
     * cancellation and silently kill the operation.
     */
    private suspend fun writeFramed(messageBytes: ByteArray): TonexResult<Unit> {
        val t = transport ?: return TonexResult.Failure(
            TonexError.ProtocolStateViolation(_connectionState.value, "no transport is attached"),
        )
        val framed = HdlcFrame.encode(messageBytes)
        return try {
            val written = withTimeout(timeouts.transportWriteMillis) { t.write(framed) }
            if (written != framed.size) {
                TonexResult.Failure(TonexError.TransportFailure(ShortWriteException(written, framed.size)))
            } else {
                TonexResult.Success(Unit)
            }
        } catch (e: TimeoutCancellationException) {
            TonexResult.Failure(TonexError.Timeout("transport-write", timeouts.transportWriteMillis))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            TonexResult.Failure(TonexError.TransportFailure(e))
        }
    }

    // ---- TonexResult propagation helpers ----------------------------------------------------

    /**
     * Plain propagation: returns [TonexResult.Success.value], or invokes [onFailure] with no side
     * effect on connection state. Used by [selectPreset]/[setParameter]/[revertActivePreset]/
     * [harvestPresetNames]/[harvestMasterVolume], where a single operation's failure must not by
     * itself end the whole connection.
     */
    private inline fun <T> TonexResult<T>.orReturn(onFailure: (TonexResult.Failure) -> Nothing): T = when (this) {
        is TonexResult.Success -> value
        is TonexResult.Failure -> onFailure(this)
    }

    /**
     * [connect]-only: a failure at this point in the handshake sequence DOES end the connection —
     * tears down and routes to [ConnectionState.Error]/[ConnectionState.Idle] per [finalStateFor]
     * before propagating. Do not use this outside [connect]'s own stage sequence.
     */
    private suspend inline fun <T> TonexResult<T>.orFinish(onFailure: (TonexResult.Failure) -> Nothing): T =
        when (this) {
            is TonexResult.Success -> value
            is TonexResult.Failure -> {
                teardown(finalStateFor(error))
                onFailure(this)
            }
        }

    private suspend inline fun TonexResult<Unit>.onFailureFinish(onFailure: (TonexResult.Failure) -> Nothing) {
        orFinish(onFailure)
    }

    /**
     * The `Error`-vs-`Idle` routing rule (§6.8): `Error` is entered only for failures observed on
     * the inbound/protocol side. Every failure originating from a transport write, or from
     * `incoming()` ending/throwing, routes to `Idle` — a transport detach is "the user unplugged
     * the cable," not a protocol-level failure. A [TonexError.ProtocolStateViolation] whose `state`
     * is already [ConnectionState.Idle] specifically identifies the [Inbound.Disconnected] case
     * (an in-flight [connect] interrupted by a concurrent [disconnect]) and also routes to `Idle`,
     * matching what [disconnect] itself would set.
     */
    private fun finalStateFor(error: TonexError): ConnectionState = when (error) {
        is TonexError.TransportFailure -> ConnectionState.Idle
        is TonexError.Timeout -> if (error.operation == "transport-write") {
            ConnectionState.Idle
        } else {
            ConnectionState.Error(error)
        }
        is TonexError.ProtocolStateViolation -> if (error.state == ConnectionState.Idle) {
            ConnectionState.Idle
        } else {
            ConnectionState.Error(error)
        }
        else -> ConnectionState.Error(error)
    }

    // ---- teardown (§6.9) ----------------------------------------------------------------------

    /**
     * Tears down the current connection and publishes [finalState] as the very last step — a
     * collector observing the new [connectionState] value must never simultaneously see a stale
     * `presets` list or a non-null `activePreset`. Idempotent via [teardownDone]: a racing
     * [disconnect] / transport-detach / connect-failure teardown is a no-op, not a double-close.
     *
     * Takes no mutex — see [disconnect]'s KDoc for why: [disconnect] must be able to interrupt an
     * in-flight [connect]/[selectPreset]/etc. that is itself holding [operationMutex].
     */
    private suspend fun teardown(finalState: ConnectionState) {
        if (!teardownDone.compareAndSet(false, true)) return
        readerJob?.cancelAndJoin()
        readerJob = null
        runCatching { transport?.close() } // close() is documented idempotent
        transport = null
        session = null
        selfInitiatedPreset = null
        footswitchSnapshot = null
        snapshotStore.clear()
        // Before the flows are cleared below: anything still buffered describes a session that no
        // longer exists, and must not be flushed back into a map that is about to be emptied.
        inboundParameterThrottle.cancelAll()
        synchronized(pendingEchoesLock) { pendingEchoes.clear() }
        _presets.value = emptyList()
        _activePreset.value = null
        _slotAssignments.value = emptyMap()
        _parameterValues.value = emptyMap()
        _connectionState.value = finalState // LAST, always
    }

    /**
     * Called when `transport.incoming()` completes or throws, from *within* the reader
     * coroutine's own body. Emits [Inbound.TransportEnded] first so any in-flight awaiter unblocks
     * with the precise cause, then tears down.
     *
     * Deliberately launches [teardown] on [scope] rather than calling it directly: [teardown]
     * calls `readerJob?.cancelAndJoin()`, and `readerJob` **is** the coroutine currently executing
     * this function — a direct call would have that coroutine try to join itself, which never
     * completes. Launching a fresh coroutine for the teardown lets this function's own coroutine
     * body finish (completing `readerJob` naturally) before anything joins it.
     *
     * Captures [readerJob]'s identity *now*, before the launch — [PR #43's Opus review, finding
     * 4](https://github.com/wiz0floyd/Tonex-OTG/pull/43#pullrequestreview-4955032352): the launched
     * teardown is only dispatched later, and a fresh [connect] can in principle run to completion
     * (resetting [teardownDone] and installing a new [readerJob]/transport) before this queued
     * teardown is picked up. Without the identity check, that stale teardown would still win
     * [teardown]'s CAS and tear down the *new* connection out from under it. Not a full generation
     * counter (that would be the "elaborate automatic-recovery safety net" this project's house
     * philosophy warns against) — just enough to no-op cleanly instead of corrupting a fresh
     * connection when the two race.
     */
    private fun onTransportEnded(cause: Throwable?) {
        inbound.tryEmit(Inbound.TransportEnded(cause))
        val endedReaderJob = readerJob
        scope.launch {
            if (readerJob !== endedReaderJob) return@launch // superseded by a fresh connect(); not ours to tear down
            teardown(ConnectionState.Idle)
        }
    }

    // ---- connect() (§7) -----------------------------------------------------------------------

    override suspend fun connect(transport: TonexTransport): TonexResult<Unit> = operationMutex.withLock {
        if (_connectionState.value !is ConnectionState.Idle) {
            return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "connect() requires Idle; call disconnect() first"),
            )
        }
        this.transport = transport
        teardownDone.set(false)
        _connectionState.value = ConnectionState.Connecting
        readerJob = startReader(transport)

        // ---- Stage 1: Hello -----------------------------------------------------------------
        _connectionState.value = ConnectionState.Hello
        requestAndAwait(HelloMessage.encode(), timeouts.helloMillis, "hello") { inb ->
            commonInbound("hello", inb) ?: when {
                inb is Inbound.Message && inb.message is TonexMessage.Hello -> TonexResult.Success(Unit)
                inb is Inbound.Message && inb.message is TonexMessage.Other -> null
                inb is Inbound.Message -> TonexResult.Failure(
                    TonexError.ProtocolStateViolation(ConnectionState.Hello, "expected a Hello response (0x02), got ${inb.message}"),
                )
                else -> null
            }
        }.onFailureFinish { return@withLock it }

        // ---- Stage 2: GetState ----------------------------------------------------------------
        _connectionState.value = ConnectionState.GetState
        val blob: ByteArray = requestAndAwait(
            RequestStateMessage.encode(),
            timeouts.getStateMillis,
            "get-state",
        ) { inb ->
            commonInbound("get-state", inb) ?: when {
                inb is Inbound.Message && inb.message is TonexMessage.StateUpdate ->
                    TonexResult.Success((inb.message as TonexMessage.StateUpdate).payload)
                inb is Inbound.Message && inb.message is TonexMessage.Other -> null
                inb is Inbound.Message -> TonexResult.Failure(
                    TonexError.ProtocolStateViolation(ConnectionState.GetState, "expected a state update (0x0306), got ${inb.message}"),
                )
                else -> null
            }
        }.orFinish { return@withLock it }

        // ---- Validate the handshake blob BEFORE minting a session. Deliberately NOT wrapped in a
        // PedalState -- see TonexError.BlobSizeChangedSinceHandshake's KDoc: a SessionId does not
        // exist until Ready, and Ready is only reached after this blob is already read, so no pin
        // can ever be anchored to this specific read. Do not "optimise" this by reusing the
        // handshake blob as the first PedalState. -----------------------------------------------
        val initialActive = StateBlobReader.activePreset(blob).orFinish { return@withLock it }

        // ---- Ready -----------------------------------------------------------------------------
        val newSession = SessionId.create() // ← THE ONLY SessionId.create() CALL SITE
        session = newSession
        _activePreset.value = initialActive // first observation ⇒ no ExternalPresetChange
        // issue #36: capture the footswitch slot assignments from the handshake blob ALREADY read
        // above — before this Ready assignment, before harvestPresetNames/harvestMasterVolume, and
        // long before any selectPreset() this connection could ever issue. No further wire read.
        footswitchSnapshot = captureFootswitchSnapshot(newSession, blob)
        // Seed from the footswitchSnapshot just captured above -- reuses that same handshake
        // blob read rather than decoding it a second time. Must land before Ready is published:
        // teardown's own invariant (a collector must never see Ready with stale flows) applies
        // here too.
        _slotAssignments.value = footswitchSnapshot?.toMap() ?: emptyMap()
        // issue #83: seed the six GLOBAL-scope parameters' displayed values from this same
        // handshake blob - a pure decode of bytes already in hand, not a further round trip - so a
        // home-screen control never renders a ParameterRegistry default before its first live read.
        val initialGlobals = StateBlobReader.globalParameterValues(blob)
        if (initialGlobals is TonexResult.Success) _parameterValues.update { it + initialGlobals.value }
        _connectionState.value = ConnectionState.Ready

        // ---- Post-Ready harvest -----------------------------------------------------------------
        harvestPresetNames().orFinish { return@withLock it } // fatal — see harvestPresetNames' KDoc
        harvestMasterVolume() // best-effort, result ignored
        captureSnapshotLocked(initialActive) // best-effort, result ignored — see D3 / §E.5 of the S9b plan
        TonexResult.Success(Unit)
    }

    // ---- post-Ready harvest (§7.2 / §7.3) -----------------------------------------------------

    /**
     * Reads all 20 onboard presets' names, **strictly sequentially, never pipelined** — this is a
     * correctness requirement, not a style choice. The preset-details *response* carries no preset
     * index, so positional correlation (request N, await its response, only then request N+1) is
     * the only sound mechanism available; upstream does the same. Do not "optimise" this into a
     * parallel fan-out.
     *
     * Harvest failure is fatal to [connect]: [PresetInfo.pedalName] is non-null, so a missing name
     * can only be represented by inventing a placeholder (exactly the guessing "fail fast and
     * loud" forbids) or a short list (which every UI screen would have to special-case). This
     * preserves the invariant `connect() returned Success ⟺ Ready ⟺ presets has exactly 20 entries`.
     *
     * The awaiter matches `!full` specifically (S9b §E.6): a FULL (`0x0303`) preset-details
     * response is never requested by this module (see [PresetParameterExtractor]'s KDoc for why)
     * and would otherwise be a latent cross-match now that [captureSnapshotLocked] issues a second,
     * distinct preset-details read elsewhere in this class.
     */
    private suspend fun harvestPresetNames(): TonexResult<Unit> {
        val out = ArrayList<PresetInfo>(PresetIndex.VALID_RANGE.count())
        for (i in PresetIndex.VALID_RANGE) {
            val idx = PresetIndex(i)
            val payload = requestAndAwait(
                RequestPresetDetailsMessage.encode(idx, PresetDetailsKind.SUMMARY),
                timeouts.presetDetailsMillis,
                "preset-details",
            ) { inb ->
                commonInbound("preset-details", inb) ?: when {
                    inb is Inbound.Message && inb.message is TonexMessage.PresetDetails &&
                        !(inb.message as TonexMessage.PresetDetails).full ->
                        TonexResult.Success((inb.message as TonexMessage.PresetDetails).payload)
                    else -> null // StateUpdate / Other / ParameterChanged / a FULL response interleaved: ignore, keep waiting
                }
            }.orReturn { return it }
            val name = PresetNameExtractor.extract(payload).orReturn { return it }
            out += PresetInfo(idx, name)
        }
        _presets.value = out
        return TonexResult.Success(Unit)
    }

    /**
     * Best-effort: gated on [FirmwareCapabilities.supportsSingleParameterWrite] despite being a
     * read, because upstream's own request carries the identical "only supported in newer Pedal
     * firmware that came with Editor support!" comment as the write paths — the only signal
     * `:protocol` has for "newer firmware with Editor support" (see issue #25 for the S20 probe
     * that would confirm whether the two capabilities are genuinely the same gate).
     *
     * A missing master volume is representable exactly and honestly by [parameterValues]' own
     * "absence is not the same as a value of zero" contract — so a harvest failure here does not
     * fail [connect]. This asymmetry with [harvestPresetNames] is principled, not arbitrary — see
     * that function's KDoc.
     */
    private suspend fun harvestMasterVolume() {
        if (!capabilities.supportsSingleParameterWrite) return
        requestAndAwait(RequestMasterVolumeMessage.encode(), timeouts.masterVolumeMillis, "master-volume") { inb ->
            commonInbound("master-volume", inb) ?: when {
                inb is Inbound.Message && inb.message is TonexMessage.ParameterChanged ->
                    TonexResult.Success(Unit) // the reader already applied the value via applyParameterChanged
                else -> null
            }
        } // result deliberately discarded
    }

    // ---- footswitch-snapshot capture (issue #36) -------------------------------------------------

    /**
     * Decodes [blob]'s three footswitch slot assignments and wraps them as a [FootswitchSnapshot]
     * stamped with [sessionId] — issue #36's "capture at handshake, before any write" requirement.
     * [blob] is the raw handshake `GetState` bytes [connect] already read (the same bytes
     * [StateBlobReader.activePreset] decoded a few lines above this call site) — this is a pure
     * decode of bytes already in hand, not a further round trip, and has no failure mode beyond
     * the blob failing to look like a plausible slot region.
     *
     * `null` on failure (an implausibly-shaped or too-short handshake blob) — never fatal to
     * [connect], mirroring [captureSnapshotLocked]'s "a read failure records nothing, never blocks
     * Ready" discipline: a pedal a user can otherwise fully operate minus the footswitch-restore
     * affordance is still a fully usable app. [restoreFootswitches] is what surfaces this failure
     * to the caller, as [TonexError.NoFootswitchSnapshotAvailable], the first time restore is
     * actually attempted — not silently swallowed here.
     */
    private fun captureFootswitchSnapshot(sessionId: SessionId, blob: ByteArray): FootswitchSnapshot? {
        val result = StateBlobReader.slotAssignments(blob)
        return (result as? TonexResult.Success)?.let { FootswitchSnapshot(sessionId, it.value) }
    }

    // ---- snapshot capture (S9b / §E) -----------------------------------------------------------

    /**
     * Reads preset [index]'s 109 live parameter values off the pedal, always publishes them to
     * [parameterValues] via [applyCapturedValues], and — first-arrival-wins (issue #46) — records
     * them into [snapshotStore] as the preset's revert snapshot **only if [index] has no snapshot
     * yet this session**. Requests [PresetDetailsKind.SUMMARY] (byte-identical to
     * [harvestPresetNames]' own request), **not** `FULL` — see [PresetParameterExtractor]'s KDoc
     * for why `FULL` is never the right request here.
     *
     * ## First-arrival-wins retention (issue #46)
     * [SnapshotStore.record] itself still unconditionally replaces any existing entry — that stays
     * true and is exactly right for "this is the first time *this session* we've seen this preset."
     * The retention *policy* lives here, one call site up: step 6 below only reaches `record` when
     * [SnapshotStore.snapshotFor] for [index] is `null` **or belongs to a different session** —
     * checked as `snapshotFor(index)?.sessionId !== s`, not mere presence, so a snapshot that
     * outlives its own session (see step 6's own comment for the concurrent-teardown race that
     * makes this reachable) gets replaced rather than permanently pinned. Re-arriving at a preset
     * already snapshotted *this session* — including returning to it after edits, or after an
     * aborted/partial [revertActivePreset] ([TonexError.ActivePresetChangedDuringRevert]) — refreshes
     * the *displayed* values (below) but leaves the *retained* snapshot exactly as it was on first
     * arrival. This is the product-owner-decided policy; see issue #46 for the alternatives
     * considered and why this one was chosen, and for the accepted staleness tradeoff (a retained
     * snapshot cannot see changes an external editor/footswitch/MIDI makes to the pedal after that
     * first capture).
     *
     * ## Caller contract
     * The caller MUST already hold [operationMutex]. `Mutex` is not reentrant, so this function
     * must never take it itself — [connect] calls it while already holding the lock, and the
     * launched path in [applyStateUpdate] acquires the lock *around* the call.
     *
     * ## Failure semantics — best-effort, never fatal, never partial
     * A failed read records **nothing** in [snapshotStore] and publishes nothing to
     * [parameterValues] — every failure path below returns before either happens, and
     * [PresetParameterExtractor] structurally cannot return a partially-populated array. The
     * user-visible consequence, when no snapshot exists yet for [index] and its first read fails,
     * is that [revertActivePreset] returns [TonexError.NoSnapshotAvailable] — not a gap, that
     * error's own KDoc anticipates exactly this case. Both callers of this function (`connect` and
     * `applyStateUpdate`) therefore discard the result; do not make a read failure fatal to
     * [connect] (a pedal that answers the name harvest but not the parameter read still gives a
     * fully usable app minus the revert affordance), and do not add a retry loop here (reject-and-
     * explain beats patch-and-hope).
     */
    private suspend fun captureSnapshotLocked(index: PresetIndex): TonexResult<Unit> {
        // 1. Re-check liveness. Load-bearing for the launched path in applyStateUpdate, which can
        //    be dispatched after a racing disconnect()/detach.
        if (_connectionState.value !is ConnectionState.Ready) {
            return TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "snapshot capture requires Ready"),
            )
        }
        // 2. Pin the session identity — mirrors selectPreset's existing guard verbatim.
        val s = session ?: return TonexResult.Failure(
            TonexError.ProtocolStateViolation(_connectionState.value, "no session (internal invariant violated)"),
        )

        // 3. Request and await, mirroring harvestPresetNames' awaiter shape (including the `!full`
        //    tightening, §E.6) exactly.
        val payload = requestAndAwait(
            RequestPresetDetailsMessage.encode(index, PresetDetailsKind.SUMMARY),
            timeouts.presetParametersMillis,
            "preset-parameters",
        ) { inb ->
            commonInbound("preset-parameters", inb) ?: when {
                inb is Inbound.Message && inb.message is TonexMessage.PresetDetails &&
                    !(inb.message as TonexMessage.PresetDetails).full ->
                    TonexResult.Success((inb.message as TonexMessage.PresetDetails).payload)
                else -> null // StateUpdate / ParameterChanged / a FULL response interleaved: ignore, keep waiting
            }
        }.orReturn { return it }

        // 4. Extract the 109-float block.
        val values = PresetParameterExtractor.extract(payload).orReturn { return it }

        // 5. Re-check that the capture is still about the currently active preset. The reader
        //    coroutine can change _activePreset at any moment (a footswitch press); whether a
        //    SUMMARY response for a NON-active preset even contains a parameter block — and if so,
        //    whose values it holds — is unverified and unverifiable without hardware (upstream
        //    only ever parses this block out of a response for the currently active preset). Discard
        //    rather than guess: a stale snapshot from a different preset is worse than none
        //    (SnapshotStore.record's own KDoc). Filed for hardware on #25.
        if (session !== s || _activePreset.value != index) {
            return TonexResult.Failure(
                TonexError.ProtocolStateViolation(
                    _connectionState.value,
                    "the active preset changed while preset ${index.value}'s snapshot was being " +
                        "captured; discarding the capture rather than recording a possibly-wrong snapshot",
                ),
            )
        }

        // 6. Publish the live read unconditionally, then record it as the revert snapshot ONLY if
        //    this preset has no snapshot yet from THIS session (first-arrival-wins, issue #46).
        //    PresetSnapshot's constructor copies `values`, so handing it the same array
        //    applyCapturedValues just read from is safe; applyCapturedValues itself builds a
        //    Map<ParameterId, Float> of boxed floats and does not retain the array.
        //
        //    Comparing sessionId (not just presence) closes a cross-session phantom-snapshot race
        //    (Opus review, issue #46 PR): onTransportEnded's teardown runs on `scope` WITHOUT
        //    operationMutex (see its own KDoc), so it can call `session = null` then
        //    `snapshotStore.clear()` concurrently with a capture that already passed step 5 above on
        //    another thread of a caller-supplied multithreaded `scope`. A presence-only check
        //    (`snapshotFor(index) == null`) would let that capture record into the just-cleared store
        //    a snapshot stamped with the DEAD session's SessionId — and unlike the old unconditional
        //    `record`, nothing would ever overwrite it: connect() never calls clear(), so this
        //    phantom would silently outlive the session boundary and revertActivePreset would replay
        //    a previous session's values onto the current session's preset. Comparing sessionId is
        //    behaviorally identical within one session (anything recorded this session carries
        //    sessionId === s, so re-arrival still skips) and self-heals across a session boundary.
        applyCapturedValues(values)
        if (snapshotStore.snapshotFor(index)?.sessionId !== s) {
            snapshotStore.record(PresetSnapshot(index, s, values))
        }
        return TonexResult.Success(Unit)
    }

    /**
     * Publishes a freshly captured preset's 109 values, replacing any prior PRESET-scoped entries
     * and preserving GLOBAL-scoped ones (master volume is not part of any preset). Implements the
     * "preset load/change" half of [parameterValues]' documented contract — before S9b only
     * master volume and post-[setParameter] values ever landed there.
     *
     * This is also the self-widening read hook (issue #80): every value here came from a genuine
     * pedal state read ([captureSnapshotLocked] → [PresetParameterExtractor]), never from the
     * optimistic post-write cache update in [writeParameterLocked] — exactly the distinction the
     * feature depends on (a write echoing back a caller-supplied value must never be allowed to
     * widen its own ceiling). [SelfWideningParameterBounds.observeRead] is a no-op for every id
     * outside [ParameterRegistry.SELF_WIDENING_PARAMETER_IDS], so this call is harmless for the
     * other ~106 preset parameters.
     */
    private fun applyCapturedValues(values: FloatArray) {
        val captured = ParameterId.PRESET_RANGE.associate { ParameterId(it) to values[it] }
        captured.forEach { (id, value) -> effectiveBounds.observeRead(id, value) }
        _parameterValues.update { previous ->
            previous.filterKeys { it.index in ParameterId.GLOBAL_RANGE } + captured
        }
    }

    /**
     * Drops every PRESET-scoped entry from [parameterValues], keeping GLOBAL ones. Called
     * synchronously the moment the active preset changes ([applyStateUpdate]): the outgoing
     * preset's values are not the incoming preset's values, and [parameterValues]' contract is
     * that absence means "not yet known", never "zero" — so if the subsequent capture then fails,
     * the map is left with no preset entries rather than a stale or wrong ones.
     */
    private fun dropPresetScopedValues() {
        _parameterValues.update { it.filterKeys { id -> id.index in ParameterId.GLOBAL_RANGE } }
    }

    // ---- disconnect() (§6.10) ------------------------------------------------------------------

    /**
     * Ends the current connection. **Documented safe to call from any state, including
     * [ConnectionState.Error]** — this is the machine's only guaranteed escape hatch, so it must
     * always land on [ConnectionState.Idle] itself rather than merely delegating to [teardown] and
     * trusting whatever that publishes. Deliberately does NOT take [operationMutex] first — its
     * sequence:
     * 1. No-op if already [ConnectionState.Idle].
     * 2. Emit [Inbound.Disconnected] — every awaiter unblocks *immediately* with
     *    [TonexError.ProtocolStateViolation]`(Idle, ...)`, rather than hanging until its timeout.
     * 3. `operationMutex.withLock { teardown(Idle); _connectionState.value = Idle }` — by then the
     *    interrupted operation has returned and released the lock.
     *
     * This is deadlock-free: [disconnect] can interrupt an in-flight [connect]/[selectPreset]/etc.
     * that is itself holding [operationMutex], because it never tries to acquire that lock first.
     *
     * **Why the explicit re-assignment after [teardown]:** [teardown]'s [teardownDone] guard makes
     * it idempotent by design, but that guard trips on *state publication*, not just resource
     * release — a teardown that already ran (e.g. a failing [connect] that published
     * [ConnectionState.Error], or a racing transport-detach that already published `Idle`) makes
     * this call's own `teardown(Idle)` a complete no-op that publishes nothing. Without the
     * explicit assignment below, calling [disconnect] from [ConnectionState.Error] would silently
     * do nothing and leave the machine wedged in `Error` forever — contradicting this function's
     * own "safe to call from any state" contract, [ConnectionState.Error]'s documented recovery
     * path, and issue #13's "must not wedge" scope bullet. The extra assignment is a no-op publish
     * when [teardown] already did the real work (same value, same `StateFlow` de-dupe), and the
     * one-and-only recovery path when it didn't.
     *
     * **Accepted simplification:** between steps 2 and 3 a fresh [connect] could in principle
     * acquire the mutex first; it would then observe `connectionState` is not `Idle` and fail with
     * [TonexError.ProtocolStateViolation] — loud and harmless. Guarding this further (a full
     * generation counter) is exactly the "elaborate automatic-recovery safety net" this project's
     * house philosophy says not to build for a hobby project.
     */
    override suspend fun disconnect() {
        if (_connectionState.value is ConnectionState.Idle) return
        inbound.tryEmit(Inbound.Disconnected)
        operationMutex.withLock {
            teardown(ConnectionState.Idle)
            // teardown() may have no-opped above (a failing connect() — or a racing
            // transport-detach — already tore down and published Error/Idle itself).
            // disconnect() is documented safe from any state and must always land on Idle.
            _connectionState.value = ConnectionState.Idle
        }
    }

    // ---- selectPreset() (§9) -------------------------------------------------------------------

    override suspend fun selectPreset(index: PresetIndex): TonexResult<Unit> = operationMutex.withLock {
        if (_connectionState.value !is ConnectionState.Ready) {
            return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "selectPreset requires Ready"),
            )
        }
        // Defense-in-depth against @JvmInline erasure at the JVM ABI boundary — see
        // TonexError.InvalidPresetIndex's KDoc.
        if (index.value !in PresetIndex.VALID_RANGE) {
            return@withLock TonexResult.Failure(TonexError.InvalidPresetIndex(index.value))
        }
        val s = session ?: return@withLock TonexResult.Failure(
            TonexError.ProtocolStateViolation(_connectionState.value, "no session (internal invariant violated)"),
        )

        // ---- MANDATORY re-read, immediately before the patch (PedalState's freshness contract). ---
        val fresh: PedalState = requestAndAwait(
            RequestStateMessage.encode(),
            timeouts.stateReadMillis,
            "state-read",
        ) { inb ->
            commonInbound("state-read", inb) ?: when (inb) {
                is Inbound.State -> inb.result // Success(PedalState) or Failure(OversizedStateBlob)
                else -> null
            }
        }.orReturn { return@withLock it }

        val bytes = fresh.copyOfBytes()
        val assignments = StateBlobReader.slotAssignments(bytes).orReturn { return@withLock it }
        val activeSlot = StateBlobReader.activeSlot(bytes).orReturn { return@withLock it }
        val currentActive = StateBlobReader.presetInSlot(bytes, activeSlot).orReturn { return@withLock it }

        // Prefer activeSlot when it ALSO holds index (issue #86, Opus review prep for #85): two
        // slots can hold the same preset (a duplicate the user set at the physical footswitch, or
        // one restoreFootswitches faithfully restores), and `assignments.entries.firstOrNull` alone
        // would return whichever slot happens to iterate first — not necessarily the active one —
        // defeating the `holdingSlot == activeSlot` short-circuit below even when the active slot
        // itself already holds the target preset.
        val holdingSlot = if (assignments[activeSlot] == index) {
            activeSlot
        } else {
            assignments.entries.firstOrNull { it.value == index }?.key
        }

        val patched: ByteArray = when {
            // Already active and already assigned — verified against a read taken moments ago.
            holdingSlot == activeSlot -> return@withLock TonexResult.Success(Unit)

            // Already assigned to another footswitch slot: just switch to it. Touches ONE byte and
            // leaves all three of the user's slot assignments intact.
            holdingSlot != null ->
                StateBlobPatcher.patchActiveSlot(fresh, s, holdingSlot).orReturn { return@withLock it }

            // Not assigned anywhere: assign it to the currently active slot and keep that slot
            // active — the least destructive available choice.
            else ->
                StateBlobPatcher.selectPreset(fresh, s, activeSlot, index).orReturn { return@withLock it }
        }

        // Set AFTER the re-read, BEFORE the write: setting it earlier would cause the re-read's own
        // StateUpdate (which reports the OLD active preset) to be misread as self-initiated,
        // suppressing a genuine ExternalPresetChange the app is learning about for the first time.
        //
        // Armed CONDITIONALLY (issue #86, defense-in-depth on top of the holdingSlot fix above):
        // every reachable branch here ends with the active preset becoming `index`, so comparing
        // `currentActive` (same `fresh` re-read) against `index` is exact, not a heuristic — mirrors
        // restoreFootswitches's already-conditional arm (see that function's KDoc) rather than
        // relying solely on the short-circuit above being exhaustive.
        // Given the holdingSlot preference above, this is currently always true — the
        // `holdingSlot == activeSlot` short-circuit already catches every case where the active
        // preset wouldn't move, so every path that reaches here has `currentActive != index` by
        // construction. Kept deliberately (not simplified to an unconditional set) so a future
        // regression in that preference can't silently resurrect #86 by re-arming unconditionally.
        val changesActivePreset = currentActive != index
        if (changesActivePreset) selfInitiatedPreset = index
        writeFramed(SetStateMessage.encode(patched)).orReturn {
            if (changesActivePreset) selfInitiatedPreset = null
            return@withLock it
        }
        TonexResult.Success(Unit)
    }

    // ---- assignPresetToSlot() ------------------------------------------------------------------

    /**
     * Assigns [preset] to [slot] without changing which slot is active — see [TonexController]'s
     * KDoc for the contrast with [selectPreset].
     *
     * ## Move/swap semantics (issue #85 — supersedes an earlier "refuse if already assigned"
     * design)
     * This operation never *creates* a second slot holding the same preset — it does not
     * guarantee no duplicate can exist at all (the pedal itself, or an external editor, could
     * still arrive with two slots already pointing at the same preset; neither this guard nor its
     * KDoc claims to undo that — see issue #86). The wire protocol has no "empty slot" concept —
     * every slot byte must hold a valid preset index — so there is no way to merely "clear" a
     * preset's old slot; the only invariant-preserving move this function can make is a **swap**:
     * if [preset] is currently assigned to a different slot ([slot]'s "source slot"), that source
     * slot takes on whichever preset [slot] held right before this call, [slot] takes on [preset],
     * and the third (untouched) slot's byte is carried over unchanged — three bytes patched, one
     * of them to the same value it already had. Two other cases short-circuit before any write:
     * - [slot] already holds [preset] — a no-op, [TonexResult.Success] with nothing written.
     * - [preset] is not assigned to any slot — the common case (most of the 20 presets are not on
     *   any footswitch) — a plain single-byte [StateBlobPatcher.patchSlotAssignment] write.
     *
     * ## [selfInitiatedPreset] is armed CONDITIONALLY, only when this write actually changes the
     * active preset
     * Reassigning a slot other than the currently active one never changes [_activePreset] — the
     * active-slot byte is untouched, and neither the active slot's own assignment (the swap's
     * "third, untouched slot" case) nor its own byte value moves when the active slot is not
     * involved in this call's [slot]/source-slot pair. But reassigning the *currently active*
     * slot's preset DOES change what's active, because the active preset is defined as "whatever
     * preset the active slot currently points at" (see [StateBlobReader.activePreset]) — and in
     * the swap case, the active slot can be either [slot] or the source slot, each of which gets a
     * new preset value. So [selfInitiatedPreset] must be armed exactly when the active slot is one
     * of the slots actually being rewritten — the same conditional-arm rule [restoreFootswitches]
     * follows (its KDoc has the full rationale for why arming it unconditionally would leave the
     * latch stranded and swallow a later genuine [TonexEvent.ExternalPresetChange]). Unlike
     * [selectPreset], there is no short-circuit here that makes "always arm" safe.
     */
    override suspend fun assignPresetToSlot(slot: PresetSlot, preset: PresetIndex): TonexResult<Unit> =
        operationMutex.withLock {
            if (_connectionState.value !is ConnectionState.Ready) {
                return@withLock TonexResult.Failure(
                    TonexError.ProtocolStateViolation(_connectionState.value, "assignPresetToSlot requires Ready"),
                )
            }
            // Defense-in-depth against @JvmInline erasure at the JVM ABI boundary — see
            // TonexError.InvalidPresetIndex's KDoc.
            if (preset.value !in PresetIndex.VALID_RANGE) {
                return@withLock TonexResult.Failure(TonexError.InvalidPresetIndex(preset.value))
            }
            val s = session ?: return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "no session (internal invariant violated)"),
            )

            // ---- MANDATORY re-read, immediately before the patch (PedalState's freshness contract). ---
            val fresh: PedalState = requestAndAwait(
                RequestStateMessage.encode(),
                timeouts.stateReadMillis,
                "state-read",
            ) { inb ->
                commonInbound("state-read", inb) ?: when (inb) {
                    is Inbound.State -> inb.result // Success(PedalState) or Failure(OversizedStateBlob)
                    else -> null
                }
            }.orReturn { return@withLock it }

            val bytes = fresh.copyOfBytes()
            val assignments = StateBlobReader.slotAssignments(bytes).orReturn { return@withLock it }
            val targetCurrent = assignments.getValue(slot)

            // Case 1: already assigned — verified against a read taken moments ago. No write sent.
            if (targetCurrent == preset) return@withLock TonexResult.Success(Unit)

            val activeSlot = StateBlobReader.activeSlot(bytes).orReturn { return@withLock it }
            val sourceSlot = assignments.entries.firstOrNull { it.key != slot && it.value == preset }?.key

            // The complete post-write assignment map, in both branches — this is the single source
            // of truth for "what does each slot hold after this write," which is what determines
            // whether the ACTIVE slot's preset actually changed (below). Deriving that from this
            // map, rather than from a separately-tracked "touched slots" set, is what keeps the
            // touched-slots bookkeeping and the self-initiated arming value from being able to
            // drift apart — see the swap case: when the active slot IS sourceSlot, its post-write
            // value is [targetCurrent], NOT [preset] (Opus review, PR #85 part 2/2 pre-review).
            val postWrite: Map<PresetSlot, PresetIndex> = if (sourceSlot == null) {
                assignments + (slot to preset)
            } else {
                assignments + (slot to preset) + (sourceSlot to targetCurrent)
            }

            val patched = if (sourceSlot == null) {
                // Case 2: preset is not on any slot — plain single-byte write.
                StateBlobPatcher.patchSlotAssignment(fresh, s, slot, preset).orReturn { return@withLock it }
            } else {
                // Case 3: preset currently sits on a different slot — move/swap. sourceSlot takes
                // on whatever slot previously held; the third slot carries its current value over
                // unchanged. restoreSlotAssignments requires exactly one entry per slot.
                StateBlobPatcher.restoreSlotAssignments(fresh, s, postWrite).orReturn { return@withLock it }
            }

            // The active slot's preset only changes if this write actually altered ITS byte — which,
            // in the swap case, may carry a value other than [preset] (see postWrite's KDoc above).
            val newActivePreset = postWrite.getValue(activeSlot)
            val changesActivePreset = newActivePreset != assignments.getValue(activeSlot)

            // Set AFTER the re-read, BEFORE the write — identical ordering rule to selectPreset's
            // own comment: setting it earlier would misread the re-read's own StateUpdate.
            if (changesActivePreset) selfInitiatedPreset = newActivePreset
            writeFramed(SetStateMessage.encode(patched)).orReturn {
                if (changesActivePreset) selfInitiatedPreset = null
                return@withLock it
            }
            TonexResult.Success(Unit)
        }

    // ---- setParameter() (§10.1) ----------------------------------------------------------------

    /**
     * The single per-parameter write path, shared by [setParameter] and [revertActivePreset]'s
     * replay: registry lookup, range rejection, PRESET/master-volume/other-global routing, the
     * framed write, and the post-success [parameterValues] update.
     *
     * ## Caller contract
     * The caller MUST already hold [operationMutex] and MUST already have verified
     * [ConnectionState.Ready]. This function takes no lock and performs no lifecycle check —
     * `Mutex` is not reentrant, so a version that locked internally could not be called from
     * [revertActivePreset]'s replay loop at all (it would deadlock on the lock that loop already
     * holds).
     *
     * Deliberately does NOT emit [TonexEvent.FirstDestructiveWrite]: that signal belongs to a
     * user-initiated edit, not to a revert restoring values the user already had. See
     * [maybeSignalFirstDestructiveWrite].
     */
    private suspend fun writeParameterLocked(id: ParameterId, value: Float): TonexResult<Unit> {
        // Same @JvmInline ABI-erasure hazard as PresetIndex — re-validate explicitly.
        val spec = ParameterRegistry.byIndex(id.index)
            ?: return TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "parameter index ${id.index} is not in the registry"),
            )

        // Reject, do NOT clamp — TonexController.setParameter's contract is explicit about this.
        // Bound against the EFFECTIVE max (issue #80: self-widened for the allowlisted VIR_*
        // parameters), not the registry's static max — otherwise a previously-observed widened
        // value could never be re-written.
        val effectiveMax = effectiveBounds.effectiveMax(id)
        if (value < spec.min || value > effectiveMax) {
            return TonexResult.Failure(TonexError.ParameterValueOutOfRange(id, value, spec.min, effectiveMax))
        }

        if (spec.scope == ParameterScope.PRESET) {
            val encoded = ParameterWriteMessage.encode(id, value, capabilities, effectiveBounds).orReturn { return it }
            writeFramed(encoded).orReturn { return it }
            // The pedal will echo this back within a few ms; ignore it when it arrives.
            recordExpectedEcho(SingleParameterPayloadCodec.KIND_PARAMETER, id.index, value)
            _parameterValues.update { it + (id to value) } // AFTER success only
            return TonexResult.Success(Unit)
        }

        if (spec.enumName == "MASTER_VOLUME") {
            val encoded = MasterVolumeMessage.encode(value, capabilities).orReturn { return it }
            writeFramed(encoded).orReturn { return it }
            // The echo carries the NATIVE value that went on the wire, not the dB stored below --
            // MasterVolumeMessage.encode clamps in dB and then converts, so mirror both steps.
            recordExpectedEcho(
                SingleParameterPayloadCodec.KIND_MASTER_VOLUME,
                0,
                MasterVolumeMessage.decibelsToNative(ParameterRegistry.clamp(id, value)),
            )
            _parameterValues.update { it + (id to value) } // AFTER success only
            return TonexResult.Success(Unit)
        }

        // The remaining GLOBAL-scope parameters (issue #83): upstream writes these by patching the
        // full state blob, the same read-modify-write mechanism selectPreset/restoreFootswitches
        // already use. Mandatory re-read immediately before the patch, per PedalState's freshness
        // contract.
        val globalPatcher: ((PedalState, SessionId, Float) -> TonexResult<ByteArray>)? = when (spec.enumName) {
            "BPM" -> StateBlobPatcher::patchBpm
            "INPUT_TRIM" -> StateBlobPatcher::patchInputTrim
            "CABSIM_BYPASS" -> StateBlobPatcher::patchCabSimBypass
            "TEMPO_SOURCE" -> StateBlobPatcher::patchTempoSource
            "TUNING_REFERENCE" -> StateBlobPatcher::patchTuningReference
            "BYPASS" -> StateBlobPatcher::patchBypassMode
            else -> null
        }
        if (globalPatcher == null) {
            return TonexResult.Failure(
                TonexError.ProtocolStateViolation(
                    _connectionState.value,
                    "${spec.enumName} is a global parameter with no known write path in :protocol",
                ),
            )
        }

        val s = session ?: return TonexResult.Failure(
            TonexError.ProtocolStateViolation(_connectionState.value, "no session (internal invariant violated)"),
        )
        val fresh: PedalState = requestAndAwait(
            RequestStateMessage.encode(),
            timeouts.stateReadMillis,
            "state-read",
        ) { inb ->
            commonInbound("state-read", inb) ?: when (inb) {
                is Inbound.State -> inb.result
                else -> null
            }
        }.orReturn { return it }

        val patched = globalPatcher(fresh, s, value).orReturn { return it }
        writeFramed(SetStateMessage.encode(patched)).orReturn { return it }
        _parameterValues.update { it + (id to value) } // AFTER success only
        return TonexResult.Success(Unit)
    }

    /**
     * Fires [TonexEvent.FirstDestructiveWrite] at most once per session, after the session's first
     * successful parameter write that actually altered a snapshotted preset. See [TonexController]
     * and the S9b architecture plan's §F.3 for why this is a post-hoc notice rather than a
     * pre-write gate: `events` is a zero-replay `SharedFlow` with no suspending-confirmation
     * mechanism, a pre-write gate would have to block inside [operationMutex] (wedging every other
     * operation for the duration of a modal dialog), and firing before the write would be
     * dishonest when the write then fails.
     *
     * The four conditions below are all mandated by existing KDoc, not invented here:
     * [SnapshotStore.hasWarnedThisSession]'s KDoc defines the triggering fact as "a parameter
     * write against a **snapshotted** preset," which pins both the snapshot-exists condition and
     * (with [PresetSnapshot]'s "a snapshot covers exactly the 109 PRESET-scoped parameters") the
     * PRESET-scope condition. Master volume is excluded because it is global, not part of any
     * preset, not captured by any snapshot, and not restored by revert — writing it destroys
     * nothing a snapshot could have saved.
     *
     * Always called with [operationMutex] held, which is what makes the read-then-mark sequence
     * below safe without further synchronisation. `markWarned()` precedes `tryEmit` so the
     * once-per-session guarantee is structural rather than dependent on the emit succeeding.
     */
    private fun maybeSignalFirstDestructiveWrite(id: ParameterId) {
        if (snapshotStore.hasWarnedThisSession()) return
        val spec = ParameterRegistry.byIndex(id.index) ?: return
        if (spec.scope != ParameterScope.PRESET) return // master volume is global; not part of a preset
        val active = _activePreset.value ?: return
        if (snapshotStore.snapshotFor(active) == null) return // "against a snapshotted preset" — SnapshotStore's own wording
        snapshotStore.markWarned() // mark BEFORE emit: "at most once" is the contract
        _events.tryEmit(TonexEvent.FirstDestructiveWrite(active))
    }

    override suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit> = operationMutex.withLock {
        if (_connectionState.value !is ConnectionState.Ready) {
            return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "setParameter requires Ready"),
            )
        }
        val result = writeParameterLocked(id, value)
        if (result is TonexResult.Success) maybeSignalFirstDestructiveWrite(id)
        result
    }

    // ---- revertActivePreset() (§10.2) -----------------------------------------------------------

    /**
     * A complete four-guard chain (S9, issue #13) followed by the replay S9b (issue #14) adds:
     * restoring the active preset's snapshot as **per-parameter writes only, never a whole-state
     * write**. Guards 1-4 are unchanged from S9 and verified correct and complete for S9b; only
     * guard 4's result is now bound (a snapshot can genuinely exist, now that
     * [captureSnapshotLocked] calls [SnapshotStore.record]) rather than discarded.
     *
     * ## Guard 2 discharges issue #14's firmware-fallback prohibition in full
     * It runs before any write, returns [TonexError.UnsupportedByFirmware]`("revert-active-preset")`,
     * and there is no fallback branch anywhere in this function. [writeParameterLocked] →
     * [ParameterWriteMessage.encode] independently re-checks the same capability and returns
     * `UnsupportedByFirmware("single-parameter-write")`, so the prohibition is enforced twice, at
     * two layers, on two distinct operation strings.
     *
     * ## Replay: all 109, unconditionally, ascending wire index
     * A diff-based replay (only parameters that differ from [parameterValues]) is tempting —
     * 109 writes typically becomes 2 — and issue #14's wording ("restores every *changed*
     * parameter") permits it, but [parameterValues] is a local mirror that can drift from the
     * pedal without this module knowing (the reader deliberately applies only `index == 0` of an
     * inbound `ParameterChanged`, see [applyParameterChanged]'s KDoc): a diff computed against a
     * drifted mirror would silently skip a parameter that genuinely needed restoring and report
     * `Success` — a false success in the one operation whose entire job is to be trustworthy.
     * Writing all 109 unconditionally also makes [TonexError.RevertIncomplete.appliedCount] mean
     * something exact, and makes retrying a partial revert idempotent (see below). Ascending
     * [ParameterId.PRESET_RANGE] order is load-bearing, not cosmetic — it is what makes
     * `appliedCount` decodable as "`ParameterId(0)` … `ParameterId(appliedCount - 1)` were
     * accepted."
     *
     * ## Partial failure: abort at the first failure, snapshot retained, retry is safe
     * A [writeParameterLocked] failure means the transport threw, short-wrote, or timed out; the
     * remaining parameters are overwhelmingly likely to fail identically, so this aborts rather
     * than continuing through them (turning one clear error into a long pile-up). The snapshot is
     * deliberately **not** discarded on this failure — because the replay always re-issues all 109
     * writes from the same immutable snapshot, calling this function again after a
     * [TonexError.RevertIncomplete] simply re-issues everything from scratch; nothing accumulates,
     * nothing is skipped. No automatic retry is implemented here (CLAUDE.md: no elaborate
     * automatic-recovery nets) — offering the user a retry is a UI-layer decision.
     *
     * ## Revert never touches a state-blob-patched global, by range, not by call-site count
     * [SetStateMessage.encode] is no longer called from a single site — [selectPreset],
     * [assignPresetToSlot], and [restoreFootswitches] all call it, and issue #83 added a fourth
     * call site inside [writeParameterLocked] itself, for the six `GLOBAL_RANGE` parameters
     * ([ParameterId.GLOBAL_RANGE], `110..116`) that [dev.tonexotg.protocol.state.StateBlobPatcher]
     * patches into the state blob. An earlier version of this KDoc claimed safety came from
     * [SetStateMessage.encode] having exactly one call site; that was already stale before #83 (it
     * ignored [assignPresetToSlot]/[restoreFootswitches]) and #83's new call site would have broken
     * it outright.
     *
     * The actual invariant that keeps this function safe: this loop iterates
     * [ParameterId.PRESET_RANGE] (`0..108`) only, which is disjoint from [ParameterId.GLOBAL_RANGE]
     * — revert replays preset parameters exclusively and never issues a write for any of the six
     * global parameters, so it can never trigger [writeParameterLocked]'s state-blob-patch branch or
     * produce a [SetStateMessage.encode] write of its own. [DefaultTonexControllerRevertTest]
     * asserts this directly (`newWrites.none { it.header.type == MessageType.StateUpdate }`) rather
     * than relying on call-site counting.
     *
     * @throws never — every failure mode returns a typed [TonexResult.Failure].
     */
    override suspend fun revertActivePreset(): TonexResult<Unit> = operationMutex.withLock {
        // 1. Lifecycle.
        if (_connectionState.value !is ConnectionState.Ready) {
            return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "revertActivePreset requires Ready"),
            )
        }
        // 2. Capability, checked first — reverting via whole-state writes is the dangerous path
        //    this safety net exists to avoid; never fall back to it.
        if (!capabilities.supportsSingleParameterWrite) {
            return@withLock TonexResult.Failure(TonexError.UnsupportedByFirmware("revert-active-preset"))
        }
        // 3. Active preset must be known.
        val active = _activePreset.value ?: return@withLock TonexResult.Failure(
            TonexError.ProtocolStateViolation(_connectionState.value, "the active preset is not known yet"),
        )
        // 4. A snapshot must exist for it, AND must belong to the current session — [PresetSnapshot]'s
        //    own KDoc is explicit that a cross-session revert "is not a supported operation." This is
        //    belt-and-braces on top of captureSnapshotLocked's own sessionId-scoped retention guard
        //    (issue #46 PR, Opus review should-fix #2): if a phantom snapshot from a dead session
        //    ever did make it into the store, replaying 109 stale values onto the CURRENT session's
        //    preset would be exactly the corruption class this safety net exists to prevent, so this
        //    checks rather than trusts. Reuses [TonexError.NoSnapshotAvailable] rather than a new
        //    typed error — its message ("no snapshot has been captured for this preset during this
        //    session") is already precisely true of a snapshot stamped with a different session.
        val s = session ?: return@withLock TonexResult.Failure(
            TonexError.ProtocolStateViolation(_connectionState.value, "no session (internal invariant violated)"),
        )
        val snapshot = snapshotStore.snapshotFor(active)?.takeIf { it.sessionId === s }
            ?: return@withLock TonexResult.Failure(TonexError.NoSnapshotAvailable(active))

        // 5a. Pre-validate the WHOLE snapshot before issuing a single write. A value that the
        //     per-write range check would reject must not be discovered at parameter 47, with 46
        //     writes already on the wire and the preset left half-reverted. The snapshot holds
        //     values the PEDAL reported; if any captured value falls outside the registry's
        //     bounds, that most likely means the registry's bounds are wrong for this pedal's
        //     firmware, not that the caller did anything wrong (see #25 — ParameterRegistry
        //     already flags two entries as pending hardware verification, e.g. VIR M2X). Refusing
        //     up front, with zero writes, is the only outcome that is both honest and atomic —
        //     clamping is not even reachable as a design option, since ParameterWriteMessage.encode
        //     clamps internally regardless, so "write the captured value verbatim" is impossible
        //     through the existing encoder either way.
        for (i in ParameterId.PRESET_RANGE) {
            val id = ParameterId(i)
            val spec = ParameterRegistry.byIndex(i) ?: return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(
                    _connectionState.value,
                    "parameter index $i is not in the registry (internal invariant violated)",
                ),
            )
            val v = snapshot.valueOf(id)
            // Effective max (issue #80): a snapshot captured from a genuine pedal read already
            // widened the allowlisted VIR_* parameters via applyCapturedValues' observeRead call,
            // so this must agree with that, not re-check against the static registry max.
            val effectiveMax = effectiveBounds.effectiveMax(id)
            if (v < spec.min || v > effectiveMax) {
                return@withLock TonexResult.Failure(
                    TonexError.ParameterValueOutOfRange(id, v, spec.min, effectiveMax),
                )
            }
        }

        // 5b. Replay. Per-parameter writes only, NEVER a whole-state write (issue #14). Ascending
        //     wire index, so RevertIncomplete's appliedCount identifies exactly which parameters
        //     landed.
        var applied = 0
        for (i in ParameterId.PRESET_RANGE) {
            val id = ParameterId(i)
            // Re-verify the target BEFORE every write, including the first. Per-parameter writes
            // are NOT preset-indexed on the wire — they land on whatever preset is active on the
            // pedal at the moment they arrive, not the preset this snapshot was captured from. If
            // the pedal has moved (footswitch, MIDI program change, external editor) since `active`
            // was captured above, every remaining write would corrupt a DIFFERENT preset than the
            // one being reverted. Abort loudly; never continue. This lives here, in the replay
            // loop, and NOT inside writeParameterLocked — setParameter deliberately targets
            // "whatever preset is active right now" (correct for a live slider drag), and pushing
            // this check into the shared write path would break that. Replay is the only caller
            // with a pinned target preset, so the check belongs to replay alone. This narrows the
            // corruption window; see TonexError.ActivePresetChangedDuringRevert's KDoc for why it
            // cannot close it.
            val now = _activePreset.value
            if (now != active) {
                return@withLock TonexResult.Failure(
                    TonexError.ActivePresetChangedDuringRevert(
                        intendedPreset = active,
                        observedPreset = now,
                        appliedCount = applied,
                        totalCount = PresetSnapshot.PARAMETER_COUNT,
                        nextParameter = id,
                    ),
                )
            }
            when (val r = writeParameterLocked(id, snapshot.valueOf(id))) {
                is TonexResult.Success -> applied++
                is TonexResult.Failure -> return@withLock TonexResult.Failure(
                    TonexError.RevertIncomplete(
                        presetIndex = active,
                        appliedCount = applied,
                        totalCount = PresetSnapshot.PARAMETER_COUNT,
                        failedParameter = id,
                        cause = r.error,
                    ),
                )
            }
        }
        TonexResult.Success(Unit)
    }

    // ---- restoreFootswitches() (issue #36) -------------------------------------------------------

    /**
     * The explicit, user-invoked "restore my footswitches" action (issue #36): returns the pedal's
     * A/B/C slot assignments to the values [footswitchSnapshot] captured at handshake, before any
     * write this session could have touched them.
     *
     * ## Why a whole-state write, not per-parameter writes
     * Unlike [revertActivePreset], there is no per-parameter write path for footswitch slot
     * assignments — they are pedal globals, not [ParameterScope.PRESET]-scoped parameters, and
     * unlike the six other GLOBAL-scope parameters [writeParameterLocked] now has a write path for
     * (issue #83: `BPM`, `INPUT_TRIM`, `CABSIM_BYPASS`, `TEMPO_SOURCE`, `TUNING_REFERENCE`,
     * `BYPASS`, plus `MASTER_VOLUME`'s own dedicated message), footswitch slot assignments are the
     * one remaining global with no per-parameter write path at all. The *only* way to change a slot
     * assignment on this pedal at all is the same
     * read-modify-write whole-state mechanism [selectPreset] already uses — this function follows
     * that exact, already-reviewed pattern: mandatory re-read immediately before the patch,
     * [StateBlobPatcher] as the sole byte-touching authority, and [selfInitiatedPreset] set (only)
     * if the write is about to change what [_activePreset] reports, following the identical
     * ordering rule [selectPreset] documents (set AFTER the re-read, BEFORE the write).
     *
     * ## Guard order
     * 1. Lifecycle — requires [ConnectionState.Ready].
     * 2. Session — mirrors every other write path's `session ?: ...` guard.
     * 3. A footswitch snapshot must exist for THIS session — compared by [SessionId] identity
     *    (`=== s`), not mere presence, deliberately mirroring [revertActivePreset]'s guard 4 and
     *    [captureSnapshotLocked]'s first-arrival-wins retention check (issue #46 Opus review): this
     *    class's whole `footswitchSnapshot` field is nulled by [teardown] on every disconnect, so
     *    there is no launched-coroutine race that could let a phantom cross-session snapshot slip
     *    through the way issue #46 found for [SnapshotStore] — but this check is the same
     *    belt-and-braces defense-in-depth the rest of this class applies everywhere a captured
     *    value is about to be written back, not a presence-only check reintroducing that bug's
     *    shape. Failing with [TonexError.NoFootswitchSnapshotAvailable] here, rather than treating
     *    a missing/foreign-session snapshot as "nothing to do," is exactly issue #36's "surfaces a
     *    typed error rather than silently no-opping" acceptance criterion.
     *
     * ## No no-op short-circuit, unlike [selectPreset]
     * [selectPreset] skips the write when the target is already active+assigned because it runs on
     * essentially every screen tap, including taps on the already-active preset. This function is a
     * rare, deliberate, user-invoked action (D2: "hard to trigger by accident") — always patching
     * and writing when called is simpler and costs nothing a real user would notice, even on the
     * (equally rare) call where nothing had actually drifted from the snapshot.
     *
     * ## [selfInitiatedPreset] is armed CONDITIONALLY, only when this write actually changes the
     * active preset (Opus review, issue #36 round 1)
     * [selfInitiatedPreset] is a one-shot latch: [applyStateUpdate] consumes and clears it only
     * inside its `previous != idx` branch. This function deliberately has no short-circuit (see
     * above), so a restore that leaves the active slot's assignment unchanged (nothing had drifted,
     * or only B/C drifted while A/active stayed put) still reaches this point and issues a write.
     * Arming the latch unconditionally in that case would leave it armed with nothing to ever
     * consume it — [applyStateUpdate] never even evaluates the latch unless it first observes
     * `previous != idx`, so a same-preset confirming push leaves the stale latch sitting there,
     * silently misclassifying the *next* genuinely external preset change back to that same preset
     * as self-initiated and swallowing its [TonexEvent.ExternalPresetChange]. Comparing the
     * snapshot's target preset for [activeSlot] against what that slot currently holds — both
     * already decoded from the SAME `fresh` re-read — is what makes this conditional check exact
     * rather than a heuristic. [selectPreset] now arms its own latch the same way, for the same
     * reason (issue #86): it historically armed unconditionally, relying solely on its
     * `holdingSlot == activeSlot` short-circuit — which was not exhaustive when two slots held the
     * same preset (a duplicate this function itself can faithfully restore from a snapshot, or one
     * the user set directly at the physical footswitch). Issue #87 closed that gap by making
     * [selectPreset] prefer `activeSlot` when resolving `holdingSlot`, so the short-circuit is now
     * exhaustive there too — but [selectPreset] keeps the same conditional-arm defense-in-depth
     * this function has always had, matching its shape and guarding against a future regression in
     * that preference silently re-arming unconditionally.
     */
    override suspend fun restoreFootswitches(): TonexResult<Unit> = operationMutex.withLock {
        // 1. Lifecycle.
        if (_connectionState.value !is ConnectionState.Ready) {
            return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "restoreFootswitches requires Ready"),
            )
        }
        // 2. Session.
        val s = session ?: return@withLock TonexResult.Failure(
            TonexError.ProtocolStateViolation(_connectionState.value, "no session (internal invariant violated)"),
        )
        // 3. A footswitch snapshot must exist, AND must belong to the current session — see this
        //    function's KDoc for why this compares sessionId identity rather than mere presence.
        val snapshot = footswitchSnapshot?.takeIf { it.sessionId === s }
            ?: return@withLock TonexResult.Failure(TonexError.NoFootswitchSnapshotAvailable)

        // ---- MANDATORY re-read, immediately before the patch (PedalState's freshness contract),
        // identical to selectPreset's own re-read. ------------------------------------------------
        val fresh: PedalState = requestAndAwait(
            RequestStateMessage.encode(),
            timeouts.stateReadMillis,
            "state-read",
        ) { inb ->
            commonInbound("state-read", inb) ?: when (inb) {
                is Inbound.State -> inb.result // Success(PedalState) or Failure(OversizedStateBlob)
                else -> null
            }
        }.orReturn { return@withLock it }

        val bytes = fresh.copyOfBytes()
        val activeSlot = StateBlobReader.activeSlot(bytes).orReturn { return@withLock it }
        val currentActive = StateBlobReader.presetInSlot(bytes, activeSlot).orReturn { return@withLock it }
        val restoredActive = snapshot.presetFor(activeSlot)
        val patched = StateBlobPatcher.restoreSlotAssignments(fresh, s, snapshot.toMap()).orReturn { return@withLock it }

        // Restoring the currently-active slot's assignment changes what _activePreset reports only
        // when the snapshot's value for that slot actually differs from what it holds right now
        // (this `fresh` re-read) — see this function's KDoc for why an unconditional latch-arm here
        // (mirroring selectPreset's) is wrong for this function specifically. Set AFTER the re-read,
        // BEFORE the write, for the identical ordering reason selectPreset's own comment explains:
        // setting it earlier would cause the re-read's own StateUpdate (reporting the OLD active
        // preset) to be misread as self-initiated, suppressing a genuine ExternalPresetChange the
        // app is learning about for the first time.
        val changesActivePreset = restoredActive != currentActive
        if (changesActivePreset) selfInitiatedPreset = restoredActive
        writeFramed(SetStateMessage.encode(patched)).orReturn {
            if (changesActivePreset) selfInitiatedPreset = null
            return@withLock it
        }
        TonexResult.Success(Unit)
    }

    /**
     * ⚠️ DIAGNOSTIC-ONLY HOOK (issue #27 / S22, Opus review finding M1). Runs [block] while
     * holding the same [operationMutex] that [connect]/[selectPreset]/[setParameter]/
     * [revertActivePreset]/[restoreFootswitches] already serialize themselves against.
     *
     * ## Why this exists
     * `dev.tonexotg.protocol.diagnostics.SafetyDrill`'s raw, transport-level captures
     * (`RequestStateMessage`/`RequestPresetDetailsMessage`, sent directly over the transport
     * because this controller intentionally exposes no method for either — see that package's
     * KDoc) sit entirely outside this controller's own request/response bookkeeping. Without this
     * hook, one of those raw captures can race the post-preset-change snapshot capture
     * [applyStateUpdate] launches asynchronously on [scope] (`scope.launch { operationMutex.withLock
     * { captureSnapshotLocked(idx) } }`): both issue a `RequestPresetDetailsMessage` and both
     * correlate the response by message type only (`requestAndAwait`'s predicate here,
     * `MessageCaptureTap.awaitMessage`'s predicate there) — no per-request id exists to tell the
     * two apart, so either one can consume the response meant for the other. Acquiring the same
     * mutex for the drill's raw round trip closes that: whichever caller (this controller's own
     * launched capture, or the drill via this hook) gets the lock first completes its full
     * request-then-await round trip before the other can even issue its write.
     *
     * ## Not part of [dev.tonexotg.protocol.TonexController]
     * Deliberately not surfaced on the public interface the UI layer consumes — an ordinary
     * caller has no reason to hold this lock directly, and exposing it there would invite far
     * more dangerous misuse than a diagnostics-only drill reaching into this concrete class.
     *
     * ## [block] must never call back into this controller's own locked operations
     * [Mutex] is not reentrant: [block] calling [connect], [selectPreset], [setParameter],
     * [revertActivePreset], or [restoreFootswitches] — all of which acquire [operationMutex]
     * themselves — would deadlock this controller. [block] must only ever issue raw, read-only
     * requests directly over the transport (exactly what `SafetyDrill`'s capture functions do).
     */
    suspend fun <T> withOperationLock(block: suspend () -> T): T = operationMutex.withLock { block() }
}

/**
 * How many un-echoed writes to remember per `(kind, index)` key — see
 * `DefaultTonexController.pendingEchoes`. A drag conflates to a handful of writes at most, so this
 * is generous for the real case; its job is to bound the leak if some write path turns out not to
 * be echoed at all.
 */
private const val MAX_PENDING_ECHOES_PER_KEY: Int = 8

/**
 * `"B9 04 02 00 6D 88 00 00 00 00"` — uppercase, space-separated, one byte per group. Used for
 * [TonexEvent.UnroutableParameterNotification]'s raw payload, so an unroutable notification reaches
 * a debug dump in a form the next hardware session can decode by hand (issue #104).
 */
private fun ByteArray.toSpacedHex(): String = joinToString(" ") { "%02X".format(it) }
