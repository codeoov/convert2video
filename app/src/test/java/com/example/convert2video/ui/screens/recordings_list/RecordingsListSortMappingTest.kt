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
 * [sortDisplayedItems] 순수 함수 단위 테스트.
 * Robolectric: [Uri.parse] 접근 최소화 (id/dateAdded/title 비교만 검증).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordingsListSortMappingTest {

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun item(
        id: Long,
        title: String,
        dateAdded: Long,
    ): AudioItem = AudioItem(
        id = id,
        title = title,
        artist = null,
        durationMs = 0L,
        uri = Uri.parse("content://audio/$id"),
        dateAdded = dateAdded,
        fileName = title,
    )

    // ── Time 정렬 ─────────────────────────────────────────────────────────────

    @Test
    fun success_sortTime_ordersNewestFirst() {
        // Given
        val items = listOf(
            item(id = 1L, title = "A", dateAdded = 100L),
            item(id = 2L, title = "B", dateAdded = 300L),
            item(id = 3L, title = "C", dateAdded = 200L),
        )

        // When
        val result = sortDisplayedItems(items, RecordingsListSortOrder.Time)

        // Then — dateAdded 내림차순
        assertEquals(listOf(300L, 200L, 100L), result.map { it.dateAdded })
    }

    @Test
    fun success_sortTime_tieBreaker_idDescending() {
        // Given — dateAdded 동일, id로 타이브레이크
        val items = listOf(
            item(id = 1L, title = "A", dateAdded = 100L),
            item(id = 3L, title = "C", dateAdded = 100L),
            item(id = 2L, title = "B", dateAdded = 100L),
        )

        // When
        val result = sortDisplayedItems(items, RecordingsListSortOrder.Time)

        // Then — id 내림차순 타이브레이크
        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }

    @Test
    fun success_sortTime_emptyList() {
        // Given / When / Then
        assertTrue(sortDisplayedItems(emptyList(), RecordingsListSortOrder.Time).isEmpty())
    }

    @Test
    fun success_sortTime_singleItem() {
        // Given
        val items = listOf(item(id = 5L, title = "Only", dateAdded = 50L))

        // When
        val result = sortDisplayedItems(items, RecordingsListSortOrder.Time)

        // Then
        assertEquals(1, result.size)
        assertEquals(5L, result[0].id)
    }

    // ── Name 정렬 ─────────────────────────────────────────────────────────────

    @Test
    fun success_sortName_ordersAlphabetically() {
        // Given
        val items = listOf(
            item(id = 1L, title = "Charlie", dateAdded = 100L),
            item(id = 2L, title = "Alpha",   dateAdded = 200L),
            item(id = 3L, title = "Bravo",   dateAdded = 300L),
        )

        // When
        val result = sortDisplayedItems(items, RecordingsListSortOrder.Name)

        // Then — title.lowercase() 오름차순
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), result.map { it.title })
    }

    @Test
    fun success_sortName_caseInsensitive() {
        // Given — 대소문자 혼재
        val items = listOf(
            item(id = 1L, title = "banana", dateAdded = 100L),
            item(id = 2L, title = "Apple",  dateAdded = 200L),
            item(id = 3L, title = "CHERRY", dateAdded = 300L),
        )

        // When
        val result = sortDisplayedItems(items, RecordingsListSortOrder.Name)

        // Then — lowercase 기준 apple < banana < cherry
        assertEquals(listOf("Apple", "banana", "CHERRY"), result.map { it.title })
    }

    @Test
    fun success_sortName_tieBreaker_dateAddedDescending() {
        // Given — 동일 title.lowercase(), dateAdded로 타이브레이크
        val items = listOf(
            item(id = 1L, title = "Same", dateAdded = 100L),
            item(id = 2L, title = "Same", dateAdded = 300L),
            item(id = 3L, title = "Same", dateAdded = 200L),
        )

        // When
        val result = sortDisplayedItems(items, RecordingsListSortOrder.Name)

        // Then — dateAdded 내림차순 타이브레이크
        assertEquals(listOf(300L, 200L, 100L), result.map { it.dateAdded })
    }

    @Test
    fun success_sortName_emptyList() {
        // Given / When / Then
        assertTrue(sortDisplayedItems(emptyList(), RecordingsListSortOrder.Name).isEmpty())
    }

    // ── 공통 속성 보존 ────────────────────────────────────────────────────────

    @Test
    fun success_sort_doesNotDropItems() {
        // Given
        val items = (1..5).map { i ->
            item(id = i.toLong(), title = "Track $i", dateAdded = (10 - i).toLong())
        }

        // When / Then — 어느 순서에도 건수 유지
        assertEquals(5, sortDisplayedItems(items, RecordingsListSortOrder.Time).size)
        assertEquals(5, sortDisplayedItems(items, RecordingsListSortOrder.Name).size)
    }

    @Test
    fun success_sort_preservesItemIdentity() {
        // Given
        val items = listOf(
            item(id = 10L, title = "Z", dateAdded = 1L),
            item(id = 20L, title = "A", dateAdded = 2L),
        )

        // When
        val byTime = sortDisplayedItems(items, RecordingsListSortOrder.Time)
        val byName = sortDisplayedItems(items, RecordingsListSortOrder.Name)

        // Then — id로 동일 객체 확인
        assertEquals(listOf(20L, 10L), byTime.map { it.id })  // dateAdded desc
        assertEquals(listOf(20L, 10L), byName.map { it.id })  // "A" < "Z"
    }

    // ── Locale.ROOT 독립성 ─────────────────────────────────────────────────────

    /**
     * 터키어 로케일에서 "I".lowercase() 는 "ı"(dotless i)를 반환한다.
     * Locale.ROOT 사용 시 "I" → "i" 로 일관되게 처리되어야 한다.
     * 테스트는 시스템 로케일 무관하게 동일 결과를 보장한다.
     */
    @Test
    fun success_sortName_localeRoot_turkishIInsensitive() {
        // Given — "Istanbul"은 터키어 로케일에서 lowercase() 시 "ıstanbul" (dotless-i)
        val items = listOf(
            item(id = 1L, title = "Istanbul", dateAdded = 100L),
            item(id = 2L, title = "athens",   dateAdded = 200L),
            item(id = 3L, title = "Berlin",   dateAdded = 300L),
        )

        // When — Locale.ROOT 기준 "athens" < "berlin" < "istanbul"
        val result = sortDisplayedItems(items, RecordingsListSortOrder.Name)

        // Then — Locale.ROOT lowercase: a < b < i (i ≠ ı)
        assertEquals(listOf("athens", "Berlin", "Istanbul"), result.map { it.title })
    }

    @Test
    fun success_sortName_localeRoot_consistentAcrossJvmLocale() {
        // Given — 기본 JVM Locale과 무관하게 동일 순서 보장
        val items = listOf(
            item(id = 1L, title = "Zebra",  dateAdded = 1L),
            item(id = 2L, title = "apple",  dateAdded = 2L),
            item(id = 3L, title = "Mango",  dateAdded = 3L),
        )

        // When
        val result = sortDisplayedItems(items, RecordingsListSortOrder.Name)

        // Then — Locale.ROOT lowercase: apple < mango < zebra
        assertEquals(listOf("apple", "Mango", "Zebra"), result.map { it.title })
    }
}
