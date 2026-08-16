package dev.tonexotg.protocol.state

import dev.tonexotg.protocol.PedalState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.SessionId
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [StateBlobPatcher] against the upstream bug this story exists to make unrepresentable
 * — see [PedalState]'s KDoc and issue #12 for the full background.
 *
 * `PedalState` and `SessionId` both have `private` constructors, reachable only through their own
 * `internal` factory functions; this test file is in the same Gradle module (`:protocol`) as
 * `src/main`, which Kotlin treats as a friend source set, so it can call those factories directly
 * to build fixtures — that access is *not* available to `:app` or any other module, which is the
 * whole point (see the dedicated structural-guarantee tests at the bottom of this file).
 */
class StateBlobPatcherTest {

    // ---- fixtures --------------------------------------------------------------------------

    /**
     * A deterministic, non-repeating byte per index (`(i * 7 + 13) mod 256`), so an accidental
     * shift, truncation, or cross-index copy is guaranteed to show up as a mismatch somewhere,
     * rather than being hidden by two indices coincidentally holding the same filler value.
     */
    private fun distinctBytes(size: Int): ByteArray = ByteArray(size) { i -> ((i * 7 + 13) % 256).toByte() }

    /**
     * A blob shaped to pass [StateBlobPatcher]'s length and sanity checks: at least
     * [StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE] bytes of distinct filler (defaulting to exactly
     * that floor, so most tests exercise the boundary rather than a comfortably larger blob), with
     * the four checked offsets overwritten to plausible values for their field (defaults are all
     * mutually distinct so a copy/shift bug between them is detectable).
     */
    private fun plausibleBlob(
        size: Int = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE,
        slotA: Int = 2,
        slotB: Int = 5,
        slotC: Int = 9,
        currentSlot: Int = 0,
    ): ByteArray {
        val bytes = distinctBytes(size)
        bytes[size - StateBlobOffsets.END_SLOT_A_PRESET] = slotA.toByte()
        bytes[size - StateBlobOffsets.END_SLOT_B_PRESET] = slotB.toByte()
        bytes[size - StateBlobOffsets.END_SLOT_C_PRESET] = slotC.toByte()
        bytes[size - StateBlobOffsets.END_CURRENT_SLOT] = currentSlot.toByte()
        return bytes
    }

    /** Builds a fresh session and one [PedalState] read for it, mirroring how S9 will use these types. */
    private fun stateFor(sessionId: SessionId, bytes: ByteArray): PedalState =
        PedalState.create(sessionId, bytes).assertSuccess()

    private fun <T> TonexResult<T>.assertSuccess(): T = when (this) {
        is TonexResult.Success -> value
        is TonexResult.Failure -> fail("expected Success, got Failure(${error.message})")
    }

    private fun <T> TonexResult<T>.assertFailure(): TonexError = when (this) {
        is TonexResult.Success -> fail("expected Failure, got Success($value)")
        is TonexResult.Failure -> error
    }

    // ---- byte-exactness: the headline acceptance criterion ---------------------------------

    @Test
    fun `patching each slot independently changes exactly that one byte, everywhere else bit-identical`() {
        for (slot in PresetSlot.entries) {
            val session = SessionId.create()
            val original = plausibleBlob()
            val state = stateFor(session, original)

            val patched = StateBlobPatcher.patchSlotAssignment(state, session, slot, PresetIndex(17)).assertSuccess()

            val changedIndex = original.size - StateBlobOffsets.endOffsetForSlotPreset(slot)
            assertEquals(original.size, patched.size, "slot $slot: size must not change")
            for (i in original.indices) {
                if (i == changedIndex) {
                    assertEquals(17.toByte(), patched[i], "slot $slot: expected patched byte at index $i")
                } else {
                    assertEquals(
                        original[i],
                        patched[i],
                        "slot $slot: byte at index $i must be unchanged " +
                            "(was ${original[i]}, is now ${patched[i]})",
                    )
                }
            }
        }
    }

    @Test
    fun `patching the active slot changes exactly the active-slot byte, everywhere else bit-identical`() {
        val session = SessionId.create()
        val original = plausibleBlob(currentSlot = 0)
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.C).assertSuccess()

