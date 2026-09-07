package com.example.convert2video.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [parseDriveFailureReason].
 * Uses the real `org.json:json` implementation (testImplementation dependency) so JSON assertions
 * are not vacuously true under isReturnDefaultValues.
 */
class GoogleDriveFailureReasonTest {

    // ── known reasons → Korean message ────────────────────────────────────────

    @Test
    fun success_storageQuotaExceeded_returnsKoreanMessage() {
        // Given
        val body = """
            {
              "error": {
                "code": 403,
                "errors": [
                  { "reason": "storageQuotaExceeded", "domain": "global", "message": "quota" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseDriveFailureReason(body)

        // Then
        assertEquals("Google Drive 저장 공간이 부족합니다", result)
    }

    @Test
    fun success_rateLimitExceeded_returnsKoreanMessage() {
        // Given
        val body = """
            {
              "error": {
                "code": 403,
                "errors": [
                  { "reason": "rateLimitExceeded", "domain": "usageLimits" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseDriveFailureReason(body)

        // Then
        assertEquals("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요", result)
    }

    @Test
    fun success_userRateLimitExceeded_returnsKoreanMessage() {
        // Given
        val body = """
            {
              "error": {
                "code": 403,
                "errors": [
                  { "reason": "userRateLimitExceeded", "domain": "usageLimits" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseDriveFailureReason(body)

        // Then
        assertEquals("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요", result)
    }

    @Test
    fun success_insufficientPermissions_returnsKoreanMessage() {
        // Given
        val body = """
            {
              "error": {
                "code": 403,
                "errors": [
                  { "reason": "insufficientPermissions", "domain": "global" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseDriveFailureReason(body)

        // Then
        assertEquals("Google Drive 권한이 없습니다. 다시 로그인해 주세요", result)
    }

    @Test
    fun success_notFound_returnsKoreanMessage() {
        // Given
        val body = """
            {
              "error": {
                "code": 404,
                "errors": [
                  { "reason": "notFound", "domain": "global" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseDriveFailureReason(body)

        // Then
        assertEquals("파일을 찾을 수 없습니다", result)
    }

    @Test
    fun success_accessNotConfigured_returnsKoreanMessage() {
        // Given: Drive API disabled on the OAuth Cloud project
        val body = """
            {
              "error": {
                "code": 403,
                "errors": [
                  { "reason": "accessNotConfigured", "domain": "usageLimits" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseDriveFailureReason(body)

        // Then
        assertEquals("Google Drive API가 이 프로젝트에서 사용 설정되지 않았습니다", result)
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
                  { "reason": "forbidden", "domain": "global" }
                ]
              }
            }
        """.trimIndent()

        // When
        val result = parseDriveFailureReason(body)

        // Then
        assertNull(result)
    }

    @Test
    fun failure_emptyBody_returnsNull() {
        // Given / When
        val result = parseDriveFailureReason("")

        // Then
        assertNull(result)
    }

    @Test
    fun failure_malformedJson_returnsNull() {
        // Given: syntactically invalid JSON
        val body = "{ not valid json }"

        // When
        val result = parseDriveFailureReason(body)

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
        val result = parseDriveFailureReason(body)

        // Then
        assertNull(result)
    }
}
