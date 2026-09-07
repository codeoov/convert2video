package com.example.convert2video.analytics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class CrashReporterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPending()
    }

    @After
    fun tearDown() {
        clearPending()
    }

    @Test
    fun success_persistPendingCrash_writesPendingStackTrace() {
        // Given
        val stackTrace = "java.lang.RuntimeException: boom"

        // When
        CrashReporter.persistPendingCrash(context, stackTrace)

        // Then
        assertEquals(stackTrace, pendingStackTrace())
    }

    @Test
    fun success_flushPendingCrashIfAny_true_removesKey() = runTest {
        // Given
        val stackTrace = "java.lang.IllegalStateException: posted"
        CrashReporter.persistPendingCrash(context, stackTrace)
        var received: String? = null

        // When
        CrashReporter.flushPendingCrashIfAny(context) { stack ->
            received = stack
            true
        }

        // Then
        assertEquals(stackTrace, received)
        assertNull(pendingStackTrace())
    }

    @Test
    fun success_flushPendingCrashIfAny_missingKey_isNoOp() = runTest {
        // Given
        var calls = 0

        // When
        CrashReporter.flushPendingCrashIfAny(context) {
            calls += 1
            true
        }

        // Then
        assertEquals(0, calls)
        assertNull(pendingStackTrace())
    }

    @Test
    fun success_flushPendingCrashIfAny_blankKey_removesWithoutPost() = runTest {
        // Given
        context.getSharedPreferences(CrashReporter.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CrashReporter.KEY_PENDING_STACK_TRACE, "   ")
            .commit()
        var calls = 0

        // When
        CrashReporter.flushPendingCrashIfAny(context) {
            calls += 1
            true
        }

        // Then
        assertEquals(0, calls)
        assertNull(pendingStackTrace())
    }

    @Test
    fun success_persistPendingCrash_truncatesToMaxChars() {
        // Given — cap is Kotlin String.length (UTF-16 code units)
        val raw = "x".repeat(CrashReporter.MAX_PENDING_STACK_TRACE_CHARS + 32)

        // When
        CrashReporter.persistPendingCrash(context, raw)

        // Then
        val stored = pendingStackTrace()
        assertEquals(CrashReporter.MAX_PENDING_STACK_TRACE_CHARS, stored?.length)
        assertEquals(raw.take(CrashReporter.MAX_PENDING_STACK_TRACE_CHARS), stored)
        assertEquals(
            CrashReporter.MAX_PENDING_STACK_TRACE_CHARS,
            CrashReporter.truncatePendingStackTrace(raw).length,
        )
    }

    @Test
    fun failure_flushPendingCrashIfAny_false_keepsKey() = runTest {
        // Given
        val stackTrace = "java.lang.IllegalStateException: keep"
        CrashReporter.persistPendingCrash(context, stackTrace)

        // When
        CrashReporter.flushPendingCrashIfAny(context) { false }

        // Then
        assertEquals(stackTrace, pendingStackTrace())
    }

    @Test
    fun exception_flushPendingCrashIfAny_postCrashThrow_keepsKey() = runTest {
        // Given
        val stackTrace = "java.lang.IllegalStateException: boom"
        CrashReporter.persistPendingCrash(context, stackTrace)

        // When
        try {
            CrashReporter.flushPendingCrashIfAny(context) {
                throw IOException("network")
            }
            fail("expected IOException")
        } catch (e: IOException) {
            // Then — flush does not swallow; key stays for retry
            assertEquals(stackTrace, pendingStackTrace())
        }
    }

    private fun pendingStackTrace(): String? =
        context.getSharedPreferences(CrashReporter.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(CrashReporter.KEY_PENDING_STACK_TRACE, null)

    private fun clearPending() {
        context.getSharedPreferences(CrashReporter.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(CrashReporter.KEY_PENDING_STACK_TRACE)
            .commit()
    }
}