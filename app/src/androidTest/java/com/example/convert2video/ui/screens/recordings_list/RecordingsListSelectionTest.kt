package com.example.convert2video.ui.screens.recordings_list

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.AudioItem
import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.recordingAudioItemId
import com.example.convert2video.record.C2vRecordingNames
import com.example.convert2video.record.RecordingController
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.ui.screens.audio_pick.AudioSourceFilter
import com.example.convert2video.ui.screens.converted_videos.convertedVideoRowId
import com.example.convert2video.ui.screens.home.HomeScreen
import com.example.convert2video.ui.screens.home.HomeTab
import com.example.convert2video.ui.screens.record.RecordViewModel
import com.example.convert2video.ui.theme.Convert2videoTheme
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
 * Sprint E — Listen 오디오 아이템 선택 → Convert 프리필 UI 시나리오.
 * 롱프레스로 선택 모드 진입; RecordingRecord가 아닌 AudioItem 기반.
 * HomeScreen 통합 테스트는 시드 후 롱프레스로 선택 진입한다 (선택 미진입 Then 금지).
 */
@RunWith(AndroidJUnit4::class)
class RecordingsListSelectionTest {

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

    private val itemA = AudioItem(
        id = 1L,
        title = "Item A",
        fileName = "(C2V)a.m4a",
        artist = null,
        durationMs = 1_000L,
        uri = Uri.parse("content://test/a"),
    )

    private val itemB = AudioItem(
        id = 2L,
        title = "Item B",
        fileName = "(C2V)b.m4a",
        artist = null,
        durationMs = 2_000L,
        uri = Uri.parse("content://test/b"),
    )

    private val allFilterMediaItem = AudioItem(
        id = 101L,
        title = "Media audio",
        fileName = "media_audio.mp3",
        artist = null,
        durationMs = 3_000L,
        uri = Uri.parse("content://media/external/audio/101"),
    )

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

    // ── Content 직접 주입 테스트 ─────────────────────────────────────────────

    @Test
    fun success_longPress_entersSelectionAndPrefillsId() {
        // Given — 2개 아이템 목록
        setSelectionHost(audioItems = listOf(itemA, itemB))

        // When — 롱프레스로 선택 모드 진입
        composeTestRule.onNodeWithTag("audio_item_1").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Then — 선택 모드 + id=1 체크
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertIsDisplayed()
        composeTestRule.onNodeWithText(selectionCountLabel(1)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_checkbox_1").assertIsOn()
    }

    @Test
    fun success_nonSelectionMode_hidesInlineCancelButton() {
        // Given — 일반 Listen 목록 상태
        setSelectionHost(audioItems = listOf(itemA))

        // Then — 인라인 취소는 선택 모드에서만 존재
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertDoesNotExist()
    }

    @Test
    fun success_inlineCancel_hasButtonSemantics_andCallsExitOnce() {
        // Given — 선택 모드 + 선택된 항목
        var exitCalls = 0
        var convertCalls = 0
        setSelectionHost(
            audioItems = listOf(itemA),
            initialSelectionMode = true,
            initialSelectedIds = setOf(itemA.id),
            onConvertAudioItems = { convertCalls++ },
            onExitSelectionMode = { exitCalls++ },
        )
        val roleButton = SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.Button,
        )
        val cancelContentDescription =
            composeTestRule.activity.getString(R.string.cd_recording_selection_cancel)

        // When / Then — 접근성 의미·활성·click action 확인 후 취소
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assert(roleButton)
            .assert(contentDescriptionMatcher(cancelContentDescription))
            .assert(hasClickAction())
            .performClick()
        composeTestRule.waitForIdle()

        // Then — 단일 취소 경로, Convert 미호출, 선택 UI 전체 제거
        assertEquals(1, exitCalls)
        assertEquals(0, convertCalls)
        assertSelectionUiRemoved(itemA.id)
    }

