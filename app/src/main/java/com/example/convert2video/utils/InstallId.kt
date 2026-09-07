package com.example.convert2video.utils

import android.content.Context
import java.util.UUID

private const val PREFS_NAME = "install_id"
private const val KEY = "install_id"
private val installIdLock = Any()

/**
 * Returns a stable, anonymous install identifier persisted in SharedPreferences.
 * Generated once via [UUID.randomUUID] and reused on every subsequent call.
 * Read-check-write is atomized under [installIdLock] to prevent duplicate generation on concurrent calls.
 * Does not log the full id — only length is acceptable if debugging is needed.
 */
fun Context.installId(): String {
    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    synchronized(installIdLock) {
        val existing = prefs.getString(KEY, null)
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).commit()
        return id
    }
}
