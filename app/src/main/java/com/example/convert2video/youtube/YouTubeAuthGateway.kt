package com.example.convert2video.youtube

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Store-neutral result of a YouTube authorization operation. */
sealed interface AuthorizationOutcome {
    data class Authorized(val accessToken: String) : AuthorizationOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthorizationOutcome
    data class Failed(val message: String) : AuthorizationOutcome
}

/** GMS-free contract used by YouTube UI and worker callers. */
internal interface YouTubeAuthGateway {
    val isAuthorized: Boolean
    val cachedChannelTitle: String?
    val cachedAccountName: String?

    suspend fun requestAuthorization(account: Account? = null): AuthorizationOutcome

    fun resultFromIntent(intent: Intent): AuthorizationOutcome

    fun rememberChannelTitle(title: String)
    fun rememberAccountName(name: String)
    fun signOut()
}

internal const val YOUTUBE_AUTH_PREFS_NAME = "youtube_auth"
internal const val YOUTUBE_AUTH_KEY_AUTHORIZED = "authorized"

/**
 * Reads only the persisted YouTube authorized flag. This intentionally does not construct an
 * auth gateway, request a token, write preferences, use the network, or depend on GMS.
 */
internal fun isYoutubeAuthorizedFromPrefs(context: Context): Boolean =
    context.applicationContext
        .getSharedPreferences(YOUTUBE_AUTH_PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(YOUTUBE_AUTH_KEY_AUTHORIZED, false)

