package com.example.convert2video.data

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.convert2video.R
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class AudioMediaStoreCollection {
    FilesExternal,
    AudioExternal,
}

internal data class AudioMediaStoreQueryPlan(
    val collection: AudioMediaStoreCollection,
    val projection: List<String>,
    val selection: String?,
    val selectionArgs: List<String>?,
    val sortOrder: String,
)

internal fun audioMediaStoreQueryPlan(sdkInt: Int): AudioMediaStoreQueryPlan {
    val isModernMediaStore = sdkInt >= Build.VERSION_CODES.Q
    val collection = if (isModernMediaStore) {
        AudioMediaStoreCollection.FilesExternal
    } else {
        AudioMediaStoreCollection.AudioExternal
    }
    val projection = if (isModernMediaStore) {
        listOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            // Requested for provider metadata completeness; AudioItem has no size field yet.
            MediaStore.MediaColumns.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
    } else {
        listOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            // Requested for provider metadata completeness; AudioItem has no size field yet.
            MediaStore.MediaColumns.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
    }
    val selection = if (isModernMediaStore) {
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND " +
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
    } else null
    val selectionArgs = if (isModernMediaStore) {
        listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
    } else null

    return AudioMediaStoreQueryPlan(
        collection = collection,
        projection = projection,
        selection = selection,
        selectionArgs = selectionArgs,
        sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC",
    )
}

