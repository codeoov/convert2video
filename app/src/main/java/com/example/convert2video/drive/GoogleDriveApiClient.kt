package com.example.convert2video.drive

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
import com.example.convert2video.R
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "GoogleDriveApiClient"
private const val API_BASE = "https://www.googleapis.com/drive/v3"
/** Internal Failure.message tokens — not user-facing; Options does not emit them to Snackbar. */
private const val QUOTA_FAILURE_UNPARSEABLE = "quota_unparseable"
private const val QUOTA_FAILURE_NETWORK = "quota_network"
private const val QUOTA_FAILURE_HTTP = "quota_http"
private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
private const val STREAM_BUFFER_SIZE = 64 * 1024
private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

sealed interface DriveApiResult<out T> {
    data class Success<out T>(val value: T) : DriveApiResult<T>
    data class Failure(val message: String, val httpCode: Int? = null) : DriveApiResult<Nothing>
}

data class DriveUploadedFile(
    val fileId: String,
    val webViewLink: String?,
)

data class DriveStorageQuota(
    val usageBytes: Long,
    val limitBytes: Long?,
)

/**
 * SI (1000) byte format. [numberLabel] is digits only; [unitRes] is an Options unit string.
 * Largest unit is GB (`options_drive_storage_unit_gb`); 1e12 bytes → 1000 + that unit. No TB key.
 */
internal data class DriveStorageByteFormat(
    val numberLabel: String,
    @StringRes val unitRes: Int,
)

sealed interface DriveUploadEvent {
    data class Progress(val percent: Int) : DriveUploadEvent
    data class Done(val result: DriveApiResult<DriveUploadedFile>) : DriveUploadEvent
}

/**
 * Escapes a string for use inside a single-quoted Drive `q` value.
 * Backslash first, then single quote — per Drive query language.
 */
internal fun escapeDriveQueryString(value: String): String =
    value.replace("\\", "\\\\").replace("'", "\\'")

/**
 * Whitelist for resumable-upload session URLs from Drive `Location` headers.
 * Requires `https` and a `*.googleapis.com` / `googleapis.com` host.
 */
internal fun isAllowedDriveUploadSessionUrl(sessionUrl: String): Boolean {
    if (sessionUrl.isBlank()) return false
    val url = try {
        sessionUrl.toHttpUrl()
    } catch (_: Exception) {
        return false
    }
    if (!url.isHttps) return false
    val host = url.host.lowercase()
    return host == "googleapis.com" || host.endsWith(".googleapis.com")
}
/**
 * Pure JSON body for Drive file/folder metadata; parents is included only when non-null
 * (omit for root-level folder create; include for file upload into an app folder).
 * Returns empty string on unexpected build failure (never throws).
 */
internal fun buildDriveFileMetadataJson(
    name: String,
    mimeType: String,
    parentFolderId: String? = null,
): String = try {
    JSONObject().apply {
        put("name", name)
        put("mimeType", mimeType)
        if (parentFolderId != null) {
            put("parents", JSONArray().put(parentFolderId))
        }
    }.toString()
} catch (_: Exception) {
    ""
}

