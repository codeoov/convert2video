package com.example.convert2video.video

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.convert2video.MainActivity
import java.io.File

/**
 * Runs the audio+background -> MP4 export as a foreground-service-backed [CoroutineWorker] so it
 * survives the app leaving the foreground (SON-7), showing an ongoing progress notification with
 * a Cancel action and a separate completion/failure notification once done.
 */
class ConversionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val videoConverter = VideoConverter(applicationContext)

    override suspend fun doWork(): Result {
        setForeground(buildProgressForegroundInfo(percent = 0))

        val backgroundFilePath = inputData.getString(KEY_BACKGROUND_PATH)
            ?: return failWithNotification("배경화면 정보를 찾을 수 없습니다")
        val audioUri = inputData.getString(KEY_AUDIO_URI)?.let(Uri::parse)
            ?: return failWithNotification("오디오 파일 정보를 찾을 수 없습니다")

        val durationUs = videoConverter.audioDurationUsOrNull(audioUri)
            ?: return failWithNotification("오디오 파일을 읽을 수 없습니다. 다른 파일을 선택해 주세요.")

        val outputFile = File(applicationContext.cacheDir, "convert_${System.currentTimeMillis()}.mp4")

        var result: Result? = null
        videoConverter.convert(File(backgroundFilePath), audioUri, durationUs, outputFile).collect { event ->
            when (event) {
                is ConversionEvent.Progress -> {
                    setProgress(workDataOf(KEY_PROGRESS to event.percent))
                    setForeground(buildProgressForegroundInfo(event.percent))
                }
                is ConversionEvent.Done -> {
                    result = when (val outcome = event.result) {
                        is ConversionResult.Success -> {
                            try {
                                val existingNames =
                                    C2vOutputNames.queryExistingDisplayNames(applicationContext)
                                val displayName = C2vOutputNames.buildDisplayName(existingNames)
                                val savedUri = MediaStoreSaver.saveVideoToMovies(
                                    context = applicationContext,
                                    sourceFile = outcome.outputFile,
                                    displayName = displayName,
                                )
                                outcome.outputFile.delete()
                                postResultNotification(success = true, message = null)
                                Result.success(workDataOf(KEY_VIDEO_URI to savedUri.toString()))
                            } catch (e: Exception) {
                                val message = "변환은 완료됐지만 갤러리 저장에 실패했습니다"
                                postResultNotification(success = false, message = message)
                                Result.failure(workDataOf(KEY_MESSAGE to message))
                            }
                        }
                        is ConversionResult.Failure -> {
                            postResultNotification(success = false, message = outcome.message)
                            Result.failure(workDataOf(KEY_MESSAGE to outcome.message))
                        }
                    }
                }
            }
        }

        return result ?: failWithNotification("알 수 없는 오류로 변환이 중단되었습니다")
    }

    private fun failWithNotification(message: String): Result {
        postResultNotification(success = false, message = message)
        return Result.failure(workDataOf(KEY_MESSAGE to message))
    }

    private fun buildProgressForegroundInfo(percent: Int): ForegroundInfo {
        ensureChannels()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, PROGRESS_CHANNEL_ID)
            .setContentTitle("영상으로 변환 중")
            .setContentText("$percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .addAction(0, "취소", cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, type)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun postResultNotification(success: Boolean, message: String?) {
        ensureChannels()
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, RESULT_CHANNEL_ID)
            .setContentTitle(if (success) "변환 완료" else "변환 실패")
            .setContentText(if (success) "갤러리(Movies)에 저장했습니다" else (message ?: "변환에 실패했습니다"))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        // Posting without POST_NOTIFICATIONS granted (API 33+) is a silent no-op, not a crash (AC-7).
        NotificationManagerCompat.from(applicationContext).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun ensureChannels() {
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(PROGRESS_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("변환 진행률")
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(RESULT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("변환 결과")
                .build(),
        )
    }

    companion object {
        const val KEY_BACKGROUND_PATH = "backgroundFilePath"
        const val KEY_AUDIO_URI = "audioUri"
        const val KEY_PROGRESS = "progress"
        const val KEY_VIDEO_URI = "videoUri"
        const val KEY_MESSAGE = "message"

        private const val PROGRESS_CHANNEL_ID = "conversion_progress"
        private const val RESULT_CHANNEL_ID = "conversion_result"
        private const val PROGRESS_NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
    }
}
