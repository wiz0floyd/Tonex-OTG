package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSnapshot
import dev.tonexotg.protocol.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemorySnapshotStoreTest {

    private fun snapshotFor(index: PresetIndex, session: SessionId = SessionId.create()): PresetSnapshot =
        PresetSnapshot(index, session, FloatArray(PresetSnapshot.PARAMETER_COUNT))

    @Test
    fun `snapshotFor returns null when nothing has been recorded for that preset`() {
        val store = InMemorySnapshotStore()
        assertNull(store.snapshotFor(PresetIndex(0)))
    }

    @Test
    fun `record then snapshotFor returns the recorded snapshot`() {
        val store = InMemorySnapshotStore()
        val snapshot = snapshotFor(PresetIndex(3))

        store.record(snapshot)

        assertEquals(snapshot, store.snapshotFor(PresetIndex(3)))
    }

    @Test
    fun `recording a new snapshot for the same preset replaces the prior one`() {
        val store = InMemorySnapshotStore()
        val first = snapshotFor(PresetIndex(5))
        val second = snapshotFor(PresetIndex(5))

        store.record(first)
        store.record(second)

        assertEquals(second, store.snapshotFor(PresetIndex(5)))
    }

    @Test
    fun `snapshots for different presets are tracked independently`() {
        val store = InMemorySnapshotStore()
        val a = snapshotFor(PresetIndex(1))
        val b = snapshotFor(PresetIndex(2))

        store.record(a)
        store.record(b)

        assertEquals(a, store.snapshotFor(PresetIndex(1)))
        assertEquals(b, store.snapshotFor(PresetIndex(2)))
    }

    @Test
    fun `hasWarnedThisSession starts false and markWarned flips it, idempotently`() {
        val store = InMemorySnapshotStore()
        assertFalse(store.hasWarnedThisSession())

        store.markWarned()
        assertTrue(store.hasWarnedThisSession())

        store.markWarned() // idempotent
        assertTrue(store.hasWarnedThisSession())
    }

    @Test
    fun `clear discards all snapshots and resets the warned flag`() {
        val store = InMemorySnapshotStore()
        store.record(snapshotFor(PresetIndex(0)))
        store.markWarned()

        store.clear()

        assertNull(store.snapshotFor(PresetIndex(0)))
        assertFalse(store.hasWarnedThisSession())
    }
}
