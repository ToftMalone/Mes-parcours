@file:Suppress("DEPRECATION")

package com.example.util

import android.content.Context
import android.preference.PreferenceManager

/**
 * Réglages de la sauvegarde automatique : est-elle active, et dans quels formats.
 *
 * Il n'y a pas de réglage de destination : les fichiers vont dans
 * `Téléchargements/Mes parcours`, et rien d'autre n'est proposé. Le couple
 * `isDestLocal` / `setDestLocal` qui traînait ici est un vestige d'une sauvegarde
 * vers Drive retirée depuis — jamais lu, il laissait croire à un choix qui n'existe
 * pas.
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
