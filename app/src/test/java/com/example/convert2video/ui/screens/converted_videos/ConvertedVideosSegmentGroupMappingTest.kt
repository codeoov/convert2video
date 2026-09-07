package com.example.convert2video.ui.screens.converted_videos

import com.example.convert2video.data.ConversionRecord
import com.example.convert2video.data.UploadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertedVideosSegmentGroupMappingTest {

    private fun media(
        uri: String,
        name: String,
        dateAdded: Long,
    ) = ConvertedVideoMediaRef(
        uriString = uri,
        displayName = name,
        dateAdded = dateAdded,
    )

    private fun conversion(
        videoUri: String,
        batchId: String?,
        index: Int?,
        total: Int?,
        createdAt: Long = 1_000L,
        audioUri: String = "content://audio/1",
    ) = ConversionRecord(
        id = 0,
        audioUri = audioUri,
        videoUri = videoUri,
        createdAt = createdAt,
        segmentBatchId = batchId,
        segmentIndex = index,
        segmentTotal = total,
    )

    private fun upload(
        videoUri: String,
        uploadedAt: Long = 2_000L,
        id: Long = 0,
    ) = UploadRecord(
        id = id,
        videoUri = videoUri,
        youtubeVideoId = "yt_$uploadedAt",
        watchUrl = "https://youtu.be/yt_$uploadedAt",
        uploadedAt = uploadedAt,
    )

    // Given / When / Then

    @Test
    fun success_groupsSameBatchId_childrenSortedBySegmentIndexAsc() {
        // Given: 동일 batch, index 순서 뒤섞임
        val media = listOf(
            media("content://v/3", "c.mp4", dateAdded = 30),
            media("content://v/1", "a.mp4", dateAdded = 10),
            media("content://v/2", "b.mp4", dateAdded = 20),
        )
        val conversions = listOf(
            conversion("content://v/3", "batch-a", 3, 3),
            conversion("content://v/1", "batch-a", 1, 3),
            conversion("content://v/2", "batch-a", 2, 3),
        )

        // When
        val rows = mapConvertedVideosToSegmentRows(media, conversions, uploads = emptyList())

        // Then
        assertEquals(1, rows.size)
        val group = rows.single() as ConvertedVideosSegmentRow.Group
        assertEquals("batch-a", group.segmentBatchId)
        assertEquals(3, group.segmentCount)
        assertEquals(
            listOf("content://v/1", "content://v/2", "content://v/3"),
            group.children.map { it.uriString },
        )
        assertEquals(listOf(1, 2, 3), group.children.map { it.segmentIndex })
        // 대표 제목 = index ASC 첫 자식
        assertEquals("a.mp4", group.title)
    }

    @Test
    fun success_nullOrUnmatchedBatchBecomesStandalone() {
        // Given: 이력 없음 + batch null
        val media = listOf(
            media("content://v/alone", "alone.mp4", dateAdded = 5),
            media("content://v/legacy", "legacy.mp4", dateAdded = 9),
        )
        val conversions = listOf(
            conversion("content://v/alone", batchId = null, index = null, total = null),
        )

        // When
        val rows = mapConvertedVideosToSegmentRows(media, conversions, uploads = emptyList())

        // Then: 둘 다 standalone, dateAdded DESC
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is ConvertedVideosSegmentRow.Standalone })
        val uris = rows.map { (it as ConvertedVideosSegmentRow.Standalone).item.uriString }
        assertEquals(listOf("content://v/legacy", "content://v/alone"), uris)
    }

    @Test
    fun success_blankBatchIdBecomesStandalone() {
        // Given: blank batchId는 그룹 금지
        val media = listOf(
            media("content://v/blank", "blank.mp4", dateAdded = 1),
            media("content://v/ws", "ws.mp4", dateAdded = 2),
        )
        val conversions = listOf(
            conversion("content://v/blank", batchId = "", index = 1, total = 1),
            conversion("content://v/ws", batchId = "   ", index = 1, total = 1),
        )

        // When
        val rows = mapConvertedVideosToSegmentRows(media, conversions, uploads = emptyList())

        // Then
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is ConvertedVideosSegmentRow.Standalone })
    }

    @Test
    fun success_nullSegmentIndexSortsLast() {
        // Given: null index는 Int.MAX_VALUE로 맨 뒤
        val media = listOf(
            media("content://v/null", "n.mp4", dateAdded = 1),
            media("content://v/2", "b.mp4", dateAdded = 2),
            media("content://v/1", "a.mp4", dateAdded = 3),
        )
        val conversions = listOf(
            conversion("content://v/null", "batch-n", index = null, total = 3),
            conversion("content://v/2", "batch-n", index = 2, total = 3),
            conversion("content://v/1", "batch-n", index = 1, total = 3),
        )

        // When
        val group = mapConvertedVideosToSegmentRows(media, conversions, uploads = emptyList())
            .single() as ConvertedVideosSegmentRow.Group

        // Then
        assertEquals(
            listOf("content://v/1", "content://v/2", "content://v/null"),
            group.children.map { it.uriString },
        )
        assertEquals(listOf(1, 2, null), group.children.map { it.segmentIndex })
    }

    @Test
    fun success_uploadRecordJoinedByUri_latestKept() {
        // Given: 동일 URI 업로드 2건 — 목록 앞쪽이 최신
        val media = listOf(media("content://v/1", "a.mp4", dateAdded = 1))
        val uploads = listOf(
            upload("content://v/1", uploadedAt = 200),
            upload("content://v/1", uploadedAt = 100),
        )

        // When
        val rows = mapConvertedVideosToSegmentRows(
            media,
            conversions = emptyList(),
            uploads = uploads,
        )

        // Then
        val item = (rows.single() as ConvertedVideosSegmentRow.Standalone).item
        assertEquals(200L, item.uploadRecord?.uploadedAt)
        assertEquals("https://youtu.be/yt_200", item.uploadRecord?.watchUrl)
    }

    @Test
    fun success_uploadRecordJoinedByUri_maxByUploadedAt_reverseInputOrder() {
        // Given: 오래된 것이 앞에 와도 uploadedAt maxBy
        val media = listOf(media("content://v/1", "a.mp4", dateAdded = 1))
        val uploads = listOf(
            upload("content://v/1", uploadedAt = 50),
            upload("content://v/1", uploadedAt = 300),
            upload("content://v/1", uploadedAt = 120),
        )

        // When
        val item = mapConvertedVideosToSegmentRows(
            media,
            conversions = emptyList(),
            uploads = uploads,
        ).single() as ConvertedVideosSegmentRow.Standalone

        // Then
        assertEquals(300L, item.item.uploadRecord?.uploadedAt)
        assertEquals("https://youtu.be/yt_300", item.item.uploadRecord?.watchUrl)
    }

    @Test
    fun success_roomOnlyOrphanDoesNotCreateRow() {
        // Given: Room에만 있는 세그먼트 이력, 미디어 없음
        val conversions = listOf(
            conversion("content://v/missing", "batch-x", 1, 2),
            conversion("content://v/missing2", "batch-x", 2, 2),
        )

        // When
        val rows = mapConvertedVideosToSegmentRows(
            media = emptyList(),
            conversions = conversions,
            uploads = emptyList(),
        )

        // Then: 빈 그룹 헤더 금지
        assertTrue(rows.isEmpty())
    }

    @Test
    fun success_partialBatch_groupsOnlyExistingMedia_matchedCountOnly() {
        // Given: batch 3개 중 미디어 2개만 존재 — 허수 total=3 표시 금지
        val media = listOf(
            media("content://v/1", "a.mp4", dateAdded = 10),
            media("content://v/3", "c.mp4", dateAdded = 30),
        )
        val conversions = listOf(
            conversion("content://v/1", "batch-p", 1, 3),
            conversion("content://v/2", "batch-p", 2, 3),
            conversion("content://v/3", "batch-p", 3, 3),
        )

        // When
        val rows = mapConvertedVideosToSegmentRows(media, conversions, uploads = emptyList())

        // Then
        val group = rows.single() as ConvertedVideosSegmentRow.Group
        assertEquals(listOf("content://v/1", "content://v/3"), group.children.map { it.uriString })
        assertEquals(2, group.segmentCount)
    }

    @Test
    fun success_mixedGroupAndStandalone_sortedByDateAddedDesc() {
        // Given: 그룹(max date=50) + standalone(date=40)
        val media = listOf(
            media("content://v/g1", "g1.mp4", dateAdded = 10),
            media("content://v/g2", "g2.mp4", dateAdded = 50),
            media("content://v/s", "s.mp4", dateAdded = 40),
        )
        val conversions = listOf(
            conversion("content://v/g1", "batch-m", 1, 2),
            conversion("content://v/g2", "batch-m", 2, 2),
        )

        // When
        val rows = mapConvertedVideosToSegmentRows(media, conversions, uploads = emptyList())

        // Then
        assertEquals(2, rows.size)
        assertTrue(rows[0] is ConvertedVideosSegmentRow.Group)
        assertTrue(rows[1] is ConvertedVideosSegmentRow.Standalone)
        assertEquals(
            "content://v/s",
            (rows[1] as ConvertedVideosSegmentRow.Standalone).item.uriString,
        )
    }

    @Test
    fun success_childUploadBadgePreservedInsideGroup() {
        // Given
        val media = listOf(
            media("content://v/1", "a.mp4", dateAdded = 1),
            media("content://v/2", "b.mp4", dateAdded = 2),
        )
        val conversions = listOf(
            conversion("content://v/1", "batch-u", 1, 2),
            conversion("content://v/2", "batch-u", 2, 2),
        )
        val uploads = listOf(upload("content://v/2", uploadedAt = 99))

        // When
        val group = mapConvertedVideosToSegmentRows(media, conversions, uploads)
            .single() as ConvertedVideosSegmentRow.Group

        // Then
        assertNull(group.children[0].uploadRecord)
        assertEquals(99L, group.children[1].uploadRecord?.uploadedAt)
    }

    @Test
    fun failure_emptyMediaWithUploadsOnly_yieldsNoRows() {
        // Given: 업로드·변환만 있고 미디어 없음
        val uploads = listOf(upload("content://v/ghost"))
        val conversions = listOf(conversion("content://v/ghost", "b", 1, 1))

        // When
        val rows = mapConvertedVideosToSegmentRows(
            media = emptyList(),
            conversions = conversions,
            uploads = uploads,
        )

        // Then
        assertTrue(rows.isEmpty())
    }

    @Test
    fun success_resolveSegmentCount_alwaysMatchedChildrenSize() {
        // Given: segmentTotal=4여도 매칭 2건이면 2
        val children = listOf(
            ConvertedVideoJoinedRef(
                uriString = "a",
                displayName = "a",
                dateAdded = 1,
                uploadRecord = null,
                segmentBatchId = "b",
                segmentIndex = 1,
                segmentTotal = 4,
                audioUri = null,
            ),
            ConvertedVideoJoinedRef(
                uriString = "b",
                displayName = "b",
                dateAdded = 2,
                uploadRecord = null,
                segmentBatchId = "b",
                segmentIndex = 2,
                segmentTotal = 4,
                audioUri = null,
            ),
        )

        // When / Then
        assertEquals(2, resolveSegmentCount(children))
    }

    @Test
    fun success_resolveSegmentCount_fallsBackToMatchedSizeWhenTotalsDisagree() {
        // Given
        val children = listOf(
            ConvertedVideoJoinedRef(
                uriString = "a",
                displayName = "a",
                dateAdded = 1,
                uploadRecord = null,
                segmentBatchId = "b",
                segmentIndex = 1,
                segmentTotal = 3,
                audioUri = null,
            ),
            ConvertedVideoJoinedRef(
                uriString = "b",
                displayName = "b",
                dateAdded = 2,
                uploadRecord = null,
                segmentBatchId = "b",
                segmentIndex = 2,
                segmentTotal = 5,
                audioUri = null,
            ),
        )

        // When / Then
        assertEquals(2, resolveSegmentCount(children))
    }

    @Test
    fun success_resolvePromotedSegmentCount_ignoresMappedMismatch_usesChildrenSize() {
        // Given: 매핑 segmentCount=5, 승격 후 children 2건만 남음
        val mappedSegmentCount = 5
        val childrenSize = 2

        // When / Then: 승격 시 항상 children.size
        assertEquals(
            2,
            resolvePromotedSegmentCount(mappedSegmentCount, childrenSize),
        )
        assertTrue(mappedSegmentCount != childrenSize)
    }

    // --- Selection helpers ---

    @Test
    fun success_syncSelectionWithVisibleKeys_intersectsOnly() {
        // Given
        val selected = setOf("a", "b", "gone")
        val visible = setOf("a", "b", "c")

        // When / Then
        assertEquals(setOf("a", "b"), syncSelectionWithVisibleKeys(selected, visible))
    }

    @Test
    fun success_applyGroupSelectAll_unionsWhenNotAllSelected() {
        // Given: 일부만 선택
        val current = setOf("x", "a")
        val children = setOf("a", "b")

        // When / Then: union
        assertEquals(setOf("x", "a", "b"), applyGroupSelectAll(current, children))
    }

    @Test
    fun success_applyGroupSelectAll_togglesOffWhenAllSelected() {
        // Given: 자식 전부 선택됨
        val current = setOf("x", "a", "b")
        val children = setOf("a", "b")

        // When / Then: 자식만 해제, x 유지
        assertEquals(setOf("x"), applyGroupSelectAll(current, children))
    }

    @Test
    fun success_applyGroupSelectAll_emptyChildrenNoop() {
        // Given / When / Then
        assertEquals(setOf("a"), applyGroupSelectAll(setOf("a"), emptySet()))
    }

    @Test
    fun success_applyGroupSelectAllWithExpandPolicy_selectsAndExpandsBatch() {
        // Given: 접힌 그룹, 자식 미선택
        val current = setOf("x")
        val children = setOf("a", "b")
        val expanded = emptySet<String>()

        // When: union → 해당 batch 자동 expand
        val (nextSel, nextExp) = applyGroupSelectAllWithExpandPolicy(
            currentSelection = current,
            childKeys = children,
            batchId = "batch-a",
            expandedBatchIds = expanded,
        )

        // Then
        assertEquals(setOf("x", "a", "b"), nextSel)
        assertEquals(setOf("batch-a"), nextExp)
    }

    @Test
    fun success_applyGroupSelectAllWithExpandPolicy_deselectKeepsExpanded() {
        // Given: 자식 전부 선택 + 이미 expand
        val current = setOf("a", "b")
        val children = setOf("a", "b")
        val expanded = setOf("batch-a")

        // When: toggle-off — expand 유지
        val (nextSel, nextExp) = applyGroupSelectAllWithExpandPolicy(
            currentSelection = current,
            childKeys = children,
            batchId = "batch-a",
            expandedBatchIds = expanded,
        )

        // Then
        assertEquals(emptySet<String>(), nextSel)
        assertEquals(setOf("batch-a"), nextExp)
    }

    @Test
    fun success_applyGroupSelectAllWithExpandPolicy_emptyChildrenNoop() {
        // Given / When
        val (nextSel, nextExp) = applyGroupSelectAllWithExpandPolicy(
            currentSelection = setOf("a"),
            childKeys = emptySet(),
            batchId = "batch-a",
            expandedBatchIds = emptySet(),
        )

        // Then
        assertEquals(setOf("a"), nextSel)
        assertEquals(emptySet<String>(), nextExp)
    }

    @Test
    fun success_pruneExpandedSegmentBatchIds_intersectsCurrentGroupsOnly() {
        // Given: 사라진 batch-gone은 prune
        val expanded = setOf("batch-a", "batch-gone")
        val current = setOf("batch-a", "batch-b")

        // When / Then
        assertEquals(
            setOf("batch-a"),
            pruneExpandedSegmentBatchIds(expanded, current),
        )
    }

    @Test
    fun success_pickLatestUploadRecord_tieBreaksByIdDesc() {
        // Given: uploadedAt 동점, id가 큰 쪽이 최신
        val olderId = upload("content://v/1", uploadedAt = 100, id = 1)
        val newerId = upload("content://v/1", uploadedAt = 100, id = 9)

        // When / Then
        assertEquals(9L, pickLatestUploadRecord(olderId, newerId).id)
        assertEquals(9L, pickLatestUploadRecord(newerId, olderId).id)
    }

    @Test
    fun success_uploadRecordJoinedByUri_tieBreaksByIdDesc_reverseInputOrder() {
        // Given: uploadedAt 동일, 입력 순서가 작→큰 id여도 id DESC
        val media = listOf(media("content://v/1", "a.mp4", dateAdded = 1))
        val uploads = listOf(
            upload("content://v/1", uploadedAt = 100, id = 3),
            upload("content://v/1", uploadedAt = 100, id = 7),
            upload("content://v/1", uploadedAt = 100, id = 5),
        )

        // When
        val item = mapConvertedVideosToSegmentRows(
            media,
            conversions = emptyList(),
            uploads = uploads,
        ).single() as ConvertedVideosSegmentRow.Standalone

        // Then
        assertEquals(7L, item.item.uploadRecord?.id)
        assertEquals(100L, item.item.uploadRecord?.uploadedAt)
    }

    @Test
    fun success_resolveDialogSelectionCount_matchesVisibleOnly() {
        // Given: 요청 3, 실제 목록 2
        val requested = setOf("a", "b", "gone")
        val visible = setOf("a", "b")

        // When / Then
        assertEquals(2, resolveDialogSelectionCount(requested, visible))
    }
}
