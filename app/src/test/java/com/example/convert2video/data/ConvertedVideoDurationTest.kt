package com.example.convert2video.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConvertedVideoDurationTest {

    @Before
    fun clearCache() {
        ConvertedVideoDuration.clearCache()
    }

    @Test
    fun success_normalizeDurationMsKeepsPositive() {
        // Given
        val raw = 12_345L

        // When
        val result = ConvertedVideoDuration.normalizeDurationMs(raw)

        // Then
        assertEquals(12_345L, result)
    }

    @Test
    fun success_normalizeDurationMsClampsNegativeToZero() {
        // Given
        val raw = -1L

        // When
        val result = ConvertedVideoDuration.normalizeDurationMs(raw)

        // Then
        assertEquals(0L, result)
    }

    @Test
    fun success_normalizeDurationMsNullIsZero() {
        // Given
        val raw: Long? = null

        // When
        val result = ConvertedVideoDuration.normalizeDurationMs(raw)

        // Then
        assertEquals(0L, result)
    }

    @Test
    fun success_cacheHitReturnsSameDuration() {
        // Given
        val key = ConvertedVideoDuration.cacheKey("/videos/a.mp4", 1_000L)
        ConvertedVideoDuration.putCached(key, 4_000L)

        // When
        val cached = ConvertedVideoDuration.getCached(key)

        // Then
        assertEquals(4_000L, cached)
    }

    @Test
    fun success_cacheMissWhenLastModifiedChanges() {
        // Given
        val path = "/videos/a.mp4"
        ConvertedVideoDuration.putCached(
            ConvertedVideoDuration.cacheKey(path, 1_000L),
            4_000L,
        )

        // When
        val afterEdit = ConvertedVideoDuration.getCached(
            ConvertedVideoDuration.cacheKey(path, 2_000L),
        )

        // Then
        assertNull(afterEdit)
    }

    @Test
    fun failure_normalizeDurationMsTreatsFailureAsZero() {
        // Given: MMR/MediaStore 실패를 null raw 로 표현
        val failedRaw: Long? = null

        // When
        val result = ConvertedVideoDuration.normalizeDurationMs(failedRaw)

        // Then
        assertEquals(0L, result)
    }

    @Test
    fun exception_normalizeDurationMsClampsExtremeNegative() {
        // Given
        val raw = Long.MIN_VALUE

        // When
        val result = ConvertedVideoDuration.normalizeDurationMs(raw)

        // Then
        assertEquals(0L, result)
    }

    @Test
    fun success_shouldCacheRawAllowsZeroSeconds() {
        // Given / When / Then
        assertEquals(true, ConvertedVideoDuration.shouldCacheRaw(0L))
        assertEquals(true, ConvertedVideoDuration.shouldCacheRaw(1_000L))
    }

    @Test
    fun failure_shouldCacheRawRejectsNull() {
        // Given / When / Then — 실패(null)는 캐시 금지
        assertEquals(false, ConvertedVideoDuration.shouldCacheRaw(null))
    }

    @Test
    fun success_removeByAbsolutePathClearsOldKeys() {
        // Given
        val path = "/videos/a.mp4"
        ConvertedVideoDuration.putCached(
            ConvertedVideoDuration.cacheKey(path, 1_000L),
            4_000L,
        )
        ConvertedVideoDuration.putCached(
            ConvertedVideoDuration.cacheKey("/videos/b.mp4", 1_000L),
            5_000L,
        )

        // When
        ConvertedVideoDuration.removeByAbsolutePath(path)

        // Then
        assertNull(
            ConvertedVideoDuration.getCached(
                ConvertedVideoDuration.cacheKey(path, 1_000L),
            ),
        )
        assertEquals(
            5_000L,
            ConvertedVideoDuration.getCached(
                ConvertedVideoDuration.cacheKey("/videos/b.mp4", 1_000L),
            ),
        )
    }
}
