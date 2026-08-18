package dev.tonexotg.app.probe

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.connection.ConnectionTimeouts
import dev.tonexotg.protocol.connection.DefaultTonexController
import dev.tonexotg.protocol.message.FirmwareCapabilities
import dev.tonexotg.protocol.params.ParameterRegistry
import kotlinx.coroutines.CoroutineScope

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

    /** The parameter this session's write test targets — see [findWriteTestCandidate]'s KDoc. */
    val writeTestParameterEnumName: String = "EQ_MID"

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
        val transport = UsbTonexTransport(connection, inEndpoint, outEndpoint)
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
        val transport1 = UsbTonexTransport(connection, inEndpoint, outEndpoint)
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
        val transport2 = UsbTonexTransport(connection, inEndpoint, outEndpoint)
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
            return
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
        log.warn("=== Single-parameter write test complete ===")
    }
}
