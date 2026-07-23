package com.example.convert2video.video

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
}
