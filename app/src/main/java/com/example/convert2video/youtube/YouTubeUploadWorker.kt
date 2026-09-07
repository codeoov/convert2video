package com.example.convert2video.youtube

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.convert2video.R
import com.example.convert2video.data.UploadHistoryRepository
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.video.ConversionWorker
import kotlinx.coroutines.CancellationException

internal const val YOUTUBE_UPLOAD_SKIPPED_PREDECESSOR_LOG =
    "youtube upload skipped: predecessor itemFailed"

internal const val SIGNED_OUT_AFTER_HTTP_401_LOG = "signed out after HTTP 401"

private const val TAG = "YouTubeUploadWorker"

private data class YouTubeWorkerTestSeam(
    val capabilities: StoreCapabilities,
    val googleUpload: (suspend () -> ListenableWorker.Result)?,
)

/**
 * HTTP 401 only. 403 and null are no-ops. Non-CE signOut failures are swallowed so the
 * Worker can still fail with notification. [CancellationException] is rethrown.
 * Extra AppLogger.e is forbidden; success logs [SIGNED_OUT_AFTER_HTTP_401_LOG] at w.
 */
internal fun signOutIfUnauthorized(
    httpCode: Int?,
    signOut: () -> Unit,
) {
    if (httpCode != 401) return
    try {
        signOut()
        AppLogger.w(TAG, SIGNED_OUT_AFTER_HTTP_401_LOG)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLogger.w(TAG, "signOut after HTTP 401 failed: ${e.javaClass.simpleName}")
    }
}

/**
 * True when a chained [ConversionWorker] predecessor encoded a per-item soft-failure
 * ([ConversionWorker.KEY_ITEM_FAILED]). Manual enqueue (key absent) is unchanged.
 */
internal fun shouldSkipUploadForPredecessorFailure(input: Data): Boolean =
    input.getBoolean(ConversionWorker.KEY_ITEM_FAILED, false)

/** Empty success used when [shouldSkipUploadForPredecessorFailure] is true — no output keys. */
internal fun predecessorFailureSkipResult(): ListenableWorker.Result =
    ListenableWorker.Result.success()

/**
 * First doWork gate: predecessor soft-fail → empty success; otherwise null so the
 * worker continues to setForeground/auth/API.
 */
internal fun youtubeUploadPreludeResult(input: Data): ListenableWorker.Result? =
    if (shouldSkipUploadForPredecessorFailure(input)) predecessorFailureSkipResult() else null

internal fun youtubeUploadCapabilitySkipResult(
    capabilities: StoreCapabilities,
): ListenableWorker.Result? =
    if (!capabilities.supportsYouTube) ListenableWorker.Result.success() else null

internal suspend fun youtubeUploadDoWorkForCapabilities(
    capabilities: StoreCapabilities,
    googleUpload: suspend () -> ListenableWorker.Result,
): ListenableWorker.Result =
    youtubeUploadCapabilitySkipResult(capabilities) ?: googleUpload()

/**
 * Runs the resumable YouTube upload as a foreground-service-backed [CoroutineWorker], mirroring
 * [com.example.convert2video.video.ConversionWorker]'s structure (progress notification with
 * Cancel, separate completion/failure notification). Re-authorizes silently via the YouTube auth
 * gateway using [applicationContext] — if that now needs user consent (e.g. access
 * was revoked externally), a Worker has no UI to resolve it, so it fails cleanly instead.
 */
