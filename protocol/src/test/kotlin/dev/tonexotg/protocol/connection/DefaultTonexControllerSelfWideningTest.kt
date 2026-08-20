@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.params.ParameterRegistry
import dev.tonexotg.protocol.params.SelfWideningParameterBounds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Issue #80: the self-widening ceiling for [ParameterRegistry.SELF_WIDENING_PARAMETER_IDS]
 * ({25 VIR_CABINET_MODEL, 27 VIR_MIC_1, 30 VIR_MIC_2}), exercised through [DefaultTonexController]
 * end-to-end (a genuine pedal read widening [setParameter]/`revertActivePreset` validation
 * immediately, no restart needed) — complements the pure-logic coverage in
 * `dev.tonexotg.protocol.params.EffectiveParameterBoundsTest`.
 *
 * The literal `38.0` used throughout as VIR_CABINET_MODEL's *static* registry floor is the real
 * hardware-observed value from `docs/hardware-probes/tonexprobe20260819_220308.log.txt:11127`
 * (preset 14 "Medium Class") — see `ParameterRegistryTest`'s "spot check VIR cabinet model" test
 * and `ParameterRegistry`'s class KDoc for the full citation. This file's own tests use values
 * *above* 38 (42, 43) to exercise the widening mechanism itself, not to re-assert that literal.
 */
class DefaultTonexControllerSelfWideningTest {

    private val virCabinetModelId = ParameterId(25)
    private val staticMax = requireNotNull(ParameterRegistry.byIndex(25)).max // 38, a hardware floor (issue #77)

    // ---- a genuine pedal read widens the ceiling immediately, no restart needed -----------------

    @Test
    fun `a captured preset read reporting VIR_CABINET_MODEL above the static max widens setParameter's ceiling immediately`() = runTest {
        val fake = FakeTonexTransport()
        val effectiveBounds = SelfWideningParameterBounds()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            effectiveBounds = effectiveBounds,
        )
        val observedValue = 42f
        val captured = snapshotValues().also { it[25] = observedValue }
        connectToReady(controller, fake, captureValues = captured)

        // The read itself (during connect's capture) must already have widened the ceiling -- no
        // separate write, no restart, needed.
        val acceptN = controller.setParameter(virCabinetModelId, observedValue)
        val rejectNPlus1 = controller.setParameter(virCabinetModelId, observedValue + 1f)

