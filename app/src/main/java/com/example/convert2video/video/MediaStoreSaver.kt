package com.example.convert2video.video

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaStoreSaver {

    /**
     * [sourceFile]을 앱 전용 외부 저장소(Movies/C2V)에 저장하고 FileProvider URI를 반환한다.
     * MediaStore에는 등록하지 않으므로 갤러리에 노출되지 않는다.
     *
     * 원자적 쓰기: 임시 파일(.tmp)에 먼저 복사 후 renameTo로 교체한다.
     * renameTo 실패 시(다른 파일시스템 등) copy+delete 폴백.
     *
     * TOCTOU (Sprint 7-2): [C2vOutputNames.buildDisplayName] → 저장 사이에 다른 Worker가
     * 같은 이름을 만들 수 있다. 저장 시점에 파일이 있으면 `_n`으로 올려 재시도하고,
     * [File.createNewFile]로 빈 파일을 선점한 뒤에만 본문을 쓴다.
     *
     * @param context     Application context
     * @param sourceFile  변환 결과 임시 파일 (cache dir)
     * @param displayName 저장 파일명 (e.g. "(C2V)2026-07-28_10-00.mp4")
     * @return FileProvider content URI
     */
    suspend fun saveVideoToAppStorage(
        context: Context,
        sourceFile: File,
        displayName: String,
    ): Uri = withContext(Dispatchers.IO) {
        val destDir = C2vOutputNames.appStorageDir(context)
        val claimed = claimUniqueDestFile(destDir, displayName)
        val destFile = claimed.destFile
        val tempFile = File(destDir, "${claimed.displayName}.tmp")

        try {
            sourceFile.copyTo(tempFile, overwrite = true)
            if (!tempFile.renameTo(destFile)) {
                // 다른 파일시스템이면 renameTo false — copy+delete 폴백
                // destFile은 createNewFile로 선점한 우리 파일이므로 overwrite=true 안전
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            destFile.delete()
            throw e
        }

        FileProvider.getUriForFile(
            context,
            C2vOutputNames.FILE_PROVIDER_AUTHORITY,
            destFile,
        )
    }

    /**
     * Picks [preferredDisplayName] or the next `_n` collision name, then atomically claims
     * the dest path with [File.createNewFile]. Returns the claimed empty file.
     */
    internal fun claimUniqueDestFile(
        destDir: File,
        preferredDisplayName: String,
    ): ClaimedDest {
        val racedAway = mutableSetOf<String>()
        // Up to MAX_SUFFIX attempts: each failed createNewFile means another writer won the race.
        repeat(C2vOutputNames.MAX_SUFFIX) {
            val candidate = C2vOutputNames.firstAvailableDisplayName(preferredDisplayName) { name ->
                name in racedAway || File(destDir, name).exists()
            }
            val destFile = File(destDir, candidate)
            // Atomic claim closes the window between "name free" check and write.
            if (destFile.createNewFile()) {
                return ClaimedDest(displayName = candidate, destFile = destFile)
            }
            racedAway += candidate
        }
        throw IllegalStateException(
            "같은 분에 저장 가능한 영상 수(${C2vOutputNames.MAX_SUFFIX})를 초과했습니다",
        )
    }

    internal data class ClaimedDest(
        val displayName: String,
        val destFile: File,
    )
}
