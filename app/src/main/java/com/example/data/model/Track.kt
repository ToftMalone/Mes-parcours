package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val activityType: String = "Randonnée", // Hiking, Cycling, Running, etc.
    val startTime: Long,
    val endTime: Long = 0,
    val totalDistance: Double = 0.0, // in meters
    val duration: Long = 0, // in seconds
    val maxSpeed: Double = 0.0, // in m/s
    val avgSpeed: Double = 0.0, // in m/s
    val elevationGain: Double = 0.0, // in meters
    val elevationLoss: Double = 0.0, // in meters
    val isRecording: Boolean = false,
    val isImported: Boolean = false,
    val isSelectedForMap: Boolean = false,
    val isMerged: Boolean = false,
    /**
     * Couleur de tracé lue dans le fichier importé (ARGB), ou null si le fichier n'en
     * portait pas — cas de tous les GPX, et des KML sans style de ligne.
     *
     * Nullable à dessein : il faut pouvoir distinguer « le fichier ne disait rien »
     * de « le fichier demandait du noir ». Sans cette distinction, un parcours sans
     * couleur se dessinerait en noir au lieu de reprendre celle de la palette.
     */
    val sourceColor: Int? = null,
    /**
     * Couleur choisie par l'utilisateur pour **ce parcours précis** (ARGB), ou null
     * pour « automatique » : la couleur du fichier d'origine si le fichier en portait
     * une, sinon la couleur par défaut de la catégorie.
     *
     * Nullable pour la même raison que `sourceColor` : « je n'ai rien choisi » et
     * « j'ai choisi le noir » doivent rester distincts. Sans cette distinction, un
     * parcours jamais colorié à la main perdrait la couleur de son fichier.
     *
     * Remplace les trois couleurs par catégorie qui existaient jusqu'ici en
     * préférences : la couleur appartient désormais au parcours, pas à l'onglet.
     */
    val displayColor: Int? = null,
    /**
     * Épaisseur du trait choisie pour **ce parcours précis**, en dp, ou null tant que
     * personne n'en a choisi : il garde alors l'épaisseur générale d'avant (voir
     * `TrackStylePreferences.resolveThicknessDp`). Se règle avec la couleur, dans la
     * même feuille ; le réglage général a quitté les Paramètres.
     */
    val displayWidthDp: Float? = null,
    /**
     * Enregistrement en pause, **mémorisé en base** (migration 9→10).
     *
     * La pause ne vivait que dans la mémoire du service : si Android tuait
     * l'application pendant une pause, puis la relançait — ou après un redémarrage du
     * téléphone — l'enregistrement reprenait tout seul, et le trajet suivant
     * s'enregistrait alors que l'utilisateur l'avait mis en pause. N'a de sens que
     * tant que [isRecording] est vrai.
     */
    @ColumnInfo(defaultValue = "0")
    val isPaused: Boolean = false
) {
    companion object {
        /**
         * `startTime` de la ligne réservée pendant un import, avant que le fichier
         * n'en donne un. L'import n'est plus une seule transaction (voir
         * `TrackRepository.importTrackFromUri`) : cette ligne existe donc en base le
         * temps de l'import, et c'est ce repère qui la cache de l'historique et
         * permet de la retrouver si l'application est tuée en cours de route.
         */
        const val IMPORT_PLACEHOLDER_START_TIME = -1L
    }
}
