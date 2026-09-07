package com.example.convert2video.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImportedAudioRepositoryAndroidTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: ImportedAudioRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ImportedAudioRepository(
            context = context,
            dao = database.importedAudioDao(),
            trashRepository = TrashRepository(
                context,
                database.trashedItemDao(),
                database.recordingDao(),
                database.importedAudioDao(),
            ),
            durationProvider = { 12_345L },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun success_wavStream_isIndexedWithAuthoritativeDuration() = runTest {
        val record = repository.importFromStream("recorded.wav") { output ->
            output.write(validWav())
        }

        assertTrue(record.id > 0L)
        assertEquals(12_345L, record.durationMs)
        assertEquals(validWav().size.toLong(), record.sizeBytes)
        assertTrue(File(record.filePath).isFile)
    }

    @Test
    fun failure_emptyOrInvalidWav_isRejectedAndFileIsRemoved() = runTest {
        val directory = ImportedAudioRepository.appStorageDir(context)

        assertTrue(runCatching {
            repository.importFromStream("empty.wav") { }
        }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(directory, "empty.wav").exists())

        assertTrue(runCatching {
            repository.importFromStream("invalid.wav") { output -> output.write(byteArrayOf(1, 2, 3)) }
        }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(directory, "invalid.wav").exists())
    }

    @Test
    fun failure_metadataProvider_rollsBackUploadAndPropagatesFailure() = runTest {
        val failingMetadataRepository = ImportedAudioRepository(
            context = context,
            dao = database.importedAudioDao(),
            trashRepository = repositoryTrash(),
            durationProvider = { throw IllegalStateException("metadata failure") },
        )

        assertTrue(runCatching {
            failingMetadataRepository.importFromStream("metadata.wav") { output ->
                output.write(validWav())
            }
        }.isFailure)
        assertFalse(File(ImportedAudioRepository.appStorageDir(context), "metadata.wav").exists())
    }

    @Test
    fun failure_daoInsert_removesClaimedFile() = runTest {
        val throwingDao = object : ImportedAudioDao {
            override fun observeAll(): Flow<List<ImportedAudioRecord>> = flowOf(emptyList())
            override suspend fun insert(record: ImportedAudioRecord): Long =
                throw IllegalStateException("insert failure")
            override suspend fun deleteById(id: Long) = Unit
        }
        val failingRepository = ImportedAudioRepository(
            context = context,
            dao = throwingDao,
            trashRepository = repositoryTrash(),
            durationProvider = { 1L },
        )

        assertTrue(runCatching {
            failingRepository.importFromStream("dao.wav") { output -> output.write(validWav()) }
        }.isFailure)
        assertFalse(File(ImportedAudioRepository.appStorageDir(context), "dao.wav").exists())
    }

    @Test
    fun failure_streamCancellation_removesPartialFileAndRow() = runTest {
        val result = runCatching {
            repository.importFromStream("cancel.wav") {
                throw CancellationException("test cancellation")
            }
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(File(ImportedAudioRepository.appStorageDir(context), "cancel.wav").exists())
        assertTrue(database.importedAudioDao().observeAll().first().isEmpty())
    }

    private fun repositoryTrash(): TrashRepository = TrashRepository(
        context,
        database.trashedItemDao(),
        database.recordingDao(),
        database.importedAudioDao(),
    )

    private fun validWav(): ByteArray = byteArrayOf(
        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        0, 0, 0, 0,
        'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
    )
}
