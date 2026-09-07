package com.example.convert2video.ui.screens.trash

import com.example.convert2video.data.TrashedItem

/**
 * 휴지통 행 UI 모델. [isConverted]는 오디오만 의미가 있다.
 */
internal data class TrashRowUi(
    val item: TrashedItem,
    val isAudio: Boolean,
    val isConverted: Boolean,
)

internal fun isTrashedAudioType(itemType: String): Boolean = when (itemType) {
    TrashedItem.RECORDING_AUDIO, TrashedItem.IMPORTED_AUDIO -> true
    else -> false
}

internal fun isTrashedVideoType(itemType: String): Boolean =
    itemType == TrashedItem.CONVERTED_VIDEO

/**
 * Listen 탭과 동일 SSOT: 변환 이력 URI 집합과 대조한다.
 *
 * - [TrashedItem.sourceAudioUri]가 있으면 그 문자열을 [convertedUris]와 비교 (임포트 오디오).
 * - 녹음은 휴지통 이관 시 URI를 안 남기는 경우가 있어 [TrashedItem.displayName]과
 *   URI last path segment를 보조 매칭한다.
 */
internal fun isTrashedAudioConverted(
    item: TrashedItem,
    convertedUris: Set<String>,
): Boolean {
    if (!isTrashedAudioType(item.itemType)) return false
    val source = item.sourceAudioUri?.takeIf { it.isNotBlank() }
    if (source != null && source in convertedUris) return true
    val name = item.displayName.trim()
    if (name.isEmpty()) return false
    return convertedUris.any { uri ->
        uri.substringAfterLast('/').substringBefore('?').equals(name, ignoreCase = true)
    }
}

internal fun toTrashRowUi(
    item: TrashedItem,
    convertedUris: Set<String>,
): TrashRowUi = TrashRowUi(
    item = item,
    isAudio = isTrashedAudioType(item.itemType),
    isConverted = isTrashedAudioConverted(item, convertedUris),
)
