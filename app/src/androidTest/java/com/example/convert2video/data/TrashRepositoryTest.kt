package com.example.convert2video.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrashRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrashRepository
    private lateinit var context: Context
    private lateinit var trashDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TrashRepository(context, db.trashedItemDao(), db.recordingDao(), db.importedAudioDao())
        trashDir = TrashRepository.trashDir(context)
        trashDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        db.close()
        trashDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun success_trashDir_isFilesDirTrash() {
        // Given / When
        val dir = TrashRepository.trashDir(context)

        // Then
        assertEquals(File(context.filesDir, "trash").canonicalPath, dir.canonicalPath)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun success_moveToTrash_transfersThenInserts() = runTest {
        // Given
        val source = writeSource("move_${System.nanoTime()}.m4a")

        // When
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "rec.m4a",
            wasIndexed = true,
            durationMs = 1_500L,
            recordingFormat = "AAC",
        )

        // Then
        assertNotNull(trashed)
        assertTrue(trashed!!.id > 0L)
        assertFalse(source.exists())
        val dest = File(trashed.trashFilePath)
        assertTrue(dest.isFile)
        assertTrue(dest.canonicalPath.startsWith(trashDir.canonicalPath + File.separator))
        val list = repository.observeAll().first()
        assertEquals(1, list.size)
        assertEquals(TrashedItem.RECORDING_AUDIO, list.single().itemType)
        assertEquals("rec.m4a", list.single().displayName)
        assertTrue(list.single().wasIndexed)
        assertEquals(1_500L, list.single().durationMs)
        assertEquals("AAC", list.single().recordingFormat)
    }

    @Test
    fun success_moveToTrash_copyKeepsSource() = runTest {
        // Given
        val source = writeSource("copy_${System.nanoTime()}.wav")
        val sourceBytes = source.readBytes()

        // When
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.IMPORTED_AUDIO,
            displayName = "import.wav",
            wasIndexed = false,
            deleteSource = false,
        )

        // Then
        assertNotNull(trashed)
        assertTrue(source.exists())
        assertTrue(source.readBytes().contentEquals(sourceBytes))
        val dest = File(trashed!!.trashFilePath)
        assertTrue(dest.isFile)
        assertTrue(dest.readBytes().contentEquals(sourceBytes))
        assertTrue(dest.canonicalPath.startsWith(trashDir.canonicalPath + File.separator))
        assertEquals(TrashedItem.IMPORTED_AUDIO, repository.observeAll().first().single().itemType)
        assertFalse(repository.observeAll().first().single().wasIndexed)
    }

    @Test
    fun success_observeAll_ordersByDeletedAtDesc() = runTest {
        // Given
        val older = writeSource("old_${System.nanoTime()}.m4a")
        val newer = writeSource("new_${System.nanoTime()}.m4a")
        repository.moveToTrash(
            sourceFile = older,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "old.m4a",
            wasIndexed = true,
            deletedAt = 1_000L,
        )
        repository.moveToTrash(
            sourceFile = newer,
            itemType = TrashedItem.CONVERTED_VIDEO,
            displayName = "new.mp4",
            wasIndexed = true,
            deletedAt = 2_000L,
        )

        // When
        val list = repository.observeAll().first()

        // Then
        assertEquals(2, list.size)
        assertEquals("new.mp4", list[0].displayName)
        assertEquals(2_000L, list[0].deletedAt)
        assertEquals("old.m4a", list[1].displayName)
        assertEquals(1_000L, list[1].deletedAt)
        assertEquals(TrashedItem.CONVERTED_VIDEO, list[0].itemType)
    }

    @Test
    fun success_observeAll_tieBreaksByIdDesc() = runTest {
        // Given — same deletedAt; later insert has higher id
        val first = writeSource("tie1_${System.nanoTime()}.m4a")
        val second = writeSource("tie2_${System.nanoTime()}.m4a")
        val olderId = repository.moveToTrash(
            sourceFile = first,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "tie-first.m4a",
            wasIndexed = true,
            deletedAt = 5_000L,
        )!!.id
        val newerId = repository.moveToTrash(
            sourceFile = second,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "tie-second.m4a",
            wasIndexed = true,
            deletedAt = 5_000L,
        )!!.id

        // When
        val list = repository.observeAll().first()

        // Then
        assertTrue(newerId > olderId)
        assertEquals(2, list.size)
        assertEquals(newerId, list[0].id)
        assertEquals(olderId, list[1].id)
    }

    @Test
    fun success_permanentlyDelete_removesFileAndRow() = runTest {
        // Given
        val source = writeSource("perm_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "perm.m4a",
            wasIndexed = true,
        )!!
        val dest = File(trashed.trashFilePath)
        assertTrue(dest.isFile)

        // When
        repository.permanentlyDelete(trashed)

        // Then
        assertFalse(dest.exists())
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun success_permanentlyDelete_missingFileStillDeletesRow() = runTest {
        // Given — row pointing at a missing dest under trashDir
        val missing = File(trashDir, "missing_${System.nanoTime()}.m4a")
        val id = db.trashedItemDao().insert(
            TrashedItem(
                itemType = TrashedItem.CONVERTED_VIDEO,
                displayName = "gone.mp4",
                trashFilePath = missing.absolutePath,
                deletedAt = 3_000L,
                wasIndexed = false,
            ),
        )
        assertFalse(missing.exists())

        // When
        repository.permanentlyDelete(
            TrashedItem(
                id = id,
                itemType = TrashedItem.CONVERTED_VIDEO,
                displayName = "gone.mp4",
                trashFilePath = missing.absolutePath,
                deletedAt = 3_000L,
                wasIndexed = false,
            ),
        )

        // Then
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun success_purgeExpired_deletesOlderThanRetention() = runTest {
        // Given — 16 days old vs 1 day old (retention 15)
        val now = 1_700_000_000_000L
        val dayMs = 24L * 60L * 60L * 1000L
        val expiredSource = writeSource("exp_${System.nanoTime()}.m4a")
        val recentSource = writeSource("rec_${System.nanoTime()}.m4a")
        val expired = repository.moveToTrash(
            sourceFile = expiredSource,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "expired.m4a",
            wasIndexed = true,
            deletedAt = now - 16L * dayMs,
        )!!
        val recent = repository.moveToTrash(
            sourceFile = recentSource,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "recent.m4a",
            wasIndexed = true,
            deletedAt = now - 1L * dayMs,
        )!!

        // When
        repository.purgeExpired(
            retentionDays = TrashRepository.DEFAULT_RETENTION_DAYS,
            nowEpochMs = now,
        )

        // Then
        assertFalse(File(expired.trashFilePath).exists())
        assertTrue(File(recent.trashFilePath).exists())
        val remaining = repository.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals("recent.m4a", remaining.single().displayName)
    }

    @Test
    fun failure_moveToTrash_noRowWhenTransferFails() = runTest {
        // Given — missing source
        val missing = File(context.cacheDir, "absent_${System.nanoTime()}.m4a")
        assertFalse(missing.exists())
        val before = trashFileCount()

        // When
        val result = repository.moveToTrash(
            sourceFile = missing,
            itemType = TrashedItem.IMPORTED_AUDIO,
            displayName = "absent.m4a",
            wasIndexed = false,
        )

        // Then
        assertNull(result)
        assertTrue(repository.observeAll().first().isEmpty())
        assertEquals(before, trashFileCount())
    }

    @Test
    fun failure_moveToTrash_insertFailsReturnsNull() = runTest {
        // Given
        val source = writeSource("insfail_${System.nanoTime()}.m4a")
        val failing = TrashRepository(
            context,
            InsertThrowingDao(db.trashedItemDao(), RuntimeException("insert boom")),
            db.recordingDao(),
            db.importedAudioDao(),
        )

        // When
        val result = failing.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "insfail.m4a",
            wasIndexed = true,
        )

        // Then — dest restored to source (move); no row; never both missing
        assertNull(result)
        assertNotLost(source)
        assertTrue(source.exists())
        assertTrue(repository.observeAll().first().isEmpty())
        assertEquals(0, trashFileCount())
    }

    @Test
    fun failure_moveToTrash_insertFailsCopyKeepsSourceDeletesDest() = runTest {
        // Given
        val source = writeSource("insfail_copy_${System.nanoTime()}.m4a")
        val original = source.readBytes()
        val failing = TrashRepository(
            context,
            InsertThrowingDao(db.trashedItemDao(), RuntimeException("insert boom")),
            db.recordingDao(),
            db.importedAudioDao(),
        )

        // When
        val result = failing.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.IMPORTED_AUDIO,
            displayName = "insfail_copy.m4a",
            wasIndexed = false,
            deleteSource = false,
        )

        // Then
        assertNull(result)
        assertNotLost(source)
        assertTrue(source.exists())
        assertTrue(source.readBytes().contentEquals(original))
        assertTrue(repository.observeAll().first().isEmpty())
        assertEquals(0, trashFileCount())
    }

    @Test
    fun failure_moveToTrash_sourceDeleteFailsAfterCopy() = runTest {
        // Given — rename 실패 + source.delete() 실패 → dest 롤백, source 유지
        val source = writeUndeletableSource("srcdel_${System.nanoTime()}.m4a")
        assertTrue(source.isFile)

        // When
        val result = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "srcdel.m4a",
            wasIndexed = true,
            deleteSource = true,
        )

        // Then
        assertNull(result)
        assertNotLost(source)
        assertTrue(source.exists())
        assertTrue(repository.observeAll().first().isEmpty())
        assertEquals(0, trashFileCount())
    }

    @Test
    fun failure_moveToTrash_unknownItemTypeNoTransfer() = runTest {
        // Given
        val source = writeSource("badtype_${System.nanoTime()}.m4a")

        // When
        val result = repository.moveToTrash(
            sourceFile = source,
            itemType = "NOT_A_TYPE",
            displayName = "bad.m4a",
            wasIndexed = true,
        )

        // Then — no dest claim, no row, source untouched
        assertNull(result)
        assertTrue(source.exists())
        assertTrue(repository.observeAll().first().isEmpty())
        assertEquals(0, trashFileCount())
    }

    @Test
    fun failure_permanentlyDelete_fileDeleteFailsLeavesOrphanFile() = runTest {
        // Given — dest is a non-empty directory so File.delete() fails after deleteById
        val source = writeSource("keep_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "keep.m4a",
            wasIndexed = true,
        )!!
        replaceWithNonEmptyDirectory(File(trashed.trashFilePath))

        // When
        repository.permanentlyDelete(trashed)

        // Then — row gone first; undeletable dest remains (orphan file, not ghost row)
        assertTrue(File(trashed.trashFilePath).exists())
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun failure_permanentlyDelete_trashDirPathSkipsDirectoryDeletesRow() = runTest {
        // Given — corrupt path is trashDir itself (not a child)
        val id = db.trashedItemDao().insert(
            TrashedItem(
                itemType = TrashedItem.RECORDING_AUDIO,
                displayName = "trash-dir",
                trashFilePath = trashDir.absolutePath,
                deletedAt = 4_500L,
                wasIndexed = false,
            ),
        )
        assertTrue(trashDir.isDirectory)

        // When
        repository.permanentlyDelete(
            TrashedItem(
                id = id,
                itemType = TrashedItem.RECORDING_AUDIO,
                displayName = "trash-dir",
                trashFilePath = trashDir.absolutePath,
                deletedAt = 4_500L,
                wasIndexed = false,
            ),
        )

        // Then — directory survives; row deleted
        assertTrue(trashDir.isDirectory)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun failure_permanentlyDelete_pathOutsideTrashSkipsFileDeletesRow() = runTest {
        // Given — corrupt path outside trashDir; outside file must survive
        val outside = File(context.cacheDir, "outside_${System.nanoTime()}.m4a")
        outside.writeBytes(byteArrayOf(7, 7, 7))
        val id = db.trashedItemDao().insert(
            TrashedItem(
                itemType = TrashedItem.IMPORTED_AUDIO,
                displayName = "outside.m4a",
                trashFilePath = outside.absolutePath,
                deletedAt = 4_000L,
                wasIndexed = false,
            ),
        )

        // When
        repository.permanentlyDelete(
            TrashedItem(
                id = id,
                itemType = TrashedItem.IMPORTED_AUDIO,
                displayName = "outside.m4a",
                trashFilePath = outside.absolutePath,
                deletedAt = 4_000L,
                wasIndexed = false,
            ),
        )

        // Then — skip File.delete outside trash; still delete row
        assertTrue(outside.exists())
        assertTrue(repository.observeAll().first().isEmpty())
        outside.delete()
    }

    @Test
    fun failure_purgeExpired_continuesAfterPerItemFailure() = runTest {
        // Given — first deleteById throws; file not touched; second succeeds
        val now = 2_000_000_000_000L
        val dayMs = 24L * 60L * 60L * 1000L
        val cutoffAge = now - 16L * dayMs
        val firstSource = writeSource("p1_${System.nanoTime()}.m4a")
        val secondSource = writeSource("p2_${System.nanoTime()}.m4a")
        val first = repository.moveToTrash(
            sourceFile = firstSource,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "p1.m4a",
            wasIndexed = true,
            deletedAt = cutoffAge,
        )!!
        val second = repository.moveToTrash(
            sourceFile = secondSource,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "p2.m4a",
            wasIndexed = true,
            deletedAt = cutoffAge,
        )!!
        val failing = TrashRepository(
            context,
            DeleteThrowingDao(
                real = db.trashedItemDao(),
                throwOnId = first.id,
                error = RuntimeException("delete boom"),
            ),
            db.recordingDao(),
            db.importedAudioDao(),
        )

        // When
        failing.purgeExpired(
            retentionDays = TrashRepository.DEFAULT_RETENTION_DAYS,
            nowEpochMs = now,
        )

        // Then — first file+row unchanged (deleteById failed before file delete); second gone
        assertTrue(File(first.trashFilePath).exists())
        assertFalse(File(second.trashFilePath).exists())
        val remaining = repository.observeAll().first()
        assertEquals(1, remaining.size)
        assertEquals(first.id, remaining.single().id)
    }

    @Test
    fun failure_purgeExpired_retentionDaysLessThanOneNoOp() = runTest {
        // Given
        val now = 2_050_000_000_000L
        val dayMs = 24L * 60L * 60L * 1000L
        val source = writeSource("noop_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "noop.m4a",
            wasIndexed = true,
            deletedAt = now - 16L * dayMs,
        )!!

        // When
        repository.purgeExpired(retentionDays = 0, nowEpochMs = now)
        repository.purgeExpired(retentionDays = -3, nowEpochMs = now)

        // Then
        assertTrue(File(trashed.trashFilePath).exists())
        assertEquals(1, repository.observeAll().first().size)
    }

    @Test
    fun exception_moveToTrash_insertCancellationExceptionRethrown() = runBlocking {
        // Given
        val source = writeSource("ce_ins_${System.nanoTime()}.m4a")
        val failing = TrashRepository(
            context,
            InsertThrowingDao(db.trashedItemDao(), CancellationException("insert cancelled")),
            db.recordingDao(),
            db.importedAudioDao(),
        )

        // When / Then
        try {
            failing.moveToTrash(
                sourceFile = source,
                itemType = TrashedItem.RECORDING_AUDIO,
                displayName = "ce.m4a",
                wasIndexed = true,
            )
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("insert cancelled", e.message)
        }
        assertNotLost(source)
        assertTrue(source.exists())
        assertTrue(repository.observeAll().first().isEmpty())
        assertEquals(0, trashFileCount())
    }

    @Test
    fun exception_moveToTrash_insertCeCopyKeepsSource() = runBlocking {
        // Given
        val source = writeSource("ce_copy_${System.nanoTime()}.m4a")
        val failing = TrashRepository(
            context,
            InsertThrowingDao(db.trashedItemDao(), CancellationException("insert cancelled")),
            db.recordingDao(),
            db.importedAudioDao(),
        )

        // When / Then
        try {
            failing.moveToTrash(
                sourceFile = source,
                itemType = TrashedItem.IMPORTED_AUDIO,
                displayName = "ce_copy.m4a",
                wasIndexed = false,
                deleteSource = false,
            )
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("insert cancelled", e.message)
        }
        assertNotLost(source)
        assertTrue(source.exists())
        assertTrue(repository.observeAll().first().isEmpty())
        assertEquals(0, trashFileCount())
    }

    @Test
    fun exception_purgeExpired_cancellationExceptionRethrown() = runBlocking {
        // Given
        val now = 2_100_000_000_000L
        val dayMs = 24L * 60L * 60L * 1000L
        val source = writeSource("ce_purge_${System.nanoTime()}.m4a")
        val trashed = repository.moveToTrash(
            sourceFile = source,
            itemType = TrashedItem.RECORDING_AUDIO,
            displayName = "ce_purge.m4a",
            wasIndexed = true,
            deletedAt = now - 16L * dayMs,
        )!!
        val failing = TrashRepository(
            context,
            DeleteThrowingDao(
                real = db.trashedItemDao(),
                throwOnId = trashed.id,
                error = CancellationException("purge cancelled"),
            ),
            db.recordingDao(),
            db.importedAudioDao(),
        )

        // When / Then
        try {
            failing.purgeExpired(
                retentionDays = TrashRepository.DEFAULT_RETENTION_DAYS,
                nowEpochMs = now,
            )
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("purge cancelled", e.message)
        }
        assertEquals(1, repository.observeAll().first().size)
        assertTrue(File(trashed.trashFilePath).exists())
    }

    private fun writeSource(name: String): File {
        val file = File(context.cacheDir, name)
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        return file
    }

    /**
     * Production [TrashRepository] calls [File.renameTo]/[File.delete] on the passed
     * source instance (identity-bound). Subclass overrides those methods only.
     */
    private fun writeUndeletableSource(name: String): File {
        val real = writeSource(name)
        return object : File(real.absolutePath) {
            override fun renameTo(dest: File): Boolean = false
            override fun delete(): Boolean = false
        }
    }

    private fun replaceWithNonEmptyDirectory(file: File) {
        assertTrue(file.isFile)
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "child.bin").writeBytes(byteArrayOf(9, 9))
    }

    private fun trashFileCount(): Int =
        trashDir.listFiles()?.count { it.isFile || it.isDirectory } ?: 0

    /** Insert-fail/CE 경로: source와 dest가 동시에 없으면 파일 소실. */
    private fun assertNotLost(source: File) {
        val destAlive = trashDir.listFiles()?.any { it.isFile } == true
        assertTrue("source.exists() || dest.exists() required", source.exists() || destAlive)
    }
}

private class InsertThrowingDao(
    private val real: TrashedItemDao,
    private val error: Throwable,
) : TrashedItemDao {
    override fun observeAll(): Flow<List<TrashedItem>> = real.observeAll()
    override suspend fun insert(item: TrashedItem): Long = throw error
    override suspend fun deleteById(id: Long) = real.deleteById(id)
    override suspend fun listExpired(cutoffEpochMs: Long): List<TrashedItem> =
        real.listExpired(cutoffEpochMs)
}

private class DeleteThrowingDao(
    private val real: TrashedItemDao,
    private val throwOnId: Long,
    private val error: Throwable,
) : TrashedItemDao {
    override fun observeAll(): Flow<List<TrashedItem>> = real.observeAll()
    override suspend fun insert(item: TrashedItem): Long = real.insert(item)
    override suspend fun deleteById(id: Long) {
        if (id == throwOnId) throw error
        real.deleteById(id)
    }
    override suspend fun listExpired(cutoffEpochMs: Long): List<TrashedItem> =
        real.listExpired(cutoffEpochMs)
}
