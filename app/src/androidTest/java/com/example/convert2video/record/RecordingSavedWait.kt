package com.example.convert2video.record

/**
 * androidTest Saved 경로 대기 SSOT — Service/Controller/Screen/VM 공통.
 * [RecordingErrorCodes.MIN_SAVE_DURATION_MS] + slack 이상 elapsed 후 stop.
 */
object RecordingSavedWait {
    const val SAVED_WAIT_SLACK_MS = 500L

    val minElapsedForSavedMs: Long =
        RecordingErrorCodes.MIN_SAVE_DURATION_MS + SAVED_WAIT_SLACK_MS

    /** ≥ max(minElapsed+5s, 10s) */
    val timeoutForSavedWaitMs: Long =
        maxOf(minElapsedForSavedMs + 5_000L, 10_000L)
}
