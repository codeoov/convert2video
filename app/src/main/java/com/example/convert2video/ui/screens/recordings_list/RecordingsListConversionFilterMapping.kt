package com.example.convert2video.ui.screens.recordings_list

import com.example.convert2video.data.AudioItem

/**
 * 변환 여부 필터를 [items]에 적용하여 표시할 목록을 반환한다 — SSOT.
 *
 * ViewModel 인라인 금지. [RecordingsListViewModel.audioItems] combine 내부에서 호출한다.
 *
 * @param items 이미 소스 필터([filterListenAudioItems])를 거친 오디오 목록.
 * @param filter 적용할 변환 여부 필터 ([RecordingsListConversionFilter]).
 * @param convertedUris 변환 이력 URI 문자열 집합 ([RecordingsListViewModel.convertedAudioUris] SSOT).
 *        각 항목의 [AudioItem.uri].toString()과 비교한다.
 * @return [filter]에 따라 걸러진 목록.
 *   - [RecordingsListConversionFilter.All]: [items] 그대로 반환.
 *   - [RecordingsListConversionFilter.Converted]: uri.toString()이 [convertedUris]에 포함된 항목만.
 *   - [RecordingsListConversionFilter.NotConverted]: uri.toString()이 [convertedUris]에 미포함된 항목만.
 */
fun filterByConversion(
    items: List<AudioItem>,
    filter: RecordingsListConversionFilter,
    convertedUris: Set<String>,
): List<AudioItem> = when (filter) {
    RecordingsListConversionFilter.All -> items
    RecordingsListConversionFilter.Converted -> items.filter { it.uri.toString() in convertedUris }
    RecordingsListConversionFilter.NotConverted -> items.filter { it.uri.toString() !in convertedUris }
}

/**
 * [RecordingsListConversionFilter] → DropdownSelector 선택 인덱스 — SSOT.
 *
 * 0=[All] / 1=[Converted] / 2=[NotConverted].
 * Screen의 인라인 `when` 대신 이 함수를 사용한다.
 *
 * @see conversionFilterFromIndex 역함수
 */
fun conversionFilterToIndex(filter: RecordingsListConversionFilter): Int = when (filter) {
    RecordingsListConversionFilter.All -> 0
    RecordingsListConversionFilter.Converted -> 1
    RecordingsListConversionFilter.NotConverted -> 2
}

/**
 * DropdownSelector 선택 인덱스 → [RecordingsListConversionFilter] — SSOT.
 *
 * 범위 밖 인덱스는 [RecordingsListConversionFilter.All]로 폴백한다.
 * Screen의 인라인 `when` 대신 이 함수를 사용한다.
 *
 * @see conversionFilterToIndex 역함수
 */
fun conversionFilterFromIndex(index: Int): RecordingsListConversionFilter = when (index) {
    0 -> RecordingsListConversionFilter.All
    1 -> RecordingsListConversionFilter.Converted
    2 -> RecordingsListConversionFilter.NotConverted
    else -> RecordingsListConversionFilter.All
}
