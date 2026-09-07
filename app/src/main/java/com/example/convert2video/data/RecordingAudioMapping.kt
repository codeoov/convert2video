package com.example.convert2video.data

import android.net.Uri
import com.example.convert2video.video.C2vOutputNames
import java.io.File

/** [res/xml/file_paths.xml] `name="c2v_music"` 와 일치 — FileProvider 세그먼트 SSOT. */
internal const val RECORDING_AUDIO_FILE_PROVIDER_SEGMENT = "c2v_music"

/**
 * true = 실제 마이크 녹음([RECORDING_AUDIO_FOLDER_LABEL], FileProvider content URI)에서 온 오디오.
 * import([IMPORTED_AUDIO_FOLDER_LABEL])·MediaStore·기타 URI는 false.
 */
internal fun isRecordingSourcedAudioUri(audioUri: String?): Boolean {
    if (audioUri.isNullOrBlank()) return false
    val uri = try {
        Uri.parse(audioUri)
    } catch (_: Exception) {
        return false
    }
    return uri.authority == C2vOutputNames.FILE_PROVIDER_AUTHORITY &&
        uri.pathSegments.firstOrNull() == RECORDING_AUDIO_FILE_PROVIDER_SEGMENT
}

/** recording_records.id → AudioItem.id SSOT: `-recordingId - 1` (MediaStore id 충돌 방지). */
internal fun recordingAudioItemId(recordingId: Long): Long = -recordingId - 1

/**
 * AudioItem.id → recording_records.id ([recordingAudioItemId]의 역함수, involution 재사용).
 * 음수(녹음 AudioItem.id) → [recordingAudioItemId] 재사용(동일 수식), 양수/0(MediaStore id) → null.
 */
internal fun recordingIdFromAudioItemId(audioItemId: Long): Long? =
    if (audioItemId < 0 && audioItemId > -IMPORTED_AUDIO_ID_OFFSET) {
        recordingAudioItemId(audioItemId)
    } else {
        null
    }

/** [RecordingRecord.filePath] → AudioPick 목록 표시 제목. */
internal fun recordingAudioItemTitle(filePath: String): String = File(filePath).name

/** mapRecordingToAudioItem folderLabel SSOT — 리터럴 중복 금지. */
internal const val RECORDING_AUDIO_FOLDER_LABEL = "Music/C2V"

/**
 * [RecordingRecord] → [AudioItem] 필드 매핑 (URI는 호출자가 주입).
 * [RecordingRepository.uriFor]와 분리해 JVM 단위 테스트 가능.
 * fileName = [recordingAudioItemTitle], folderLabel = [RECORDING_AUDIO_FOLDER_LABEL].
 */
internal fun mapRecordingToAudioItem(
    record: RecordingRecord,
    uri: Uri,
): AudioItem {
    val title = recordingAudioItemTitle(record.filePath)
    return AudioItem(
        id = recordingAudioItemId(record.id),
        title = title,
        artist = null,
        durationMs = record.durationMs,
        uri = uri,
        dateAdded = record.createdAt,
        fileName = title,
        folderLabel = RECORDING_AUDIO_FOLDER_LABEL,
    )
}

/**
 * uriFor 호출 후 [mapRecordingToAudioItem] — Repository·fake wiring SSOT.
 * JVM 테스트: fake uriFor가 동일 record를 받는지 검증.
 */
internal fun bindRecordingToAudioItem(
    record: RecordingRecord,
    uriFor: (RecordingRecord) -> Uri,
): AudioItem = mapRecordingToAudioItem(record, uriFor(record))

/** [RecordingRepository.uriFor] 주입 — JVM 테스트에서 fake wiring 검증용. */
internal fun RecordingRecord.toAudioItem(uriFor: (RecordingRecord) -> Uri): AudioItem =
    bindRecordingToAudioItem(this, uriFor)

/** RecordingRecord → AudioItem. id = [recordingAudioItemId]. */
internal fun RecordingRecord.toAudioItem(repository: RecordingRepository): AudioItem =
    toAudioItem(repository::uriFor)
