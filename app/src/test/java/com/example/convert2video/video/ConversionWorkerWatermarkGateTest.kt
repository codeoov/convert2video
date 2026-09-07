package com.example.convert2video.video

import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversionWorkerWatermarkGateTest {

    @Test
    fun success_withEntitlementSnapshot_proMapsToWatermarkDisabled() = runTest {
        var blockCalls = 0

        val watermarkEnabled = withEntitlementSnapshot(
            readProSnapshot = { true },
        ) { isPro ->
            blockCalls += 1
            !isPro
        }

        assertFalse(watermarkEnabled)
        assertEquals(1, blockCalls)
    }

    @Test
    fun success_withEntitlementSnapshot_freeMapsToWatermarkEnabled() = runTest {
        val watermarkEnabled = withEntitlementSnapshot(
            readProSnapshot = { false },
        ) { isPro -> !isPro }

        assertTrue(watermarkEnabled)
    }

    @Test
    fun exception_withEntitlementSnapshot_readerFailureFallsBackToFreeAndLogs() = runTest {
        val logged = mutableListOf<Throwable?>()
        AppLogger.installPersistSink { level, _, _, throwable ->
            if (level == "E") logged += throwable
        }
        try {
            var blockCalls = 0
            val watermarkEnabled = withEntitlementSnapshot(
                readProSnapshot = { throw IllegalStateException("reader failed") },
            ) { isPro ->
                blockCalls += 1
                !isPro
            }

            assertTrue(watermarkEnabled)
            assertEquals(1, blockCalls)
            assertEquals(1, logged.size)
            assertTrue(logged.single() is IllegalStateException)
        } finally {
            AppLogger.clearPersistSinkForTests()
        }
    }

    @Test
    fun exception_withEntitlementSnapshot_cancellationRethrowsWithoutBlockOrLog() = runTest {
        val logged = mutableListOf<Throwable?>()
        AppLogger.installPersistSink { _, _, _, throwable -> logged += throwable }
        try {
            var blockCalls = 0
            val cancellation = CancellationException("cancelled")
            val thrown = try {
                withEntitlementSnapshot(
                    readProSnapshot = { throw cancellation },
                ) {
                    blockCalls += 1
                    Unit
                }
                null
            } catch (error: CancellationException) {
                error
            }

            assertSame(cancellation, thrown)
            assertEquals(0, blockCalls)
            assertTrue(logged.isEmpty())
        } finally {
            AppLogger.clearPersistSinkForTests()
        }
    }

    @Test
    fun success_withEntitlementSnapshot_readsAndExecutesBlockExactlyOnce() = runTest {
        var readerCalls = 0
        var blockCalls = 0

        withEntitlementSnapshot(
            readProSnapshot = {
                readerCalls += 1
                true
            },
        ) {
            blockCalls += 1
            Unit
        }

        assertEquals(1, readerCalls)
        assertEquals(1, blockCalls)
    }

    @Test
    fun exception_withEntitlementSnapshot_blockFailurePropagates_fromHelperContractOnly() = runTest {
        // Given — this verifies only the helper Contract; doWork's outer soft-fail wrapper is not
        // involved and must not be inferred from this test.
        val expected = IllegalStateException("block failed")

        // When / Then
        val thrown = try {
            withEntitlementSnapshot(readProSnapshot = { true }) {
                throw expected
            }
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertSame(expected, thrown)
    }

    @Test
    fun success_withEntitlementSnapshot_freezesInitialSnapshotBeforeEntitlementChanges() = runTest {
        // Given — the backing entitlement changes only after the reader returns.
        var backingIsPro = true
        var readerCalls = 0
        var blockCalls = 0

        // When
        val observedSnapshot = withEntitlementSnapshot(
            readProSnapshot = {
                readerCalls += 1
                backingIsPro
            },
        ) { isPro ->
            backingIsPro = false
            blockCalls += 1
            isPro
        }

        // Then — the block receives the initial frozen value exactly once.
        assertTrue(observedSnapshot)
        assertFalse(backingIsPro)
        assertEquals(1, readerCalls)
        assertEquals(1, blockCalls)
    }

    @Test
    fun success_full_proDisablesWatermark() = runTest {
        // Given / When — full-file branch with a Pro snapshot
        val call = simulateConvertBranch(isPro = true, isSegment = false)

        // Then
        assertEquals(SimulatedBranch.FULL, call.branch)
        assertFalse(call.applyWatermark)
    }

    @Test
    fun success_full_freeEnablesWatermark() = runTest {
        // Given / When — full-file branch with a Free snapshot
        val call = simulateConvertBranch(isPro = false, isSegment = false)

        // Then
        assertEquals(SimulatedBranch.FULL, call.branch)
        assertTrue(call.applyWatermark)
    }

    @Test
    fun success_segment_proDisablesWatermark() = runTest {
        // Given / When — segment branch with a Pro snapshot
        val call = simulateConvertBranch(isPro = true, isSegment = true)

        // Then
        assertEquals(SimulatedBranch.SEGMENT, call.branch)
        assertFalse(call.applyWatermark)
    }

    @Test
    fun success_segment_freeEnablesWatermark() = runTest {
        // Given / When — segment branch with a Free snapshot
        val call = simulateConvertBranch(isPro = false, isSegment = true)

        // Then
        assertEquals(SimulatedBranch.SEGMENT, call.branch)
        assertTrue(call.applyWatermark)
    }

    @Test
    fun success_sourceStructure_hasBothConvertCallsWithFrozenWatermarkGate() {
        val source = conversionWorkerSource()

        assertEquals(2, Regex("videoConverter\\.convert\\(").findAll(source).count())
        assertEquals(
            1,
            Regex("EntitlementRepository\\.getInstance\\(applicationContext\\)\\.isProSnapshot\\(\\)")
                .findAll(source)
                .count(),
        )

        val branchStart = source.indexOf("if (segmentKeys.range != null) {")
        val branchOpen = source.indexOf('{', branchStart)
        val branchClose = matchingBrace(source, branchOpen)
        val elseMarker = source.indexOf("} else {", branchClose)
        val fullOpen = elseMarker + "} else ".length
        val fullClose = matchingBrace(source, fullOpen)
        val segmentBranch = source.substring(branchOpen + 1, branchClose)
        val fullBranch = source.substring(fullOpen + 1, fullClose)

        assertEquals(1, Regex("videoConverter\\.convert\\(").findAll(segmentBranch).count())
        assertTrue(segmentBranch.contains("applyWatermark = !isPro"))
        assertEquals(1, Regex("videoConverter\\.convert\\(").findAll(fullBranch).count())
        assertTrue(fullBranch.contains("applyWatermark = !isPro"))
    }

    @Test
    fun success_sourceStructure_snapshotsAfterValidationAndBeforeFirstConvert() {
        val source = conversionWorkerSource()
        val backgroundValidation = source.indexOf("inputData.getString(KEY_BACKGROUND_PATH)")
        val backgroundFailure = source.indexOf(
            "R.string.conversion_error_background_not_found",
            backgroundValidation,
        )
        val audioValidation = source.indexOf("inputData.getString(KEY_AUDIO_URI)", backgroundFailure)
        val audioFailure = source.indexOf(
            "R.string.conversion_error_audio_uri_not_found",
            audioValidation,
        )
        val segmentValidation = source.indexOf("resolveSegmentKeys(inputData)", audioFailure)
        val segmentFailure = source.indexOf(
            "R.string.conversion_error_segment_input_invalid",
            segmentValidation,
        )
        val durationValidation = source.indexOf("audioDurationUsOrNull(audioUri)", segmentFailure)
        val durationFailure = source.indexOf(
            "R.string.conversion_error_audio_unreadable",
            durationValidation,
        )
        val rangeValidation = source.indexOf(
            "validateResolvedRangeForConvert(segmentKeys.range, durationUs)",
            durationFailure,
        )
        val rangeFailure = source.indexOf("if (rangeGuardError != null)", rangeValidation)
        val rangeFailureReturn = source.indexOf(
            "return failWithNotification(segmentErrorToStringRes(rangeGuardError))",
            rangeFailure,
        )
        val snapshot = source.indexOf(
            "readProSnapshot = { EntitlementRepository.getInstance(applicationContext)",
        )
        val firstConvert = source.indexOf("videoConverter.convert(")

        assertTrue(backgroundValidation >= 0)
        assertTrue(backgroundFailure > backgroundValidation)
        assertTrue(audioValidation > backgroundFailure)
        assertTrue(audioFailure > audioValidation)
        assertTrue(source.substring(audioValidation, audioFailure).contains("takeIf { it.isNotBlank() }"))
        assertTrue(source.substring(audioValidation, audioFailure).contains("let(Uri::parse)"))
        assertTrue(segmentValidation > audioFailure)
        assertTrue(segmentFailure > segmentValidation)
        assertTrue(durationValidation > segmentFailure)
        assertTrue(durationFailure > durationValidation)
        assertTrue(rangeValidation > durationFailure)
        assertTrue(rangeFailure > rangeValidation)
        assertTrue(rangeFailureReturn > rangeFailure)
        assertTrue(snapshot > rangeFailure)
        assertTrue(snapshot > rangeFailureReturn)
        assertTrue(firstConvert > snapshot)

        // Source-structure evidence is used because concrete Worker/VideoConverter dependencies
        // cannot be injected here. No reader/block seam is entered until every current doWork
        // guard has returned normally, so validation failures have zero convert calls.
        assertEquals(2, Regex("withEntitlementSnapshot\\(").findAll(source).count())
        assertEquals(0, Regex("videoConverter\\.convert\\(").findAll(source.substring(0, snapshot)).count())
        val validationGuards = source.substring(backgroundValidation, snapshot)
        assertTrue(validationGuards.contains("return failWithNotification"))
    }

    private fun conversionWorkerSource(): String {
        val relativePath = "app/src/main/java/com/example/convert2video/video/ConversionWorker.kt"
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: break
        }
        error("ConversionWorker.kt source was not found from ${System.getProperty("user.dir")}")
    }

    private suspend fun simulateConvertBranch(
        isPro: Boolean,
        isSegment: Boolean,
    ): SimulatedConvertCall =
        withEntitlementSnapshot(
            readProSnapshot = { isPro },
        ) { frozenIsPro ->
            if (isSegment) {
                SimulatedConvertCall(
                    branch = SimulatedBranch.SEGMENT,
                    applyWatermark = !frozenIsPro,
                )
            } else {
                SimulatedConvertCall(
                    branch = SimulatedBranch.FULL,
                    applyWatermark = !frozenIsPro,
                )
            }
        }

    private data class SimulatedConvertCall(
        val branch: SimulatedBranch,
        val applyWatermark: Boolean,
    )

    private enum class SimulatedBranch {
        FULL,
        SEGMENT,
    }

    private fun matchingBrace(source: String, openIndex: Int): Int {
        assertTrue(openIndex >= 0)
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        error("Unbalanced braces in ConversionWorker source")
    }
}
