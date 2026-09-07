package com.example.convert2video.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val HUAWEI_AUTH_UNAVAILABLE_MESSAGE = "Google 인증을 진행할 수 없습니다"

private class HuaweiDriveAuthGateway(@Suppress("UNUSED_PARAMETER") appContext: Context?) : DriveAuthGateway {
    override val isAuthorized: Boolean = false
    override val cachedAccountEmail: String? = null
    override val cachedAppFolderId: String? = null

    override suspend fun requestAuthorization(account: Account?): DriveAuthorizationOutcome {
        currentCoroutineContext().ensureActive()
        return DriveAuthorizationOutcome.Failed(HUAWEI_AUTH_UNAVAILABLE_MESSAGE)
    }

    override fun resultFromIntent(intent: Intent): DriveAuthorizationOutcome =
        DriveAuthorizationOutcome.Failed(HUAWEI_AUTH_UNAVAILABLE_MESSAGE)

    override fun rememberAccountEmail(email: String) = Unit

    override fun rememberAppFolderId(id: String) = Unit

    override fun clearAppFolderId() = Unit

    override fun signOut() = Unit
}

internal fun createDriveAuthGateway(context: Context): DriveAuthGateway =
    HuaweiDriveAuthGateway(context.applicationContext)
