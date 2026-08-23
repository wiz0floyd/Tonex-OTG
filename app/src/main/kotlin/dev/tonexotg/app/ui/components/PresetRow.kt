package dev.tonexotg.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.app.ui.theme.minTouchTarget

/**
 * A single row in the preset list (S16). Built for the mockup's active/inactive states: an
 * accent-highlighted active row with a checkmark, or a plain row for everything else — see D2
 * screen 1.
 *
 * [index] and [name] are plain strings, not protocol types — this component never imports from
 * `dev.tonexotg.protocol.*`; the view model that builds S16's actual list is what translates a
 * protocol preset slot into these parameters.
 *
 * The whole row meets [dev.tonexotg.app.ui.theme.TonexTouchTargets.min] (64dp) per D1 §4.2 — a
 * preset switch is the single most performance-critical action in the app.
 *
 * @param onEditAlias when non-null, renders a separate edit affordance (its own
 *   [dev.tonexotg.app.ui.theme.TonexTouchTargets.secondary] touch target, so it can never
 *   register as the row's own [onClick]) that invokes this callback — S16's inline alias-editing
 *   entry point. `null` (the default) renders no edit affordance at all, which is what every
 *   pre-S16 caller/preview of this component already expects.
 * @param assignedSlots plain strings (e.g. `"A"`, `"B"`) for the footswitch slots currently
 *   pointed at this preset (S85 part 2a) — this component never imports
 *   `dev.tonexotg.protocol.*`, so the caller converts `PresetSlot.name` before passing these in.
 *   Empty (the default) renders no badges at all, matching every pre-S85 caller/preview.
 * @param onAssignSlot when non-null, renders a third icon affordance (S85 part 2b) — its own
 *   [dev.tonexotg.app.ui.theme.TonexTouchTargets.secondary] touch target, alongside (not in place
 *   of) [onEditAlias] and the [assignedSlots] badges — that invokes this callback to open the
 *   "assign this preset to a footswitch slot" dialog. `null` (the default) renders no affordance
 *   at all, matching every pre-S85-part-2b caller/preview.
 */
@Composable
fun PresetRow(
    index: String,
    name: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    assignedSlots: Set<String> = emptySet(),
    onEditAlias: (() -> Unit)? = null,
    onAssignSlot: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent
    val background = if (isActive) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .minTouchTarget()
            .clip(shape)
            .background(background, shape)
            .border(2.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = TonexTheme.spacing.space2, vertical = TonexTheme.spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space2),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) MaterialTheme.colorScheme.onPrimary else TonexTheme.extendedColors.onSurfaceTertiary,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space1),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (assignedSlots.isNotEmpty()) {
                    val sortedSlots = assignedSlots.sorted()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(TonexTheme.spacing.space1 / 2),
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Assigned to footswitch ${sortedSlots.joinToString(", ")}"
                        },
                    ) {
                        sortedSlots.forEach { slot ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = slot,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TonexTheme.extendedColors.onSurfaceTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isActive) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (onEditAlias != null) {
            Box(
                modifier = Modifier
                    .minTouchTarget(TonexTheme.touchTargets.secondary)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEditAlias)
                    .semantics { contentDescription = "Edit name for preset $index" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✎",
                    style = MaterialTheme.typography.titleMedium,
                    color = TonexTheme.extendedColors.onSurfaceTertiary,
                )
            }
        }

        if (onAssignSlot != null) {
            Box(
                modifier = Modifier
                    .minTouchTarget(TonexTheme.touchTargets.secondary)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAssignSlot)
                    .semantics { contentDescription = "Assign preset $index to a footswitch slot" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⇄",
                    style = MaterialTheme.typography.titleMedium,
                    color = TonexTheme.extendedColors.onSurfaceTertiary,
                )
            }
        }
    }
}

@Preview(name = "Preset row — active, with subtitle", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PresetRowActivePreview() {
    TonexTheme {
        PresetRow(
            index = "07",
            name = "Set Opener",
            isActive = true,
            onClick = {},
            subtitle = "from pedal: PRESET 07",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Preset row — inactive, no subtitle", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PresetRowInactivePreview() {
    TonexTheme {
        PresetRow(
            index = "12",
            name = "DJENT TIGHT",
            isActive = false,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Preset row — with edit-alias affordance (S16)", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PresetRowEditableAliasPreview() {
    TonexTheme {
        PresetRow(
            index = "07",
            name = "Set Opener",
            isActive = true,
            onClick = {},
            subtitle = "from pedal: PRESET 07",
            onEditAlias = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Preset row — footswitch slot badges (S85)", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PresetRowSlotBadgesPreview() {
    TonexTheme {
        Column {
            PresetRow(
                index = "07",
                name = "Set Opener",
                isActive = true,
                onClick = {},
                subtitle = "from pedal: PRESET 07",
                assignedSlots = setOf("A", "B"),
                modifier = Modifier.padding(16.dp),
            )
            PresetRow(
                index = "12",
                name = "DJENT TIGHT",
                isActive = false,
                onClick = {},
                assignedSlots = setOf("C"),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Preview(name = "Preset row — assign-to-slot affordance (S85 part 2b)", showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PresetRowAssignSlotPreview() {
    TonexTheme {
        PresetRow(
            index = "07",
            name = "Set Opener",
            isActive = true,
            onClick = {},
            subtitle = "from pedal: PRESET 07",
            assignedSlots = setOf("A"),
            onEditAlias = {},
            onAssignSlot = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
