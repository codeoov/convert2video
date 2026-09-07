package com.example.convert2video

import com.example.convert2video.ui.screens.home.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home 3탭 방문 히스토리 — push/pop/Saver 정규화 회귀 (JVM unit).
 */
class HomeTabHistoryTest {

    @Test
    fun success_push_skipsConsecutiveDuplicate() {
        // Given
        val stack = listOf(HomeTab.Record, HomeTab.Listen)

        // When
        val result = pushHomeTabHistory(stack, HomeTab.Listen)

        // Then — last == current 이면 추가하지 않음
        assertEquals(stack, result)
    }

    @Test
    fun success_push_capsAtMax() {
        // Given — 이미 상한 8
        val stack = listOf(
            HomeTab.Record,
            HomeTab.Listen,
            HomeTab.ConvertedVideos,
            HomeTab.Record,
            HomeTab.Listen,
            HomeTab.ConvertedVideos,
            HomeTab.Record,
            HomeTab.Listen,
        )

        // When
        val result = pushHomeTabHistory(stack, HomeTab.ConvertedVideos)

        // Then — 앞에서 drop, 길이 ≤ 8, 마지막은 방금 push한 탭
        assertEquals(HOME_TAB_HISTORY_MAX, result.size)
        assertEquals(HomeTab.Listen, result.first())
        assertEquals(HomeTab.ConvertedVideos, result.last())
    }

    @Test
    fun success_pop_returnsPreviousAndRemainder() {
        // Given
        val stack = listOf(HomeTab.Record, HomeTab.Listen)

        // When
        val popped = popHomeTabHistory(stack)

        // Then
        assertEquals(listOf(HomeTab.Record), popped?.stack)
        assertEquals(HomeTab.Listen, popped?.previous)
    }

    @Test
    fun failure_pop_emptyReturnsNull() {
        // Given
        val stack = emptyList<HomeTab>()

        // When
        val popped = popHomeTabHistory(stack)

        // Then
        assertNull(popped)
    }

    @Test
    fun success_saver_skipsUnknownName() {
        // Given — 알 수 없는 name은 건너뛴다
        val names = arrayListOf("Listen", "NotATab", "Record")

        // When
        val restored = HomeTabHistorySaver.restore(names)

        // Then
        assertEquals(listOf(HomeTab.Listen, HomeTab.Record), restored)
    }

    @Test
    fun success_saver_restoreCollapsesDuplicatesAndCaps() {
        // Given — 연속 중복 + unknown + cap 초과
        val names = arrayListOf(
            "Listen",
            "Listen",
            "Record",
            "ConvertedVideos",
            "Listen",
            "Record",
            "ConvertedVideos",
            "Listen",
            "Record",
            "ConvertedVideos",
            "NotATab",
            "Listen",
        )

        // When
        val restored = HomeTabHistorySaver.restore(names)

        // Then — unknown skip 후 연속 중복 제거, 길이 ≤ 8
        val expected = listOf(
            HomeTab.ConvertedVideos,
            HomeTab.Listen,
            HomeTab.Record,
            HomeTab.ConvertedVideos,
            HomeTab.Listen,
            HomeTab.Record,
            HomeTab.ConvertedVideos,
            HomeTab.Listen,
        )
        assertEquals(expected, restored)
        assertTrue(restored!!.size <= HOME_TAB_HISTORY_MAX)
        assertEquals(HOME_TAB_HISTORY_MAX, restored.size)
        restored.zipWithNext().forEach { (a, b) ->
            assertTrue("consecutive duplicate after restore: $a", a != b)
        }
    }

    @Test
    fun success_normalize_collapsesConsecutiveDuplicates() {
        // Given
        val stack = listOf(
            HomeTab.Listen,
            HomeTab.Listen,
            HomeTab.Record,
            HomeTab.Record,
        )

        // When
        val normalized = normalizeHomeTabHistory(stack)

        // Then
        assertEquals(listOf(HomeTab.Listen, HomeTab.Record), normalized)
    }
}
