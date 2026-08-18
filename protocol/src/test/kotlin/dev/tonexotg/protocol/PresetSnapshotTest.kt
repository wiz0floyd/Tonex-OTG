package dev.tonexotg.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * §H.2 of the S9b architecture plan. Issue #14 calls float-array aliasing out by name as "an easy
 * aliasing bug" and lists it as an explicit acceptance criterion — these tests make the guarantee
 * explicit and unmissable, not just implied by the constructor's `copyOf()`.
 */
class PresetSnapshotTest {

    private val session = SessionId.create()

    private fun snapshot(values: FloatArray = FloatArray(PresetSnapshot.PARAMETER_COUNT)): PresetSnapshot =
        PresetSnapshot(PresetIndex(3), session, values)

    // ---- aliasing: construction ------------------------------------------------------------------

    @Test
    fun `mutating the source array after construction does not change the snapshot`() {
        val source = FloatArray(PresetSnapshot.PARAMETER_COUNT) { it.toFloat() }
        val snap = snapshot(source)

        source[0] = -999f
        source[108] = -999f

        assertEquals(0f, snap.valueOf(ParameterId(0)))
        assertEquals(108f, snap.valueOf(ParameterId(108)))
    }

    // ---- aliasing: toMap ---------------------------------------------------------------------------

    @Test
    fun `mutating a map returned by toMap does not change the snapshot`() {
        val snap = snapshot(FloatArray(PresetSnapshot.PARAMETER_COUNT) { it.toFloat() })

        val mutable = snap.toMap().toMutableMap()
        mutable[ParameterId(0)] = -999f

        assertEquals(0f, snap.valueOf(ParameterId(0)))
        assertEquals(0f, snap.toMap().getValue(ParameterId(0)))
    }

    @Test
    fun `two toMap calls return independent instances`() {
        val snap = snapshot(FloatArray(PresetSnapshot.PARAMETER_COUNT) { it.toFloat() })

        val first = snap.toMap()
        val second = snap.toMap()

        assertNotSame(first, second)
        assertEquals(first, second)
    }

    // ---- positional indexing convention -------------------------------------------------------------

    @Test
    fun `valueOf returns the value at the id's own wire index for all 109`() {
        val snap = snapshot(FloatArray(PresetSnapshot.PARAMETER_COUNT) { it.toFloat() })

        for (n in ParameterId.PRESET_RANGE) {
            assertEquals(n.toFloat(), snap.valueOf(ParameterId(n)), "ParameterId($n)")
        }
    }

    @Test
    fun `toMap has exactly 109 entries whose keys are exactly PRESET_RANGE`() {
        val snap = snapshot()

        val map = snap.toMap()

        assertEquals(109, map.size)
        assertEquals(ParameterId.PRESET_RANGE.map { ParameterId(it) }.toSet(), map.keys)
    }

    // ---- scope guard -----------------------------------------------------------------------------

    @Test
    fun `valueOf on a GLOBAL id throws IllegalArgumentException`() {
        val snap = snapshot()

        assertFailsWith<IllegalArgumentException> {
            snap.valueOf(ParameterId(116)) // MASTER_VOLUME
        }
    }

    // ---- constructor invariant ---------------------------------------------------------------------

    @Test
    fun `constructing with 108 or 110 values throws`() {
        assertFailsWith<IllegalArgumentException> {
            PresetSnapshot(PresetIndex(0), session, FloatArray(108))
        }
        assertFailsWith<IllegalArgumentException> {
            PresetSnapshot(PresetIndex(0), session, FloatArray(110))
        }
    }

    // ---- identity fields ----------------------------------------------------------------------------

    @Test
    fun `presetIndex and sessionId are carried through`() {
        val snap = PresetSnapshot(PresetIndex(7), session, FloatArray(PresetSnapshot.PARAMETER_COUNT))

        assertEquals(PresetIndex(7), snap.presetIndex)
        assertTrue(snap.sessionId === session)
    }
}
