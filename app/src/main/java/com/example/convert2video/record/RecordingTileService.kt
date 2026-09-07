package com.example.convert2video.record

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.convert2video.R
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Quick Settings 타일 진입점 — RecordingController만 호출. RecordingService/MediaRecorder/bindService 직접 참조 금지.
 *
 * onClick 흐름: [performRecordingQuickClick] SSOT —
 * Review→앱 열기(Review UI, silent no-op/Start 금지) /
 * 활성→stop / 권한+Idle→start / 권한거부→MainActivity.
 * [isActiveRecordingSession]은 Review에서 false (occupancy와 접지 금지).
 * 타일 라벨은 Review 시 [tileLabelResIdFor] = recording_notification_review.
 */
class RecordingTileService : TileService() {

    private val controller by lazy { RecordingController.getInstance(application) }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            recordingScopeExceptionHandler(TAG),
    )
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = scope.launch {
            controller.state.collect { state ->
                runCatching { applyToTile(state) }
                    .onFailure { e -> AppLogger.w(TAG, "applyToTile failed: ${e.message}", e) }
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        listenJob?.cancel()
        listenJob = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        applyToTile(controller.state.value)
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            performRecordingQuickClick(
                context = this@RecordingTileService,
                controller = controller,
                permissionLaunchRequestCode = REQ_LAUNCH_TILE,
                launchAppForPermission = { pendingIntent ->
                    launchRecordingPermissionPendingIntent(
                        pendingIntent = pendingIntent,
                        tileService = this@RecordingTileService,
                    )
                },
            )
        }
    }

    private fun applyToTile(state: RecordingState) {
        val tile = qsTile ?: return
        tile.state = if (isActiveRecordingSession(state)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(tileLabelResIdFor(state))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                is RecordingState.Failed -> getString(recordingErrorCodeToStringRes(state.errorCode))
                else -> getString(notificationTextResIdFor(state))
            }
        }
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        tile.updateTile()
    }

    companion object {
        private const val TAG = "RecordingTileService"
    }
}

/** Tile이 녹음 진행 중 상태인지 판단 — [RecordingState.Recording]/[RecordingState.Paused]/[RecordingState.Stopping]. Review는 false. */
internal fun isActiveRecordingSession(state: RecordingState): Boolean =
    state is RecordingState.Recording || state is RecordingState.Paused || state is RecordingState.Stopping

/** Review는 idle과 같은 INACTIVE여도 라벨을 구분한다 (tap-to-start처럼 보이지 않게). */
internal fun tileLabelResIdFor(state: RecordingState): Int =
    if (state is RecordingState.Review) R.string.recording_notification_review
    else R.string.recording_notification_title
