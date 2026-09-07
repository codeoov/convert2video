package com.example.convert2video.ui.shared

import androidx.work.Data
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WorkInfoUiPhaseTest {

    private fun workInfo(state: WorkInfo.State): WorkInfo =
        WorkInfo(UUID.randomUUID(), state, emptySet(), Data.EMPTY, Data.EMPTY)

    @Test
    fun success_enqueuedRunningBlockedMapToActive() {
        // Given / When / Then
        assertEquals(WorkInfoUiPhase.Active, WorkInfo.State.ENQUEUED.toWorkInfoUiPhase())
        assertEquals(WorkInfoUiPhase.Active, WorkInfo.State.RUNNING.toWorkInfoUiPhase())
        assertEquals(WorkInfoUiPhase.Active, WorkInfo.State.BLOCKED.toWorkInfoUiPhase())
        assertEquals(WorkInfoUiPhase.Active, workInfo(WorkInfo.State.ENQUEUED).toWorkInfoUiPhase())
        assertEquals(WorkInfoUiPhase.Active, workInfo(WorkInfo.State.RUNNING).toWorkInfoUiPhase())
        assertEquals(WorkInfoUiPhase.Active, workInfo(WorkInfo.State.BLOCKED).toWorkInfoUiPhase())
    }

    @Test
    fun success_succeededMapsToSucceeded() {
        // Given / When / Then
        assertEquals(WorkInfoUiPhase.Succeeded, WorkInfo.State.SUCCEEDED.toWorkInfoUiPhase())
        assertEquals(WorkInfoUiPhase.Succeeded, workInfo(WorkInfo.State.SUCCEEDED).toWorkInfoUiPhase())
    }

    @Test
    fun success_failedMapsToFailed() {
        // Given / When / Then
        assertEquals(WorkInfoUiPhase.Failed, WorkInfo.State.FAILED.toWorkInfoUiPhase())
        assertEquals(WorkInfoUiPhase.Failed, workInfo(WorkInfo.State.FAILED).toWorkInfoUiPhase())
    }

    @Test
    fun success_cancelledMapsToCancelled() {
        // Given / When / Then
        assertEquals(WorkInfoUiPhase.Cancelled, WorkInfo.State.CANCELLED.toWorkInfoUiPhase())
        assertEquals(WorkInfoUiPhase.Cancelled, workInfo(WorkInfo.State.CANCELLED).toWorkInfoUiPhase())
    }

    @Test
    fun success_runningIsActiveButNotPendingConstrained() {
        // Given / When / Then
        // WorkInfoUiPhase.Active includes RUNNING; Options hasPendingConstrainedYouTubeUpload
        // stays ENQUEUED||BLOCKED and must not use Active (RUNNING is already executing).
        assertEquals(WorkInfoUiPhase.Active, WorkInfo.State.RUNNING.toWorkInfoUiPhase())
        assertFalse(WorkInfo.State.RUNNING.isPendingConstrained())
        assertTrue(WorkInfo.State.ENQUEUED.isPendingConstrained())
        assertTrue(WorkInfo.State.BLOCKED.isPendingConstrained())
        assertEquals(WorkInfoUiPhase.Active, WorkInfo.State.ENQUEUED.toWorkInfoUiPhase())
        assertEquals(WorkInfoUiPhase.Active, WorkInfo.State.BLOCKED.toWorkInfoUiPhase())
    }

    @Test
    fun success_isRunningOnlyForRunningState() {
        // Given / When / Then
        assertTrue(workInfo(WorkInfo.State.RUNNING).isRunning())
        assertFalse(workInfo(WorkInfo.State.ENQUEUED).isRunning())
        assertFalse(workInfo(WorkInfo.State.BLOCKED).isRunning())
        assertFalse(workInfo(WorkInfo.State.SUCCEEDED).isRunning())
        assertFalse(workInfo(WorkInfo.State.FAILED).isRunning())
        assertFalse(workInfo(WorkInfo.State.CANCELLED).isRunning())
    }
}
