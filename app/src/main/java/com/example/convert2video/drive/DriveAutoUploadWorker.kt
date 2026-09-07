package com.example.convert2video.drive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.convert2video.R
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private data class DriveWorkerTestSeam(
    val capabilities: StoreCapabilities,
    val googleUpload: (suspend () -> ListenableWorker.Result)?,
)

internal fun driveAutoUploadCapabilitySkipResult(
    capabilities: StoreCapabilities,
): ListenableWorker.Result? =
    if (!capabilities.supportsDrive) ListenableWorker.Result.success() else null

internal suspend fun driveAutoUploadDoWorkForCapabilities(
    capabilities: StoreCapabilities,
    googleUpload: suspend () -> ListenableWorker.Result,
): ListenableWorker.Result =
    driveAutoUploadCapabilitySkipResult(capabilities) ?: googleUpload()

/**
 * Runs a resumable Google Drive file upload as a foreground-service-backed [CoroutineWorker],
 * mirroring [com.example.convert2video.youtube.YouTubeUploadWorker]'s structure (progress
 * notification with Cancel, separate failure notification). Success is silent (no result
 * notification). Re-authorizes silently via the Drive auth gateway; if consent is needed again
 * the Worker fails cleanly (no UI to resolve PendingIntent).
 *
 * Never uses [Result.retry] / backoff — a failed recording upload is abandoned; the next
 * recording may succeed (failure copy explains this).
 *
 * Cancel must stay real cancellation: [CancellationException] is always rethrown so WorkManager
 * records CANCELLED, not FAILED. [uploadFileBytes] does not guarantee [DriveUploadEvent.Done]
 * after cancel.
 */
