package com.example.convert2video.record

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.convert2video.utils.AppLogger
import java.util.concurrent.CancellationException

/**
 * Storage Access Framework helper for the user-selected recording backup tree.
 *
 * The caller writes a DataStore URI only for [SelectionResult.Persisted]. All
 * [SelectionResult.KeepExisting] outcomes intentionally retain the prior value.
 */
internal object RecordingBackupFolder {
    private const val TAG = "RecordingBackupFolder"
    private const val NO_MEDIA_FILE_NAME = ".nomedia"
    private const val NO_MEDIA_MIME_TYPE = "application/octet-stream"

    internal const val TREE_URI_GRANT_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    private const val PERSISTED_READ_WRITE_GRANT_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    internal sealed interface SelectionResult {
        data class Persisted(val uri: String) : SelectionResult

        data class KeepExisting(val reason: KeepExistingReason) : SelectionResult
    }

    internal enum class KeepExistingReason {
        Cancelled,
        InvalidTreeUri,
        PersistPermissionFailed,
    }

    /** Snapshot keeps persisted-grant matching unit-testable without Android framework mocks. */
    internal data class PersistedGrant(
        val uri: Uri,
        val hasReadPermission: Boolean,
        val hasWritePermission: Boolean,
    )

    /** Builds the system picker request with the complete persistable tree grant set. */
    fun buildOpenDocumentTreeIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(TREE_URI_GRANT_FLAGS)

    /**
     * Persists a selected tree URI before returning it for DataStore storage.
     * Marker creation is best-effort: a marker failure never discards a successful grant.
     */
    fun handleSelection(context: Context, selectedTreeUri: Uri?): SelectionResult =
        handleSelection(
            selectedTreeUri = selectedTreeUri,
            takePersistableUriPermission = { uri, flags ->
                context.contentResolver.takePersistableUriPermission(uri, flags)
            },
            ensureNoMediaMarker = { uri ->
                ensureNoMediaMarker(context, uri)
            },
        )

    /**
     * Test seam for permission and marker operations. A caller must persist only
     * [SelectionResult.Persisted] and retain its old DataStore value otherwise.
     */
    internal fun handleSelection(
        selectedTreeUri: Uri?,
        takePersistableUriPermission: (Uri, Int) -> Unit,
        ensureNoMediaMarker: (Uri) -> Unit,
    ): SelectionResult {
        if (selectedTreeUri == null) {
            return SelectionResult.KeepExisting(KeepExistingReason.Cancelled)
        }
        val treeUri = selectedTreeUri.toValidTreeUriOrNull()
            ?: return SelectionResult.KeepExisting(KeepExistingReason.InvalidTreeUri)

        try {
            takePersistableUriPermission(treeUri, PERSISTED_READ_WRITE_GRANT_FLAGS)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            logWarning("Recording backup permission could not be persisted")
            return SelectionResult.KeepExisting(KeepExistingReason.PersistPermissionFailed)
        }

        try {
            ensureNoMediaMarker(treeUri)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            logWarning("Recording backup marker could not be created")
        }
        return SelectionResult.Persisted(treeUri.toString())
    }

    /**
     * Returns true only when [storedUri] is a tree URI with an active persisted
     * read-and-write grant. Malformed, revoked, and unavailable values are false.
     */
    fun isStoredUriAccessible(context: Context, storedUri: String?): Boolean =
        isStoredUriAccessible(storedUri) {
            context.contentResolver.persistedUriPermissions.map { permission ->
                PersistedGrant(
                    uri = permission.uri,
                    hasReadPermission = permission.isReadPermission,
                    hasWritePermission = permission.isWritePermission,
                )
            }
        }

    /** Test seam for persisted-grant access failures without a process-global logger sink. */
    internal fun isStoredUriAccessible(
        storedUri: String?,
        persistedGrantsProvider: () -> Iterable<PersistedGrant>,
    ): Boolean = try {
        hasPersistedReadWriteGrant(storedUri, persistedGrantsProvider())
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        logWarning("Recording backup permissions could not be read")
        false
    }

    /** Visible for unit tests of malformed and revoked-grant handling. */
    internal fun hasPersistedReadWriteGrant(
        storedUri: String?,
        persistedGrants: Iterable<PersistedGrant>,
    ): Boolean {
        val treeUri = storedUri.toTreeUriOrNull() ?: return false
        return persistedGrants.any { grant ->
            grant.uri == treeUri && grant.hasReadPermission && grant.hasWritePermission
        }
    }

    /** Creates `.nomedia` at the selected tree root, or recognizes an existing marker. */
    private fun ensureNoMediaMarker(context: Context, treeUri: Uri) {
        val root = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            logWarning("Recording backup root could not be opened")
            return
        }
        ensureNoMediaMarker(root)
    }

    /**
     * Narrow marker operation seam. Production adapts [DocumentFile]; unit tests can supply a
     * fake without subclassing DocumentFile, whose constructor is package-private.
     */
    internal interface NoMediaMarkerFile {
        fun exists(): Boolean
        fun isDirectory(): Boolean
        fun isFile(): Boolean
        fun findFile(displayName: String): NoMediaMarkerFile?
        fun createFile(mimeType: String, displayName: String): NoMediaMarkerFile?
    }

    /** Uses [DocumentFile] in production so SAF tree roots and their marker stay consistent. */
    internal fun ensureNoMediaMarker(root: DocumentFile?): Boolean =
        ensureNoMediaMarkerOperations(root?.let(::DocumentFileMarkerFile))

    /** Internal seam for marker-operation safety tests. */
    internal fun ensureNoMediaMarkerOperations(root: NoMediaMarkerFile?): Boolean = try {
        if (root == null || !root.exists() || !root.isDirectory()) {
            logWarning("Recording backup root unavailable")
            false
        } else {
            val existingMarker = root.findFile(NO_MEDIA_FILE_NAME)
            if (existingMarker == null || !existingMarker.exists()) {
                val createdMarker = root.createFile(NO_MEDIA_MIME_TYPE, NO_MEDIA_FILE_NAME)
                if (createdMarker?.exists() == true && createdMarker.isFile()) {
                    true
                } else {
                    logWarning("Recording backup marker could not be created")
                    false
                }
            } else if (existingMarker.isFile()) {
                true
            } else {
                logWarning("Recording backup marker is not a file")
                false
            }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        logWarning("Recording backup marker could not be checked")
        false
    }

    /** Production-only adapter for the small marker-operation surface above. */
    private class DocumentFileMarkerFile(
        private val documentFile: DocumentFile,
    ) : NoMediaMarkerFile {
        override fun exists(): Boolean = documentFile.exists()

        override fun isDirectory(): Boolean = documentFile.isDirectory

        override fun isFile(): Boolean = documentFile.isFile

        override fun findFile(displayName: String): NoMediaMarkerFile? =
            documentFile.findFile(displayName)?.let(::DocumentFileMarkerFile)

        override fun createFile(mimeType: String, displayName: String): NoMediaMarkerFile? =
            documentFile.createFile(mimeType, displayName)?.let(::DocumentFileMarkerFile)
    }

    private fun String?.toTreeUriOrNull(): Uri? {
        if (isNullOrBlank()) return null
        return try {
            Uri.parse(this).toValidTreeUriOrNull()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }

    private fun Uri.toValidTreeUriOrNull(): Uri? = try {
        takeIf { uri ->
            uri.scheme == ContentResolver.SCHEME_CONTENT &&
                !uri.authority.isNullOrBlank() &&
                DocumentsContract.isTreeUri(uri)
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    private fun logWarning(message: String) {
        AppLogger.w(TAG, message)
    }
}
