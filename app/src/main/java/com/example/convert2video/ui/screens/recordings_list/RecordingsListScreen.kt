package com.example.convert2video.ui.screens.recordings_list

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.convert2video.R
import com.example.convert2video.data.AudioItem
import com.example.convert2video.data.FORBIDDEN_DISPLAY_NAME_CHARS
import com.example.convert2video.data.MAX_DISPLAY_NAME_STEM_LENGTH
import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.data.isValidDisplayNameStem
import com.example.convert2video.data.importedIdFromAudioItemId
import com.example.convert2video.data.recordingIdFromAudioItemId
import com.example.convert2video.record.recordingExtension
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.ui.components.badges.StatusBadge
import com.example.convert2video.ui.components.buttons.AccentCtaButton
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import com.example.convert2video.ui.components.buttons.SoftChipButton
import com.example.convert2video.ui.components.cards.C2vCard
import com.example.convert2video.ui.components.controls.DropdownSelector
import com.example.convert2video.ui.components.controls.SegmentedControl
import com.example.convert2video.ui.screens.audio_pick.AudioSourceFilter
import com.example.convert2video.ui.shared.AudioRowBody
import com.example.convert2video.ui.shared.MediaDurationStyle
import com.example.convert2video.ui.shared.formatMediaDurationMs
import com.example.convert2video.ui.shared.rememberAudioMediaPermissionState
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme
import kotlinx.coroutines.launch
/**
 * Home Listen 선택 모드 컨텍스트 바 바인딩.
 * [RecordingsListTabContent] → [com.example.convert2video.ui.screens.home.HomeScreen] 단일 셸 TopBar용.
 */
data class ListenSelectionChrome(
    val selectedCount: Int,
    val onCancelClick: () -> Unit,
)

/**
 * Home Listen 탭 임베드 — nested TopBar 없이 Stateful만 표시.
 *
 * 선택 모드는 로컬 remember. SelectionTopBar는 Home 셸이 호스트하므로
 * Content에는 showSelectionTopBar=false.
 *
 * [onNavigateHome]: 루트 NavigateHome.
 * [navigationBlocked]: Home Saving 게이트와 동일 플래그.
 * import chip `enabled = !navigationBlocked && !selectionMode` (선택 중에도 칩은 표시).
 */
@Composable
fun RecordingsListTabContent(
    viewModel: RecordingsListViewModel,
    modifier: Modifier = Modifier,
    onNavigateHome: () -> Unit,
    onConvertAudioItems: (List<AudioItem>) -> Unit = {},
    navigationBlocked: Boolean = false,
    onSelectionChromeChange: (ListenSelectionChrome?) -> Unit = {},
) {
    RecordingsListStateful(
        viewModel = viewModel,
        showTopBar = false,
        onNavigateHome = onNavigateHome,
        selectionEnabled = true,
        showSelectionTopBar = false,
        onConvertAudioItems = onConvertAudioItems,
        navigationBlocked = navigationBlocked,
        onSelectionChromeChange = onSelectionChromeChange,
        modifier = modifier,
    )
}

