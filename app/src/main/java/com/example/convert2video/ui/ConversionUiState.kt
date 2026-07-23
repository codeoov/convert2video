package com.example.convert2video.ui

import android.net.Uri

sealed interface ConversionUiState {
    data object Idle : ConversionUiState
    data class InProgress(val percent: Int) : ConversionUiState
    data object Cancelled : ConversionUiState
    data class Success(val videoUri: Uri) : ConversionUiState
    data class Failed(val message: String) : ConversionUiState
}
