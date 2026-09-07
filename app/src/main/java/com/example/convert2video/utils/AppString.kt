package com.example.convert2video.utils

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel

internal fun Context.appString(@StringRes resId: Int, vararg formatArgs: Any): String =
    if (formatArgs.isEmpty()) getString(resId) else getString(resId, *formatArgs)

internal fun AndroidViewModel.appString(@StringRes resId: Int, vararg formatArgs: Any): String =
    getApplication<Application>().appString(resId, *formatArgs)
