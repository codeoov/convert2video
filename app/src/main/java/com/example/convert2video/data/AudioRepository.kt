package com.example.convert2video.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioRepository(private val context: Context) {

    /**
     * MediaStore에서 오디오 파일 목록을 최근 추가순(DATE_ADDED DESC)으로 반환한다.
     * 권한 미부여·쿼리 실패 시 예외를 호출자(ViewModel)로 전파한다.
     */
    suspend fun queryAudioFiles(): List<AudioItem> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val results = mutableListOf<AudioItem>()
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                results.add(
                    AudioItem(
                        id = id,
                        title = cursor.getString(titleCol) ?: "알 수 없는 제목",
                        artist = cursor.getString(artistCol)
                            ?.takeIf { it != "<unknown>" && it.isNotBlank() },
                        durationMs = cursor.getLong(durationCol),
                        uri = uri,
                    )
                )
            }
        }
        results
    }
}
