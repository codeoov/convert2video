package com.example.convert2video.youtube

import android.content.ContentResolver
import android.net.Uri
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "YouTubeApiClient"
private const val API_BASE = "https://www.googleapis.com/youtube/v3"
private const val UPLOAD_BASE = "https://www.googleapis.com/upload/youtube/v3"
private const val STREAM_BUFFER_SIZE = 64 * 1024
private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

sealed interface YouTubeApiResult<out T> {
    data class Success<out T>(val value: T) : YouTubeApiResult<T>
    data class Failure(val message: String, val httpCode: Int? = null) : YouTubeApiResult<Nothing>
}

sealed interface UploadEvent {
    data class Progress(val percent: Int) : UploadEvent
    data class Done(val result: YouTubeApiResult<String>) : UploadEvent
}

/** Pure JSON body for the resumable-upload session request; kept top-level so it's unit-testable without OkHttp/Android. */
internal fun buildUploadMetadataJson(
    title: String,
    description: String,
    privacyStatus: String,
    categoryId: String,
): String = JSONObject().apply {
    put(
        "snippet",
        JSONObject().apply {
            put("title", title)
            put("description", description)
            put("categoryId", categoryId)
        },
    )
    put(
        "status",
        JSONObject().apply {
            put("privacyStatus", privacyStatus)
        },
    )
}.toString()

/** Parses `channels?part=snippet&mine=true` responses. Returns null on any missing/blank field. */
internal fun parseChannelTitle(responseBody: String): String? {
    val items = JSONObject(responseBody).optJSONArray("items") ?: return null
    if (items.length() == 0) return null
    return items.optJSONObject(0)
        ?.optJSONObject("snippet")
        ?.optString("title")
        ?.takeIf { it.isNotBlank() }
}

/** Parses the `videos.insert` response body for the new video's id. */
internal fun parseVideoId(responseBody: String): String? =
    JSONObject(responseBody).optString("id").takeIf { it.isNotBlank() }

/**
 * Parses `errors[0].reason` from a YouTube Data API 403 error response body and maps it to a
 * Korean user-facing message. Returns null for unknown reasons or unparseable bodies.
 *
 * Pure function — no OkHttp/Android dependency. JVM unit-testable with `org.json:json`.
 */
