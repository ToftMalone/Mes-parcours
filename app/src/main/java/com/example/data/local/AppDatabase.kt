package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.Track
import com.example.data.model.TrackPoint

/**
 * Version du schéma de la base.
 *
 * Déclarée à part plutôt qu'écrite en dur dans l'annotation : `MigrationChainTest` la
 * compare à la chaîne de migrations, et une annotation ne se relit pas toujours à
 * l'exécution. L'incrémenter sans ajouter la migration correspondante fait échouer ce
 * test — c'est précisément ce qu'on lui demande.
 */
internal const val DATABASE_VERSION = 8

/**
 * `exportSchema = true` : Room écrit le schéma de chaque version dans
 * `app/schemas/`, ces fichiers étant versionnés avec le code.
 *
 * Ce n'est pas de la documentation. C'est ce qui permet à `AppDatabaseMigrationTest`
 * d'ouvrir une base d'ancienne version, d'y appliquer les migrations déclarées et de
 * vérifier que le résultat correspond au schéma attendu. Sans ces fichiers, aucun
 * test ne peut le faire, et l'invariant nº 2 de CLAUDE.md — « toute nouvelle version
 * de schéma vient avec sa migration » — ne repose que sur la vigilance humaine, alors
 * que l'oublier efface tous les parcours des utilisateurs sans un mot.
 */
@Database(
    entities = [Track::class, TrackPoint::class],
    version = DATABASE_VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract val trackDao: TrackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Ajoute l'index (trackId, latitude) utilisé par l'affichage par fenêtre de vue.
         *
         * Migration explicite et non destructive : la base est construite avec
         * fallbackToDestructiveMigration(), donc sans cette migration le passage en
         * version 5 effacerait tous les parcours enregistrés.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_track_points_trackId_latitude` " +
                            "ON `track_points` (`trackId`, `latitude`)"
                )
            }
        }

        /**
         * Ajoute la colonne `sourceColor`, la couleur de tracé lue dans le fichier
         * importé.
         *
         * Même exigence que la migration précédente : sans elle,
         * fallbackToDestructiveMigration() effacerait tous les parcours de
         * l'utilisateur au premier lancement de la nouvelle version. La colonne est
         * nullable et sans valeur par défaut — les parcours déjà en base n'ont pas
         * de couleur d'origine, et c'est bien ce qu'il faut enregistrer.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `sourceColor` INTEGER")
            }
        }

        /**
         * Ajoute `segmentColor` sur les points : la couleur ne suffisait pas au niveau
         * du parcours, un seul fichier KML pouvant réunir des dizaines de trajets de
         * couleurs différentes.
         *
         * `ALTER TABLE … ADD COLUMN` sur une colonne nullable et sans valeur par défaut
         * ne touche que le schéma dans SQLite : la table n'est pas réécrite. C'est ce
         * qui rend la migration instantanée même sur une base de plusieurs millions de
         * points, là où une colonne avec valeur par défaut les réécrirait toutes.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `track_points` ADD COLUMN `segmentColor` INTEGER")
            }
        }

        /**
         * Ajoute `displayColor` : la couleur choisie par l'utilisateur pour un parcours
         * donné, la couleur ayant cessé d'appartenir à la catégorie pour appartenir au
         * parcours lui-même.
         *
         * Nullable et sans valeur par défaut, comme les précédentes : « aucune couleur
         * choisie » doit rester distinct de « noir choisi », et une valeur par défaut
         * ferait réécrire toute la table au lieu du seul schéma.
         *
         * Les parcours déjà en base sortent donc de cette migration avec `displayColor`
         * à null. C'est `TrackRepository.backfillDisplayColors` qui leur écrit ensuite,
         * une seule fois, la couleur qu'ils affichaient jusqu'ici : cette couleur venait
         * des préférences, que le SQL d'une migration ne sait pas lire.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `displayColor` INTEGER")
            }
        }

        /**
         * Toutes les migrations déclarées, dans l'ordre.
         *
         * Exposée pour que le test de migration applique exactement la même liste que
         * l'application : la recopier dans le test laisserait passer une migration
         * ajoutée ici et oubliée là.
         */
        @androidx.annotation.VisibleForTesting
        val MIGRATIONS = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Re-vérification sous le verrou : sans elle, deux appels simultanés
                // construisent chacun une instance Room sur le même fichier. Chaque
                // instance a son propre pool de connexions et son propre suivi
                // d'invalidation, donc les Flow de l'une ignorent les écritures de
                // l'autre — l'interface cesse silencieusement de se mettre à jour.
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "my_tracks_db"
                )
                    .addMigrations(*MIGRATIONS)
                    // Uniquement à la descente de version : une base plus récente que
                    // le code ne peut arriver qu'en réinstallant une version antérieure,
                    // et Room ne saurait pas la relire. La variante générale, elle,
                    // effaçait aussi les parcours à la moindre migration oubliée en
                    // montée de version — le cas exact contre lequel il faut se
                    // protéger. Mieux vaut alors un plantage franc, qui se voit et se
                    // corrige, qu'un historique effacé en silence.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