class DriveAutoUploadWorker private constructor(
    context: Context,
    params: WorkerParameters,
    private val testSeam: DriveWorkerTestSeam?,
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    /** JVM seam keeps the real doWork entry while replacing only the Google side effect. */
    internal constructor(
        context: Context,
        params: WorkerParameters,
        capabilities: StoreCapabilities,
        googleUpload: (suspend () -> Result)? = null,
    ) : this(context, params, DriveWorkerTestSeam(capabilities, googleUpload))

    private val authGateway by lazy { createDriveAuthGateway(applicationContext) }
    private val apiClient by lazy { GoogleDriveApiClient() }
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    override suspend fun doWork(): Result {
        return driveAutoUploadDoWorkForCapabilities(testSeam?.capabilities ?: StoreCapabilities.current) {
            try {
                testSeam?.googleUpload?.invoke() ?: doUploadWork()
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "upload cancelled: ${e.javaClass.simpleName}")
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "unexpected failure: ${e.javaClass.simpleName}")
                failWithCause(
                    applicationContext.getString(R.string.drive_auto_upload_error_unknown),
                )
            }
        }
    }

    private suspend fun doUploadWork(): Result {
        setForeground(buildProgressForegroundInfo(percent = 0))

        val fileUri = inputData.getString(KEY_FILE_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return failWithCause(
                applicationContext.getString(R.string.drive_auto_upload_error_no_file_uri),
            )
        val displayName = inputData.getString(KEY_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
            ?: return failWithCause(
                applicationContext.getString(R.string.drive_auto_upload_error_no_display_name),
            )
        val contentType = inputData.getString(KEY_CONTENT_TYPE)?.takeIf { it.isNotBlank() }
            ?: return failWithCause(
                applicationContext.getString(R.string.drive_auto_upload_error_no_content_type),
            )

        val accessToken = when (val outcome = authGateway.requestAuthorization()) {
            is DriveAuthorizationOutcome.Authorized -> outcome.accessToken
            is DriveAuthorizationOutcome.NeedsConsent -> {
                authGateway.signOut()
                return failWithFullMessage(
                    applicationContext.getString(R.string.drive_auto_upload_error_relogin),
                )
            }
            is DriveAuthorizationOutcome.Failed -> {
                // Never surface AuthManager detail (e.g. statusCode) in the user notification.
                AppLogger.e(TAG, "authorization Failed")
                return failWithCause(
                    applicationContext.getString(R.string.drive_auto_upload_error_auth_failed),
                )
            }
        }

        val contentLength = queryContentLength(fileUri)
            ?: return failWithCause(
                applicationContext.getString(R.string.drive_auto_upload_error_size_unreadable),
            )

        if (contentLength <= 0L) {
            return failWithCause(
                applicationContext.getString(R.string.drive_auto_upload_error_size_unreadable),
            )
        }

        if (contentLength > MAX_FILE_SIZE_BYTES) {
            return failWithCause(
                applicationContext.getString(R.string.drive_auto_upload_error_file_too_large),
            )
        }

        val sessionUrl = when (
            val sessionResult = initiateWithFolderRefreshOnce(
                accessToken = accessToken,
                displayName = displayName,
                contentType = contentType,
                contentLength = contentLength,
            )
        ) {
            is DriveApiResult.Success -> sessionResult.value
            is DriveApiResult.Failure -> return failWithCause(driveUserFacingMessage(sessionResult.message))
        }

        var result: Result? = null
        apiClient.uploadFileBytes(
            sessionUrl = sessionUrl,
            uri = fileUri,
            contentResolver = applicationContext.contentResolver,
            contentType = contentType,
            contentLength = contentLength,
        ).collect { event ->
            if (isStopped) {
                throw CancellationException("DriveAutoUploadWorker stopped")
            }
            when (event) {
                is DriveUploadEvent.Progress -> {
                    setProgress(workDataOf(KEY_PROGRESS to event.percent))
                    setForeground(buildProgressForegroundInfo(event.percent))
                }
                is DriveUploadEvent.Done -> {
                    if (isStopped) {
                        throw CancellationException("DriveAutoUploadWorker stopped")
                    }
                    result = when (val outcome = event.result) {
                        is DriveApiResult.Success -> {
                            val uploaded = outcome.value
                            val data = if (uploaded.webViewLink != null) {
                                workDataOf(
                                    KEY_FILE_ID to uploaded.fileId,
                                    KEY_WEB_VIEW_LINK to uploaded.webViewLink,
                                )
                            } else {
                                workDataOf(KEY_FILE_ID to uploaded.fileId)
                            }
                            // Success is silent — no result notification (auto-upload UX).
                            Result.success(data)
                        }
                        is DriveApiResult.Failure -> failWithCause(driveUserFacingMessage(outcome.message))
                    }
                }
            }
        }

        if (isStopped) {
            throw CancellationException("DriveAutoUploadWorker stopped")
        }
        // Non-cancel null (Done never arrived) — real failure with notification.
        return result ?: failWithCause(
            applicationContext.getString(R.string.drive_auto_upload_error_unknown),
        )
    }

    /**
     * Initiates a resumable session. On parent-folder [DriveApiResult.Failure.httpCode] == 404,
     * clears the cached folder id, re-resolves with [forceRefresh]=true, and initiates **exactly
     * once more**. Further 404s (or any other failure) propagate without another refresh.
     */
    private suspend fun initiateWithFolderRefreshOnce(
        accessToken: String,
        displayName: String,
        contentType: String,
        contentLength: Long,
    ): DriveApiResult<String> {
        val folderId = when (val folderResult = resolveAppFolderId(accessToken, forceRefresh = false)) {
            is DriveApiResult.Success -> folderResult.value
            is DriveApiResult.Failure -> return folderResult
        }
        val first = apiClient.initiateResumableUpload(
            accessToken = accessToken,
            fileName = displayName,
            parentFolderId = folderId,
            contentType = contentType,
            contentLength = contentLength,
        )
        when (first) {
            is DriveApiResult.Success -> return first
            is DriveApiResult.Failure -> {
                if (first.httpCode != 404) return first
                authGateway.clearAppFolderId()
                val refreshedFolderId = when (
                    val refreshed = resolveAppFolderId(accessToken, forceRefresh = true)
                ) {
                    is DriveApiResult.Success -> refreshed.value
                    is DriveApiResult.Failure -> return refreshed
                }
                return apiClient.initiateResumableUpload(
                    accessToken = accessToken,
                    fileName = displayName,
                    parentFolderId = refreshedFolderId,
                    contentType = contentType,
                    contentLength = contentLength,
                )
            }
        }
    }

    /**
     * Resolves the app folder id: cache hit (unless [forceRefresh]), else find-or-create using
     * [resolveFolderName] and remember. API failures propagate as [DriveApiResult.Failure].
     */
    private suspend fun resolveAppFolderId(
        accessToken: String,
        forceRefresh: Boolean,
    ): DriveApiResult<String> {
        ensureFolderNameMigration()
        if (!forceRefresh) {
            authGateway.cachedAppFolderId?.takeIf { it.isNotBlank() }?.let { cached ->
                return DriveApiResult.Success(cached)
            }
        }
        val folderName = resolveFolderName()
        when (val findResult = apiClient.findFolder(accessToken, folderName)) {
            is DriveApiResult.Failure -> return findResult
            is DriveApiResult.Success -> {
                val existingId = findResult.value
                if (existingId != null) {
                    authGateway.rememberAppFolderId(existingId)
                    return DriveApiResult.Success(existingId)
                }
            }
        }
        return when (val createResult = apiClient.createFolder(accessToken, folderName)) {
            is DriveApiResult.Failure -> createResult
            is DriveApiResult.Success -> {
                authGateway.rememberAppFolderId(createResult.value)
                createResult
            }
        }
    }

    /**
     * 사용자가 지정한 폴더 이름을 반환한다. null이거나 trim 후 blank이면
     * [R.string.app_name]을 기본값으로 사용한다.
     */
    private suspend fun resolveFolderName(): String {
        val saved = settingsRepository.driveFolderName.first()
        return if (saved != null && saved.trim().isNotBlank()) {
            saved.trim()
        } else {
            applicationContext.getString(R.string.app_name)
        }
    }

    /**
     * 앱 폴더 이름 마이그레이션(v1) 1회 수행.
     * [SettingsRepository.driveFolderNameMigrationV1Done]이 false이면
     * 기존 캐시를 무효화하고 플래그를 true로 설정한다.
     * 기존 Drive 폴더("Convert2Video Recordings")는 방치하며, 캐시만 제거해
     * 다음 업로드 시 새 이름으로 find-or-create가 재수행되도록 한다.
     *
     * IOException은 삼켜서 업로드가 계속 진행되도록 한다. 플래그 저장 실패 시
     * 다음 업로드 시 재시도됨(clearAppFolderId는 idempotent).
     */
    private suspend fun ensureFolderNameMigration() {
        try {
            if (!settingsRepository.driveFolderNameMigrationV1Done.first()) {
                authGateway.clearAppFolderId()
                settingsRepository.setDriveFolderNameMigrationV1Done(true)
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "ensureFolderNameMigration DataStore I/O 오류 — 캐시 무효화 후 업로드 계속", e)
            // first() 실패 시에도 방어적으로 캐시 무효화(idempotent) — 업로드 차단하지 않음
            authGateway.clearAppFolderId()
        }
    }

    private fun queryContentLength(uri: Uri): Long? = try {
        applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        }
    } catch (e: Exception) {
        AppLogger.e(TAG, "queryContentLength failed: ${e.javaClass.simpleName}")
        null
    }

    /** Wraps [cause] in the auto-upload failure template and posts a failure notification. */
    private fun failWithCause(cause: String): Result {
        val message = applicationContext.getString(R.string.drive_auto_upload_failed_body, cause)
        return failWithFullMessage(message)
    }

    /** Posts [message] as-is (e.g. relogin copy) and returns [Result.failure]. */
    private fun failWithFullMessage(message: String): Result {
        postFailureNotification(message)
        return Result.failure(workDataOf(KEY_MESSAGE to message))
    }

    /**
     * Maps [GoogleDriveApiClient]/Drive auth gateway raw 메시지(Korean 내부 리터럴)를
     * [driveFailureMessageToStringRes]로 로케일에 맞는 문자열로 매핑한다.
     */
    private fun driveUserFacingMessage(raw: String?): String {
        val (resId, code) = driveFailureMessageToStringRes(raw)
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
            .setContentTitle(applicationContext.getString(R.string.drive_auto_upload_in_progress))
            .setContentText(
                applicationContext.getString(R.string.drive_auto_upload_progress_percent, percent),
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

    private fun postFailureNotification(message: String) {
        ensureChannels()
        val notificationId = failureNotificationId()
        val notification = NotificationCompat.Builder(applicationContext, FAILURE_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.drive_auto_upload_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
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

    /** Per-worker failure notification id derived from [id] (WorkManager UUID). */
    private fun failureNotificationId(): Int =
        FAILURE_NOTIFICATION_ID + (id.hashCode() and 0xFFFF)

    /** Creates channels once per process — Progress updates must not re-create channels. */
    private fun ensureChannels() {
        if (!channelsEnsured.compareAndSet(false, true)) return
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(PROGRESS_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.drive_auto_upload_channel_progress))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(FAILURE_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(applicationContext.getString(R.string.drive_auto_upload_channel_failure))
                .build(),
        )
    }

    companion object {
        const val KEY_FILE_URI = "fileUri"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_CONTENT_TYPE = "contentType"
        const val KEY_PROGRESS = "progress"
        const val KEY_FILE_ID = "fileId"
        const val KEY_WEB_VIEW_LINK = "webViewLink"
        const val KEY_MESSAGE = "message"

        private const val PROGRESS_CHANNEL_ID = "drive_auto_upload_progress"
        private const val FAILURE_CHANNEL_ID = "drive_auto_upload_failure"
        private const val PROGRESS_NOTIFICATION_ID = 3001
        private const val FAILURE_NOTIFICATION_ID = 3002
        private const val TAG = "DriveAutoUploadWorker"

        /** Defensive upload size cap: 5 GiB. */
        private const val MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024 * 1024

        private val channelsEnsured = AtomicBoolean(false)
    }
}
