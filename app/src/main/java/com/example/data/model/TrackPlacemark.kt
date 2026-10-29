package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un tracé d'un fichier KML importé, tel que Google Earth le présentait : son nom, le
 * chemin de ses dossiers, sa couleur et son épaisseur (migration 11→12).
 *
 * Demandé par le père de l'auteur : un fichier « Mes traces » rangé en dossiers
 * (France, Étranger, À refaire…) et en tracés nommés (Belgique, Luxembourg…) ressortait
 * de l'export en une liste plate de « Mes traces », sans noms ni épaisseurs. Les points
 * d'un tracé portent l'identifiant de sa ligne (`TrackPoint.placemarkId`) ; l'export les
 * regroupe à nouveau sous ce nom, dans ces dossiers (`Exporter.KmlWriter`).
 *
 * Une ligne n'appartient à aucun parcours : découpage, fusion ou nettoyage recopient les
 * points avec leur identifiant, et plusieurs parcours peuvent donc y renvoyer.
 */
@Entity(tableName = "placemarks")
data class TrackPlacemark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String? = null,
    /** Les dossiers qui contiennent le tracé, du plus haut au plus profond, joints par [FOLDER_SEPARATOR]. */
    val folderPath: String? = null,
    val color: Int? = null,
    val width: Float? = null
) {
    val folders: List<String>
        get() = folderPath?.split(FOLDER_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    companion object {
        /** Séparateur d'unités ASCII : ne peut pas apparaître dans un nom de dossier saisi. */
        const val FOLDER_SEPARATOR = "\u001F"
    }
}
