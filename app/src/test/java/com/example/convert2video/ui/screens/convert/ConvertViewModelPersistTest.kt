package com.example.convert2video.ui.screens.convert

import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * ConvertViewModel persist seam — 3-guard + catching + last-writer Job.
 * Does not construct [ConvertViewModel] (ctor / WorkManager 불변).
 */
class ConvertViewModelPersistTest {

    @Test
    fun failure_persistGuards_nullBackground_doesNotPass() {
        // Given / When / Then
        assertFalse(
            passesStartConversionPersistGuards(
                backgroundPath = null,
                audioItemCount = 1,
                isPreparingConversion = false,
            ),
        )
    }

    @Test
    fun failure_persistGuards_emptyAudio_doesNotPass() {
        // Given / When / Then
        assertFalse(
            passesStartConversionPersistGuards(
                backgroundPath = "/files/backgrounds/bg.jpg",
                audioItemCount = 0,
                isPreparingConversion = false,
            ),
        )
    }

    @Test
    fun failure_persistGuards_blankBackground_doesNotPass() {
        // Given / When / Then
        assertFalse(
            passesStartConversionPersistGuards(
                backgroundPath = "   ",
                audioItemCount = 1,
                isPreparingConversion = false,
            ),
        )
        assertFalse(
            passesStartConversionPersistGuards(
                backgroundPath = "",
                audioItemCount = 1,
                isPreparingConversion = false,
            ),
        )
    }

    @Test
    fun failure_persistGuards_preparing_doesNotPass() {
        // Given / When / Then
        assertFalse(
            passesStartConversionPersistGuards(
                backgroundPath = "/files/backgrounds/bg.jpg",
                audioItemCount = 1,
                isPreparingConversion = true,
            ),
        )
    }

    @Test
    fun success_persistGuards_ready_passes() {
        // Given / When / Then
        assertTrue(
            passesStartConversionPersistGuards(
                backgroundPath = "/files/backgrounds/bg.jpg",
                audioItemCount = 1,
                isPreparingConversion = false,
            ),
        )
    }

    @Test
    fun success_persistCatching_invokesPersist() = runTest {
        // Given
        var persisted: String? = null

        // When
        persistLastUsedBackgroundPathCatching("/files/backgrounds/bg.jpg") { path ->
            persisted = path
        }

        // Then
        assertEquals("/files/backgrounds/bg.jpg", persisted)
    }

    @Test
    fun failure_persistCatching_swallowsExceptionAndLogsSimpleNameWithoutThrowable() = runTest {
        // Given
        var capturedMsg: String? = null
        var capturedThrowable: Throwable? = null
        AppLogger.installPersistSink { _, _, msg, throwable ->
            capturedMsg = msg
            capturedThrowable = throwable
        }
        try {
            persistLastUsedBackgroundPathCatching("/files/backgrounds/bg.jpg") {
                throw IOException("disk at /files/backgrounds/secret.jpg")
            }
        } finally {
            AppLogger.installPersistSink { _, _, _, _ -> }
        }

        // Then — throwable not passed to ErrorLog; path tokens must not appear
        assertTrue(capturedThrowable == null)
        assertTrue(capturedMsg != null)
        val msg = capturedMsg!!
        assertTrue(msg.contains("IOException"))
        assertFalse(msg.contains("backgrounds/"))
        assertFalse(msg.contains("/files/"))
        assertFalse(msg.contains("secret.jpg"))
    }

    @Test
    fun exception_persistCatching_rethrowsCancellationException() = runTest {
        // Given / When / Then
        try {
            persistLastUsedBackgroundPathCatching("/files/backgrounds/bg.jpg") {
                throw CancellationException("persist-cancel")
            }
            fail("expected CancellationException")
        } catch (ce: CancellationException) {
            assertEquals("persist-cancel", ce.message)
        }
    }

    @Test
    fun success_launchPersist_lastWriterWinsAfterCancel() = runTest {
        // Given
        val mutex = Mutex()
        val persisted = mutableListOf<String>()
        val job1 = launchPersistLastUsedBackgroundPath(
            scope = this,
            mutex = mutex,
            previousJob = null,
            path = "first",
            persist = {
                delay(1_000)
                persisted += it
            },
        )

        // When — newer start cancels the in-flight persist
        val job2 = launchPersistLastUsedBackgroundPath(
            scope = this,
            mutex = mutex,
            previousJob = job1,
            path = "second",
            persist = { persisted += it },
        )
        job2.join()

        // Then
        assertEquals(listOf("second"), persisted)
        assertTrue(job1.isCancelled)
    }

