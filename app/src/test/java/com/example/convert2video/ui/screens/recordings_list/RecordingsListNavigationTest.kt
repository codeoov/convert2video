package com.example.convert2video.ui.screens.recordings_list

import com.example.convert2video.data.RecordingRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingsListNavigationTest {

    // Given / When / Then

    @Test
    fun success_resolveBackAction_dialogBeforeExpanded() {
        assertEquals(
            RecordingsListBackAction.DismissDeleteDialog,
            resolveRecordingsListBackAction(expandedRecordId = 1L, hasPendingDelete = true, hasPendingRename = false),
        )
        assertEquals(
            RecordingsListBackAction.DismissRenameDialog,
            resolveRecordingsListBackAction(expandedRecordId = 1L, hasPendingDelete = false, hasPendingRename = true),
        )
    }

    @Test
    fun success_resolveBackAction_expandedBeforeNavigateHome() {
        assertEquals(
            RecordingsListBackAction.DismissExpandedMenu,
            resolveRecordingsListBackAction(expandedRecordId = 42L, hasPendingDelete = false, hasPendingRename = false),
        )
    }

    @Test
    fun success_resolveBackAction_navigateHomeWhenClean() {
        assertEquals(
            RecordingsListBackAction.NavigateHome,
            resolveRecordingsListBackAction(expandedRecordId = null, hasPendingDelete = false, hasPendingRename = false),
        )
    }

    @Test
    fun success_pruneExpandedRecordId_removesStaleId() {
        assertNull(pruneExpandedRecordId(99L, setOf(1L, 2L)))
    }

    @Test
    fun success_pruneExpandedRecordId_keepsVisibleId() {
        assertEquals(2L, pruneExpandedRecordId(2L, setOf(1L, 2L)))
    }
}