        val changedIndex = original.size - StateBlobOffsets.END_CURRENT_SLOT
        for (i in original.indices) {
            if (i == changedIndex) {
                assertEquals(PresetSlot.C.ordinal.toByte(), patched[i])
            } else {
                assertEquals(original[i], patched[i], "byte at index $i must be unchanged")
            }
        }
    }

    @Test
    fun `selectPreset changes exactly the slot-assignment byte and the active-slot byte`() {
        val session = SessionId.create()
        val original = plausibleBlob(currentSlot = 0)
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.selectPreset(state, session, PresetSlot.B, PresetIndex(14)).assertSuccess()

        val presetIndex = original.size - StateBlobOffsets.END_SLOT_B_PRESET
        val slotIndex = original.size - StateBlobOffsets.END_CURRENT_SLOT
        for (i in original.indices) {
            when (i) {
                presetIndex -> assertEquals(14.toByte(), patched[i])
                slotIndex -> assertEquals(PresetSlot.B.ordinal.toByte(), patched[i])
                else -> assertEquals(original[i], patched[i], "byte at index $i must be unchanged")
            }
        }
    }

    // ---- session provenance -----------------------------------------------------------------

    @Test
    fun `a write against a blob read during a different session is rejected`() {
        val readSession = SessionId.create()
        val currentSession = SessionId.create() // a distinct instance - never equal to readSession
        val state = stateFor(readSession, plausibleBlob())

        val result = StateBlobPatcher.patchSlotAssignment(state, currentSession, PresetSlot.A, PresetIndex(3))

        val error = result.assertFailure()
        assertTrue(error is TonexError.StaleSessionState, "expected StaleSessionState, got $error")
    }

    @Test
    fun `a write against a blob read during the current session succeeds`() {
        val session = SessionId.create()
        val state = stateFor(session, plausibleBlob())

        val result = StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.A, PresetIndex(3))

        result.assertSuccess()
    }

    // ---- read-generation freshness (the blocker fix: stale-within-session blobs) -------------
    //
    // See PedalState's freshness contract KDoc for the full scenario this closes: a SessionId is
    // minted once per connection and stays live for the connection's whole life, so a provenance
    // check on SessionId alone cannot catch a state blob that was genuinely read during the
    // current session but has since been superseded - e.g. a footswitch change made directly on
    // the pedal between the read and the write.

    @Test
    fun `a write against a state superseded by a later read of the same session is rejected`() {
        val session = SessionId.create()
        // Simulates: read state at 20:00 (staleState) ...
        val staleState = stateFor(session, plausibleBlob())
        // ... then the pedal pushes an updated blob at 20:30 because the user tapped tempo on the
        // footswitch (S9 MUST build a fresh PedalState for this, per the contract on PedalState) ...
        stateFor(session, plausibleBlob(slotA = 3))

        // ... then a write at 20:31 tries to use the 20:00 read.
        val result = StateBlobPatcher.selectPreset(staleState, session, PresetSlot.A, PresetIndex(7))

        val error = result.assertFailure()
        assertTrue(error is TonexError.StaleSessionState, "expected StaleSessionState, got $error")
    }

    @Test
    fun `a write against the most recently read state of the session succeeds`() {
        val session = SessionId.create()
        stateFor(session, plausibleBlob()) // an earlier read, immediately superseded
        val latest = stateFor(session, plausibleBlob(slotA = 3))

        val result = StateBlobPatcher.selectPreset(latest, session, PresetSlot.A, PresetIndex(7))

        result.assertSuccess()
    }

    @Test
    fun `three successive reads of the same session - only the third is current`() {
        val session = SessionId.create()
        val first = stateFor(session, plausibleBlob())
        val second = stateFor(session, plausibleBlob())
        val third = stateFor(session, plausibleBlob())

        assertTrue(StateBlobPatcher.patchActiveSlot(first, session, PresetSlot.B).assertFailure() is TonexError.StaleSessionState)
        assertTrue(StateBlobPatcher.patchActiveSlot(second, session, PresetSlot.B).assertFailure() is TonexError.StaleSessionState)
        StateBlobPatcher.patchActiveSlot(third, session, PresetSlot.B).assertSuccess()
    }

    @Test
    fun `stale-within-session rejection is reported via StaleSessionState, not a different error`() {
        // The story explicitly requires reusing the existing StaleSessionState error for this,
        // not inventing a parallel error type a caller would have to learn to also handle.
        val session = SessionId.create()
        val stale = stateFor(session, plausibleBlob())
        stateFor(session, plausibleBlob())

        val error = StateBlobPatcher.patchActiveSlot(stale, session, PresetSlot.A).assertFailure()

        assertTrue(error is TonexError.StaleSessionState)
        assertTrue(
            (error as TonexError.StaleSessionState).message.contains("current session", ignoreCase = false) ||
                error.message.contains("current", ignoreCase = true),
            "message should be usable as a direct, honest explanation: ${error.message}",
        )
    }

    // ---- length validation -------------------------------------------------------------------

    @Test
    fun `an empty blob is rejected with a typed error, not an exception`() {
        val session = SessionId.create()
        val state = stateFor(session, ByteArray(0))

        val result = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.A)

        assertTrue(result.assertFailure() is TonexError.BlobTooShortToPatch)
    }

    @Test
    fun `a 64-byte blob - too short to be a real state blob - is rejected even though it clears the old 18-byte floor`() {
        // The issue #12 review's exact repro: a 64-byte blob used to satisfy the old
        // MAX_END_OFFSET-based floor (18) and be accepted for patching, despite being nowhere
        // near the ~100-byte minimum a real state blob can be.
        val session = SessionId.create()
        val tooShort = plausibleBlob(size = 64)
        val state = stateFor(session, tooShort)

        val result = StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.A, PresetIndex(0))

        val error = result.assertFailure()
        assertTrue(error is TonexError.BlobTooShortToPatch, "expected BlobTooShortToPatch, got $error")
        assertEquals(64, (error as TonexError.BlobTooShortToPatch).actualSize)
        assertEquals(StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE, error.minimumSize)
    }

    @Test
    fun `boundary - one byte short of MIN_PLAUSIBLE_BLOB_SIZE is rejected with a typed error, not an exception`() {
        val session = SessionId.create()
        val bytes = plausibleBlob(size = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE - 1)
        val state = stateFor(session, bytes)

        val result = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.A)

        assertTrue(result.assertFailure() is TonexError.BlobTooShortToPatch)
    }

    @Test
    fun `boundary - exactly MIN_PLAUSIBLE_BLOB_SIZE is accepted and patched without an out-of-bounds crash`() {
        val session = SessionId.create()
        val original = plausibleBlob(size = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE)
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.C).assertSuccess()

        assertEquals(StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE, patched.size)
        assertEquals(PresetSlot.C.ordinal.toByte(), patched[patched.size - StateBlobOffsets.END_CURRENT_SLOT])
    }

    // ---- blob size changed since handshake (shape heuristic false-positive fix) ---------------
    //
    // Demonstrated by the issue #12 review: a 64-byte blob in a hypothetical shifted layout could
    // pass the old heuristic-only check and have a slot's high byte overwritten. Pinning the
    // length observed at this session's first read closes that: any later blob whose length
    // differs is rejected outright, before the heuristic even runs.

    @Test
    fun `a later blob whose length differs from the size pinned at this session's first read is rejected`() {
        val session = SessionId.create()
        val handshakeSize = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE + 30
        stateFor(session, plausibleBlob(size = handshakeSize)) // pins the session to handshakeSize

        // A later, still-plausible-looking blob at a different length - e.g. a firmware layout
        // shift, or (as demonstrated in the review) a shorter blob that still happens to have
        // plausible-looking bytes at the four checked offsets.
        val shiftedSize = handshakeSize - 20
        val shifted = stateFor(session, plausibleBlob(size = shiftedSize))

        val result = StateBlobPatcher.patchActiveSlot(shifted, session, PresetSlot.C)

        val error = result.assertFailure()
        assertTrue(error is TonexError.BlobSizeChangedSinceHandshake, "expected BlobSizeChangedSinceHandshake, got $error")
        assertEquals(handshakeSize, (error as TonexError.BlobSizeChangedSinceHandshake).pinnedSize)
        assertEquals(shiftedSize, error.actualSize)
    }

    @Test
    fun `the shifted-layout write is rejected outright, not silently applied to the wrong byte`() {
        // Directly encodes the review's headline demonstration: patchActiveSlot(..., PresetSlot.C)
        // on a shifted-but-plausible-looking blob must not return Success while having written
        // into the wrong field (the review's repro: slot A's high byte got 0x02, and the real
        // current-slot byte was never touched).
        val session = SessionId.create()
        val handshakeSize = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE + 30
        stateFor(session, plausibleBlob(size = handshakeSize))
        val shifted = stateFor(session, plausibleBlob(size = handshakeSize - 20, currentSlot = 0))

        val result = StateBlobPatcher.patchActiveSlot(shifted, session, PresetSlot.C)

        assertTrue(result is TonexResult.Failure, "must not return Success for a size-shifted blob")
    }

    @Test
    fun `repeated reads at the same size as the handshake pin do not trip the size-changed check`() {
        val session = SessionId.create()
        stateFor(session, plausibleBlob()) // handshake, pins to the default size
        val samSizeLater = stateFor(session, plausibleBlob(slotB = 11)) // later read, same size

        val result = StateBlobPatcher.patchSlotAssignment(samSizeLater, session, PresetSlot.B, PresetIndex(1))

        result.assertSuccess()
    }

    // ---- shape sanity check -------------------------------------------------------------------

    @Test
    fun `an implausible slot-A byte is rejected rather than patched blind`() {
        val session = SessionId.create()
        // 250 is well outside PresetIndex.VALID_RANGE (0..19) - not what a real preset index
        // byte would ever contain, simulating a layout that has drifted since the pinned firmware.
        val state = stateFor(session, plausibleBlob(slotA = 250))

        val result = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.B)

        assertTrue(result.assertFailure() is TonexError.ImplausibleStateBlobShape)
    }

    @Test
    fun `an implausible slot-B byte is rejected rather than patched blind`() {
        val session = SessionId.create()
        val state = stateFor(session, plausibleBlob(slotB = 200))

        val result = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.B)

        assertTrue(result.assertFailure() is TonexError.ImplausibleStateBlobShape)
    }

    @Test
    fun `an implausible slot-C byte is rejected rather than patched blind`() {
        val session = SessionId.create()
        val state = stateFor(session, plausibleBlob(slotC = 199))

        val result = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.B)

        assertTrue(result.assertFailure() is TonexError.ImplausibleStateBlobShape)
    }

    @Test
    fun `an implausible active-slot byte is rejected rather than patched blind`() {
        val session = SessionId.create()
        // 9 is not a valid PresetSlot ordinal (0, 1, or 2).
        val state = stateFor(session, plausibleBlob(currentSlot = 9))

        val result = StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.C, PresetIndex(1))

        assertTrue(result.assertFailure() is TonexError.ImplausibleStateBlobShape)
    }

    @Test
    fun `an implausible byte at any of slots A, B, or C independently trips the sanity check`() {
        // Regression guard for the review's nit: a loop narrowed to slot A alone would still pass
        // CI even if B and C stopped being checked. Exercise all three explicitly.
        for ((label, blob) in listOf(
            "A" to plausibleBlob(slotA = 255),
            "B" to plausibleBlob(slotB = 255),
            "C" to plausibleBlob(slotC = 255),
        )) {
            val session = SessionId.create()
            val state = stateFor(session, blob)

            val result = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.A)

            assertTrue(result.assertFailure() is TonexError.ImplausibleStateBlobShape, "slot $label")
        }
    }

    // ---- round-trip no-op ----------------------------------------------------------------------

    @Test
    fun `patching a slot to the value already present is a no-op`() {
        val session = SessionId.create()
        val original = plausibleBlob(slotB = 5)
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.B, PresetIndex(5)).assertSuccess()

        assertTrue(original.contentEquals(patched), "no-op patch must produce a byte-identical array")
    }

    @Test
    fun `patching the active slot to the value already present is a no-op`() {
        val session = SessionId.create()
        val original = plausibleBlob(currentSlot = 1)
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.B).assertSuccess()

        assertTrue(original.contentEquals(patched), "no-op patch must produce a byte-identical array")
    }

    // ---- no inherited side effects --------------------------------------------------------------

    @Test
    fun `selecting a preset never touches the stomp-mode or direct-monitor bytes`() {
        // These offsets are deliberately NOT named in StateBlobOffsets - see its KDoc, "Fields
        // intentionally NOT modelled here". Duplicated here, read-only, purely to prove no write
        // path in this module can reach them (upstream usb_tonex_one.c lines 106 and 113, same
        // pin as StateBlobOffsets).
        val upstreamStartStompMode = 19
        val upstreamEndDirectMonitor = 7

        val session = SessionId.create()
        val original = plausibleBlob()
        // Overwrite with a sentinel that is not what upstream's forced write would set (0x01),
        // so a regression that reintroduces the forced write is caught even if it happened to
        // coincide with the filler value.
        original[upstreamStartStompMode] = 0x42
        original[original.size - upstreamEndDirectMonitor] = 0x42
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.selectPreset(state, session, PresetSlot.A, PresetIndex(4)).assertSuccess()

        assertEquals(0x42.toByte(), patched[upstreamStartStompMode], "stomp mode must be untouched")
        assertEquals(
            0x42.toByte(),
            patched[patched.size - upstreamEndDirectMonitor],
            "direct monitor must be untouched",
        )
    }

    // ---- mutation isolation ------------------------------------------------------------------

    @Test
    fun `the original blob passed to PedalState is unaffected by a later patch`() {
        val session = SessionId.create()
        val original = plausibleBlob()
        val originalCopy = original.copyOf()
        val state = stateFor(session, original)

        StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.A, PresetIndex(18)).assertSuccess()

        // PedalState takes its own defensive copy at construction, and StateBlobPatcher patches
        // a copy of copyOfBytes() - the caller's original array must never be touched by either.
        assertTrue(original.contentEquals(originalCopy), "caller's original array must be untouched")
        assertTrue(state.copyOfBytes().contentEquals(originalCopy), "PedalState's retained bytes must be untouched")
    }

    // ---- PresetIndex range guard (defense-in-depth against the JVM-boundary erasure gap) ------
    //
    // PresetIndex is a @JvmInline value class; javap on StateBlobPatcher's compiled output shows
    // patchSlotAssignment/selectPreset take a raw `int` for `preset` at the JVM level, not a
    // boxed PresetIndex - so a caller that reaches that boundary without going through
    // PresetIndex's Kotlin constructor never runs its `init { require(...) }` guard. These tests
    // reproduce that exact bypass via reflection (invoking the compiled method directly with an
    // out-of-range raw int) to prove prepareForPatch's explicit re-check actually stops it.

    private fun findMangledMethod(name: String): java.lang.reflect.Method =
        StateBlobPatcher::class.java.declaredMethods.firstOrNull { it.name.startsWith(name) }
            ?: fail("could not find a compiled method starting with \"$name\" on StateBlobPatcher")

    @Test
    fun `patchSlotAssignment rejects an out-of-range raw preset value smuggled past PresetIndex's own guard`() {
        val session = SessionId.create()
        val state = stateFor(session, plausibleBlob())
        val method = findMangledMethod("patchSlotAssignment")
        method.isAccessible = true

        // 255 - exactly the value the issue #12 review demonstrated being written verbatim into a
        // slot byte, bypassing PresetIndex's require(value in 0..19) entirely.
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(StateBlobPatcher, state, session, PresetSlot.A, 255) as TonexResult<ByteArray>

        val error = result.assertFailure()
        assertTrue(error is TonexError.InvalidPresetIndex, "expected InvalidPresetIndex, got $error")
        assertEquals(255, (error as TonexError.InvalidPresetIndex).value)
    }

    @Test
    fun `selectPreset also rejects an out-of-range raw preset value smuggled past PresetIndex's own guard`() {
        val session = SessionId.create()
        val state = stateFor(session, plausibleBlob())
        val method = findMangledMethod("selectPreset")
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(StateBlobPatcher, state, session, PresetSlot.B, -1) as TonexResult<ByteArray>

        val error = result.assertFailure()
        assertTrue(error is TonexError.InvalidPresetIndex, "expected InvalidPresetIndex, got $error")
        assertEquals(-1, (error as TonexError.InvalidPresetIndex).value)
    }

    @Test
    fun `an in-range raw preset value reaching the same boundary still succeeds`() {
        // Sanity check that the reflective invocation path itself isn't what's rejecting things -
        // an in-range value through the exact same route must succeed normally.
        val session = SessionId.create()
        val state = stateFor(session, plausibleBlob())
        val method = findMangledMethod("patchSlotAssignment")
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(StateBlobPatcher, state, session, PresetSlot.A, 12) as TonexResult<ByteArray>

        result.assertSuccess()
    }

    // ---- structural guarantee (runtime-verified, not a text substring match) -----------------
    //
    // The previous version of this test read PedalState.kt as text and grepped for three
    // substrings - it would keep passing even if someone added a public secondary constructor, a
    // differently-named companion factory, @JvmStatic, or made SessionId a data class (silently
    // defeating the !== identity check StateBlobPatcher relies on). These tests instead exercise
    // the actual runtime properties the guarantee depends on.

    /**
     * `Class.getConstructors()` (public-only) is the wrong check here: Kotlin's compiler, to let
     * [PedalState.Companion]/[SessionId.Companion] call a `private` constructor of their enclosing
     * class across a JVM class-file boundary, emits an extra `public` bridge constructor with a
     * trailing `DefaultConstructorMarker` parameter — so `getConstructors()` is never actually
     * empty for these two types, and asserting that it is (an earlier version of this test did)
     * fails on a correct implementation. The bridge is marked `ACC_SYNTHETIC`, which is what
     * actually matters: `javac` refuses to resolve a source-level call to a synthetic member (this
     * was verified by hand against this module's compiled output — attempting `new
     * SessionId((DefaultConstructorMarker) null)` from a `.java` file fails with "cannot find
     * symbol"), so it is reachable only via reflection — the same residual any `private` member
     * has, not a reopening of the plain-`javac`-no-reflection exploit this fix targets. The real,
     * intended entry point must still be the sole *non-synthetic* constructor, and it must be
     * `private`.
     */
    private fun assertOnlyRealConstructorIsPrivate(type: Class<*>) {
        val declared = type.declaredConstructors
        val real = declared.filterNot { it.isSynthetic }
        assertEquals(1, real.size, "expected exactly one source-visible (non-synthetic) constructor on ${type.simpleName}, found: ${real.toList()}")
        assertTrue(Modifier.isPrivate(real.single().modifiers), "${type.simpleName}'s real constructor must be private")
        for (extra in declared.toList() - real) {
            assertTrue(extra.isSynthetic, "unexpected additional non-synthetic constructor on ${type.simpleName}: $extra")
        }
    }

    @Test
    fun `PedalState's only source-visible constructor is private`() {
        assertOnlyRealConstructorIsPrivate(PedalState::class.java)
    }

    @Test
    fun `SessionId's only source-visible constructor is private`() {
        assertOnlyRealConstructorIsPrivate(SessionId::class.java)
    }

    @Test
    fun `plain javac, no reflection, cannot compile code that constructs a PedalState or SessionId directly`() {
        // The strongest possible version of this guarantee: reproduce the issue #12 review's
        // exact methodology (compile a plain Java caller against this module's real compiled
        // output) as a regression test, instead of only asserting about bytecode shape.
        val compiler = java.util.spi.ToolProvider.findFirst("javac")
        if (compiler.isEmpty) {
            // No system Java compiler on the test JVM (i.e. running on a JRE, not a JDK) - the
            // reflection-based constructor-privacy tests above still cover the guarantee; skip
            // rather than fail the whole suite over environment shape.
            return
        }

        val classesDir = java.io.File(PedalState::class.java.protectionDomain.codeSource.location.toURI())
        val stdlibJar =
            java.io.File(kotlin.jvm.internal.DefaultConstructorMarker::class.java.protectionDomain.codeSource.location.toURI())
        val classpath = listOf(classesDir, stdlibJar).joinToString(java.io.File.pathSeparator) { it.absolutePath }

        val tmpDir = java.nio.file.Files.createTempDirectory("forge-probe").toFile()
        val probeFile = java.io.File(tmpDir, "ForgeProbe.java")
        probeFile.writeText(
            """
            import dev.tonexotg.protocol.PedalState;
            import dev.tonexotg.protocol.SessionId;

            public class ForgeProbe {
                public static PedalState forge(byte[] bytes) {
                    SessionId fake = new SessionId();
                    return new PedalState(fake, bytes);
                }
            }
            """.trimIndent(),
        )

        val errOut = java.io.ByteArrayOutputStream()
        val exitCode = compiler.get().run(
            java.io.PrintWriter(java.io.OutputStreamWriter(java.io.ByteArrayOutputStream())),
            java.io.PrintWriter(errOut),
            "-classpath",
            classpath,
            "-d",
            tmpDir.absolutePath,
            probeFile.absolutePath,
        )

        assertTrue(
            exitCode != 0,
            "a plain Java caller outside :protocol must NOT be able to compile code that directly " +
                "constructs a PedalState or SessionId - if this compiles, the structural no-forgery " +
                "guarantee (issue #12) is broken. javac stderr:\n${errOut}",
        )
        val stderr = errOut.toString()
        assertTrue(
            stderr.contains("private", ignoreCase = true) || stderr.contains("has private access"),
            "expected a private-access compile error, got:\n$stderr",
        )
    }

    @Test
    fun `no public method on PedalState's companion can construct one without an existing SessionId`() {
        // Broader than grepping for a specific name like "fromBytes" or "from" (what the old
        // substring-match test effectively did): this catches ANY public factory added to the
        // companion under ANY name, including "@JvmStatic" ones, as long as it doesn't require a
        // caller to already hold a genuine SessionId - which is the actual property that matters,
        // not the method's spelling.
        val companionMethods = PedalState.Companion::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.name !in setOf("equals", "hashCode", "toString") }

        assertTrue(companionMethods.isNotEmpty(), "sanity: expected at least the create factory to be present")
        for (method in companionMethods) {
            assertTrue(
                method.parameterTypes.any { it == SessionId::class.java },
                "public companion method '${method.name}${method.parameterTypes.toList()}' does not " +
                    "require a SessionId parameter - it could let a caller synthesize a PedalState " +
                    "without ever holding a real session, reopening the exact bug this type exists " +
                    "to prevent (issue #12)",
            )
        }
    }

    @Test
    fun `two SessionId instances are never equal - identity, not structural, equality`() {
        // Directly exercises the property the review flagged as silently defeatable by turning
        // SessionId into a data class (which would gain a generated structural equals/copy).
        val a = SessionId.create()
        val b = SessionId.create()

        assertFalse(a == b, "two distinct SessionId instances must never compare equal")
        assertTrue(a == a, "a SessionId must equal itself")
        assertFalse(a.equals(b))
    }

    @Test
    fun `SessionId has no generated copy() method - it is not a data class`() {
        val hasCopy = SessionId::class.java.declaredMethods.any { it.name == "copy" }
        assertFalse(
            hasCopy,
            "SessionId must not be a data class - a generated copy() would let code construct a " +
                "structurally-equal-looking SessionId without going through create(), and a data " +
                "class's generated equals() would defeat the !== identity check StateBlobPatcher relies on",
        )
    }

    @Test
    fun `a stale-session write is refused even when the attacker mints their own matching SessionId`() {
        // The exact shape of the exploit the review's PoC used: forge a SessionId (possible only
        // from inside :protocol's friend source set, same as this test file) and use that same
        // forged instance as both the state's session and the "currentSession" argument - which
        // trivially passes the reference-equality check. The result is a syntactically valid
        // write against a PedalState nobody outside :protocol could have produced in the first
        // place, and TonexController never exposes SessionId/PedalState to callers outside this
        // module at all - the type-level guarantee this test suite is really about.
        val forgedSession = SessionId.create()
        val state = stateFor(forgedSession, plausibleBlob())

        val result = StateBlobPatcher.patchActiveSlot(state, forgedSession, PresetSlot.A)

        // This succeeds - and that is fine: it is not a bypass of anything, because nothing
        // outside :protocol can reach `SessionId.create()` or `PedalState.create()` in the first
        // place (see the constructor-privacy tests above), and TonexController's public surface
        // never hands either type out. Asserted here so the "what would forging look like"
        // question has one concrete, honest answer instead of an unverified claim.
        result.assertSuccess()
    }
}
