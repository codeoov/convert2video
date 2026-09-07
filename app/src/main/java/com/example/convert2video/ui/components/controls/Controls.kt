package com.example.convert2video.ui.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme

/**
 * Public control composables:
 * - [SegmentedControl]
 * - [StepperControl]
 * - [C2vSwitch]
 */

/** Pill-track segmented control, e.g. "등분 / 커스텀" or "라이트 / 다크 / 시스템". */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionTestTags: List<String>? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(C2vRadius.pill))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val optionTag = optionTestTags?.getOrNull(index)
            // Transparent: unselected option must show the parent surfaceVariant track.
            val optionModifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape((C2vRadius.pill.value - 4).dp))
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .semantics {
                    selected = isSelected
                    if (!enabled) disabled()
                }
                .clickable(enabled = enabled) { onSelect(index) }
                .then(if (optionTag != null) Modifier.testTag(optionTag) else Modifier)
            Box(
                modifier = optionModifier.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        !enabled -> C2vTheme.colors.ink3
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "−  N  +" stepper used for segment count. */
@Composable
fun StepperControl(
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
    decrementTestTag: String? = null,
    incrementTestTag: String? = null,
    decrementContentDescription: String? = null,
    incrementContentDescription: String? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(C2vRadius.pill))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StepperGlyphButton(
            glyph = "−",
            enabled = decrementEnabled,
            onClick = onDecrement,
            testTag = decrementTestTag,
            contentDescription = decrementContentDescription,
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        StepperGlyphButton(
            glyph = "+",
            enabled = incrementEnabled,
            onClick = onIncrement,
            testTag = incrementTestTag,
            contentDescription = incrementContentDescription,
        )
    }
}

@Composable
private fun StepperGlyphButton(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String?,
    contentDescription: String?,
) {
    val glyphModifier = Modifier
        .size(36.dp)
        .clip(RoundedCornerShape(C2vRadius.chip))
        .background(MaterialTheme.colorScheme.surface)
        .clickable(enabled = enabled, onClick = onClick)
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
        .then(
            if (contentDescription != null) {
                Modifier.semantics { this.contentDescription = contentDescription }
            } else {
                Modifier
            },
        )
    Box(modifier = glyphModifier, contentAlignment = Alignment.Center) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else C2vTheme.colors.ink3,
        )
    }
}

/** Themed [Switch] with the honey-accent track used across the app. */
@Composable
fun C2vSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedThumbColor = C2vTheme.colors.ink3,
            uncheckedBorderColor = C2vTheme.colors.cardBorder,
        ),
    )
}
