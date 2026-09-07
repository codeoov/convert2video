package com.example.convert2video.video

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.data.YoutubeDefaultVisibility
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.ui.screens.convert.ConvertViewModel
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.youtube.YouTubeUploadWorker
import com.example.convert2video.video.enqueueAutoConvertIfEnabled as productionEnqueueAutoConvertIfEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RecordingAutoConvertTriggerTest {

    /** Google-flow tests must stay Google even when this test class runs in Huawei source sets. */
    private suspend fun enqueueAutoConvertIfEnabled(
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
        youtubeAutoUploadEnabled: suspend () -> Boolean = {
            settingsRepository.youtubeAutoUploadEnabled.first()
        },
        wifiOnlyUpload: suspend () -> Boolean = { settingsRepository.wifiOnlyUpload.first() },
        defaultVisibility: suspend () -> YoutubeDefaultVisibility = {
            settingsRepository.youtubeDefaultVisibility.first()
        },
        capabilities: StoreCapabilities = StoreCapabilities.forStoreId(
            StoreCapabilities.GOOGLE_PLAY_STORE_ID,
        ),
    ): Unit = productionEnqueueAutoConvertIfEnabled(
        context = context,
        file = file,
        settingsRepository = settingsRepository,
        fileUriFor = fileUriFor,
        enqueueUniqueWork = enqueueUniqueWork,
        backgroundsDir = backgroundsDir,
        isManualConversionRunning = isManualConversionRunning,
        isYoutubeAuthorized = isYoutubeAuthorized,
        youtubeAutoUploadEnabled = youtubeAutoUploadEnabled,
        wifiOnlyUpload = wifiOnlyUpload,
        defaultVisibility = defaultVisibility,
        capabilities = capabilities,
    )

    private suspend fun SettingsRepository.enableYoutubeAutoUploadForTriggerTests() {
        setYoutubeAutoUploadEnabled(true)
    }

    @Test
    fun success_autoConvertWorkName_usesPrefixAndEncodedUri() {
        // Given
        val fileUri = "content://com.convert2video.fileprovider/recordings/c2v_rec.m4a"

        // When
        val workName = autoConvertWorkName(fileUri)

        // Then
        assertTrue(workName.startsWith("auto_convert_"))
        assertEquals("auto_convert_" + Uri.encode(fileUri), workName)
        assertNotEquals(MANUAL_CONVERSION_WORK_NAME, workName)
    }

    @Test
    fun success_manualConversionWorkName_equalsConvertViewModelConstant() {
        // Given / When / Then — Trigger must not import convert; tests may.
        assertEquals(CONVERSION_UNIQUE_WORK_NAME, MANUAL_CONVERSION_WORK_NAME)
        assertEquals(ConvertViewModel.CONVERSION_WORK_NAME, MANUAL_CONVERSION_WORK_NAME)
    }

    @Test
    fun failure_manualSkip_enqueuedOrBlockedOrRunning_isTrue() {
        // Given / When / Then — !isFinished
        assertTrue(shouldSkipForManualConversionStates(listOf(WorkInfo.State.ENQUEUED)))
        assertTrue(shouldSkipForManualConversionStates(listOf(WorkInfo.State.BLOCKED)))
        assertTrue(shouldSkipForManualConversionStates(listOf(WorkInfo.State.RUNNING)))
    }

    @Test
    fun failure_manualSkip_queryThrow_failClosedTrue() {
        // Given / When / Then
        assertTrue(
            shouldSkipForManualConversionQuery { error("wm get() timeout") },
        )
    }

    @Test
    fun success_manualSkip_finishedOrEmpty_isFalse() {
        // Given / When / Then
        assertFalse(shouldSkipForManualConversionStates(emptyList()))
        assertFalse(shouldSkipForManualConversionStates(listOf(WorkInfo.State.SUCCEEDED)))
        assertFalse(shouldSkipForManualConversionStates(listOf(WorkInfo.State.FAILED)))
        assertFalse(shouldSkipForManualConversionStates(listOf(WorkInfo.State.CANCELLED)))
    }

    @Test
    fun exception_shouldSkipForManualConversionQuery_rethrowsCancellationExceptionFromManualQuery() {
        // Given / When / Then — CE must not fail-closed
        try {
            shouldSkipForManualConversionQuery { throw CancellationException("manual-query-cancel") }
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("manual-query-cancel", ce.message)
        }
    }

    @Test
    fun success_pathInsideDirectory_confinedFile_isTrue() {
        // Given
        val (bgDir, bgFile) = confinedBackgroundFixture()

        // When / Then — default isPathInsideDirectory (no Boolean fold)
        assertEquals(true, isPathInsideDirectory(bgFile, bgDir))
        assertEquals(true, evaluateExistingLastUsedFile(bgFile))
        assertFalse(shouldClearLastUsedBackgroundPath(true, true))
    }

    @Test
    fun failure_pathInsideDirectory_outsideDir_isFalse() {
        // Given
        val bgDir = unusedBackgroundsDir()
        val outside = File.createTempFile("c2v_outside", ".jpg").apply {
            writeText("x")
            deleteOnExit()
        }

        // When / Then — outside is false, not undetermined; Boolean helper removed
        assertEquals(false, isPathInsideDirectory(outside, bgDir))
        assertEquals(true, evaluateExistingLastUsedFile(outside))
        assertTrue(shouldClearLastUsedBackgroundPath(true, false))
    }

    @Test
    fun success_shouldClearLastUsed_missingOrOutside_isTrue() {
        // Given / When / Then — missing/non-file or outside dir
        assertTrue(shouldClearLastUsedBackgroundPath(isExistingFile = false, pathInside = null))
        assertTrue(shouldClearLastUsedBackgroundPath(isExistingFile = true, pathInside = false))
    }

    @Test
    fun success_shouldClearLastUsed_undetermined_isFalse() {
        // Given / When / Then — canonical 판정 불가 must not clear (distinct from isFile false)
        assertFalse(shouldClearLastUsedBackgroundPath(isExistingFile = true, pathInside = null))
        assertFalse(shouldClearLastUsedBackgroundPath(isExistingFile = true, pathInside = true))
    }

    @Test
    fun success_shouldClearLastUsed_isFileUndetermined_isFalse() {
        // Given / When / Then — isFile Exception is unknown; not the same as isFile false
        assertFalse(shouldClearLastUsedBackgroundPath(isExistingFile = null, pathInside = null))
        assertFalse(shouldClearLastUsedBackgroundPath(isExistingFile = null, pathInside = false))
    }

    @Test
    fun failure_evaluateExistingLastUsedFile_securityException_isNull() {
        // Given
        val file = SecurityThrowingIsFile("lastUsed.jpg")

        // When
        val result = evaluateExistingLastUsedFile(file)

        // Then — isFile throw ≠ isFile false
        assertNull(result)
        assertFalse(shouldClearLastUsedBackgroundPath(result, pathInside = null))
    }

    @Test
    fun failure_evaluateExistingLastUsedFile_missing_isFalse() {
        // Given — isFile false is known missing, not undetermined
        val missing = File("/data/user/0/com.convert2video/files/backgrounds/missing.jpg")

        // When
        val result = evaluateExistingLastUsedFile(missing)

        // Then — isFile false → clear; distinct from isFile Exception
        assertEquals(false, result)
        assertTrue(shouldClearLastUsedBackgroundPath(result, pathInside = null))
    }

    @Test
    fun exception_evaluateExistingLastUsedFile_rethrowsCancellationException() {
        // Given / When / Then
        try {
            evaluateExistingLastUsedFile(CancellationThrowingIsFile("lastUsed.jpg"))
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("isFile-cancel", ce.message)
        }
    }

    @Test
    fun failure_isPathInsideDirectory_canonicalIoException_isNull() {
        // Given — default isPathInsideDirectory (no pathInsideDirectory stub)
        val dir = unusedBackgroundsDir()
        val file = IoThrowingCanonicalFile("lastUsed.jpg")

        // When
        val pathInside = isPathInsideDirectory(file, dir)

        // Then — undetermined is null, not false; enqueue SSOT does not clear
        assertEquals(true, evaluateExistingLastUsedFile(file))
        assertNull(pathInside)
        assertFalse(shouldClearLastUsedBackgroundPath(true, pathInside))
    }

    @Test
    fun failure_isPathInsideDirectory_canonicalSecurityException_isNull() {
        // Given
        val dir = unusedBackgroundsDir()
        val file = SecurityThrowingCanonicalFile("lastUsed.jpg")

        // When
        val pathInside = isPathInsideDirectory(file, dir)

        // Then
        assertEquals(true, evaluateExistingLastUsedFile(file))
        assertNull(pathInside)
        assertFalse(shouldClearLastUsedBackgroundPath(true, pathInside))
    }

    @Test
    fun success_evaluatePathInsideDirectory_trueFalse() {
        // Given / When / Then
        assertEquals(true, evaluatePathInsideDirectory { true })
        assertEquals(false, evaluatePathInsideDirectory { false })
    }

    @Test
    fun failure_evaluatePathInsideDirectory_ioException_isNull() {
        // Given / When
        val result = evaluatePathInsideDirectory { throw java.io.IOException("canon boom") }

        // Then — 판정 불가, not outside
        assertNull(result)
    }

    @Test
    fun failure_evaluatePathInsideDirectory_securityException_isNull() {
        // Given / When
        val result = evaluatePathInsideDirectory { throw SecurityException("denied") }

        // Then
        assertNull(result)
    }

    @Test
    fun exception_evaluatePathInsideDirectory_rethrowsCancellationException() {
        // Given / When / Then
        try {
            evaluatePathInsideDirectory { throw CancellationException("path-cancel") }
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("path-cancel", ce.message)
        }
    }

    @Test
    fun failure_enqueue_skippedWhenLastUsedBackgroundPathNull() = runTest {
        // Given — master ON but lastUsedBackgroundPath is null
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val auth = AuthCallCounter()

        // When — internal overload + Fake WM seam (not public WorkManager path)
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = unusedBackgroundsDir(),
            isYoutubeAuthorized = auth::isYoutubeAuthorized,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
        assertEquals(0, auth.authCalls)
    }

    @Test
    fun failure_enqueue_skippedWhenLastUsedBackgroundPathBlank() = runTest {
        // Given — master ON; blank write removes the key; Trigger still skips
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.enableYoutubeAutoUploadForTriggerTests()
        settings.setLastUsedBackgroundPath("   ")
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val auth = AuthCallCounter()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = unusedBackgroundsDir(),
            isYoutubeAuthorized = auth::isYoutubeAuthorized,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
        assertEquals(0, auth.authCalls)
    }

    @Test
    fun failure_enqueue_skippedWhenLastUsedBackgroundPathEmptyString() = runTest {
        // Given — master ON; stale empty string still in DataStore (bypass setter blank-remove)
        val store = InMemoryPreferencesDataStore()
        store.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[stringPreferencesKey("last_used_background_path")] = ""
            }
        }
        val settings = SettingsRepository(store)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val auth = AuthCallCounter()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = unusedBackgroundsDir(),
            isYoutubeAuthorized = auth::isYoutubeAuthorized,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
        assertEquals(0, auth.authCalls)
    }

    @Test
    fun failure_enqueue_skippedWhenBackgroundPathIsNotAFile() = runTest {
        // Given — non-blank path that is not an existing file
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(
            "/data/user/0/com.convert2video/files/backgrounds/missing.jpg",
        )
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val auth = AuthCallCounter()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = unusedBackgroundsDir(),
            isYoutubeAuthorized = auth::isYoutubeAuthorized,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
        assertNull(settings.lastUsedBackgroundPath.first())
        assertEquals(0, auth.authCalls)
    }

    @Test
    fun failure_enqueue_skippedWhenBackgroundNotUnderBackgroundsDir() = runTest {
        // Given — existing file outside backgroundsDir
        val bgDir = unusedBackgroundsDir()
        val outside = File.createTempFile("c2v_outside", ".jpg").apply {
            writeText("x")
            deleteOnExit()
        }
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(outside.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val auth = AuthCallCounter()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isYoutubeAuthorized = auth::isYoutubeAuthorized,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
        assertNull(settings.lastUsedBackgroundPath.first())
        assertEquals(0, auth.authCalls)
    }

    @Test
    fun failure_enqueue_skippedWhenManualWorkEnqueued() = runTest {
        // Given — ENQUEUED fixture via !isFinished query seam
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val auth = AuthCallCounter()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isManualConversionRunning = {
                shouldSkipForManualConversionQuery { listOf(WorkInfo.State.ENQUEUED) }
            },
            isYoutubeAuthorized = auth::isYoutubeAuthorized,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
        assertEquals(0, auth.authCalls)
    }

    @Test
    fun failure_enqueue_skippedWhenManualWorkBlocked() = runTest {
        // Given — BLOCKED fixture via !isFinished query seam
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val auth = AuthCallCounter()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isManualConversionRunning = {
                shouldSkipForManualConversionQuery { listOf(WorkInfo.State.BLOCKED) }
            },
            isYoutubeAuthorized = auth::isYoutubeAuthorized,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
        assertEquals(0, auth.authCalls)
    }

    @Test
    fun failure_enqueue_skippedWhenManualWorkQueryThrows() = runTest {
        // Given — get() timeout / throw → fail-closed skip
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val auth = AuthCallCounter()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isManualConversionRunning = {
                shouldSkipForManualConversionQuery { error("wm get() timeout") }
            },
            isYoutubeAuthorized = auth::isYoutubeAuthorized,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
        assertEquals(0, auth.authCalls)
    }

    @Test
    fun failure_enqueue_skippedWhenManualConversionRunning() = runTest {
        // Given
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val auth = AuthCallCounter()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isManualConversionRunning = { true },
            isYoutubeAuthorized = auth::isYoutubeAuthorized,
        )

        // Then — unique names stay separate; auto work is not enqueued
        assertFalse(fakeWm.wasCalled)
        assertNotEquals(MANUAL_CONVERSION_WORK_NAME, autoConvertWorkName("content://test/c2v_rec.m4a"))
        assertEquals(0, auth.authCalls)
    }

    @Test
    fun success_enqueue_toggleFalse_convertOnlyLoggedSkip() = runTest {
        // Given — usable lastUsed; authorized; explicit toggle OFF seam (not DataStore default)
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, _ -> captured += msg }
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        var toggleCalls = 0
        try {
            // When
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { true },
                youtubeAutoUploadEnabled = {
                    toggleCalls++
                    false
                },
            )
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — convert-only, unconstrained; toggle was read once; skip is logged (not quiet)
        assertTrue(fakeWm.wasCalled)
        assertNull(fakeWm.youtubeRequest)
        assertEquals(1, toggleCalls)
        assertEquals(ConversionWorker::class.java.name, fakeWm.request!!.workSpec.workerClassName)
        assertFalse(fakeWm.request!!.workSpec.hasConstraints())
        assertEquals(bgFile.absolutePath, settings.lastUsedBackgroundPath.first())
        assertTrue(captured.any { it.contains("youtube step omitted: toggle off") })
        assertFalse(captured.any { it.contains("youtube chain degraded") })
    }

    @Test
    fun success_enqueue_callsWorkManagerWhenPathSet() = runTest {
        // Given
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val file = File("c2v_rec.m4a")
        val fileUri = "content://test/${file.name}"

        // When — internal Fake WM seam + String fileUriFor (no FileProvider / Uri.parse)
        enqueueAutoConvertIfEnabled(
            context = context,
            file = file,
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
        )

        // Then
        assertTrue("Fake WM must be called when lastUsedBackgroundPath is a file", fakeWm.wasCalled)
        assertEquals(ExistingWorkPolicy.KEEP, fakeWm.policy)
        assertEquals(autoConvertWorkName(fileUri), fakeWm.uniqueWorkName)
        assertNotEquals(MANUAL_CONVERSION_WORK_NAME, fakeWm.uniqueWorkName)
        val request = fakeWm.request!!
        assertFalse("auto convert must not call setConstraints", request.workSpec.hasConstraints())
        val input = request.workSpec.input
        assertEquals(bgFile.absolutePath, input.getString(ConversionWorker.KEY_BACKGROUND_PATH))
        assertEquals(fileUri, input.getString(ConversionWorker.KEY_AUDIO_URI))
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_START_US))
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_END_US))
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_BATCH_ID))
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_INDEX))
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_TOTAL))
        assertNull("default isYoutubeAuthorized is false — YouTube step omitted", fakeWm.youtubeRequest)
    }

    @Test
    fun success_huawei_keepsLocalConversionAndSkipsYouTubeReads() = runTest {
        // Given — a usable local background and Huawei capabilities
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val huawei = StoreCapabilities.forStoreId(StoreCapabilities.HUAWEI_STORE_ID)

        // When — every Google-only read fails if touched
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isYoutubeAuthorized = { throw AssertionError("Huawei must not read YouTube auth") },
            youtubeAutoUploadEnabled = {
                throw AssertionError("Huawei must not read YouTube settings")
            },
            wifiOnlyUpload = { throw AssertionError("Huawei must not read upload Wi-Fi setting") },
            defaultVisibility = {
                throw AssertionError("Huawei must not read YouTube visibility")
            },
            capabilities = huawei,
        )

        // Then — the local ConversionWorker request remains and no YouTube request exists
        assertTrue(fakeWm.wasCalled)
        assertEquals(ConversionWorker::class.java.name, fakeWm.request!!.workSpec.workerClassName)
        assertEquals(
            bgFile.absolutePath,
            fakeWm.request!!.workSpec.input.getString(ConversionWorker.KEY_BACKGROUND_PATH),
        )
        assertEquals("content://test/c2v_rec.m4a", fakeWm.request!!.workSpec.input.getString(ConversionWorker.KEY_AUDIO_URI))
        assertNull(fakeWm.youtubeRequest)
    }

    @Test
    fun success_networkTypeForYoutubeWifiOnly_false_usesConnected() {
        // Given
        val wifiOnly = false

        // When
        val networkType = networkTypeForYoutubeWifiOnly(wifiOnly)

        // Then
        assertEquals(NetworkType.CONNECTED, networkType)
    }

    @Test
    fun success_networkTypeForYoutubeWifiOnly_true_usesUnmetered() {
        // Given
        val wifiOnly = true

        // When
        val networkType = networkTypeForYoutubeWifiOnly(wifiOnly)

        // Then
        assertEquals(NetworkType.UNMETERED, networkType)
    }

    @Test
    fun success_enqueue_unauthorized_youtubeRequestNull_convertUnconstrained() = runTest {
        // Given — lastUsed usable, explicit !auth
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        settings.setWifiOnlyUpload(true)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val file = File("c2v_rec.m4a")
        var toggleCalls = 0

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = file,
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isYoutubeAuthorized = { false },
            youtubeAutoUploadEnabled = {
                toggleCalls++
                true
            },
        )

        // Then — conversion only, unconstrained; wifiOnly must not leak onto convert
        assertTrue(fakeWm.wasCalled)
        assertNull(fakeWm.youtubeRequest)
        assertEquals(0, toggleCalls)
        val conversion = fakeWm.request!!
        assertEquals(ConversionWorker::class.java.name, conversion.workSpec.workerClassName)
        assertFalse("conversion must stay unconstrained when !auth", conversion.workSpec.hasConstraints())
        val input = conversion.workSpec.input
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_START_US))
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_END_US))
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_BATCH_ID))
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_INDEX))
        assertFalse(input.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_TOTAL))
    }

    @Test
    fun success_enqueue_authorized_chainsYoutubePrivateWifiOnly() = runTest {
        // Given
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        settings.setWifiOnlyUpload(true)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val file = File("c2v_rec.m4a")

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = file,
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isYoutubeAuthorized = { true },
        )

        // Then — 2 steps; YouTube-only wifi constraints; no videoUri at enqueue
        assertTrue(fakeWm.wasCalled)
        assertEquals(ExistingWorkPolicy.KEEP, fakeWm.policy)
        val conversion = fakeWm.request!!
        val youtube = fakeWm.youtubeRequest!!
        assertEquals(ConversionWorker::class.java.name, conversion.workSpec.workerClassName)
        assertEquals(YouTubeUploadWorker::class.java.name, youtube.workSpec.workerClassName)
        assertFalse("conversion stays unconstrained", conversion.workSpec.hasConstraints())
        assertEquals(NetworkType.UNMETERED, youtube.workSpec.constraints.requiredNetworkType)
        val ytInput = youtube.workSpec.input
        assertEquals("private", ytInput.getString(YouTubeUploadWorker.KEY_PRIVACY_STATUS))
        assertEquals("", ytInput.getString(YouTubeUploadWorker.KEY_DESCRIPTION))
        val title = ytInput.getString(YouTubeUploadWorker.KEY_TITLE)
        assertTrue("title must be non-blank", !title.isNullOrBlank())
        assertEquals(
            C2vOutputNames.sanitizeOriginalFileStem(file.name),
            title,
        )
        assertFalse(
            "KEY_VIDEO_URI must not be set at enqueue",
            ytInput.keyValueMap.containsKey(YouTubeUploadWorker.KEY_VIDEO_URI),
        )
        val convInput = conversion.workSpec.input
        assertFalse(convInput.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_START_US))
        assertFalse(convInput.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_END_US))
        assertFalse(convInput.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_BATCH_ID))
        assertFalse(convInput.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_INDEX))
        assertFalse(convInput.keyValueMap.containsKey(ConversionWorker.KEY_SEGMENT_TOTAL))
    }

    @Test
    fun success_enqueue_authorized_usesDefaultVisibilityFromSettings() = runTest {
        // Given
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        settings.setYoutubeDefaultVisibility(YoutubeDefaultVisibility.Public)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isYoutubeAuthorized = { true },
        )

        // Then
        val ytInput = fakeWm.youtubeRequest!!.workSpec.input
        assertEquals("public", ytInput.getString(YouTubeUploadWorker.KEY_PRIVACY_STATUS))
    }

    @Test
    fun success_enqueue_authorized_youtubeConnectedWhenWifiOnlyFalse() = runTest {
        // Given — auth + wifiOnly explicitly false (unset default is true)
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        settings.setWifiOnlyUpload(false)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        // When
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isYoutubeAuthorized = { true },
        )

        // Then — YouTube CONNECTED; conversion still unconstrained
        val conversion = fakeWm.request!!
        val youtube = fakeWm.youtubeRequest!!
        assertFalse(conversion.workSpec.hasConstraints())
        assertEquals(NetworkType.CONNECTED, youtube.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun success_enqueue_authThrow_degradesToConvertOnly() = runTest {
        // Given — usable lastUsed; auth seam throws
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        var toggleCalls = 0

        // When / Then — convert still enqueued; YouTube omitted; toggle unread
        enqueueAutoConvertIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            settingsRepository = settings,
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            backgroundsDir = bgDir,
            isYoutubeAuthorized = { error("auth boom") },
            youtubeAutoUploadEnabled = {
                toggleCalls++
                true
            },
        )
        assertTrue(fakeWm.wasCalled)
        assertNull(fakeWm.youtubeRequest)
        assertEquals(0, toggleCalls)
        assertEquals(ConversionWorker::class.java.name, fakeWm.request!!.workSpec.workerClassName)
        assertFalse(fakeWm.request!!.workSpec.hasConstraints())
    }

    @Test
    fun success_enqueue_toggleThrow_degradesToConvertOnly() = runTest {
        // Given — authorized; youtubeAutoUploadEnabled seam throws non-CE
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, throwable ->
            captured += msg
            throwable?.message?.let { captured += it }
        }
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { true },
                youtubeAutoUploadEnabled = { error("toggle boom") },
            )
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — convert-only + degrade log (simpleName only)
        assertTrue(fakeWm.wasCalled)
        assertNull(fakeWm.youtubeRequest)
        assertEquals(ConversionWorker::class.java.name, fakeWm.request!!.workSpec.workerClassName)
        assertFalse(fakeWm.request!!.workSpec.hasConstraints())
        assertTrue(captured.any { it.contains("youtube chain degraded: IllegalStateException") })
        captured.forEach { text ->
            assertFalse("leaked content:// in: $text", text.contains("content://"))
            assertFalse("leaked backgrounds/ in: $text", text.contains("backgrounds/"))
        }
    }

    @Test
    fun exception_enqueue_propagatesErrorFromToggle() = runTest {
        // Given — Error ≠ Exception swallow; no degrade log
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, _ -> captured += msg }
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { true },
                youtubeAutoUploadEnabled = { throw OutOfMemoryError("toggle-oom") },
            )
            fail("expected OutOfMemoryError to propagate")
        } catch (error: OutOfMemoryError) {
            assertEquals("toggle-oom", error.message)
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — not degraded, not swallowed
        assertFalse(fakeWm.wasCalled)
        assertFalse(captured.any { it.contains("youtube chain degraded") })
    }

    @Test
    fun success_enqueue_wifiOnlyThrow_chainsYoutubeConnected() = runTest {
        // Given — auth true; wifiOnly seam throws non-CE
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, _ -> captured += msg }
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        try {
            // When
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { true },
                wifiOnlyUpload = { error("wifiOnly boom") },
            )
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — convert + YouTube CONNECTED (not convert-only); no degrade log
        assertTrue(fakeWm.wasCalled)
        val youtube = fakeWm.youtubeRequest!!
        assertEquals(YouTubeUploadWorker::class.java.name, youtube.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED, youtube.workSpec.constraints.requiredNetworkType)
        assertFalse(fakeWm.request!!.workSpec.hasConstraints())
        assertTrue(captured.any { it.contains("wifiOnly read failed; using CONNECTED") })
        assertTrue(captured.none { it.contains("youtube chain degraded") })
    }

    @Test
    fun exception_enqueue_rethrowsCancellationExceptionFromAuth() = runTest {
        // Given
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        // When / Then
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { throw CancellationException("auth-cancel") },
            )
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("auth-cancel", ce.message)
        }
        assertFalse(fakeWm.wasCalled)
    }

    @Test
    fun exception_enqueue_rethrowsCancellationExceptionFromYoutubeAutoUploadEnabled() = runTest {
        // Given — auth true so toggle seam runs
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        // When / Then
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { true },
                youtubeAutoUploadEnabled = { throw CancellationException("toggle-cancel") },
            )
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("toggle-cancel", ce.message)
        }
        assertFalse(fakeWm.wasCalled)
    }

    @Test
    fun exception_enqueue_rethrowsCancellationExceptionFromWifiOnly() = runTest {
        // Given — auth true so wifiOnly seam runs
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        // When / Then
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { true },
                wifiOnlyUpload = { throw CancellationException("wifi-cancel") },
            )
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("wifi-cancel", ce.message)
        }
        assertFalse(fakeWm.wasCalled)
    }

    @Test
    fun success_enqueue_authThrow_logsDegradeWithoutPath() = runTest {
        // Given
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, throwable ->
            captured += msg
            throwable?.message?.let { captured += it }
        }
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val context: Context = Application()
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = { _, _, _, _ -> },
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { error("auth boom") },
            )
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then
        assertTrue(captured.any { it.contains("youtube chain degraded: IllegalStateException") })
        captured.forEach { text ->
            assertFalse("leaked content:// in: $text", text.contains("content://"))
            assertFalse("leaked backgrounds/ in: $text", text.contains("backgrounds/"))
        }
    }

    @Test
    fun success_enqueue_unauthorized_doesNotLogDegrade() = runTest {
        // Given
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, _ -> captured += msg }
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { false },
            )
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — logged convert-only skip (not degrade)
        assertTrue(fakeWm.wasCalled)
        assertNull(fakeWm.youtubeRequest)
        assertEquals(ConversionWorker::class.java.name, fakeWm.request!!.workSpec.workerClassName)
        assertTrue(captured.any { it.contains("youtube step omitted: not authorized") })
        assertFalse(captured.any { it.contains("youtube chain degraded") })
    }

    @Test
    fun success_enqueue_blankTitle_doesNotLogDegrade() = runTest {
        // Given
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, _ -> captured += msg }
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("_.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { true },
            )
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — logged convert-only skip (not degrade)
        assertTrue(fakeWm.wasCalled)
        assertNull(fakeWm.youtubeRequest)
        assertEquals(ConversionWorker::class.java.name, fakeWm.request!!.workSpec.workerClassName)
        assertTrue(captured.any { it.contains("youtube step omitted: blank title") })
        assertFalse(captured.any { it.contains("youtube chain degraded") })
    }

    @Test
    fun success_enqueue_blankSanitizedTitle_omitsYoutube() = runTest {
        // Given — `_.m4a` sanitizes to null (underscore-only stem)
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, _ -> captured += msg }
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val file = File("_.m4a")

        try {
            // When
            enqueueAutoConvertIfEnabled(
                context = context,
                file = file,
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
                isYoutubeAuthorized = { true },
            )
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — convert-only + blank title skip log
        assertNull(youtubeAutoUploadTitleOrNull(file.name))
        assertTrue(fakeWm.wasCalled)
        assertNull(fakeWm.youtubeRequest)
        assertEquals(ConversionWorker::class.java.name, fakeWm.request!!.workSpec.workerClassName)
        assertTrue(captured.any { it.contains("youtube step omitted: blank title") })
        assertFalse(captured.any { it.contains("youtube chain degraded") })
    }

    @Test
    fun success_enqueueAssembledChain_enqueuesThenResultNotStarted() {
        // Given
        var enqueued: String? = null

        // When
        enqueueAssembledAutoConvertChain(
            started = "started",
            youtube = "yt",
            then = { chain, yt -> "$chain.then($yt)" },
            enqueue = { enqueued = it },
        )

        // Then — enqueue receives then() result, not the original continuation
        assertEquals("started.then(yt)", enqueued)
    }

    @Test
    fun success_enqueueAssembledChain_withoutYoutube_enqueuesStarted() {
        // Given
        var enqueued: String? = null
        var thenCalls = 0

        // When
        enqueueAssembledAutoConvertChain(
            started = "started",
            youtube = null as String?,
            then = { _, _ ->
                thenCalls++
                "should-not-run"
            },
            enqueue = { enqueued = it },
        )

        // Then
        assertEquals("started", enqueued)
        assertEquals(0, thenCalls)
    }

    @Test
    fun success_youtubeAutoUploadTitleOrNull_underscoreOnly_isNull() {
        // Given / When / Then
        assertNull(youtubeAutoUploadTitleOrNull("_.m4a"))
        assertNull(youtubeAutoUploadTitleOrNull("???.wav"))
        assertEquals("c2v_rec", youtubeAutoUploadTitleOrNull("c2v_rec.m4a"))
        assertEquals(
            "(C2V)2026-08-16_10-00-00",
            youtubeAutoUploadTitleOrNull("(C2V)2026-08-16_10-00-00.m4a"),
        )
    }

    @Test
    fun exception_enqueue_swallowsFailuresWithoutThrow() = runTest {
        // Given — path is a real file, but enqueue blows up
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val context: Context = Application()
        var boomHit = false

        // When / Then — must not throw; boom must have been invoked
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = { _, _, _, _ ->
                    boomHit = true
                    error("fake WM boom")
                },
                backgroundsDir = bgDir,
            )
        } catch (e: Throwable) {
            fail("expected swallow, but threw: ${e.javaClass.simpleName}")
        }
        assertTrue("enqueueUniqueWork must have been invoked before swallow", boomHit)
    }

    @Test
    fun exception_enqueue_swallowsFileUriForThrow() = runTest {
        // Given
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        // When / Then
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, _ -> error("fileUriFor boom") },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = bgDir,
            )
        } catch (e: Throwable) {
            fail("expected swallow, but threw: ${e.javaClass.simpleName}")
        }
        assertFalse(fakeWm.wasCalled)
    }

    @Test
    fun exception_enqueue_swallowsFirstThrow() = runTest {
        // Given — lastUsedBackgroundPath.first() throws non-IOException
        val settings = SettingsRepository(
            ThrowingReadDataStore(IllegalStateException("first() boom")),
        )
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        // When / Then
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = fakeWm::enqueueUniqueWork,
                backgroundsDir = unusedBackgroundsDir(),
                youtubeAutoUploadEnabled = { true },
            )
        } catch (e: Throwable) {
            fail("expected swallow, but threw: ${e.javaClass.simpleName}")
        }
        assertFalse(fakeWm.wasCalled)
    }

    @Test
    fun exception_enqueue_rethrowsCancellationException() = runTest {
        // Given
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val context: Context = Application()
        var wasInvoked = false

        // When / Then
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = { _, _, _, _ ->
                    wasInvoked = true
                    throw CancellationException("cancel-after-build")
                },
                backgroundsDir = bgDir,
            )
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("cancel-after-build", ce.message)
        }
        assertTrue(wasInvoked)
    }

    @Test
    fun failure_enqueue_logDoesNotLeakPathOrContentUri() = runTest {
        // Given — exception message contains path/URI tokens that must not reach persist
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, throwable ->
            captured += msg
            throwable?.message?.let { captured += it }
            throwable?.let { captured += it.stackTraceToString() }
        }
        val (bgDir, bgFile) = confinedBackgroundFixture()
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setLastUsedBackgroundPath(bgFile.absolutePath)
        settings.enableYoutubeAutoUploadForTriggerTests()
        val context: Context = Application()
        try {
            enqueueAutoConvertIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                settingsRepository = settings,
                fileUriFor = { _, _ ->
                    error("boom at content://secret/files/backgrounds/x.jpg")
                },
                enqueueUniqueWork = { _, _, _, _ -> error("unused") },
                backgroundsDir = bgDir,
            )
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — ErrorLog/log strings must not contain path or content URI
        assertTrue("expected at least one persist log", captured.isNotEmpty())
        captured.forEach { text ->
            assertFalse("leaked backgrounds/ in: $text", text.contains("backgrounds/"))
            assertFalse("leaked content:// in: $text", text.contains("content://"))
        }
    }

    private fun unusedBackgroundsDir(): File =
        File.createTempFile("c2v_bg_unused", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }

    /**
     * lastUsed fixture under a fake [BackgroundRepository.backgroundsDir].
     * Temp files outside this dir must not be used for success enqueue tests.
     */
    private fun confinedBackgroundFixture(): Pair<File, File> {
        val dir = unusedBackgroundsDir()
        val file = File(dir, "bg.jpg").apply {
            writeText("x")
            deleteOnExit()
        }
        return dir to file
    }

    /** Counts [isYoutubeAuthorized] invocations — early skip must stay at 0. */
    private class AuthCallCounter {
        var authCalls: Int = 0
            private set

        fun isYoutubeAuthorized(): Boolean {
            authCalls++
            return true
        }
    }

    /**
     * [File.isFile] throws [SecurityException] — used with [evaluateExistingLastUsedFile]
     * (enqueue reconstructs File(path); this is not an enqueue injection stub).
     */
    private class SecurityThrowingIsFile(path: String) : File(path) {
        override fun isFile(): Boolean {
            throw SecurityException("isFile denied")
        }
    }

    private class CancellationThrowingIsFile(path: String) : File(path) {
        override fun isFile(): Boolean {
            throw CancellationException("isFile-cancel")
        }
    }

    /**
     * Default [isPathInsideDirectory] evidence: canonical IOException → null, not false.
     * No `pathInsideDirectory` seam stub.
     */
    private class IoThrowingCanonicalFile(path: String) : File(path) {
        override fun isFile(): Boolean = true
        override fun getCanonicalFile(): File {
            throw java.io.IOException("canon boom")
        }
    }

    private class SecurityThrowingCanonicalFile(path: String) : File(path) {
        override fun isFile(): Boolean = true
        override fun getCanonicalFile(): File {
            throw SecurityException("canon denied")
        }
    }

    /** Capturing Fake WM — WorkManager itself cannot be subclassed outside androidx.work. */
    private class FakeWorkEnqueuer {
        var wasCalled: Boolean = false
            private set
        var uniqueWorkName: String? = null
            private set
        var policy: ExistingWorkPolicy? = null
            private set
        var request: OneTimeWorkRequest? = null
            private set
        var youtubeRequest: OneTimeWorkRequest? = null
            private set

        fun enqueueUniqueWork(
            uniqueWorkName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            conversionRequest: OneTimeWorkRequest,
            youtubeRequest: OneTimeWorkRequest?,
        ) {
            wasCalled = true
            this.uniqueWorkName = uniqueWorkName
            this.policy = existingWorkPolicy
            this.request = conversionRequest
            this.youtubeRequest = youtubeRequest
        }
    }

    /** Minimal in-memory Preferences DataStore for JVM SettingsRepository tests. */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            val updated = transform(state.value)
            state.update { updated }
            updated
        }
    }

    /** DataStore whose [data] Flow throws (non-IOException) so Flow.catch rethrows. */
    private class ThrowingReadDataStore(
        private val boom: Throwable,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw boom }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw boom
    }
}
