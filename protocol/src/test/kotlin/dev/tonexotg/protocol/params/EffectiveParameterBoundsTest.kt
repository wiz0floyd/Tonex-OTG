package dev.tonexotg.protocol.params

import dev.tonexotg.protocol.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Exercises [EffectiveParameterBounds]/[SelfWideningParameterBounds] against issue #80's
 * acceptance criteria directly, independent of [dev.tonexotg.protocol.connection.DefaultTonexController]
 * wiring (that integration is covered separately in `DefaultTonexControllerSetParameterTest`/
 * `DefaultTonexControllerRevertTest`).
 */
class EffectiveParameterBoundsTest {

    private val virCabinetModel = requireNotNull(ParameterRegistry.byIndex(25)) // max 38 (hardware floor)
    private val virMic1 = requireNotNull(ParameterRegistry.byIndex(27)) // max 2
    private val cabinetType = requireNotNull(ParameterRegistry.byIndex(24)) // NOT allowlisted, max 2
    private val noiseGateThreshold = requireNotNull(ParameterRegistry.byIndex(2)) // NOT allowlisted, RANGE

    // ---- EffectiveParameterBounds.STATIC -------------------------------------------------------

    @Test
    fun `STATIC always returns the registry's static max`() {
        assertEquals(virCabinetModel.max, EffectiveParameterBounds.STATIC.effectiveMax(virCabinetModel.id))
        assertEquals(noiseGateThreshold.max, EffectiveParameterBounds.STATIC.effectiveMax(noiseGateThreshold.id))
    }

    @Test
    fun `STATIC throws for an invalid parameter id`() {
        assertFailsWith<IllegalArgumentException> {
            EffectiveParameterBounds.STATIC.effectiveMax(ParameterId(109 + 1000))
        }
    }

    // ---- SelfWideningParameterBounds: default construction -------------------------------------

    @Test
    fun `with no observations, effectiveMax equals the static max for an allowlisted id`() {
        val bounds = SelfWideningParameterBounds()
        assertEquals(virCabinetModel.max, bounds.effectiveMax(virCabinetModel.id))
    }

