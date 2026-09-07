package com.example.convert2video.record

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.example.convert2video.MainActivity
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.flow.first

/** Quick Settings 타일 권한 유도 PendingIntent requestCode (90001). */
internal const val REQ_LAUNCH_TILE = 90001

/** Glance QuickRecord 위젯 권한 유도 PendingIntent requestCode (90002). [EXTRA_QUICK_RECORD] 미사용. */
internal const val REQ_LAUNCH_WIDGET = 90002

internal enum class RecordingQuickClickBranch {
    Stop,
    StartRecording,
    LaunchAppForPermission,
    ReviewPending,
}

/** [performRecordingQuickClick] 대상 — JVM 테스트용 fake 주입. */
internal interface RecordingQuickActionHost {
    val quickActionState: RecordingState
    fun quickActionStart(format: RecordingFormat)
    fun quickActionStop()
}

internal fun RecordingController.asQuickActionHost(): RecordingQuickActionHost =
    object : RecordingQuickActionHost {
        override val quickActionState: RecordingState
            get() = state.value

        override fun quickActionStart(format: RecordingFormat) = start(format)

        override fun quickActionStop() = stop()
    }

internal fun resolveRecordingQuickClickBranch(
    state: RecordingState,
    hasRecordAudioPermission: Boolean,
): RecordingQuickClickBranch = when {
    state is RecordingState.Review -> RecordingQuickClickBranch.ReviewPending
    isActiveRecordingSession(state) -> RecordingQuickClickBranch.Stop
    hasRecordAudioPermission -> RecordingQuickClickBranch.StartRecording
    else -> RecordingQuickClickBranch.LaunchAppForPermission
}

internal fun hasRecordAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

internal suspend fun resolveRecordingFormatForQuickAction(context: Context): RecordingFormat =
    SettingsRepository.recordingFormatHot.value
        ?: SettingsRepository(context).recordingFormat.first()

/**
 * Tile API34+ [TileService.startActivityAndCollapse(PendingIntent)] 또는
 * Widget·레거시 Tile [PendingIntent.send] — [scheduledRecordingAppLaunchPendingIntent] 전달 seam.
 */
internal fun launchRecordingPermissionPendingIntent(
    pendingIntent: PendingIntent,
    tileService: TileService? = null,
) {
    when {
        tileService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            runCatching { tileService.startActivityAndCollapse(pendingIntent) }
                .onFailure { e ->
                    AppLogger.w(
                        TAG,
                        "launchRecordingPermission (tile API34+) failed: ${e.message}",
                        e,
                    )
                }
        }
        tileService != null -> {
            runCatching {
                @Suppress("DEPRECATION")
                tileService.startActivityAndCollapse(
                    Intent(tileService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                )
            }.onFailure { e ->
                AppLogger.w(
                    TAG,
                    "launchRecordingPermission (tile legacy) failed: ${e.message}",
                    e,
                )
            }
        }
        else -> {
            runCatching { pendingIntent.send() }
                .onFailure { e ->
                    AppLogger.w(
                        TAG,
                        "launchRecordingPermission (widget) failed: ${e.message}",
                        e,
                    )
                }
        }
    }
}

/**
 * Tile·Widget 공통 onClick — [RecordingQuickActionHost]만 호출.
 *
 * ReviewPending은 silent no-op이 아니다 — 앱을 열어 Review UI로 보낸다 (Start 금지).
 *
 * @param launchAppForPermission 권한 거부 또는 ReviewPending 시 앱 실행 PendingIntent 전달.
 */
internal suspend fun performRecordingQuickClick(
    context: Context,
    host: RecordingQuickActionHost,
    permissionLaunchRequestCode: Int,
    launchAppForPermission: (PendingIntent) -> Unit,
) {
    when (
        resolveRecordingQuickClickBranch(
            state = host.quickActionState,
            hasRecordAudioPermission = hasRecordAudioPermission(context),
        )
    ) {
        RecordingQuickClickBranch.Stop -> host.quickActionStop()
        RecordingQuickClickBranch.StartRecording -> {
            val format = resolveRecordingFormatForQuickAction(context)
            host.quickActionStart(format)
        }
        RecordingQuickClickBranch.LaunchAppForPermission -> {
            launchAppForPermission(
                scheduledRecordingAppLaunchPendingIntent(context, permissionLaunchRequestCode),
            )
        }
        RecordingQuickClickBranch.ReviewPending -> {
            launchAppForPermission(
                scheduledRecordingAppLaunchPendingIntent(context, permissionLaunchRequestCode),
            )
        }
    }
}

internal suspend fun performRecordingQuickClick(
    context: Context,
    controller: RecordingController,
    permissionLaunchRequestCode: Int,
    launchAppForPermission: (PendingIntent) -> Unit,
) {
    performRecordingQuickClick(
        context = context,
        host = controller.asQuickActionHost(),
        permissionLaunchRequestCode = permissionLaunchRequestCode,
        launchAppForPermission = launchAppForPermission,
    )
}

private const val TAG = "RecordingQuickAction"
