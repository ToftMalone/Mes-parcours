@file:Suppress("DEPRECATION")

package com.example.util

import android.content.Context
import android.preference.PreferenceManager

object AutoBackupPreferences {
    private const val KEY_ENABLED = "pref_auto_backup_enabled"
    private const val KEY_FORMAT_GPX = "pref_auto_backup_format_gpx"
    private const val KEY_FORMAT_KML = "pref_auto_backup_format_kml"
    private const val KEY_DEST_LOCAL = "pref_auto_backup_dest_local"

    fun isAutoBackupEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setAutoBackupEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isFormatGpx(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_FORMAT_GPX, true)
    }

    fun setFormatGpx(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_FORMAT_GPX, enabled)
            .apply()
    }

    fun isFormatKml(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_FORMAT_KML, false)
    }

    fun setFormatKml(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_FORMAT_KML, enabled)
            .apply()
    }

    fun isDestLocal(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_DEST_LOCAL, true)
    }

    fun setDestLocal(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_DEST_LOCAL, enabled)
            .apply()
    }
}
