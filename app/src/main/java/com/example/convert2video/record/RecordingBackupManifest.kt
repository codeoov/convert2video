package com.example.convert2video.record

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal interface RecordingBackupSafFile {
    val exists: Boolean
    val isFile: Boolean
    val isDirectory: Boolean
}

internal interface RecordingBackupSafAdapter {
    val rootExists: Boolean
    val rootIsDirectory: Boolean

    fun findFile(displayName: String): RecordingBackupSafFile?

    fun createFile(mimeType: String, displayName: String): RecordingBackupSafFile?

    fun openInputStream(file: RecordingBackupSafFile): InputStream?

    fun openOutputStream(file: RecordingBackupSafFile): OutputStream?

    fun delete(file: RecordingBackupSafFile): Boolean

    fun rename(file: RecordingBackupSafFile, displayName: String): Boolean
}

private const val MAX_MANIFEST_BYTES = 1_048_576
private const val MAX_ENTRIES = 10_000
private const val PRIMARY_TEMP_FILE_NAME = ".c2v_manifest.json.tmp"
private const val ROLLBACK_TEMP_FILE_NAME = ".c2v_manifest.json.rollback.tmp"
private const val JSON_MIME_TYPE = "application/json"
private val recordingBackupSafMutex = Mutex()

/** Backup payload identity; display names remain manifest metadata and never select this file. */
internal fun recordingBackupFileName(backupId: Long, format: RecordingFormat): String {
    require(backupId > 0L) { "Recording backup ID must be positive" }
    return "c2v_recording_${backupId}.${format.fileExtension}"
}

