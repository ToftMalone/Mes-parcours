@file:Suppress("DEPRECATION")

package com.example.util

import android.content.Context
import android.preference.PreferenceManager

/**
 * Settings of the automatic backup: is it active, and in which formats.
 *
 * There is no destination setting: files go to
 * `Downloads/Mes parcours`, and nothing else is offered. The
 * `isDestLocal` / `setDestLocal` pair that lingered here was a remnant of a backup
 * to Drive since removed — never read, it suggested a choice that does not
 * exist.
 */
object AutoBackupPreferences {
    private const val KEY_ENABLED = "pref_auto_backup_enabled"
    private const val KEY_FORMAT_GPX = "pref_auto_backup_format_gpx"
    private const val KEY_FORMAT_KML = "pref_auto_backup_format_kml"

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
}
