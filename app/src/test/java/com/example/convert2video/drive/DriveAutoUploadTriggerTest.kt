package com.example.convert2video.drive

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.drive.enqueueDriveAutoUploadIfEnabled as productionEnqueueDriveAutoUploadIfEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DriveAutoUploadTriggerTest {

    /** Google-flow tests must stay Google in Huawei variant test runs. */
    private suspend fun enqueueDriveAutoUploadIfEnabled(
        context: Context,
        file: File,
        format: RecordingFormat,
        settingsRepository: SettingsRepository,
        isAuthorized: () -> Boolean,
        fileUriFor: (Context, File) -> String,
        enqueueUniqueWork: (
            uniqueWorkName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) -> Unit,
        capabilities: StoreCapabilities = StoreCapabilities.forStoreId(
            StoreCapabilities.GOOGLE_PLAY_STORE_ID,
        ),
    ): Unit = productionEnqueueDriveAutoUploadIfEnabled(
        context = context,
        file = file,
        format = format,
        settingsRepository = settingsRepository,
        isAuthorized = isAuthorized,
        fileUriFor = fileUriFor,
        enqueueUniqueWork = enqueueUniqueWork,
        capabilities = capabilities,
    )

    @Test
    fun success_networkTypeForDriveWifiOnly_false_usesConnected() {
        // Given
        val wifiOnly = false

        // When
        val networkType = networkTypeForDriveWifiOnly(wifiOnly)

        // Then
        assertEquals(NetworkType.CONNECTED, networkType)
    }

    @Test
    fun success_networkTypeForDriveWifiOnly_true_usesUnmetered() {
        // Given
        val wifiOnly = true

        // When
        val networkType = networkTypeForDriveWifiOnly(wifiOnly)

        // Then
        assertEquals(NetworkType.UNMETERED, networkType)
    }

    @Test
    fun success_driveContentTypeFor_aac_isAudioMp4() {
        // Given
        val format = RecordingFormat.AAC

        // When
        val contentType = driveContentTypeFor(format)

        // Then
        assertEquals("audio/mp4", contentType)
    }

    @Test
    fun success_driveContentTypeFor_wav_isAudioWav() {
        // Given
        val format = RecordingFormat.WAV

        // When
        val contentType = driveContentTypeFor(format)

        // Then
        assertEquals("audio/wav", contentType)
    }

    @Test
    fun success_driveAutoUploadWorkName_usesPrefixAndEncodedUri() {
        // Given
        val fileUri = "content://com.convert2video.fileprovider/recordings/c2v_rec.m4a"

        // When
        val workName = driveAutoUploadWorkName(fileUri)

        // Then
        assertTrue(workName.startsWith("drive_auto_upload_"))
        assertEquals("drive_auto_upload_" + Uri.encode(fileUri), workName)
    }

    @Test
    fun failure_enqueue_skippedWhenAutoUploadDisabled() = runTest {
        // Given — default driveAutoUploadEnabled is false
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        // When — internal overload + Fake WM seam (not public WorkManager path)
        enqueueDriveAutoUploadIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            format = RecordingFormat.AAC,
            settingsRepository = settings,
            isAuthorized = { true },
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
    }

    @Test
    fun failure_enqueue_skippedWhenUnauthorized() = runTest {
        // Given
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setDriveAutoUploadEnabled(true)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()

        // When
        enqueueDriveAutoUploadIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            format = RecordingFormat.AAC,
            settingsRepository = settings,
            isAuthorized = { false },
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
    }

    @Test
    fun exception_enqueue_swallowsFailuresWithoutThrow() = runTest {
        // Given — authorized + enabled, but enqueue blows up
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setDriveAutoUploadEnabled(true)
        val context: Context = Application()
        var boomHit = false

        // When / Then — must not throw; boom must have been invoked
        try {
            enqueueDriveAutoUploadIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                format = RecordingFormat.AAC,
                settingsRepository = settings,
                isAuthorized = { true },
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = { _, _, _ ->
                    boomHit = true
                    error("fake WM boom")
                },
            )
        } catch (e: Throwable) {
            fail("expected swallow, but threw: ${e.javaClass.simpleName}")
        }
        assertTrue("enqueueUniqueWork must have been invoked before swallow", boomHit)
    }

    @Test
    fun exception_enqueue_rethrowsCancellationException() = runTest {
        // Given
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setDriveAutoUploadEnabled(true)
        val context: Context = Application()
        var wasInvoked = false

        // When / Then
        try {
            enqueueDriveAutoUploadIfEnabled(
                context = context,
                file = File("c2v_rec.m4a"),
                format = RecordingFormat.AAC,
                settingsRepository = settings,
                isAuthorized = { true },
                fileUriFor = { _, f -> "content://test/${f.name}" },
                enqueueUniqueWork = { _, _, _ ->
                    wasInvoked = true
                    throw CancellationException("cancel-after-build")
                },
            )
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("cancel-after-build", ce.message)
        }
        assertTrue(wasInvoked)
    }

    @Test
    fun success_enqueue_callsWorkManagerWhenEnabledAndAuthorized() = runTest {
        // Given
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        settings.setDriveAutoUploadEnabled(true)
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val file = File("c2v_rec.m4a")
        val fileUri = "content://test/${file.name}"

        // When — internal Fake WM seam + String fileUriFor (no FileProvider / Uri.parse)
        enqueueDriveAutoUploadIfEnabled(
            context = context,
            file = file,
            format = RecordingFormat.AAC,
            settingsRepository = settings,
            isAuthorized = { true },
            fileUriFor = { _, f -> "content://test/${f.name}" },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
        )

        // Then
        assertTrue("Fake WM must be called on enabled+authorized path", fakeWm.wasCalled)
        assertEquals(ExistingWorkPolicy.KEEP, fakeWm.policy)
        assertEquals(driveAutoUploadWorkName(fileUri), fakeWm.uniqueWorkName)
        val input = fakeWm.request!!.workSpec.input
        assertEquals(fileUri, input.getString(DriveAutoUploadWorker.KEY_FILE_URI))
        assertEquals(file.name, input.getString(DriveAutoUploadWorker.KEY_DISPLAY_NAME))
        assertEquals("audio/mp4", input.getString(DriveAutoUploadWorker.KEY_CONTENT_TYPE))
    }

    @Test
    fun success_huawei_skipsSettingsAuthAndEnqueue() = runTest {
        // Given — Huawei capability must short-circuit before every Google-only read
        val settings = SettingsRepository(ThrowingPreferencesDataStore())
        val fakeWm = FakeWorkEnqueuer()
        val context: Context = Application()
        val huawei = StoreCapabilities.forStoreId(StoreCapabilities.HUAWEI_STORE_ID)

        // When
        enqueueDriveAutoUploadIfEnabled(
            context = context,
            file = File("c2v_rec.m4a"),
            format = RecordingFormat.AAC,
            settingsRepository = settings,
            isAuthorized = { throw AssertionError("Huawei must not read Drive auth") },
            fileUriFor = { _, _ -> throw AssertionError("Huawei must not resolve upload URI") },
            enqueueUniqueWork = fakeWm::enqueueUniqueWork,
            capabilities = huawei,
        )

        // Then
        assertFalse(fakeWm.wasCalled)
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

        fun enqueueUniqueWork(
            uniqueWorkName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            wasCalled = true
            this.uniqueWorkName = uniqueWorkName
            this.policy = existingWorkPolicy
            this.request = request
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

    private class ThrowingPreferencesDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            error("Huawei must not read Drive settings")
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = error("Huawei must not write Drive settings")
    }
}
