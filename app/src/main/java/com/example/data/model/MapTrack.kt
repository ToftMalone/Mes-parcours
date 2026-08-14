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
    /** Couleur lue dans le fichier importé, null s'il n'en portait pas. */
    val sourceColor: Int?,
    val points: List<TrackPoint>
)
