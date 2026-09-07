package com.example.convert2video.youtube

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.convert2video.utils.AppLogger
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine

private const val YOUTUBE_UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload"
/** Required for `channels.list?mine=true` (channel title). `youtube.upload` alone → HTTP 403. */
private const val YOUTUBE_READONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
private const val TAG = "YouTubeAuthManager"
private const val KEY_CHANNEL_TITLE = "channel_title"
private const val KEY_ACCOUNT_NAME = "account_name"

internal data class YouTubeAuthorizationResponse(
    val hasResolution: Boolean,
    val pendingIntent: PendingIntent?,
    val accessToken: String?,
)

internal interface YouTubeAuthorizationClient {
    fun authorize(
        requestedScopes: List<String>,
        account: Account?,
        onSuccess: (YouTubeAuthorizationResponse) -> Unit,
        onFailure: (Exception) -> Unit,
    )

    fun getAuthorizationResultFromIntent(intent: Intent): YouTubeAuthorizationResponse
}

private class GoogleYouTubeAuthorizationClient(context: Context) : YouTubeAuthorizationClient {
    private val client = Identity.getAuthorizationClient(context)

    override fun authorize(
        requestedScopes: List<String>,
        account: Account?,
        onSuccess: (YouTubeAuthorizationResponse) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val requestBuilder = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes.map(::Scope))
            .setOptOutIncludingGrantedScopes(true)
        if (account != null) requestBuilder.setAccount(account)
        client.authorize(requestBuilder.build())
            .addOnSuccessListener { result ->
                onSuccess(
                    YouTubeAuthorizationResponse(
                        hasResolution = result.hasResolution(),
                        pendingIntent = result.pendingIntent,
                        accessToken = result.accessToken,
                    ),
                )
            }
            .addOnFailureListener(onFailure)
    }

    override fun getAuthorizationResultFromIntent(intent: Intent): YouTubeAuthorizationResponse {
        val result = client.getAuthorizationResultFromIntent(intent)
        return YouTubeAuthorizationResponse(
            hasResolution = result.hasResolution(),
            pendingIntent = result.pendingIntent,
            accessToken = result.accessToken,
        )
    }
}

/** Google Play Services implementation of the store-neutral YouTube auth contract. */
internal class YouTubeAuthManager(
    context: Context,
    authorizationClientOverride: YouTubeAuthorizationClient? = null,
) : YouTubeAuthGateway {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(YOUTUBE_AUTH_PREFS_NAME, Context.MODE_PRIVATE)
    private val authorizationClient = authorizationClientOverride
        ?: GoogleYouTubeAuthorizationClient(appContext)

    override val isAuthorized: Boolean
        get() = prefs.getBoolean(YOUTUBE_AUTH_KEY_AUTHORIZED, false)

    override val cachedChannelTitle: String?
        get() = prefs.getString(KEY_CHANNEL_TITLE, null)

    /** Last picked/stored Google account name, or null if not yet set. */
    override val cachedAccountName: String?
        get() = prefs.getString(KEY_ACCOUNT_NAME, null)

    /**
     * Requests upload + readonly scopes with AM-1/AM-2/AM-3 account binding.
     *
     * - AM-1: [account] != null → bind directly via `setAccount(account)`
     * - AM-2: [account] == null + stored name non-blank → `Account(name, "com.google")` + `setAccount`
     * - AM-3: [account] == null + no stored name → omit `setAccount` (Play Services default)
     */
    override suspend fun requestAuthorization(account: Account?): AuthorizationOutcome =
        suspendCancellableCoroutine { cont ->
            val storedName = prefs.getString(KEY_ACCOUNT_NAME, null)
            val effectiveAccount: Account? = when {
                account != null -> account
                storedName != null && storedName.isNotBlank() -> Account(storedName, "com.google")
                else -> null
            }
            authorizationClient.authorize(
                requestedScopes = listOf(YOUTUBE_UPLOAD_SCOPE, YOUTUBE_READONLY_SCOPE),
                account = effectiveAccount,
                onSuccess = { result ->
                    val outcome = if (result.hasResolution) {
                        val pendingIntent = result.pendingIntent
                        if (pendingIntent != null) {
                            AuthorizationOutcome.NeedsConsent(pendingIntent)
                        } else {
                            AuthorizationOutcome.Failed("Google 인증을 진행할 수 없습니다")
                        }
                    } else {
                        val token = result.accessToken
                        if (token != null) {
                            markAuthorized()
                            AuthorizationOutcome.Authorized(token)
                        } else {
                            AuthorizationOutcome.Failed("Google 인증에 실패했습니다")
                        }
                    }
                    if (cont.isActive) cont.resumeWith(Result.success(outcome))
                },
                onFailure = { e ->
                    AppLogger.e(TAG, "authorize() failed: ${e.javaClass.simpleName}")
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(AuthorizationOutcome.Failed(messageForException(e))))
                    }
                },
            )
        }

    /** Parses the consent result [Intent] handed back by the Activity Result launcher. */
    override fun resultFromIntent(intent: Intent): AuthorizationOutcome = try {
        val token = authorizationClient.getAuthorizationResultFromIntent(intent).accessToken
        if (token != null) {
            markAuthorized()
            AuthorizationOutcome.Authorized(token)
        } else {
            AuthorizationOutcome.Failed("Google 인증에 실패했습니다")
        }
    } catch (e: ApiException) {
        AppLogger.e(TAG, "getAuthorizationResultFromIntent failed: status=${e.statusCode}")
        AuthorizationOutcome.Failed(messageForException(e))
    }

    override fun rememberChannelTitle(title: String) {
        prefs.edit().putString(KEY_CHANNEL_TITLE, title).apply()
    }

    /** Persists the picked account name so AM-2 can restore the same account on silent re-auth. */
    override fun rememberAccountName(name: String) {
        prefs.edit().putString(KEY_ACCOUNT_NAME, name).apply()
    }

    /** Clears the local authorized flag only — no server-side revoke. */
    override fun signOut() {
        prefs.edit().clear().apply()
    }

    private fun markAuthorized() {
        prefs.edit().putBoolean(YOUTUBE_AUTH_KEY_AUTHORIZED, true).apply()
    }

    private fun messageForException(e: Exception): String = if (e is ApiException) {
        "Google 인증에 실패했습니다 (코드 ${e.statusCode})"
    } else {
        "Google 인증에 실패했습니다"
    }
}

internal fun createYouTubeAuthGateway(context: Context): YouTubeAuthGateway =
    YouTubeAuthManager(context.applicationContext)
