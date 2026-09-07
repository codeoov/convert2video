package com.example.convert2video.data

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.convert2video.record.MicrophoneSource
import com.example.convert2video.record.NoiseReductionMode
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.IOException

private const val TAG = "SettingsRepository"

/** App-wide light/dark preference (옵션 화면의 "테마" 세그먼트 컨트롤). */
enum class ThemeMode {
    System,
    Light,
    Dark,
    ;

    companion object {
        fun fromStorageValue(value: String?): ThemeMode = when (value) {
            "light" -> Light
            "dark" -> Dark
            else -> System
        }
    }

    val storageValue: String
        get() = when (this) {
            System -> "system"
            Light -> "light"
            Dark -> "dark"
        }
}

/** YouTube auto-upload default visibility — raw values match upload dialog privacy options. */
enum class YoutubeDefaultVisibility {
    Private,
    Unlisted,
    Public,
    ;

    val storageValue: String
        get() = when (this) {
            Private -> "private"
            Unlisted -> "unlisted"
            Public -> "public"
        }

    companion object {
        fun fromStorageValue(value: String?): YoutubeDefaultVisibility = when (value) {
            "unlisted" -> Unlisted
            "public" -> Public
            else -> Private
        }
    }
}

/**
 * Per-app UI language (AppCompat [AppCompatDelegate] locales).
 * Tags match `res/xml/locales_config.xml` (`en` / `ko`).
 * Current selection is **not** a DataStore key.
 *
 * Persistence:
 * - API 33+: framework [android.app.LocaleManager] (via AppCompat).
 * - Pre-33: AppCompat `autoStoreLocales` Manifest opt-in
 *   (`AppLocalesMetadataHolderService` meta-data) — not DataStore.
 */
enum class LanguageOption {
    English,
    Korean,
    ;

    val tag: String
        get() = when (this) {
            English -> "en"
            Korean -> "ko"
        }

    companion object {
        private const val TAG = "LanguageOption"

        /**
         * Maps BCP-47 language tags from AppCompat locales.
         * - `null` / `"en"` → [English] (no log)
         * - `"ko"` → [Korean]
         * - other non-null → [AppLogger.w] once, then [English]
         */
        fun fromTag(tag: String?): LanguageOption = when (tag) {
            null, "en" -> English
            "ko" -> Korean
            else -> {
                AppLogger.w(
                    TAG,
                    "Unknown language tag='$tag'; defaulting to English",
                )
                English
            }
        }
    }
}

/**
 * Persisted **per-app** locale read (never system [android.content.res.Resources] /
 * [android.content.res.Configuration]).
 *
 * [Ready] — list was read successfully. Empty list maps to [LanguageOption.English] (policy A).
 * [NotReady] — LocaleManager missing/throw (API 33+). Not unset; must not be stored as English.
 */
sealed class PerAppLocalesRead {
    data class Ready(val option: LanguageOption) : PerAppLocalesRead()
    data object NotReady : PerAppLocalesRead()
}

/**
 * Options Quick Timer (countdown) 대기 상태.
 * [recordingStartedElapsedMillis] null = START 알람만 무장, non-null = 녹음 시작 후 STOP 대기.
 */
data class PendingCountdown(
    val startElapsedMillis: Long,
    val durationMinutes: Int,
    val recordingStartedElapsedMillis: Long? = null,
)

/** Raw settings needed to evaluate the post-Keep recording-backup prompt. */
internal data class RecordingBackupPromptPreferences(
    val isHandled: Boolean,
    val backupFolderUri: String?,
)

private val Context.settingsDataStore by preferencesDataStore(
    name = SettingsRepository.DATA_STORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        AppLogger.e(TAG, "Corrupted preferences; resetting to defaults", e)
        emptyPreferences()
    },
)

