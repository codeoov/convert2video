package com.example.convert2video.youtube

import com.example.convert2video.R

private val CODE_SUFFIX_REGEX = Regex("""\(코드 (\d+)\)$""")
private const val AUTH_FAILED_PREFIX = "Google 인증에 실패했습니다"

/**
 * Maps [YouTubeApiClient]/YouTubeAuthGateway's internal Korean-literal
 * [YouTubeApiResult.Failure.message] / [AuthorizationOutcome.Failed.message] text to a
 * localized [R.string] resource id, plus a format arg when the literal carries a numeric
 * HTTP/status code suffix (`"...(코드 N)"`). The internal literals stay Korean — they're
 * matched by [com.example.convert2video.youtube.YouTubeFailureReasonTest] — so this mapping
 * is the only place that text becomes user-facing.
 *
 * Unrecognized or blank input falls back to [R.string.youtube_auth_incomplete].
 */
internal fun youTubeFailureMessageToStringRes(raw: String?): Pair<Int, Int?> {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return R.string.youtube_auth_incomplete to null

    CODE_SUFFIX_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { code ->
        val resId = if (trimmed.startsWith(AUTH_FAILED_PREFIX)) {
            R.string.error_google_auth_failed_with_code
        } else {
            R.string.error_upload_failed_with_code
        }
        return resId to code
    }

    val resId = when (trimmed) {
        "채널 정보를 가져올 수 없습니다" -> R.string.youtube_error_channel_info_unavailable
        "네트워크 오류로 채널 정보를 가져올 수 없습니다" -> R.string.youtube_error_channel_info_network
        "업로드 세션을 시작할 수 없습니다" -> R.string.youtube_error_upload_session_failed
        "네트워크 오류로 업로드를 시작할 수 없습니다" -> R.string.youtube_error_upload_start_network
        "업로드 결과를 확인할 수 없습니다" -> R.string.youtube_error_upload_result_unknown
        "네트워크 오류로 업로드에 실패했습니다" -> R.string.youtube_error_upload_network
        "인증이 만료되었습니다. 다시 로그인해 주세요" -> R.string.error_auth_expired
        "권한이 없거나 일일 업로드 한도를 초과했습니다" -> R.string.youtube_error_permission_or_limit
        "YouTube 서버 오류입니다. 잠시 후 다시 시도해 주세요" -> R.string.youtube_error_server
        "YouTube API 일일 할당량을 초과했습니다. 내일 다시 시도해 주세요" -> R.string.youtube_error_quota_exceeded
        "일일 업로드 횟수 한도를 초과했습니다. 내일 다시 시도해 주세요" -> R.string.youtube_error_daily_limit_exceeded
        "업로드 용량 한도를 초과했습니다" -> R.string.youtube_error_upload_limit_exceeded
        "Google 인증을 진행할 수 없습니다" -> R.string.error_google_auth_unavailable
        "Google 인증에 실패했습니다" -> R.string.error_google_auth_failed
        else -> null
    }
    return (resId ?: R.string.youtube_auth_incomplete) to null
}
