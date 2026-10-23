package com.example.data.model

/**
 * Zone géographique actuellement visible sur la carte, déjà élargie d'une marge
 * par l'appelant pour que de petits déplacements ne vident pas le tracé.
 */
data class MapViewport(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val zoom: Double
) {
    val latSpan: Double get() = maxLat - minLat
    val lonSpan: Double get() = maxLon - minLon
}
