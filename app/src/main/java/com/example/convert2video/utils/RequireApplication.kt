package com.example.convert2video.utils

import android.app.Application
import android.content.Context

private const val TAG = "RequireApplication"

internal fun Context.requireApplication(): Application {
    val app = applicationContext
    if (app is Application) return app
    AppLogger.e(TAG, "applicationContext is not Application: ${app.javaClass.simpleName}")
    throw IllegalArgumentException("applicationContext is not Application")
}
