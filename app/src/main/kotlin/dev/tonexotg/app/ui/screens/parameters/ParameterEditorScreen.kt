package dev.tonexotg.app.ui.screens.parameters

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.tonexotg.app.ui.components.DestructiveActionConfirmationDialog
import dev.tonexotg.app.ui.components.MasterVolumeDock
import dev.tonexotg.app.ui.components.ParameterControl
import dev.tonexotg.app.ui.components.ParameterNumericEntrySheet
import dev.tonexotg.app.ui.components.ParameterSelectChipRow
import dev.tonexotg.app.ui.components.ParameterStepper
import dev.tonexotg.app.ui.components.ParameterSwitch
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget
import dev.tonexotg.protocol.ParameterId

/**
 * The Parameter Editor screen (S17, issue #22) — quick tier, then a persistent app bar, a
 * "Master Volume" bottom dock, and the full-tier accordion, all as D3 §1.3 requires: "one
 * continuous scrolling screen," not two separate routes or tabs.
 *
 * Takes an already-constructed [ParameterEditorViewModel] rather than constructing one itself or
 * reading a `NavController` — per this story's guardrail, this file does not touch
 * `MainActivity.kt` or build any navigation graph; a future navigation story wires a real
 * `TonexController`/scope into a [ParameterEditorViewModel] and hosts this composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParameterEditorScreen(
    viewModel: ParameterEditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val externalMessage = uiState.externalPresetChangeMessage
    LaunchedEffect(externalMessage) {
        if (externalMessage != null) {
            snackbarHostState.showSnackbar(externalMessage, duration = SnackbarDuration.Short)
            viewModel.onExternalPresetChangeMessageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.presetName.ifBlank { "Parameter Editor" },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    // TextButton, not an arrow-icon IconButton: this project has no
                    // material-icons-extended dependency and no Icons.* usage anywhere in `:app`
                    // (S23, issue #74) -- a text affordance sidesteps that question entirely
                    // rather than adding a new dependency for one glyph. #107 briefly added that
                    // dependency for the home-screen globals tray's 6 icon chips, then reverted it
                    // (the extended artifact cost ~4MB unshrunk in a release build -- this
                    // project's release build type has isMinifyEnabled = false, so R8 never got a
                    // chance to strip the unused vectors) in favour of hand-authored Canvas glyphs
                    // (see PresetListScreen.kt's GlyphIcon) — so this comment's original claim is
                    // true again, unchanged by that story.
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("parameterEditor.backButton"),
                    ) {
                        Text("Back")
                    }
                },
            )
        },
        bottomBar = {
            uiState.masterVolume?.let { row ->
                MasterVolumeDock(
                    row = row,
                    onValueChange = { viewModel.onRangeDrag(row.id, it) },
                    onValueChangeFinished = { viewModel.onRangeDragEnd(row.id) },
                    onValueTextClick = { viewModel.onNumericEntryOpen(row.id) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .testTag("parameterEditor.scrollList"),
            contentPadding = PaddingValues(TonexTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space4),
        ) {
            items(uiState.quickTier, key = { "quick.${it.row.id.index}" }) { card ->
                QuickTierCardView(
                    card = card,
                    onValueChange = { viewModel.onRangeDrag(card.row.id, it) },
                    onValueChangeFinished = { viewModel.onRangeDragEnd(card.row.id) },
                    onValueTextClick = { viewModel.onNumericEntryOpen(card.row.id) },
                )
            }

            item(key = "allParametersHeader") {
                Text(
                    text = "All Parameters",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            items(uiState.categories, key = { "category.${it.title}" }) { category ->
                CategoryAccordion(
                    category = category,
                    expanded = category.title in uiState.expandedCategories,
                    onToggle = { viewModel.onCategoryToggle(category.title) },
                    onRangeChange = viewModel::onRangeDrag,
                    onRangeChangeFinished = viewModel::onRangeDragEnd,
                    onValueTextClick = viewModel::onNumericEntryOpen,
                    onSwitchToggle = viewModel::onSwitchToggle,
                    onStepperChange = viewModel::onStepperChange,
                    onModelSelect = viewModel::onModelSelect,
                )
            }

            item(key = "revertSection") {
                RevertSection(onRevertClick = viewModel::onRevertRequested)
            }
        }
    }

    uiState.numericEntryTarget?.let { row ->
        ParameterNumericEntrySheet(
            parameterName = row.label,
            initialValue = row.value,
            valueRange = row.range,
            unit = row.unit.ifBlank { null },
            decimalPlaces = row.decimalPlaces,
            onDismiss = viewModel::onNumericEntryDismiss,
            onSet = viewModel::onNumericEntrySet,
        )
    }

    if (uiState.firstWriteWarningVisible) {
        FirstDestructiveWriteNotice(presetName = uiState.presetName, onDismiss = viewModel::onFirstWriteWarningDismiss)
    }

    if (uiState.revertConfirmVisible) {
        DestructiveActionConfirmationDialog(
            title = "Revert to snapshot?",
            message = "This immediately rewrites all 109 parameters of ${uiState.presetName} back to " +
                "their values from when it became active. The pedal auto-saves — this can't be undone either.",
            confirmLabel = "Revert Anyway",
            onConfirm = viewModel::onRevertConfirmed,
            onCancel = viewModel::onRevertCancelled,
        )
    }

    uiState.revertError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::onRevertErrorDismissed,
            confirmButton = {
                TextButton(onClick = viewModel::onRevertErrorDismissed) { Text("OK") }
            },
            title = { Text("Revert failed") },
            text = { Text(message) },
        )
    }

    uiState.writeError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::onWriteErrorDismissed,
            confirmButton = {
                TextButton(onClick = viewModel::onWriteErrorDismissed) { Text("OK") }
            },
            title = { Text("Couldn't update parameter") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun QuickTierCardView(
    card: QuickTierCardUiState,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onValueTextClick: () -> Unit,
) {
    Column(modifier = Modifier.testTag("quickTier.card.${card.row.id.index}")) {
        // Same disposal hazard as ParameterRowView's Range branch: a quick-tier card can scroll out
        // of the LazyColumn, or have its resolved id swapped by a model change, mid-drag.
        DisposableEffect(card.row.id) {
            onDispose { onValueChangeFinished() }
        }
        ParameterControl(
            label = card.row.label,
            value = card.row.value,
            valueRange = card.row.range,
            valueText = card.row.valueText,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            abbreviation = card.row.abbreviation,
            onValueTextClick = onValueTextClick,
        )
        card.resolvedCaption?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = TonexTheme.extendedColors.onSurfaceTertiary,
            )
        }
    }
}

@Composable
private fun CategoryAccordion(
    category: CategoryUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRangeChange: (ParameterId, Float) -> Unit,
    onRangeChangeFinished: (ParameterId) -> Unit,
    onValueTextClick: (ParameterId) -> Unit,
    onSwitchToggle: (ParameterId, Boolean) -> Unit,
    onStepperChange: (ParameterId, Int) -> Unit,
    onModelSelect: (ParameterId, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minTouchTarget(TonexTheme.touchTargets.secondary)
                .clickable(onClick = onToggle)
                .testTag("category.header.${category.title}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = category.title, style = MaterialTheme.typography.headlineSmall)
            Text(text = if (expanded) "−" else "+", style = MaterialTheme.typography.headlineSmall)
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = TonexTheme.spacing.space2, top = TonexTheme.spacing.space2)
                    .testTag("category.body.${category.title}"),
                verticalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space3),
            ) {
                when (category) {
                    is CategoryUiState.Flat ->
                        category.rows.forEach { row ->
                            ParameterRowView(row, onRangeChange, onRangeChangeFinished, onValueTextClick, onSwitchToggle, onStepperChange)
                        }

                    is CategoryUiState.Banked -> {
                        category.alwaysOnRows.forEach { row ->
                            ParameterRowView(row, onRangeChange, onRangeChangeFinished, onValueTextClick, onSwitchToggle, onStepperChange)
                        }
                        ParameterSelectChipRow(
                            options = category.selector.options,
                            selected = category.selector.selectedIndex,
                            onSelect = { onModelSelect(category.selector.id, it) },
                            label = category.selector.optionLabel,
                            modifier = Modifier.testTag("modelSelector.${category.title}"),
                        )
                        // D3 §2.1: only the selected model's rows are ever composed here — every
                        // other model's rows are absent, not grayed or collapsed.
                        category.modelRows.forEach { row ->
                            ParameterRowView(row, onRangeChange, onRangeChangeFinished, onValueTextClick, onSwitchToggle, onStepperChange)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterRowView(
    row: ParameterRow,
    onRangeChange: (ParameterId, Float) -> Unit,
    onRangeChangeFinished: (ParameterId) -> Unit,
    onValueTextClick: (ParameterId) -> Unit,
    onSwitchToggle: (ParameterId, Boolean) -> Unit,
    onStepperChange: (ParameterId, Int) -> Unit,
) {
    when (row) {
        is ParameterRow.Range -> {
            // `Slider.onValueChangeFinished` is the ONLY thing that ends a drag's
            // inbound-suppression window, and it never fires if this row is disposed mid-gesture --
            // an accordion collapsing, a model-selector chip swapping out the rows below it, a
            // connection-state rebuild. A row left flagged as "being dragged" stops mirroring the
            // pedal for the rest of the session, so disposal has to end the gesture too (issue
            // #104). onRangeDragEnd is idempotent, so firing it for a row that was never dragged
            // costs nothing.
            DisposableEffect(row.id) {
                onDispose { onRangeChangeFinished(row.id) }
            }
            ParameterControl(
                label = row.label,
                value = row.value,
                valueRange = row.range,
                valueText = row.valueText,
                onValueChange = { onRangeChange(row.id, it) },
                onValueChangeFinished = { onRangeChangeFinished(row.id) },
                abbreviation = row.abbreviation,
                onValueTextClick = { onValueTextClick(row.id) },
                modifier = Modifier.testTag("paramRow.range.${row.id.index}"),
            )
        }

        is ParameterRow.Switch -> ParameterSwitch(
            label = row.label,
            checked = row.checked,
            onCheckedChange = { onSwitchToggle(row.id, it) },
            abbreviation = row.abbreviation,
            modifier = Modifier.testTag("paramRow.switch.${row.id.index}"),
        )

        is ParameterRow.Stepper -> ParameterStepper(
            label = row.label,
            value = row.value,
            range = row.range,
            onValueChange = { onStepperChange(row.id, it) },
            abbreviation = row.abbreviation,
            modifier = Modifier.testTag("paramRow.stepper.${row.id.index}"),
        )
    }
}

@Composable
private fun FirstDestructiveWriteNotice(presetName: String, onDismiss: (dontShowAgain: Boolean) -> Unit) {
    // D3 §5.2: a single "Got it" button, no Cancel — dismissing the dialog *is* proceeding,
    // because the write it describes has already happened. Structurally different from
    // DestructiveActionConfirmationDialog (two buttons, inverted hierarchy, gates an action that
    // has not happened yet), so this is its own small dialog rather than a forced reuse.
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDismiss(dontShowAgain) },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(dontShowAgain) },
                modifier = Modifier
                    .minTouchTarget(TonexTheme.touchTargets.secondary)
                    .testTag("firstWriteNotice.gotIt"),
            ) {
                Text("Got it")
            }
        },
        title = { Text("Heads up") },
        text = {
            Column {
                Text(
                    "Tonex-OTG writes every change straight to the pedal as you make it. There's no undo " +
                        "on the pedal itself. A snapshot of $presetName was taken the moment it became " +
                        "active. You can revert to it any time from Settings — until then, experiment freely.",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minTouchTarget(TonexTheme.touchTargets.secondary)
                        .clickable { dontShowAgain = !dontShowAgain }
                        .testTag("firstWriteNotice.dontShowAgain"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                    Text("Don't show again")
                }
            }
        },
    )
}

@Composable
private fun RevertSection(onRevertClick: () -> Unit) {
    // D3 §5.1 places this entry point in Settings (S18, not yet built) — see
    // ParameterEditorViewModel's KDoc for why it lives here, at the very bottom of the screen,
    // for now.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TonexTheme.spacing.space5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedButton(
            onClick = onRevertClick,
            modifier = Modifier
                .minTouchTarget(TonexTheme.touchTargets.secondary)
                .testTag("parameterEditor.revertButton"),
        ) {
            Text("Revert…")
        }
    }
}
