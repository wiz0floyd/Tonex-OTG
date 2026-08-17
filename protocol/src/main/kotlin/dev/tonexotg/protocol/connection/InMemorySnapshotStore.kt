package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSnapshot
import dev.tonexotg.protocol.SnapshotStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The straightforward map-backed [SnapshotStore]. Session-scoped and in-memory only, matching
 * [SnapshotStore]'s own KDoc ("there is deliberately no persistence API here").
 *
 * S9 wires this in as [DefaultTonexController]'s default [SnapshotStore] so `clear()` — a
 * connection-lifecycle concern per [SnapshotStore]'s own KDoc ("Discards all retained snapshots
 * and the warned flag, e.g. on disconnect") — is called from `teardown()` from day one, even
 * though nothing in S9 itself calls [SnapshotStore.record] yet (that is S9b's capture path). See
 * [DefaultTonexController.revertActivePreset]'s KDoc for why this is safe to wire now rather than
 * leaving a nullable placeholder for S9b to fill in later.
 */
internal class InMemorySnapshotStore : SnapshotStore {

    // Keyed by PresetIndex.value: Int, not PresetIndex itself - a @JvmInline value class key would
    // box on every ConcurrentHashMap access; the Int key is equivalent and avoids that churn.
    private val snapshots = ConcurrentHashMap<Int, PresetSnapshot>()
    private val warned = AtomicBoolean(false)

    override fun snapshotFor(presetIndex: PresetIndex): PresetSnapshot? = snapshots[presetIndex.value]

    override fun record(snapshot: PresetSnapshot) {
        snapshots[snapshot.presetIndex.value] = snapshot
    }

    override fun hasWarnedThisSession(): Boolean = warned.get()

    override fun markWarned() {
        warned.set(true)
    }

    override fun clear() {
        snapshots.clear()
        warned.set(false)
    }
}
