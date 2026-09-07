package com.example.convert2video.ui.screens.recordings_list

import com.example.convert2video.data.AudioItem
import com.example.convert2video.ui.screens.audio_pick.AudioSourceFilter

/**
 * Listen 소스 필터를 표시 목록으로 매핑한다 — SSOT.
 *
 * 첫 번째 [mediaAndImported] 입력은 호출자가 MediaStore 오디오와 imported 오디오를 합친 목록이다.
 * Listen의 [AudioSourceFilter.All]과 [AudioSourceFilter.Files]는 이 입력만 사용하고,
 * [AudioSourceFilter.MyRecordings]만 두 번째 녹음 입력을 사용한다.
 * 두 입력 모두 [AudioItem.dateAdded] 내림차순이며, 동점은 [AudioItem.id] 내림차순이다.
 */
internal fun filterListenAudioItems(
    mediaAndImported: List<AudioItem>,
    recordings: List<AudioItem>,
    filter: AudioSourceFilter,
): List<AudioItem> = when (filter) {
    AudioSourceFilter.All,
    AudioSourceFilter.Files,
    -> mediaAndImported.sortedWith(
        compareByDescending<AudioItem> { it.dateAdded }
            .thenByDescending { it.id },
    )

    AudioSourceFilter.MyRecordings -> recordings.sortedWith(
        compareByDescending<AudioItem> { it.dateAdded }
            .thenByDescending { it.id },
    )
}
