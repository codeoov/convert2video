package com.example.convert2video.ui.screens.options

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.convert2video.R
import com.example.convert2video.data.PendingCountdown
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.record.COUNTDOWN_MINUTES_RANGE
import com.example.convert2video.record.DEFAULT_COUNTDOWN_DURATION_MINUTES
import com.example.convert2video.record.DEFAULT_COUNTDOWN_START_IN_MINUTES
import com.example.convert2video.record.RecordingCountdownAlarmScheduler
import com.example.convert2video.record.clampCountdownMinutes
import com.example.convert2video.record.countdownTriggerElapsedMillis
import com.example.convert2video.record.isCountdownMinutesInRange
import com.example.convert2video.record.shouldPersistPendingCountdownAfterRegister
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "RecordingCountdownViewModel"

/**
 * Options Quick Timer — START 알람 등록 **성공 후에만** DataStore persist.
 * Exact-alarm 배너는 Screen 스케줄 섹션 SSOT (여기서 복제하지 않음).
 */
class RecordingCountdownViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    val pendingCountdown: StateFlow<PendingCountdown?> = settingsRepository.pendingCountdown
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private val _startInMinutes = MutableStateFlow(DEFAULT_COUNTDOWN_START_IN_MINUTES)
    val startInMinutes: StateFlow<Int> = _startInMinutes.asStateFlow()

    private val _durationMinutes = MutableStateFlow(DEFAULT_COUNTDOWN_DURATION_MINUTES)
    val durationMinutes: StateFlow<Int> = _durationMinutes.asStateFlow()

    private val _isInFlight = MutableStateFlow(false)
    val isInFlight: StateFlow<Boolean> = _isInFlight.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    fun decrementStartIn() {
        _startInMinutes.value = clampCountdownMinutes(_startInMinutes.value - 1)
    }

    fun incrementStartIn() {
        _startInMinutes.value = clampCountdownMinutes(_startInMinutes.value + 1)
    }

    fun decrementDuration() {
        _durationMinutes.value = clampCountdownMinutes(_durationMinutes.value - 1)
    }

    fun incrementDuration() {
        _durationMinutes.value = clampCountdownMinutes(_durationMinutes.value + 1)
    }

    /**
     * registerStart true 일 때만 [SettingsRepository.setPendingCountdown].
     * persist 실패 시 알람 cancel (Receiver가 duration 없이 START 하지 않게).
     */
    fun startCountdown() {
        if (_isInFlight.value) return
        if (pendingCountdown.value != null) return
        val startIn = _startInMinutes.value
        val duration = _durationMinutes.value
        if (!isCountdownMinutesInRange(startIn) || !isCountdownMinutesInRange(duration)) {
            return
        }
        viewModelScope.launch {
            if (_isInFlight.value) return@launch
            _isInFlight.value = true
            try {
                val app = getApplication<Application>()
                val nowElapsed = SystemClock.elapsedRealtime()
                val registered = RecordingCountdownAlarmScheduler.registerStart(
                    app,
                    startIn,
                    nowElapsed,
                )
                if (!shouldPersistPendingCountdownAfterRegister(registered)) {
                    AppLogger.e(TAG, "startCountdown: registerStart failed — skip persist")
                    _userMessage.emit(
                        appString(R.string.options_recording_countdown_alarm_register_failed),
                    )
                    return@launch
                }
                val triggerAt = countdownTriggerElapsedMillis(nowElapsed, startIn)
                try {
                    settingsRepository.setPendingCountdown(triggerAt, duration)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    AppLogger.e(TAG, "startCountdown: persist failed — cancel alarm", e)
                    RecordingCountdownAlarmScheduler.cancel(app)
                    _userMessage.emit(
                        appString(R.string.options_recording_countdown_save_failed),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "startCountdown failed", e)
                _userMessage.emit(
                    appString(R.string.options_recording_countdown_save_failed),
                )
            } finally {
                _isInFlight.value = false
            }
        }
    }

    fun cancelCountdown() {
        if (_isInFlight.value) return
        viewModelScope.launch {
            if (_isInFlight.value) return@launch
            _isInFlight.value = true
            try {
                val app = getApplication<Application>()
                RecordingCountdownAlarmScheduler.cancel(app)
                settingsRepository.clearPendingCountdown()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "cancelCountdown failed", e)
                _userMessage.emit(
                    appString(R.string.options_recording_countdown_save_failed),
                )
            } finally {
                _isInFlight.value = false
            }
        }
    }
}

internal fun isCountdownStepperAtMin(value: Int): Boolean =
    value <= COUNTDOWN_MINUTES_RANGE.first

internal fun isCountdownStepperAtMax(value: Int): Boolean =
    value >= COUNTDOWN_MINUTES_RANGE.last
