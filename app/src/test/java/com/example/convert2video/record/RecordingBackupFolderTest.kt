package com.example.convert2video.record

import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordingBackupFolderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun success_buildOpenDocumentTreeIntent_hasActionAndAllTreeGrantFlags() {
        // When
        val intent = RecordingBackupFolder.buildOpenDocumentTreeIntent()

        // Then
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertHasFlag(intent, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertHasFlag(intent, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertHasFlag(intent, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertHasFlag(intent, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    @Test
    fun success_handleSelectionCancelled_keepsExistingValue() {
        // When
        val result = RecordingBackupFolder.handleSelection(
            selectedTreeUri = null,
            takePersistableUriPermission = { _, _ -> error("must not request permission") },
            ensureNoMediaMarker = { error("must not create marker") },
        )

        // Then
        assertEquals(
            RecordingBackupFolder.SelectionResult.KeepExisting(
                RecordingBackupFolder.KeepExistingReason.Cancelled,
            ),
            result,
        )
    }

    @Test
    fun success_handleSelectionInvalidTreeUri_keepsExistingValueWithoutRequestingGrant() {
        // Given
        val nonTreeUri = Uri.parse("content://example/document/primary%3ARecordings")
        var requestedGrant = false

        // When
        val result = RecordingBackupFolder.handleSelection(
            selectedTreeUri = nonTreeUri,
            takePersistableUriPermission = { _, _ -> requestedGrant = true },
            ensureNoMediaMarker = { error("must not create marker") },
        )

        // Then
        assertFalse(requestedGrant)
        assertEquals(
            RecordingBackupFolder.SelectionResult.KeepExisting(
                RecordingBackupFolder.KeepExistingReason.InvalidTreeUri,
            ),
            result,
        )
    }

    @Test
    fun exception_handleSelectionPersistFailure_keepsExistingValue() {
        // Given
        val treeUri = Uri.parse("content://example/tree/primary%3ARecordings")

        // When
        val result = RecordingBackupFolder.handleSelection(
            selectedTreeUri = treeUri,
            takePersistableUriPermission = { _, _ -> throw SecurityException("grant rejected") },
            ensureNoMediaMarker = { error("must not create marker") },
        )

        // Then
        assertEquals(
            RecordingBackupFolder.SelectionResult.KeepExisting(
                RecordingBackupFolder.KeepExistingReason.PersistPermissionFailed,
            ),
            result,
        )
    }

    @Test
    fun exception_handleSelectionPermissionCancellation_propagates() {
        // Given
        val cancellation = CancellationException("permission cancelled")

        // When / Then
        try {
            RecordingBackupFolder.handleSelection(
                selectedTreeUri = Uri.parse("content://example/tree/primary%3ARecordings"),
                takePersistableUriPermission = { _, _ -> throw cancellation },
                ensureNoMediaMarker = { error("must not create marker") },
            )
            fail("CancellationException must propagate")
        } catch (exception: CancellationException) {
            assertSame(cancellation, exception)
        }
    }

    @Test
    fun success_handleSelectionPersistsReadWriteGrantAndKeepsSelectionWhenMarkerFails() {
        // Given
        val treeUri = Uri.parse("content://example/tree/primary%3ARecordings")
        var persistedUri: Uri? = null
        var persistedFlags = 0

        // When: marker creation failure is non-blocking after permission persistence
        val result = RecordingBackupFolder.handleSelection(
            selectedTreeUri = treeUri,
            takePersistableUriPermission = { uri, flags ->
                persistedUri = uri
                persistedFlags = flags
            },
            ensureNoMediaMarker = { throw IOException("marker unavailable") },
        )

        // Then
        assertEquals(treeUri, persistedUri)
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            persistedFlags,
        )
        assertEquals(RecordingBackupFolder.SelectionResult.Persisted(treeUri.toString()), result)
    }

    @Test
    fun exception_handleSelectionMarkerCancellation_propagates() {
        // Given
        val cancellation = CancellationException("marker cancelled")

        // When / Then
        try {
            RecordingBackupFolder.handleSelection(
                selectedTreeUri = Uri.parse("content://example/tree/primary%3ARecordings"),
                takePersistableUriPermission = { _, _ -> Unit },
                ensureNoMediaMarker = { throw cancellation },
            )
            fail("CancellationException must propagate")
        } catch (exception: CancellationException) {
            assertSame(cancellation, exception)
        }
    }

    @Test
    fun success_hasPersistedReadWriteGrant_returnsFalseForMalformedAndRevokedValues() {
        // Given
        val grantedUri = Uri.parse("content://example/tree/primary%3ARecordings")
        val fullGrant = RecordingBackupFolder.PersistedGrant(
            uri = grantedUri,
            hasReadPermission = true,
            hasWritePermission = true,
        )
        val readOnlyGrant = RecordingBackupFolder.PersistedGrant(
            uri = grantedUri,
            hasReadPermission = true,
            hasWritePermission = false,
        )

        // Then: malformed / non-tree / missing or incomplete grants are inaccessible
        assertFalse(RecordingBackupFolder.hasPersistedReadWriteGrant(null, listOf(fullGrant)))
        assertFalse(RecordingBackupFolder.hasPersistedReadWriteGrant("not a uri", listOf(fullGrant)))
        assertFalse(
            RecordingBackupFolder.hasPersistedReadWriteGrant(
                "content://example/document/primary%3ARecordings",
                listOf(fullGrant),
            ),
        )
        assertFalse(RecordingBackupFolder.hasPersistedReadWriteGrant(grantedUri.toString(), emptyList()))
        assertFalse(RecordingBackupFolder.hasPersistedReadWriteGrant(grantedUri.toString(), listOf(readOnlyGrant)))
        assertTrue(RecordingBackupFolder.hasPersistedReadWriteGrant(grantedUri.toString(), listOf(fullGrant)))
    }

    @Test
    fun exception_isStoredUriAccessibleGrantAccessCancellation_propagates() {
        // Given
        val cancellation = CancellationException("grant access cancelled")

        // When / Then
        try {
            RecordingBackupFolder.isStoredUriAccessible(
                storedUri = "content://example/tree/primary%3ARecordings",
                persistedGrantsProvider = { throw cancellation },
            )
            fail("CancellationException must propagate")
        } catch (exception: CancellationException) {
            assertSame(cancellation, exception)
        }
    }

    @Test
    fun success_ensureNoMediaMarker_createsThenRecognizesRootMarker() {
        // Given
        val rootDirectory = temporaryFolder.newFolder("recording-backup")
        val root = DocumentFile.fromFile(rootDirectory)

        // When / Then
        assertTrue(RecordingBackupFolder.ensureNoMediaMarker(root))
        assertTrue(File(rootDirectory, ".nomedia").isFile)
        assertTrue(RecordingBackupFolder.ensureNoMediaMarker(root))
    }

    @Test
    fun failure_ensureNoMediaMarkerUnavailableRoot_returnsFalse() {
        // When / Then
        assertFalse(RecordingBackupFolder.ensureNoMediaMarker(null))
    }

    @Test
    fun failure_ensureNoMediaMarkerDirectoryCollision_returnsFalseWithoutCreatingFile() {
        // Given
        val markerDirectory = TestMarkerFile(
            isDirectoryValue = true,
        )
        val root = TestMarkerFile(
            isDirectoryValue = true,
            existingMarker = markerDirectory,
        )

        // When / Then
        assertFalse(RecordingBackupFolder.ensureNoMediaMarkerOperations(root))
        assertEquals(0, root.createFileCallCount)
    }

    @Test
    fun failure_ensureNoMediaMarkerCreateFileNull_returnsFalse() {
        // Given
        val root = TestMarkerFile(
            isDirectoryValue = true,
            createdFile = null,
        )

        // When / Then
        assertFalse(RecordingBackupFolder.ensureNoMediaMarkerOperations(root))
        assertEquals(1, root.createFileCallCount)
    }

    @Test
    fun failure_ensureNoMediaMarkerCreatedDirectory_returnsFalse() {
        // Given
        val createdDirectory = TestMarkerFile(isDirectoryValue = true)
        val root = TestMarkerFile(
            isDirectoryValue = true,
            createdFile = createdDirectory,
        )

        // When / Then: a `.nomedia` directory must not be accepted as the marker file.
        assertFalse(RecordingBackupFolder.ensureNoMediaMarkerOperations(root))
        assertEquals(1, root.createFileCallCount)
    }

    @Test
    fun exception_ensureNoMediaMarkerCancellation_propagates() {
        // Given
        val cancellation = CancellationException("marker check cancelled")
        val root = TestMarkerFile(
            isDirectoryValue = true,
            operationFailure = cancellation,
        )

        // When / Then
        try {
            RecordingBackupFolder.ensureNoMediaMarkerOperations(root)
            fail("CancellationException must propagate")
        } catch (exception: CancellationException) {
            assertSame(cancellation, exception)
        }
    }

    private fun assertHasFlag(intent: Intent, flag: Int) {
        assertTrue("missing intent flag $flag", intent.flags and flag == flag)
    }

    private class TestMarkerFile(
        private val existsValue: Boolean = true,
        private val isDirectoryValue: Boolean = false,
        private val isFileValue: Boolean = false,
        private val existingMarker: RecordingBackupFolder.NoMediaMarkerFile? = null,
        private val createdFile: RecordingBackupFolder.NoMediaMarkerFile? = null,
        private val operationFailure: Throwable? = null,
    ) : RecordingBackupFolder.NoMediaMarkerFile {
        var createFileCallCount = 0
            private set

        override fun createFile(
            mimeType: String,
            displayName: String,
        ): RecordingBackupFolder.NoMediaMarkerFile? {
            createFileCallCount += 1
            throwConfiguredFailure()
            return createdFile
        }

        override fun exists(): Boolean {
            throwConfiguredFailure()
            return existsValue
        }

        override fun isDirectory(): Boolean {
            throwConfiguredFailure()
            return isDirectoryValue
        }

        override fun isFile(): Boolean {
            throwConfiguredFailure()
            return isFileValue
        }

        override fun findFile(displayName: String): RecordingBackupFolder.NoMediaMarkerFile? {
            throwConfiguredFailure()
            return existingMarker
        }

        private fun throwConfiguredFailure() {
            operationFailure?.let { throw it }
        }
    }

}
