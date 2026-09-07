package com.example.convert2video.ui.screens.recordings_list

import com.example.convert2video.ui.screens.audio_pick.AudioSourceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenListScrollPolicyTest {

    private val myRecordingsTimeAll: ListenListFilterKey = Triple(
        AudioSourceFilter.MyRecordings,
        RecordingsListSortOrder.Time,
        RecordingsListConversionFilter.All,
    )

    private val allTimeAll: ListenListFilterKey = Triple(
        AudioSourceFilter.All,
        RecordingsListSortOrder.Time,
        RecordingsListConversionFilter.All,
    )

    @Test
    fun success_skipFirstComposition_returnsNull() {
        // Given — previousKey null (첫 조성), 유효 index
        // When
        val decision = resolveListenListScrollDecision(
            previousKey = null,
            currentKey = myRecordingsTimeAll,
            itemCount = 24,
            firstVisibleItemIndex = 0,
        )

        // Then — 리셋/coerce 없음
        assertNull(decision)
    }

    @Test
    fun success_filterChanged_returnsResetToFirst() {
        // Given — 필터 키 변경 + 하단 stale index
        // When
        val decision = resolveListenListScrollDecision(
            previousKey = myRecordingsTimeAll,
            currentKey = allTimeAll,
            itemCount = 24,
            firstVisibleItemIndex = 20,
        )

        // Then — 0만. last와 동시에 쏘지 않음
        assertEquals(
            ListenListScrollDecision(ListenListScrollKind.ResetToFirst, 0),
            decision,
        )
    }

    @Test
    fun success_filterChanged_staleIndexAfterShrink_returnsResetNotCoerce() {
        // Given — 키 미커밋(필터 변경 유지) + 다음 프레임 24→8 + stale index
        // When
        val decision = resolveListenListScrollDecision(
            previousKey = myRecordingsTimeAll,
            currentKey = allTimeAll,
            itemCount = 8,
            firstVisibleItemIndex = 20,
        )

        // Then — 축소여도 Reset. Coerce(last=7) 금지
        assertEquals(
            ListenListScrollDecision(ListenListScrollKind.ResetToFirst, 0),
            decision,
        )
    }

    @Test
    fun success_sameKeyStaleIndex_returnsCoerceToLast() {
        // Given — 필터 유지 + firstVisible >= itemCount
        // When
        val decision = resolveListenListScrollDecision(
            previousKey = myRecordingsTimeAll,
            currentKey = myRecordingsTimeAll,
            itemCount = 8,
            firstVisibleItemIndex = 20,
        )

        // Then
        assertEquals(
            ListenListScrollDecision(ListenListScrollKind.CoerceToLast, 7),
            decision,
        )
    }

    @Test
    fun success_emptyItemCount_returnsNull() {
        // Given — 필터가 바뀌어도 빈 목록
        // When
        val decision = resolveListenListScrollDecision(
            previousKey = myRecordingsTimeAll,
            currentKey = allTimeAll,
            itemCount = 0,
            firstVisibleItemIndex = 20,
        )

        // Then — no-op. 호출측은 키를 커밋하지 않음
        assertNull(decision)
    }

    @Test
    fun success_sameKeyValidIndex_returnsNull() {
        // Given / When
        val decision = resolveListenListScrollDecision(
            previousKey = myRecordingsTimeAll,
            currentKey = myRecordingsTimeAll,
            itemCount = 24,
            firstVisibleItemIndex = 5,
        )

        // Then
        assertNull(decision)
    }

    @Test
    fun success_applyIndex_pendingReset_winsOverCoerce() {
        // Given — 취소된 리셋이 남아 있고 이번 결정은 Coerce
        val coerce = ListenListScrollDecision(ListenListScrollKind.CoerceToLast, 7)

        // When / Then — 0만. last와 동시에 쏘지 않음
        assertEquals(0, listenListScrollApplyIndex(coerce, pendingReset = true))
        assertEquals(7, listenListScrollApplyIndex(coerce, pendingReset = false))
        assertEquals(
            0,
            listenListScrollApplyIndex(
                ListenListScrollDecision(ListenListScrollKind.ResetToFirst, 0),
                pendingReset = false,
            ),
        )
        assertNull(listenListScrollApplyIndex(null, pendingReset = false))
    }
}
