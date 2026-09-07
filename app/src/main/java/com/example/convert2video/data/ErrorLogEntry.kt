package com.example.convert2video.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 영속 에러 로그 항목. 레벨은 "E" / "W" / "D" 중 하나. */
@Entity(tableName = "error_log_entries")
data class ErrorLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "E" / "W" / "D" */
    val level: String,
    val tag: String,
    val message: String,
    /** throwable?.stackTraceToString() — null이면 스택 없음. */
    val stackTrace: String? = null,
    /** System.currentTimeMillis() epoch ms. */
    val createdAt: Long,
)
