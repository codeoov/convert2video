package com.example.convert2video.utils

import android.app.Application
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class AppLoggerTest {

    @After
    fun tearDown() {
        AppLogger.clearPersistSinkForTests()
    }

    @Test
    fun success_errorAndWarning_forwardExactSinkPayload() {
        // Given
        val payloads = mutableListOf<Payload>()
        val error = IllegalStateException("error")
        AppLogger.installPersistSink { level, tag, msg, throwable ->
            payloads += Payload(level, tag, msg, throwable)
        }

        // When
        AppLogger.e("ErrorTag", "error message", error)
        AppLogger.w("WarnTag", "warning message")

        // Then
        assertEquals(
            listOf(
                Payload("E", "ErrorTag", "error message", error),
                Payload("W", "WarnTag", "warning message", null),
            ),
            payloads,
        )
    }

    @Test
    fun exception_sinkException_isSwallowedForErrorAndWarning() {
        // Given
        var calls = 0
        AppLogger.installPersistSink { _, _, _, _ ->
            calls += 1
            error("sink failure")
        }

        // When
        val result = runCatching {
            AppLogger.e("ErrorTag", "error message")
            AppLogger.w("WarnTag", "warning message")
        }

        // Then
        assertTrue(result.isSuccess)
        assertEquals(2, calls)
    }

    @Test
    fun success_debugAndLocalError_doNotInvokeSink() {
        // Given
        var calls = 0
        AppLogger.installPersistSink { _, _, _, _ -> calls += 1 }

        // When
        AppLogger.d("DebugTag", "debug message")
        AppLogger.i("InfoTag", "info message")
        AppLogger.eLocal("LocalTag", "local error")

        // Then
        assertEquals(0, calls)
    }

    @Test
    fun success_sink_remainsUsableAfterDebugInfoAndLocalError() {
        // Given
        val messages = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, _ -> messages += msg }

        // When
        AppLogger.d("DebugTag", "debug message")
        AppLogger.i("InfoTag", "info message")
        AppLogger.eLocal("LocalTag", "local error")
        AppLogger.e("ErrorTag", "persisted error")
        AppLogger.w("WarnTag", "persisted warning")

        // Then
        assertEquals(listOf("persisted error", "persisted warning"), messages)
    }

    @Test
    fun success_debugAndInfoLogCalls_areGuardedByBuildConfigDebug() {
        // Given
        val source = appLoggerSource()

        // When
        val debugFunction = source.substringAfter("fun d(").substringBefore("fun i(")
        val infoFunction = source.substringAfter("fun i(").substringBefore("/**")

        // Then — every D/I call appears between the DEBUG guard and the function's trailing comment.
        assertDebugGuardContainsOnlyLogCalls(debugFunction, "d")
        assertDebugGuardContainsOnlyLogCalls(infoFunction, "i")
    }

    private fun assertDebugGuardContainsOnlyLogCalls(functionSource: String, level: String) {
        val functionOpen = functionSource.indexOf('{')
        val functionClose = matchingBrace(functionSource, functionOpen)
        val functionBody = functionSource.substring(functionOpen + 1, functionClose)
        val guardStart = functionBody.indexOf("if (BuildConfig.DEBUG)")
        val guardOpen = functionBody.indexOf('{', guardStart)
        val guardClose = matchingBrace(functionBody, guardOpen)
        val guardedBody = functionBody.substring(guardOpen + 1, guardClose)
        val outsideGuard = functionBody.removeRange(guardStart, guardClose + 1)

        assertEquals(1, "BuildConfig.DEBUG".toRegex().findAll(functionBody).count())
        assertEquals(2, "Log\\.$level\\(".toRegex().findAll(guardedBody).count())
        assertTrue(!outsideGuard.contains("Log.$level("))
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
        throw AssertionError("Unbalanced braces in AppLogger source")
    }

    private fun appLoggerSource(): String {
        val relativePaths = listOf(
            "src/main/java/com/example/convert2video/utils/AppLogger.kt",
            "app/src/main/java/com/example/convert2video/utils/AppLogger.kt",
        )
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            relativePaths.forEach { relativePath ->
                val candidate = File(directory, relativePath)
                if (candidate.isFile) return candidate.readText()
            }
            directory = directory.parentFile ?: break
        }
        throw AssertionError("AppLogger.kt source was not found from ${System.getProperty("user.dir")}")
    }

    private data class Payload(
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )
}
