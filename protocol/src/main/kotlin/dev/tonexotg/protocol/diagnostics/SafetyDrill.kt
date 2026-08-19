package dev.tonexotg.protocol.diagnostics

import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.TonexTransport
import dev.tonexotg.protocol.framing.HdlcFrame
import dev.tonexotg.protocol.message.PresetDetailsKind
import dev.tonexotg.protocol.message.PresetNameExtractor
import dev.tonexotg.protocol.message.PresetParameterExtractor
import dev.tonexotg.protocol.message.RequestPresetDetailsMessage
import dev.tonexotg.protocol.message.RequestStateMessage
import dev.tonexotg.protocol.message.TonexMessage
import kotlinx.coroutines.CancellationException

/**
 * ⚠️ DIAGNOSTIC-ONLY (issue #27, S22). The first-write safety drill's actual sequencing logic:
 * backup capture, the preset-change byte-diff audit, and the revert drill.
 *
 * ## Every write here goes through the real controller — nothing here synthesizes one
 * The only writes issued anywhere in this file are [dev.tonexotg.protocol.TonexController.selectPreset],
 * [dev.tonexotg.protocol.TonexController.setParameter], and
 * [dev.tonexotg.protocol.TonexController.revertActivePreset] — called by [runPresetChangeByteDiffDrill]
 * and [runRevertDrill] exactly as production code would call them. The raw requests this file
 * issues directly over [TonexTransport] ([RequestStateMessage], [RequestPresetDetailsMessage]) are
 * both read-only asks the pedal is documented to answer without side effects — see each message
 * type's own KDoc — and their responses are captured via [MessageCaptureTap] purely to be
 * hex-dumped or byte-diffed, never to be replayed as a write. This preserves this project's core
 * safety invariant (no whole-state-blob replay from a hardcoded or previously-read capture) — see
 * [dev.tonexotg.protocol.PedalState]'s KDoc for the bug this invariant exists to prevent.
 *
 * ## Why raw requests are issued directly over [TonexTransport], not through the controller
 * [dev.tonexotg.protocol.TonexController] has no public method to fetch a full state blob or a raw
 * preset-details payload — by design (see [MessageCaptureTap]'s KDoc). Reading either one for a
 * diagnostic audit therefore means sending the underlying wire request directly and capturing the
 * response via [MessageCaptureTap], exactly as an external tool (the IK editor, a footswitch) would
 * be observed doing by the real controller's own reader. See the caller contract on
 * [captureStateBlob] and [capturePresetDetails]: never call these concurrently with a
 * [dev.tonexotg.protocol.TonexController] operation on the same connection.
 */

// ---- raw, read-only captures --------------------------------------------------------------

/**
 * Sends [RequestStateMessage] directly over [transport] and captures the matching `GetState`
 * response via [tap] — the raw bytes of the pedal's whole-device state blob, unparsed.
 *
 * Calls [MessageCaptureTap.drain] first, so a stale `StateUpdate` already sitting in [tap]'s
 * buffer (e.g. left over from the connection handshake) can never be mistaken for the response to
 * *this* request.
 *
 * ## Caller contract
 * Must not be called concurrently with a [dev.tonexotg.protocol.TonexController] operation on the
 * same connection — both write to the same physical transport, and interleaving would race two
 * writes onto the wire. Every function in this file that calls this one already respects that
 * sequencing; a caller building a new drill on top of it must preserve it too.
 */
suspend fun captureStateBlob(
    transport: TonexTransport,
    tap: MessageCaptureTap,
    timeoutMillis: Long,
): TonexResult<ByteArray> {
    tap.drain()
    writeRawFramed(transport, RequestStateMessage.encode()).let { if (it is TonexResult.Failure) return it }
    val message = tap.awaitMessage(timeoutMillis) { it is TonexMessage.StateUpdate }
        ?: return TonexResult.Failure(TonexError.Timeout("diagnostics-state-read", timeoutMillis))
    return TonexResult.Success((message as TonexMessage.StateUpdate).payload)
}

/** One preset's name and 109 parameter values, as captured by [capturePresetDetails]. */
data class PresetBackupEntry(
    val index: PresetIndex,
    val name: String,
    val parameters: FloatArray,
)

/**
 * Sends [RequestPresetDetailsMessage] for [index] at [PresetDetailsKind.SUMMARY] — never `FULL`;
 * see [PresetParameterExtractor]'s KDoc for why `SUMMARY` is the response that actually carries
 * parsed parameter values — and captures the response via [tap], extracting the preset's name and
 * its 109 parameter values.
 *
 * Read-only: requesting another preset's details does not change the pedal's active preset (the
 * same mechanism [dev.tonexotg.protocol.connection.DefaultTonexController]'s own handshake already
 * uses to harvest all 20 preset names without ever touching what's active).
 *
 * Same [MessageCaptureTap.drain]-first and non-concurrency caller contract as [captureStateBlob].
 */
