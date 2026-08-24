package com.example.data.model

/**
 * Un tracé superposé sur la carte, accompagné de quoi choisir sa couleur.
 *
 * Remplace la paire `(estImporté, points)` employée jusqu'ici : la couleur ne se
 * déduit plus de la seule catégorie du parcours, puisqu'un parcours importé peut
 * désormais porter celle de son fichier d'origine.
 */
data class MapTrack(
    val isImported: Boolean,
    /** Un parcours fusionné forme sa propre catégorie, avec sa propre couleur. */
    val isMerged: Boolean,
    /** Couleur lue dans le fichier importé, null s'il n'en portait pas. */
    val sourceColor: Int?,
    /** Couleur choisie par l'utilisateur pour ce parcours, null s'il n'a rien choisi. */
    val displayColor: Int? = null,
    val points: List<TrackPoint>
)
