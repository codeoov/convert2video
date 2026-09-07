package com.example.convert2video.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upload_records")
data class UploadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoUri: String,
    val youtubeVideoId: String,
    val watchUrl: String,
    /** Epoch milliseconds ([System.currentTimeMillis]). */
    val uploadedAt: Long,
)
