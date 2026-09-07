package com.example.convert2video.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import com.example.convert2video.record.MicrophoneSource
import com.example.convert2video.record.NoiseReductionMode
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.utils.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * JVM unit tests for [SettingsRepository].
 *
 * On Windows, PreferenceDataStoreFactory file rename during a second `edit` is unreliable,
 * so [createRepository] uses [InMemoryPreferencesDataStore] there; exception/read seams
 * always use the in-memory stub (OS-agnostic).
 */
class SettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * Builds a [SettingsRepository] for JVM tests.
     *
     * Always allocates a unique file path
     * (`settings-${UUID}.preferences_pb`) as required for file-backed stores.
     * On Windows, PreferenceDataStoreFactory cannot reliably rename an existing
     * `.preferences_pb` during a second `edit` in unit tests, so an in-memory
     * [DataStore] is used there; other OSes use the file-backed factory.
     */
    private fun TestScope.createRepository(): SettingsRepository {
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val file = File(
            temporaryFolder.root,
            "settings-${UUID.randomUUID()}.preferences_pb",
        )
        check(file.parentFile == temporaryFolder.root)
        val dataStore: DataStore<Preferences> =
            if (isWindowsJvm()) {
                InMemoryPreferencesDataStore()
            } else {
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { file },
                )
            }
        return SettingsRepository(dataStore)
    }

    @Test
    fun success_defaultWifiOnlyUploadIsTrue() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.wifiOnlyUpload.first()

        // Then
        assertTrue(value)
    }

    @Test
    fun success_setWifiOnlyUploadPersistsOnReread() = runTest {
        // Given
        val repository = createRepository()

        // When
        repository.setWifiOnlyUpload(true)
        val value = repository.wifiOnlyUpload.first()

        // Then
        assertEquals(true, value)
    }

    @Test
    fun success_setWifiOnlyUploadFalseAfterTrue_rereadsFalse() = runTest {
        // Given
        val repository = createRepository()
        repository.setWifiOnlyUpload(true)
        assertEquals(true, repository.wifiOnlyUpload.first())

        // When
        repository.setWifiOnlyUpload(false)
        val value = repository.wifiOnlyUpload.first()

        // Then
        assertFalse(value)
    }

    @Test
    fun success_defaultDriveAutoUploadEnabledIsFalse() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.driveAutoUploadEnabled.first()

        // Then
        assertFalse(value)
    }

    @Test
    fun success_defaultYoutubeAutoUploadEnabledIsFalse() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.youtubeAutoUploadEnabled.first()

        // Then
        assertFalse(value)
    }

    @Test
    fun success_youtubeAutoUploadKeyNameIsLiteral() {
        assertEquals(
            "youtube_auto_upload_enabled",
            SettingsRepository.KEY_YOUTUBE_AUTO_UPLOAD_ENABLED.name,
        )
    }

    @Test
    fun success_driveAutoUploadKeyNameIsLiteral() {
        assertEquals(
            "drive_auto_upload_enabled",
            SettingsRepository.KEY_DRIVE_AUTO_UPLOAD_ENABLED.name,
        )
    }

    @Test
    fun success_pendingCountdownKeyNamesAreLiterals() {
        assertEquals(
            "pending_countdown_start_elapsed_millis",
            SettingsRepository.KEY_PENDING_COUNTDOWN_START_ELAPSED_MILLIS.name,
        )
        assertEquals(
            "pending_countdown_duration_minutes",
            SettingsRepository.KEY_PENDING_COUNTDOWN_DURATION_MINUTES.name,
        )
        assertEquals(
            "pending_countdown_recording_started_elapsed_millis",
            SettingsRepository.KEY_PENDING_COUNTDOWN_RECORDING_STARTED_ELAPSED_MILLIS.name,
        )
    }

    @Test
    fun success_defaultPendingCountdownIsNull() = runTest {
        val repository = createRepository()
        assertNull(repository.pendingCountdown.first())
    }

    @Test
    fun success_setPendingCountdownPersistsStartAndDuration_startedNull() = runTest {
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)

        repository.setPendingCountdown(startElapsedMillis = 12_000L, durationMinutes = 10)
        val pending = repository.pendingCountdown.first()

        assertEquals(12_000L, pending?.startElapsedMillis)
        assertEquals(10, pending?.durationMinutes)
        assertNull(pending?.recordingStartedElapsedMillis)
        val prefs = dataStore.data.first()
        assertEquals(
            12_000L,
            prefs[SettingsRepository.KEY_PENDING_COUNTDOWN_START_ELAPSED_MILLIS],
        )
        assertEquals(
            10,
            prefs[SettingsRepository.KEY_PENDING_COUNTDOWN_DURATION_MINUTES],
        )
        assertFalse(
            prefs.contains(
                SettingsRepository.KEY_PENDING_COUNTDOWN_RECORDING_STARTED_ELAPSED_MILLIS,
            ),
        )
    }

    @Test
    fun success_markPendingCountdownRecordingStarted_rereadsElapsed() = runTest {
        val repository = createRepository()
        repository.setPendingCountdown(startElapsedMillis = 1L, durationMinutes = 5)
        repository.markPendingCountdownRecordingStarted(9_000L)
        val pending = repository.pendingCountdown.first()
        assertEquals(9_000L, pending?.recordingStartedElapsedMillis)
        assertEquals(1L, pending?.startElapsedMillis)
        assertEquals(5, pending?.durationMinutes)
    }

    @Test
    fun success_setPendingCountdown_clearsPreviousStartedKey() = runTest {
        val repository = createRepository()
        repository.setPendingCountdown(startElapsedMillis = 1L, durationMinutes = 5)
        repository.markPendingCountdownRecordingStarted(9_000L)
        assertEquals(9_000L, repository.pendingCountdown.first()?.recordingStartedElapsedMillis)

        repository.setPendingCountdown(startElapsedMillis = 2L, durationMinutes = 8)
        val pending = repository.pendingCountdown.first()
        assertEquals(2L, pending?.startElapsedMillis)
        assertEquals(8, pending?.durationMinutes)
        assertNull(pending?.recordingStartedElapsedMillis)
    }

    @Test
    fun success_clearPendingCountdown_rereadsNull() = runTest {
        val repository = createRepository()
        repository.setPendingCountdown(startElapsedMillis = 1L, durationMinutes = 5)
        repository.markPendingCountdownRecordingStarted(3L)
        repository.clearPendingCountdown()
        assertNull(repository.pendingCountdown.first())
    }

    @Test
    fun success_pendingCountdownDefaultsNullWhenDataStoreReadFails() = runTest {
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextData()
        val repository = SettingsRepository(dataStore)
        assertNull(repository.pendingCountdown.first())
    }

    @Test
    fun exception_pendingCountdownReadRethrowsNonIOException() = runTest {
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextDataWith(IllegalStateException("Injected non-IOException read failure"))
        val repository = SettingsRepository(dataStore)
        try {
            repository.pendingCountdown.first()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Injected non-IOException read failure", e.message)
        }
    }

    @Test
    fun exception_setPendingCountdownRethrowsIOException() = runTest {
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)
        try {
            repository.setPendingCountdown(1L, 5)
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger
        }
        assertNull(repository.pendingCountdown.first())
    }

    @Test
    fun success_setYoutubeAutoUploadEnabledPersistsOnReread() = runTest {
        // Given: in-memory store so raw Preferences key can be asserted
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)

        // When
        repository.setYoutubeAutoUploadEnabled(true)
        val value = repository.youtubeAutoUploadEnabled.first()

        // Then: Flow true + raw key `"youtube_auto_upload_enabled"` present
        assertEquals(true, value)
        dataStore.assertContainsYoutubeAutoUploadEnabled(expected = true)
    }

    @Test
    fun success_setYoutubeAutoUploadEnabledFalseAfterTrue_rereadsFalse() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)
        repository.setYoutubeAutoUploadEnabled(true)
        assertEquals(true, repository.youtubeAutoUploadEnabled.first())

        // When
        repository.setYoutubeAutoUploadEnabled(false)
        val value = repository.youtubeAutoUploadEnabled.first()

        // Then
        assertFalse(value)
        dataStore.assertContainsYoutubeAutoUploadEnabled(expected = false)
    }

    @Test
    fun success_setYoutubeAutoUploadEnabledFalseOnFreshStore_persistsExplicitFalse() = runTest {
        // Given: fresh store — key absent (Flow default false, not an explicit write)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)
        assertFalse(repository.youtubeAutoUploadEnabled.first())
        assertFalse(
            dataStore.data.first().contains(SettingsRepository.KEY_YOUTUBE_AUTO_UPLOAD_ENABLED),
        )

        // When: explicit false (distinct from key-absent default)
        repository.setYoutubeAutoUploadEnabled(false)

        // Then: reread false AND key `"youtube_auto_upload_enabled"` exists
        assertFalse(repository.youtubeAutoUploadEnabled.first())
        dataStore.assertContainsYoutubeAutoUploadEnabled(expected = false)
    }

    @Test
    fun success_setDriveAutoUploadEnabledPersistsOnReread() = runTest {
        // Given: in-memory store so raw Preferences key can be asserted
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)

        // When
        repository.setDriveAutoUploadEnabled(true)
        val value = repository.driveAutoUploadEnabled.first()

        // Then: Flow true + raw key `"drive_auto_upload_enabled"` present
        assertEquals(true, value)
        dataStore.assertContainsDriveAutoUploadEnabled(expected = true)
    }

    @Test
    fun success_setDriveAutoUploadEnabledFalseAfterTrue_rereadsFalse() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)
        repository.setDriveAutoUploadEnabled(true)
        assertEquals(true, repository.driveAutoUploadEnabled.first())

        // When
        repository.setDriveAutoUploadEnabled(false)
        val value = repository.driveAutoUploadEnabled.first()

        // Then
        assertFalse(value)
        dataStore.assertContainsDriveAutoUploadEnabled(expected = false)
    }

    @Test
    fun success_setDriveAutoUploadEnabledFalseOnFreshStore_persistsExplicitFalse() = runTest {
        // Given: fresh store — key absent (Flow default false, not an explicit write)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)
        assertFalse(repository.driveAutoUploadEnabled.first())
        assertFalse(
            dataStore.data.first().contains(SettingsRepository.KEY_DRIVE_AUTO_UPLOAD_ENABLED),
        )

        // When: explicit false (distinct from key-absent default)
        repository.setDriveAutoUploadEnabled(false)

        // Then: reread false AND key `"drive_auto_upload_enabled"` exists
        assertFalse(repository.driveAutoUploadEnabled.first())
        dataStore.assertContainsDriveAutoUploadEnabled(expected = false)
    }

    @Test
    fun success_youtubeAutoUploadEnabledDefaultsFalseWhenDataStoreReadFails() = runTest {
        // Given: in-memory store with one-shot data-read IOException
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextData()
        val repository = SettingsRepository(dataStore)

        // When: read-path IOException → catch → emptyPreferences → default false
        val value = repository.youtubeAutoUploadEnabled.first()

        // Then
        assertFalse(value)
    }

    @Test
    fun exception_youtubeAutoUploadEnabledReadRethrowsNonIOException() = runTest {
        // Given: non-IOException on read — else branch must rethrow IllegalStateException
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextDataWith(IllegalStateException("Injected non-IOException read failure"))
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.youtubeAutoUploadEnabled.first()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Injected non-IOException read failure", e.message)
        }
    }

    @Test
    fun exception_setYoutubeAutoUploadEnabledRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (OS-agnostic)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setYoutubeAutoUploadEnabled(true)
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — preference unchanged
        }
        assertFalse(repository.youtubeAutoUploadEnabled.first())
    }

    @Test
    fun success_setYoutubeAutoUploadEnabledSucceedsAfterIOException() = runTest {
        // Given: one-shot write failure, then seam cleared
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        try {
            repository.setYoutubeAutoUploadEnabled(true)
            fail("Expected IOException")
        } catch (_: IOException) {
            // one-shot seam released inside updateData
        }
        assertFalse(repository.youtubeAutoUploadEnabled.first())

        // When: retry after seam cleared
        repository.setYoutubeAutoUploadEnabled(true)

        // Then
        assertEquals(true, repository.youtubeAutoUploadEnabled.first())
    }

    @Test
    fun success_driveAutoUploadEnabledDefaultsFalseWhenDataStoreReadFails() = runTest {
        // Given: in-memory store with one-shot data-read IOException
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextData()
        val repository = SettingsRepository(dataStore)

        // When: read-path IOException → catch → emptyPreferences → default false
        val value = repository.driveAutoUploadEnabled.first()

        // Then
        assertFalse(value)
    }

    @Test
    fun exception_setDriveAutoUploadEnabledRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (OS-agnostic)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setDriveAutoUploadEnabled(true)
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — preference unchanged
        }
        assertFalse(repository.driveAutoUploadEnabled.first())
    }

    @Test
    fun success_setDriveAutoUploadEnabledSucceedsAfterIOException() = runTest {
        // Given: one-shot write failure, then seam cleared
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        try {
            repository.setDriveAutoUploadEnabled(true)
            fail("Expected IOException")
        } catch (_: IOException) {
            // one-shot seam released inside updateData
        }
        assertFalse(repository.driveAutoUploadEnabled.first())

        // When: retry after seam cleared
        repository.setDriveAutoUploadEnabled(true)

        // Then
        assertEquals(true, repository.driveAutoUploadEnabled.first())
    }

    @Test
    fun exception_setWifiOnlyUploadRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (wifi symmetry)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setWifiOnlyUpload(true)
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — preference unchanged
        }
        assertTrue(repository.wifiOnlyUpload.first())
    }

    @Test
    fun success_setWifiOnlyUploadSucceedsAfterIOException() = runTest {
        // Given: one-shot write failure, then seam cleared
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        try {
            repository.setWifiOnlyUpload(false)
            fail("Expected IOException")
        } catch (_: IOException) {
            // one-shot seam released inside updateData
        }
        assertTrue(repository.wifiOnlyUpload.first())

        // When: retry after seam cleared
        repository.setWifiOnlyUpload(false)

        // Then
        assertEquals(false, repository.wifiOnlyUpload.first())
    }

    @Test
    fun success_wifiOnlyUploadDefaultsTrueWhenDataStoreReadFails() = runTest {
        // Given: in-memory store with one-shot data-read IOException
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextData()
        val repository = SettingsRepository(dataStore)

        // When: read-path IOException → catch → emptyPreferences → default true
        val value = repository.wifiOnlyUpload.first()

        // Then
        assertTrue(value)
    }

    @Test
    fun success_driveAndWifiOnlyUploadKeysDoNotInterfere() = runTest {
        // Given
        val repository = createRepository()

        // When: drive on — wifi stays default true
        repository.setDriveAutoUploadEnabled(true)
        assertEquals(true, repository.driveAutoUploadEnabled.first())
        assertTrue(repository.wifiOnlyUpload.first())

        // When: wifi on — drive stays true
        repository.setWifiOnlyUpload(true)
        assertEquals(true, repository.wifiOnlyUpload.first())
        assertEquals(true, repository.driveAutoUploadEnabled.first())

        // When: drive off — wifi stays true
        repository.setDriveAutoUploadEnabled(false)
        assertFalse(repository.driveAutoUploadEnabled.first())
        assertEquals(true, repository.wifiOnlyUpload.first())
    }

    @Test
    fun success_driveWifiAndYoutubeAutoUploadKeysDoNotInterfere() = runTest {
        // Given
        val repository = createRepository()

        // When: youtube on — drive/wifi stay defaults (false / true)
        repository.setYoutubeAutoUploadEnabled(true)
        assertEquals(true, repository.youtubeAutoUploadEnabled.first())
        assertFalse(repository.driveAutoUploadEnabled.first())
        assertTrue(repository.wifiOnlyUpload.first())

        // When: drive on — youtube/wifi unchanged
        repository.setDriveAutoUploadEnabled(true)
        assertEquals(true, repository.driveAutoUploadEnabled.first())
        assertEquals(true, repository.youtubeAutoUploadEnabled.first())
        assertTrue(repository.wifiOnlyUpload.first())

        // When: wifi off — drive/youtube stay true
        repository.setWifiOnlyUpload(false)
        assertFalse(repository.wifiOnlyUpload.first())
        assertEquals(true, repository.driveAutoUploadEnabled.first())
        assertEquals(true, repository.youtubeAutoUploadEnabled.first())

        // When: youtube off — drive/wifi unchanged
        repository.setYoutubeAutoUploadEnabled(false)
        assertFalse(repository.youtubeAutoUploadEnabled.first())
        assertEquals(true, repository.driveAutoUploadEnabled.first())
        assertFalse(repository.wifiOnlyUpload.first())
    }

    @Test
    fun success_defaultRecordingFormatIsAac() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.recordingFormat.first()

        // Then
        assertEquals(RecordingFormat.AAC, value)
    }

    @Test
    fun success_setRecordingFormatPersistsOnReread() = runTest {
        // Given
        val repository = createRepository()

        // When
        repository.setRecordingFormat(RecordingFormat.WAV)
        val value = repository.recordingFormat.first()

        // Then: DataStore + process-wide hot cache 동시 갱신
        assertEquals(RecordingFormat.WAV, value)
        assertEquals(RecordingFormat.WAV, SettingsRepository.recordingFormatHot.value)
    }

    @Test
    fun success_setRecordingFormatAacAfterWav_rereadsAac() = runTest {
        // Given
        val repository = createRepository()
        repository.setRecordingFormat(RecordingFormat.WAV)
        assertEquals(RecordingFormat.WAV, repository.recordingFormat.first())

        // When
        repository.setRecordingFormat(RecordingFormat.AAC)
        val value = repository.recordingFormat.first()

        // Then
        assertEquals(RecordingFormat.AAC, value)
    }

    @Test
    fun success_defaultNoiseReductionModeIsDeviceDefault() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.noiseReductionMode.first()

        // Then
        assertEquals(NoiseReductionMode.DeviceDefault, value)
    }

    @Test
    fun success_setNoiseReductionModeOnPersistsOnReread() = runTest {
        // Given
        val repository = createRepository()

        // When
        repository.setNoiseReductionMode(NoiseReductionMode.On)
        val value = repository.noiseReductionMode.first()

        // Then: DataStore + process-wide hot cache 동시 갱신
        assertEquals(NoiseReductionMode.On, value)
        assertEquals(NoiseReductionMode.On, SettingsRepository.noiseReductionModeHot.value)
    }

    @Test
    fun success_setNoiseReductionModeOffAfterOn_rereadsOff() = runTest {
        // Given
        val repository = createRepository()
        repository.setNoiseReductionMode(NoiseReductionMode.On)
        assertEquals(NoiseReductionMode.On, repository.noiseReductionMode.first())

        // When
        repository.setNoiseReductionMode(NoiseReductionMode.Off)
        val value = repository.noiseReductionMode.first()

        // Then: DataStore + process-wide hot cache 동시 갱신
        assertEquals(NoiseReductionMode.Off, value)
        assertEquals(NoiseReductionMode.Off, SettingsRepository.noiseReductionModeHot.value)
    }

    @Test
    fun exception_setNoiseReductionModeRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (OS-agnostic)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setNoiseReductionMode(NoiseReductionMode.On)
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — hot cache rolled back to previous value
        }
        assertEquals(NoiseReductionMode.DeviceDefault, repository.noiseReductionMode.first())
        assertEquals(NoiseReductionMode.DeviceDefault, SettingsRepository.noiseReductionModeHot.value)
    }

    @Test
    fun success_defaultMicrophoneSourceIsDefault() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.microphoneSource.first()

        // Then
        assertEquals(MicrophoneSource.Default, value)
    }

    @Test
    fun success_setMicrophoneSourceBluetoothPersistsOnReread() = runTest {
        // Given
        val repository = createRepository()

        // When
        repository.setMicrophoneSource(MicrophoneSource.Bluetooth)
        val value = repository.microphoneSource.first()

        // Then: DataStore + process-wide hot cache 동시 갱신
        assertEquals(MicrophoneSource.Bluetooth, value)
        assertEquals(MicrophoneSource.Bluetooth, SettingsRepository.microphoneSourceHot.value)
    }

    @Test
    fun success_setMicrophoneSourceDefaultAfterBluetooth_rereadsDefault() = runTest {
        // Given
        val repository = createRepository()
        repository.setMicrophoneSource(MicrophoneSource.Bluetooth)
        assertEquals(MicrophoneSource.Bluetooth, repository.microphoneSource.first())

        // When
        repository.setMicrophoneSource(MicrophoneSource.Default)
        val value = repository.microphoneSource.first()

        // Then: DataStore + process-wide hot cache 동시 갱신
        assertEquals(MicrophoneSource.Default, value)
        assertEquals(MicrophoneSource.Default, SettingsRepository.microphoneSourceHot.value)
    }

    @Test
    fun exception_setMicrophoneSourceRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (OS-agnostic)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setMicrophoneSource(MicrophoneSource.Bluetooth)
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — hot cache rolled back to previous value
        }
        assertEquals(MicrophoneSource.Default, repository.microphoneSource.first())
        assertEquals(MicrophoneSource.Default, SettingsRepository.microphoneSourceHot.value)
    }

    // ── driveFolderName ───────────────────────────────────────────────────────

    @Test
    fun success_defaultDriveFolderNameIsNull() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.driveFolderName.first()

        // Then: 미설정 시 null 반환
        assertNull(value)
    }

    @Test
    fun success_setDriveFolderNamePersistsOnReread() = runTest {
        // Given
        val repository = createRepository()

        // When
        repository.setDriveFolderName("MyRecordings")
        val value = repository.driveFolderName.first()

        // Then
        assertEquals("MyRecordings", value)
    }

    @Test
    fun success_driveFolderNameDefaultsNullWhenDataStoreReadFails() = runTest {
        // Given: in-memory store with one-shot data-read IOException
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextData()
        val repository = SettingsRepository(dataStore)

        // When: read-path IOException → catch → emptyPreferences → null (key absent)
        val value = repository.driveFolderName.first()

        // Then
        assertNull(value)
    }

    @Test
    fun exception_setDriveFolderNameRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (OS-agnostic)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setDriveFolderName("NewFolder")
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — preference unchanged
        }
        assertNull(repository.driveFolderName.first())
    }

    // ── lastUsedBackgroundPath ────────────────────────────────────────────────

    @Test
    fun success_defaultLastUsedBackgroundPathIsNull() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.lastUsedBackgroundPath.first()

        // Then: 미설정 시 null 반환
        assertNull(value)
    }

    @Test
    fun success_setLastUsedBackgroundPathPersistsOnReread() = runTest {
        // Given
        val repository = createRepository()

        // When
        repository.setLastUsedBackgroundPath("/files/backgrounds/bg.jpg")
        val value = repository.lastUsedBackgroundPath.first()

        // Then
        assertEquals("/files/backgrounds/bg.jpg", value)
    }

    @Test
    fun success_setLastUsedBackgroundPathNullRemovesKeyOnReread() = runTest {
        // Given
        val repository = createRepository()
        repository.setLastUsedBackgroundPath("/files/backgrounds/bg.jpg")
        assertEquals("/files/backgrounds/bg.jpg", repository.lastUsedBackgroundPath.first())

        // When
        repository.setLastUsedBackgroundPath(null)
        val value = repository.lastUsedBackgroundPath.first()

        // Then
        assertNull(value)
    }

    @Test
    fun success_setLastUsedBackgroundPathBlankRemovesKeyOnReread() = runTest {
        // Given
        val repository = createRepository()
        repository.setLastUsedBackgroundPath("/files/backgrounds/bg.jpg")

        // When
        repository.setLastUsedBackgroundPath("   ")
        val value = repository.lastUsedBackgroundPath.first()

        // Then
        assertNull(value)
    }

    @Test
    fun exception_setLastUsedBackgroundPathNullRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (OS-agnostic)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)
        repository.setLastUsedBackgroundPath("/files/backgrounds/bg.jpg")
        dataStore.failNextUpdateData()

        // When / Then
        try {
            repository.setLastUsedBackgroundPath(null)
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — preference unchanged
        }
        assertEquals("/files/backgrounds/bg.jpg", repository.lastUsedBackgroundPath.first())
    }

    @Test
    fun success_lastUsedBackgroundPathDefaultsNullWhenDataStoreReadFails() = runTest {
        // Given: in-memory store with one-shot data-read IOException
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextData()
        val repository = SettingsRepository(dataStore)

        // When: read-path IOException → catch → emptyPreferences → null (key absent)
        val value = repository.lastUsedBackgroundPath.first()

        // Then
        assertNull(value)
    }

    @Test
    fun exception_setLastUsedBackgroundPathRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (OS-agnostic)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setLastUsedBackgroundPath("/files/backgrounds/bg.jpg")
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — preference unchanged
        }
        assertNull(repository.lastUsedBackgroundPath.first())
    }

    @Test
    fun exception_setLastUsedBackgroundPath_logDoesNotLeakPath() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, throwable ->
            captured += msg
            throwable?.message?.let { captured += it }
            throwable?.let { captured += it.stackTraceToString() }
        }
        try {
            repository.setLastUsedBackgroundPath("/files/backgrounds/secret.jpg")
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — ErrorLog must not contain the absolute path
        assertTrue(captured.isNotEmpty())
        captured.forEach { text ->
            assertFalse("leaked backgrounds/ in: $text", text.contains("backgrounds/"))
            assertFalse("leaked secret.jpg in: $text", text.contains("secret.jpg"))
            assertFalse("leaked /files/ in: $text", text.contains("/files/"))
        }
    }

    @Test
    fun exception_readLastUsedBackgroundPath_logDoesNotLeakPath() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextData()
        val repository = SettingsRepository(dataStore)
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, throwable ->
            captured += msg
            throwable?.message?.let { captured += it }
            throwable?.let { captured += it.stackTraceToString() }
        }
        try {
            repository.lastUsedBackgroundPath.first()
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then
        assertTrue(captured.isNotEmpty())
        captured.forEach { text ->
            assertFalse("leaked lastUsed path token in: $text", text.contains("backgrounds/"))
        }
    }

    @Test
    fun success_clearLastUsedBackgroundPathCatching_removesKey() = runTest {
        // Given
        val repository = createRepository()
        repository.setLastUsedBackgroundPath("/files/backgrounds/bg.jpg")

        // When
        repository.clearLastUsedBackgroundPathCatching()

        // Then
        assertNull(repository.lastUsedBackgroundPath.first())
    }

    @Test
    fun exception_clearLastUsedBackgroundPathCatching_swallowsIOException() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)
        repository.setLastUsedBackgroundPath("/files/backgrounds/bg.jpg")
        dataStore.failNextUpdateData()
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, throwable ->
            captured += msg
            throwable?.message?.let { captured += it }
            throwable?.let { captured += it.stackTraceToString() }
        }

        // When / Then — must not throw
        try {
            repository.clearLastUsedBackgroundPathCatching()
        } catch (e: Throwable) {
            fail("expected swallow, but threw: ${e.javaClass.simpleName}")
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }
        assertEquals("/files/backgrounds/bg.jpg", repository.lastUsedBackgroundPath.first())
        assertTrue(captured.isNotEmpty())
        captured.forEach { text ->
            assertFalse("leaked backgrounds/ in: $text", text.contains("backgrounds/"))
            assertFalse("leaked /files/ in: $text", text.contains("/files/"))
        }
    }

    // ── recordingBackupFolderUri ─────────────────────────────────────────────

    @Test
    fun success_recordingBackupFolderUriKeyNameIsLiteral() {
        assertEquals(
            "recording_backup_folder_uri",
            SettingsRepository.KEY_RECORDING_BACKUP_FOLDER_URI.name,
        )
    }

    @Test
    fun success_defaultRecordingBackupFolderUriIsNull() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.recordingBackupFolderUri.first()

        // Then: 미설정 시 null 반환
        assertNull(value)
    }

    @Test
    fun success_setRecordingBackupFolderUriPersistsSingleKeyOnReread() = runTest {
        // Given: in-memory store so raw Preferences key can be asserted
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ARecordings"

        // When
        repository.setRecordingBackupFolderUri(uri)

        // Then: Flow value and exactly the intended string key are persisted
        assertEquals(uri, repository.recordingBackupFolderUri.first())
        val preferences = dataStore.data.first()
        assertTrue(preferences.contains(SettingsRepository.KEY_RECORDING_BACKUP_FOLDER_URI))
        assertEquals(uri, preferences[SettingsRepository.KEY_RECORDING_BACKUP_FOLDER_URI])
    }

    @Test
    fun success_setRecordingBackupFolderUriNullRemovesKeyOnReread() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)
        repository.setRecordingBackupFolderUri("content://example/tree/recordings")

        // When
        repository.setRecordingBackupFolderUri(null)

        // Then
        assertNull(repository.recordingBackupFolderUri.first())
        assertFalse(dataStore.data.first().contains(SettingsRepository.KEY_RECORDING_BACKUP_FOLDER_URI))
    }

    @Test
    fun success_setRecordingBackupFolderUriBlankRemovesKeyOnReread() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)
        repository.setRecordingBackupFolderUri("content://example/tree/recordings")

        // When
        repository.setRecordingBackupFolderUri("   ")

        // Then
        assertNull(repository.recordingBackupFolderUri.first())
        assertFalse(dataStore.data.first().contains(SettingsRepository.KEY_RECORDING_BACKUP_FOLDER_URI))
    }

    @Test
    fun success_recordingBackupPromptHandledKeyNameIsLiteral() {
        assertEquals(
            "recording_backup_prompt_handled",
            SettingsRepository.KEY_RECORDING_BACKUP_PROMPT_HANDLED.name,
        )
    }

    @Test
    fun success_recordingBackupPromptHandledDefaultsFalseAndPersists() = runTest {
        // Given: fresh DataStore with no written preferences
        val dataStore = InMemoryPreferencesDataStore()
        val repository = SettingsRepository(dataStore)

        // When / Then
        assertFalse(repository.recordingBackupPromptHandled.first())
        repository.setRecordingBackupPromptHandled(true)
        assertTrue(repository.recordingBackupPromptHandled.first())
        assertEquals(
            true,
            dataStore.data.first()[SettingsRepository.KEY_RECORDING_BACKUP_PROMPT_HANDLED],
        )
    }

    @Test
    fun failure_recordingBackupPromptPreferencesReadReturnsNullForIOException() = runTest {
        // Given: the generic Boolean flow would map this IOException to false
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextData()
        val repository = SettingsRepository(dataStore)

        // When / Then: the prompt-specific seam preserves read failure for retry
        assertNull(repository.readRecordingBackupPromptPreferencesOrNull())
    }

    @Test
    fun exception_recordingBackupPromptPreferencesReadRethrowsNonIOException() = runTest {
        // Given
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextDataWith(IllegalStateException("Injected prompt preferences read failure"))
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.readRecordingBackupPromptPreferencesOrNull()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Injected prompt preferences read failure", e.message)
        }
    }

    @Test
    fun exception_recordingBackupPromptPreferencesReadRethrowsCancellationException() = runTest {
        // Given
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextDataWith(CancellationException("Injected prompt preferences cancellation"))
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.readRecordingBackupPromptPreferencesOrNull()
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Injected prompt preferences cancellation", e.message)
        }
    }

    @Test
    fun success_recordingBackupFolderUriDefaultsNullWhenDataStoreReadFails() = runTest {
        // Given: in-memory store with one-shot data-read IOException
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextData()
        val repository = SettingsRepository(dataStore)

        // When: read-path IOException → catch → emptyPreferences → null (key absent)
        val value = repository.recordingBackupFolderUri.first()

        // Then
        assertNull(value)
    }

    @Test
    fun exception_recordingBackupFolderUriReadRethrowsNonIOException() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextDataWith(IllegalStateException("Injected non-IOException read failure"))
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.recordingBackupFolderUri.first()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Injected non-IOException read failure", e.message)
        }
    }

    @Test
    fun exception_recordingBackupFolderUriReadRethrowsCancellationException() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextDataWith(CancellationException("Injected cancellation"))
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.recordingBackupFolderUri.first()
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Injected cancellation", e.message)
        }
    }

    @Test
    fun exception_setRecordingBackupFolderUriRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (OS-agnostic)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setRecordingBackupFolderUri("content://example/tree/recordings")
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — preference unchanged
        }
        assertNull(repository.recordingBackupFolderUri.first())
    }

    @Test
    fun exception_setRecordingBackupFolderUriRethrowsNonIOException() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateDataWith(IllegalStateException("Injected non-IOException write failure"))
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setRecordingBackupFolderUri("content://example/tree/recordings")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Injected non-IOException write failure", e.message)
        }
        assertNull(repository.recordingBackupFolderUri.first())
    }

    @Test
    fun exception_setRecordingBackupFolderUriRethrowsCancellationException() = runTest {
        // Given
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateDataWith(CancellationException("Injected cancellation"))
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setRecordingBackupFolderUri("content://example/tree/recordings")
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Injected cancellation", e.message)
        }
        assertNull(repository.recordingBackupFolderUri.first())
    }

    @Test
    fun exception_recordingBackupFolderUriReadLogDoesNotLeakUri() = runTest {
        // Given: the IOException message itself contains a sensitive URI
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val backupUri = "content://example/tree/recordings-secret"
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextDataWith(IOException(backupUri))
        val repository = SettingsRepository(dataStore)
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, message, throwable ->
            captured += message
            throwable?.message?.let { captured += it }
            throwable?.let { captured += it.stackTraceToString() }
        }

        // When
        try {
            assertNull(repository.recordingBackupFolderUri.first())
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then
        assertTrue(captured.isNotEmpty())
        captured.forEach { text ->
            assertFalse("leaked recording backup URI in: $text", text.contains(backupUri))
        }
    }

    @Test
    fun exception_setRecordingBackupFolderUriLogDoesNotLeakUri() = runTest {
        // Given: the IOException message itself contains a sensitive URI
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val backupUri = "content://example/tree/recordings-secret"
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateDataWith(IOException(backupUri))
        val repository = SettingsRepository(dataStore)
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, message, throwable ->
            captured += message
            throwable?.message?.let { captured += it }
            throwable?.let { captured += it.stackTraceToString() }
        }

        // When / Then
        try {
            repository.setRecordingBackupFolderUri(backupUri)
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after safe logging
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        assertTrue(captured.isNotEmpty())
        captured.forEach { text ->
            assertFalse("leaked recording backup URI in: $text", text.contains(backupUri))
        }
    }

    // ── driveFolderNameMigrationV1Done ────────────────────────────────────────

    @Test
    fun success_defaultDriveFolderNameMigrationV1DoneIsFalse() = runTest {
        // Given: fresh DataStore with no written preferences
        val repository = createRepository()

        // When
        val value = repository.driveFolderNameMigrationV1Done.first()

        // Then
        assertFalse(value)
    }

    @Test
    fun success_setDriveFolderNameMigrationV1DonePersistsOnReread() = runTest {
        // Given
        val repository = createRepository()

        // When
        repository.setDriveFolderNameMigrationV1Done(true)
        val value = repository.driveFolderNameMigrationV1Done.first()

        // Then
        assertEquals(true, value)
    }

    @Test
    fun exception_setDriveFolderNameMigrationV1DoneRethrowsIOException() = runTest {
        // Given: in-memory store with updateData failure injection (OS-agnostic)
        SettingsRepository.resetRecordingFormatHotForTests()
        SettingsRepository.resetNoiseReductionModeHotForTests()
        SettingsRepository.resetMicrophoneSourceHotForTests()
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.failNextUpdateData()
        val repository = SettingsRepository(dataStore)

        // When / Then
        try {
            repository.setDriveFolderNameMigrationV1Done(true)
            fail("Expected IOException")
        } catch (_: IOException) {
            // rethrown after AppLogger — preference unchanged
        }
        assertFalse(repository.driveFolderNameMigrationV1Done.first())
    }

    @Test
    fun success_languageOptionFromTag_nullDefaultsToEnglish() {
        // Given / When / Then — null → English, no unknown-tag path
        assertEquals(LanguageOption.English, LanguageOption.fromTag(null))
    }

    @Test
    fun success_languageOptionFromTag_enAndKo() {
        // Given / When / Then
        assertEquals(LanguageOption.English, LanguageOption.fromTag("en"))
        assertEquals(LanguageOption.Korean, LanguageOption.fromTag("ko"))
    }

    @Test
    fun failure_languageOptionFromTag_unknownLogsWarnAndDefaultsToEnglish() {
        // Given — capture AppLogger.w via persistSink seam (unit-test only)
        val warnings = mutableListOf<Pair<String, String>>()
        AppLogger.installPersistSink { level, tag, msg, _ ->
            if (level == "W") warnings += tag to msg
        }
        try {
            // When / Then — soft recovery: warn with LanguageOption TAG, then English
            assertEquals(LanguageOption.English, LanguageOption.fromTag("ja"))
            assertTrue(
                warnings.any { (tag, msg) ->
                    tag == "LanguageOption" && msg.contains("ja")
                },
            )
            assertEquals(LanguageOption.English, LanguageOption.fromTag("garbage"))
            assertTrue(
                warnings.any { (tag, msg) ->
                    tag == "LanguageOption" && msg.contains("garbage")
                },
            )
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }
    }

    private fun isWindowsJvm(): Boolean =
        System.getProperty("os.name")
            ?.startsWith("Windows", ignoreCase = true) == true

    /**
     * Asserts raw Preferences contains [SettingsRepository.KEY_YOUTUBE_AUTO_UPLOAD_ENABLED]
     * with [expected] value. Key-name literal is asserted in
     * [success_youtubeAutoUploadKeyNameIsLiteral].
     */
    private suspend fun DataStore<Preferences>.assertContainsYoutubeAutoUploadEnabled(
        expected: Boolean,
    ) {
        val prefs = data.first()
        assertTrue(
            "expected key youtube_auto_upload_enabled to exist",
            prefs.contains(SettingsRepository.KEY_YOUTUBE_AUTO_UPLOAD_ENABLED),
        )
        assertEquals(expected, prefs[SettingsRepository.KEY_YOUTUBE_AUTO_UPLOAD_ENABLED])
    }

    /**
     * Asserts raw Preferences contains [SettingsRepository.KEY_DRIVE_AUTO_UPLOAD_ENABLED]
     * with [expected] value. Key-name literal is asserted in
     * [success_driveAutoUploadKeyNameIsLiteral].
     */
    private suspend fun DataStore<Preferences>.assertContainsDriveAutoUploadEnabled(
        expected: Boolean,
    ) {
        val prefs = data.first()
        assertTrue(
            "expected key drive_auto_upload_enabled to exist",
            prefs.contains(SettingsRepository.KEY_DRIVE_AUTO_UPLOAD_ENABLED),
        )
        assertEquals(expected, prefs[SettingsRepository.KEY_DRIVE_AUTO_UPLOAD_ENABLED])
    }

    /**
     * Minimal in-memory [DataStore] for Windows JVM unit tests where file rename
     * during a second `updateData` is unreliable.
     *
     * Seams: [failNextUpdateData] / [failNextUpdateDataWith] (write),
     * [failNextData] (read IOException), and [failNextDataWith] (read arbitrary
     * Throwable — used to inject [IllegalStateException] for the repo else-branch
     * rethrow) inject a one-shot failure for exception-path coverage on all OSes.
     */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        private var failNextUpdate: Boolean = false
        private var failNextUpdateWith: Throwable? = null
        private var failNextRead: Boolean = false
        private var failNextReadWith: Throwable? = null

        fun failNextUpdateData() {
            failNextUpdate = true
        }

        /** One-shot arbitrary Throwable on the next [updateData] call (write path). */
        fun failNextUpdateDataWith(throwable: Throwable) {
            failNextUpdateWith = throwable
        }

        /** One-shot [IOException] on the next [data] collection (read path). */
        fun failNextData() {
            failNextRead = true
            failNextReadWith = null
        }

        /**
         * One-shot [throwable] on the next [data] collection (read path).
         * Use a non-[IOException] (e.g. [IllegalStateException]) to assert the repo
         * else branch rethrows.
         */
        fun failNextDataWith(throwable: Throwable) {
            failNextRead = true
            failNextReadWith = throwable
        }

        override val data: Flow<Preferences> = state.onStart {
            if (failNextRead) {
                failNextRead = false
                val injected = failNextReadWith
                failNextReadWith = null
                throw injected ?: IOException("Injected data read failure")
            }
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            if (failNextUpdate) {
                failNextUpdate = false
                throw IOException("Injected updateData failure")
            }
            val injected = failNextUpdateWith
            if (injected != null) {
                failNextUpdateWith = null
                throw injected
            }
            val updated = transform(state.value)
            state.update { updated }
            updated
        }
    }
}
