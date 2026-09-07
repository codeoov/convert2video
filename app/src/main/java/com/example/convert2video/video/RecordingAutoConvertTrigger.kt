package com.example.convert2video.video

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.convert2video.data.BackgroundRepository
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.data.YoutubeDefaultVisibility
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.youtube.YouTubeUploadWorker
import com.example.convert2video.youtube.isYoutubeAuthorizedFromPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "RecordingAutoConvertTrigger"
private const val AUTO_CONVERT_WORK_NAME_PREFIX = "auto_convert_"
private const val MANUAL_WORK_QUERY_TIMEOUT_MS = 5_000L

/**
 * Manual Convert unique work name (same value as ConvertViewModel.CONVERSION_WORK_NAME).
 * Defined here so [enqueueAutoConvertIfEnabled] can skip while that work is unfinished
 * (`!WorkInfo.State.isFinished`) without importing `ui.screens.convert`.
 * Never used as the auto unique work name.
 */
internal const val MANUAL_CONVERSION_WORK_NAME = CONVERSION_UNIQUE_WORK_NAME

/**
 * Enqueues a plain [ConversionWorker] after a recording is indexed, when
 * [SettingsRepository.lastUsedBackgroundPath] is set and the file is a real file
 * under [BackgroundRepository.backgroundsDir]. Convert is lastUsed-gated;
 * [youtubeAutoUploadEnabled] gates the YouTube chain only (read only when authorized).
 *
 * If [isYoutubeAuthorized] is true, [youtubeAutoUploadEnabled] is true, and
 * [youtubeAutoUploadTitleOrNull] is non-null, chains [YouTubeUploadWorker]
 * (privacy private, empty description). Toggle is not read when !authorized
 * or auth throws — YouTube step omitted; toggle unread.
 * convert-only skip (logs `youtube step omitted: not authorized` / `toggle off` /
 * `blank title`): `!auth` / toggle false / blank title.
 * convert-only degrade (logs `youtube chain degraded`): auth-throw / toggle-throw.
 * wifiOnlyUpload read failure → CONNECTED, still chain YouTube (not convert-only;
 * logs `wifiOnly read failed; using CONNECTED`, not degrade).
 * Public default is [isYoutubeAuthorizedFromPrefs] (no Play Services Identity client).
 *
 * Does not import `ui.screens.convert` or `ui.screens.youtube_upload`.
 * Unique work name is [autoConvertWorkName] — never [MANUAL_CONVERSION_WORK_NAME].
 *
 * Non-[CancellationException] [Exception]s are logged (simpleName only — no path/URI)
 * and swallowed so [com.example.convert2video.record.RecordingService] never maps
 * them to INDEX_FAILED.
 * [CancellationException] is logged and **rethrown** (cooperative cancel).
 * [Error] is not caught (Error ≠ Exception swallow).
 */
