package dev.tonexotg.app.ui.screens.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.tonexotg.app.ui.components.ParameterControl
import dev.tonexotg.app.ui.components.ParameterSwitch
import dev.tonexotg.app.ui.components.PresetRow
import dev.tonexotg.app.ui.screens.connection.ConnectionErrorPanel
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexController
import dev.tonexotg.protocol.TonexError

/**
 * S16's primary screen: all 20 presets, `localAlias ?: pedalName` per row, active preset
 * unmistakable, tap-to-load, live-updating on footswitch changes (FR6), and inline alias editing.
 * Deliberately self-contained — no navigation, no `MainActivity` wiring — per this story's scope;
 * a host screen/nav graph is a later story's job.
 *
 * Renders straight off [PresetListViewModel.uiState], which is itself a live projection of
 * [TonexController]'s own [kotlinx.coroutines.flow.StateFlow]s — there is no intermediate
 * "selected preset" state held in this composable that could drift from what the pedal actually
 * reports, which is what makes footswitch-driven external changes show up here with zero extra
 * plumbing (FR6).
 */
@Composable
fun PresetListScreen(
    viewModel: PresetListViewModel,
    onPresetOpened: (PresetIndex) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var assigningIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    DisposableEffect(viewModel) {
        onDispose { viewModel.onGlobalParametersCleared() }
    }

    PresetListContent(
        uiState = uiState,
        onPresetClick = { index ->
            // S23 (issue #74; docs/architecture/s23-ui-wiring.md §2.2): select and navigate fire
            // together, unconditionally, on the same tap -- do not await the write result before
            // navigating.
            viewModel.selectPreset(index)
            onPresetOpened(index)
        },
        onEditAliasRequested = { index -> editingIndex = index.value },
        onAssignSlotRequested = { index -> assigningIndex = index.value },
        onGlobalRangeChange = viewModel::onGlobalRangeDrag,
        onGlobalRangeChangeFinished = viewModel::onGlobalRangeChangeFinished,
        onGlobalSwitchToggle = viewModel::onGlobalSwitchToggle,
        modifier = modifier,
    )

    val editingItem = editingIndex?.let { i -> uiState.items.getOrNull(i) }
    if (editingItem != null) {
        AliasEditDialog(
            item = editingItem,
            onConfirm = { newAlias ->
                viewModel.setAlias(editingItem.index, newAlias)
                editingIndex = null
            },
            onCancel = { editingIndex = null },
        )
    }

    val assigningItem = assigningIndex?.let { i -> uiState.items.getOrNull(i) }
    if (assigningItem != null) {
        SlotAssignDialog(
            item = assigningItem,
            allItems = uiState.items,
            onSlotSelected = { slot ->
                // Fire-and-forget (same rationale as tap-to-load above): close the dialog
                // immediately rather than waiting for the write to complete. Neither this slot's
                // badge nor any displaced slot's badge -- nor activePreset if either slot was
                // active -- update here; PresetListViewModel.assignToSlot deliberately makes no
                // local guess, so the row(s) simply re-render once the controller's own
                // slotAssignments/activePreset flows report the pedal's confirming push.
                viewModel.assignToSlot(assigningItem.index, slot)
                assigningIndex = null
            },
            onCancel = { assigningIndex = null },
        )
    }
}

/**
 * The stateless half of [PresetListScreen] — everything the screen renders, taking a plain
 * [PresetListUiState] rather than a [PresetListViewModel]. Split out so tests and `@Preview`s can
 * exercise every visual state (live, disconnected, with/without an active preset, with a
 * [PresetListUiState.selectPresetError]) without wiring up a whole [TonexController].
 */