/**
 * Local preferences for app settings (Wi-Fi-only upload, YouTube/Drive auto-upload, theme, recording format).
 *
 * Drive auto-upload preference: [driveAutoUploadEnabled] / [setDriveAutoUploadEnabled]
 * (`drive_auto_upload_enabled`, default false). Read by
 * [com.example.convert2video.drive.DriveAutoUploadTrigger] and toggled from
 * `OptionsScreen`'s Google Drive section.
 *
 * YouTube auto-upload master toggle: [youtubeAutoUploadEnabled] / [setYoutubeAutoUploadEnabled]
 * (`youtube_auto_upload_enabled`, default false). Read by
 * [com.example.convert2video.video.RecordingAutoConvertTrigger] /
 * [com.example.convert2video.video.enqueueAutoConvertIfEnabled]
 * and toggled from `OptionsScreen`'s YouTube section.
 *
 * String prefs (themeMode / recordingFormat) intentionally deferred — outside C2 scope;
 * boolean prefs share [booleanPreferenceFlow] / [setBooleanPreference].
 *
 * App entry point: `SettingsRepository(context)`.
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.settingsDataStore)

    /** Wi-Fi-only upload (default **true** when key absent — not [booleanPreferenceFlow]). */
    val wifiOnlyUpload: Flow<Boolean> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read wifiOnlyUpload", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            preferences[KEY_WIFI_ONLY_UPLOAD] ?: true
        }
        .distinctUntilChanged()

    suspend fun setWifiOnlyUpload(enabled: Boolean) {
        setBooleanPreference(KEY_WIFI_ONLY_UPLOAD, enabled, "wifiOnlyUpload")
    }

    /**
     * Drive auto-upload toggle (default **false** when key absent).
     * Uses [booleanPreferenceFlow] with `default = false` (wifiOnly stays inline, default true).
     * IOException on read → emptyPreferences → false.
     * Non-IOException (e.g. [IllegalStateException]) is rethrown by the helper else branch.
     * [distinctUntilChanged] applied.
     *
     * Reader: [com.example.convert2video.drive.DriveAutoUploadTrigger].
     */
    val driveAutoUploadEnabled: Flow<Boolean> =
        booleanPreferenceFlow(
            KEY_DRIVE_AUTO_UPLOAD_ENABLED,
            "driveAutoUploadEnabled",
            default = false,
        )

    /**
     * Persists [driveAutoUploadEnabled]. IOException is logged then rethrown.
     */
    suspend fun setDriveAutoUploadEnabled(enabled: Boolean) {
        setBooleanPreference(KEY_DRIVE_AUTO_UPLOAD_ENABLED, enabled, "driveAutoUploadEnabled")
    }

    /**
     * YouTube auto-upload master toggle (default **false** when key absent).
     * Uses [booleanPreferenceFlow] with `default = false` (wifiOnly stays inline, default true).
     * IOException on read → emptyPreferences → false.
     * Non-IOException (e.g. [IllegalStateException]) is rethrown by the helper else branch.
     * [distinctUntilChanged] applied.
     *
     * Reader: [com.example.convert2video.video.RecordingAutoConvertTrigger] /
     * [com.example.convert2video.video.enqueueAutoConvertIfEnabled].
     */
    val youtubeAutoUploadEnabled: Flow<Boolean> =
        booleanPreferenceFlow(
            KEY_YOUTUBE_AUTO_UPLOAD_ENABLED,
            "youtubeAutoUploadEnabled",
            default = false,
        )

    /**
     * Persists [youtubeAutoUploadEnabled]. IOException is logged then rethrown.
     */
    suspend fun setYoutubeAutoUploadEnabled(enabled: Boolean) {
        setBooleanPreference(KEY_YOUTUBE_AUTO_UPLOAD_ENABLED, enabled, "youtubeAutoUploadEnabled")
    }

    /**
     * YouTube auto-upload default visibility (default [YoutubeDefaultVisibility.Private] when key absent).
     * Reader: [com.example.convert2video.video.RecordingAutoConvertTrigger].
     */
    val youtubeDefaultVisibility: Flow<YoutubeDefaultVisibility> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read youtubeDefaultVisibility", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            YoutubeDefaultVisibility.fromStorageValue(preferences[KEY_YOUTUBE_DEFAULT_VISIBILITY])
        }
        .distinctUntilChanged()

    suspend fun setYoutubeDefaultVisibility(visibility: YoutubeDefaultVisibility) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_YOUTUBE_DEFAULT_VISIBILITY] = visibility.storageValue
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write youtubeDefaultVisibility", e)
            throw e
        }
    }

    /**
     * 사용자가 지정한 Google Drive 업로드 폴더 이름. null이면 미설정.
     * 화면/Worker에서 null인 경우 [R.string.app_name]을 기본값으로 사용한다.
     * IOException 시 emptyPreferences → null 반환.
     */
    val driveFolderName: Flow<String?> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read driveFolderName", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            preferences[KEY_DRIVE_FOLDER_NAME]
        }
        .distinctUntilChanged()

    /** Drive 폴더 이름 저장. IOException은 로그 후 rethrow. */
    suspend fun setDriveFolderName(name: String) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_DRIVE_FOLDER_NAME] = name
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write driveFolderName", e)
            throw e
        }
    }

    /**
     * 마지막 수동 변환에 사용한 배경 절대경로. null이면 미설정.
     * IOException 시 emptyPreferences → null 반환.
     * 경로 값은 로그하지 않는다.
     */
    val lastUsedBackgroundPath: Flow<String?> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read lastUsedBackgroundPath: ${e.javaClass.simpleName}")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            preferences[KEY_LAST_USED_BACKGROUND_PATH]
        }
        .distinctUntilChanged()

    /**
     * 마지막 수동 변환 배경 경로 저장.
     * [path]가 null이거나 blank이면 키를 제거한다.
     * IOException은 로그 후 rethrow. 경로 값은 로그하지 않는다.
     */
    suspend fun setLastUsedBackgroundPath(path: String?) {
        try {
            dataStore.edit { preferences ->
                if (path.isNullOrBlank()) {
                    preferences.remove(KEY_LAST_USED_BACKGROUND_PATH)
                } else {
                    preferences[KEY_LAST_USED_BACKGROUND_PATH] = path
                }
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write lastUsedBackgroundPath: ${e.javaClass.simpleName}")
            throw e
        }
    }

    /**
     * Removes [lastUsedBackgroundPath]. Write failures are logged (simpleName only, no path)
     * and swallowed so delete/Trigger skip paths cannot fail the whole operation.
     * [CancellationException] is rethrown.
     */
    suspend fun clearLastUsedBackgroundPathCatching() {
        try {
            setLastUsedBackgroundPath(null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear lastUsedBackgroundPath: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 녹음 백업 대상 폴더의 tree URI. null이면 미설정.
     * IOException 시 emptyPreferences → null 반환.
     * URI 값은 로그하지 않는다.
     */
    val recordingBackupFolderUri: Flow<String?> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read recordingBackupFolderUri: ${e.javaClass.simpleName}")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            preferences[KEY_RECORDING_BACKUP_FOLDER_URI]
        }
        .distinctUntilChanged()

    /**
     * 녹음 백업 대상 폴더 tree URI 저장.
     * [uri]가 null이거나 blank이면 키를 제거한다.
     * IOException은 로그 후 rethrow. URI 값은 로그하지 않는다.
     */
    suspend fun setRecordingBackupFolderUri(uri: String?) {
        try {
            dataStore.edit { preferences ->
                if (uri.isNullOrBlank()) {
                    preferences.remove(KEY_RECORDING_BACKUP_FOLDER_URI)
                } else {
                    preferences[KEY_RECORDING_BACKUP_FOLDER_URI] = uri
                }
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write recordingBackupFolderUri: ${e.javaClass.simpleName}")
            throw e
        }
    }

    /**
     * 하드코딩 폴더명 → 설정 가능 폴더명으로의 1회성 마이그레이션 완료 플래그.
     * false이면 다음 업로드 시 캐시 무효화 후 true로 전환한다.
     */
    val driveFolderNameMigrationV1Done: Flow<Boolean> =
        booleanPreferenceFlow(KEY_DRIVE_FOLDER_NAME_MIGRATION_V1_DONE, "driveFolderNameMigrationV1Done")

    suspend fun setDriveFolderNameMigrationV1Done(done: Boolean) {
        setBooleanPreference(KEY_DRIVE_FOLDER_NAME_MIGRATION_V1_DONE, done, "driveFolderNameMigrationV1Done")
    }

    /** Exact alarm 시스템 유도 다이얼로그를 이미 1회 띄웠는지 (default false). */
    val exactAlarmPrompted: Flow<Boolean> =
        booleanPreferenceFlow(KEY_EXACT_ALARM_PROMPTED, "exactAlarmPrompted")

    suspend fun setExactAlarmPrompted(prompted: Boolean) {
        setBooleanPreference(KEY_EXACT_ALARM_PROMPTED, prompted, "exactAlarmPrompted")
    }

    /** 최초 언어 선택 프롬프트를 이미 띄웠는지 (default false). */
    val languagePromptShown: Flow<Boolean> =
        booleanPreferenceFlow(KEY_LANGUAGE_PROMPT_SHOWN, "languagePromptShown")

    suspend fun setLanguagePromptShown(shown: Boolean) {
        setBooleanPreference(KEY_LANGUAGE_PROMPT_SHOWN, shown, "languagePromptShown")
    }

    /** 녹음 백업 안내를 사용자가 처리했는지 (default false). */
    val recordingBackupPromptHandled: Flow<Boolean> =
        booleanPreferenceFlow(KEY_RECORDING_BACKUP_PROMPT_HANDLED, "recordingBackupPromptHandled")

    suspend fun setRecordingBackupPromptHandled(handled: Boolean) {
        setBooleanPreference(KEY_RECORDING_BACKUP_PROMPT_HANDLED, handled, "recordingBackupPromptHandled")
    }

    /**
     * Reads the prompt flag and backup URI from one DataStore snapshot.
     *
     * Unlike [recordingBackupPromptHandled], this seam preserves an IOException as `null` so
     * the one-shot successful-Keep caller can retry instead of treating a failed read as false.
     */
    internal suspend fun readRecordingBackupPromptPreferencesOrNull(): RecordingBackupPromptPreferences? =
        try {
            val preferences = dataStore.data.first()
            RecordingBackupPromptPreferences(
                isHandled = preferences[KEY_RECORDING_BACKUP_PROMPT_HANDLED] ?: false,
                backupFolderUri = preferences[KEY_RECORDING_BACKUP_FOLDER_URI],
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppLogger.e(
                TAG,
                "Failed to read recording backup prompt preferences: ${e.javaClass.simpleName}",
            )
            null
        }

    /**
     * Quick Timer 대기 countdown. start+duration 키가 모두 있을 때만 non-null.
     * IOException → emptyPreferences → null. 경로/시각 값은 로그하지 않는다.
     */
    val pendingCountdown: Flow<PendingCountdown?> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read pendingCountdown: ${e.javaClass.simpleName}")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            val startElapsed = preferences[KEY_PENDING_COUNTDOWN_START_ELAPSED_MILLIS]
            val durationMinutes = preferences[KEY_PENDING_COUNTDOWN_DURATION_MINUTES]
            if (startElapsed == null || durationMinutes == null) {
                null
            } else {
                PendingCountdown(
                    startElapsedMillis = startElapsed,
                    durationMinutes = durationMinutes,
                    recordingStartedElapsedMillis =
                        preferences[KEY_PENDING_COUNTDOWN_RECORDING_STARTED_ELAPSED_MILLIS],
                )
            }
        }
        .distinctUntilChanged()

    /**
     * Countdown START 알람 등록 **성공 후에만** 호출한다 (VM).
     * started 키는 새 무장 시 제거한다.
     * IOException은 로그 후 rethrow.
     */
    suspend fun setPendingCountdown(startElapsedMillis: Long, durationMinutes: Int) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_PENDING_COUNTDOWN_START_ELAPSED_MILLIS] = startElapsedMillis
                preferences[KEY_PENDING_COUNTDOWN_DURATION_MINUTES] = durationMinutes
                preferences.remove(KEY_PENDING_COUNTDOWN_RECORDING_STARTED_ELAPSED_MILLIS)
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write pendingCountdown: ${e.javaClass.simpleName}")
            throw e
        }
    }

    /**
     * COUNTDOWN START Accepted 후 STOP 등록 시각을 기록한다.
     * IOException은 로그 후 rethrow.
     */
    suspend fun markPendingCountdownRecordingStarted(elapsedMillis: Long) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_PENDING_COUNTDOWN_RECORDING_STARTED_ELAPSED_MILLIS] = elapsedMillis
            }
        } catch (e: IOException) {
            AppLogger.e(
                TAG,
                "Failed to write pendingCountdown recordingStarted: ${e.javaClass.simpleName}",
            )
            throw e
        }
    }

    /**
     * Countdown 키 3개를 모두 제거한다. Busy/fail/STOP/Cancel 경로.
     * IOException은 로그 후 rethrow.
     */
    suspend fun clearPendingCountdown() {
        try {
            dataStore.edit { preferences ->
                preferences.remove(KEY_PENDING_COUNTDOWN_START_ELAPSED_MILLIS)
                preferences.remove(KEY_PENDING_COUNTDOWN_DURATION_MINUTES)
                preferences.remove(KEY_PENDING_COUNTDOWN_RECORDING_STARTED_ELAPSED_MILLIS)
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to clear pendingCountdown: ${e.javaClass.simpleName}")
            throw e
        }
    }

    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read themeMode", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            ThemeMode.fromStorageValue(preferences[KEY_THEME_MODE])
        }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_THEME_MODE] = mode.storageValue
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write themeMode", e)
            throw e
        }
    }

    /**
     * 녹음 출력 포맷 Flow. emit 시 process-wide [recordingFormatHot]도 갱신한다.
     * 타입은 [RecordingFormat] SSOT — 이 파일에 enum 재선언 금지.
     */
    val recordingFormat: Flow<RecordingFormat> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read recordingFormat", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            RecordingFormat.fromStorageValue(preferences[KEY_RECORDING_FORMAT])
        }
        .onEach { format -> _recordingFormatHot.value = format }
        .distinctUntilChanged()

    /**
     * DataStore 저장 + process-wide hot cache 즉시 갱신.
     * 실패 시 hot cache를 이전 값(또는 DataStore 재읽기)으로 롤백한다.
     */
    suspend fun setRecordingFormat(format: RecordingFormat) {
        val previous = _recordingFormatHot.value
        _recordingFormatHot.value = format
        try {
            dataStore.edit { preferences ->
                preferences[KEY_RECORDING_FORMAT] = format.storageValue
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write recordingFormat", e)
            val restored = runCatching { recordingFormat.first() }.getOrNull() ?: previous
            _recordingFormatHot.value = restored
            throw e
        }
    }

    /**
     * 잡음 감소 모드 Flow. emit 시 process-wide [noiseReductionModeHot]도 갱신한다.
     * 타입은 [NoiseReductionMode] SSOT — 이 파일에 enum 재선언 금지.
     */
    val noiseReductionMode: Flow<NoiseReductionMode> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read noiseReductionMode", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            NoiseReductionMode.fromStorageValue(preferences[KEY_NOISE_REDUCTION_MODE])
        }
        .onEach { mode -> _noiseReductionModeHot.value = mode }
        .distinctUntilChanged()

    /**
     * DataStore 저장 + process-wide hot cache 즉시 갱신.
     * 실패 시 hot cache를 이전 값(또는 DataStore 재읽기)으로 롤백한다.
     */
    suspend fun setNoiseReductionMode(mode: NoiseReductionMode) {
        val previous = _noiseReductionModeHot.value
        _noiseReductionModeHot.value = mode
        try {
            dataStore.edit { preferences ->
                preferences[KEY_NOISE_REDUCTION_MODE] = mode.storageValue
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write noiseReductionMode", e)
            val restored = runCatching { noiseReductionMode.first() }.getOrNull() ?: previous
            _noiseReductionModeHot.value = restored
            throw e
        }
    }

    /**
     * 마이크 입력 소스 Flow. emit 시 process-wide [microphoneSourceHot]도 갱신한다.
     * 타입은 [MicrophoneSource] SSOT — 이 파일에 enum 재선언 금지.
     */
    val microphoneSource: Flow<MicrophoneSource> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read microphoneSource", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            MicrophoneSource.fromStorageValue(preferences[KEY_MICROPHONE_SOURCE])
        }
        .onEach { source -> _microphoneSourceHot.value = source }
        .distinctUntilChanged()

    /**
     * DataStore 저장 + process-wide hot cache 즉시 갱신.
     * 실패 시 hot cache를 이전 값(또는 DataStore 재읽기)으로 롤백한다.
     */
    suspend fun setMicrophoneSource(source: MicrophoneSource) {
        val previous = _microphoneSourceHot.value
        _microphoneSourceHot.value = source
        try {
            dataStore.edit { preferences ->
                preferences[KEY_MICROPHONE_SOURCE] = source.storageValue
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write microphoneSource", e)
            val restored = runCatching { microphoneSource.first() }.getOrNull() ?: previous
            _microphoneSourceHot.value = restored
            throw e
        }
    }

    /**
     * Boolean preference cold-Flow: IOException → emptyPreferences + [default]
     * (false unless overridden). The `else` branch rethrows non-IOException
     * (e.g. [IllegalStateException]). [CancellationException] is not caught by
     * `Flow.catch` (kotlinx.coroutines library); this helper's else branch does
     * not handle CE. [distinctUntilChanged] applied.
     * [wifiOnlyUpload] stays inline (default true) — do not route it through this helper.
     * String prefs (themeMode/recordingFormat) intentionally deferred — outside C2 scope.
     */
    private fun booleanPreferenceFlow(
        key: Preferences.Key<Boolean>,
        readLogLabel: String,
        default: Boolean = false,
    ): Flow<Boolean> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLogger.e(TAG, "Failed to read $readLogLabel", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            preferences[key] ?: default
        }
        .distinctUntilChanged()

    /** Boolean preference write: log + rethrow IOException. */
    private suspend fun setBooleanPreference(
        key: Preferences.Key<Boolean>,
        enabled: Boolean,
        writeLogLabel: String,
    ) {
        try {
            dataStore.edit { preferences ->
                preferences[key] = enabled
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed to write $writeLogLabel", e)
            throw e
        }
    }

    companion object {
        /**
         * Preferences DataStore file name.
         * The `preferencesDataStore` delegate that uses this name lives only in this file.
         */
        const val DATA_STORE_NAME = "settings"

        private val KEY_WIFI_ONLY_UPLOAD = booleanPreferencesKey("wifi_only_upload")
        /** DataStore key `youtube_auto_upload_enabled` — `internal` for unit-test raw-prefs assertion. */
        internal val KEY_YOUTUBE_AUTO_UPLOAD_ENABLED = booleanPreferencesKey("youtube_auto_upload_enabled")
        private val KEY_YOUTUBE_DEFAULT_VISIBILITY = stringPreferencesKey("youtube_default_visibility")
        /** DataStore key `drive_auto_upload_enabled` — `internal` for unit-test raw-prefs assertion. */
        internal val KEY_DRIVE_AUTO_UPLOAD_ENABLED = booleanPreferencesKey("drive_auto_upload_enabled")
        private val KEY_EXACT_ALARM_PROMPTED = booleanPreferencesKey("exact_alarm_prompted")
        private val KEY_LANGUAGE_PROMPT_SHOWN = booleanPreferencesKey("language_prompt_shown")
        /** DataStore key for the one-time recording-backup guidance prompt. */
        internal val KEY_RECORDING_BACKUP_PROMPT_HANDLED =
            booleanPreferencesKey("recording_backup_prompt_handled")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_RECORDING_FORMAT = stringPreferencesKey("recording_format")
        private val KEY_NOISE_REDUCTION_MODE = stringPreferencesKey("noise_reduction_mode")
        private val KEY_MICROPHONE_SOURCE = stringPreferencesKey("microphone_source")
        private val KEY_DRIVE_FOLDER_NAME = stringPreferencesKey("drive_folder_name")
        private val KEY_DRIVE_FOLDER_NAME_MIGRATION_V1_DONE =
            booleanPreferencesKey("drive_folder_name_migration_v1_done")
        private val KEY_LAST_USED_BACKGROUND_PATH =
            stringPreferencesKey("last_used_background_path")
        /** DataStore key `recording_backup_folder_uri` — `internal` for unit-test raw-prefs assertion. */
        internal val KEY_RECORDING_BACKUP_FOLDER_URI =
            stringPreferencesKey("recording_backup_folder_uri")
        /** DataStore key `pending_countdown_start_elapsed_millis` — unit-test raw-prefs assertion. */
        internal val KEY_PENDING_COUNTDOWN_START_ELAPSED_MILLIS =
            longPreferencesKey("pending_countdown_start_elapsed_millis")
        /** DataStore key `pending_countdown_duration_minutes` — unit-test raw-prefs assertion. */
        internal val KEY_PENDING_COUNTDOWN_DURATION_MINUTES =
            intPreferencesKey("pending_countdown_duration_minutes")
        /** DataStore key `pending_countdown_recording_started_elapsed_millis` — unit-test. */
        internal val KEY_PENDING_COUNTDOWN_RECORDING_STARTED_ELAPSED_MILLIS =
            longPreferencesKey("pending_countdown_recording_started_elapsed_millis")

        /**
         * Applies per-app locale via AppCompat ([AppCompatDelegate.setApplicationLocales]).
         *
         * Persistence: API 33+ LocaleManager; pre-33 requires Manifest `autoStoreLocales=true`
         * on [androidx.appcompat.app.AppLocalesMetadataHolderService].
         *
         * Locale only — does not recreate or [Runtime.exit]. Options Screen owns `restartApp`
         * (hard restart); LanguagePicker / MainActivity first-launch owns
         * [android.app.Activity.recreate]. ViewModels must not hold Activity or call
         * recreate / exit.
         */
        fun applyLanguage(option: LanguageOption) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(option.tag),
            )
        }

        /**
         * Reads the current **per-app** locale for Options seeding.
         *
         * API 33+: [readLocaleManagerApplicationLocales] only — never
         * [AppCompatDelegate.getApplicationLocales] (static cache). Null/throw → [PerAppLocalesRead.NotReady]
         * (not English). Successful empty list → [PerAppLocalesRead.Ready] of
         * [LanguageOption.English] (policy A).
         *
         * Pre-33: AppCompat `autoStoreLocales` list. Empty → Ready(English).
         *
         * Never [android.content.res.Resources] / [android.content.res.Configuration].
         * TODO: system-locale fallback (policy B) — later sprint.
         */
        fun currentLanguageOption(context: Context): PerAppLocalesRead {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val locales = readLocaleManagerApplicationLocales(context)
                    ?: return PerAppLocalesRead.NotReady
                return PerAppLocalesRead.Ready(languageOptionFromPerAppLocales(locales))
            }
            val stored = AppCompatDelegate.getApplicationLocales()
            return PerAppLocalesRead.Ready(languageOptionFromPerAppLocales(stored))
        }

        /**
         * Maps a **successfully read** per-app [LocaleListCompat] (empty = policy A English).
         * [LanguageOption.fromTag] receives the locale's **language subtag** only
         * (`Locale.language`, e.g. `ko` from `ko-KR`) — not a raw BCP-47 string or
         * substring. Blank language → English without calling [LanguageOption.fromTag].
         */
        private fun languageOptionFromPerAppLocales(locales: LocaleListCompat): LanguageOption {
            if (locales.isEmpty) {
                return LanguageOption.English
            }
            val primary = locales[0] ?: return LanguageOption.English
            val language = primary.language
            if (language.isBlank()) {
                return LanguageOption.English
            }
            return LanguageOption.fromTag(language)
        }

        /**
         * API 33+ [LocaleManager.getApplicationLocales] seam.
         *
         * Uses [Application] when given, otherwise `applicationContext` then [context].
         * Does not abort when `applicationContext` is null.
         *
         * @return wrapped per-app list, including empty (unset / policy A);
         * `null` if the service is missing or the binder read failed (not-ready — not English).
         */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        internal fun readLocaleManagerApplicationLocales(context: Context): LocaleListCompat? {
            return try {
                val appContext = (context as? Application) ?: context.applicationContext ?: context
                val localeManager = appContext.getSystemService(LocaleManager::class.java)
                    ?: return null
                LocaleListCompat.wrap(localeManager.applicationLocales)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.w(TAG, "Failed to read LocaleManager application locales", e)
                null
            }
        }

        /**
         * Process-wide 녹음 포맷 hot cache.
         * Options optimistic set과 Record [start]가 동일 인스턴스를 본다(VM별 Flow 아님).
         * null = DataStore 미시드.
         */
        private val _recordingFormatHot = MutableStateFlow<RecordingFormat?>(null)
        val recordingFormatHot: StateFlow<RecordingFormat?> = _recordingFormatHot.asStateFlow()

        /** JVM 테스트 격리용 — 프로덕션 호출 금지. */
        fun resetRecordingFormatHotForTests() {
            _recordingFormatHot.value = null
        }

        /**
         * Process-wide 잡음 감소 모드 hot cache.
         * Options optimistic set과 RecordingEngine.create()가 동일 인스턴스를 본다(VM별 Flow 아님).
         * null = DataStore 미시드.
         */
        private val _noiseReductionModeHot = MutableStateFlow<NoiseReductionMode?>(null)
        val noiseReductionModeHot: StateFlow<NoiseReductionMode?> = _noiseReductionModeHot.asStateFlow()

        /** JVM 테스트 격리용 — 프로덕션 호출 금지. */
        fun resetNoiseReductionModeHotForTests() {
            _noiseReductionModeHot.value = null
        }

        /**
         * Process-wide 마이크 입력 소스 hot cache.
         * Options optimistic set과 RecordingEngine.create()가 동일 인스턴스를 본다(VM별 Flow 아님).
         * null = DataStore 미시드.
         */
        private val _microphoneSourceHot = MutableStateFlow<MicrophoneSource?>(null)
        val microphoneSourceHot: StateFlow<MicrophoneSource?> = _microphoneSourceHot.asStateFlow()

        /** JVM 테스트 격리용 — 프로덕션 호출 금지. */
        fun resetMicrophoneSourceHotForTests() {
            _microphoneSourceHot.value = null
        }
    }
}
