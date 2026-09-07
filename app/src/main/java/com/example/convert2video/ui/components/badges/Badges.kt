package com.example.convert2video.ui.components.badges

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme

/**
 * Public badge composables:
 * - [SectionStepBadge]
 * - [SectionLabel]
 * - [StatusBadge]
 * - [AccentGlyphBadge]
 */

/** Small numbered step badge, e.g. the "1 / 2 / 3" markers on the home screen sections. */
@Composable
fun SectionStepBadge(
    number: Int,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(19.dp)
            .clip(RoundedCornerShape(C2vRadius.badge))
            .background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.onPrimary else C2vTheme.colors.ink3,
        )
    }
}

/** Section eyebrow row: numbered badge + label, e.g. "1 배경화면". */
@Composable
fun SectionLabel(
    number: Int,
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionStepBadge(number = number, active = active)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact status pill, e.g. "변환됨 / 업로드됨" list-row badges. */
@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val badgeModifier = modifier
        .clip(RoundedCornerShape(C2vRadius.badge))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .then(
            if (onClick != null) {
                Modifier
                    .semantics { role = Role.Button }
                    .clickable(onClick = onClick)
            } else {
                Modifier
            },
        )
    Box(modifier = badgeModifier.padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = C2vTheme.colors.ink3,
        )
    }
}

/** Circular accent badge used for the music-note avatar. */
@Composable
fun AccentGlyphBadge(
    glyph: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    shape: Shape = RoundedCornerShape(C2vRadius.control + 4.dp),
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
        )
    }
}
