package com.example.convert2video.record

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingBackupManifestTest {
    @Test
    fun success_toJsonAndFromJson_roundTripsActiveAndTrashedEntries() {
        // Given
        val manifest = manifest()

        // When
        val restored = RecordingBackupManifest.fromJson(manifest.toJson())

        // Then
        assertEquals(manifest, restored)
    }

    @Test
    fun success_recordingBackupFileName_usesIdAndFormatNotDisplayName() {
        // Given / When
        val first = recordingBackupFileName(11L, RecordingFormat.AAC)
        val second = recordingBackupFileName(11L, RecordingFormat.AAC)
        val wav = recordingBackupFileName(11L, RecordingFormat.WAV)

        // Then
        assertEquals("c2v_recording_11.m4a", first)
        assertEquals(first, second)
        assertEquals("c2v_recording_11.wav", wav)
    }

    @Test
    fun success_toJson_activeOmitsDeletedAt() {
        // Given
        val manifest = RecordingBackupManifest(listOf(activeEntry()))

        // When
        val json = manifest.toJson()

        // Then
        assertFalse(json.contains("deletedAt"))
    }

    @Test
    fun success_fromJson_emptyEntriesIsSupported() {
        // Given / When
        val manifest = RecordingBackupManifest.fromJson("{\"entries\":[]}")

        // Then
        assertTrue(manifest.entries.isEmpty())
    }

    @Test
    fun success_replaceAndReadFromSaf_usesWholeManifest() = runTest {
        // Given
        val adapter = FakeAdapter()
        val expected = manifest()

        // When
        RecordingBackupManifest.replaceInSaf(adapter, expected)
        val actual = RecordingBackupManifest.readFromSaf(adapter)

        // Then
        assertEquals(expected, actual)
        assertEquals("application/json", adapter.createdMimeType)
    }

    @Test
    fun success_replaceInSaf_cleansStalePrimaryAndRollback() = runTest {
        // Given
        val adapter = FakeAdapter().apply {
            putRaw(PRIMARY_TEMP_FILE_NAME, "stale primary".toByteArray())
            putRaw(ROLLBACK_TEMP_FILE_NAME, "stale rollback".toByteArray())
        }

        // When
        RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))

        // Then
        assertFalse(adapter.has(PRIMARY_TEMP_FILE_NAME))
        assertFalse(adapter.has(ROLLBACK_TEMP_FILE_NAME))
        assertTrue(adapter.has(FILE_NAME))
    }

    @Test
    fun success_replaceInSaf_serializesConcurrentCallsWithMutex() = runTest {
        // Given
        val adapter = FakeAdapter().apply { operationDelayMillis = 2L }

        // When
        coroutineScope {
            listOf(
                launch(Dispatchers.Default) {
                    RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))
                },
                launch(Dispatchers.Default) {
                    RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
                },
            ).forEach { it.join() }
        }

        // Then
        assertEquals(1, adapter.maxConcurrentOperations)
    }

    @Test
    fun success_mutateInSaf_serializesReadTransformReplaceWithoutLostEntry() = runTest {
        // Given
        val adapter = FakeAdapter().apply { operationDelayMillis = 2L }

        // When
        coroutineScope {
            listOf(activeEntry(), trashedEntry()).map { entry ->
                launch(Dispatchers.Default) {
                    RecordingBackupManifest.mutateInSaf(adapter) { _, current ->
                        (current ?: RecordingBackupManifest(emptyList()))
                            .copy(entries = (current?.entries.orEmpty()) + entry)
                    }
                }
            }.forEach { it.join() }
        }

        // Then
        assertEquals(2, RecordingBackupManifest.readFromSaf(adapter)?.entries?.size)
        assertEquals(1, adapter.maxConcurrentOperations)
    }

    @Test
    fun failure_fromJson_rejectsStrictJsonViolations() {
        // Given
        val invalidJson = listOf(
            "{\"entries\": [{\"id\": \"1\"}]}",
            "{\"entries\": [{\"id\": 1.0}]}",
            "{\"entries\": [{\"id\": -0}]}",
            "{\"entries\": [{\"id\": 1, \"id\": 2}]}",
            "{\"entries\": [], \"version\": 1}",
            "{\"entries\": []} trailing",
            "{\"entries\": [\"bad\"]}",
            "{\"entries\": [ {\"id\": 1, ]}",
            "{\"entries\":[{\"id\":1,\"kind\":\"ACTIVE\",\"displayName\":\"a\",\"format\":\"AAC\",\"durationMs\":0,\"sizeBytes\":0,\"createdAt\":0},{\"id\":1,\"kind\":\"ACTIVE\",\"displayName\":\"b\",\"format\":\"AAC\",\"durationMs\":0,\"sizeBytes\":0,\"createdAt\":0}]}",
        )

        // When / Then
        invalidJson.forEach { json ->
            expectJsonException { RecordingBackupManifest.fromJson(json) }
        }
    }

    @Test
    fun failure_fromJson_rejectsMoreThanTenThousandEntries() {
        // Given
        val entry = "{\"id\":0,\"kind\":\"ACTIVE\",\"displayName\":\"a\",\"format\":\"AAC\",\"durationMs\":0,\"sizeBytes\":0,\"createdAt\":0}"
        val json = buildString {
            append("{\"entries\":[")
            repeat(10_001) { index ->
                if (index > 0) append(',')
                append(entry)
            }
            append("]}")
        }

        // When / Then
        expectJsonException { RecordingBackupManifest.fromJson(json) }
    }

    @Test
    fun failure_toJson_rejectsMoreThanOneMiBOutput() {
        // Given
        val manifest = RecordingBackupManifest(
            listOf(activeEntry(displayName = "a".repeat(1_100_000))),
        )

        // When / Then
        expectJsonException { manifest.toJson() }
    }

    @Test
    fun failure_constructorRejectsInvalidModelInvariants() {
        // Given / When / Then
        assertIllegalArgument { activeEntry(deletedAt = 1L) }
        assertIllegalArgument { activeEntry(displayName = "   ") }
        assertIllegalArgument { activeEntry(id = -1L) }
    }

    @Test
    fun failure_readFromSaf_absentManifestOnlyReturnsNull() = runTest {
        // Given
        val adapter = FakeAdapter()

        // When
        val result = RecordingBackupManifest.readFromSaf(adapter)

        // Then
        assertNull(result)
    }

    @Test
    fun failure_readFromSaf_invalidRootIsIOException() = runTest {
        // Given
        val adapter = FakeAdapter().apply { rootExists = false }

        // When / Then
        expectIOException { RecordingBackupManifest.readFromSaf(adapter) }
    }

    @Test
    fun failure_readFromSaf_malformedSchemaIsIOException() = runTest {
        // Given
        val adapter = FakeAdapter().apply {
            putRaw(FILE_NAME, "{\"entries\":[{\"id\":\"bad\"}]}")
        }

        // When / Then
        expectIOException { RecordingBackupManifest.readFromSaf(adapter) }
    }

    @Test
    fun failure_readFromSaf_malformedUtf8IsIOException() = runTest {
        // Given
        val adapter = FakeAdapter().apply { inputFailure = InputFailure.MALFORMED_UTF8 }
        adapter.putRaw(FILE_NAME, byteArrayOf(0xC3.toByte(), 0x28))

        // When / Then
        expectIOException { RecordingBackupManifest.readFromSaf(adapter) }
    }

    @Test
    fun failure_readFromSaf_inputLimitIsIOException() = runTest {
        // Given
        val adapter = FakeAdapter().apply { inputFailure = InputFailure.OVER_LIMIT }
        adapter.putRaw(FILE_NAME, byteArrayOf(1))

        // When / Then
        expectIOException { RecordingBackupManifest.readFromSaf(adapter) }
    }

    @Test
    fun failure_readFromSaf_streamNullAndRuntimeAreIOException() = runTest {
        // Given / When / Then
        listOf(InputFailure.NULL_STREAM, InputFailure.RUNTIME).forEach { failure ->
            val adapter = FakeAdapter().apply {
                inputFailure = failure
                putRaw(FILE_NAME, "{}".toByteArray())
            }
            expectIOException { RecordingBackupManifest.readFromSaf(adapter) }
        }
    }

    @Test
    fun failure_readFromSaf_inputReadAndCloseAreIOException() = runTest {
        // Given / When / Then
        listOf(InputFailure.READ, InputFailure.CLOSE).forEach { failure ->
            val adapter = FakeAdapter().apply {
                inputFailure = failure
                putRaw(FILE_NAME, "{\"entries\":[]}")
            }
            expectIOException { RecordingBackupManifest.readFromSaf(adapter) }
        }
    }

    @Test
    fun failure_replaceInSaf_oversizedSerializedOutputIsIOException() = runTest {
        // Given
        val manifest = RecordingBackupManifest(listOf(activeEntry(displayName = "a".repeat(1_100_000))))

        // When / Then
        expectIOException { RecordingBackupManifest.replaceInSaf(FakeAdapter(), manifest) }
    }

    @Test
    fun failure_replaceInSaf_createOpenWriteFlushCloseFailuresAreIOException() = runTest {
        // Given / When / Then
        listOf(
            OutputFailure.CREATE_NULL,
            OutputFailure.CREATE_RUNTIME,
            OutputFailure.OPEN_NULL,
            OutputFailure.OPEN_RUNTIME,
            OutputFailure.WRITE,
            OutputFailure.FLUSH,
            OutputFailure.CLOSE,
        ).forEach { failure ->
            val adapter = FakeAdapter().apply { outputFailure = failure }
            expectIOException {
                RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))
            }
        }
    }

    @Test
    fun failure_replaceInSaf_deleteAndRenameFailuresAreIOException() = runTest {
        // Given
        val deleteFailure = FakeAdapter()
        RecordingBackupManifest.replaceInSaf(deleteFailure, RecordingBackupManifest(listOf(activeEntry())))
        deleteFailure.deleteFailure = DeleteFailure.FALSE

        val renameFailure = FakeAdapter().apply {
            primaryRenameFailure = RenameFailure.FALSE
        }

        // When / Then
        expectIOException {
            RecordingBackupManifest.replaceInSaf(deleteFailure, RecordingBackupManifest(listOf(trashedEntry())))
        }
        expectIOException {
            RecordingBackupManifest.replaceInSaf(renameFailure, RecordingBackupManifest(listOf(activeEntry())))
        }
    }

    @Test
    fun failure_replaceInSaf_providerRuntimeIsIOException() = runTest {
        // Given
        val adapter = FakeAdapter()
        RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))
        adapter.deleteFailure = DeleteFailure.RUNTIME

        // When / Then
        expectIOException {
            RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
        }
    }

    @Test
    fun failure_replaceInSaf_renameVerificationFailureRollsBackOriginalBytes() = runTest {
        // Given
        val adapter = FakeAdapter()
        RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))
        val original = adapter.targetBytes()
        adapter.verifyPrimaryTargetMissing = true

        // When / Then
        expectIOException {
            RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
        }
        assertEquals(original.toList(), adapter.targetBytes().toList())
    }

    @Test
    fun failure_replaceInSaf_rollbackDeleteFalseAndRuntimeAreSuppressedInOrder() = runTest {
        // Given
        listOf(DeleteFailure.FALSE, DeleteFailure.RUNTIME).forEach { deleteFailure ->
            val adapter = adapterWithVerificationFailure().apply {
                rollbackDeleteFailure = deleteFailure
                rollbackCreateFailure = RollbackCreateFailure.CANCELLATION
            }

            // When
            val failure = expectIOException {
                RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
            }

            // Then
            assertEquals(2, failure.suppressed.size)
            if (deleteFailure == DeleteFailure.FALSE) {
                assertEquals("Manifest rollback target deletion failed", failure.suppressed[0].message)
            } else {
                assertTrue(failure.suppressed[0] is IOException)
                assertTrue(failure.suppressed[0].cause is IllegalStateException)
            }
            assertTrue(failure.suppressed[1] is CancellationException)
        }
    }

    @Test
    fun failure_replaceInSaf_rollbackWriteFlushCloseAndRenameFailuresAreSuppressed() = runTest {
        // Given / When / Then
        listOf(
            RollbackOutputFailure.WRITE,
            RollbackOutputFailure.FLUSH,
            RollbackOutputFailure.CLOSE,
        ).forEach { outputFailure ->
            val adapter = adapterWithVerificationFailure().apply {
                rollbackOutputFailure = outputFailure
            }
            val failure = expectIOException {
                RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
            }
            assertTrue(failure.suppressed.any { it is IOException && it.message == outputFailure.message })
        }

        listOf(RenameFailure.FALSE, RenameFailure.RUNTIME, RenameFailure.CANCELLATION).forEach { renameFailure ->
            val adapter = adapterWithVerificationFailure().apply {
                rollbackRenameFailure = renameFailure
            }
            val failure = expectIOException {
                RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
            }
            if (renameFailure == RenameFailure.CANCELLATION) {
                assertTrue(failure.suppressed.any { it is CancellationException })
            } else {
                assertTrue(failure.suppressed.any { it is IOException })
            }
        }
    }

    @Test
    fun failure_replaceInSaf_rollbackVerificationFailureIsSuppressed() = runTest {
        // Given
        val adapter = adapterWithVerificationFailure().apply { verifyRollbackTargetInvalid = true }

        // When
        val failure = expectIOException {
            RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
        }

        // Then
        assertTrue(failure.suppressed.any { it is IOException && it.message == "Manifest rollback verification failed" })
    }

    @Test
    fun exception_replaceInSaf_commitCancellationRollsBackAndRethrowsCancellation() = runTest {
        // Given
        val adapter = FakeAdapter()
        RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))
        val original = adapter.targetBytes()
        adapter.primaryRenameFailure = RenameFailure.CANCELLATION

        // When / Then
        val cancellation = expectCancellation {
            RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
        }
        assertTrue(cancellation.suppressed.isEmpty())
        assertEquals(original.toList(), adapter.targetBytes().toList())
    }

    @Test
    fun exception_replaceInSaf_commitCancellationSuppressesRollbackCancellation() = runTest {
        // Given
        val adapter = FakeAdapter()
        RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))
        adapter.primaryRenameFailure = RenameFailure.CANCELLATION
        adapter.rollbackCreateFailure = RollbackCreateFailure.CANCELLATION

        // When
        val cancellation = expectCancellation {
            RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
        }

        // Then
        assertTrue(cancellation.suppressed.single() is CancellationException)
    }

    @Test
    fun exception_replaceInSaf_rollbackCancellationPreservesOriginalIOException() = runTest {
        // Given
        val adapter = FakeAdapter()
        RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))
        adapter.primaryRenameFailure = RenameFailure.FALSE
        adapter.rollbackCreateFailure = RollbackCreateFailure.CANCELLATION

        // When
        val failure = expectIOException {
            RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
        }

        // Then
        assertEquals("Manifest temp rename failed", failure.message)
        assertEquals(1, failure.suppressed.size)
        assertTrue(
            "rollbackCreates=${adapter.rollbackCreateCalls}, suppressed=${failure.suppressed.toList()}",
            failure.suppressed.single() is CancellationException,
        )
    }

    @Test
    fun exception_replaceInSaf_stagingCancellationKeepsTarget() = runTest {
        // Given
        val adapter = FakeAdapter()
        RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))
        val original = adapter.targetBytes()
        adapter.outputFailure = OutputFailure.CANCELLATION

        // When / Then
        expectCancellation {
            RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
        }
        assertEquals(original.toList(), adapter.targetBytes().toList())
    }

    @Test
    fun exception_replaceInSaf_postCommitCancellationKeepsNewTarget() = runTest {
        // Given
        val adapter = FakeAdapter()
        lateinit var job: Job
        var cancellation: CancellationException? = null
        job = launch {
            try {
                RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(trashedEntry())))
            } catch (exception: CancellationException) {
                cancellation = exception
            }
        }
        adapter.onPrimaryRename = { job.cancel() }

        // When
        runCurrent()
        job.join()

        // Then
        assertNotNull(cancellation)
        assertEquals(
            RecordingBackupManifest(listOf(trashedEntry())),
            RecordingBackupManifest.readFromSaf(adapter),
        )
    }

    private fun manifest(): RecordingBackupManifest = RecordingBackupManifest(
        entries = listOf(activeEntry(), trashedEntry()),
    )

    private suspend fun adapterWithVerificationFailure(): FakeAdapter {
        val adapter = FakeAdapter()
        RecordingBackupManifest.replaceInSaf(adapter, RecordingBackupManifest(listOf(activeEntry())))
        adapter.verifyPrimaryTargetInvalid = true
        return adapter
    }

    private fun activeEntry(
        id: Long = 1L,
        displayName: String = "active.m4a",
        deletedAt: Long? = null,
    ) = RecordingBackupManifest.Entry(
        id = id,
        kind = RecordingBackupManifest.Entry.Kind.ACTIVE,
        displayName = displayName,
        format = RecordingFormat.AAC,
        durationMs = 1_000L,
        sizeBytes = 2_000L,
        createdAt = 3_000L,
        deletedAt = deletedAt,
    )

    private fun trashedEntry() = RecordingBackupManifest.Entry(
        id = 2L,
        kind = RecordingBackupManifest.Entry.Kind.TRASHED,
        displayName = "trashed.wav",
        format = RecordingFormat.WAV,
        durationMs = 4_000L,
        sizeBytes = 5_000L,
        createdAt = 6_000L,
        deletedAt = 7_000L,
    )

    private suspend fun expectIOException(block: suspend () -> Unit): IOException {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (exception: IOException) {
            return exception
        }
    }

    private fun expectJsonException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected JSONException")
        } catch (_: JSONException) {
            // Expected.
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private suspend fun expectCancellation(block: suspend () -> Unit): CancellationException {
        try {
            block()
            throw AssertionError("Expected CancellationException")
        } catch (exception: CancellationException) {
            return exception
        }
    }

    private enum class InputFailure {
        NONE,
        NULL_STREAM,
        RUNTIME,
        MALFORMED_UTF8,
        OVER_LIMIT,
        READ,
        CLOSE,
    }

    private enum class OutputFailure {
        NONE,
        CREATE_NULL,
        CREATE_RUNTIME,
        OPEN_NULL,
        OPEN_RUNTIME,
        WRITE,
        FLUSH,
        CLOSE,
        CANCELLATION,
    }

    private enum class DeleteFailure {
        NONE,
        FALSE,
        RUNTIME,
    }

    private enum class RenameFailure {
        NONE,
        FALSE,
        RUNTIME,
        CANCELLATION,
    }

    private enum class RollbackCreateFailure {
        NONE,
        CANCELLATION,
    }

    private enum class RollbackOutputFailure(val message: String) {
        WRITE("rollback write failure"),
        FLUSH("rollback flush failure"),
        CLOSE("rollback close failure"),
    }

    private class FakeAdapter : RecordingBackupSafAdapter {
        private val files = linkedMapOf<String, FakeFile>()
        private val operationLock = Any()
        private var activeOperations = 0

        override var rootExists: Boolean = true
        override var rootIsDirectory: Boolean = true
        var inputFailure = InputFailure.NONE
        var outputFailure = OutputFailure.NONE
        var deleteFailure = DeleteFailure.NONE
        var rollbackDeleteFailure = DeleteFailure.NONE
        var primaryRenameFailure = RenameFailure.NONE
        var rollbackRenameFailure = RenameFailure.NONE
        var rollbackCreateFailure = RollbackCreateFailure.NONE
        var rollbackOutputFailure: RollbackOutputFailure? = null
        var verifyPrimaryTargetMissing = false
        var verifyPrimaryTargetInvalid = false
        var verifyRollbackTargetInvalid = false
        var onPrimaryRename: (() -> Unit)? = null
        var createdMimeType: String? = null
        var rollbackCreateCalls = 0
        var operationDelayMillis: Long = 0L
        var maxConcurrentOperations = 0
        private var targetDeleteCompleted = false

        override fun findFile(displayName: String): RecordingBackupSafFile? = tracked {
            files[displayName]
        }

        override fun createFile(
            mimeType: String,
            displayName: String,
        ): RecordingBackupSafFile? = tracked {
            createdMimeType = mimeType
            if (displayName == ROLLBACK_TEMP_FILE_NAME) {
                rollbackCreateCalls++
                when (rollbackCreateFailure) {
                    RollbackCreateFailure.CANCELLATION -> throw CancellationException("rollback cancellation")
                    RollbackCreateFailure.NONE -> Unit
                }
            }
            when (outputFailure) {
                OutputFailure.CREATE_NULL -> return@tracked null
                OutputFailure.CREATE_RUNTIME -> throw IllegalStateException("create failure")
                else -> Unit
            }
            FakeFile(displayName).also { files[displayName] = it }
        }

        override fun openInputStream(file: RecordingBackupSafFile): InputStream? = tracked {
            when (inputFailure) {
                InputFailure.NULL_STREAM -> null
                InputFailure.RUNTIME -> throw IllegalStateException("input failure")
                InputFailure.MALFORMED_UTF8 -> ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))
                InputFailure.OVER_LIMIT -> ByteArrayInputStream(ByteArray(1_048_577))
                InputFailure.READ -> object : ByteArrayInputStream((file as FakeFile).bytes) {
                    override fun read(source: ByteArray, offset: Int, length: Int): Int {
                        throw IOException("input read failure")
                    }
                }
                InputFailure.CLOSE -> object : ByteArrayInputStream((file as FakeFile).bytes) {
                    override fun close() {
                        throw IOException("input close failure")
                    }
                }
                InputFailure.NONE -> ByteArrayInputStream((file as FakeFile).bytes)
            }
        }

        override fun openOutputStream(file: RecordingBackupSafFile): OutputStream? = tracked {
            val isRollbackOutput = (file as FakeFile).name == ROLLBACK_TEMP_FILE_NAME
            val selectedRollbackFailure = if (isRollbackOutput) rollbackOutputFailure else null
            when (outputFailure) {
                OutputFailure.OPEN_NULL -> return@tracked null
                OutputFailure.OPEN_RUNTIME -> throw IllegalStateException("open failure")
                else -> Unit
            }
            object : ByteArrayOutputStream() {
                override fun write(source: ByteArray, offset: Int, length: Int) {
                    if (outputFailure == OutputFailure.WRITE) throw IOException("write failure")
                    if (outputFailure == OutputFailure.CANCELLATION) {
                        throw CancellationException("staging cancellation")
                    }
                    if (selectedRollbackFailure == RollbackOutputFailure.WRITE) {
                        throw IOException(selectedRollbackFailure.message)
                    }
                    super.write(source, offset, length)
                }

                override fun flush() {
                    if (outputFailure == OutputFailure.FLUSH) throw IOException("flush failure")
                    if (selectedRollbackFailure == RollbackOutputFailure.FLUSH) {
                        throw IOException(selectedRollbackFailure.message)
                    }
                    super.flush()
                }

                override fun close() {
                    if (outputFailure == OutputFailure.CLOSE) throw IOException("close failure")
                    if (selectedRollbackFailure == RollbackOutputFailure.CLOSE) {
                        throw IOException(selectedRollbackFailure.message)
                    }
                    (file as FakeFile).bytes = toByteArray()
                    super.close()
                }
            }
        }

        override fun delete(file: RecordingBackupSafFile): Boolean = tracked {
            val fakeFile = file as FakeFile
            if (fakeFile.name == FILE_NAME && targetDeleteCompleted) {
                when (rollbackDeleteFailure) {
                    DeleteFailure.FALSE -> return@tracked false
                    DeleteFailure.RUNTIME -> throw IllegalStateException("rollback delete failure")
                    DeleteFailure.NONE -> Unit
                }
            }
            when (deleteFailure) {
                DeleteFailure.FALSE -> return@tracked false
                DeleteFailure.RUNTIME -> throw IllegalStateException("delete failure")
                DeleteFailure.NONE -> Unit
            }
            if (fakeFile.name == FILE_NAME) targetDeleteCompleted = true
            files.entries.removeIf { it.value === file }
            true
        }

        override fun rename(file: RecordingBackupSafFile, displayName: String): Boolean = tracked {
            val source = file as FakeFile
            if (source.name == PRIMARY_TEMP_FILE_NAME) {
                when (primaryRenameFailure) {
                    RenameFailure.FALSE -> return@tracked false
                    RenameFailure.RUNTIME -> throw IllegalStateException("rename failure")
                    RenameFailure.CANCELLATION -> throw CancellationException("commit cancellation")
                    RenameFailure.NONE -> Unit
                }
            }
            if (source.name == ROLLBACK_TEMP_FILE_NAME) {
                when (rollbackRenameFailure) {
                    RenameFailure.FALSE -> return@tracked false
                    RenameFailure.RUNTIME -> throw IllegalStateException("rollback rename failure")
                    RenameFailure.CANCELLATION -> throw CancellationException("rollback rename cancellation")
                    RenameFailure.NONE -> Unit
                }
            }
            val wasPrimary = source.name == PRIMARY_TEMP_FILE_NAME
            files.remove(source.name)
            source.name = displayName
            if (!(wasPrimary && verifyPrimaryTargetMissing)) {
                if (wasPrimary && verifyPrimaryTargetInvalid) source.exists = false
                if (!wasPrimary && verifyRollbackTargetInvalid) source.exists = false
                files[displayName] = source
            }
            if (wasPrimary) onPrimaryRename?.invoke()
            true
        }

        fun putRaw(name: String, bytes: ByteArray) {
            files[name] = FakeFile(name, bytes)
        }

        fun putRaw(name: String, text: String) = putRaw(name, text.toByteArray())

        fun has(name: String): Boolean = files.containsKey(name)

        fun targetBytes(): ByteArray = files[FILE_NAME]?.bytes ?: ByteArray(0)

        private fun <T> tracked(block: () -> T): T {
            synchronized(operationLock) {
                activeOperations++
                maxConcurrentOperations = maxOf(maxConcurrentOperations, activeOperations)
            }
            return try {
                if (operationDelayMillis > 0L) Thread.sleep(operationDelayMillis)
                synchronized(files) { block() }
            } finally {
                synchronized(operationLock) { activeOperations-- }
            }
        }
    }

    private class FakeFile(
        var name: String,
        var bytes: ByteArray = ByteArray(0),
        override var exists: Boolean = true,
        override var isFile: Boolean = true,
        override var isDirectory: Boolean = false,
    ) : RecordingBackupSafFile

    private companion object {
        const val FILE_NAME = ".c2v_manifest.json"
        const val PRIMARY_TEMP_FILE_NAME = ".c2v_manifest.json.tmp"
        const val ROLLBACK_TEMP_FILE_NAME = ".c2v_manifest.json.rollback.tmp"
    }
}
