package com.example.convert2video.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 녹음 완료 이력 1건. [format]은 RecordingFormat.name ("AAC" | "WAV"). */
@Entity(tableName = "recording_records")
data class RecordingRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    /** RecordingFormat.name — "AAC" | "WAV" */
    val format: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** epoch ms */
    val createdAt: Long,
    /** Stable identifier for one recording backup identity. Zero is legacy/unassigned only. */
    val backupId: Long = 0,
)
