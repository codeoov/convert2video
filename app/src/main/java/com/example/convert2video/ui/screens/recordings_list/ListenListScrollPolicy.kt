package com.example.convert2video.ui.screens.recordings_list

import com.example.convert2video.ui.screens.audio_pick.AudioSourceFilter

/** Listen 목록 필터·정렬·변환 필터 키. */
typealias ListenListFilterKey =
    Triple<AudioSourceFilter, RecordingsListSortOrder, RecordingsListConversionFilter>

enum class ListenListScrollKind {
    ResetToFirst,
    CoerceToLast,
}

data class ListenListScrollDecision(
    val kind: ListenListScrollKind,
    val index: Int,
)

/**
 * Listen 목록 스크롤 정책 SSOT.
 *
 * - [itemCount] 0 이하 → no-op (`null`). 호출측은 키를 커밋하지 않는다.
 * - 첫 조성(`previousKey == null`) + 유효 index → skip (`null`).
 * - 필터 키가 실제로 바뀌면 [ListenListScrollKind.ResetToFirst]만 (stale index여도 Coerce 금지).
 * - 필터 유지 + stale index → [ListenListScrollKind.CoerceToLast]만.
 */
fun resolveListenListScrollDecision(
    previousKey: ListenListFilterKey?,
    currentKey: ListenListFilterKey,
    itemCount: Int,
    firstVisibleItemIndex: Int,
): ListenListScrollDecision? {
    if (itemCount <= 0) {
        return null
    }
    val filterChanged = previousKey != null && previousKey != currentKey
    return when {
        filterChanged -> ListenListScrollDecision(ListenListScrollKind.ResetToFirst, 0)
        firstVisibleItemIndex >= itemCount -> {
            ListenListScrollDecision(ListenListScrollKind.CoerceToLast, itemCount - 1)
        }
        else -> null
    }
}

/**
 * SideEffect·LaunchedEffect가 같은 인덱스를 쓰게 한다.
 * [pendingReset]이면 항상 0 — Coerce(last)와 동시에 쏘지 않는다.
 */
fun listenListScrollApplyIndex(
    decision: ListenListScrollDecision?,
    pendingReset: Boolean,
): Int? = when {
    pendingReset || decision?.kind == ListenListScrollKind.ResetToFirst -> 0
    decision != null -> decision.index
    else -> null
}
