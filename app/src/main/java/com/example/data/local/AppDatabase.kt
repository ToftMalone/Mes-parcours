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
 * Schema version of the database.
 *
 * Declared separately rather than hard-coded in the annotation: `MigrationChainTest`
 * compares it with the migration chain, and an annotation cannot always be read back
 * at runtime. Incrementing it without adding the matching migration makes that
 * test fail — which is exactly what we ask of it.
 */
internal const val DATABASE_VERSION = 10

/**
 * `exportSchema = true`: Room writes the schema of each version to
 * `app/schemas/`, and these files are versioned along with the code.
 *
 * This is not documentation. It is what lets `AppDatabaseMigrationTest`
 * open a database of an old version, apply the declared migrations to it and
 * check that the result matches the expected schema. Without these files, no
 * test can do that, and invariant no. 2 of CLAUDE.md — “every new schema version
 * comes with its migration” — rests only on human vigilance, whereas forgetting it
 * erases every user's tracks without a word.
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
         * Adds the (trackId, latitude) index used by the viewport-based display.
         *
         * Explicit, non-destructive migration: the database is built with
         * fallbackToDestructiveMigration(), so without this migration the move to
         * version 5 would erase every recorded track.
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
         * Adds the `sourceColor` column, the track color read from the
         * imported file.
         *
         * Same requirement as the previous migration: without it,
         * fallbackToDestructiveMigration() would erase all of the user's tracks on the
         * first launch of the new version. The column is nullable and has no default
         * value — tracks already in the database have no original color, and that is
         * exactly what should be recorded.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `sourceColor` INTEGER")
            }
        }

        /**
         * Adds `segmentColor` to points: color at the track level was not enough, since a
         * single KML file can gather dozens of trips of different colors.
         *
         *
         * `ALTER TABLE … ADD COLUMN` on a nullable column without a default value
         * only touches the schema in SQLite: the table is not rewritten. That is
         * what makes the migration instantaneous even on a database of several million
         * points, where a column with a default value would rewrite them all.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `track_points` ADD COLUMN `segmentColor` INTEGER")
            }
        }

        /**
         * Adds `displayColor`: the color chosen by the user for a given track, color
         * having stopped belonging to the category to belong to the track itself.
         *
         *
         * Nullable and without a default value, like the previous ones: “no color
         * chosen” must remain distinct from “black chosen”, and a default value
         * would rewrite the whole table instead of just the schema.
         *
         * Tracks already in the database therefore leave this migration with `displayColor`
         * set to null. It is `TrackRepository.backfillDisplayColors` that then writes to them,
         * once, the color they displayed until now: that color came from
         * the preferences, which the SQL of a migration cannot read.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `displayColor` INTEGER")
            }
        }

        /**
         * Adds `displayWidthDp`: the line thickness, which became a per-track
         * choice like the color. Nullable and without a default value, for the same
         * reason as the previous ones: at null, the track keeps the general thickness
         * it displayed until now, with no backfill needed.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `displayWidthDp` REAL")
            }
        }

        /**
         * Adds `isPaused`: the pause of a recording, stored so it survives the
         * process being killed. A default value costs nothing here, the tracks table
         * has only a few hundred rows — unlike
         * `track_points`, where invariant 2 forbids it.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `isPaused` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * All declared migrations, in order.
         *
         * Exposed so that the migration test applies exactly the same list as
         * the app: copying it into the test would let a migration added here
         * and forgotten there slip through.
         */
        @androidx.annotation.VisibleForTesting
        val MIGRATIONS = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Re-check under the lock: without it, two simultaneous calls
                // each build a Room instance on the same file. Each
                // instance has its own connection pool and its own invalidation
                // tracking, so the Flows of one ignore the writes of
                // the other — the interface silently stops updating.
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "my_tracks_db"
                )
                    .addMigrations(*MIGRATIONS)
                    // Only on version downgrade: a database newer than
                    // the code can only happen by reinstalling an earlier version,
                    // and Room would not know how to read it. The general variant, on the other hand,
                    // also erased tracks at the slightest forgotten migration on
                    // an upgrade — the exact case we must
                    // protect against. Better a clear crash, which is seen and
                    // fixed, than a history silently erased.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
