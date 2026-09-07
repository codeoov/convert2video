package com.example.convert2video.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ErrorLogRepositoryAndroidTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ErrorLogRepository
    private lateinit var secondRepository: ErrorLogRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ErrorLogRepository(db.errorLogDao())
        secondRepository = ErrorLogRepository(db.errorLogDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun success_insert_moreThanRetentionLimit_keepsNewestEntries() = runTest {
        // Given
        repeat(301) { index ->
            repository.insert(entry(createdAt = index.toLong(), message = "message-$index"))
        }

        // When
        val entries = repository.observeAll().first()

        // Then
        assertEquals(300, entries.size)
        assertEquals("message-300", entries.first().message)
        assertEquals("message-1", entries.last().message)
    }

    @Test
    fun success_insert_equalCreatedAt_ordersByDescendingId_andRetainsLargerIds() = runTest {
        // Given
        for (id in 302L downTo 1L) {
            repository.insert(entry(id = id, createdAt = 42L, message = "id-$id"))
        }

        // When
        val entries = repository.observeAll().first()

        // Then
        assertEquals(300, entries.size)
        assertEquals((3L..302L).toList(), entries.map { it.id })
        assertEquals("id-302", entries.first().message)
        assertEquals("id-3", entries.last().message)
    }

    @Test
    fun success_observeAll_ordersCreatedAtDescendingThenIdDescending() = runTest {
        // Given
        repository.insert(entry(createdAt = 10L, message = "old"))
        repository.insert(entry(createdAt = 20L, message = "newer-first"))
        repository.insert(entry(createdAt = 20L, message = "newer-second"))

        // When
        val entries = repository.observeAll().first()

        // Then
        assertEquals(listOf("newer-second", "newer-first", "old"), entries.map { it.message })
    }

    @Test
    fun success_insertAndTrim_neverPublishesMoreThanRetentionLimit() = runTest {
        // Given
        val observedSizes = CopyOnWriteArrayList<Int>()
        val observer = launch(Dispatchers.Default) {
            repository.observeAll().collect { entries -> observedSizes += entries.size }
        }
        withTimeout(5_000L) {
            while (observedSizes.isEmpty()) {
                yield()
            }
        }

        // When
        kotlinx.coroutines.coroutineScope {
            (0 until 301).map { index ->
                async(Dispatchers.Default) {
                    val targetRepository = if (index % 2 == 0) repository else secondRepository
                    targetRepository.insert(
                        entry(createdAt = index.toLong(), message = "message-$index"),
                    )
                }
            }.awaitAll()
        }
        observer.cancelAndJoin()

        // Then
        assertTrue(observedSizes.isNotEmpty())
        assertTrue(observedSizes.all { it <= 300 })
        assertEquals(300, repository.observeAll().first().size)
    }

    @Test
    fun success_concurrentInserts_areSerializedAndRetainedByCreationOrder() = runTest {
        // Given
        val totalEntries = 325

        // When
        kotlinx.coroutines.coroutineScope {
            (0 until totalEntries).map { index ->
                async(Dispatchers.Default) {
                    val targetRepository = if (index % 2 == 0) repository else secondRepository
                    targetRepository.insert(
                        entry(createdAt = index.toLong(), message = "message-$index"),
                    )
                }
            }.awaitAll()
        }

        // Then
        val entries = repository.observeAll().first()
        assertEquals(300, entries.size)
        assertEquals(
            (25 until totalEntries).toSet(),
            entries.map { it.createdAt }.toSet(),
        )
        assertTrue(entries.zipWithNext().all { (current, next) ->
            current.createdAt > next.createdAt ||
                (current.createdAt == next.createdAt && current.id > next.id)
        })
    }

    @Test
    fun success_retentionConstant_isExactlyThreeHundred() {
        // Given / When / Then
        assertEquals(300, ERROR_LOG_RETENTION_LIMIT)
    }

    @Test
    fun exception_duplicateId_rollsBackFailedInsertTransaction() = runTest {
        // Given — explicit IDs make the failure deterministic and fill the retained set.
        for (id in 1L..300L) {
            repository.insert(entry(id = id, createdAt = id, message = "original-$id"))
        }
        val before = repository.observeAll().first()

        // When — the duplicate primary key fails inside insertAndTrim's transaction.
        val error = runCatching {
            secondRepository.insert(entry(id = 300L, createdAt = 301L, message = "duplicate"))
        }.exceptionOrNull()

        // Then — the failed transaction did not replace the existing row.
        assertTrue(error != null)
        assertEquals(before, repository.observeAll().first())
        assertEquals(300, repository.observeAll().first().size)
    }

    @Test
    fun exception_trimFailure_rollsBackInsertedRow() = runTest {
        // Given — an in-memory-only trigger aborts the trim DELETE after insertion.
        for (id in 1L..300L) {
            repository.insert(entry(id = id, createdAt = id, message = "original-$id"))
        }
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_error_log_trim
            BEFORE DELETE ON error_log_entries
            WHEN OLD.id = (
                SELECT id FROM error_log_entries
                ORDER BY createdAt DESC, id DESC
                LIMIT 1 OFFSET 300
            )
            BEGIN
                SELECT RAISE(ABORT, 'forced trim failure');
            END
            """.trimIndent(),
        )
        val before = repository.observeAll().first()

        // When — insert succeeds inside the transaction, then trim is aborted.
        val error = runCatching {
            repository.insert(entry(createdAt = 301L, message = "must-rollback"))
        }.exceptionOrNull()

        // Then — the inserted row and the failed trim are both rolled back.
        assertTrue(error != null)
        assertEquals(before, repository.observeAll().first())
        assertTrue(repository.observeAll().first().none { it.message == "must-rollback" })
    }

    @Test
    fun exception_insertCancellation_propagatesAndReleasesSharedMutex() = runTest {
        // Given — the fake DAO suspends during trim after the insert step.
        val trimStarted = CompletableDeferred<Unit>()
        val blockingRepository = ErrorLogRepository(
            BlockingErrorLogDao(trimStarted),
        )
        val blockedInsert = async {
            blockingRepository.insert(entry(createdAt = 1L, message = "cancelled"))
        }
        trimStarted.await()

        // When — cancellation occurs while the shared repository lock is held.
        blockedInsert.cancel()
        val cancellation = runCatching { blockedInsert.await() }.exceptionOrNull()

        // Then — CancellationException is not swallowed and another repository can acquire the lock.
        assertTrue(cancellation is CancellationException)
        withTimeout(1_000L) {
            ErrorLogRepository(
                ProbeErrorLogDao(CompletableDeferred(), CompletableDeferred()),
            ).insert(
                entry(createdAt = 2L, message = "after-cancel"),
            )
        }
    }

    @Test
    fun success_deleteOperations_waitForSharedInsertMutex() = runTest {
        // Given — one repository holds the process-wide lock during its transaction helper.
        val trimStarted = CompletableDeferred<Unit>()
        val deleteAllStarted = CompletableDeferred<Unit>()
        val deleteByIdStarted = CompletableDeferred<Unit>()
        val blockingRepository = ErrorLogRepository(BlockingErrorLogDao(trimStarted))
        val deletingRepository = ErrorLogRepository(
            ProbeErrorLogDao(deleteAllStarted, deleteByIdStarted),
        )

        // When — deleteAll starts while insert is still inside the shared lock.
        val blockedInsert = async {
            blockingRepository.insert(entry(createdAt = 1L, message = "blocked"))
        }
        trimStarted.await()
        val deleteAll = async { deletingRepository.deleteAll() }
        yield()

        // Then — deleteAll cannot overtake the in-flight insert.
        assertTrue(!deleteAllStarted.isCompleted)
        blockedInsert.cancelAndJoin()
        deleteAll.await()
        assertTrue(deleteAllStarted.isCompleted)

        // When — the same ordering rule is checked for deleteById.
        val trimStartedAgain = CompletableDeferred<Unit>()
        val blockingRepositoryAgain = ErrorLogRepository(BlockingErrorLogDao(trimStartedAgain))
        val blockedInsertAgain = async {
            blockingRepositoryAgain.insert(entry(createdAt = 2L, message = "blocked-again"))
        }
        trimStartedAgain.await()
        val deleteById = async { deletingRepository.deleteById(1L) }
        yield()

        // Then
        assertTrue(!deleteByIdStarted.isCompleted)
        blockedInsertAgain.cancelAndJoin()
        deleteById.await()
        assertTrue(deleteByIdStarted.isCompleted)
    }

    private class BlockingErrorLogDao(
        private val trimStarted: CompletableDeferred<Unit>,
    ) : ErrorLogDao {
        private val entries = MutableStateFlow<List<ErrorLogEntry>>(emptyList())

        override suspend fun insert(entry: ErrorLogEntry) {
            entries.value += entry
        }

        override fun observeAll(): Flow<List<ErrorLogEntry>> = entries

        override suspend fun deleteOutsideRetention(retentionLimit: Int) {
            trimStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun deleteAll() {
            entries.value = emptyList()
        }

        override suspend fun deleteById(id: Long) {
            entries.value = entries.value.filterNot { it.id == id }
        }
    }

    private class ProbeErrorLogDao(
        private val deleteAllStarted: CompletableDeferred<Unit>,
        private val deleteByIdStarted: CompletableDeferred<Unit>,
    ) : ErrorLogDao {
        override suspend fun insert(entry: ErrorLogEntry) = Unit

        override fun observeAll(): Flow<List<ErrorLogEntry>> = MutableStateFlow(emptyList())

        override suspend fun deleteOutsideRetention(retentionLimit: Int) = Unit

        override suspend fun deleteAll() {
            deleteAllStarted.complete(Unit)
        }

        override suspend fun deleteById(id: Long) {
            deleteByIdStarted.complete(Unit)
        }
    }

    private fun entry(
        id: Long = 0L,
        createdAt: Long,
        message: String,
    ): ErrorLogEntry = ErrorLogEntry(
        id = id,
        level = "E",
        tag = "Test",
        message = message,
        createdAt = createdAt,
    )
}
