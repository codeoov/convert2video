package com.example.convert2video.record

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real [MediaRecorder] capture. Emulators without a virtual mic still usually produce a valid
 * (silent) .m4a; if this flakes in CI, re-run on a device/emulator with mic access.
 */
@RunWith(AndroidJUnit4::class)
class MediaRecorderAacBackendAndroidTest {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun success_startStop_writesNonEmptyM4a() {
        // Given
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = RecordingEngine(
            backendFactory = { MediaRecorderAacBackend(context) },
            resolveStorageDir = { C2vRecordingNames.appStorageDir(context) },
        )

        // When
        assertTrue(engine.start(RecordingFormat.AAC))
        Thread.sleep(1_500L)
        assertTrue(engine.stop())

        // Then
        val stopped = engine.state.value as RecordingState.Stopped
        val file = stopped.result.file
        assertTrue(file.exists())
        assertTrue(file.length() > 0L)
        assertTrue(file.name.endsWith(".m4a"))
        engine.release()
    }
}