suspend fun enqueueAutoConvertIfEnabled(
    context: Context,
    file: File,
    settingsRepository: SettingsRepository,
    workManager: WorkManager,
    isYoutubeAuthorized: () -> Boolean = { isYoutubeAuthorizedFromPrefs(context) },
    capabilities: StoreCapabilities = StoreCapabilities.current,
): Unit = enqueueAutoConvertIfEnabled(
    context = context,
    file = file,
    settingsRepository = settingsRepository,
    fileUriFor = { ctx, f -> RecordingRepository.contentUriFor(ctx, f).toString() },
    enqueueUniqueWork = { name, policy, conversion, youtube ->
        enqueueAssembledAutoConvertChain(
            started = workManager.beginUniqueWork(name, policy, conversion),
            youtube = youtube,
            then = { chain, yt -> chain.then(yt) },
            enqueue = { it.enqueue() },
        )
    },
    backgroundsDir = BackgroundRepository.backgroundsDir(context),
    isManualConversionRunning = {
        shouldSkipForManualConversionQuery {
            workManager.getWorkInfosForUniqueWork(MANUAL_CONVERSION_WORK_NAME)
                .get(MANUAL_WORK_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .map { it.state }
        }
    },
    isYoutubeAuthorized = isYoutubeAuthorized,
    youtubeAutoUploadEnabled = { settingsRepository.youtubeAutoUploadEnabled.first() },
    defaultVisibility = { settingsRepository.youtubeDefaultVisibility.first() },
    capabilities = capabilities,
)

/**
 * Testable entry — Fake WM is a capturing 4-arg chain seam
 * (WorkManager ctor is module-internal; JVM tests must not call FileProvider
 * or construct a YouTube auth gateway).
 */
internal suspend fun enqueueAutoConvertIfEnabled(
    context: Context,
    file: File,
    settingsRepository: SettingsRepository,
    fileUriFor: (Context, File) -> String,
    enqueueUniqueWork: (
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        conversionRequest: OneTimeWorkRequest,
        youtubeRequest: OneTimeWorkRequest?,
    ) -> Unit,
    backgroundsDir: File,
    isManualConversionRunning: suspend () -> Boolean = { false },
    isYoutubeAuthorized: () -> Boolean = { false },
    youtubeAutoUploadEnabled: suspend () -> Boolean = { settingsRepository.youtubeAutoUploadEnabled.first() },
    wifiOnlyUpload: suspend () -> Boolean = { settingsRepository.wifiOnlyUpload.first() },
    defaultVisibility: suspend () -> YoutubeDefaultVisibility =
        { settingsRepository.youtubeDefaultVisibility.first() },
    capabilities: StoreCapabilities = StoreCapabilities.current,
): Unit {
    try {
        withContext(Dispatchers.IO) {
            val backgroundPath = settingsRepository.lastUsedBackgroundPath.first()
            if (backgroundPath.isNullOrBlank()) return@withContext
            val backgroundFile = File(backgroundPath)
            val isExistingFile = evaluateExistingLastUsedFile(backgroundFile)
            val pathInside = if (isExistingFile == true) {
                isPathInsideDirectory(backgroundFile, backgroundsDir)
            } else {
                null
            }
            if (isExistingFile != true || pathInside != true) {
                when {
                    isExistingFile == null -> {
                        AppLogger.w(TAG, "enqueue skipped: lastUsed isFile undetermined")
                    }
                    isExistingFile == true && pathInside == null -> {
                        AppLogger.w(TAG, "enqueue skipped: lastUsed confinement undetermined")
                    }
                    else -> {
                        AppLogger.w(TAG, "enqueue skipped: lastUsedBackgroundPath is not a usable file")
                    }
                }
                if (shouldClearLastUsedBackgroundPath(isExistingFile, pathInside)) {
                    settingsRepository.clearLastUsedBackgroundPathCatching()
                }
                return@withContext
            }
            if (isManualConversionRunning()) {
                AppLogger.w(TAG, "enqueue skipped: manual conversion is unfinished")
                return@withContext
            }

            val fileUriString = fileUriFor(context, file)
            val conversionRequest = OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(
                    workDataOf(
                        ConversionWorker.KEY_BACKGROUND_PATH to backgroundPath,
                        ConversionWorker.KEY_AUDIO_URI to fileUriString,
                    ),
                )
                .build()
            val shouldChainYoutube = if (!capabilities.supportsYouTube) {
                false
            } else {
                val authorized = try {
                    val value = isYoutubeAuthorized()
                    if (!value) {
                        AppLogger.w(TAG, "youtube step omitted: not authorized")
                    }
                    value
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    youtubeChainDegradeOrRethrow(e)
                }
                if (!authorized) {
                    false
                } else {
                    try {
                        val enabled = youtubeAutoUploadEnabled()
                        if (!enabled) {
                            AppLogger.w(TAG, "youtube step omitted: toggle off")
                        }
                        enabled
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        youtubeChainDegradeOrRethrow(e)
                    }
                }
            }
            val youtubeRequest = if (shouldChainYoutube) {
                val title = youtubeAutoUploadTitleOrNull(file.name)
                if (title == null) {
                    AppLogger.w(TAG, "youtube step omitted: blank title")
                    null
                } else {
                    val wifiOnly = try {
                        wifiOnlyUpload()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        wifiOnlyConnectedFallbackOrRethrow(e)
                    }
                    val visibility = try {
                        defaultVisibility()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "defaultVisibility read failed; using private")
                        YoutubeDefaultVisibility.Private
                    }
                    OneTimeWorkRequestBuilder<YouTubeUploadWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(networkTypeForYoutubeWifiOnly(wifiOnly))
                                .build(),
                        )
                        .setInputData(
                            workDataOf(
                                YouTubeUploadWorker.KEY_TITLE to title,
                                YouTubeUploadWorker.KEY_DESCRIPTION to "",
                                YouTubeUploadWorker.KEY_PRIVACY_STATUS to visibility.storageValue,
                            ),
                        )
                        .build()
                }
            } else {
                null
            }
            enqueueUniqueWork(
                autoConvertWorkName(fileUriString),
                ExistingWorkPolicy.KEEP,
                conversionRequest,
                youtubeRequest,
            )
        }
    } catch (ce: CancellationException) {
        AppLogger.w(TAG, "enqueue cancelled: ${ce.javaClass.simpleName}")
        throw ce
    } catch (e: Exception) {
        // simpleName only — do not pass throwable (path/URI can leak via stack/message).
        AppLogger.e(TAG, "enqueue failed: ${e.javaClass.simpleName}")
        return
    }
}

/** wifiOnlyUpload → WorkManager NetworkType (YouTube auto-convert chain; not shared with Drive). */
internal fun networkTypeForYoutubeWifiOnly(wifiOnly: Boolean): NetworkType =
    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

/**
 * YouTube auto-upload title from the recording file name. Null/blank after
 * [C2vOutputNames.sanitizeOriginalFileStem] → omit the YouTube step (convert-only).
 */
