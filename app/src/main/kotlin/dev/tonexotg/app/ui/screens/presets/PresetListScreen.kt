package dev.tonexotg.app.ui.screens.presets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.components.MasterVolumeDock
import dev.tonexotg.app.ui.components.ParameterControl
import dev.tonexotg.app.ui.components.ParameterSwitch
import dev.tonexotg.app.ui.components.PresetRow
import dev.tonexotg.app.ui.screens.connection.ConnectionErrorPanel
import dev.tonexotg.app.ui.screens.parameters.ParameterRow
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget
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
        onMasterVolumeChange = viewModel::onMasterVolumeDrag,
        onDismissSelectError = viewModel::dismissSelectError,
        onDismissAssignError = viewModel::dismissAssignError,
        onDismissGlobalWriteError = viewModel::dismissGlobalWriteError,
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
    onMasterVolumeChange: (Float) -> Unit = {},
    onDismissSelectError: () -> Unit = {},
    onDismissAssignError: () -> Unit = {},
    onDismissGlobalWriteError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // #107: default collapsed, survives rotation/process death. Hoisted here (rather than inside
    // GlobalParametersSection) so it isn't lost across a disconnect/reconnect cycle, which removes
    // that composable from the tree entirely whenever uiState.globalParameters is null -- see that
    // property's kdoc.
    var globalsExpanded by rememberSaveable { mutableStateOf(false) }

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
                onDismiss = onDismissSelectError,
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
                onDismiss = onDismissAssignError,
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
                onDismiss = onDismissGlobalWriteError,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(TonexTheme.spacing.space2)
                    .testTag("globalParameters.error"),
            )
        }

        // #107: sticky master volume + collapsible globals tray, both pinned above the
        // LazyColumn (outside its scrolling items) rather than as the first row(s) that scroll
        // away with the preset list.
        uiState.masterVolume?.let { row ->
            MasterVolumeDock(
                row = row,
                onValueChange = onMasterVolumeChange,
                modifier = Modifier.testTag("presetList.masterVolume"),
            )
        }

        uiState.globalParameters?.let { globalParameters ->
            GlobalParametersSection(
                state = globalParameters,
                expanded = globalsExpanded,
                onToggleExpanded = { globalsExpanded = !globalsExpanded },
                onRangeChange = onGlobalRangeChange,
                onRangeChangeFinished = onGlobalRangeChangeFinished,
                onSwitchToggle = onGlobalSwitchToggle,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("presetList"),
            contentPadding = PaddingValues(TonexTheme.spacing.space2),
            verticalArrangement = Arrangement.spacedBy(TonexTheme.touchTargets.spacing),
        ) {
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
 * The home-screen tray (issue #83, collapsed into a two-row tray by #107) for the 6 relocated
 * GLOBAL parameters — BPM/Input Trim/Tuning Reference as sliders (reusing [ParameterControl], the
 * same composable the Parameter Editor's RANGE rows use), Cab Sim Bypass/Tempo Source/Bypass as
 * one-tap toggles (reusing [ParameterSwitch]) in the expanded body, unchanged from #83. Rendered
 * only when [PresetListUiState.globalParameters] is non-null — see that property's kdoc for why
 * `null` means "don't render this at all," never a placeholder-default state.
 *
 * [expanded]/[onToggleExpanded] are hoisted to the caller (see [PresetListContent]) rather than
 * owned here, so the collapsed/expanded choice survives this composable dropping out of the tree
 * across a disconnect/reconnect.
 */
@Composable
private fun GlobalParametersSection(
    state: GlobalParametersUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
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
        Column {
            GlobalParametersHeader(state = state, expanded = expanded, onToggle = onToggleExpanded)

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    modifier = Modifier
                        // #116: this tray sits above the LazyColumn, outside its scrolling items
                        // (see the #107 comment on PresetListContent), so on a real device short
                        // enough that all 6 rows don't fit, the body has no scroll of its own and
                        // both it and the preset list below it become unreachable. Bound the height
                        // and let this section scroll independently instead.
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(TonexTheme.spacing.space3)
                        .testTag("globalParameters.body"),
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
                            // Review finding 1 on PR #101: the drag only moves the local override;
                            // the one full state-blob rewrite this gesture costs happens here, on
                            // release.
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
    }
}

/**
 * The collapsed summary row (#107) — all 6 globals, every time, as icon(+value) chips. Design
 * table from issue #107: BPM/Input Trim/Tuning Reference/Tempo Source render icon+compact-value
 * ([RangeChip]); Cab Sim Bypass/Bypass render icon-only, tint+shape signalling on/off
 * ([SwitchChip]) since colour alone isn't an accessible signal on its own.
 *
 * Tempo Source is a `ParameterRow.Switch` in [GlobalParametersUiState] but renders here with the
 * same icon+text shape as the 3 RANGE chips, not as an icon-only [SwitchChip] — the issue's own
 * icon table lists it that way, and its abbreviation already carries real protocol-derived text
 * ([state.tempoSource]'s `"GLOBAL"`/`"PRESET"`, not the mockup's illustrative "INT"/"EXT" — see
 * [dev.tonexotg.protocol.state.StateBlobOffsets.END_TEMPO_SOURCE]'s documented polarity).
 *
 * The whole row is ONE merged tap target (`Modifier.semantics(mergeDescendants = true)` on this
 * composable's own [Modifier.clickable] + [Modifier.semantics] pairing) that expands/collapses
 * the tray — the 6 chips underneath carry [Modifier.semantics] `contentDescription`s of
 * their own (per-chip, naming the parameter and its value, per the AC) but no [Modifier.clickable]
 * of their own, so TalkBack announces one merged element with six concatenated descriptions
 * instead of six competing touch targets.
 *
 * ## Glyphs: hand-drawn [Canvas] shapes, not `material-icons-extended`
 * This story tried `androidx.compose.material:material-icons-extended` first (per its own
 * dependency note) and measured its actual release-build cost: `:app:assembleRelease` grew
 * ~4.2MB (8,918,741 → 13,162,679 bytes) with the dependency added, because this project's release
 * build type has `isMinifyEnabled = false` (see `app/build.gradle.kts`) — R8 never got a chance
 * to strip the ~2000 unused vectors the extended artifact ships. That's the "material" delta the
 * issue's own contingency names, so per that contingency this falls back to 6 small
 * [androidx.compose.foundation.Canvas]-drawn glyphs ([GlyphIcon]) instead — original simple
 * shapes approximating each named icon (a gauge needle for Speed, three bars for Tune, a
 * note-head+stem for MusicNote, a speaker box for Speaker, a clock face for Schedule, a power
 * ring for PowerSettingsNew), not a copy of Material's own path data. They are **not** pixel-
 * faithful reproductions of the named Material glyphs — flag that explicitly for the product
 * owner's own-eyes/own-phone sign-off this story's issue already requires (see its "Sign-off"
 * section), same as any other layout-only judgment call this pass made.
 */
@Composable
private fun GlobalParametersHeader(
    state: GlobalParametersUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .minTouchTarget(TonexTheme.touchTargets.secondary)
            .padding(horizontal = TonexTheme.spacing.space2)
            .semantics(mergeDescendants = true) {}
            .testTag("globalParameters.header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RangeChip(glyph = GlobalGlyph.Speed, valueText = state.bpm.valueText, contentDescription = "${state.bpm.label}, ${state.bpm.valueText}")
            RangeChip(glyph = GlobalGlyph.Tune, valueText = state.inputTrim.valueText, contentDescription = "${state.inputTrim.label}, ${state.inputTrim.valueText}")
            RangeChip(glyph = GlobalGlyph.MusicNote, valueText = state.tuningReference.valueText, contentDescription = "${state.tuningReference.label}, ${state.tuningReference.valueText}")
            SwitchChip(
                glyph = GlobalGlyph.Speaker,
                checked = state.cabSimBypass.checked,
                contentDescription = "${state.cabSimBypass.label}, ${if (state.cabSimBypass.checked) "on" else "off"}",
            )
            RangeChip(glyph = GlobalGlyph.Schedule, valueText = state.tempoSource.abbreviation, contentDescription = "${state.tempoSource.label}, ${state.tempoSource.abbreviation}")
            SwitchChip(
                glyph = GlobalGlyph.Power,
                checked = state.bypass.checked,
                contentDescription = "${state.bypass.label}, ${if (state.bypass.checked) "on" else "off"}",
            )
        }

        // Decorative expand/collapse affordance -- same "no Icons.* usage" text-affordance
        // convention CategoryAccordion already uses (its own "−"/"+"), not a 7th authored glyph.
        // Decorative because the header row's own merged semantics above already cover
        // expand/collapse for accessibility.
        Text(
            text = if (expanded) "⌃" else "⌄",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val TonexChipIconSize = 16.dp

/** One icon+compact-value chip in [GlobalParametersHeader] — BPM/Input Trim/Tuning Reference/Tempo Source. */
@Composable
private fun RangeChip(glyph: GlobalGlyph, valueText: String, contentDescription: String) {
    Row(
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space0_5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(
            glyph = glyph,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            slashed = false,
            modifier = Modifier.size(TonexChipIconSize),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * One icon-only chip in [GlobalParametersHeader] — Cab Sim Bypass/Bypass. Colour alone (tint)
 * isn't an accessible on/off signal on its own (issue #107's constraint), so [checked] also draws
 * a slash through the glyph when off — a shape change, not just a colour change, without needing
 * a second hand-authored glyph variant per icon.
 */
@Composable
private fun SwitchChip(glyph: GlobalGlyph, checked: Boolean, contentDescription: String) {
    GlyphIcon(
        glyph = glyph,
        tint = if (checked) MaterialTheme.colorScheme.primary else TonexTheme.extendedColors.onSurfaceDisabled,
        slashed = !checked,
        modifier = Modifier
            .size(TonexChipIconSize)
            .semantics { this.contentDescription = contentDescription },
    )
}

/** The 6 hand-drawn glyphs [GlyphIcon] knows how to render — see [GlobalParametersHeader]'s kdoc for why these are original shapes, not Material's own path data. */
private enum class GlobalGlyph { Speed, Tune, MusicNote, Speaker, Schedule, Power }

/**
 * Draws one [GlobalGlyph] as simple original shapes on a [Canvas] — small enough, and few enough
 * (6, one per [GlobalGlyph]), that hand-authoring is cheaper and lighter than a dependency; see
 * [GlobalParametersHeader]'s kdoc for the measurement that led here. [slashed] draws one extra
 * diagonal line across the glyph, the shape-change half of [SwitchChip]'s on/off signal.
 */
@Composable
private fun GlyphIcon(glyph: GlobalGlyph, tint: Color, slashed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.minDimension * 0.12f, cap = StrokeCap.Round)
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f

        when (glyph) {
            GlobalGlyph.Speed -> {
                // Gauge: a 3/4 arc plus a needle from center towards upper-right.
                drawArc(
                    color = tint,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = stroke,
                )
                drawLine(tint, center, Offset(center.x + r * 0.6f, center.y - r * 0.6f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }

            GlobalGlyph.Tune -> {
                // Three vertical bars of different heights -- a mixer/equalizer look.
                val barWidth = stroke.width
                val heights = listOf(0.5f, 1f, 0.7f)
                val xs = listOf(center.x - r * 0.6f, center.x, center.x + r * 0.6f)
                heights.forEachIndexed { i, h ->
                    val half = r * h
                    drawLine(tint, Offset(xs[i], center.y - half), Offset(xs[i], center.y + half), strokeWidth = barWidth, cap = StrokeCap.Round)
                }
            }

            GlobalGlyph.MusicNote -> {
                // A note-head (filled circle) plus a stem.
                val headRadius = r * 0.4f
                val headCenter = Offset(center.x - headRadius * 0.4f, center.y + r * 0.4f)
                drawCircle(tint, radius = headRadius, center = headCenter)
                drawLine(
                    tint,
                    Offset(headCenter.x + headRadius * 0.9f, headCenter.y),
                    Offset(headCenter.x + headRadius * 0.9f, center.y - r * 0.9f),
                    strokeWidth = stroke.width * 0.6f,
                    cap = StrokeCap.Round,
                )
            }

            GlobalGlyph.Speaker -> {
                // A rounded box with a smaller circle "cone" inside.
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(center.x - r * 0.6f, center.y - r * 0.85f),
                    size = androidx.compose.ui.geometry.Size(r * 1.2f, r * 1.7f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.2f, r * 0.2f),
                    style = stroke,
                )
                drawCircle(tint, radius = r * 0.35f, center = center, style = stroke)
            }

            GlobalGlyph.Schedule -> {
                // A clock face: circle outline plus two hands.
                drawCircle(tint, radius = r * 0.85f, center = center, style = stroke)
                drawLine(tint, center, Offset(center.x, center.y - r * 0.5f), strokeWidth = stroke.width * 0.6f, cap = StrokeCap.Round)
                drawLine(tint, center, Offset(center.x + r * 0.4f, center.y), strokeWidth = stroke.width * 0.6f, cap = StrokeCap.Round)
            }

            GlobalGlyph.Power -> {
                // The standard power-symbol shape: an open ring plus a vertical tick through the top.
                drawArc(
                    color = tint,
                    startAngle = -240f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.8f, center.y - r * 0.8f),
                    size = androidx.compose.ui.geometry.Size(r * 1.6f, r * 1.6f),
                    style = stroke,
                )
                drawLine(tint, Offset(center.x, center.y - r * 0.9f), Offset(center.x, center.y - r * 0.1f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }

        if (slashed) {
            drawLine(
                color = tint,
                start = Offset(center.x - r * 0.9f, center.y - r * 0.9f),
                end = Offset(center.x + r * 0.9f, center.y + r * 0.9f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
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

// #107: sample data for the sticky master-volume row + globals tray previews below. Numbers are
// illustrative only, not real registry bounds -- these previews exercise layout, not protocol
// correctness (that's ParameterCatalogTest's job).
private val previewMasterVolume = ParameterRow.Range(
    id = ParameterId(116),
    label = "Master Volume",
    abbreviation = "MASTER_VOLUME · index 116",
    value = -8f,
    range = -40f..3f,
    valueText = "-8 dB",
    decimalPlaces = 1,
    unit = "dB",
)

private val previewGlobalParameters = GlobalParametersUiState(
    bpm = ParameterRow.Range(ParameterId(110), "BPM", "BPM · index 110", 120f, 40f..300f, "120", 0, ""),
    inputTrim = ParameterRow.Range(ParameterId(111), "Input Trim", "INPUT_TRIM · index 111", -2f, -20f..20f, "-2 dB", 1, "dB"),
    tuningReference = ParameterRow.Range(ParameterId(114), "Tuning Reference", "TUNING_REFERENCE · index 114", 440f, 400f..480f, "440 Hz", 0, "Hz"),
    cabSimBypass = ParameterRow.Switch(ParameterId(112), "Cab Sim Bypass", "CABSIM_BYPASS · index 112", checked = true),
    tempoSource = ParameterRow.Switch(ParameterId(113), "Tempo Source", "GLOBAL", checked = false),
    bypass = ParameterRow.Switch(ParameterId(115), "Bypass", "BYPASS · index 115", checked = false),
)

private val previewGlobalsTrayState = previewLiveState.copy(
    masterVolume = previewMasterVolume,
    globalParameters = previewGlobalParameters,
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

/**
 * #107 acceptance criterion: the collapsed globals tray's header row must fit 360dp — the
 * narrowest common Android width class -- with no horizontal scroll and no truncated value,
 * "verified with a @Preview pinned at 360dp, not by eyeballing one emulator." [widthDp] pins
 * exactly that; the tray renders collapsed by default (no interaction needed to see the row this
 * checks).
 */
@Preview(
    name = "Preset list — sticky master volume + globals tray, collapsed, 360dp",
    showBackground = true,
    backgroundColor = 0xFF121212,
    widthDp = 360,
)
@Composable
private fun PresetListGlobalsTrayCollapsed360Preview() {
    TonexTheme {
        PresetListContent(
            uiState = previewGlobalsTrayState,
            onPresetClick = {},
            onEditAliasRequested = {},
        )
    }
}

/** Same state as above, but expanded -- the tray's animated body, at the same 360dp width. */
@Preview(
    name = "Preset list — sticky master volume + globals tray, expanded, 360dp",
    showBackground = true,
    backgroundColor = 0xFF121212,
    widthDp = 360,
)
@Composable
private fun PresetListGlobalsTrayExpanded360Preview() {
    TonexTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                GlobalParametersHeaderPreviewHost(expanded = true)
            }
        }
    }
}

@Composable
private fun GlobalParametersHeaderPreviewHost(expanded: Boolean) {
    // GlobalParametersSection's own expanded state is hoisted to PresetListContent (issue #107's
    // rationale in that composable's kdoc), so this preview exercises GlobalParametersSection
    // directly with a fixed [expanded] rather than needing to drive PresetListContent's
    // rememberSaveable state via interaction.
    GlobalParametersSection(
        state = previewGlobalParameters,
        expanded = expanded,
        onToggleExpanded = {},
        onRangeChange = { _, _ -> },
        onRangeChangeFinished = {},
        onSwitchToggle = { _, _ -> },
    )
}
