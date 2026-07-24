package com.example.convert2video.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaStoreSaver {

    /**
     * Copies [sourceFile] into Movies/C2V so it shows up in the gallery.
     * Runs entirely on [Dispatchers.IO]. On write failure, removes the IS_PENDING orphan entry
     * created by insert() before rethrowing.
     */
    suspend fun saveVideoToMovies(context: Context, sourceFile: File, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, C2vOutputNames.C2V_FOLDER)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("갤러리에 저장할 수 없습니다")

            try {
                resolver.openOutputStream(itemUri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error("갤러리에 저장할 수 없습니다")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(
                        itemUri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                }
            } catch (e: Exception) {
                // IS_PENDING 고아 정리: 쓰기 실패 시 MediaStore 항목 제거 후 재던지기
                runCatching { resolver.delete(itemUri, null, null) }
                throw e
            }

            itemUri
        }
}