    @Test
    fun success_selectionMode_tapTogglesCheckbox() {
        // Given — 선택 모드, A만 선택
        setSelectionHost(
            audioItems = listOf(itemA, itemB),
            initialSelectionMode = true,
            initialSelectedIds = setOf(1L),
        )

        // Then — Checkbox 표시
        composeTestRule.onNodeWithTag("audio_checkbox_1").assertIsDisplayed().assertIsOn()
        composeTestRule.onNodeWithTag("audio_checkbox_2").assertIsDisplayed()

        // When — B 카드 탭으로 토글
        composeTestRule.onNodeWithTag("audio_item_2").performClick()
        composeTestRule.waitForIdle()

        // Then — B 체크 + 2개
        composeTestRule.onNodeWithTag("audio_checkbox_2").assertIsOn()
        composeTestRule.onNodeWithText(selectionCountLabel(2)).assertIsDisplayed()

        // When — A·B 해제 (0개여도 모드 유지)
        composeTestRule.onNodeWithTag("audio_item_1").performClick()
        composeTestRule.onNodeWithTag("audio_item_2").performClick()
        composeTestRule.waitForIdle()

        // Then — 선택 모드 유지, 변환 disabled
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertIsDisplayed()
        composeTestRule.onNodeWithText(selectionCountLabel(0)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list_convert_button").assertIsNotEnabled()
    }

    @Test
    fun success_selectionTopBar_showsCountConvertAndCancel() {
        // Given
        setSelectionHost(
            audioItems = listOf(itemA, itemB),
            initialSelectionMode = true,
            initialSelectedIds = setOf(1L, 2L),
        )

        // Then — N개·변환(enabled)·X
        composeTestRule.onNodeWithText(selectionCountLabel(2)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list_convert_button")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithTag("recordings_list_selection_cancel_button")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(convertActionLabel()).assertIsDisplayed()
    }

    @Test
    fun success_convertAction_clearsSelectionBeforeCallback() {
        // Given — 콜백보다 먼저 selectionMode=false (프로덕션 exit→navigate 순서)
        var converted: List<AudioItem> = emptyList()
        var clearedBeforeCallback = false
        setSelectionHost(
            audioItems = listOf(itemA, itemB),
            initialSelectionMode = true,
            initialSelectedIds = setOf(1L, 2L),
            onConvertAudioItems = { items ->
                converted = items
            },
            assertClearedBeforeConvert = { cleared ->
                clearedBeforeCallback = cleared
            },
        )

        // When
        composeTestRule.onNodeWithTag("recordings_list_convert_button").performClick()
        composeTestRule.waitForIdle()

        // Then
        assertEquals(listOf(itemA, itemB), converted)
        assertTrue(clearedBeforeCallback)
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_checkbox_1").assertDoesNotExist()
    }

    @Test
    fun success_selectionMode_keepsImportChip() {
        // Given — 선택 모드
        setSelectionHost(
            audioItems = listOf(itemA, itemB),
            initialSelectionMode = true,
            initialSelectedIds = setOf(1L),
        )

        // Then — import·필터·정렬 표시 유지 + semantics 잠금 (변환 칩과 병존)
        composeTestRule.onNodeWithTag("recordings_list_import_button")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        assertListenFilterLocked()
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertIsDisplayed()
    }

    @Test
    fun success_inlineCancelExitsSelectionMode_myRecordings() {
        // Given — My recordings 선택 모드
        setSelectionHost(
            audioItems = listOf(itemA, itemB),
            audioFilter = AudioSourceFilter.MyRecordings,
            initialSelectionMode = true,
            initialSelectedIds = setOf(itemA.id),
        )
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertIsDisplayed()

        // When — My recordings 아래 인라인 취소 버튼 탭
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .performClick()
        composeTestRule.waitForIdle()

        // Then — 선택 UI 전체 종료
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_checkbox_${itemA.id}").assertDoesNotExist()
        composeTestRule.onNodeWithTag("recordings_list_convert_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertDoesNotExist()

        // When — 다시 B를 롱프레스해 선택 모드 재진입
        composeTestRule.onNodeWithTag("audio_item_${itemB.id}")
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Then — 이전 A 선택 ID가 남지 않음
        composeTestRule.onNodeWithTag("audio_checkbox_${itemB.id}").assertIsOn()
        composeTestRule.onNodeWithTag("audio_checkbox_${itemA.id}").assertIsOff()
    }

    @Test
    fun success_inlineCancelExitsSelectionMode_allFilter_keepsImportAfterward() {
        // Given — All의 실제 source 범위를 대표하는 media AudioItem fixture
        var importClicks = 0
        setSelectionHost(
            audioItems = listOf(allFilterMediaItem),
            initialSelectionMode = true,
            initialSelectedIds = setOf(allFilterMediaItem.id),
            onImportClick = { importClicks++ },
        )
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list_import_button")
            .assertIsDisplayed()
            .assertIsNotEnabled()

        // When
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .performClick()
        composeTestRule.waitForIdle()

        // Then — 선택 종료 후 import chip은 다시 활성화됨
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_checkbox_${allFilterMediaItem.id}")
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("recordings_list_import_button")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, importClicks)
    }

    @Test
    fun success_inlineCancelExitsSelectionMode_whenNothingSelected() {
        // Given — 선택 모드에서 마지막 항목을 해제해 0개가 된 상태
        setSelectionHost(
            audioItems = listOf(itemA),
            initialSelectionMode = true,
            initialSelectedIds = setOf(itemA.id),
        )
        composeTestRule.onNodeWithTag("audio_item_${itemA.id}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(selectionCountLabel(0)).assertIsDisplayed()

        // When
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .performClick()
        composeTestRule.waitForIdle()

        // Then — Convert가 disabled여도 인라인 취소는 선택 모드를 종료함
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertDoesNotExist()
        composeTestRule.onNodeWithTag("recordings_list_convert_button").assertDoesNotExist()
    }

    @Test
    fun success_inlineCancel_remainsEnabled_whenNavigationBlocked() {
        // Given — navigationBlocked=true인 선택 모드
        var exitCalls = 0
        setSelectionHost(
            audioItems = listOf(itemA),
            initialSelectionMode = true,
            initialSelectedIds = setOf(itemA.id),
            navigationBlocked = true,
            onExitSelectionMode = { exitCalls++ },
        )

        // When — 네비게이션 차단 중에도 인라인 취소
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertIsEnabled()
            .performClick()
        composeTestRule.waitForIdle()

        // Then
        assertEquals(1, exitCalls)
        assertSelectionUiRemoved(itemA.id)
    }

    @Test
    fun success_cancelExitsSelectionMode() {
        // Given — X(onExitSelectionMode)
        var exitCalls = 0
        setSelectionHost(
            audioItems = listOf(itemA),
            initialSelectionMode = true,
            initialSelectedIds = setOf(1L),
            onExitSelectionMode = { exitCalls++ },
        )

        // When
        composeTestRule.onNodeWithTag("recordings_list_selection_cancel_button").performClick()
        composeTestRule.waitForIdle()

        // Then
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_checkbox_1").assertDoesNotExist()
        assertEquals(1, exitCalls)
    }

    // ── HomeScreen 통합: 시드 후 롱프레스 선택 ───────────────────────────────

    @Test
    fun success_homeScreen_selectionMode_keepsTabsLocked() {
        // Given — HomeScreen + Listen(My recordings) + 시드 녹음
        val seeded = seedRecording()
        val audioItemId = recordingAudioItemId(seeded.id)
        val activeMirrorTransitions = mutableListOf<Boolean>()
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = HomeTab.Listen,
                onSelectTab = {},
                onOpenDrawer = {},
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
                onListenSelectionActiveChange = { activeMirrorTransitions += it },
            )
        }
        waitForAudioItem(audioItemId)

        // When — 롱프레스 선택 진입
        composeTestRule.onNodeWithTag("audio_item_$audioItemId")
            .assertIsDisplayed()
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Then — My recordings 정책에 맞게 Import 없이 탭·필터 잠금, 선택 UI, 햄버거 없음
        composeTestRule.onNodeWithTag("home_tab_record")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_listen")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_converted_videos")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("recordings_list_import_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertIsDisplayed()
        assertListenFilterLocked()
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_drawer_button").assertDoesNotExist()

        // When — Listen 목록 아래 인라인 취소로 선택 해제
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .performClick()
        composeTestRule.waitForIdle()

        // Then — 탭 3개 enabled + 햄버거 복귀
        assertHomeShellUnlocked()
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertDoesNotExist()
        assertEquals(listOf(true, false), activeMirrorTransitions)
    }

    @Test
    fun success_recordingsListTabContent_inlineCancelPublishesChromeNull() {
        // Given — Stateful Listen 경로 + 실제 녹음 항목
        val seeded = seedRecording()
        val audioItemId = recordingAudioItemId(seeded.id)
        val chromeEvents = mutableListOf<ListenSelectionChrome?>()
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListTabContent(
                    viewModel = recordingsListViewModel,
                    onNavigateHome = {},
                    onSelectionChromeChange = { chromeEvents += it },
                )
            }
        }
        waitForAudioItem(audioItemId)

