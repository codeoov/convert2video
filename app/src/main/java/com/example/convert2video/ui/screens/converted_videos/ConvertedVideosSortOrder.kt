package com.example.convert2video.ui.screens.converted_videos

/** 변환 결과 목록 정렬 순서 — SSOT. 기본값 [Time]. */
enum class ConvertedVideosSortOrder {
    /** dateAdded 내림차순 (최신 먼저). Group은 자식 max dateAdded. */
    Time,

    /** displayName/title.lowercase(Locale.ROOT) 오름차순. */
    Name,

    /** durationMs 내림차순. Group은 자식 duration 합. 동점 시 dateAdded 내림차순. */
    Duration,
}
