package com.example.convert2video.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [parseFailureReason].
 * Uses the real `org.json:json` implementation (testImplementation dependency) so JSON assertions
 * are not vacuously true under isReturnDefaultValues.
 */
class YouTubeFailureReasonTest {

    // ── known reasons → Korean message ────────────────────────────────────────

    @Test
    fun success_quotaExceeded_returnsKoreanMessage() {
        // Given: typical YouTube 403 body with reason = quotaExceeded
        val body = """
            {
              "error": {
                "code": 403,
                "errors": [
                  { "reason": "quotaExceeded", "domain": "youtube.quota", "message": "quota exceeded" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseFailureReason(body)

        // Then
        assertEquals("YouTube API 일일 할당량을 초과했습니다. 내일 다시 시도해 주세요", result)
    }

    @Test
    fun success_dailyLimitExceeded_returnsKoreanMessage() {
        // Given
        val body = """
            {
              "error": {
                "code": 403,
                "errors": [
                  { "reason": "dailyLimitExceeded", "domain": "youtube.quota" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseFailureReason(body)

        // Then
        assertEquals("일일 업로드 횟수 한도를 초과했습니다. 내일 다시 시도해 주세요", result)
    }

    @Test
    fun success_uploadLimitExceeded_returnsKoreanMessage() {
        // Given
        val body = """
            {
              "error": {
                "code": 403,
                "errors": [
                  { "reason": "uploadLimitExceeded", "domain": "youtube.quota" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseFailureReason(body)

        // Then
        assertEquals("업로드 용량 한도를 초과했습니다", result)
    }

    // ── failure / boundary cases → null ───────────────────────────────────────

    @Test
    fun failure_unknownReason_returnsNull() {
        // Given: valid body structure but unrecognised reason value
        val body = """
            {
              "error": {
                "code": 403,
                "errors": [
                  { "reason": "forbidden", "domain": "youtube" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseFailureReason(body)

        // Then
        assertNull(result)
    }

    @Test
    fun failure_emptyBody_returnsNull() {
        // Given / When
        val result = parseFailureReason("")

        // Then
        assertNull(result)
    }

    @Test
    fun failure_malformedJson_returnsNull() {
        // Given: syntactically invalid JSON
        val body = "{ not valid json }"

        // When
        val result = parseFailureReason(body)

        // Then
        assertNull(result)
    }

    @Test
    fun failure_emptyErrorsArray_returnsNull() {
        // Given: valid error envelope but errors array is empty — no reason object to read
        val body = """
            {
              "error": {
                "code": 403,
                "errors": []
              }
            }
        """.trimIndent()

        // When
        val result = parseFailureReason(body)

        // Then
        assertNull(result)
    }
}