internal data class RecordingBackupManifest(
    val entries: List<Entry>,
) {
    init {
        validateManifestEntries(entries)
    }

    internal data class Entry(
        val id: Long,
        val kind: Kind,
        val displayName: String,
        val format: RecordingFormat,
        val durationMs: Long,
        val sizeBytes: Long,
        val createdAt: Long,
        val deletedAt: Long?,
    ) {
        init {
            validateManifestEntry(this)
        }

        internal enum class Kind {
            ACTIVE,
            TRASHED,
        }
    }

    internal fun toJson(): String = try {
        val bytes = serializeUtf8Checked(this)
        decodeUtf8(bytes)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: IllegalArgumentException) {
        throw exception
    } catch (exception: JSONException) {
        throw exception
    } catch (exception: Exception) {
        throw JSONException("Recording backup manifest serialization failed", exception)
    }

    internal companion object {
        internal const val FILE_NAME = ".c2v_manifest.json"

        internal fun fromJson(json: String): RecordingBackupManifest = try {
            val inputBytes = encodeUtf8(json)
            if (inputBytes.size > MAX_MANIFEST_BYTES) {
                throw JSONException("Recording backup manifest exceeds byte limit")
            }
            StrictJsonScanner(json).scan()

            val root = JSONObject(JSONTokener(json))
            requireExactKeys(root, setOf("entries"))
            val entriesValue = root.get("entries")
            if (entriesValue !is JSONArray) {
                throw JSONException("entries must be an array")
            }
            if (entriesValue.length() > MAX_ENTRIES) {
                throw JSONException("Too many recording backup entries")
            }

            val entries = buildList(entriesValue.length()) {
                repeat(entriesValue.length()) { index ->
                    val rawEntry = entriesValue.get(index)
                    if (rawEntry !is JSONObject) {
                        throw JSONException("Entry must be an object")
                    }
                    add(parseEntry(rawEntry))
                }
            }
            RecordingBackupManifest(entries)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: JSONException) {
            throw exception
        } catch (exception: Exception) {
            throw JSONException("Recording backup manifest parsing failed", exception)
        }

        internal suspend fun readFromSaf(
            contentResolver: ContentResolver,
            root: DocumentFile,
        ): RecordingBackupManifest? = readFromSaf(
            DocumentFileRecordingBackupSafAdapter(contentResolver, root),
        )

        internal suspend fun readFromSaf(
            adapter: RecordingBackupSafAdapter,
        ): RecordingBackupManifest? = recordingBackupSafMutex.withLock {
            readFromSafLocked(adapter)
        }

        private fun readFromSafLocked(
            adapter: RecordingBackupSafAdapter,
        ): RecordingBackupManifest? = try {
                validateRoot(adapter)
                val target = adapter.findFile(FILE_NAME)
                if (target == null) {
                    null
                } else {
                    validateRegularFile(target, "manifest")
                    val bytes = adapter.openInputStream(target)?.use(::readLimitedBytes)
                        ?: throw IOException("Manifest input stream unavailable")
                    fromJson(decodeUtf8(bytes))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: JSONException) {
                throw IOException("Manifest JSON read failed", exception)
            } catch (exception: IOException) {
                throw exception
            } catch (exception: Exception) {
                throw IOException("Recording backup manifest read failed", exception)
            }

        internal suspend fun replaceInSaf(
            contentResolver: ContentResolver,
            root: DocumentFile,
            manifest: RecordingBackupManifest,
        ): Unit = replaceInSaf(
            DocumentFileRecordingBackupSafAdapter(contentResolver, root),
            manifest,
        )

        internal suspend fun replaceInSaf(
            adapter: RecordingBackupSafAdapter,
            manifest: RecordingBackupManifest,
        ): Unit = recordingBackupSafMutex.withLock {
            replaceInSafLocked(adapter, manifest)
        }

        /**
         * Reads, transforms, and replaces the manifest while holding the shared SAF mutex.
         * A null transform result means no manifest write (including an absent-manifest no-op).
         */
        internal suspend fun mutateInSaf(
            adapter: RecordingBackupSafAdapter,
            transform: suspend (
                adapter: RecordingBackupSafAdapter,
                current: RecordingBackupManifest?,
            ) -> RecordingBackupManifest?,
        ): RecordingBackupManifest? = recordingBackupSafMutex.withLock {
            val current = readFromSafLocked(adapter)
            val updated = transform(adapter, current)
            if (updated != null && updated != current) {
                replaceInSafLocked(adapter, updated)
            }
            updated
        }

        private suspend fun replaceInSafLocked(
            adapter: RecordingBackupSafAdapter,
            manifest: RecordingBackupManifest,
        ) {
            var primary: RecordingBackupSafFile? = null
            var committed = false
            val commitRollbackFailures = mutableListOf<Throwable>()
            try {
                validateRoot(adapter)
                cleanupStaleFile(adapter, PRIMARY_TEMP_FILE_NAME)
                cleanupStaleFile(adapter, ROLLBACK_TEMP_FILE_NAME)

                val bytes = serializeUtf8Checked(manifest)
                primary = adapter.createFile(JSON_MIME_TYPE, PRIMARY_TEMP_FILE_NAME)
                    ?: throw IOException("Manifest temp file could not be created")
                validateRegularFile(primary, "manifest temp")
                writeUtf8Bytes(adapter, primary, bytes)

                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    commitReplacement(adapter, primary, commitRollbackFailures)
                }
                committed = true
                currentCoroutineContext().ensureActive()
            } catch (exception: CancellationException) {
                if (!committed) {
                    commitRollbackFailures.forEach { failure ->
                        if (exception !== failure) exception.addSuppressed(failure)
                    }
                    cleanupSuppressed(adapter, PRIMARY_TEMP_FILE_NAME, exception)
                    cleanupSuppressed(adapter, ROLLBACK_TEMP_FILE_NAME, exception)
                }
                throw exception
            } catch (exception: JSONException) {
                val normalized = IOException("Recording backup manifest write failed", exception)
                cleanupSuppressed(adapter, PRIMARY_TEMP_FILE_NAME, normalized)
                cleanupSuppressed(adapter, ROLLBACK_TEMP_FILE_NAME, normalized)
                throw normalized
            } catch (exception: IOException) {
                commitRollbackFailures.forEach { failure ->
                    if (exception !== failure) exception.addSuppressed(failure)
                }
                cleanupSuppressed(adapter, PRIMARY_TEMP_FILE_NAME, exception)
                cleanupSuppressed(adapter, ROLLBACK_TEMP_FILE_NAME, exception)
                throw exception
            } catch (exception: Exception) {
                val normalized = IOException("Recording backup manifest write failed", exception)
                cleanupSuppressed(adapter, PRIMARY_TEMP_FILE_NAME, normalized)
                cleanupSuppressed(adapter, ROLLBACK_TEMP_FILE_NAME, normalized)
                throw normalized
            }
        }

        private fun parseEntry(entry: JSONObject): Entry {
            val kind = when (val rawKind = requiredString(entry, "kind")) {
                Entry.Kind.ACTIVE.name -> Entry.Kind.ACTIVE
                Entry.Kind.TRASHED.name -> Entry.Kind.TRASHED
                else -> throw JSONException("Unknown recording backup kind")
            }
            val expectedKeys = if (kind == Entry.Kind.ACTIVE) {
                setOf("id", "kind", "displayName", "format", "durationMs", "sizeBytes", "createdAt")
            } else {
                setOf(
                    "id",
                    "kind",
                    "displayName",
                    "format",
                    "durationMs",
                    "sizeBytes",
                    "createdAt",
                    "deletedAt",
                )
            }
            requireExactKeys(entry, expectedKeys)
            val deletedAt = if (kind == Entry.Kind.TRASHED) {
                requiredLong(entry, "deletedAt")
            } else {
                null
            }
            val format = when (requiredString(entry, "format")) {
                RecordingFormat.AAC.name -> RecordingFormat.AAC
                RecordingFormat.WAV.name -> RecordingFormat.WAV
                else -> throw JSONException("Unknown recording backup format")
            }
            return Entry(
                id = requiredLong(entry, "id"),
                kind = kind,
                displayName = requiredString(entry, "displayName").also {
                    if (it.isBlank()) throw JSONException("displayName must not be blank")
                },
                format = format,
                durationMs = requiredLong(entry, "durationMs"),
                sizeBytes = requiredLong(entry, "sizeBytes"),
                createdAt = requiredLong(entry, "createdAt"),
                deletedAt = deletedAt,
            )
        }

        private fun requiredString(objectValue: JSONObject, key: String): String {
            val value = objectValue.get(key)
            if (value !is String) throw JSONException("$key must be a string")
            return value
        }

        private fun requiredLong(objectValue: JSONObject, key: String): Long {
            val value = objectValue.get(key)
            if (value === JSONObject.NULL || value !is Number) {
                throw JSONException("$key must be an integer")
            }
            if (value is Double || value is Float) {
                throw JSONException("$key must be an integer")
            }
            val parsed = value.toString().toLongOrNull()
                ?: throw JSONException("$key is outside Long range")
            if (parsed < 0L) throw JSONException("$key must be non-negative")
            return parsed
        }

        private fun requireExactKeys(objectValue: JSONObject, expected: Set<String>) {
            val actual = objectValue.keys().asSequence().toSet()
            if (actual != expected) throw JSONException("Unexpected manifest fields")
        }

        private fun validateRoot(adapter: RecordingBackupSafAdapter) {
            if (!adapter.rootExists || !adapter.rootIsDirectory) {
                throw IOException("Manifest SAF root unavailable")
            }
        }

        private fun validateRegularFile(file: RecordingBackupSafFile, label: String) {
            if (!file.exists || !file.isFile || file.isDirectory) {
                throw IOException("Invalid manifest $label")
            }
        }

        private fun cleanupStaleFile(adapter: RecordingBackupSafAdapter, name: String) {
            val file = adapter.findFile(name) ?: return
            validateRegularFile(file, "stale file")
            if (!adapter.delete(file)) throw IOException("Manifest stale file cleanup failed")
        }

        private fun writeUtf8Bytes(
            adapter: RecordingBackupSafAdapter,
            file: RecordingBackupSafFile,
            bytes: ByteArray,
        ) {
            adapter.openOutputStream(file)?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("Manifest output stream unavailable")
        }

        private fun commitReplacement(
            adapter: RecordingBackupSafAdapter,
            primary: RecordingBackupSafFile,
            rollbackFailures: MutableList<Throwable>,
        ) {
            var originalTarget: RecordingBackupSafFile? = null
            var originalBytes: ByteArray? = null
            var targetDeletionStarted = false
            try {
                originalTarget = adapter.findFile(FILE_NAME)
                if (originalTarget != null) {
                    validateRegularFile(originalTarget!!, "target")
                    originalBytes = readRawBackup(adapter, originalTarget!!)
                    targetDeletionStarted = true
                    if (!adapter.delete(originalTarget!!)) {
                        throw IOException("Manifest target deletion failed")
                    }
                }
                if (!adapter.rename(primary, FILE_NAME)) {
                    throw IOException("Manifest temp rename failed")
                }
                val targetAfterRename = adapter.findFile(FILE_NAME)
                if (targetAfterRename == null || !targetAfterRename.exists ||
                    !targetAfterRename.isFile || targetAfterRename.isDirectory
                ) {
                    throw IOException("Manifest target verification failed")
                }
            } catch (exception: CancellationException) {
                if (targetDeletionStarted && originalBytes != null) {
                    rollback(adapter, originalBytes, rollbackFailures)
                }
                throw exception
            } catch (exception: IOException) {
                if (targetDeletionStarted && originalBytes != null) {
                    rollback(adapter, originalBytes, rollbackFailures)
                }
                throw exception
            } catch (exception: Exception) {
                val normalized = IOException("Manifest replacement failed", exception)
                if (targetDeletionStarted && originalBytes != null) {
                    rollback(adapter, originalBytes, rollbackFailures)
                }
                throw normalized
            }
        }

        private fun readRawBackup(
            adapter: RecordingBackupSafAdapter,
            target: RecordingBackupSafFile,
        ): ByteArray = adapter.openInputStream(target)?.use(::readLimitedBytes)
            ?: throw IOException("Manifest backup input stream unavailable")

        private fun rollback(
            adapter: RecordingBackupSafAdapter,
            originalBytes: ByteArray,
            failures: MutableList<Throwable>,
        ) {
            try {
                val currentTarget = adapter.findFile(FILE_NAME)
                if (currentTarget != null) {
                    if (!adapter.delete(currentTarget)) {
                        throw IOException("Manifest rollback target deletion failed")
                    }
                }
            } catch (exception: CancellationException) {
                failures += exception
            } catch (exception: Exception) {
                failures += normalizeSafException(exception)
            }

            var rollbackFile: RecordingBackupSafFile? = null
            try {
                rollbackFile = adapter.createFile(JSON_MIME_TYPE, ROLLBACK_TEMP_FILE_NAME)
                    ?: throw IOException("Manifest rollback temp creation failed")
                validateRegularFile(rollbackFile, "rollback temp")
                writeUtf8Bytes(adapter, rollbackFile, originalBytes)
            } catch (exception: CancellationException) {
                failures += exception
                return
            } catch (exception: Exception) {
                failures += normalizeSafException(exception)
                return
            }

            try {
                if (!adapter.rename(rollbackFile!!, FILE_NAME)) {
                    throw IOException("Manifest rollback rename failed")
                }
                val restored = adapter.findFile(FILE_NAME)
                if (restored == null || !restored.exists || !restored.isFile || restored.isDirectory) {
                    throw IOException("Manifest rollback verification failed")
                }
            } catch (exception: CancellationException) {
                failures += exception
            } catch (exception: Exception) {
                failures += normalizeSafException(exception)
            }
        }

        private fun cleanupSuppressed(
            adapter: RecordingBackupSafAdapter,
            name: String,
            original: Throwable,
        ) {
            try {
                val file = adapter.findFile(name) ?: return
                validateRegularFile(file, "cleanup")
                if (!adapter.delete(file)) throw IOException("Manifest cleanup failed")
            } catch (exception: CancellationException) {
                if (original !== exception) original.addSuppressed(exception)
            } catch (exception: Exception) {
                val normalized = normalizeSafException(exception)
                if (original !== normalized) original.addSuppressed(normalized)
            }
        }

        private fun normalizeSafException(exception: Exception): IOException = when (exception) {
            is IOException -> exception
            else -> IOException("Manifest SAF operation failed", exception)
        }

        private fun serializeUtf8Checked(manifest: RecordingBackupManifest): ByteArray {
            validateModel(manifest)
            val entries = JSONArray()
            manifest.entries.forEach { entry ->
                val objectValue = JSONObject()
                    .put("id", entry.id)
                    .put("kind", entry.kind.name)
                    .put("displayName", entry.displayName)
                    .put("format", entry.format.name)
                    .put("durationMs", entry.durationMs)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("createdAt", entry.createdAt)
                if (entry.kind == Entry.Kind.TRASHED) objectValue.put("deletedAt", entry.deletedAt)
                entries.put(objectValue)
            }
            val json = JSONObject().put("entries", entries).toString()
            val bytes = encodeUtf8(json)
            if (bytes.size > MAX_MANIFEST_BYTES) {
                throw JSONException("Recording backup manifest exceeds byte limit")
            }
            decodeUtf8(bytes)
            return bytes
        }

        private fun validateModel(manifest: RecordingBackupManifest) {
            validateManifestEntries(manifest.entries)
        }

        private fun encodeUtf8(value: String): ByteArray = try {
            val encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = encoder.encode(CharBuffer.wrap(value))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (exception: CharacterCodingException) {
            throw JSONException("Invalid UTF-8 manifest text", exception)
        }

        private fun decodeUtf8(bytes: ByteArray): String = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (exception: CharacterCodingException) {
            throw IOException("Invalid UTF-8 manifest bytes", exception)
        }

        private fun readLimitedBytes(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() > MAX_MANIFEST_BYTES - read) {
                    throw IOException("Manifest exceeds byte limit")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        private class StrictJsonScanner(private val input: String) {
            private var index = 0

            fun scan() {
                skipWhitespace()
                scanValue()
                skipWhitespace()
                if (index != input.length) fail("Trailing JSON token")
            }

            private fun scanValue() {
                if (index >= input.length) fail("Unexpected EOF")
                when (input[index]) {
                    '{' -> scanObject()
                    '[' -> scanArray()
                    '"' -> scanString()
                    't' -> scanLiteral("true")
                    'f' -> scanLiteral("false")
                    'n' -> scanLiteral("null")
                    in '0'..'9' -> scanNumber()
                    '-' -> fail("Negative JSON number")
                    else -> fail("Invalid JSON value")
                }
            }

            private fun scanObject() {
                expect('{')
                skipWhitespace()
                val keys = mutableSetOf<String>()
                if (consumeIf('}')) return
                while (true) {
                    if (index >= input.length || input[index] != '"') fail("Invalid object key")
                    val key = scanString()
                    if (!keys.add(key)) fail("Duplicate JSON key")
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    scanValue()
                    skipWhitespace()
                    if (consumeIf('}')) return
                    expect(',')
                    skipWhitespace()
                }
            }

            private fun scanArray() {
                expect('[')
                skipWhitespace()
                if (consumeIf(']')) return
                while (true) {
                    scanValue()
                    skipWhitespace()
                    if (consumeIf(']')) return
                    expect(',')
                    skipWhitespace()
                }
            }

            private fun scanString(): String {
                expect('"')
                val value = StringBuilder()
                while (index < input.length) {
                    when (val character = input[index++]) {
                        '"' -> return value.toString()
                        '\\' -> {
                            if (index >= input.length) fail("Malformed escape")
                            when (val escaped = input[index++]) {
                                '"', '\\', '/' -> value.append(escaped)
                                'b' -> value.append('\b')
                                'f' -> value.append('\u000C')
                                'n' -> value.append('\n')
                                'r' -> value.append('\r')
                                't' -> value.append('\t')
                                'u' -> {
                                    if (index + 4 > input.length) fail("Malformed unicode escape")
                                    val hex = input.substring(index, index + 4)
                                    if (!hex.all { it in "0123456789abcdefABCDEF" }) {
                                        fail("Malformed unicode escape")
                                    }
                                    value.append(hex.toInt(16).toChar())
                                    index += 4
                                }
                                else -> fail("Malformed escape")
                            }
                        }
                        else -> {
                            if (character.code < 0x20) fail("Control character in string")
                            value.append(character)
                        }
                    }
                }
                fail("Unexpected EOF in string")
            }

            private fun scanNumber() {
                if (input[index] == '0') {
                    index++
                    if (index < input.length && input[index] in '0'..'9') {
                        fail("Invalid JSON integer")
                    }
                } else {
                    if (input[index] !in '1'..'9') fail("Invalid JSON integer")
                    while (index < input.length && input[index] in '0'..'9') index++
                }
                if (index < input.length && input[index] in ".eE") {
                    fail("Only JSON integers are allowed")
                }
            }

            private fun scanLiteral(literal: String) {
                if (!input.startsWith(literal, index)) fail("Invalid JSON literal")
                index += literal.length
            }

            private fun expect(expected: Char) {
                if (index >= input.length || input[index++] != expected) fail("Expected $expected")
            }

            private fun consumeIf(value: Char): Boolean = if (index < input.length && input[index] == value) {
                index++
                true
            } else {
                false
            }

            private fun skipWhitespace() {
                while (index < input.length && input[index] in charArrayOf(' ', '\t', '\n', '\r')) {
                    index++
                }
            }

            private fun fail(message: String): Nothing = throw JSONException(message)
        }
    }
}

private fun validateManifestEntries(entries: List<RecordingBackupManifest.Entry>) {
    if (entries.size > MAX_ENTRIES) {
        throw IllegalArgumentException("Too many recording backup entries")
    }
    if (entries.map { it.id }.toSet().size != entries.size) {
        throw IllegalArgumentException("Recording backup entry IDs must be unique")
    }
    entries.forEach(::validateManifestEntry)
}

private fun validateManifestEntry(entry: RecordingBackupManifest.Entry) {
    if (entry.id < 0L || entry.durationMs < 0L || entry.sizeBytes < 0L ||
        entry.createdAt < 0L || (entry.deletedAt != null && entry.deletedAt < 0L)
    ) {
        throw IllegalArgumentException("Manifest numeric fields must be non-negative")
    }
    require(entry.displayName.isNotBlank()) { "displayName must not be blank" }
    when (entry.kind) {
        RecordingBackupManifest.Entry.Kind.ACTIVE -> require(entry.deletedAt == null) {
            "ACTIVE entry must not have deletedAt"
        }
        RecordingBackupManifest.Entry.Kind.TRASHED -> require(entry.deletedAt != null) {
            "TRASHED entry must have deletedAt"
        }
    }
}

internal class DocumentFileRecordingBackupSafAdapter(
    private val contentResolver: ContentResolver,
    private val root: DocumentFile,
) : RecordingBackupSafAdapter {
    override val rootExists: Boolean
        get() = root.exists()

    override val rootIsDirectory: Boolean
        get() = root.isDirectory

    override fun findFile(displayName: String): RecordingBackupSafFile? =
        root.findFile(displayName)?.let(::DocumentFileRecordingBackupSafFile)

    override fun createFile(
        mimeType: String,
        displayName: String,
    ): RecordingBackupSafFile? = root.createFile(mimeType, displayName)?.let(::DocumentFileRecordingBackupSafFile)

    override fun openInputStream(file: RecordingBackupSafFile): InputStream? =
        contentResolver.openInputStream(documentFile(file).uri)

    override fun openOutputStream(file: RecordingBackupSafFile): OutputStream? =
        contentResolver.openOutputStream(documentFile(file).uri, "wt")

    override fun delete(file: RecordingBackupSafFile): Boolean = documentFile(file).delete()

    override fun rename(file: RecordingBackupSafFile, displayName: String): Boolean =
        documentFile(file).renameTo(displayName)

    private fun documentFile(file: RecordingBackupSafFile): DocumentFile =
        (file as? DocumentFileRecordingBackupSafFile)?.documentFile
            ?: throw IllegalArgumentException("Foreign SAF file adapter")
}

private class DocumentFileRecordingBackupSafFile(
    val documentFile: DocumentFile,
) : RecordingBackupSafFile {
    override val exists: Boolean
        get() = documentFile.exists()
    override val isFile: Boolean
        get() = documentFile.isFile
    override val isDirectory: Boolean
        get() = documentFile.isDirectory
}
