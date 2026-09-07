package com.example.convert2video.ui.screens.convert

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 3 — Convert [AudioSection] 빈 상태 탭 / 채움 변경 재진입.
 * [BackgroundPickContentTest]와 동일하게 content composable 직접 검증.
 */
class ConvertScreenAudioSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        selectedAudioList: List<ConvertViewModel.SelectedAudio> = emptyList(),
        selectedTab: ConvertViewModel.AudioSourceTab = ConvertViewModel.AudioSourceTab.Record,
        onSelectTab: (ConvertViewModel.AudioSourceTab) -> Unit = {},
        onNavigateToRecord: () -> Unit = {},
        onNavigateToAudioPick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            AudioSection(
                selectedAudioList = selectedAudioList,
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                onNavigateToRecord = onNavigateToRecord,
                onNavigateToAudioPick = onNavigateToAudioPick,
            )
        }
    }

    // Given / When / Then

    @Test
    fun success_emptyShowsDefaultRecordTab() {
        // Given: 오디오 없음 · 기본 탭 Record
        setContent(selectedAudioList = emptyList())

        // Then: SegmentedControl 탭 표시
        composeTestRule.onNodeWithTag("home_audio_tab_record").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_audio_tab_filepick").assertIsDisplayed()
    }

    @Test
    fun success_tappingRecordTabInvokesSelectAndNavigate() {
        // Given
        var selected: ConvertViewModel.AudioSourceTab? = null
        var navigatedToRecord = false
        setContent(
            selectedTab = ConvertViewModel.AudioSourceTab.FilePick,
            onSelectTab = { selected = it },
            onNavigateToRecord = { navigatedToRecord = true },
        )

        // When
        composeTestRule.onNodeWithTag("home_audio_tab_record").performClick()

        // Then
        assertEquals(ConvertViewModel.AudioSourceTab.Record, selected)
        assertTrue(navigatedToRecord)
    }

    @Test
    fun success_tappingFilePickTabInvokesSelectAndNavigate() {
        // Given
        var selected: ConvertViewModel.AudioSourceTab? = null
        var navigatedToFilePick = false
        setContent(
            selectedTab = ConvertViewModel.AudioSourceTab.Record,
            onSelectTab = { selected = it },
            onNavigateToAudioPick = { navigatedToFilePick = true },
        )

        // When
        composeTestRule.onNodeWithTag("home_audio_tab_filepick").performClick()

        // Then
        assertEquals(ConvertViewModel.AudioSourceTab.FilePick, selected)
        assertTrue(navigatedToFilePick)
    }

    @Test
    fun success_filledHidesTabsAndChangeReentersLastTab() {
        // Given: 오디오 채움 · 마지막 탭 FilePick
        var navigatedToFilePick = false
        var navigatedToRecord = false
        setContent(
            selectedAudioList = listOf(
                ConvertViewModel.SelectedAudio(
                    uri = Uri.parse("content://test/audio.m4a"),
                    title = "clip.m4a",
                    artist = null,
                ),
            ),
            selectedTab = ConvertViewModel.AudioSourceTab.FilePick,
            onNavigateToAudioPick = { navigatedToFilePick = true },
            onNavigateToRecord = { navigatedToRecord = true },
        )

        // Then: 탭 숨김 + 변경 버튼 표시
        composeTestRule.onNodeWithTag("home_audio_tab_record").assertDoesNotExist()
        composeTestRule.onNodeWithTag("home_audio_tab_filepick").assertDoesNotExist()
        composeTestRule.onNodeWithTag("home_pick_audio_button").assertIsDisplayed()

        // When
        composeTestRule.onNodeWithTag("home_pick_audio_button").performClick()

        // Then: 마지막 탭(FilePick) 재진입
        assertTrue(navigatedToFilePick)
        assertEquals(false, navigatedToRecord)
    }

    @Test
    fun success_filledChangeReentersRecordTab() {
        // Given: 오디오 채움 · 마지막 탭 Record
        var navigatedToRecord = false
        setContent(
            selectedAudioList = listOf(
                ConvertViewModel.SelectedAudio(
                    uri = Uri.parse("content://test/rec.m4a"),
                    title = "rec.m4a",
                    artist = null,
                ),
            ),
            selectedTab = ConvertViewModel.AudioSourceTab.Record,
            onNavigateToRecord = { navigatedToRecord = true },
        )

        // When
        composeTestRule.onNodeWithTag("home_pick_audio_button").performClick()

        // Then
        assertTrue(navigatedToRecord)
    }

    @Test
    fun success_sameTabRetapStillNavigates() {
        // Given — 이미 Record 선택. 규칙: 동일 탭 재탭도 navigate 스킵하지 않음(재진입 허용).
        var navigateCount = 0
        var selectedCount = 0
        setContent(
            selectedTab = ConvertViewModel.AudioSourceTab.Record,
            onSelectTab = { selectedCount++ },
            onNavigateToRecord = { navigateCount++ },
        )

        // When — 동일 Record 탭 재탭
        composeTestRule.onNodeWithTag("home_audio_tab_record").performClick()

        // Then
        assertEquals(1, selectedCount)
        assertEquals(1, navigateCount)
    }

}
