package com.example.convert2video.record

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real [android.media.AudioRecord] WAV capture. May be unstable in headless CI —
 * prefer device/emulator re-run if flaky.
 */
@RunWith(AndroidJUnit4::class)
class AudioRecordWavBackendAndroidTest {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun success_startStop_writesRiffWaveWithMatchingLength() {
        // Given
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = RecordingEngine(
            backendFactory = { AudioRecordWavBackend(NoiseReductionMode.DeviceDefault) },
            resolveStorageDir = { C2vRecordingNames.appStorageDir(context) },
        )

        // When
        assertTrue(engine.start(RecordingFormat.WAV))
        Thread.sleep(1_500L)
        assertTrue(engine.stop())

        // Then
        val stopped = engine.state.value as RecordingState.Stopped
        val file = stopped.result.file
        assertTrue(file.exists())
        assertTrue(file.name.endsWith(".wav"))
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            val pcmBytes = ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(44L + pcmBytes.toLong(), file.length())
            assertTrue(pcmBytes > 0)
        }
        engine.release()
    }
}
