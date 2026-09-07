package com.example.convert2video.video

import android.content.Context
import android.content.res.Configuration
import androidx.media3.transformer.ExportException
import androidx.test.core.app.ApplicationProvider
import com.example.convert2video.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * JVM unit tests for [ConversionWorker] notification string resource mapping SSOT.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ConversionWorkerNotificationTest {

    @Test
    fun success_segmentErrorToStringRes_mapsAllErrors() {
        ConversionSegmentError.entries.forEach { error ->
            val resId = segmentErrorToStringRes(error)
            assertTrue(
                "segmentErrorToStringRes must return a valid @StringRes for $error",
                resId != 0,
            )
        }
        assertEquals(
            R.string.conversion_error_segment_range_invalid,
            segmentErrorToStringRes(ConversionSegmentError.SEGMENT_RANGE_INVALID),
        )
        assertEquals(
            R.string.conversion_error_segment_too_short_after_margin,
            segmentErrorToStringRes(ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN),
        )
    }

    @Test
    fun success_segmentErrorUserMessages_mapViaVideoConverterFailureHelper() {
        ConversionSegmentError.entries.forEach { error ->
            assertEquals(
                segmentErrorToStringRes(error),
                videoConverterFailureMessageToStringRes(error.userMessage),
            )
        }
    }

    @Test
    fun success_videoConverterFailureMessageToStringRes_mapsKnownKoreanLiterals() {
        assertEquals(
            R.string.conversion_error_segment_range_invalid,
            videoConverterFailureMessageToStringRes(
                ConversionSegmentError.SEGMENT_RANGE_INVALID.userMessage,
            ),
        )
        assertEquals(
            R.string.conversion_error_segment_too_short_after_margin,
            videoConverterFailureMessageToStringRes(
                ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN.userMessage,
            ),
        )
        assertEquals(
            R.string.conversion_error_audio_duration_inconsistent,
            videoConverterFailureMessageToStringRes(VIDEO_CONVERTER_AUDIO_DURATION_INCONSISTENT_MESSAGE),
        )
    }

    @Test
    fun success_videoConverterFailureMessageToStringRes_unknownFallsBack() {
        assertEquals(
            R.string.conversion_notification_failure_fallback,
            videoConverterFailureMessageToStringRes("some raw Media3 error (?�인: IllegalStateException)"),
        )
    }

    @Test
    fun success_videoConverterAudioDurationMessage_matchesVideoConverterDescribeExportFailure() {
        val cause = IllegalStateException().apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "androidx.media3.transformer.ExoPlayerAssetLoader",
                    "onTimelineChanged",
                    "ExoPlayerAssetLoader.java",
                    428,
                ),
            )
        }
        val exportException = ExportException.createForAssetLoader(
            cause,
            ExportException.ERROR_CODE_UNSPECIFIED,
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val converter = VideoConverter(context)
        val method = VideoConverter::class.java.getDeclaredMethod(
            "describeExportFailure",
            ExportException::class.java,
        )
        method.isAccessible = true
        val converterMessage = method.invoke(converter, exportException) as String
        assertEquals(VIDEO_CONVERTER_AUDIO_DURATION_INCONSISTENT_MESSAGE, converterMessage)
    }

    @Test
    fun success_workerOwnedErrorStringKeys_resolveNonBlankInEnAndKo() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val enContext = appContext
        val koContext = appContext.createConfigurationContext(
            Configuration(appContext.resources.configuration).apply {
                setLocale(Locale.KOREAN)
            },
        )

        val plainKeys = listOf(
            R.string.conversion_notification_progress_title,
            R.string.conversion_notification_success_body,
            R.string.conversion_notification_failure_fallback,
            R.string.conversion_notification_success_title,
            R.string.conversion_notification_failure_title,
            R.string.conversion_notification_channel_progress,
            R.string.conversion_notification_channel_result,
            R.string.conversion_error_background_not_found,
            R.string.conversion_error_audio_uri_not_found,
            R.string.conversion_error_segment_input_invalid,
            R.string.conversion_error_audio_unreadable,
            R.string.conversion_error_unexpected,
            R.string.conversion_error_save_failed,
            R.string.conversion_error_unknown_abort,
            R.string.conversion_error_segment_range_invalid,
            R.string.conversion_error_segment_too_short_after_margin,
            R.string.conversion_error_audio_duration_inconsistent,
        )

        plainKeys.forEach { key ->
            val en = enContext.getString(key)
            val ko = koContext.getString(key)
            assertTrue("EN string must be non-blank for key=$key", en.isNotBlank())
            assertTrue("KO string must be non-blank for key=$key", ko.isNotBlank())
        }

        val enProgress = enContext.getString(R.string.conversion_notification_progress_percent, 42)
        val koProgress = koContext.getString(R.string.conversion_notification_progress_percent, 42)
        assertTrue(enProgress.isNotBlank())
        assertTrue(koProgress.isNotBlank())
        assertTrue(enProgress.contains("42"))
        assertTrue(koProgress.contains("42"))

        val localizedKeys = listOf(
            R.string.conversion_notification_progress_title,
            R.string.conversion_error_background_not_found,
            R.string.conversion_error_audio_duration_inconsistent,
        )
        localizedKeys.forEach { key ->
            assertNotEquals(
                "EN and KO translations should differ for key=$key",
                enContext.getString(key),
                koContext.getString(key),
            )
        }
    }

    @Test
    fun success_mappedKoreanConverterMessages_matchKoStringResources() {
        val koContext = ApplicationProvider.getApplicationContext<Context>()
            .createConfigurationContext(
                Configuration().apply { setLocale(Locale.KOREAN) },
            )

        val mappings = listOf(
            ConversionSegmentError.SEGMENT_RANGE_INVALID.userMessage to
                R.string.conversion_error_segment_range_invalid,
            ConversionSegmentError.SEGMENT_TOO_SHORT_AFTER_MARGIN.userMessage to
                R.string.conversion_error_segment_too_short_after_margin,
            VIDEO_CONVERTER_AUDIO_DURATION_INCONSISTENT_MESSAGE to
                R.string.conversion_error_audio_duration_inconsistent,
        )

        mappings.forEach { (koreanLiteral, expectedResId) ->
            assertEquals(expectedResId, videoConverterFailureMessageToStringRes(koreanLiteral))
            assertEquals(koreanLiteral, koContext.getString(expectedResId))
        }
    }
}
