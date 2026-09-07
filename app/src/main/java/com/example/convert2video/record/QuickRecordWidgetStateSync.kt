package com.example.convert2video.record

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * [RecordingController.state] 중 위젯 UI에 반영되는 변화만 감지해 Glance 인스턴스를 갱신한다.
 * amplitude tick 등 [WidgetUiKey]에 없는 변화는 [distinctUntilChanged]로 [updateAll] 생략.
 * [RecordingTileService] collect 패턴과 대칭 — Controller/Service/Engine 직접 import 없음.
 *
 * Scope: [Convert2videoApplication.widgetSyncScope] 주입 — Application 수명과 동일.
 * Android는 일반적으로 [Application.onTerminate]를 호출하지 않으므로 명시 cancel 없음.
 */
internal object QuickRecordWidgetStateSync {
    private const val TAG = "QuickRecordWidgetSync"
    private const val PERMISSION_POLL_INTERVAL_MS = 2_000L

    @Volatile
    private var installed = false

    internal data class WidgetUiKey(
        val isActive: Boolean,
        val showPermissionUi: Boolean,
        val isReview: Boolean,
    )

    internal fun deriveWidgetUiKey(
        state: RecordingState,
        hasRecordAudioPermission: Boolean,
    ): WidgetUiKey = WidgetUiKey(
        isActive = isActiveRecordingSession(state),
        showPermissionUi = resolveRecordingQuickClickBranch(
            state = state,
            hasRecordAudioPermission = hasRecordAudioPermission,
        ) == RecordingQuickClickBranch.LaunchAppForPermission,
        isReview = state is RecordingState.Review,
    )

    fun install(application: Application, scope: CoroutineScope) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val controller = RecordingController.getInstance(application)
        scope.launch {
            val permissionFlow = flow {
                while (true) {
                    emit(hasRecordAudioPermission(application))
                    delay(PERMISSION_POLL_INTERVAL_MS)
                }
            }.distinctUntilChanged()

            combine(
                controller.state,
                permissionFlow,
            ) { state, hasPermission ->
                deriveWidgetUiKey(state, hasPermission)
            }
                .distinctUntilChanged()
                .collect {
                    runCatching {
                        quickRecordGlanceWidget.updateAll(application)
                    }.onFailure { e ->
                        AppLogger.w(TAG, "updateAll failed: ${e.message}", e)
                    }
                }
        }
    }
}
