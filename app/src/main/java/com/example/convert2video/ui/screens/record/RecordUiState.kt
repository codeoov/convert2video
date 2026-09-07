package com.example.convert2video.ui.screens.record

import java.io.File

/**
 * 녹음 화면 UI 상태 (Sprint 2-1).
 * [RecordingState] + 마이크 권한은 top-level [toRecordUiState]가 매핑한다.
 * Saved/Failed(비권한) sticky·활성 세션은 권한 철회여도 PermissionDenied로 덮지 않는다.
 */
sealed interface RecordUiState {
    data object Idle : RecordUiState

    data class PermissionDenied(val canRequestAgain: Boolean) : RecordUiState

    data class Recording(val elapsedMs: Long, val amplitude: Int) : RecordUiState

    data class Paused(val elapsedMs: Long) : RecordUiState

    /** Controller/Service [RecordingState.Stopping] (인덱싱 중). */
    data object Saving : RecordUiState

    data class Saved(val outputFile: File, val elapsedMs: Long) : RecordUiState

    /** [RecordingState.Review] — Keep 전 검토. Saved/seam 아님. */
    data class Review(val outputFile: File, val elapsedMs: Long) : RecordUiState

    /** [RecordingState.Failed] → 사용자용 Fallback 메시지 (예외/스택 아님). */
    data class Error(val message: String) : RecordUiState
}
