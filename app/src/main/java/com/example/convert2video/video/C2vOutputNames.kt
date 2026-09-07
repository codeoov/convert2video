package com.example.convert2video.video

import android.content.Context
import android.os.Environment
import com.example.convert2video.data.requireSegmentIndexTotalConsistent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object C2vOutputNames {
    /** FileProvider authority — 단일 소스. Manifest와 반드시 동일해야 한다. */
    const val FILE_PROVIDER_AUTHORITY = "com.convert2video.fileprovider"

    /** 신규 저장 폴더: app external files Movies/C2V (갤러리 비노출) */
    val C2V_FOLDER: String = "${Environment.DIRECTORY_MOVIES}/C2V"

    /** 이전 폴더 — MediaStore 레거시 조회 전용 */
    val LEGACY_FOLDER: String = "${Environment.DIRECTORY_MOVIES}/convert2video"

    private val TIMESTAMP_FORMAT = SimpleDateFormat("'(C2V)'yyyy-MM-dd_HH-mm", Locale.US)

    internal const val MAX_SUFFIX = 99

    /** 새니타이즈 후 stem 최대 길이. NofM(`_1of20`)·충돌 접미사(`_99`)·`.mp4`가 붙을 여유를 남겨둔다. */
    internal const val MAX_ORIGINAL_STEM_LENGTH = 80

    /** Windows/Android 공통 파일시스템 금지 문자 — 원본 파일명을 stem으로 재사용할 때 치환 대상. */
    private val FORBIDDEN_STEM_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /**
     * 신규 영상이 저장되는 앱 전용 외부 저장소 디렉터리.
     * Context.getExternalFilesDir(DIRECTORY_MOVIES)/C2V — 단일 소스.
     * 디렉터리가 없으면 자동 생성한다.
     *
     * @throws IllegalStateException 외부 저장소를 마운트할 수 없는 경우 (에뮬레이터/저장소 제거).
     */
    fun appStorageDir(context: Context): File {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: error("외부 저장소를 사용할 수 없습니다")
        val dir = File(moviesDir, "C2V")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 충돌 방지 파일명 생성.
     * - [originalFileStem]이 있고 새니타이즈 후 빈 문자열이 아니면 그 값을 stem으로 사용:
     *   - 비세그먼트: `{stem}.mp4`
     *   - 세그먼트(NofM): `{stem}_{index}of{total}.mp4`
     * - 없거나 새니타이즈 후 빈 문자열이면 타임스탬프로 폴백:
     *   - 비세그먼트: `(C2V)yyyy-MM-dd_HH-mm.mp4`
     *   - 세그먼트(NofM): `(C2V)yyyy-MM-dd_HH-mm_{index}of{total}.mp4`
     * 같은 stem이 이미 있으면 `_2` … `_99` 접미사 부여.
     * 99개 초과 시 [IllegalStateException] 발생.
     *
     * [segmentIndex]/[segmentTotal] 검증은 [requireSegmentIndexTotalConsistent] SSOT.
     *
     * @param existingNames 대상 폴더에 이미 존재하는 파일명 집합.
     * @param originalFileStem 원본 오디오 파일명(확장자 포함/미포함 무관). [sanitizeOriginalFileStem] 참조.
     * @param nowMillis 타임스탬프 기준 epoch ms (테스트에서 고정 주입 가능, 폴백 시에만 사용).
     */
    fun buildDisplayName(
        existingNames: Set<String> = emptySet(),
        segmentIndex: Int? = null,
        segmentTotal: Int? = null,
        originalFileStem: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        requireSegmentIndexTotalConsistent(segmentIndex, segmentTotal)
        val baseStem = sanitizeOriginalFileStem(originalFileStem)
            ?: TIMESTAMP_FORMAT.format(Date(nowMillis))
        val stem = if (segmentIndex != null && segmentTotal != null) {
            "${baseStem}_${segmentIndex}of$segmentTotal"
        } else {
            baseStem
        }
        return firstAvailableDisplayName("$stem.mp4", existingNames::contains)
    }

    /**
     * 원본 파일명을 저장 가능한 stem으로 새니타이즈한다: 확장자 제거, 파일시스템 금지
     * 문자([FORBIDDEN_STEM_CHARS])·제어문자를 `_`로 치환, 앞뒤 공백 제거,
     * [MAX_ORIGINAL_STEM_LENGTH]로 길이 제한(NofM·충돌 접미사·`.mp4`가 붙을 여유 확보).
     *
     * @return null이면 폴백(타임스탬프) 신호 — 원본이 null/공백이거나, 새니타이즈 후 빈 문자열이거나,
     * 치환된 `_`만 남아 원본 정보가 사실상 없는 경우(예: `"???.wav"`).
     */
    internal fun sanitizeOriginalFileStem(rawFileName: String?): String? {
        if (rawFileName.isNullOrBlank()) return null
        val withoutExtension = rawFileName.substringBeforeLast('.', rawFileName)
        val sanitized = withoutExtension
            .map { c -> if (c in FORBIDDEN_STEM_CHARS || c.isISOControl()) '_' else c }
            .joinToString("")
            .trim()
            .take(MAX_ORIGINAL_STEM_LENGTH)
            .trim()
        if (sanitized.isBlank() || sanitized.all { it == '_' }) return null
        return sanitized
    }

    /**
     * Preferred name이 이미 쓰였으면 `stem_2` … `stem_99`로 올린다.
     * MediaStoreSaver TOCTOU 재시도·[buildDisplayName] 충돌 회피가 공유한다.
     *
     * @param isTaken true면 해당 파일명을 건너뛴다 (in-memory set 또는 File.exists).
     */
    fun firstAvailableDisplayName(
        preferredDisplayName: String,
        isTaken: (String) -> Boolean,
    ): String {
        require(preferredDisplayName.endsWith(".mp4")) {
            "displayName must end with .mp4"
        }
        if (!isTaken(preferredDisplayName)) return preferredDisplayName
        val stem = preferredDisplayName.removeSuffix(".mp4")
        for (n in 2..MAX_SUFFIX) {
            val candidate = "${stem}_$n.mp4"
            if (!isTaken(candidate)) return candidate
        }
        throw IllegalStateException("같은 분에 저장 가능한 영상 수($MAX_SUFFIX)를 초과했습니다")
    }

    /**
     * appStorageDir 안에 이미 존재하는 파일명 집합을 반환 (IO).
     * MediaStore가 아닌 파일시스템 직접 조회이므로 API 레벨 제약 없음.
     */
    suspend fun queryExistingDisplayNames(context: Context): Set<String> =
        withContext(Dispatchers.IO) {
            appStorageDir(context).listFiles()
                ?.map { it.name }
                ?.toSet()
                ?: emptySet()
        }
}
