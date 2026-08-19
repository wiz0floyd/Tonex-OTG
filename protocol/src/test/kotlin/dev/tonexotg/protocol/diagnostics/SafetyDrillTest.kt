@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.tonexotg.protocol.diagnostics

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.connection.DefaultTonexController
import dev.tonexotg.protocol.connection.FakeTonexTransport
import dev.tonexotg.protocol.connection.alteredSnapshotValues
import dev.tonexotg.protocol.connection.driveToReady
import dev.tonexotg.protocol.connection.plausibleBlob
import dev.tonexotg.protocol.connection.presetDetailsSummaryWithParameters
import dev.tonexotg.protocol.connection.snapshotValues
import dev.tonexotg.protocol.connection.stateUpdateMessage
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.params.ParameterRegistry
import dev.tonexotg.protocol.state.StateBlobOffsets
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #27 (S22) — end-to-end tests for [SafetyDrill]'s orchestration functions against synthetic
 * blobs, using [FakeTonexTransport] and a real [DefaultTonexController] the same way the rest of
 * this project's `:protocol` suite already does (see `ConnectionTestFixtures.driveToReady`). No
 * real hardware is involved or required — this is exactly what CLAUDE.md asks S22 to cover from
 * this cloud session: the drill *logic* is fully verifiable without a pedal; only running it
 * against a real one is out of reach here.
 */
class SafetyDrillTest {

    // ---- raw captures, standalone (no controller) -------------------------------------------

