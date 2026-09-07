package com.example.convert2video.ui.components.buttons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme

/**
 * Public button composables:
 * - [SoftChipButton]
 * - [AccentCtaButton]
 * - [SoftIconButton]
 * - [RoundedIconButton]
 * - [TopBarChipButton]
 */

/** Soft rounded chip button — the small "변경/선택/파일 선택" actions with a `surface2` background. */
@Composable
fun SoftChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(C2vRadius.chip))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else C2vTheme.colors.ink3,
        )
    }
}

/** Full-width accent CTA (e.g. "변환하기" / "완료"). Falls back to a muted look when disabled. */
@Composable
fun AccentCtaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 54.dp,
) {
    val containerColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else C2vTheme.colors.ink3
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(C2vRadius.cta))
            .background(containerColor)
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

/** Small rounded action icon button, e.g. the play/rename/upload/delete row actions. */
@Composable
fun SoftIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(C2vRadius.chip))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else C2vTheme.colors.ink3,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Pill-shaped icon button used in top bars for the back/menu affordance. */
@Composable
fun RoundedIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(C2vRadius.control))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, C2vTheme.colors.cardBorder), RoundedCornerShape(C2vRadius.control))
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else C2vTheme.colors.ink3,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** Top bar action chip, e.g. "결과 / 정렬 / 편집". */
@Composable
fun TopBarChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(C2vRadius.control))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, C2vTheme.colors.cardBorder), RoundedCornerShape(C2vRadius.control))
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else C2vTheme.colors.ink3,
        )
    }
}
