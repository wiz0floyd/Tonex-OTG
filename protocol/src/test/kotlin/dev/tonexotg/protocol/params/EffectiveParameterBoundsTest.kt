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
}
