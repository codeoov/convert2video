package com.example.convert2video.ui.shared

import androidx.work.WorkInfo

/**
 * WorkInfo 4-state UI phase. [Active] = ENQUEUED | RUNNING | BLOCKED.
 *
 * Options `hasPendingConstrainedYouTubeUpload` must **not** use [Active]:
 * that check uses [WorkInfo.State.isPendingConstrained] (ENQUEUED || BLOCKED).
 * RUNNING is [Active] but is not pending (already executing, not waiting on a constraint).
 */
internal enum class WorkInfoUiPhase {
    Active,
    Succeeded,
    Failed,
    Cancelled,
}

internal fun WorkInfo.State.toWorkInfoUiPhase(): WorkInfoUiPhase = when (this) {
    WorkInfo.State.ENQUEUED,
    WorkInfo.State.RUNNING,
    WorkInfo.State.BLOCKED,
    -> WorkInfoUiPhase.Active
    WorkInfo.State.SUCCEEDED -> WorkInfoUiPhase.Succeeded
    WorkInfo.State.FAILED -> WorkInfoUiPhase.Failed
    WorkInfo.State.CANCELLED -> WorkInfoUiPhase.Cancelled
}

internal fun WorkInfo.toWorkInfoUiPhase(): WorkInfoUiPhase = state.toWorkInfoUiPhase()

internal fun WorkInfo.isRunning(): Boolean = state == WorkInfo.State.RUNNING

internal fun WorkInfo.State.isPendingConstrained(): Boolean =
    this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.BLOCKED
