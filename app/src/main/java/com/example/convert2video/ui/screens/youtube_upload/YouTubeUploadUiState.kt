package com.example.convert2video.ui.screens.youtube_upload

sealed interface YouTubeUploadUiState {
    data object Idle : YouTubeUploadUiState
    data class InProgress(val percent: Int) : YouTubeUploadUiState
    data object Cancelled : YouTubeUploadUiState
    data class Success(val videoId: String, val watchUrl: String) : YouTubeUploadUiState
    data class Failed(val message: String) : YouTubeUploadUiState
}