/**
 * 선택 모드·Import flow·에러 수집의 단일 소스.
 * [RecordingsListTabContent]는 이 래퍼만 호출한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingsListStateful(
    viewModel: RecordingsListViewModel,
    showTopBar: Boolean,
    onNavigateHome: () -> Unit,
    selectionEnabled: Boolean,
    showSelectionTopBar: Boolean,
    modifier: Modifier = Modifier,
    onConvertAudioItems: (List<AudioItem>) -> Unit = {},
    navigationBlocked: Boolean = false,
    onSelectionChromeChange: (ListenSelectionChrome?) -> Unit = {},
) {
    val audioItems by viewModel.audioItems.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val audioFilter by viewModel.audioFilter.collectAsStateWithLifecycle()
    // 첫 프레임부터 Files 인덱스 깨짐·flash 방지 — ViewModel 정규화 전 과도 상태 대비 동기 보정.
    val effectiveFilter = if (audioFilter == AudioSourceFilter.Files) {
        AudioSourceFilter.MyRecordings
    } else {
        audioFilter
    }
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val conversionFilter by viewModel.conversionFilter.collectAsStateWithLifecycle()
    val convertedAudioUris by viewModel.convertedAudioUris.collectAsStateWithLifecycle()
    val pendingDeleteUri by viewModel.pendingDeleteUri.collectAsStateWithLifecycle()
    val latestPendingDeleteUri by rememberUpdatedState(pendingDeleteUri)

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playNoAppMessage = stringResource(R.string.recordings_list_play_no_app)
    val playFailedMessage = stringResource(R.string.recordings_list_play_failed)
    val playNoAppMessageState = rememberUpdatedState(playNoAppMessage)
    val playFailedMessageState = rememberUpdatedState(playFailedMessage)
    val onPlayAudio: (AudioItem) -> Unit =
        remember(context, scope, snackbarHostState) {
            { item ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(item.uri, "audio/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    AppLogger.w(TAG, "No app to play audio: title=${item.title}", e)
                    scope.launch { snackbarHostState.showSnackbar(playNoAppMessageState.value) }
                } catch (e: SecurityException) {
                    AppLogger.e(TAG, "Play audio permission denied: title=${item.title}", e)
                    scope.launch { snackbarHostState.showSnackbar(playFailedMessageState.value) }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Play audio failed: title=${item.title}", e)
                    scope.launch { snackbarHostState.showSnackbar(playFailedMessageState.value) }
                }
            }
        }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var expandedMenuId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteRecording by remember { mutableStateOf<RecordingRecord?>(null) }
    var pendingDeleteMedia by remember { mutableStateOf<AudioItem?>(null) }
    var pendingRenameRecording by remember { mutableStateOf<RecordingRecord?>(null) }
    var renameInput by remember { mutableStateOf("") }

    fun recordingFor(item: AudioItem): RecordingRecord? {
        val recordingId = recordingIdFromAudioItemId(item.id) ?: return null
        return recordings.find { it.id == recordingId }.also { found ->
            if (found == null) {
                AppLogger.w(TAG, "recording lookup failed")
            }
        }
    }

    fun clearPendingDialogs() {
        pendingDeleteRecording = null
        pendingDeleteMedia = null
        pendingRenameRecording = null
        renameInput = ""
    }

    fun openDeleteMedia(item: AudioItem) {
        if (pendingDeleteUri != null) {
            viewModel.deleteMediaAudioItem(item)
            return
        }
        clearPendingDialogs()
        expandedMenuId = null
        pendingDeleteMedia = item
    }

    fun openDeleteRecording(record: RecordingRecord) {
        clearPendingDialogs()
        expandedMenuId = null
        pendingDeleteRecording = record
    }

    fun beginRename(record: RecordingRecord) {
        clearPendingDialogs()
        expandedMenuId = null
        pendingRenameRecording = record
        renameInput = viewModel.displayNameStemFor(record)
    }

    val latestOnConvertAudioItems by rememberUpdatedState(onConvertAudioItems)
    val latestOnSelectionChromeChange by rememberUpdatedState(onSelectionChromeChange)
    val latestOnNavigateHome by rememberUpdatedState(onNavigateHome)
    val latestAudioItems by rememberUpdatedState(audioItems)
    val chromeEmitGate = remember {
        object {
            var lastActive: Boolean? = null
            var lastCount: Int = -1
        }
    }

    val exitSelectionMode: () -> Unit = remember {
        {
            selectionMode = false
            selectedIds = emptySet()
            if (chromeEmitGate.lastActive == true) {
                chromeEmitGate.lastActive = false
                chromeEmitGate.lastCount = 0
                latestOnSelectionChromeChange(null)
            } else {
                chromeEmitGate.lastActive = false
                chromeEmitGate.lastCount = 0
            }
        }
    }

    val visibleItemIds = remember(audioItems) { audioItems.map { it.id }.toSet() }
    LaunchedEffect(visibleItemIds, selectionMode) {
        val previousSelectedIds = selectedIds
        val pruned = previousSelectedIds.intersect(visibleItemIds)
        selectedIds = pruned
        expandedMenuId = pruneExpandedRecordId(expandedMenuId, visibleItemIds)
        // 목록 prune으로 선택이 비었을 때만 종료. 사용자가 0개로 만든 모드는 유지.
        if (selectionMode && pruned.isEmpty() && previousSelectedIds.isNotEmpty()) {
            exitSelectionMode()
        }
    }

    LaunchedEffect(selectionEnabled) {
        if (!selectionEnabled) {
            selectionMode = false
            selectedIds = emptySet()
        }
    }

    // Listen은 Files 필터를 표시하지 않으므로 ViewModel 상태가 Files면 MyRecordings로 교정한다.
    // 외부(ViewModel 초기화·상태 복원 등)가 Files를 주입해도 UI·데이터 어긋남 방지.
    LaunchedEffect(audioFilter) {
        if (audioFilter == AudioSourceFilter.Files) {
            viewModel.setAudioFilter(AudioSourceFilter.MyRecordings)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val mediaDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val uri = latestPendingDeleteUri
        if (uri != null) {
            viewModel.confirmMediaDelete(uri, result.resultCode == Activity.RESULT_OK)
        } else {
            viewModel.clearPendingMediaDelete()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.mediaDeleteConfirmation.collect { request ->
            mediaDeleteLauncher.launch(request)
        }
    }

    val hasPendingDelete = pendingDeleteRecording != null || pendingDeleteMedia != null
    val hasPendingRename = pendingRenameRecording != null

    BackHandler(
        enabled = expandedMenuId != null ||
            hasPendingDelete ||
            hasPendingRename ||
            (selectionEnabled && selectionMode),
    ) {
        when (
            resolveRecordingsListBackAction(
                expandedMenuId,
                hasPendingDelete,
                hasPendingRename,
            )
        ) {
            RecordingsListBackAction.DismissDeleteDialog -> clearPendingDialogs()
            RecordingsListBackAction.DismissRenameDialog -> {
                pendingRenameRecording = null
                renameInput = ""
            }
            RecordingsListBackAction.DismissExpandedMenu -> expandedMenuId = null
            RecordingsListBackAction.NavigateHome -> {
                if (selectionMode) {
                    exitSelectionMode()
                } else {
                    latestOnNavigateHome()
                }
            }
        }
    }

    // 선택 chrome — selectionMode/count가 바뀔 때만 emit. 비선택은 null 전이 1회만.
    SideEffect {
        if (selectionEnabled && selectionMode) {
            val count = selectedIds.size
            if (chromeEmitGate.lastActive != true || chromeEmitGate.lastCount != count) {
                chromeEmitGate.lastActive = true
                chromeEmitGate.lastCount = count
                latestOnSelectionChromeChange(
                    ListenSelectionChrome(
                        selectedCount = count,
                        onCancelClick = exitSelectionMode,
                    ),
                )
            }
        } else if (chromeEmitGate.lastActive == true) {
            chromeEmitGate.lastActive = false
            chromeEmitGate.lastCount = 0
            latestOnSelectionChromeChange(null)
        } else {
            chromeEmitGate.lastActive = false
            chromeEmitGate.lastCount = 0
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (chromeEmitGate.lastActive == true) {
                chromeEmitGate.lastActive = false
                chromeEmitGate.lastCount = 0
                latestOnSelectionChromeChange(null)
            }
        }
    }

    val audioPermission = rememberAudioMediaPermissionState(
        onPermissionGranted = { viewModel.loadMediaAudio() },
        onAudioSelected = { uri -> viewModel.importAudioFromUri(uri) },
    )
    val audioMediaPermissionName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val deleteActionLabel = stringResource(R.string.recordings_list_action_delete)
    val cancelLabel = stringResource(R.string.action_cancel)

    pendingDeleteMedia?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMedia = null },
            title = { Text(stringResource(R.string.recordings_list_media_delete_title)) },
            text = { Text(stringResource(R.string.recordings_list_media_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    if (importedIdFromAudioItemId(item.id) != null) {
                        viewModel.deleteImportedAudioItem(item)
                    } else {
                        viewModel.deleteMediaAudioItem(item)
                    }
                    pendingDeleteMedia = null
                }) { Text(deleteActionLabel) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMedia = null }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    pendingDeleteRecording?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRecording = null },
            title = { Text(stringResource(R.string.recordings_list_delete_title)) },
            text = { Text(stringResource(R.string.recordings_list_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecording(record)
                    pendingDeleteRecording = null
                }) { Text(deleteActionLabel) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRecording = null }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    pendingRenameRecording?.let { record ->
        val isValidName = isValidDisplayNameStem(renameInput)
        val invalidCharsHint = stringResource(R.string.converted_videos_rename_invalid_chars)
        val tooLongHint = stringResource(
            R.string.converted_videos_rename_too_long,
            MAX_DISPLAY_NAME_STEM_LENGTH,
        )
        val blankOrInvalidHint = stringResource(R.string.recordings_list_rename_failed)
        val supportingMessage: String? = when {
            isValidName -> null
            renameInput.contains(FORBIDDEN_DISPLAY_NAME_CHARS) -> invalidCharsHint
            renameInput.length > MAX_DISPLAY_NAME_STEM_LENGTH -> tooLongHint
            else -> blankOrInvalidHint
        }
        val fileExtension = recordingExtension(record.format)

        AlertDialog(
            onDismissRequest = {
                pendingRenameRecording = null
                renameInput = ""
            },
            title = { Text(stringResource(R.string.recordings_list_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.converted_videos_rename_label)) },
                    suffix = { Text(".$fileExtension") },
                    singleLine = true,
                    isError = !isValidName,
                    supportingText = supportingMessage?.let { msg -> { Text(msg) } },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = isValidName,
                    onClick = {
                        viewModel.renameRecording(record, renameInput)
                        pendingRenameRecording = null
                        renameInput = ""
                    },
                ) { Text(stringResource(R.string.converted_videos_rename_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRenameRecording = null
                    renameInput = ""
                }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    RecordingsListContent(
        audioItems = audioItems,
        convertedAudioUris = convertedAudioUris,
        audioFilter = effectiveFilter,
        sortOrder = sortOrder,
        conversionFilter = conversionFilter,
        onAudioFilterChange = { filter ->
            viewModel.setAudioFilter(filter)
            val hasMediaAudioPermission = ContextCompat.checkSelfPermission(
                context,
                audioMediaPermissionName,
            ) == PackageManager.PERMISSION_GRANTED
            if (filter == AudioSourceFilter.All && !hasMediaAudioPermission) {
                audioPermission.launch()
            }
        },
        onSortOrderChange = { viewModel.setSortOrder(it) },
        onConversionFilterChange = { viewModel.setConversionFilter(it) },
        onConvertAudioItems = { items ->
            if (items.isNotEmpty()) {
                expandedMenuId = null
                exitSelectionMode()
                latestOnConvertAudioItems(items)
            }
        },
        // [RecordingsListContent] / [AudioListItem]가 비선택·메뉴 닫힘 시 onAudioItemClick을 호출하지 않음 — 본문 탭 gate SSOT는 Content.
        onAudioItemClick = { item ->
            selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
        },
        onEnterSelectionMode = { id ->
            if (selectionEnabled) {
                expandedMenuId = null
                selectionMode = true
                selectedIds = setOf(id)
            }
        },
        onImportClick = { audioPermission.launchGetContent() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        selectionMode = selectionEnabled && selectionMode,
        selectedIds = selectedIds,
        showSelectionTopBar = showSelectionTopBar,
        onExitSelectionMode = exitSelectionMode,
        navigationBlocked = navigationBlocked,
        expandedMenuId = expandedMenuId,
        onToggleExpandedMenu = { id ->
            expandedMenuId = if (expandedMenuId == id) null else id
        },
        onPlayClick = { item ->
            expandedMenuId = null
            onPlayAudio(item)
        },
        onRenameClick = { item ->
            if (recordingIdFromAudioItemId(item.id) != null) {
                val record = recordingFor(item)
                if (record != null) {
                    beginRename(record)
                } else {
                    viewModel.reportRecordingLookupFailed(isRename = true)
                }
            }
        },
        onDeleteClick = { item ->
            if (recordingIdFromAudioItemId(item.id) != null) {
                val record = recordingFor(item)
                if (record != null) {
                    openDeleteRecording(record)
                } else {
                    viewModel.reportRecordingLookupFailed(isRename = false)
                }
            } else {
                openDeleteMedia(item)
            }
        },
        modifier = modifier,
    )
}

/**
 * Stateless UI.
 *
 * 제품 기본 showTopBar=false (Home Listen 임베드).
 * Home Listen 임베드에서는 showSelectionTopBar=false — SelectionTopBar는 Home 셸이 담당.
 * Content 단위 테스트에서는 showSelectionTopBar=true로 SelectionTopBar를 직접 검증한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingsListContent(
    audioItems: List<AudioItem>,
    convertedAudioUris: Set<String>,
    audioFilter: AudioSourceFilter,
    sortOrder: RecordingsListSortOrder,
    onAudioFilterChange: (AudioSourceFilter) -> Unit,
    onSortOrderChange: (RecordingsListSortOrder) -> Unit,
    onConvertAudioItems: (List<AudioItem>) -> Unit,
    onAudioItemClick: (AudioItem) -> Unit,
    onEnterSelectionMode: (Long) -> Unit,
    onImportClick: () -> Unit = {},
    snackbarHost: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    showSelectionTopBar: Boolean = true,
    onExitSelectionMode: () -> Unit = {},
    navigationBlocked: Boolean = false,
    expandedMenuId: Long? = null,
    onToggleExpandedMenu: (Long) -> Unit = {},
    onPlayClick: (AudioItem) -> Unit = {},
    onRenameClick: (AudioItem) -> Unit = {},
    onDeleteClick: (AudioItem) -> Unit = {},
    conversionFilter: RecordingsListConversionFilter = RecordingsListConversionFilter.All,
    onConversionFilterChange: (RecordingsListConversionFilter) -> Unit = {},
) {
    val emptyLabel = stringResource(R.string.recordings_list_empty)
    val convertLabel = stringResource(R.string.recordings_list_convert_action)
    val importLabel = stringResource(R.string.recordings_list_import_action)
    val filterMyRecordingsLabel = stringResource(R.string.recordings_list_filter_my_recordings)
    val filterAllLabel = stringResource(R.string.recordings_list_filter_all)
    val sortTimeLabel = stringResource(R.string.recordings_list_sort_time)
    val sortNameLabel = stringResource(R.string.recordings_list_sort_name)
    val sortDurationLabel = stringResource(R.string.recordings_list_sort_duration)
    val sortOptions = remember(sortTimeLabel, sortNameLabel, sortDurationLabel) {
        listOf(sortTimeLabel, sortNameLabel, sortDurationLabel)
    }
    val conversionFilterAllLabel =
        stringResource(R.string.recordings_list_conversion_filter_all)
    val conversionFilterConvertedLabel =
        stringResource(R.string.recordings_list_conversion_filter_converted)
    val conversionFilterNotConvertedLabel =
        stringResource(R.string.recordings_list_conversion_filter_not_converted)
    val selectionClearContentDescription =
        stringResource(R.string.cd_recording_selection_cancel)
    val conversionFilterOptions = remember(
        conversionFilterAllLabel,
        conversionFilterConvertedLabel,
        conversionFilterNotConvertedLabel,
    ) {
        listOf(
            conversionFilterAllLabel,
            conversionFilterConvertedLabel,
            conversionFilterNotConvertedLabel,
        )
    }
    val listenFilterDropdowns: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownSelector(
                options = conversionFilterOptions,
                selectedIndex = conversionFilterToIndex(conversionFilter),
                onSelect = { index ->
                    onConversionFilterChange(conversionFilterFromIndex(index))
                },
                enabled = !selectionMode,
                testTag = "listen_conversion_filter_dropdown",
            )
            DropdownSelector(
                options = sortOptions,
                selectedIndex = when (sortOrder) {
                    RecordingsListSortOrder.Time -> 0
                    RecordingsListSortOrder.Name -> 1
                    RecordingsListSortOrder.Duration -> 2
                },
                onSelect = { index ->
                    onSortOrderChange(
                        when (index) {
                            0 -> RecordingsListSortOrder.Time
                            1 -> RecordingsListSortOrder.Name
                            2 -> RecordingsListSortOrder.Duration
                            else -> RecordingsListSortOrder.Time
                        },
                    )
                },
                enabled = !selectionMode,
                testTag = "listen_sort_dropdown",
            )
        }
    }

    val listState = rememberLazyListState()
    val latestAudioItems by rememberUpdatedState(audioItems)
    val filterKey: ListenListFilterKey = Triple(audioFilter, sortOrder, conversionFilter)
    val itemCount = audioItems.size
    val scrollGate = remember {
        object {
            var previousKey: ListenListFilterKey? = null
            var pendingReset = false
        }
    }
    // 레이아웃 전 — SSOT 결정 전체를 requestScrollToItem으로 적용.
    // Reset이면 0, Coerce면 last. 같은 결정만 써서 0과 last를 동시에 쏘지 않는다.
    SideEffect {
        if (itemCount <= 0) {
            return@SideEffect
        }
        val decision = resolveListenListScrollDecision(
            previousKey = scrollGate.previousKey,
            currentKey = filterKey,
            itemCount = itemCount,
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
        )
        if (decision?.kind == ListenListScrollKind.ResetToFirst) {
            scrollGate.pendingReset = true
        }
        val applyIndex = listenListScrollApplyIndex(decision, scrollGate.pendingReset)
        if (applyIndex != null) {
            listState.requestScrollToItem(applyIndex)
        }
    }
    // itemCount가 키 — empty/축소 시 in-flight 취소 후 같은 SSOT로 재평가.
    // previousKey는 스크롤 성공 후에만 커밋. empty early-return은 키를 커밋하지 않음.
    // pendingReset은 itemCount 재실행을 건너뛰므로 취소된 리셋을 비어 있지 않을 때 재발행한다.
    LaunchedEffect(filterKey, itemCount) {
        val items = latestAudioItems
        if (items.isEmpty()) {
            return@LaunchedEffect
        }
        val decision = resolveListenListScrollDecision(
            previousKey = scrollGate.previousKey,
            currentKey = filterKey,
            itemCount = items.size,
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
        )
        val scrollIndex = listenListScrollApplyIndex(decision, scrollGate.pendingReset)
        if (scrollIndex != null) {
            listState.scrollToItem(scrollIndex)
        }
        scrollGate.previousKey = filterKey
        scrollGate.pendingReset = false
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = if (selectionMode && showSelectionTopBar) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        snackbarHost = snackbarHost,
        topBar = {
            if (selectionMode && showSelectionTopBar) {
                RecordingsListSelectionTopBar(
                    selectedCount = selectedIds.size,
                    onCancelClick = onExitSelectionMode,
                )
            }
        },
        bottomBar = {
            if (selectionMode) {
                AccentCtaButton(
                    text = convertLabel,
                    onClick = {
                        onConvertAudioItems(audioItems.filter { it.id in selectedIds })
                    },
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("recordings_list_convert_button"),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            SegmentedControl(
                options = listOf(filterMyRecordingsLabel, filterAllLabel),
                selectedIndex = listenFilterToIndex(audioFilter),
                onSelect = { index ->
                    onAudioFilterChange(listenFilterFromIndex(index))
                },
                enabled = !selectionMode,
                optionTestTags = listOf(
                    "listen_filter_my_recordings",
                    "listen_filter_all",
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 4.dp)
                    .testTag("listen_filter_segmented_control"),
            )
            if (selectionMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundedIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = selectionClearContentDescription,
                            onClick = onExitSelectionMode,
                            modifier = Modifier
                                .testTag("recordings_list_inline_clear_selection_button")
                                .semantics(mergeDescendants = true) {
                                    this.contentDescription = selectionClearContentDescription
                                    role = Role.Button
                                },
                        )
                        if (audioFilter == AudioSourceFilter.All) {
                            SoftChipButton(
                                text = importLabel,
                                onClick = onImportClick,
                                enabled = !navigationBlocked && !selectionMode,
                                modifier = Modifier.testTag("recordings_list_import_button"),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        listenFilterDropdowns()
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        if (audioFilter == AudioSourceFilter.All) {
                            SoftChipButton(
                                text = importLabel,
                                onClick = onImportClick,
                                enabled = !navigationBlocked && !selectionMode,
                                modifier = Modifier.testTag("recordings_list_import_button"),
                            )
                        }
                    }
                    listenFilterDropdowns()
                }
            }
            if (audioItems.isEmpty()) {
                Text(
                    text = emptyLabel,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("recordings_empty_state"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = C2vTheme.colors.ink3,
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("recordings_list"),
                ) {
                    items(audioItems, key = { it.id }) { item ->
                        val isSelected = item.id in selectedIds
                        val isAlreadyConverted = item.uri.toString() in convertedAudioUris
                        val isRecording = recordingIdFromAudioItemId(item.id) != null
                        AudioListItem(
                            item = item,
                            isSelectionMode = selectionMode,
                            isSelected = isSelected,
                            isAlreadyConverted = isAlreadyConverted,
                            isRecording = isRecording,
                            isMenuOpen = expandedMenuId == item.id,
                            onClick = { onAudioItemClick(item) },
                            onLongClick = { onEnterSelectionMode(item.id) },
                            onToggleMenu = { onToggleExpandedMenu(item.id) },
                            onPlayClick = { onPlayClick(item) },
                            onRenameClick = { onRenameClick(item) },
                            onConvertClick = { onConvertAudioItems(listOf(item)) },
                            onDeleteClick = { onDeleteClick(item) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsListTopBar(
    onBackClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.recordings_list_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        navigationIcon = {
            RoundedIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_navigate_back),
                onClick = onBackClick,
                modifier = Modifier
                    .padding(start = 20.dp)
                    .testTag("navigate_back_button"),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsListSelectionTopBar(
    selectedCount: Int,
    onCancelClick: () -> Unit,
) {
    val cancelCd = stringResource(R.string.cd_recording_selection_cancel)
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.recordings_list_selection_title, selectedCount),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("recordings_list_selection_title"),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        navigationIcon = {
            RoundedIconButton(
                icon = Icons.Filled.Close,
                contentDescription = cancelCd,
                onClick = onCancelClick,
                modifier = Modifier
                    .padding(start = 20.dp)
                    .testTag("recordings_list_selection_cancel_button"),
            )
        },
    )
}

/**
 * Listen 목록 행. **본문 탭 gate SSOT**: [AudioListItem] — 비선택·메뉴 닫힘(`!isSelectionMode && !showMenu`)이면
 * onClick 미호출 + semantics `disabled`. 선택모드·메뉴 열림만 본문 탭 반응(토글/collapse).
 * [RecordingsListStateful] `onAudioItemClick`은 selection 토글만; gate 중복 없음.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioListItem(
    item: AudioItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isAlreadyConverted: Boolean,
    isRecording: Boolean,
    isMenuOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleMenu: () -> Unit,
    onPlayClick: () -> Unit,
    onRenameClick: () -> Unit,
    onConvertClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val alreadyConvertedLabel = stringResource(R.string.audio_already_converted_badge)
    val moreCd = stringResource(R.string.cd_listen_audio_item_more)
    val playLabel = stringResource(R.string.cd_play_video)
    val renameLabel = stringResource(R.string.recordings_list_action_rename)
    val convertLabel = stringResource(R.string.recordings_list_convert_action)
    val deleteLabel = stringResource(R.string.recordings_list_action_delete)
    val renameCd = stringResource(R.string.cd_rename_video)
    val deleteCd = stringResource(R.string.cd_delete_video)
    // title 우선: Name 정렬 기준(SortMapping)과 동기화
    val displayName = item.title.ifBlank { item.fileName }.ifBlank { item.uri.lastPathSegment.orEmpty() }.ifBlank { "-" }
    val durationStr = formatMediaDurationMs(item.durationMs, MediaDurationStyle.ListRow)
    val secondaryLine = if (item.artist != null) "$durationStr · ${item.artist}" else durationStr
    val showMenu = isMenuOpen && !isSelectionMode

    C2vCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audio_item_${item.id}"),
        accentBorder = isSelected || showMenu,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (isSelectionMode) {
                    SelectionCheckbox(
                        isSelected = isSelected,
                        modifier = Modifier.testTag("audio_checkbox_${item.id}"),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("audio_item_body_${item.id}")
                        .combinedClickable(
                            onClick = {
                                if (showMenu) {
                                    onToggleMenu()
                                } else if (isSelectionMode) {
                                    onClick()
                                }
                            },
                            onLongClick = onLongClick,
                        )
                        .semantics {
                            if (!isSelectionMode && !showMenu) {
                                disabled()
                            }
                        },
                ) {
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
                if (!isSelectionMode) {
                    ListenAudioMoreButton(
                        isOpen = showMenu,
                        contentDescription = moreCd,
                        onClick = onToggleMenu,
                    )
                }
            }

            if (showMenu) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp),
                    color = C2vTheme.colors.cardBorder,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ListenAudioMenuAction(
                        icon = Icons.Filled.PlayArrow,
                        label = playLabel,
                        contentDescription = playLabel,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        onClick = onPlayClick,
                        modifier = Modifier.weight(1f),
                    )
                    if (isRecording) {
                        ListenAudioMenuAction(
                            icon = Icons.Default.Edit,
                            label = renameLabel,
                            contentDescription = renameCd,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            onClick = onRenameClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ListenAudioMenuAction(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        label = convertLabel,
                        contentDescription = convertLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        onClick = onConvertClick,
                        modifier = Modifier.weight(1f),
                    )
                    ListenAudioMenuAction(
                        icon = Icons.Default.Delete,
                        label = deleteLabel,
                        contentDescription = deleteCd,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.error,
                        onClick = onDeleteClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ListenAudioMoreButton(
    isOpen: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(C2vRadius.control))
            .background(
                if (isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            )
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⋯",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isOpen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListenAudioMenuAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(C2vRadius.avatar))
            .background(containerColor)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SelectionCheckbox(
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
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

private const val TAG = "RecordingsListScreen"

// ── Listen 전용 2옵션 필터 매핑 ────────────────────────────────────────────────
// AudioPick 3옵션 audioSourceFilterToIndex/FromIndex와 분리된 독립 SSOT.
// Files는 Listen UI에 표시되지 않으므로 방어 폴백(0=MyRecordings)만 유지.

/** Listen SegmentedControl 인덱스 → [AudioSourceFilter]. */
private fun listenFilterFromIndex(index: Int): AudioSourceFilter = when (index) {
    1 -> AudioSourceFilter.All
    else -> AudioSourceFilter.MyRecordings
}

/** [AudioSourceFilter] → Listen SegmentedControl 선택 인덱스. Files는 0으로 폴백. */
private fun listenFilterToIndex(filter: AudioSourceFilter): Int = when (filter) {
    AudioSourceFilter.MyRecordings -> 0
    AudioSourceFilter.All -> 1
    AudioSourceFilter.Files -> 0
}