suspend fun capturePresetDetails(
    transport: TonexTransport,
    tap: MessageCaptureTap,
    index: PresetIndex,
    timeoutMillis: Long,
): TonexResult<PresetBackupEntry> {
    tap.drain()
    writeRawFramed(transport, RequestPresetDetailsMessage.encode(index, PresetDetailsKind.SUMMARY))
        .let { if (it is TonexResult.Failure) return it }
    val message = tap.awaitMessage(timeoutMillis) { it is TonexMessage.PresetDetails && !it.full }
        ?: return TonexResult.Failure(TonexError.Timeout("diagnostics-preset-details-${index.value}", timeoutMillis))
    val payload = (message as TonexMessage.PresetDetails).payload

    val name = when (val r = PresetNameExtractor.extract(payload)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return TonexResult.Failure(r.error)
    }
    val parameters = when (val r = PresetParameterExtractor.extract(payload)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return TonexResult.Failure(r.error)
    }
    return TonexResult.Success(PresetBackupEntry(index, name, parameters))
}

/** A full backup: the raw state blob plus every one of the pedal's 20 onboard presets. */
data class FullBackup(
    val stateBlob: ByteArray,
    val presets: List<PresetBackupEntry>,
)

/**
 * Issue #27 §1 — captures the pedal's full state blob and all 20 presets' names/parameters,
 * before any write this session might issue. Fails fast (aborts, returning the first failure) on
 * any single preset read failing — a partial backup silently reported as complete would be worse
 * than an honest error (this project's "fail fast and loud" philosophy).
 */
suspend fun captureFullBackup(
    transport: TonexTransport,
    tap: MessageCaptureTap,
    timeoutMillis: Long,
): TonexResult<FullBackup> {
    val blob = when (val r = captureStateBlob(transport, tap, timeoutMillis)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }
    val entries = mutableListOf<PresetBackupEntry>()
    for (i in PresetIndex.VALID_RANGE) {
        when (val r = capturePresetDetails(transport, tap, PresetIndex(i), timeoutMillis)) {
            is TonexResult.Success -> entries.add(r.value)
            is TonexResult.Failure -> return r
        }
    }
    return TonexResult.Success(FullBackup(blob, entries))
}

// ---- the preset-change byte-diff drill (issue #27 §2/§3) ---------------------------------------

/** The result of [runPresetChangeByteDiffDrill]. */
data class PresetChangeDrillResult(
    val targetPreset: PresetIndex,
    val before: ByteArray,
    val after: ByteArray,
    val audit: PresetChangeAudit,
)

/**
 * Issue #27 §2/§3's actual drill: capture a full state blob, change the active preset via the real
 * [dev.tonexotg.protocol.TonexController.selectPreset] (never a synthesized write — see class
 * KDoc), capture the state blob again, and byte-diff the two arrays completely via
 * [PresetChangeAudit.audit].
 *
 * A clean [PresetChangeAudit.passed] result is, simultaneously, the answer to three separate parts
 * of issue #27: "only slot bytes changed" (§2), "no global drifted" (§2), and "`DIRECT_MONITOR`/
 * stomp-AB mode unchanged" (§3) — see [PresetChangeAudit]'s own KDoc for why those are one fact,
 * not three separate checks, once the diff is genuinely exhaustive.
 *
 * @return [TonexResult.Failure] if either state-blob capture or the [selectPreset][dev.tonexotg.protocol.TonexController.selectPreset]
 *   call itself fails — never a "drill completed, but here's an error" result buried inside a
 *   [TonexResult.Success]. A completed drill's [PresetChangeAudit.passed] may still be `false`;
 *   that is the drill *working*, not the drill failing to run.
 */
suspend fun runPresetChangeByteDiffDrill(
    controller: dev.tonexotg.protocol.TonexController,
    transport: TonexTransport,
    tap: MessageCaptureTap,
    targetPreset: PresetIndex,
    timeoutMillis: Long,
): TonexResult<PresetChangeDrillResult> {
    val before = when (val r = captureStateBlob(transport, tap, timeoutMillis)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }

    when (val r = controller.selectPreset(targetPreset)) {
        is TonexResult.Success -> Unit
        is TonexResult.Failure -> return r
    }

    val after = when (val r = captureStateBlob(transport, tap, timeoutMillis)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }

    val audit = PresetChangeAudit.audit(before, after)
    return TonexResult.Success(PresetChangeDrillResult(targetPreset, before, after, audit))
}

// ---- the revert drill (issue #27 §4) ------------------------------------------------------------

