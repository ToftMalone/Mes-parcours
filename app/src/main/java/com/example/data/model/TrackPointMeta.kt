package com.example.data.model

/**
 * Effectif et emprise géographique des points d'une trace.
 *
 * Sert à calibrer l'affichage par fenêtre de vue sans jamais charger la trace
 * entière. Les champs sont nullables car les agrégats SQL valent NULL pour une
 * trace sans aucun point.
 */
data class TrackPointMeta(
    val minId: Long?,
    val pointCount: Int,
    val minLat: Double?,
    val maxLat: Double?,
    val minLon: Double?,
    val maxLon: Double?
)
