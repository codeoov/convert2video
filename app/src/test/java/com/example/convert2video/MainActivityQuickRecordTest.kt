package com.example.convert2video

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.example.convert2video.record.RecordingErrorCodes
import com.example.convert2video.record.RecordingState
import com.example.convert2video.ui.shared.ConversionUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Quick Record extra 파서·consume 가드 SSOT (JVM / Robolectric).
 * Glance ActionCallback을 Application receiver로 올리지 않도록 [Application] + [Config.NONE].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class MainActivityQuickRecordTest {

    @Test
    fun success_stringTrue() {
        // Given — shortcuts.xml extra는 String
        val intent = Intent().putExtra(MainActivity.EXTRA_QUICK_RECORD, "true")
        val upper = Intent().putExtra(MainActivity.EXTRA_QUICK_RECORD, "TRUE")

        // When / Then
        assertTrue(intent.isQuickRecordRequested())
        assertTrue(upper.isQuickRecordRequested())
    }

    @Test
    fun success_booleanTrue() {
        // Given
        val intent = Intent().putExtra(MainActivity.EXTRA_QUICK_RECORD, true)

        // When / Then
        assertTrue(intent.isQuickRecordRequested())
    }

    @Test
    fun failure_missing() {
        // Given
        val intent = Intent()

        // When / Then
        assertFalse(intent.isQuickRecordRequested())
    }

    @Test
    fun failure_stringFalse() {
        // Given
        val intent = Intent().putExtra(MainActivity.EXTRA_QUICK_RECORD, "false")
        val boolFalse = Intent().putExtra(MainActivity.EXTRA_QUICK_RECORD, false)

        // When / Then
        assertFalse(intent.isQuickRecordRequested())
        assertFalse(boolFalse.isQuickRecordRequested())
    }

    @Test
    fun success_shouldDefer_whenSavingOrActiveOrSavedOrInProgress() {
        // Given / When / Then
        assertTrue(
            shouldDeferQuickRecord(
                isRecordingSaving = true,
                isActiveSession = false,
                isSavedSticky = false,
                isConversionBlocking = false,
                isReviewPending = false,
            ),
        )
        assertTrue(
            shouldDeferQuickRecord(
                isRecordingSaving = false,
                isActiveSession = true,
                isSavedSticky = false,
                isConversionBlocking = false,
                isReviewPending = false,
            ),
        )
        assertTrue(
            shouldDeferQuickRecord(
                isRecordingSaving = false,
                isActiveSession = false,
                isSavedSticky = true,
                isConversionBlocking = false,
                isReviewPending = false,
            ),
        )
        assertTrue(
            shouldDeferQuickRecord(
                isRecordingSaving = false,
                isActiveSession = false,
                isSavedSticky = false,
                isConversionBlocking = true,
                isReviewPending = false,
            ),
        )
        assertTrue(
            shouldDeferQuickRecord(
                isRecordingSaving = false,
                isActiveSession = false,
                isSavedSticky = false,
                isConversionBlocking = false,
                isReviewPending = true,
            ),
        )
    }

    @Test
    fun failure_shouldDefer_whenIdle() {
        // Given / When / Then — 가드 없음 → navigate·start 후 consume 허용
        assertFalse(
            shouldDeferQuickRecord(
                isRecordingSaving = false,
                isActiveSession = false,
                isSavedSticky = false,
                isConversionBlocking = false,
                isReviewPending = false,
            ),
        )
    }

    @Test
    fun success_isConversionBlocking_inProgressAndSuccess() {
        // Given / When / Then — Success 순간 자동 start 금지 (dismiss까지 defer)
        assertTrue(isConversionBlockingQuickRecord(ConversionUiState.InProgress(percent = 10)))
        assertTrue(isConversionBlockingQuickRecord(ConversionUiState.Success(videoUri = Uri.EMPTY)))
        assertFalse(isConversionBlockingQuickRecord(ConversionUiState.Idle))
        assertFalse(isConversionBlockingQuickRecord(ConversionUiState.Cancelled))
    }

    @Test
    fun success_consumeWhenIdleAfterStart() {
        // Given / When / Then — Idle(no-op start)도 Failed가 아니면 consume
        assertTrue(shouldConsumeAfterQuickStart(RecordingState.Idle))
        assertTrue(
            shouldConsumeAfterQuickStart(
                RecordingState.Recording(elapsedMs = 0L, amplitude = 0),
            ),
        )
    }

    @Test
    fun failure_keepPendingWhenFailed() {
        // Given / When / Then — start 후 Failed면 consume·destination 금지
        assertFalse(
            shouldConsumeAfterQuickStart(
                RecordingState.Failed(RecordingErrorCodes.START_FAILED),
            ),
        )
    }

    @Test
    fun success_isConversionBlocking_failed() {
        // Given / When / Then — Failed 다이얼로그 dismiss 전 자동 start 금지
        assertTrue(isConversionBlockingQuickRecord(ConversionUiState.Failed(message = "x")))
    }

    @Test
    fun success_stripQuickRecordExtra_removesStringAndBoolean() {
        // Given
        val stringIntent = Intent().putExtra(MainActivity.EXTRA_QUICK_RECORD, "true")
        val boolIntent = Intent().putExtra(MainActivity.EXTRA_QUICK_RECORD, true)

        // When
        stringIntent.stripQuickRecordExtra()
        boolIntent.stripQuickRecordExtra()

        // Then
        assertFalse(stringIntent.hasExtra(MainActivity.EXTRA_QUICK_RECORD))
        assertFalse(boolIntent.hasExtra(MainActivity.EXTRA_QUICK_RECORD))
        assertFalse(stringIntent.isQuickRecordRequested())
        assertFalse(boolIntent.isQuickRecordRequested())
    }

    @Test
    fun failure_stripQuickRecordExtra_whenMissing_isNoOp() {
        // Given
        val intent = Intent()

        // When
        intent.stripQuickRecordExtra()

        // Then
        assertFalse(intent.hasExtra(MainActivity.EXTRA_QUICK_RECORD))
        assertFalse(intent.isQuickRecordRequested())
    }
}