internal fun parseFailureReason(errorBody: String): String? {
    if (errorBody.isBlank()) return null
    return try {
        val errors = JSONObject(errorBody)
            .optJSONObject("error")
            ?.optJSONArray("errors")
            ?: return null
        if (errors.length() == 0) return null
        when (errors.optJSONObject(0)?.optString("reason")) {
            "quotaExceeded" -> "YouTube API 일일 할당량을 초과했습니다. 내일 다시 시도해 주세요"
            "dailyLimitExceeded" -> "일일 업로드 횟수 한도를 초과했습니다. 내일 다시 시도해 주세요"
            "uploadLimitExceeded" -> "업로드 용량 한도를 초과했습니다"
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Stateless OkHttp wrapper for the YouTube Data API v3 calls this app needs: reading the signed-in
 * channel's title, and a resumable video upload (session init + single-shot streaming PUT). The
 * caller supplies the access token on every call — this class holds no auth state itself, that
 * lives behind [YouTubeAuthGateway].
 */
class YouTubeApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun fetchChannelTitle(accessToken: String): YouTubeApiResult<String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$API_BASE/channels?part=snippet&mine=true")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext failureFor(response)
                    val body = response.body?.string().orEmpty()
                    val title = parseChannelTitle(body)
                    if (title != null) {
                        YouTubeApiResult.Success(title)
                    } else {
                        YouTubeApiResult.Failure("채널 정보를 가져올 수 없습니다")
                    }
                }
            } catch (e: IOException) {
                AppLogger.e(TAG, "fetchChannelTitle IO error: ${e.javaClass.simpleName}")
                YouTubeApiResult.Failure("네트워크 오류로 채널 정보를 가져올 수 없습니다")
            }
        }

    /** Starts a resumable upload session; on success returns the `Location` header session URL. */
    suspend fun initiateResumableUpload(
        accessToken: String,
        title: String,
        description: String,
        privacyStatus: String,
        contentType: String,
        contentLength: Long,
        categoryId: String = "22",
    ): YouTubeApiResult<String> = withContext(Dispatchers.IO) {
        val metadata = buildUploadMetadataJson(title, description, privacyStatus, categoryId)
        val request = Request.Builder()
            .url("$UPLOAD_BASE/videos?uploadType=resumable&part=snippet,status")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", contentType)
            .header("X-Upload-Content-Length", contentLength.toString())
            .post(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext failureFor(response)
                val sessionUrl = response.header("Location")
                if (sessionUrl != null) {
                    YouTubeApiResult.Success(sessionUrl)
                } else {
                    YouTubeApiResult.Failure("업로드 세션을 시작할 수 없습니다")
                }
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "initiateResumableUpload IO error: ${e.javaClass.simpleName}")
            YouTubeApiResult.Failure("네트워크 오류로 업로드를 시작할 수 없습니다")
        }
    }

    /**
     * MVP: single-shot streaming PUT of the whole file — a dropped connection means starting over
     * from byte 0, not a range-resume (see plan §10, tracked as follow-up work). Streams directly
     * from [ContentResolver] so the video is never fully buffered in memory.
     *
     * Cancellation: collector cancel cancels the underlying OkHttp [okhttp3.Call].
     * [UploadEvent.Done] is not guaranteed after cancel.
     */
    fun uploadVideoBytes(
        sessionUrl: String,
        uri: Uri,
        contentResolver: ContentResolver,
        contentType: String,
        contentLength: Long,
    ): Flow<UploadEvent> = callbackFlow {
        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength() = contentLength

            override fun writeTo(sink: BufferedSink) {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException("영상 파일을 열 수 없습니다")
                input.use { stream ->
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    var uploaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = stream.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        uploaded += read
                        if (contentLength > 0) {
                            val percent = ((uploaded * 100) / contentLength).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                trySend(UploadEvent.Progress(percent))
                            }
                        }
                    }
                }
            }
        }

        val request = Request.Builder().url(sessionUrl).put(body).build()
        val call = client.newCall(request)
        val uploadJob = launch {
            val done = try {
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        val videoId = parseVideoId(response.body?.string().orEmpty())
                        if (videoId != null) {
                            trySend(UploadEvent.Progress(100))
                            YouTubeApiResult.Success(videoId)
                        } else {
                            YouTubeApiResult.Failure("업로드 결과를 확인할 수 없습니다")
                        }
                    } else {
                        failureFor(response)
                    }
                }
            } catch (e: IOException) {
                AppLogger.e(TAG, "uploadVideoBytes IO error: ${e.javaClass.simpleName}")
                YouTubeApiResult.Failure("네트워크 오류로 업로드에 실패했습니다")
            }
            trySend(UploadEvent.Done(done))
            close()
        }
        awaitClose {
            call.cancel()
            uploadJob.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun failureFor(response: Response): YouTubeApiResult.Failure {
        val message = when (response.code) {
            401 -> "인증이 만료되었습니다. 다시 로그인해 주세요"
            403 -> {
                // body is consumed here — confirmed safe: all callers invoke failureFor only on
                // the failure path, where the success branch (which reads body) is never reached.
                val errorBody = try {
                    response.body?.string().orEmpty()
                } catch (e: IOException) {
                    AppLogger.w(TAG, "403 error body read failed", e)
                    ""
                }
                parseFailureReason(errorBody) ?: "권한이 없거나 일일 업로드 한도를 초과했습니다"
            }
            in 500..599 -> "YouTube 서버 오류입니다. 잠시 후 다시 시도해 주세요"
            else -> "업로드에 실패했습니다 (코드 ${response.code})"
        }
        AppLogger.e(TAG, "YouTube API error: ${response.code}")
        return YouTubeApiResult.Failure(message, httpCode = response.code)
    }
}
