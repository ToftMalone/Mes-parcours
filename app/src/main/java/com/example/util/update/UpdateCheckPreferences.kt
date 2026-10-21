package com.example.util.update

import android.content.Context
import android.preference.PreferenceManager

/**
 * Prevents reporting the same version, already notified once, every day.
 *
 * Without it, `UpdateCheckWorker` would notify the same ignored update again at each
 * daily pass, as long as it remains the most recent published.
 */
internal object UpdateCheckPreferences {

    private const val KEY_LAST_NOTIFIED_VERSION_CODE = "pref_update_last_notified_version_code"

    fun wasAlreadyNotified(context: Context, versionCode: Int): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(KEY_LAST_NOTIFIED_VERSION_CODE, -1) == versionCode

    fun markNotified(context: Context, versionCode: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(KEY_LAST_NOTIFIED_VERSION_CODE, versionCode)
            .apply()
    }
}
