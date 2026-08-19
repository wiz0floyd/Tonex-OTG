package dev.tonexotg.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Issue #36: mirrors [PresetSnapshotTest]'s aliasing/immutability discipline for
 * [FootswitchSnapshot] — the same "an easy aliasing bug" concern [PresetSnapshot] was built
 * against applies identically here: a restore is only trustworthy if what it restores *to*
 * cannot itself have drifted.
 */
class FootswitchSnapshotTest {

    private val session = SessionId.create()

    private fun assignments(a: Int = 0, b: Int = 1, c: Int = 2): Map<PresetSlot, PresetIndex> = mapOf(
        PresetSlot.A to PresetIndex(a),
        PresetSlot.B to PresetIndex(b),
        PresetSlot.C to PresetIndex(c),
    )

    // ---- aliasing: construction --------------------------------------------------------------

    @Test
    fun `mutating the source map after construction does not change the snapshot`() {
        val source = assignments(a = 3, b = 4, c = 5).toMutableMap()
        val snap = FootswitchSnapshot(session, source)

        source[PresetSlot.A] = PresetIndex(19)

        assertEquals(PresetIndex(3), snap.presetFor(PresetSlot.A))
    }

    @Test
    fun `mutating a map returned by toMap does not change the snapshot`() {
        val snap = FootswitchSnapshot(session, assignments(a = 3, b = 4, c = 5))

        val mutable = snap.toMap().toMutableMap()
        mutable[PresetSlot.A] = PresetIndex(19)

        assertEquals(PresetIndex(3), snap.presetFor(PresetSlot.A))
        assertEquals(PresetIndex(3), snap.toMap().getValue(PresetSlot.A))
    }

    @Test
    fun `two toMap calls return independent instances`() {
        val snap = FootswitchSnapshot(session, assignments())

        val first = snap.toMap()
        val second = snap.toMap()

        assertNotSame(first, second)
        assertEquals(first, second)
    }

    // ---- accessors ----------------------------------------------------------------------------

    @Test
    fun `presetFor and toMap agree for all three slots`() {
        val snap = FootswitchSnapshot(session, assignments(a = 7, b = 12, c = 19))

        assertEquals(PresetIndex(7), snap.presetFor(PresetSlot.A))
        assertEquals(PresetIndex(12), snap.presetFor(PresetSlot.B))
        assertEquals(PresetIndex(19), snap.presetFor(PresetSlot.C))
        assertEquals(assignments(a = 7, b = 12, c = 19), snap.toMap())
    }

    // ---- constructor invariant ------------------------------------------------------------------

    @Test
    fun `constructing with a map missing a slot throws`() {
        assertFailsWith<IllegalArgumentException> {
            FootswitchSnapshot(session, mapOf(PresetSlot.A to PresetIndex(0), PresetSlot.B to PresetIndex(1)))
        }
    }

    @Test
    fun `constructing with an empty map throws`() {
        assertFailsWith<IllegalArgumentException> {
            FootswitchSnapshot(session, emptyMap())
        }
    }

    // ---- identity field -----------------------------------------------------------------------

    @Test
    fun `sessionId is carried through by reference`() {
        val snap = FootswitchSnapshot(session, assignments())

        assertTrue(snap.sessionId === session)
    }
}
