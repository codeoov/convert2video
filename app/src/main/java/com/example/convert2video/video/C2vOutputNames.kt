package com.example.convert2video.video

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object C2vOutputNames {
    /** 저장 폴더: Movies/C2V */
    val C2V_FOLDER: String = "${Environment.DIRECTORY_MOVIES}/C2V"

    /** 이전 폴더 (읽기 전용 OR 조회) */
    val LEGACY_FOLDER: String = "${Environment.DIRECTORY_MOVIES}/convert2video"

    private val TIMESTAMP_FORMAT = SimpleDateFormat("(C2V)yyyy-MM-dd_HH-mm", Locale.US)

    private const val MAX_SUFFIX = 99

    /**
     * 충돌 방지 파일명 생성: "(C2V)yyyy-MM-dd_HH-mm.mp4".
     * 같은 분에 이미 동일 이름이 있으면 _2, _3, … _99 접미사 부여.
     * 99개 초과 시 [IllegalStateException] 발생.
     *
     * @param existingNames 대상 폴더에 이미 존재하는 DISPLAY_NAME 집합.
     */
    fun buildDisplayName(existingNames: Set<String> = emptySet()): String {
        val timestamp = TIMESTAMP_FORMAT.format(Date())
        val base = "$timestamp.mp4"
        if (base !in existingNames) return base
        for (n in 2..MAX_SUFFIX) {
            val candidate = "${timestamp}_$n.mp4"
            if (candidate !in existingNames) return candidate
        }
        throw IllegalStateException("같은 분에 저장 가능한 영상 수($MAX_SUFFIX)를 초과했습니다")
    }

    /**
     * Movies/C2V 폴더에 이미 저장된 DISPLAY_NAME 목록을 반환 (API 29+ 전용).
     * API 28 이하에서는 빈 집합 반환.
     */
    suspend fun queryExistingDisplayNames(context: Context): Set<String> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext emptySet()
            val names = mutableSetOf<String>()
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$C2V_FOLDER%"),
                null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    names += cursor.getString(idx)
                }
            }
            names
        }
}
