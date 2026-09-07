package com.example.convert2video.record

import java.io.File

/**
 * [RecordingRecord.format] ("AAC"|"WAV") → 파일 확장자 (m4a|wav).
 * record 패키지 SSOT — Repository·UI 공통.
 */
fun recordingExtension(format: String): String =
    runCatching { RecordingFormat.valueOf(format).fileExtension }
        .getOrDefault(format.lowercase())

/** Review Keep extras용 — 확장자 → [RecordingFormat]. unknown은 AAC. */
internal fun recordingFormatFromFile(file: File): RecordingFormat =
    when (file.extension.lowercase()) {
        RecordingFormat.WAV.fileExtension -> RecordingFormat.WAV
        else -> RecordingFormat.AAC
    }
