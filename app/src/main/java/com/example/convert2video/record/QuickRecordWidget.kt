package com.example.convert2video.record

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.example.convert2video.R
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.requireApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 1×1 홈 Glance 위젯 — 탭 시 [RecordingTileService]와 동일 3-way 녹음 트리거.
 * RecordingController만 호출; Service/Engine/MediaRecorder/bindService 직접 참조 금지.
 */
class QuickRecordWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            QuickRecordWidgetContent(context)
        }
    }

    @Composable
    private fun QuickRecordWidgetContent(context: Context) {
        val app = context.requireApplication()
        val controller = RecordingController.getInstance(app)
        val state = controller.state.value
        val hasPermission = hasRecordAudioPermission(context)
        val branch = resolveRecordingQuickClickBranch(state, hasPermission)
        val isActive = isActiveRecordingSession(state)
        val isReview = state is RecordingState.Review
        val showPermissionUi = branch == RecordingQuickClickBranch.LaunchAppForPermission

        val backgroundColor = when {
            showPermissionUi -> QuickRecordWidgetColors.permissionBackground
            isActive -> QuickRecordWidgetColors.activeBackground
            isReview -> QuickRecordWidgetColors.permissionBackground
            else -> QuickRecordWidgetColors.idleBackground
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(backgroundColor)
                .clickable(actionRunCallback<QuickRecordActionCallback>()),
            contentAlignment = Alignment.Center,
        ) {
            if (showPermissionUi) {
                Text(
                    text = context.getString(R.string.quick_record_widget_permission_needed),
                    style = TextStyle(
                        textAlign = TextAlign.Center,
                        color = QuickRecordWidgetColors.permissionText,
                    ),
                    maxLines = 3,
                )
            } else if (isReview) {
                Text(
                    text = context.getString(R.string.recording_notification_review),
                    style = TextStyle(
                        textAlign = TextAlign.Center,
                        color = QuickRecordWidgetColors.permissionText,
                    ),
                    maxLines = 3,
                )
            } else {
                Image(
                    provider = ImageProvider(android.R.drawable.ic_btn_speak_now),
                    contentDescription = context.getString(
                        if (isActive) {
                            R.string.recording_notification_recording
                        } else {
                            R.string.quick_record_widget_description
                        },
                    ),
                )
            }
        }
    }
}

class QuickRecordActionCallback : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters,
    ) {
        withContext(Dispatchers.Main.immediate) {
            val app = context.requireApplication()
            val controller = RecordingController.getInstance(app)
            runCatching {
                performRecordingQuickClick(
                    context = context,
                    controller = controller,
                    permissionLaunchRequestCode = REQ_LAUNCH_WIDGET,
                    launchAppForPermission = { pendingIntent ->
                        launchRecordingPermissionPendingIntent(pendingIntent)
                    },
                )
            }.onFailure { e ->
                AppLogger.w(TAG, "QuickRecordActionCallback failed: ${e.message}", e)
            }
            runCatching {
                quickRecordGlanceWidget.update(context, glanceId)
            }.onFailure { e ->
                AppLogger.w(TAG, "widget update after action failed: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "QuickRecordActionCallback"
    }
}
