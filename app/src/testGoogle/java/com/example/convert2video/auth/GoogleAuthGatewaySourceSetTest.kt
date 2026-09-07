package com.example.convert2video.auth

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.convert2video.MainActivity
import com.example.convert2video.drive.DriveAuthorizationClient
import com.example.convert2video.drive.DriveAuthorizationOutcome
import com.example.convert2video.drive.DriveAuthorizationResponse
import com.example.convert2video.drive.DriveAuthGateway
import com.example.convert2video.drive.GoogleDriveAuthManager
import com.example.convert2video.drive.createDriveAuthGateway
import com.example.convert2video.youtube.AuthorizationOutcome
import com.example.convert2video.youtube.YouTubeAuthorizationClient
import com.example.convert2video.youtube.YouTubeAuthorizationResponse
import com.example.convert2video.youtube.YouTubeAuthGateway
import com.example.convert2video.youtube.YouTubeAuthManager
import com.example.convert2video.youtube.createYouTubeAuthGateway
import com.example.convert2video.youtube.parseFailureReason
import com.example.convert2video.youtube.signOutIfUnauthorized
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleAuthGatewaySourceSetTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("youtube_auth", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("drive_auth", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun success_googleYouTubeGatewayRequestsScopesAndReturnsConsentPendingIntent() = runBlocking {
        // Given
        val pendingIntent = pendingIntent(101)
        val client = FakeYouTubeAuthorizationClient(
            nextResponse = YouTubeAuthorizationResponse(
                hasResolution = true,
                pendingIntent = pendingIntent,
                accessToken = null,
            ),
        )
        val account = Account("youtube@example.com", "com.google")
        val gateway = YouTubeAuthManager(context, client)

        // When
        val outcome = gateway.requestAuthorization(account)

        // Then
        assertEquals(
            listOf(
                "https://www.googleapis.com/auth/youtube.upload",
                "https://www.googleapis.com/auth/youtube.readonly",
            ),
            client.lastRequestedScopes,
        )
        assertEquals(account, client.lastAccount)
        assertEquals(AuthorizationOutcome.NeedsConsent(pendingIntent), outcome)
        assertFalse(gateway.isAuthorized)
    }

    @Test
    fun success_googleYouTubeGatewayUsesCachedAccountForSilentAuthAndCachesCallbackToken() = runBlocking {
        // Given
        val client = FakeYouTubeAuthorizationClient(
            nextResponse = YouTubeAuthorizationResponse(
                hasResolution = false,
                pendingIntent = null,
                accessToken = "silent-token",
            ),
        )
        val gateway = YouTubeAuthManager(context, client)
        gateway.rememberAccountName("cached@example.com")
        gateway.rememberChannelTitle("Cached channel")

        // When
        val silentOutcome = gateway.requestAuthorization()
        client.callbackResponse = YouTubeAuthorizationResponse(false, null, "callback-token")
        val callbackOutcome = gateway.resultFromIntent(Intent("consent-result"))

        // Then
        assertEquals(Account("cached@example.com", "com.google"), client.lastAccount)
        assertEquals(AuthorizationOutcome.Authorized("silent-token"), silentOutcome)
        assertEquals(AuthorizationOutcome.Authorized("callback-token"), callbackOutcome)
        assertTrue(gateway.isAuthorized)
        assertEquals("Cached channel", gateway.cachedChannelTitle)
        assertEquals("cached@example.com", gateway.cachedAccountName)
    }

    @Test
    fun failure_googleYouTubeGatewayMapsApiExceptionAndSignOutClearsCache() = runBlocking {
        // Given
        val client = FakeYouTubeAuthorizationClient()
        client.failure = ApiException(Status(7))
        val gateway = YouTubeAuthManager(context, client)
        gateway.rememberChannelTitle("Channel")
        gateway.rememberAccountName("account@example.com")

        // When
        val outcome = gateway.requestAuthorization()
        gateway.signOut()

        // Then
        assertEquals(
            AuthorizationOutcome.Failed("Google 인증에 실패했습니다 (코드 7)"),
            outcome,
        )
        assertFalse(gateway.isAuthorized)
        assertEquals(null, gateway.cachedChannelTitle)
        assertEquals(null, gateway.cachedAccountName)
    }

    @Test
    fun success_googleDriveGatewayRequestsScopeAndReturnsConsentPendingIntent() = runBlocking {
        // Given
        val pendingIntent = pendingIntent(102)
        val client = FakeDriveAuthorizationClient(
            nextResponse = DriveAuthorizationResponse(true, pendingIntent, null),
        )
        val account = Account("drive@example.com", "com.google")
        val gateway = GoogleDriveAuthManager(context, client)

        // When
        val outcome = gateway.requestAuthorization(account)

        // Then
        assertEquals(listOf("https://www.googleapis.com/auth/drive.file"), client.lastRequestedScopes)
        assertEquals(account, client.lastAccount)
        assertEquals(DriveAuthorizationOutcome.NeedsConsent(pendingIntent), outcome)
        assertFalse(gateway.isAuthorized)
    }

    @Test
    fun success_googleDriveGatewayUsesCachedAccountAndCallbackCachesToken() = runBlocking {
        // Given
        val client = FakeDriveAuthorizationClient(
            nextResponse = DriveAuthorizationResponse(false, null, "silent-drive-token"),
        )
        val gateway = GoogleDriveAuthManager(context, client)
        gateway.rememberAccountEmail("cached-drive@example.com")
        gateway.rememberAppFolderId("folder-id")

        // When
        val silentOutcome = gateway.requestAuthorization()
        client.callbackResponse = DriveAuthorizationResponse(false, null, "callback-drive-token")
        val callbackOutcome = gateway.resultFromIntent(Intent("consent-result"))

        // Then
        assertEquals(Account("cached-drive@example.com", "com.google"), client.lastAccount)
        assertEquals(DriveAuthorizationOutcome.Authorized("silent-drive-token"), silentOutcome)
        assertEquals(DriveAuthorizationOutcome.Authorized("callback-drive-token"), callbackOutcome)
        assertTrue(gateway.isAuthorized)
        assertEquals("cached-drive@example.com", gateway.cachedAccountEmail)
        assertEquals("folder-id", gateway.cachedAppFolderId)
    }

    @Test
    fun failure_googleDriveGatewayMapsApiExceptionAndSignOutClearsCache() = runBlocking {
        // Given
        val client = FakeDriveAuthorizationClient()
        client.failure = ApiException(Status(13))
        val gateway = GoogleDriveAuthManager(context, client)
        gateway.rememberAccountEmail("account@example.com")
        gateway.rememberAppFolderId("folder-id")

        // When
        val outcome = gateway.requestAuthorization()
        gateway.signOut()

        // Then
        assertEquals(DriveAuthorizationOutcome.Failed("Google 인증에 실패했습니다"), outcome)
        assertFalse(gateway.isAuthorized)
        assertEquals(null, gateway.cachedAccountEmail)
        assertEquals(null, gateway.cachedAppFolderId)
    }

    @Test
    fun success_googleFactoriesAcceptContextWrapperAndUseApplicationContext() {
        // Given
        val wrapper = ContextWrapper(context)

        // When
        val youtube = createYouTubeAuthGateway(wrapper)
        val drive = createDriveAuthGateway(wrapper)

        // Then — both factories must construct successfully from a wrapper and read shared state.
        assertFalse(youtube.isAuthorized)
        assertFalse(drive.isAuthorized)
    }

    @Test
    fun success_googleUploadAuthKeeps401SignOutAnd403NoOpBehavior() {
        // Given
        var signOutCalls = 0

        // When
        signOutIfUnauthorized(401) { signOutCalls++ }
        signOutIfUnauthorized(403) { signOutCalls++ }
        signOutIfUnauthorized(null) { signOutCalls++ }

        // Then
        assertEquals(1, signOutCalls)
        assertEquals(
            "YouTube API 일일 할당량을 초과했습니다. 내일 다시 시도해 주세요",
            parseFailureReason(
                """{"error":{"errors":[{"reason":"quotaExceeded"}]}}""",
            ),
        )
    }

    private fun pendingIntent(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private class FakeYouTubeAuthorizationClient(
        var nextResponse: YouTubeAuthorizationResponse = YouTubeAuthorizationResponse(false, null, null),
    ) : YouTubeAuthorizationClient {
        var failure: Exception? = null
        var callbackResponse = nextResponse
        var lastRequestedScopes: List<String>? = null
        var lastAccount: Account? = null

        override fun authorize(
            requestedScopes: List<String>,
            account: Account?,
            onSuccess: (YouTubeAuthorizationResponse) -> Unit,
            onFailure: (Exception) -> Unit,
        ) {
            lastRequestedScopes = requestedScopes
            lastAccount = account
            val error = failure
            if (error == null) onSuccess(nextResponse) else onFailure(error)
        }

        override fun getAuthorizationResultFromIntent(intent: Intent): YouTubeAuthorizationResponse =
            callbackResponse
    }

    private class FakeDriveAuthorizationClient(
        var nextResponse: DriveAuthorizationResponse = DriveAuthorizationResponse(false, null, null),
    ) : DriveAuthorizationClient {
        var failure: Exception? = null
        var callbackResponse = nextResponse
        var lastRequestedScopes: List<String>? = null
        var lastAccount: Account? = null

        override fun authorize(
            requestedScopes: List<String>,
            account: Account?,
            onSuccess: (DriveAuthorizationResponse) -> Unit,
            onFailure: (Exception) -> Unit,
        ) {
            lastRequestedScopes = requestedScopes
            lastAccount = account
            val error = failure
            if (error == null) onSuccess(nextResponse) else onFailure(error)
        }

        override fun getAuthorizationResultFromIntent(intent: Intent): DriveAuthorizationResponse =
            callbackResponse
    }
}
