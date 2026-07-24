package com.example.convert2video.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.convert2video.video.C2vOutputNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ConvertedVideo(
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
)

class ConvertedVideoRepository(private val context: Context) {

    /**
     * Movies/C2V(신규) 및 Movies/convert2video(구) 폴더의 영상을 DATE_ADDED DESC 순으로 반환.
     * API 29+: RELATIVE_PATH OR 쿼리.
     * API 26-28: DATA 컬럼 LIKE 쿼리 (deprecated — TODO(2026-07-24): minSdk >= 29 되면 제거).
     */
    suspend fun getConvertedVideos(): List<ConvertedVideo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
        )

        val (selection, selectionArgs) = buildSelectionClause()

        val videos = mutableListOf<ConvertedVideo>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                videos += ConvertedVideo(
                    uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id.toString(),
                    ),
                    displayName = cursor.getString(nameIdx),
                    dateAdded = cursor.getLong(dateIdx),
                )
            }
        }
        videos
    }

    /**
     * @return true if the video was successfully deleted from MediaStore.
     */
    suspend fun deleteVideo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 영상 파일명 변경. ".mp4" 미포함 시 자동 부여.
     * @return 성공 시 true
     */
    suspend fun renameVideo(uri: Uri, newName: String): Boolean = withContext(Dispatchers.IO) {
        val safeName = if (newName.endsWith(".mp4", ignoreCase = true)) newName else "$newName.mp4"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, safeName)
            }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun buildSelectionClause(): Pair<String, Array<String>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sel = "(${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?)"
            val args = arrayOf(
                "${C2vOutputNames.C2V_FOLDER}%",
                "${C2vOutputNames.LEGACY_FOLDER}%",
            )
            sel to args
        } else {
            @Suppress("DEPRECATION")
            // TODO(2026-07-24): DATA 컬럼은 API 29부터 deprecated; minSdk >= 29 시 제거.
            val sel = "(${MediaStore.Video.Media.DATA} LIKE ? OR " +
                "${MediaStore.Video.Media.DATA} LIKE ?)"
            val args = arrayOf("%/C2V/%", "%/convert2video/%")
            sel to args
        }
    }
}
