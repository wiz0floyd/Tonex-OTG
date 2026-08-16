package dev.tonexotg.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [PedalState] itself — the cap on how large a blob it will accept, the defensive
 * copies it takes and hands back, and the per-read [ReadGeneration] it stamps.
 *
 * Before this file existed there was no dedicated coverage at all for
 * [PedalState.MAX_STATE_BYTES], the defensive-copy behaviour, or the [PedalState.size] accessor
 * (issue #12 review) — every other test that touched [PedalState] did so only incidentally, via
 * [dev.tonexotg.protocol.state.StateBlobPatcherTest].
 */
class PedalStateTest {

    private fun <T> TonexResult<T>.assertSuccess(): T = when (this) {
        is TonexResult.Success -> value
        is TonexResult.Failure -> fail("expected Success, got Failure(${error.message})")
    }

    private fun <T> TonexResult<T>.assertFailure(): TonexError = when (this) {
        is TonexResult.Success -> fail("expected Failure, got Success($value)")
        is TonexResult.Failure -> error
    }

    // ---- MAX_STATE_BYTES cap -----------------------------------------------------------------

    @Test
    fun `a blob exactly at MAX_STATE_BYTES is accepted`() {
        val session = SessionId.create()
        val bytes = ByteArray(PedalState.MAX_STATE_BYTES) { it.toByte() }

        val state = PedalState.create(session, bytes).assertSuccess()

        assertEquals(PedalState.MAX_STATE_BYTES, state.size)
    }

    @Test
    fun `a blob one byte over MAX_STATE_BYTES is rejected with a typed error, not an exception`() {
        val session = SessionId.create()
        val bytes = ByteArray(PedalState.MAX_STATE_BYTES + 1) { it.toByte() }

        val result = PedalState.create(session, bytes)

        val error = result.assertFailure()
        assertTrue(error is TonexError.OversizedStateBlob, "expected OversizedStateBlob, got $error")
        assertEquals(PedalState.MAX_STATE_BYTES, (error as TonexError.OversizedStateBlob).maxSize)
        assertEquals(PedalState.MAX_STATE_BYTES + 1, error.actualSize)
    }

    @Test
    fun `a 513-byte blob is rejected exactly as the issue #12 review demonstrated`() {
        // The review's own repro case: a 513-byte array used to blow past `require()` as an
        // uncaught IllegalArgumentException instead of surfacing through TonexResult.
        val session = SessionId.create()
        val bytes = ByteArray(513)

        val result = PedalState.create(session, bytes)

        assertTrue(result.assertFailure() is TonexError.OversizedStateBlob)
    }

    @Test
    fun `an empty blob is accepted - zero is not oversized`() {
        val session = SessionId.create()

        val state = PedalState.create(session, ByteArray(0)).assertSuccess()

        assertEquals(0, state.size)
        assertTrue(state.copyOfBytes().isEmpty())
    }

    // ---- size accessor ------------------------------------------------------------------------

    @Test
    fun `size reflects the constructing byte array's length`() {
        val session = SessionId.create()

        for (length in listOf(0, 1, 64, 100, 511, 512)) {
            val state = PedalState.create(session, ByteArray(length)).assertSuccess()
            assertEquals(length, state.size, "size for a $length-byte blob")
        }
    }

    // ---- defensive copies ----------------------------------------------------------------------

    @Test
    fun `mutating the caller's original array after construction does not affect the retained state`() {
        val session = SessionId.create()
        val original = ByteArray(32) { it.toByte() }

        val state = PedalState.create(session, original).assertSuccess()
        original.fill(0x42)

        assertFalse(
            state.copyOfBytes().contentEquals(original),
            "PedalState must have taken its own copy at construction, not aliased the caller's array",
        )
        assertEquals((0 until 32).map { it.toByte() }, state.copyOfBytes().toList())
    }

    @Test
    fun `mutating one copyOfBytes() result does not affect a later call`() {
        val session = SessionId.create()
        val state = PedalState.create(session, ByteArray(16) { it.toByte() }).assertSuccess()

        val firstCopy = state.copyOfBytes()
        firstCopy.fill(0x7F)
        val secondCopy = state.copyOfBytes()

        assertFalse(firstCopy.contentEquals(secondCopy), "mutating one returned copy must not leak into another")
        assertEquals((0 until 16).map { it.toByte() }, secondCopy.toList())
    }

    // ---- readGeneration ------------------------------------------------------------------------

    @Test
    fun `readGeneration is distinct and increasing across successive reads of the same session`() {
        val session = SessionId.create()

        val first = PedalState.create(session, ByteArray(10)).assertSuccess()
        val second = PedalState.create(session, ByteArray(10)).assertSuccess()
        val third = PedalState.create(session, ByteArray(10)).assertSuccess()

        assertNotEquals(first.readGeneration, second.readGeneration)
        assertNotEquals(second.readGeneration, third.readGeneration)
        assertTrue(
            third.readGeneration.value > second.readGeneration.value &&
                second.readGeneration.value > first.readGeneration.value,
            "each successive PedalState.create for the same session must mint a strictly later generation",
        )
    }

    @Test
    fun `readGeneration is independent per session - a fresh session starts its own sequence`() {
        val sessionA = SessionId.create()
        val sessionB = SessionId.create()

        PedalState.create(sessionA, ByteArray(4)).assertSuccess()
        PedalState.create(sessionA, ByteArray(4)).assertSuccess()
        val firstForB = PedalState.create(sessionB, ByteArray(4)).assertSuccess()

        // sessionB's first read must not somehow inherit sessionA's counter position - each
        // SessionId has its own independent generation sequence.
        assertEquals(1L, firstForB.readGeneration.value)
    }

    // ---- generation advances on failed create() too (MEDIUM, round-4) -------------------------
    //
    // Previously mintReadGeneration() was only called on the success path, so a failed create()
    // (oversized blob, or any other rejection) left the session's generation counter untouched -
    // a caller holding a previously-successful PedalState could still use it to authorize a write
    // after a failed re-read, reopening the stale-whole-device-echo bug this story exists to
    // prevent. See StateBlobPatcherTest for the end-to-end repro through StateBlobPatcher.

    @Test
    fun `a failed create() attempt (oversized blob) still advances the session's generation`() {
        val session = SessionId.create()
        val before = session.latestReadGeneration()

        val result = PedalState.create(session, ByteArray(PedalState.MAX_STATE_BYTES + 1))

        assertTrue(result is TonexResult.Failure)
        assertNotEquals(before, session.latestReadGeneration(), "even a failed create() attempt must advance the generation")
    }

    @Test
    fun `the generation minted by a failed create() attempt is strictly later than the previous successful read`() {
        val session = SessionId.create()
        val valid = PedalState.create(session, ByteArray(10)).assertSuccess()

        PedalState.create(session, ByteArray(PedalState.MAX_STATE_BYTES + 1)) // fails

        assertTrue(
            session.latestReadGeneration().value > valid.readGeneration.value,
            "the failed attempt must mint a generation past the previously-valid read's own generation",
        )
    }

    // ---- sessionId -----------------------------------------------------------------------------

    @Test
    fun `sessionId is exactly the instance passed to create`() {
        val session = SessionId.create()

        val state = PedalState.create(session, ByteArray(4)).assertSuccess()

        assertTrue(state.sessionId === session, "PedalState.sessionId must be reference-identical to the session it was read during")
    }

    // ---- invalidateCurrentRead() - the S9 contract for byte-less observation failures (round-5) -
    //
    // PedalState.create()'s unconditional mint only ever runs for an observation that actually
    // produced bytes; it has no way to cover a timeout, CRC failure, or malformed frame, because
    // none of those ever call create() at all - there is no ByteArray to pass it. SessionId.
    // invalidateCurrentRead() is the dedicated verb S9 must call for exactly those byte-less
    // failures, so a previously-held valid PedalState does not stay fully write-authorized across
    // a failed re-read (issue #12 round-5 review, MEDIUM finding).

    @Test
    fun `invalidateCurrentRead advances the session's generation with no new PedalState`() {
        val session = SessionId.create()
        val before = session.latestReadGeneration()

        session.invalidateCurrentRead()

        assertNotEquals(before, session.latestReadGeneration(), "invalidateCurrentRead must advance the generation")
    }

    @Test
    fun `invalidateCurrentRead invalidates a previously-held PedalState as a write authorization`() {
        // Simulates the exact scenario the KDoc describes: a valid earlier read, then a byte-less
        // re-read failure (timeout/CRC/malformed frame - simulated here since S9 does not exist yet
        // to actually produce one), then a caller wrongly trying to patch with the stale state.
        val session = SessionId.create()
        val staleButOtherwiseValid = PedalState.create(session, ByteArray(10)).assertSuccess()

        session.invalidateCurrentRead() // simulates S9's byte-less re-read failure

        assertNotEquals(
            staleButOtherwiseValid.readGeneration,
            session.latestReadGeneration(),
            "a PedalState held across a byte-less observation failure must no longer be the session's current generation",
        )
    }

    @Test
    fun `repeated invalidateCurrentRead calls each advance the generation further`() {
        val session = SessionId.create()

        session.invalidateCurrentRead()
        val afterFirst = session.latestReadGeneration()
        session.invalidateCurrentRead()
        val afterSecond = session.latestReadGeneration()

        assertNotEquals(afterFirst, afterSecond)
        assertTrue(afterSecond.value > afterFirst.value)
    }
}
