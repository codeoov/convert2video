package com.example.convert2video.analytics

import android.app.Application
import android.content.Context
import android.os.Build
import com.example.convert2video.BuildConfig
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.utils.installId
import com.example.convert2video.utils.requireApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide fire-and-forget analytics. Callers must not create their own coroutine scope.
 * Owns the process [TelemetryApiClient].
 */
class AnalyticsReporter private constructor(
    private val application: Application,
    private val api: TelemetryApiClient = TelemetryApiClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        startLanguagePromptWatch()
    }

    fun logEvent(eventName: String, payload: JSONObject? = null) {
        if (eventName == EVENT_APP_OPEN) {
            if (!languagePromptEligible.get()) {
                appOpenPending.set(true)
                if (!languagePromptEligible.get()) return
                flushPendingAppOpenIfEligible()
                return
            }
            if (!appOpenLogged.compareAndSet(false, true)) return
        }
        launchPostEvent(eventName, payload)
    }

    internal suspend fun postCrash(stackTrace: String): Boolean {
        return api.postCrash(
            installId = application.installId(),
            appVersion = BuildConfig.VERSION_NAME,
            osVersion = Build.VERSION.RELEASE,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            stackTrace = stackTrace,
        )
    }

    private fun startLanguagePromptWatch() {
        scope.launch {
            try {
                SettingsRepository(application).languagePromptShown.collect { shown ->
                    if (!shown) return@collect
                    languagePromptEligible.set(true)
                    flushPendingAppOpenIfEligible()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "languagePromptShown collect failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun flushPendingAppOpenIfEligible() {
        if (!languagePromptEligible.get()) return
        if (!appOpenPending.compareAndSet(true, false)) return
        if (!appOpenLogged.compareAndSet(false, true)) return
        launchPostEvent(EVENT_APP_OPEN, payload = null)
    }

    private fun launchPostEvent(eventName: String, payload: JSONObject?) {
        scope.launch {
            try {
                api.postEvent(
                    installId = application.installId(),
                    eventName = eventName,
                    payload = payload,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "logEvent failed: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "AnalyticsReporter"
        const val EVENT_APP_OPEN = "app_open"
        const val EVENT_CONVERSION_STARTED = "conversion_started"
        const val EVENT_CONVERSION_COMPLETED = "conversion_completed"

        private val appOpenLogged = AtomicBoolean(false)
        private val appOpenPending = AtomicBoolean(false)
        private val languagePromptEligible = AtomicBoolean(false)

        @Volatile
        private var instance: AnalyticsReporter? = null

        fun getInstance(application: Application): AnalyticsReporter =
            instance ?: synchronized(this) {
                instance ?: AnalyticsReporter(application).also { instance = it }
            }

        fun getInstance(context: Context): AnalyticsReporter =
            getInstance(context.requireApplication())
    }
}
