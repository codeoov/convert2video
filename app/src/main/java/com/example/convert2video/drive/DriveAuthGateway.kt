package com.example.convert2video.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Intent

/** Store-neutral result of a Drive authorization operation. */
sealed interface DriveAuthorizationOutcome {
    data class Authorized(val accessToken: String) : DriveAuthorizationOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuthorizationOutcome
    data class Failed(val message: String) : DriveAuthorizationOutcome
}

/** GMS-free contract used by Drive UI and worker callers. */
internal interface DriveAuthGateway {
    val isAuthorized: Boolean
    val cachedAccountEmail: String?
    val cachedAppFolderId: String?

    suspend fun requestAuthorization(account: Account? = null): DriveAuthorizationOutcome

    fun resultFromIntent(intent: Intent): DriveAuthorizationOutcome

    fun rememberAccountEmail(email: String)
    fun rememberAppFolderId(id: String)
    fun clearAppFolderId()
    fun signOut()
}