private fun audioMediaStoreCollectionUri(collection: AudioMediaStoreCollection): Uri =
    when (collection) {
        AudioMediaStoreCollection.FilesExternal ->
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        AudioMediaStoreCollection.AudioExternal -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

internal fun audioMediaStoreItemUri(
    collection: AudioMediaStoreCollection,
    id: Long,
): Uri = ContentUris.withAppendedId(audioMediaStoreCollectionUri(collection), id)

class AudioRepository(
    private val context: Context,
    private val trashRepository: TrashRepository = TrashRepository.create(context),
) {

    private val pendingStaging = ConcurrentHashMap<String, File>()

    /**
     * MediaStore에서 오디오 파일 목록을 최근 추가순(DATE_ADDED DESC)으로 반환한다.
     * 권한 미부여·쿼리 실패 시 예외를 호출자(ViewModel)로 전파한다.
     * fileName = DISPLAY_NAME, folderLabel = RELATIVE_PATH 끝 슬래시 제거 (API<Q → null).
     */
    suspend fun queryAudioFiles(): List<AudioItem> = withContext(Dispatchers.IO) {
        val queryPlan = audioMediaStoreQueryPlan(Build.VERSION.SDK_INT)
        val collection = audioMediaStoreCollectionUri(queryPlan.collection)

        val results = mutableListOf<AudioItem>()
        context.contentResolver.query(
            collection,
            queryPlan.projection.toTypedArray(),
            queryPlan.selection,
            queryPlan.selectionArgs?.toTypedArray(),
            queryPlan.sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val relativePathCol = if (MediaStore.MediaColumns.RELATIVE_PATH in queryPlan.projection) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(displayNameCol)
                val relativePath = if (relativePathCol >= 0) {
                    cursor.getString(relativePathCol)
                } else null
                val uri = audioMediaStoreItemUri(queryPlan.collection, id)
                val fileName = displayName ?: ""
                val folderLabel = relativePath?.trimEnd('/')

                results.add(
                    AudioItem(
                        id = id,
                        title = cursor.getString(titleCol)
                            ?: context.getString(R.string.audio_item_unknown_title),
                        artist = cursor.getString(artistCol)
                            ?.takeIf { it != "<unknown>" && it.isNotBlank() },
                        durationMs = cursor.getLong(durationCol),
                        uri = uri,
                        dateAdded = cursor.getLong(dateAddedCol) * 1_000L,
                        fileName = fileName,
                        folderLabel = folderLabel,
                    ),
                )
            }
        }
        results
    }

    /**
     * 오디오 파일 삭제 결과.
     * - [Deleted]: 즉시 삭제 성공
     * - [NeedsConfirmation]: 시스템 확인 UI([intentSender])를 Activity에서 실행해야 함
     * - [Failed]: 삭제 실패
     */
    sealed class MediaDeleteOutcome {
        data object Deleted : MediaDeleteOutcome()
        data class NeedsConfirmation(val intentSender: IntentSender) : MediaDeleteOutcome()
        data object Failed : MediaDeleteOutcome()
    }

    /**
     * MediaStore [item] 삭제 — 삭제 시도 전 URI 내용을 staging에 복사해 휴지통 확정에 사용한다.
     */
    suspend fun deleteAudioItem(item: AudioItem): MediaDeleteOutcome = withContext(Dispatchers.IO) {
        discardStaging(item.uri)
        val staged = stageUriToTempFile(item.uri, item.fileName) ?: return@withContext MediaDeleteOutcome.Failed
        pendingStaging[item.uri.toString()] = staged

        when (val outcome = performMediaStoreDelete(item.uri)) {
            is MediaDeleteOutcome.Deleted -> {
                pendingStaging.remove(item.uri.toString())
                if (finalizeTrashFromStaging(item, staged)) {
                    MediaDeleteOutcome.Deleted
                } else {
                    MediaDeleteOutcome.Failed
                }
            }
            is MediaDeleteOutcome.NeedsConfirmation -> outcome
            is MediaDeleteOutcome.Failed -> {
                discardStaging(item.uri)
                MediaDeleteOutcome.Failed
            }
        }
    }

    /**
     * 시스템 삭제 확인 결과 처리.
     * [confirmed] false → staging 폐기.
     */
    suspend fun confirmMediaDelete(item: AudioItem, confirmed: Boolean): MediaDeleteOutcome =
        withContext(Dispatchers.IO) {
            if (!confirmed) {
                discardStaging(item.uri)
                return@withContext MediaDeleteOutcome.Failed
            }
            val staged = pendingStaging.remove(item.uri.toString())
            if (staged == null || !staged.isFile) {
                AppLogger.e(TAG, "confirmMediaDelete missing staging")
                return@withContext MediaDeleteOutcome.Failed
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return@withContext if (finalizeTrashFromStaging(item, staged)) {
                    MediaDeleteOutcome.Deleted
                } else {
                    MediaDeleteOutcome.Failed
                }
            }
            when (performMediaStoreDelete(item.uri)) {
                is MediaDeleteOutcome.Deleted ->
                    if (finalizeTrashFromStaging(item, staged)) {
                        MediaDeleteOutcome.Deleted
                    } else {
                        MediaDeleteOutcome.Failed
                    }
                else -> {
                    discardStagingFile(staged)
                    MediaDeleteOutcome.Failed
                }
            }
        }

    /** NeedsConfirmation 취소·race 시 staging 해제. */
    fun cancelPendingDelete(uri: Uri) {
        discardStaging(uri)
    }

    /**
     * MediaStore 오디오 URI를 삭제한다. API 레벨별 동작:
     * - API 30+ (R): MediaStore.createDeleteRequest → NeedsConfirmation
     * - API 29 (Q): delete 시도; RecoverableSecurityException → NeedsConfirmation; rows>0 → Deleted
     * - API 26-28: delete 시도; rows>0 → Deleted; 예외 → AppLogger + Failed
     */
    suspend fun deleteAudioFile(uri: Uri): MediaDeleteOutcome = deleteAudioItem(
        AudioItem(
            id = 0L,
            title = uri.lastPathSegment.orEmpty(),
            artist = null,
            durationMs = 0L,
            uri = uri,
            fileName = uri.lastPathSegment.orEmpty(),
        ),
    )

    private fun performMediaStoreDelete(uri: Uri): MediaDeleteOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(
                context.contentResolver,
                listOf(uri),
            )
            return MediaDeleteOutcome.NeedsConfirmation(pendingIntent.intentSender)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return deleteAudioFileQ(uri)
        }
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) MediaDeleteOutcome.Deleted else MediaDeleteOutcome.Failed
        } catch (e: Exception) {
            AppLogger.e(TAG, "오디오 파일 삭제 실패 (API<29)", e)
            MediaDeleteOutcome.Failed
        }
    }

    @Suppress("NewApi")
    private fun deleteAudioFileQ(uri: Uri): MediaDeleteOutcome {
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) MediaDeleteOutcome.Deleted else MediaDeleteOutcome.Failed
        } catch (e: android.app.RecoverableSecurityException) {
            MediaDeleteOutcome.NeedsConfirmation(e.userAction.actionIntent.intentSender)
        } catch (e: Exception) {
            AppLogger.e(TAG, "오디오 파일 삭제 실패 (API29)", e)
            MediaDeleteOutcome.Failed
        }
    }

    private fun stagingDir(): File =
        File(context.cacheDir, "audio_delete_staging").apply { mkdirs() }

    private fun stageUriToTempFile(uri: Uri, displayName: String): File? {
        return try {
            val ext = guessExtension(displayName, uri)
            val dest = File(stagingDir(), "stage_${UUID.randomUUID()}$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                AppLogger.e(TAG, "stageUriToTempFile open failed")
                return null
            }
            if (!dest.isFile || dest.length() <= 0L) {
                dest.delete()
                AppLogger.e(TAG, "stageUriToTempFile empty result")
                return null
            }
            dest
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "stageUriToTempFile failed: ${e.javaClass.simpleName}", e)
            null
        }
    }

    private fun guessExtension(displayName: String, uri: Uri): String {
        val name = displayName.ifBlank { uri.lastPathSegment.orEmpty() }
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot) else ".tmp"
    }

    private suspend fun finalizeTrashFromStaging(item: AudioItem, staged: File): Boolean {
        val displayName = item.fileName.ifBlank { staged.name }
        val trashed = try {
            trashRepository.moveToTrash(
                sourceFile = staged,
                itemType = TrashedItem.IMPORTED_AUDIO,
                displayName = displayName,
                wasIndexed = true,
                originalFilePath = null,
                durationMs = item.durationMs,
                sourceAudioUri = item.uri.toString(),
                deleteSource = true,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "finalizeTrashFromStaging failed: ${e.javaClass.simpleName}", e)
            null
        }
        if (trashed == null) {
            AppLogger.e(TAG, "finalizeTrashFromStaging moveToTrash failed")
            discardStagingFile(staged)
            return false
        }
        return true
    }

    private fun discardStaging(uri: Uri) {
        pendingStaging.remove(uri.toString())?.let { discardStagingFile(it) }
    }

    private fun discardStagingFile(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            AppLogger.e(TAG, "discardStagingFile failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "AudioRepository"
        fun create(context: Context): AudioRepository = AudioRepository(context)
    }
}
