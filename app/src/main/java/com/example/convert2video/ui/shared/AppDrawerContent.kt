package com.example.convert2video.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.convert2video.AppDestination
import com.example.convert2video.R
import com.example.convert2video.ui.components.badges.AccentGlyphBadge
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme

/**
 * Modal drawer body: exactly 5 nav rows
 * (Home / Options / Trash / ErrorLog / Microphone Source).
 * Must not create a `drawer_theme_section` tag — theme controls are Options-only.
 * Convert and ConvertedVideos are not drawer rows.
 */
@Composable
fun AppDrawerContent(
    selected: AppDestination? = null,
    modifier: Modifier = Modifier,
    /** [RecordingController] 활성 세션(Recording/Paused/Stopping)일 때 Home 행 배지. */
    isRecordingActive: Boolean = false,
    onSelect: (AppDestination) -> Unit,
) {
    val homeLabel = stringResource(R.string.drawer_home)
    val recordActiveBadge = stringResource(R.string.drawer_record_active_badge)
    val optionsLabel = stringResource(R.string.drawer_options)
    val trashLabel = stringResource(R.string.drawer_trash)
    val errorLogLabel = stringResource(R.string.drawer_error_log)
    val microphoneSourceLabel = stringResource(R.string.drawer_microphone_source)
    val appName = stringResource(R.string.app_name)

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentGlyphBadge(
                    glyph = "♪",
                    modifier = Modifier.semantics { contentDescription = appName },
                    size = 38.dp,
                    shape = RoundedCornerShape(C2vRadius.control + 1.dp),
                )
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DrawerNavRow(
                    glyph = "⌂",
                    label = homeLabel,
                    selected = selected == AppDestination.Home,
                    onClick = { onSelect(AppDestination.Home) },
                    testTag = "drawer_home_item",
                    badgeLabel = if (isRecordingActive) recordActiveBadge else null,
                )
                DrawerNavRow(
                    glyph = "⚙",
                    label = optionsLabel,
                    selected = selected == AppDestination.Options,
                    onClick = { onSelect(AppDestination.Options) },
                    testTag = "drawer_options_item",
                )
                DrawerNavRow(
                    glyph = "🗑",
                    label = trashLabel,
                    selected = selected == AppDestination.Trash,
                    onClick = { onSelect(AppDestination.Trash) },
                    testTag = "drawer_trash_item",
                )
                DrawerNavRow(
                    glyph = "!",
                    label = errorLogLabel,
                    selected = selected == AppDestination.ErrorLog,
                    onClick = { onSelect(AppDestination.ErrorLog) },
                    testTag = "drawer_error_log_item",
                )
                DrawerNavRow(
                    glyph = "🎙",
                    label = microphoneSourceLabel,
                    selected = selected == AppDestination.MicrophoneSource,
                    onClick = { onSelect(AppDestination.MicrophoneSource) },
                    testTag = "drawer_microphone_source_item",
                )
            }
        }
    }
}

@Composable
private fun DrawerNavRow(
    glyph: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    badgeLabel: String? = null,
) {
    val rowDescription = if (badgeLabel != null) "$label, $badgeLabel" else label
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(C2vRadius.innerCard))
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = rowDescription
                role = Role.Button
                this.selected = selected
            }
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else C2vTheme.colors.ink3,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (badgeLabel != null) {
            // Visual badge only — row mergeDescendants already announces "$label, $badgeLabel".
            Text(
                text = badgeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.testTag("drawer_record_active_badge"),
            )
        }
    }
}
