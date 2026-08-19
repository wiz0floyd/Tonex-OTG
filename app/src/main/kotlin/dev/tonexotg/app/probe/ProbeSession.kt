package dev.tonexotg.app.probe

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.ParameterSpec
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.connection.ConnectionTimeouts
import dev.tonexotg.protocol.connection.DefaultTonexController
import dev.tonexotg.protocol.diagnostics.BurstStats
import dev.tonexotg.protocol.diagnostics.TimedResult
import dev.tonexotg.protocol.diagnostics.measureLatency
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.params.ParameterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val MICROS_PER_MILLI = 1_000.0

/**
 * ⚠️ DIAGNOSTIC-ONLY. Orchestrates the S20 hardware probe (issue #25) against a real pedal: the
 * read-only pass (§1-5 of the issue's brief) and, separately and only on explicit confirmation,
 * the single-parameter write test (§6). See [UsbTonexTransport]'s class KDoc for why this
 * harness's transport lifecycle is unusual (one physical [UsbDeviceConnection] shared across
 * several logical `:protocol` connect/disconnect cycles).
 *
 * Not wired into the main app's navigation — see [ProbeActivity].
 */
class ProbeSession(
    private val scope: CoroutineScope,
    private val log: ProbeLog,
) {

    /**
     * The parameter this session's write test targets: a single mid-band EQ level. Deliberately
     * not master volume (a global, not preset-scoped) and not anything preset-identity-related —
     * see issue #25 §6 and [ParameterRegistry] for the full field list this was chosen from.
     */
    val writeTestParameterEnumName: String = "EQ_MID"

    /** S21 (issue #26): how many back-to-back writes [runLatencyMeasurements]'s slider-drag burst fires. Mirrors [SLIDER_DRAG_STEPS]. */
    val sliderDragSteps: Int = SLIDER_DRAG_STEPS

    /**
     * Runs the read-only diagnostic pass: connects with [FirmwareCapabilities.NONE_CONFIRMED]
     * (the conservative default this codebase's own KDoc calls for before a capability is
     * actually confirmed), dumps everything issue #25 asks for that a read can answer, then
     * disconnects. Never calls [DefaultTonexController.setParameter] or anything else that
     * writes to the pedal.
     *
     * @return the active preset observed, for the write test to cross-check against later, or
     *   `null` if the connect itself failed.
     */
    suspend fun runReadOnlyDiagnostics(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
    ): PresetIndex? {
        log.info("=== Read-only diagnostic pass starting ===")
        val transport = LoggingTonexTransport(UsbTonexTransport(connection, inEndpoint, outEndpoint), log, "read-only")
        val controller = DefaultTonexController(
            scope = scope,
            capabilities = FirmwareCapabilities.NONE_CONFIRMED,
            timeouts = ConnectionTimeouts.DEFAULT,
        )

        val result = controller.connect(transport)
        if (result is TonexResult.Failure) {
            log.error("connect() failed: ${result.error.message} (state=${controller.connectionState.value})")
            transport.close()
            return null
        }
        log.info("connect() reached ${controller.connectionState.value} — handshake, state-blob read, and " +
            "preset-name harvest all succeeded.")

        // Firmware version — genuinely not available. Noted per issue #25's "if not, note that as
        // a finding" instruction rather than guessed at.
        log.finding(
            "Firmware version: :protocol does not parse or expose a firmware-version field from any " +
                "known pedal message (TonexMessage.Hello's payload is documented as carrying nothing " +
                "further extractable, and no other catalogued message carries a version string). No " +
                "firmware version could be recorded automatically this run. If the IK ToneX Editor / " +
                "companion app shows a firmware version for this pedal, please note it manually as a " +
                "comment on issue #25 — S8's offset table is currently only pinned to an upstream " +
                "source commit as a proxy, not a real observed firmware version.",
        )

        log.finding(
            "request_preset_details (SUMMARY, wire type 0x0300 / response 0x0304): CONFIRMED WORKING — " +
                "${controller.presets.value.size}/20 preset names harvested successfully during connect().",
        )

        log.finding(
            "State blob (GetState, wire type 0x0306) was read and parsed by StateBlobReader without " +
                "error — implies this firmware's state-blob size/shape is at least structurally " +
                "compatible with the offsets StateBlobOffsets currently pins. This is NOT a full " +
                "byte-level confirmation of every S8 offset; that needs a hand-checked comparison " +
                "against a real captured blob, which this harness does not attempt.",
        )

        val active = controller.activePreset.value
        log.info("Active preset: ${active?.value?.let { "slot $it" } ?: "unknown"}")
        controller.presets.value.forEach { preset ->
            log.info("  preset ${preset.index.value}: \"${preset.pedalName}\"")
        }

        val paramValues = controller.parameterValues.value
        log.finding(
            "parameterValues after connect(): ${paramValues.size} entries known " +
                "(expected up to 109 preset-scoped + up to 7 global, minus master volume since " +
                "capabilities.supportsSingleParameterWrite=false was used for this read-only pass).",
        )

        logS6TableProbe(paramValues, enumName = "CABINET_TYPE", index = 24)
        logS6TableProbe(paramValues, enumName = "VIR_MIC_2_X", index = 31)

        controller.disconnect()
        transport.close()
        log.info("=== Read-only diagnostic pass complete; disconnected ===")
        return active
    }

    private fun logS6TableProbe(paramValues: Map<ParameterId, Float>, enumName: String, index: Int) {
        val spec = ParameterRegistry.byIndex(index)
        val value = paramValues[ParameterId(index)]
        if (value == null) {
            log.finding(
                "$enumName (index $index): not present in this preset's captured parameter values " +
                    "(snapshot capture may have failed or not yet run) — S6 table question left " +
                    "unresolved this run, skipping rather than forcing it.",
            )
            return
        }
        log.finding(
            "$enumName (index $index) current value on this preset: $value (registered range " +
                "${spec?.min}..${spec?.max}). Cross-check against what the pedal's own front panel " +
                "or the IK editor currently shows for this preset's setting, and note the correspondence " +
                "as a comment on issue #25 — a single read confirms the value is in-range but does not " +
                "by itself resolve the max/ordering question; a value found OUTSIDE the registered range " +
                "would, however, immediately disprove the current registry entry.",
        )
    }

    /**
     * The single-parameter write test (issue #25 §6) — the one piece of this harness that
     * writes to the pedal. Must only be called after the caller's own explicit, human-confirmed
     * warning step; see [ProbeActivity]. Never chained automatically after
     * [runReadOnlyDiagnostics].
     *
     * Runs as two more `:protocol` connect/disconnect cycles against the same physical
     * [connection] (see [UsbTonexTransport]'s class KDoc):
     * 1. Reconnect, read [writeTestParameterEnumName]'s current value, write a distinguishable
     *    test value.
     * 2. Reconnect again and re-read the same parameter — a genuine pedal-side read-back, not
     *    just "the transport accepted the write bytes" — to answer issue #25's actual question
     *    ("per-parameter write support: confirmed yes/no") empirically. Then write the original
     *    value back, but do NOT chain a third reconnect to re-verify the restore: this project's
     *    own documented stance (see `TonexError.RevertIncomplete`'s KDoc) is that "accepted by
     *    the transport" is deliberately not conflated with "confirmed applied by the pedal," and
     *    it does not pay a per-write read-back round trip. The restore's own success is reported
     *    exactly that honestly — sent, not independently confirmed — rather than implied.
     */
    suspend fun runWriteTest(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
        expectedActivePreset: PresetIndex?,
    ) {
        log.warn("=== Single-parameter write test starting (this WILL write to the pedal) ===")
        val spec = ParameterRegistry.byEnumName(writeTestParameterEnumName)
        if (spec == null) {
            log.error("$writeTestParameterEnumName not found in ParameterRegistry — aborting write test.")
            return
        }
        val id = spec.id

        // ---- Cycle 1: reconnect, read current value, write a distinguishable test value ------
        val transport1 = LoggingTonexTransport(UsbTonexTransport(connection, inEndpoint, outEndpoint), log, "write-cycle1")
        val controller1 = DefaultTonexController(
            scope = scope,
            // Deliberate probe override, NOT a claim that firmware support is confirmed — this
            // is exactly the fact this test exists to determine. See FirmwareCapabilities' own
            // KDoc: "false is the only safe default for a caller that has not yet established
            // this some other way." This harness IS that "some other way."
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            timeouts = ConnectionTimeouts.DEFAULT,
        )
        val connect1 = controller1.connect(transport1)
        if (connect1 is TonexResult.Failure) {
            log.error("Write test: reconnect (cycle 1) failed: ${connect1.error.message}. Aborting; nothing was written.")
            transport1.close()
            return
        }

        val active1 = controller1.activePreset.value
        if (expectedActivePreset != null && active1 != expectedActivePreset) {
            log.warn(
                "Active preset changed since the read-only pass (was ${expectedActivePreset.value}, now " +
                    "${active1?.value}) — proceeding against the CURRENTLY active preset (freshest read wins), " +
                    "but note this if it's unexpected.",
            )
        }

        val before = controller1.parameterValues.value[id]
        if (before == null) {
            log.error(
                "Write test: current value of $writeTestParameterEnumName is not known after connect() " +
                    "(snapshot capture likely failed) — refusing to write, because the original value " +
                    "needed to safely restore it afterward cannot be confirmed. Aborting; nothing was written.",
            )
            controller1.disconnect()
            transport1.close()
            return
        }
        if (!before.isFinite() || before < spec.min || before > spec.max) {
            // DefaultTonexController.writeParameterLocked REJECTS (does not clamp) out-of-range
            // values, and the read path applies no range validation — so a pedal-reported value
            // outside the registered bounds would mean the test write lands, then the restore
            // write is rejected client-side before a single byte is sent. Abort before writing
            // anything, same as the "before == null" guard above. The explicit isFinite() check
            // is required because NaN < x and NaN > x are both false — a NaN before value would
            // otherwise slide straight through the range comparison. See issue #25,
            // opus-rereviewer-probe25, B2 checkpoint 3/5.
            log.error(
                "Write test: current value of $writeTestParameterEnumName ($before ${spec.unit}) is OUTSIDE " +
                    "the registered range (${spec.min}..${spec.max} ${spec.unit}) or not finite — refusing to " +
                    "write, because the restore write would be rejected client-side after the test write " +
                    "already landed. This itself is a finding: this firmware reports " +
                    "$writeTestParameterEnumName outside ParameterRegistry's bounds. Aborting; nothing was " +
                    "written.",
            )
            controller1.disconnect()
            transport1.close()
            return
        }

        val testValue = if (before <= (spec.min + spec.max) / 2f) spec.max else spec.min
        log.info(
            "$writeTestParameterEnumName current value: $before ${spec.unit}. Test value chosen: " +
                "$testValue ${spec.unit} (registered range ${spec.min}..${spec.max}).",
        )

        val writeResult = controller1.setParameter(id, testValue)
        when (writeResult) {
            is TonexResult.Success -> log.info(
                "Test write ACCEPTED BY THE TRANSPORT (bytes were sent; this is NOT yet confirmed applied " +
                    "by the pedal — verifying next via a fresh reconnect and read-back).",
            )
            is TonexResult.Failure -> {
                log.error(
                    "Test write FAILED at the transport/protocol layer: ${writeResult.error.message}. " +
                        "Per-parameter write support: NOT CONFIRMED (failed outright).",
                )
                controller1.disconnect()
                transport1.close()
                return
            }
        }
        controller1.disconnect()
        transport1.close()

        // ---- Cycle 2: reconnect, read back to verify, then restore the original value ---------
        //
        // Wrapped in NonCancellable from here through the end of the restore: ProbeActivity's
        // scope can be cancelled (onDestroy, e.g. back press or the system reclaiming the
        // Activity) at any point in this multi-second reconnect-and-verify window, and a plain
        // CancellationException unwind here would skip the restore write silently — the pedal
        // would be left at the test value with no log trace. See issue #25, opus-reviewer-probe25
        // B1. (ProbeActivity also declares android:configChanges so a rotation specifically can't
        // trigger this in the first place — this NonCancellable guard is the backstop for the
        // other triggers: back press, Home + system reclaim, etc.)
        withContext(NonCancellable) {
            val transport2 = LoggingTonexTransport(UsbTonexTransport(connection, inEndpoint, outEndpoint), log, "write-cycle2")
            val controller2 = DefaultTonexController(
                scope = scope,
                capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
                timeouts = ConnectionTimeouts.DEFAULT,
            )
            val connect2 = controller2.connect(transport2)
            if (connect2 is TonexResult.Failure) {
                log.error(
                    "Write test: reconnect (cycle 2, to verify + restore) FAILED: ${connect2.error.message}. " +
                        "Per-parameter write support: UNKNOWN (write was sent but could not be verified). " +
                        "The pedal's $writeTestParameterEnumName may still hold the TEST value ($testValue " +
                        "${spec.unit}), NOT the original ($before ${spec.unit}) — please check/fix this " +
                        "manually on the pedal or via a future connection.",
                )
                transport2.close()
                return@withContext
            }

            // Per-parameter writes carry no preset index — they land on whatever preset is
            // currently active on the pedal. If the active preset drifted during the reconnect
            // window (footswitch, front panel, IK editor — anything), then `after` below would be
            // read from the WRONG preset (a near-certain false "CONFIRMED NO"), and restoring
            // `before` would write preset A's original value onto preset B. Re-check before
            // trusting either. See issue #25, opus-reviewer-probe25 B3.
            val active2 = controller2.activePreset.value
            if (active2 != active1) {
                log.error(
                    "Write test: active preset changed during the reconnect window (was ${active1?.value}, " +
                        "now ${active2?.value}). The read-back cannot be trusted to reflect preset " +
                        "${active1?.value}, and restoring $writeTestParameterEnumName would land on preset " +
                        "${active2?.value}, not the preset it came from. Per-parameter write support: " +
                        "INCONCLUSIVE (preset drift during test — not a real result, do NOT treat as " +
                        "CONFIRMED NO). Skipping the restore write. Preset ${active1?.value} may still hold " +
                        "the TEST value ($testValue ${spec.unit}) — please manually restore " +
                        "$writeTestParameterEnumName on preset ${active1?.value} to $before ${spec.unit}.",
                )
                controller2.disconnect()
                transport2.close()
                return@withContext
            }

            val after = controller2.parameterValues.value[id]
            when {
                after == null -> log.error(
                    "Per-parameter write support: INCONCLUSIVE — could not re-read $writeTestParameterEnumName " +
                        "after reconnecting (snapshot capture failed on the verification read).",
                )
                kotlin.math.abs(after - testValue) < 0.01f -> log.finding(
                    "Per-parameter write support: CONFIRMED YES. Read back $writeTestParameterEnumName = " +
                        "$after ${spec.unit} after reconnecting — matches the test value ($testValue) that was " +
                        "written, independently of the transport's own accepted-the-bytes signal.",
                )
                else -> log.finding(
                    "Per-parameter write support: CONFIRMED NO (or firmware silently ignored it). Read back " +
                        "$writeTestParameterEnumName = $after ${spec.unit} after reconnecting — this does NOT " +
                        "match the test value ($testValue) that was written. STOP AND ESCALATE per issue #25: " +
                        "S9b's revert design (per-parameter writes) needs revisiting for this pedal's firmware.",
                )
            }

            val restoreResult = controller2.setParameter(id, before)
            when (restoreResult) {
                is TonexResult.Success -> log.info(
                    "Restore write (back to original value $before ${spec.unit}) was ACCEPTED BY THE " +
                        "TRANSPORT. Its actual effect on the pedal was NOT independently re-verified via " +
                        "another read-back — this harness does not chain a third reconnect for that, matching " +
                        "this project's documented stance against per-write read-back verification (see " +
                        "TonexError.RevertIncomplete's KDoc). If you want certainty, check " +
                        "$writeTestParameterEnumName on the pedal directly, or reconnect once more.",
                )
                is TonexResult.Failure -> log.error(
                    "Restore write FAILED at the transport/protocol layer: ${restoreResult.error.message}. The " +
                        "pedal's $writeTestParameterEnumName is very likely STILL AT THE TEST VALUE " +
                        "($testValue ${spec.unit}), not the original ($before ${spec.unit}). Please restore it " +
                        "manually.",
                )
            }

            controller2.disconnect()
            transport2.close()
        }
        log.warn("=== Single-parameter write test complete ===")
    }

    // ==== S21 (issue #26): latency measurements =================================================
    //
    // Purely informational. Per the product owner's issue #26 comment (posted after the story was
    // written, recalibrating it for a hobby, non-gig-critical project): "there is no hard numeric
    // target to hit or block on. Measure and report actual latency for information." Nothing below
    // treats a measured number as pass/fail against 200ms or any other figure -- it only logs what
    // was actually measured, honestly labelled as either a fire-and-forget transport-write duration
    // ([setParameter]/[selectPreset] await no pedal acknowledgement -- see [DefaultTonexController]'s
    // own `writeFramed`) or a burst summary, never implied to be more than that.

    /**
     * Runs all three S21 latency measurements in one reconnect cycle against [connection]:
     * preset-change, single-parameter-write, and a rapid-fire "slider drag" burst. Uses
     * [transportKind] to pick which low-level USB transfer API backs this run — see [TransportKind]
     * — so a human can execute this twice against the same pedal (once per kind) and compare the
     * two saved logs, per issue #26's UsbRequest-vs-bulkTransfer acceptance criterion.
     *
     * Like [runWriteTest], this WRITES to the pedal (parameter writes and preset switches) and must
     * only be called after the caller's own explicit, human-confirmed warning step; see
     * [ProbeActivity]. Every measurement restores what it changed (the test parameter's original
     * value, the original active preset) before this function returns, wherever that is possible —
     * see each sub-measurement's own KDoc for what happens when a restore can't be attempted.
     */
    suspend fun runLatencyMeasurements(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
        transportKind: TransportKind,
    ) {
        log.warn("=== Latency measurements starting (transport=${transportKind.label}; this WILL write to the pedal) ===")
        val spec = ParameterRegistry.byEnumName(writeTestParameterEnumName)
        if (spec == null) {
            log.error("$writeTestParameterEnumName not found in ParameterRegistry — aborting latency measurements.")
            return
        }
        val id = spec.id

        val transport = LoggingTonexTransport(transportKind.create(connection, inEndpoint, outEndpoint), log, "latency")
        val controller = DefaultTonexController(
            scope = scope,
            // Same deliberate probe override as runWriteTest -- see its KDoc. This measurement pass
            // needs setParameter to actually be attempted to time it.
            capabilities = FirmwareCapabilities(supportsSingleParameterWrite = true),
            timeouts = ConnectionTimeouts.DEFAULT,
        )
        val connectResult = controller.connect(transport)
        if (connectResult is TonexResult.Failure) {
            log.error(
                "Latency measurements: reconnect failed: ${connectResult.error.message}. Aborting; nothing was " +
                    "measured or written.",
            )
            transport.close()
            return
        }

        val originalActive = controller.activePreset.value
        if (originalActive == null) {
            log.error(
                "Latency measurements: active preset unknown after connect() — cannot safely run the preset-change " +
                    "measurement (there would be no preset to restore to afterward). Aborting the whole pass rather " +
                    "than running only some of it against an unknown baseline.",
            )
            controller.disconnect()
            transport.close()
            return
        }

        // Wrapped in NonCancellable: each sub-measurement below changes pedal state (preset,
        // parameter value) and then restores it, exactly like runWriteTest's cycle 2 -- see that
        // function's KDoc for why a plain cancellation unwind here (ProbeActivity's scope dying on
        // back press or system reclaim mid-measurement) would silently skip a restore and leave the
        // pedal on the wrong preset or an extreme parameter value. See issue #26's adversarial
        // review, finding S3. disconnect()/close() live INSIDE this block, in a finally, and behind
        // runCatching -- matching runWriteTest's shape exactly. A first cut left them outside: a
        // cancellation there still resumed teardown() far enough to CAS teardownDone before dying at
        // the next suspension point (readerJob.cancelAndJoin()), permanently orphaning the transport
        // and leaving UsbRequestTonexTransport's loop thread as a second live requestWait() caller on
        // the shared connection -- exactly the misrouting hazard that class's own KDoc calls out. A
        // throw out of the measurements (e.g. BurstStats.of's require()) skipped the same teardown
        // even without cancellation, since there was no try/finally at all. See PR #60 review.
        withContext(NonCancellable) {
            // Suppress LoggingTonexTransport's per-write hex-dump logging for the timed calls only
            // -- that string building plus ProbeLog's list-copy append are systematic overhead
            // sitting inside the very window being measured, and specifically bias the burst's
            // growth-ratio signal upward as the log grows. Connect/disconnect traffic above and
            // below this block is still logged normally. See issue #26's adversarial review,
            // finding S4.
            transport.quiet = true
            try {
                measurePresetChangeLatency(controller, originalActive)
                measureParameterWriteLatency(controller, id, spec)
                measureSliderDragBurst(controller, id, spec)
            } finally {
                transport.quiet = false
                runCatching { controller.disconnect() }
                    .onFailure { log.error("Latency measurements: disconnect() failed during teardown: ${it.message}") }
                runCatching { transport.close() }
                    .onFailure { log.error("Latency measurements: transport.close() failed during teardown: ${it.message}") }
            }
        }

        log.warn("=== Latency measurements complete ===")
    }

    /**
     * Issue #26's "preset-change latency": times [DefaultTonexController.selectPreset] from call to
     * completion, switching to a different preset and then immediately switching back to
     * [originalActive] (also timed) so the pedal ends this measurement on the preset it started on.
     * `(originalActive.value + 1) mod 20` is always a different, always-valid preset index — no
     * lookup or extra state needed to pick a target.
     */
    private suspend fun measurePresetChangeLatency(controller: DefaultTonexController, originalActive: PresetIndex) {
        val target = PresetIndex((originalActive.value + 1) % (PresetIndex.VALID_RANGE.last + 1))

        log.info("Preset-change latency: switching preset ${originalActive.value} -> ${target.value}...")
        logPresetChangeResult(measureLatency { controller.selectPreset(target) }, originalActive.value, target.value)

        log.info("Preset-change latency: restoring preset ${target.value} -> ${originalActive.value}...")
        logPresetChangeResult(measureLatency { controller.selectPreset(originalActive) }, target.value, originalActive.value)
    }

    private fun logPresetChangeResult(timed: TimedResult<TonexResult<Unit>>, from: Int, to: Int) {
        val ms = timed.elapsedMicros / MICROS_PER_MILLI
        when (val result = timed.value) {
            is TonexResult.Success -> log.finding(
                "Preset-change latency (preset $from -> $to): ${"%.3f".format(ms)} ms (selectPreset() call to " +
                    "completion — informational only, no pass/fail target; see issue #26).",
            )
            is TonexResult.Failure -> log.error(
                "Preset-change latency (preset $from -> $to): selectPreset() FAILED after ${"%.3f".format(ms)} ms: " +
                    "${result.error.message}",
            )
        }
    }

    /**
     * Issue #26's "single-parameter-write latency": times [DefaultTonexController.setParameter]
     * writing a distinguishable test value, then times writing the original value back. Reuses the
     * same before-writing safety checks as [runWriteTest] (known, finite, in-range current value) —
     * duplicated rather than shared, since [runWriteTest] is S20's already-reviewed code and this is
     * a separate, narrower measurement that should not risk altering its behaviour.
     */
    private suspend fun measureParameterWriteLatency(controller: DefaultTonexController, id: ParameterId, spec: ParameterSpec) {
        val before = controller.parameterValues.value[id]
        if (before == null || !before.isFinite() || before < spec.min || before > spec.max) {
            log.error(
                "Parameter-write latency: current value of $writeTestParameterEnumName is unknown, non-finite, or " +
                    "outside the registered range (${spec.min}..${spec.max}) — refusing to write (same safety rule " +
                    "as the single-parameter write test). Skipping this measurement.",
            )
            return
        }
        val testValue = if (before <= (spec.min + spec.max) / 2f) spec.max else spec.min

        log.info("Parameter-write latency: writing $writeTestParameterEnumName = $testValue ${spec.unit} (from $before)...")
        logParamWriteResult(measureLatency { controller.setParameter(id, testValue) }, before, testValue, spec.unit)

        log.info("Parameter-write latency: restoring $writeTestParameterEnumName = $before ${spec.unit}...")
        logParamWriteResult(measureLatency { controller.setParameter(id, before) }, testValue, before, spec.unit)
    }

    private fun logParamWriteResult(timed: TimedResult<TonexResult<Unit>>, from: Float, to: Float, unit: String) {
        val ms = timed.elapsedMicros / MICROS_PER_MILLI
        when (val result = timed.value) {
            is TonexResult.Success -> log.finding(
                "Parameter-write latency ($writeTestParameterEnumName $from -> $to $unit): ${"%.3f".format(ms)} ms " +
                    "(setParameter() call to completion. This is a fire-and-forget transport write — no pedal " +
                    "acknowledgement is awaited, so this measures 'accepted by the transport', not a confirmed " +
                    "pedal-applied round trip. Informational only, no pass/fail target; see issue #26).",
            )
            is TonexResult.Failure -> log.error(
                "Parameter-write latency ($writeTestParameterEnumName $from -> $to $unit): setParameter() FAILED " +
                    "after ${"%.3f".format(ms)} ms: ${result.error.message}",
            )
        }
    }

    /**
     * Issue #26's "sustained slider-drag throughput": fires [SLIDER_DRAG_STEPS] back-to-back
     * [DefaultTonexController.setParameter] calls sweeping [id] linearly from [ParameterSpec.min] to
     * [ParameterSpec.max], with deliberately NO pacing between calls — simulating a fast slider drag
     * — and times each one individually to see whether per-call latency grows over the burst
     * ([BurstStats.growthRatio]/[BurstStats.backlogSuspected]) rather than holding steady.
     *
     * Aborts the burst at the first failed call (fail-fast, matching this codebase's house style —
     * see [DefaultTonexController.revertActivePreset]'s KDoc for the same "abort at first failure"
     * reasoning) rather than continuing through a wedged transport. Always attempts to restore
     * [id]'s original value afterward, however far the burst got.
     */
    private suspend fun measureSliderDragBurst(controller: DefaultTonexController, id: ParameterId, spec: ParameterSpec) {
        val before = controller.parameterValues.value[id]
        if (before == null || !before.isFinite() || before < spec.min || before > spec.max) {
            log.error(
                "Slider-drag burst: current value of $writeTestParameterEnumName is unknown, non-finite, or " +
                    "outside the registered range (${spec.min}..${spec.max}) — refusing to write. Skipping this " +
                    "measurement.",
            )
            return
        }

        log.info(
            "Slider-drag burst: issuing $SLIDER_DRAG_STEPS back-to-back setParameter() calls on " +
                "$writeTestParameterEnumName (${spec.min}..${spec.max} ${spec.unit}), no pacing between them...",
        )
        val samples = mutableListOf<Long>()
        var completedCalls = 0
        for (i in 0 until SLIDER_DRAG_STEPS) {
            // .coerceIn guards against the final step landing fractionally outside [spec.min,
            // spec.max] by float rounding (i / (SLIDER_DRAG_STEPS - 1) need not hit exactly 1.0 at
            // the last step) -- writeParameterLocked REJECTS an out-of-range value rather than
            // clamping it, which would otherwise abort the burst on a spurious failure at the very
            // last call. See issue #26's adversarial review, LOW nits.
            val value = (spec.min + (spec.max - spec.min) * i / (SLIDER_DRAG_STEPS - 1)).coerceIn(spec.min, spec.max)
            val timed = measureLatency { controller.setParameter(id, value) }
            val ms = timed.elapsedMicros / MICROS_PER_MILLI
            when (val result = timed.value) {
                is TonexResult.Success -> {
                    samples += timed.elapsedMicros
                    completedCalls++
                }
                is TonexResult.Failure -> {
                    log.error(
                        "Slider-drag burst: call ${i + 1}/$SLIDER_DRAG_STEPS FAILED after ${"%.3f".format(ms)} ms: " +
                            "${result.error.message}. Aborting the rest of the burst rather than continuing through " +
                            "further failures.",
                    )
                    completedCalls++
                    break
                }
            }
        }

        if (samples.isNotEmpty()) {
            val stats = BurstStats.of(samples)
            fun msLong(micros: Long) = "${"%.3f".format(micros / MICROS_PER_MILLI)}ms"
            fun msDouble(micros: Double) = "${"%.3f".format(micros / MICROS_PER_MILLI)}ms"
            val backlogNote = when {
                !stats.growthRatio.isFinite() -> {
                    // The first-third mean was exactly 0 -- growthRatio is undefined (0/0-shaped),
                    // NOT evidence of steady latency. Reporting this as "no backlog signal" would
                    // claim the opposite of what an undefined ratio actually means. See issue #26's
                    // adversarial review, finding S1.
                    "growth ratio is UNDEFINED (first-third mean measured ${msDouble(stats.firstThirdMeanMicros)}, " +
                        "last-third mean ${msDouble(stats.lastThirdMeanMicros)}) — too fast to compute a ratio " +
                        "from, not evidence either way of backlog."
                }
                stats.backlogSuspected -> "LATENCY GREW SUBSTANTIALLY across the burst (growth ratio " +
                    "${"%.2f".format(stats.growthRatio)}x) — consistent with writes queuing or backing up under " +
                    "sustained, unpaced load."
                else -> "latency held roughly steady across the burst (growth ratio " +
                    "${"%.2f".format(stats.growthRatio)}x) — no backlog signal."
            }
            log.finding(
                "Slider-drag burst ($writeTestParameterEnumName): $SLIDER_DRAG_STEPS calls planned, " +
                    "$completedCalls attempted, ${samples.size} succeeded. min=${msLong(stats.minMicros)} " +
                    "mean=${msDouble(stats.meanMicros)} p50=${msLong(stats.p50Micros)} p90=${msLong(stats.p90Micros)} " +
                    "p99=${msLong(stats.p99Micros)} max=${msLong(stats.maxMicros)}. First-third mean " +
                    "${msDouble(stats.firstThirdMeanMicros)} vs last-third mean ${msDouble(stats.lastThirdMeanMicros)}. " +
                    "$backlogNote Informational only, no pass/fail target; see issue #26.",
            )
        } else {
            log.error("Slider-drag burst: every call failed — no successful samples to summarize.")
        }

        log.info("Slider-drag burst: restoring $writeTestParameterEnumName = $before ${spec.unit}...")
        when (val restore = controller.setParameter(id, before)) {
            is TonexResult.Success -> log.info("Slider-drag burst: restore write accepted by the transport.")
            is TonexResult.Failure -> log.error(
                "Slider-drag burst: restore write FAILED: ${restore.error.message}. $writeTestParameterEnumName may " +
                    "still hold a burst value, NOT the original ($before ${spec.unit}) — please restore it manually.",
            )
        }
    }

    private companion object {
        /**
         * Not derived from any measurement — a reasonable diagnostic burst size for simulating a
         * fast slider drag (comparable to the number of `onValueChange` callbacks a real Compose
         * `Slider` drag gesture can emit in well under a second). Large enough for [BurstStats]'
         * first-third/last-third comparison to be meaningful, small enough to run quickly and keep
         * one probe session short.
         */
        const val SLIDER_DRAG_STEPS: Int = 40
    }
}