    @Test
    fun `with no observations, widenedMaxima is empty`() {
        val bounds = SelfWideningParameterBounds()
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    // ---- observeRead: the widening mechanism itself ---------------------------------------------

    @Test
    fun `observeRead above the static max widens effectiveMax for an allowlisted id`() {
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(virCabinetModel.id, 42f)

        assertEquals(42f, bounds.effectiveMax(virCabinetModel.id))
        assertEquals(mapOf(virCabinetModel.id to 42f), bounds.widenedMaxima.value)
    }

    @Test
    fun `observeRead at or below the static max is a no-op`() {
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(virCabinetModel.id, virCabinetModel.max) // exactly the floor
        bounds.observeRead(virCabinetModel.id, virCabinetModel.max - 1f) // below the floor

        assertEquals(virCabinetModel.max, bounds.effectiveMax(virCabinetModel.id))
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    @Test
    fun `observeRead never lowers an already-widened ceiling`() {
        val bounds = SelfWideningParameterBounds()
        bounds.observeRead(virCabinetModel.id, 50f)

        bounds.observeRead(virCabinetModel.id, 45f) // lower than the current widened ceiling

        assertEquals(50f, bounds.effectiveMax(virCabinetModel.id), "the ceiling must never lower")
    }

    @Test
    fun `observeRead keeps widening on repeated higher observations`() {
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(virCabinetModel.id, 40f)
        bounds.observeRead(virCabinetModel.id, 45f)
        bounds.observeRead(virCabinetModel.id, 60f)

        assertEquals(60f, bounds.effectiveMax(virCabinetModel.id))
    }

    // ---- allowlist does not leak (issue #80's core scoping requirement) -------------------------

    @Test
    fun `observeRead for a non-allowlisted id is a harmless no-op, even far above its max`() {
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(cabinetType.id, 999f)
        bounds.observeRead(noiseGateThreshold.id, 999f)

        assertEquals(cabinetType.max, bounds.effectiveMax(cabinetType.id))
        assertEquals(noiseGateThreshold.max, bounds.effectiveMax(noiseGateThreshold.id))
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    @Test
    fun `all three allowlisted ids widen independently of each other`() {
        val bounds = SelfWideningParameterBounds()
        val virMic2 = requireNotNull(ParameterRegistry.byIndex(30))

        bounds.observeRead(virCabinetModel.id, 42f)
        bounds.observeRead(virMic1.id, 5f)
        // virMic2 never observed above its max

        assertEquals(42f, bounds.effectiveMax(virCabinetModel.id))
        assertEquals(5f, bounds.effectiveMax(virMic1.id))
        assertEquals(virMic2.max, bounds.effectiveMax(virMic2.id), "unobserved allowlisted id stays at its static max")
    }

    // ---- floor never lowers: static max is always a lower bound on effectiveMax ------------------

    @Test
    fun `effectiveMax is always at least the static max, even with a lower seeded value`() {
        // A pathological/stale seed below the static floor must never lower effectiveMax below it.
        val bounds = SelfWideningParameterBounds(initialWidened = mapOf(virCabinetModel.id to 10f))

        assertEquals(virCabinetModel.max, bounds.effectiveMax(virCabinetModel.id))
    }

    // ---- construction seeding (the :app DataStore restart-persistence contract) ------------------

    @Test
    fun `seeding with a value above the static max widens immediately, no observeRead needed`() {
        val bounds = SelfWideningParameterBounds(initialWidened = mapOf(virCabinetModel.id to 42f))

        assertEquals(42f, bounds.effectiveMax(virCabinetModel.id))
        assertEquals(mapOf(virCabinetModel.id to 42f), bounds.widenedMaxima.value)
    }

    @Test
    fun `seeding a non-allowlisted id is silently ignored, not rejected`() {
        val bounds = SelfWideningParameterBounds(initialWidened = mapOf(cabinetType.id to 999f))

        assertEquals(cabinetType.max, bounds.effectiveMax(cabinetType.id))
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    // ---- "observed value N permits writing N and still rejects N+1" — the acceptance criterion ---
    // (write rejection itself is DefaultTonexController's job; this asserts the bounds primitive
    // that decision is built on reports exactly this shape.)

    @Test
    fun `after observing N, effectiveMax is exactly N - N is in-range and N+1 is not`() {
        val bounds = SelfWideningParameterBounds()
        val n = 42f

        bounds.observeRead(virCabinetModel.id, n)

        val effectiveMax = bounds.effectiveMax(virCabinetModel.id)
        assertEquals(n, effectiveMax)
        assert(n <= effectiveMax) { "N must be permitted" }
        assert(n + 1f > effectiveMax) { "N+1 must still be rejected" }
    }

    // ---- finiteness guard (Opus review, PR #81): NaN/Infinity/huge-finite reads never widen anything ----
    // A naive `if (value <= staticMax) return` guard is not enough: comparisons against NaN are
    // always false in IEEE 754, so a NaN read would fall through, get stored as the "ceiling," and
    // then silently defeat every downstream `value > effectiveMax` check and every
    // `coerceIn(min, effectiveMax)` clamp that consults it (both comparisons are also false
    // against a NaN bound). This must be caught here, at the source, not merely downstream.

    @Test
    fun `observeRead rejects NaN outright - it must never become the ceiling`() {
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(virCabinetModel.id, Float.NaN)

        assertEquals(
            virCabinetModel.max,
            bounds.effectiveMax(virCabinetModel.id),
            "a NaN read must leave the ceiling exactly at the static max, not become the ceiling itself",
        )
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    @Test
    fun `observeRead rejects positive Infinity outright`() {
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(virCabinetModel.id, Float.POSITIVE_INFINITY)

        assertEquals(virCabinetModel.max, bounds.effectiveMax(virCabinetModel.id))
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    @Test
    fun `observeRead rejects negative Infinity outright`() {
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(virCabinetModel.id, Float.NEGATIVE_INFINITY)

        assertEquals(virCabinetModel.max, bounds.effectiveMax(virCabinetModel.id))
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    @Test
    fun `observeRead rejects an implausibly huge but technically finite value`() {
        // isFinite() alone does not bound magnitude -- this is a deliberate design choice
        // documented on observeRead's KDoc, not a gap: an offset-drift-produced 1e30 is exactly
        // as untrustworthy as NaN/Infinity as a "widened ceiling," so this asserts the current,
        // intentional behavior (accepted, since it IS finite) so a future change to add a
        // magnitude cap is a deliberate decision, not an accidental regression either way.
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(virCabinetModel.id, 1e30f)

        assertEquals(
            1e30f,
            bounds.effectiveMax(virCabinetModel.id),
            "1e30 is finite, so it IS accepted by the current isFinite()-only guard -- see this test's own KDoc",
        )
    }

    @Test
    fun `after a rejected NaN read, a subsequent genuine finite read still widens normally`() {
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(virCabinetModel.id, Float.NaN) // rejected
        bounds.observeRead(virCabinetModel.id, 42f) // genuine

        assertEquals(42f, bounds.effectiveMax(virCabinetModel.id))
    }

    @Test
    fun `NaN in a non-allowlisted id's read is also rejected - both guards apply independently`() {
        val bounds = SelfWideningParameterBounds()

        bounds.observeRead(cabinetType.id, Float.NaN)

        assertEquals(cabinetType.max, bounds.effectiveMax(cabinetType.id))
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    // ---- finiteness guard applies to construction-time seeding too (Opus review, PR #81) ----
    // Same rationale as observeRead: a seed value is not more trustworthy than a live read just
    // because it came from :app's DataStore -- a corrupted prefs file or a NaN that somehow
    // reached disk before this fix must not resurrect the write-path bypass on load.

    @Test
    fun `seeding with NaN is silently ignored, not stored as the ceiling`() {
        val bounds = SelfWideningParameterBounds(initialWidened = mapOf(virCabinetModel.id to Float.NaN))

        assertEquals(virCabinetModel.max, bounds.effectiveMax(virCabinetModel.id))
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    @Test
    fun `seeding with Infinity is silently ignored, not stored as the ceiling`() {
        val bounds = SelfWideningParameterBounds(initialWidened = mapOf(virCabinetModel.id to Float.POSITIVE_INFINITY))

        assertEquals(virCabinetModel.max, bounds.effectiveMax(virCabinetModel.id))
        assertEquals(emptyMap(), bounds.widenedMaxima.value)
    }

    @Test
    fun `seeding one id with NaN does not prevent a different id from seeding normally`() {
        val bounds = SelfWideningParameterBounds(
            initialWidened = mapOf(virCabinetModel.id to Float.NaN, virMic1.id to 5f),
        )

        assertEquals(virCabinetModel.max, bounds.effectiveMax(virCabinetModel.id), "the NaN entry must not poison the map construction")
        assertEquals(5f, bounds.effectiveMax(virMic1.id))
    }
}
