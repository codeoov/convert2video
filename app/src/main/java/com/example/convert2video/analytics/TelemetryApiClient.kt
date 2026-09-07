package com.example.convert2video.analytics

import com.example.convert2video.BuildConfig
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val TAG = "TelemetryApiClient"
private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

/**
 * Stateless OkHttp client for telemetry `/events` and `/crash`.
 * Never logs install ids, stack traces, URLs, or field values.
 */
internal class TelemetryApiClient(
    private val httpClient: OkHttpClient = defaultClient(),
    baseUrl: String = BuildConfig.BACKEND_BASE_URL,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    /** backend 미배포 placeholder(blank 또는 TODO.example)면 아웃바운드 없이 no-op 성공 처리. */
    private val isBackendConfigured =
        this.baseUrl.isNotBlank() && !this.baseUrl.contains(PLACEHOLDER_HOST, ignoreCase = true)

    suspend fun postEvent(
        installId: String,
        eventName: String,
        payload: JSONObject?,
        appVersion: String,
    ): Boolean = postJson(
        "$baseUrl/events",
        JSONObject().apply {
            put("install_id", installId)
            put("event_name", eventName)
            payload?.let { put("payload", it) }
            put("app_version", appVersion)
        },
    )

    suspend fun postCrash(
        installId: String,
        appVersion: String,
        osVersion: String,
        deviceModel: String,
        stackTrace: String,
    ): Boolean = postJson(
        "$baseUrl/crash",
        JSONObject().apply {
            put("install_id", installId)
            put("app_version", appVersion)
            put("os_version", osVersion)
            put("device_model", deviceModel)
            put("stack_trace", stackTrace)
        },
    )

    private suspend fun postJson(url: String, json: JSONObject): Boolean {
        if (!isBackendConfigured) return true
        return withContext(Dispatchers.IO) {
            val call = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            )
            val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                call.execute().use { it.isSuccessful }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                AppLogger.e(TAG, "telemetry post failed: ${e.javaClass.simpleName}")
                false
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                AppLogger.e(TAG, "telemetry post failed: ${e.javaClass.simpleName}")
                false
            } finally {
                cancelHandle.dispose()
            }
        }
    }

    companion object {
        private const val PLACEHOLDER_HOST = "TODO.example"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}