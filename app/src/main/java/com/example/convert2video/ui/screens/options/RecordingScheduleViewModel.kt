package com.example.convert2video.ui.screens.options

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.RecordingSchedule
import com.example.convert2video.data.RecordingScheduleRepository
import com.example.convert2video.record.RecordingScheduleAlarmScheduler
import com.example.convert2video.record.RecordingScheduleRepeatMode
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.appString
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "RecordingScheduleViewModel"
private val MINUTE_OF_DAY_RANGE = 0..1439

/**
 * Options「예약 녹음」목록 CRUD + AlarmManager 배선 (D-5).
 * Room 성공 후 알람 등록. registerStart 실패 시 DB↔알람 정합을 위해 롤백한다.
 */
class RecordingScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingScheduleRepository(
        AppDatabase.getInstance(application).recordingScheduleDao(),
    )

    val schedules: StateFlow<List<RecordingSchedule>> = repository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private val _writeSucceeded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val writeSucceeded: SharedFlow<Unit> = _writeSucceeded.asSharedFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /**
     * insert 후 registerStart 실패 시 orphan row delete + 메시지.
     * id=0 / register 실패 시 writeSucceeded 미emit.
     */
    fun insertSchedule(
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        repeatMode: RecordingScheduleRepeatMode,
        daysOfWeekMask: Int,
    ) {
        if (_isSaving.value) return
        viewModelScope.launch {
            if (_isSaving.value) return@launch
            if (!validateBeforeWrite(startMinuteOfDay, endMinuteOfDay, repeatMode, daysOfWeekMask)) {
                return@launch
            }
            _isSaving.value = true
            try {
                val toInsert = RecordingSchedule(
                    startMinuteOfDay = startMinuteOfDay,
                    endMinuteOfDay = endMinuteOfDay,
                    repeatMode = repeatMode.name,
                    daysOfWeekMask = normalizedMask(repeatMode, daysOfWeekMask),
                    enabled = true,
                    createdAt = System.currentTimeMillis(),
                )
                val id = repository.insert(toInsert)
                if (id == 0L) {
                    AppLogger.e(TAG, "insertSchedule: insert returned id=0 — skip registerStart")
                    _userMessage.emit(
                        appString(R.string.options_recording_schedule_alarm_register_failed),
                    )
                    return@launch
                }
                val registered = RecordingScheduleAlarmScheduler.registerStart(
                    getApplication(),
                    toInsert.copy(id = id),
                )
                if (shouldDeleteOrphanAfterInsertRegisterFailure(registered)) {
                    AppLogger.e(TAG, "insertSchedule: registerStart failed — delete orphan id=$id")
                    runCatching { repository.deleteById(id) }
                        .onFailure { e ->
                            AppLogger.e(TAG, "insertSchedule: orphan delete failed id=$id", e)
                        }
                    _userMessage.emit(
                        appString(R.string.options_recording_schedule_alarm_register_failed),
                    )
                    return@launch
                }
                _writeSucceeded.emit(Unit)
            } catch (e: IllegalArgumentException) {
                AppLogger.w(TAG, "insertSchedule rejected: ${e.message}")
                _userMessage.emit(appString(R.string.options_recording_schedule_save_failed))
            } catch (e: Exception) {
                AppLogger.e(TAG, "insertSchedule failed", e)
                _userMessage.emit(appString(R.string.options_recording_schedule_save_failed))
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * update 후 registerStart 실패 시 previous 복구.
     * 복구 불가하면 setEnabled(false)+cancel.
     */
    fun updateSchedule(
        existing: RecordingSchedule,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        repeatMode: RecordingScheduleRepeatMode,
        daysOfWeekMask: Int,
    ) {
        if (_isSaving.value) return
        viewModelScope.launch {
            if (_isSaving.value) return@launch
            if (!validateBeforeWrite(startMinuteOfDay, endMinuteOfDay, repeatMode, daysOfWeekMask)) {
                return@launch
            }
            _isSaving.value = true
            try {
                val updated = existing.copy(
                    startMinuteOfDay = startMinuteOfDay,
                    endMinuteOfDay = endMinuteOfDay,
                    repeatMode = repeatMode.name,
                    daysOfWeekMask = normalizedMask(repeatMode, daysOfWeekMask),
                )
                repository.update(updated)
                if (updated.enabled) {
                    val registered = RecordingScheduleAlarmScheduler.registerStart(
                        getApplication(),
                        updated,
                    )
                    if (!registered) {
                        AppLogger.e(
                            TAG,
                            "updateSchedule: registerStart failed id=${updated.id} — recover",
                        )
                        recoverAfterUpdateRegisterFailure(existing, updated.id)
                        _userMessage.emit(
                            appString(R.string.options_recording_schedule_alarm_register_failed),
                        )
                        return@launch
                    }
                } else {
                    RecordingScheduleAlarmScheduler.cancel(getApplication(), updated.id)
                }
                _writeSucceeded.emit(Unit)
            } catch (e: IllegalArgumentException) {
                AppLogger.w(TAG, "updateSchedule rejected: ${e.message}")
                _userMessage.emit(appString(R.string.options_recording_schedule_save_failed))
            } catch (e: Exception) {
                AppLogger.e(TAG, "updateSchedule failed", e)
                _userMessage.emit(appString(R.string.options_recording_schedule_save_failed))
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteById(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteById(id)
                RecordingScheduleAlarmScheduler.cancel(getApplication(), id)
            } catch (e: Exception) {
                AppLogger.e(TAG, "deleteById failed id=$id", e)
                _userMessage.emit(appString(R.string.options_recording_schedule_delete_failed))
            }
        }
    }

    /**
     * setEnabled(true) 후 registerStart 실패 시 enabled=false 롤백 + cancel + 메시지.
     */
    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setEnabled(id, enabled)
                if (enabled) {
                    val schedule = repository.getById(id)
                    if (schedule == null) {
                        AppLogger.e(
                            TAG,
                            "setEnabled(true): getById null id=$id — rollback enabled",
                        )
                        repository.setEnabled(id, false)
                        RecordingScheduleAlarmScheduler.cancel(getApplication(), id)
                        _userMessage.emit(
                            appString(R.string.options_recording_schedule_alarm_register_failed),
                        )
                        return@launch
                    }
                    val registered = RecordingScheduleAlarmScheduler.registerStart(
                        getApplication(),
                        schedule,
                    )
                    if (shouldRollbackEnabledOnRegisterFailure(registered)) {
                        AppLogger.e(
                            TAG,
                            "setEnabled(true): registerStart failed — rollback id=$id",
                        )
                        repository.setEnabled(id, false)
                        RecordingScheduleAlarmScheduler.cancel(getApplication(), id)
                        _userMessage.emit(
                            appString(R.string.options_recording_schedule_alarm_register_failed),
                        )
                    }
                } else {
                    RecordingScheduleAlarmScheduler.cancel(getApplication(), id)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "setEnabled failed id=$id", e)
                _userMessage.emit(appString(R.string.options_recording_schedule_save_failed))
            }
        }
    }

    private suspend fun recoverAfterUpdateRegisterFailure(
        previous: RecordingSchedule,
        updatedId: Long,
    ) {
        when (val action = decideUpdateAlarmFailureRecovery(previous)) {
            is UpdateAlarmFailureRecovery.Restore -> {
                try {
                    repository.update(action.previous)
                    if (action.previous.enabled) {
                        val restoredOk = RecordingScheduleAlarmScheduler.registerStart(
                            getApplication(),
                            action.previous,
                        )
                        if (!restoredOk) {
                            AppLogger.e(
                                TAG,
                                "recover: previous registerStart failed — disable id=$updatedId",
                            )
                            repository.setEnabled(updatedId, false)
                            RecordingScheduleAlarmScheduler.cancel(getApplication(), updatedId)
                        }
                    } else {
                        RecordingScheduleAlarmScheduler.cancel(getApplication(), updatedId)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "recover: restore previous failed id=$updatedId", e)
                    runCatching {
                        repository.setEnabled(updatedId, false)
                        RecordingScheduleAlarmScheduler.cancel(getApplication(), updatedId)
                    }.onFailure { rollbackError ->
                        AppLogger.e(
                            TAG,
                            "recover: disable+cancel failed id=$updatedId",
                            rollbackError,
                        )
                    }
                }
            }
            UpdateAlarmFailureRecovery.DisableAndCancel -> {
                repository.setEnabled(updatedId, false)
                RecordingScheduleAlarmScheduler.cancel(getApplication(), updatedId)
            }
        }
    }

    private suspend fun validateBeforeWrite(
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        repeatMode: RecordingScheduleRepeatMode,
        daysOfWeekMask: Int,
    ): Boolean {
        if (startMinuteOfDay !in MINUTE_OF_DAY_RANGE || endMinuteOfDay !in MINUTE_OF_DAY_RANGE) {
            _userMessage.emit(appString(R.string.options_recording_schedule_save_failed))
            return false
        }
        if (startMinuteOfDay == endMinuteOfDay) {
            _userMessage.emit(appString(R.string.options_recording_schedule_same_time_error))
            return false
        }
        if (repeatMode == RecordingScheduleRepeatMode.WEEKLY && daysOfWeekMask == 0) {
            _userMessage.emit(appString(R.string.options_recording_schedule_weekly_no_days_error))
            return false
        }
        return true
    }

    private fun normalizedMask(
        repeatMode: RecordingScheduleRepeatMode,
        daysOfWeekMask: Int,
    ): Int = when (repeatMode) {
        RecordingScheduleRepeatMode.ONCE,
        RecordingScheduleRepeatMode.DAILY,
        -> 0
        RecordingScheduleRepeatMode.WEEKLY -> daysOfWeekMask
    }
}

/** insert 후 registerStart 실패 → orphan delete. */
internal fun shouldDeleteOrphanAfterInsertRegisterFailure(registerOk: Boolean): Boolean =
    !registerOk

/** setEnabled(true) 후 registerStart 실패 → enabled 롤백. */
internal fun shouldRollbackEnabledOnRegisterFailure(registerOk: Boolean): Boolean =
    !registerOk

/** update 알람 실패 복구: previous 있으면 Restore, 없으면 DisableAndCancel. */
internal sealed class UpdateAlarmFailureRecovery {
    data class Restore(val previous: RecordingSchedule) : UpdateAlarmFailureRecovery()
    data object DisableAndCancel : UpdateAlarmFailureRecovery()
}

internal fun decideUpdateAlarmFailureRecovery(
    previous: RecordingSchedule?,
): UpdateAlarmFailureRecovery =
    if (previous != null) {
        UpdateAlarmFailureRecovery.Restore(previous)
    } else {
        UpdateAlarmFailureRecovery.DisableAndCancel
    }
