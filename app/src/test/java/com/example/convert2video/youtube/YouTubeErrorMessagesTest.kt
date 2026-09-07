package com.example.convert2video.youtube

import com.example.convert2video.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM unit tests for [youTubeFailureMessageToStringRes]. */
class YouTubeErrorMessagesTest {

    @Test
    fun success_knownMessage_mapsToStringRes() {
        // Given
        val raw = "채널 정보를 가져올 수 없습니다"

        // When
        val (resId, code) = youTubeFailureMessageToStringRes(raw)

        // Then
        assertEquals(R.string.youtube_error_channel_info_unavailable, resId)
        assertEquals(null, code)
    }

    @Test
    fun success_uploadCodeSuffixMessage_mapsToGenericCodeString() {
        // Given
        val raw = "업로드에 실패했습니다 (코드 500)"

        // When
        val (resId, code) = youTubeFailureMessageToStringRes(raw)

        // Then
        assertEquals(R.string.error_upload_failed_with_code, resId)
        assertEquals(500, code)
    }

    @Test
    fun success_authCodeSuffixMessage_mapsToAuthCodeString() {
        // Given: distinct prefix from the generic upload-failed-with-code case
        val raw = "Google 인증에 실패했습니다 (코드 12500)"

        // When
        val (resId, code) = youTubeFailureMessageToStringRes(raw)

        // Then
        assertEquals(R.string.error_google_auth_failed_with_code, resId)
        assertEquals(12500, code)
    }

    @Test
    fun failure_nullMessage_fallsBackToAuthIncomplete() {
        // Given / When
        val (resId, code) = youTubeFailureMessageToStringRes(null)

        // Then
        assertEquals(R.string.youtube_auth_incomplete, resId)
        assertEquals(null, code)
    }

    @Test
    fun failure_unrecognizedMessage_fallsBackToAuthIncomplete() {
        // Given / When
        val (resId, code) = youTubeFailureMessageToStringRes("some unmapped message")

        // Then
        assertEquals(R.string.youtube_auth_incomplete, resId)
        assertEquals(null, code)
    }
}
