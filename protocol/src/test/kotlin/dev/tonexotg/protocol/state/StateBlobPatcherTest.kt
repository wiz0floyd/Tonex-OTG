package dev.tonexotg.protocol.state

import dev.tonexotg.protocol.PedalState
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.SessionId
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [StateBlobPatcher] against the upstream bug this story exists to make unrepresentable
 * — see [PedalState]'s KDoc and issue #12 for the full background.
 *
 * `PedalState` and `SessionId` both have `internal` constructors; this test file is in the same
 * Gradle module (`:protocol`) as `src/main`, which Kotlin treats as a friend source set, so it
 * can call them directly to build fixtures — that access is *not* available to `:app` or any
 * other module, which is the whole point (see the dedicated structural-guarantee test at the
 * bottom of this file).
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
     * [StateBlobOffsets.MAX_END_OFFSET] bytes of distinct filler, with the four checked offsets
     * overwritten to plausible values for their field (defaults are all mutually distinct so a
     * copy/shift bug between them is detectable).
     */
    private fun plausibleBlob(
        size: Int = 64,
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

    private fun stateFor(sessionId: SessionId, bytes: ByteArray): PedalState = PedalState(sessionId, bytes)

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
            val session = SessionId()
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
        val session = SessionId()
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
        val session = SessionId()
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
        val readSession = SessionId()
        val currentSession = SessionId() // a distinct instance - never equal to readSession
        val state = stateFor(readSession, plausibleBlob())

        val result = StateBlobPatcher.patchSlotAssignment(state, currentSession, PresetSlot.A, PresetIndex(3))

        val error = result.assertFailure()
        assertTrue(error is TonexError.StaleSessionState, "expected StaleSessionState, got $error")
    }

    @Test
    fun `a write against a blob read during the current session succeeds`() {
        val session = SessionId()
        val state = stateFor(session, plausibleBlob())

        val result = StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.A, PresetIndex(3))

        result.assertSuccess()
    }

    // ---- length validation -------------------------------------------------------------------

    @Test
    fun `a blob shorter than the largest end offset is rejected with a typed error, not an exception`() {
        val session = SessionId()
        val tooShort = ByteArray(StateBlobOffsets.MAX_END_OFFSET - 1) { it.toByte() }
        val state = stateFor(session, tooShort)

        val result = StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.A, PresetIndex(0))

        val error = result.assertFailure()
        assertTrue(error is TonexError.UnexpectedBlobShape, "expected UnexpectedBlobShape, got $error")
        assertEquals(tooShort.size, (error as TonexError.UnexpectedBlobShape).actualSize)
    }

    @Test
    fun `an empty blob is rejected with a typed error, not an exception`() {
        val session = SessionId()
        val state = stateFor(session, ByteArray(0))

        val result = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.A)

        assertTrue(result.assertFailure() is TonexError.UnexpectedBlobShape)
    }

    // ---- shape sanity check -------------------------------------------------------------------

    @Test
    fun `an implausible slot-assignment byte is rejected rather than patched blind`() {
        val session = SessionId()
        // 250 is well outside PresetIndex.VALID_RANGE (0..19) - not what a real preset index
        // byte would ever contain, simulating a layout that has drifted since the pinned firmware.
        val bytes = plausibleBlob(slotA = 250)
        val state = stateFor(session, bytes)

        val result = StateBlobPatcher.patchActiveSlot(state, session, PresetSlot.B)

        assertTrue(result.assertFailure() is TonexError.UnexpectedBlobShape)
    }

    @Test
    fun `an implausible active-slot byte is rejected rather than patched blind`() {
        val session = SessionId()
        // 9 is not a valid PresetSlot ordinal (0, 1, or 2).
        val bytes = plausibleBlob(currentSlot = 9)
        val state = stateFor(session, bytes)

        val result = StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.C, PresetIndex(1))

        assertTrue(result.assertFailure() is TonexError.UnexpectedBlobShape)
    }

    // ---- round-trip no-op ----------------------------------------------------------------------

    @Test
    fun `patching a slot to the value already present is a no-op`() {
        val session = SessionId()
        val original = plausibleBlob(slotB = 5)
        val state = stateFor(session, original)

        val patched = StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.B, PresetIndex(5)).assertSuccess()

        assertTrue(original.contentEquals(patched), "no-op patch must produce a byte-identical array")
    }

    @Test
    fun `patching the active slot to the value already present is a no-op`() {
        val session = SessionId()
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

        val session = SessionId()
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
        val session = SessionId()
        val original = plausibleBlob()
        val originalCopy = original.copyOf()
        val state = stateFor(session, original)

        StateBlobPatcher.patchSlotAssignment(state, session, PresetSlot.A, PresetIndex(18)).assertSuccess()

        // PedalState takes its own defensive copy at construction, and StateBlobPatcher patches
        // a copy of copyOfBytes() - the caller's original array must never be touched by either.
        assertTrue(original.contentEquals(originalCopy), "caller's original array must be untouched")
        assertTrue(state.copyOfBytes().contentEquals(originalCopy), "PedalState's retained bytes must be untouched")
    }

    // ---- structural guarantee (documented, not runtime-provable) -----------------------------

    /**
     * Kotlin's `internal` visibility is a compile-time, module-scoped check with no distinguishing
     * JVM bytecode signature (internal members compile down to `public` on the JVM), so the
     * guarantee that "code outside `:protocol` cannot construct a `PedalState`" cannot be proven
     * by runtime reflection from inside this test suite — it can only be verified by reading
     * `PedalState.kt` and confirming the constructors stay `internal` with no public factory
     * alongside them. This test does that mechanically, so a future edit that widens visibility
     * fails CI instead of only being caught by careful review.
     */
    @Test
    fun `PedalState and SessionId constructors stay internal - the structural no-forgery guarantee`() {
        val source = readProtocolSource("PedalState.kt")

        assertTrue(
            source.contains("class SessionId internal constructor()"),
            "SessionId's constructor must stay `internal` - widening it would let code outside " +
                ":protocol forge a session identity, defeating PedalState's provenance guarantee",
        )
        assertTrue(
            source.contains("class PedalState internal constructor("),
            "PedalState's constructor must stay `internal` - a public constructor, factory, or " +
                "fromBytes would let code outside :protocol synthesize a state blob, which is " +
                "exactly the upstream bug this project exists to not repeat (see issue #12)",
        )
        assertFalse(
            source.contains("fun fromBytes") || source.contains("fun from("),
            "no fromBytes/from factory should ever be added to PedalState",
        )
    }

    /** Finds `protocol/src/main/kotlin/dev/tonexotg/protocol/<fileName>` regardless of whether the test JVM's working directory is the module root or the repo root. */
    private fun readProtocolSource(fileName: String): String {
        val relative = "src/main/kotlin/dev/tonexotg/protocol/$fileName"
        val candidates = listOf(File(relative), File("protocol", relative))
        val file = candidates.firstOrNull { it.exists() }
            ?: fail(
                "could not locate $fileName from working dir ${File(".").absolutePath} " +
                    "(tried ${candidates.map { it.path }})",
            )
        return file.readText()
    }
}
