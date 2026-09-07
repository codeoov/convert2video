package com.example.convert2video.ui.shared

import android.net.Uri

/**
 * Conversion 진행 UI 상태(sealed).
 *
 * WorkInfo 4상태 분기는 [WorkInfoUiPhase] 단일 소스다.
 * 페이로드 매핑은 [com.example.convert2video.ui.screens.convert.ConvertViewModel]의
 * `toConversionUiState` / `toAggregateConversionUiState` / `toBatchConversionUiState` 가 담당한다.
 * Screen에서 WorkInfo 분기를 복제하지 않는다.
 */
sealed interface ConversionUiState {
    data object Idle : ConversionUiState
    data class InProgress(val percent: Int) : ConversionUiState
    data object Cancelled : ConversionUiState
    data class Success(val videoUri: Uri) : ConversionUiState
    data class Failed(val message: String) : ConversionUiState
}
