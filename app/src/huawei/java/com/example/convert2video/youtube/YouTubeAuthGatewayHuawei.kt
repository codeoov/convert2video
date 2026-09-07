package com.example.convert2video.youtube

import android.accounts.Account
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val HUAWEI_AUTH_UNAVAILABLE_MESSAGE = "Google 인증을 진행할 수 없습니다"

private class HuaweiYouTubeAuthGateway(@Suppress("UNUSED_PARAMETER") appContext: Context?) : YouTubeAuthGateway {
    override val isAuthorized: Boolean = false
    override val cachedChannelTitle: String? = null
    override val cachedAccountName: String? = null

    override suspend fun requestAuthorization(account: Account?): AuthorizationOutcome {
        currentCoroutineContext().ensureActive()
        return AuthorizationOutcome.Failed(HUAWEI_AUTH_UNAVAILABLE_MESSAGE)
    }

    override fun resultFromIntent(intent: Intent): AuthorizationOutcome =
        AuthorizationOutcome.Failed(HUAWEI_AUTH_UNAVAILABLE_MESSAGE)

    override fun rememberChannelTitle(title: String) = Unit

    override fun rememberAccountName(name: String) = Unit

    override fun signOut() = Unit
}

internal fun createYouTubeAuthGateway(context: Context): YouTubeAuthGateway =
    HuaweiYouTubeAuthGateway(context.applicationContext)
