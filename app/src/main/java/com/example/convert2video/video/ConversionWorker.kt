package com.example.convert2video.video

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker.Result
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.convert2video.MainActivity
import com.example.convert2video.R
import com.example.convert2video.billing.EntitlementRepository
import com.example.convert2video.data.ConversionHistoryRepository
import com.example.convert2video.data.requireSegmentTrioConsistent
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import java.io.File

/** Reads one entitlement snapshot, then invokes [block] with the frozen result. */
internal suspend fun <T> withEntitlementSnapshot(
    readProSnapshot: suspend () -> Boolean,
    block: suspend (isPro: Boolean) -> T,
): T {
    val isPro = try {
        readProSnapshot()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.e(ConversionWorker.TAG, "Entitlement snapshot failed; defaulting to free", e)
        false
    }
    return block(isPro)
}

/**
 * Runs the audio+background -> MP4 export as a foreground-service-backed [CoroutineWorker] so it
 * survives the app leaving the foreground (SON-7), showing an ongoing progress notification with
 * a Cancel action and a separate completion/failure notification once done.
 *
 * Optional segment keys (Sprint 7-2):
 * - Range set ([KEY_SEGMENT_START_US] + [KEY_SEGMENT_END_US]): clip window for convert
 * - Batch set ([KEY_SEGMENT_BATCH_ID] + [KEY_SEGMENT_INDEX] + [KEY_SEGMENT_TOTAL]): history + NofM name
 * Sets are independent; presence is decided by [Data] key existence (not getLong default 0).
 * Partial sets → soft-failure ([Result.success] + [KEY_ITEM_FAILED]) before convert, so a bad
 * item never cascade-cancels the rest of a chained batch/segment run. The convert-and-save body
 * is additionally wrapped by [runCatchingUnexpectedAsSoftFail] so an *unexpected* exception (e.g.
 * Media3 Transformer/MediaCodec resource contention) softens the same way instead of escaping
 * `doWork()` as [Result.failure], which would CANCEL the rest of a chained
 * `WorkContinuation` (`beginUniqueWork(...).then(...).then(...)`).
 */
class ConversionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val videoConverter = VideoConverter(applicationContext)
    private val conversionHistoryRepository =
        ConversionHistoryRepository.create(applicationContext)

    @Volatile
    private var channelsEnsured = false

    override suspend fun doWork(): Result {
        setForeground(buildProgressForegroundInfo(percent = 0))

        val backgroundFilePath = inputData.getString(KEY_BACKGROUND_PATH)
            ?: return failWithNotification(R.string.conversion_error_background_not_found)
        val audioUri = inputData.getString(KEY_AUDIO_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return failWithNotification(R.string.conversion_error_audio_uri_not_found)

        val segmentKeys = resolveSegmentKeys(inputData)
            ?: return failWithNotification(R.string.conversion_error_segment_input_invalid)

        val durationUs = videoConverter.audioDurationUsOrNull(audioUri)
            ?: return failWithNotification(R.string.conversion_error_audio_unreadable)

        // Duration + margin guards before convert (same pure helpers Converter uses).
        val rangeGuardError = validateResolvedRangeForConvert(segmentKeys.range, durationUs)
        if (rangeGuardError != null) {
            return failWithNotification(segmentErrorToStringRes(rangeGuardError))
        }

        return runCatchingUnexpectedAsSoftFail(
            onUnexpectedFailure = { failWithNotification(R.string.conversion_error_unexpected) },
        ) {
            val outputFile = File(applicationContext.cacheDir, "convert_${System.currentTimeMillis()}.mp4")

            val convertFlow = withEntitlementSnapshot(
                readProSnapshot = { EntitlementRepository.getInstance(applicationContext).isProSnapshot() },
            ) { isPro ->
                if (segmentKeys.range != null) {
                    videoConverter.convert(
                        backgroundImageFile = File(backgroundFilePath),
                        audioUri = audioUri,
                        audioDurationUs = durationUs,
                        outputFile = outputFile,
                        segmentStartUs = segmentKeys.range.startUs,
                        segmentEndUs = segmentKeys.range.endUs,
                        applyWatermark = !isPro,
                    )
                } else {
                    videoConverter.convert(
                        backgroundImageFile = File(backgroundFilePath),
                        audioUri = audioUri,
                        audioDurationUs = durationUs,
                        outputFile = outputFile,
                        applyWatermark = !isPro,
                    )
                }
            }

            var result: Result? = null
            convertFlow.collect { event ->
                when (event) {
                    is ConversionEvent.Progress -> {
                        setProgress(workDataOf(KEY_PROGRESS to event.percent))
                        setForeground(buildProgressForegroundInfo(event.percent))
                    }
                    is ConversionEvent.Done -> {
                        result = when (val outcome = event.result) {
                            is ConversionResult.Success -> {
                                val savedUriOrNull = try {
                                    val existingNames =
                                        C2vOutputNames.queryExistingDisplayNames(applicationContext)
                                    val displayName = C2vOutputNames.buildDisplayName(
                                        existingNames = existingNames,
                                        segmentIndex = segmentKeys.batch?.index,
                                        segmentTotal = segmentKeys.batch?.total,
                                        originalFileStem = queryOriginalFileName(audioUri),
                                    )
                                    MediaStoreSaver.saveVideoToAppStorage(
                                        context = applicationContext,
                                        sourceFile = outcome.outputFile,
                                        displayName = displayName,
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.e(TAG, "MediaStore save failed", e)
                                    null
                                }
                                if (savedUriOrNull == null) {
                                    failWithNotification(R.string.conversion_error_save_failed)
                                } else {
                                    val historyRecorded = recordConversionResilient(
                                        audioUri = audioUri,
                                        videoUri = savedUriOrNull,
                                        segmentBatchId = segmentKeys.batch?.batchId,
                                        segmentIndex = segmentKeys.batch?.index,
                                        segmentTotal = segmentKeys.batch?.total,
                                    )
                                    outcome.outputFile.delete()
                                    postResultNotification(success = true)
                                    Result.success(
                                        workDataOf(
                                            KEY_VIDEO_URI to savedUriOrNull.toString(),
                                            KEY_HISTORY_RECORDED to historyRecorded,
                                        ),
                                    )
                                }
                            }
                            is ConversionResult.Failure -> failWithConverterFailure(outcome.message)
                        }
                    }
                }
            }

            result ?: failWithNotification(R.string.conversion_error_unknown_abort)
        }
    }

    /**
     * Persists conversion history. Non-[IllegalArgumentException] failures soft-end with
     * `false` so the Worker can still return [Result.success] + [KEY_HISTORY_RECORDED]=false.
     * IAE is a programming/guard bug after resolve — not soft-failed (rethrows).
     */
    private suspend fun recordConversionResilient(
        audioUri: Uri,
        videoUri: Uri,
        segmentBatchId: String?,
        segmentIndex: Int?,
        segmentTotal: Int?,
    ): Boolean {
        try {
            conversionHistoryRepository.recordConversion(
                audioUri = audioUri,
                videoUri = videoUri,
                segmentBatchId = segmentBatchId,
                segmentIndex = segmentIndex,
                segmentTotal = segmentTotal,
            )
            return true
        } catch (e: IllegalArgumentException) {
            AppLogger.e(
                TAG,
                "recordConversion rejected args " +
                    "segment(hasBatch=${segmentBatchId != null}, index=$segmentIndex, total=$segmentTotal)",
                e,
            )
            // IAE soft-fail 금지 — resolve/SSOT 통과 후 IAE는 프로그래밍 오류
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(
                TAG,
                "recordConversion failed; retrying " +
                    "segment(hasBatch=${segmentBatchId != null}, index=$segmentIndex, total=$segmentTotal)",
                e,
            )
            try {
                conversionHistoryRepository.recordConversion(
                    audioUri = audioUri,
                    videoUri = videoUri,
                    segmentBatchId = segmentBatchId,
                    segmentIndex = segmentIndex,
                    segmentTotal = segmentTotal,
                )
                return true
            } catch (e2: IllegalArgumentException) {
                AppLogger.e(
                    TAG,
                    "recordConversion rejected args " +
                        "segment(hasBatch=${segmentBatchId != null}, index=$segmentIndex, total=$segmentTotal)",
                    e2,
                )
                throw e2
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                AppLogger.e(
                    TAG,
                    "recordConversion failed after retry " +
                        "segment(hasBatch=${segmentBatchId != null}, index=$segmentIndex, total=$segmentTotal)",
                    e2,
                )
                return false
            }
        }
    }

    /**
     * Best-effort lookup of the source audio's display name via [OpenableColumns.DISPLAY_NAME],
     * so [C2vOutputNames.buildDisplayName] can name the output after it (falls back to the
     * timestamp scheme when this is null). Works for both MediaStore and SAF document URIs.
     * Lookup failures other than [CancellationException] return null so they do not fail the
     * conversion; [CancellationException] is rethrown to preserve worker cancellation.
     */
    private fun queryOriginalFileName(audioUri: Uri): String? = try {
        applicationContext.contentResolver
            .query(audioUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.w(TAG, "Original filename query failed", e)
        null
    }

    /**
     * Returns [Result.success] (not [Result.failure]) so a single bad batch/segment item doesn't
     * cascade-cancel the rest of the chained work (WorkManager marks chain dependents as CANCELLED
     * once a prerequisite returns [Result.failure]). The real outcome is carried via
     * [KEY_ITEM_FAILED] + [KEY_MESSAGE] and decoded by [toConversionUiState] in [ConvertViewModel] (Home/Convert assembly screen).
     */
    private fun failWithNotification(@StringRes messageRes: Int): Result {
        val message = applicationContext.getString(messageRes)
        postResultNotification(success = false, contentText = message)
        return Result.success(workDataOf(KEY_MESSAGE to message, KEY_ITEM_FAILED to true))
    }

    private fun failWithConverterFailure(rawMessage: String): Result {
        val message = applicationContext.getString(videoConverterFailureMessageToStringRes(rawMessage))
        postResultNotification(success = false, contentText = message)
        return Result.success(workDataOf(KEY_MESSAGE to message, KEY_ITEM_FAILED to true))
    }

    private fun buildProgressForegroundInfo(percent: Int): ForegroundInfo {
        ensureChannels()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, PROGRESS_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.conversion_notification_progress_title))
            .setContentText(
                applicationContext.getString(R.string.conversion_notification_progress_percent, percent),
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .addAction(0, applicationContext.getString(R.string.action_cancel), cancelIntent)
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

    private fun postResultNotification(success: Boolean, contentText: String? = null) {
        ensureChannels()
        val notificationId = resultNotificationId()
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val displayText = if (success) {
            applicationContext.getString(R.string.conversion_notification_success_body)
        } else {
            contentText ?: applicationContext.getString(R.string.conversion_notification_failure_fallback)
        }
        val notification = NotificationCompat.Builder(applicationContext, RESULT_CHANNEL_ID)
            .setContentTitle(
                applicationContext.getString(
                    if (success) {
                        R.string.conversion_notification_success_title
                    } else {
                        R.string.conversion_notification_failure_title
                    },
                ),
            )
            .setContentText(displayText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        // Without POST_NOTIFICATIONS (API 33+) skip notify — silent no-op, not a crash (AC-7).
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        }
    }

    /** Per-worker result notification id derived from [id] (WorkManager UUID). */
    private fun resultNotificationId(): Int =
        RESULT_NOTIFICATION_ID + (id.hashCode() and 0xFFFF)

    private fun ensureChannels() {
        if (channelsEnsured) return
        synchronized(this) {
            if (channelsEnsured) return
            val manager = NotificationManagerCompat.from(applicationContext)
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(PROGRESS_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(applicationContext.getString(R.string.conversion_notification_channel_progress))
                    .build(),
            )
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(RESULT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(applicationContext.getString(R.string.conversion_notification_channel_result))
                    .build(),
            )
            channelsEnsured = true
        }
    }

    companion object {
        const val KEY_BACKGROUND_PATH = "backgroundFilePath"
        const val KEY_AUDIO_URI = "audioUri"
        const val KEY_PROGRESS = "progress"
        const val KEY_VIDEO_URI = "videoUri"
        const val KEY_MESSAGE = "message"

        /**
         * Whether [ConversionHistoryRepository.recordConversion] succeeded for this run.
         * Present on [Result.success] so UI/7-4 can detect soft-failed history.
         */
        const val KEY_HISTORY_RECORDED = "historyRecorded"

        /**
         * Marks a per-item conversion failure that is still encoded as [Result.success] (paired
         * with [KEY_MESSAGE]) so batch/segment siblings chained after this item in WorkManager
         * still run instead of being cascade-CANCELLED.
         */
        const val KEY_ITEM_FAILED = "itemFailed"

        /** Clip start (µs). With [KEY_SEGMENT_END_US] forms the Range set. */
        const val KEY_SEGMENT_START_US = "segmentStartUs"
        /** Clip end (µs). With [KEY_SEGMENT_START_US] forms the Range set. */
        const val KEY_SEGMENT_END_US = "segmentEndUs"
        /** Batch UUID. With INDEX+TOTAL forms the Batch set. */
        const val KEY_SEGMENT_BATCH_ID = "segmentBatchId"
        /** 1-based segment index. With BATCH_ID+TOTAL forms the Batch set. */
        const val KEY_SEGMENT_INDEX = "segmentIndex"
        /** Segment count in batch. With BATCH_ID+INDEX forms the Batch set. */
        const val KEY_SEGMENT_TOTAL = "segmentTotal"

        private const val PROGRESS_CHANNEL_ID = "conversion_progress"
        private const val RESULT_CHANNEL_ID = "conversion_result"
        private const val PROGRESS_NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
        internal const val TAG = "ConversionWorker"

        /**
         * Resolves Range/Batch key sets independently via key presence.
         * @return null when either set is partial, blank batchId, invalid range values,
         * non-1-based index/total, or BATCH_ID present with getString null
         * (caller maps null to [Result.success] with [KEY_ITEM_FAILED] soft-failure).
         * Trio [IllegalArgumentException] is mapped to null;
         * present+null BATCH_ID is a Worker-safe early null, not trio IAE.
         */
        internal fun resolveSegmentKeys(data: Data): ResolvedSegmentKeys? {
            val hasStart = data.hasKey(KEY_SEGMENT_START_US)
            val hasEnd = data.hasKey(KEY_SEGMENT_END_US)
            val range = when {
                !hasStart && !hasEnd -> null
                hasStart && hasEnd -> {
                    val startUs = data.getLong(KEY_SEGMENT_START_US, 0L)
                    val endUs = data.getLong(KEY_SEGMENT_END_US, 0L)
                    // Early-fail invalid windows (incl. START=0 which is valid only when end > 0).
                    if (startUs < 0L || startUs >= endUs) return null
                    SegmentRange(startUs = startUs, endUs = endUs)
                }
                else -> return null
            }

            val hasBatchId = data.hasKey(KEY_SEGMENT_BATCH_ID)
            val hasIndex = data.hasKey(KEY_SEGMENT_INDEX)
            val hasTotal = data.hasKey(KEY_SEGMENT_TOTAL)
            val batchPresentCount = listOf(hasBatchId, hasIndex, hasTotal).count { it }
            val batch = when (batchPresentCount) {
                0 -> null
                3 -> {
                    val rawBatchId = data.getString(KEY_SEGMENT_BATCH_ID)
                    val index = data.getInt(KEY_SEGMENT_INDEX, 0)
                    val total = data.getInt(KEY_SEGMENT_TOTAL, 0)
                    // Present key + getString null: Worker-safe resolve-null (not trio IAE).
                    val batchId = rawBatchId ?: return null
                    // Trio IAE only (blank id / invalid index/total) → resolve null.
                    // Type narrowing is `?: return null` above; it is outside this catch.
                    try {
                        requireSegmentTrioConsistent(batchId, index, total)
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                    SegmentBatchMeta(
                        batchId = batchId,
                        index = index,
                        total = total,
                    )
                }
                else -> return null
            }

            return ResolvedSegmentKeys(range = range, batch = batch)
        }

        private fun Data.hasKey(key: String): Boolean = keyValueMap.containsKey(key)
    }
}

/** Maps [ConversionSegmentError] to notification/UI string resources (Worker SSOT). */
internal fun segmentErrorToStringRes(error: ConversionSegmentError): Int = when (error) {
    ConversionSegmentError.SEGMENT_RANGE_INVALID -> R.string.conversion_error_segment_range_invalid
    ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN ->
        R.string.conversion_error_segment_too_short_after_margin
}

/**
 * Maps known [VideoConverter] failure messages (Korean literals from Converter) to string resources.
 * Unknown messages fall back to [R.string.conversion_notification_failure_fallback].
 */
internal fun videoConverterFailureMessageToStringRes(rawMessage: String): Int = when (rawMessage) {
    ConversionSegmentError.SEGMENT_RANGE_INVALID.userMessage ->
        R.string.conversion_error_segment_range_invalid
    ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN.userMessage ->
        R.string.conversion_error_segment_too_short_after_margin
    VIDEO_CONVERTER_AUDIO_DURATION_INCONSISTENT_MESSAGE ->
        R.string.conversion_error_audio_duration_inconsistent
    else -> R.string.conversion_notification_failure_fallback
}

/**
 * Korean literal from [VideoConverter.describeExportFailure] (VideoConverter.kt ~L184) —
 * must stay in sync; [ConversionWorkerNotificationTest] asserts via reflection.
 */
internal const val VIDEO_CONVERTER_AUDIO_DURATION_INCONSISTENT_MESSAGE =
    "선택한 오디오 파일의 재생 시간 정보가 일관되지 않아 변환할 수 없습니다. 다른 오디오 파일을 선택해 주세요."

/**
 * After [durationUs] is known: validate range against audio length and margin clip window.
 * Null [range] → no-op. Failure → Korean [ConversionSegmentError] for [ConversionWorker] notify.
 */
internal fun validateResolvedRangeForConvert(
    range: SegmentRange?,
    audioDurationUs: Long,
): ConversionSegmentError? {
    if (range == null) return null
    validateSegmentRange(range.startUs, range.endUs, audioDurationUs).exceptionOrNull()?.let { err ->
        return (err as? ConversionSegmentException)?.error
            ?: ConversionSegmentError.SEGMENT_RANGE_INVALID
    }
    computeClipWindow(range.startUs, range.endUs).exceptionOrNull()?.let { err ->
        return (err as? ConversionSegmentException)?.error
            ?: ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN
    }
    return null
}

/**
 * Soft-fail policy for history insert: [CancellationException] and IAE must propagate; other
 * failures map to [ConversionWorker.KEY_HISTORY_RECORDED]=false.
 */
internal fun historySoftFailOrRethrow(error: Throwable): Boolean {
    if (error is CancellationException) throw error
    if (error is IllegalArgumentException) throw error
    return false
}

/**
 * Runs [block] and softens any exception it doesn't already handle via early-return into the
 * same soft-fail shape ([onUnexpectedFailure]) as [ConversionWorker]'s known failures. Without
 * this, an exception escaping the convert-and-save body (e.g. Media3 Transformer/MediaCodec
 * resource contention deep inside [VideoConverter.convert], or an unswallowed history-insert IAE)
 * would surface `doWork()` as [Result.failure], and WorkManager CANCELs every remaining item
 * chained after it in a batch/segment `WorkContinuation`. [CancellationException] always
 * rethrows — worker cancellation must stay real cancellation, not a soft-fail.
 */
internal suspend fun runCatchingUnexpectedAsSoftFail(
    onUnexpectedFailure: () -> Result,
    block: suspend () -> Result,
): Result {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.e(ConversionWorker.TAG, "Unexpected exception during conversion", e)
        onUnexpectedFailure()
    }
}

/** Builds success output Data including [ConversionWorker.KEY_HISTORY_RECORDED]. */
internal fun successOutputData(videoUri: String, historyRecorded: Boolean): Data =
    workDataOf(
        ConversionWorker.KEY_VIDEO_URI to videoUri,
        ConversionWorker.KEY_HISTORY_RECORDED to historyRecorded,
    )

/** Parsed optional Range + Batch sets after none/all/partial validation. */
internal data class ResolvedSegmentKeys(
    val range: SegmentRange?,
    val batch: SegmentBatchMeta?,
)

internal data class SegmentRange(
    val startUs: Long,
    val endUs: Long,
)

internal data class SegmentBatchMeta(
    val batchId: String,
    val index: Int,
    val total: Int,
)
