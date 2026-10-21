package com.example.data.model

/**
 * État du signal GPS tel que le suit [com.example.data.repository.TrackRepository].
 *
 * C'était une chaîne française comparée au caractère près (« Signal trouvé »…) à six
 * endroits : traduire l'interface aurait cassé la logique en silence. Le libellé
 * affiché se choisit à l'écran ; ici, on ne tient que l'état.
 */
enum class GpsStatus {
    /** Aucune position reçue récemment : recherche, ou signal perdu. */
    SEARCHING,
    FOUND,
    /** Aucun fournisseur de position actif sur l'appareil. */
    DISABLED,
    /** Positions inventées par la simulation (debug uniquement). */
    SIMULATION
}
