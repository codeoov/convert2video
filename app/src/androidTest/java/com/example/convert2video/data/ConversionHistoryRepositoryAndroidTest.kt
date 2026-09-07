package com.example.convert2video.data

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversionHistoryRepositoryAndroidTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ConversionHistoryRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ConversionHistoryRepository(db.conversionRecordDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun success_recordConversion_and_observeAll() = runTest {
        // Given
        val audioUri = Uri.parse("content://media/audio/1")
        val videoUri = Uri.parse("content://media/video/1")

        // When
        val id = repository.recordConversion(audioUri, videoUri, createdAtMillis = 1_000L)

        // Then
        assertTrue(id > 0L)
        val list = repository.observeAll().first()
        assertEquals(1, list.size)
        assertEquals(audioUri.toString(), list.single().audioUri)
        assertEquals(videoUri.toString(), list.single().videoUri)
        assertEquals(1_000L, list.single().createdAt)
    }

    @Test
    fun success_observeAll_ordersByCreatedAtDescending() = runTest {
        // Given
        val olderAudio = Uri.parse("content://media/audio/older")
        val newerAudio = Uri.parse("content://media/audio/newer")
        repository.recordConversion(
            olderAudio,
            Uri.parse("content://media/video/older"),
            createdAtMillis = 1_000L,
        )
        repository.recordConversion(
            newerAudio,
            Uri.parse("content://media/video/newer"),
            createdAtMillis = 2_000L,
        )

        // When
        val list = repository.observeAll().first()

        // Then
        assertEquals(2, list.size)
        assertEquals(newerAudio.toString(), list[0].audioUri)
        assertEquals(2_000L, list[0].createdAt)
        assertEquals(1_000L, list[1].createdAt)
    }

    @Test
    fun success_findByAudioUri_returnsLatestOfTwo() = runTest {
        // Given — same audioUri, two videos; later createdAt wins
        val audioUri = Uri.parse("content://media/audio/find")
        val olderVideo = Uri.parse("content://media/video/older")
        val newerVideo = Uri.parse("content://media/video/newer")
        repository.recordConversion(audioUri, olderVideo, createdAtMillis = 1_000L)
        repository.recordConversion(audioUri, newerVideo, createdAtMillis = 2_000L)

        // When
        val found = repository.findByAudioUri(audioUri)
        val missing = repository.findByAudioUri(Uri.parse("content://media/audio/missing"))

        // Then
        assertNotNull(found)
        assertEquals(newerVideo.toString(), found!!.videoUri)
        assertEquals(2_000L, found.createdAt)
        assertNull(missing)
    }

    @Test
    fun success_deleteByVideoUri_removesRecord() = runTest {
        // Given
        val audioUri = Uri.parse("content://media/audio/del")
        val videoUri = Uri.parse("content://media/video/del")
        repository.recordConversion(audioUri, videoUri, createdAtMillis = 1_000L)
        assertEquals(1, repository.observeAll().first().size)

        // When
        repository.deleteByVideoUri(videoUri)

        // Then
        assertTrue(repository.observeAll().first().isEmpty())
        assertNull(repository.findByAudioUri(audioUri))
    }

    @Test
    fun exception_recordConversion_rejectsBlankAudioUri() = runTest {
        // Given / When
        val error = runCatching {
            repository.recordConversion(
                audioUri = Uri.parse(""),
                videoUri = Uri.parse("content://media/video/1"),
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun exception_recordConversion_rejectsBlankVideoUri() = runTest {
        // Given / When
        val error = runCatching {
            repository.recordConversion(
                audioUri = Uri.parse("content://media/audio/1"),
                videoUri = Uri.parse(""),
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun exception_recordConversion_rejectsNonPositiveCreatedAt() = runTest {
        // Given / When
        val error = runCatching {
            repository.recordConversion(
                audioUri = Uri.parse("content://media/audio/1"),
                videoUri = Uri.parse("content://media/video/1"),
                createdAtMillis = 0L,
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun success_recordConversion_segmentTrioPassThrough() = runTest {
        // Given / When
        repository.recordConversion(
            audioUri = Uri.parse("content://media/audio/seg"),
            videoUri = Uri.parse("content://media/video/seg"),
            createdAtMillis = 1_000L,
            segmentBatchId = "batch-uuid",
            segmentIndex = 2,
            segmentTotal = 3,
        )
        // Then
        val row = repository.observeAll().first().single()
        assertEquals("batch-uuid", row.segmentBatchId)
        assertEquals(2, row.segmentIndex)
        assertEquals(3, row.segmentTotal)
    }

    @Test
    fun exception_recordConversion_rejectsPartialSegmentTrio() = runTest {
        // Given / When
        val error = runCatching {
            repository.recordConversion(
                audioUri = Uri.parse("content://media/audio/1"),
                videoUri = Uri.parse("content://media/video/1"),
                createdAtMillis = 1_000L,
                segmentBatchId = "batch-only",
            )
        }.exceptionOrNull()
        // Then
        assertTrue(error is IllegalArgumentException)
        assertTrue(repository.observeAll().first().isEmpty())
    }
}
