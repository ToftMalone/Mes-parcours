package com.example

import com.example.data.local.AppDatabase
import com.example.data.local.DATABASE_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde-fou sur l'invariant nº 2 de CLAUDE.md : toute version de schéma vient avec sa
 * migration.
 *
 * Ce n'est pas une précaution théorique. Une migration manquante fait échouer
 * l'ouverture de la base sur l'appareil d'un utilisateur qui met à jour — et avant
 * `fallbackToDestructiveMigrationOnDowngrade`, elle effaçait tous ses parcours sans
 * un mot. Le défaut ne se voit jamais en développement, où l'on installe le plus
 * souvent par-dessus une base déjà à la bonne version, et ne se manifeste que chez
 * ceux qui viennent d'une version antérieure.
 *
 * Le test relit la chaîne réellement déclarée, sans la recopier : recopier laisserait
 * passer exactement l'oubli que l'on cherche à empêcher.
 */
class MigrationChainTest {

    private val declaredVersion: Int = DATABASE_VERSION

    /** Version la plus ancienne encore rattrapable, celle d'où part la première migration. */
    private val oldestSupported: Int =
        AppDatabase.MIGRATIONS.minOf { it.startVersion }

    @Test
    fun `les migrations forment une chaine continue jusqu a la version declaree`() {
        val steps = AppDatabase.MIGRATIONS
            .associateBy { it.startVersion }

        var version = oldestSupported
        while (version < declaredVersion) {
            val step = steps[version]
            assertTrue(
                "Aucune migration ne part de la version $version : une base en $version " +
                        "ne peut pas atteindre la version $declaredVersion. Ajoutez " +
                        "MIGRATION_${version}_${version + 1} dans AppDatabase.",
                step != null
            )
            assertEquals(
                "La migration partant de $version doit mener à ${version + 1} : " +
                        "un saut de version laisse un trou dans la chaîne.",
                version + 1,
                step!!.endVersion
            )
            version = step.endVersion
        }

        assertEquals(
            "La dernière migration doit mener à la version déclarée par @Database.",
            declaredVersion,
            version
        )
    }

    @Test
    fun `aucune version de depart n est declaree deux fois`() {
        val starts = AppDatabase.MIGRATIONS.map { it.startVersion }
        assertEquals(
            "Deux migrations partent de la même version : Room ne saurait pas laquelle " +
                    "appliquer.",
            starts.size,
            starts.distinct().size
        )
    }

    @Test
    fun `aucune migration ne depasse la version declaree`() {
        val tooFar = AppDatabase.MIGRATIONS.filter { it.endVersion > declaredVersion }
        assertTrue(
            "Migration(s) menant au-delà de la version déclarée ($declaredVersion) : " +
                    "$tooFar. La version de @Database a-t-elle été oubliée ?",
            tooFar.isEmpty()
        )
    }
}
