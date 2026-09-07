package com.example.convert2video.ui.screens.recordings_list

import com.example.convert2video.data.AudioItem
import java.util.Locale

/**
 * [RecordingsListSortOrder]에 따라 [items]를 정렬한 새 목록을 반환한다.
 *
 * - [RecordingsListSortOrder.Time]: dateAdded 내림차순 (최신 먼저). 동점 시 id 내림차순 (안정 보장).
 * - [RecordingsListSortOrder.Name]: `title.lowercase(Locale.ROOT)` 오름차순.
 *   [Locale.ROOT] 사용으로 터키어 i/I 등 로케일 의존 변환 방지. 동점 시 dateAdded 내림차순.
 * - [RecordingsListSortOrder.Duration]: durationMs 내림차순 (긴 것 먼저). 동점 시 dateAdded 내림차순.
 */
fun sortDisplayedItems(
    items: List<AudioItem>,
    order: RecordingsListSortOrder,
): List<AudioItem> = when (order) {
    RecordingsListSortOrder.Time ->
        items.sortedWith(
            compareByDescending<AudioItem> { it.dateAdded }
                .thenByDescending { it.id },
        )
    RecordingsListSortOrder.Name ->
        items.sortedWith(
            compareBy<AudioItem> { it.title.lowercase(Locale.ROOT) }
                .thenByDescending { it.dateAdded },
        )
    RecordingsListSortOrder.Duration ->
        items.sortedWith(
            compareByDescending<AudioItem> { it.durationMs }
                .thenByDescending { it.dateAdded },
        )
}
