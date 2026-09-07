package com.example.convert2video.ui.screens.home

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.recordingAudioItemId
import com.example.convert2video.record.C2vRecordingNames
import com.example.convert2video.record.RecordingController
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.record.RecordingState
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.ui.screens.converted_videos.convertedVideoRowId
import com.example.convert2video.ui.screens.record.RecordViewModel
import com.example.convert2video.ui.screens.recordings_list.RecordingsListViewModel
import com.example.convert2video.video.C2vOutputNames
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Device에 녹음이 있어도 flaky하지 않도록 empty XOR list만 통과.
 * [MainActivityHomeWiringAndroidTest]와 공유 (동일 androidTest 모듈 · internal).
 */
internal fun assertListenContentOrEmpty(rule: ComposeTestRule) {
    rule.waitUntil(timeoutMillis = 10_000) {
        val emptyCount = rule.onAllNodesWithTag("recordings_empty_state")
            .fetchSemanticsNodes().size
        val listCount = rule.onAllNodesWithTag("recordings_list")
            .fetchSemanticsNodes().size
        (emptyCount == 1 && listCount == 0) || (emptyCount == 0 && listCount >= 1)
    }

    val emptyCount = rule.onAllNodesWithTag("recordings_empty_state")
        .fetchSemanticsNodes().size
    val listCount = rule.onAllNodesWithTag("recordings_list")
        .fetchSemanticsNodes().size
    assertTrue(
        "Listen tab must show exactly one of empty XOR list " +
            "(empty=$emptyCount, list=$listCount)",
        (emptyCount == 1 && listCount == 0) || (emptyCount == 0 && listCount >= 1),
    )
}

/**
 * 로딩(negative wait: empty·list 모두 없으면 대기) 종료 후 strict empty XOR list만 통과.
 * [MainActivityHomeWiringAndroidTest] CV 스모크와 공유 (동일 androidTest 모듈 · internal).
 */
internal fun assertConvertedVideosContentOrIdle(rule: ComposeTestRule) {
    val emptyText = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .getString(R.string.converted_videos_empty)

    rule.waitUntil(timeoutMillis = 10_000) {
        convertedVideosContentSettled(rule, emptyText)
    }

    val (emptyCount, listCount) = convertedVideosContentCounts(rule, emptyText)
    assertTrue(
        "ConvertedVideos tab must show exactly one of empty XOR list " +
            "(empty=$emptyCount, list=$listCount)",
        (emptyCount == 1 && listCount == 0) || (emptyCount == 0 && listCount >= 1),
    )
}

private fun convertedVideosContentSettled(rule: ComposeTestRule, emptyText: String): Boolean {
    val (emptyCount, listCount) = convertedVideosContentCounts(rule, emptyText)
    return (emptyCount == 1 && listCount == 0) || (emptyCount == 0 && listCount >= 1)
}

private fun convertedVideosContentCounts(
    rule: ComposeTestRule,
    emptyText: String,
): Pair<Int, Int> {
    val emptyCount = rule.onAllNodesWithText(emptyText).fetchSemanticsNodes().size
    val listCount = convertedVideosListCount(rule, emptyCount)
    return emptyCount to listCount
}

/**
 * empty 표시 중 scroll false-positive 방어: emptyCount > 0 이면 listCount = 0.
 * segment testTag 우선, standalone 목록만 LazyColumn scroll fallback.
 */
private fun convertedVideosListCount(rule: ComposeTestRule, emptyCount: Int): Int {
    if (emptyCount > 0) {
        return 0
    }
    val segmentMarkerCount = rule.onAllNodes(convertedVideosSegmentListMatcher())
        .fetchSemanticsNodes().size
    if (segmentMarkerCount > 0) {
        return segmentMarkerCount
    }
    return rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size
}

