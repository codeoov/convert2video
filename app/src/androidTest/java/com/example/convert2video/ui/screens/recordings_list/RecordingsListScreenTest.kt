package com.example.convert2video.ui.screens.recordings_list

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.AudioItem
import com.example.convert2video.data.RecordingRecord
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.recordingAudioItemId
import com.example.convert2video.record.C2vRecordingNames
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.ui.screens.audio_pick.AudioSourceFilter
import com.example.convert2video.ui.theme.Convert2videoTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingsListScreenTest {

    // "All" 필터는 RecordingsListStateful에서 실제 READ_MEDIA_AUDIO 권한을 체크한다 —
    // 미부여 시 실제 시스템 권한 다이얼로그가 떠 Compose 테스트 동기화를 깨뜨리므로 사전 부여.
    // GrantPermissionRule의 내부 승인 절차가 composeTestRule의 Activity 기동보다 먼저 끝나야 하므로
    // RuleChain으로 순서를 강제한다 (outer=grant 먼저 적용, inner=composeTestRule).
    private val grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        )

    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissionRule)
        .around(composeTestRule)

    private lateinit var tabContentApp: Application
    private lateinit var tabContentRepository: RecordingRepository
    private val tabContentSeededRecords = mutableListOf<RecordingRecord>()

    private val sampleItem = AudioItem(
        id = 1L,
        title = "Test Recording",
        fileName = "(C2V)test.m4a",
        artist = null,
        durationMs = 1_000L,
        uri = Uri.parse("content://test/1"),
    )

    private val sampleItem2 = AudioItem(
        id = 2L,
        title = "Test Recording 2",
        fileName = "(C2V)test2.m4a",
        artist = null,
        durationMs = 2_000L,
        uri = Uri.parse("content://test/2"),
    )

    private val recordingSampleItem = AudioItem(
        id = -1L,
        title = "My Recording",
        fileName = "(C2V)rec.m4a",
        artist = null,
        durationMs = 5_000L,
        uri = Uri.parse("content://test/recording/1"),
    )

    private val longListenItems: List<AudioItem> = (1L..24L).map { id ->
        AudioItem(
            id = id,
            title = "Listen Item $id",
            fileName = "(C2V)item_$id.m4a",
            artist = null,
            durationMs = 1_000L * id,
            uri = Uri.parse("content://test/$id"),
        )
    }

    private val sortNameLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.recordings_list_sort_name)

    private val conversionFilterConvertedLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.recordings_list_conversion_filter_converted)

    private val filterAllLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.recordings_list_filter_all)

    private val filterMyRecordingsLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.recordings_list_filter_my_recordings)

    private val audioPickFilesLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.audio_pick_filter_files)

    private val recordingsListTitle: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.recordings_list_title)

    private val moreActionsLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.cd_listen_audio_item_more)

    private val playActionLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.cd_play_video)

    private val convertActionLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.recordings_list_convert_action)

    private val renameActionCd: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.cd_rename_video)

    private val deleteActionCd: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.cd_delete_video)

    @Before
    fun setUpTabContentHarness() {
        tabContentApp = ApplicationProvider.getApplicationContext()
        tabContentRepository = RecordingRepository(
            tabContentApp,
            AppDatabase.getInstance(tabContentApp).recordingDao(),
        )
    }

    @After
    fun tearDownTabContentHarness() {
        runBlocking {
            tabContentSeededRecords.toList().forEach { record ->
                runCatching { tabContentRepository.deleteRecording(record) }
            }
        }
        tabContentSeededRecords.clear()
    }

    // Given / When / Then
    // TopBar 관련 케이스는 Content/TopBar fixture에 showTopBar=true 명시
    // (제품은 Home Listen TabContent 임베드 — nested TopBar 없음).

    @Test
    fun success_topBarShowsTitleWithoutDrawer() {
        // Given / When — RecordingsListTopBar 단독 fixture
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListTopBar(onBackClick = {})
            }
        }

        composeTestRule.onNodeWithText(recordingsListTitle).assertIsDisplayed()
        composeTestRule.onNodeWithTag("navigate_back_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_drawer_button").assertDoesNotExist()
    }

    @Test
    fun success_emptyList_showsEmptyState() {
        // Given / When — empty 목록
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = emptyList(),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                )
            }
        }

        // Then — empty 메시지 표시
        composeTestRule.onNodeWithTag("recordings_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list").assertDoesNotExist()
        composeTestRule.onNodeWithTag("navigate_back_button").assertDoesNotExist()
    }

    @Test
    fun success_audioItemsList_showsItems() {
        // Given / When — 목록 있음
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = listOf(sampleItem, sampleItem2),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                )
            }
        }

        // Then — 목록 + 두 아이템
        composeTestRule.onNodeWithTag("recordings_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_item_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_item_2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_empty_state").assertDoesNotExist()
    }

    @Test
    fun success_filterSegmentedControl_displayed() {
        // Given / When — Content 표시
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = emptyList(),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                )
            }
        }

        // Then — 필터 SegmentedControl 표시
        composeTestRule.onNodeWithTag("listen_filter_segmented_control").assertIsDisplayed()
    }

    @Test
    fun success_listenFilterLabels_areDedicatedAndFilesIsNotDisplayed() {
        // Given — Listen filter content with its dedicated labels.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val listenAllLabel = context.getString(R.string.recordings_list_filter_all)
        val listenMyRecordingsLabel =
            context.getString(R.string.recordings_list_filter_my_recordings)
        assertNotEquals(R.string.audio_pick_filter_all, R.string.recordings_list_filter_all)
        assertNotEquals(
            R.string.audio_pick_filter_my_recordings,
            R.string.recordings_list_filter_my_recordings,
        )
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = emptyList(),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                )
            }
        }

        // When — the Listen filter row is rendered.
        composeTestRule.waitForIdle()

        // Then — Listen exposes exactly its two labels and no AudioPick Files option.
        assertEquals(listenMyRecordingsLabel, filterMyRecordingsLabel)
        assertEquals(listenAllLabel, filterAllLabel)
        composeTestRule.onNodeWithText(listenMyRecordingsLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(listenAllLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(audioPickFilesLabel).assertDoesNotExist()
        composeTestRule.onNodeWithTag("listen_filter_files").assertDoesNotExist()
    }

    @Test
    fun success_listenFilterAll_includesMediaAndImportedAudio() {
        // Given — source lists represent recordings, MediaStore audio, and imported audio.
        val mediaItem = sampleItem.copy(id = 11L, title = "Media audio", dateAdded = 20L)
        val importedItem = sampleItem.copy(id = 12L, title = "Imported audio", dateAdded = 30L)

        // When — Listen All applies its source-range policy.
        val result = filterListenAudioItems(
            mediaAndImported = listOf(mediaItem, importedItem),
            recordings = listOf(recordingSampleItem),
            filter = AudioSourceFilter.All,
        )

        // Then — Listen All keeps MediaStore/imported audio in date order and excludes recordings.
        assertEquals(listOf(importedItem, mediaItem), result)
    }

    @Test
    fun success_listenFilterMyRecordings_includesOnlyAppRecordings() {
        // Given — source lists contain app recordings and external audio.
        val mediaItem = sampleItem.copy(id = 11L, title = "Media audio")
        val importedItem = sampleItem.copy(id = 12L, title = "Imported audio")

        // When — Listen My recordings is selected.
        val result = filterListenAudioItems(
            mediaAndImported = listOf(mediaItem, importedItem),
            recordings = listOf(recordingSampleItem),
            filter = AudioSourceFilter.MyRecordings,
        )

        // Then — only app recordings remain visible.
        assertEquals(listOf(recordingSampleItem), result)
    }

    @Test
    fun success_listenTabContent_filtersAndLabelsMatchSourceContract() {
        // Given — the actual Listen TabContent receives one recording, one MediaStore item,
        // and one imported audio item through its existing ViewModel test seams.
        val mediaItem = sampleItem.copy(id = 1001L, title = "Media audio")
        val importedItem = sampleItem.copy(id = 1002L, title = "Imported audio")
        val viewModel = RecordingsListViewModel(tabContentApp)
        viewModel.setAudioPipelineFixturesForTests(
            recordings = listOf(recordingSampleItem),
            imported = listOf(importedItem),
            convertedUris = emptySet(),
        )
        viewModel.setMediaAudioQueryForTests { listOf(mediaItem) }
        viewModel.loadMediaAudio()

        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListTabContent(
                    viewModel = viewModel,
                    onNavigateHome = {},
                )
            }
        }

        // When — the initial My recordings filter is rendered.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("audio_item_${recordingSampleItem.id}")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Then — Listen uses dedicated labels, hides Files, and initially shows recordings only.
        composeTestRule.onNodeWithText(filterMyRecordingsLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(filterAllLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(audioPickFilesLabel).assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_item_${recordingSampleItem.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_item_${mediaItem.id}").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_item_${importedItem.id}").assertDoesNotExist()

        // When — Listen All is selected.
        composeTestRule.onNodeWithText(filterAllLabel).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("audio_item_${importedItem.id}")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Then — Listen All shows MediaStore/imported audio but excludes recordings.
        composeTestRule.onNodeWithTag("audio_item_${recordingSampleItem.id}").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_item_${mediaItem.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_item_${importedItem.id}").assertIsDisplayed()
    }

    @Test
    fun success_sortDropdown_displayed() {
        // Given / When
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = emptyList(),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                )
            }
        }

        // Then — 정렬 DropdownSelector 표시
        composeTestRule.onNodeWithTag("listen_sort_dropdown").assertIsDisplayed()
    }

    @Test
    fun success_conversionFilterDropdown_displayed() {
        // Given / When — Content 기본값 표시
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = emptyList(),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                )
            }
        }

        // Then — 변환 필터 DropdownSelector 표시
        composeTestRule.onNodeWithTag("listen_conversion_filter_dropdown").assertIsDisplayed()
    }

    @Test
    fun success_importChip_hiddenWhenMyRecordings() {
        // Given — My recordings 필터 (Import는 All에서만)
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = emptyList(),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.MyRecordings,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                )
            }
        }

        // Then — 칩 없음, 필터·드롭다운은 표시
        composeTestRule.onNodeWithTag("recordings_list_import_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("listen_filter_segmented_control").assertIsDisplayed()
        composeTestRule.onNodeWithTag("listen_conversion_filter_dropdown").assertIsDisplayed()
        composeTestRule.onNodeWithTag("listen_sort_dropdown").assertIsDisplayed()
    }

    @Test
    fun success_importChip_shownWhenNotSelecting_displayed_enabled() {
        // Given — !selectionMode, empty 목록
        var importClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = emptyList(),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = { importClicks++ },
                    snackbarHost = {},
                )
            }
        }

        // Then — import chip 표시 + 활성
        composeTestRule.onNodeWithTag("recordings_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list_import_button")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule.onNodeWithTag("navigate_back_button").assertDoesNotExist()

        // When
        composeTestRule.onNodeWithTag("recordings_list_import_button").performClick()
        composeTestRule.waitForIdle()

        // Then — 콜백 1회
        assertEquals(1, importClicks)
    }

    @Test
    fun success_importChip_shownWithList_displayed_enabled() {
        // Given — !selectionMode + 목록
        var importClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = listOf(sampleItem),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = { importClicks++ },
                    snackbarHost = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("recordings_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list_import_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("recordings_list_import_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, importClicks)
    }

    @Test
    fun failure_importChip_navigationBlocked_isNotEnabled() {
        // Given — empty 목록 + navigationBlocked (Saving 게이트)
        var importClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = emptyList(),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = { importClicks++ },
                    snackbarHost = {},
                    navigationBlocked = true,
                )
            }
        }

        // Then — chip 표시·비활성, 콜백 미호출
        composeTestRule.onNodeWithTag("recordings_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list_import_button")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        assertEquals(0, importClicks)
    }

    @Test
    fun success_nonSelectionMode_bodySemantics_notClickable() {
        // Given — Content 직접 mount, 비선택·메뉴 닫힘
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = listOf(sampleItem),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                )
            }
        }

        // Then — 본문 semantics disabled (TalkBack 단일탭 불가)
        composeTestRule.onNodeWithTag("audio_item_body_1").assertIsNotEnabled()
    }

    @Test
    fun success_nonSelectionMode_menuOpen_bodyTap_collapsesWithoutConvert() {
        // Given — ⋯ 열린 상태
        var convertCalls = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(
                    audioItems = listOf(sampleItem),
                    onConvertAudioItems = { convertCalls++ },
                )
            }
        }
        openListenOverflowMenu()
        composeTestRule.onNodeWithContentDescription(convertActionLabel).assertIsDisplayed()

        // When — 본문 탭(메뉴 collapse)
        composeTestRule.onNodeWithTag("audio_item_1").performClick()
        composeTestRule.waitForIdle()

        // Then — overflow 사라짐 + Convert 미호출
        assertListenOverflowActionsDoNotExist(includeRename = false)
        assertEquals(0, convertCalls)
    }

    @Test
    fun success_tabContent_nonSelectionBodyTap_doesNotCallOnConvertAudioItems() {
        // Given — RecordingsListTabContent(Stateful) 직접 mount + Room 시드
        val audioItemId = seedTabContentRecording()
        val viewModel = RecordingsListViewModel(tabContentApp)
        var convertCalls = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListTabContent(
                    viewModel = viewModel,
                    onNavigateHome = {},
                    onConvertAudioItems = { convertCalls++ },
                )
            }
        }
        waitForListenAudioItem(audioItemId)

        // When — 비선택모드 본문 탭
        composeTestRule.onNodeWithTag("audio_item_body_$audioItemId").performClick()
        composeTestRule.waitForIdle()

        // Then — Stateful 경로에서 onConvertAudioItems 0회
        assertEquals(0, convertCalls)
    }

    @Test
    fun success_tabContent_menuOpen_bodyTap_collapsesWithoutConvert() {
        // Given — TabContent + Room 시드, ⋯ 열린 상태
        val audioItemId = seedTabContentRecording()
        val viewModel = RecordingsListViewModel(tabContentApp)
        var convertCalls = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListTabContent(
                    viewModel = viewModel,
                    onNavigateHome = {},
                    onConvertAudioItems = { convertCalls++ },
                )
            }
        }
        waitForListenAudioItem(audioItemId)
        openListenOverflowMenu()
        composeTestRule.onNodeWithContentDescription(convertActionLabel).assertIsDisplayed()

        // When — 본문 탭(메뉴 collapse)
        composeTestRule.onNodeWithTag("audio_item_body_$audioItemId").performClick()
        composeTestRule.waitForIdle()

        // Then — overflow 사라짐 + Convert 미호출
        assertListenOverflowActionsDoNotExist(includeRename = true)
        assertEquals(0, convertCalls)
    }

    @Test
    fun success_contentSelectionMode_bodyTap_callsOnAudioItemClick() {
        // Given — Content 직접 mount, selectionMode=true.
        // Body tap gate SSOT는 AudioListItem; 선택모드일 때만 onAudioItemClick 호출.
        var clickedItem: AudioItem? = null
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = listOf(sampleItem),
                    convertedAudioUris = emptySet(),
                    audioFilter = AudioSourceFilter.All,
                    sortOrder = RecordingsListSortOrder.Time,
                    onAudioFilterChange = {},
                    onSortOrderChange = {},
                    onConvertAudioItems = {},
                    onAudioItemClick = { clickedItem = it },
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                    selectionMode = true,
                    selectedIds = setOf(sampleItem.id),
                )
            }
        }

        // When — 선택모드 본문 탭
        composeTestRule.onNodeWithTag("audio_item_body_1").performClick()
        composeTestRule.waitForIdle()

        // Then — Content callback 호출(Convert와 무관)
        assertEquals(sampleItem, clickedItem)
    }

    @Test
    fun success_importedItemOverflow_showsPlayConvertDelete() {
        // Given — 양수 id (가져온 파일) + Stateful과 같은 overflow collapse fixture
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(audioItems = listOf(sampleItem))
            }
        }

        // When — "..." 펼침
        openListenOverflowMenu()

        // Then — Play·Convert·Delete, Rename 없음
        composeTestRule.onNodeWithContentDescription(playActionLabel).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(convertActionLabel).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(deleteActionCd).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(renameActionCd).assertDoesNotExist()
    }

    @Test
    fun success_importedItemOverflow_playClick_callsOnPlayClickOnly() {
        // Given — 양수 id + onPlayClick 추적. 본문 탭과 분리
        var played: AudioItem? = null
        var audioClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(
                    audioItems = listOf(sampleItem),
                    onAudioItemClick = { audioClicks++ },
                    onPlayClick = { played = it },
                )
            }
        }

        // When — "..." 펼침 후 Play
        openListenOverflowMenu()
        composeTestRule.onNodeWithContentDescription(playActionLabel).performClick()
        composeTestRule.waitForIdle()

        // Then — Play만, 메뉴 collapse, 본문 탭 0회
        assertEquals(sampleItem, played)
        assertEquals(0, audioClicks)
        assertListenOverflowActionsDoNotExist(includeRename = false)
    }

    @Test
    fun success_importedItemOverflow_convertClick_callsOnConvertAudioItems() {
        // Given — 양수 id. Convert는 ⋯를 연 다음 클릭 (Play와 한 테스트에 구기지 않음)
        var convertedItems: List<AudioItem>? = null
        var convertCalls = 0
        var audioClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(
                    audioItems = listOf(sampleItem),
                    onConvertAudioItems = { items ->
                        convertCalls++
                        convertedItems = items
                    },
                    onAudioItemClick = { audioClicks++ },
                )
            }
        }

        // When — "..." 펼침 후 Convert
        openListenOverflowMenu()
        composeTestRule.onNodeWithContentDescription(convertActionLabel).performClick()
        composeTestRule.waitForIdle()

        // Then — Convert만 1회, 본문 탭 0회
        assertEquals(listOf(sampleItem), convertedItems)
        assertEquals(1, convertCalls)
        assertEquals(0, audioClicks)
    }

    @Test
    fun success_importedItemOverflow_deleteClick_doesNotCallOnAudioItemClick() {
        // Given — 양수 id. Delete만 검증 (Play/Convert와 분리)
        var audioClicks = 0
        var deleteClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(
                    audioItems = listOf(sampleItem),
                    onAudioItemClick = { audioClicks++ },
                    onDeleteClick = { deleteClicks++ },
                )
            }
        }

        // When — "..." 펼침 후 Delete
        openListenOverflowMenu()
        composeTestRule.onNodeWithContentDescription(deleteActionCd).performClick()
        composeTestRule.waitForIdle()

        // Then — Delete 1회, 본문 탭 0회
        assertEquals(1, deleteClicks)
        assertEquals(0, audioClicks)
    }

    @Test
    fun success_recordingItemOverflow_showsPlayRenameConvertDelete() {
        // Given — 음수 id (녹음) + overflow collapse fixture
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(audioItems = listOf(recordingSampleItem))
            }
        }

        // When — "..." 펼침
        openListenOverflowMenu()

        // Then — Play / Rename / Convert / Delete 4셀
        composeTestRule.onNodeWithContentDescription(playActionLabel).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(renameActionCd).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(convertActionLabel).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(deleteActionCd).assertIsDisplayed()
    }

    @Test
    fun success_recordingItemOverflow_playClick_doesNotCallOnAudioItemClick() {
        // Given — 녹음 overflow Play. 본문 탭과 분리
        var audioClicks = 0
        var playClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(
                    audioItems = listOf(recordingSampleItem),
                    onAudioItemClick = { audioClicks++ },
                    onPlayClick = { playClicks++ },
                )
            }
        }

        // When
        openListenOverflowMenu()
        composeTestRule.onNodeWithContentDescription(playActionLabel).performClick()
        composeTestRule.waitForIdle()

        // Then
        assertEquals(1, playClicks)
        assertEquals(0, audioClicks)
        assertListenOverflowActionsDoNotExist(includeRename = true)
    }

    @Test
    fun success_recordingItemOverflow_renameClick_doesNotCallOnAudioItemClick() {
        // Given — 녹음 overflow Rename
        var audioClicks = 0
        var renameClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(
                    audioItems = listOf(recordingSampleItem),
                    onAudioItemClick = { audioClicks++ },
                    onRenameClick = { renameClicks++ },
                )
            }
        }

        // When
        openListenOverflowMenu()
        composeTestRule.onNodeWithContentDescription(renameActionCd).performClick()
        composeTestRule.waitForIdle()

        // Then
        assertEquals(1, renameClicks)
        assertEquals(0, audioClicks)
    }

    @Test
    fun success_recordingItemOverflow_convertClick_doesNotCallOnAudioItemClick() {
        // Given — 녹음 overflow Convert. ⋯를 연 다음 클릭
        var convertedItems: List<AudioItem>? = null
        var convertCalls = 0
        var audioClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(
                    audioItems = listOf(recordingSampleItem),
                    onConvertAudioItems = { items ->
                        convertCalls++
                        convertedItems = items
                    },
                    onAudioItemClick = { audioClicks++ },
                )
            }
        }

        // When
        openListenOverflowMenu()
        composeTestRule.onNodeWithContentDescription(convertActionLabel).performClick()
        composeTestRule.waitForIdle()

        // Then
        assertEquals(listOf(recordingSampleItem), convertedItems)
        assertEquals(1, convertCalls)
        assertEquals(0, audioClicks)
    }

    @Test
    fun success_recordingItemOverflow_deleteClick_doesNotCallOnAudioItemClick() {
        // Given — 녹음 overflow Delete
        var audioClicks = 0
        var deleteClicks = 0
        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListOverflowFixture(
                    audioItems = listOf(recordingSampleItem),
                    onAudioItemClick = { audioClicks++ },
                    onDeleteClick = { deleteClicks++ },
                )
            }
        }

        // When
        openListenOverflowMenu()
        composeTestRule.onNodeWithContentDescription(deleteActionCd).performClick()
        composeTestRule.waitForIdle()

        // Then
        assertEquals(1, deleteClicks)
        assertEquals(0, audioClicks)
    }

    @Test
    fun success_expandedMenu_backDismissesWithoutNavigateHome() {
        // resolveRecordingsListBackAction 직접 테스트 (Navigation.kt SSOT)
        var navigatedHome = false
        var expandedId: Long? = 1L

        composeTestRule.setContent {
            Convert2videoTheme {
                RecordingsListTopBar(
                    onBackClick = {
                        when (
                            resolveRecordingsListBackAction(
                                expandedRecordId = expandedId,
                                hasPendingDelete = false,
                                hasPendingRename = false,
                            )
                        ) {
                            RecordingsListBackAction.NavigateHome -> navigatedHome = true
                            RecordingsListBackAction.DismissExpandedMenu -> expandedId = null
                            else -> {}
                        }
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("navigate_back_button").performClick()
        assertEquals(false, navigatedHome)
        assertEquals(null, expandedId)
    }

    @Test
    fun success_filterOrSortChange_resetsScrollToFirstItem() {
        // Given — 뷰포트보다 긴 목록. 세 필터 State는 Content 콜백으로만 갱신.
        composeTestRule.setContent {
            var audioFilter by remember { mutableStateOf(AudioSourceFilter.MyRecordings) }
            var sortOrder by remember { mutableStateOf(RecordingsListSortOrder.Time) }
            var conversionFilter by remember {
                mutableStateOf(RecordingsListConversionFilter.All)
            }
            Convert2videoTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    RecordingsListContent(
                        audioItems = longListenItems,
                        convertedAudioUris = emptySet(),
                        audioFilter = audioFilter,
                        sortOrder = sortOrder,
                        conversionFilter = conversionFilter,
                        onAudioFilterChange = { audioFilter = it },
                        onSortOrderChange = { sortOrder = it },
                        onConversionFilterChange = { conversionFilter = it },
                        onConvertAudioItems = {},
                        onAudioItemClick = {},
                        onEnterSelectionMode = {},
                        onImportClick = {},
                        snackbarHost = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("recordings_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_item_1").assertIsDisplayed()

        // When / Then — 스크롤 후 오디오 필터 변경 → 첫 항목 표시, 마지막 항목 비표시
        scrollListenListAwayFromFirstItem()
        clickListenAudioFilterAll()
        assertListenScrollResetToFirstItem()

        // When / Then — 스크롤 후 정렬 변경 → 첫 항목 표시, 마지막 항목 비표시
        scrollListenListAwayFromFirstItem()
        composeTestRule.onNodeWithTag("listen_sort_dropdown").performClick()
        composeTestRule.onNodeWithText(sortNameLabel).performClick()
        composeTestRule.waitForIdle()
        assertListenScrollResetToFirstItem()

        // When / Then — 스크롤 후 변환 필터 변경 → 첫 항목 표시, 마지막 항목 비표시
        scrollListenListAwayFromFirstItem()
        composeTestRule.onNodeWithTag("listen_conversion_filter_dropdown").performClick()
        composeTestRule.onNodeWithText(conversionFilterConvertedLabel).performClick()
        composeTestRule.waitForIdle()
        assertListenScrollResetToFirstItem()
    }

    @Test
    fun success_emptyList_filterChange_keepsEmptyState() {
        // Given — LazyColumn 미구성(빈 목록)
        composeTestRule.setContent {
            var audioFilter by remember { mutableStateOf(AudioSourceFilter.MyRecordings) }
            var sortOrder by remember { mutableStateOf(RecordingsListSortOrder.Time) }
            var conversionFilter by remember {
                mutableStateOf(RecordingsListConversionFilter.All)
            }
            Convert2videoTheme {
                RecordingsListContent(
                    audioItems = emptyList(),
                    convertedAudioUris = emptySet(),
                    audioFilter = audioFilter,
                    sortOrder = sortOrder,
                    conversionFilter = conversionFilter,
                    onAudioFilterChange = { audioFilter = it },
                    onSortOrderChange = { sortOrder = it },
                    onConversionFilterChange = { conversionFilter = it },
                    onConvertAudioItems = {},
                    onAudioItemClick = {},
                    onEnterSelectionMode = {},
                    onImportClick = {},
                    snackbarHost = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("recordings_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list").assertDoesNotExist()

        // When — 세 필터를 각각 변경 (scrollToItem 미호출이어야 크래시 없음)
        clickListenAudioFilterAll()
        composeTestRule.onNodeWithTag("listen_sort_dropdown").performClick()
        composeTestRule.onNodeWithText(sortNameLabel).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("listen_conversion_filter_dropdown").performClick()
        composeTestRule.onNodeWithText(conversionFilterConvertedLabel).performClick()
        composeTestRule.waitForIdle()

        // Then — empty 유지, LazyColumn 미구성
        composeTestRule.onNodeWithTag("recordings_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list").assertDoesNotExist()
    }

    @Test
    fun success_selectionMenuOrItemCount_doesNotResetScroll() {
        // Given — 필터 키 외 상태(선택·메뉴·import·항목 수)는 리셋 트리거가 아님
        val itemsState = mutableStateOf(longListenItems)
        val selectionModeState = mutableStateOf(false)
        val selectedIdsState = mutableStateOf(emptySet<Long>())
        val expandedMenuIdState = mutableStateOf<Long?>(null)
        var importClicks = 0
        composeTestRule.setContent {
            val items by itemsState
            val selectionMode by selectionModeState
            val selectedIds by selectedIdsState
            val expandedMenuId by expandedMenuIdState
            Convert2videoTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    RecordingsListContent(
                        audioItems = items,
                        convertedAudioUris = emptySet(),
                        audioFilter = AudioSourceFilter.All,
                        sortOrder = RecordingsListSortOrder.Time,
                        conversionFilter = RecordingsListConversionFilter.All,
                        onAudioFilterChange = {},
                        onSortOrderChange = {},
                        onConversionFilterChange = {},
                        onConvertAudioItems = {},
                        onAudioItemClick = {},
                        onEnterSelectionMode = {},
                        onImportClick = { importClicks++ },
                        snackbarHost = {},
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        expandedMenuId = expandedMenuId,
                    )
                }
            }
        }

        scrollListenListAwayFromFirstItem()

        // When — import chip 클릭 (선택 모드 아님 — chip 표시)
        composeTestRule.onNodeWithTag("recordings_list_import_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, importClicks)
        assertListenScrollStayedAwayFromFirstItem()

        // When — 선택 모드
        composeTestRule.runOnIdle {
            selectionModeState.value = true
            selectedIdsState.value = setOf(longListenItems.last().id)
        }
        composeTestRule.waitForIdle()
        assertListenScrollStayedAwayFromFirstItem()

        // When — 메뉴 펼침 (선택 해제 후)
        composeTestRule.runOnIdle {
            selectionModeState.value = false
            selectedIdsState.value = emptySet()
            expandedMenuIdState.value = longListenItems.last().id
        }
        composeTestRule.waitForIdle()
        assertListenScrollStayedAwayFromFirstItem()

        // When — 항목 증감
        composeTestRule.runOnIdle {
            itemsState.value = longListenItems + AudioItem(
                id = 99L,
                title = "Listen Item 99",
                fileName = "(C2V)item_99.m4a",
                artist = null,
                durationMs = 99_000L,
                uri = Uri.parse("content://test/99"),
            )
        }
        composeTestRule.waitForIdle()

        // Then — 첫 항목은 여전히 뷰포트 밖
        assertListenScrollStayedAwayFromFirstItem()
    }

    @Test
    fun success_listReappearsAfterEmpty_staleIndex_doesNotCrash() {
        // Given — 긴 목록에서 끝까지 스크롤한 뒤 빈 목록으로 전환
        val itemsState = mutableStateOf(longListenItems)
        composeTestRule.setContent {
            val items by itemsState
            Convert2videoTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    RecordingsListContent(
                        audioItems = items,
                        convertedAudioUris = emptySet(),
                        audioFilter = AudioSourceFilter.MyRecordings,
                        sortOrder = RecordingsListSortOrder.Time,
                        conversionFilter = RecordingsListConversionFilter.All,
                        onAudioFilterChange = {},
                        onSortOrderChange = {},
                        onConversionFilterChange = {},
                        onConvertAudioItems = {},
                        onAudioItemClick = {},
                        onEnterSelectionMode = {},
                        onImportClick = {},
                        snackbarHost = {},
                    )
                }
            }
        }

        scrollListenListAwayFromFirstItem()

        // When — LazyColumn dispose (빈 목록). listState 인덱스는 stale로 유지
        composeTestRule.runOnIdle { itemsState.value = emptyList() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recordings_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_list").assertDoesNotExist()

        // When — 더 짧은 목록 복귀 (stale firstVisibleItemIndex >= itemCount)
        val shorterItems = longListenItems.take(8)
        composeTestRule.runOnIdle { itemsState.value = shorterItems }
        composeTestRule.waitForIdle()

        // Then — 크래시 없음. 필터 미변경이므로 맨 위 강제 리셋이 아님
        composeTestRule.onNodeWithTag("recordings_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_empty_state").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_item_${shorterItems.last().id}").assertIsDisplayed()
        assertListenItemNotDisplayed(itemId = 1L)
    }

    @Test
    fun success_listShrinksWithoutEmpty_staleIndex_coercesToLastValid() {
        // Given — 긴 목록에서 끝까지 스크롤한 뒤, empty를 거치지 않고 축소
        val itemsState = mutableStateOf(longListenItems)
        composeTestRule.setContent {
            val items by itemsState
            Convert2videoTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    RecordingsListContent(
                        audioItems = items,
                        convertedAudioUris = emptySet(),
                        audioFilter = AudioSourceFilter.MyRecordings,
                        sortOrder = RecordingsListSortOrder.Time,
                        conversionFilter = RecordingsListConversionFilter.All,
                        onAudioFilterChange = {},
                        onSortOrderChange = {},
                        onConversionFilterChange = {},
                        onConvertAudioItems = {},
                        onAudioItemClick = {},
                        onEnterSelectionMode = {},
                        onImportClick = {},
                        snackbarHost = {},
                    )
                }
            }
        }

        scrollListenListAwayFromFirstItem()

        // When — 24→8, empty 경로 없음. 필터 키 불변
        val shorterItems = longListenItems.take(8)
        composeTestRule.runOnIdle { itemsState.value = shorterItems }
        composeTestRule.waitForIdle()

        // Then — last valid로 coerce. itemCount만으로 0 리셋 금지
        composeTestRule.onNodeWithTag("recordings_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recordings_empty_state").assertDoesNotExist()
        composeTestRule.onNodeWithTag("audio_item_${shorterItems.last().id}").assertIsDisplayed()
        assertListenItemNotDisplayed(itemId = 1L)
    }

    @Test
    fun success_filterChange_nextFrameShrink_resetsToFirstNotLast() {
        // Given — 하단 스크롤. 필터 변경과 다음 프레임 24→8 축소를 겹친다.
        val itemsState = mutableStateOf(longListenItems)
        val audioFilterState = mutableStateOf(AudioSourceFilter.MyRecordings)
        composeTestRule.setContent {
            val items by itemsState
            val audioFilter by audioFilterState
            Convert2videoTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    RecordingsListContent(
                        audioItems = items,
                        convertedAudioUris = emptySet(),
                        audioFilter = audioFilter,
                        sortOrder = RecordingsListSortOrder.Time,
                        conversionFilter = RecordingsListConversionFilter.All,
                        onAudioFilterChange = { audioFilterState.value = it },
                        onSortOrderChange = {},
                        onConversionFilterChange = {},
                        onConvertAudioItems = {},
                        onAudioItemClick = {},
                        onEnterSelectionMode = {},
                        onImportClick = {},
                        snackbarHost = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("recordings_list").assertIsDisplayed()
        scrollListenListAwayFromFirstItem()

        // When — 제품 타이밍: 필터 변경 프레임 다음 프레임에 목록 24→8
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnUiThread {
            audioFilterState.value = AudioSourceFilter.All
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.runOnUiThread {
            itemsState.value = longListenItems.take(8)
        }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        // Then — 맨 위. last(8)가 보이면 Coerce 회귀로 FAIL
        composeTestRule.onNodeWithTag("audio_item_1").assertIsDisplayed()
        assertListenItemNotDisplayed(itemId = 8L)
        assertListenItemNotDisplayed(itemId = 24L)
    }

    private fun openListenOverflowMenu() {
        composeTestRule.onNodeWithContentDescription(moreActionsLabel).performClick()
        composeTestRule.waitForIdle()
    }

    private fun seedTabContentRecording(): Long {
        val file = File(
            C2vRecordingNames.appStorageDir(tabContentApp),
            "tab_content_${System.nanoTime()}.m4a",
        )
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val recorded = runBlocking {
            tabContentRepository.recordFinishedRecording(
                file = file,
                format = RecordingFormat.AAC,
                durationMs = 1_000L,
            )
        }
        tabContentSeededRecords += recorded
        return recordingAudioItemId(recorded.id)
    }

    private fun waitForListenAudioItem(audioItemId: Long) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("audio_item_$audioItemId")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun assertListenOverflowActionsDoNotExist(includeRename: Boolean) {
        composeTestRule.onNodeWithContentDescription(playActionLabel).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(convertActionLabel).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(deleteActionCd).assertDoesNotExist()
        if (includeRename) {
            composeTestRule.onNodeWithContentDescription(renameActionCd).assertDoesNotExist()
        }
    }

    private fun clickListenAudioFilterAll() {
        composeTestRule.onNode(
            hasText(filterAllLabel) and hasAnyAncestor(hasTestTag("listen_filter_segmented_control")),
        ).performClick()
        composeTestRule.waitForIdle()
    }

    private fun scrollListenListAwayFromFirstItem() {
        composeTestRule.onNodeWithTag("recordings_list")
            .performScrollToIndex(longListenItems.lastIndex)
        composeTestRule.waitForIdle()
        assertListenScrollStayedAwayFromFirstItem()
    }

    private fun assertListenScrollStayedAwayFromFirstItem() {
        assertListenItemNotDisplayed(itemId = 1L)
    }

    private fun assertListenScrollResetToFirstItem() {
        composeTestRule.onNodeWithTag("audio_item_1").assertIsDisplayed()
        assertListenItemNotDisplayed(itemId = longListenItems.last().id)
    }

    /** 매칭 0개 = 비표시로 통과. 노드가 있으면 [assertIsNotDisplayed]. 없는 노드에 assert 강제 금지. */
    private fun assertListenItemNotDisplayed(itemId: Long) {
        val tag = "audio_item_$itemId"
        val matchCount = composeTestRule.onAllNodesWithTag(tag)
            .fetchSemanticsNodes()
            .size
        if (matchCount == 0) {
            return
        }
        composeTestRule.onNodeWithTag(tag).assertIsNotDisplayed()
    }
}

/**
 * Overflow fixture — Stateful과 같이 Play/Convert/Rename/Delete에서 `expandedMenuId = null`.
 * [RecordingsListContent]의 `onPlayClick` 기본값 `{}`는 변경하지 않는다.
 */
@Composable
private fun RecordingsListOverflowFixture(
    audioItems: List<AudioItem>,
    onConvertAudioItems: (List<AudioItem>) -> Unit = {},
    onAudioItemClick: (AudioItem) -> Unit = {},
    onPlayClick: (AudioItem) -> Unit = {},
    onRenameClick: (AudioItem) -> Unit = {},
    onDeleteClick: (AudioItem) -> Unit = {},
) {
    var expandedMenuId by remember { mutableStateOf<Long?>(null) }
    RecordingsListContent(
        audioItems = audioItems,
        convertedAudioUris = emptySet(),
        audioFilter = AudioSourceFilter.All,
        sortOrder = RecordingsListSortOrder.Time,
        onAudioFilterChange = {},
        onSortOrderChange = {},
        onConvertAudioItems = { items ->
            expandedMenuId = null
            onConvertAudioItems(items)
        },
        onAudioItemClick = onAudioItemClick,
        onEnterSelectionMode = {},
        onImportClick = {},
        snackbarHost = {},
        expandedMenuId = expandedMenuId,
        onToggleExpandedMenu = { id ->
            expandedMenuId = if (expandedMenuId == id) null else id
        },
        onPlayClick = { item ->
            expandedMenuId = null
            onPlayClick(item)
        },
        onRenameClick = { item ->
            expandedMenuId = null
            onRenameClick(item)
        },
        onDeleteClick = { item ->
            expandedMenuId = null
            onDeleteClick(item)
        },
    )
}
