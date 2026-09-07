package com.example.convert2video.drive

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

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val PREFS_NAME = "drive_auth"
private const val KEY_AUTHORIZED = "authorized"
private const val KEY_ACCOUNT_EMAIL = "account_email"
private const val KEY_APP_FOLDER_ID = "app_folder_id"
private const val TAG = "GoogleDriveAuthManager"

internal data class DriveAuthorizationResponse(
    val hasResolution: Boolean,
    val pendingIntent: PendingIntent?,
    val accessToken: String?,
)

internal interface DriveAuthorizationClient {
    fun authorize(
        requestedScopes: List<String>,
        account: Account?,
        onSuccess: (DriveAuthorizationResponse) -> Unit,
        onFailure: (Exception) -> Unit,
    )

    fun getAuthorizationResultFromIntent(intent: Intent): DriveAuthorizationResponse
}

private class GoogleDriveAuthorizationClient(context: Context) : DriveAuthorizationClient {
    private val client = Identity.getAuthorizationClient(context)

    override fun authorize(
        requestedScopes: List<String>,
        account: Account?,
        onSuccess: (DriveAuthorizationResponse) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val requestBuilder = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes.map(::Scope))
            .setOptOutIncludingGrantedScopes(true)
        if (account != null) requestBuilder.setAccount(account)
        client.authorize(requestBuilder.build())
            .addOnSuccessListener { result ->
                onSuccess(
                    DriveAuthorizationResponse(
                        hasResolution = result.hasResolution(),
                        pendingIntent = result.pendingIntent,
                        accessToken = result.accessToken,
                    ),
                )
            }
            .addOnFailureListener(onFailure)
    }

    override fun getAuthorizationResultFromIntent(intent: Intent): DriveAuthorizationResponse {
        val result = client.getAuthorizationResultFromIntent(intent)
        return DriveAuthorizationResponse(
            hasResolution = result.hasResolution(),
            pendingIntent = result.pendingIntent,
            accessToken = result.accessToken,
        )
    }
}

/** Google Play Services implementation of the store-neutral Drive auth contract. */
internal class GoogleDriveAuthManager(
    context: Context,
    authorizationClientOverride: DriveAuthorizationClient? = null,
) : DriveAuthGateway {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val authorizationClient = authorizationClientOverride
        ?: GoogleDriveAuthorizationClient(appContext)

    override val isAuthorized: Boolean
        get() = prefs.getBoolean(KEY_AUTHORIZED, false)

    override val cachedAccountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)

    /** Cached Drive folder id for [DriveAutoUploadWorker]'s app folder, or null if unset. */
    override val cachedAppFolderId: String?
        get() = prefs.getString(KEY_APP_FOLDER_ID, null)

    override fun rememberAppFolderId(id: String) {
        if (id.isBlank()) return
        prefs.edit().putString(KEY_APP_FOLDER_ID, id).apply()
    }

    override fun clearAppFolderId() {
        prefs.edit().remove(KEY_APP_FOLDER_ID).apply()
    }

    /**
     * Requests the drive.file scope with AM-1/AM-2/AM-3 account binding.
     *
     * - AM-1: [account] != null → bind via `setAccount(account)`
     * - AM-2: [account] == null + stored email non-blank → `Account(email, "com.google")` + `setAccount`
     * - AM-3: [account] == null + no stored email → omit `setAccount` (Play Services default)
     */
    override suspend fun requestAuthorization(account: Account?): DriveAuthorizationOutcome =
        suspendCancellableCoroutine { cont ->
            val storedEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null)
            val effectiveAccount: Account? = when {
                account != null -> account
                storedEmail != null && storedEmail.isNotBlank() -> Account(storedEmail, "com.google")
                else -> null
            }
            authorizationClient.authorize(
                requestedScopes = listOf(DRIVE_FILE_SCOPE),
                account = effectiveAccount,
                onSuccess = { result ->
                    val outcome = if (result.hasResolution) {
                        val pendingIntent = result.pendingIntent
                        if (pendingIntent != null) {
                            DriveAuthorizationOutcome.NeedsConsent(pendingIntent)
                        } else {
                            DriveAuthorizationOutcome.Failed("Google 인증을 진행할 수 없습니다")
                        }
                    } else {
                        val token = result.accessToken
                        if (token != null) {
                            markAuthorized()
                            DriveAuthorizationOutcome.Authorized(token)
                        } else {
                            DriveAuthorizationOutcome.Failed(USER_AUTH_FAILED_MESSAGE)
                        }
                    }
                    if (cont.isActive) cont.resumeWith(Result.success(outcome))
                },
                onFailure = { e ->
                    logAuthFailure("authorize()", e)
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(DriveAuthorizationOutcome.Failed(USER_AUTH_FAILED_MESSAGE)))
                    }
                },
            )
            cont.invokeOnCancellation {
                // Play Services Task cannot be cancelled — intentionally fire-and-forget.
            }
        }

    /** Parses the consent result [Intent] handed back by the Activity Result launcher. */
    override fun resultFromIntent(intent: Intent): DriveAuthorizationOutcome = try {
        val token = authorizationClient.getAuthorizationResultFromIntent(intent).accessToken
        if (token != null) {
            markAuthorized()
            DriveAuthorizationOutcome.Authorized(token)
        } else {
            DriveAuthorizationOutcome.Failed(USER_AUTH_FAILED_MESSAGE)
        }
    } catch (e: ApiException) {
        logAuthFailure("getAuthorizationResultFromIntent", e)
        DriveAuthorizationOutcome.Failed(USER_AUTH_FAILED_MESSAGE)
    }

    override fun rememberAccountEmail(email: String) {
        prefs.edit().putString(KEY_ACCOUNT_EMAIL, email).apply()
    }

    /** Clears the local authorized flag only — no server-side revoke. */
    override fun signOut() {
        prefs.edit().clear().apply()
    }

    private fun markAuthorized() {
        prefs.edit().putBoolean(KEY_AUTHORIZED, true).apply()
    }

    /** Logs statusCode / type for diagnostics — never puts codes into the outcome message. */
    private fun logAuthFailure(where: String, e: Exception) {
        if (e is ApiException) {
            AppLogger.e(TAG, "$where failed: ${e.javaClass.simpleName}, status=${e.statusCode}")
        } else {
            AppLogger.e(TAG, "$where failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val USER_AUTH_FAILED_MESSAGE = "Google 인증에 실패했습니다"
    }
}

internal fun createDriveAuthGateway(context: Context): DriveAuthGateway =
    GoogleDriveAuthManager(context.applicationContext)
