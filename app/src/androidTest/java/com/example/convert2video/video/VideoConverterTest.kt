package com.example.convert2video.video

import android.media.MediaCodecList
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class VideoConverterTest {

    @Test
    fun audioDurationUsOrNullReturnsNullForUnreadableFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val garbage = File(context.cacheDir, "not_audio_${System.nanoTime()}.mp3")
        garbage.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))

        val duration = VideoConverter(context).audioDurationUsOrNull(Uri.fromFile(garbage))

        assertNull(duration)
    }

    @Test
    fun success_productionConverter_validPngAndWav_emitsDoneAndWritesMp4() =
        runBlocking(Dispatchers.Main.immediate) {
            assertMainLooper()
            assumeH264AndAacEncoders()
            val fixtures = createFixtures("success", validAudio = true)
            try {
                val events = withTimeout(CONVERSION_TIMEOUT_MS) {
                    VideoConverter(fixtures.context).convert(
                        backgroundImageFile = fixtures.backgroundFile,
                        audioUri = Uri.fromFile(fixtures.audioFile),
                        audioDurationUs = AUDIO_DURATION_US,
                        outputFile = fixtures.outputFile,
                        applyWatermark = false,
                    ).toList()
                }

                val done = events.filterIsInstance<ConversionEvent.Done>().singleOrNull()
                assertNotNull("production converter must emit exactly one Done event", done)
                assertTrue((done!!.result as? ConversionResult.Success)?.outputFile == fixtures.outputFile)
                assertTrue("Media3 output must be a non-empty MP4", fixtures.outputFile.isFile)
                assertTrue(fixtures.outputFile.length() > 0L)
            } finally {
                fixtures.deleteAll()
            }
        }

    @Test
    fun failure_productionConverter_malformedAudio_emitsFailureDone() =
        runBlocking(Dispatchers.Main.immediate) {
            assertMainLooper()
            assumeH264AndAacEncoders()
            val fixtures = createFixtures("error", validAudio = false)
            try {
                val events = withTimeout(CONVERSION_TIMEOUT_MS) {
                    VideoConverter(fixtures.context).convert(
                        backgroundImageFile = fixtures.backgroundFile,
                        audioUri = Uri.fromFile(fixtures.audioFile),
                        audioDurationUs = AUDIO_DURATION_US,
                        outputFile = fixtures.outputFile,
                        applyWatermark = false,
                    ).toList()
                }

                val done = events.filterIsInstance<ConversionEvent.Done>().singleOrNull()
                assertNotNull("malformed input must be reported through the production flow", done)
                assertTrue(done!!.result is ConversionResult.Failure)
                assertFalse("failed conversion must not leave an output", fixtures.outputFile.exists())
            } finally {
                fixtures.deleteAll()
            }
        }

    @Test
    fun success_productionConverter_collectorCancellation_cleansOutputWithoutDone() =
        runBlocking(Dispatchers.Main.immediate) {
            assertMainLooper()
            assumeH264AndAacEncoders()
            val fixtures = createFixtures("cancel", validAudio = true)
            val firstProgress = CompletableDeferred<Unit>()
            val events = mutableListOf<ConversionEvent>()
            val conversionJob = launch(Dispatchers.Main.immediate) {
                VideoConverter(fixtures.context).convert(
                    backgroundImageFile = fixtures.backgroundFile,
                    audioUri = Uri.fromFile(fixtures.audioFile),
                    audioDurationUs = AUDIO_DURATION_US,
                    outputFile = fixtures.outputFile,
                    applyWatermark = false,
                ).collect { event ->
                    events += event
                    if (event is ConversionEvent.Progress) {
                        firstProgress.complete(Unit)
                    }
                }
            }
            try {
                withTimeout(CONVERSION_TIMEOUT_MS) { firstProgress.await() }
                conversionJob.cancelAndJoin()

                assertTrue(events.none { it is ConversionEvent.Done })
                assertFalse("cancellation must clean a partial output", fixtures.outputFile.exists())
            } finally {
                conversionJob.cancelAndJoin()
                fixtures.deleteAll()
            }
        }

    private fun assertMainLooper() {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
    }

    private fun assumeH264AndAacEncoders() {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        fun hasEncoder(mimeType: String): Boolean = codecInfos.any { codecInfo ->
            codecInfo.isEncoder && codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
        assumeTrue("H.264 encoder is required for the production conversion test", hasEncoder(MimeTypes.VIDEO_H264))
        assumeTrue("AAC encoder is required for the production conversion test", hasEncoder(MimeTypes.AUDIO_AAC))
    }

    private data class TestFixtures(
        val context: android.content.Context,
        val root: File,
        val backgroundFile: File,
        val audioFile: File,
        val outputFile: File,
    ) {
        fun deleteAll() {
            backgroundFile.delete()
            audioFile.delete()
            outputFile.delete()
            root.delete()
        }
    }

    private fun createFixtures(label: String, validAudio: Boolean): TestFixtures {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "video_converter_android_test_${label}_${System.nanoTime()}")
            .also { assertTrue("fixture directory must be created", it.mkdirs()) }
        val backgroundFile = File(root, "background.png")
        val audioFile = File(root, "audio.wav")
        val outputFile = File(root, "output.mp4")
        writeTinyPng(backgroundFile)
        if (validAudio) {
            writeTinyWav(audioFile)
        } else {
            audioFile.writeBytes(byteArrayOf(0x43, 0x32, 0x56, 0x00, 0x01, 0x02))
        }
        return TestFixtures(context, root, backgroundFile, audioFile, outputFile)
            .also { check(root.isDirectory) }
    }

    private fun writeTinyPng(file: File) {
        val pngBytes = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT,
        )
        file.writeBytes(pngBytes)
    }

    private fun writeTinyWav(file: File) {
        val sampleRateHz = 8_000
        val durationMs = 1_500
        val sampleCount = sampleRateHz * durationMs / 1_000
        val dataSize = sampleCount * Short.SIZE_BYTES
        val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII))
        wav.putInt(36 + dataSize)
        wav.put("WAVE".toByteArray(Charsets.US_ASCII))
        wav.put("fmt ".toByteArray(Charsets.US_ASCII))
        wav.putInt(16)
        wav.putShort(1.toShort())
        wav.putShort(1.toShort())
        wav.putInt(sampleRateHz)
        wav.putInt(sampleRateHz * Short.SIZE_BYTES)
        wav.putShort(Short.SIZE_BYTES.toShort())
        wav.putShort(16.toShort())
        wav.put("data".toByteArray(Charsets.US_ASCII))
        wav.putInt(dataSize)
        repeat(sampleCount) { wav.putShort(0) }
        file.writeBytes(wav.array())
    }

    private companion object {
        const val AUDIO_DURATION_US = 1_500_000L
        const val CONVERSION_TIMEOUT_MS = 20_000L
    }
}
