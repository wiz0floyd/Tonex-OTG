package dev.tonexotg.app.probe

import android.content.Context
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TonexTheme {
                Surface {
                    ProbeScreen(scope)
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

/**
 * Writes [text] to a fresh timestamped file under `probe-logs/` in the app's external files dir
 * and returns it. That subdirectory name must match `probe_log_file_paths.xml`'s
 * `external-files-path` entry, which is what makes [FileProvider.getUriForFile] willing to hand
 * out a `content://` Uri for a file in it.
 *
 * Exists because issue #25's full read/write test logs (raw descriptor hex dumps plus every
 * chunk of a multi-KB preset read) overflow `ClipboardManager`'s Binder transaction size limit —
 * the "Copy log to clipboard" button this replaced would throw or silently truncate on those
 * runs.
 */
private fun writeLogFile(context: Context, text: String): File {
    val dir = File(context.getExternalFilesDir(null), "probe-logs").apply { mkdirs() }
    val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
    return File(dir, "tonex-probe-$timestamp.log").apply { writeText(text) }
}

private class UsbConnectionHandles(
    val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    val commInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
)

@Composable
private fun ProbeScreen(scope: CoroutineScope) {
    val context = LocalContext.current
    val log = remember { ProbeLog() }
    val entries by log.entries.collectAsState()
    val probeSession = remember { ProbeSession(scope, log) }

    var handles by remember { mutableStateOf<UsbConnectionHandles?>(null) }
    var readOnlyPassDone by remember { mutableStateOf(false) }
    var activePreset by remember { mutableStateOf<PresetIndex?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showWriteTestDialog by remember { mutableStateOf(false) }
    var s22BackupDone by remember { mutableStateOf(false) }
    var showS22PresetDiffDialog by remember { mutableStateOf(false) }
    var showS22RevertDialog by remember { mutableStateOf(false) }
    var showLatencyDialog by remember { mutableStateOf(false) }
    var latencyTransportKind by remember { mutableStateOf(TransportKind.BULK_TRANSFER) }

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

        Text("S22 first-write safety drill (issue #27)", style = MaterialTheme.typography.titleSmall)

        Button(
            enabled = !busy && readOnlyPassDone && handles != null,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        handles?.let {
                            s22BackupDone = probeSession.runSafetyBackup(it.connection, it.inEndpoint, it.outEndpoint)
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        log.error("S22 backup crashed: ${t::class.simpleName}: ${t.message}")
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text("S22: Full backup (read-only, run this first)")
        }

        Button(
            enabled = !busy && s22BackupDone && handles != null,
            onClick = { showS22PresetDiffDialog = true },
        ) {
            Text("S22: Preset-change byte-diff drill (writes to your pedal)")
        }

        Button(
            enabled = !busy && s22BackupDone && handles != null,
            onClick = { showS22RevertDialog = true },
        ) {
            Text("S22: Revert drill (writes to your pedal)")
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

        OutlinedButton(
            onClick = {
                val file = writeLogFile(context, log.render())
                log.info("Log saved to ${file.absolutePath}")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.probelogprovider", file)
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

    if (showS22PresetDiffDialog) {
        AlertDialog(
            onDismissRequest = { showS22PresetDiffDialog = false },
            title = { Text("This will change your pedal's active preset") },
            text = {
                Text(
                    "S22 (issue #27): selects a different preset via the real controller, byte-diffs the " +
                        "full state blob before/after, then attempts to restore your original active preset. " +
                        "Requires a completed S22 backup first (already checked). This never performs a " +
                        "hardcoded whole-state replay -- only the real selectPreset() path.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showS22PresetDiffDialog = false
                    busy = true
                    scope.launch {
                        try {
                            handles?.let {
                                probeSession.runPresetChangeSafetyDrill(it.connection, it.inEndpoint, it.outEndpoint)
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            log.error("S22 preset-change drill crashed: ${t::class.simpleName}: ${t.message}")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("I understand, run the drill") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showS22PresetDiffDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showS22RevertDialog) {
        AlertDialog(
            onDismissRequest = { showS22RevertDialog = false },
            title = { Text("This will edit several parameters, then revert them") },
            text = {
                Text(
                    "S22 (issue #27): edits ${probeSession.revertDrillParameterEnumNames.joinToString()} on the " +
                        "currently active preset via the real controller, calls revertActivePreset(), then " +
                        "independently re-reads the pedal to confirm the revert actually landed (not just that " +
                        "the write was accepted). Requires a completed S22 backup first (already checked).",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showS22RevertDialog = false
                    busy = true
                    scope.launch {
                        try {
                            handles?.let {
                                probeSession.runRevertSafetyDrill(it.connection, it.inEndpoint, it.outEndpoint)
                            }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            log.error("S22 revert drill crashed: ${t::class.simpleName}: ${t.message}")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("I understand, run the drill") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showS22RevertDialog = false }) { Text("Cancel") }
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
}
