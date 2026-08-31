package com.example

import android.app.Application
import com.example.data.repository.TrackRepository
import com.example.util.OnboardingPreferences
import com.example.util.OsmConfig
import com.example.util.update.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrackApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OsmConfig.init(this)

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
            runCatching {
                TrackRepository.getInstance(this@TrackApplication)
                    .backfillDisplayColors(this@TrackApplication)
            }
        }
    }
}
