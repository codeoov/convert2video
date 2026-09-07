package com.example.convert2video.ui.screens.convert

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.resolveRecordingSavedSeamAction
import com.example.convert2video.ui.shared.ConversionUiState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 — AudioSourceTab SSOT · applyRecordingSaved / applyFilePickSaved · URI 실패 정책.
 */
@RunWith(AndroidJUnit4::class)
class ConvertViewModelAudioSourceTabAndroidTest {

    private lateinit var app: Application
    private lateinit var viewModel: ConvertViewModel

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as Application
        viewModel = ConvertViewModel(app)
    }

    // Given / When / Then

    @Test
    fun success_defaultAudioSourceTabIsRecord() {
        // Given / When — 새 ViewModel
        // Then
        assertEquals(ConvertViewModel.AudioSourceTab.Record, viewModel.audioSourceTab.value)
    }

    @Test
    fun success_selectAudioSourceTab_updatesState() {
        // Given
        assertEquals(ConvertViewModel.AudioSourceTab.Record, viewModel.audioSourceTab.value)

        // When
        viewModel.selectAudioSourceTab(ConvertViewModel.AudioSourceTab.FilePick)

        // Then
        assertEquals(ConvertViewModel.AudioSourceTab.FilePick, viewModel.audioSourceTab.value)
    }

    @Test
    fun success_applyRecordingSaved_setsRecordTabAndAudioList() {
        // Given
        viewModel.selectAudioSourceTab(ConvertViewModel.AudioSourceTab.FilePick)
        val uri = Uri.parse("content://com.convert2video.fileprovider/test.m4a")

        // When
        viewModel.applyRecordingSaved(uri = uri, title = "test.m4a")

        // Then
        assertEquals(ConvertViewModel.AudioSourceTab.Record, viewModel.audioSourceTab.value)
        assertEquals(1, viewModel.selectedAudioList.value.size)
        assertEquals(uri, viewModel.selectedAudioList.value.single().uri)
        assertEquals("test.m4a", viewModel.selectedAudioList.value.single().title)
        assertEquals(0L, viewModel.selectedAudioList.value.single().durationMs)
        assertEquals(ConversionUiState.Idle, viewModel.conversionState.value)
    }

    @Test
    fun success_applyRecordingSaved_preservesDuration() {
        // Given
        val uri = Uri.parse("content://com.convert2video.fileprovider/long.m4a")

        // When
        viewModel.applyRecordingSaved(uri, "long.m4a", durationMs = 600_001L)

        // Then
        assertEquals(600_001L, viewModel.selectedAudioList.value.single().durationMs)
    }

    @Test
    fun success_applyFilePickSaved_setsFilePickTabAndAudioList() {
        // Given
        viewModel.selectAudioSourceTab(ConvertViewModel.AudioSourceTab.Record)
        val items = listOf(
            ConvertViewModel.SelectedAudio(
                uri = Uri.parse("content://media/1"),
                title = "a.mp3",
                artist = "Artist",
            ),
            ConvertViewModel.SelectedAudio(
                uri = Uri.parse("content://media/2"),
                title = "b.mp3",
                artist = null,
            ),
        )

        // When
        viewModel.applyFilePickSaved(items)

        // Then — FilePick 대칭 + startConversion 미호출
        assertEquals(ConvertViewModel.AudioSourceTab.FilePick, viewModel.audioSourceTab.value)
        assertEquals(items, viewModel.selectedAudioList.value)
        assertEquals(ConversionUiState.Idle, viewModel.conversionState.value)
    }

    @Test
    fun failure_reportRecordingUriFailed_keepsTabAndList() = runTest {
        // Given — 실패 정책: 이전 탭(FilePick) + 기존 목록 유지
        val existing = ConvertViewModel.SelectedAudio(
            uri = Uri.parse("content://test/prev.m4a"),
            title = "prev.m4a",
            artist = null,
        )
        viewModel.setAudioList(listOf(existing))
        viewModel.selectAudioSourceTab(ConvertViewModel.AudioSourceTab.FilePick)

        val messageDeferred = async {
            withTimeout(3_000L) { viewModel.userMessage.first() }
        }

        // When
        viewModel.reportRecordingUriFailed()

        // Then
        val message = messageDeferred.await()
        assertTrue(message.isNotBlank())
        assertEquals(ConvertViewModel.AudioSourceTab.FilePick, viewModel.audioSourceTab.value)
        assertEquals(listOf(existing), viewModel.selectedAudioList.value)
        assertEquals(ConversionUiState.Idle, viewModel.conversionState.value)
    }

    @Test
    fun success_seamFoldPolicy_matchesApplyApis() {
        // Given / When — seam fold + apply* 가 동일 성공/실패 계약을 가리킴
        val success = resolveRecordingSavedSeamAction(uriSucceeded = true)
        val failure = resolveRecordingSavedSeamAction(uriSucceeded = false)

        // Then — 성공: Home 적용 경로 / 실패: Record 잔류·terminal clear·탭목록 보존
        assertTrue(success.applyAudioToHome && success.navigateHome)
        assertFalse(success.clearRecordTerminal)
        assertFalse(failure.applyAudioToHome)
        assertFalse(failure.navigateHome)
        assertTrue(failure.clearRecordTerminal)
        assertTrue(failure.showUriFailedFeedback)

        viewModel.applyRecordingSaved(
            uri = Uri.parse("content://ok/rec.m4a"),
            title = "rec.m4a",
        )
        assertEquals(ConvertViewModel.AudioSourceTab.Record, viewModel.audioSourceTab.value)

        viewModel.applyFilePickSaved(
            listOf(
                ConvertViewModel.SelectedAudio(
                    uri = Uri.parse("content://ok/f.mp3"),
                    title = "f.mp3",
                    artist = null,
                ),
            ),
        )
        assertEquals(ConvertViewModel.AudioSourceTab.FilePick, viewModel.audioSourceTab.value)
        viewModel.reportRecordingUriFailed()
        assertEquals(ConvertViewModel.AudioSourceTab.FilePick, viewModel.audioSourceTab.value)
        assertEquals(1, viewModel.selectedAudioList.value.size)
    }
}
