package com.example.convert2video.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 예약 녹음 스케줄 1건.
 * [repeatMode]는 [com.example.convert2video.record.RecordingScheduleRepeatMode.name]
 * (`ONCE` | `WEEKLY` | `DAILY`).
 *
 * - overnight: [endMinuteOfDay] < [startMinuteOfDay] 허용(자정 넘김).
 * - **동일 분 금지**: [startMinuteOfDay] == [endMinuteOfDay] 불가 (길이 0 구간).
 */
@Entity(tableName = "recording_schedules")
data class RecordingSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    /** RecordingScheduleRepeatMode.name — "ONCE" | "WEEKLY" | "DAILY" */
    val repeatMode: String,
    /**
     * WEEKLY: bit0=월 .. bit6=일 (월요일 시작, Monday-start). **최소 1 bit 필수**(0 금지).
     * ONCE/DAILY: **저장값 0, 해석 무시** (요일 필터에 쓰지 말 것).
     */
    val daysOfWeekMask: Int,
    val enabled: Boolean,
    /** epoch ms */
    val createdAt: Long,
)
