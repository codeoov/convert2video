package com.example.convert2video.data

import android.net.Uri

data class AudioItem(
    val id: Long,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val uri: Uri,
    val dateAdded: Long = 0L,
    /** MediaStore DISPLAY_NAME — 파일명 그대로. 녹음 항목은 File.name. 미지정 시 빈 문자열. */
    val fileName: String = "",
    /** RELATIVE_PATH 끝 슬래시 제거 결과. API < Q 또는 미지정 시 null. */
    val folderLabel: String? = null,
)