class YouTubeUploadWorker private constructor(
    context: Context,
    params: WorkerParameters,
    private val testSeam: YouTubeWorkerTestSeam?,
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    /** JVM seam keeps the real doWork entry while replacing only the Google side effect. */
    internal constructor(
        context: Context,
        params: WorkerParameters,
        capabilities: StoreCapabilities,
        googleUpload: (suspend () -> Result)? = null,
    ) : this(context, params, YouTubeWorkerTestSeam(capabilities, googleUpload))

    private val authGateway by lazy { createYouTubeAuthGateway(applicationContext) }
    private val apiClient by lazy { YouTubeApiClient() }
    private val uploadHistoryRepository by lazy {
        UploadHistoryRepository.create(applicationContext)
    }

    /**
     * Predecessor skip log ([YOUTUBE_UPLOAD_SKIPPED_PREDECESSOR_LOG]) is emitted only at this
     * doWork call site immediately before returning the prelude result.
     * [youtubeUploadPreludeResult] body and return value are unchanged.
     */
    override suspend fun doWork(): Result {
        return try {
            youtubeUploadDoWorkForCapabilities(testSeam?.capabilities ?: StoreCapabilities.current) {
                testSeam?.googleUpload?.invoke() ?: doGoogleUploadWork()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unexpected exception during YouTube upload", e)
            failWithNotification(applicationContext.getString(R.string.youtube_upload_failed_fallback))
        }
    }

    private suspend fun doGoogleUploadWork(): Result {
        youtubeUploadPreludeResult(inputData)?.let {
            AppLogger.w(TAG, YOUTUBE_UPLOAD_SKIPPED_PREDECESSOR_LOG)
            return it
        }
        setForeground(buildProgressForegroundInfo(percent = 0))

        val videoUri = inputData.getString(KEY_VIDEO_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return failWithNotification(
                applicationContext.getString(R.string.youtube_upload_error_no_video_uri),
            )
        val title = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: return failWithNotification(
                applicationContext.getString(R.string.youtube_upload_error_no_title),
            )
        val description = inputData.getString(KEY_DESCRIPTION).orEmpty()
        val privacyStatus = inputData.getString(KEY_PRIVACY_STATUS) ?: "private"

        val accessToken = when (val outcome = authGateway.requestAuthorization()) {
            is AuthorizationOutcome.Authorized -> outcome.accessToken
            is AuthorizationOutcome.NeedsConsent -> {
                // Revoked/expired since the last app-side consent — clear the stale local flag so
                // the UI naturally re-prompts for login instead of retrying the same failure.
                try {
                authGateway.signOut()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    AppLogger.w(TAG, "NeedsConsent signOut failed: ${e.javaClass.simpleName}")
                }
                AppLogger.e(TAG, "authorization NeedsConsent; signed out for re-login")
                return failWithNotification(
                    applicationContext.getString(R.string.youtube_upload_error_relogin),
                )
            }
            is AuthorizationOutcome.Failed -> {
                AppLogger.e(TAG, "authorization Failed")
                return failWithNotification(youTubeUserFacingMessage(outcome.message))
            }
        }

        val contentLength = queryContentLength(videoUri)
            ?: return failWithNotification(
                applicationContext.getString(R.string.youtube_upload_error_size_unreadable),
            )

        // Pre-validation (a): YouTube limit is 256 GB per file.
        if (contentLength > MAX_FILE_SIZE_BYTES) {
            return failWithNotification(
                applicationContext.getString(R.string.youtube_error_file_too_large),
            )
        }

        // Pre-validation (b): YouTube limit is 12 hours per video.
        // fail-closed: if duration cannot be determined, block the upload rather than let it through.
        val durationMs = queryVideoDurationMs(videoUri)
            ?: return failWithNotification(
                applicationContext.getString(R.string.youtube_error_duration_unreadable),
            )
        if (durationMs > MAX_DURATION_MS) {
            return failWithNotification(
                applicationContext.getString(R.string.youtube_error_duration_too_long),
            )
        }

        val sessionResult = apiClient.initiateResumableUpload(
            accessToken = accessToken,
            title = title,
            description = description,
            privacyStatus = privacyStatus,
            contentType = CONTENT_TYPE_MP4,
            contentLength = contentLength,
        )
        val sessionUrl = when (sessionResult) {
            is YouTubeApiResult.Success -> sessionResult.value
            is YouTubeApiResult.Failure -> {
                signOutIfUnauthorized(sessionResult.httpCode) { authGateway.signOut() }
                return failWithNotification(youTubeUserFacingMessage(sessionResult.message))
            }
        }

        var result: Result? = null
        apiClient.uploadVideoBytes(
            sessionUrl = sessionUrl,
            uri = videoUri,
            contentResolver = applicationContext.contentResolver,
            contentType = CONTENT_TYPE_MP4,
            contentLength = contentLength,
        ).collect { event ->
            when (event) {
                is UploadEvent.Progress -> {
                    setProgress(workDataOf(KEY_PROGRESS to event.percent))
                    setForeground(buildProgressForegroundInfo(event.percent))
                }
                is UploadEvent.Done -> {
                    result = when (val outcome = event.result) {
                        is YouTubeApiResult.Success -> {
                            val videoId = outcome.value
                            val watchUrl = "https://youtu.be/$videoId"
                            recordUploadResilient(
                                videoUri = videoUri,
                                youtubeVideoId = videoId,
                                watchUrl = watchUrl,
                            )
                            postResultNotification(success = true, message = null, watchUrl = watchUrl)
                            Result.success(
                                workDataOf(
                                    KEY_VIDEO_ID to videoId,
                                    KEY_WATCH_URL to watchUrl,
                                ),
                            )
                        }
                        is YouTubeApiResult.Failure -> {
                            signOutIfUnauthorized(outcome.httpCode) { authGateway.signOut() }
                            failWithNotification(youTubeUserFacingMessage(outcome.message))
                        }
                    }
                }
            }
        }

        return result ?: failWithNotification(
            applicationContext.getString(R.string.youtube_upload_error_unknown),
        )
    }

    /**
     * Persists upload history. Failures (including [IllegalArgumentException]) soft-end so
     * the Worker can still return [Result.success]. Non-IAE failures retry once with the same args.
     */
    private suspend fun recordUploadResilient(
        videoUri: Uri,
        youtubeVideoId: String,
        watchUrl: String,
    ) {
        try {
            uploadHistoryRepository.recordUpload(
                videoUri = videoUri,
                youtubeVideoId = youtubeVideoId,
                watchUrl = watchUrl,
            )
        } catch (e: IllegalArgumentException) {
            AppLogger.e(TAG, "recordUpload rejected args", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "recordUpload failed; retrying", e)
            try {
                uploadHistoryRepository.recordUpload(
                    videoUri = videoUri,
                    youtubeVideoId = youtubeVideoId,
                    watchUrl = watchUrl,
                )
            } catch (e2: IllegalArgumentException) {
                AppLogger.e(TAG, "recordUpload rejected args", e2)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                AppLogger.e(TAG, "recordUpload failed after retry", e2)
            }
        }
    }

    private fun queryContentLength(uri: Uri): Long? = try {
        applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.e(TAG, "queryContentLength failed", e)
        null
    }

    /**
     * Queries the video duration via [MediaMetadataRetriever], mirroring the
     * [com.example.convert2video.data.ConvertedVideoRepository] MediaStore duration pattern.
     * Works for both FileProvider URIs (new app storage) and MediaStore content URIs (legacy).
     * Returns null if the duration cannot be determined. Callers must reject the upload with
     * [failWithNotification] rather than skipping — this check is fail-closed.
     */
    private fun queryVideoDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "영상 길이 확인 실패", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "MediaMetadataRetriever 해제 실패", e)
            }
        }
    }

    private fun failWithNotification(message: String): Result {
        postResultNotification(success = false, message = message, watchUrl = null)
        return Result.failure(workDataOf(KEY_MESSAGE to message))
    }

    /**
     * Maps [YouTubeApiClient]/YouTube auth gateway raw 메시지(Korean 내부 리터럴)를
     * [youTubeFailureMessageToStringRes]로 로케일에 맞는 문자열로 매핑한다.
     */
    private fun youTubeUserFacingMessage(raw: String?): String {
        val (resId, code) = youTubeFailureMessageToStringRes(raw)
        return if (code != null) {
            applicationContext.getString(resId, code)
        } else {
            applicationContext.getString(resId)
        }
    }

    private fun buildProgressForegroundInfo(percent: Int): ForegroundInfo {
        ensureChannels()
        val notificationId = progressNotificationId()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, PROGRESS_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.youtube_upload_in_progress))
            .setContentText(
                applicationContext.getString(R.string.youtube_batch_item_progress, percent),
            )
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .addAction(0, applicationContext.getString(R.string.action_cancel), cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun postResultNotification(success: Boolean, message: String?, watchUrl: String?) {
        ensureChannels()
        val notificationId = resultNotificationId()
        val contentIntent = if (success && watchUrl != null) {
            PendingIntent.getActivity(
                applicationContext,
                notificationId,
                Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }
        val contentText = if (success) {
            applicationContext.getString(R.string.youtube_upload_success_body)
        } else {
            message ?: applicationContext.getString(R.string.youtube_upload_failed_fallback)
        }
        val notification = NotificationCompat.Builder(applicationContext, RESULT_CHANNEL_ID)
            .setContentTitle(
                if (success) applicationContext.getString(R.string.youtube_upload_success_title)
                else applicationContext.getString(R.string.youtube_upload_failed_title),
            )
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
        // Without POST_NOTIFICATIONS (API 33+) skip notify — silent no-op, not a crash.
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        }
    }

    /** Per-worker progress notification id derived from [id] (WorkManager UUID). */
    private fun progressNotificationId(): Int =
        PROGRESS_NOTIFICATION_ID + (id.hashCode() and 0xFFFF)

    /** Per-worker result notification id derived from [id] (WorkManager UUID). */
    private fun resultNotificationId(): Int =
        RESULT_NOTIFICATION_ID + (id.hashCode() and 0xFFFF)

    private fun ensureChannels() {
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(PROGRESS_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.youtube_upload_channel_progress))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(RESULT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(applicationContext.getString(R.string.youtube_upload_channel_result))
                .build(),
        )
    }

    companion object {
        const val KEY_VIDEO_URI = "videoUri"
        const val KEY_TITLE = "title"
        const val KEY_DESCRIPTION = "description"
        const val KEY_PRIVACY_STATUS = "privacyStatus"
        const val KEY_PROGRESS = "progress"
        const val KEY_VIDEO_ID = "videoId"
        const val KEY_WATCH_URL = "watchUrl"
        const val KEY_MESSAGE = "message"

        private const val CONTENT_TYPE_MP4 = "video/mp4"
        private const val PROGRESS_CHANNEL_ID = "youtube_upload_progress"
        private const val RESULT_CHANNEL_ID = "youtube_upload_result"
        private const val PROGRESS_NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002

        /** YouTube hard limit: 256 GB per upload. */
        private const val MAX_FILE_SIZE_BYTES = 256L * 1024 * 1024 * 1024

        /** YouTube hard limit: 12 hours per video. */
        private const val MAX_DURATION_MS = 12L * 3600 * 1000
    }
}