/** The result of [runRevertDrill]. */
data class RevertDrillResult(
    val presetIndex: PresetIndex,
    /** State-blob audit across the whole edit-then-revert sequence — expected to pass identically
     * to [PresetChangeAudit]'s meaning: revert uses only per-parameter writes, so the state blob
     * (globals/slots/colour table) should not move a single byte, not even the sanctioned ones. */
    val stateBlobAudit: PresetChangeAudit,
    /** The active preset's 109 parameter values as read from the pedal BEFORE the edit. */
    val beforeParameters: FloatArray,
    /** The active preset's 109 parameter values as read from the pedal AFTER the revert. */
    val afterParameters: FloatArray,
    /** Indices (0..108) where [beforeParameters] and [afterParameters] disagree beyond a small
     * float tolerance. Empty means the revert is confirmed byte-for-byte (well, float-for-float)
     * restored, verified by an independent read-back — not merely by trusting the local
     * `parameterValues` mirror the write path itself updates. */
    val mismatchedParameterIndices: List<Int>,
) {
    val passed: Boolean get() = stateBlobAudit.passed && mismatchedParameterIndices.isEmpty()
}

/** Float tolerance for parameter comparisons — matches the tolerance this project's own test
 * fixtures already use (`ConnectionTestFixtures.assertSnapshotValuesEqual`). */
private const val PARAMETER_TOLERANCE = 1e-3f

/**
 * Issue #27 §4: capture the active preset's live parameters and the full state blob, run [edit]
 * (the caller's own sequence of [dev.tonexotg.protocol.TonexController.setParameter] calls against
 * several parameters), call [dev.tonexotg.protocol.TonexController.revertActivePreset], then
 * independently re-read both the parameters and the state blob and compare against the
 * pre-[edit] baseline.
 *
 * "Independently re-read," not "trust `parameterValues`": the parameter read-back goes through a
 * fresh [capturePresetDetails] request-and-capture, a genuine round trip to the pedal, exactly the
 * same "accepted by the transport is not the same as confirmed applied" distinction
 * `ProbeSession.runWriteTest` already draws for its own single-parameter write test.
 *
 * @param edit the caller's edit sequence — must return [TonexResult.Success] for the drill to
 *   proceed to the revert step; a failure here aborts the drill (nothing to revert).
 * @return [TonexResult.Failure] if any capture, [edit], or the revert call itself fails outright;
 *   see [runPresetChangeByteDiffDrill]'s KDoc for the same "failure vs. a completed-but-failed-
 *   audit result" distinction.
 */
suspend fun runRevertDrill(
    controller: dev.tonexotg.protocol.TonexController,
    transport: TonexTransport,
    tap: MessageCaptureTap,
    timeoutMillis: Long,
    edit: suspend () -> TonexResult<Unit>,
): TonexResult<RevertDrillResult> {
    val active = controller.activePreset.value
        ?: return TonexResult.Failure(
            TonexError.ProtocolStateViolation(controller.connectionState.value, "revert drill: active preset is not known"),
        )

    val beforeBlob = when (val r = captureStateBlob(transport, tap, timeoutMillis)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }
    val beforeEntry = when (val r = capturePresetDetails(transport, tap, active, timeoutMillis)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }

    when (val r = edit()) {
        is TonexResult.Success -> Unit
        is TonexResult.Failure -> return r
    }

    when (val r = controller.revertActivePreset()) {
        is TonexResult.Success -> Unit
        is TonexResult.Failure -> return r
    }

    val afterEntry = when (val r = capturePresetDetails(transport, tap, active, timeoutMillis)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }
    val afterBlob = when (val r = captureStateBlob(transport, tap, timeoutMillis)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }

    val mismatched = beforeEntry.parameters.indices.filter { i ->
        kotlin.math.abs(beforeEntry.parameters[i] - afterEntry.parameters[i]) > PARAMETER_TOLERANCE
    }
    val stateAudit = PresetChangeAudit.audit(beforeBlob, afterBlob)

    return TonexResult.Success(
        RevertDrillResult(
            presetIndex = active,
            stateBlobAudit = stateAudit,
            beforeParameters = beforeEntry.parameters,
            afterParameters = afterEntry.parameters,
            mismatchedParameterIndices = mismatched,
        ),
    )
}

// ---- shared plumbing ------------------------------------------------------------------------

/**
 * Frames [payload] as an HDLC frame and writes it directly to [transport] — the raw-write half of
 * every capture function above. Deliberately does not go through
 * [dev.tonexotg.protocol.TonexController] (it has no method for sending an arbitrary read-only
 * request); this mirrors [dev.tonexotg.protocol.connection.DefaultTonexController]'s own
 * `writeFramed` (short-write and exception handling), duplicated here rather than reused because
 * that function is `private` to the controller.
 */
private suspend fun writeRawFramed(transport: TonexTransport, payload: ByteArray): TonexResult<Unit> {
    val framed = HdlcFrame.encode(payload)
    val written = try {
        transport.write(framed)
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        return TonexResult.Failure(TonexError.TransportFailure(t))
    }
    if (written != framed.size) {
        return TonexResult.Failure(
            TonexError.TransportFailure(IllegalStateException("short write: wrote $written of ${framed.size} bytes")),
        )
    }
    return TonexResult.Success(Unit)
}
