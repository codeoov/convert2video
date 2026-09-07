package com.example.convert2video.ui.screens.converted_videos

/**
 * 변환 결과 탭 본문 + Home HostChrome 헬퍼.
 *
 * [ConvertedVideosTabContent]와 [ConvertedVideosHostChrome]·[hasConvertedVideosPendingDialog]·
 * [isConvertedVideosChromeEnabled] 등을 담는다. Part 5에서 전체 화면 [ConvertedVideosScreen]
 * 래퍼 Composable은 제거되었고, Home 탭에 임베드되는 TabContent만 유지한다. (파일명은 레거시 호환으로 유지.)
 */
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.R
import com.example.convert2video.data.ConvertedVideo
import com.example.convert2video.data.FORBIDDEN_DISPLAY_NAME_CHARS
import com.example.convert2video.data.MAX_DISPLAY_NAME_STEM_LENGTH
import com.example.convert2video.data.isValidDisplayNameStem
import com.example.convert2video.ui.components.badges.StatusBadge
import com.example.convert2video.ui.components.buttons.SoftIconButton
import com.example.convert2video.ui.components.cards.C2vCard
import com.example.convert2video.ui.components.controls.DropdownSelector
import com.example.convert2video.ui.components.controls.SegmentedControl
import com.example.convert2video.ui.screens.youtube_upload.YouTubeBatchUploadItem
import com.example.convert2video.ui.screens.youtube_upload.YouTubeUploadUiState
import com.example.convert2video.ui.screens.youtube_upload.YouTubeUploadViewModel
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.ui.shared.AudioRowBody
import com.example.convert2video.ui.shared.MediaDurationStyle
import com.example.convert2video.ui.shared.formatMediaDurationMs
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme
import kotlinx.coroutines.launch

private const val GOOGLE_ACCOUNT_TYPE = "com.google"

/** Google 계정 선택기 Intent — Options와 동일 패턴(공통 추출 금지, GET_ACCOUNTS 불필요). */
@Suppress("DEPRECATION")
private fun newGoogleAccountPickerIntent() = AccountManager.newChooseAccountIntent(
    null,
    null,
    arrayOf(GOOGLE_ACCOUNT_TYPE),
    null,
    null,
    null,
    null,
)

data class ConvertedVideosHostChrome(
    val isSelectionMode: Boolean,
    val selectedCount: Int,
    val isMenuEnabled: Boolean,
    val areSelectionActionsEnabled: Boolean,
)

/** Home selection TopBar 액션 홀더. HostChrome equals와 분리 — 람다는 클릭 시 필드 lookup. */
class ConvertedVideosHostChromeActions {
    var onExitSelectionOrDismissBatch: () -> Unit = {}
    var onBatchUploadClick: () -> Unit = {}
    var onBatchDeleteClick: () -> Unit = {}
}

