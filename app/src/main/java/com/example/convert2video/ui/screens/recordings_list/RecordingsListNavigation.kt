package com.example.convert2video.ui.screens.recordings_list

/** RecordingsList TopBar·BackHandler 공통 back 동작 SSOT. */
internal sealed class RecordingsListBackAction {
    data object DismissExpandedMenu : RecordingsListBackAction()
    data object DismissDeleteDialog : RecordingsListBackAction()
    data object DismissRenameDialog : RecordingsListBackAction()
    data object NavigateHome : RecordingsListBackAction()
}

/**
 * expanded·dialog·Home 우선순위 — dialog > expanded > navigate.
 * TopBar back과 BackHandler가 동일 함수를 사용한다.
 */
internal fun resolveRecordingsListBackAction(
    expandedRecordId: Long?,
    hasPendingDelete: Boolean,
    hasPendingRename: Boolean,
): RecordingsListBackAction = when {
    hasPendingDelete -> RecordingsListBackAction.DismissDeleteDialog
    hasPendingRename -> RecordingsListBackAction.DismissRenameDialog
    expandedRecordId != null -> RecordingsListBackAction.DismissExpandedMenu
    else -> RecordingsListBackAction.NavigateHome
}

/** recordings Flow 갱신 시 사라진 row의 expanded id 제거. */
internal fun pruneExpandedRecordId(
    expandedRecordId: Long?,
    visibleRecordIds: Set<Long>,
): Long? = if (expandedRecordId != null && expandedRecordId !in visibleRecordIds) {
    null
} else {
    expandedRecordId
}
