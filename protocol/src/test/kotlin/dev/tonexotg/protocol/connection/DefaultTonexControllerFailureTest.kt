@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.framing.HdlcFrame
import dev.tonexotg.protocol.message.FirmwareCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Step 10 (final) of the S9 build order: per-stage timeouts, out-of-order/uncatalogued messages,
 * and malformed frames mid-handshake, per §11.3/§11.4 of the architecture plan.
 */
class DefaultTonexControllerFailureTest {

    // ---- per-stage timeouts, with exact operation strings and the §6.8 Error-vs-Idle routing ----

    @Test
    fun `hello timeout - Failure(Timeout('hello')), state becomes Error, no real waiting`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.helloMillis + 1)
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.Timeout>(error)
        assertEquals("hello", (error as TonexError.Timeout).operation)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
        assertTrue(testScheduler.currentTime < 60_000L, "must not have actually waited in real terms")
    }

    @Test
    fun `get-state timeout - Failure(Timeout('get-state')), state becomes Error`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.getStateMillis + 1)
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.Timeout>(error)
        assertEquals("get-state", (error as TonexError.Timeout).operation)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
    }

    @Test
    fun `preset-details timeout - Failure(Timeout('preset-details')), state becomes Error, presets stays empty`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        testScheduler.runCurrent() // preset 0's request written, awaiting response
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.presetDetailsMillis + 1)
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.Timeout>(error)
        assertEquals("preset-details", (error as TonexError.Timeout).operation)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
        assertTrue(controller.presets.value.isEmpty())
    }

    @Test
    fun `state-read timeout during selectPreset - Failure(Timeout('state-read')), connectionState stays Ready`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()

        val selectDeferred = async { controller.selectPreset(PresetIndex(5)) }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.stateReadMillis + 1)
        testScheduler.runCurrent()

        val result = selectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.Timeout>(error)
        assertEquals("state-read", (error as TonexError.Timeout).operation)
        // Unlike a handshake-stage timeout, a Ready-state operation's own timeout must NOT tear
        // down the connection - it stays exactly Ready (§9's orReturn, not connect()'s orFinish).
        assertEquals(ConnectionState.Ready, controller.connectionState.value)
    }

    @Test
    fun `master-volume timeout - connect() still Succeeds, key stays absent, state stays Ready`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )

        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        testScheduler.runCurrent() // RequestMasterVolume written, awaiting response
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.masterVolumeMillis + 1)
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)
        assertEquals(ConnectionState.Ready, controller.connectionState.value)
    }

    @Test
    fun `transport-write timeout - Failure(Timeout('transport-write')), state becomes Idle (not Error)`() = runTest {
        val fake = FakeTonexTransport()
        fake.writeHangs = true
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.transportWriteMillis + 1)
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.Timeout>(error)
        assertEquals("transport-write", (error as TonexError.Timeout).operation)
        // Write-side failure is transport-shaped, per §6.8 -> Idle, despite being a Timeout.
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
    }

    // ---- out-of-order / unexpected message types rejected without corrupting state -------------

    @Test
    fun `an unexpected message during Hello fails with ProtocolStateViolation naming Hello, nothing minted`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob())) // wrong stage entirely
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ProtocolStateViolation>(error)
        assertEquals(ConnectionState.Hello, (error as TonexError.ProtocolStateViolation).state)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
        assertTrue(controller.presets.value.isEmpty())
        assertEquals(null, controller.activePreset.value)
    }

    @Test
    fun `an unexpected message during GetState fails with ProtocolStateViolation naming GetState`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse()) // a second Hello, unexpected at GetState
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ProtocolStateViolation>(error)
        assertEquals(ConnectionState.GetState, (error as TonexError.ProtocolStateViolation).state)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
    }

    @Test
    fun `an uncatalogued wire ID is dropped at Hello, GetState, and Ready - handshake still completes`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        fake.emitMessage(uncatalogued()) // dropped at Hello
        testScheduler.runCurrent()
        fake.emitMessage(helloResponse())
        testScheduler.runCurrent()
        fake.emitMessage(uncatalogued()) // dropped at GetState
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        for (i in PresetIndex.VALID_RANGE) {
            testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummary("Preset $i"))
        }
        testScheduler.runCurrent()
        val result = connectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)

        fake.emitMessage(uncatalogued()) // dropped at Ready
        testScheduler.runCurrent()
        assertEquals(ConnectionState.Ready, controller.connectionState.value, "an uncatalogued message must never drop the connection")
    }

    // ---- malformed frame mid-handshake surfaces an error and does not hang ---------------------

    @Test
    fun `a CRC-corrupt frame during Hello fails immediately with CrcMismatch, before helloMillis elapses`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        val corrupt = HdlcFrame.encode(helloResponse()).also {
            it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte()
        }
        fake.emitRaw(corrupt)
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.CrcMismatch>(error)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
        assertTrue(
            testScheduler.currentTime < ConnectionTimeouts.DEFAULT.helloMillis,
            "must resolve before the hello timeout elapses - this is what 'does not hang' means",
        )
    }

    @Test
    fun `a frame with a malformed header (bad B9 03 prefix) fails with MalformedFrame`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        val badHeader = HdlcFrame.encode(byteArrayOf(0x00, 0x00, 0x02, 0x00, 0x00, 0x00))
        fake.emitRaw(badHeader)
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.MalformedFrame>(error)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
    }

    @Test
    fun `a declared-size mismatch fails with UnexpectedBlobShape`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        // Valid B9 03 prefix + type(0x02) + declared size 99, but far fewer bytes actually follow.
        val badSize = HdlcFrame.encode(
            byteArrayOf(0xB9.toByte(), 0x03, 0x02, 0x82.toByte(), 99, 0x00, 0x80.toByte(), 0x0B, 0x03, 0x01, 0x02),
        )
        fake.emitRaw(badSize)
        testScheduler.runCurrent()

        val result = connectDeferred.await()
        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.UnexpectedBlobShape>(error)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
    }

    @Test
    fun `a dangling-escape frame fails with MalformedFrame`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val connectDeferred = async { controller.connect(fake) }
        testScheduler.runCurrent()
        // 0x7E <content...> 0x7D 0x7E - a dangling escape immediately before the closing delimiter.
        val danglingEscape = byteArrayOf(0x7E, 0x01, 0x02, 0x7D, 0x7E)
        fake.emitRaw(danglingEscape)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ConnectionTimeouts.DEFAULT.helloMillis + 1)
        testScheduler.runCurrent()

        // A dangling escape is reported by FrameReassembler as one MalformedFrame item, but the
        // 0x7E that follows it still opens a fresh frame - no complete frame follows in this test,
        // so the hello stage simply times out afterward rather than surfacing the dangling-escape
        // error directly (there's nothing after it for the reader to report against an in-flight
        // await). Assert the connection still resolves loudly, not by hanging.
        val result = connectDeferred.await()
        assertIs<TonexResult.Failure>(result)
        assertIs<ConnectionState.Error>(controller.connectionState.value)
    }

    // ---- connect() while not Idle ---------------------------------------------------------------

    @Test
    fun `connect while already Ready is rejected, the existing connection is untouched`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake)
        connectDeferred.await()
        assertEquals(ConnectionState.Ready, controller.connectionState.value)

        val secondFake = FakeTonexTransport()
        val secondResult = controller.connect(secondFake)

        val error = (secondResult as TonexResult.Failure).error
        assertIs<TonexError.ProtocolStateViolation>(error)
        assertEquals(ConnectionState.Ready, (error as TonexError.ProtocolStateViolation).state)
        // The original connection is untouched.
        assertEquals(ConnectionState.Ready, controller.connectionState.value)
        assertEquals(20, controller.presets.value.size)
        assertTrue(!secondFake.closed, "the rejected second transport was never even attached")
    }

    // ---- short write is a hard failure -----------------------------------------------------------

    @Test
    fun `a short write fails with TransportFailure and lands on Idle`() = runTest {
        val fake = FakeTonexTransport()
        fake.writeBehavior = { it.size - 1 }
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)

        val result = controller.connect(fake)

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.TransportFailure>(error)
        assertEquals(ConnectionState.Idle, controller.connectionState.value)
    }

    // ---- invalidateCurrentRead closes the load-bearing window (§8) ------------------------------

    /**
     * §11.4 of the architecture plan names this test and explicitly anticipates this exact
     * outcome: *"If this proves non-deterministic under virtual time, mark it `@Ignore` with the
     * reason spelled out, and file the exact resolving observation on #25 — per CLAUDE.md, do not
     * silently pin or drop it."*
     *
     * ## Why this cannot be constructed deterministically under a single-threaded test dispatcher
     *
     * The window §8 describes as load-bearing is the gap between `requestAndAwait` returning a
     * [dev.tonexotg.protocol.PedalState] inside `selectPreset` and that same local being consumed
     * by [dev.tonexotg.protocol.state.StateBlobPatcher] a few lines later — and there is **no
     * suspension point anywhere in that gap**: `StateBlobReader.slotAssignments`/`activeSlot` and
     * `StateBlobPatcher.selectPreset`/`patchActiveSlot` are all synchronous. Kotlin's cooperative,
     * single-threaded `TestDispatcher` never preempts mid-synchronous-execution — there is no way
     * to schedule the reader coroutine's processing of a corrupt frame to land *inside* that gap,
     * no matter how the emissions in this test are sequenced or interleaved; any frame emitted
     * before `selectPreset` starts is processed (and its invalidation effect fully resolved)
     * before the re-read's `PedalState` is ever handed back, and any frame emitted after
     * `selectPreset` has already returned is too late to matter. Attempting the scenario the test
     * name describes here produces a `Success` (proved below), not the intended `StaleSessionState`.
     *
     * This gap **is** real for production use, where `scope` may be backed by a genuinely
     * multi-threaded dispatcher (e.g. `Dispatchers.Default`) — a JVM thread can be preempted at
     * any point, including mid-synchronous-code, so the reader coroutine (on one thread) racing
     * `selectPreset`'s synchronous gap (on another) is a real scenario there. It is specifically
     * the *deterministic, single-threaded test* reproduction that is infeasible, not the
     * production behaviour itself — `session?.invalidateCurrentRead()` and the freshness check it
     * feeds remain in place and exercised by every other test in this suite; only this one
     * *specific interleaving* is untestable without real thread-level concurrency.
     */
    @Ignore(
        "Cannot be constructed deterministically under a single-threaded TestDispatcher - see this " +
            "test's KDoc. Filed on issue #25 per S9_ARCHITECTURE_PLAN.md §11.4 / CLAUDE.md's rule " +
            "against silently pinning or dropping an untestable-without-hardware-or-real-threads finding.",
    )
    @Test
    fun `a good StateUpdate immediately followed by a CRC-bad frame invalidates the read - selectPreset sees StaleSessionState`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()

        // An unsolicited good push (e.g. a footswitch tap) lands first...
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()
        // ...immediately followed by a CRC-corrupt frame, which invalidates the session's current
        // read generation per SessionId.invalidateCurrentRead's contract (§8).
        val corrupt = HdlcFrame.encode(stateUpdateMessage(plausibleBlob())).also {
            it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte()
        }
        fake.emitRaw(corrupt)
        testScheduler.runCurrent()

        val writesBefore = fake.writtenMessages().size
        val selectDeferred = async { controller.selectPreset(PresetIndex(5)) }
        testScheduler.runCurrent()
        // The re-read's own response arrives, carrying a now-stale generation relative to what the
        // corrupt frame already invalidated - StateBlobPatcher's freshness check must reject it.
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()

        val result = selectDeferred.await()
        // Either the re-read itself is rejected as stale by the time it's used to patch, or the
        // patch attempt fails - both are acceptable outcomes of "no state write happens here";
        // assert the stronger, specific claim the contract promises: no SetState write occurred.
        assertIs<TonexResult.Failure>(result)
        val newWrites = fake.writtenMessages().drop(writesBefore)
        assertTrue(
            newWrites.none { it.header.type == dev.tonexotg.protocol.codec.MessageType.StateUpdate },
            "no state write must occur when the mandatory re-read's generation was invalidated in-flight",
        )
    }
}
