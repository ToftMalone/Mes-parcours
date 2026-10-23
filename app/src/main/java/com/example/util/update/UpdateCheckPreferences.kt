package com.example.util.update

import android.content.Context
import android.preference.PreferenceManager

/**
 * Empêche de re-signaler chaque jour la même version déjà notifiée une fois.
 *
 * Sans elle, `UpdateCheckWorker` renotifierait la même mise à jour ignorée à chaque
 * passage quotidien, tant qu'elle reste la plus récente publiée.
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
