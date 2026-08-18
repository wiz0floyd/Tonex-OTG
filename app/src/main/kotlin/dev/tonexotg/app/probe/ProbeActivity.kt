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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        scope.cancel()
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
                    val device = UsbDeviceOpener.findDevice(context)
                    if (device == null) {
                        log.error(
                            "No USB device found for VID=0x${UsbDeviceOpener.VENDOR_ID.toString(16)}/" +
                                "PID=0x${UsbDeviceOpener.PRODUCT_ID.toString(16)}. Is the pedal plugged in " +
                                "and this phone's USB-OTG adapter/cable working?",
                        )
                        busy = false
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
                    busy = false
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
            enabled = handles != null,
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
                        handles?.let {
                            probeSession.runWriteTest(it.connection, it.inEndpoint, it.outEndpoint, activePreset)
                        }
                        busy = false
                    }
                }) { Text("I understand, run the write test") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWriteTestDialog = false }) { Text("Cancel") }
            },
        )
    }
}