        // When — 롱프레스 후 실제 목록의 inline 취소 탭
        composeTestRule.onNodeWithTag("audio_item_$audioItemId")
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertTrue(chromeEvents.any { it != null })
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .performClick()
        composeTestRule.waitForIdle()

        // Then — Stateful chrome이 null로 전이되고 inline UI가 제거됨
        assertTrue(chromeEvents.lastOrNull() == null)
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertDoesNotExist()
    }

    @Test
    fun success_homeScreen_convertedVideosSelection_keepsTabsLocked() {
        // Given — HomeScreen + ConvertedVideos + 시드 행
        val rowTag = seedConvertedVideoRowTag()
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = HomeTab.ConvertedVideos,
                onSelectTab = {},
                onOpenDrawer = {},
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
            )
        }
        waitForConvertedVideoItem(rowTag)

        // When — 시드 행 롱프레스
        composeTestRule.onNodeWithTag(rowTag)
            .assertIsDisplayed()
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // Then — 탭 행 표시·잠금, 선택 chrome, 햄버거 없음
        composeTestRule.onNodeWithTag("home_tab_record")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_listen")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("home_tab_converted_videos")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("converted_videos_clear_selection_button")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_drawer_button").assertDoesNotExist()

        // When — 선택 해제
        composeTestRule.onNodeWithTag("converted_videos_clear_selection_button").performClick()
        composeTestRule.waitForIdle()

        // Then — 탭 3개 enabled + 햄버거 복귀
        assertHomeShellUnlocked()
    }

    @Test
    fun success_homeScreen_convertClearsListenMirrorBeforeCallback() {
        // Given — HomeScreen + Listen 시드 + 미러 콜백
        val seeded = seedRecording()
        val audioItemId = recordingAudioItemId(seeded.id)
        var listenMirror = false
        var mirrorWasFalseAtConvert = false
        var convertCalled = false
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = HomeTab.Listen,
                onSelectTab = {},
                onOpenDrawer = {},
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
                onListenSelectionActiveChange = { listenMirror = it },
                onConvertAudioItems = {
                    mirrorWasFalseAtConvert = !listenMirror
                    convertCalled = true
                },
            )
        }
        waitForAudioItem(audioItemId)

        // When — 롱프레스 후 셸 Convert
        composeTestRule.onNodeWithTag("audio_item_$audioItemId")
            .assertIsDisplayed()
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertTrue(listenMirror)
        composeTestRule.onNodeWithTag("recordings_list_convert_button").performClick()
        composeTestRule.waitForIdle()

        // Then — 콜백 시점에 mirror 이미 false, 셸 잠금 해제
        assertTrue(convertCalled)
        assertTrue(mirrorWasFalseAtConvert)
        assertFalse(listenMirror)
        assertHomeShellUnlocked()
    }

    @Test
    fun success_homeScreen_systemBack_exitsSelectionMode() {
        // Given — HomeScreen Listen + 시드
        val seeded = seedRecording()
        val audioItemId = recordingAudioItemId(seeded.id)
        composeTestRule.setContent {
            HomeScreen(
                selectedTab = HomeTab.Listen,
                onSelectTab = {},
                onOpenDrawer = {},
                recordViewModel = recordViewModel,
                recordingsListViewModel = recordingsListViewModel,
                onRecordingSaved = {},
            )
        }
        waitForAudioItem(audioItemId)

        // When — 롱프레스 선택 진입
        composeTestRule.onNodeWithTag("audio_item_$audioItemId")
            .assertIsDisplayed()
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertIsDisplayed()

        // When — 시스템 Back (HomeScreen TabContent BackHandler)
        composeTestRule.runOnIdle {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // Then — 선택 종료 + 셸 잠금 해제
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_checkbox_$audioItemId").assertDoesNotExist()
        assertHomeShellUnlocked()
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private fun selectionCountLabel(count: Int): String =
        composeTestRule.activity.getString(R.string.recordings_list_selection_title, count)

    private fun convertActionLabel(): String =
        composeTestRule.activity.getString(R.string.recordings_list_convert_action)

    private fun contentDescriptionMatcher(expected: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(
            SemanticsProperties.ContentDescription,
            listOf(expected),
        )

    private fun assertSelectionUiRemoved(audioItemId: Long) {
        composeTestRule.onNodeWithTag("recordings_list_selection_title").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_checkbox_$audioItemId").assertDoesNotExist()
        composeTestRule.onNodeWithTag("recordings_list_convert_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("recordings_list_inline_clear_selection_button")
            .assertDoesNotExist()
    }

    private fun setSelectionHost(
        audioItems: List<AudioItem>,
        audioFilter: AudioSourceFilter = AudioSourceFilter.All,
        initialSelectionMode: Boolean = false,
        initialSelectedIds: Set<Long> = emptySet(),
        onConvertAudioItems: (List<AudioItem>) -> Unit = {},
        assertClearedBeforeConvert: (Boolean) -> Unit = {},
        onImportClick: () -> Unit = {},
        navigationBlocked: Boolean = false,
        onExitSelectionMode: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            Convert2videoTheme {
                var selectionMode by remember { mutableStateOf(initialSelectionMode) }
                var selectedIds by remember { mutableStateOf(initialSelectedIds) }
                RecordingsListContent(
                    audioItems = audioItems,
                    convertedAudioUris = emptySet(),
                    audioFilter = audioFilter,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = { items ->
                        val selected = items.ifEmpty {
                            audioItems.filter { it.id in selectedIds }
                        }
                        if (selected.isNotEmpty()) {
                            // 프로덕션과 동일: 콜백보다 먼저 선택 종료
                            selectionMode = false
                            selectedIds = emptySet()
                            assertClearedBeforeConvert(!selectionMode && selectedIds.isEmpty())
                            onConvertAudioItems(selected)
                        }
                    },
                    onAudioItemClick = { item ->
                        if (selectionMode) {
                            selectedIds = if (item.id in selectedIds) {
                                selectedIds - item.id
                            } else {
                                selectedIds + item.id
                            }
                        }
                    },
                    onEnterSelectionMode = { id ->
                        selectionMode = true
                        selectedIds = setOf(id)
                    },
                    onImportClick = onImportClick,
                    snackbarHost = {},
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    showSelectionTopBar = true,
                    navigationBlocked = navigationBlocked,
                    onExitSelectionMode = {
                        selectionMode = false
                        selectedIds = emptySet()
                        onExitSelectionMode()
                    },
                )
            }
        }
    }

    private fun seedRecording(): RecordingRecord {
        val dir = C2vRecordingNames.appStorageDir(app)
        val file = File(dir, "listen_sel_${System.nanoTime()}.m4a")
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
        val file = File(dir, "cv_sel_${System.nanoTime()}.mp4")
        file.writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        seededVideoFiles += file
        val uri = FileProvider.getUriForFile(
            app,
            C2vOutputNames.FILE_PROVIDER_AUTHORITY,
            file,
        )
        return "converted_video_item_${convertedVideoRowId(uri)}"
    }

    private fun assertListenFilterLocked() {
        composeTestRule.onNodeWithTag("listen_filter_segmented_control").assertIsDisplayed()
        composeTestRule.onNodeWithTag("listen_filter_my_recordings")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithTag("listen_filter_all")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        assertDropdownNotEnabled("listen_conversion_filter_dropdown")
        assertDropdownNotEnabled("listen_sort_dropdown")
    }

    private fun assertDropdownNotEnabled(tag: String) {
        composeTestRule.onNodeWithTag(tag).assertIsDisplayed()
        composeTestRule.onNode(
            hasClickAction() and isNotEnabled() and hasAnyAncestor(hasTestTag(tag)),
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun assertHomeShellUnlocked() {
        composeTestRule.onNodeWithTag("home_tab_record")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithTag("home_tab_listen")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithTag("home_tab_converted_videos")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithTag("open_drawer_button")
            .assertIsDisplayed()
            .assertIsEnabled()
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
