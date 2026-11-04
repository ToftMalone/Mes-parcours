package com.example

import android.app.Application
import com.example.data.repository.TrackRepository
import com.example.util.DebugLog
import com.example.util.FormatUtils
import com.example.util.OnboardingPreferences
import com.example.util.OsmConfig
import com.example.util.update.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrackApplication : Application() {
    /** Gardé en champ : `SharedPreferences` ne retient ses écouteurs que faiblement. */
    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        // En tout premier : tout ce qui suit, plantages compris, doit pouvoir y être noté.
        DebugLog.init(this)
        installDebugObservers()
        OsmConfig.init(this)
        // Les formats (durées, noms de parcours) ont besoin des ressources hors composition.
        FormatUtils.init(this)

        // Programme la vérification quotidienne de mise à jour — voir
        // UpdateCheckWorker pour le choix d'une tâche de fond plutôt que d'une
        // surveillance continue. Sans effet si elle est déjà programmée.
        UpdateCheckWorker.schedule(this)

        // Marque l'écran d'accueil comme déjà vu sur une installation déjà en usage
        // avant son introduction — voir OnboardingPreferences.backfillIfAlreadyUsed.
        // Avant tout accès à la base : l'ouvrir la créerait, et fausserait le résultat.
        OnboardingPreferences.backfillIfAlreadyUsed(
            context = this,
            hasExistingDatabase = getDatabasePath("my_tracks_db").exists()
        )

        // Reprise des couleurs, une seule fois, au premier lancement après le passage
        // aux couleurs par parcours : chaque parcours déjà en base reçoit explicitement
        // celle qu'il affichait jusqu'ici. Sans cela, un utilisateur ayant réglé ses
        // couleurs par catégorie verrait tout son historique changer d'apparence sans
        // avoir rien demandé.
        //
        // Sur un fil d'arrière-plan : c'est une écriture en base, et la migration Room
        // se déclenche à cette première ouverture. Un échec ne doit pas empêcher
        // l'application de démarrer — au pire les couleurs se recalculent par défaut.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Un import tué avec l'application laisse sa ligne réservée en base, cachée
            // de l'historique : on la retire ici, aucun import ne pouvant être en cours
            // au démarrage du processus (voir TrackRepository.importTrackFromUri).
            runCatching {
                TrackRepository.getInstance(this@TrackApplication).cleanupInterruptedImports()
            }
            runCatching {
                TrackRepository.getInstance(this@TrackApplication)
                    .backfillDisplayColors(this@TrackApplication)
            }
            // La corbeille se vide d'elle-même : ce qui y a passé trente jours est
            // effacé au démarrage (voir Track.deletedAt).
            runCatching {
                TrackRepository.getInstance(this@TrackApplication).purgeTrash()
            }
            // Puis la borne de place, et la place libérée rendue au téléphone même quand
            // rien n'a expiré : une base gonflée par une version d'avant la borne (ou un
            // effacement fait téléphone plein) se rétrécit ainsi au lancement suivant.
            runCatching {
                val repository = TrackRepository.getInstance(this@TrackApplication)
                repository.enforceTrashLimit()
                repository.compactDatabase()
            }
        }
    }

    /**
     * Ce que le journal relève de lui-même, sans qu'aucun écran ait à y penser : le cycle
     * de vie de chaque écran, les alertes mémoire d'Android, chaque réglage modifié, et un
     * état des lieux toutes les cinq minutes (mémoire, place libre).
     */
    private fun installDebugObservers() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun log(activity: android.app.Activity, event: String) =
                DebugLog.i("Cycle", "${activity.javaClass.simpleName} : $event")
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) =
                log(a, if (b == null) "créée" else "recréée (état restauré)")
            override fun onActivityStarted(a: android.app.Activity) = log(a, "visible")
            override fun onActivityResumed(a: android.app.Activity) = log(a, "au premier plan")
            override fun onActivityPaused(a: android.app.Activity) = log(a, "en pause")
            override fun onActivityStopped(a: android.app.Activity) = log(a, "cachée")
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = log(a, "état sauvegardé")
            override fun onActivityDestroyed(a: android.app.Activity) =
                log(a, if (a.isChangingConfigurations) "détruite (rotation ou changement de thème)" else "détruite")
        })

        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            DebugLog.d("Réglage", "$key = ${key?.let { p.all[it] }}")
        }.also(prefs::registerOnSharedPreferenceChangeListener)

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (true) {
                DebugLog.i("État", DebugLog.environment(this@TrackApplication))
                kotlinx.coroutines.delay(5 * 60 * 1000L)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugLog.w("Mémoire", "Android demande de libérer de la mémoire (niveau $level) — ${DebugLog.environment(this)}")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DebugLog.w("Mémoire", "Mémoire presque épuisée — ${DebugLog.environment(this)}")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        DebugLog.i("Cycle", "Configuration changée : orientation ${newConfig.orientation}, mode nuit ${newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}")
    }
}