@Composable
fun PresetListContent(
    uiState: PresetListUiState,
    onPresetClick: (PresetIndex) -> Unit,
    onEditAliasRequested: (PresetIndex) -> Unit,
    onAssignSlotRequested: (PresetIndex) -> Unit = {},
    onGlobalRangeChange: (ParameterId, Float) -> Unit = { _, _ -> },
    onGlobalRangeChangeFinished: (ParameterId) -> Unit = {},
    onGlobalSwitchToggle: (ParameterId, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (!uiState.isLive) {
            Text(
                text = "Not connected — showing saved names only",
                style = MaterialTheme.typography.labelMedium,
                color = TonexTheme.extendedColors.onSurfaceTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = TonexTheme.spacing.space3, vertical = TonexTheme.spacing.space1)
                    .semantics {
                        contentDescription =
                            "Not connected to pedal. This list shows saved local names only and is not live."
                    },
            )
        }

        val errorPresentation = uiState.selectPresetErrorPresentation
        if (errorPresentation != null) {
            ConnectionErrorPanel(
                presentation = errorPresentation,
                onReconnect = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(TonexTheme.spacing.space2),
            )
        }

        val assignErrorPresentation = uiState.assignSlotErrorPresentation
        if (assignErrorPresentation != null) {
            ConnectionErrorPanel(
                presentation = assignErrorPresentation,
                onReconnect = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(TonexTheme.spacing.space2),
            )
        }

        val globalWriteErrorPresentation = uiState.globalWriteErrorPresentation
        if (globalWriteErrorPresentation != null) {
            ConnectionErrorPanel(
                presentation = globalWriteErrorPresentation,
                onReconnect = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(TonexTheme.spacing.space2)
                    .testTag("globalParameters.error"),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("presetList"),
            contentPadding = PaddingValues(TonexTheme.spacing.space2),
            verticalArrangement = Arrangement.spacedBy(TonexTheme.touchTargets.spacing),
        ) {
            uiState.globalParameters?.let { globalParameters ->
                item(key = "globalParameters") {
                    GlobalParametersSection(
                        state = globalParameters,
                        onRangeChange = onGlobalRangeChange,
                        onRangeChangeFinished = onGlobalRangeChangeFinished,
                        onSwitchToggle = onGlobalSwitchToggle,
                    )
                }
            }

            items(uiState.items, key = { it.index.value }) { item ->
                val subtitle = if (item.localAlias != null && item.pedalName != null) {
                    "from pedal: ${item.pedalName}"
                } else {
                    null
                }
                PresetRow(
                    index = "%02d".format(item.index.value + 1),
                    name = item.displayName,
                    isActive = item.isActive,
                    onClick = { onPresetClick(item.index) },
                    subtitle = subtitle,
                    assignedSlots = item.assignedSlots.map { it.name }.toSet(),
                    onEditAlias = { onEditAliasRequested(item.index) },
                    // Gated on isLive, not just null-checked: assignedSlots is forced empty
                    // while disconnected (same reason the badges themselves hide then), so
                    // showing this affordance would let the dialog open and render every slot
                    // "Empty" -- falsely claiming known pedal state we don't have.
                    onAssignSlot = if (uiState.isLive) {
                        { onAssignSlotRequested(item.index) }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("presetRow.${item.index.value}"),
                )
            }
        }
    }
}

/**
 * The always-visible home-screen section (issue #83) for the 6 relocated GLOBAL parameters —
 * BPM/Input Trim/Tuning Reference as sliders (reusing [ParameterControl], the same composable
 * the Parameter Editor's RANGE rows use), Cab Sim Bypass/Tempo Source/Bypass as one-tap toggles
 * (reusing [ParameterSwitch]). Rendered only when [PresetListUiState.globalParameters] is
 * non-null — see that property's kdoc for why `null` means "don't render this at all," never a
 * placeholder-default state.
 */
@Composable
private fun GlobalParametersSection(
    state: GlobalParametersUiState,
    onRangeChange: (ParameterId, Float) -> Unit,
    onRangeChangeFinished: (ParameterId) -> Unit,
    onSwitchToggle: (ParameterId, Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("globalParameters.section"),
    ) {
        Column(
            modifier = Modifier.padding(TonexTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space2),
        ) {
            Text(text = "Global", style = MaterialTheme.typography.headlineSmall)

            listOf(state.bpm, state.inputTrim, state.tuningReference).forEach { row ->
                ParameterControl(
                    label = row.label,
                    value = row.value,
                    valueRange = row.range,
                    valueText = row.valueText,
                    onValueChange = { onRangeChange(row.id, it) },
                    // Review finding 1 on PR #101: the drag only moves the local override; the one
                    // full state-blob rewrite this gesture costs happens here, on release.
                    onValueChangeFinished = { onRangeChangeFinished(row.id) },
                    abbreviation = row.abbreviation,
                    modifier = Modifier.testTag("globalParameters.range.${row.id.index}"),
                )
            }

            listOf(state.cabSimBypass, state.tempoSource, state.bypass).forEach { row ->
                ParameterSwitch(
                    label = row.label,
                    checked = row.checked,
                    onCheckedChange = { onSwitchToggle(row.id, it) },
                    abbreviation = row.abbreviation,
                    modifier = Modifier.testTag("globalParameters.switch.${row.id.index}"),
                )
            }
        }
    }
}

/**
 * Inline alias-editing dialog (S16/S14) — prefilled with [PresetListItem.localAlias] (never the
 * resolved [PresetListItem.displayName], so an unmodified pedal name doesn't look like a
 * user-typed alias), with an explicit action to clear back to the pedal name.
 */
@Composable
private fun AliasEditDialog(
    item: PresetListItem,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable(item.index.value) { mutableStateOf(item.localAlias ?: "") }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = "Rename preset ${item.index.value + 1}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space1)) {
                if (item.pedalName != null) {
                    Text(
                        text = "Pedal name: ${item.pedalName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TonexTheme.extendedColors.onSurfaceTertiary,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Local name") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
    )
}

/**
 * "Assign this preset to a footswitch slot" dialog (S85 part 2b) — one row per [PresetSlot], each
 * a button that immediately confirms and closes (fire-and-forget, mirroring tap-to-load; see
 * [PresetListScreen]'s own comment on why this dialog never awaits the write).
 *
 * Shows two things so the user can see at a glance what a swap would displace, per
 * [dev.tonexotg.protocol.TonexController.assignPresetToSlot]'s move/swap contract:
 *  - which slot(s), if any, [item] itself currently occupies (from [PresetListItem.assignedSlots],
 *    already live per S85 part 2a) — tapping that slot's own button is a no-op per the
 *    controller's own kdoc.
 *  - for every *other* slot, which preset currently occupies it (derived from [allItems], since
 *    [PresetListItem] only carries its own slot set, not what's holding any other slot) — that's
 *    the preset a tap on that slot would displace via the swap.
 */
@Composable
private fun SlotAssignDialog(
    item: PresetListItem,
    allItems: List<PresetListItem>,
    onSlotSelected: (PresetSlot) -> Unit,
    onCancel: () -> Unit,
) {
    val occupantBySlot: Map<PresetSlot, PresetListItem> = allItems
        .flatMap { candidate -> candidate.assignedSlots.map { slot -> slot to candidate } }
        .toMap()

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = "Assign preset ${item.index.value + 1} to a footswitch slot",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space1)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TonexTheme.extendedColors.onSurfaceTertiary,
                )
                PresetSlot.entries.forEach { slot ->
                    val currentlyHere = slot in item.assignedSlots
                    val occupant = occupantBySlot[slot]
                    val subtitle = when {
                        currentlyHere -> "Already here"
                        occupant != null -> "Will swap with ${occupant.displayName}"
                        else -> "Empty"
                    }
                    TextButton(
                        onClick = { onSlotSelected(slot) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("assignSlotDialog.slot.${slot.name}"),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Slot ${slot.name}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = TonexTheme.extendedColors.onSurfaceTertiary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
    )
}

// ---- Previews --------------------------------------------------------------------------------

private fun previewItem(
    index: Int,
    pedalName: String?,
    localAlias: String? = null,
    isActive: Boolean = false,
    assignedSlots: Set<PresetSlot> = emptySet(),
): PresetListItem = PresetListItem(
    index = PresetIndex(index),
    pedalName = pedalName,
    localAlias = localAlias,
    displayName = localAlias ?: pedalName ?: "Preset ${index + 1}",
    isActive = isActive,
    assignedSlots = assignedSlots,
)

private val previewLiveState = PresetListUiState(
    items = listOf(
        previewItem(0, "PRESET 01", isActive = false, assignedSlots = setOf(PresetSlot.C)),
        previewItem(
            6,
            "PRESET 07",
            localAlias = "Set Opener",
            isActive = true,
            assignedSlots = setOf(PresetSlot.A, PresetSlot.B),
        ),
        previewItem(11, "DJENT TIGHT"),
    ) + (0..19).filterNot { it in setOf(0, 6, 11) }.map { previewItem(it, "PRESET %02d".format(it + 1)) },
    isLive = true,
)

private val previewDisconnectedState = PresetListUiState(
    items = (0..19).map { i ->
        previewItem(
            index = i,
            pedalName = null,
            localAlias = if (i == 6) "Set Opener" else null,
        )
    },
    isLive = false,
)

@Preview(name = "Preset list — live, connected", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PresetListContentLivePreview() {
    TonexTheme {
        PresetListContent(
            uiState = previewLiveState,
            onPresetClick = {},
            onEditAliasRequested = {},
        )
    }
}

@Preview(name = "Preset list — disconnected, aliases only", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PresetListContentDisconnectedPreview() {
    TonexTheme {
        PresetListContent(
            uiState = previewDisconnectedState,
            onPresetClick = {},
            onEditAliasRequested = {},
        )
    }
}

@Preview(name = "Preset list — select-preset error surfaced", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PresetListContentErrorPreview() {
    TonexTheme {
        PresetListContent(
            uiState = previewLiveState.copy(
                selectPresetError = TonexError.ProtocolStateViolation(
                    state = ConnectionState.Connecting,
                    details = "selectPreset requires Ready",
                ),
            ),
            onPresetClick = {},
            onEditAliasRequested = {},
        )
    }
}

@Preview(name = "Preset list — assign-slot error surfaced (S85 part 2b)", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PresetListContentAssignErrorPreview() {
    TonexTheme {
        PresetListContent(
            uiState = previewLiveState.copy(
                assignSlotError = TonexError.ProtocolStateViolation(
                    state = ConnectionState.Connecting,
                    details = "assignPresetToSlot requires Ready",
                ),
            ),
            onPresetClick = {},
            onEditAliasRequested = {},
        )
    }
}

@Preview(name = "Preset list — assign-to-slot dialog open (S85 part 2b)", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun SlotAssignDialogPreview() {
    TonexTheme {
        SlotAssignDialog(
            item = previewLiveState.items[11],
            allItems = previewLiveState.items,
            onSlotSelected = {},
            onCancel = {},
        )
    }
}
