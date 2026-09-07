package com.example.convert2video.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.R
import com.example.convert2video.data.AudioItem
import com.example.convert2video.shouldBlockNavigationWhileRecordingSaving
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import com.example.convert2video.ui.components.controls.SegmentedControl
import com.example.convert2video.ui.screens.converted_videos.ConvertedVideosHostChrome
import com.example.convert2video.ui.screens.converted_videos.ConvertedVideosHostChromeActions
import com.example.convert2video.ui.screens.converted_videos.ConvertedVideosTabContent
import com.example.convert2video.ui.screens.converted_videos.ConvertedVideosViewModel
import com.example.convert2video.ui.screens.record.RecordScreen
import com.example.convert2video.ui.screens.record.RecordViewModel
import com.example.convert2video.ui.screens.recordings_list.ListenSelectionChrome
import com.example.convert2video.ui.screens.recordings_list.RecordingsListSelectionTopBar
import com.example.convert2video.ui.screens.recordings_list.RecordingsListTabContent
import com.example.convert2video.ui.screens.recordings_list.RecordingsListViewModel
import com.example.convert2video.ui.screens.youtube_upload.YouTubeUploadViewModel
import com.example.convert2video.store.StoreCapabilities
import java.io.File

enum class HomeTab {
    Record,
    Listen,
    ConvertedVideos,
}

private val IdleConvertedVideosHostChrome = ConvertedVideosHostChrome(
    isSelectionMode = false,
    selectedCount = 0,
    isMenuEnabled = true,
    areSelectionActionsEnabled = false,
)

internal fun shouldShowConvertedVideosBatchUpload(capabilities: StoreCapabilities): Boolean =
    capabilities.supportsYouTube

