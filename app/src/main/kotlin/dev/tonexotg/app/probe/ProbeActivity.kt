package dev.tonexotg.app.probe

import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.protocol.PresetIndex
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ⚠️ DIAGNOSTIC-ONLY entry point for the S20 hardware probe (issue #25).
 *
 * Deliberately a standalone launcher Activity, not wired into `MainActivity`'s navigation or
 * `dev.tonexotg.app.ui.components` — see this package's other files for why. Launch it directly
 * (it has its own launcher icon, "Tonex Probe (Diagnostic)") to run the probe against a real
 * pedal, then use "Save & share log" to hand the results back for review.
 */
class ProbeActivity : ComponentActivity() {

    /**
     * Deliberately a plain Activity-scoped [CoroutineScope], not `rememberCoroutineScope()` —
     * [dev.tonexotg.protocol.connection.DefaultTonexController]'s own KDoc requires a scope that
     * outlives one `connect()` call and is not torn down by a Compose recomposition (e.g. a
     * screen rotation mid-probe would cancel a `rememberCoroutineScope()`-backed reader job).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Activity-scoped, not `remember { ProbeLog() }` inside the composable (issue #69): the file
     * sink has to be attached in [onCreate], before the first composition, so a log file exists
     * "from the moment the screen opens" per the issue rather than from first recomposition.
     * `android:configChanges` on this Activity (see the manifest) means `onCreate` genuinely
     * means "this Activity instance/process started", not "every rotation".
     */
    private val log = ProbeLog()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Real-time file sink + crash capture (issue #69), wired before any probe action can
        // run: the product owner currently has no way to pull logcat from a real device, so this
        // file is the actual retrieval path if anything goes wrong, including a crash.
        val logFile = ProbeLogFile.create(applicationContext)
        log.attachFileSink(logFile)
        installProbeCrashHandler(logFile)
        log.info("Diagnostic log file opened: ${logFile.file.absolutePath}")
        setContent {
            TonexTheme {
                Surface {
                    ProbeScreen(scope, log, logFile.file)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Deliberately NOT scope.cancel() here. This is a one-shot diagnostic Activity, not a
        // production app: DefaultTonexController.startReader launches its reader coroutine on
        // this same scope, and cancelling the scope from onDestroy (back press, Home + system
        // reclaim, etc.) races runWriteTest's cycle-2 reconnect-and-restore in two ways that a
        // withContext(NonCancellable) block inside ProbeSession cannot fix on its own: (a) a
        // cancellation landing before that block is reached (e.g. during the preceding
        // disconnect()'s cancelAndJoin) throws immediately, and (b) even once inside the block,
        // controller2.connect() starts its reader via scope.launch on this same
        // cancelled-or-cancelling scope, so the reader never runs, the Hello handshake times out,
        // and the restore is skipped. See issue #25, opus-rereviewer-probe25, B1 checkpoint 2/5.
        // Every operation here is already bounded by ConnectionTimeouts, so the reader and any
        // in-flight probe work finish on their own even after the Activity is gone, and ProbeLog
        // mirrors every entry to logcat so results survive independently of the Activity's
        // lifecycle. This works specifically because the UsbDeviceConnection is deliberately NOT
        // closed here either — it's only released by the explicit "Release USB connection"
        // button — so the transport stays usable for the reader/restore to finish.
    }
}

private class UsbConnectionHandles(
    val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    val commInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
)

@Composable
private fun ProbeScreen(scope: CoroutineScope, log: ProbeLog, logFile: File) {
    val context = LocalContext.current
    val entries by log.entries.collectAsState()
    val probeSession = remember { ProbeSession(scope, log) }

    var handles by remember { mutableStateOf<UsbConnectionHandles?>(null) }
    var readOnlyPassDone by remember { mutableStateOf(false) }
    var activePreset by remember { mutableStateOf<PresetIndex?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showWriteTestDialog by remember { mutableStateOf(false) }
    var showLatencyDialog by remember { mutableStateOf(false) }
    var latencyTransportKind by remember { mutableStateOf(TransportKind.BULK_TRANSFER) }

    // S22 (issue #27): the write-drill buttons below are gated on `s22BackupPersisted`, not just
    // "the backup call returned true" -- Opus review finding L7: "the backup only really exists
    // once it has left in-memory state." A successful runSafetyBackup() only sets this once its
    // full log (state blob + all 20 presets) has also been written to a file on disk, same as
    // "Save & share log" does, but automatically -- so a user who runs the backup and immediately
    // proceeds to a write drill without remembering to press "Save & share log" still has a durable
    // backup sitting in probe-logs/ before either write drill can run.
    var s22BackupPersisted by remember { mutableStateOf(false) }
    var showPresetChangeDrillDialog by remember { mutableStateOf(false) }
    var showRevertDrillDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("S20 Hardware Probe (issue #25) — diagnostic only", style = MaterialTheme.typography.titleMedium)

        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val device = UsbDeviceOpener.findDevice(context)
                        if (device == null) {
                            log.error(
                                "No USB device found for VID=0x${UsbDeviceOpener.VENDOR_ID.toString(16)}/" +
                                    "PID=0x${UsbDeviceOpener.PRODUCT_ID.toString(16)}. Is the pedal plugged in " +
                                    "and this phone's USB-OTG adapter/cable working?",
                            )
                            return@launch
                        }
                        log.info("Found device: ${device.deviceName} (VID=0x${device.vendorId.toString(16)}, PID=0x${device.productId.toString(16)})")

                        when (val opened = UsbDeviceOpener.requestPermissionAndOpen(context, device)) {
                            is UsbDeviceOpener.OpenResult.Failure -> {
                                log.error("Could not open device: ${opened.reason}")
                            }
                            is UsbDeviceOpener.OpenResult.Success -> {
                                log.finding(
                                    "Raw config descriptors (${opened.rawDescriptors.size} bytes) — see issue #16's " +
                                        "malformed wMaxPacketSize hazard; inspect this dump to confirm/refute it on " +
                                        "Android specifically:\n${UsbDeviceOpener.hexDump(opened.rawDescriptors)}",
                                )
                                log.info(
                                    "Claimed data interface ${opened.usbInterface.id} and comm interface " +
                                        "${opened.commInterface.id}, asserted DTR, IN endpoint " +
                                        "0x${opened.inEndpoint.address.toString(16)}, OUT endpoint " +
                                        "0x${opened.outEndpoint.address.toString(16)}.",
                                )
                                handles = UsbConnectionHandles(
                                    opened.connection,
                                    opened.usbInterface,
                                    opened.commInterface,
                                    opened.inEndpoint,
                                    opened.outEndpoint,
                                )
                                activePreset = probeSession.runReadOnlyDiagnostics(
                                    opened.connection,
                                    opened.inEndpoint,
                                    opened.outEndpoint,
                                )
                                readOnlyPassDone = true
                            }
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        log.error("Read-only diagnostic pass crashed: ${t::class.simpleName}: ${t.message}")
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text("Find & connect pedal (read-only diagnostics)")
        }

        Button(
            enabled = !busy && readOnlyPassDone && handles != null,
            onClick = { showWriteTestDialog = true },
        ) {
            Text("Single-parameter write test (writes to your pedal)")
        }

        Text("S21 Latency measurements (issue #26) — informational only, no pass/fail target", style = MaterialTheme.typography.titleSmall)

        Text("Transport for the latency run:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportKind.entries.forEach { kind ->
                FilterChip(
                    enabled = !busy,
                    selected = latencyTransportKind == kind,
                    onClick = { latencyTransportKind = kind },
                    label = { Text(kind.label) },
                )
            }
        }

        Button(
            enabled = !busy && readOnlyPassDone && handles != null,
            onClick = { showLatencyDialog = true },
        ) {
            Text("Run latency measurements (writes to your pedal)")
        }

        Text("S22 Safety drill (issue #27) — prove a write cannot corrupt the pedal", style = MaterialTheme.typography.titleSmall)

        Button(
            enabled = !busy && readOnlyPassDone && handles != null,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        handles?.let {
                            val ok = probeSession.runSafetyBackup(it.connection, it.inEndpoint, it.outEndpoint)
                            if (ok) {
                                // L7 (Opus review): the backup only really exists once it has left
                                // in-memory state. Since issue #69, every ProbeLog entry
                                // runSafetyBackup produced above is already durably flushed to
                                // logFile in real time -- no separate write needed (and no
                                // second, divergent copy of the same log) to unlock the two
                                // write drills below.
                                log.info("S22 backup persisted to disk: $logFile")
                                s22BackupPersisted = true
                            } else {
                                s22BackupPersisted = false
                            }
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        log.error("S22 backup crashed: ${t::class.simpleName}: ${t.message}")
                        s22BackupPersisted = false
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text("S22: Full backup (read-only, persists to disk)")
        }

        Button(
            enabled = !busy && s22BackupPersisted && handles != null,
            onClick = { showPresetChangeDrillDialog = true },
        ) {
            Text("S22: Preset-change byte-diff drill (writes to your pedal)")
        }

        Button(
            enabled = !busy && s22BackupPersisted && handles != null,
            onClick = { showRevertDrillDialog = true },
        ) {
            Text("S22: Revert drill (writes to your pedal)")
        }

        OutlinedButton(
            onClick = {
                // Issue #69: share the already-current real-time log file directly instead of
                // writing a fresh render()-based copy -- avoids two divergent copies of the same
                // log, and this file has been kept current (flushed) since the screen opened.
                log.info("Log shared: $logFile")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.probelogprovider", logFile)
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Share Tonex probe log",
                    ),
                )
            },
        ) {
            Text("Save & share log")
        }

        OutlinedButton(
            enabled = !busy && handles != null,
            onClick = {
                handles?.let {
                    runCatching { it.connection.releaseInterface(it.commInterface) }
                    runCatching { it.connection.releaseInterface(it.usbInterface) }
                    runCatching { it.connection.close() }
                    log.info("USB connection released.")
                }
                handles = null
                readOnlyPassDone = false
                // A fresh connection means a fresh pedal state as far as this session's records
                // are concerned -- the persisted backup was captured against the connection just
                // released, not whatever gets connected next. Require a new S22 backup before
                // either write drill unlocks again.
                s22BackupPersisted = false
            },
        ) {
            Text("Release USB connection")
        }

        LazyColumn(
            // Deliberately no ColumnScope.weight() here - `import ...layout.weight` collides with
            // an internal `RowColumnParentData?.weight` property in this Compose BOM version and
            // fails the whole module's compile ("it is internal in file"). fillMaxWidth() alone is
            // enough: the parent Column already has a bounded height (fillMaxSize()), so this
            // LazyColumn gets a real max-height constraint from measurement without needing weight.
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(entries) { entry ->
                Text(
                    text = "[${entry.level}] ${entry.message}",
                    color = when (entry.level) {
                        ProbeLogLevel.ERROR -> Color.Red
                        ProbeLogLevel.WARN -> Color(0xFFB8860B)
                        ProbeLogLevel.FINDING -> MaterialTheme.colorScheme.primary
                        ProbeLogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showWriteTestDialog) {
        AlertDialog(
            onDismissRequest = { showWriteTestDialog = false },
            title = { Text("This will write to your pedal") },
            text = {
                Text(
                    "This sends a single-parameter write (${probeSession.writeTestParameterEnumName}) to " +
                        "the currently active preset, then attempts to restore the original value. " +
                        "Per issue #25's safety protocol, back up this preset's settings first if you " +
                        "have any way to (e.g. the IK ToneX Editor's export/backup). This harness never " +
                        "performs a whole-state write, but it does auto-save one parameter change, twice, " +
                        "on this pedal's currently active preset.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showWriteTestDialog = false
                    busy = true
                    scope.launch {
                        try {
                            handles?.let {
                                probeSession.runWriteTest(it.connection, it.inEndpoint, it.outEndpoint, activePreset)
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            log.error("Single-parameter write test crashed: ${t::class.simpleName}: ${t.message}")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("I understand, run the write test") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWriteTestDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showLatencyDialog) {
        AlertDialog(
            onDismissRequest = { showLatencyDialog = false },
            title = { Text("This will write to your pedal") },
            text = {
                Text(
                    "This measures issue #26's latency numbers using transport: ${latencyTransportKind.label}. " +
                        "It switches the active preset and back, writes ${probeSession.writeTestParameterEnumName} " +
                        "to a test value and restores it, then fires ${probeSession.sliderDragSteps} rapid-fire " +
                        "parameter writes with no pacing (a simulated slider drag) and restores the original value " +
                        "afterward. Purely informational — there is no pass/fail target, per the product owner's " +
                        "issue #26 comment. As with the single-parameter write test, back up this preset's " +
                        "settings first if you have any way to.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showLatencyDialog = false
                    busy = true
                    scope.launch {
                        try {
                            handles?.let {
                                probeSession.runLatencyMeasurements(it.connection, it.inEndpoint, it.outEndpoint, latencyTransportKind)
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            log.error("Latency measurements crashed: ${t::class.simpleName}: ${t.message}")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("I understand, run the latency measurements") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLatencyDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showPresetChangeDrillDialog) {
        AlertDialog(
            onDismissRequest = { showPresetChangeDrillDialog = false },
            title = { Text("This will write to your pedal") },
            text = {
                Text(
                    "This is issue #27's preset-change byte-diff drill: it captures the full state " +
                        "blob, switches the active preset to a different one, captures the state blob " +
                        "again, and byte-diffs every single byte to prove only the sanctioned slot " +
                        "bytes changed. It then attempts to restore the original active preset and " +
                        "independently re-verifies the restore with a third capture. A completed S22 " +
                        "backup (${if (s22BackupPersisted) "already saved to disk" else "REQUIRED first"}) " +
                        "is your safety net if anything goes wrong.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPresetChangeDrillDialog = false
                    busy = true
                    scope.launch {
                        try {
                            handles?.let {
                                probeSession.runPresetChangeSafetyDrill(it.connection, it.inEndpoint, it.outEndpoint)
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            log.error("Preset-change byte-diff drill crashed: ${t::class.simpleName}: ${t.message}")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("I understand, run the preset-change drill") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPresetChangeDrillDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRevertDrillDialog) {
        AlertDialog(
            onDismissRequest = { showRevertDrillDialog = false },
            title = { Text("This will write to your pedal") },
            text = {
                Text(
                    "This is issue #27's revert drill: it edits ${probeSession.revertDrillParameterEnumNames.joinToString()} " +
                        "on the currently active preset to distinguishable test values, calls the " +
                        "app's own revert operation, then independently re-reads the preset (a fresh " +
                        "round trip, not the local cache) to confirm every parameter and the full " +
                        "state blob genuinely came back to their pre-edit values. A completed S22 " +
                        "backup (${if (s22BackupPersisted) "already saved to disk" else "REQUIRED first"}) " +
                        "is your safety net if anything goes wrong.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRevertDrillDialog = false
                    busy = true
                    scope.launch {
                        try {
                            handles?.let {
                                probeSession.runRevertSafetyDrill(it.connection, it.inEndpoint, it.outEndpoint)
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            log.error("Revert drill crashed: ${t::class.simpleName}: ${t.message}")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("I understand, run the revert drill") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRevertDrillDialog = false }) { Text("Cancel") }
            },
        )
    }
}
