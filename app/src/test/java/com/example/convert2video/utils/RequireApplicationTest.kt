package com.example.convert2video.utils

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RequireApplicationTest {

    private class FailedNonApplicationContext : ContextWrapper(Application()) {
        override fun getApplicationContext(): Context = this
    }

    private class CallerContext(
        base: Application,
        private val failed: Context,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = failed
    }

    private class ApplicationBackedContext(
        private val application: Application,
    ) : ContextWrapper(application) {
        override fun getApplicationContext(): Context = application
    }

    @Test
    fun success_contextWrapperReturningApplication_returnsSameInstance() {
        // Given
        val application = Application()
        val context = ApplicationBackedContext(application)

        // When
        val result = context.requireApplication()

        // Then
        assertSame(application, result)
    }

    @Test
    fun exception_nonApplicationApplicationContext_throwsAfterLog() {
        // Given — applicationContext is a non-Application object (distinct from caller)
        val captured = mutableListOf<String>()
        AppLogger.installPersistSink { _, _, msg, _ -> captured += msg }
        val failed = FailedNonApplicationContext()
        val context = CallerContext(Application(), failed)
        try {
            // When
            context.requireApplication()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Then — log failed object's simpleName only (no path/URI), then throw
            assertTrue(captured.any { it.contains("FailedNonApplicationContext") })
            assertTrue(captured.none { it.contains("CallerContext") })
            assertTrue(captured.none { "/" in it || "content:" in it })
        } finally {
            AppLogger.clearPersistSinkForTests()
        }
    }
}