/**
 * 홈 셸 — 녹음·오디오·변환 탭.
 * 표시명(오디오/변환) ≠ enum [HomeTab.Listen]/[HomeTab.ConvertedVideos].
 * 4중 네이밍 KEEP:
 * - Listen: enum [HomeTab.Listen] / val audioTabLabel / key home_tab_listen / testTag home_tab_listen / 표시 Audio·오디오
 * - Converted: enum [HomeTab.ConvertedVideos] / val convertedVideosTabLabel / key home_tab_converted / testTag home_tab_converted_videos / 표시 Converted·변환
 * TopBar titleText는 탭과 동일 val (Contract). a11y 이중 낭독은 후속. titleText val 분리 금지.
 * 기본 chrome: 톱니 없음. Menu는 actions(`open_drawer_button`), enabled=!shellLocked.
 * Record 탭은 [RecordScreen]을, [HomeTab.Listen]은 [RecordingsListTabContent]를,
 * [HomeTab.ConvertedVideos]는 [ConvertedVideosTabContent]를 nested TopBar 없이 임베드한다.
 * Saving 네비게이션 차단 SSOT: [shouldBlockNavigationWhileRecordingSaving].
 *
 * [HomeTab.Listen] 선택 모드 UI SSOT는 이 컴포저블의 [listenSelectionActive]/카운트·콜백 MutableState.
 * 선택 중에는 TopBar만 [RecordingsListSelectionTopBar]로 스왑한다. SegmentedControl 탭 행은 표시 유지
 * (전환은 [shellLocked]로 잠금).
 * [onSelectionChromeChange]는 count 변경·null 전이 때만 state를 쓴다 (chrome 펌프 방지).
 * Activity 미러는 [onListenSelectionActiveChange]로 **동기** 전달 (Convert 네비 전 false 보장).
 *
 * ConvertedVideos 선택 모드 UI SSOT는 [hostChrome]. 선택 중에는 TopBar만 선택 chrome으로 스왑하고
 * SegmentedControl 탭 행은 표시 유지한다. 선택 TopBar를 [chromeActions]에 연결한다. Activity 미러는
 * [onConvertedVideosSelectionActiveChange]로 **동기** 전달
 * ([publishConvertedVideosSelectionActive] 변경 감지 + dispose/탭 이탈 리셋).
 * 셸 잠금은 [HomeTab.Listen] 선택 OR Saving OR [HomeTab.ConvertedVideos] 선택/`!isMenuEnabled` chrome lock.
 * [HomeTab.ConvertedVideos]가 아니면 [hostChrome]을 idle로 되돌린다
 * (Listen [onSelectionChromeChange] null 대칭 — 좀비 lock·죽은 chromeActions 방지).
 *
 * Listen [RecordingsListTabContent.onNavigateHome] → [HomeTab.Record] (루트 NavigateHome 정책).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    selectedTab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    onOpenDrawer: () -> Unit,
    recordViewModel: RecordViewModel,
    recordingsListViewModel: RecordingsListViewModel,
    convertedVideosViewModel: ConvertedVideosViewModel = viewModel(
        factory = ConvertedVideosViewModel.Factory,
    ),
    onRecordingSaved: (File) -> Unit,
    onRecordingSavedWithDuration: ((File, Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onConvertAudioItems: (List<AudioItem>) -> Unit = {},
    onListenSelectionActiveChange: (Boolean) -> Unit = {},
    youTubeViewModel: YouTubeUploadViewModel? =
        if (StoreCapabilities.current.supportsYouTube) viewModel() else null,
    onConvertedVideosSelectionActiveChange: (Boolean) -> Unit = {},
) {
    val uiState by recordViewModel.uiState.collectAsStateWithLifecycle()
    val isSavingGate by recordViewModel.isSavingGate.collectAsStateWithLifecycle()
    val navigationBlocked = shouldBlockNavigationWhileRecordingSaving(
        recordUiState = uiState,
        isSavingGate = isSavingGate,
    )
    var listenSelectionActive by remember { mutableStateOf(false) }
    var selectionSelectedCount by remember { mutableIntStateOf(0) }
    var selectionCancel by remember { mutableStateOf<(() -> Unit)?>(null) }
    val latestCancel by rememberUpdatedState(selectionCancel)
    val latestOnListenSelectionActiveChange by rememberUpdatedState(onListenSelectionActiveChange)
    val latestOnConvertedVideosSelectionActiveChange by rememberUpdatedState(
        onConvertedVideosSelectionActiveChange,
    )

    val snackbarHostState = remember { SnackbarHostState() }
    val chromeActions = remember { ConvertedVideosHostChromeActions() }
    var hostChrome by remember { mutableStateOf(IdleConvertedVideosHostChrome) }
    var convertedVideosSelectionActive by remember { mutableStateOf(false) }

    fun publishListenSelectionActive(active: Boolean) {
        if (listenSelectionActive != active) {
            listenSelectionActive = active
            latestOnListenSelectionActiveChange(active)
        }
    }

    fun publishConvertedVideosSelectionActive(active: Boolean) {
        if (convertedVideosSelectionActive != active) {
            convertedVideosSelectionActive = active
            latestOnConvertedVideosSelectionActiveChange(active)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            publishConvertedVideosSelectionActive(false)
        }
    }

    SideEffect {
        if (selectedTab != HomeTab.ConvertedVideos) {
            if (hostChrome != IdleConvertedVideosHostChrome) {
                hostChrome = IdleConvertedVideosHostChrome
            }
            publishConvertedVideosSelectionActive(false)
        }
    }

    val handleListenSelectionChromeChange = remember {
        { chrome: ListenSelectionChrome? ->
            if (chrome == null) {
                if (listenSelectionActive) {
                    selectionCancel = null
                    selectionSelectedCount = 0
                    publishListenSelectionActive(false)
                }
            } else {
                val entering = !listenSelectionActive
                val countChanged = selectionSelectedCount != chrome.selectedCount
                if (entering || countChanged) {
                    selectionCancel = chrome.onCancelClick
                    if (countChanged) {
                        selectionSelectedCount = chrome.selectedCount
                    }
                    if (entering) {
                        publishListenSelectionActive(true)
                    }
                }
            }
        }
    }
    val handleConvertedVideosHostChromeChange = remember {
        { next: ConvertedVideosHostChrome ->
            if (hostChrome != next) {
                hostChrome = next
                publishConvertedVideosSelectionActive(next.isSelectionMode)
            }
        }
    }

    BackHandler(enabled = navigationBlocked) { }

    val convertedVideosChromeLocked =
        convertedVideosSelectionActive || !hostChrome.isMenuEnabled
    val supportsYouTube = shouldShowConvertedVideosBatchUpload(StoreCapabilities.current)
    val shellLocked = navigationBlocked ||
        listenSelectionActive ||
        convertedVideosChromeLocked

    val cdOpenDrawer = stringResource(R.string.cd_open_drawer)
    val cdClearConvertedVideosSelection = stringResource(
        R.string.converted_videos_clear_selection,
    )
    val cdConvertedVideosBatchUpload = stringResource(R.string.cd_youtube_batch_upload)
    val cdConvertedVideosBatchDelete = stringResource(R.string.converted_videos_batch_delete)
    val recordTabLabel = stringResource(R.string.home_tab_record)
    val audioTabLabel = stringResource(R.string.home_tab_listen)
    val convertedVideosTabLabel = stringResource(R.string.home_tab_converted)
    val convertedVideosSelectionCountText = stringResource(
        R.string.converted_videos_selection_count,
        hostChrome.selectedCount,
    )
    val titleText = when (selectedTab) {
        HomeTab.Record -> recordTabLabel
        HomeTab.Listen -> audioTabLabel
        HomeTab.ConvertedVideos -> convertedVideosTabLabel
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                // TopBar만 스왑 — SegmentedControl 탭 행은 선택 중에도 표시 유지 (전환은 shellLocked)
                if (listenSelectionActive) {
                    RecordingsListSelectionTopBar(
                        selectedCount = selectionSelectedCount,
                        onCancelClick = { latestCancel?.invoke() },
                    )
                } else if (convertedVideosSelectionActive) {
                    TopAppBar(
                        title = {
                            Text(
                                text = convertedVideosSelectionCountText,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                        navigationIcon = {
                            RoundedIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = cdClearConvertedVideosSelection,
                                onClick = { chromeActions.onExitSelectionOrDismissBatch() },
                                modifier = Modifier
                                    .padding(start = 20.dp)
                                    .testTag("converted_videos_clear_selection_button"),
                            )
                        },
                        actions = {
                            if (supportsYouTube) {
                                RoundedIconButton(
                                    icon = Icons.Filled.Share,
                                    contentDescription = cdConvertedVideosBatchUpload,
                                    enabled = hostChrome.areSelectionActionsEnabled,
                                    onClick = { chromeActions.onBatchUploadClick() },
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .testTag("converted_videos_batch_upload_button"),
                                )
                            }
                            RoundedIconButton(
                                icon = Icons.Filled.Delete,
                                contentDescription = cdConvertedVideosBatchDelete,
                                enabled = hostChrome.areSelectionActionsEnabled,
                                onClick = { chromeActions.onBatchDeleteClick() },
                                modifier = Modifier
                                    .padding(end = 20.dp)
                                    .testTag("converted_videos_batch_delete_button"),
                            )
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                titleText,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.testTag("home_shell_title"),
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                        actions = {
                            RoundedIconButton(
                                icon = Icons.Filled.Menu,
                                contentDescription = cdOpenDrawer,
                                onClick = {
                                    if (!shellLocked) {
                                        onOpenDrawer()
                                    }
                                },
                                enabled = !shellLocked,
                                modifier = Modifier
                                    .padding(end = 20.dp)
                                    .testTag("open_drawer_button"),
                            )
                        },
                    )
                }
                SegmentedControl(
                    options = listOf(
                        recordTabLabel,
                        audioTabLabel,
                        convertedVideosTabLabel,
                    ),
                    selectedIndex = when (selectedTab) {
                        HomeTab.Record -> 0
                        HomeTab.Listen -> 1
                        HomeTab.ConvertedVideos -> 2
                    },
                    onSelect = { index ->
                        if (!shellLocked) {
                            val tab = when (index) {
                                0 -> HomeTab.Record
                                1 -> HomeTab.Listen
                                2 -> HomeTab.ConvertedVideos
                                else -> return@SegmentedControl
                            }
                            onSelectTab(tab)
                        }
                    },
                    enabled = !shellLocked,
                    optionTestTags = listOf(
                        "home_tab_record",
                        "home_tab_listen",
                        "home_tab_converted_videos",
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp),
                )
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            HomeTab.Record -> {
                // showTopBar 기본 false — Home outer TopBar만; 목록 CTA 없음([HomeTab.Listen] 오디오)
                RecordScreen(
                    onNavigateBack = {},
                    onOpenDrawer = {},
                    onRecordingSaved = onRecordingSaved,
                    onRecordingSavedWithDuration = onRecordingSavedWithDuration,
                    viewModel = recordViewModel,
                    modifier = Modifier.padding(innerPadding),
                )
            }

            HomeTab.Listen -> {
                RecordingsListTabContent(
                    viewModel = recordingsListViewModel,
                    onNavigateHome = { onSelectTab(HomeTab.Record) },
                    onConvertAudioItems = onConvertAudioItems,
                    navigationBlocked = navigationBlocked,
                    onSelectionChromeChange = handleListenSelectionChromeChange,
                    modifier = Modifier.padding(innerPadding),
                )
            }

            HomeTab.ConvertedVideos -> ConvertedVideosTabContent(
                viewModel = convertedVideosViewModel,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                youTubeViewModel = youTubeViewModel,
                onHostChromeChange = handleConvertedVideosHostChromeChange,
                chromeActions = chromeActions,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}
