package com.example.convert2video.record

import android.service.quicksettings.Tile
import com.example.convert2video.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingTileServiceStateTest {

    // android.service.quicksettings.Tile.STATE_* 는 컴파일타임 정수 상수라 Android stub jar에서도 값이 보존되어 Robolectric 없이 JVM 유닛 테스트에서 안전하게 참조 가능하다.
    private fun tileStateFor(state: RecordingState): Int =
        if (isActiveRecordingSession(state)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

    // Group A — isActiveRecordingSession 직접 검증

    @Test
    fun success_isActiveRecordingSession_recording_returnsTrue() {
        assertTrue(isActiveRecordingSession(RecordingState.Recording(elapsedMs = 0L, amplitude = 0)))
    }

    @Test
    fun success_isActiveRecordingSession_paused_returnsTrue() {
        assertTrue(isActiveRecordingSession(RecordingState.Paused(elapsedMs = 0L)))
    }

    @Test
    fun success_isActiveRecordingSession_stopping_returnsTrue() {
        assertTrue(isActiveRecordingSession(RecordingState.Stopping))
    }

    @Test
    fun success_isActiveRecordingSession_idle_returnsFalse() {
        assertFalse(isActiveRecordingSession(RecordingState.Idle))
    }

    @Test
    fun success_isActiveRecordingSession_saved_returnsFalse() {
        // Given
        val outputFile = File("dummy.m4a")
        // When / Then
        assertFalse(isActiveRecordingSession(RecordingState.Saved(outputFile = outputFile, elapsedMs = 0L)))
    }

    @Test
    fun success_isActiveRecordingSession_failed_returnsFalse() {
        assertFalse(isActiveRecordingSession(RecordingState.Failed(RecordingErrorCodes.CAPTURE_FAILED)))
    }

    @Test
    fun success_isActiveRecordingSession_stopped_returnsFalse() {
        // Given
        val result = RecordingResult(
            file = File("dummy.m4a"),
            format = RecordingFormat.AAC,
            durationMs = 1_000L,
        )
        // When / Then
        assertFalse(isActiveRecordingSession(RecordingState.Stopped(result)))
    }

    @Test
    fun success_isActiveRecordingSession_review_returnsFalse() {
        assertFalse(
            isActiveRecordingSession(
                RecordingState.Review(outputFile = File("dummy.m4a"), elapsedMs = 6_000L),
            ),
        )
    }

    // Group B — tileStateFor 헬퍼 경유 Tile 상태 매핑 검증

    @Test
    fun success_tileState_recording_returnsActive() {
        assertEquals(Tile.STATE_ACTIVE, tileStateFor(RecordingState.Recording(elapsedMs = 0L, amplitude = 0)))
    }

    @Test
    fun success_tileState_paused_returnsActive() {
        assertEquals(Tile.STATE_ACTIVE, tileStateFor(RecordingState.Paused(elapsedMs = 0L)))
    }

    @Test
    fun success_tileState_stopping_returnsActive() {
        assertEquals(Tile.STATE_ACTIVE, tileStateFor(RecordingState.Stopping))
    }

    @Test
    fun success_tileState_idle_returnsInactive() {
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(RecordingState.Idle))
    }

    @Test
    fun success_tileState_saved_returnsInactive() {
        // Given
        val outputFile = File("dummy.m4a")
        // When / Then
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(RecordingState.Saved(outputFile = outputFile, elapsedMs = 0L)))
    }

    @Test
    fun success_tileState_failed_returnsInactive() {
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(RecordingState.Failed(RecordingErrorCodes.CAPTURE_FAILED)))
    }

    @Test
    fun success_tileState_stopped_returnsInactive() {
        // Given
        val result = RecordingResult(
            file = File("dummy.m4a"),
            format = RecordingFormat.AAC,
            durationMs = 1_000L,
        )
        // When / Then
        assertEquals(Tile.STATE_INACTIVE, tileStateFor(RecordingState.Stopped(result)))
    }

    @Test
    fun success_tileState_review_returnsInactive() {
        assertEquals(
            Tile.STATE_INACTIVE,
            tileStateFor(RecordingState.Review(outputFile = File("dummy.m4a"), elapsedMs = 6_000L)),
        )
    }

    @Test
    fun success_tileLabelResIdFor_review_distinctFromIdle() {
        val review = RecordingState.Review(outputFile = File("dummy.m4a"), elapsedMs = 6_000L)
        assertEquals(R.string.recording_notification_review, tileLabelResIdFor(review))
        assertEquals(R.string.recording_notification_title, tileLabelResIdFor(RecordingState.Idle))
        assertTrue(tileLabelResIdFor(review) != tileLabelResIdFor(RecordingState.Idle))
        assertFalse(isActiveRecordingSession(review))
    }
}
