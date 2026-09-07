package com.example.convert2video.record

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler

/**
 * AudioFocus 휴리스틱으로 통화 등 포커스 선점을 감지한다.
 *
 * pause는 [AudioManager]가 통화 모드(`MODE_IN_CALL` / `MODE_IN_COMMUNICATION`)일 때만
 * 적용한다 — 미디어 재생 등 일반 포커스 손실에는 반응하지 않는다.
 *
 * **권한 0:** `READ_PHONE_STATE` / TelephonyCallback 없음. 포커스를 요청하지 않는 VoIP는
 * 놓칠 수 있다(100% 통화 감지가 아님).
 *
 * **영구 LOSS:** [AudioManager.AUDIOFOCUS_LOSS] 이후 시스템이
 * [AudioManager.AUDIOFOCUS_GAIN] 을 보내지 않을 수 있다 — 사용자는 알림 Pause/Resume으로
 * 수동 resume. pause 대기 중에는 [abandon] 금지(GAIN을 받아야 함). 세션 종료 시에만 abandon.
 *
 * Deprecated `AudioManager.requestAudioFocus(listener, streamType, durationHint)` 금지 —
 * [AudioFocusRequest] + [AudioManager.requestAudioFocus] / [AudioManager.abandonAudioFocusRequest].
 */
internal class CallAudioFocusMonitor(
    private val backend: RecordingAudioFocusBackend,
) {
    fun request(onFocusChange: (Int) -> Unit): Boolean = backend.request(onFocusChange)

    fun abandon() = backend.abandon()
}

/**
 * AudioFocus 테스트 seam. 프로덕션: [AndroidRecordingAudioFocusBackend].
 */
internal interface RecordingAudioFocusBackend {
    /**
     * @return true if [AudioManager.AUDIOFOCUS_REQUEST_GRANTED]. 실패여도 호출부는 fail-open.
     */
    fun request(onFocusChange: (Int) -> Unit): Boolean

    fun abandon()
}

/** Production backend — [AudioFocusRequest] only (deprecated streamType overload 금지). */
internal class AndroidRecordingAudioFocusBackend(
    private val audioManager: AudioManager,
    private val listenerHandler: Handler? = null,
) : RecordingAudioFocusBackend {

    private var focusRequest: AudioFocusRequest? = null

    override fun request(onFocusChange: (Int) -> Unit): Boolean {
        abandon()
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            onFocusChange(focusChange)
        }
        val builder = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
        if (listenerHandler != null) {
            builder.setOnAudioFocusChangeListener(listener, listenerHandler)
        } else {
            builder.setOnAudioFocusChangeListener(listener)
        }
        val request = builder.build()
        focusRequest = request
        val result = audioManager.requestAudioFocus(request)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        val request = focusRequest ?: return
        focusRequest = null
        audioManager.abandonAudioFocusRequest(request)
    }
}

internal enum class CallAudioFocusAction {
    Pause,
    Resume,
    None,
}

/**
 * AudioFocus change → Service pause/resume 매핑 SSOT.
 *
 * - `LOSS` / `LOSS_TRANSIENT` + [RecordingState.Recording] + [isCallModeActive] → Pause
 *   (호출부가 `pausedByCallDetection = true`)
 * - `GAIN` / `GAIN_TRANSIENT` + [RecordingState.Paused] + flag → [CallAudioFocusAction.Resume]
 * - `LOSS_TRANSIENT_CAN_DUCK` → [CallAudioFocusAction.None] (duck은 캡처에 적용하지 않음)
 * - 사용자 pause(flag=false) + GAIN → None
 * - 이미 Paused + LOSS → None (flag 유지)
 * - Idle / Stopping / Review / Saved / Failed / Recording+GAIN → None
 * - Recording + LOSS이지만 !isCallModeActive → None (미디어 재생 등)
 *
 * 영구 LOSS 이후 GAIN이 없으면 매핑은 Resume을 만들지 않는다 — 수동 resume.
 */
internal fun resolveCallAudioFocusAction(
    focusChange: Int,
    state: RecordingState,
    pausedByCallDetection: Boolean,
    isCallModeActive: Boolean,
): CallAudioFocusAction {
    val isLoss = focusChange == AudioManager.AUDIOFOCUS_LOSS ||
        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    val isGain = focusChange == AudioManager.AUDIOFOCUS_GAIN ||
        focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    return when {
        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> CallAudioFocusAction.None
        isLoss && state is RecordingState.Recording && isCallModeActive -> CallAudioFocusAction.Pause
        isGain && state is RecordingState.Paused && pausedByCallDetection ->
            CallAudioFocusAction.Resume
        else -> CallAudioFocusAction.None
    }
}

/** 로그용 포커스 이름. 경로·전화번호 금지. */
internal fun audioFocusChangeLabel(focusChange: Int): String =
    when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "GAIN_TRANSIENT"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "GAIN_TRANSIENT_MAY_DUCK"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "GAIN_TRANSIENT_EXCLUSIVE"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> "OTHER($focusChange)"
    }