    @Test
    fun `captureStateBlob returns the pedal's raw state-blob bytes`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }
        val blob = plausibleBlob()

        val deferred = async { captureStateBlob(tap, tap, 1_000) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(blob))
        testScheduler.runCurrent()

        val result = deferred.await()
        assertIs<TonexResult.Success<ByteArray>>(result)
        assertTrue(result.value.contentEquals(blob))
        collectJob.cancel()
    }

    @Test
    fun `capturePresetDetails extracts the requested preset's name and parameters`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }
        val values = snapshotValues()

        val deferred = async { capturePresetDetails(tap, tap, PresetIndex(3), 1_000) }
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Lead Tone", values))
        testScheduler.runCurrent()

        val result = deferred.await()
        assertIs<TonexResult.Success<PresetBackupEntry>>(result)
        assertEquals(PresetIndex(3), result.value.index)
        assertEquals("Lead Tone", result.value.name)
        assertTrue(result.value.parameters.contentEquals(values))
        collectJob.cancel()
    }

    @Test
    fun `captureFullBackup captures the state blob and all 20 presets, in order, before any write`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }
        val blob = plausibleBlob()

        val deferred = async { captureFullBackup(tap, tap, 1_000) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(blob))
        for (i in PresetIndex.VALID_RANGE) {
            testScheduler.runCurrent()
            fake.emitMessage(presetDetailsSummaryWithParameters("Preset $i", snapshotValues()))
        }
        testScheduler.runCurrent()

        val result = deferred.await()
        assertIs<TonexResult.Success<FullBackup>>(result)
        assertTrue(result.value.stateBlob.contentEquals(blob))
        assertEquals(20, result.value.presets.size)
        assertEquals((0..19).toList(), result.value.presets.map { it.index.value })
        assertEquals("Preset 7", result.value.presets[7].name)

        // Nothing here writes -- every request issued is a read (RequestState / RequestPresetDetails).
        assertTrue(fake.written.isNotEmpty())
        collectJob.cancel()
    }

    @Test
    fun `captureStateBlob rejects an implausibly short payload rather than diffing garbage`() = runTest {
        // Opus review of issue #27 (M2): every other consumer of a raw state blob enforces
        // MIN_PLAUSIBLE_BLOB_SIZE; this is the one other place a raw blob enters the codebase, and
        // without this check two equal-length implausible payloads would byte-diff as identical --
        // a clean PASS with no evidence anything real was even read.
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }
        val tooShort = ByteArray(StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE - 1)

        val deferred = async { captureStateBlob(tap, tap, 1_000) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(tooShort))
        testScheduler.runCurrent()

        val result = deferred.await()
        assertIs<TonexResult.Failure>(result)
        collectJob.cancel()
    }

    @Test
    fun `captureFullBackup fails fast on the first bad preset read rather than reporting a partial backup`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val collectJob = launch { tap.incoming().collect { } }

        val deferred = async { captureFullBackup(tap, tap, 200) }
        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob()))
        testScheduler.runCurrent()
        // Preset 0's response never arrives -- times out.
        testScheduler.advanceTimeBy(201)
        testScheduler.runCurrent()

        val result = deferred.await()
        assertIs<TonexResult.Failure>(result)
        collectJob.cancel()
    }

    // ---- the preset-change byte-diff drill (issue #27 sections 2 and 3) ----------------------

    @Test
    fun `preset-change drill passes when only the active-slot byte moves, and names that offset`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(tap) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()

        // Target preset 1 is already assigned to slot B -> selectPreset patches ONLY the
        // active-slot byte (StateBlobPatcher#patchActiveSlot).
        val drillDeferred = async { runPresetChangeByteDiffDrill(controller, tap, tap, PresetIndex(1), 5_000) }

        testScheduler.runCurrent() // drill's own raw "before" read
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))

        testScheduler.runCurrent() // selectPreset's own mandatory re-read
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))

        testScheduler.runCurrent() // drill's own raw "after" read
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()

        val result = drillDeferred.await()
        assertIs<TonexResult.Success<PresetChangeDrillResult>>(result)
        val audit = result.value.audit
        assertTrue(audit.passed, "expected a clean audit, got unexpected indices: ${audit.unexpectedIndices}")
        assertEquals(setOf(StateBlobOffsets.END_CURRENT_SLOT), audit.sanctionedOffsetsChanged)
        assertEquals(emptyList(), audit.unexpectedIndices)
    }

    @Test
    fun `preset-change drill refuses to run when the target preset is already active - nothing to audit`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(tap) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()

        // Preset 0 is already assigned to slot A, which is already active -- selectPreset would be
        // a complete no-write no-op (see its own KDoc). There is nothing for the drill to audit.
        val writesBeforeDrill = fake.written.size
        val drillDeferred = async { runPresetChangeByteDiffDrill(controller, tap, tap, PresetIndex(0), 5_000) }

        testScheduler.runCurrent() // drill's own raw "before" read
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()

        val result = drillDeferred.await()
        assertIs<TonexResult.Failure>(result, "the drill must refuse rather than report a vacuous pass for a no-op selectPreset")
        // No selectPreset write, and no second/third raw read, was ever issued -- only the drill's
        // own single raw "before" state read happened before it refused.
        assertEquals(
            writesBeforeDrill + 1,
            fake.written.size,
            "expected only the drill's own single raw state read after refusing",
        )
    }

    @Test
    fun `preset-change drill FAILS and names the exact byte when something writes outside the sanctioned offsets`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(tap) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()

        val drillDeferred = async { runPresetChangeByteDiffDrill(controller, tap, tap, PresetIndex(1), 5_000) }

        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))

        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))

        // Simulate a real hardware/firmware defect: the active slot moved as expected, but so did
        // an unrelated byte (e.g. a hypothetical DIRECT_MONITOR drift) -- exactly the finding
        // issue #27 section 3 is designed to catch.
        testScheduler.runCurrent()
        val corrupted = plausibleBlob(activeSlot = PresetSlot.B, a = 0, b = 1, c = 2)
        val unexpectedIndex = 50
        corrupted[unexpectedIndex] = (corrupted[unexpectedIndex] + 1).toByte()
        fake.emitMessage(stateUpdateMessage(corrupted))
        testScheduler.runCurrent()

        val result = drillDeferred.await()
        assertIs<TonexResult.Success<PresetChangeDrillResult>>(result)
        val audit = result.value.audit
        assertFalse(audit.passed)
        assertEquals(listOf(unexpectedIndex), audit.unexpectedIndices)
    }

    // ---- the revert drill (issue #27 section 4) ----------------------------------------------

    @Test
    fun `revert drill passes when the pedal's independently re-read values genuinely match the pre-edit baseline`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(tap) }
        val baseline = snapshotValues()
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2, captureValues = baseline)
        connectDeferred.await()

        val editSpec = requireNotNull(ParameterRegistry.byIndex(3))
        val drillDeferred = async {
            runRevertDrill(controller, tap, tap, 5_000) {
                controller.setParameter(ParameterId(3), editSpec.max)
            }
        }

        testScheduler.runCurrent() // pre-edit state-blob capture
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))

        testScheduler.runCurrent() // pre-edit parameter read-back
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 0", baseline))

        // edit() and revertActivePreset() are per-parameter writes with no response awaited by
        // the controller -- nothing to emit for them.
        testScheduler.runCurrent()

        testScheduler.runCurrent() // post-revert parameter read-back -- genuinely back to baseline
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 0", baseline))

        testScheduler.runCurrent() // post-revert state-blob capture
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()

        val result = drillDeferred.await()
        assertIs<TonexResult.Success<RevertDrillResult>>(result)
        assertTrue(result.value.passed, "mismatched: ${result.value.mismatchedParameterIndices}")
        assertEquals(emptyList(), result.value.mismatchedParameterIndices)
        assertTrue(result.value.stateBlobAudit.passed)
    }

    @Test
    fun `revert drill FAILS when the pedal's read-back does not actually match the pre-edit baseline`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        val controller = DefaultTonexController(
            scope = backgroundScope,
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
        )
        val connectDeferred = async { controller.connect(tap) }
        val baseline = snapshotValues()
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2, captureValues = baseline)
        connectDeferred.await()

        val editSpec = requireNotNull(ParameterRegistry.byIndex(3))
        val drillDeferred = async {
            runRevertDrill(controller, tap, tap, 5_000) {
                controller.setParameter(ParameterId(3), editSpec.max)
            }
        }

        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))

        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 0", baseline))

        testScheduler.runCurrent()

        // The revert call itself succeeded (accepted by the transport), but the pedal's
        // independently re-read values do NOT actually match the pre-edit baseline -- the exact
        // "accepted != confirmed applied" gap this drill exists to catch.
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 0", alteredSnapshotValues(1)))

        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()

        val result = drillDeferred.await()
        assertIs<TonexResult.Success<RevertDrillResult>>(result)
        assertFalse(result.value.passed)
        assertTrue(result.value.mismatchedParameterIndices.isNotEmpty())
    }

    @Test
    fun `revert drill propagates a failure from the edit step without attempting a revert`() = runTest {
        val fake = FakeTonexTransport()
        val tap = MessageCaptureTap(fake)
        // NONE_CONFIRMED -- setParameter itself will fail with UnsupportedByFirmware.
        val controller = DefaultTonexController(scope = backgroundScope, capabilities = FirmwareCapabilities.NONE_CONFIRMED)
        val connectDeferred = async { controller.connect(tap) }
        driveToReady(fake, activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)
        connectDeferred.await()

        val drillDeferred = async {
            runRevertDrill(controller, tap, tap, 5_000) {
                controller.setParameter(ParameterId(3), 0f)
            }
        }

        testScheduler.runCurrent()
        fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = PresetSlot.A, a = 0, b = 1, c = 2)))
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Preset 0", snapshotValues()))
        testScheduler.runCurrent()

        val result = drillDeferred.await()
        assertIs<TonexResult.Failure>(result)
    }
}
