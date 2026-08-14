package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.Track
import com.example.data.model.TrackPoint

@Database(entities = [Track::class, TrackPoint::class], version = 6, exportSchema = false)
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
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
