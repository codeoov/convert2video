package com.example.convert2video.ui.screens.recordings_list

/** 녹음 목록 정렬 순서 — SSOT. 기본값 [Time]. */
enum class RecordingsListSortOrder {
    /** dateAdded 내림차순 (최신 먼저). */
    Time,

    /** `title.lowercase(Locale.ROOT)` 오름차순 — 로케일 독립 정렬. */
    Name,

    /** durationMs 내림차순 (긴 것 먼저). 동점 시 dateAdded 내림차순. */
    Duration,
}
