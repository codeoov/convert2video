package com.example.convert2video

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.record.RecordingController
import com.example.convert2video.ui.screens.home.assertConvertedVideosContentOrIdle
import com.example.convert2video.ui.screens.home.assertListenContentOrEmpty
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * MainActivity → HomeScreen wiring smoke (G6).
 * Full navigation graph 부담 없이 기본 랜딩·탭 전환만 검증한다.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MainActivityHomeWiringAndroidTest {

    private val grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )

    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val testName: TestName = TestName()

    /**
     * Activity launch 직전마다 실행 — write + first collect로 DataStore flush 보장.
     * exactAlarmPrompted 도 선행 true — 첫 ON_RESUME settings Intent가 Home shell을 가리지 않게.
     */
    private val languagePromptGateRule = object : ExternalResource() {
        override fun before() {
            val application = InstrumentationRegistry.getInstrumentation()
                .targetContext
                .applicationContext as Application
            runBlocking {
                val repo = SettingsRepository(application)
                repo.setLanguagePromptShown(true)
                check(repo.languagePromptShown.first()) {
                    "languagePromptShown must be true before Activity launch"
                }
                repo.setExactAlarmPrompted(true)
                check(repo.exactAlarmPrompted.first()) {
                    "exactAlarmPrompted must be true before Activity launch"
                }
            }
        }
    }

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(languagePromptGateRule)
        .around(grantPermissionRule)
        .around(composeTestRule)

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as Application
        RecordingController.clearInstanceForTest()
        if (testName.methodName == LAUNCH_TEST_METHOD) {
            awaitColdLaunchAppGate()
        } else {
            awaitHomeRecordTab(timeoutMillis = 30_000)
            resetToRecordTabIfVisible()
        }
    }

    @After
    fun tearDown() {
        RecordingController.clearInstanceForTest()
    }

    /**
     * 첫 launch 전용: compose 부착 → DataStore 재확인 → recreate 1회 → [home_tab_record] 필수 대기.
     * 이후 테스트는 RuleChain preset만으로 30s 이내 통과(회귀 방지).
     */
    private fun awaitColdLaunchAppGate() {
        awaitComposeHierarchyAttached(timeoutMillis = 30_000)
        runBlocking {
            val repo = SettingsRepository(app)
            repo.setLanguagePromptShown(true)
            check(repo.languagePromptShown.first())
            repo.setExactAlarmPrompted(true)
            check(repo.exactAlarmPrompted.first())
        }
        recreateActivityOnMainThread()
        awaitComposeHierarchyAttached(timeoutMillis = 30_000)
        awaitHomeRecordTab(timeoutMillis = 60_000)
    }

    private fun awaitHomeRecordTab(timeoutMillis: Long) {
        composeTestRule.waitUntil(timeoutMillis = timeoutMillis) {
            isHomeRecordTabVisible()
        }
    }

    private fun awaitComposeHierarchyAttached(timeoutMillis: Long) {
        composeTestRule.waitUntil(timeoutMillis = timeoutMillis) {
            try {
                composeTestRule.onAllNodesWithTag("c2v_compose_attached_probe")
                    .fetchSemanticsNodes()
                true
            } catch (_: IllegalStateException) {
                false
            }
        }
    }

    private fun isHomeRecordTabVisible(): Boolean {
        return try {
            composeTestRule.onAllNodesWithTag("home_tab_record")
                .fetchSemanticsNodes()
                .isNotEmpty()
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun recreateActivityOnMainThread() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (!composeTestRule.activity.isFinishing) {
                composeTestRule.activity.recreate()
            }
        }
    }

    /** 이전 테스트가 Listen/CV 탭·selection chrome에 남지 않도록 Record 탭으로 리셋. */
    private fun resetToRecordTabIfVisible() {
        try {
            if (isHomeRecordTabVisible()) {
                composeTestRule.onNodeWithTag("home_tab_record").performClick()
            }
        } catch (_: IllegalStateException) {
            // no-op
        }
    }

    @Test
    fun success_a_launchShowsHomeRecordTabEmbedded() {
        composeTestRule.onNodeWithTag("home_tab_record")
            .assertIsDisplayed()
            .assertIsSelected()
        composeTestRule.onNodeWithTag("home_tab_listen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_tab_converted_videos").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_button").assertIsDisplayed()
    }

    @Test
    fun success_homeTabSwitchViaSegmentedControl() {
        composeTestRule.onNodeWithTag("home_tab_listen")
            .assertIsDisplayed()
            .performClick()

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
    fun success_homeTabConvertedVideosSwitchViaSegmentedControl() {
        composeTestRule.onNodeWithTag("home_tab_converted_videos")
            .assertIsDisplayed()
            .performClick()

        assertConvertedVideosContentOrIdle(composeTestRule)
        composeTestRule.onNodeWithTag("navigate_back_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("converted_videos_clear_selection_button")
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag("home_tab_converted_videos").assertIsSelected()
        composeTestRule.onNodeWithTag("open_drawer_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("record_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("home_tab_record").assertIsNotSelected()
        composeTestRule.onNodeWithTag("home_tab_listen").assertIsNotSelected()
    }

    @Test
    fun success_listenImportChipStaysOnHomeListen() {
        // Given — Listen 탭 진입
        composeTestRule.onNodeWithTag("home_tab_listen")
            .assertIsDisplayed()
            .performClick()
        assertListenContentOrEmpty(composeTestRule)
        composeTestRule.onNodeWithTag("listen_filter_all").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recordings_list_import_button").assertIsDisplayed()

        // When — import 칩 클릭 (시스템 파일 피커 실행 — 테스트에서는 Home/Listen 잔류만 검증)
        composeTestRule.onNodeWithTag("recordings_list_import_button").performClick()
        composeTestRule.waitForIdle()

        // Then — Home/Listen 잔류 (AudioPick destination 미진입)
        composeTestRule.onNodeWithTag("home_tab_listen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_filter_segmented_control").assertDoesNotExist()
    }

    private companion object {
        const val LAUNCH_TEST_METHOD = "success_a_launchShowsHomeRecordTabEmbedded"
    }
}