internal fun youtubeAutoUploadTitleOrNull(fileName: String): String? =
    C2vOutputNames.sanitizeOriginalFileStem(fileName)?.takeIf { it.isNotBlank() }

/**
 * Assembles unique-work continuation then always [enqueue]s **that** handle.
 * When [youtube] is non-null, [then] result is enqueued — never the pre-then [started].
 */
internal fun <C, Y> enqueueAssembledAutoConvertChain(
    started: C,
    youtube: Y?,
    then: (C, Y) -> C,
    enqueue: (C) -> Unit,
) {
    val toEnqueue = if (youtube != null) then(started, youtube) else started
    enqueue(toEnqueue)
}

/** Unique work name for a FileProvider content URI string. */
internal fun autoConvertWorkName(fileUri: String): String =
    AUTO_CONVERT_WORK_NAME_PREFIX + Uri.encode(fileUri)

/**
 * [File.isFile] as ternary SSOT used by enqueue (not folded to Boolean).
 * `true` = existing file; `false` = missing/non-file; `null` = undetermined ([Exception], not [Error]).
 * [CancellationException] is rethrown. Does not catch [Error].
 *
 * Clear policy is **not** the same as [isPathInsideDirectory]:
 * isFile `false` → known unusable → [shouldClearLastUsedBackgroundPath] true;
 * isFile `null` → unknown → do not clear (same keep as canonical undetermined).
 */
internal fun evaluateExistingLastUsedFile(file: File): Boolean? {
    return try {
        file.isFile
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        AppLogger.w(TAG, "lastUsed isFile undetermined: ${e.javaClass.simpleName}")
        null
    }
}

/**
 * Canonical-path prefix check (enqueue default — no injection seam).
 * `true` = inside [directory]; `false` = outside; `null` = undetermined (IO/Security).
 * Undetermined is not outside and is not folded to `false`.
 * [CancellationException] is rethrown. Does not catch [Error].
 */
internal fun isPathInsideDirectory(file: File, directory: File): Boolean? =
    evaluatePathInsideDirectory {
        val fileCanon = file.canonicalFile.toPath()
        val dirCanon = directory.canonicalFile.toPath()
        fileCanon.startsWith(dirCanon)
    }

/**
 * [compute] result, or `null` when confinement cannot be judged (IO/Security).
 * [CancellationException] is rethrown. Does not catch [Error].
 */
internal fun evaluatePathInsideDirectory(compute: () -> Boolean): Boolean? {
    return try {
        compute()
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: IOException) {
        AppLogger.w(TAG, "path confinement undetermined: ${e.javaClass.simpleName}")
        null
    } catch (e: SecurityException) {
        AppLogger.w(TAG, "path confinement undetermined: ${e.javaClass.simpleName}")
        null
    }
}

/**
 * lastUsed clear policy after the file guard. [isExistingFile] is ternary.
 * - isFile `false` (missing/non-file) → clear
 * - isFile `null` (isFile [Exception]) → do not clear
 * - isFile `true` + [pathInside] `false` (outside) → clear
 * - isFile `true` + [pathInside] `null` (canonical IO/Security) → do not clear
 * - isFile `true` + [pathInside] `true` → do not clear (usable)
 */
internal fun shouldClearLastUsedBackgroundPath(
    isExistingFile: Boolean?,
    pathInside: Boolean?,
): Boolean {
    if (isExistingFile == false) return true
    if (isExistingFile != true) return false
    return pathInside == false
}

/**
 * Manual unique work is treated as blocking auto-convert when any state is
 * unfinished (`!isFinished`): ENQUEUED / RUNNING / BLOCKED.
 */
internal fun shouldSkipForManualConversionStates(states: List<WorkInfo.State>): Boolean =
    states.any { !it.isFinished }

/**
 * Non-CE query throw / timeout → skip auto-convert (`true`, fail-closed).
 * [CancellationException] is rethrown. Does not catch [Error].
 */
internal fun shouldSkipForManualConversionQuery(query: () -> List<WorkInfo.State>): Boolean {
    return try {
        shouldSkipForManualConversionStates(query())
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        true
    }
}

/**
 * Non-CE auth/toggle failure → false (degrade). Not used for wifiOnly.
 * [CancellationException] is rethrown. Logs simpleName only — no throwable, no path/URI.
 * Does not catch [Error] (Error ≠ Exception swallow; caller must catch [Exception] only).
 */
private fun youtubeChainDegradeOrRethrow(error: Exception): Boolean {
    if (error is CancellationException) throw error
    AppLogger.w(TAG, "youtube chain degraded: ${error.javaClass.simpleName}")
    return false
}

/**
 * Non-CE wifiOnly read failure → `false` (CONNECTED). Does not log degrade.
 * [CancellationException] is rethrown.
 */
private fun wifiOnlyConnectedFallbackOrRethrow(error: Exception): Boolean {
    if (error is CancellationException) throw error
    AppLogger.w(TAG, "wifiOnly read failed; using CONNECTED")
    return false
}
