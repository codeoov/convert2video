package com.example.convert2video.drive

import com.example.convert2video.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM unit tests for [driveFailureMessageToStringRes]. */
class DriveErrorMessagesTest {

    @Test
    fun success_knownMessage_mapsToStringRes() {
        // Given
        val raw = "Google Drive 저장 공간이 부족합니다"

        // When
        val (resId, code) = driveFailureMessageToStringRes(raw)

        // Then
        assertEquals(R.string.drive_error_storage_quota_exceeded, resId)
        assertEquals(null, code)
    }

    @Test
    fun success_accessNotConfiguredMessage_mapsToApiNotConfigured() {
        // Given
        val raw = "Google Drive API가 이 프로젝트에서 사용 설정되지 않았습니다"

        // When
        val (resId, code) = driveFailureMessageToStringRes(raw)

        // Then
        assertEquals(R.string.drive_error_api_not_configured, resId)
        assertEquals(null, code)
    }

    @Test
    fun success_codeSuffixMessage_extractsCodeArg() {
        // Given
        val raw = "업로드에 실패했습니다 (코드 500)"

        // When
        val (resId, code) = driveFailureMessageToStringRes(raw)

        // Then
        assertEquals(R.string.error_upload_failed_with_code, resId)
        assertEquals(500, code)
    }

    @Test
    fun failure_nullMessage_fallsBackToAuthIncomplete() {
        // Given / When
        val (resId, code) = driveFailureMessageToStringRes(null)

        // Then
        assertEquals(R.string.drive_auth_incomplete, resId)
        assertEquals(null, code)
    }

    @Test
    fun failure_blankMessage_fallsBackToAuthIncomplete() {
        // Given / When
        val (resId, code) = driveFailureMessageToStringRes("   ")

        // Then
        assertEquals(R.string.drive_auth_incomplete, resId)
        assertEquals(null, code)
    }

    @Test
    fun failure_unrecognizedMessage_fallsBackToAuthIncomplete() {
        // Given / When
        val (resId, code) = driveFailureMessageToStringRes("some unmapped message")

        // Then
        assertEquals(R.string.drive_auth_incomplete, resId)
        assertEquals(null, code)
    }
}
