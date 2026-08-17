package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.ParameterScope
import dev.tonexotg.protocol.PedalState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetInfo
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
import dev.tonexotg.protocol.message.RequestMasterVolumeMessage
import dev.tonexotg.protocol.message.RequestPresetDetailsMessage
import dev.tonexotg.protocol.message.RequestStateMessage
import dev.tonexotg.protocol.message.SetStateMessage
import dev.tonexotg.protocol.message.SingleParameterPayloadCodec
import dev.tonexotg.protocol.message.TonexMessage
import dev.tonexotg.protocol.message.TonexMessageDecoder
import dev.tonexotg.protocol.params.ParameterRegistry
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
) : TonexController {

    // ---- state -----------------------------------------------------------------------------

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _activePreset = MutableStateFlow<PresetIndex?>(null)
    private val _presets = MutableStateFlow<List<PresetInfo>>(emptyList())
    private val _parameterValues = MutableStateFlow<Map<ParameterId, Float>>(emptyMap())
    private val _events = MutableSharedFlow<TonexEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val activePreset: StateFlow<PresetIndex?> = _activePreset.asStateFlow()
    override val presets: StateFlow<List<PresetInfo>> = _presets.asStateFlow()
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
     */
    private fun applyStateUpdate(state: PedalState) {
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
        }
    }

    /**
     * Applies a `ParameterChanged` notification to [parameterValues] — restrained to `index == 0`
     * (master volume) only. [SingleParameterPayloadCodec]'s KDoc is explicit that the nonzero-index
     * correspondence to [ParameterId]'s numbering is "plausible but unconfirmed" and not exercised
     * by upstream's own control flow; mapping a nonzero index here would be inventing protocol
     * truth. **Do not "fix" this restraint** — see issue #25 / S20 for the hardware probe that
     * would resolve it.
     */
    private fun applyParameterChanged(msg: TonexMessage.ParameterChanged) {
        val decoded = SingleParameterPayloadCodec.decode(msg.payload)
        if (decoded !is TonexResult.Success) return
        val p = decoded.value
        if (p.index != 0) return
        val spec = ParameterRegistry.byEnumName("MASTER_VOLUME") ?: return
        val decibels = MasterVolumeMessage.nativeToDecibels(p.value)
        _parameterValues.update { it + (spec.id to decibels) }
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
        snapshotStore.clear()
        _presets.value = emptyList()
        _activePreset.value = null
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
        session = SessionId.create() // ← THE ONLY SessionId.create() CALL SITE
        _activePreset.value = initialActive // first observation ⇒ no ExternalPresetChange
        _connectionState.value = ConnectionState.Ready

        // ---- Post-Ready harvest -----------------------------------------------------------------
        harvestPresetNames().orFinish { return@withLock it } // fatal — see harvestPresetNames' KDoc
        harvestMasterVolume() // best-effort, result ignored
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
                    inb is Inbound.Message && inb.message is TonexMessage.PresetDetails ->
                        TonexResult.Success((inb.message as TonexMessage.PresetDetails).payload)
                    else -> null // StateUpdate / Other / ParameterChanged interleaved: ignore, keep waiting
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

        val holdingSlot = assignments.entries.firstOrNull { it.value == index }?.key

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
        selfInitiatedPreset = index
        writeFramed(SetStateMessage.encode(patched)).orReturn {
            selfInitiatedPreset = null
            return@withLock it
        }
        TonexResult.Success(Unit)
    }

    // ---- setParameter() (§10.1) ----------------------------------------------------------------

    override suspend fun setParameter(id: ParameterId, value: Float): TonexResult<Unit> = operationMutex.withLock {
        if (_connectionState.value !is ConnectionState.Ready) {
            return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "setParameter requires Ready"),
            )
        }
        // Same @JvmInline ABI-erasure hazard as PresetIndex — re-validate explicitly.
        val spec = ParameterRegistry.byIndex(id.index)
            ?: return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(_connectionState.value, "parameter index ${id.index} is not in the registry"),
            )

        // Reject, do NOT clamp — TonexController.setParameter's contract is explicit about this.
        if (value < spec.min || value > spec.max) {
            return@withLock TonexResult.Failure(TonexError.ParameterValueOutOfRange(id, value, spec.min, spec.max))
        }

        val encoded: ByteArray = when {
            spec.scope == ParameterScope.PRESET ->
                ParameterWriteMessage.encode(id, value, capabilities).orReturn { return@withLock it }
            spec.enumName == "MASTER_VOLUME" ->
                MasterVolumeMessage.encode(value, capabilities).orReturn { return@withLock it }
            else -> return@withLock TonexResult.Failure(
                TonexError.ProtocolStateViolation(
                    _connectionState.value,
                    "${spec.enumName} is a global parameter other than master volume; :protocol has no write " +
                        "path for it — upstream writes those by patching the full state blob at offsets " +
                        "StateBlobOffsets deliberately does not model",
                ),
            )
        }

        writeFramed(encoded).orReturn { return@withLock it }
        _parameterValues.update { it + (id to value) } // AFTER success only
        TonexResult.Success(Unit)
    }

    // ---- revertActivePreset() (§10.2) — S9's deliberate stopping point --------------------------

    /**
     * S9 implements this as a complete, permanent four-guard chain that returns a real typed
     * [TonexResult.Failure] — no `TODO()`, no throw, no stub, and no misuse of an existing error
     * case. S9b (issue #14) adds exactly one step to the end (replaying a captured [dev.tonexotg.protocol.PresetSnapshot]
     * as per-parameter writes) — steps 1-4 below are the permanent implementation and do not
     * change when that lands.
     *
     * Step 4 (snapshot lookup) is structurally unreachable to a `Success` in S9: nothing in S9
     * calls [SnapshotStore.record], so [SnapshotStore.snapshotFor] always returns `null` and this
     * always ends at [TonexError.NoSnapshotAvailable]. This is not a placeholder to "fix" — both
     * non-snapshot guards (steps 2 and 3) remain independently reachable and tested, because
     * [capabilities] is injected.
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
        // 4. A snapshot must exist for it.
        snapshotStore.snapshotFor(active)
            ?: return@withLock TonexResult.Failure(TonexError.NoSnapshotAvailable(active))

        // 5. ── S9b INSERTS THE REPLAY HERE ──
        //    Unreachable in S9: step 4 above always returns at the Failure, since nothing in S9
        //    calls snapshotStore.record(). S9b replays the snapshot as per-parameter writes via
        //    setParameter's encode path — NEVER a whole-state write (issue #14).
        TonexResult.Failure(TonexError.NoSnapshotAvailable(active)) // unreachable; see above.
    }
}