private fun convertedVideosSegmentListMatcher(): SemanticsMatcher {
    return SemanticsMatcher("ConvertedVideos segment list marker") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            ?: return@SemanticsMatcher false
        tag.startsWith("converted_videos_segment_expand_") ||
            tag.startsWith("converted_videos_segment_select_all_")
    }
}

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    // GrantPermissionRule의 내부 승인 절차가 composeTestRule의 Activity 기동보다 먼저 끝나야 하므로
    // RuleChain으로 순서를 강제한다 (outer=grant 먼저 적용, inner=composeTestRule) —
    // 순서 미보장 시 승인 Activity가 테스트 Activity를 백그라운드로 보내 나머지 테스트가 연쇄 실패한다.
    private val grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )

    private val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissionRule)
        .around(composeTestRule)

    private lateinit var app: Application
    private lateinit var recordViewModel: RecordViewModel
    private lateinit var recordingsListViewModel: RecordingsListViewModel
    private val seededRecords = mutableListOf<RecordingRecord>()
    private val seededVideoFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as Application
        RecordingController.clearInstanceForTest()
        recordViewModel = RecordViewModel(app)
        recordingsListViewModel = RecordingsListViewModel(app)
    }

    @After
    fun tearDown() {
        RecordingController.clearInstanceForTest()
        if (::app.isInitialized) {
            runBlocking {
                if (seededRecords.isNotEmpty()) {
                    val repo = RecordingRepository(
                        app,
                        AppDatabase.getInstance(app).recordingDao(),
                    )
                    seededRecords.forEach { repo.deleteRecording(it) }
                }
            }
        }
        seededRecords.clear()
        seededVideoFiles.forEach { it.delete() }
        seededVideoFiles.clear()
    }

    private fun setHomeContent(
        selectedTab: HomeTab = HomeTab.Record,
        onSelectTab: (HomeTab) -> Unit = {},
        onOpenDrawer: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                onOpenDrawer = onOpenDrawer,
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
            )
        }
    }

    private fun clickListenFilterAll() {
        composeTestRule.onNodeWithTag("listen_filter_all").performClick()
        composeTestRule.waitForIdle()
    }

    private fun setControllerStateForTest(state: RecordingState) {
        RecordingController.getInstance(app).setStateForTest(state)
    }

    // Given / When / Then — Contract 5 cases

    @Test
    fun success_defaultEntryRecordTabShowsTabsAndEmbeddedRecord() {
        // Given / When — 기본 Record 탭 진입
        setHomeContent(selectedTab = HomeTab.Record)

        // Then — SegmentedControl 탭·Record 선택·임베드 Record 콘텐츠
        composeTestRule.onNodeWithTag("home_tab_record")
            .assertIsDisplayed()
            .assertIsSelected()
        composeTestRule.onNodeWithTag("home_tab_listen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_tab_converted_videos").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("navigate_back_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("record_navigate_recordings_list_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("open_drawer_button")
            .assertIsDisplayed()
            .assertIsEnabled()
        assertHomeSettingsChromeAbsent()
    }

    @Test
    fun success_segmentedControlInvokesOnSelectTab() {
        // Given — stateful host: 탭 전환 시 UI 재구성
        var selected by mutableStateOf(HomeTab.Record)
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = selected,
                onSelectTab = { selected = it },
                onOpenDrawer = {},
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("home_tab_listen").performClick()
        composeTestRule.waitForIdle()

        // Then — 콜백 + Listen UI (empty XOR list) + dual TopBar 없음
        assertEquals(HomeTab.Listen, selected)
        assertListenContentOrEmpty(composeTestRule)
        composeTestRule.onNodeWithTag("navigate_back_button").assertDoesNotExist()
        composeTestRule.onNodeWithText(
            composeTestRule.activity.getString(R.string.recordings_list_title),
        ).assertDoesNotExist()
        composeTestRule.onNodeWithTag("open_drawer_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_tab_listen").assertIsSelected()
        composeTestRule.onNodeWithTag("record_button").assertDoesNotExist()
    }

    @Test
    fun success_segmentedControlSelectsConvertedVideosTab() {
        // Given — stateful host: 탭 전환 시 UI 재구성 (Listen 패턴 대칭)
        var selected by mutableStateOf(HomeTab.Record)
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = selected,
                onSelectTab = { selected = it },
                onOpenDrawer = {},
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
            )
        }

        // When
        composeTestRule.onNodeWithTag("home_tab_converted_videos").performClick()
        composeTestRule.waitForIdle()

        // Then — 콜백 + ConvertedVideos UI (loading 종료 후 empty XOR list) + dual TopBar 없음
        assertEquals(HomeTab.ConvertedVideos, selected)
        assertConvertedVideosContentOrIdle(composeTestRule)
        composeTestRule.onNodeWithTag("navigate_back_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("converted_videos_clear_selection_button")
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("open_drawer_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_tab_converted_videos").assertIsSelected()
        composeTestRule.onNodeWithTag("home_tab_record").assertIsNotSelected()
        composeTestRule.onNodeWithTag("home_tab_listen").assertIsNotSelected()
        composeTestRule.onNodeWithTag("record_button").assertDoesNotExist()
    }

    @Test
    fun success_savingBlocksHomeNavigation_whenStopping() {
        // Given — RecordUiState.Saving (Controller Stopping)
        setControllerStateForTest(RecordingState.Stopping)
        var openedDrawer = false
        var tabSwitched = false
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = HomeTab.Record,
                onSelectTab = { tabSwitched = true },
                onOpenDrawer = { openedDrawer = true },
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
            )
        }
        composeTestRule.waitForIdle()

        // Then — TopBar·SegmentedControl 비활성 + Saving UI
        composeTestRule.onNodeWithTag("open_drawer_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_record").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_listen").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_converted_videos").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("record_saving_state").assertIsDisplayed()
        assertFalse(openedDrawer)
        assertFalse(tabSwitched)

        // When — disabled CV 탭 클릭 시도
        composeTestRule.onNodeWithTag("home_tab_converted_videos").performClick()
        composeTestRule.waitForIdle()

        // Then — 탭 전환 콜백 없음
        assertFalse(tabSwitched)
    }

    @Test
    fun success_savingBlocksHomeNavigation_whenSavingGate() {
        // Given — isSavingGate 레이스 가드 (RecordViewModel test helper)
        var gateDrawer = false
        var gateTabSwitched = false
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = HomeTab.Record,
                onSelectTab = { gateTabSwitched = true },
                onOpenDrawer = { gateDrawer = true },
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
            )
        }
        composeTestRule.runOnIdle {
            recordViewModel.setSavingGateForTest(true)
        }
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithTag("open_drawer_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_record").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_listen").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_converted_videos").assertIsNotEnabled()
        assertFalse(gateDrawer)
        assertFalse(gateTabSwitched)

        // When — disabled CV 탭 클릭 시도 (isSavingGate)
        composeTestRule.onNodeWithTag("home_tab_converted_videos").performClick()
        composeTestRule.waitForIdle()

        // Then
        assertFalse(gateTabSwitched)
    }

    @Test
    fun success_savingConsumesSystemBack() {
        // Given — outer BackHandler는 Saving BackHandler에 가로채여 호출되지 않아야 함
        setControllerStateForTest(RecordingState.Stopping)
        var outerBackCalled = false
        composeTestRule.setContent {
            BackHandler { outerBackCalled = true }
            HomeScreen(
                selectedTab = HomeTab.Record,
                onSelectTab = {},
                onOpenDrawer = {},
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
            )
        }
        composeTestRule.waitForIdle()

        // When
        composeTestRule.runOnIdle {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        // Then
        assertFalse(outerBackCalled)
    }

    @Test
    fun success_listenTab_importChipIsDisplayedAndEnabled() {
        // Given — Listen 탭; import 칩은 시스템 파일 피커를 직접 실행 (HomeScreen 콜백 없음)
        setHomeContent(selectedTab = HomeTab.Listen)
        composeTestRule.waitForIdle()
        clickListenFilterAll()

        // Then — All 필터에서 import chip이 표시되고 활성
        composeTestRule.onNodeWithTag("recordings_list_import_button")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun failure_savingBlocksListenImportChip_whenStopping() {
        // Given — Listen 탭 + Saving (Controller Stopping)
        setControllerStateForTest(RecordingState.Stopping)
        setHomeContent(selectedTab = HomeTab.Listen)
        composeTestRule.waitForIdle()
        clickListenFilterAll()

        // Then — import chip 표시되나 비활성 (탭/드로어와 동일 navigationBlocked)
        composeTestRule.onNodeWithTag("recordings_list_import_button")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun failure_savingBlocksListenImportChip_whenSavingGate() {
        // Given — isSavingGate 레이스 가드
        setHomeContent(selectedTab = HomeTab.Listen)
        composeTestRule.runOnIdle {
            recordViewModel.setSavingGateForTest(true)
        }
        composeTestRule.waitForIdle()
        clickListenFilterAll()

        // Then — import chip 표시되나 비활성
        composeTestRule.onNodeWithTag("recordings_list_import_button")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun success_defaultChromeShowsDrawerWithoutSettingsOnAllTabs() {
        // Given — stateful host: 3탭 기본 chrome (톱니 없음 · 햄버거만)
        var selected by mutableStateOf(HomeTab.Record)
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = selected,
                onSelectTab = { selected = it },
                onOpenDrawer = {},
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
            )
        }

        // Then — Record
        assertUnlockedDefaultDrawerChrome()

        // When / Then — Listen
        composeTestRule.onNodeWithTag("home_tab_listen").performClick()
        composeTestRule.waitForIdle()
        assertUnlockedDefaultDrawerChrome()

        // When / Then — ConvertedVideos
        composeTestRule.onNodeWithTag("home_tab_converted_videos").performClick()
        composeTestRule.waitForIdle()
        assertUnlockedDefaultDrawerChrome()
    }

    @Test
    fun success_unlockedDefaultChromeDrawerClickInvokesOnOpenDrawer() {
        // Given — 언락 기본 chrome (Record)
        var openedDrawer = false
        setHomeContent(
            selectedTab = HomeTab.Record,
            onOpenDrawer = { openedDrawer = true },
        )

        // When
        composeTestRule.onNodeWithTag("open_drawer_button")
            .assertIsEnabled()
            .performClick()
        composeTestRule.waitForIdle()

        // Then — 클릭 시그니처
        assertTrue(openedDrawer)
    }

    @Test
    fun success_listenSelectionHidesOpenDrawerButton() {
        // Given — Listen + 시드 녹음
        val seeded = seedRecording()
        val audioItemId = recordingAudioItemId(seeded.id)
        setHomeContent(selectedTab = HomeTab.Listen)
        waitForAudioItem(audioItemId)

        // When — 롱프레스 선택 모드
        composeTestRule.onNodeWithTag("audio_item_$audioItemId")
            .assertIsDisplayed()
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Then — 선택 TopBar와 인라인 취소 버튼, 햄버거 비침투
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_drawer_button").assertDoesNotExist()

        // When — Listen 목록 아래 인라인 취소 버튼으로 선택 종료
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .performClick()
        composeTestRule.waitForIdle()

        // Then — 선택 UI와 셸 잠금이 해제되고 햄버거 복귀
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertDoesNotExist()
        composeTestRule.onNodeWithTag("open_drawer_button")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun success_convertedVideosSelectionHidesOpenDrawerButton() {
        // Given — ConvertedVideos + 시드 mp4 (잔여 정리 후 해당 파일 tag만)
        val rowTag = seedConvertedVideoRowTag()
        setHomeContent(selectedTab = HomeTab.ConvertedVideos)
        waitForConvertedVideoItem(rowTag)

        // When — 시드 파일 행만 롱프레스
        composeTestRule.onNodeWithTag(rowTag)
            .assertIsDisplayed()
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Then — 선택 TopBar만, 햄버거 비침투
        composeTestRule.onNodeWithTag("converted_videos_clear_selection_button")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_drawer_button").assertDoesNotExist()

        // When — 선택 종료
        composeTestRule.onNodeWithTag("converted_videos_clear_selection_button").performClick()
        composeTestRule.waitForIdle()

        // Then — 햄버거 복귀
        composeTestRule.onNodeWithTag("open_drawer_button")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun success_convertedVideosSelectionUploadActionMatchesCurrentStore() {
        val rowTag = seedConvertedVideoRowTag()
        setHomeContent(selectedTab = HomeTab.ConvertedVideos)
        waitForConvertedVideoItem(rowTag)

        composeTestRule.onNodeWithTag(rowTag)
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        val uploadCount = composeTestRule
            .onAllNodesWithTag("converted_videos_batch_upload_button")
            .fetchSemanticsNodes()
            .size
        if (StoreCapabilities.current.supportsYouTube) {
            assertEquals(1, uploadCount)
        } else {
            assertEquals(0, uploadCount)
        }
        composeTestRule.onNodeWithTag("converted_videos_batch_delete_button").assertIsDisplayed()
    }

    @Test
    fun success_convertedVideoMenuUploadActionMatchesCurrentStore() {
        val rowTag = seedConvertedVideoRowTag()
        setHomeContent(selectedTab = HomeTab.ConvertedVideos)
        waitForConvertedVideoItem(rowTag)

        composeTestRule
            .onNodeWithContentDescription(composeTestRule.activity.getString(R.string.cd_converted_video_more_actions))
            .performClick()
        composeTestRule.waitForIdle()

        val shareLabel = composeTestRule.activity.getString(R.string.converted_video_action_share)
        if (StoreCapabilities.current.supportsYouTube) {
            composeTestRule.onNodeWithText(shareLabel).assertIsDisplayed()
        } else {
            composeTestRule.onNodeWithText(shareLabel).assertDoesNotExist()
        }
    }

    private fun assertUnlockedDefaultDrawerChrome() {
        composeTestRule.onNodeWithTag("open_drawer_button")
            .assertIsDisplayed()
            .assertIsEnabled()
        assertOpenDrawerButtonOnTrailingSide()
        assertHomeSettingsChromeAbsent()
    }

    private fun assertOpenDrawerButtonOnTrailingSide() {
        val titleBounds = composeTestRule.onNodeWithTag("home_shell_title")
            .fetchSemanticsNode()
            .boundsInRoot
        val buttonBounds = composeTestRule.onNodeWithTag("open_drawer_button")
            .fetchSemanticsNode()
            .boundsInRoot
        val rootWidth = composeTestRule.onRoot()
            .fetchSemanticsNode()
            .boundsInRoot
            .width
        val isRightOfTitle = buttonBounds.left > titleBounds.right
        val isRightHalf = buttonBounds.left > rootWidth / 2f
        assertTrue(
            "open_drawer_button must be trailing of title " +
                "(button.left=${buttonBounds.left}, title.right=${titleBounds.right}, " +
                "half=$rootWidth/2) — navigationIcon 배치면 실패",
            isRightOfTitle && isRightHalf,
        )
    }

    private fun assertHomeSettingsChromeAbsent() {
        val settingsCdCandidates = listOf(
            composeTestRule.activity.getString(R.string.options_title),
            composeTestRule.activity.getString(R.string.drawer_options),
        )
        settingsCdCandidates.forEach { cd ->
            composeTestRule.onNodeWithContentDescription(cd).assertDoesNotExist()
        }
    }

    private fun seedRecording(): RecordingRecord {
        val dir = C2vRecordingNames.appStorageDir(app)
        val file = File(dir, "home_chrome_listen_${System.nanoTime()}.m4a")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val recorded = runBlocking {
            RecordingRepository(
                app,
                AppDatabase.getInstance(app).recordingDao(),
            ).recordFinishedRecording(
                file = file,
                format = RecordingFormat.AAC,
                durationMs = 1_000L,
            )
        }
        seededRecords += recorded
        recordingsListViewModel = RecordingsListViewModel(app)
        return recorded
    }

    private fun seedConvertedVideoRowTag(): String {
        val dir = C2vOutputNames.appStorageDir(app)
        dir.listFiles()?.forEach { leftover ->
            if (leftover.isFile && leftover.name.endsWith(".mp4", ignoreCase = true)) {
                leftover.delete()
            }
        }
        val file = File(dir, "home_chrome_sel_${System.nanoTime()}.mp4")
        file.writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        seededVideoFiles += file
        val uri = FileProvider.getUriForFile(
            app,
            C2vOutputNames.FILE_PROVIDER_AUTHORITY,
            file,
        )
        return "converted_video_item_${convertedVideoRowId(uri)}"
    }

    private fun waitForAudioItem(audioItemId: Long) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag("audio_item_$audioItemId")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForConvertedVideoItem(rowTag: String) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(rowTag)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
