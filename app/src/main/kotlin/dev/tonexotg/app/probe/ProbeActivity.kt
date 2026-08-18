package dev.tonexotg.app.probe

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.protocol.PresetIndex
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
 * pedal, then use "Copy log to clipboard" to hand the results back for review.
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

private class UsbConnectionHandles(
    val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
)

@Composable
private fun ProbeScreen(scope: CoroutineScope) {
    val context = LocalContext.current
    val clipboard: ClipboardManager = LocalClipboardManager.current
    val log = remember { ProbeLog() }
    val entries by log.entries.collectAsState()
    val probeSession = remember { ProbeSession(scope, log) }

    var handles by remember { mutableStateOf<UsbConnectionHandles?>(null) }
    var readOnlyPassDone by remember { mutableStateOf(false) }
    var activePreset by remember { mutableStateOf<PresetIndex?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showWriteTestDialog by remember { mutableStateOf(false) }

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
                                    "Claimed interface ${opened.usbInterface.id}, asserted DTR, IN endpoint " +
                                        "0x${opened.inEndpoint.address.toString(16)}, OUT endpoint " +
                                        "0x${opened.outEndpoint.address.toString(16)}.",
                                )
                                handles = UsbConnectionHandles(
                                    opened.connection,
                                    opened.usbInterface,
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

        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(log.renderForClipboard())) },
        ) {
            Text("Copy log to clipboard")
        }

        OutlinedButton(
            enabled = !busy && handles != null,
            onClick = {
                handles?.let {
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
}
