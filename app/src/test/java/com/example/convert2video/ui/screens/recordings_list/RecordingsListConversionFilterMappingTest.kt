package com.example.convert2video.ui.screens.recordings_list

import android.net.Uri
import com.example.convert2video.data.AudioItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [filterByConversion] 순수 함수 단위 테스트.
 * Robolectric: [Uri.parse] 사용 최소화 — uri.toString() 비교만 검증.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordingsListConversionFilterMappingTest {

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun item(id: Long, uriStr: String): AudioItem = AudioItem(
        id = id,
        title = "Track $id",
        fileName = "track_$id.m4a",
        artist = null,
        durationMs = 0L,
        uri = Uri.parse(uriStr),
    )

    private val uri1 = "content://audio/1"
    private val uri2 = "content://audio/2"
    private val uri3 = "content://audio/3"

    private val item1 = item(1L, uri1)
    private val item2 = item(2L, uri2)
    private val item3 = item(3L, uri3)

    // ── All 필터 ─────────────────────────────────────────────────────────────

    @Test
    fun success_filterAll_returnsAllItems() {
        // Given
        val items = listOf(item1, item2, item3)
        val convertedUris = setOf(uri1)

        // When
        val result = filterByConversion(items, RecordingsListConversionFilter.All, convertedUris)

        // Then — 전체 반환, 순서 유지
        assertEquals(listOf(item1, item2, item3), result)
    }

    @Test
    fun success_filterAll_emptyList_returnsEmpty() {
        // Given / When / Then
        assertTrue(
            filterByConversion(
                emptyList(),
                RecordingsListConversionFilter.All,
                setOf(uri1),
            ).isEmpty(),
        )
    }

    @Test
    fun success_filterAll_emptyConvertedUris_returnsAllItems() {
        // Given
        val items = listOf(item1, item2)

        // When
        val result = filterByConversion(items, RecordingsListConversionFilter.All, emptySet())

        // Then — convertedUris 비어 있어도 All은 그대로 반환
        assertEquals(2, result.size)
    }

    // ── Converted 필터 ────────────────────────────────────────────────────────

    @Test
    fun success_filterConverted_returnsOnlyConvertedItems() {
        // Given
        val items = listOf(item1, item2, item3)
        val convertedUris = setOf(uri1, uri3)

        // When
        val result = filterByConversion(items, RecordingsListConversionFilter.Converted, convertedUris)

        // Then — uri1, uri3 항목만
        assertEquals(listOf(item1, item3), result)
    }

    @Test
    fun success_filterConverted_emptyConvertedUris_returnsEmpty() {
        // Given
        val items = listOf(item1, item2)

        // When
        val result = filterByConversion(items, RecordingsListConversionFilter.Converted, emptySet())

        // Then — 변환 이력 없으면 결과 비어야 함
        assertTrue(result.isEmpty())
    }

    @Test
    fun success_filterConverted_emptyList_returnsEmpty() {
        // Given / When / Then
        assertTrue(
            filterByConversion(
                emptyList(),
                RecordingsListConversionFilter.Converted,
                setOf(uri1),
            ).isEmpty(),
        )
    }

    @Test
    fun success_filterConverted_allConverted_returnsAll() {
        // Given — 모두 변환됨
        val items = listOf(item1, item2)
        val convertedUris = setOf(uri1, uri2)

        // When
        val result = filterByConversion(items, RecordingsListConversionFilter.Converted, convertedUris)

        // Then
        assertEquals(listOf(item1, item2), result)
    }

    // ── NotConverted 필터 ─────────────────────────────────────────────────────

    @Test
    fun success_filterNotConverted_returnsOnlyNotConvertedItems() {
        // Given
        val items = listOf(item1, item2, item3)
        val convertedUris = setOf(uri1, uri3)

        // When
        val result = filterByConversion(items, RecordingsListConversionFilter.NotConverted, convertedUris)

        // Then — uri2만 미변환
        assertEquals(listOf(item2), result)
    }

    @Test
    fun success_filterNotConverted_emptyConvertedUris_returnsAll() {
        // Given — 변환 이력 없음 → 전부 미변환
        val items = listOf(item1, item2)

        // When
        val result = filterByConversion(items, RecordingsListConversionFilter.NotConverted, emptySet())

        // Then
        assertEquals(listOf(item1, item2), result)
    }

    @Test
    fun success_filterNotConverted_allConverted_returnsEmpty() {
        // Given — 모두 변환됨
        val items = listOf(item1, item2)
        val convertedUris = setOf(uri1, uri2)

        // When
        val result = filterByConversion(items, RecordingsListConversionFilter.NotConverted, convertedUris)

        // Then
        assertTrue(result.isEmpty())
    }

    @Test
    fun success_filterNotConverted_emptyList_returnsEmpty() {
        // Given / When / Then
        assertTrue(
            filterByConversion(
                emptyList(),
                RecordingsListConversionFilter.NotConverted,
                setOf(uri1),
            ).isEmpty(),
        )
    }

    // ── 인덱스 변환 (SSOT 라운드트립) ───────────────────────────────────────────

    @Test
    fun success_conversionFilterIndex_roundTrip() {
        // Given / When / Then — 모든 enum 값이 인덱스 ↔ 필터 왕복 일치
        RecordingsListConversionFilter.entries.forEach { filter ->
            assertEquals(filter, conversionFilterFromIndex(conversionFilterToIndex(filter)))
        }
    }

    @Test
    fun success_conversionFilterFromIndex_outOfBounds_returnsAll() {
        // Given / When / Then — 범위 밖 인덱스는 All 폴백
        assertEquals(RecordingsListConversionFilter.All, conversionFilterFromIndex(99))
        assertEquals(RecordingsListConversionFilter.All, conversionFilterFromIndex(-1))
    }

    // ── 공통 속성 보존 ────────────────────────────────────────────────────────

    @Test
    fun success_filter_complementProperty_convertedPlusNotConverted_equalsAll() {
        // Given
        val items = listOf(item1, item2, item3)
        val convertedUris = setOf(uri1, uri2)

        // When
        val converted = filterByConversion(items, RecordingsListConversionFilter.Converted, convertedUris)
        val notConverted = filterByConversion(items, RecordingsListConversionFilter.NotConverted, convertedUris)

        // Then — Converted + NotConverted 합집합 == All
        val combined = (converted + notConverted).sortedBy { it.id }
        assertEquals(items.sortedBy { it.id }, combined)
    }
}
