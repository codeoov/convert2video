package com.example.convert2video.data

import android.net.Uri
import java.io.File

/** imported_audio_records.id → AudioItem.id SSOT — 녹음 id 영역과 분리. */
internal const val IMPORTED_AUDIO_ID_OFFSET = 1_000_000_000_000L

internal fun importedAudioItemId(importedId: Long): Long = -importedId - IMPORTED_AUDIO_ID_OFFSET

internal fun importedIdFromAudioItemId(audioItemId: Long): Long? =
    if (audioItemId <= -IMPORTED_AUDIO_ID_OFFSET) {
        -audioItemId - IMPORTED_AUDIO_ID_OFFSET
    } else {
        null
    }

/** mapImportedToAudioItem folderLabel SSOT — 리터럴 중복 금지. */
internal const val IMPORTED_AUDIO_FOLDER_LABEL = "Music/C2VImported"

internal fun importedAudioItemTitle(filePath: String): String = File(filePath).name

internal fun mapImportedToAudioItem(
    record: ImportedAudioRecord,
    uri: Uri,
): AudioItem {
    val title = record.originalDisplayName.ifBlank { importedAudioItemTitle(record.filePath) }
    return AudioItem(
        id = importedAudioItemId(record.id),
        title = title,
        artist = null,
        durationMs = record.durationMs,
        uri = uri,
        dateAdded = record.createdAt,
        fileName = importedAudioItemTitle(record.filePath),
        folderLabel = IMPORTED_AUDIO_FOLDER_LABEL,
    )
}

internal fun bindImportedToAudioItem(
    record: ImportedAudioRecord,
    uriFor: (ImportedAudioRecord) -> Uri,
): AudioItem = mapImportedToAudioItem(record, uriFor(record))

internal fun ImportedAudioRecord.toAudioItem(uriFor: (ImportedAudioRecord) -> Uri): AudioItem =
    bindImportedToAudioItem(this, uriFor)

internal fun ImportedAudioRecord.toAudioItem(repository: ImportedAudioRepository): AudioItem =
    toAudioItem(repository::uriFor)