/** Parses a Drive file resource for its top-level `id`. Malformed JSON → null. */
internal fun parseDriveFileId(responseBody: String): String? = try {
    JSONObject(responseBody).optString("id").takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/** Parses a Drive file resource for its top-level `webViewLink`. Malformed JSON → null. */
internal fun parseDriveWebViewLink(responseBody: String): String? = try {
    JSONObject(responseBody).optString("webViewLink").takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/** Parses `about?fields=user` responses for `user.emailAddress`. Malformed JSON → null. */
internal fun parseDriveUserEmail(responseBody: String): String? = try {
    JSONObject(responseBody)
        .optJSONObject("user")
        ?.optString("emailAddress")
        ?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

/**
 * Parses `about?fields=storageQuota`. `usage` is required; missing `limit` → [DriveStorageQuota.limitBytes]
 * null (unlimited). Malformed JSON / unparseable usage / present-but-unparseable limit → null.
 */
internal fun parseDriveStorageQuota(responseBody: String): DriveStorageQuota? = try {
    val quota = JSONObject(responseBody).optJSONObject("storageQuota") ?: return null
    val usageBytes = parseDriveQuotaLong(quota, "usage") ?: return null
    val limitBytes = if (!quota.has("limit") || quota.isNull("limit")) {
        null
    } else {
        parseDriveQuotaLong(quota, "limit") ?: return null
    }
    DriveStorageQuota(usageBytes = usageBytes, limitBytes = limitBytes)
} catch (_: Exception) {
    null
}

private fun parseDriveQuotaLong(obj: JSONObject, key: String): Long? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return when (val raw = obj.opt(key)) {
        is String -> raw.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
        is Int -> raw.toLong()
        is Long -> raw
        is Double -> if (raw.isFinite() && raw % 1.0 == 0.0) raw.toLong() else null
        is Number -> raw.toLong()
        else -> null
    }
}

/**
 * Decimal SI (1000). Caller localizes [DriveStorageByteFormat.unitRes] — no unit literals here.
 * Cap is GB (1000^3); values ≥ 1000 GB stay in GB (1 TB → 1000 + GB unit).
 */
internal fun formatDriveStorageBytes(bytes: Long): DriveStorageByteFormat {
    val safe = if (bytes < 0L) 0L else bytes
    val unitRes = intArrayOf(
        R.string.options_drive_storage_unit_b,
        R.string.options_drive_storage_unit_kb,
        R.string.options_drive_storage_unit_mb,
        R.string.options_drive_storage_unit_gb,
    )
    if (safe < 1000L) return DriveStorageByteFormat(safe.toString(), unitRes[0])
    var value = safe.toDouble()
    var unitIndex = 0
    while (value >= 1000.0 && unitIndex < unitRes.lastIndex) {
        value /= 1000.0
        unitIndex++
    }
    return DriveStorageByteFormat(formatDriveStorageSiNumber(value), unitRes[unitIndex])
}

private fun formatDriveStorageSiNumber(value: Double): String {
    val scaled = kotlin.math.round(value * 10.0).toLong()
    val whole = scaled / 10L
    val frac = kotlin.math.abs(scaled % 10L)
    return if (frac == 0L) whole.toString() else "$whole.$frac"
}

/**
 * Parses `files.list` search responses for the first matching folder id.
 * Empty `files` array or malformed JSON → null (not found / unparseable).
 */
internal fun parseDriveFolderIdFromSearch(responseBody: String): String? {
    return try {
        val files = JSONObject(responseBody).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        files.optJSONObject(0)
            ?.optString("id")
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

/**
 * Parses `errors[0].reason` from a Drive API error response body and maps it to a
 * Korean user-facing message. Returns null for unknown reasons or unparseable bodies.
 */
internal fun parseDriveFailureReason(errorBody: String): String? {
    if (errorBody.isBlank()) return null
    return try {
        val errors = JSONObject(errorBody)
            .optJSONObject("error")
            ?.optJSONArray("errors")
            ?: return null
        if (errors.length() == 0) return null
        when (errors.optJSONObject(0)?.optString("reason")) {
            "storageQuotaExceeded" -> "Google Drive 저장 공간이 부족합니다"
            "rateLimitExceeded" -> "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"
            "userRateLimitExceeded" -> "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"
            "insufficientPermissions" -> "Google Drive 권한이 없습니다. 다시 로그인해 주세요"
            "notFound" -> "파일을 찾을 수 없습니다"
            // GCP project has Drive API disabled (SERVICE_DISABLED / accessNotConfigured).
            "accessNotConfigured" -> "Google Drive API가 이 프로젝트에서 사용 설정되지 않았습니다"
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Stateless OkHttp wrapper for the Drive API v3 calls this app needs: reading the signed-in
 * account email, finding/creating an app folder, and a resumable file upload (session init +
 * single-shot streaming PUT). The caller supplies the access token on every call — this class
 * holds no auth state itself, that lives behind [DriveAuthGateway].
 */
class GoogleDriveApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun fetchAccountEmail(accessToken: String): DriveApiResult<String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$API_BASE/about?fields=user")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext failureFor(response)
                    val email = parseDriveUserEmail(response.body?.string().orEmpty())
                    if (email != null) {
                        DriveApiResult.Success(email)
                    } else {
                        DriveApiResult.Failure("계정 정보를 가져올 수 없습니다")
                    }
                }
            } catch (e: IOException) {
                AppLogger.e(TAG, "fetchAccountEmail IO error: ${e.javaClass.simpleName}")
                DriveApiResult.Failure("네트워크 오류로 계정 정보를 가져올 수 없습니다")
            }
        }

    suspend fun fetchStorageQuota(accessToken: String): DriveApiResult<DriveStorageQuota> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$API_BASE/about?fields=storageQuota")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLogger.e(TAG, "fetchStorageQuota HTTP error: httpCode=${response.code}")
                        return@withContext DriveApiResult.Failure(
                            QUOTA_FAILURE_HTTP,
                            httpCode = response.code,
                        )
                    }
                    val quota = parseDriveStorageQuota(response.body?.string().orEmpty())
                    if (quota != null) {
                        DriveApiResult.Success(quota)
                    } else {
                        // Internal token — not a user string; Options never shows Failure.message.
                        DriveApiResult.Failure(QUOTA_FAILURE_UNPARSEABLE)
                    }
                }
            } catch (e: IOException) {
                AppLogger.e(TAG, "fetchStorageQuota IO error: ${e.javaClass.simpleName}")
                DriveApiResult.Failure(QUOTA_FAILURE_NETWORK)
            }
        }

    /**
     * Searches for a folder by exact name under My Drive. [DriveApiResult.Success] with `null`
     * means not found (not an error). Blank [folderName] → [DriveApiResult.Failure] (no request).
     */
    suspend fun findFolder(
        accessToken: String,
        folderName: String,
    ): DriveApiResult<String?> = withContext(Dispatchers.IO) {
        if (folderName.isBlank()) {
            return@withContext DriveApiResult.Failure("폴더 이름이 올바르지 않습니다")
        }
        val escapedName = escapeDriveQueryString(folderName)
        val q = "mimeType='$FOLDER_MIME_TYPE' and name='$escapedName' and trashed=false"
        val url = "$API_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("pageSize", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext failureFor(response)
                val folderId = parseDriveFolderIdFromSearch(response.body?.string().orEmpty())
                DriveApiResult.Success(folderId)
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "findFolder IO error: ${e.javaClass.simpleName}")
            DriveApiResult.Failure("네트워크 오류로 폴더를 찾을 수 없습니다")
        }
    }

    /**
     * Creates a Drive folder (no parents — root of the authorized drive.file space).
     * Blank [folderName] → [DriveApiResult.Failure] (no request).
     */
    suspend fun createFolder(
        accessToken: String,
        folderName: String,
    ): DriveApiResult<String> = withContext(Dispatchers.IO) {
        if (folderName.isBlank()) {
            return@withContext DriveApiResult.Failure("폴더 이름이 올바르지 않습니다")
        }
        val metadata = buildDriveFileMetadataJson(folderName, FOLDER_MIME_TYPE, parentFolderId = null)
        if (metadata.isBlank()) {
            return@withContext DriveApiResult.Failure("폴더를 만들 수 없습니다")
        }
        val url = "$API_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext failureFor(response)
                val folderId = parseDriveFileId(response.body?.string().orEmpty())
                if (folderId != null) {
                    DriveApiResult.Success(folderId)
                } else {
                    DriveApiResult.Failure("폴더를 만들 수 없습니다")
                }
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "createFolder IO error: ${e.javaClass.simpleName}")
            DriveApiResult.Failure("네트워크 오류로 폴더를 만들 수 없습니다")
        }
    }

    /**
     * Starts a resumable upload session; on success returns the `Location` header session URL.
     * Request asks for `fields=id,webViewLink` so the final PUT response includes both.
     * Blank [fileName]/[parentFolderId] or non-positive [contentLength] → Failure (no request).
     */
    suspend fun initiateResumableUpload(
        accessToken: String,
        fileName: String,
        parentFolderId: String,
        contentType: String,
        contentLength: Long,
    ): DriveApiResult<String> = withContext(Dispatchers.IO) {
        if (fileName.isBlank()) {
            return@withContext DriveApiResult.Failure("파일 이름이 올바르지 않습니다")
        }
        if (parentFolderId.isBlank()) {
            return@withContext DriveApiResult.Failure("폴더 정보가 올바르지 않습니다")
        }
        if (contentLength <= 0L) {
            return@withContext DriveApiResult.Failure("업로드할 파일 크기가 올바르지 않습니다")
        }
        val metadata = buildDriveFileMetadataJson(fileName, contentType, parentFolderId)
        if (metadata.isBlank()) {
            return@withContext DriveApiResult.Failure("업로드 세션을 시작할 수 없습니다")
        }
        val url = "$UPLOAD_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id,webViewLink")
            .build()
        val request = Request.Builder()
            .url(url)
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
                    DriveApiResult.Success(sessionUrl)
                } else {
                    DriveApiResult.Failure("업로드 세션을 시작할 수 없습니다")
                }
            }
        } catch (e: IOException) {
            AppLogger.e(TAG, "initiateResumableUpload IO error: ${e.javaClass.simpleName}")
            DriveApiResult.Failure("네트워크 오류로 업로드를 시작할 수 없습니다")
        }
    }

    /**
     * MVP: single-shot streaming PUT of the whole file — a dropped connection means starting over
     * from byte 0, not a range-resume. Streams directly from [ContentResolver] so the file is
     * never fully buffered in memory.
     *
     * [DriveUploadEvent.Progress] is best-effort (closed-channel `trySend` failures are ignored).
     *
     * Cancellation: collector cancel cancels the underlying OkHttp [okhttp3.Call] only.
     * [DriveUploadEvent.Done] is **not** guaranteed after cancel.
     *
     * Invalid [sessionUrl] (non-https / non-googleapis host) or non-positive [contentLength]
     * emits [DriveUploadEvent.Done] with [DriveApiResult.Failure] (`httpCode = null`) and ends.
     */
    fun uploadFileBytes(
        sessionUrl: String,
        uri: Uri,
        contentResolver: ContentResolver,
        contentType: String,
        contentLength: Long,
    ): Flow<DriveUploadEvent> = callbackFlow {
        if (contentLength <= 0L) {
            trySend(
                DriveUploadEvent.Done(
                    DriveApiResult.Failure("업로드할 파일 크기가 올바르지 않습니다"),
                ),
            )
            close()
            awaitClose { }
            return@callbackFlow
        }
        if (!isAllowedDriveUploadSessionUrl(sessionUrl)) {
            trySend(
                DriveUploadEvent.Done(
                    DriveApiResult.Failure("업로드 주소가 올바르지 않습니다"),
                ),
            )
            close()
            awaitClose { }
            return@callbackFlow
        }

        fun emitProgressBestEffort(percent: Int) {
            // trySend is non-throwing; failed result when channel already closed is ignored.
            trySend(DriveUploadEvent.Progress(percent))
        }

        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaType()
            override fun contentLength() = contentLength

            override fun writeTo(sink: BufferedSink) {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IOException("파일을 열 수 없습니다")
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
                                emitProgressBestEffort(percent)
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
                        val bodyString = response.body?.string().orEmpty()
                        val fileId = parseDriveFileId(bodyString)
                        if (fileId != null) {
                            emitProgressBestEffort(100)
                            DriveApiResult.Success(
                                DriveUploadedFile(
                                    fileId = fileId,
                                    webViewLink = parseDriveWebViewLink(bodyString),
                                ),
                            )
                        } else {
                            DriveApiResult.Failure("업로드 결과를 확인할 수 없습니다")
                        }
                    } else {
                        failureFor(response)
                    }
                }
            } catch (e: IOException) {
                AppLogger.e(TAG, "uploadFileBytes IO error: ${e.javaClass.simpleName}")
                DriveApiResult.Failure("네트워크 오류로 업로드에 실패했습니다")
            }
            trySend(DriveUploadEvent.Done(done))
            close()
        }
        awaitClose {
            call.cancel()
            uploadJob.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun failureFor(response: Response): DriveApiResult.Failure {
        val code = response.code
        val message = when (code) {
            401 -> "인증이 만료되었습니다. 다시 로그인해 주세요"
            403, 404 -> {
                val errorBody = try {
                    response.body?.string().orEmpty()
                } catch (e: IOException) {
                    AppLogger.w(TAG, "error body read failed: httpCode=$code, ${e.javaClass.simpleName}")
                    ""
                }
                parseDriveFailureReason(errorBody)
                    ?: if (code == 404) {
                        "파일을 찾을 수 없습니다"
                    } else {
                        "권한이 없거나 요청이 거부되었습니다"
                    }
            }
            in 500..599 -> "Google Drive 서버 오류입니다. 잠시 후 다시 시도해 주세요"
            else -> "업로드에 실패했습니다 (코드 $code)"
        }
        AppLogger.e(TAG, "Drive API error: httpCode=$code")
        return DriveApiResult.Failure(message, httpCode = code)
    }
}
