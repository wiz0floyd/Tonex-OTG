package dev.tonexotg.protocol.diagnostics

import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.TonexError
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.TonexTransport
import dev.tonexotg.protocol.connection.DefaultTonexController
import dev.tonexotg.protocol.framing.HdlcFrame
import dev.tonexotg.protocol.message.PresetDetailsKind
import dev.tonexotg.protocol.message.PresetNameExtractor
import dev.tonexotg.protocol.message.PresetParameterExtractor
import dev.tonexotg.protocol.message.RequestPresetDetailsMessage
import dev.tonexotg.protocol.message.RequestStateMessage
import dev.tonexotg.protocol.message.TonexMessage
import dev.tonexotg.protocol.state.StateBlobOffsets
import dev.tonexotg.protocol.state.StateBlobReader
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
 * ## Caller contract — and how [controller] closes the race the old contract only documented
 * Must not race a [dev.tonexotg.protocol.TonexController] operation's own request/response
 * correlation on the same connection — both this function and, e.g.,
 * [dev.tonexotg.protocol.connection.DefaultTonexController]'s internally-launched post-preset-
 * change snapshot capture correlate responses by message type only, so either can consume the
 * response meant for the other (Opus review of issue #27, finding M1). Pass the live
 * [dev.tonexotg.protocol.connection.DefaultTonexController] as [controller] and this function
 * acquires [dev.tonexotg.protocol.connection.DefaultTonexController.withOperationLock] around its
 * whole write-then-await round trip, serializing it against every one of that controller's own
 * locked operations, including that launched capture — see [DefaultTonexController.withOperationLock]'s
 * own KDoc for the full mechanism. [controller] is `null` only in tests exercising this function
 * with no live controller at all (nothing to race against then); every real caller in this file
 * passes one.
 *
 * ## Rejects an implausibly short payload rather than diffing garbage
 * Every other consumer of a raw state blob in this codebase ([dev.tonexotg.protocol.PedalState.create],
 * [StateBlobReader], [dev.tonexotg.protocol.state.StateBlobPatcher]) enforces
 * [StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE] before trusting the bytes. This function is the one
 * other place a raw state blob enters this codebase, so it enforces the same floor — an Opus
 * review of issue #27 (M2) pointed out that without this, two equal-length *implausible* payloads
 * (e.g. a truncated read on both sides of a drill) would byte-diff as identical and the drill would
 * report a clean PASS with no evidence anything real was even read.
 */
suspend fun captureStateBlob(
    transport: TonexTransport,
    tap: MessageCaptureTap,
    timeoutMillis: Long,
    controller: DefaultTonexController? = null,
): TonexResult<ByteArray> {
    suspend fun roundTrip(): TonexResult<ByteArray> {
        tap.drain()
        writeRawFramed(transport, RequestStateMessage.encode()).let { if (it is TonexResult.Failure) return it }
        val message = tap.awaitMessage(timeoutMillis) { it is TonexMessage.StateUpdate }
            ?: return TonexResult.Failure(TonexError.Timeout("diagnostics-state-read", timeoutMillis))
        val payload = (message as TonexMessage.StateUpdate).payload
        if (payload.size < StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE) {
            return TonexResult.Failure(
                TonexError.BlobTooShortToPatch(
                    minimumSize = StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE,
                    actualSize = payload.size,
                ),
            )
        }
        return TonexResult.Success(payload)
    }
    return controller?.withOperationLock { roundTrip() } ?: roundTrip()
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
 * Same [MessageCaptureTap.drain]-first caller contract as [captureStateBlob], including the same
 * [controller]-serializes-against-M1 mechanism — see that function's KDoc.
 */
suspend fun capturePresetDetails(
    transport: TonexTransport,
    tap: MessageCaptureTap,
    index: PresetIndex,
    timeoutMillis: Long,
    controller: DefaultTonexController? = null,
): TonexResult<PresetBackupEntry> {
    suspend fun roundTrip(): TonexResult<PresetBackupEntry> {
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
    return controller?.withOperationLock { roundTrip() } ?: roundTrip()
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
    controller: DefaultTonexController? = null,
): TonexResult<FullBackup> {
    val blob = when (val r = captureStateBlob(transport, tap, timeoutMillis, controller)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }
    val entries = mutableListOf<PresetBackupEntry>()
    for (i in PresetIndex.VALID_RANGE) {
        when (val r = capturePresetDetails(transport, tap, PresetIndex(i), timeoutMillis, controller)) {
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
 * stomp-AB mode/bypass mode unchanged" (§3) — see [PresetChangeAudit]'s own KDoc for why those are
 * one fact, not three separate checks, once the diff is genuinely exhaustive.
 *
 * ## Closing the vacuous-pass hole (Opus review, blocking finding B1)
 * Which offset [targetPreset]'s selection *should* touch is computed here from the real
 * pre-write [before] blob — via [StateBlobReader], mirroring exactly the same branches
 * [dev.tonexotg.protocol.connection.DefaultTonexController.selectPreset] itself takes — and passed
 * to [PresetChangeAudit.audit] as `expectedOffsets`. That closes the specific hole an Opus review
 * of this issue found reachable: [dev.tonexotg.protocol.connection.DefaultTonexController.selectPreset]
 * returns success the instant its write is framed onto the wire, never waiting for pedal
 * confirmation, so a dropped/deferred write, or an `awaitMessage` match against a stale
 * `StateUpdate`, could previously produce two identical blobs and a clean, evidence-free PASS —
 * exactly the "no side effects detected: true" report issue #27's own ground-truth comment
 * pre-declared unacceptable. If [targetPreset] is already the active preset,
 * [selectPreset][dev.tonexotg.protocol.TonexController.selectPreset] performs a complete no-write
 * no-op (see its own KDoc) — there is nothing to audit, so this function refuses to run rather than
 * report a vacuous pass for that case either.
 *
 * @return [TonexResult.Failure] if either state-blob capture, the pre-write shape read used to
 *   compute `expectedOffsets`, the [selectPreset][dev.tonexotg.protocol.TonexController.selectPreset]
 *   call itself, or the already-active-preset refusal above — never a "drill completed, but here's
 *   an error" result buried inside a [TonexResult.Success]. A completed drill's
 *   [PresetChangeAudit.passed] may still be `false`; that is the drill *working*, not the drill
 *   failing to run.
 */
suspend fun runPresetChangeByteDiffDrill(
    controller: DefaultTonexController,
    transport: TonexTransport,
    tap: MessageCaptureTap,
    targetPreset: PresetIndex,
    timeoutMillis: Long,
): TonexResult<PresetChangeDrillResult> {
    val before = when (val r = captureStateBlob(transport, tap, timeoutMillis, controller)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }

    // Mirrors DefaultTonexController#selectPreset's own branch logic exactly, against the SAME
    // `before` blob the drill will diff against — not a separate, possibly-stale read.
    val assignments = when (val r = StateBlobReader.slotAssignments(before)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }
    val activeSlot = when (val r = StateBlobReader.activeSlot(before)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }
    val holdingSlot = assignments.entries.firstOrNull { it.value == targetPreset }?.key
    val expectedOffsets: Set<Int> = when {
        holdingSlot == activeSlot -> return TonexResult.Failure(
            TonexError.ProtocolStateViolation(
                controller.connectionState.value,
                "preset-change byte-diff drill: preset ${targetPreset.value} is already active on slot " +
                    "$activeSlot — selectPreset() would be a complete no-write no-op, so there is nothing " +
                    "for this drill to audit. Choose a different target preset.",
            ),
        )
        holdingSlot != null -> setOf(StateBlobOffsets.END_CURRENT_SLOT)
        else -> setOf(StateBlobOffsets.endOffsetForSlotPreset(activeSlot))
    }

    when (val r = controller.selectPreset(targetPreset)) {
        is TonexResult.Success -> Unit
        is TonexResult.Failure -> return r
    }

    val after = when (val r = captureStateBlob(transport, tap, timeoutMillis, controller)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }

    val audit = PresetChangeAudit.audit(before, after, expectedOffsets)
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
 * @param edit the caller's edit sequence, invoked with the just-captured pre-edit baseline
 *   ([PresetBackupEntry.parameters]) so the caller can log it before issuing a single write — an
 *   Opus review of issue #27 (M4) pointed out that without this, a caller has no way to record the
 *   one piece of data that would let a human recover manually if [edit] or the revert call right
 *   after it fails outright (this function returns [TonexResult.Failure] in either case, carrying
 *   no baseline of its own). Must return [TonexResult.Success] for the drill to proceed to the
 *   revert step; a failure here aborts the drill (nothing to revert).
 * @return [TonexResult.Failure] if any capture, [edit], or the revert call itself fails outright;
 *   see [runPresetChangeByteDiffDrill]'s KDoc for the same "failure vs. a completed-but-failed-
 *   audit result" distinction.
 */
suspend fun runRevertDrill(
    controller: DefaultTonexController,
    transport: TonexTransport,
    tap: MessageCaptureTap,
    timeoutMillis: Long,
    edit: suspend (beforeEntry: PresetBackupEntry) -> TonexResult<Unit>,
): TonexResult<RevertDrillResult> {
    val active = controller.activePreset.value
        ?: return TonexResult.Failure(
            TonexError.ProtocolStateViolation(controller.connectionState.value, "revert drill: active preset is not known"),
        )

    val beforeBlob = when (val r = captureStateBlob(transport, tap, timeoutMillis, controller)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }
    val beforeEntry = when (val r = capturePresetDetails(transport, tap, active, timeoutMillis, controller)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }

    when (val r = edit(beforeEntry)) {
        is TonexResult.Success -> Unit
        is TonexResult.Failure -> return r
    }

    when (val r = controller.revertActivePreset()) {
        is TonexResult.Success -> Unit
        is TonexResult.Failure -> return r
    }

    val afterEntry = when (val r = capturePresetDetails(transport, tap, active, timeoutMillis, controller)) {
        is TonexResult.Success -> r.value
        is TonexResult.Failure -> return r
    }
    val afterBlob = when (val r = captureStateBlob(transport, tap, timeoutMillis, controller)) {
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