        assertIs<TonexResult.Success<Unit>>(acceptN, "observed value N must be writable")
        val error = (rejectNPlus1 as TonexResult.Failure).error
        assertIs<TonexError.ParameterValueOutOfRange>(error, "N+1, never observed, must still be rejected")
        assertEquals(observedValue, effectiveBounds.effectiveMax(virCabinetModelId))
    }

    @Test
    fun `before any widening read, a value above the static max is still rejected`() = runTest {
        val fake = FakeTonexTransport()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        connectToReady(controller, fake) // captureValues default (snapshotValues()) -- index 25 in range

        val result = controller.setParameter(virCabinetModelId, staticMax + 1f)

        assertIs<TonexError.ParameterValueOutOfRange>((result as TonexResult.Failure).error)
    }

    // ---- floor never lowers: seeding below the static max has no effect ---------------------------

    @Test
    fun `seeding effectiveBounds with a value below the static max never lowers effectiveMax`() = runTest {
        val fake = FakeTonexTransport()
        val effectiveBounds = SelfWideningParameterBounds(initialWidened = mapOf(virCabinetModelId to 10f))
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            effectiveBounds = effectiveBounds,
        )
        connectToReady(controller, fake)

        // Still rejects anything above the static floor (38), unaffected by the below-floor seed.
        val result = controller.setParameter(virCabinetModelId, staticMax + 1f)

        assertIs<TonexError.ParameterValueOutOfRange>((result as TonexResult.Failure).error)
    }

    // ---- restart persistence contract: seeding a widened value at construction time survives ------

    @Test
    fun `seeding effectiveBounds with a previously-observed widened value (the restart contract) permits it immediately`() = runTest {
        val fake = FakeTonexTransport()
        // Simulates :app loading a DataStore-persisted widened maximum before constructing the
        // controller -- no state read this session needed for the ceiling to already be wide.
        val effectiveBounds = SelfWideningParameterBounds(initialWidened = mapOf(virCabinetModelId to 42f))
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            effectiveBounds = effectiveBounds,
        )
        connectToReady(controller, fake) // capture reads back in-range (<=38) values this session

        val acceptPersisted = controller.setParameter(virCabinetModelId, 42f)
        val rejectAbovePersisted = controller.setParameter(virCabinetModelId, 43f)

        assertIs<TonexResult.Success<Unit>>(acceptPersisted, "a value persisted from a prior session must not regress to the static floor")
        assertIs<TonexError.ParameterValueOutOfRange>((rejectAbovePersisted as TonexResult.Failure).error)
    }

    // ---- allowlist does not leak: an out-of-range read on a non-allowlisted id never widens anything ----

    @Test
    fun `an out-of-range captured value for a non-allowlisted parameter does not widen it, and setParameter still rejects it`() = runTest {
        val fake = FakeTonexTransport()
        val effectiveBounds = SelfWideningParameterBounds()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            effectiveBounds = effectiveBounds,
        )
        // CABINET_TYPE (index 24) is NOT in the allowlist -- a fixed 3-way selector, deliberately
        // excluded by issue #80's second follow-up comment. Simulate a captured value far outside
        // its static range (0..2), as if a decode bug produced garbage.
        val cabinetTypeId = ParameterId(24)
        val captured = snapshotValues().also { it[24] = 999f }
        connectToReady(controller, fake, captureValues = captured)

        val result = controller.setParameter(cabinetTypeId, 999f)

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ParameterValueOutOfRange>(error, "non-allowlisted out-of-range reads must keep failing loud, never silently widen")
        assertEquals(2f, effectiveBounds.effectiveMax(cabinetTypeId), "CABINET_TYPE's effective max must stay at its static max")
        // And the allowlisted VIR_CABINET_MODEL, unrelated to this read, must be untouched too.
        assertEquals(staticMax, effectiveBounds.effectiveMax(virCabinetModelId))
    }

    // ---- revertActivePreset's snapshot pre-validation also uses the effective (widened) bounds ----

    @Test
    fun `revertActivePreset succeeds when the captured snapshot holds a self-widened value above the static max`() = runTest {
        val fake = FakeTonexTransport()
        val effectiveBounds = SelfWideningParameterBounds()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            effectiveBounds = effectiveBounds,
        )
        val captured = snapshotValues().also { it[25] = 42f } // widens VIR_CABINET_MODEL to 42 on capture
        connectToReady(controller, fake, captureValues = captured)

        val result = controller.revertActivePreset()

        assertIs<TonexResult.Success<Unit>>(
            result,
            "a snapshot holding a self-widened value must not be rejected by pre-validation against the STATIC max",
        )
    }

    // ---- finiteness guard end-to-end (Opus review, PR #81) -----------------------------------
    // A NaN/Infinity captured value is exactly what a state-blob offset drift (the S5/S8 failure
    // class) could produce for one of the three allowlisted ids. This confirms the fix actually
    // protects the write path, not just SelfWideningParameterBounds in isolation.

    @Test
    fun `a captured NaN value for VIR_CABINET_MODEL does not disable setParameter's upper-bound check`() = runTest {
        val fake = FakeTonexTransport()
        val effectiveBounds = SelfWideningParameterBounds()
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            effectiveBounds = effectiveBounds,
        )
        val captured = snapshotValues().also { it[25] = Float.NaN } // simulates an offset-drift decode producing garbage
        connectToReady(controller, fake, captureValues = captured)

        // Without the finiteness guard, effectiveMax(25) would be NaN, and `value > NaN` is always
        // false -- an absurd write like this would have been wrongly accepted.
        val result = controller.setParameter(virCabinetModelId, 1e9f)

        val error = (result as TonexResult.Failure).error
        assertIs<TonexError.ParameterValueOutOfRange>(error, "a NaN-poisoned ceiling must never disable upper-bound validation")
        assertEquals(staticMax, effectiveBounds.effectiveMax(virCabinetModelId), "the ceiling must remain the static max, not NaN")
    }

    // ---- shared setup -------------------------------------------------------------------------

    private suspend fun TestScope.connectToReady(
        controller: DefaultTonexController,
        fake: FakeTonexTransport,
        activeSlot: PresetSlot = PresetSlot.A,
        a: Int = 0,
        b: Int = 1,
        c: Int = 2,
        captureValues: FloatArray? = snapshotValues(),
    ) {
        val connectDeferred = async { controller.connect(fake) }
        driveToReady(fake, activeSlot = activeSlot, a = a, b = b, c = c, captureValues = captureValues)
        val result = connectDeferred.await()
        assertIs<TonexResult.Success<Unit>>(result)
    }
}
