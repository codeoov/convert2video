package com.example.convert2video.ui.screens.convert

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.WorkInfo
import com.example.convert2video.R
import com.example.convert2video.ui.shared.ConversionUiState
import com.example.convert2video.video.ConversionWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Covers the one branch [ConversionUiStateMappingTest] can't: real android.net.Uri.parse. */
@RunWith(AndroidJUnit4::class)
class ConversionUiStateMappingInstrumentedTest {

    @Test
    fun succeededWithVideoUriMapsToSuccessWithParsedUri() {
        val failedFallback = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.conversion_failed_fallback)
        val output = Data.Builder()
            .putString(ConversionWorker.KEY_VIDEO_URI, "content://media/external/video/1")
            .build()
        val workInfo = WorkInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet(), output, Data.EMPTY)

        val result = workInfo.toConversionUiState(failedFallback)

        assertEquals(
            ConversionUiState.Success(android.net.Uri.parse("content://media/external/video/1")),
            result,
        )
    }
}
