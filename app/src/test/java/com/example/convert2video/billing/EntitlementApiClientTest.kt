package com.example.convert2video.billing

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class EntitlementApiClientTest {

    private companion object {
        const val CANONICAL_INSTALL_ID = "123e4567-e89b-12d3-a456-426614174000"
    }

    @Test
    fun success_verifyPurchased_parsesEntitlementAndSendsCanonicalRequest() {
        // Given
        val capture = RequestCapture()
        val client = clientReturning(
            code = 200,
            body = """{"ok":true,"data":{"jwt":"verified-jwt","expires_at":4102444800}}""",
            capture = capture,
        )
        val apiClient = EntitlementApiClient(client, "https://billing.test///")

        // When
        val result = runBlocking {
            apiClient.verify(
                installId = CANONICAL_INSTALL_ID,
                store = "google_play",
                productId = PRO_PRODUCT_ID,
                purchaseToken = "purchase-token",
            )
        }

        // Then
        assertEquals(
            EntitlementApiResult.Success("verified-jwt", 4102444800L),
            result,
        )
        assertEquals("POST", capture.request.method)
        assertEquals("https://billing.test/entitlement/verify", capture.request.url.toString())
        val requestJson = JSONObject(capture.body)
        assertEquals(CANONICAL_INSTALL_ID, requestJson.getString("install_id"))
        assertEquals("google_play", requestJson.getString("store"))
        assertEquals(PRO_PRODUCT_ID, requestJson.getString("product_id"))
        assertEquals("purchase-token", requestJson.getString("purchase_token"))
        assertTrue(capture.request.header("Authorization") == null)
    }

    @Test
    fun failure_verify409_mapsConflictWithoutUsingResponseBody() {
        // Given
        val client = clientReturning(
            code = 409,
            body = "{\"message\":\"restore unavailable\"}",
        )
        val apiClient = EntitlementApiClient(client, "https://billing.test")

        // When
        val result = runBlocking {
            apiClient.verify(
                installId = CANONICAL_INSTALL_ID,
                store = "google_play",
                productId = PRO_PRODUCT_ID,
                purchaseToken = "purchase-token",
            )
        }

        // Then
        assertEquals(
            EntitlementApiResult.Failure(EntitlementFailureKind.Conflict, 409),
            result,
        )
    }

    @Test
    fun failure_refresh403_mapsRevokedAndSendsBearerHeader() {
        // Given
        val capture = RequestCapture()
        val client = clientReturning(
            code = 403,
            body = "{}",
            capture = capture,
        )
        val apiClient = EntitlementApiClient(client, "https://billing.test")

        // When
        val result = runBlocking {
            apiClient.refresh(jwt = "cached-jwt", installId = CANONICAL_INSTALL_ID)
        }

        // Then
        assertEquals(
            EntitlementApiResult.Failure(EntitlementFailureKind.Revoked, 403),
            result,
        )
        assertEquals("https://billing.test/entitlement/refresh", capture.request.url.toString())
        assertEquals("Bearer cached-jwt", capture.request.header("Authorization"))
        assertEquals("{\"install_id\":\"$CANONICAL_INSTALL_ID\"}", capture.body)
    }

    @Test
    fun failure_refresh503_mapsUnavailable() {
        // Given
        val apiClient = EntitlementApiClient(
            clientReturning(code = 503, body = "service unavailable"),
            "https://billing.test",
        )

        // When
        val result = runBlocking {
            apiClient.refresh(jwt = "cached-jwt", installId = CANONICAL_INSTALL_ID)
        }

        // Then
        assertEquals(
            EntitlementApiResult.Failure(EntitlementFailureKind.Unavailable, 503),
            result,
        )
    }

    @Test
    fun failure_networkFailure_mapsNetworkNotUserCancelled() {
        // Given
        val apiClient = EntitlementApiClient(
            clientThrowing(IOException("offline")),
            "https://billing.test",
        )

        // When
        val result = runBlocking {
            apiClient.verify(
                installId = CANONICAL_INSTALL_ID,
                store = "google_play",
                productId = PRO_PRODUCT_ID,
                purchaseToken = "purchase-token",
            )
        }

        // Then
        assertEquals(
            EntitlementApiResult.Failure(EntitlementFailureKind.Network),
            result,
        )
    }

    @Test
    fun failure_malformedSuccessBody_mapsInvalidResponse() {
        // Given
        val apiClient = EntitlementApiClient(
            clientReturning(code = 200, body = "{\"ok\":true,\"data\":{}}"),
            "https://billing.test",
        )

        // When
        val result = runBlocking {
            apiClient.refresh(jwt = "cached-jwt", installId = CANONICAL_INSTALL_ID)
        }

        // Then
        assertEquals(
            EntitlementApiResult.Failure(EntitlementFailureKind.InvalidResponse, 200),
            result,
        )
    }

    @Test
    fun failure_malformedJsonBody_mapsInvalidResponse() {
        // Given
        val apiClient = EntitlementApiClient(
            clientReturning(code = 200, body = "{not-json"),
            "https://billing.test",
        )

        // When
        val result = runBlocking {
            apiClient.refresh(jwt = "cached-jwt", installId = CANONICAL_INSTALL_ID)
        }

        // Then
        assertEquals(
            EntitlementApiResult.Failure(EntitlementFailureKind.InvalidResponse, 200),
            result,
        )
    }

    @Test
    fun failure_http400MalformedBody_mapsInvalidResponseWithHttpCode() {
        // Given
        val apiClient = EntitlementApiClient(
            clientReturning(code = 400, body = "{not-json"),
            "https://billing.test",
        )

        // When
        val result = runBlocking {
            apiClient.verify(
                installId = CANONICAL_INSTALL_ID,
                store = "google_play",
                productId = PRO_PRODUCT_ID,
                purchaseToken = "purchase-token",
            )
        }

        // Then
        assertEquals(
            EntitlementApiResult.Failure(EntitlementFailureKind.InvalidResponse, 400),
            result,
        )
    }

    @Test
    fun exception_cancellation_isRepropagatedInsteadOfMappedToNetworkOrCancelled() {
        // Given
        val apiClient = EntitlementApiClient(
            clientThrowing(CancellationException("cancelled")),
            "https://billing.test",
        )

        // When / Then
        try {
            runBlocking {
                apiClient.verify(
                    installId = CANONICAL_INSTALL_ID,
                    store = "google_play",
                    productId = PRO_PRODUCT_ID,
                    purchaseToken = "purchase-token",
                )
            }
            fail("CancellationException must be repropagated")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
    }

    private fun clientReturning(
        code: Int,
        body: String,
        capture: RequestCapture = RequestCapture(),
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            capture.request = chain.request()
            capture.body = chain.request().body?.let { requestBody ->
                Buffer().also(requestBody::writeTo).readUtf8()
            }
            response(code, body, chain.request())
        })
        .build()

    private fun clientThrowing(error: Exception): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { throw error })
        .build()

    private fun response(code: Int, body: String, request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private class RequestCapture {
        lateinit var request: Request
        var body: String? = null
    }
}
