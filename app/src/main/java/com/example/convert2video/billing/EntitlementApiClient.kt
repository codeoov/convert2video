package com.example.convert2video.billing

import com.example.convert2video.BuildConfig
import com.example.convert2video.utils.AppLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal enum class EntitlementFailureKind {
    NotConfigured,
    Network,
    Unauthorized,
    Conflict,
    Revoked,
    Rejected,
    Unavailable,
    InvalidResponse,
}

internal sealed interface EntitlementApiResult {
    data class Success(
        val jwt: String,
        val expiresAtEpochSeconds: Long,
    ) : EntitlementApiResult

    data class Failure(
        val kind: EntitlementFailureKind,
        val httpCode: Int? = null,
    ) : EntitlementApiResult
}

/** Restricted OkHttp client for entitlement verification and refresh only. */
internal class EntitlementApiClient(
    httpClient: OkHttpClient = defaultClient(),
    baseUrl: String = BuildConfig.BACKEND_BASE_URL,
) : EntitlementVerifier {
    private val httpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val baseUrl = baseUrl.trim().trimEnd('/')
    private val parsedBaseUrl = this.baseUrl.toHttpUrlOrNull()
    private val isBackendConfigured =
        this.baseUrl.isNotBlank() &&
            !this.baseUrl.contains(PLACEHOLDER_HOST, ignoreCase = true) &&
            parsedBaseUrl?.host?.isNotBlank() == true &&
            parsedBaseUrl.scheme in SUPPORTED_SCHEMES

    override suspend fun verify(
        installId: String,
        store: String,
        productId: String,
        purchaseToken: String,
    ): EntitlementApiResult = postJson(
        path = VERIFY_PATH,
        body = JSONObject().apply {
            put("install_id", installId)
            put("store", store)
            put("product_id", productId)
            put("purchase_token", purchaseToken)
        },
    )

    override suspend fun refresh(
        jwt: String,
        installId: String,
    ): EntitlementApiResult = postJson(
        path = REFRESH_PATH,
        body = JSONObject().apply {
            put("install_id", installId)
        },
        bearerJwt = jwt,
    )

    private fun invalidInput(): EntitlementApiResult =
        EntitlementApiResult.Failure(EntitlementFailureKind.InvalidResponse)

    private suspend fun postJson(
        path: String,
        body: JSONObject,
        bearerJwt: String? = null,
    ): EntitlementApiResult {
        if (path == VERIFY_PATH &&
            (body.optString("install_id").isBlank() ||
                !isCanonicalBillingStore(body.optString("store")) ||
                body.optString("product_id") != PRO_PRODUCT_ID ||
                body.optString("purchase_token").isBlank())
        ) {
            return invalidInput()
        }
        if (path == REFRESH_PATH &&
            (body.optString("install_id").isBlank() || bearerJwt.isNullOrBlank())
        ) {
            return invalidInput()
        }
        if (!isBackendConfigured) {
            return EntitlementApiResult.Failure(EntitlementFailureKind.NotConfigured)
        }

        return withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(parsedBaseUrl.toString().trimEnd('/') + path)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (bearerJwt != null) {
                requestBuilder.header("Authorization", "Bearer $bearerJwt")
            }
            val call = httpClient.newCall(requestBuilder.build())
            val cancellationHandle = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                coroutineContext.ensureActive()
                call.execute().use { response ->
                    coroutineContext.ensureActive()
                    parseResponse(response)
                }
            } catch (error: CancellationException) {
                call.cancel()
                throw error
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                AppLogger.e(TAG, "Entitlement request failed: ${error.javaClass.simpleName}")
                EntitlementApiResult.Failure(EntitlementFailureKind.Network)
            } catch (error: IllegalArgumentException) {
                coroutineContext.ensureActive()
                AppLogger.e(TAG, "Entitlement request configuration failed: ${error.javaClass.simpleName}")
                EntitlementApiResult.Failure(EntitlementFailureKind.InvalidResponse)
            } catch (error: SecurityException) {
                coroutineContext.ensureActive()
                AppLogger.e(TAG, "Entitlement request unavailable: ${error.javaClass.simpleName}")
                EntitlementApiResult.Failure(EntitlementFailureKind.Unavailable)
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                AppLogger.e(TAG, "Entitlement request unavailable: ${error.javaClass.simpleName}")
                EntitlementApiResult.Failure(EntitlementFailureKind.Unavailable)
            } finally {
                cancellationHandle.dispose()
            }
        }
    }

    private fun parseResponse(response: Response): EntitlementApiResult {
        val httpCode = response.code
        if (!response.isSuccessful) {
            return EntitlementApiResult.Failure(
                kind = failureKindForHttpCode(httpCode),
                httpCode = httpCode,
            )
        }

        val responseBody = try {
            response.body?.let(::readResponseBody)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return EntitlementApiResult.Failure(EntitlementFailureKind.InvalidResponse, httpCode)

        return try {
            val root = JSONObject(responseBody)
            if (root.length() != 2 || root.get("ok") != true) {
                return EntitlementApiResult.Failure(
                    EntitlementFailureKind.InvalidResponse,
                    httpCode,
                )
            }
            val data = root.get("data") as? JSONObject
                ?: return EntitlementApiResult.Failure(
                    EntitlementFailureKind.InvalidResponse,
                    httpCode,
                )
            if (data.length() != 2) {
                return EntitlementApiResult.Failure(
                    EntitlementFailureKind.InvalidResponse,
                    httpCode,
                )
            }
            val jwt = data.get("jwt") as? String
            val expiry = data.get("expires_at")
            if (jwt.isNullOrBlank() || (expiry !is Int && expiry !is Long)) {
                return EntitlementApiResult.Failure(
                    EntitlementFailureKind.InvalidResponse,
                    httpCode,
                )
            }
            val expiresAtEpochSeconds = expiry.toLong()
            if (expiresAtEpochSeconds <= 0L) {
                return EntitlementApiResult.Failure(
                    EntitlementFailureKind.InvalidResponse,
                    httpCode,
                )
            }
            EntitlementApiResult.Success(jwt, expiresAtEpochSeconds)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            EntitlementApiResult.Failure(EntitlementFailureKind.InvalidResponse, httpCode)
        }
    }

    private fun failureKindForHttpCode(httpCode: Int): EntitlementFailureKind = when {
        httpCode == 401 -> EntitlementFailureKind.Unauthorized
        // 402 means verification is still pending: callers retain the token and retry it.
        // This client never deletes pending purchases.
        httpCode == 402 -> EntitlementFailureKind.Rejected
        httpCode == 403 -> EntitlementFailureKind.Revoked
        httpCode == 409 -> EntitlementFailureKind.Conflict
        httpCode == 400 || httpCode == 422 -> EntitlementFailureKind.InvalidResponse
        httpCode == 408 || httpCode == 429 || httpCode >= 500 -> EntitlementFailureKind.Unavailable
        httpCode in 400..499 -> EntitlementFailureKind.Rejected
        else -> EntitlementFailureKind.InvalidResponse
    }

    private fun readResponseBody(body: okhttp3.ResponseBody): String? {
        val output = ByteArrayOutputStream()
        var totalBytes = 0
        body.byteStream().use { input ->
            val buffer = ByteArray(RESPONSE_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                totalBytes += count
                if (totalBytes > MAX_RESPONSE_BYTES) return null
                output.write(buffer, 0, count)
            }
        }
        return decodeUtf8Strict(output.toByteArray())
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()

    private companion object {
        const val TAG = "EntitlementApiClient"
        const val VERIFY_PATH = "/entitlement/verify"
        const val REFRESH_PATH = "/entitlement/refresh"
        const val PLACEHOLDER_HOST = "TODO.example"
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val RESPONSE_BUFFER_SIZE = 8 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        // Production entitlement traffic is HTTPS-only; invalid configuration is fail-closed.
        val SUPPORTED_SCHEMES = setOf("https")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
