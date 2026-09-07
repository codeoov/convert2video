package com.example.convert2video.ui.screens.recordings_list

/**
 * Listen 화면 변환 여부 필터 — SSOT.
 *
 * 기본값 [All]. DropdownSelector 인덱스 0=[All] / 1=[Converted] / 2=[NotConverted].
 * 실제 필터링은 [filterByConversion] 단일 소스에서 수행한다.
 *
 * @see filterByConversion
 * @see RecordingsListViewModel.conversionFilter
 */
enum class RecordingsListConversionFilter {

    /** 변환 여부와 무관하게 전체 목록을 표시. */
    All,

    /** 이미 변환된 오디오만 표시 (uri.toString()이 convertedUris에 포함된 항목). */
    Converted,

    /** 아직 변환되지 않은 오디오만 표시 (uri.toString()이 convertedUris에 미포함된 항목). */
    NotConverted,
}
