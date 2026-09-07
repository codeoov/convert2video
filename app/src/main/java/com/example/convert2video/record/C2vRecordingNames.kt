package com.example.convert2video.record

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 녹음 파일 저장 경로·네이밍 유틸.
 * 경로(C2V dir)·충돌 `_n` 규칙은 video [com.example.convert2video.video.C2vOutputNames]와 유사하나,
 * displayName 접두사·시간 정밀도는 video와 의도적으로 다름.
 * 타임스탬프는 디바이스 로컬 TZ wall-clock, 초 단위(`yyyy-MM-dd_HH-mm-ss`), [Locale.US] 숫자 고정.
 * 스레드 정책: [DateTimeFormatter]는 불변·스레드 안전이며, [ZoneId.systemDefault]는 호출마다 조회한다.
 */
object C2vRecordingNames {

    /** Pattern only — zone applied per call via [ZoneId.systemDefault]. Thread-safe. Locale.US for digit invariance. */
    private val TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)

    internal const val MAX_SUFFIX = 99

    /**
     * 녹음이 저장되는 앱 전용 외부 저장소 디렉터리.
     * Context.getExternalFilesDir(DIRECTORY_MUSIC)/C2V — 단일 소스.
     *
     * @throws IllegalStateException 외부 저장소를 마운트할 수 없거나 C2V 디렉터리를 만들 수 없는 경우
     */
    fun appStorageDir(context: Context): File {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: error("외부 저장소를 사용할 수 없습니다")
        val dir = File(musicDir, "C2V")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            if (!created && !dir.isDirectory) {
                error("C2V 녹음 디렉터리를 만들 수 없습니다")
            }
        }
        return dir
    }

    /**
     * 충돌 방지 파일명 생성.
     * - 기본: `yyyy-MM-dd_HH-mm-ss.{ext}` (신규 무접두사)
     * - 디스크에 남은 legacy 이름은 목록·재생에 그대로 쓰일 수 있고, 신규 [buildDisplayName]만 무접두사
     * - 같은 stem이 이미 있으면 `_2` … `_99` 접미사
     * - 99개 초과 시 [IllegalStateException]
     * - 시각은 디바이스 로컬 TZ wall-clock
     */
    fun buildDisplayName(
        existingNames: Set<String> = emptySet(),
        format: RecordingFormat,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val stem = Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneId.systemDefault())
            .format(TIMESTAMP_FORMATTER)
        val preferred = "$stem.${format.fileExtension}"
        return firstAvailableDisplayName(preferred, existingNames::contains)
    }

    /**
     * Preferred name이 이미 쓰였으면 `stem_2` … `stem_99`로 올린다.
     *
     * @param isTaken true면 해당 파일명을 건너뛴다
     */
    fun firstAvailableDisplayName(
        preferredDisplayName: String,
        isTaken: (String) -> Boolean,
    ): String {
        val ext = preferredDisplayName.substringAfterLast('.', missingDelimiterValue = "")
        require(ext == RecordingFormat.AAC.fileExtension || ext == RecordingFormat.WAV.fileExtension) {
            "displayName must end with .m4a or .wav"
        }
        val suffix = ".$ext"
        if (!isTaken(preferredDisplayName)) return preferredDisplayName
        val stem = preferredDisplayName.removeSuffix(suffix)
        for (n in 2..MAX_SUFFIX) {
            val candidate = "${stem}_$n$suffix"
            if (!isTaken(candidate)) return candidate
        }
        throw IllegalStateException("같은 초에 저장 가능한 녹음 수($MAX_SUFFIX)를 초과했습니다")
    }

    /** appStorageDir 안에 이미 존재하는 파일명 집합을 반환 (IO). */
    suspend fun queryExistingDisplayNames(context: Context): Set<String> =
        withContext(Dispatchers.IO) {
            appStorageDir(context).listFiles()
                ?.map { it.name }
                ?.toSet()
                ?: emptySet()
        }

    /**
     * Preferred name 또는 다음 `_n` 충돌명을 고른 뒤 [File.createNewFile]로 원자적 선점한다.
     */
    internal fun claimUniqueDestFile(
        destDir: File,
        preferredDisplayName: String,
    ): ClaimedRecordingDest {
        val racedAway = mutableSetOf<String>()
        repeat(MAX_SUFFIX) {
            val candidate = firstAvailableDisplayName(preferredDisplayName) { name ->
                name in racedAway || File(destDir, name).exists()
            }
            val destFile = File(destDir, candidate)
            if (destFile.createNewFile()) {
                return ClaimedRecordingDest(displayName = candidate, destFile = destFile)
            }
            racedAway += candidate
        }
        throw IllegalStateException(
            "같은 초에 저장 가능한 녹음 수($MAX_SUFFIX)를 초과했습니다",
        )
    }

    internal data class ClaimedRecordingDest(
        val displayName: String,
        val destFile: File,
    )
}
