package com.example.convert2video.data

import android.net.Uri

data class AudioItem(
    val id: Long,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val uri: Uri,
)
