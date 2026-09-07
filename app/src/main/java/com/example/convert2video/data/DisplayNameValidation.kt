package com.example.convert2video.data

/** 파일시스템·MediaStore 공통 금지 문자 (display name stem). data/ui 공유. */
val FORBIDDEN_DISPLAY_NAME_CHARS = Regex("""[/\\:*?"<>|]""")

/** display name stem 최대 길이 (확장자 제외). */
const val MAX_DISPLAY_NAME_STEM_LENGTH = 200

/** stem 유효성 — blank·금지문자·길이 초과 거부. Repository rename 진입점·UI 공유. */
fun isValidDisplayNameStem(input: String): Boolean =
    input.isNotBlank() &&
        !input.contains(FORBIDDEN_DISPLAY_NAME_CHARS) &&
        input.length <= MAX_DISPLAY_NAME_STEM_LENGTH
