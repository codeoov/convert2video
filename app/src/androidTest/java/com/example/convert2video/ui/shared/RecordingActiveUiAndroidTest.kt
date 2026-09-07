package com.example.convert2video.ui.shared

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.convert2video.AppDestination
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.AudioItem
import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.recordingAudioItemId
import com.example.convert2video.isDrawerGesturesEnabled
import com.example.convert2video.record.C2vRecordingNames
import com.example.convert2video.record.RecordingController
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.resolveDrawerSelected
import com.example.convert2video.shouldBlockNavigationWhileRecordingSaving
import com.example.convert2video.ui.screens.convert.ConvertScreen
import com.example.convert2video.ui.screens.convert.ConvertViewModel
import com.example.convert2video.ui.screens.home.HomeScreen
import com.example.convert2video.ui.screens.home.HomeTab
import com.example.convert2video.ui.screens.record.RecordContent
import com.example.convert2video.ui.screens.record.RecordUiState
import com.example.convert2video.ui.screens.record.RecordViewModel
import com.example.convert2video.ui.screens.recordings_list.RecordingsListViewModel
import com.example.convert2video.ui.theme.Convert2videoTheme
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * 통합 UI 스모크 — 주체는 [AppDrawerContent](`ui/shared`).
 * Home/Record는 cross-import로 힌트·Saving 게이트·Drawer 배지를 함께 검증한다.
 *
 * Sprint 2-3/2-4 — 화면 이탈 중 FGS 녹음 UX·Saving Drawer 게이트 회귀.
 * Home hint · 드로어 Home 행 활성 배지 · Saving 중 openDrawer/gestures/Back 실차단.
 */
@RunWith(AndroidJUnit4::class)
class RecordingActiveUiAndroidTest {

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
    private lateinit var convertViewModel: ConvertViewModel
    private lateinit var recordViewModel: RecordViewModel
    private lateinit var recordingsListViewModel: RecordingsListViewModel
    private lateinit var repository: RecordingRepository
    private val seededRecords = mutableListOf<RecordingRecord>()

    private val listenMoreActionsLabel: String
        get() = app.getString(R.string.cd_listen_audio_item_more)

