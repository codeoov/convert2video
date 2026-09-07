package com.example.convert2video.ui.screens.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.R
import com.example.convert2video.data.TrashedItem
import com.example.convert2video.ui.components.badges.AccentGlyphBadge
import com.example.convert2video.ui.components.badges.StatusBadge
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import com.example.convert2video.ui.components.buttons.SoftChipButton
import com.example.convert2video.ui.components.cards.C2vCard
import com.example.convert2video.ui.shared.MediaDurationStyle
import com.example.convert2video.ui.shared.formatMediaDurationMs
import com.example.convert2video.ui.theme.C2vTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onNavigateBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = viewModel(factory = TrashViewModel.Factory),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val actionInFlightIds by viewModel.actionInFlightIds.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeleteItem by remember { mutableStateOf<TrashedItem?>(null) }

    val titleStr = stringResource(R.string.trash_title)
    val emptyStr = stringResource(R.string.trash_empty)
    val restoreStr = stringResource(R.string.trash_restore)
    val deleteForeverStr = stringResource(R.string.trash_permanently_delete)
    val cancelStr = stringResource(R.string.action_cancel)
    val confirmStr = stringResource(R.string.action_confirm)
    val convertedBadgeStr = stringResource(R.string.audio_already_converted_badge)
    val notConvertedBadgeStr = stringResource(R.string.trash_not_converted_badge)
    val cdOpenDrawerStr = stringResource(R.string.cd_open_drawer)
    val cdNavigateBackStr = stringResource(R.string.cd_navigate_back)
    val cdRestoreStr = stringResource(R.string.cd_trash_restore)
    val cdDeleteForeverStr = stringResource(R.string.cd_trash_permanently_delete)
    val cdAudioStr = stringResource(R.string.cd_trash_audio_item)
    val cdVideoStr = stringResource(R.string.cd_trash_video_item)

    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(rows) {
        val pendingId = pendingDeleteItem?.id ?: return@LaunchedEffect
        if (rows.none { it.item.id == pendingId }) {
            pendingDeleteItem = null
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("trash_empty_state"),
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
                    .testTag("trash_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.item.id }) { row ->
                    val inFlight = row.item.id in actionInFlightIds
                    TrashItemRow(
                        row = row,
                        enabled = !inFlight,
                        restoreLabel = restoreStr,
                        deleteForeverLabel = deleteForeverStr,
                        convertedBadge = convertedBadgeStr,
                        notConvertedBadge = notConvertedBadgeStr,
                        restoreCd = cdRestoreStr,
                        deleteForeverCd = cdDeleteForeverStr,
                        typeCd = if (row.isAudio) cdAudioStr else cdVideoStr,
                        onRestore = { viewModel.restore(row.item) },
                        onDeleteForever = { pendingDeleteItem = row.item },
                        modifier = Modifier.testTag("trash_item_${row.item.id}"),
                    )
                }
            }
        }
    }

    val pending = pendingDeleteItem
    if (pending != null) {
        val confirmText = stringResource(
            R.string.trash_permanently_delete_confirm,
            pending.displayName,
        )
        AlertDialog(
            modifier = Modifier.testTag("trash_permanently_delete_confirm_dialog"),
            onDismissRequest = { pendingDeleteItem = null },
            text = { Text(confirmText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.permanentlyDelete(pending)
                        pendingDeleteItem = null
                    },
                    modifier = Modifier.testTag("trash_permanently_delete_confirm_button"),
                ) {
                    Text(confirmStr)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteItem = null }) {
                    Text(cancelStr)
                }
            },
        )
    }
}

@Composable
private fun TrashItemRow(
    row: TrashRowUi,
    enabled: Boolean,
    restoreLabel: String,
    deleteForeverLabel: String,
    convertedBadge: String,
    notConvertedBadge: String,
    restoreCd: String,
    deleteForeverCd: String,
    typeCd: String,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = row.item
    val timestamp = remember(item.deletedAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.deletedAt))
    }
    val durationMs = item.durationMs
    val durationStr = if (durationMs != null && durationMs >= 0L) {
        formatMediaDurationMs(durationMs, MediaDurationStyle.ListRow)
    } else {
        null
    }
    val secondaryLine = if (durationStr != null) "$durationStr · $timestamp" else timestamp
    val glyph = if (row.isAudio) AUDIO_GLYPH else VIDEO_GLYPH

    C2vCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AccentGlyphBadge(
                    glyph = glyph,
                    size = 40.dp,
                    modifier = Modifier.semantics { contentDescription = typeCd },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName.ifBlank { "-" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = secondaryLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = C2vTheme.colors.ink3,
                    )
                    if (row.isAudio) {
                        val badgeText = if (row.isConverted) convertedBadge else notConvertedBadge
                        val badgeTag = if (row.isConverted) {
                            "trash_converted_badge_${item.id}"
                        } else {
                            "trash_not_converted_badge_${item.id}"
                        }
                        StatusBadge(
                            text = badgeText,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .semantics { contentDescription = badgeText }
                                .testTag(badgeTag),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SoftChipButton(
                    text = restoreLabel,
                    onClick = onRestore,
                    enabled = enabled,
                    modifier = Modifier
                        .semantics { contentDescription = restoreCd }
                        .testTag("trash_restore_button_${item.id}"),
                )
                SoftChipButton(
                    text = deleteForeverLabel,
                    onClick = onDeleteForever,
                    enabled = enabled,
                    modifier = Modifier
                        .semantics { contentDescription = deleteForeverCd }
                        .testTag("trash_permanently_delete_button_${item.id}"),
                )
            }
        }
    }
}

private const val AUDIO_GLYPH = "♪"
private const val VIDEO_GLYPH = "▶"
