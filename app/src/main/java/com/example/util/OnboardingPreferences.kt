package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.preference.PreferenceManager
import androidx.core.content.ContextCompat

/**
 * The welcome screen must only appear once, on the very first launch.
 */
object OnboardingPreferences {

    private const val KEY_COMPLETED = "pref_onboarding_completed"

    fun isCompleted(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_COMPLETED, false)

    fun setCompleted(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

    /**
     * Marks the welcome as already seen on an installation that was in use before
     * this screen was introduced.
     *
     * Without this backfill, the screen would appear once too often to a user who
     * has long since answered the permission requests and recorded tracks —
     * same logic as `TrackRepository.backfillDisplayColors` for
     * per-track colors.
     *
     * Two clues retained, for lack of better: the location permission already
     * granted (it can only be so after a first pass through the system
     * dialog, hence through an earlier version of the app), or
     * the database file already existing — a sign that a track was written to it.
     *
     * [hasExistingDatabase] must be checked **before** any database access: opening
     * the database creates it if it did not exist yet, which would skew the result.
     */
    fun backfillIfAlreadyUsed(context: Context, hasExistingDatabase: Boolean) {
        if (isCompleted(context)) return
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocationPermission || hasExistingDatabase) {
            setCompleted(context)
        }
    }
}