    private val listenConvertActionLabel: String
        get() = app.getString(R.string.recordings_list_convert_action)

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as Application
        RecordingController.clearInstanceForTest()
        convertViewModel = ConvertViewModel(app)
        recordViewModel = RecordViewModel(app)
        repository = RecordingRepository(
            app,
            AppDatabase.getInstance(app).recordingDao(),
        )
        recordingsListViewModel = RecordingsListViewModel(app)
    }

    @After
    fun tearDown() {
        runBlocking {
            seededRecords.toList().forEach { record ->
                runCatching { repository.deleteRecording(record) }
            }
        }
        seededRecords.clear()
        RecordingController.clearInstanceForTest()
    }

    // Given / When / Then

    @Test
    fun success_homeRecordingActive_showsHint() {
        // Given / When
        composeTestRule.setContent {
            ConvertScreen(
                onNavigateToBackgroundPick = {},
                onNavigateToAudioPick = {},
                onNavigateToRecord = {},
                onNavigateToConvertedVideos = {},
                onOpenDrawer = {},
                isRecordingActive = true,
                viewModel = convertViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithTag("home_recording_active_hint").assertIsDisplayed()
    }

    @Test
    fun success_homeRecordingInactive_hidesHint() {
        // Given / When
        composeTestRule.setContent {
            ConvertScreen(
                onNavigateToBackgroundPick = {},
                onNavigateToAudioPick = {},
                onNavigateToRecord = {},
                onNavigateToConvertedVideos = {},
                onOpenDrawer = {},
                isRecordingActive = false,
                viewModel = convertViewModel,
            )
        }

        // Then
        composeTestRule.onNodeWithTag("home_recording_active_hint").assertDoesNotExist()
    }

    @Test
    fun success_drawerRecordingActive_showsBadge() {
        // Given / When — 배지는 Home 행에 표시
        val homeLabel = composeTestRule.activity.getString(R.string.drawer_home)
        val badgeLabel = composeTestRule.activity.getString(R.string.drawer_record_active_badge)
        composeTestRule.setContent {
            AppDrawerContent(
                selected = AppDestination.Home,
                isRecordingActive = true,
                onSelect = {},
            )
        }

        // Then — badge under Home row (drawer_home_item context); 레거시 drawer_record_item 부재
        composeTestRule.onNodeWithTag("drawer_home_item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_record_item").assertDoesNotExist()
        composeTestRule.onNodeWithTag("drawer_record_active_badge").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("$homeLabel, $badgeLabel")
            .assertIsDisplayed()
        composeTestRule.assertDrawerHasNoThemeUi()
    }

    @Test
    fun success_drawerHasNoThemeUi_directComposition() {
        // Given / When — AppDrawerContent only (no Home/Convert chrome)
        composeTestRule.setContent {
            AppDrawerContent(
                selected = AppDestination.Home,
                isRecordingActive = false,
                onSelect = {},
            )
        }

        // Then
        composeTestRule.assertDrawerHasNoThemeUi()
    }

    @Test
    fun success_drawerRecordingInactive_hidesBadge() {
        // Given / When
        composeTestRule.setContent {
            AppDrawerContent(
                selected = AppDestination.Home,
                isRecordingActive = false,
                onSelect = {},
            )
        }

        // Then — 레거시 drawer_record_item 부재 회귀 + theme helper (Options-only)
        composeTestRule.onNodeWithTag("drawer_record_item").assertDoesNotExist()
        composeTestRule.onNodeWithTag("drawer_record_active_badge").assertDoesNotExist()
        composeTestRule.assertDrawerHasNoThemeUi()
    }

    @Test
    fun success_drawerNavRow_selectedSemantics_viaContentDescription() {
        // Given — Home selected; row mergeDescendants contentDescription == label
        val homeLabel = composeTestRule.activity.getString(R.string.drawer_home)
        composeTestRule.setContent {
            AppDrawerContent(
                selected = AppDestination.Home,
                isRecordingActive = false,
                onSelect = {},
            )
        }

        // Then — a11y: find by contentDescription + selected
        composeTestRule
            .onNodeWithContentDescription(homeLabel)
            .assertIsDisplayed()
            .assertIsSelected()
    }

    @Test
    fun success_savingSsot_unifiesUiStateAndGate() {
        // Given / Then — RecordUiState.Saving OR isSavingGate
        assertTrue(
            shouldBlockNavigationWhileRecordingSaving(
                recordUiState = RecordUiState.Saving,
                isSavingGate = false,
            ),
        )
        assertTrue(
            shouldBlockNavigationWhileRecordingSaving(
                recordUiState = RecordUiState.Idle,
                isSavingGate = true,
            ),
        )
        assertTrue(
            shouldBlockNavigationWhileRecordingSaving(
                recordUiState = RecordUiState.Recording(elapsedMs = 1_000L, amplitude = 0),
                isSavingGate = true,
            ),
        )
        assertFalse(
            shouldBlockNavigationWhileRecordingSaving(
                recordUiState = RecordUiState.Recording(elapsedMs = 1_000L, amplitude = 0),
                isSavingGate = false,
            ),
        )
        assertFalse(
            shouldBlockNavigationWhileRecordingSaving(
                recordUiState = RecordUiState.Saved(
                    outputFile = File("/tmp/clip.m4a"),
                    elapsedMs = 1_000L,
                ),
                isSavingGate = false,
            ),
        )
        assertFalse(
            isDrawerGesturesEnabled(
                isDrawerActive = false,
                isRecordingSaving = true,
            ),
        )
        assertFalse(
            isDrawerGesturesEnabled(
                isDrawerActive = true,
                isRecordingSaving = true,
            ),
        )
        assertFalse(
            isDrawerGesturesEnabled(
                isDrawerActive = false,
                isRecordingSaving = false,
            ),
        )
        assertTrue(
            isDrawerGesturesEnabled(
                isDrawerActive = true,
                isRecordingSaving = false,
            ),
        )
    }

    @Test
    fun success_savingBlocksDrawerGesturesAndDestinationChange() {
        // Given — Drawer onSelect가 MainActivity와 동일하게 Saving이면 destination 무시
        var currentDestination by mutableStateOf(AppDestination.Home)
        val isRecordingSaving = true
        composeTestRule.setContent {
            AppDrawerContent(
                selected = currentDestination,
                isRecordingActive = true,
                onSelect = { dest ->
                    if (isRecordingSaving) return@AppDrawerContent
                    currentDestination = dest
                },
            )
        }

        // When — Options 행 탭
        composeTestRule.onNodeWithTag("drawer_options_item").performClick()
        composeTestRule.waitForIdle()

        // Then — destination 변경 없음
        assertEquals(AppDestination.Home, currentDestination)
    }

    @Test
    fun success_drawerSelectChangesDestinationWhenNotSaving() {
        // Given — Saving 아님 → onSelect 정상
        var currentDestination by mutableStateOf(AppDestination.Home)
        val isRecordingSaving = false
        composeTestRule.setContent {
            AppDrawerContent(
                selected = currentDestination,
                isRecordingActive = false,
                onSelect = { dest ->
                    if (isRecordingSaving) return@AppDrawerContent
                    currentDestination = dest
                },
            )
        }

        // When
        composeTestRule.onNodeWithTag("drawer_options_item").performClick()
        composeTestRule.waitForIdle()

        // Then
        assertEquals(AppDestination.Options, currentDestination)
    }

    /**
     * MainActivity와 동일 openDrawer / gesturesEnabled / BackHandler 계약을
     * 상태 주입 Compose로 검증 (pure-only SSOT assert로 끝내지 않음).
     */
    /**
     * RecordContent legacy TopBar (showTopBar=true) onNavigateBack 계약:
     * isSavingGate=true + uiState=Recording 이면 destination 변경 no-op.
     * 제품 경로는 Home 임베드(showTopBar=false) + drawer disable —
     * 이 케이스는 nested TopBar enabled=false SSOT만 검증한다.
     */
    @Test
    fun success_isSavingGateBlocksNavigateBackCallback_whileRecording() {
        // Given — legacy TopBar fixture (제품 Home 임베드는 navigate_back 미표시)
        var currentDestination by mutableStateOf(AppDestination.Home)
        val uiState = RecordUiState.Recording(elapsedMs = 1_000L, amplitude = 0)
        val isSavingGate = true
        val onNavigateBack: () -> Unit = {
            if (!shouldBlockNavigationWhileRecordingSaving(uiState, isSavingGate)) {
                currentDestination = AppDestination.Convert
            }
        }

        composeTestRule.setContent {
            RecordContent(
                uiState = uiState,
                onStart = {},
                onPause = {},
                onResume = {},
                onStop = {},
                onRequestPermission = {},
                onOpenSettings = {},
                onRecordAgain = {},
                onDismissError = {},
                onNavigateBack = onNavigateBack,
                onOpenDrawer = {},
                isSavingGate = isSavingGate,
                isStartEnabled = true,
                showTopBar = true,
            )
        }

        // Then — TopBar 비활성 + 콜백/destination 불변
        composeTestRule.onNodeWithTag("navigate_back_button").assertIsNotEnabled()
        assertEquals(AppDestination.Home, currentDestination)

        // When — 방어적으로 콜백 직접 호출해도 MainActivity와 동일하게 no-op
        composeTestRule.runOnIdle { onNavigateBack() }
        assertEquals(AppDestination.Home, currentDestination)
    }

    @Test
    fun success_savingBlocksOpenDrawerGesturesAndBack_integration() {
        // Given — Saving=true, destination=Options (non-Home Back 복귀 검증용 fixture)
        var currentDestination by mutableStateOf(AppDestination.Options)
        var isRecordingSaving by mutableStateOf(true)

        composeTestRule.setContent {
            MainActivityDrawerScaffold(
                currentDestination = currentDestination,
                onDestinationChange = { currentDestination = it },
                isRecordingSaving = isRecordingSaving,
                isRecordingActive = true,
                onNavigateToLanding = { currentDestination = AppDestination.Home },
            ) { openDrawer, gesturesEnabled, drawerCurrentValue ->
                Column {
                    // AppDestination.name — intentional enum probe for test_destination tag
                    Text(
                        text = currentDestination.name,
                        modifier = Modifier.testTag("test_destination"),
                    )
                    Text(
                        text = if (gesturesEnabled) "gestures_on" else "gestures_off",
                        modifier = Modifier.testTag("test_gestures"),
                    )
                    Text(
                        text = drawerCurrentValue.name,
                        modifier = Modifier.testTag("test_drawer_value"),
                    )
                    Button(
                        onClick = openDrawer,
                        modifier = Modifier.testTag("test_open_drawer"),
                    ) {
                        Text("open")
                    }
                }
            }
        }

        // Then — gestures off
        composeTestRule.onNodeWithTag("test_gestures").assertTextEquals("gestures_off")
        composeTestRule.onNodeWithTag("test_drawer_value").assertTextEquals("Closed")
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Options")

        // When — openDrawer (Saving 중 무시)
        composeTestRule.onNodeWithTag("test_open_drawer").performClick()
        composeTestRule.waitForIdle()

        // Then — drawer 여전히 Closed
        composeTestRule.onNodeWithTag("test_drawer_value").assertTextEquals("Closed")

        // When — 시스템 Back (Saving 중 Home 복귀 금지)
        composeTestRule.runOnIdle {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // Then — destination 유지
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Options")
        assertEquals(AppDestination.Options, currentDestination)

        // When — Saving 해제 후 Back → Home
        composeTestRule.runOnIdle { isRecordingSaving = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Home")
    }

    @Test
    fun success_drawerOnConvertDestination_showsThreeRowsNoConvertRow_integration() {
        // Given — Convert destination; drawer selected=null (non-drawer destination)
        var currentDestination by mutableStateOf(AppDestination.Convert)
        val isRecordingSaving = false
        val homeLabel = composeTestRule.activity.getString(R.string.drawer_home)
        val optionsLabel = composeTestRule.activity.getString(R.string.drawer_options)
        val errorLogLabel = composeTestRule.activity.getString(R.string.drawer_error_log)

        composeTestRule.setContent {
            MainActivityDrawerScaffold(
                currentDestination = currentDestination,
                onDestinationChange = { currentDestination = it },
                isRecordingSaving = isRecordingSaving,
                isRecordingActive = false,
            ) { openDrawer, gesturesEnabled, _ ->
                Column {
                    Text(
                        text = currentDestination.name,
                        modifier = Modifier.testTag("test_destination"),
                    )
                    Text(
                        text = if (gesturesEnabled) "gestures_on" else "gestures_off",
                        modifier = Modifier.testTag("test_gestures"),
                    )
                    Button(
                        onClick = openDrawer,
                        modifier = Modifier.testTag("test_open_drawer"),
                    ) {
                        Text("open")
                    }
                }
            }
        }

        // When — 드로어 열기
        composeTestRule.onNodeWithTag("test_open_drawer").performClick()
        composeTestRule.waitForIdle()

        // Then — Home/Options/ErrorLog만 표시, Convert 행 없음 + theme helper, selected=null → 모두 미선택
        assertDrawerShowsThreeRowsNoConvertRow()
        composeTestRule.assertDrawerHasNoThemeUi()
        composeTestRule
            .onNodeWithContentDescription(homeLabel)
            .assertIsDisplayed()
            .assertIsNotSelected()
        composeTestRule
            .onNodeWithContentDescription(optionsLabel)
            .assertIsDisplayed()
            .assertIsNotSelected()
        composeTestRule
            .onNodeWithContentDescription(errorLogLabel)
            .assertIsDisplayed()
            .assertIsNotSelected()

        // Then — Convert destination·열린 드로어 닫기 제스처 유지
        assertEquals(AppDestination.Convert, currentDestination)
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Convert")
        composeTestRule.onNodeWithTag("test_gestures").assertTextEquals("gestures_on")
    }

    @Test
    fun success_drawerHomeItem_selectsHomeAndDisablesEdgeGestures_integration() {
        // Given — Convert에서 시작; Home seam landing 계약 미러
        var currentDestination by mutableStateOf(AppDestination.Convert)
        val isRecordingSaving = false
        val homeLabel = composeTestRule.activity.getString(R.string.drawer_home)
        val optionsLabel = composeTestRule.activity.getString(R.string.drawer_options)

        composeTestRule.setContent {
            MainActivityDrawerScaffold(
                currentDestination = currentDestination,
                onDestinationChange = { currentDestination = it },
                isRecordingSaving = isRecordingSaving,
                isRecordingActive = false,
            ) { openDrawer, gesturesEnabled, _ ->
                Column {
                    Text(
                        text = currentDestination.name,
                        modifier = Modifier.testTag("test_destination"),
                    )
                    Text(
                        text = if (gesturesEnabled) "gestures_on" else "gestures_off",
                        modifier = Modifier.testTag("test_gestures"),
                    )
                    Button(
                        onClick = openDrawer,
                        modifier = Modifier.testTag("test_open_drawer"),
                    ) {
                        Text("open")
                    }
                }
            }
        }

        // When — 드로어 열기 (Home/Options/ErrorLog 3행만)
        composeTestRule.onNodeWithTag("test_open_drawer").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drawer_home_item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_options_item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_error_log_item").assertIsDisplayed()

        // When — 「홈」 행 탭
        composeTestRule.onNodeWithTag("drawer_home_item").performClick()
        composeTestRule.waitForIdle()

        // Then — Home destination + 엣지 스와이프 비활성(드로어 닫힘)
        assertEquals(AppDestination.Home, currentDestination)
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Home")
        composeTestRule.onNodeWithTag("test_gestures").assertTextEquals("gestures_off")

        // When — 드로어 재오픈 → Home 행 selected
        composeTestRule.onNodeWithTag("test_open_drawer").performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(homeLabel)
            .assertIsDisplayed()
            .assertIsSelected()
        composeTestRule
            .onNodeWithContentDescription(optionsLabel)
            .assertIsDisplayed()
            .assertIsNotSelected()
    }

    @Test
    fun success_drawerErrorLogItem_navigatesToErrorLog_integration() {
        // Given — Home에서 시작
        var currentDestination by mutableStateOf(AppDestination.Home)
        val isRecordingSaving = false
        val errorLogLabel = composeTestRule.activity.getString(R.string.drawer_error_log)

        composeTestRule.setContent {
            MainActivityDrawerScaffold(
                currentDestination = currentDestination,
                onDestinationChange = { currentDestination = it },
                isRecordingSaving = isRecordingSaving,
                isRecordingActive = false,
            ) { openDrawer, _, _ ->
                Column {
                    Text(
                        text = currentDestination.name,
                        modifier = Modifier.testTag("test_destination"),
                    )
                    Button(
                        onClick = openDrawer,
                        modifier = Modifier.testTag("test_open_drawer"),
                    ) {
                        Text("open")
                    }
                }
            }
        }

        // When — ErrorLog 행 탭
        composeTestRule.onNodeWithTag("test_open_drawer").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drawer_error_log_item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_error_log_item").performClick()
        composeTestRule.waitForIdle()

        // Then — ErrorLog destination
        assertEquals(AppDestination.ErrorLog, currentDestination)
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("ErrorLog")

        // When — 드로어 재오픈 → ErrorLog 행 selected
        composeTestRule.onNodeWithTag("test_open_drawer").performClick()
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithContentDescription(errorLogLabel)
            .assertIsDisplayed()
            .assertIsSelected()
    }

    @Test
    fun success_listenSelectionConvert_selectsConvertAndDisablesEdgeGestures_integration() {
        // Given — Listen 탭 + 시드 녹음 + MainActivity 미러 fixture
        val seeded = seedRecording()
        val audioItemId = recordingAudioItemId(seeded.id)
        setListenNavigationFixture(
            initialHomeTab = HomeTab.Listen,
        )
        waitForAudioItem(audioItemId)

        // When — 롱프레스 선택 → 변환
        composeTestRule.onNodeWithTag("audio_item_$audioItemId").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recordings_list_convert_button").performClick()
        composeTestRule.waitForIdle()

        // Then — Convert destination + 엣지 스와이프 비활성(드로어 닫힘)
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Convert")
        composeTestRule.onNodeWithTag("test_gestures").assertTextEquals("gestures_off")
        composeTestRule.runOnIdle {
            assertEquals(
                ConvertViewModel.AudioSourceTab.FilePick,
                convertViewModel.audioSourceTab.value,
            )
        }
        assertSelectedAudioMatchesSeeded(seeded)

        // When — 드로어 열기
        composeTestRule.onNodeWithTag("open_drawer_button").performClick()
        composeTestRule.waitForIdle()

        // Then — Home/Options/ErrorLog 3행만 (Convert 행 없음) + theme helper
        assertDrawerShowsThreeRowsNoConvertRow()
        composeTestRule.assertDrawerHasNoThemeUi()
    }

    @Test
    fun success_listenBodyTap_staysOnHomeNoConvert_integration() {
        // Given — Listen 탭 + 시드 녹음
        val seeded = seedRecording()
        val audioItemId = recordingAudioItemId(seeded.id)
        setListenNavigationFixture(
            initialHomeTab = HomeTab.Listen,
        )
        waitForAudioItem(audioItemId)

        // When — 비선택모드 본문 탭
        composeTestRule.onNodeWithTag("audio_item_body_$audioItemId").performClick()
        composeTestRule.waitForIdle()

        // Then — Home 잔류 (Convert 미진입)
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Home")
    }

    @Test
    fun success_listenImportChipDoesNotNavigateToAudioPick_integration() {
        // Given — Listen 탭; import 클릭 시 Home/Listen 잔류 (AudioPick 미진입)
        val seeded = seedRecording()
        val audioItemId = recordingAudioItemId(seeded.id)
        setListenNavigationFixture(
            initialHomeTab = HomeTab.Listen,
        )
        waitForAudioItem(audioItemId)
        composeTestRule.onNodeWithTag("listen_filter_all").performClick()
        composeTestRule.waitForIdle()

        // When — import 칩 클릭 (시스템 파일 피커 실행 — 테스트에서는 Home 잔류만 검증)
        composeTestRule.onNodeWithTag("recordings_list_import_button").performClick()
        composeTestRule.waitForIdle()

        // Then — Home 잔류 (AudioPick destination 미진입)
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Home")
    }

    @Test
    fun success_dualListenEntryConvert_preservesSharedViewModelState_integration() {
        // Given — Listen 탭 + 시드 녹음; 공유 ConvertViewModel
        val initialVm = convertViewModel
        val seeded = seedRecording()
        val audioItemId = recordingAudioItemId(seeded.id)
        setListenNavigationFixture(
            initialHomeTab = HomeTab.Listen,
        )
        waitForAudioItem(audioItemId)

        // Path A — 롱프레스 선택 → Convert
        composeTestRule.onNodeWithTag("audio_item_$audioItemId").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recordings_list_convert_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Convert")
        composeTestRule.runOnIdle {
            assertSame(initialVm, convertViewModel)
            assertEquals(
                ConvertViewModel.AudioSourceTab.FilePick,
                convertViewModel.audioSourceTab.value,
            )
            assertEquals(1, convertViewModel.selectedAudioList.value.size)
        }
        val pathATitle = convertViewModel.selectedAudioList.value.first().title

        // When — 드로어 Home 복귀
        composeTestRule.onNodeWithTag("open_drawer_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drawer_home_item").performClick()
        composeTestRule.waitForIdle()

        // Then — Home + VM 상태 유지
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Home")
        composeTestRule.runOnIdle {
            assertSame(initialVm, convertViewModel)
            assertEquals(
                ConvertViewModel.AudioSourceTab.FilePick,
                convertViewModel.audioSourceTab.value,
            )
            assertEquals(pathATitle, convertViewModel.selectedAudioList.value.first().title)
        }

        // Path B — 「...」 메뉴 Convert (직접 변환)
        waitForAudioItem(audioItemId)
        composeTestRule.onNodeWithContentDescription(listenMoreActionsLabel).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(listenConvertActionLabel).performClick()
        composeTestRule.waitForIdle()

        // Then — 동일 VM 인스턴스·FilePick 탭·오디오 반영 + 엣지 스와이프 비활성
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Convert")
        composeTestRule.onNodeWithTag("test_gestures").assertTextEquals("gestures_off")
        composeTestRule.runOnIdle {
            assertSame(initialVm, convertViewModel)
            assertEquals(
                ConvertViewModel.AudioSourceTab.FilePick,
                convertViewModel.audioSourceTab.value,
            )
            assertEquals(1, convertViewModel.selectedAudioList.value.size)
            assertEquals(pathATitle, convertViewModel.selectedAudioList.value.first().title)
        }

        // When — 드로어 Home 재복귀
        composeTestRule.onNodeWithTag("open_drawer_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drawer_home_item").performClick()
        composeTestRule.waitForIdle()

        // Then — VM 상태 여전히 유지
        composeTestRule.onNodeWithTag("test_destination").assertTextEquals("Home")
        composeTestRule.runOnIdle {
            assertSame(initialVm, convertViewModel)
            assertEquals(
                ConvertViewModel.AudioSourceTab.FilePick,
                convertViewModel.audioSourceTab.value,
            )
            assertEquals(pathATitle, convertViewModel.selectedAudioList.value.first().title)
        }
    }

    private fun seedRecording(): RecordingRecord {
        val dir = C2vRecordingNames.appStorageDir(app)
        val file = File(dir, "listen_nav_test_${System.nanoTime()}.m4a")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val recorded = runBlocking {
            repository.recordFinishedRecording(
                file = file,
                format = RecordingFormat.AAC,
                durationMs = 1_000L,
            )
        }
        seededRecords += recorded
        recordingsListViewModel = RecordingsListViewModel(app)
        return recorded
    }

    private fun waitForAudioItem(audioItemId: Long) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("audio_item_$audioItemId")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * MainActivity Listen→Convert 배선 미러:
     * [MainActivityDrawerScaffold] + HomeScreen(Listen) + ConvertScreen(공유 VM).
     */
    private fun setListenNavigationFixture(
        initialDestination: AppDestination = AppDestination.Home,
        initialHomeTab: HomeTab = HomeTab.Listen,
    ) {
        composeTestRule.setContent {
            Convert2videoTheme {
                var currentDestination by mutableStateOf(initialDestination)
                var currentHomeTab by mutableStateOf(initialHomeTab)
                var listenSelectionActive by mutableStateOf(false)
                var convertedVideosSelectionActive by mutableStateOf(false)
                val isRecordingSaving = false

                val navigateConvert: () -> Unit = {
                    currentDestination = AppDestination.Convert
                }

                MainActivityDrawerScaffold(
                    currentDestination = currentDestination,
                    onDestinationChange = { currentDestination = it },
                    isRecordingSaving = isRecordingSaving,
                    isRecordingActive = false,
                    listenSelectionActive = listenSelectionActive,
                    convertedVideosSelectionActive = convertedVideosSelectionActive,
                    onNavigateToLanding = { currentDestination = AppDestination.Home },
                ) { openDrawer, gesturesEnabled, _ ->
                    Column {
                        Text(
                            text = currentDestination.name,
                            modifier = Modifier.testTag("test_destination"),
                        )
                        Text(
                            text = if (gesturesEnabled) "gestures_on" else "gestures_off",
                            modifier = Modifier.testTag("test_gestures"),
                        )
                        when (currentDestination) {
                            AppDestination.Home -> HomeScreen(
                                selectedTab = currentHomeTab,
                                onSelectTab = { tab ->
                                    if (tab != HomeTab.ConvertedVideos) {
                                        convertedVideosSelectionActive = false
                                    }
                                    currentHomeTab = tab
                                },
                                onOpenDrawer = openDrawer,
                                recordViewModel = recordViewModel,
                                recordingsListViewModel = recordingsListViewModel,
                                onRecordingSaved = {},
                                onConvertAudioItems = { items ->
                                    listenSelectionActive = false
                                    applyPickedAudioAndNavigateConvert(
                                        selected = items.map { item ->
                                            ConvertViewModel.SelectedAudio(
                                                uri = item.uri,
                                                title = item.title,
                                                artist = item.artist,
                                            )
                                        },
                                        onNavigateConvert = navigateConvert,
                                    )
                                },
                                onListenSelectionActiveChange = { listenSelectionActive = it },
                                onConvertedVideosSelectionActiveChange = {
                                    convertedVideosSelectionActive = it
                                },
                            )

                            AppDestination.Convert -> ConvertScreen(
                                onNavigateToBackgroundPick = {},
                                onNavigateToAudioPick = {
                                    currentDestination = AppDestination.AudioPick
                                },
                                onNavigateToRecord = {},
                                onNavigateToConvertedVideos = {
                                    convertedVideosSelectionActive = false
                                    currentDestination = AppDestination.Home
                                    currentHomeTab = HomeTab.ConvertedVideos
                                },
                                onOpenDrawer = openDrawer,
                                viewModel = convertViewModel,
                            )

                            else -> Text(text = currentDestination.name)
                        }
                    }
                }
            }
        }
    }

    private fun applyPickedAudioAndNavigateConvert(
        selected: List<ConvertViewModel.SelectedAudio>,
        onNavigateConvert: () -> Unit,
    ) {
        convertViewModel.applyFilePickSaved(selected)
        onNavigateConvert()
    }

    private fun assertSelectedAudioMatchesSeeded(seeded: RecordingRecord) {
        composeTestRule.runOnIdle {
            assertEquals(1, convertViewModel.selectedAudioList.value.size)
            val selected = convertViewModel.selectedAudioList.value.first()
            assertEquals(recordingsListViewModel.uriFor(seeded), selected.uri)
        }
    }

    /**
     * MainActivity ModalNavigationDrawer shell — openDrawer / gestures / onSelect(Saving·selection guard) SSOT.
     * Part4 integration tests and Listen fixtures share this helper for MainActivity contract parity.
     */
    @Composable
    private fun MainActivityDrawerScaffold(
        currentDestination: AppDestination,
        onDestinationChange: (AppDestination) -> Unit,
        isRecordingSaving: Boolean,
        isRecordingActive: Boolean,
        listenSelectionActive: Boolean = false,
        convertedVideosSelectionActive: Boolean = false,
        onNavigateToLanding: () -> Unit = { onDestinationChange(AppDestination.Home) },
        content: @Composable (
            openDrawer: () -> Unit,
            gesturesEnabled: Boolean,
            drawerCurrentValue: DrawerValue,
        ) -> Unit,
    ) {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val isDrawerActive =
            drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
        val isHomeSelectionActive = listenSelectionActive || convertedVideosSelectionActive
        val gesturesEnabled = isDrawerGesturesEnabled(
            isDrawerActive = isDrawerActive,
            isRecordingSaving = isRecordingSaving,
            isListenSelectionActive = listenSelectionActive,
            isConvertedVideosSelectionActive = convertedVideosSelectionActive,
        )
        val openDrawer: () -> Unit = {
            if (!isRecordingSaving &&
                !isHomeSelectionActive &&
                drawerState.targetValue == DrawerValue.Closed &&
                !drawerState.isAnimationRunning
            ) {
                scope.launch { drawerState.open() }
            }
        }

        BackHandler(enabled = isDrawerActive) {
            scope.launch { drawerState.close() }
        }
        BackHandler(
            enabled = !isDrawerActive &&
                currentDestination != AppDestination.Home &&
                !isRecordingSaving,
        ) {
            onNavigateToLanding()
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = {
                AppDrawerContent(
                    selected = resolveDrawerSelected(currentDestination),
                    isRecordingActive = isRecordingActive,
                    onSelect = { dest ->
                        if (isRecordingSaving || isHomeSelectionActive) {
                            scope.launch { drawerState.close() }
                            return@AppDrawerContent
                        }
                        onDestinationChange(dest)
                        scope.launch { drawerState.close() }
                    },
                )
            },
        ) {
            content(openDrawer, gesturesEnabled, drawerState.currentValue)
        }
    }

    private fun assertDrawerShowsThreeRowsNoConvertRow() {
        composeTestRule.onNodeWithTag("drawer_home_item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_options_item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_error_log_item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drawer_convert_item").assertDoesNotExist()
    }
}

internal fun ComposeContentTestRule.assertDrawerHasNoThemeUi() {
    onNodeWithTag("drawer_theme_section", useUnmergedTree = true).assertDoesNotExist()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    onNodeWithText(
        context.getString(R.string.options_theme_section),
        useUnmergedTree = true,
    ).assertDoesNotExist()
    onNodeWithText(
        context.getString(R.string.options_theme_system),
        useUnmergedTree = true,
    ).assertDoesNotExist()
    onNodeWithText(
        context.getString(R.string.options_theme_light),
        useUnmergedTree = true,
    ).assertDoesNotExist()
    onNodeWithText(
        context.getString(R.string.options_theme_dark),
        useUnmergedTree = true,
    ).assertDoesNotExist()
}
