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
class UploadHistoryRepositoryAndroidTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: UploadHistoryRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = UploadHistoryRepository(db.uploadRecordDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun success_recordUpload_and_observeAll() = runTest {
        // Given
        val videoUri = Uri.parse("content://media/video/up1")

        // When
        val id = repository.recordUpload(
            videoUri = videoUri,
            youtubeVideoId = "  yt_abc  ",
            watchUrl = "  https://youtube.com/watch?v=yt_abc  ",
            uploadedAtMillis = 1_000L,
        )

        // Then
        assertTrue(id > 0L)
        val list = repository.observeAll().first()
        assertEquals(1, list.size)
        assertEquals(videoUri.toString(), list.single().videoUri)
        assertEquals("yt_abc", list.single().youtubeVideoId)
        assertEquals("https://youtube.com/watch?v=yt_abc", list.single().watchUrl)
        assertEquals(1_000L, list.single().uploadedAt)
    }

    @Test
    fun success_observeAll_ordersByUploadedAtDescending() = runTest {
        // Given
        val olderVideo = Uri.parse("content://media/video/older")
        val newerVideo = Uri.parse("content://media/video/newer")
        repository.recordUpload(
            videoUri = olderVideo,
            youtubeVideoId = "yt_old",
            watchUrl = "https://youtube.com/watch?v=yt_old",
            uploadedAtMillis = 1_000L,
        )
        repository.recordUpload(
            videoUri = newerVideo,
            youtubeVideoId = "yt_new",
            watchUrl = "https://youtube.com/watch?v=yt_new",
            uploadedAtMillis = 2_000L,
        )

        // When
        val list = repository.observeAll().first()

        // Then
        assertEquals(2, list.size)
        assertEquals(newerVideo.toString(), list[0].videoUri)
        assertEquals(2_000L, list[0].uploadedAt)
        assertEquals(1_000L, list[1].uploadedAt)
    }

    @Test
    fun success_findLatestForVideo_returnsNewestOfTwo() = runTest {
        // Given
        val videoUri = Uri.parse("content://media/video/latest")
        repository.recordUpload(
            videoUri = videoUri,
            youtubeVideoId = "yt_old",
            watchUrl = "https://youtube.com/watch?v=yt_old",
            uploadedAtMillis = 1_000L,
        )
        repository.recordUpload(
            videoUri = videoUri,
            youtubeVideoId = "yt_new",
            watchUrl = "https://youtube.com/watch?v=yt_new",
            uploadedAtMillis = 2_000L,
        )

        // When
        val latest = repository.findLatestForVideo(videoUri)

        // Then
        assertNotNull(latest)
        assertEquals("yt_new", latest!!.youtubeVideoId)
        assertEquals(2_000L, latest.uploadedAt)
        assertEquals(2, repository.observeAll().first().size)
    }

    @Test
    fun success_deleteByVideoUri_removesAllForVideo() = runTest {
        // Given
        val videoUri = Uri.parse("content://media/video/del")
        repository.recordUpload(
            videoUri = videoUri,
            youtubeVideoId = "yt_1",
            watchUrl = "https://youtube.com/watch?v=yt_1",
            uploadedAtMillis = 1_000L,
        )
        repository.recordUpload(
            videoUri = videoUri,
            youtubeVideoId = "yt_2",
            watchUrl = "https://youtube.com/watch?v=yt_2",
            uploadedAtMillis = 2_000L,
        )
        assertEquals(2, repository.observeAll().first().size)

        // When
        repository.deleteByVideoUri(videoUri)

        // Then
        assertTrue(repository.observeAll().first().isEmpty())
        assertNull(repository.findLatestForVideo(videoUri))
    }

    @Test
    fun exception_recordUpload_rejectsBlankYoutubeVideoId() = runTest {
        // Given / When
        val error = runCatching {
            repository.recordUpload(
                videoUri = Uri.parse("content://media/video/1"),
                youtubeVideoId = "   ",
                watchUrl = "https://youtube.com/watch?v=x",
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun exception_recordUpload_rejectsBlankWatchUrl() = runTest {
        // Given / When
        val error = runCatching {
            repository.recordUpload(
                videoUri = Uri.parse("content://media/video/1"),
                youtubeVideoId = "yt_x",
                watchUrl = "   ",
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun exception_recordUpload_rejectsBlankVideoUri() = runTest {
        // Given / When
        val error = runCatching {
            repository.recordUpload(
                videoUri = Uri.parse(""),
                youtubeVideoId = "yt_x",
                watchUrl = "https://youtube.com/watch?v=x",
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun exception_recordUpload_rejectsNonPositiveUploadedAt() = runTest {
        // Given / When
        val error = runCatching {
            repository.recordUpload(
                videoUri = Uri.parse("content://media/video/1"),
                youtubeVideoId = "yt_x",
                watchUrl = "https://youtube.com/watch?v=x",
                uploadedAtMillis = 0L,
            )
        }.exceptionOrNull()

        // Then
        assertTrue(error is IllegalArgumentException)
        assertTrue(repository.observeAll().first().isEmpty())
    }
}
