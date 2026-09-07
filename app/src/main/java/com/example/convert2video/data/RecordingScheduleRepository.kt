package com.example.convert2video.data

import com.example.convert2video.record.RecordingScheduleRepeatMode
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "RecordingScheduleRepository"

/**
 * 예약 녹음 스케줄 Room CRUD 진입점.
 * UI/Worker는 [RecordingScheduleDao]를 직접 import하지 않는다. 파일 I/O·Alarm·Context는 포함하지 않는다.
 */
class RecordingScheduleRepository(
    private val dao: RecordingScheduleDao,
) {
    fun observeAll(): Flow<List<RecordingSchedule>> =
        dao.observeAll().map { list -> list.mapNotNull { tryNormalizeRecordingScheduleForRead(it) } }

    /** id로 1건 조회. 복구 불가 row는 normalize skip → null. */
    suspend fun getById(id: Long): RecordingSchedule? =
        dao.getById(id)?.let { tryNormalizeRecordingScheduleForRead(it) }

    suspend fun insert(schedule: RecordingSchedule): Long =
        dao.insert(normalizeRecordingScheduleForWrite(schedule))

    suspend fun update(schedule: RecordingSchedule) =
        dao.update(normalizeRecordingScheduleForWrite(schedule))

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun getAllEnabled(): List<RecordingSchedule> =
        dao.getAllEnabled().mapNotNull { tryNormalizeRecordingScheduleForRead(it) }
}

/**
 * 쓰기 경로 정규화·검증.
 *
 * - [repeatMode]: [RecordingScheduleRepeatMode.requireFromStorageValue] — `ONCE`|`WEEKLY`|`DAILY`만
 * - minute 0..1439; **동일 분 금지** (`start != end`); overnight(`end < start`) 허용
 * - WEEKLY: [daysOfWeekMask] 1..127 (최소 1 bit)
 * - ONCE/DAILY: mask → 0
 *
 * @throws IllegalArgumentException 검증 실패·unknown repeatMode
 */
internal fun normalizeRecordingScheduleForWrite(schedule: RecordingSchedule): RecordingSchedule {
    require(schedule.startMinuteOfDay in MINUTE_OF_DAY_RANGE) {
        "startMinuteOfDay must be in 0..1439, was ${schedule.startMinuteOfDay}"
    }
    require(schedule.endMinuteOfDay in MINUTE_OF_DAY_RANGE) {
        "endMinuteOfDay must be in 0..1439, was ${schedule.endMinuteOfDay}"
    }
    require(schedule.startMinuteOfDay != schedule.endMinuteOfDay) {
        "startMinuteOfDay and endMinuteOfDay must differ (same-minute forbidden); " +
            "was ${schedule.startMinuteOfDay}"
    }
    require(schedule.daysOfWeekMask in DAYS_OF_WEEK_MASK_RANGE) {
        "daysOfWeekMask must be in 0..127, was ${schedule.daysOfWeekMask}"
    }
    val mode = RecordingScheduleRepeatMode.requireFromStorageValue(schedule.repeatMode)
    val mask = when (mode) {
        RecordingScheduleRepeatMode.ONCE,
        RecordingScheduleRepeatMode.DAILY,
        -> 0
        RecordingScheduleRepeatMode.WEEKLY -> {
            require(schedule.daysOfWeekMask != 0) {
                "WEEKLY daysOfWeekMask must have at least 1 bit set, was 0"
            }
            schedule.daysOfWeekMask
        }
    }
    return schedule.copy(
        repeatMode = mode.name,
        daysOfWeekMask = mask,
    )
}

/**
 * 읽기/복구 정규화.
 * - unknown [repeatMode] → [RecordingScheduleRepeatMode.fromStorageValue] soft default(ONCE)
 * - ONCE/DAILY mask → 0
 * - 복구 불가(동일 분·분 범위·WEEKLY mask0·mask 범위) → [AppLogger.w] 후 null(skip)
 */
internal fun tryNormalizeRecordingScheduleForRead(schedule: RecordingSchedule): RecordingSchedule? {
    if (schedule.startMinuteOfDay !in MINUTE_OF_DAY_RANGE ||
        schedule.endMinuteOfDay !in MINUTE_OF_DAY_RANGE
    ) {
        AppLogger.w(
            TAG,
            "Skipping schedule id=${schedule.id}: minute out of range " +
                "(start=${schedule.startMinuteOfDay}, end=${schedule.endMinuteOfDay})",
        )
        return null
    }
    if (schedule.startMinuteOfDay == schedule.endMinuteOfDay) {
        AppLogger.w(
            TAG,
            "Skipping schedule id=${schedule.id}: same-minute forbidden " +
                "(minute=${schedule.startMinuteOfDay})",
        )
        return null
    }
    if (schedule.daysOfWeekMask !in DAYS_OF_WEEK_MASK_RANGE) {
        AppLogger.w(
            TAG,
            "Skipping schedule id=${schedule.id}: daysOfWeekMask out of range " +
                "(mask=${schedule.daysOfWeekMask})",
        )
        return null
    }
    val mode = RecordingScheduleRepeatMode.fromStorageValue(schedule.repeatMode)
    val mask = when (mode) {
        RecordingScheduleRepeatMode.ONCE,
        RecordingScheduleRepeatMode.DAILY,
        -> 0
        RecordingScheduleRepeatMode.WEEKLY -> {
            if (schedule.daysOfWeekMask == 0) {
                AppLogger.w(
                    TAG,
                    "Skipping schedule id=${schedule.id}: WEEKLY with daysOfWeekMask=0",
                )
                return null
            }
            schedule.daysOfWeekMask
        }
    }
    return schedule.copy(
        repeatMode = mode.name,
        daysOfWeekMask = mask,
    )
}

private val MINUTE_OF_DAY_RANGE = 0..1439
private val DAYS_OF_WEEK_MASK_RANGE = 0..0b1111111
