package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.preference.PreferenceManager
import androidx.core.content.ContextCompat

/**
 * L'écran d'accueil ne doit apparaître qu'une fois, au tout premier lancement.
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
     * Marque l'accueil comme déjà vu sur une installation déjà en usage avant
     * l'introduction de cet écran.
     *
     * Sans cette reprise, l'écran apparaîtrait une fois de trop à un utilisateur qui
     * a déjà répondu aux demandes d'autorisation et enregistré des parcours depuis
     * longtemps — même logique que `TrackRepository.backfillDisplayColors` pour les
     * couleurs par parcours.
     *
     * Deux indices retenus, faute d'un mieux : la permission de localisation déjà
     * accordée (elle ne peut l'être qu'après un premier passage par la boîte de
     * dialogue système, donc par une version antérieure de l'application), ou
     * l'existence déjà du fichier de base — signe qu'un parcours y a été écrit.
     *
     * [hasExistingDatabase] doit être vérifié **avant** tout accès à la base : ouvrir
     * la base la crée si elle n'existait pas encore, ce qui fausserait le résultat.
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
