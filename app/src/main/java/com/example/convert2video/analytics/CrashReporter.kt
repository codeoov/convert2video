package com.example.convert2video.analytics

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide uncaught-exception persist + next-launch flush.
 * Persist uses synchronous [android.content.SharedPreferences.Editor.commit] only.
 */
internal class CrashReporter private constructor(
    private val context: Context,
) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            persistPendingCrash(context, e.stackTraceToString())
        } catch (_: Exception) {
            // Swallow — throwing from the handler can hide the original crash.
        }
        val handler = defaultHandler
        if (handler != null) {
            handler.uncaughtException(t, e)
        } else {
            Process.killProcess(Process.myPid())
        }
    }

    companion object {
        internal const val PREFS_NAME = "crash_reporter"
        internal const val KEY_PENDING_STACK_TRACE = "pending_stack_trace"

        /** UTF-16 code-unit cap ([String.length]), 16 KiB. */
        internal const val MAX_PENDING_STACK_TRACE_CHARS = 16 * 1024

        private val installed = AtomicBoolean(false)

        fun install(context: Context) {
            if (!installed.compareAndSet(false, true)) return
            Thread.setDefaultUncaughtExceptionHandler(
                CrashReporter(context.applicationContext),
            )
        }

        internal fun truncatePendingStackTrace(stackTrace: String): String {
            if (stackTrace.length <= MAX_PENDING_STACK_TRACE_CHARS) return stackTrace
            return stackTrace.substring(0, MAX_PENDING_STACK_TRACE_CHARS)
        }

        internal fun persistPendingCrash(context: Context, stackTrace: String) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val truncated = truncatePendingStackTrace(stackTrace)
            if (writePendingStack(prefs, truncated)) return
            writePendingStack(prefs, truncated)
        }

        private fun writePendingStack(prefs: SharedPreferences, stackTrace: String): Boolean {
            return prefs.edit().putString(KEY_PENDING_STACK_TRACE, stackTrace).commit()
        }

        suspend fun flushPendingCrashIfAny(
            context: Context,
            postCrash: suspend (stack: String) -> Boolean,
        ) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stackTrace = prefs.getString(KEY_PENDING_STACK_TRACE, null)
            if (stackTrace.isNullOrBlank()) {
                prefs.edit().remove(KEY_PENDING_STACK_TRACE).apply()
                return
            }
            val posted = postCrash(stackTrace)
            if (posted) {
                prefs.edit().remove(KEY_PENDING_STACK_TRACE).apply()
            }
        }
    }
}