internal fun shouldShowConvertedVideosUploadUi(capabilities: StoreCapabilities): Boolean =
    capabilities.supportsYouTube

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConvertedVideosTabContent(
    viewModel: ConvertedVideosViewModel,
    modifier: Modifier = Modifier,
    youTubeViewModel: YouTubeUploadViewModel? =
        if (shouldShowConvertedVideosUploadUi(StoreCapabilities.current)) viewModel() else null,
    onHostChromeChange: (ConvertedVideosHostChrome) -> Unit,
    chromeActions: ConvertedVideosHostChromeActions,
    snackbarHostState: SnackbarHostState,
) {
    val listRows by viewModel.listRows.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.sourceFilter.collectAsStateWithLifecycle()
    val convertedSortOrder by viewModel.convertedSortOrder.collectAsStateWithLifecycle()
    val flatVideos = remember(listRows) { listRows.flatVideoItems() }
    val flatUriKeys = remember(flatVideos) {
        flatVideos.map { it.video.uri.toString() }.toSet()
    }
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isAuthorized by if (youTubeViewModel != null) {
        youTubeViewModel.isAuthorized.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(false) }
    }
    val channelTitle by if (youTubeViewModel != null) {
        youTubeViewModel.channelTitle.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<String?>(null) }
    }
    val todayUploadCount by if (youTubeViewModel != null) {
        youTubeViewModel.todayUploadCount.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(0) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingDelete by remember { mutableStateOf<ConvertedVideo?>(null) }
    var pendingRename by remember { mutableStateOf<ConvertedVideo?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var pendingUpload by remember { mutableStateOf<ConvertedVideo?>(null) }
    var selectedUriKeys by remember { mutableStateOf(setOf<String>()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var pendingBatchDelete by remember { mutableStateOf<Set<String>?>(null) }
    /** 일괄 업로드 폼(제목 입력)용 URI 집합. */
    var pendingBatchUpload by remember { mutableStateOf<Set<String>?>(null) }
    /** enqueue 이후 진행/결과 요약 다이얼로그용 URI 집합. */
    var trackingBatchUpload by remember { mutableStateOf<Set<String>?>(null) }
    /** 펼친 세그먼트 그룹. 기본은 접힘(빈 집합). */
    var expandedSegmentBatchIds by remember { mutableStateOf(setOf<String>()) }
    /** ⋯ 메뉴가 펼쳐진 항목의 uri 키. 한 번에 하나만 열린다. */
    var expandedMenuUriKey by remember { mutableStateOf<String?>(null) }

    fun publishHostChrome() {
        val hasPendingDialog = hasConvertedVideosPendingDialog(
            pendingDelete = pendingDelete,
            pendingRename = pendingRename,
            pendingUpload = pendingUpload,
            pendingBatchDelete = pendingBatchDelete,
            pendingBatchUpload = pendingBatchUpload,
            trackingBatchUpload = trackingBatchUpload,
        )
        val next = ConvertedVideosHostChrome(
            isSelectionMode = isSelectionMode,
            selectedCount = selectedUriKeys.size,
            isMenuEnabled = isConvertedVideosChromeEnabled(
                pendingDelete = pendingDelete,
                pendingRename = pendingRename,
                pendingUpload = pendingUpload,
                pendingBatchDelete = pendingBatchDelete,
                pendingBatchUpload = pendingBatchUpload,
                trackingBatchUpload = trackingBatchUpload,
                isSelectionMode = isSelectionMode,
            ),
            areSelectionActionsEnabled = areConvertedVideosSelectionActionsEnabled(
                selectedCount = selectedUriKeys.size,
                hasPendingDialog = hasPendingDialog,
            ),
        )
        onHostChromeChange(next)
    }

    fun setPendingDelete(value: ConvertedVideo?) {
        pendingDelete = value
        publishHostChrome()
    }

    fun setPendingRename(value: ConvertedVideo?) {
        pendingRename = value
        publishHostChrome()
    }

    fun setPendingUpload(value: ConvertedVideo?) {
        pendingUpload = value
        publishHostChrome()
    }

    fun setPendingBatchDelete(value: Set<String>?) {
        pendingBatchDelete = value
        publishHostChrome()
    }

    fun setPendingBatchUpload(value: Set<String>?) {
        pendingBatchUpload = value
        publishHostChrome()
    }

    fun setTrackingBatchUpload(value: Set<String>?) {
        trackingBatchUpload = value
        publishHostChrome()
    }

    fun clearSelection() {
        // 단일 스냅샷 + publish 1회 (필드별 setter 연쇄 금지)
        selectedUriKeys = emptySet()
        isSelectionMode = false
        pendingBatchUpload = null
        pendingBatchDelete = null
        publishHostChrome()
    }

    fun exitSelectionOrDismissBatch() {
        when {
            pendingBatchDelete != null -> setPendingBatchDelete(null)
            pendingBatchUpload != null -> setPendingBatchUpload(null)
            else -> clearSelection()
        }
    }

    fun enterSelectionMode(uri: Uri) {
        isSelectionMode = true
        selectedUriKeys = setOf(uri.toString())
        expandedMenuUriKey = null
        publishHostChrome()
    }

    fun toggleSelection(uri: Uri) {
        val key = uri.toString()
        val nextKeys = if (key in selectedUriKeys) {
            selectedUriKeys - key
        } else {
            selectedUriKeys + key
        }
        selectedUriKeys = nextKeys
        isSelectionMode = nextKeys.isNotEmpty()
        publishHostChrome()
    }

    fun selectAllInGroup(batchId: String, children: List<ConvertedVideoListItem>) {
        val keys = children.map { it.video.uri.toString() }.toSet()
        val (nextSelection, nextExpanded) = applyGroupSelectAllWithExpandPolicy(
            currentSelection = selectedUriKeys,
            childKeys = keys,
            batchId = batchId,
            expandedBatchIds = expandedSegmentBatchIds,
        )
        selectedUriKeys = nextSelection
        expandedSegmentBatchIds = nextExpanded
        isSelectionMode = nextSelection.isNotEmpty()
        publishHostChrome()
    }

    fun beginRename(video: ConvertedVideo) {
        renameInput = video.displayName.removeSuffix(".mp4")
        setPendingRename(video)
    }

    fun dismissRename() {
        renameInput = ""
        setPendingRename(null)
    }

    fun finishBatchUploadEnqueue(trackingKeys: Set<String>?) {
        // 단일 스냅샷 + publish 1회 (필드별 setter 연쇄 금지)
        pendingBatchUpload = null
        selectedUriKeys = emptySet()
        isSelectionMode = false
        trackingBatchUpload = trackingKeys
        publishHostChrome()
    }

    DisposableEffect(chromeActions) {
        chromeActions.onExitSelectionOrDismissBatch = { exitSelectionOrDismissBatch() }
        chromeActions.onBatchUploadClick = {
            if (youTubeViewModel != null && selectedUriKeys.isNotEmpty()) {
                setPendingBatchUpload(selectedUriKeys)
            }
        }
        chromeActions.onBatchDeleteClick = {
            if (selectedUriKeys.isNotEmpty()) {
                setPendingBatchDelete(selectedUriKeys)
            }
        }
        onDispose {
            chromeActions.onExitSelectionOrDismissBatch = {}
            chromeActions.onBatchUploadClick = {}
            chromeActions.onBatchDeleteClick = {}
        }
    }

    val toggleMenu: (Uri) -> Unit = { uri ->
        val key = uri.toString()
        expandedMenuUriKey = if (expandedMenuUriKey == key) null else key
    }

    val toggleGroupExpanded: (String) -> Unit = { batchId ->
        expandedSegmentBatchIds = if (batchId in expandedSegmentBatchIds) {
            expandedSegmentBatchIds - batchId
        } else {
            expandedSegmentBatchIds + batchId
        }
    }

    val openWatchUrl: (String) -> Unit = { watchUrl ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)))
        } catch (e: ActivityNotFoundException) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.converted_videos_youtube_open_no_app),
                )
            }
        } catch (e: Exception) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.converted_videos_youtube_open_failed),
                )
            }
        }
    }

    val playVideo: (ConvertedVideo) -> Unit = { video ->
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(video.uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.converted_videos_play_no_app),
                )
            }
        } catch (e: Exception) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.converted_videos_play_failed),
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        publishHostChrome()
    }

    // 목록 갱신 시 사라진 URI는 선택·단건/일괄 pending에서 제거.
    // trackingBatchUpload는 prune 제외 — fallbackUriKeys 계약(목록에 없어도 추적 유지).
    LaunchedEffect(flatUriKeys) {
        val nextSelection = syncSelectionWithVisibleKeys(selectedUriKeys, flatUriKeys)
        val nextBatchDelete = prunePendingBatchUriKeys(pendingBatchDelete, flatUriKeys)
        val nextBatchUpload = prunePendingBatchUriKeys(pendingBatchUpload, flatUriKeys)
        val nextTracking = retainTrackingBatchUploadKeys(trackingBatchUpload)
        val nextPendingDelete = prunePendingConvertedVideoIfAbsent(pendingDelete, flatUriKeys)
        val nextPendingRename = prunePendingConvertedVideoIfAbsent(pendingRename, flatUriKeys)
        val nextPendingUpload = prunePendingConvertedVideoIfAbsent(pendingUpload, flatUriKeys)
        val nextMenuUriKey =
            if (expandedMenuUriKey != null && expandedMenuUriKey !in flatUriKeys) {
                null
            } else {
                expandedMenuUriKey
            }

        val selectionChanged = nextSelection != selectedUriKeys
        val batchDeleteChanged = nextBatchDelete != pendingBatchDelete
        val batchUploadChanged = nextBatchUpload != pendingBatchUpload
        val trackingChanged = nextTracking != trackingBatchUpload
        val deleteChanged = nextPendingDelete != pendingDelete
        val renameChanged = nextPendingRename != pendingRename
        val uploadChanged = nextPendingUpload != pendingUpload
        val menuChanged = nextMenuUriKey != expandedMenuUriKey
        if (!selectionChanged && !batchDeleteChanged && !batchUploadChanged && !trackingChanged &&
            !deleteChanged && !renameChanged && !uploadChanged && !menuChanged
        ) {
            return@LaunchedEffect
        }

        if (selectionChanged) {
            selectedUriKeys = nextSelection
            if (nextSelection.isEmpty()) {
                isSelectionMode = false
            }
        }
        if (batchDeleteChanged) {
            pendingBatchDelete = nextBatchDelete
        }
        if (batchUploadChanged) {
            pendingBatchUpload = nextBatchUpload
        }
        if (trackingChanged) {
            trackingBatchUpload = nextTracking
        }
        if (deleteChanged) {
            pendingDelete = nextPendingDelete
        }
        if (uploadChanged) {
            pendingUpload = nextPendingUpload
        }
        if (menuChanged) {
            expandedMenuUriKey = nextMenuUriKey
        }
        if (renameChanged && nextPendingRename == null) {
            dismissRename()
            return@LaunchedEffect
        }
        if (renameChanged) {
            pendingRename = nextPendingRename
        }
        publishHostChrome()
    }

    // expandedSegmentBatchIds ∩ 현재 Group batchId — 사라진 배치 prune
    val currentGroupBatchIds = remember(listRows) {
        listRows.mapNotNull { row ->
            (row as? ConvertedVideosListRow.Group)?.segmentBatchId
        }.toSet()
    }
    LaunchedEffect(currentGroupBatchIds) {
        val pruned = pruneExpandedSegmentBatchIds(expandedSegmentBatchIds, currentGroupBatchIds)
        if (pruned != expandedSegmentBatchIds) {
            expandedSegmentBatchIds = pruned
        }
    }

    BackHandler(enabled = isSelectionMode) {
        exitSelectionOrDismissBatch()
    }

    val videoDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.confirmVideoDelete(result.resultCode == Activity.RESULT_OK)
    }

    if (youTubeViewModel != null) {
        val authorizationLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { result -> youTubeViewModel.onAuthorizationActivityResult(result.data) }
        val accountPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
                ?: GOOGLE_ACCOUNT_TYPE
            youTubeViewModel.onYouTubeAccountPicked(
                if (result.resultCode == Activity.RESULT_OK) {
                    name?.takeIf { it.isNotBlank() }?.let { Account(it, type) }
                } else {
                    null
                },
            )
        }

        LaunchedEffect(Unit) {
            youTubeViewModel.errorMessage.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }
        LaunchedEffect(Unit) {
            youTubeViewModel.authorizationRequest.collect {
                authorizationLauncher.launch(it)
            }
        }
        LaunchedEffect(Unit) {
            youTubeViewModel.youtubeAccountPickerRequest.collect {
                accountPickerLauncher.launch(newGoogleAccountPickerIntent())
            }
        }
        LaunchedEffect(Unit) {
            youTubeViewModel.refreshAuthState()
            youTubeViewModel.refreshTodayCount()
        }
    }

    // NG4: SharedFlow 수집 — 단발 이벤트이므로 key=Unit으로 구독 유지
    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.videoDeleteConfirmation.collect { request ->
            videoDeleteLauncher.launch(request)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadVideos()
    }

    val filterMyRecordingsLabel = stringResource(R.string.audio_pick_filter_my_recordings)
    val filterAllLabel = stringResource(R.string.audio_pick_filter_all)
    val sortTimeLabel = stringResource(R.string.recordings_list_sort_time)
    val sortNameLabel = stringResource(R.string.recordings_list_sort_name)
    val sortDurationLabel = stringResource(R.string.recordings_list_sort_duration)
    val sortOptions = remember(sortTimeLabel, sortNameLabel, sortDurationLabel) {
        listOf(sortTimeLabel, sortNameLabel, sortDurationLabel)
    }

    Column(modifier = modifier.fillMaxSize()) {
        SegmentedControl(
            options = listOf(filterMyRecordingsLabel, filterAllLabel),
            selectedIndex = convertedSourceFilterToIndex(sourceFilter),
            onSelect = { index ->
                viewModel.setSourceFilter(convertedSourceFilterFromIndex(index))
            },
            enabled = !isSelectionMode,
            optionTestTags = listOf(
                "converted_filter_my_recordings",
                "converted_filter_all",
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 4.dp)
                .testTag("converted_filter_segmented_control"),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DropdownSelector(
                options = sortOptions,
                selectedIndex = when (convertedSortOrder) {
                    ConvertedVideosSortOrder.Time -> 0
                    ConvertedVideosSortOrder.Name -> 1
                    ConvertedVideosSortOrder.Duration -> 2
                },
                onSelect = { index ->
                    viewModel.setSortOrder(
                        when (index) {
                            0 -> ConvertedVideosSortOrder.Time
                            1 -> ConvertedVideosSortOrder.Name
                            2 -> ConvertedVideosSortOrder.Duration
                            else -> ConvertedVideosSortOrder.Time
                        },
                    )
                },
                enabled = !isSelectionMode,
                testTag = "converted_sort_dropdown",
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                listRows.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.converted_videos_empty),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> {
                    // Upload collect 상한: composition되는 행만 uploadStateFor 구독.
                    // 접힌 그룹 자식은 items 미포함 → 미구독. Group/Standalone은 공통 RowSlot.
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listRows.forEach { row ->
                            when (row) {
                                is ConvertedVideosListRow.Group -> {
                                    val isExpanded = row.segmentBatchId in expandedSegmentBatchIds
                                    val selectedChildCount = row.children.count {
                                        it.video.uri.toString() in selectedUriKeys
                                    }
                                    item(key = "group:${row.segmentBatchId}") {
                                        ConvertedVideosSegmentGroupHeader(
                                            group = row,
                                            isExpanded = isExpanded,
                                            selectedChildCount = selectedChildCount,
                                            onToggleExpanded = {
                                                toggleGroupExpanded(row.segmentBatchId)
                                            },
                                            onSelectAll = {
                                                selectAllInGroup(row.segmentBatchId, row.children)
                                            },
                                        )
                                    }
                                    if (isExpanded) {
                                        items(
                                            items = row.children,
                                            key = { child -> "video:${child.video.uri}" },
                                        ) { child ->
                                            ConvertedVideoListRowSlot(
                                                item = child,
                                                youTubeViewModel = youTubeViewModel,
                                                isSelectionMode = isSelectionMode,
                                                isSelected = child.video.uri.toString() in selectedUriKeys,
                                                isMenuOpen = expandedMenuUriKey == child.video.uri.toString(),
                                                onItemClick = {
                                                    if (isSelectionMode) {
                                                        toggleSelection(child.video.uri)
                                                    }
                                                },
                                                onItemLongClick = {
                                                    if (!isSelectionMode) {
                                                        enterSelectionMode(child.video.uri)
                                                    }
                                                },
                                                onToggleMenu = { toggleMenu(child.video.uri) },
                                                onPlayClick = { playVideo(child.video) },
                                                onDeleteClick = { setPendingDelete(child.video) },
                                                onRenameClick = { beginRename(child.video) },
                                                onUploadClick = {
                                                    if (youTubeViewModel != null) setPendingUpload(child.video)
                                                },
                                                onWatchUrlClick = if (youTubeViewModel != null) {
                                                    openWatchUrl
                                                } else {
                                                    {}
                                                },
                                            )
                                        }
                                    }
                                }
                                is ConvertedVideosListRow.Standalone -> {
                                    item(key = "video:${row.item.video.uri}") {
                                        val item = row.item
                                        ConvertedVideoListRowSlot(
                                            item = item,
                                            youTubeViewModel = youTubeViewModel,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = item.video.uri.toString() in selectedUriKeys,
                                            isMenuOpen = expandedMenuUriKey == item.video.uri.toString(),
                                            onItemClick = {
                                                if (isSelectionMode) {
                                                    toggleSelection(item.video.uri)
                                                }
                                            },
                                            onItemLongClick = {
                                                if (!isSelectionMode) {
                                                    enterSelectionMode(item.video.uri)
                                                }
                                            },
                                            onToggleMenu = { toggleMenu(item.video.uri) },
                                            onPlayClick = { playVideo(item.video) },
                                            onDeleteClick = { setPendingDelete(item.video) },
                                            onRenameClick = { beginRename(item.video) },
                                            onUploadClick = {
                                                if (youTubeViewModel != null) setPendingUpload(item.video)
                                            },
                                            onWatchUrlClick = if (youTubeViewModel != null) {
                                                openWatchUrl
                                            } else {
                                                {}
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
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { setPendingDelete(null) },
            title = { Text(stringResource(R.string.converted_videos_delete_title)) },
            text = { Text(stringResource(R.string.converted_videos_delete_confirm_one)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteVideo(target)
                    setPendingDelete(null)
                }) { Text(stringResource(R.string.converted_videos_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { setPendingDelete(null) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    pendingBatchDelete?.let { uriKeys ->
        val deleteCount = resolveDialogSelectionCount(uriKeys, flatUriKeys)
        AlertDialog(
            onDismissRequest = { setPendingBatchDelete(null) },
            title = { Text(stringResource(R.string.converted_videos_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.converted_videos_delete_confirm_count, deleteCount),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = deleteCount > 0,
                    onClick = {
                        val urisToDelete = flatVideos
                            .filter { it.video.uri.toString() in uriKeys }
                            .map { it.video.uri }
                        viewModel.deleteVideos(urisToDelete)
                        clearSelection()
                    },
                ) { Text(stringResource(R.string.converted_videos_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { setPendingBatchDelete(null) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    pendingRename?.let { target ->
        val isValidName = isValidDisplayNameStem(renameInput)
        val invalidCharsHint = stringResource(R.string.converted_videos_rename_invalid_chars)
        val tooLongHint = stringResource(
            R.string.converted_videos_rename_too_long,
            MAX_DISPLAY_NAME_STEM_LENGTH,
        )
        // blank 등 기타 무효 — 신규 string 없이 기존 rename_failed 재사용
        val blankOrInvalidHint = stringResource(R.string.converted_videos_rename_failed)
        val supportingMessage: String? = when {
            isValidName -> null
            renameInput.contains(FORBIDDEN_DISPLAY_NAME_CHARS) -> invalidCharsHint
            renameInput.length > MAX_DISPLAY_NAME_STEM_LENGTH -> tooLongHint
            else -> blankOrInvalidHint
        }

        AlertDialog(
            onDismissRequest = { dismissRename() },
            title = { Text(stringResource(R.string.converted_videos_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.converted_videos_rename_label)) },
                    suffix = { Text(".mp4") },
                    singleLine = true,
                    isError = !isValidName,
                    supportingText = supportingMessage?.let { msg -> { Text(msg) } },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = isValidName,
                    onClick = {
                        viewModel.renameVideo(target, renameInput)
                        dismissRename()
                    },
                ) { Text(stringResource(R.string.converted_videos_rename_action)) }
            },
            dismissButton = {
                TextButton(onClick = { dismissRename() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (youTubeViewModel != null) pendingUpload?.let { target ->
        val uploadState by youTubeViewModel.uploadStateFor(target.uri).collectAsStateWithLifecycle()
        YouTubeUploadDialog(
            video = target,
            isAuthorized = isAuthorized,
            channelTitle = channelTitle,
            uploadState = uploadState,
            todayUploadCount = todayUploadCount,
            onDismiss = { setPendingUpload(null) },
            onLoginClick = youTubeViewModel::requestYouTubeAccountPick,
            onSignOutClick = youTubeViewModel::signOut,
            onStartUpload = { title, description, privacyStatus ->
                scope.launch {
                    youTubeViewModel.startUpload(target.uri, title, description, privacyStatus)
                }
            },
            onCancelUpload = { youTubeViewModel.cancelUpload(target.uri) },
            onDismissResult = {
                youTubeViewModel.dismissUploadResult(target.uri)
                setPendingUpload(null)
            },
            onWatchOnYouTube = openWatchUrl,
        )
    }

    if (youTubeViewModel != null) pendingBatchUpload?.let { uriKeys ->
        val selectedVideos = flatVideos
            .filter { it.video.uri.toString() in uriKeys }
            .map { it.video }
        YouTubeBatchUploadFormDialog(
            videos = selectedVideos,
            isAuthorized = isAuthorized,
            channelTitle = channelTitle,
            todayUploadCount = todayUploadCount,
            onDismiss = { setPendingBatchUpload(null) },
            onLoginClick = youTubeViewModel::requestYouTubeAccountPick,
            onSignOutClick = youTubeViewModel::signOut,
            onStartBatchUpload = { items, description, privacyStatus ->
                scope.launch {
                    val result = youTubeViewModel.startBatchUpload(items, description, privacyStatus)
                    finishBatchUploadEnqueue(
                        trackingKeys = if (result.startedCount > 0) uriKeys else null,
                    )
                }
            },
        )
    }

    if (youTubeViewModel != null) trackingBatchUpload?.let { uriKeys ->
        val selectedVideos = flatVideos
            .filter { it.video.uri.toString() in uriKeys }
            .map { it.video }
            .ifEmpty {
                // 목록 새로고침 전에도 URI 기준으로 추적할 수 있도록 최소 ConvertedVideo 구성은 불가 —
                // 화면에 없는 경우 uriKeys만으로 상태 행을 그린다.
                emptyList()
            }
        YouTubeBatchUploadTrackingDialog(
            videos = selectedVideos,
            fallbackUriKeys = uriKeys,
            youTubeViewModel = youTubeViewModel,
            onCancelAll = {
                uriKeys.forEach { key ->
                    youTubeViewModel.cancelUpload(Uri.parse(key))
                }
            },
            onDismiss = {
                uriKeys.forEach { key ->
                    youTubeViewModel.dismissUploadResult(Uri.parse(key))
                }
                setTrackingBatchUpload(null)
            },
            onWatchOnYouTube = openWatchUrl,
        )
    }
}

/**
 * 단건/일괄 pending 다이얼로그가 하나라도 열려 있으면 true.
 *
 * emptySet vs null: 헬퍼는 `!= null`만 본다. emptySet도 다이얼로그 있음.
 * 운영 prune(pendingBatchDelete/Upload)은 빈 키 집합을 **null로 접는다**.
 * trackingBatchUpload는 prune하지 않는다(fallbackUriKeys).
 */
fun hasConvertedVideosPendingDialog(
    pendingDelete: ConvertedVideo? = null,
    pendingRename: ConvertedVideo? = null,
    pendingUpload: ConvertedVideo? = null,
    pendingBatchDelete: Set<String>? = null,
    pendingBatchUpload: Set<String>? = null,
    trackingBatchUpload: Set<String>? = null,
): Boolean = pendingDelete != null ||
    pendingRename != null ||
    pendingUpload != null ||
    pendingBatchDelete != null ||
    pendingBatchUpload != null ||
    trackingBatchUpload != null

/**
 * Home shell drawer/settings lock(HostChrome) 활성 여부.
 * [hasConvertedVideosPendingDialog]이 false이고 선택 모드가 아닐 때만 true.
 */
fun isConvertedVideosChromeEnabled(
    pendingDelete: ConvertedVideo? = null,
    pendingRename: ConvertedVideo? = null,
    pendingUpload: ConvertedVideo? = null,
    pendingBatchDelete: Set<String>? = null,
    pendingBatchUpload: Set<String>? = null,
    trackingBatchUpload: Set<String>? = null,
    isSelectionMode: Boolean = false,
): Boolean = !hasConvertedVideosPendingDialog(
    pendingDelete = pendingDelete,
    pendingRename = pendingRename,
    pendingUpload = pendingUpload,
    pendingBatchDelete = pendingBatchDelete,
    pendingBatchUpload = pendingBatchUpload,
    trackingBatchUpload = trackingBatchUpload,
) && !isSelectionMode

/**
 * 선택 모드 TopBar 액션(업로드/삭제) 활성 여부.
 * 선택이 하나 이상이고 pending 다이얼로그가 없을 때만 true.
 */
fun areConvertedVideosSelectionActionsEnabled(
    selectedCount: Int,
    hasPendingDialog: Boolean,
): Boolean = selectedCount > 0 && !hasPendingDialog

/**
 * 단건 pending(delete/rename/upload)이 [visibleUriKeys]에 없으면 null, 있으면 [pending] 그대로.
 * 이미 null이면 null (no-op). 호출부는 변경 시에만 상태를 갱신한다.
 */
internal fun prunePendingConvertedVideoIfAbsent(
    pending: ConvertedVideo?,
    visibleUriKeys: Set<String>,
): ConvertedVideo? {
    if (pending == null) return null
    return if (pending.uri.toString() in visibleUriKeys) pending else null
}

/**
 * 일괄 pending(delete/upload) 키를 화면 URI와 intersect.
 * 비면 null로 접는다. 내용이 같고 비어 있지 않으면 호출부가 setter를 건너뛴다(단건과 대칭).
 *
 * trackingBatchUpload에는 쓰지 않는다 — [retainTrackingBatchUploadKeys].
 */
internal fun prunePendingBatchUriKeys(
    pendingKeys: Set<String>?,
    visibleUriKeys: Set<String>,
): Set<String>? {
    if (pendingKeys == null) return null
    val next = syncSelectionWithVisibleKeys(pendingKeys, visibleUriKeys)
    return next.takeIf { it.isNotEmpty() }
}

/**
 * trackingBatchUpload는 화면 목록 prune에서 제외한다.
 * [YouTubeBatchUploadTrackingDialog]의 fallbackUriKeys가 목록 새로고침 뒤에도 유지되어야 한다.
 */
internal fun retainTrackingBatchUploadKeys(trackingKeys: Set<String>?): Set<String>? = trackingKeys

@Composable
private fun ConvertedVideosSegmentGroupHeader(
    group: ConvertedVideosListRow.Group,
    isExpanded: Boolean,
    selectedChildCount: Int,
    onToggleExpanded: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val expandCd = stringResource(R.string.cd_converted_videos_segment_expand)
    val collapseCd = stringResource(R.string.cd_converted_videos_segment_collapse)
    val selectAllCd = stringResource(R.string.cd_converted_videos_segment_select_all)
    val selectAllLabel = stringResource(R.string.converted_videos_segment_group_select_all)
    // segmentCount = 매칭된 children.size (허수 segmentTotal 미사용)
    val countLabel = stringResource(
        R.string.converted_videos_segment_group_count,
        group.segmentCount,
    )
    val selectedBadgeLabel = stringResource(
        R.string.converted_videos_segment_selected_badge,
        selectedChildCount,
    )

    C2vCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SoftIconButton(
                icon = if (isExpanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (isExpanded) collapseCd else expandCd,
                onClick = onToggleExpanded,
                modifier = Modifier.testTag(
                    "converted_videos_segment_expand_${group.segmentBatchId}",
                ),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            ) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = C2vTheme.colors.ink3,
                )
            }
            // 접힌 상태에서도 선택 현황 표시 (select-all 자동 expand 보조)
            if (selectedChildCount > 0) {
                StatusBadge(
                    text = selectedBadgeLabel,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .testTag(
                            "converted_videos_segment_selected_badge_${group.segmentBatchId}",
                        )
                        .semantics { contentDescription = selectedBadgeLabel },
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(C2vRadius.chip))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onSelectAll)
                    .padding(horizontal = 13.dp, vertical = 8.dp)
                    .testTag("converted_videos_segment_select_all_${group.segmentBatchId}")
                    .semantics { contentDescription = selectAllCd },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = selectAllLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Group 자식·Standalone 공통 row 슬롯.
 * uploadState 수집은 이 슬롯이 composition될 때만 수행 → 접힌 자식은 미구독.
 */
@Composable
private fun ConvertedVideoListRowSlot(
    item: ConvertedVideoListItem,
    youTubeViewModel: YouTubeUploadViewModel?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isMenuOpen: Boolean,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onToggleMenu: () -> Unit,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameClick: () -> Unit,
    onUploadClick: () -> Unit,
    onWatchUrlClick: (watchUrl: String) -> Unit,
) {
    val uploadUiState by if (youTubeViewModel != null) {
        youTubeViewModel.uploadStateFor(item.video.uri).collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<YouTubeUploadUiState>(YouTubeUploadUiState.Idle) }
    }
    ConvertedVideoItem(
        item = item,
        uploadUiState = uploadUiState,
        supportsYouTube = youTubeViewModel != null,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        isMenuOpen = isMenuOpen,
        onItemClick = onItemClick,
        onItemLongClick = onItemLongClick,
        onToggleMenu = onToggleMenu,
        onPlayClick = onPlayClick,
        onDeleteClick = onDeleteClick,
        onRenameClick = onRenameClick,
        onUploadClick = onUploadClick,
        onWatchUrlClick = onWatchUrlClick,
    )
}

/** internal: ConvertedVideoItemAndroidTest가 직접 합성한다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConvertedVideoItem(
    item: ConvertedVideoListItem,
    uploadUiState: YouTubeUploadUiState,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isMenuOpen: Boolean,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onToggleMenu: () -> Unit,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameClick: () -> Unit,
    onUploadClick: () -> Unit,
    onWatchUrlClick: (watchUrl: String) -> Unit,
    modifier: Modifier = Modifier,
    supportsYouTube: Boolean = true,
) {
    val video = item.video
    val rowId = convertedVideoRowId(video.uri)
    val uploadRecord = item.uploadRecord
    val uploadedBadgeLabel = stringResource(R.string.converted_video_uploaded_badge)
    val uploadingBadgeLabel = stringResource(R.string.converted_video_uploading_badge)
    val watchCd = stringResource(R.string.cd_open_youtube_watch)
    val playLabel = stringResource(R.string.cd_play_video)
    val renameLabel = stringResource(R.string.cd_rename_video)
    val shareLabel = stringResource(R.string.converted_video_action_share)
    val deleteLabel = stringResource(R.string.cd_delete_video)
    val moreCd = stringResource(R.string.cd_converted_video_more_actions)
    val isUploading = uploadUiState is YouTubeUploadUiState.InProgress
    val showMenu = isMenuOpen && !isSelectionMode
    val folderLabel = convertedVideoFolderLabel(video)
    val secondaryLine = remember(video.durationMs, video.sizeBytes) {
        "${formatMediaDurationMs(video.durationMs, MediaDurationStyle.ListRow)} · " +
            formatConvertedVideoSize(video.sizeBytes)
    }
    val talkBackDescription = remember(video.displayName, secondaryLine, folderLabel) {
        listOf(video.displayName, secondaryLine, folderLabel)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }

    C2vCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("converted_video_item_$rowId"),
        accentBorder = showMenu,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onItemClick,
                    onLongClick = onItemLongClick,
                )
                .padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isSelectionMode) {
                    ConvertedVideoSelectionCheckbox(
                        isSelected = isSelected,
                        modifier = Modifier.testTag("converted_video_checkbox_$rowId"),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    AudioRowBody(
                        fileName = video.displayName,
                        secondaryLine = secondaryLine,
                        folderLabel = folderLabel,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = talkBackDescription
                        },
                    )
                    // Semantics-only folder probe — 0-size, TalkBack-hidden.
                    // Visual folder line stays in AudioRowBody; this node is testTag honesty only.
                    Box(
                        modifier = Modifier
                            .size(0.dp)
                            .testTag("converted_video_folder_$rowId")
                            .semantics {
                                text = AnnotatedString(folderLabel)
                                hideFromAccessibility()
                            },
                    )
                    if (isUploading) {
                        StatusBadge(
                            text = uploadingBadgeLabel,
                            modifier = Modifier.semantics {
                                contentDescription = uploadingBadgeLabel
                            },
                        )
                    } else if (supportsYouTube && uploadRecord != null) {
                        StatusBadge(
                            text = uploadedBadgeLabel,
                            onClick = if (!isSelectionMode) {
                                { onWatchUrlClick(uploadRecord.watchUrl) }
                            } else {
                                null
                            },
                            modifier = Modifier.semantics {
                                contentDescription = watchCd
                            },
                        )
                    }
                }
                if (!isSelectionMode) {
                    ConvertedVideoMoreButton(
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
                    ConvertedVideoMenuAction(
                        icon = Icons.Default.PlayArrow,
                        label = playLabel,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        onClick = onPlayClick,
                        modifier = Modifier.weight(1f),
                    )
                    ConvertedVideoMenuAction(
                        icon = Icons.Default.Edit,
                        label = renameLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        onClick = onRenameClick,
                        modifier = Modifier.weight(1f),
                    )
                    if (supportsYouTube) {
                        ConvertedVideoMenuAction(
                            icon = Icons.Default.Share,
                            label = shareLabel,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            onClick = onUploadClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ConvertedVideoMenuAction(
                        icon = Icons.Default.Delete,
                        label = deleteLabel,
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

/** ⋯ 토글 버튼. 메뉴가 열려 있으면 accent 배경으로 강조한다. */
@Composable
private fun ConvertedVideoMoreButton(
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
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
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

/** ⋯ 메뉴가 펼쳐졌을 때 노출되는 재생/이름변경/공유/삭제 액션 셀. */
@Composable
private fun ConvertedVideoMenuAction(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(C2vRadius.avatar))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
        )
    }
}

/**
 * Formats MediaStore / file length as a whole-number MB label.
 *
 * Uses binary MiB (`1024 * 1024`) because [ConvertedVideo.sizeBytes] is the actual
 * MediaStore SIZE / File.length() byte count. This is intentional and distinct
 * from [com.example.convert2video.ui.shared.formatApproxSizePerMinute], which uses
 * decimal `1_000_000` for a recording-bitrate *estimate*. Do not unify the two divisors.
 */
private fun formatConvertedVideoSize(sizeBytes: Long): String {
    val safeBytes = sizeBytes.coerceAtLeast(0L)
    val mb = safeBytes / (1024.0 * 1024.0)
    val rounded = if (safeBytes > 0) Math.round(mb).coerceAtLeast(1L) else 0L
    return "${rounded}MB"
}

@Composable
private fun ConvertedVideoSelectionCheckbox(
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val selectCd = stringResource(R.string.cd_recording_select)
    Box(
        modifier = modifier
            .size(24.dp)
            .border(
                width = 2.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = RoundedCornerShape(4.dp),
            )
            .semantics { contentDescription = selectCd },
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun youtubePrivacyOptions(): List<Pair<String, String>> = listOf(
    "private" to stringResource(R.string.youtube_privacy_private),
    "unlisted" to stringResource(R.string.youtube_privacy_unlisted),
    "public" to stringResource(R.string.youtube_privacy_public),
)

@Composable
private fun YouTubePublicPrivacyWarningDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val title = stringResource(R.string.youtube_privacy_public_warning_title)
    val body = stringResource(R.string.youtube_privacy_public_warning_body)
    val confirmLabel = stringResource(R.string.action_confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("youtube_privacy_public_warning_confirm_button"),
            ) {
                Text(confirmLabel)
            }
        },
    )
}

@Composable
private fun YouTubePrivacyOptionRow(
    value: String,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    publicWarningInfoCd: String,
    onPublicWarningClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
        if (value == "public") {
            SoftIconButton(
                icon = Icons.Filled.Info,
                contentDescription = publicWarningInfoCd,
                onClick = onPublicWarningClick,
                modifier = Modifier.testTag("youtube_privacy_public_warning_info_button"),
            )
        }
    }
}

@Composable
private fun YouTubeUploadDialog(
    video: ConvertedVideo,
    isAuthorized: Boolean,
    channelTitle: String?,
    uploadState: YouTubeUploadUiState,
    todayUploadCount: Int,
    onDismiss: () -> Unit,
    onLoginClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onStartUpload: (title: String, description: String, privacyStatus: String) -> Unit,
    onCancelUpload: () -> Unit,
    onDismissResult: () -> Unit,
    onWatchOnYouTube: (watchUrl: String) -> Unit,
) {
    val failedFallback = stringResource(R.string.youtube_upload_failed_fallback)
    val cancelLabel = stringResource(R.string.action_cancel)
    val confirmLabel = stringResource(R.string.action_confirm)
    when (uploadState) {
        is YouTubeUploadUiState.InProgress -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.youtube_upload_in_progress)) },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { uploadState.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.youtube_batch_item_progress,
                                uploadState.percent,
                            ),
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onCancelUpload) { Text(cancelLabel) }
                },
            )
        }
        is YouTubeUploadUiState.Success -> {
            AlertDialog(
                onDismissRequest = onDismissResult,
                title = { Text(stringResource(R.string.youtube_upload_success_title)) },
                text = { Text(stringResource(R.string.youtube_upload_success_body)) },
                confirmButton = {
                    TextButton(onClick = { onWatchOnYouTube(uploadState.watchUrl) }) {
                        Text(stringResource(R.string.youtube_watch_on_youtube))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissResult) { Text(confirmLabel) }
                },
            )
        }
        is YouTubeUploadUiState.Failed -> {
            val message = uploadState.message.ifBlank { failedFallback }
            AlertDialog(
                onDismissRequest = onDismissResult,
                title = { Text(stringResource(R.string.youtube_upload_failed_title)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = onDismissResult) { Text(confirmLabel) }
                },
            )
        }
        YouTubeUploadUiState.Cancelled -> {
            AlertDialog(
                onDismissRequest = onDismissResult,
                title = { Text(stringResource(R.string.youtube_upload_cancelled_title)) },
                text = { Text(stringResource(R.string.youtube_upload_cancelled_body)) },
                confirmButton = {
                    TextButton(onClick = onDismissResult) { Text(confirmLabel) }
                },
            )
        }
        YouTubeUploadUiState.Idle -> {
            YouTubeUploadFormDialog(
                video = video,
                isAuthorized = isAuthorized,
                channelTitle = channelTitle,
                todayUploadCount = todayUploadCount,
                onDismiss = onDismiss,
                onLoginClick = onLoginClick,
                onSignOutClick = onSignOutClick,
                onStartUpload = onStartUpload,
            )
        }
    }
}

@Composable
private fun YouTubeUploadFormDialog(
    video: ConvertedVideo,
    isAuthorized: Boolean,
    channelTitle: String?,
    todayUploadCount: Int,
    onDismiss: () -> Unit,
    onLoginClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onStartUpload: (title: String, description: String, privacyStatus: String) -> Unit,
) {
    val cancelLabel = stringResource(R.string.action_cancel)
    val signOutLabel = stringResource(R.string.youtube_sign_out)
    val channelChecking = stringResource(R.string.youtube_channel_checking)
    val privacyOptions = youtubePrivacyOptions()

    if (!isAuthorized) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.youtube_upload_title)) },
            text = { Text(stringResource(R.string.youtube_upload_login_required)) },
            confirmButton = {
                TextButton(onClick = onLoginClick) {
                    Text(stringResource(R.string.youtube_login_google))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(cancelLabel) }
            },
        )
        return
    }

    var title by remember(video.uri) { mutableStateOf(video.displayName.removeSuffix(".mp4")) }
    var description by remember(video.uri) { mutableStateOf("") }
    var privacyStatus by remember(video.uri) { mutableStateOf("private") }
    var showPublicPrivacyWarning by rememberSaveable(video.uri) { mutableStateOf(false) }
    val publicWarningInfoCd = stringResource(R.string.cd_youtube_privacy_public_warning_info)

    YouTubePublicPrivacyWarningDialog(
        visible = showPublicPrivacyWarning,
        onDismiss = { showPublicPrivacyWarning = false },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.youtube_upload_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.youtube_field_title)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.youtube_field_description)) },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.youtube_privacy_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    privacyOptions.forEach { (value, label) ->
                        YouTubePrivacyOptionRow(
                            value = value,
                            label = label,
                            selected = privacyStatus == value,
                            onSelect = { privacyStatus = value },
                            publicWarningInfoCd = publicWarningInfoCd,
                            onPublicWarningClick = { showPublicPrivacyWarning = true },
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.youtube_signed_in,
                        channelTitle ?: channelChecking,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.youtube_today_upload_count, todayUploadCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onSignOutClick) { Text(signOutLabel) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onStartUpload(title, description, privacyStatus) },
            ) { Text(stringResource(R.string.youtube_upload_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

@Composable
private fun YouTubeBatchUploadFormDialog(
    videos: List<ConvertedVideo>,
    isAuthorized: Boolean,
    channelTitle: String?,
    todayUploadCount: Int,
    onDismiss: () -> Unit,
    onLoginClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onStartBatchUpload: (List<YouTubeBatchUploadItem>, description: String, privacyStatus: String) -> Unit,
) {
    val cancelLabel = stringResource(R.string.action_cancel)
    val signOutLabel = stringResource(R.string.youtube_sign_out)
    val channelChecking = stringResource(R.string.youtube_channel_checking)
    val privacyOptions = youtubePrivacyOptions()

    if (!isAuthorized) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.youtube_batch_upload_title)) },
            text = { Text(stringResource(R.string.youtube_upload_login_required)) },
            confirmButton = {
                TextButton(onClick = onLoginClick) {
                    Text(stringResource(R.string.youtube_login_google))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(cancelLabel) }
            },
        )
        return
    }

    var titlesByUri by remember(videos) {
        mutableStateOf(
            videos.associate { it.uri.toString() to it.displayName.removeSuffix(".mp4") },
        )
    }
    var description by remember { mutableStateOf("") }
    var privacyStatus by remember { mutableStateOf("private") }
    var showPublicPrivacyWarning by rememberSaveable { mutableStateOf(false) }
    val publicWarningInfoCd = stringResource(R.string.cd_youtube_privacy_public_warning_info)

    YouTubePublicPrivacyWarningDialog(
        visible = showPublicPrivacyWarning,
        onDismiss = { showPublicPrivacyWarning = false },
    )

    val allTitlesValid = videos.all { video ->
        titlesByUri[video.uri.toString()]?.isNotBlank() == true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.youtube_batch_upload_title_count, videos.size))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                videos.forEach { video ->
                    val uriKey = video.uri.toString()
                    OutlinedTextField(
                        value = titlesByUri[uriKey].orEmpty(),
                        onValueChange = { titlesByUri = titlesByUri + (uriKey to it) },
                        label = { Text(stringResource(R.string.youtube_field_title)) },
                        placeholder = { Text(video.displayName) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.youtube_field_description_common)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.youtube_privacy_label_common),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    privacyOptions.forEach { (value, label) ->
                        YouTubePrivacyOptionRow(
                            value = value,
                            label = label,
                            selected = privacyStatus == value,
                            onSelect = { privacyStatus = value },
                            publicWarningInfoCd = publicWarningInfoCd,
                            onPublicWarningClick = { showPublicPrivacyWarning = true },
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.youtube_signed_in,
                        channelTitle ?: channelChecking,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.youtube_today_upload_count, todayUploadCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onSignOutClick) { Text(signOutLabel) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = allTitlesValid && videos.isNotEmpty(),
                onClick = {
                    val items = videos.map { video ->
                        YouTubeBatchUploadItem(
                            videoUri = video.uri,
                            title = titlesByUri[video.uri.toString()].orEmpty(),
                        )
                    }
                    onStartBatchUpload(items, description, privacyStatus)
                },
                modifier = Modifier.testTag("converted_videos_batch_upload_confirm_button"),
            ) { Text(stringResource(R.string.youtube_upload_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

/**
 * 일괄 업로드 enqueue 후 항목별 WorkManager 상태를 모은 진행/결과 요약 다이얼로그.
 * Snackbar만으로 끝내지 않고 단건 다이얼로그와 같이 화면에서 상태를 유지한다.
 */
@Composable
private fun YouTubeBatchUploadTrackingDialog(
    videos: List<ConvertedVideo>,
    fallbackUriKeys: Set<String>,
    youTubeViewModel: YouTubeUploadViewModel,
    onCancelAll: () -> Unit,
    onDismiss: () -> Unit,
    onWatchOnYouTube: (watchUrl: String) -> Unit,
) {
    val failedFallback = stringResource(R.string.youtube_upload_failed_fallback)
    // fallback 키로 한 번 고정 — videos 갱신으로 추적 Uri 집합이 흔들리지 않게 함
    val trackedUris = remember(fallbackUriKeys) {
        val byKey = videos.associateBy { it.uri.toString() }
        fallbackUriKeys.sorted().map { key ->
            byKey[key]?.uri ?: Uri.parse(key)
        }
    }
    val displayNames = remember(videos, trackedUris) {
        val byKey = videos.associate { it.uri.toString() to it.displayName }
        trackedUris.associate { uri ->
            val key = uri.toString()
            key to (byKey[key] ?: key.substringAfterLast('/'))
        }
    }

    // 단일 Map StateFlow 구독 — for 루프 collectAsState 금지
    val statesFlow = remember(trackedUris) { youTubeViewModel.uploadStatesFor(trackedUris) }
    val statesMap by statesFlow.collectAsStateWithLifecycle()
    val states = remember(trackedUris, statesMap) {
        trackedUris.map { uri ->
            uri to (statesMap[uri] ?: YouTubeUploadUiState.Idle)
        }
    }

    val successCount = states.count { it.second is YouTubeUploadUiState.Success }
    val failedCount = states.count { it.second is YouTubeUploadUiState.Failed }
    val cancelledCount = states.count { it.second is YouTubeUploadUiState.Cancelled }
    val allTerminal = states.isNotEmpty() && states.all { (_, state) ->
        state is YouTubeUploadUiState.Success ||
            state is YouTubeUploadUiState.Failed ||
            state is YouTubeUploadUiState.Cancelled
    }
    val anyInProgress = states.any { it.second is YouTubeUploadUiState.InProgress }
    val canDismiss = !anyInProgress
    val cancelLabel = stringResource(R.string.action_cancel)
    val confirmLabel = stringResource(R.string.action_confirm)

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (allTerminal) {
                        R.string.youtube_batch_upload_summary_title
                    } else {
                        R.string.youtube_batch_upload_progress_title
                    },
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (allTerminal) {
                    Text(
                        text = stringResource(
                            R.string.youtube_batch_upload_summary,
                            successCount,
                            failedCount,
                            cancelledCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                states.forEach { (uri, state) ->
                    key(uri.toString()) {
                        val name = displayNames[uri.toString()].orEmpty()
                        val statusText = when (state) {
                            is YouTubeUploadUiState.InProgress ->
                                stringResource(R.string.youtube_batch_item_progress, state.percent)
                            is YouTubeUploadUiState.Success ->
                                stringResource(R.string.youtube_batch_item_success)
                            is YouTubeUploadUiState.Failed ->
                                state.message.ifBlank { failedFallback }
                            YouTubeUploadUiState.Cancelled ->
                                stringResource(R.string.youtube_batch_item_cancelled)
                            YouTubeUploadUiState.Idle ->
                                stringResource(R.string.youtube_batch_item_waiting)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (state is YouTubeUploadUiState.InProgress) {
                                    LinearProgressIndicator(
                                        progress = { state.percent / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                    )
                                }
                            }
                            if (state is YouTubeUploadUiState.Success) {
                                TextButton(onClick = { onWatchOnYouTube(state.watchUrl) }) {
                                    Text(stringResource(R.string.youtube_watch_on_youtube))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (canDismiss) {
                TextButton(onClick = onDismiss) { Text(confirmLabel) }
            }
        },
        dismissButton = {
            if (anyInProgress) {
                TextButton(onClick = onCancelAll) { Text(cancelLabel) }
            }
        },
    )
}

/** Converted SegmentedControl 인덱스 → [ConvertedVideoSourceFilter]. */
private fun convertedSourceFilterFromIndex(index: Int): ConvertedVideoSourceFilter = when (index) {
    1 -> ConvertedVideoSourceFilter.All
    else -> ConvertedVideoSourceFilter.MyRecordings
}

/** [ConvertedVideoSourceFilter] → Converted SegmentedControl 선택 인덱스. */
private fun convertedSourceFilterToIndex(filter: ConvertedVideoSourceFilter): Int = when (filter) {
    ConvertedVideoSourceFilter.MyRecordings -> 0
    ConvertedVideoSourceFilter.All -> 1
}
