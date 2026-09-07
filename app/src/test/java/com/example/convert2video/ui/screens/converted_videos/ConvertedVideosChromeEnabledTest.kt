package com.example.convert2video.ui.screens.converted_videos

import android.net.Uri
import com.example.convert2video.data.ConvertedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ConvertedVideosChromeEnabledTest {

    private fun pendingVideo(): ConvertedVideo = ConvertedVideo(
        uri = Uri.parse("content://media/external/video/media/1"),
        displayName = "pending.mp4",
        dateAdded = 0L,
        durationMs = 0L,
    )

    @Test
    fun success_enabledWhenAllPendingNull() {
        // Given
        val pendingDelete: ConvertedVideo? = null
        val pendingRename: ConvertedVideo? = null
        val pendingUpload: ConvertedVideo? = null

        // When
        val enabled = isConvertedVideosChromeEnabled(
            pendingDelete = pendingDelete,
            pendingRename = pendingRename,
            pendingUpload = pendingUpload,
        )

        // Then
        assertTrue(enabled)
    }

    @Test
    fun success_disabledWhenPendingDeleteNonNull() {
        // Given: pendingDelete만 non-null (TabContent publishHostChrome과 동일)
        val pendingDelete = pendingVideo()

        // When
        val enabled = isConvertedVideosChromeEnabled(
            pendingDelete = pendingDelete,
            pendingRename = null,
            pendingUpload = null,
        )

        // Then
        assertFalse(enabled)
    }

    @Test
    fun success_disabledWhenPendingRenameNonNull() {
        // Given
        val pendingRename = pendingVideo()

        // When
        val enabled = isConvertedVideosChromeEnabled(
            pendingDelete = null,
            pendingRename = pendingRename,
            pendingUpload = null,
        )

        // Then
        assertFalse(enabled)
    }

    @Test
    fun success_disabledWhenPendingUploadNonNull() {
        // Given
        val pendingUpload = pendingVideo()

        // When
        val enabled = isConvertedVideosChromeEnabled(
            pendingDelete = null,
            pendingRename = null,
            pendingUpload = pendingUpload,
        )

        // Then
        assertFalse(enabled)
    }

    @Test
    fun success_disabledWhenPendingBatchDeleteNonNull() {
        // Given
        val pendingBatchDelete = setOf("content://test/1")

        // When
        val enabled = isConvertedVideosChromeEnabled(
            pendingBatchDelete = pendingBatchDelete,
        )

        // Then
        assertFalse(enabled)
    }

    @Test
    fun success_disabledWhenPendingBatchUploadNonNull() {
        // Given
        val pendingBatchUpload = setOf("content://test/1")

        // When
        val enabled = isConvertedVideosChromeEnabled(
            pendingBatchUpload = pendingBatchUpload,
        )

        // Then
        assertFalse(enabled)
    }

    @Test
    fun success_disabledWhenTrackingBatchUploadNonNull() {
        // Given
        val trackingBatchUpload = setOf("content://test/1")

        // When
        val enabled = isConvertedVideosChromeEnabled(
            trackingBatchUpload = trackingBatchUpload,
        )

        // Then
        assertFalse(enabled)
    }

    @Test
    fun success_disabledWhenSelectionModeActive() {
        // Given
        val isSelectionMode = true

        // When
        val enabled = isConvertedVideosChromeEnabled(
            isSelectionMode = isSelectionMode,
        )

        // Then
        assertFalse(enabled)
    }

    @Test
    fun success_hasPendingDialog_nullBatchMeansNoDialog() {
        // Given — 운영 prune은 빈 키 집합을 null로 접음
        val pendingBatchDelete: Set<String>? = null

        // When
        val hasDialog = hasConvertedVideosPendingDialog(
            pendingBatchDelete = pendingBatchDelete,
        )

        // Then
        assertFalse(hasDialog)
        assertTrue(isConvertedVideosChromeEnabled(pendingBatchDelete = pendingBatchDelete))
    }

    @Test
    fun success_hasPendingDialog_emptySetStillCountsAsDialog() {
        // Given — emptySet은 운영 prune 경로에 없음(null로 접힘). 헬퍼는 != null.
        val pendingBatchDelete: Set<String>? = emptySet()

        // When
        val hasDialog = hasConvertedVideosPendingDialog(
            pendingBatchDelete = pendingBatchDelete,
        )

        // Then
        assertTrue(hasDialog)
        assertFalse(isConvertedVideosChromeEnabled(pendingBatchDelete = pendingBatchDelete))
    }

    @Test
    fun success_hasPendingDialog_emptySetStillCountsAsDialog_batchUpload() {
        // Given
        val pendingBatchUpload: Set<String>? = emptySet()

        // When
        val hasDialog = hasConvertedVideosPendingDialog(
            pendingBatchUpload = pendingBatchUpload,
        )

        // Then
        assertTrue(hasDialog)
        assertFalse(isConvertedVideosChromeEnabled(pendingBatchUpload = pendingBatchUpload))
    }

    @Test
    fun success_hasPendingDialog_emptySetStillCountsAsDialog_trackingUpload() {
        // Given
        val trackingBatchUpload: Set<String>? = emptySet()

        // When
        val hasDialog = hasConvertedVideosPendingDialog(
            trackingBatchUpload = trackingBatchUpload,
        )

        // Then
        assertTrue(hasDialog)
        assertFalse(isConvertedVideosChromeEnabled(trackingBatchUpload = trackingBatchUpload))
    }

    @Test
    fun success_chromeEnabled_isNotHasDialogAndNotSelection() {
        // Given — 항등: enabled == !hasDialog && !selection
        val pending = pendingVideo()

        // When / Then: !dialog && !selection → enabled
        assertEquals(
            !hasConvertedVideosPendingDialog() && !false,
            isConvertedVideosChromeEnabled(),
        )
        assertTrue(isConvertedVideosChromeEnabled())

        // When / Then: !dialog && selection → disabled
        assertEquals(
            !hasConvertedVideosPendingDialog() && !true,
            isConvertedVideosChromeEnabled(isSelectionMode = true),
        )
        assertFalse(isConvertedVideosChromeEnabled(isSelectionMode = true))

        // When / Then: dialog && !selection → disabled
        val hasDialog = hasConvertedVideosPendingDialog(pendingDelete = pending)
        val enabledDialogOnly = isConvertedVideosChromeEnabled(pendingDelete = pending)
        assertTrue(hasDialog)
        assertEquals(!hasDialog && !false, enabledDialogOnly)
        assertFalse(enabledDialogOnly)

        // When / Then: dialog && selection → disabled
        val enabledBoth = isConvertedVideosChromeEnabled(
            pendingDelete = pending,
            isSelectionMode = true,
        )
        assertEquals(
            !hasConvertedVideosPendingDialog(pendingDelete = pending) && !true,
            enabledBoth,
        )
        assertFalse(enabledBoth)
    }

    @Test
    fun success_selectionActionsEnabled_whenNonEmptyAndNoDialog() {
        // Given / When
        val enabled = areConvertedVideosSelectionActionsEnabled(
            selectedCount = 1,
            hasPendingDialog = false,
        )

        // Then
        assertTrue(enabled)
    }

    @Test
    fun success_selectionActionsDisabled_whenEmptySelection() {
        // Given / When
        val enabled = areConvertedVideosSelectionActionsEnabled(
            selectedCount = 0,
            hasPendingDialog = false,
        )

        // Then
        assertFalse(enabled)
    }

    @Test
    fun success_selectionActionsDisabled_whenPendingDialog() {
        // Given / When
        val enabled = areConvertedVideosSelectionActionsEnabled(
            selectedCount = 2,
            hasPendingDialog = true,
        )

        // Then
        assertFalse(enabled)
    }

    @Test
    fun success_selectionActionsDisabled_whenEmptySetCountsAsDialog() {
        // Given
        val hasDialog = hasConvertedVideosPendingDialog(pendingBatchUpload = emptySet())

        // When
        val enabled = areConvertedVideosSelectionActionsEnabled(
            selectedCount = 1,
            hasPendingDialog = hasDialog,
        )

        // Then
        assertTrue(hasDialog)
        assertFalse(enabled)
    }

    @Test
    fun success_prunePendingConvertedVideo_keepsWhenUriVisible() {
        // Given
        val pending = pendingVideo()
        val visibleUriKeys = setOf(pending.uri.toString())

        // When
        val next = prunePendingConvertedVideoIfAbsent(pending, visibleUriKeys)

        // Then
        assertEquals(pending, next)
    }

    @Test
    fun success_prunePendingConvertedVideo_clearsWhenUriMissing() {
        // Given
        val pending = pendingVideo()
        val visibleUriKeys = setOf("content://media/external/video/media/other")

        // When
        val next = prunePendingConvertedVideoIfAbsent(pending, visibleUriKeys)

        // Then
        assertNull(next)
    }

    @Test
    fun success_prunePendingConvertedVideo_nullStaysNull() {
        // Given
        val visibleUriKeys = setOf("content://media/external/video/media/1")

        // When
        val next = prunePendingConvertedVideoIfAbsent(null, visibleUriKeys)

        // Then
        assertNull(next)
    }

    @Test
    fun success_prunePendingBatchUriKeys_contentEqualNonEmptyUnchanged() {
        // Given
        val pending = setOf("content://a", "content://b")
        val visibleUriKeys = setOf("content://a", "content://b", "content://c")

        // When
        val next = prunePendingBatchUriKeys(pending, visibleUriKeys)

        // Then — 내용이 같고 비어 있지 않으면 setter 호출 금지(호출부 equals 가드)
        assertEquals(pending, next)
    }

    @Test
    fun success_prunePendingBatchUriKeys_dropsMissingKeys() {
        // Given
        val pending = setOf("content://a", "content://gone")
        val visibleUriKeys = setOf("content://a")

        // When
        val next = prunePendingBatchUriKeys(pending, visibleUriKeys)

        // Then
        assertEquals(setOf("content://a"), next)
    }

    @Test
    fun success_prunePendingBatchUriKeys_emptyVisibleCollapsesToNull() {
        // Given
        val pending = setOf("content://a")

        // When
        val next = prunePendingBatchUriKeys(pending, emptySet())

        // Then
        assertNull(next)
    }

    @Test
    fun success_prunePendingBatchUriKeys_nullStaysNull() {
        // Given / When
        val next = prunePendingBatchUriKeys(null, setOf("content://a"))

        // Then
        assertNull(next)
    }

    @Test
    fun success_trackingBatchUpload_keepsKeysAbsentFromVisibleSet() {
        // Given — enqueue 추적 키는 목록에 없어도 fallbackUriKeys로 유지
        val trackingKeys = setOf("content://media/external/video/media/tracking")
        val visibleUriKeys = setOf("content://media/external/video/media/other")

        // When
        val retained = retainTrackingBatchUploadKeys(trackingKeys)
        val ifTreatedAsPending = prunePendingBatchUriKeys(trackingKeys, visibleUriKeys)

        // Then
        assertEquals(trackingKeys, retained)
        assertTrue(trackingKeys.none { it in visibleUriKeys })
        assertNull(ifTreatedAsPending)
    }
}
