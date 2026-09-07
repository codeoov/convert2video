package com.example.convert2video.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 휴지통 항목 1건. [itemType]은 companion 상수만 사용한다 (TypeConverter 없음).
 *
 * [wasIndexed]는 Room INTEGER NOT NULL ([RecordingSchedule.enabled]와 동일).
 */
@Entity(tableName = "trashed_items")
data class TrashedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemType: String,
    val displayName: String,
    val trashFilePath: String,
    /** epoch ms */
    val deletedAt: Long,
    val wasIndexed: Boolean,
    val originalFilePath: String? = null,
    val durationMs: Long? = null,
    val recordingFormat: String? = null,
    val sourceAudioUri: String? = null,
    val mimeType: String? = null,
    /** Stable backup ID for a previously kept recording; null means no backup transition exists. */
    val recordingBackupId: Long? = null,
) {
    companion object {
        const val RECORDING_AUDIO = "RECORDING_AUDIO"
        const val IMPORTED_AUDIO = "IMPORTED_AUDIO"
        const val CONVERTED_VIDEO = "CONVERTED_VIDEO"

        fun isKnownItemType(itemType: String): Boolean = when (itemType) {
            RECORDING_AUDIO, IMPORTED_AUDIO, CONVERTED_VIDEO -> true
            else -> false
        }
    }
}
