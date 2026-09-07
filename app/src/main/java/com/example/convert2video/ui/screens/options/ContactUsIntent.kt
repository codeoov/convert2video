package com.example.convert2video.ui.screens.options

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.convert2video.BuildConfig
import com.example.convert2video.R

// TODO: Play 출시 전 이 플레이스홀더를 실제 CS 이메일 주소로 교체하세요. (단일 수정 지점)
internal const val SUPPORT_EMAIL = "support@convert2video.example.com"

internal fun buildContactUsIntent(context: Context): Intent {
    val appName = context.getString(R.string.app_name)
    val subject = context.getString(
        R.string.options_contact_us_subject,
        appName,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
    )
    val body = context.getString(
        R.string.options_contact_us_body,
        appName,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
        Build.VERSION.RELEASE,
        Build.VERSION.SDK_INT,
        Build.MANUFACTURER,
        Build.MODEL,
    )
    // RFC 6068: mailto body는 CRLF 줄바꿈이 필요하므로 LF → CRLF 변환한다.
    // crlfBody를 URI 쿼리 파라미터와 EXTRA_TEXT 양쪽에 동일하게 사용한다.
    val crlfBody = body.replace("\n", "\r\n")
    // appendQueryParameter가 내부적으로 퍼센트 인코딩을 처리하므로 사전 Uri.encode 불필요.
    val mailtoUri = Uri.fromParts("mailto", SUPPORT_EMAIL, null)
        .buildUpon()
        .appendQueryParameter("subject", subject)
        .appendQueryParameter("body", crlfBody)
        .build()
    return Intent(Intent.ACTION_SENDTO).apply {
        // URI query params are the source of truth: Gmail and many clients ignore EXTRA_*.
        data = mailtoUri
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, crlfBody)
    }
}
