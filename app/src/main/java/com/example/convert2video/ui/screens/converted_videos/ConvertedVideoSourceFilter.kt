package com.example.convert2video.ui.screens.converted_videos

/** 변환 결과 목록 원본 오디오 출처 필터 — SSOT. 기본값 [All]. */
enum class ConvertedVideoSourceFilter {
    /** 마이크 녹음(Music/C2V FileProvider)에서 변환된 영상만. */
    MyRecordings,

    /** 녹음·import·기타 오디오 출처 전체. */
    All,
}
