package com.example.convert2video.ui.screens.audio_pick

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.R
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.data.AudioItem
import com.example.convert2video.ui.shared.AudioRowBody
import com.example.convert2video.ui.shared.MediaDurationStyle
import com.example.convert2video.ui.shared.formatMediaDurationMs
import com.example.convert2video.ui.shared.rememberAudioMediaPermissionState
import com.example.convert2video.ui.components.badges.StatusBadge
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import com.example.convert2video.ui.components.controls.SegmentedControl
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme

@Composable
fun AudioPickScreen(
    onAudioBatchPicked: (List<AudioItem>) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudioPickViewModel = viewModel(),
) {
    val displayedItems by viewModel.displayedAudioItems.collectAsStateWithLifecycle()
    val mediaItems by viewModel.mediaItems.collectAsStateWithLifecycle()
    val recordingItems by viewModel.recordingItems.collectAsStateWithLifecycle()
    val audioFilter by viewModel.audioFilter.collectAsStateWithLifecycle()
    val isLoadingMedia by viewModel.isLoadingMedia.collectAsStateWithLifecycle()
    val isLoadingRecordings by viewModel.isLoadingRecordings.collectAsStateWithLifecycle()
    val mediaError by viewModel.mediaError.collectAsStateWithLifecycle()
    val recordingsError by viewModel.recordingsError.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val convertedAudioUris by viewModel.convertedAudioUris.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionState = rememberAudioMediaPermissionState(
        onPermissionGranted = { viewModel.loadAudio() },
        onAudioSelected = { uri ->
            scope.launch {
                val durationMs = withContext(Dispatchers.IO) {
                    try {
                        MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(context, uri)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L
                        }
                    } catch (e: Exception) {
                        AppLogger.w("AudioPickScreen", "Failed to read duration for picked audio", e)
                        0L
                    }
                }
                // id=0 금지 — 선택 Set 충돌 방지; uri hash 기반 음수 id 사용 (RecordingsListViewModel.buildAudioItemFromUri와 동일 식)
                val itemId = -(uri.hashCode().toLong().and(0x7fffffffL) + 1L)
                onAudioBatchPicked(
                    listOf(
                        AudioItem(
                            id = itemId,
                            title = context.getString(R.string.audio_pick_selected_title),
                            fileName = uri.lastPathSegment.orEmpty(),
                            artist = null,
                            durationMs = durationMs,
                            uri = uri,
                        ),
                    ),
                )
            }
        },
    )

    LaunchedEffect(Unit) {
        permissionState.launch()
        viewModel.loadRecordings()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.clearSelection() }
    }

    BackHandler(enabled = isSelectionMode) {
        viewModel.clearSelection()
    }

    val currentOnAudioBatchPicked = rememberUpdatedState(onAudioBatchPicked)
    val pickAndClearSelection = remember<(List<AudioItem>) -> Unit>(viewModel) {
        { items ->
            viewModel.clearSelection()
            currentOnAudioBatchPicked.value(items)
        }
    }

    AudioPickContent(
        audioItems = displayedItems,
        mediaItems = mediaItems,
        recordingItems = recordingItems,
        audioFilter = audioFilter,
        isLoadingMedia = isLoadingMedia,
        isLoadingRecordings = isLoadingRecordings,
        mediaError = mediaError,
        recordingsError = recordingsError,
        selectedIds = selectedIds,
        isSelectionMode = isSelectionMode,
        convertedAudioUris = convertedAudioUris,
        onAudioBatchPicked = pickAndClearSelection,
        onEnterSelectionMode = viewModel::enterSelectionMode,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onFilterChange = viewModel::setAudioFilter,
        onRetryMedia = {
            if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) {
                viewModel.loadAudio()
            } else {
                permissionState.launchGetContent()
            }
        },
        onRetryRecordings = viewModel::loadRecordings,
        onNavigateBack = onNavigateBack,
        onOpenDrawer = onOpenDrawer,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AudioPickContent(
    audioItems: List<AudioItem>,
    mediaItems: List<AudioItem>,
    recordingItems: List<AudioItem>,
    audioFilter: AudioSourceFilter,
    isLoadingMedia: Boolean,
    isLoadingRecordings: Boolean,
    mediaError: String?,
    recordingsError: String?,
    selectedIds: Set<Long>,
    isSelectionMode: Boolean,
    convertedAudioUris: Set<String> = emptySet(),
    onAudioBatchPicked: (List<AudioItem>) -> Unit,
    onEnterSelectionMode: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onFilterChange: (AudioSourceFilter) -> Unit,
    onRetryMedia: () -> Unit,
    onRetryRecordings: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val topBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
    )
    val filterLabels = listOf(
        stringResource(R.string.audio_pick_filter_all),
        stringResource(R.string.audio_pick_filter_my_recordings),
        stringResource(R.string.audio_pick_filter_files),
    )
    val filterIndex = audioSourceFilterToIndex(audioFilter)
    val emptyMessage = stringResource(emptyMessageForAudioFilter(audioFilter))
    val selectionCountLabel = stringResource(R.string.audio_pick_selection_count, selectedIds.size)
    val clearSelectionCd = stringResource(R.string.cd_audio_clear_selection)
    val confirmSelectionCd = stringResource(R.string.cd_audio_confirm_selection)
    val audioPickTitle = stringResource(R.string.audio_pick_title)
    val retryLabel = stringResource(R.string.audio_pick_retry)
    val isLoading = isLoadingForAudioFilter(audioFilter, isLoadingMedia, isLoadingRecordings)
    val showFullScreenError = shouldShowFullScreenAudioPickError(
        filter = audioFilter,
        displayedItems = audioItems,
        mediaItems = mediaItems,
        recordingItems = recordingItems,
        isLoadingMedia = isLoadingMedia,
        isLoadingRecordings = isLoadingRecordings,
        mediaError = mediaError,
        recordingsError = recordingsError,
    )
    val fullScreenError = if (showFullScreenError) {
        fullScreenAudioPickErrorMessage(audioFilter, mediaError, recordingsError)
    } else {
        null
    }
    val inlineError = inlineAudioPickError(
        filter = audioFilter,
        displayedItems = audioItems,
        mediaItems = mediaItems,
        recordingItems = recordingItems,
        mediaError = mediaError,
        recordingsError = recordingsError,
    )
    val currentOnRetryMedia = rememberUpdatedState(onRetryMedia)
    val currentOnRetryRecordings = rememberUpdatedState(onRetryRecordings)
    val currentAudioFilter = rememberUpdatedState(audioFilter)
    val onRetry = remember<() -> Unit> {
        {
            if (shouldRetryMediaOnAudioPickRetry(currentAudioFilter.value)) {
                currentOnRetryMedia.value()
            }
            if (shouldRetryRecordingsOnAudioPickRetry(currentAudioFilter.value)) {
                currentOnRetryRecordings.value()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            selectionCountLabel,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    colors = topBarColors,
                    navigationIcon = {
                        RoundedIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = clearSelectionCd,
                            onClick = onClearSelection,
                            modifier = Modifier
                                .padding(start = 20.dp)
                                .testTag("audio_clear_selection_button"),
                        )
                    },
                    actions = {
                        RoundedIconButton(
                            icon = Icons.Filled.Check,
                            contentDescription = confirmSelectionCd,
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val selected = audioItems.filter { it.id in selectedIds }
                                if (selected.isNotEmpty()) {
                                    onAudioBatchPicked(selected)
                                }
                            },
                            modifier = Modifier
                                .padding(end = 20.dp)
                                .testTag("audio_confirm_selection_button"),
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(audioPickTitle, style = MaterialTheme.typography.titleLarge)
                    },
                    colors = topBarColors,
                    navigationIcon = {
                        RoundedIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                            onClick = onNavigateBack,
                            modifier = Modifier.padding(start = 20.dp),
                        )
                    },
                    actions = {
                        RoundedIconButton(
                            icon = Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.cd_open_drawer),
                            onClick = onOpenDrawer,
                            modifier = Modifier
                                .padding(end = 20.dp)
                                .testTag("open_drawer_button"),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            if (!isSelectionMode) {
                SegmentedControl(
                    options = filterLabels,
                    selectedIndex = filterIndex,
                    onSelect = { index ->
                        onFilterChange(audioSourceFilterFromIndex(index))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("audio_filter_segmented_control"),
                    optionTestTags = listOf(
                        "audio_filter_all",
                        "audio_filter_my_recordings",
                        "audio_filter_files",
                    ),
                )
            }
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                fullScreenError != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("audio_error_state"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = fullScreenError,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .testTag("audio_retry_button"),
                        ) {
                            Text(retryLabel)
                        }
                    }
                }
                audioItems.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("audio_empty_state"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            emptyMessage,
                            color = C2vTheme.colors.ink3,
                        )
                    }
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        inlineError?.let { banner ->
                            Text(
                                text = banner,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("audio_inline_error"),
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp)
                                .testTag("audio_list"),
                            contentPadding = PaddingValues(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(audioItems, key = { it.id }) { item ->
                                val isSelected = item.id in selectedIds
                                AudioListItem(
                                    item = item,
                                    isSelectionMode = isSelectionMode,
                                    isSelected = isSelected,
                                    isAlreadyConverted = item.uri.toString() in convertedAudioUris,
                                    onClick = {
                                        if (isSelectionMode) {
                                            onToggleSelection(item.id)
                                        } else {
                                            onAudioBatchPicked(listOf(item))
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            onEnterSelectionMode(item.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun audioItemDisplayName(item: AudioItem): String =
    item.fileName.ifBlank { item.title }.ifBlank { item.uri.lastPathSegment.orEmpty() }.ifBlank { "-" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioListItem(
    item: AudioItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isAlreadyConverted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(C2vRadius.innerCard))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 13.dp)
            .testTag("audio_item_${item.id}"),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alreadyConvertedLabel = stringResource(R.string.audio_already_converted_badge)
        if (isSelectionMode) {
            SelectionCheckbox(isSelected = isSelected)
        }
        val displayName = audioItemDisplayName(item)
        val durationStr = formatMediaDurationMs(item.durationMs, MediaDurationStyle.ListRow)
        val secondaryLine = if (item.artist != null) "$durationStr · ${item.artist}" else durationStr
        Column(modifier = Modifier.weight(1f)) {
            AudioRowBody(
                fileName = displayName,
                secondaryLine = secondaryLine,
                folderLabel = item.folderLabel,
            )
            if (isAlreadyConverted) {
                StatusBadge(
                    text = alreadyConvertedLabel,
                    modifier = Modifier
                        .semantics { contentDescription = alreadyConvertedLabel }
                        .testTag("audio_item_${item.id}_already_converted_badge"),
                )
            }
        }
    }
}

@Composable
private fun SelectionCheckbox(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .then(
                if (!isSelected) {
                    Modifier.border(
                        BorderStroke(1.5.dp, C2vTheme.colors.cardBorder),
                        RoundedCornerShape(7.dp),
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