    @Test
    fun success_launchPersist_joinCompletesBeforeNextWriter() = runTest {
        // Given
        val mutex = Mutex()
        var order = ""
        val persistJob = launchPersistLastUsedBackgroundPath(
            scope = this,
            mutex = mutex,
            previousJob = null,
            path = "p",
            persist = {
                delay(50)
                order += "p"
            },
        )
        val enqueue = async {
            persistJob.join()
            mutex.withLock {
                order += "e"
            }
        }

        // When
        enqueue.await()

        // Then — persist Job completes before a later mutex holder (join + mutex)
        assertEquals("pe", order)
        assertTrue(persistJob.isCompleted)
    }

    @Test
    fun failure_persistDecision_buttonTapBeforeEnqueue_doesNotPersist() {
        // Given / When / Then — enqueue not yet called (null) ≡ tap 직후
        assertFalse(
            shouldPersistLastUsedAfterStart(
                guardsPassed = true,
                segmentedSingleAudioRequired = false,
                enqueueSucceeded = null,
            ),
        )
    }

    @Test
    fun failure_persistDecision_guardsFailed_doesNotPersist() {
        // Given / When / Then
        assertFalse(
            shouldPersistLastUsedAfterStart(
                guardsPassed = false,
                segmentedSingleAudioRequired = false,
                enqueueSucceeded = true,
            ),
        )
    }

    @Test
    fun failure_persistDecision_singleAudioRequired_doesNotPersist() {
        // Given / When / Then
        assertFalse(
            shouldPersistLastUsedAfterStart(
                guardsPassed = true,
                segmentedSingleAudioRequired = true,
                enqueueSucceeded = null,
            ),
        )
        assertFalse(
            shouldPersistLastUsedAfterStart(
                guardsPassed = true,
                segmentedSingleAudioRequired = true,
                enqueueSucceeded = true,
            ),
        )
    }

    @Test
    fun failure_persistDecision_enqueueSkipped_doesNotPersist() {
        // Given / When / Then — SkipUnfinished / query-failed
        assertFalse(
            shouldPersistLastUsedAfterStart(
                guardsPassed = true,
                segmentedSingleAudioRequired = false,
                enqueueSucceeded = false,
            ),
        )
    }

    @Test
    fun success_persistDecision_enqueueSucceeded_persists() {
        // Given / When / Then
        assertTrue(
            shouldPersistLastUsedAfterStart(
                guardsPassed = true,
                segmentedSingleAudioRequired = false,
                enqueueSucceeded = true,
            ),
        )
    }

    @Test
    fun success_persistPipeline_onlyAfterEnqueueTrue() {
        // Given — tap → (no persist) → enqueue outcome → persist iff true
        fun pipelineEvents(enqueueSucceeded: Boolean?): List<String> {
            val events = mutableListOf("tap")
            if (shouldPersistLastUsedAfterStart(
                    guardsPassed = true,
                    segmentedSingleAudioRequired = false,
                    enqueueSucceeded = null,
                )
            ) {
                events += "persist"
            }
            events += "enqueue"
            if (shouldPersistLastUsedAfterStart(
                    guardsPassed = true,
                    segmentedSingleAudioRequired = false,
                    enqueueSucceeded = enqueueSucceeded,
                )
            ) {
                events += "persist"
            }
            return events
        }

        // When / Then
        assertEquals(listOf("tap", "enqueue", "persist"), pipelineEvents(true))
        assertEquals(listOf("tap", "enqueue"), pipelineEvents(false))
        assertEquals(listOf("tap", "enqueue"), pipelineEvents(null))
    }

    @Test
    fun failure_persistPipeline_singleAudioRequired_neverPersists() {
        // Given / When
        val events = mutableListOf("tap")
        if (shouldPersistLastUsedAfterStart(
                guardsPassed = true,
                segmentedSingleAudioRequired = true,
                enqueueSucceeded = null,
            )
        ) {
            events += "persist"
        }

        // Then
        assertEquals(listOf("tap"), events)
    }
}
