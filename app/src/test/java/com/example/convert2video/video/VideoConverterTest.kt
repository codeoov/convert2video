package com.example.convert2video.video

import android.content.Context
import android.net.Uri
import com.example.convert2video.R
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** JVM coverage for the Media3 lifecycle through [VideoConverter]'s internal platform seam. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class VideoConverterTest {

    @Test
    fun success_validationFailure_attemptsOneDoneWithoutCreatingExport() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val platform = FakePlatform()
            val events = VideoConverter(ApplicationProvider.getApplicationContext(), platform)
                .convert(
                    backgroundImageFile = File("background.jpg"),
                    audioUri = Uri.parse("file:///audio.wav"),
                    audioDurationUs = 1_000_000L,
                    outputFile = File("output.mp4"),
                    segmentStartUs = 900_000L,
                    segmentEndUs = 100_000L,
                    applyWatermark = false,
                )
                .toList()

            assertEquals(1, events.count { it is ConversionEvent.Done })
            assertEquals(1, platform.terminalAttempts.size)
            assertTrue(platform.terminalAttempts.single() is ConversionResult.Failure)
            assertEquals(0, platform.createCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun success_exportCompletion_attemptsOneDone_andCleansWatermark() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val platform = FakePlatform(useTemporaryWatermark = true)
            val outputFile = temporaryFile("success")
            val job = async {
                VideoConverter(testContext(), platform).convert(
                    backgroundImageFile = File("background.jpg"),
                    audioUri = Uri.parse("file:///audio.wav"),
                    audioDurationUs = 1_000_000L,
                    outputFile = outputFile,
                    applyWatermark = true,
                ).toList()
            }
            runCurrent()
            assertNotNull(platform.export)
            platform.export!!.complete(outputFile)
            runCurrent()
            val events = job.await()

            assertEquals(1, events.count { it is ConversionEvent.Done })
            assertEquals(1, platform.terminalAttempts.size)
            assertTrue(platform.terminalAttempts.single() is ConversionResult.Success)
            assertTrue(outputFile.isFile && outputFile.length() > 0L)
            assertFalse(platform.watermarkFile!!.exists())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun success_exportError_attemptsOneFailureDone_andDeletesPartialOutputAndWatermark() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val platform = FakePlatform(useTemporaryWatermark = true)
            val outputFile = temporaryFile("error")
            val job = async {
                VideoConverter(testContext(), platform).convert(
                    backgroundImageFile = File("background.jpg"),
                    audioUri = Uri.parse("file:///audio.wav"),
                    audioDurationUs = 1_000_000L,
                    outputFile = outputFile,
                ).toList()
            }
            runCurrent()
            platform.export!!.fireError(testExportException())
            runCurrent()
            val events = job.await()

            assertEquals(1, events.count { it is ConversionEvent.Done })
            val failureResult = platform.terminalAttempts.single() as ConversionResult.Failure
            assertEquals(testContext().getString(R.string.conversion_failed_fallback), failureResult.message)
            assertFalse(failureResult.message.contains("IllegalStateException"))
            assertFalse(outputFile.exists())
            assertFalse(platform.watermarkFile!!.exists())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun success_collectorCancellation_cancelsPollingAndExport_withoutDoneOrLeftovers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val platform = FakePlatform(useTemporaryWatermark = true)
            val outputFile = temporaryFile("cancel")
            val job = async {
                VideoConverter(testContext(), platform).convert(
                    backgroundImageFile = File("background.jpg"),
                    audioUri = Uri.parse("file:///audio.wav"),
                    audioDurationUs = 1_000_000L,
                    outputFile = outputFile,
                ).toList()
            }
            runCurrent()
            job.cancelAndJoin()
            runCurrent()

            assertTrue(platform.export!!.wasCancelled)
            assertTrue(platform.terminalAttempts.isEmpty())
            assertFalse(outputFile.exists())
            assertFalse(platform.watermarkFile!!.exists())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun success_collectorCancellation_preservesPreexistingOutput_andIgnoresLateCallback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val outputFile = temporaryFile("preexisting")
        try {
            val platform = FakePlatform(useTemporaryWatermark = true)
            outputFile.writeBytes(byteArrayOf(1, 2, 3))
            val observedEvents = mutableListOf<ConversionEvent>()
            val job = launch {
                VideoConverter(testContext(), platform).convert(
                    backgroundImageFile = File("background.jpg"),
                    audioUri = Uri.parse("file:///audio.wav"),
                    audioDurationUs = 1_000_000L,
                    outputFile = outputFile,
                ).collect { observedEvents += it }
            }
            runCurrent()
            assertNotNull(platform.export)

            job.cancelAndJoin()
            runCurrent()
            assertTrue(platform.export!!.wasCancelled)
            assertTrue(outputFile.exists())
            assertTrue(platform.terminalAttempts.isEmpty())

            // The production listener seam still receives a callback after collector cancellation.
            platform.export!!.fireError(testExportException())
            runCurrent()
            assertTrue(platform.terminalAttempts.isEmpty())
            assertTrue(observedEvents.none { it is ConversionEvent.Done })
            assertTrue(outputFile.exists())
            assertFalse(platform.watermarkFile!!.exists())
        } finally {
            outputFile.delete()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failure_transformerConstruction_doesNotMaskOriginalException_andCleansWatermark() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val failure = IllegalStateException("construction failed")
            val platform = FakePlatform(useTemporaryWatermark = true, createFailure = failure)
            val outputFile = temporaryFile("construction")

            val thrown = runCatching {
                VideoConverter(testContext(), platform).convert(
                    backgroundImageFile = File("background.jpg"),
                    audioUri = Uri.parse("file:///audio.wav"),
                    audioDurationUs = 1_000_000L,
                    outputFile = outputFile,
                ).toList()
            }.exceptionOrNull()

            assertExceptionShapePreserved(failure, thrown)
            assertFalse(outputFile.exists())
            assertFalse(platform.watermarkFile!!.exists())
            assertTrue(platform.terminalAttempts.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failure_transformerStart_doesNotMaskOriginalException_andDeletesPartialOutputAndWatermark() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val failure = IllegalArgumentException("start failed")
            val platform = FakePlatform(useTemporaryWatermark = true, startFailure = failure)
            val outputFile = temporaryFile("start")

            val thrown = runCatching {
                VideoConverter(testContext(), platform).convert(
                    backgroundImageFile = File("background.jpg"),
                    audioUri = Uri.parse("file:///audio.wav"),
                    audioDurationUs = 1_000_000L,
                    outputFile = outputFile,
                ).toList()
            }.exceptionOrNull()

            assertExceptionShapePreserved(failure, thrown)
            assertFalse(outputFile.exists())
            assertFalse(platform.watermarkFile!!.exists())
            assertTrue(platform.terminalAttempts.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun success_duplicateCallbacks_attemptOnlyOneDone() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val platform = FakePlatform()
            val outputFile = temporaryFile("duplicate")
            val job = async {
                VideoConverter(testContext(), platform).convert(
                    File("background.jpg"), Uri.parse("file:///audio.wav"), 1_000_000L, outputFile,
                    applyWatermark = false,
                ).toList()
            }
            runCurrent()
            platform.export!!.complete(outputFile)
            runCurrent()
            job.await()
            platform.export!!.complete(outputFile)
            platform.export!!.fireError(testExportException())

            assertEquals(1, platform.terminalAttempts.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun success_polling_emitsMappedProgressBeforeTerminalDone() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val platform = FakePlatform(progressPercent = 75)
            val outputFile = temporaryFile("polling")
            val job = async {
                VideoConverter(testContext(), platform).convert(
                    File("background.jpg"), Uri.parse("file:///audio.wav"), 1_000_000L, outputFile,
                    applyWatermark = false,
                ).toList()
            }
            runCurrent()
            val export = checkNotNull(platform.export)
            val initialReadCount = export.progressReadCount
            assertTrue(initialReadCount >= 1)
            advanceTimeBy(300L)
            runCurrent()
            assertEquals(initialReadCount + 1, export.progressReadCount)
            export.complete(outputFile)
            runCurrent()

            val events = job.await()
            assertTrue(events.any { it == ConversionEvent.Progress(50) })
            assertEquals(1, events.count { it is ConversionEvent.Done })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun success_cancellationBeforeCommit_returnsFalseWithoutTerminalAttempt() {
        val attempts = mutableListOf<ConversionResult>()
        val gate = ConversionTerminalGate { attempts += it }

        gate.cancel()

        assertFalse(gate.tryCommit(ConversionResult.Failure("cancelled before commit")))
        assertEquals(0, attempts.size)
    }

    @Test
    fun success_concurrentCancellation_orderedBeforeCallbackCommit_wins() {
        val attempts = mutableListOf<ConversionResult>()
        val gate = ConversionTerminalGate { attempts += it }
        val callbackReady = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val callback = executor.submit<Boolean> {
                callbackReady.countDown()
                check(releaseCallback.await(2, TimeUnit.SECONDS))
                gate.tryCommit(ConversionResult.Failure("late"))
            }

            assertTrue(callbackReady.await(2, TimeUnit.SECONDS))
            gate.cancel()
            releaseCallback.countDown()

            assertFalse(callback.get(2, TimeUnit.SECONDS))
            assertEquals(0, attempts.size)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun testContext(): Context = ApplicationProvider.getApplicationContext()

    private fun temporaryFile(label: String): File =
        File(testContext().cacheDir, "video_converter_test_${label}_${System.nanoTime()}.mp4")

    private fun assertExceptionShapePreserved(expected: Throwable, actual: Throwable?) {
        assertNotNull("conversion should rethrow the original failure", actual)
        val actualFailure = unwrapAsyncFailure(requireNotNull(actual))
        assertEquals(expected.javaClass, actualFailure.javaClass)
        assertEquals(expected.message, actualFailure.message)
        assertFalse(actualFailure is kotlinx.coroutines.CancellationException)
    }

    private fun unwrapAsyncFailure(actual: Throwable): Throwable = when (actual) {
        is CompletionException, is ExecutionException ->
            actual.cause?.let(::unwrapAsyncFailure) ?: actual

        else -> actual
    }

    private fun testExportException(): ExportException =
        ExportException.createForAssetLoader(
            IllegalStateException("fake Media3 failure"),
            ExportException.ERROR_CODE_UNSPECIFIED,
        )

    private class FakePlatform(
        private val useTemporaryWatermark: Boolean = false,
        private val createFailure: RuntimeException? = null,
        private val startFailure: RuntimeException? = null,
        private val progressPercent: Int? = null,
    ) : VideoConverterPlatform {
        var createCount = 0
        var watermarkFile: File? = null
        var export: FakeExport? = null
        val terminalAttempts = mutableListOf<ConversionResult>()

        override suspend fun applyWatermark(sourceFile: File, cacheDir: File): File {
            if (!useTemporaryWatermark) return sourceFile
            return File(cacheDir, "video_converter_watermark_${System.nanoTime()}.jpg").also {
                it.writeBytes(byteArrayOf(1, 2, 3))
                watermarkFile = it
            }
        }

        override suspend fun detectImageMimeType(file: File): String = MimeTypes.IMAGE_JPEG

        override fun createExport(listener: VideoConverterExportListener): VideoConverterExport {
            createCount++
            createFailure?.let { throw it }
            return FakeExport(listener, startFailure, progressPercent).also { export = it }
        }

        override fun recordTerminalAttempt(result: ConversionResult) {
            terminalAttempts += result
        }
    }

    private class FakeExport(
        private val listener: VideoConverterExportListener,
        private val startFailure: RuntimeException?,
        private val progressPercent: Int?,
    ) : VideoConverterExport {
        var wasCancelled = false
        var progressReadCount = 0

        override fun start(composition: Composition, outputPath: String) {
            File(outputPath).writeBytes(byteArrayOf(9, 8, 7))
            startFailure?.let { throw it }
        }

        override fun cancel() {
            wasCancelled = true
        }

        override fun readProgressPercent(): Int? {
            progressReadCount++
            return progressPercent?.let(::remapConversionProgress)
        }

        fun complete(outputFile: File) {
            outputFile.writeBytes(byteArrayOf(4, 5, 6))
            listener.onCompleted()
        }

        fun fireError(exportException: ExportException) {
            listener.onError(exportException)
        }
    }
}
