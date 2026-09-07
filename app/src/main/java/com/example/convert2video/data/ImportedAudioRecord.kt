package com.example.convert2video.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Import로 앱 저장소에 복사된 오디오 1건. */
@Entity(tableName = "imported_audio_records")
data class ImportedAudioRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val originalDisplayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** epoch ms */
    val createdAt: Long,
)
