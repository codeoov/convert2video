package com.example.convert2video.drive

import com.example.convert2video.R

private val CODE_SUFFIX_REGEX = Regex("""\(코드 (\d+)\)$""")

/**
 * Maps [GoogleDriveApiClient]/DriveAuthGateway's internal Korean-literal
 * [DriveApiResult.Failure.message] / [DriveAuthorizationOutcome.Failed.message] text to a
 * localized [R.string] resource id, plus a format arg when the literal carries a numeric
 * HTTP/status code suffix (`"...(코드 N)"`). The internal literals stay Korean — they're
 * matched by [com.example.convert2video.drive.GoogleDriveFailureReasonTest] — so this mapping
 * is the only place that text becomes user-facing.
 *
 * Unrecognized or blank input falls back to [R.string.drive_auth_incomplete].
 */
internal fun driveFailureMessageToStringRes(raw: String?): Pair<Int, Int?> {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return R.string.drive_auth_incomplete to null

    CODE_SUFFIX_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { code ->
        return R.string.error_upload_failed_with_code to code
    }

    val resId = when (trimmed) {
        "계정 정보를 가져올 수 없습니다" -> R.string.drive_error_account_info_unavailable
        "네트워크 오류로 계정 정보를 가져올 수 없습니다" -> R.string.drive_error_account_info_network
        "폴더 이름이 올바르지 않습니다" -> R.string.drive_error_folder_name_invalid
        "네트워크 오류로 폴더를 찾을 수 없습니다" -> R.string.drive_error_folder_search_network
        "폴더를 만들 수 없습니다" -> R.string.drive_error_folder_create_failed
        "네트워크 오류로 폴더를 만들 수 없습니다" -> R.string.drive_error_folder_create_network
        "파일 이름이 올바르지 않습니다" -> R.string.drive_error_file_name_invalid
        "폴더 정보가 올바르지 않습니다" -> R.string.drive_error_folder_info_invalid
        "업로드할 파일 크기가 올바르지 않습니다" -> R.string.drive_error_file_size_invalid
        "업로드 세션을 시작할 수 없습니다" -> R.string.drive_error_upload_session_failed
        "네트워크 오류로 업로드를 시작할 수 없습니다" -> R.string.drive_error_upload_start_network
        "업로드 주소가 올바르지 않습니다" -> R.string.drive_error_upload_url_invalid
        "업로드 결과를 확인할 수 없습니다" -> R.string.drive_error_upload_result_unknown
        "네트워크 오류로 업로드에 실패했습니다" -> R.string.drive_error_upload_network
        "인증이 만료되었습니다. 다시 로그인해 주세요" -> R.string.error_auth_expired
        "파일을 찾을 수 없습니다" -> R.string.drive_error_file_not_found
        "권한이 없거나 요청이 거부되었습니다" -> R.string.drive_error_permission_denied
        "Google Drive 서버 오류입니다. 잠시 후 다시 시도해 주세요" -> R.string.drive_error_server
        "Google Drive 저장 공간이 부족합니다" -> R.string.drive_error_storage_quota_exceeded
        "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요" -> R.string.drive_error_rate_limited
        "Google Drive 권한이 없습니다. 다시 로그인해 주세요" -> R.string.drive_error_insufficient_permissions
        "Google Drive API가 이 프로젝트에서 사용 설정되지 않았습니다" -> R.string.drive_error_api_not_configured
        "Google 인증을 진행할 수 없습니다" -> R.string.error_google_auth_unavailable
        "Google 인증에 실패했습니다" -> R.string.error_google_auth_failed
        else -> null
    }
    return (resId ?: R.string.drive_auth_incomplete) to null
}
