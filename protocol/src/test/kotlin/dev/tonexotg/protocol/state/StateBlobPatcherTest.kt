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
import kotlin.test.assertFailsWith
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
        assertTrue((error as TonexError.StaleSessionState).sameSession, "the stale blob IS from the current session")
    }

    // ---- generation-aware message accuracy (issue #12 round-3 review, LOW finding #1) ---------
    //
    // The round-2 message was hardcoded to "state blob is not from the current session" even in
    // the stale-generation case, where the blob demonstrably IS from the current session - it is
    // just no longer the current one. A test that only asserted the message contains the word
    // "current" was too weak to catch that (both the accurate and the inaccurate wording contain
    // "current"). These assert the actual accuracy for both cases.

    @Test
    fun `cross-session rejection message accurately says the blob is not from the current session`() {
        val readSession = SessionId.create()
        val currentSession = SessionId.create()
        val state = stateFor(readSession, plausibleBlob())

        val error = StateBlobPatcher.patchSlotAssignment(state, currentSession, PresetSlot.A, PresetIndex(3)).assertFailure()

        assertTrue(error is TonexError.StaleSessionState)
        error as TonexError.StaleSessionState
        assertFalse(error.sameSession, "this IS a genuinely cross-session rejection")
        assertTrue(
            error.message.contains("is not from the current session"),
            "cross-session message must say the blob isn't from the current session: ${error.message}",
        )
    }

    @Test
    fun `stale-generation rejection message does NOT falsely claim the blob is from a different session`() {
        val session = SessionId.create()
        val stale = stateFor(session, plausibleBlob())
        stateFor(session, plausibleBlob()) // supersedes `stale` within the same session

        val error = StateBlobPatcher.patchActiveSlot(stale, session, PresetSlot.A).assertFailure()

        assertTrue(error is TonexError.StaleSessionState)
        error as TonexError.StaleSessionState
        assertTrue(error.sameSession, "the blob IS from the current session, just not the current read")
        assertFalse(
            error.message.contains("is not from the current session"),
            "the blob genuinely IS from the current session - the message must not claim otherwise: ${error.message}",
        )
        assertTrue(
            error.message.contains("IS from the current session", ignoreCase = false) ||
                error.message.contains("current session", ignoreCase = true),
            "message should still name the session, accurately: ${error.message}",
        )
    }

    // ---- single-use write authorization (BLOCKER fix, issue #12 round-3 review) ----------------
    //
    // Live reproduction from the round-2 review, reproduced exactly: a PedalState previously
    // authorized an UNBOUNDED number of writes once obtained, because the generation check only
    // verified "is this the newest PedalState this module ever built" - not "has this specific
    // PedalState already been spent on a write." Both the session check and the (pre-fix)
    // generation check passed on a SECOND call reusing the same read, letting the second write
    // silently revert whatever the first write had just changed - the exact failure class (a
    // stale whole-device blob echoed back over an intervening change) this entire story exists to
    // prevent, just relocated from "across a footswitch press" to "across the module's own second
    // call."

    @Test
    fun `a PedalState is single-use for a write - reusing it for a second patch is rejected, not silently accepted`() {
        val session = SessionId.create()
        // read1 = create(session, blob): A=2 B=5 C=9, active=A, generation 1.
        val read1 = stateFor(session, plausibleBlob(slotA = 2, slotB = 5, slotC = 9, currentSlot = 0))

        // selectPreset(read1, session, A, 7) -> ACCEPTED, pedal now holds A=7.
        val firstWrite = StateBlobPatcher.selectPreset(read1, session, PresetSlot.A, PresetIndex(7))
        val firstBytes = firstWrite.assertSuccess()
        assertEquals(7.toByte(), firstBytes[firstBytes.size - StateBlobOffsets.END_SLOT_A_PRESET], "first write must actually set A=7")

        // selectPreset(read1, session, B, 11) -> reusing the SAME read1, no re-read in between.
        // Pre-fix this was ACCEPTED and echoed A=2 back (reverting the write above). Post-fix it
        // must be REJECTED outright.
        val secondWrite = StateBlobPatcher.selectPreset(read1, session, PresetSlot.B, PresetIndex(11))

        val error = secondWrite.assertFailure()
        assertTrue(error is TonexError.StaleSessionState, "expected StaleSessionState (spent PedalState), got $error")
        assertTrue((error as TonexError.StaleSessionState).sameSession, "read1 IS from the current session - it's just already spent")
    }

    @Test
    fun `single-use enforcement applies uniformly across patchSlotAssignment, patchActiveSlot, and selectPreset`() {
        // The blocker is about the shared prepareForPatch gate, not about selectPreset
        // specifically - prove all three public entry points are single-use.
        for (secondCall in listOf<(PedalState, SessionId) -> TonexResult<ByteArray>>(
            { state, session -> StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.B, PresetIndex(1)) },
            { state, session -> StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.B) },
            { state, session -> StateBlobPatcher.selectPreset(state, session, PresetSlot.B, PresetIndex(1)) },
        )) {
            val session = SessionId.create()
            val read1 = stateFor(session, plausibleBlob())

            StateBlobPatcher.patchSlotAssignment(read1, session, PresetSlot.A, PresetIndex(3)).assertSuccess()

            val second = secondCall(read1, session)
            assertTrue(second.assertFailure() is TonexError.StaleSessionState, "second call against the spent read1 must be rejected")
        }
    }

    @Test
    fun `after a PedalState is spent by one successful patch, a fresh re-read authorizes the next write`() {
        // The fix must fail closed on reuse, not brick the session - a genuine re-read (what S9
        // is required to do before every patch) must still work normally afterwards.
        val session = SessionId.create()
        val read1 = stateFor(session, plausibleBlob())
        StateBlobPatcher.selectPreset(read1, session, PresetSlot.A, PresetIndex(7)).assertSuccess()

        val read2 = stateFor(session, plausibleBlob(slotA = 7)) // S9 re-reads before the next write
        val result = StateBlobPatcher.selectPreset(read2, session, PresetSlot.B, PresetIndex(11))

        result.assertSuccess()
    }

    // ---- a FAILED create() attempt must also invalidate the caller's previous state (MEDIUM, round-4) --
    //
    // PedalState.create() previously only minted a fresh generation on the SUCCESS path. A failed
    // re-read (e.g. S9's mandatory pre-patch re-read hitting an oversized frame, a timeout, or a
    // CRC failure) left the caller's previously-held, still-valid PedalState fully authorized to
    // write with - so a caller that proceeded to patch anyway after a failed re-read would echo
    // the STALE state back over any intervening pedal change, exactly the failure class this story
    // exists to prevent, just reached via a failed observation instead of a stale one.

    @Test
    fun `a failed create() attempt invalidates a previously-held valid PedalState for patching`() {
        val session = SessionId.create()
        // A valid, successful read - normally fully authorized to write with.
        val valid = stateFor(session, plausibleBlob())

        // A failed create() attempt on the SAME session - e.g. S9's mandatory pre-patch re-read
        // came back oversized. Must fail, per PedalState.create's existing size cap.
        val failedReread = PedalState.create(session, ByteArray(PedalState.MAX_STATE_BYTES + 1))
        assertTrue(failedReread.assertFailure() is TonexError.OversizedStateBlob)

        // Proceeding to patch using the ORIGINAL valid PedalState must now be REJECTED - the
        // failed observation attempt must have advanced the generation and invalidated it, not
        // left it usable.
        val result = StateBlobPatcher.patchActiveSlot(valid, session, PresetSlot.A)

        val error = result.assertFailure()
        assertTrue(error is TonexError.StaleSessionState, "expected StaleSessionState, got $error")
        assertTrue((error as TonexError.StaleSessionState).sameSession, "the blob IS from the current session - it's just been invalidated by the failed re-read")
    }

    @Test
    fun `after a failed create() attempt, a genuine successful re-read still authorizes the next write normally`() {
        // The fix must fail closed on the pre-failure state, not brick the session - a real
        // successful re-read after the failure must still work.
        val session = SessionId.create()
        stateFor(session, plausibleBlob())

        PedalState.create(session, ByteArray(PedalState.MAX_STATE_BYTES + 1)) // failed re-read attempt

        val freshRead = stateFor(session, plausibleBlob(slotA = 3)) // a genuine successful re-read
        val result = StateBlobPatcher.patchActiveSlot(freshRead, session, PresetSlot.B)

        result.assertSuccess()
    }

    // ---- accurate spent-vs-superseded message (LOW finding #1, round-4) -----------------------
    //
    // Sequential reuse of a just-spent PedalState was previously caught by the same generic
    // "may have been superseded by a later read..." message used for every stale-generation
    // rejection - even though the actual cause here is that the CALLER'S OWN prior patch consumed
    // it, not an external/footswitch change. A dedicated "already used for a write" message
    // existed but was unreachable in this exact, common sequential-reuse case. These assert the
    // message reaching the caller is now accurate for each distinct cause.

    @Test
    fun `reusing a PedalState immediately after it was spent by this caller's own patch reports the spent-by-write message`() {
        val session = SessionId.create()
        val read1 = stateFor(session, plausibleBlob())
        StateBlobPatcher.selectPreset(read1, session, PresetSlot.A, PresetIndex(7)).assertSuccess()

        // Immediate reuse of the exact PedalState just spent - no intervening read.
        val error = StateBlobPatcher.selectPreset(read1, session, PresetSlot.B, PresetIndex(11)).assertFailure()

        assertTrue(error is TonexError.StaleSessionState)
        error as TonexError.StaleSessionState
        assertTrue(error.sameSession)
        assertTrue(
            error.message.contains("already used for a write") || error.message.contains("already been used"),
            "reusing a just-spent PedalState should report it was already used for a write, not a generic " +
                "superseded message: ${error.message}",
        )
    }

    @Test
    fun `a state superseded by a later read (not by this caller's own write) still reports the generic superseded message`() {
        val session = SessionId.create()
        val stale = stateFor(session, plausibleBlob())
        stateFor(session, plausibleBlob()) // a later read supersedes `stale` - `stale` was never spent by a write

        val error = StateBlobPatcher.patchActiveSlot(stale, session, PresetSlot.A).assertFailure()

        assertTrue(error is TonexError.StaleSessionState)
        error as TonexError.StaleSessionState
        assertTrue(error.sameSession)
        assertFalse(
            error.message.contains("already used for a write") || error.message.contains("already been used"),
            "a state that was never spent by a write must not be reported as already-used: ${error.message}",
        )
        assertTrue(
            error.message.contains("superseded"),
            "a genuinely superseded-by-a-later-read state should keep the generic superseded wording: ${error.message}",
        )
    }

    @Test
    fun `a state invalidated by a failed create() attempt (not spent by a write) reports the generic superseded message, not spent-by-write`() {
        val session = SessionId.create()
        val valid = stateFor(session, plausibleBlob())

        PedalState.create(session, ByteArray(PedalState.MAX_STATE_BYTES + 1)) // failed re-read, never spent by a write

        val error = StateBlobPatcher.patchActiveSlot(valid, session, PresetSlot.A).assertFailure()

        assertTrue(error is TonexError.StaleSessionState)
        error as TonexError.StaleSessionState
        assertFalse(
            error.message.contains("already used for a write") || error.message.contains("already been used"),
            "a state invalidated by a FAILED create() was never spent by a write - must not claim it was: ${error.message}",
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

    // ---- truncated first read must not permanently brick the session (HIGH fix, round-3) ------
    //
    // pinBlobSizeIfAbsent ran on every create() call with no minimum-size check and the pin was
    // one-way with no reset: create(session, ByteArray(0)) pinned the session's expected blob size
    // at 0, and every subsequent legitimate read - for the rest of the connection's life - was
    // then rejected as "size changed from 0 to N", recoverable only by a reconnect. Fix: only a
    // plausible-sized (>= MIN_PLAUSIBLE_BLOB_SIZE) read may pin the size at all.

    @Test
    fun `a truncated corrupt first read does not permanently brick every subsequent legitimate patch`() {
        val session = SessionId.create()
        // Simulates a truncated/corrupt first frame - e.g. a malformed read that reassembled down
        // to zero usable bytes.
        PedalState.create(session, ByteArray(0)).assertSuccess()

        // A subsequent, legitimate 120-byte read.
        val good = plausibleBlob(size = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE + 20)
        val state = stateFor(session, good)

        val result = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.C)

        // Pre-fix this was rejected forever with BlobSizeChangedSinceHandshake(pinnedSize = 0, ...).
        result.assertSuccess()
    }

    @Test
    fun `a too-short first read does not pin the session's blob size at all - a later legitimate size gets pinned instead`() {
        val session = SessionId.create()

        PedalState.create(session, ByteArray(0)).assertSuccess()
        assertEquals(null, session.pinnedBlobSize(), "a too-short blob must not establish the size pin")

        val goodState = stateFor(session, plausibleBlob())
        assertEquals(
            StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE,
            session.pinnedBlobSize(),
            "the first plausible-sized read must establish the pin",
        )

        // And a third read at that same pinned size continues to work normally.
        val thirdState = stateFor(session, plausibleBlob(slotB = 11))
        StateBlobPatcher.patchSlotAssignment(thirdState, session, PresetSlot.B, PresetIndex(2)).assertSuccess()
    }

    // ---- self-correcting pin: growth widens, only a genuine shrink is rejected (HIGH, round-4) --
    //
    // The round-3 fix only stopped a too-SHORT (< MIN_PLAUSIBLE_BLOB_SIZE) first read from
    // pinning at all - it did not stop a corrupt/partial read landing ANYWHERE in
    // [MIN_PLAUSIBLE_BLOB_SIZE, MAX_STATE_BYTES) from pinning and then permanently rejecting every
    // subsequent LEGITIMATE (larger) read for the rest of the connection, recoverable only by a
    // reconnect. Fix: the pin tracks the largest plausible size observed so far, not the first -
    // growth widens the pin (evidence the smaller pin was itself corrupt/truncated), and only a
    // shrink below the established pin is still treated as the "layout changed" signal.

    @Test
    fun `a corrupt short read that pins first does not permanently reject a legitimate larger read that follows - exact repro`() {
        val session = SessionId.create()

        // create(session, <corrupt 120-byte read>) -> pins at 120. 120 is >= MIN_PLAUSIBLE_BLOB_SIZE
        // (100) so it is "plausible enough" to pin under the round-3 fix, despite being corrupt.
        val corruptSize = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE + 20
        PedalState.create(session, ByteArray(corruptSize)).assertSuccess()
        assertEquals(corruptSize, session.pinnedBlobSize(), "the corrupt-but-plausible read pins first")

        // create(session, <legit 512-byte read>) -> pre-fix this was Failure(BlobSizeChangedSinceHandshake)
        // forever after. Post-fix the larger read must widen the pin and be accepted for patching.
        val legit = plausibleBlob(size = PedalState.MAX_STATE_BYTES)
        val legitState = stateFor(session, legit)
        assertEquals(
            PedalState.MAX_STATE_BYTES,
            session.pinnedBlobSize(),
            "a larger legitimate read must widen the pin, not be rejected against the smaller corrupt one",
        )

        val firstPatch = StateBlobPatcher.patchActiveSlot(legitState, session, PresetSlot.B)
        firstPatch.assertSuccess()

        // And legit reads keep succeeding after the widen - not reject-forever, and not a one-shot
        // fluke either.
        val again = stateFor(session, plausibleBlob(size = PedalState.MAX_STATE_BYTES, slotB = 11))
        StateBlobPatcher.patchSlotAssignment(again, session, PresetSlot.B, PresetIndex(3)).assertSuccess()
    }

    @Test
    fun `once the pin has widened to a larger size, a genuinely implausible later shrink is still caught`() {
        val session = SessionId.create()
        // Pin at the full 512-byte size via a legitimate read.
        stateFor(session, plausibleBlob(size = PedalState.MAX_STATE_BYTES))
        assertEquals(PedalState.MAX_STATE_BYTES, session.pinnedBlobSize())

        // A later read that is genuinely much shorter than the established pin - the real
        // "layout changed" signal this check exists to catch - must still be rejected, not
        // silently treated as a new, smaller pin.
        val shrunkSize = 150
        val shrunk = stateFor(session, plausibleBlob(size = shrunkSize))

        val result = StateBlobPatcher.patchActiveSlot(shrunk, session, PresetSlot.A)

        val error = result.assertFailure()
        assertTrue(error is TonexError.BlobSizeChangedSinceHandshake, "expected BlobSizeChangedSinceHandshake, got $error")
        assertEquals(PedalState.MAX_STATE_BYTES, (error as TonexError.BlobSizeChangedSinceHandshake).pinnedSize)
        assertEquals(shrunkSize, error.actualSize)
        assertEquals(
            PedalState.MAX_STATE_BYTES,
            session.pinnedBlobSize(),
            "a rejected shrink must not move the pin down",
        )
    }

    @Test
    fun `pinnedBlobSize only ever widens across repeated create() calls, never narrows`() {
        val session = SessionId.create()
        val sizes = listOf(150, 300, 200, 512, 250, 512)

        for (size in sizes) {
            PedalState.create(session, plausibleBlob(size = size)).assertSuccess()
        }

        assertEquals(512, session.pinnedBlobSize(), "the pin must land on the largest size ever observed")
    }

    // ---- accepted residual risk: widening is gated on size alone, not shape (round-5 review) ----
    //
    // pinOrWidenBlobSize (see SessionId's KDoc) widens on ANY plausible-SIZED read, with no check
    // that the bytes at that size actually look like a real state blob - that shape check
    // (looksLikeSlotRegion, below) only runs later, at patch time. This is documented as an
    // accepted residual risk rather than fixed here: closing it would mean PedalState.create()
    // consulting this package's field-layout knowledge directly, which is exactly the
    // circular-package-dependency / opacity problem round 4 fixed for a different constant. These
    // tests characterise the accepted behaviour precisely, so a future change to it is deliberate,
    // not accidental - and confirm the failure mode is a false reject (fail loud), never a silent
    // bad write.

    @Test
    fun `a later corrupt-but-oversized read still widens the pin even though it would fail the shape check - accepted risk, exact repro`() {
        val session = SessionId.create()

        // A real, correctly-shaped 200-byte read - patches fine.
        val legitSize = 200
        val legit = stateFor(session, plausibleBlob(size = legitSize))
        StateBlobPatcher.patchActiveSlot(legit, session, PresetSlot.B).assertSuccess()

        // A later, larger read that is corrupt in shape, not just size - implausible values at
        // every offset looksLikeSlotRegion checks. Nothing at PedalState.create()'s call site
        // evaluates shape, only length, so this still widens the pin.
        val corruptOversized = ByteArray(PedalState.MAX_STATE_BYTES) { 0xFF.toByte() }
        PedalState.create(session, corruptOversized).assertSuccess()
        assertEquals(
            PedalState.MAX_STATE_BYTES,
            session.pinnedBlobSize(),
            "documents the accepted risk: a shape-implausible oversized read still widens the pin",
        )

        // Every subsequent, genuinely legitimate 200-byte read is now rejected - not silently
        // patched wrong, but rejected loudly - until reconnect.
        val nowRejected = stateFor(session, plausibleBlob(size = legitSize, slotB = 11))
        val result = StateBlobPatcher.patchSlotAssignment(nowRejected, session, PresetSlot.B, PresetIndex(4))

        val error = result.assertFailure()
        assertTrue(
            error is TonexError.BlobSizeChangedSinceHandshake,
            "a legitimate read must be rejected loudly, not silently patched against the wrong pin - got $error",
        )
        assertEquals(PedalState.MAX_STATE_BYTES, (error as TonexError.BlobSizeChangedSinceHandshake).pinnedSize)
        assertEquals(legitSize, error.actualSize)
    }

    @Test
    fun `the shape-implausible oversized read that widened the pin is itself never usable to patch`() {
        // The residual risk is a false reject of later LEGITIMATE reads, never a bad write: the
        // corrupt read that caused the widening is, itself, still caught by the shape check at
        // patch time - it never becomes a successful write.
        val session = SessionId.create()
        val corruptOversized = ByteArray(PedalState.MAX_STATE_BYTES) { 0xFF.toByte() }
        val corruptState = stateFor(session, corruptOversized)

        val result = StateBlobPatcher.patchActiveSlot(corruptState, session, PresetSlot.A)

        assertTrue(
            result.assertFailure() is TonexError.ImplausibleStateBlobShape,
            "the corrupt read must never itself be usable to author a write, even though it widened the pin",
        )
    }

    @Test
    fun `a too-short blob is diagnosed as too-short, not as a pin mismatch, even when a pin already exists`() {
        // Reproduces the check-ordering bug: with a valid pin already established by an earlier
        // legitimate read, a later corrupt/truncated read is both "too short" AND "differs from
        // the pin" - the more useful diagnosis (too short) must win because it is checked first.
        val session = SessionId.create()
        stateFor(session, plausibleBlob()) // legitimate first read - pins the session

        val tooShort = PedalState.create(session, ByteArray(10)).assertSuccess()

        val result = StateBlobPatcher.patchActiveSlot(tooShort, session, PresetSlot.A)

        val error = result.assertFailure()
        assertTrue(
            error is TonexError.BlobTooShortToPatch,
            "expected BlobTooShortToPatch (min-length checked before the pin comparison), got $error",
        )
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
        // Fail loud-and-visible, per this project's own philosophy (CLAUDE.md), rather than a
        // silent `return` that lets this flagship test disappear with zero signal on a JRE-only
        // environment (issue #12 round-3 review, LOW finding #2) - a silently-passing green test
        // is exactly what "fail fast and loud" exists to rule out. Assumptions.assumeTrue reports
        // as a visible SKIPPED result (this module's testLogging is configured to show
        // "skipped" events, see protocol/build.gradle.kts) with an explicit reason, not a normal
        // pass.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            compiler.isPresent,
            "No system Java compiler (javac) available on this test JVM - probably running on a " +
                "JRE, not a JDK. This is the strongest version of the no-forgery guarantee test; " +
                "the reflection-based constructor-privacy tests above still cover the same " +
                "guarantee in this environment, but this specific test is being SKIPPED, not " +
                "silently passed.",
        )

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

        // This succeeds - and that is fine, but the reason is narrower than an earlier version of
        // this comment claimed. `SessionId.create()` and `PedalState.create()` are NOT unreachable
        // from outside :protocol in an absolute sense: `internal` compiles to a public,
        // name-mangled JVM method (e.g. `create$protocol`), Kotlin's mangling uses `$` (a legal
        // Java identifier character), and a caller who goes looking for that exact mangled name
        // can spell `SessionId.Companion.create$protocol()` /
        // `PedalState.Companion.create$protocol(...)` directly from plain `javac`-compiled Java
        // source with ZERO reflection - re-demonstrated in issue #12's round-3 review, correcting
        // this same overclaim for the second time (see [PedalState]'s KDoc for the matching
        // correction there). What actually makes this safe is narrower: unreachable from ordinary
        // Kotlin callers, and unreachable from Java without independently knowing the mangled
        // name; and `TonexController`'s public surface never hands a [SessionId] or [PedalState]
        // out to begin with, so there is no legitimate caller with a payload to build one from.
        // Not exploitable today - but that is a "nothing to reach it with" argument, not a
        // "cannot be reached" one, and this test should not overclaim the latter a third time.
        result.assertSuccess()
    }

    // ---- restoreSlotAssignments (issue #36) -------------------------------------------------

    @Test
    fun `restoreSlotAssignments changes exactly the three slot-assignment bytes, everywhere else bit-identical`() {
        val session = SessionId.create()
        val original = plausibleBlob(slotA = 0, slotB = 1, slotC = 2, currentSlot = 1)
        val state = stateFor(session, original)

        val target = mapOf(PresetSlot.A to PresetIndex(11), PresetSlot.B to PresetIndex(12), PresetSlot.C to PresetIndex(13))
        val patched = StateBlobPatcher.restoreSlotAssignments(state, session, target).assertSuccess()

        val changed = setOf(
            original.size - StateBlobOffsets.END_SLOT_A_PRESET,
            original.size - StateBlobOffsets.END_SLOT_B_PRESET,
            original.size - StateBlobOffsets.END_SLOT_C_PRESET,
        )
        assertEquals(original.size, patched.size)
        for (i in original.indices) {
            if (i in changed) continue
            assertEquals(original[i], patched[i], "byte at index $i must be unchanged (was ${original[i]}, is now ${patched[i]})")
        }
        assertEquals(11.toByte(), patched[original.size - StateBlobOffsets.END_SLOT_A_PRESET])
        assertEquals(12.toByte(), patched[original.size - StateBlobOffsets.END_SLOT_B_PRESET])
        assertEquals(13.toByte(), patched[original.size - StateBlobOffsets.END_SLOT_C_PRESET])
    }

    @Test
    fun `restoreSlotAssignments never touches the active-slot byte`() {
        val session = SessionId.create()
        val original = plausibleBlob(slotA = 0, slotB = 1, slotC = 2, currentSlot = 2) // active = C
        val state = stateFor(session, original)

        val target = mapOf(PresetSlot.A to PresetIndex(5), PresetSlot.B to PresetIndex(6), PresetSlot.C to PresetIndex(7))
        val patched = StateBlobPatcher.restoreSlotAssignments(state, session, target).assertSuccess()

        assertEquals(
            2.toByte(),
            patched[original.size - StateBlobOffsets.END_CURRENT_SLOT],
            "restoreSlotAssignments must never change which slot is active",
        )
    }

    @Test
    fun `restoreSlotAssignments is single-use, session-scoped, and length-checked identically to the other patch functions`() {
        for (secondCall in listOf<(PedalState, SessionId) -> TonexResult<ByteArray>>(
            { state, session ->
                StateBlobPatcher.restoreSlotAssignments(
                    state,
                    session,
                    mapOf(PresetSlot.A to PresetIndex(1), PresetSlot.B to PresetIndex(2), PresetSlot.C to PresetIndex(3)),
                )
            },
        )) {
            val session = SessionId.create()
            val read1 = stateFor(session, plausibleBlob())
            StateBlobPatcher.patchSlotAssignment(read1, session, PresetSlot.A, PresetIndex(4)).assertSuccess()

            val second = secondCall(read1, session)
            assertTrue(second.assertFailure() is TonexError.StaleSessionState, "second call against the spent read1 must be rejected")
        }

        // Cross-session rejection.
        val readSession = SessionId.create()
        val currentSession = SessionId.create()
        val crossState = stateFor(readSession, plausibleBlob())
        val crossResult = StateBlobPatcher.restoreSlotAssignments(
            crossState,
            currentSession,
            mapOf(PresetSlot.A to PresetIndex(1), PresetSlot.B to PresetIndex(2), PresetSlot.C to PresetIndex(3)),
        )
        assertTrue(crossResult.assertFailure() is TonexError.StaleSessionState)

        // Too-short blob rejection.
        val shortSession = SessionId.create()
        val shortState = stateFor(shortSession, ByteArray(0))
        val shortResult = StateBlobPatcher.restoreSlotAssignments(
            shortState,
            shortSession,
            mapOf(PresetSlot.A to PresetIndex(1), PresetSlot.B to PresetIndex(2), PresetSlot.C to PresetIndex(3)),
        )
        assertTrue(shortResult.assertFailure() is TonexError.BlobTooShortToPatch)
    }

    @Test
    fun `restoreSlotAssignments with a map missing a slot throws IllegalArgumentException, not a TonexResult failure`() {
        // FootswitchSnapshot's own constructor already guarantees a complete map; a caller reaching
        // this function with an incomplete one is an internal-invariant violation, not a runtime
        // condition a UI-facing TonexResult should have to model.
        val session = SessionId.create()
        val state = stateFor(session, plausibleBlob())

        assertFailsWith<IllegalArgumentException> {
            StateBlobPatcher.restoreSlotAssignments(state, session, mapOf(PresetSlot.A to PresetIndex(1), PresetSlot.B to PresetIndex(2)))
        }
    }

    /**
     * `restoreSlotAssignments`'s per-value out-of-range re-check IS reachable, unlike an earlier
     * version of this file's reasoning claimed — verified against the compiled bytecode rather than
     * assumed (Opus review, issue #36 round 1, SHOULD-FIX #3). `javap` on [PresetIndex] shows
     * `box-impl(int)` invoking the `private` constructor directly, never `constructor-impl(int)` —
     * the synthetic static method that actually runs `init { require(...) }` — so a caller who
     * invokes the compiled `box-impl` method via reflection gets a genuinely boxed `PresetIndex`
     * whose out-of-range `.value` never passed that check, fit to store directly as a `Map` value.
     * Same class of bypass the two `patchSlotAssignment`/`selectPreset` reflection tests above
     * already exercise for their own bare `PresetIndex` parameter — this is the `Map`-value-position
     * equivalent.
     */
    private fun boxedPresetIndexOf(value: Int): PresetIndex {
        val boxImpl = PresetIndex::class.java.getDeclaredMethod("box-impl", Int::class.javaPrimitiveType)
        boxImpl.isAccessible = true
        return boxImpl.invoke(null, value) as PresetIndex
    }

    @Test
    fun `restoreSlotAssignments rejects an out-of-range boxed PresetIndex smuggled past its own guard, before consuming the read generation`() {
        val session = SessionId.create()
        val state = stateFor(session, plausibleBlob())
        val forged = boxedPresetIndexOf(255)

        val result = StateBlobPatcher.restoreSlotAssignments(
            state,
            session,
            mapOf(PresetSlot.A to PresetIndex(1), PresetSlot.B to forged, PresetSlot.C to PresetIndex(3)),
        )

        val error = result.assertFailure()
        assertTrue(error is TonexError.InvalidPresetIndex, "expected InvalidPresetIndex, got $error")
        assertEquals(255, (error as TonexError.InvalidPresetIndex).value)

        // The read generation must NOT have been consumed - a genuine subsequent write against the
        // same state must still succeed, proving the invalid-value rejection happened before
        // prepareForPatch's generation-consuming step.
        StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.B).assertSuccess()
    }

    // ---- global-parameter writes (issue #83) --------------------------------------------------

    private fun floatBitsLe(bytes: ByteArray, index: Int): Int =
        (bytes[index].toInt() and 0xFF) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            ((bytes[index + 2].toInt() and 0xFF) shl 16) or
            ((bytes[index + 3].toInt() and 0xFF) shl 24)

    @Test
    fun `patchBpm writes exactly the 4-byte little-endian float at END_BPM, nothing else changes`() {
        val session = SessionId.create()
        val original = plausibleBlob()
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchBpm(state, session, 120f).assertSuccess()

        val index = original.size - StateBlobOffsets.END_BPM
        assertEquals(120f.toRawBits(), floatBitsLe(patched, index))
        for (i in original.indices) {
            if (i in index until index + 4) continue
            assertEquals(original[i], patched[i], "byte at index $i must be unchanged")
        }
    }

    @Test
    fun `patchInputTrim writes exactly the 4-byte little-endian float at START_INPUT_TRIM, nothing else changes`() {
        val session = SessionId.create()
        val original = plausibleBlob()
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchInputTrim(state, session, -6.5f).assertSuccess()

        val index = StateBlobOffsets.START_INPUT_TRIM
        assertEquals((-6.5f).toRawBits(), floatBitsLe(patched, index))
        for (i in original.indices) {
            if (i in index until index + 4) continue
            assertEquals(original[i], patched[i], "byte at index $i must be unchanged")
        }
    }

    @Test
    fun `patchInputTrim writes the exact literal bytes for -15f, confirmed against upstream and a captured blob`() {
        // Pins ENDIANNESS specifically, via literals independent of writeFloatLe's own shift
        // sequence (see StateBlobOffsets' "Endianness and anchor confirmed byte-by-byte" KDoc for
        // the upstream `usb_tonex_one.c` line-105 comment and the captured S22 hardware blob this
        // is checked against). The anchor (start-relative vs. end-relative) is pinned by that same
        // captured-blob evidence, not by this test.
        val session = SessionId.create()
        val state = stateFor(session, plausibleBlob())

        val patched = StateBlobPatcher.patchInputTrim(state, session, -15f).assertSuccess()

        val index = StateBlobOffsets.START_INPUT_TRIM
        assertEquals(0x00.toByte(), patched[index])
        assertEquals(0x00.toByte(), patched[index + 1])
        assertEquals(0x70.toByte(), patched[index + 2])
        assertEquals(0xC1.toByte(), patched[index + 3])
    }

    @Test
    fun `patchTuningReference writes the exact literal bytes for 440Hz, confirmed against a captured blob`() {
        // Pins ENDIANNESS specifically — see the sibling patchInputTrim literal test above.
        val session = SessionId.create()
        val original = plausibleBlob()
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchTuningReference(state, session, 440f).assertSuccess()

        val index = original.size - StateBlobOffsets.END_TUNING_REF
        assertEquals(0xB8.toByte(), patched[index])
        assertEquals(0x01.toByte(), patched[index + 1])
    }

    @Test
    fun `patchCabSimBypass writes exactly the one byte at START_CAB_BYPASS, nothing else changes`() {
        val session = SessionId.create()
        val original = plausibleBlob()
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchCabSimBypass(state, session, 1f).assertSuccess()

        val index = StateBlobOffsets.START_CAB_BYPASS
        assertEquals(1.toByte(), patched[index])
        for (i in original.indices) {
            if (i == index) continue
            assertEquals(original[i], patched[i], "byte at index $i must be unchanged")
        }
    }

    @Test
    fun `patchTempoSource writes exactly the one byte at END_TEMPO_SOURCE, nothing else changes`() {
        val session = SessionId.create()
        val original = plausibleBlob()
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchTempoSource(state, session, 1f).assertSuccess()

        val index = original.size - StateBlobOffsets.END_TEMPO_SOURCE
        assertEquals(1.toByte(), patched[index])
        for (i in original.indices) {
            if (i == index) continue
            assertEquals(original[i], patched[i], "byte at index $i must be unchanged")
        }
    }

    @Test
    fun `patchTuningReference writes exactly the 2-byte little-endian uint16 at END_TUNING_REF, nothing else changes`() {
        val session = SessionId.create()
        val original = plausibleBlob()
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchTuningReference(state, session, 442f).assertSuccess()

        val index = original.size - StateBlobOffsets.END_TUNING_REF
        val value = (patched[index].toInt() and 0xFF) or ((patched[index + 1].toInt() and 0xFF) shl 8)
        assertEquals(442, value)
        for (i in original.indices) {
            if (i in index..index + 1) continue
            assertEquals(original[i], patched[i], "byte at index $i must be unchanged")
        }
    }

    @Test
    fun `patchBypassMode writes exactly the one byte at END_BYPASS_MODE, nothing else changes`() {
        val session = SessionId.create()
        val original = plausibleBlob()
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchBypassMode(state, session, 1f).assertSuccess()

        val index = original.size - StateBlobOffsets.END_BYPASS_MODE
        assertEquals(1.toByte(), patched[index])
        for (i in original.indices) {
            if (i == index) continue
            assertEquals(original[i], patched[i], "byte at index $i must be unchanged")
        }
    }

    @Test
    fun `all six global-parameter patches are single-use, session-scoped, and length-checked identically to the other patch functions`() {
        for (secondCall in listOf<(PedalState, SessionId) -> TonexResult<ByteArray>>(
            { state, session -> StateBlobPatcher.patchBpm(state, session, 100f) },
            { state, session -> StateBlobPatcher.patchInputTrim(state, session, 0f) },
            { state, session -> StateBlobPatcher.patchCabSimBypass(state, session, 0f) },
            { state, session -> StateBlobPatcher.patchTempoSource(state, session, 0f) },
            { state, session -> StateBlobPatcher.patchTuningReference(state, session, 440f) },
            { state, session -> StateBlobPatcher.patchBypassMode(state, session, 0f) },
        )) {
            val session = SessionId.create()
            val read1 = stateFor(session, plausibleBlob())

            StateBlobPatcher.patchSlotAssignment(read1, session, PresetSlot.A, PresetIndex(3)).assertSuccess()

            val second = secondCall(read1, session)
            assertTrue(second.assertFailure() is TonexError.StaleSessionState, "second call against the spent read1 must be rejected")
        }
    }

    @Test
    fun `a too-short blob is rejected for every global-parameter patch, same as the slot patches`() {
        for (call in listOf<(PedalState, SessionId) -> TonexResult<ByteArray>>(
            { state, session -> StateBlobPatcher.patchBpm(state, session, 100f) },
            { state, session -> StateBlobPatcher.patchInputTrim(state, session, 0f) },
            { state, session -> StateBlobPatcher.patchCabSimBypass(state, session, 0f) },
            { state, session -> StateBlobPatcher.patchTempoSource(state, session, 0f) },
            { state, session -> StateBlobPatcher.patchTuningReference(state, session, 440f) },
            { state, session -> StateBlobPatcher.patchBypassMode(state, session, 0f) },
        )) {
            val session = SessionId.create()
            val state = stateFor(session, ByteArray(0))

            assertTrue(call(state, session).assertFailure() is TonexError.BlobTooShortToPatch)
        }
    }

    // ---- regression: preset selection/assignment never touches END_BYPASS_MODE ----------------
    //
    // END_BYPASS_MODE is now a real named constant in StateBlobOffsets (issue #83), reused for a
    // legitimate direct global-parameter write. This is the guarantee that naming it did not
    // reopen the side-effect the issue #27 fix stripped: selectPreset/patchSlotAssignment/
    // restoreSlotAssignments must still never touch this byte.

    @Test
    fun `selectPreset, patchSlotAssignment, and restoreSlotAssignments never touch END_BYPASS_MODE`() {
        val sentinel = 0x77.toByte()

        run {
            val session = SessionId.create()
            val original = plausibleBlob()
            original[original.size - StateBlobOffsets.END_BYPASS_MODE] = sentinel
            val state = stateFor(session, original)
            val patched = StateBlobPatcher.selectPreset(state, session, PresetSlot.A, PresetIndex(4)).assertSuccess()
            assertEquals(sentinel, patched[patched.size - StateBlobOffsets.END_BYPASS_MODE], "selectPreset must not touch END_BYPASS_MODE")
        }

        run {
            val session = SessionId.create()
            val original = plausibleBlob()
            original[original.size - StateBlobOffsets.END_BYPASS_MODE] = sentinel
            val state = stateFor(session, original)
            val patched = StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.B, PresetIndex(4)).assertSuccess()
            assertEquals(sentinel, patched[patched.size - StateBlobOffsets.END_BYPASS_MODE], "patchSlotAssignment must not touch END_BYPASS_MODE")
        }

        run {
            val session = SessionId.create()
            val original = plausibleBlob()
            original[original.size - StateBlobOffsets.END_BYPASS_MODE] = sentinel
            val state = stateFor(session, original)
            val target = mapOf(PresetSlot.A to PresetIndex(1), PresetSlot.B to PresetIndex(2), PresetSlot.C to PresetIndex(3))
            val patched = StateBlobPatcher.restoreSlotAssignments(state, session, target).assertSuccess()
            assertEquals(sentinel, patched[patched.size - StateBlobOffsets.END_BYPASS_MODE], "restoreSlotAssignments must not touch END_BYPASS_MODE")
        }
    }
}
