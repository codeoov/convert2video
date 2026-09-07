package com.example.convert2video.ui.screens.error_log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.R
import com.example.convert2video.data.ErrorLogEntry
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import com.example.convert2video.ui.components.buttons.SoftIconButton
import com.example.convert2video.ui.components.cards.C2vCard
import com.example.convert2video.ui.theme.C2vTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogScreen(
    onNavigateBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ErrorLogViewModel = viewModel(factory = ErrorLogViewModel.Factory),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    var expandedIds by remember { mutableStateOf(emptySet<Long>()) }

    val titleStr = stringResource(R.string.error_log_title)
    val clearAllStr = stringResource(R.string.error_log_clear_all)
    val emptyStr = stringResource(R.string.error_log_empty)
    val clearConfirmStr = stringResource(R.string.error_log_clear_confirm)
    val cancelStr = stringResource(R.string.action_cancel)
    val confirmStr = stringResource(R.string.action_confirm)
    val expandCdStr = stringResource(R.string.cd_error_log_expand)
    val collapseCdStr = stringResource(R.string.cd_error_log_collapse)
    val deleteCdStr = stringResource(R.string.cd_delete_error_log_entry)
    val cdOpenDrawerStr = stringResource(R.string.cd_open_drawer)
    val cdNavigateBackStr = stringResource(R.string.cd_navigate_back)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = titleStr,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    RoundedIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = cdNavigateBackStr,
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("navigate_back_button"),
                    )
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.testTag("error_log_clear_all_button"),
                        ) {
                            Text(
                                text = clearAllStr,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    RoundedIconButton(
                        icon = Icons.Filled.Menu,
                        contentDescription = cdOpenDrawerStr,
                        onClick = onOpenDrawer,
                        modifier = Modifier.testTag("open_drawer_button"),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("error_log_empty_state"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emptyStr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = C2vTheme.colors.ink3,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("error_log_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    val isExpanded = entry.id in expandedIds
                    ErrorLogEntryCard(
                        entry = entry,
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedIds = if (isExpanded) {
                                expandedIds - entry.id
                            } else {
                                expandedIds + entry.id
                            }
                        },
                        onDelete = { viewModel.deleteById(entry.id) },
                        expandCd = expandCdStr,
                        collapseCd = collapseCdStr,
                        deleteCd = deleteCdStr,
                        modifier = Modifier.testTag("error_log_entry_${entry.id}"),
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            modifier = Modifier.testTag("error_log_clear_confirm_dialog"),
            onDismissRequest = { showClearDialog = false },
            text = { Text(clearConfirmStr) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAll()
                        showClearDialog = false
                    },
                    modifier = Modifier.testTag("error_log_clear_confirm_button"),
                ) {
                    Text(confirmStr)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(cancelStr)
                }
            },
        )
    }
}

@Composable
private fun ErrorLogEntryCard(
    entry: ErrorLogEntry,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit,
    expandCd: String,
    collapseCd: String,
    deleteCd: String,
    modifier: Modifier = Modifier,
) {
    val timestamp = remember(entry.createdAt) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.createdAt))
    }
    val hasStack = !entry.stackTrace.isNullOrBlank()
    val toggleCd = if (isExpanded) collapseCd else expandCd

    C2vCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (hasStack) {
                            Modifier
                                .semantics { contentDescription = toggleCd }
                                .clickable(onClick = onToggleExpand)
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LevelBadge(level = entry.level)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = C2vTheme.colors.ink3,
                    )
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = C2vTheme.colors.ink3,
                    )
                }
                if (hasStack) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = C2vTheme.colors.ink3,
                    )
                }
                SoftIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = deleteCd,
                    onClick = onDelete,
                    modifier = Modifier.testTag("error_log_delete_${entry.id}"),
                )
            }

            if (isExpanded && hasStack) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                ) {
                    Text(
                        text = entry.stackTrace ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelBadge(
    level: String,
    modifier: Modifier = Modifier,
) {
    val (bgColor, textColor) = when (level) {
        "E" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        // W: 테마 토큰 사용 — 다크/라이트 테마 자동 대응 (하드코딩 Color 없음)
        "W" -> C2vTheme.colors.warningContainer to C2vTheme.colors.onWarningContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to C2vTheme.colors.ink3
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = level,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}
